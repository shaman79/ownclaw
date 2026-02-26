package com.ownclaw.agent;

import com.ownclaw.agent.memory.AgentMemory;
import com.ownclaw.agent.tools.*;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.llm.LlmProvider;
import com.ownclaw.observability.ChatStatusEmitter;
import com.ownclaw.observability.ChatStatusEmitter.StatusMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
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

    public AgentLoop(
            ThinkingEngine thinkingEngine,
            CriticAgent criticAgent,
            ToolRegistry toolRegistry,
            ChatStatusEmitter statusEmitter,
            OwnClawConfig config,
            LlmRouter llmRouter,
            AgentMemory memory
    ) {
        this.thinkingEngine = thinkingEngine;
        this.criticAgent = criticAgent;
        this.toolRegistry = toolRegistry;
        this.statusEmitter = statusEmitter;
        this.config = config;
        this.llmRouter = llmRouter;
        this.memory = memory;
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
        String taskId = UUID.randomUUID().toString().substring(0, 8);
        AgentContext context = new AgentContext(userId, taskId, message);

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

        statusEmitter.emit(userId, StatusMessage.Type.STARTED, "Processing your request...");

        try {
            AgentResult result = runLoop(context);
            emitResult(userId, result);

            // Store this execution as an episodic memory
            storeEpisode(context, result);

            return result.response();
        } catch (Exception e) {
            log.error("AgentLoop fatal error for user={} task={}: {}", userId, taskId, e.getMessage(), e);
            statusEmitter.emit(userId, StatusMessage.Type.FAILED, "An unexpected error occurred.");
            return "I encountered an unexpected error while processing your request. Please try again.";
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
            // Check cancellation
            if (context.isCancelled()) {
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

            AgentAction action = thinkingEngine.decideNextAction(context, provider);
            log.info("Task {} step {}: tool={} reasoning={}",
                    context.taskId(), step + 1, action.tool(),
                    truncate(action.reasoning(), 100));

            // === RESPOND / ASK ===
            if (action.isResponse()) {
                return AgentResult.completed(
                        action.responseText(),
                        context.trajectory(),
                        context.elapsedMs()
                );
            }

            if (action.isAskUser()) {
                // For now, deliver the question as the response.
                // A more sophisticated version would pause and wait for user input.
                return AgentResult.completed(
                        action.responseText(),
                        context.trajectory(),
                        context.elapsedMs()
                );
            }

            // === CRITIQUE ===
            CriticAgent.Verdict verdict = criticAgent.evaluate(action, context);
            if (!verdict.allowed()) {
                log.warn("Task {} step {} blocked by critic: {}", context.taskId(), step + 1, verdict.blockReason());
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

            if (observation.success()) {
                statusEmitter.emit(context.userId(), StatusMessage.Type.PROGRESS,
                        action.tool() + " completed (" + observation.durationMs() + "ms)");
            } else {
                statusEmitter.emit(context.userId(), StatusMessage.Type.WARNING,
                        action.tool() + " failed: " + truncate(observation.output(), 100));
            }
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
            return AgentObservation.failure(action.tool(), "Tool not found: " + action.tool(), 0);
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

    private void emitResult(String userId, AgentResult result) {
        if (result.success()) {
            statusEmitter.emit(userId, StatusMessage.Type.COMPLETED,
                    "Task completed in " + result.totalSteps() + " steps (" +
                            result.totalDurationMs() + "ms)");
        } else {
            statusEmitter.emit(userId, StatusMessage.Type.FAILED,
                    "Task ended: " + result.terminationReason());
        }
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
