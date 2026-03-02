package com.ownclaw.agent;

import com.ownclaw.agent.memory.AgentMemory;
import com.ownclaw.agent.tools.*;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.conversation.ConversationService;
import com.ownclaw.core.TaskCancellationService;
import com.ownclaw.llm.LlmProvider;
import com.ownclaw.observability.ChatStatusEmitter;
import com.ownclaw.observability.ChatStatusEmitter.StatusMessage;
import com.ownclaw.observability.DebugSessionService;
import com.ownclaw.users.CredentialVault;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.ownclaw.llm.*;

import java.util.*;
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
            ConversationService conversationService
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

        // Clear any stale cancel flag from a previous task
        cancellationService.clear(userId);

        statusEmitter.emit(userId, StatusMessage.Type.STARTED, "Processing your request...");

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
        long timeoutMs = config.getTasks().getDefaultTimeout() * 1000L;

        for (int step = 0; step < maxSteps; step++) {
            // Check cancellation — both local flag and service flag from WebSocket cancel button
            if (context.isCancelled() || cancellationService.isCancelled(context.userId())) {
                log.info("Task {} cancelled by user", context.taskId());
                return AgentResult.cancelled(
                        "Task was cancelled.",
                        context.trajectory(),
                        context.elapsedMs()
                );
            }

            // Check timeout
            if (context.elapsedMs() > timeoutMs) {
                log.warn("Task {} timed out after {}ms", context.taskId(), context.elapsedMs());
                return AgentResult.timeout(
                        "I ran out of time working on this task. Here's what I found so far:\n" +
                                summarizeProgress(context),
                        context.trajectory(),
                        context.elapsedMs()
                );
            }

            // === THINK ===
            LlmProvider provider = llmRouter.selectProvider(context);
            statusEmitter.emit(context.userId(), StatusMessage.Type.STEP,
                    "Thinking... (step " + (step + 1) + ")");

            boolean debug = debugService.isEnabled(context.userId());

            ThinkResult thinkResult = thinkingEngine.decideNextActionFull(context, provider);
            AgentAction action = thinkResult.action();

            // Track token usage per provider
            if ("ollama".equals(provider.name())) {
                context.addLocalTokens(thinkResult.totalTokens());
            } else {
                context.addCloudTokens(thinkResult.totalTokens());
            }

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

                if (isFallback && step < maxSteps - 1) {
                    log.warn("Task {} step {}: LLM produced fallback response ('{}'), retrying...",
                            context.taskId(), step + 1, truncate(action.responseText(), 80));
                    // Record this as a failed thinking step so the LLM sees it in trajectory
                    AgentObservation failedThink = AgentObservation.failure(
                            "_thinking",
                            "LLM failed to produce a valid action. The model may be confused by the current context. " +
                            "Try a different approach or tool.",
                            0);
                    context.trajectory().record(action, failedThink);
                    continue;
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
                statusEmitter.emit(context.userId(), StatusMessage.Type.STEP,
                        "Creating skill '" + action.params().getOrDefault("name", "?") + "'...");

                // Generate skill code exclusively with cloud LLM — never use local model for code gen
                Map<String, Object> enhancedParams = generateSkillCodeWithCloud(action.params(), context);
                if (enhancedParams == null) {
                    long durationMs = System.currentTimeMillis() - System.currentTimeMillis();
                    String errMsg = "ERROR: Cloud LLM unavailable — cannot generate skill code. " +
                            "Skill creation requires the cloud provider.";
                    context.trajectory().record(action, AgentObservation.failure(action.tool(), errMsg, 0));
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
                if (debug) {
                    emitDebug(context.userId(),
                            "CREDENTIAL_MANAGE [" + action.params().getOrDefault("action", "?") + "] "
                                    + (ok ? "OK" : "FAIL") + " (" + durationMs + "ms)\n"
                                    + truncate(result, 500));
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
                continue;
            }

            if (verdict.hasWarnings()) {
                for (String warning : verdict.warnings()) {
                    log.info("Task {} critic warning: {}", context.taskId(), warning);
                }
            }

            // === ACT ===
            AgentObservation observation = executeTool(action, context);

            // === OBSERVE ===
            context.trajectory().record(action, observation);

            if (debug) {
                emitDebug(context.userId(),
                        "TOOL RESULT [" + action.tool() + "] "
                                + (observation.success() ? "OK" : "FAIL")
                                + " (" + observation.durationMs() + "ms)\n"
                                + truncate(observation.output(), 2000));
            }

            // Track tool usage for skill curation analytics
            curatorService.recordUsage(action.tool(), context.userId(), context.taskId(),
                    observation.success(), observation.durationMs());

            if (observation.success()) {
                statusEmitter.emit(context.userId(), StatusMessage.Type.PROGRESS,
                        action.tool() + " completed (" + observation.durationMs() + "ms)");
            } else {
                statusEmitter.emit(context.userId(), StatusMessage.Type.WARNING,
                        action.tool() + " failed: " + truncate(observation.output(), 100));
            }

            // Inject reflection after consecutive failures OR consecutive hollow results
            injectReflection(context, action);
        }

        // Exhausted max steps
        log.warn("Task {} hit max steps ({})", context.taskId(), maxSteps);
        return AgentResult.maxSteps(
                "I've reached the maximum number of steps for this task. Here's what I found:\n" +
                        summarizeProgress(context),
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
                    : "Available tools: " + available + ". Use skill_create to build a new tool if none of these fit.";
            return AgentObservation.failure(action.tool(),
                    "Tool '" + action.tool() + "' not found. " + hint, 0);
        }

        Tool tool = toolOpt.get();
        ToolExecutionContext execCtx = new ToolExecutionContext(
                context.userId(),
                context.taskId(),
                null, // workDir — can be extended later
                context::isCancelled
        );

        long startMs = System.currentTimeMillis();
        try {
            ToolResult result = tool.execute(action.params(), execCtx);
            long durationMs = System.currentTimeMillis() - startMs;

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
                    "to find the bug, then fix it with skill_create. Or try a fundamentally different strategy.";
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
        sb.append("I took ").append(trajectory.size()).append(" actions (")
                .append(successCount).append(" successful).\n");

        // Include the last successful observation's output as the partial result
        for (int i = trajectory.turns().size() - 1; i >= 0; i--) {
            var turn = trajectory.turns().get(i);
            if (turn.observation().success() && turn.observation().output() != null
                    && !turn.observation().output().isBlank()) {
                sb.append("\nLast successful result:\n");
                String output = turn.observation().output();
                if (output.length() > 1000) {
                    output = output.substring(0, 1000) + "...[truncated]";
                }
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
        if (result.success()) {
            statusEmitter.emit(userId, StatusMessage.Type.COMPLETED,
                    "Task completed in " + result.totalSteps() + " steps (" +
                            result.totalDurationMs() + "ms)");
        } else {
            statusEmitter.emit(userId, StatusMessage.Type.FAILED,
                    "Task ended: " + result.terminationReason());
        }

        // Emit token usage summary as a status message
        int local = context.localTokens();
        int cloud = context.cloudTokens();
        if (local > 0 || cloud > 0) {
            StringBuilder sb = new StringBuilder("Tokens: ");
            if (cloud > 0) sb.append("Mentor ").append(String.format("%,d", cloud));
            if (cloud > 0 && local > 0) sb.append(" · ");
            if (local > 0) sb.append("Local ").append(String.format("%,d", local));
            sb.append(" · Total ").append(String.format("%,d", local + cloud));
            statusEmitter.emit(userId, StatusMessage.Type.STEP, sb.toString());
        }
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
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
                "Generating skill code with cloud LLM...");

        try {
            List<LlmMessage> messages = buildSkillCodePrompt(
                    name, description, parameters, requirements, context);

            LlmRequestConfig codeGenConfig = new LlmRequestConfig(
                    null,   // use provider default model
                    0.2,    // low temperature for precise code generation
                    8192,   // generous token budget for complete code
                    false   // no JSON mode — we want raw Python code
            );

            LlmResponse response = cloud.chat(messages, codeGenConfig);
            String cloudCode = extractPythonCode(response.content());

            // Track cloud tokens for skill code generation
            context.addCloudTokens(response.totalTokens());

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
            String requirements, AgentContext context) {

        List<LlmMessage> messages = new ArrayList<>();

        // System prompt: expert Python code generator
        var sys = new StringBuilder();
        sys.append("You are an expert Python developer generating production-quality code for a skill ");
        sys.append("in an autonomous agent system.\n\n");

        sys.append("## Skill Contract\n");
        sys.append("- The script MUST define `def run(params):` as the entry point.\n");
        sys.append("- `params` is a dict with the parameters defined in the skill spec.\n");
        sys.append("- The function MUST return a dict with an 'output' key containing the result string.\n");
        sys.append("- On failure, return `{'success': False, 'output': 'ERROR: <description>'}` — never raise unhandled exceptions.\n");
        sys.append("- ALWAYS include 'success': True or 'success': False in the returned dict. Do NOT omit it.\n\n");

        sys.append("## Quality Standards\n");
        sys.append("- **Encoding (MANDATORY)**: Character encoding is the #1 source of bugs. You MUST follow this pattern:\n");
        sys.append("  ```python\n");
        sys.append("  response = requests.get(url, ...)\n");
        sys.append("  response.encoding = response.apparent_encoding  # ALWAYS set this before using response.text\n");
        sys.append("  text = response.text\n");
        sys.append("  ```\n");
        sys.append("  Without this line, the `requests` library defaults to ISO-8859-1 for HTML, causing mojibake.\n");
        sys.append("  This is NON-NEGOTIABLE — every HTTP fetch skill MUST include this line.\n");
        sys.append("- **Content types**: Detect and handle different content types (HTML, PDF, JSON, XML, ");
        sys.append("plain text, binary). Check Content-Type headers and file extensions.\n");
        sys.append("- **HTML processing**: Use BeautifulSoup to extract clean, readable text. Strip scripts, ");
        sys.append("styles, and navigation boilerplate. Preserve document structure (headings, lists, tables).\n");
        sys.append("- **CRITICAL — Return ALL content**: NEVER filter or select specific sections of a web page. ");
        sys.append("Return the COMPLETE readable text content of the page. Modern web pages use tabs, ");
        sys.append("accordions, and hidden sections that contain important data in the HTML source. ");
        sys.append("Use soup.get_text() on the ENTIRE body — do NOT select individual divs or sections. ");
        sys.append("The agent will analyze and filter the relevant parts from the full output.\n");
        sys.append("- **Link extraction**: For HTML, ALWAYS extract and include navigation links (hrefs) ");
        sys.append("at the end of the output under a '## Links' section. Format: `[link text](url)`. ");
        sys.append("Resolve relative URLs to absolute URLs using urllib.parse.urljoin. ");
        sys.append("This is CRITICAL — the agent navigates websites by following these links.\n");
        sys.append("- **Error handling**: Catch all exceptions. Report HTTP status codes, connection errors, ");
        sys.append("and timeouts clearly. Never let the skill crash.\n");
        sys.append("- **Large content**: If output might exceed 10KB, truncate intelligently — return the ");
        sys.append("most relevant portion with a note about truncation.\n");
        sys.append("- **Network**: Set reasonable timeouts (10-30s). Use proper User-Agent headers. ");
        sys.append("Follow redirects.\n");
        sys.append("- **Robustness**: Handle edge cases — empty responses, invalid URLs, missing data, ");
        sys.append("unexpected formats. The skill must work reliably across diverse inputs.\n\n");

        sys.append("## Credentials\n");
        sys.append("- If the skill needs API keys, passwords, or tokens, read them from environment variables.\n");
        sys.append("- Use `os.environ.get('CREDENTIAL_NAME')` — NEVER hardcode secrets.\n");
        sys.append("- The agent framework injects credential env vars automatically based on the skill's ");
        sys.append("SKILL.yaml `credentials` list.\n");
        sys.append("- If a required credential is missing, return a clear error telling the user to store it ");
        sys.append("(e.g. \"ERROR: Missing credential 'GMAIL_APP_PASSWORD'. Please store it first.\").\n\n");

        sys.append("## Output Format\n");
        sys.append("Return ONLY the Python code inside a ```python code fence. No explanations before or after.\n");
        sys.append("You MUST ALWAYS include a ```requirements fence after the code listing ALL third-party ");
        sys.append("pip packages the code needs (one per line). Do NOT include Python standard library modules. ");
        sys.append("Use the correct pip package name — e.g. `beautifulsoup4` not `bs4`, `Pillow` not `PIL`, ");
        sys.append("`PyMuPDF` not `fitz`, `scikit-learn` not `sklearn`. If no third-party packages are needed, ");
        sys.append("include an empty ```requirements fence.\n");

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

        // Try to find ```python ... ``` fence
        int start = response.indexOf("```python");
        if (start >= 0) {
            start = response.indexOf('\n', start) + 1;
            int end = response.indexOf("```", start);
            if (end > start) {
                return response.substring(start, end).strip();
            }
        }

        // Try plain ``` fence
        start = response.indexOf("```");
        if (start >= 0) {
            start = response.indexOf('\n', start) + 1;
            int end = response.indexOf("```", start);
            if (end > start) {
                return response.substring(start, end).strip();
            }
        }

        // If the response looks like raw Python code (starts with import or def), use it directly
        String trimmed = response.strip();
        if (trimmed.startsWith("import ") || trimmed.startsWith("from ") || trimmed.startsWith("def ")) {
            return trimmed;
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
            String content = msg.content();
            if (content.length() > 4000) {
                content = content.substring(0, 4000) + "\n\n...[truncated, " + msg.content().length() + " chars total]";
            }
            sb.append(content).append('\n');
        }

        sb.append("\n---\n**Raw LLM output** (").append(result.totalTokens()).append(" tokens):\n```json\n");
        String raw = result.rawLlmOutput();
        if (raw != null && raw.length() > 2000) {
            raw = raw.substring(0, 2000) + "\n...[truncated]";
        }
        sb.append(raw != null ? raw : "(null)").append("\n```\n");

        sb.append("**Parsed action**: tool=`").append(result.action().tool())
                .append("` reasoning=").append(truncate(result.action().reasoning(), 300));

        emitDebug(userId, sb.toString());
    }
}
