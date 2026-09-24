package com.ownclaw.agent;

import com.ownclaw.agent.memory.AgentMemory;
import com.ownclaw.agent.tools.*;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.conversation.ConversationService;
import com.ownclaw.conversation.FileStorageService;
import com.ownclaw.core.LongRunningTaskManager;
import com.ownclaw.core.ScheduledTaskService;
import com.ownclaw.core.TaskCancellationService;
import com.ownclaw.core.TokenBudgetTracker;
import com.ownclaw.llm.ModelPricing;
import com.ownclaw.llm.LlmProvider;
import com.ownclaw.observability.ChatStatusEmitter;
import com.ownclaw.observability.ChatStatusEmitter.StatusMessage;
import com.ownclaw.observability.DebugSessionService;
import com.ownclaw.observability.EventLogService;
import com.ownclaw.sandbox.SandboxManager;
import com.ownclaw.users.CredentialVault;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.ownclaw.llm.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * The AgentLoop is the central execution engine.
 *
 * It implements the reactive Think → Critique → Act → Observe loop:
 *   1. ThinkingEngine decides the next action (LLM call)
 *   2. CriticAgent validates the action (fast, rule-based)
 *   3. Tool is executed (sandbox, HTTP, etc.)
 *   4. Observation is recorded in the trajectory
 *   5. Repeat until the agent responds, times out, or hits a limit
 *
 * The loop is the central execution engine that replaced the old plan-first approach.
 */
