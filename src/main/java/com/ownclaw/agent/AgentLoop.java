package com.ownclaw.agent;

import com.ownclaw.agent.memory.AgentMemory;
import com.ownclaw.agent.tools.*;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.conversation.ConversationService;
import com.ownclaw.core.LongRunningTaskManager;
import com.ownclaw.core.ScheduledTaskService;
import com.ownclaw.core.TaskCancellationService;
import com.ownclaw.core.TokenBudgetTracker;
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
    private final EventLogService eventLog;
    private final ScheduledTaskService scheduledTaskService;
    private final LocalExecutor localExecutor;

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
            LocalExecutor localExecutor
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
        try {
            AgentResult result = executeFull(userId, message);
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
        String taskId = UUID.randomUUID().toString().substring(0, 8);
        AgentContext context = new AgentContext(userId, taskId, message);

        // Load conversation history so the LLM sees prior exchanges
        loadConversationContext(context, userId);

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
        } catch (Exception e) {
            log.debug("Failed to load credential keys for user {}: {}", userId, e.getMessage());
        }

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
        cancellationService.clear(userId);

        AgentResult result = runLoop(context);
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
    private void loadConversationContext(AgentContext context, String userId) {
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
            if (recent.size() > 1) {
                sb.append("### Recent conversation\n");
                for (int i = recent.size() - 1; i >= 1; i--) {
                    Map<String, Object> row = recent.get(i);
                    String role = (String) row.get("role");
                    String content = (String) row.get("content");
                    sb.append(role.toUpperCase()).append(": ").append(content).append("\n");
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
    private AgentResult runLoop(AgentContext context) {
        int maxSteps = config.getTasks().getMaxPlanSteps();
        long stallTimeoutMs = config.getTasks().getStallTimeout() * 1000L;
        int consecutiveFallbacks = 0; // Track consecutive LLM failures to cap retries
        int totalThinkingFailures = 0; // Track total thinking failures across entire task

        for (int step = 0; step < maxSteps; step++) {
            // Check cancellation — both local flag and service flag from WebSocket cancel button
            if (context.isCancelled() || cancellationService.isCancelled(context.userId())) {
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

            // Check stall — no forward progress for stall-timeout seconds
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

                statusEmitter.emit(context.userId(), StatusMessage.Type.STEP,
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
                    context.trajectory().record(action, obs);
                    context.markProgress();
                    if (debug) {
                        emitDebug(context.userId(),
                                "SKILL_CREATE [" + hint.suggestedName() + "] "
                                        + (ok ? "OK" : "FAIL") + " (" + durationMs + "ms)\n"
                                        + truncate(result, 2000));
                    }
                } else {
                    context.trajectory().record(action,
                            AgentObservation.failure(action.tool(),
                                    "ERROR: Cloud LLM unavailable — cannot generate skill code", 0));
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
            statusEmitter.emit(context.userId(), StatusMessage.Type.STEP,
                    "Step " + (step + 1) + " · " + providerLabel,
                    tokenData(context));

            ScheduledFuture<?> thinkHeartbeat = startLlmHeartbeat(context.userId(),
                    "Step " + (step + 1) + " · " + providerLabel);
            ThinkResult thinkResult;
            try {
                thinkResult = thinkingEngine.decideNextActionFull(context, provider);
            } finally {
                stopHeartbeat(thinkHeartbeat);
            }
            context.markProgress(); // LLM responded — task is alive
            AgentAction action = thinkResult.action();

            // Track token usage per provider
            if (local) {
                context.addLocalTokens(thinkResult.totalTokens());
            } else {
                context.addCloudTokens(thinkResult.totalTokens());
                // Persist cloud usage for budget tracking
                if (thinkResult.totalTokens() > 0) {
                    budgetTracker.recordUsage(context.userId(), provider.name(),
                            thinkResult.totalTokens(), 0.0);
                }
            }

            // Emit running token totals so the frontend can update the live counter
            statusEmitter.emit(context.userId(), StatusMessage.Type.PROGRESS,
                    action.tool() + " (" + String.format("%,d", thinkResult.totalTokens()) + " tok)",
                    tokenData(context));

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
                        || reasoning.startsWith("LLM call failed");

                if (isFallback) {
                    consecutiveFallbacks++;
                    totalThinkingFailures++;

                    // After 3 consecutive failures, stop burning tokens and give up
                    if (consecutiveFallbacks >= 3) {
                        log.error("Task {} step {}: {} consecutive LLM failures — aborting task",
                                context.taskId(), step + 1, consecutiveFallbacks);
                        String progress = summarizeProgress(context);
                        return AgentResult.completed(
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
                        return AgentResult.completed(
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
                        feedback.append("YOUR OUTPUT COULD NOT BE PARSED. Here is what you produced:\n");
                        feedback.append(truncate(rawOutput, 500));
                        feedback.append("\n\nThis was NOT valid. You MUST respond with a JSON object containing ");
                        feedback.append("exactly these fields:\n");
                        feedback.append("{\n  \"tool\": \"<tool_name>\",\n  \"params\": {<param_key>: <param_value>},");
                        feedback.append("\n  \"reasoning\": \"<why>\"\n}\n");
                        feedback.append("Or to respond to the user:\n");
                        feedback.append("{\n  \"tool\": \"respond\",\n  \"params\": {\"message\": \"<your response>\"},");
                        feedback.append("\n  \"reasoning\": \"<why>\"\n}");

                        if (consecutiveFallbacks >= 2) {
                            feedback.append("\n\nWARNING: This is your ").append(consecutiveFallbacks)
                                    .append("th consecutive failure. ONE more and the task will be aborted.");
                        }

                        AgentObservation failedThink = AgentObservation.failure(
                                "_thinking", feedback.toString(), 0);
                        context.trajectory().record(action, failedThink);
                        context.markProgress(); // LLM produced output (even if malformed)
                        continue;
                    }
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
                return AgentResult.completed(
                        action.responseText(),
                        context.trajectory(),
                        context.elapsedMs()
                );
            }

            // === SKILL MANAGEMENT (special actions — always available) ===
            if (action.isSkillCreate()) {
                String skillName = str(action.params(), "name");

                // --- Skill-create retry guard ---
                // Count how many times we've already tried to create this skill (or any skill)
                // in this task. After 2 failed attempts for the same name, force the LLM
                // to abandon this approach instead of burning cloud tokens.
                int sameNameFails = 0;
                int totalSkillFails = 0;
                for (var turn : context.trajectory().turns()) {
                    if (AgentAction.SKILL_CREATE.equals(turn.action().tool()) && !turn.observation().success()) {
                        totalSkillFails++;
                        String prevName = str(turn.action().params(), "name");
                        if (skillName != null && skillName.equals(prevName)) sameNameFails++;
                    }
                }
                if (sameNameFails >= 2) {
                    String msg = "ERROR: Skill '" + skillName + "' has failed " + sameNameFails
                            + " times with syntax errors. Do NOT try creating it again. "
                            + "Use a different approach: break the problem into smaller skills, "
                            + "use shell_exec directly, or simplify your requirements.";
                    context.trajectory().record(action, AgentObservation.failure(action.tool(), msg, 0));
                    context.markProgress();
                    log.warn("Task {} step {}: blocked repeated skill_create for '{}' ({} fails)",
                            context.taskId(), step + 1, skillName, sameNameFails);
                    continue;
                }
                if (totalSkillFails >= 3) {
                    String msg = "ERROR: " + totalSkillFails + " skill creation attempts have failed. "
                            + "Stop creating skills. Use shell_exec or simpler existing tools instead.";
                    context.trajectory().record(action, AgentObservation.failure(action.tool(), msg, 0));
                    context.markProgress();
                    log.warn("Task {} step {}: blocked skill_create after {} total failures",
                            context.taskId(), step + 1, totalSkillFails);
                    continue;
                }

                statusEmitter.emit(context.userId(), StatusMessage.Type.STEP,
                        "Creating skill '" + action.params().getOrDefault("name", "?") + "'...");

                // Generate skill code exclusively with cloud LLM — never use local model for code gen
                Map<String, Object> enhancedParams = generateSkillCodeWithCloud(action.params(), context);
                if (enhancedParams == null) {
                    String errMsg = "ERROR: Cloud LLM unavailable — cannot generate skill code. " +
                            "Skill creation requires the cloud provider.";
                    context.trajectory().record(action, AgentObservation.failure(action.tool(), errMsg, 0));
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
                context.trajectory().record(action, obs);
                context.markProgress();
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
                    context.trajectory().record(action, blockObs);
                    context.markProgress();
                    continue;
                }

                long startMs = System.currentTimeMillis();
                String result = executeSkillManage(action.params());
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
                context.trajectory().record(action, obs);
                context.markProgress();
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
                context.trajectory().record(action, obs);
                context.markProgress();
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
                context.trajectory().record(action, obs);
                context.markProgress();

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
                context.trajectory().record(action, obs);
                context.markProgress();
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
                    context.trajectory().record(action, obs);
                    context.markProgress();
                    continue;
                }

                log.info("Task {} step {}: delegating to local LLM — goal: {}, steps: {}, max: {}",
                        context.taskId(), step + 1, truncate(plan.goal(), 100),
                        plan.steps().size(), plan.maxSteps());

                String result = localExecutor.execute(plan, context);
                long durationMs = System.currentTimeMillis() - startMs;
                boolean ok = !result.startsWith("ERROR") && !result.startsWith("Delegation incomplete");
                AgentObservation obs = ok
                        ? AgentObservation.success(action.tool(), result, Map.of(), durationMs)
                        : AgentObservation.failure(action.tool(), result, durationMs);
                context.trajectory().record(action, obs);
                context.markProgress();
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
                context.trajectory().record(action, blockObs);
                context.markProgress();
                continue;
            }

            if (verdict.hasWarnings()) {
                for (String warning : verdict.warnings()) {
                    log.info("Task {} critic warning: {}", context.taskId(), warning);
                }
            }

            // === ACT ===
            statusEmitter.emit(context.userId(), StatusMessage.Type.STEP,
                    "Running " + action.tool() + "...");
            ScheduledFuture<?> toolHeartbeat = startLlmHeartbeat(context.userId(),
                    "Running " + action.tool());
            AgentObservation observation;
            try {
                observation = executeTool(action, context);
            } finally {
                stopHeartbeat(toolHeartbeat);
            }

            // === OBSERVE ===
            context.trajectory().record(action, observation);
            context.markProgress(); // tool completed — task is alive

            if (debug) {
                emitDebug(context.userId(),
                        "TOOL RESULT [" + action.tool() + "] "
                                + (observation.success() ? "OK" : "FAIL")
                                + " (" + observation.durationMs() + "ms)\n"
                                + truncate(observation.output(), 50_000));
            }

            // Track tool usage for skill curation analytics
            curatorService.recordUsage(action.tool(), context.userId(), context.taskId(),
                    observation.success(), observation.durationMs());

            if (observation.success()) {
                statusEmitter.emit(context.userId(), StatusMessage.Type.PROGRESS,
                        action.tool() + " ✓ " + formatDurationMs(observation.durationMs()),
                        tokenData(context));
            } else {
                statusEmitter.emit(context.userId(), StatusMessage.Type.WARNING,
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
        return AgentResult.completed(
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
                progressCallback
        );

        long startMs = System.currentTimeMillis();
        try {
            ToolResult result = tool.execute(action.params(), execCtx);
            long durationMs = System.currentTimeMillis() - startMs;

            // If this tool was tracked as long-running, finalize it
            if (longRunningTaskManager.isActive(context.taskId())) {
                if (result.success()) {
                    longRunningTaskManager.complete(context.taskId(),
                            truncate(result.output(), 200));
                } else {
                    longRunningTaskManager.fail(context.taskId(),
                            truncate(result.output(), 200));
                }
            }

            if (result.success()) {
                return AgentObservation.success(
                        action.tool(),
                        result.output(),
                        result.structured(),
                        durationMs
                );
            } else {
                return AgentObservation.failure(
                        action.tool(),
                        result.output(),
                        result.structured(),
                        durationMs
                );
            }
        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startMs;
            log.error("Tool '{}' threw exception: {}", action.tool(), e.getMessage(), e);

            // Finalize as failed if tracked
            if (longRunningTaskManager.isActive(context.taskId())) {
                longRunningTaskManager.fail(context.taskId(), e.getMessage());
            }

            return AgentObservation.failure(
                    action.tool(),
                    "Tool execution error: " + e.getMessage(),
                    durationMs
            );
        }
    }

    /**
     * Dispatch a skill_manage action to the appropriate SkillManager method.
     */
    private String executeSkillManage(Map<String, Object> params) {
        String action = params.get("action") != null ? params.get("action").toString() : "";
        String name = params.get("name") != null ? params.get("name").toString() : null;

        return switch (action) {
            case "read" -> skillManager.readSkill(name);
            case "delete" -> skillManager.deleteSkill(name);
            case "list" -> skillManager.listSkills();
            case "analyze" -> skillManager.analyzeSkills();
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
        String value = params.get("value") != null ? params.get("value").toString().strip() : null;

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
            case "store" -> {
                if (key == null || key.isBlank()) {
                    yield "ERROR: 'key' parameter is required for action='store'";
                }
                if (value == null || value.isBlank()) {
                    yield "ERROR: 'value' parameter is required for action='store'";
                }
                try {
                    credentialVault.storeCredential(userId, key, value);
                    yield "Credential '" + key + "' stored securely (AES-256-GCM encrypted).";
                } catch (Exception e) {
                    log.error("Failed to store credential '{}': {}", key, e.getMessage());
                    yield "ERROR: Failed to store credential: " + e.getMessage();
                }
            }
            default -> "ERROR: Unknown action '" + action + "'. Use one of: list, check, store";
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
     * When the cloud LLM has made 2+ consecutive calls to the same registered skill
     * (non-special tool), this strongly suggests routine execution that should be
     * delegated to the local LLM to save cloud tokens.
     *
     * The nudge is picked up by ThinkingEngine's buildDynamicContext() and rendered
     * as a cost warning in the prompt.
     */
    private void injectDelegationNudge(AgentContext context) {
        // Only nudge if local LLM is available (otherwise delegation would fail)
        if (!llmRouter.local().isAvailable()) {
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
                "You've called '%s' %d times in a row. This is EXACTLY what 'delegate' is for! "
                + "Bundle remaining calls into a single delegate action to save cloud tokens. "
                + "Each step you take costs expensive cloud LLM tokens — delegate costs ZERO.",
                repeatedTool, consecutive);
        } else {
            nudge = String.format(
                "You've made %d consecutive skill calls (%s) without needing reasoning between them. "
                + "Use 'delegate' to batch remaining tool calls to the FREE local LLM. "
                + "Each step you take costs expensive cloud tokens — delegate costs ZERO.",
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
        if (trouble < 2) return;

        String reflectionHint;
        if (trouble == 2) {
            reflectionHint = "REFLECTION: The last " + trouble + " tool calls " +
                    (failures >= 2 ? "failed" : "returned empty/useless output") + ". " +
                    "Reconsider your approach. Inspect the tool's code with skill_manage(action='read') " +
                    "to find the bug, then fix it with skill_create using the SAME name (overwrites in-place). Or try a fundamentally different strategy.";
        } else {
            reflectionHint = "REFLECTION: " + trouble + " consecutive " +
                    (failures >= trouble ? "failures" : "empty results") + ". " +
                    "STOP repeating the same approach. Read the skill code, fix it, or try " +
                    "a completely different technique. If nothing works, respond with what you know.";
        }

        // Record reflection as a synthetic observation so the ThinkingEngine sees it
        AgentAction reflectionAction = new AgentAction("_reflection", Map.of(), "System-injected reflection");
        AgentObservation reflectionObs = AgentObservation.success("_reflection", reflectionHint, Map.of(), 0);
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

        StatusMessage.Type type = result.success() ? StatusMessage.Type.COMPLETED : StatusMessage.Type.FAILED;
        statusEmitter.emit(userId, type, summary.toString(), tokenData(context));

        // Persist token usage to the events table for auditing
        try {
            String details = String.format(
                    "{\"cloudTokens\":%d,\"localTokens\":%d,\"steps\":%d,\"durationMs\":%d,\"reason\":\"%s\"}",
                    cloud, local, result.totalSteps(), result.totalDurationMs(), result.terminationReason());
            eventLog.log(userId, context.taskId(), "task_completed",
                    result.success() ? "info" : "warn",
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
        return Map.of(
                "cloudTokens", context.cloudTokens(),
                "localTokens", context.localTokens()
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
    private Map<String, Object> generateSkillCodeWithCloud(Map<String, Object> originalParams, AgentContext context) {
        LlmProvider cloud = llmRouter.cloud();
        if (!cloud.isAvailable()) {
            log.error("Cloud provider unavailable — cannot generate skill code");
            return null;
        }

        String name = str(originalParams, "name");
        String description = str(originalParams, "description");
        String parameters = str(originalParams, "parameters");
        String requirements = str(originalParams, "requirements");

        statusEmitter.emit(context.userId(), StatusMessage.Type.STEP,
                    "Generating skill code · cloud", tokenData(context));
        try {
            List<LlmMessage> messages = buildSkillCodePrompt(
                    name, description, parameters, requirements, originalParams, context);

            LlmRequestConfig codeGenConfig = new LlmRequestConfig(
                    null,   // use provider default model
                    0.2,    // low temperature for precise code generation
                    8192,   // generous token budget for complete code
                    false,  // no JSON mode — we want raw Python code
                    null    // use provider default read timeout
            );

            ScheduledFuture<?> heartbeat = startLlmHeartbeat(context.userId(),
                    "Generating code for '" + name + "'");
            LlmResponse response;
            try {
                response = cloud.chat(messages, codeGenConfig);
            } finally {
                stopHeartbeat(heartbeat);
            }
            String cloudCode = extractPythonCode(response.content());

            if (cloudCode == null || cloudCode.isBlank()) {
                log.warn("extractPythonCode returned null. Raw response (first 500 chars): {}",
                        response.content() == null ? "(null)"
                                : response.content().substring(0, Math.min(500, response.content().length())));
            }

            // Track cloud tokens for skill code generation
            context.addCloudTokens(response.totalTokens());
            if (response.totalTokens() > 0) {
                budgetTracker.recordUsage(context.userId(), cloud.name(),
                        response.totalTokens(), 0.0);
            }

            if (cloudCode != null && !cloudCode.isBlank()) {
                log.info("Cloud LLM generated {} chars of skill code for '{}' ({} tokens)",
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
     */
    private List<LlmMessage> buildSkillCodePrompt(
            String name, String description, String parameters,
            String requirements, Map<String, Object> originalParams, AgentContext context) {

        List<LlmMessage> messages = new ArrayList<>();

        // System prompt: expert Python code generator
        var sys = new StringBuilder();
        sys.append("You are an expert Python developer generating production-quality skill code.\n\n");

        sys.append("## Contract\n");
        sys.append("- Define `def run(params):` as entry point. `params` is a dict.\n");
        sys.append("- Return dict with 'output' (result string) and 'success' (True/False). Never raise unhandled exceptions.\n");
        sys.append("- On failure: `{'success': False, 'output': 'ERROR: <description>'}`\n\n");

        sys.append("## Quality Standards\n");
        sys.append("- **Encoding (MANDATORY)**: Always set `response.encoding = response.apparent_encoding` ");
        sys.append("before using `response.text`. Without this, requests defaults to ISO-8859-1 causing mojibake. NON-NEGOTIABLE.\n");
        sys.append("- **Content types**: Detect via Content-Type headers/extensions. Handle HTML, PDF, JSON, XML, plain text.\n");
        sys.append("- **HTML**: Use BeautifulSoup. Extract clean text with `soup.get_text()` on the ENTIRE body — ");
        sys.append("do NOT select individual sections. Strip scripts/styles/nav. Preserve headings, lists, tables.\n");
        sys.append("- **Links (CRITICAL)**: For HTML, extract ALL hrefs under a '## Links' section as `[text](url)`. ");
        sys.append("Resolve relative URLs with `urllib.parse.urljoin`.\n");
        sys.append("- **Errors**: Catch all exceptions. Report HTTP status codes, connection errors, timeouts clearly.\n");
        sys.append("- **Large content**: Truncate >10KB intelligently with truncation note.\n");
        sys.append("- **Network**: Timeouts 10-30s. Proper User-Agent. Follow redirects.\n");
        sys.append("- **Robustness**: Handle empty responses, invalid URLs, missing data, unexpected formats.\n\n");

        sys.append("## Local Command Execution\n");
        sys.append("Skills run LOCALLY on the user's machine with FULL system access.\n");
        sys.append("- **Shell commands**: Use `subprocess.run()` with `capture_output=True, text=True`. ");
        sys.append("Return stdout as output. Check returncode for errors.\n");
        sys.append("- **Platform awareness**: Check `sys.platform` ('win32', 'linux', 'darwin') and use appropriate commands.\n");
        sys.append("- **Common tools**: nmap, ping, arp, ip/ifconfig, netstat, curl, dig, traceroute, systemctl, etc.\n");
        sys.append("- **Filesystem**: Use `os`, `pathlib`, `shutil` for file/directory operations.\n");
        sys.append("- **Permissions**: Skills run as the OwnClaw service user. Use sudo only when needed and available.\n");
        sys.append("- **Security**: Never expose credentials in command args — use env vars or temp files with 0600 permissions.\n");
        sys.append("- **System packages**: If the skill description mentions system_packages (e.g. nmap, ffmpeg), ");
        sys.append("the skill will run inside a container where those packages are pre-installed. ");
        sys.append("Write the code as if the tools are available on PATH — they will be.\n\n");

        sys.append("## Credentials\n");
        sys.append("Read from env vars: `os.environ.get('KEY')`. Never hardcode secrets.\n");
        sys.append("Missing credential → return clear error telling user to store it.\n\n");

        sys.append("## Output Format\n");
        sys.append("Return ONLY Python code in a ```python fence, followed by a ```requirements fence ");
        sys.append("listing ALL third-party pip packages (one per line). Use correct pip names ");
        sys.append("(beautifulsoup4 not bs4, Pillow not PIL, PyMuPDF not fitz). Empty fence if no deps.\n\n");

        sys.append("## Code Quality Guidelines\n");
        sys.append("- Write clean, well-structured code. Decompose complex logic into small, focused helper functions.\n");
        sys.append("- Prefer existing libraries over reimplementing (e.g. python-nmap, not raw subprocess parsing).\n");
        sys.append("- Avoid overly defensive code with redundant edge-case handling — keep it focused and practical.\n");
        sys.append("- Use efficient algorithms and data structures. Avoid unnecessary loops or repeated operations.\n");
        sys.append("- Group related logic together. Each function should do one thing well.\n");

        messages.add(LlmMessage.system(sys.toString()));

        // User prompt: the skill specification
        var user = new StringBuilder();
        user.append("Generate the Python code for this skill:\n\n");
        user.append("**Name**: ").append(name).append("\n");
        user.append("**Description**: ").append(description).append("\n");
        user.append("**Parameters**: ").append(parameters).append("\n");
        if (requirements != null && !requirements.isBlank()) {
            user.append("**Available pip packages**: ").append(requirements).append("\n");
        }

        // Tell the code generator which env var names to use for credentials
        String credentials = str(originalParams, "credentials");
        if (credentials != null && !credentials.isBlank()) {
            user.append("**Credentials (auto-injected as env vars)**: ").append(credentials).append("\n");
            user.append("Read these with `os.environ['KEY']` — they are guaranteed to be present at runtime.\n");
        }

        // Include the task context so the cloud knows what the skill needs to accomplish
        user.append("\n**Context**: The agent is working on this task: \"");
        user.append(truncate(context.originalMessage(), 500));
        user.append("\"\n");

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
    private ScheduledFuture<?> startLlmHeartbeat(String userId, String description) {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "llm-heartbeat");
            t.setDaemon(true);
            return t;
        });
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