@Component
public class AgentLoop {

    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);

    private final ThinkingEngine thinkingEngine;
    private final CriticAgent criticAgent;
    private final ToolRegistry toolRegistry;
    private final ChatStatusEmitter statusEmitter;
    private final OwnClawConfig config;
    private final LlmRouter llmRouter;
    private final AgentMemory memory;
    private final SkillCuratorService curatorService;
    private final SkillManager skillManager;
    private final DebugSessionService debugService;
    private final TaskCancellationService cancellationService;
    private final CredentialVault credentialVault;
    private final ConversationService conversationService;
    private final LongRunningTaskManager longRunningTaskManager;
    private final CapabilityResolver capabilityResolver;
    private final TokenBudgetTracker budgetTracker;
    /** For serialising step details into events.details. Jackson's mapper is thread-safe once built. */
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * Above this many characters, an unattended tool result is worth a local summary.
     * Below it, a 60-133 second call would be spent shortening something already short.
     */
    private static final int LOCAL_COMPRESSION_THRESHOLD = 8_000;

    /** What the summary should aim for — comfortably inside what the cloud sees at full detail. */
    private static final int LOCAL_COMPRESSION_TARGET = 4_000;

    private final EventLogService eventLog;
    private final ScheduledTaskService scheduledTaskService;
    private final LocalExecutor localExecutor;
    private final FileStorageService fileStorage;

    /** Max recent messages to include as conversation context for the LLM. */
    private static final int CONVERSATION_CONTEXT_MESSAGES = 20;

    public AgentLoop(
            ThinkingEngine thinkingEngine,
            CriticAgent criticAgent,
            ToolRegistry toolRegistry,
            ChatStatusEmitter statusEmitter,
            OwnClawConfig config,
            LlmRouter llmRouter,
            AgentMemory memory,
            SkillCuratorService curatorService,
            SkillManager skillManager,
            DebugSessionService debugService,
            TaskCancellationService cancellationService,
            CredentialVault credentialVault,
            ConversationService conversationService,
            LongRunningTaskManager longRunningTaskManager,
            CapabilityResolver capabilityResolver,
            TokenBudgetTracker budgetTracker,
            EventLogService eventLog,
            @Lazy ScheduledTaskService scheduledTaskService,
            LocalExecutor localExecutor,
            FileStorageService fileStorage
    ) {
        this.thinkingEngine = thinkingEngine;
        this.criticAgent = criticAgent;
        this.toolRegistry = toolRegistry;
        this.statusEmitter = statusEmitter;
        this.config = config;
        this.llmRouter = llmRouter;
        this.memory = memory;
        this.curatorService = curatorService;
        this.skillManager = skillManager;
        this.debugService = debugService;
        this.cancellationService = cancellationService;
        this.credentialVault = credentialVault;
        this.conversationService = conversationService;
        this.longRunningTaskManager = longRunningTaskManager;
        this.capabilityResolver = capabilityResolver;
        this.budgetTracker = budgetTracker;
        this.eventLog = eventLog;
        this.scheduledTaskService = scheduledTaskService;
        this.localExecutor = localExecutor;
        this.fileStorage = fileStorage;
    }

    /**
     * Execute the agent loop for a user message.
     * This is the main entry point — replaces the old orchestrator.processMessage().
     *
     * @param userId  the user who submitted the task
     * @param message the user's message
     * @return the agent's final response string
     */
    public String execute(String userId, String message) {
        return execute(userId, message, false);
    }

    /**
     * @param unattended nobody is waiting for this result — see AgentContext.isUnattended
     */
    public String execute(String userId, String message, boolean unattended) {
        try {
            AgentResult result = executeFull(userId, message, unattended);
            return result.response();
        } catch (Exception e) {
            log.error("AgentLoop fatal error for user={}: {}", userId, e.getMessage(), e);
            statusEmitter.emit(userId, StatusMessage.Type.FAILED, "An unexpected error occurred.");
            return "I encountered an unexpected error while processing your request. Please try again.";
        }
    }

    /**
     * Execute the agent loop and return the full AgentResult (with trajectory).
     * Used by the debug API for full execution trace visibility.
     *
     * @param userId  the user who submitted the task
     * @param message the user's message
     * @return the full AgentResult including trajectory, steps, and timing
     */
    public AgentResult executeFull(String userId, String message) {
        return executeFull(userId, message, false);
    }

    public AgentResult executeFull(String userId, String message, boolean unattended) {
        return executeFull(userId, message, unattended, null, List.of());
    }

    /**
     * @param currentMessageId the chat row this task answers, or null for a scheduled or
     *                         background run — which then stops inheriting the last chat row's
     *                         files and stops dropping that row from the prior context, both
     *                         of which the old index-0 guess did
     * @param attachmentIds    the files sent with this turn; registered as PRIVATE artifacts
     */
    public AgentResult executeFull(String userId, String message, boolean unattended,
                                   String currentMessageId, List<String> attachmentIds) {
        String taskId = UUID.randomUUID().toString().substring(0, 8);
        AgentContext context = new AgentContext(userId, taskId, message);
        context.setUnattended(unattended);

        // Load conversation history so the LLM sees prior exchanges
        loadConversationContext(context, userId, currentMessageId);
        registerAttachments(context, attachmentIds);

        // Recall relevant past experiences to enrich context
        try {
            List<AgentMemory.MemoryEntry> relevantMemories = memory.recallEpisodes(userId, message, 3);
            if (!relevantMemories.isEmpty()) {
                String memoryContext = relevantMemories.stream()
                        .map(m -> (m.outcome() ? "[SUCCESS] " : "[FAILED] ") + m.content())
                        .collect(Collectors.joining("\n"));
                context.metadata().put("relevantMemories", memoryContext);
            }
            List<AgentMemory.MemoryEntry> facts = memory.getFacts(userId);
            if (!facts.isEmpty()) {
                String factContext = facts.stream()
                        .map(AgentMemory.MemoryEntry::content)
                        .collect(Collectors.joining("\n"));
                context.setUserPreferences(factContext);
            }
        } catch (Exception e) {
            log.debug("Failed to recall memories for user {}: {}", userId, e.getMessage());
        }

        // Load credential keys so the LLM knows what's in the vault without calling credential_manage list
        try {
            List<String> keys = credentialVault.listCredentialKeys(userId);
            context.setCredentialKeys(keys);
            // The secret ones, decrypted once. The gateway scrubs them from every cloud call;
            // decrypting per call would run PBKDF2 on every step. Keys that are not secrets --
            // SMTP_HOST, SMTP_USER -- are not decrypted and not scrubbed, so a prompt can still
            // say who the mail goes from.
            List<String> secretKeys = keys.stream()
                    .filter(com.ownclaw.users.CredentialVault::isSecretKey).toList();
            if (!secretKeys.isEmpty()) {
                context.setSecretValues(credentialVault.getCredentials(userId, secretKeys));
            }
        } catch (Exception e) {
            log.debug("Failed to load credential keys for user {}: {}", userId, e.getMessage());
        }

        // A skill's own source is not a disclosure of what that skill returned: a Python
        // traceback quotes the line that threw, so without this a credentialed skill's failure
        // made its own repair prompt unsendable. Outside the vault's try, because it has nothing
        // to do with credentials and a vault error must not silently leave it unwired.
        context.setSkillSource(skillManager::readSkillCode);

        // Deterministic capability gap detection — if the task requires a known
        // capability (network scanning, media processing, etc.) and no existing
        // skill covers it, inject a specific hint so the LLM doesn't need to
        // figure out that it should use skill_create + system_packages.
        try {
            CapabilityResolver.CapabilityHint hint = capabilityResolver.resolve(message);
            if (hint != null) {
                context.setCapabilityHint(hint);
                log.info("Capability gap detected for task {}: {} → suggesting skill '{}'",
                        taskId, hint.category(), hint.suggestedName());
            }
        } catch (Exception e) {
            log.debug("Capability resolution failed (non-fatal): {}", e.getMessage());
        }

        // Clear any stale cancel flag from a previous task
        cancellationService.clear(userId, taskId);

        // Point the context at the authoritative cancel source. Without this, every
        // context.isCancelled() check inside a step — LocalExecutor's per-step poll and the
        // supplier handed to every tool — reads a flag nothing ever sets, so Stop could only
        // take effect between steps. A step here can be a 60-133 s local call.
        context.setExternalCancel(
                () -> cancellationService.isCancelled(userId, taskId, context.startTimeMs()));

        AgentResult result;
        inFlight.put(taskId, context);
        try {
            result = runLoop(context).withTaskId(taskId);
        } finally {
            inFlight.remove(taskId);
        }
        emitResult(context, result);

        // Store this execution as an episodic memory
        storeEpisode(context, result);

        return result;
    }

    /**
     * Load conversation history from the database and set it as the conversation
     * summary on the AgentContext. This gives the LLM visibility into prior
     * exchanges so it doesn't re-ask questions the user already answered.
     *
     * Includes:
     *   - Rolling summary of older messages (compressed by ConversationCompressor)
     *   - Last N recent messages in full (the active conversation window)
     */
    /**
     * The files sent with this turn become PRIVATE artifacts: in the canary index, so the cloud
     * cannot receive their bytes by any route, and named by handle so a delegation can hand one
     * to a skill without the cloud reading it.
     */
    private void registerAttachments(AgentContext context, List<String> attachmentIds) {
        List<String> ids = attachmentIds == null ? List.of() : attachmentIds;
        context.setAttachmentIds(ids);
        for (String id : ids) {
            try {
                Map<String, Object> info = fileStorage.getFileInfo(id);
                if (info == null) continue;
                String name = String.valueOf(info.get("original_name"));
                String ct = String.valueOf(info.get("content_type"));
                Object size = info.get("size_bytes");
                String text = fileStorage.isTextContent(ct) ? fileStorage.readAsText(id) : null;
                var why = new ArrayList<String>(List.of("attachment"));
                why.add(ct + ", " + size + " bytes" + (text == null ? ", not text or too large" : ""));
                // PRIVATE only when nobody is watching. On attended chat the owner uploaded the
                // file to this conversation and is waiting for an answer about it; labelling it
                // private there means the cloud can never read it, the delegation's summary is
                // withheld, and "summarise this" returns nothing by any path — which is a
                // capability this slice was not meant to remove. Later-turn inlining stays gone
                // either way, and a scheduled run still never inherits a chat file.
                var label = context.isUnattended()
                        ? com.ownclaw.privacy.Label.PRIVATE : com.ownclaw.privacy.Label.PUBLIC;
                Artifact a = context.addArtifact("attachment:" + name, Map.of("fileId", id),
                        Map.of("fileId", id), text == null ? "" : text, true,
                        new Artifact.Decision(label, why));
                // A row per file, metadata only. No step ever names an attachment, so without
                // this nothing recorded that a task had one -- the task page could not show the
                // file, its label, or whether it was withheld.
                eventLog.log(context.userId(), context.taskId(), "attachment", "info",
                        "attachment " + label, JSON.writeValueAsString(attachmentDetails(a)), 0);
            } catch (Exception e) {
                log.debug("Could not register attachment {}: {}", id, e.getMessage());
            }
        }
        // Recorded by no step, so no step may report them. Without this the mark was still 0
        // when step 1 persisted, and the first step that recorded nothing of its own -- a tool
        // not found, a critic block -- claimed the attachment, and the ops page named it as the
        // tool that step had run.
        context.claimAllArtifacts();
    }

    private void loadConversationContext(AgentContext context, String userId, String currentMessageId) {
        try {
            String sessionId = conversationService.getCurrentSession(userId);

            // Load the compressed summary of older messages (if any)
            String sessionSummary = conversationService.getSessionSummary(userId, sessionId);

            // Load recent messages (excluding the current message which is already in context.originalMessage).
            // The current user message was saved by ChatWebSocketHandler before queue submission,
            // so it will be at index 0 (DESC order). Skip it and reverse the rest to chronological.
            List<Map<String, Object>> recent = conversationService.getRecentMessages(
                    userId, sessionId, CONVERSATION_CONTEXT_MESSAGES + 1);

            StringBuilder sb = new StringBuilder();

            // Include compressed summary of older conversation if available
            if (sessionSummary != null && !sessionSummary.isBlank()) {
                sb.append("### Compressed history of earlier messages\n");
                sb.append(sessionSummary).append("\n\n");
            }

            // Include recent messages in chronological order (skip index 0 = current message).
            // Messages are included in FULL — no truncation. Cutting mid-sentence can cause
            // the LLM to misunderstand what was said. The ConversationCompressor already keeps
            // the overall context bounded by summarizing older messages.
            if (recent.size() > 1 || (recent.size() == 1 && currentMessageId == null)) {
                sb.append("### Recent conversation\n");
                for (int i = recent.size() - 1; i >= 0; i--) {
                    Map<String, Object> row = recent.get(i);
                    // Skip the row this task answers -- it is already the task text -- and only
                    // that one. Index 0 used to be skipped unconditionally, which on a scheduled
                    // or background run, where no row is current, dropped the newest message.
                    if (currentMessageId != null && currentMessageId.equals(row.get("id"))) continue;
                    String role = (String) row.get("role");
                    String content = (String) row.get("content");
                    sb.append(role.toUpperCase()).append(": ").append(content).append("\n");

                    // Include file attachment info for messages that have them
                    String msgId = (String) row.get("id");
                    if (msgId != null) {
                        List<Map<String, Object>> attachments = fileStorage.getMessageAttachmentDetails(msgId);
                        for (var att : attachments) {
                            String fileName = (String) att.get("original_name");
                            String fileId = (String) att.get("id");
                            String ct = (String) att.get("content_type");
                            // Never inlined. A file is PRIVATE: its bytes went to the cloud on
                            // every later task of the session, up to 100 KB each, for as long as
                            // the row stayed in the window. A skill reads it on the turn it was
                            // sent; the cloud sees that it exists.
                            sb.append("[Attached file: ").append(fileName)
                              .append(" (").append(ct).append(", ").append(att.get("size_bytes"))
                              .append(" bytes) — PRIVATE; skills read it on the turn it was sent]\n");
                        }
                    }
                }
            }

            String conversationContext = sb.toString().strip();
            if (!conversationContext.isEmpty()) {
                context.setConversationSummary(conversationContext);
                log.debug("Loaded conversation context for user {} session {}: {} chars, {} recent messages",
                        userId, sessionId, conversationContext.length(), Math.max(0, recent.size() - 1));
            }
        } catch (Exception e) {
            log.warn("Failed to load conversation context for user {}: {}", userId, e.getMessage());
            // Non-fatal — the agent can still process the message without history
        }
    }

    /**
     * Execute the agent loop with a pre-built context (for advanced use cases).
     */
    public AgentResult executeWithContext(AgentContext context) {
        try {
            return runLoop(context);
        } catch (Exception e) {
            log.error("AgentLoop error: {}", e.getMessage(), e);
            return AgentResult.error(
                    "An unexpected error occurred: " + e.getMessage(),
                    context.trajectory(),
                    context.elapsedMs()
            );
        }
    }

    /**
     * The core loop implementation.
     */
    /**
     * A prose reply on restricted unattended work, before anything has actually run.
     * <p>
     * "I'll fetch today's news digest first." is a plan, not an answer, and delivering it as
     * COMPLETED is the specific way withholding the registry breaks the owner's morning email:
     * the model cannot call the skill, says what it would do, and the task ends successfully
     * having done nothing.
     */
    static final String ANSWERED_WITHOUT_WORKING = "Answered without doing the work";

    private AgentResult runLoop(AgentContext context) {
        int maxSteps = config.getTasks().getMaxPlanSteps();
        long stallTimeoutMs = config.getTasks().getStallTimeout() * 1000L;
        int consecutiveFallbacks = 0; // Track consecutive LLM failures to cap retries
        int totalThinkingFailures = 0; // Track total thinking failures across entire task
        int unansweredQuestions = 0;   // ask_user calls on a task with nobody to answer them

        for (int step = 0; step < maxSteps; step++) {
            // Check cancellation — both local flag and service flag from WebSocket cancel button
            if (context.isCancelled()
                    || cancellationService.isCancelled(context.userId(), context.taskId(),
                                                       context.startTimeMs())) {
                log.info("Task {} cancelled by user", context.taskId());
                // Clean up any long-running task tracking
                if (longRunningTaskManager.isActive(context.taskId())) {
                    longRunningTaskManager.cancel(context.taskId());
                }
                return AgentResult.cancelled(
                        "Task was cancelled.",
                        context.trajectory(),
                        context.elapsedMs()
                );
            }

            // Backstop only. This cannot realistically fire: markProgress() runs at the end of
            // every branch below, so by the time execution returns here the reading is
            // microseconds old -- and a task that HANGS hangs inside a step, never reaching this
            // line at all. cancelStalledTasks() on the scheduler is what actually notices, and
            // its cancellation surfaces through the isCancelled() check just above. This stays
            // because it costs nothing and correctly reports a stall that somehow arrives here.
            if (context.msSinceLastProgress() > stallTimeoutMs) {
                long stallSec = context.msSinceLastProgress() / 1000;
                long elapsedSec = context.elapsedMs() / 1000;
                log.warn("Task {} stalled — no progress for {}s (total elapsed {}s)",
                        context.taskId(), stallSec, elapsedSec);
                if (longRunningTaskManager.isActive(context.taskId())) {
                    longRunningTaskManager.fail(context.taskId(),
                            "Task stalled — no progress for " + stallSec + "s");
                }
                String progress = summarizeProgress(context);
                return AgentResult.stalled(
                        "This task stalled (no progress for " + formatDuration(stallSec)
                                + ", total elapsed " + formatDuration(elapsedSec) + "). " + progress,
                        context.trajectory(),
                        context.elapsedMs()
                );
            }

            boolean debug = debugService.isEnabled(context.userId());

            // === DETERMINISTIC SKILL CREATION ===
            // When CapabilityResolver detected a gap (step 0 only), bypass the
            // ThinkingEngine entirely: synthesize the skill_create action from
            // the deterministic hint and go straight to cloud code generation.
            // This removes ALL LLM involvement from the routing decision —
            // no thinking call, no risk of refusal, no wasted tokens.
            // The cloud LLM is only used for code generation (its strength).
            if (step == 0 && context.capabilityHint() != null) {
                CapabilityResolver.CapabilityHint hint = context.capabilityHint();

                log.info("Task {} step 1: deterministic skill_create from CapabilityResolver → '{}'",
                        context.taskId(), hint.suggestedName());

                statusEmitter.emitForTask(context.userId(), context.taskId(), StatusMessage.Type.STEP,
                        "Creating skill '" + hint.suggestedName() + "' (auto-detected)...");

                // Build skill_create params directly from the hint
                Map<String, Object> skillParams = new HashMap<>();
                skillParams.put("name", hint.suggestedName());
                skillParams.put("description", hint.description());
                skillParams.put("parameters", hint.parametersJson());
                skillParams.put("timeout", String.valueOf(hint.timeout()));
                if (!hint.systemPackages().isEmpty()) {
                    skillParams.put("system_packages", String.join(" ", hint.systemPackages()));
                }
                if (!hint.pipPackages().isEmpty()) {
                    skillParams.put("requirements", String.join("\n", hint.pipPackages()));
                }
                if (!hint.credentials().isEmpty()) {
                    skillParams.put("credentials", String.join(",", hint.credentials()));
                }

                AgentAction action = new AgentAction(
                        AgentAction.SKILL_CREATE, skillParams,
                        "CapabilityResolver detected missing " + hint.category()
                                + " capability — creating skill deterministically");

                if (debug) {
                    emitDebug(context.userId(),
                            "DETERMINISTIC SKILL_CREATE: " + hint.suggestedName()
                                    + " (bypassed ThinkingEngine, no LLM call)");
                }

                // Generate code with cloud LLM and create the skill
                Map<String, Object> enhancedParams = generateSkillCodeWithCloud(skillParams, context);
                if (enhancedParams != null) {
                    long startMs = System.currentTimeMillis();
                    String result = skillManager.createSkill(enhancedParams);
                    long durationMs = System.currentTimeMillis() - startMs;
                    boolean ok = !result.startsWith("ERROR");
                    AgentObservation obs = ok
                            ? AgentObservation.success(action.tool(), result, Map.of(), durationMs)
                            : AgentObservation.failure(action.tool(), result, durationMs);
                    recordAndEmitObservation(context, action, obs, step + 1);
                    context.markProgress();
                    if (debug) {
                        emitDebug(context.userId(),
                                "SKILL_CREATE [" + hint.suggestedName() + "] "
                                        + (ok ? "OK" : "FAIL") + " (" + durationMs + "ms)\n"
                                        + truncate(result, 2000));
                    }
                } else {
                    AgentObservation cloudFailObs = AgentObservation.failure(action.tool(),
                                    "ERROR: Cloud LLM unavailable — cannot generate skill code", 0);
                    recordAndEmitObservation(context, action, cloudFailObs, step + 1);
                    context.markProgress();
                }

                // Clear the hint so subsequent steps don't re-trigger
                context.setCapabilityHint(null);
                continue;
            }

            // === THINK ===
            LlmProvider provider = llmRouter.selectProvider(context);
            boolean local = llmRouter.isLocal(provider);
            String providerLabel = local ? "local" : provider.name();
            statusEmitter.emitForTask(context.userId(), context.taskId(), StatusMessage.Type.STEP,
                    "Step " + (step + 1) + " · " + providerLabel,
                    tokenData(context));

            ScheduledFuture<?> thinkHeartbeat = startLlmHeartbeat(context.userId(),
                    "Step " + (step + 1) + " · " + providerLabel);
            ThinkResult thinkResult;
            try {
                thinkResult = thinkingEngine.decideNextActionFull(context, provider);
            } catch (com.ownclaw.llm.EgressRefused refused) {
                // The gateway found bytes of a PRIVATE artifact in the request and nothing was
                // sent. Deterministic, so there is no retry; and no valve, because handing the
                // registry back would not change what the next prompt contains. Expected count
                // in normal operation: zero. An occurrence is a bug report with the handle and
                // the part index attached, and that is what the message carries.
                log.error("Task {} step {}: PRIVACY_BLOCKED — {}", context.taskId(), step + 1,
                        refused.getMessage());
                return AgentResult.privacyBlocked(
                        "Blocked before sending: " + refused.getMessage()
                                + " See task " + context.taskId() + " in ops.",
                        context.trajectory(), context.elapsedMs());
            } finally {
                stopHeartbeat(thinkHeartbeat);
            }
            context.markProgress(); // LLM responded — task is alive
            AgentAction action = thinkResult.action();

            // Track token usage per provider.
            //
            // billedTokens(), not totalTokens(): the latter is prompt + completion as reported,
            // and Anthropic reports cache reads and writes separately and additionally. With the
            // static system prompt cached -- which is the whole point of keeping it static -- the
            // cached prefix is most of the input, so every figure derived from totalTokens was a
            // fraction of what was actually billed: the live counter, token_usage, and every
            // budget ceiling that is supposed to stop a runaway task.
            if (local) {
                context.addLocalTokens(thinkResult.billedTokens());
            } else {
                context.addCloudTokens(thinkResult.billedTokens());
                // Persist cloud usage for budget tracking
                if (thinkResult.billedTokens() > 0) {
                    // Priced from the component breakdown, not the total: cache reads cost about
                    // a tenth of base input and cache writes about a quarter more, so a single
                    // summed figure cannot be costed. This was hardcoded 0.0, which left
                    // token_usage.cost_usd a column of zeros and every budget ceiling inert.
                    double cost = ModelPricing.costUsd(thinkResult.model(),
                            thinkResult.promptTokens(), thinkResult.completionTokens(),
                            thinkResult.cacheWriteTokens(), thinkResult.cacheReadTokens());
                    budgetTracker.recordUsage(context.userId(), provider.name(),
                            thinkResult.billedTokens(), cost);
                }
            }

            // Emit running token totals so the frontend can update the live counter
            statusEmitter.emitForTask(context.userId(), context.taskId(), StatusMessage.Type.PROGRESS,
                    action.tool() + " (" + String.format("%,d", thinkResult.billedTokens()) + " tok)",
                    tokenData(context));

            // Emit thinking detail: user prompt (skip system — it repeats), reasoning, chosen tool
            emitThinkDetail(context.userId(), thinkResult, step + 1, providerLabel);

            // Emit debug info when debug mode is active
            if (debug) {
                emitDebugPrompt(context.userId(), thinkResult, step + 1);
            }

            log.info("Task {} step {}: tool={} reasoning={}", 
                    context.taskId(), step + 1, action.tool(),
                    truncate(action.reasoning(), 100));

            // === RESPOND / ASK ===
            if (action.isResponse()) {
                // Detect LLM parse failures masquerading as responses — retry instead of terminating
                String reasoning = action.reasoning() != null ? action.reasoning() : "";
                boolean isFallback = reasoning.equals("Fallback response")
                        || reasoning.startsWith("LLM did not produce structured output")
                        || reasoning.startsWith("Failed to parse structured output")
                        || reasoning.startsWith("LLM call failed")
                        || reasoning.startsWith(ANSWERED_WITHOUT_WORKING);

                if (isFallback) {
                    consecutiveFallbacks++;
                    totalThinkingFailures++;

                    // After 3 consecutive failures, stop burning tokens and give up
                    if (consecutiveFallbacks >= 3) {
                        log.error("Task {} step {}: {} consecutive LLM failures — aborting task",
                                context.taskId(), step + 1, consecutiveFallbacks);
                        String progress = summarizeProgress(context);
                        // failureLimit, not completed: three consecutive reasoning failures is
                        // an abort. Recording it as COMPLETED marked the event log "info" and
                        // stored the episode with a [SUCCESS] prefix, so the memory layer later
                        // recalled a failed task as a worked example.
                        return AgentResult.failureLimit(
                                "I had trouble completing this request (" + consecutiveFallbacks +
                                " consecutive reasoning failures). Here's what happened:\n\n" + progress,
                                context.trajectory(),
                                context.elapsedMs()
                        );
                    }

                    // After 5 total thinking failures in a task (even non-consecutive), abort
                    if (totalThinkingFailures >= 5) {
                        log.error("Task {} step {}: {} total thinking failures — aborting task",
                                context.taskId(), step + 1, totalThinkingFailures);
                        String progress = summarizeProgress(context);
                        return AgentResult.failureLimit(
                                "I've had " + totalThinkingFailures + " reasoning failures during this task. " +
                                "Here's what happened:\n\n" + progress,
                                context.trajectory(),
                                context.elapsedMs()
                        );
                    }

                    if (step < maxSteps - 1) {
                        log.warn("Task {} step {}: LLM produced fallback response ('{}'), retrying... (consec={}/3, total={}/5)",
                                context.taskId(), step + 1, truncate(action.responseText(), 80),
                                consecutiveFallbacks, totalThinkingFailures);

                        // Build feedback that shows the LLM WHAT it did wrong
                        String rawOutput = thinkResult.rawLlmOutput();
                        StringBuilder feedback = new StringBuilder();
                        if (reasoning.startsWith(ANSWERED_WITHOUT_WORKING)) {
                            // Not a parse failure, and saying so would teach the text envelope
                            // to a model that is holding a tools array.
                            feedback.append("You described what you were going to do instead of "
                                    + "doing it, and nothing has run yet:\n");
                            feedback.append(truncate(rawOutput, 500));
                            feedback.append("\n\nNobody is waiting for this, so the work runs on "
                                    + "the local model. Call 'delegate' with the goal stated in "
                                    + "full. Answer only once there is a result to report.");
                        } else {
                        feedback.append("PARSE ERROR. Your output:\n");
                        feedback.append(truncate(rawOutput, 500));
                        feedback.append("\n\nRequired format: {\"tool\": \"name\", \"params\": {...}, \"reasoning\": \"...\"}\n");
                        feedback.append("To respond: {\"tool\": \"respond\", \"params\": {\"message\": \"...\"}, \"reasoning\": \"...\"}");
                        }

                        if (consecutiveFallbacks >= 2) {
                            feedback.append("\n\nWARNING: This is your ").append(consecutiveFallbacks)
                                    .append("th consecutive failure. ONE more and the task will be aborted.");
                        }

                        AgentObservation failedThink = AgentObservation.failure(
                                "_thinking", feedback.toString(), 0);
                        recordAndEmitObservation(context, action, failedThink, step + 1);
                        context.markProgress(); // LLM produced output (even if malformed)
                        continue;
                    }

                    // Out of steps AND the output is still unparseable. This used to fall through
                    // to the COMPLETED return below, which handed the user the fallback string
                    // ("I'm not sure how to help with that") as if it were a considered answer,
                    // logged the task as info, and stored it in memory as a worked example. The
                    // two abort branches above already refuse to do that after 3 or 5 failures;
                    // running out of steps on the very same kind of failure is no different.
                    log.error("Task {} step {}: unparseable output on the final step — no retry left",
                            context.taskId(), step + 1);
                    return AgentResult.maxSteps(
                            "I ran out of steps while still failing to produce a usable answer. "
                                    + "Here's what happened:\n\n" + summarizeProgress(context),
                            context.trajectory(),
                            context.elapsedMs()
                    );
                } else {
                    consecutiveFallbacks = 0; // Reset on any successful action
                }

                return AgentResult.completed(
                        action.responseText(),
                        context.trajectory(),
                        context.elapsedMs()
                );
            }

            if (action.isAskUser()) {
                // Unattended work has nobody to ask. The question used to be returned as a
                // COMPLETED result, so a scheduled task could stop on its first uncertainty,
                // report success, and quietly never do the thing it was scheduled for.
                //
                // Telling it so and letting it carry on is better than failing here: most
                // questions an agent asks have a defensible default, and it is the agent, not a
                // rule in this file, that knows what the sensible one is. It gets one nudge; a
                // second question means it genuinely cannot proceed without an answer, and then
                // the honest outcome is to stop and say what it needed to know.
                if (context.isUnattended() && unansweredQuestions == 0) {
                    unansweredQuestions++;
                    log.info("Task {} step {}: ask_user on unattended work — telling it to decide",
                            context.taskId(), step + 1);
                    AgentObservation noOne = AgentObservation.failure("ask_user",
                            "Nobody can answer: this task is running unattended, with no user at "
                                    + "the chat. Decide it yourself using the best available "
                                    + "evidence and say plainly in your final answer which "
                                    + "assumption you made, so it can be corrected later. If the "
                                    + "task genuinely cannot proceed without this answer, ask "
                                    + "again and it will stop and report the question.", 0);
                    recordAndEmitObservation(context, action, noOne, step + 1);
                    context.markProgress();
                    continue;
                }
                return AgentResult.needsInput(
                        action.responseText(),
                        context.trajectory(),
                        context.elapsedMs()
                );
            }

            // === SKILL MANAGEMENT (special actions — always available) ===
            if (action.isSkillCreate()) {
                String skillName = str(action.params(), "name");

                // --- Skill-create retry guard ---
                // Count failures only AFTER the most recent successful deletion of this
                // skill (or any skill). A delete+recreate cycle is a legitimate retry
                // strategy and should not be blocked by stale failure history.
                int sameNameFails = 0;
                int totalSkillFails = 0;
                int lastDeleteIndex = -1;
                var allTurns = context.trajectory().turns();
                for (int i = allTurns.size() - 1; i >= 0; i--) {
                    var turn = allTurns.get(i);
                    // Find the most recent successful skill deletion (any name or this name)
                    if (AgentAction.SKILL_MANAGE.equals(turn.action().tool())
                            && turn.observation().success()
                            && "delete".equals(str(turn.action().params(), "action"))) {
                        lastDeleteIndex = i;
                        break;
                    }
                }
                // Only count failures that occurred AFTER the last deletion reset point
                for (int i = lastDeleteIndex + 1; i < allTurns.size(); i++) {
                    var turn = allTurns.get(i);
                    if (AgentAction.SKILL_CREATE.equals(turn.action().tool()) && !turn.observation().success()) {
                        totalSkillFails++;
                        String prevName = str(turn.action().params(), "name");
                        if (skillName != null && skillName.equals(prevName)) sameNameFails++;
                    }
                }
                if (sameNameFails >= 3) {
                    // The advice here used to be "break into smaller sub-skills", and the counter
                    // above keys on the skill NAME — so the cheapest way out of this block was to
                    // rename, which reset the count to zero and produced a sibling. That is how
                    // imap_move_to_trash_by_sender acquired _imaplib and _gmail variants. Renaming
                    // is now refused by the critic's duplicate gate anyway, so suggesting it would
                    // just deadlock the model between two blocks.
                    String msg = "ERROR: Skill '" + skillName + "' has failed " + sameNameFails
                            + " times. Do not retry the same approach, and do NOT create a "
                            + "differently-named variant of it — that is refused. Either change "
                            + "the implementation of '" + skillName + "' itself (a different "
                            + "library or approach, same name), or use ask_user to clarify the "
                            + "requirement.";
                    recordAndEmitObservation(context, action,
                            AgentObservation.failure(action.tool(), msg, 0), step + 1);
                    context.markProgress();
                    log.warn("Task {} step {}: blocked repeated skill_create for '{}' ({} fails)",
                            context.taskId(), step + 1, skillName, sameNameFails);
                    continue;
                }
                if (totalSkillFails >= 5) {
                    String msg = "ERROR: " + totalSkillFails + " skill creation attempts have failed. "
                            + "Simplify your approach. Describe the exact behavior needed in "
                            + "skill_create with a clear, specific description — the cloud LLM generates the code.";
                    recordAndEmitObservation(context, action,
                            AgentObservation.failure(action.tool(), msg, 0), step + 1);
                    context.markProgress();
                    log.warn("Task {} step {}: blocked skill_create after {} total failures",
                            context.taskId(), step + 1, totalSkillFails);
                    continue;
                }

                statusEmitter.emitForTask(context.userId(), context.taskId(), StatusMessage.Type.STEP,
                        "Creating skill '" + action.params().getOrDefault("name", "?") + "'...");

                // Generate skill code exclusively with cloud LLM — never use local model for code gen
                Map<String, Object> enhancedParams = generateSkillCodeWithCloud(action.params(), context);
                if (enhancedParams == null) {
                    String errMsg = "ERROR: Cloud LLM unavailable — cannot generate skill code. " +
                            "Skill creation requires the cloud provider.";
                    recordAndEmitObservation(context, action,
                            AgentObservation.failure(action.tool(), errMsg, 0), step + 1);
                    context.markProgress();
                    if (debug) emitDebug(context.userId(), "SKILL_CREATE FAILED: cloud unavailable");
                    continue;
                }

                long startMs = System.currentTimeMillis();
                String result = skillManager.createSkill(enhancedParams);
                long durationMs = System.currentTimeMillis() - startMs;
                boolean ok = !result.startsWith("ERROR");
                AgentObservation obs = ok
                        ? AgentObservation.success(action.tool(), result, Map.of(), durationMs)
                        : AgentObservation.failure(action.tool(), result, durationMs);
                recordAndEmitObservation(context, action, obs, step + 1);
                context.markProgress();
                consecutiveFallbacks = 0; // Valid tool call from LLM
                if (debug) {
                    emitDebug(context.userId(),
                            "SKILL_CREATE [" + enhancedParams.getOrDefault("name", "?") + "] "
                                    + (ok ? "OK" : "FAIL") + " (" + durationMs + "ms)\n"
                                    + truncate(result, 2000));
                }
                continue;
            }

            if (action.isSkillManage()) {
                // Apply critic evaluation for loop detection on skill_manage
                CriticAgent.Verdict smVerdict = criticAgent.evaluate(action, context);
                if (!smVerdict.allowed()) {
                    log.warn("Task {} step {} skill_manage blocked by critic: {}",
                            context.taskId(), step + 1, smVerdict.blockReason());
                    if (debug) emitDebug(context.userId(), "CRITIC BLOCKED skill_manage: " + smVerdict.blockReason());
                    AgentObservation blockObs = AgentObservation.failure(
                            action.tool(), "BLOCKED: " + smVerdict.blockReason(), 0);
                    recordAndEmitObservation(context, action, blockObs, step + 1);
                    context.markProgress();
                    continue;
                }

                long startMs = System.currentTimeMillis();
                String result = executeSkillManage(action.params(), context);
                long durationMs = System.currentTimeMillis() - startMs;
                boolean ok = !result.startsWith("ERROR");

                // If listing returned empty inventory, append guidance
                String manageAction = action.params().getOrDefault("action", "").toString();
                if ("list".equals(manageAction) && toolRegistry.all().isEmpty()) {
                    result += "\n\nNo tools are registered. Use skill_create to build tools for your task.";
                }

                AgentObservation obs = ok
                        ? AgentObservation.success(action.tool(), result, Map.of(), durationMs)
                        : AgentObservation.failure(action.tool(), result, durationMs);
                recordAndEmitObservation(context, action, obs, step + 1);
                context.markProgress();
                consecutiveFallbacks = 0; // Valid tool call from LLM
                if (debug) {
                    emitDebug(context.userId(),
                            "SKILL_MANAGE [" + action.params().getOrDefault("action", "?") + "] "
                                    + (ok ? "OK" : "FAIL") + " (" + durationMs + "ms)\n"
                                    + truncate(result, 2000));
                }
                continue;
            }

            // === MEMORY MANAGEMENT (special action) ===
            if (action.isMemoryManage()) {
                long startMs = System.currentTimeMillis();
                String result = executeMemoryManage(action.params(), context.userId());
                long durationMs = System.currentTimeMillis() - startMs;
                boolean ok = !result.startsWith("ERROR");
                AgentObservation obs = ok
                        ? AgentObservation.success(action.tool(), result, Map.of(), durationMs)
                        : AgentObservation.failure(action.tool(), result, durationMs);
                recordAndEmitObservation(context, action, obs, step + 1);
                context.markProgress();
                consecutiveFallbacks = 0; // Valid tool call from LLM
                if (debug) {
                    emitDebug(context.userId(),
                            "MEMORY_MANAGE [" + action.params().getOrDefault("action", "?") + "] "
                                    + (ok ? "OK" : "FAIL") + " (" + durationMs + "ms)\n"
                                    + truncate(result, 500));
                }
                continue;
            }

            // === CREDENTIAL MANAGEMENT (special action) ===
            if (action.isCredentialManage()) {
                long startMs = System.currentTimeMillis();
                String result = executeCredentialManage(action.params(), context.userId());
                long durationMs = System.currentTimeMillis() - startMs;
                boolean ok = !result.startsWith("ERROR");
                AgentObservation obs = ok
                        ? AgentObservation.success(action.tool(), result, Map.of(), durationMs)
                        : AgentObservation.failure(action.tool(), result, durationMs);
                recordAndEmitObservation(context, action, obs, step + 1);
                context.markProgress();
                consecutiveFallbacks = 0; // Valid tool call from LLM

                // Refresh credential keys after store so subsequent ✓/✗ marks are accurate
                if (ok && "store".equals(action.params().get("action"))) {
                    try {
                        context.setCredentialKeys(credentialVault.listCredentialKeys(context.userId()));
                    } catch (Exception e) {
                        log.debug("Failed to refresh credential keys: {}", e.getMessage());
                    }
                }

                if (debug) {
                    emitDebug(context.userId(),
                            "CREDENTIAL_MANAGE [" + action.params().getOrDefault("action", "?") + "] "
                                    + (ok ? "OK" : "FAIL") + " (" + durationMs + "ms)\n"
                                    + truncate(result, 500));
                }
                continue;
            }

            // === SCHEDULE MANAGEMENT (special action) ===
            if (action.isScheduleManage()) {
                long startMs = System.currentTimeMillis();
                String result = executeScheduleManage(action.params(), context.userId());
                long durationMs = System.currentTimeMillis() - startMs;
                boolean ok = !result.startsWith("ERROR");
                AgentObservation obs = ok
                        ? AgentObservation.success(action.tool(), result, Map.of(), durationMs)
                        : AgentObservation.failure(action.tool(), result, durationMs);
                recordAndEmitObservation(context, action, obs, step + 1);
                context.markProgress();
                consecutiveFallbacks = 0; // Valid tool call from LLM
                if (debug) {
                    emitDebug(context.userId(),
                            "SCHEDULE_MANAGE [" + action.params().getOrDefault("action", "?") + "] "
                                    + (ok ? "OK" : "FAIL") + " (" + durationMs + "ms)\n"
                                    + truncate(result, 500));
                }
                continue;
            }

            // === DELEGATE TO LOCAL LLM (special action) ===
            if (action.isDelegate()) {
                long startMs = System.currentTimeMillis();
                DelegationPlan plan = LocalExecutor.parsePlan(action.params());
                if (plan.goal().isBlank()) {
                    AgentObservation obs = AgentObservation.failure(action.tool(),
                            "ERROR: 'goal' parameter is required for delegate action.", 0);
                    recordAndEmitObservation(context, action, obs, step + 1);
                    context.markProgress();
                    continue;
                }

                log.info("Task {} step {}: delegating to local LLM — goal: {}, steps: {}, max: {}",
                        context.taskId(), step + 1, truncate(plan.goal(), 100),
                        plan.steps().size(), plan.maxSteps());

                LocalExecutor.Outcome outcome = localExecutor.execute(plan, context);
                long durationMs = System.currentTimeMillis() - startMs;
                String result = outcome.text();
                // Zero tools ran is not a success, whatever the summary says. A local model that
                // fetched nothing and called done with a confident paragraph used to produce a
                // successful step, a successful task, and a scheduled run recorded as delivered
                // -- and with the registry withheld the cloud has no way to check it. Failing
                // here also trips the valve in ThinkingEngine, so the registry comes back and
                // the cloud can finish the job itself rather than delegating into a wall.
                boolean ok = outcome.ok();
                if (!ok && outcome.stepCount() == 0 && !result.startsWith("ERROR")) {
                    log.warn("Task {} step {}: delegation claimed completion with no tool call.",
                            context.taskId(), step + 1);
                }
                // Which skills really ran, so curation and scheduled_task_runs.skills_used see
                // the work instead of a single 'delegate' entry.
                Map<String, Object> structured = new LinkedHashMap<>();
                if (!outcome.toolsRun().isEmpty()) structured.put("delegatedTools", outcome.toolsRun());
                if (!outcome.produced().isEmpty()) {
                    structured.put("artifacts", outcome.produced().stream().map(a -> Map.of(
                            "n", a.n(), "tool", a.tool(), "label", a.label().name(),
                            "chars", a.output().length(), "why", a.why(),
                            "indexed", a.indexed())).toList());
                }
                AgentObservation obs = ok
                        ? AgentObservation.success(action.tool(), result, structured, durationMs)
                        : AgentObservation.failure(action.tool(), result, structured, durationMs);
                recordAndEmitObservation(context, action, obs, step + 1);
                context.markProgress();
                consecutiveFallbacks = 0; // Valid tool call from LLM
                if (debug) {
                    emitDebug(context.userId(),
                            "DELEGATE (" + durationMs + "ms, goal: "
                                    + truncate(plan.goal(), 80) + ")\n"
                                    + truncate(result, 2000));
                }
                continue;
            }

            // === CRITIQUE ===
            CriticAgent.Verdict verdict = criticAgent.evaluate(action, context);
            if (!verdict.allowed()) {
                log.warn("Task {} step {} blocked by critic: {}", context.taskId(), step + 1, verdict.blockReason());
                if (debug) {
                    emitDebug(context.userId(), "CRITIC BLOCKED: " + verdict.blockReason());
                }
                // Feed the block reason back as an observation so the ThinkingEngine can adjust
                AgentObservation blockObs = AgentObservation.failure(
                        action.tool(),
                        "BLOCKED: " + verdict.blockReason(),
                        0
                );
                recordAndEmitObservation(context, action, blockObs, step + 1);
                context.markProgress();
                continue;
            }

            if (verdict.hasWarnings()) {
                for (String warning : verdict.warnings()) {
                    log.info("Task {} critic warning: {}", context.taskId(), warning);
                }
            }

            // === ACT ===
            emitActDetail(context.userId(), action, step + 1);
            statusEmitter.emitForTask(context.userId(), context.taskId(), StatusMessage.Type.STEP,
                    "Running " + action.tool() + "...");
            ScheduledFuture<?> toolHeartbeat = startLlmHeartbeat(context.userId(),
                    "Running " + action.tool());
            AgentObservation observation;
            try {
                observation = executeTool(action, context);
            } finally {
                stopHeartbeat(toolHeartbeat);
            }

            // Append critic warnings to the observation so the LLM sees them
            if (verdict.hasWarnings()) {
                String warningBlock = "\n\n⚠️ SYSTEM: " + String.join(" | ", verdict.warnings());
                observation = new AgentObservation(
                        observation.tool(), observation.success(),
                        observation.output() + warningBlock,
                        observation.structured(), observation.durationMs());
            }

            // === OBSERVE ===
            recordAndEmitObservation(context, action, observation, step + 1);
            context.markProgress(); // tool completed — task is alive
            consecutiveFallbacks = 0; // Reset on successful tool execution

            if (debug) {
                emitDebug(context.userId(),
                        "TOOL RESULT [" + action.tool() + "] "
                                + (observation.success() ? "OK" : "FAIL")
                                + " (" + observation.durationMs() + "ms)\n"
                                + truncate(observation.output(), 50_000));
            }

            // Track tool usage for skill curation analytics. On a FAILURE, keep the parameters
            // and the error too: that is what makes the failure reproducible, and a call that
            // really broke is a better test case than any input we could invent. Successes stay
            // counters only — there is no reason to store the arguments of every call that
            // worked, and doing so would put far more of the user's data in the database.
            if (observation.success()) {
                statusEmitter.emitForTask(context.userId(), context.taskId(), StatusMessage.Type.PROGRESS,
                        action.tool() + " ✓ " + formatDurationMs(observation.durationMs()),
                        tokenData(context));
            } else {
                statusEmitter.emitForTask(context.userId(), context.taskId(), StatusMessage.Type.WARNING,
                        action.tool() + " ✗ " + truncate(observation.output(), 100));
            }

            // === DELEGATION NUDGE ===
            // Detect when the cloud LLM is doing repetitive tool calls that should
            // be delegated to the local LLM. After 2+ consecutive calls to the same
            // registered skill, inject a cost warning into the prompt context.
            injectDelegationNudge(context);

            // Inject reflection after consecutive failures OR consecutive hollow results
            injectReflection(context, action);
        }

        // Exhausted max steps — ask user if they want to continue instead of hard-failing
        log.warn("Task {} hit max steps ({})", context.taskId(), maxSteps);
        String progress = summarizeProgress(context);
        // maxSteps, not completed. The message invites the user to continue, which is friendly,
        // but the task did NOT finish and must not be stored as a successful episode.
        return AgentResult.maxSteps(
                "I've used all " + maxSteps + " steps allocated for this task. " +
                        "Here's what I've done so far:\n" + progress + "\n\n" +
                        "Would you like me to continue working on this? " +
                        "Just say **continue** and I'll pick up where I left off.",
                context.trajectory(),
                context.elapsedMs()
        );
    }

    /**
     * Execute a tool and wrap the result in an AgentObservation.
     */
    private AgentObservation executeTool(AgentAction action, AgentContext context) {
        // The restriction, made structural. ThinkingEngine can withhold a skill from the tools
        // array, but withholding is not enforcement: the text protocol is still parsed, the
        // parser accepts any name, and this method resolves against the whole registry. Without
        // this check the model can talk its way back onto the path it was taken off -- and it
        // would, because on the step where it resists it emits the old text envelope.
        var offered = context.offeredTools();
        if (offered != null && !offered.contains(action.tool())) {
            log.info("Task {}: refused '{}' — it was not offered on this step.",
                    context.taskId(), action.tool());
            return AgentObservation.failure(action.tool(),
                    "'" + action.tool() + "' is not available to you on this task. Nobody is "
                            + "waiting for it, so the work runs on the local model: call "
                            + "'delegate' with the goal stated in full — including anything you "
                            + "have already worked out — and it picks the tools itself.", 0);
        }

        var toolOpt = toolRegistry.find(action.tool());
        if (toolOpt.isEmpty()) {
            String available = String.join(", ", toolRegistry.names());
            String hint = available.isEmpty()
                    ? "No tools are currently registered. Use skill_create to build the tool you need."
                    : "Available tools: " + available + ". Use skill_create if none of these fit (to fix an existing skill, reuse its name).";
            return AgentObservation.failure(action.tool(),
                    "Tool '" + action.tool() + "' not found. " + hint, 0);
        }

        Tool tool = toolOpt.get();

        // Build a progress callback that routes through LongRunningTaskManager.
        // The callback is available to every skill; only skills that call
        // report_progress() will actually use it.  On the first progress report
        // the task is auto-registered as long-running.
        SandboxManager.ProgressCallback progressCallback = new SandboxManager.ProgressCallback() {
            private volatile boolean registered = false;

            @Override
            public void onProgress(String message, Integer percent) {
                if (!registered) {
                    registered = true;
                    String desc = truncate(context.originalMessage(), 200);
                    longRunningTaskManager.register(
                            context.taskId(), context.userId(), desc, action.tool());
                }
                longRunningTaskManager.reportProgress(context.taskId(), message, percent);
            }
        };

        ToolExecutionContext execCtx = new ToolExecutionContext(
                context.userId(),
                context.taskId(),
                null, // workDir — can be extended later
                context::isCancelled,
                progressCallback,
                context.attachmentIds()
        );

        // The same resolver the delegation uses, against the task's results -- the cloud sees
        // task-wide handles in every descriptor, so {{3}} here is the task's third result. One
        // pass substitutes and refuses: a reference that does not resolve, names a failed result,
        // or is not the whole value would otherwise reach the skill as literal text -- as an
        // argument to smtp_send_email, an email whose whole body is five characters, sent and
        // recorded green.
        References.Resolved refs = References.resolve(action.params(), context.artifacts());
        if (!refs.ok()) {
            log.warn("Task {}: '{}' — reference refused.", context.taskId(), refs.refused());
            return AgentObservation.failure(action.tool(), "Not run: the value of '"
                    + refs.refused() + "' would have been sent as literal text. " + refs.reason(), 0);
        }
        Map<String, Object> resolved = refs.params();

        long startMs = System.currentTimeMillis();
        ToolResult result;
        try {
            result = tool.execute(resolved, execCtx);
        } catch (Exception e) {
            log.error("Tool '{}' threw exception: {}", action.tool(), e.getMessage(), e);
            result = ToolResult.failure("Tool execution error: " + e.getMessage());
        }
        long durationMs = System.currentTimeMillis() - startMs;

        // The record, and the substitution at the source. This is the only place a result on
        // the attended path enters the trajectory, and it is before the critic-warning rebuild
        // further up the loop constructs a fresh observation -- which is why the label cannot be
        // a flag on the observation: that rebuild would drop it. The bytes go to the task's
        // store; what goes on is either the bytes (PUBLIC) or the descriptor (PRIVATE), and
        // nothing downstream -- the renderers, the progress summary, the episode, the events
        // rows, the repair evidence -- ever sees the other.
        // The label from what the resolver actually pulled in, so it describes what moved.
        // The same decision the delegation uses. Never tainted here -- the cloud has not read
        // private bytes -- but a call that pulled in an unindexed result stays unindexed.
        Artifact.Decision decision = context.decide(tool.requiredCredentials(), refs.used(), false);
        Artifact artifact = context.addArtifact(tool.name(), action.params(), resolved,
                result.output(), result.success(), decision);

        // Usage, with the raw error: the curator's row is the owner's diagnostic and is read
        // through ops; the label on it is what keeps it out of the cloud's repair prompt.
        curatorService.recordUsage(action.tool(), context.userId(), context.taskId(),
                result.success(), durationMs,
                result.success() ? null : action.params(),
                result.success() ? null : result.output(), artifact.label());

        // If this tool was tracked as long-running, finalize it -- with the shaped text.
        if (longRunningTaskManager.isActive(context.taskId())) {
            String shown = truncate(artifact.isPrivate() ? artifact.describe() : result.output(), 200);
            if (result.success()) longRunningTaskManager.complete(context.taskId(), shown);
            else longRunningTaskManager.fail(context.taskId(), shown);
        }

        return Artifact.asObservation(artifact, result, durationMs);
    }

    /**
     * Dispatch a skill_manage action to the appropriate SkillManager method.
     */
    private String executeSkillManage(Map<String, Object> params, AgentContext context) {
        String action = params.get("action") != null ? params.get("action").toString() : "";
        String name = params.get("name") != null ? params.get("name").toString() : null;

        return switch (action) {
            case "read" -> skillManager.readSkill(name);
            case "delete" -> skillManager.deleteSkill(name);
            case "list" -> skillManager.listSkills();
            case "analyze" -> skillManager.analyzeSkills(context.egress("analyze"));
            default -> "ERROR: Unknown action '" + action + "'. Use one of: read, delete, list, analyze";
        };
    }

    /**
     * Dispatch a credential_manage action to the CredentialVault.
     * Supports: list, check, store.
     */
    private String executeCredentialManage(Map<String, Object> params, String userId) {
        String action = params.get("action") != null ? params.get("action").toString() : "";
        String key = params.get("key") != null ? params.get("key").toString().strip().toUpperCase() : null;
        // NOTE: a 'value' is deliberately NOT read. See the 'store' branch below.

        return switch (action) {
            case "list" -> {
                List<String> keys = credentialVault.listCredentialKeys(userId);
                if (keys.isEmpty()) {
                    yield "No credentials stored. Ask the user for needed credentials and store them with action='store'.";
                }
                yield "Stored credentials: " + String.join(", ", keys);
            }
            case "check" -> {
                if (key == null || key.isBlank()) {
                    yield "ERROR: 'key' parameter is required for action='check'";
                }
                boolean exists = credentialVault.hasCredential(userId, key);
                yield exists
                        ? "Credential '" + key + "' exists in the vault."
                        : "Credential '" + key + "' NOT found. Use ask_user to request it from the user, then store it with action='store'.";
            }
            // Storing through this action is refused on purpose.
            //
            // The old flow was: ask_user for the password -> the user types it into chat ->
            // credential_manage(store, key, value). That put the secret in plaintext in
            // conversations (and the FTS index), sent it to the cloud model as the next task,
            // had the model echo it back as a parameter, replayed it in the action params of
            // every later step, folded it into the rolling summary, wrote 200 chars of it to
            // events, stored it in an episode, and showed it in the activity panel. Only the
            // vault copy was ever encrypted.
            //
            // /cred set writes straight to the vault, never reaches an LLM, and its command text
            // is not saved to the conversation. So the agent asks the user to run that instead.
            case "store" -> {
                String name = (key == null || key.isBlank()) ? "THE_KEY" : key;
                log.info("credential_manage(store) refused for key='{}' — directing user to /cred set", name);
                yield "Storing a credential through this action is disabled: it would send the secret "
                        + "through the model and leave it in plaintext chat history. Ask the user to type "
                        + "this in chat instead, which writes it straight to the encrypted vault without "
                        + "the value passing through you:\n\n    /cred set " + name + " <value>\n\n"
                        + "Then continue — the value is injected into skills that declare '" + name + "' "
                        + "as a required credential. Do not ask the user to paste the value to you.";
            }
            default -> "ERROR: Unknown action '" + action + "'. Use one of: list, check";
        };
    }

    /**
     * Dispatch a memory_manage action to the AgentMemory.
     * Supports: store, list, delete.
     */
    private String executeMemoryManage(Map<String, Object> params, String userId) {
        String action = params.get("action") != null ? params.get("action").toString() : "";
        String key = params.get("key") != null ? params.get("key").toString().strip() : null;
        String content = params.get("content") != null ? params.get("content").toString().strip() : null;

        return switch (action) {
            case "list" -> {
                List<AgentMemory.MemoryEntry> facts = memory.getFacts(userId);
                if (facts.isEmpty()) {
                    yield "No facts stored. Use action='store' to save user preferences and instructions.";
                }
                var sb = new StringBuilder("Stored facts:\n");
                for (var fact : facts) {
                    String factKey = (fact.tags() != null && !fact.tags().isEmpty()) ? fact.tags().getFirst() : "?";
                    sb.append("- [" + factKey + "] " + fact.content() + "\n");
                }
                yield sb.toString();
            }
            case "store" -> {
                if (key == null || key.isBlank()) {
                    yield "ERROR: 'key' parameter is required for action='store'. Use a short identifier like 'lunch_preference' or 'email_style'.";
                }
                if (content == null || content.isBlank()) {
                    yield "ERROR: 'content' parameter is required for action='store'. This is the fact or instruction to remember.";
                }
                try {
                    memory.storeFact(userId, key, content);
                    yield "Remembered: [" + key + "] " + content;
                } catch (Exception e) {
                    yield "ERROR: Failed to store fact: " + e.getMessage();
                }
            }
            case "delete" -> {
                if (key == null || key.isBlank()) {
                    yield "ERROR: 'key' parameter is required for action='delete'.";
                }
                try {
                    boolean deleted = memory.deleteFact(userId, key);
                    yield deleted
                            ? "Fact '" + key + "' deleted."
                            : "Fact '" + key + "' not found — nothing to delete.";
                } catch (Exception e) {
                    yield "ERROR: Failed to delete fact: " + e.getMessage();
                }
            }
            default -> "ERROR: Unknown action '" + action + "'. Use one of: store, list, delete";
        };
    }

    /**
     * Dispatch a schedule_manage action to the ScheduledTaskService.
     * Supports: schedule_once, schedule_recurring, list, cancel, pause, resume.
     */
    private String executeScheduleManage(Map<String, Object> params, String userId) {
        String action = params.get("action") != null ? params.get("action").toString() : "";
        String description = params.get("description") != null ? params.get("description").toString().strip() : null;

        return switch (action) {
            case "schedule_once" -> {
                if (description == null || description.isBlank()) {
                    yield "ERROR: 'description' parameter is required — the task message to execute.";
                }
                String timeExpr = params.get("time") != null ? params.get("time").toString().strip() : null;
                if (timeExpr == null || timeExpr.isBlank()) {
                    yield "ERROR: 'time' parameter is required (e.g. 'in 30 minutes', 'tomorrow at 9am', 'at 14:30').";
                }
                var runAt = scheduledTaskService.parseTimeExpression(timeExpr);
                if (runAt.isEmpty()) {
                    yield "ERROR: Could not parse time expression: '" + timeExpr
                            + "'. Try: 'in N minutes/hours', 'tomorrow at HH:mm', 'at HH:mm'.";
                }
                try {
                    long id = scheduledTaskService.scheduleDeferred(userId, description, runAt.get());
                    yield "Scheduled one-shot task #" + id + " for " + runAt.get() + ": " + description;
                } catch (Exception e) {
                    yield "ERROR: Failed to schedule task: " + e.getMessage();
                }
            }
            case "schedule_recurring" -> {
                if (description == null || description.isBlank()) {
                    yield "ERROR: 'description' parameter is required — the task message to execute each time.";
                }
                String scheduleExpr = params.get("schedule") != null ? params.get("schedule").toString().strip() : null;
                if (scheduleExpr == null || scheduleExpr.isBlank()) {
                    yield "ERROR: 'schedule' parameter is required (e.g. 'every day at 11:00', 'every monday at 9am', 'every 30 minutes').";
                }
                var cronExpr = scheduledTaskService.parseScheduleExpression(scheduleExpr);
                if (cronExpr.isEmpty()) {
                    yield "ERROR: Could not parse schedule: '" + scheduleExpr
                            + "'. Try: 'every day at HH:mm', 'every N minutes', 'every <weekday> at HH:mm', or a raw Spring cron expression.";
                }
                Integer maxRuns = null;
                if (params.get("max_runs") != null) {
                    try {
                        maxRuns = Integer.parseInt(params.get("max_runs").toString());
                    } catch (NumberFormatException e) {
                        yield "ERROR: 'max_runs' must be an integer.";
                    }
                }
                try {
                    long id = scheduledTaskService.scheduleRecurring(userId, description, cronExpr.get(), maxRuns);
                    yield "Scheduled recurring task #" + id + " [" + cronExpr.get() + "]: " + description;
                } catch (Exception e) {
                    yield "ERROR: Failed to schedule recurring task: " + e.getMessage();
                }
            }
            case "list" -> {
                yield scheduledTaskService.formatTasksSummary(userId);
            }
            case "cancel" -> {
                long taskId = parseTaskId(params);
                if (taskId < 0) yield "ERROR: 'task_id' parameter is required (integer).";
                boolean ok = scheduledTaskService.cancel(userId, taskId);
                yield ok ? "Task #" + taskId + " cancelled."
                         : "ERROR: Task #" + taskId + " not found or not cancellable.";
            }
            case "pause" -> {
                long taskId = parseTaskId(params);
                if (taskId < 0) yield "ERROR: 'task_id' parameter is required (integer).";
                boolean ok = scheduledTaskService.pause(userId, taskId);
                yield ok ? "Task #" + taskId + " paused."
                         : "ERROR: Task #" + taskId + " not found or not pausable.";
            }
            case "resume" -> {
                long taskId = parseTaskId(params);
                if (taskId < 0) yield "ERROR: 'task_id' parameter is required (integer).";
                boolean ok = scheduledTaskService.resume(userId, taskId);
                yield ok ? "Task #" + taskId + " resumed."
                         : "ERROR: Task #" + taskId + " not found or not resumable.";
            }
            default -> "ERROR: Unknown action '" + action + "'. Use: schedule_once, schedule_recurring, list, cancel, pause, resume";
        };
    }

    private long parseTaskId(Map<String, Object> params) {
        Object val = params.get("task_id");
        if (val == null) return -1;
        try {
            return Long.parseLong(val.toString());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Detect repetitive tool calls and inject a delegation nudge into context metadata.
     *
     * When the cloud LLM has made 2+ consecutive calls to registered skills (non-special
     * tools), this suggests routine execution that the local model could carry instead.
     * <p>
     * Only on unattended work. The argument for delegating is entirely about cloud tokens, and
     * it ignores the minute per step the local model costs — which is free when nobody is
     * waiting and unacceptable when someone is.
     *
     * The nudge is picked up by ThinkingEngine's buildDynamicContext() and rendered
     * as a cost warning in the prompt.
     */
    private void injectDelegationNudge(AgentContext context) {
        // Only nudge if the local tier can actually take the work -- the same question the tools
        // decision asks, so it reuses the same per-task answer rather than opening its own HTTP
        // round trip on every step.
        if (!thinkingEngine.localTierReady(context)) {
            context.metadata().remove("delegationNudge");
            return;
        }

        // Never while someone is waiting. The nudge counts only cloud tokens, and on that axis
        // delegation is always the right answer -- but a local step costs about a minute, so
        // taking this advice in a live chat trades seconds of cloud time for minutes of silence.
        // It also flatly contradicts what the prompt now tells an attended run to do, and a
        // prompt that argues with itself is worse than one that says nothing.
        if (!context.isUnattended()) {
            context.metadata().remove("delegationNudge");
            return;
        }

        List<AgentTrajectory.Turn> turns = context.trajectory().turns();
        if (turns.size() < 2) {
            context.metadata().remove("delegationNudge");
            return;
        }

        // Count consecutive calls to registered skills (non-special actions) from the end
        int consecutive = 0;
        String repeatedTool = null;
        Set<String> recentSkills = new LinkedHashSet<>();
        for (int i = turns.size() - 1; i >= 0; i--) {
            AgentAction act = turns.get(i).action();
            if (act == null || act.isSpecialAction()) break; // stop at special actions
            String toolName = act.tool();
            // Only count registered tools (skills), not special actions
            if (toolRegistry.find(toolName).isEmpty()) break;
            recentSkills.add(toolName);
            consecutive++;
            if (consecutive == 1) repeatedTool = toolName;
        }

        if (consecutive < 2) {
            context.metadata().remove("delegationNudge");
            return;
        }

        // Build the nudge message
        String toolNames = String.join(", ", recentSkills);
        String nudge;
        if (recentSkills.size() == 1) {
            nudge = String.format(
                "You've called '%s' %d times in a row, which is what 'delegate' is for. "
                + "Bundle the remaining calls into one delegate action. Nobody is waiting for "
                + "this task, so the local model's minute-per-step costs you nothing and the "
                + "cloud tokens it saves are real.",
                repeatedTool, consecutive);
        } else {
            nudge = String.format(
                "You've made %d consecutive skill calls (%s) without needing reasoning between "
                + "them. Hand the rest to 'delegate'. Nobody is waiting for this task, so the "
                + "local model's minute-per-step costs you nothing and the cloud tokens it "
                + "saves are real.",
                consecutive, toolNames);
        }

        context.metadata().put("delegationNudge", nudge);
        log.info("Task {} delegation nudge: {} consecutive skill calls ({})",
                context.taskId(), consecutive, toolNames);
    }

    /**
     * Inject a reflection observation when the agent is struggling.
     * Triggers on consecutive failures OR consecutive hollow (empty-output) results.
     */
    private void injectReflection(AgentContext context, AgentAction lastAction) {
        int failures = context.trajectory().consecutiveFailures();
        int hollow = context.trajectory().consecutiveHollowResults();
        int trouble = Math.max(failures, hollow);

        // Also check overall failure ratio — catches non-consecutive waste patterns
        // (e.g., fail, succeed trivially, fail, succeed trivially, fail...)
        int totalSteps = context.trajectory().size();
        int totalFailed = 0;
        for (var turn : context.trajectory().turns()) {
            if (!turn.observation().success()) totalFailed++;
        }
        boolean highWasteRatio = totalSteps >= 6 && totalFailed * 2 > totalSteps;

        if (trouble < 2 && !highWasteRatio) return;

        String reflectionHint;
        if (highWasteRatio && trouble < 3) {
            // Many failures overall but not strictly consecutive — strategic pivot needed
            reflectionHint = "REFLECT: " + totalFailed + "/" + totalSteps
                    + " steps have failed. Your overall approach is ineffective. "
                    + "PIVOT STRATEGY: (1) Search the internet for how others solve this, "
                    + "(2) Try a completely different library/method/data source, "
                    + "(3) Simplify — deliver a partial result rather than failing completely. "
                    + "Do NOT retry what already failed.";
        } else if (trouble == 2) {
            reflectionHint = "REFLECT: " + trouble + "x " +
                    (failures >= 2 ? "failed" : "empty output") + ". " +
                    "Read skill code (skill_manage read), fix with skill_create (same name), or try different approach.";
        } else {
            reflectionHint = "REFLECT: " + trouble + "x consecutive " +
                    (failures >= trouble ? "failures" : "empty results") + ". " +
                    "STOP repeating. Try a fundamentally different approach: "
                    + "search the internet for solutions, use a different library, "
                    + "or simplify the task. If truly stuck, respond with what you have.";
        }

        // Record reflection so the ThinkingEngine sees it -- as a FAILURE, not a success.
        //
        // The agent did not do anything here; this is the harness telling it that what it has
        // been doing is not working. Recording it as a successful step put a clean turn at the
        // tail of the trajectory, and every safeguard that looks backwards from the tail reads
        // that as recovery: consecutiveFailures() resets, consecutiveHollowResults() resets, and
        // the repeated-action check counts zero identical trailing actions. So injecting the
        // "stop repeating yourself" hint was itself what cleared the evidence of repetition, and
        // a skill failing deterministically could alternate fail / reflect / fail / reflect
        // indefinitely without ever tripping the failure limit -- the hint fired over and over
        // while the counters it depends on never got above one.
        AgentAction reflectionAction = new AgentAction("_reflection", Map.of(), "System-injected reflection");
        AgentObservation reflectionObs = AgentObservation.failure("_reflection", reflectionHint, 0);
        context.trajectory().record(reflectionAction, reflectionObs);
    }

    /**
     * Summarize the progress made so far (for timeout/max-steps responses).
     */
    private String summarizeProgress(AgentContext context) {
        var trajectory = context.trajectory();
        if (trajectory.isEmpty()) return "No actions were taken.";

        var sb = new StringBuilder();
        int successCount = (int) trajectory.turns().stream()
                .filter(t -> t.observation().success())
                .count();
        int total = trajectory.size();
        sb.append("**").append(total).append(" action").append(total != 1 ? "s" : "")
                .append(" taken** (").append(successCount).append(" successful).\n\n");

        // Show each step with its outcome
        int stepNum = 0;
        for (var turn : trajectory.turns()) {
            stepNum++;
            String tool = turn.action().tool();
            boolean ok = turn.observation().success();
            sb.append(stepNum).append(". **").append(tool).append("** — ")
                    .append(ok ? "✓" : "✗");
            // Add brief context: reasoning or failure message
            if (!ok && turn.observation().output() != null && !turn.observation().output().isBlank()) {
                String err = truncate(turn.observation().output(), 200);
                sb.append(" ").append(err);
            } else if (turn.action().reasoning() != null && !turn.action().reasoning().isBlank()) {
                sb.append(" ").append(truncate(turn.action().reasoning(), 120));
            }
            sb.append("\n");
        }

        // Include the last successful observation's output as the partial result
        for (int i = trajectory.turns().size() - 1; i >= 0; i--) {
            var turn = trajectory.turns().get(i);
            if (turn.observation().success() && turn.observation().output() != null
                    && !turn.observation().output().isBlank()) {
                sb.append("\n**Last successful result:**\n");
                String output = turn.observation().output();
                sb.append(output);
                break;
            }
        }

        return sb.toString();
    }

    /**
     * Store the completed task as an episodic memory for future recall.
     */
    private void storeEpisode(AgentContext context, AgentResult result) {
        // A task waiting on an answer has no outcome yet. Storing it as success=false would
        // teach the memory layer that an approach failed when all that happened is that it
        // asked a question — the same lie as the COMPLETED it used to report, pointing the
        // other way. Nothing is recorded until the task actually ends.
        if (result.awaitingUser()) return;
        try {
            String summary = "Task: " + truncate(context.originalMessage(), 200) +
                    "\nSteps: " + result.totalSteps() +
                    "\nOutcome: " + result.terminationReason() +
                    "\nResponse: " + truncate(result.response(), 500);

            // Extract tool names used as tags
            List<String> tags = context.trajectory().turns().stream()
                    .map(t -> t.action().tool())
                    .filter(t -> t != null && !t.equals(AgentAction.RESPOND))
                    .distinct()
                    .collect(Collectors.toList());

            memory.storeEpisode(
                    context.userId(),
                    context.taskId(),
                    summary,
                    result.success(),
                    tags
            );
        } catch (Exception e) {
            log.debug("Failed to store episode for task {}: {}", context.taskId(), e.getMessage());
        }
    }

    private void emitResult(AgentContext context, AgentResult result) {
        String userId = context.userId();
        int local = context.localTokens();
        int cloud = context.cloudTokens();

        // Build compact summary line with tokens included
        StringBuilder summary = new StringBuilder();
        if (result.success()) {
            summary.append(result.totalSteps()).append(" steps · ")
                    .append(formatDurationMs(result.totalDurationMs()));
        } else if (result.awaitingUser()) {
            summary.append("waiting for your answer");
        } else {
            summary.append(result.terminationReason());
        }
        if (cloud > 0 || local > 0) {
            summary.append(" · ");
            if (cloud > 0) summary.append(String.format("%,d", cloud)).append(" cloud");
            if (cloud > 0 && local > 0) summary.append(" + ");
            if (local > 0) summary.append(String.format("%,d", local)).append(" local");
            summary.append(" tokens");
        }

        // Three outcomes, not two. A task that stopped to ask the user a question is neither
        // done nor broken, and showing it as FAILED is as wrong as the COMPLETED it used to show.
        StatusMessage.Type type = result.awaitingUser() ? StatusMessage.Type.NEED_INPUT
                : result.success() ? StatusMessage.Type.COMPLETED
                : StatusMessage.Type.FAILED;
        // Attributed to the task. A terminal status with no id is indistinguishable from any
        // other task's, and the frontend treats one as "the work is finished" -- so a background
        // digest completing would stop the spinner and close out the activity strip of an
        // interactive task still running, telling the user their question was done when it was
        // not. Everything else emitted from inside a task already carries the id; this, the one
        // that ends the UI's story, did not.
        statusEmitter.emitForTask(userId, context.taskId(), type, summary.toString(),
                tokenData(context));

        // Persist token usage to the events table for auditing
        try {
            String details = String.format(
                    "{\"cloudTokens\":%d,\"localTokens\":%d,\"steps\":%d,\"durationMs\":%d,\"reason\":\"%s\"}",
                    cloud, local, result.totalSteps(), result.totalDurationMs(), result.terminationReason());
            eventLog.log(userId, context.taskId(), "task_completed",
                    result.success() || result.awaitingUser() ? "info" : "warn",
                    truncate(context.originalMessage(), 200),
                    details, cloud + local);
        } catch (Exception e) {
            log.warn("Failed to log token usage for task {}: {}", context.taskId(), e.getMessage());
        }
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    /** Add line numbers to code for precise error location in repair prompts. */
    private String numberCodeLines(String code) {
        if (code == null) return "";
        String[] lines = code.split("\n", -1);
        var sb = new StringBuilder(code.length() + lines.length * 5);
        for (int i = 0; i < lines.length; i++) {
            sb.append(String.format("%3d| %s\n", i + 1, lines[i]));
        }
        return sb.toString();
    }

    /** Format seconds as human-readable duration, e.g. "5m 23s" or "45s". */
    private static String formatDuration(long totalSec) {
        if (totalSec >= 3600) {
            return (totalSec / 3600) + "h " + ((totalSec % 3600) / 60) + "m " + (totalSec % 60) + "s";
        } else if (totalSec >= 60) {
            return (totalSec / 60) + "m " + (totalSec % 60) + "s";
        } else {
            return totalSec + "s";
        }
    }

    /** Format milliseconds as compact duration, e.g. "5.4s" or "2m 12s". */
    private static String formatDurationMs(long ms) {
        if (ms < 1000) return ms + "ms";
        double sec = ms / 1000.0;
        if (sec < 60) return String.format("%.1fs", sec);
        long totalSec = ms / 1000;
        return (totalSec / 60) + "m " + (totalSec % 60) + "s";
    }

    /** Build structured token data for status messages. */
    private Map<String, Object> tokenData(AgentContext context) {
        int totalSteps = context.trajectory().size();
        int successes = (int) context.trajectory().turns().stream()
                .filter(t -> t.observation().success()).count();
        return Map.of(
                "cloudTokens", context.cloudTokens(),
                "localTokens", context.localTokens(),
                "totalSteps", totalSteps,
                "successCount", successes
        );
    }

    // ── Cloud skill code generation ──

    /**
     * Generate skill code using the cloud LLM exclusively.
     *
     * <p>The local model decides WHAT skill to create (name, description, parameter
     * intent) — that's fast routing.  The cloud model writes the actual Python
     * code — that's where quality matters most.  Skill code is never generated
     * by the local model: it is too sensitive to LLM quality.
     *
     * @return enhanced params with cloud-generated code, or {@code null} if cloud
     *         generation fails (caller should record the failure).
     */
    /**
     * Real calls to this skill that failed, as evidence for a repair.
     * <p>
     * Returns null when there is nothing recorded. The parameters were redacted and truncated
     * when they were stored, so this is safe to put in a prompt.
     * <p>
     * Deliberately evidence and not a test harness. Re-running these calls to check whether a
     * repair worked would be the obvious next step and it is not safe: replaying a recorded
     * invocation of a skill like {@code imap_move_to_trash_by_sender} would move real mail. The
     * only thing that could gate such a replay is the skill's own {@code has_side_effects} flag,
     * which the model that wrote the skill supplied — trusting a model's self-declaration to
     * decide whether it is safe to execute something is exactly the kind of judgement that fails
     * quietly on the case nobody thought about. Showing the failures to the model repairing the
     * code gets most of the benefit with none of that risk.
     */
    private String pastFailureEvidence(String skillName) {
        try {
            var failures = curatorService.recentFailures(skillName, 3);
            if (failures.isEmpty()) return null;
            var sb = new StringBuilder("Real calls to this skill that FAILED previously "
                    + "(parameters are redacted where they looked sensitive):\n");
            for (var f : failures) {
                sb.append("- called with: ").append(f.get("params_json")).append('\n')
                  .append("  failed with: ").append(truncate(String.valueOf(f.get("error")), 600))
                  .append('\n');
            }
            sb.append("Make sure the fixed code handles these cases.");
            return sb.toString();
        } catch (Exception e) {
            log.debug("Could not load failure history for '{}': {}", skillName, e.getMessage());
            return null;
        }
    }

    private Map<String, Object> generateSkillCodeWithCloud(Map<String, Object> originalParams, AgentContext context) {
        LlmProvider cloud = llmRouter.cloud();
        boolean usingLocalFallback = false;
        LlmProvider codeGenProvider = cloud;
        if (!cloud.isAvailable()) {
            // Degraded fallback: attempt code generation with local LLM
            LlmProvider local = llmRouter.local();
            if (local.isAvailable()) {
                log.warn("Cloud provider unavailable — falling back to local LLM for skill code generation (degraded quality)");
                codeGenProvider = local;
                usingLocalFallback = true;
            } else {
                log.error("Both cloud and local providers unavailable — cannot generate skill code");
                return null;
            }
        }

        String name = str(originalParams, "name");
        String description = str(originalParams, "description");
        String parameters = str(originalParams, "parameters");
        String requirements = str(originalParams, "requirements");

        // For existing skills: read old code and find last execution error for targeted fix
        String oldCode = skillManager.readSkillCode(name);
        String lastError = null;
        if (oldCode != null) {
            // Walk trajectory backwards to find the most recent failed execution of this skill
            var turns = context.trajectory().turns();
            for (int i = turns.size() - 1; i >= 0; i--) {
                var turn = turns.get(i);
                if (turn.action().tool().equals(name) && !turn.observation().success()) {
                    lastError = turn.observation().output();
                    break;
                }
            }
            // The trajectory only knows about failures in THIS task. A skill that broke last
            // week, in a different conversation, left nothing here — so the repair regenerated
            // blind, against an error it could not see. skill_usage now keeps the parameters
            // and the error of real failures, which is evidence rather than guesswork: the
            // exact calls that broke, so the fix can be aimed at them.
            String history = pastFailureEvidence(name);
            if (history != null) {
                lastError = lastError == null ? history : lastError + "\n\n" + history;
            }
            log.info("Skill '{}' exists — will attempt targeted fix{}{}",
                    name,
                    lastError != null ? " (error available)" : " (no error known)",
                    history != null ? " + recorded failure history" : "");
        }

        String providerLabel = usingLocalFallback ? "local (degraded)" : "cloud";
        statusEmitter.emitForTask(context.userId(), context.taskId(), StatusMessage.Type.STEP,
                    oldCode != null ? "Fixing skill code · " + providerLabel : "Generating skill code · " + providerLabel,
                    tokenData(context));
        try {
            List<LlmMessage> messages = buildSkillCodePrompt(
                    name, description, parameters, requirements, originalParams, context,
                    oldCode, lastError);

            // For local LLM fallback: add extra constraint to keep code simple
            if (usingLocalFallback) {
                messages.add(LlmMessage.user(
                        "CRITICAL: You are a local model. Keep code SIMPLE. "
                        + "Use only stdlib + one well-known library. Avoid complex logic. "
                        + "Prefer straightforward imperative code over abstractions."));
            }

            LlmRequestConfig codeGenConfig = new LlmRequestConfig(
                    null,   // use provider default model
                    0.2,    // low temperature for precise code generation
                    null,   // no token limit — let the model finish naturally
                    false,  // no JSON mode — we want raw Python code
                    null    // use provider default read timeout
            ).withEgress(context.egress("codegen"));

            ScheduledFuture<?> heartbeat = startLlmHeartbeat(context.userId(),
                    "Generating code for '" + name + "'");
            LlmResponse response;
            try {
                response = codeGenProvider.chat(messages, codeGenConfig);
            } finally {
                stopHeartbeat(heartbeat);
            }
            String cloudCode = extractPythonCode(response.content());

            if (cloudCode == null || cloudCode.isBlank()) {
                log.warn("extractPythonCode returned null. Raw response (first 500 chars): {}",
                        response.content() == null ? "(null)"
                                : response.content().substring(0, Math.min(500, response.content().length())));
            }

            // Track tokens for skill code generation against the tier that actually did it.
            //
            // The context counter already excluded the local fallback, but the budget did not:
            // when the cloud provider was unavailable and Ollama generated the code, those free
            // local tokens were still recorded against the cloud budget. So an outage that
            // forced everything local consumed the daily cloud allowance fastest, and could
            // exhaust a ceiling without a single cloud call having been made.
            if (usingLocalFallback) {
                context.addLocalTokens(response.billedInputTokens() + response.completionTokens());
            } else {
                context.addCloudTokens(response.billedInputTokens() + response.completionTokens());
            }
            if (response.totalTokens() > 0 && !usingLocalFallback) {
                budgetTracker.recordUsage(context.userId(), codeGenProvider.name(),
                        response.billedInputTokens() + response.completionTokens(),
                        ModelPricing.costUsd(codeGenProvider.model(), response));
            }

            // --- Structural pre-check: reject obviously broken code early ---
            if (cloudCode != null && !cloudCode.isBlank() && !cloudCode.contains("def run(")) {
                log.warn("Skill '{}': generated code missing 'def run(params)' — treating as extraction failure", name);
                cloudCode = null;
            }

            // --- Inner syntax-repair loop: fix syntax errors without burning outer agent steps ---
            if (cloudCode != null && !cloudCode.isBlank()) {
                String syntaxError = skillManager.checkPythonSyntax(cloudCode);
                for (int repair = 0; repair < 3 && syntaxError != null; repair++) {
                    log.warn("Skill '{}' syntax error (repair attempt {}/3): {}", name, repair + 1, syntaxError);
                    statusEmitter.emitForTask(context.userId(), context.taskId(), StatusMessage.Type.STEP,
                            "Repairing syntax error · " + providerLabel + " (attempt " + (repair + 1) + "/3)",
                            tokenData(context));

                    // Build a focused repair prompt with the error and numbered code context
                    String numberedCode = numberCodeLines(cloudCode);
                    messages.add(LlmMessage.assistant(response.content()));
                    messages.add(LlmMessage.user(
                            "Syntax error:\n" + syntaxError
                            + "\n\nNumbered code:\n" + numberedCode
                            + "\n\nFix the error. Return the COMPLETE corrected code in a ```python fence."));

                    ScheduledFuture<?> repairHeartbeat = startLlmHeartbeat(context.userId(),
                            "Repairing code for '" + name + "'");
                    LlmResponse repairResponse;
                    try {
                        repairResponse = codeGenProvider.chat(messages, codeGenConfig);
                    } finally {
                        stopHeartbeat(repairHeartbeat);
                    }

                    if (!usingLocalFallback) {
                        context.addCloudTokens(repairResponse.totalTokens());
                    }
                    if (repairResponse.totalTokens() > 0) {
                        budgetTracker.recordUsage(context.userId(), codeGenProvider.name(),
                                repairResponse.totalTokens(),
                                ModelPricing.costUsd(codeGenProvider.model(), repairResponse));
                    }

                    String repairedCode = extractPythonCode(repairResponse.content());
                    if (repairedCode != null && !repairedCode.isBlank()) {
                        cloudCode = repairedCode;
                        response = repairResponse;
                        syntaxError = skillManager.checkPythonSyntax(cloudCode);
                    } else {
                        log.warn("Repair attempt {}/3 returned no extractable code", repair + 1);
                        break;
                    }
                }
                if (syntaxError != null) {
                    log.error("Skill '{}' still has syntax errors after {} repair attempts: {}", name, 3, syntaxError);
                    // Still return the code — let SkillManager.createSkill() report the error
                    // so the outer agent loop can track the failure properly
                }
            }

            if (cloudCode != null && !cloudCode.isBlank()) {
                log.info("{} generated {} chars of skill code for '{}' ({} tokens)",
                        usingLocalFallback ? "Local LLM (fallback)" : "Cloud LLM",
                        cloudCode.length(), name, response.totalTokens());

                // Build enhanced params with cloud-generated code
                Map<String, Object> enhanced = new HashMap<>(originalParams);
                enhanced.put("code", cloudCode);

                // Cloud may also suggest better requirements — extract if present
                String cloudRequirements = extractRequirements(response.content());
                if (cloudRequirements != null) {
                    enhanced.put("requirements", cloudRequirements);
                }

                return enhanced;
            } else {
                log.error("Cloud LLM returned no extractable Python code for '{}'", name);
                return null;
            }
        } catch (Exception e) {
            log.error("Cloud skill code generation failed for '{}': {}", name, e.getMessage());
            return null;
        }
    }

    /**
     * Build a specialized prompt for the cloud LLM to generate high-quality skill code.
     *
     * <p>When {@code oldCode} is non-null, the prompt switches to "fix" mode:
     * the cloud LLM sees the existing code and the error, and is instructed to
     * make a targeted fix rather than regenerating from scratch.
     *
     * @param oldCode   the current Python code of the skill (null for new skills)
     * @param lastError the most recent execution error (null if unknown)
     */
    private List<LlmMessage> buildSkillCodePrompt(
            String name, String description, String parameters,
            String requirements, Map<String, Object> originalParams, AgentContext context,
            String oldCode, String lastError) {

        List<LlmMessage> messages = new ArrayList<>();

        // System prompt: expert Python code generator
        var sys = new StringBuilder();
        sys.append("Expert Python developer. Production-quality, first try.\n\n");
        sys.append("Contract: `def run(params)` → `{'output': str, 'success': bool}`. No unhandled exceptions.\n");
        sys.append("Always `def run(params):` — never keyword args. Use `params.get('key')`.\n");
        sys.append("```python\ndef run(params):\n    url = params.get('url', '')\n    resp = requests.get(url, timeout=30)\n    return {'success': True, 'output': resp.text}\n```\n");
        sys.append("Local env, full access. Credentials as env vars. system_packages on PATH.\n");
        sys.append("Output: ```python fence + ```requirements fence.\n\n");
        sys.append("SELF-CHECK before outputting:\n");
        sys.append("1. All strings/f-strings properly closed (watch triple-quotes and nested quotes)\n");
        sys.append("2. All brackets/parens matched\n");
        sys.append("3. Consistent indentation (4 spaces, no tabs)\n");
        sys.append("4. `def run(params):` exists at module level\n");
        sys.append("5. Every code path returns {'output': str, 'success': bool}\n\n");

        if (oldCode != null) {
            sys.append("FIXING existing skill. Minimal targeted fix — preserve working parts.\n");
        } else {
            sys.append("Clean, efficient code. Established libraries. No unnecessary boilerplate.\n");
        }

        messages.add(LlmMessage.system(sys.toString()));

        // User prompt: the skill specification (or fix request)
        var user = new StringBuilder();
        user.append(oldCode != null ? "Fix this skill:\n\n" : "Generate skill code:\n\n");
        user.append("Name: ").append(name).append(" | Params: ").append(parameters).append("\n");
        user.append("Description: ").append(description).append("\n");
        if (requirements != null && !requirements.isBlank()) {
            user.append("Pip: ").append(requirements).append("\n");
        }

        String credentials = str(originalParams, "credentials");
        if (credentials != null && !credentials.isBlank()) {
            user.append("Env vars (guaranteed present): ").append(credentials).append("\n");
        }

        if (oldCode != null) {
            user.append("\nBroken code:\n```python\n").append(oldCode).append("\n```\n");
            if (lastError != null && !lastError.isBlank()) {
                user.append("Error: ").append(truncate(lastError, 1000)).append("\n");
            }
            user.append("Return complete fixed code.\n");
        }

        user.append("\nTask context: \"").append(truncate(context.originalMessage(), 500)).append("\"\n");

        messages.add(LlmMessage.user(user.toString()));

        return messages;
    }

    /**
     * Extract Python code from a cloud LLM response.
     * Handles ```python fences, plain ``` fences, and raw code.
     */
    private String extractPythonCode(String response) {
        if (response == null || response.isBlank()) return null;

        // Try to find ```python ... ``` fence (case-insensitive, handles ```Python too)
        java.util.regex.Matcher pyFence = java.util.regex.Pattern
                .compile("```[Pp]ython\\s*\n(.*?)```", java.util.regex.Pattern.DOTALL)
                .matcher(response);
        if (pyFence.find()) {
            String code = pyFence.group(1).strip();
            if (!code.isBlank()) return code;
        }

        // Try plain ``` fence (first one)
        java.util.regex.Matcher plainFence = java.util.regex.Pattern
                .compile("```\\s*\n(.*?)```", java.util.regex.Pattern.DOTALL)
                .matcher(response);
        if (plainFence.find()) {
            String code = plainFence.group(1).strip();
            if (!code.isBlank() && (code.contains("def run") || code.startsWith("import ") || code.startsWith("from "))) {
                return code;
            }
        }

        // Handle TRUNCATED responses: opening ```python fence but no closing ``` fence
        // (happens when the LLM hits max_tokens and output is cut off mid-code)
        java.util.regex.Matcher truncatedPy = java.util.regex.Pattern
                .compile("```[Pp]ython\\s*\n(.*)", java.util.regex.Pattern.DOTALL)
                .matcher(response);
        if (truncatedPy.find()) {
            String code = truncatedPy.group(1).strip();
            // Remove any trailing ``` fences from other blocks (e.g. ```requirements)
            int nextFence = code.indexOf("```");
            if (nextFence > 0) {
                code = code.substring(0, nextFence).strip();
            }
            if (!code.isBlank() && code.contains("def run")) {
                log.warn("Extracted Python code from TRUNCATED response (no closing fence). "
                        + "Code may be incomplete — {} chars extracted.", code.length());
                return code;
            }
        }

        // If the response looks like raw Python code (starts with import, from, def, or #), use it directly
        String trimmed = response.strip();
        if (trimmed.startsWith("import ") || trimmed.startsWith("from ") || trimmed.startsWith("def ") || trimmed.startsWith("#!/")) {
            return trimmed;
        }

        // Last resort: look for def run( anywhere in the response
        int defRunIdx = response.indexOf("def run(");
        if (defRunIdx >= 0) {
            // Walk backwards to find the first import/from line or start of code block
            String beforeDef = response.substring(0, defRunIdx);
            int codeStart = Math.max(beforeDef.lastIndexOf("import "), beforeDef.lastIndexOf("from "));
            if (codeStart >= 0) {
                // Go to the start of that line
                codeStart = beforeDef.lastIndexOf('\n', codeStart) + 1;
            } else {
                codeStart = defRunIdx;
            }
            String candidate = response.substring(codeStart).strip();
            // Remove any trailing explanation after the code
            int trailingFence = candidate.indexOf("```");
            if (trailingFence > 0) {
                candidate = candidate.substring(0, trailingFence).strip();
            }
            if (!candidate.isBlank()) return candidate;
        }

        return null;
    }

    /**
     * Extract pip requirements from a cloud LLM response if it included a
     * ```requirements fence.
     */
    private String extractRequirements(String response) {
        if (response == null) return null;

        int start = response.indexOf("```requirements");
        if (start < 0) return null;

        start = response.indexOf('\n', start) + 1;
        int end = response.indexOf("```", start);
        if (end > start) {
            String reqs = response.substring(start, end).strip();
            return reqs.isBlank() ? null : reqs;
        }
        return null;
    }

    private String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v != null ? v.toString() : null;
    }

    // ── Detail emission helpers (always-on activity panel enrichment) ──

    /** Emit thinking step detail: user prompt messages (skip system), reasoning, chosen action. */
    private void emitThinkDetail(String userId, ThinkResult result, int step, String provider) {
        var detail = new LinkedHashMap<String, Object>();
        detail.put("category", "think");
        detail.put("step", step);
        detail.put("provider", provider);
        detail.put("tokens", result.totalTokens());

        // Collect user/assistant prompt messages (skip system — it repeats every step)
        var promptParts = new ArrayList<String>();
        for (var msg : result.promptMessages()) {
            if (msg.role() == LlmMessage.Role.SYSTEM) continue;
            promptParts.add("[" + msg.role().apiValue() + "] " + truncate(msg.content(), 800));
        }
        detail.put("prompt", String.join("\n---\n", promptParts));

        // LLM decision
        detail.put("tool", result.action().tool());
        detail.put("reasoning", truncate(result.action().reasoning(), 500));

        // Include params preview for non-respond actions
        if (!result.action().isResponse() && result.action().params() != null) {
            detail.put("params", truncate(result.action().params().toString(), 300));
        }

        statusEmitter.emit(userId, new StatusMessage(StatusMessage.Type.STEP,
                "💭 Think · Step " + step + " → " + result.action().tool(), detail));
    }

    /** Emit tool execution detail: tool name + input parameters. */
    private void emitActDetail(String userId, AgentAction action, int step) {
        var detail = new LinkedHashMap<String, Object>();
        detail.put("category", "act");
        detail.put("step", step);
        detail.put("tool", action.tool());
        if (action.params() != null && !action.params().isEmpty()) {
            // Show param keys + truncated values
            var paramPreview = new LinkedHashMap<String, String>();
            for (var entry : action.params().entrySet()) {
                String val = entry.getValue() != null ? entry.getValue().toString() : "null";
                paramPreview.put(entry.getKey(), truncate(val, 200));
            }
            detail.put("params", paramPreview);
        }
        statusEmitter.emit(userId, new StatusMessage(StatusMessage.Type.STEP,
                "⚡ Act · " + action.tool(), detail));
    }

    /** Record an observation in trajectory AND emit detail to the frontend (for live stats). */
    private void recordAndEmitObservation(AgentContext context, AgentAction action,
                                           AgentObservation obs, int step) {
        obs = compressIfUnattended(context, obs);
        context.trajectory().record(action, obs);
        emitObserveDetail(context.userId(), action, obs, step, context);
        persistStep(context, action, obs, step);
    }

    /**
     * When nobody is waiting, have the local model compress a large tool result before it is
     * recorded — and therefore before it is sent to the cloud.
     * <p>
     * This is the first place the local tier does real work rather than post-hoc bookkeeping,
     * and it is the one job that clearly pays for itself. A large result otherwise reaches the
     * cloud head-and-tail truncated, so the middle is simply gone: the model reasons over a
     * result with a hole in it, and pays for the parts that survived. A local summary keeps the
     * meaning of the whole thing, and local tokens cost nothing.
     * <p>
     * Only when unattended. The call takes 60-133 seconds on this hardware, which is
     * unacceptable on a turn someone is watching and irrelevant on a scheduled job at 3am.
     * That is the entire reason the attended/unattended distinction was worth building.
     * <p>
     * Only above a threshold, because a local call to shorten something that is already short
     * would spend a minute to save nothing. And failures are swallowed: summarizeIfLong falls
     * back to truncation on its own, and a compression step must never be able to fail a task.
     */
    private AgentObservation compressIfUnattended(AgentContext context, AgentObservation obs) {
        if (!context.isUnattended() || obs == null) return obs;
        // A delegation that failed keeps its full text. Its output carries the verbatim
        // traceback of whatever threw, and rewriting a skill from its stack trace is the
        // self-learning loop this project exists for -- it cannot run on a paraphrase of a
        // paraphrase by the same small model that already summarised it once. A delegation
        // that SUCCEEDED is compressed like anything else: there the summary is the point, and
        // a large one costs the cloud exactly what a large tool result would.
        if (AgentAction.DELEGATE.equals(obs.tool()) && !obs.success()) return obs;
        String output = obs.output();
        if (output == null || output.length() < LOCAL_COMPRESSION_THRESHOLD) return obs;
        try {
            long t0 = System.currentTimeMillis();
            String compressed = localExecutor.summarizeIfLong(output, LOCAL_COMPRESSION_TARGET);
            if (compressed == null || compressed.isBlank() || compressed.length() >= output.length()) {
                return obs;
            }
            log.info("Unattended task {}: compressed {} chars of {} output to {} in {}ms",
                    context.taskId(), output.length(), obs.tool(), compressed.length(),
                    System.currentTimeMillis() - t0);
            return new AgentObservation(obs.tool(), obs.success(), compressed,
                    obs.structured(), obs.durationMs());
        } catch (Exception e) {
            log.debug("Local compression failed for task {}, keeping the raw output: {}",
                    context.taskId(), e.getMessage());
            return obs;
        }
    }

    /**
     * Write one row per step, so what a task did outlives the task.
     * <p>
     * The trajectory was in memory only — no INSERT anywhere in the codebase — so the durable
     * record of a multi-minute run was a single {@code task_completed} row plus the final chat
     * message. Ten minutes later nobody, including the agent, could answer "why did it do that"
     * or "what did that skill actually return", which is most of what "user insight into what is
     * happening is limited" means in practice.
     * <p>
     * No new table: {@code events} already has task_id, a JSON details column and an index on
     * (user_id, task_id), and json_extract is already used elsewhere. The severity carries the
     * outcome, so failed steps are greppable without parsing anything.
     * <p>
     * Parameters are deliberately NOT stored here. The tool sequence is what this is for —
     * seeing what a run did, and later noticing that the same sequence keeps succeeding, which
     * is the signal a capability is worth consolidating. Arguments would put far more of the
     * user's data in the database for no added signal, and where they genuinely are needed —
     * reproducing a failure — skill_usage already keeps them, redacted.
     * <p>
     * Never allowed to break a task: a task that works but is not recorded is much better than a
     * task that dies because recording failed.
     */
    private void persistStep(AgentContext context, AgentAction action,
                             AgentObservation obs, int step) {
        try {
            var details = new LinkedHashMap<String, Object>();
            details.put("step", step);
            details.put("tool", action.tool());
            details.put("success", obs.success());
            details.put("durationMs", obs.durationMs());
            details.put("localTokens", context.localTokens());
            details.put("cloudTokens", context.cloudTokens());
            java.util.Optional<Artifact> claimed = java.util.Optional.empty();
            // Metadata only, same as the ledger: handle, label, size, hash, why. Never content.
            if (action.isDelegate()) {
                Object arts = obs.structured() == null ? null : obs.structured().get("artifacts");
                if (arts != null) {
                    details.put("artifacts", arts);
                    // The delegation listed its own; no later step may claim them again. Only
                    // when it actually ran -- a delegate step that never reached the executor
                    // recorded nothing, and claiming there would swallow an earlier artifact.
                    context.claimAllArtifacts();
                }
            } else if (!action.isSpecialAction()) {
                // Only when THIS step recorded one. A refused, not-found or critic-blocked step
                // records nothing, and attributing the previous step's handle, label and hash to
                // it made the ops page say a tool ran that never did.
                //
                // Claimed, not counted. The first attempt compared the store's size against a
                // count the caller had just derived from that same store on the same thread:
                // one expression evaluated twice, always equal, so the guard excluded nothing
                // and the misattribution it was written to stop carried on unchanged.
                claimed = context.lastArtifact().filter(a -> context.claimArtifact(a.n()));
                claimed.ifPresent(a -> {
                    details.put("artifact", a.handle());
                    details.put("label", a.label().name());
                    details.put("chars", a.output().length());
                    details.put("sha256_16", com.ownclaw.llm.CloudGateway.sha256_16(a.output()));
                    if (!a.why().isEmpty()) details.put("why", a.why());
                });
            }
            stepOutcome(details, obs, claimed);

            eventLog.log(context.userId(), context.taskId(), "step",
                    obs.success() ? "info" : "warn",
                    action.tool() + (obs.success() ? " ok" : " FAILED")
                            + " (" + obs.durationMs() + "ms)",
                    JSON.writeValueAsString(details), 0);
        } catch (Exception e) {
            log.debug("Could not persist step {} of task {}: {}",
                    step, context.taskId(), e.getMessage());
        }
    }

    /**
     * What a step's row says about how it went, beyond success: whether its result is indexed
     * for the canary, whether the skill reported a failure the loop counted as success, and --
     * for a failed step -- an excerpt of how it failed.
     * <p>
     * The excerpt is the observation the cloud model is shown next, so it holds no class of text
     * that does not already leave; and none at all for a PRIVATE step, whose observation is its
     * descriptor. Without it a failure's reason reached only the log and the model -- this
     * morning's "context window full" delegation left a row that said FAILED and nothing else.
     */
    static void stepOutcome(Map<String, Object> details, AgentObservation obs,
                            java.util.Optional<Artifact> claimed) {
        claimed.ifPresent(a -> details.put("indexed", a.indexed()));
        boolean reported = claimed.map(a -> a.success() && !a.succeeded()).orElse(false);
        if (reported) details.put("reportedFailure", true);
        boolean privateStep = claimed.map(Artifact::isPrivate).orElse(false);
        if ((!obs.success() || reported) && !privateStep) {
            details.put("reason", failureExcerpt(obs.output()));
        }
    }

    /** The head and the tail: the first line says what failed, the last says why. */
    static String failureExcerpt(String text) {
        if (text == null) return "";
        if (text.length() <= 400) return text;
        return text.substring(0, 200) + "\n…\n" + text.substring(text.length() - 200);
    }

    /** An attachment's row: what it is and how it is labelled -- never its text. */
    static Map<String, Object> attachmentDetails(Artifact a) {
        var d = new LinkedHashMap<String, Object>();
        d.put("artifact", a.handle());
        d.put("tool", a.tool());
        d.put("label", a.label().name());
        d.put("chars", a.output().length());
        d.put("indexed", a.indexed());
        if (!a.why().isEmpty()) d.put("why", a.why());
        return d;
    }

    /** Emit observation detail: success/fail status, duration, output preview. */
    private void emitObserveDetail(String userId, AgentAction action,
                                    AgentObservation obs, int step, AgentContext context) {
        var detail = new LinkedHashMap<String, Object>();
        detail.put("category", "observe");
        detail.put("step", step);
        detail.put("tool", action.tool());
        detail.put("success", obs.success());
        detail.put("durationMs", obs.durationMs());
        detail.put("output", truncate(obs.output(), 1000));

        // Stats snapshot
        int totalSteps = context.trajectory().size();
        int successes = (int) context.trajectory().turns().stream()
                .filter(t -> t.observation().success()).count();
        detail.put("totalSteps", totalSteps);
        detail.put("successCount", successes);
        detail.put("cloudTokens", context.cloudTokens());
        detail.put("localTokens", context.localTokens());
        detail.put("elapsedMs", context.elapsedMs());

        String status = obs.success() ? "✓" : "✗";
        statusEmitter.emit(userId, new StatusMessage(StatusMessage.Type.STEP,
                "👁 Observe · " + action.tool() + " " + status + " " + formatDurationMs(obs.durationMs()),
                detail));
    }

    // ── Debug helpers ──

    private void emitDebug(String userId, String text) {
        statusEmitter.emit(userId, StatusMessage.Type.DEBUG, text);
    }

    /**
     * Emit the full prompt and raw LLM response for a thinking step.
     */
    private void emitDebugPrompt(String userId, ThinkResult result, int step) {
        var sb = new StringBuilder();
        sb.append("### Step ").append(step).append(" — Thinking\n\n");

        sb.append("**Prompt messages** (").append(result.promptMessages().size()).append("):\n");
        for (var msg : result.promptMessages()) {
            sb.append("\n---\n**[").append(msg.role().name()).append("]**\n");
            sb.append(msg.content()).append('\n');
        }

        sb.append("\n---\n**Raw LLM output** (").append(result.totalTokens()).append(" tokens):\n```json\n");
        String raw = result.rawLlmOutput();
        sb.append(raw != null ? raw : "(null)").append("\n```\n");

        sb.append("**Parsed action**: tool=`").append(result.action().tool())
                .append("` reasoning=").append(truncate(result.action().reasoning(), 300));

        emitDebug(userId, sb.toString());
    }

    // ── LLM heartbeat ──

    /**
     * Start a periodic heartbeat that emits PROGRESS status messages while
     * the LLM inference call is blocking. Keeps the UI activity indicator
     * alive so users know the system isn't hung.
     *
     * @param userId      target user for status messages
     * @param description what's happening (e.g. "Generating code")
     * @return a ScheduledFuture to cancel when the LLM call completes
     */
    /**
     * Tasks currently inside the loop, so a watchdog can see them.
     * <p>
     * The in-loop stall check cannot fire. It runs at the top of the iteration and
     * {@code markProgress()} is called at the end of every branch below it, so
     * {@code msSinceLastProgress()} is a few microseconds old by the time it is read. Worse,
     * that is the wrong place entirely: a task that hangs is hanging INSIDE a step -- in a tool
     * call, or a local model call that never returns -- and while it does, the loop never
     * reaches the top of the next iteration to check anything at all. A check on the stuck
     * thread can only run when the thread is not stuck.
     */
    private final Map<String, AgentContext> inFlight = new ConcurrentHashMap<>();

    /**
     * Cancel tasks that have stopped making progress.
     *
     * <p>Runs on the scheduler, not on the task's own thread, which is the whole point. When a
     * task has not marked progress for longer than the stall timeout it is asked to cancel
     * through the ordinary mechanism -- the same flag the Stop button sets -- so it unwinds the
     * way any cancelled task does, emits a proper outcome and releases its permits.
     *
     * <p>Honest about its limits: cancellation is cooperative. A task blocked in a socket read
     * cannot notice until that read returns, so this bounds a stall by the stall timeout PLUS
     * whatever the in-flight call takes to give up -- for Ollama, up to its 600 s read timeout.
     * That is a real improvement on never noticing, and it is not a kill switch. Making it one
     * would mean interrupting threads mid-call, which risks leaving a half-written skill
     * directory or a dangling sandbox process behind.
     */
    /**
     * Whether a task has stalled long enough to be cancelled.
     *
     * @param alreadyAsked a second request would only re-log; the task has not noticed the first
     *                     yet, and asking again does not make it notice sooner
     */
    static boolean shouldCancelForStall(long idleMs, long stallTimeoutMs, boolean alreadyAsked) {
        if (alreadyAsked) return false;
        if (stallTimeoutMs <= 0) return false;   // disabled
        return idleMs > stallTimeoutMs;
    }

    @Scheduled(fixedDelay = 30_000L)
    public void cancelStalledTasks() {
        long stallTimeoutMs = config.getTasks().getStallTimeout() * 1000L;
        for (var entry : inFlight.entrySet()) {
            AgentContext ctx = entry.getValue();
            long idle = ctx.msSinceLastProgress();
            boolean asked = cancellationService.isCancelled(
                    ctx.userId(), entry.getKey(), ctx.startTimeMs());
            if (!shouldCancelForStall(idle, stallTimeoutMs, asked)) continue;
            log.warn("Task {} has made no progress for {}s (limit {}s) — requesting cancellation. "
                            + "It will stop at its next checkpoint; a call already in flight has "
                            + "to return first.",
                    entry.getKey(), idle / 1000, stallTimeoutMs / 1000);
            cancellationService.request(ctx.userId(), entry.getKey());
            statusEmitter.emitForTask(ctx.userId(), entry.getKey(), StatusMessage.Type.WARNING,
                    "No progress for " + (idle / 1000) + "s — stopping this task.");
        }
    }

    /**
     * One scheduler for every heartbeat in the process.
     * <p>
     * This used to be created per call, and only the ScheduledFuture was returned. Cancelling a
     * future does not shut down the executor that owns it, so each LLM call and each tool call
     * left a live {@code llm-heartbeat} thread parked forever. On a server that runs two
     * scheduled tasks a day plus interactive chat, at up to twenty steps a task, that is
     * thousands of threads and their stacks — an ordinary day's work would eventually exhaust
     * the process. Nothing surfaced it because the threads are daemons and idle.
     * <p>
     * A shared pool makes cancel() sufficient: the future stops, the threads stay and are reused.
     */
    private static final ScheduledExecutorService HEARTBEAT_SCHEDULER =
            Executors.newScheduledThreadPool(2, r -> {
                Thread t = new Thread(r, "llm-heartbeat");
                t.setDaemon(true);
                return t;
            });

    private ScheduledFuture<?> startLlmHeartbeat(String userId, String description) {
        ScheduledExecutorService scheduler = HEARTBEAT_SCHEDULER;
        long[] startMs = { System.currentTimeMillis() };
        return scheduler.scheduleAtFixedRate(() -> {
            long elapsed = (System.currentTimeMillis() - startMs[0]) / 1000;
            String time;
            if (elapsed < 60) {
                time = elapsed + "s";
            } else {
                time = (elapsed / 60) + "m " + (elapsed % 60) + "s";
            }
            // Emit as PROGRESS so the frontend updates the last step label
            // rather than adding a new step entry.
            statusEmitter.emit(userId, StatusMessage.Type.PROGRESS,
                    description + " (" + time + ")");
        }, 30, 20, TimeUnit.SECONDS);    // first tick at 30s, then every 20s
    }

    private void stopHeartbeat(ScheduledFuture<?> heartbeat) {
        if (heartbeat != null) {
            heartbeat.cancel(false);
        }
    }
}
