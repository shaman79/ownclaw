package com.ownclaw.agent;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.ownclaw.agent.tools.*;
import com.ownclaw.llm.*;
import com.ownclaw.observability.ChatStatusEmitter;
import com.ownclaw.observability.ChatStatusEmitter.StatusMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Executes delegation plans from the cloud LLM using the local LLM.
 *
 * <p>The cloud LLM (orchestrator) creates a structured plan with specific tool calls,
 * then delegates execution to the local LLM via this executor. The local LLM follows
 * the plan, executes tools, chains results, and produces a consolidated summary.
 *
 * <p>This enables cost-efficient operation: the cloud LLM thinks once and creates a plan,
 * while the cheaper local LLM handles the mechanical tool execution.
 *
 * <h3>Mini Agent Loop</h3>
 * The executor runs its own Think→Act→Observe cycle:
 * <ol>
 *   <li>System prompt provides the plan and available tools</li>
 *   <li>Local LLM picks the next tool call following the plan</li>
 *   <li>Executor runs the tool and feeds result back</li>
 *   <li>Repeat until local LLM says "done" or max_steps reached</li>
 * </ol>
 */
@Component
public class LocalExecutor {

    private static final Logger log = LoggerFactory.getLogger(LocalExecutor.class);

    // Lenient JSON mapper — same config as ThinkingEngine for local model quirks
    private static final ObjectMapper mapper = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER)
            .enable(JsonReadFeature.ALLOW_UNQUOTED_FIELD_NAMES)
            .enable(JsonReadFeature.ALLOW_SINGLE_QUOTES)
            .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
            .build();

    private final LlmRouter llmRouter;
    private final ToolRegistry toolRegistry;
    private final ChatStatusEmitter statusEmitter;

    public LocalExecutor(LlmRouter llmRouter, ToolRegistry toolRegistry, ChatStatusEmitter statusEmitter) {
        this.llmRouter = llmRouter;
        this.toolRegistry = toolRegistry;
        this.statusEmitter = statusEmitter;
    }

    /**
     * Execute a delegation plan using the local LLM.
     *
     * @param plan          the structured plan from the cloud LLM
     * @param parentContext the parent agent context (for userId, taskId, cancellation)
     * @return consolidated result string (success or error description)
     */
    public String execute(DelegationPlan plan, AgentContext parentContext) {
        LlmProvider localProvider = llmRouter.local();
        if (!localProvider.isAvailable()) {
            return "ERROR: Local LLM (Ollama) is not available. Cannot execute delegation.";
        }

        int maxSteps = plan.maxSteps() > 0 ? plan.maxSteps() : 10;
        List<StepResult> stepResults = new ArrayList<>();
        List<LlmMessage> messages = new ArrayList<>();

        // System prompt with plan and tools
        messages.add(LlmMessage.system(buildExecutorSystemPrompt(plan, parentContext)));

        // Initial instruction
        messages.add(LlmMessage.user("Begin executing the plan. Start with step 1."));

        statusEmitter.emit(parentContext.userId(), StatusMessage.Type.STEP,
                "Delegating to local LLM: " + truncate(plan.goal(), 100));

        for (int step = 0; step < maxSteps; step++) {
            if (parentContext.isCancelled()) {
                return "ERROR: Task cancelled during delegation.";
            }

            // THINK: ask local LLM for next action
            LlmResponse response;
            try {
                response = localProvider.chat(messages,
                        new LlmRequestConfig(null, null, 2048, true, null));
            } catch (Exception e) {
                log.error("Local LLM call failed during delegation step {}", step + 1, e);
                return buildPartialResult("Local LLM call failed: " + e.getMessage(), stepResults);
            }

            parentContext.addLocalTokens(response.totalTokens());
            String raw = response.content();

            if (raw == null || raw.isBlank()) {
                return buildPartialResult("Local LLM returned empty response", stepResults);
            }

            // Parse executor action
            ExecutorAction action = parseExecutorAction(raw);

            if (action.done) {
                log.info("Delegation completed after {} steps. Summary length: {}",
                        step + 1, action.summary != null ? action.summary.length() : 0);
                // If summary is empty, build one from collected results
                if (action.summary == null || action.summary.isBlank()) {
                    return buildConsolidatedResult(plan.goal(), stepResults);
                }
                return action.summary;
            }

            if (action.tool == null || action.tool.isBlank()) {
                // Local LLM produced something unparseable — try to recover
                messages.add(LlmMessage.assistant(raw));
                messages.add(LlmMessage.user(
                        "Invalid output. You must respond with JSON: " +
                        "{\"tool\": \"name\", \"params\": {...}} to execute a tool, " +
                        "or {\"done\": true, \"summary\": \"...\"} when finished."));
                continue;
            }

            // Prevent skill_create — local executor cannot create skills
            if ("skill_create".equals(action.tool)) {
                messages.add(LlmMessage.assistant(raw));
                messages.add(LlmMessage.user(
                        "ERROR: skill_create is not available during delegation. " +
                        "Only existing tools can be used. Pick a different tool from the plan."));
                continue;
            }

            // ACT: execute the tool
            statusEmitter.emit(parentContext.userId(), StatusMessage.Type.PROGRESS,
                    "Delegate: running " + action.tool + "...");

            String toolResult = executeToolDirect(action.tool, action.params, parentContext);
            boolean toolOk = !toolResult.startsWith("ERROR");

            stepResults.add(new StepResult(action.tool, action.params, toolResult, toolOk));

            log.info("Delegation step {} — {} {} (result: {} chars)",
                    step + 1, action.tool, toolOk ? "OK" : "FAIL", toolResult.length());

            // OBSERVE: feed result back to local LLM
            messages.add(LlmMessage.assistant(raw));
            messages.add(LlmMessage.user(
                    "Tool result [" + action.tool + "] " + (toolOk ? "SUCCESS" : "FAILED") + ":\n" +
                    truncate(toolResult, 30_000) + "\n\n" +
                    "Continue with the next step, or if all steps are done, " +
                    "output {\"done\": true, \"summary\": \"consolidated results\"}."));
        }

        // Hit max steps without "done"
        log.warn("Delegation hit max steps ({}) for goal: {}", maxSteps, plan.goal());
        return buildPartialResult("Delegation reached max steps (" + maxSteps + ")", stepResults);
    }

    /**
     * Parse a DelegationPlan from the params map of a delegate action.
     */
    @SuppressWarnings("unchecked")
    public static DelegationPlan parsePlan(Map<String, Object> params) {
        String goal = params.get("goal") != null ? params.get("goal").toString() : "";

        List<DelegationPlan.Step> steps = new ArrayList<>();
        Object stepsObj = params.get("steps");
        if (stepsObj instanceof List<?> stepsList) {
            for (Object item : stepsList) {
                if (item instanceof Map<?, ?> stepMap) {
                    String desc = stepMap.get("description") != null ? stepMap.get("description").toString() : "";
                    String tool = stepMap.get("tool") != null ? stepMap.get("tool").toString() : "";
                    Map<String, Object> stepParams = Map.of();
                    if (stepMap.get("params") instanceof Map<?, ?> pMap) {
                        stepParams = new HashMap<>();
                        for (Map.Entry<?, ?> e : pMap.entrySet()) {
                            ((Map<String, Object>) stepParams).put(e.getKey().toString(), e.getValue());
                        }
                    }
                    steps.add(new DelegationPlan.Step(desc, tool, stepParams));
                }
            }
        }

        List<String> checkpoints = new ArrayList<>();
        Object cpObj = params.get("checkpoints");
        if (cpObj instanceof List<?> cpList) {
            for (Object item : cpList) {
                checkpoints.add(item.toString());
            }
        }

        int maxSteps = 10;
        Object maxObj = params.get("max_steps");
        if (maxObj != null) {
            try {
                maxSteps = Integer.parseInt(maxObj.toString());
            } catch (NumberFormatException ignored) {}
        }

        return new DelegationPlan(goal, steps, checkpoints, maxSteps);
    }

    // ── Private helpers ──

    /**
     * Build the system prompt for the local executor.
     * Includes the plan, available tools, and constrained output format.
     */
    private String buildExecutorSystemPrompt(DelegationPlan plan, AgentContext context) {
        var sb = new StringBuilder(4096);

        sb.append("You are a TASK EXECUTOR. You follow a plan created by the orchestrator.\n");
        sb.append("Execute each step by calling the specified tools. Chain results between steps.\n");
        sb.append("You have NO planning authority — just execute the plan faithfully.\n\n");

        // Output format
        sb.append("## Output Format\n");
        sb.append("To call a tool:\n");
        sb.append("{\"tool\": \"tool_name\", \"params\": {\"param1\": \"value1\"}}\n\n");
        sb.append("When ALL steps are complete, consolidate results and output:\n");
        sb.append("{\"done\": true, \"summary\": \"Complete consolidated results here\"}\n\n");
        sb.append("ALWAYS output exactly ONE JSON object. No extra text before or after.\n\n");

        // The plan
        sb.append("## Plan\n");
        sb.append("**Goal:** ").append(plan.goal()).append("\n\n");

        if (!plan.steps().isEmpty()) {
            sb.append("**Steps to execute in order:**\n");
            for (int i = 0; i < plan.steps().size(); i++) {
                var step = plan.steps().get(i);
                sb.append(i + 1).append(". ").append(step.description());
                if (step.tool() != null && !step.tool().isBlank()) {
                    sb.append(" → use tool: **").append(step.tool()).append("**");
                }
                if (step.params() != null && !step.params().isEmpty()) {
                    sb.append(" with params: ").append(step.params());
                }
                sb.append("\n");
            }
            sb.append("\n");
        }

        if (!plan.checkpoints().isEmpty()) {
            sb.append("**Quality checkpoints:**\n");
            for (String cp : plan.checkpoints()) {
                sb.append("- ").append(cp).append("\n");
            }
            sb.append("\n");
        }

        // Available tools (all except skill_create)
        sb.append("## Available Tools\n");
        Collection<Tool> availableTools = toolRegistry.all().stream()
                .filter(t -> !"skill_create".equals(t.name()))
                .collect(Collectors.toList());
        if (!availableTools.isEmpty()) {
            String manifest = toolRegistry.generateManifest(availableTools, context.credentialKeys());
            sb.append(manifest).append("\n");
        } else {
            sb.append("No tools available.\n");
        }

        // Guidelines
        sb.append("\n## Rules\n");
        sb.append("- Execute steps in the order given by the plan.\n");
        sb.append("- If a step fails, note the error and try the next step. Do NOT give up.\n");
        sb.append("- Use results from previous steps as input for subsequent steps when needed.\n");
        sb.append("- After executing all steps, consolidate ALL results into a comprehensive summary.\n");
        sb.append("- The summary in your final {\"done\": true, \"summary\": \"...\"} must contain ALL useful data collected.\n");
        sb.append("- NEVER create new skills (skill_create is not available).\n");
        sb.append("- NEVER ask questions — just execute.\n");

        return sb.toString();
    }

    /**
     * Execute a tool directly from the registry.
     * Simplified version of AgentLoop.executeTool() without LongRunningTaskManager.
     */
    private String executeToolDirect(String toolName, Map<String, Object> params, AgentContext context) {
        var toolOpt = toolRegistry.find(toolName);
        if (toolOpt.isEmpty()) {
            return "ERROR: Tool '" + toolName + "' not found. Available: " +
                    String.join(", ", toolRegistry.names());
        }

        Tool tool = toolOpt.get();
        ToolExecutionContext execCtx = new ToolExecutionContext(
                context.userId(),
                context.taskId(),
                null,
                context::isCancelled
        );

        try {
            ToolResult result = tool.execute(params != null ? params : Map.of(), execCtx);
            return result.success()
                    ? result.output()
                    : "ERROR: " + result.output();
        } catch (Exception e) {
            log.error("Tool '{}' threw exception during delegation", toolName, e);
            return "ERROR: Tool '" + toolName + "' threw exception: " + e.getMessage();
        }
    }

    /**
     * Parse the local LLM's response into an executor action.
     * Handles: {"tool": "...", "params": {...}} or {"done": true, "summary": "..."}
     */
    private ExecutorAction parseExecutorAction(String raw) {
        if (raw == null || raw.isBlank()) {
            return ExecutorAction.invalid();
        }

        String cleaned = raw.strip();

        // Strip code fences if present
        if (cleaned.startsWith("```")) {
            int end = cleaned.lastIndexOf("```");
            if (end > 3) {
                cleaned = cleaned.substring(cleaned.indexOf('\n') + 1, end).strip();
            }
        }

        // Extract JSON object
        int jsonStart = cleaned.indexOf('{');
        int jsonEnd = cleaned.lastIndexOf('}');
        if (jsonStart < 0 || jsonEnd <= jsonStart) {
            log.warn("LocalExecutor: no JSON found in local LLM response: {}",
                    truncate(cleaned, 200));
            return ExecutorAction.invalid();
        }
        cleaned = cleaned.substring(jsonStart, jsonEnd + 1);

        try {
            Map<String, Object> parsed = mapper.readValue(cleaned, new TypeReference<>() {});

            // Check for "done" signal
            Object doneObj = parsed.get("done");
            if (doneObj != null && ("true".equalsIgnoreCase(doneObj.toString())
                    || Boolean.TRUE.equals(doneObj))) {
                String summary = parsed.get("summary") != null ? parsed.get("summary").toString() : "";
                return ExecutorAction.done(summary);
            }

            // Parse tool call
            String tool = getStr(parsed, "tool");
            if (tool == null) tool = getStr(parsed, "action");
            if (tool == null) tool = getStr(parsed, "name");

            @SuppressWarnings("unchecked")
            Map<String, Object> params = parsed.get("params") instanceof Map<?, ?>
                    ? (Map<String, Object>) parsed.get("params")
                    : parsed.get("parameters") instanceof Map<?, ?>
                        ? (Map<String, Object>) parsed.get("parameters")
                        : Map.of();

            return new ExecutorAction(false, null, tool, params);
        } catch (Exception e) {
            log.warn("LocalExecutor: failed to parse local LLM JSON: {}", e.getMessage());
            return ExecutorAction.invalid();
        }
    }

    private String buildPartialResult(String reason, List<StepResult> results) {
        var sb = new StringBuilder();
        sb.append("Delegation incomplete: ").append(reason).append("\n\n");
        if (!results.isEmpty()) {
            sb.append("Partial results collected:\n");
            for (int i = 0; i < results.size(); i++) {
                var r = results.get(i);
                sb.append(i + 1).append(". [").append(r.tool).append("] ")
                        .append(r.success ? "OK" : "FAIL").append(": ")
                        .append(truncate(r.output, 2000)).append("\n");
            }
        }
        return sb.toString();
    }

    private String buildConsolidatedResult(String goal, List<StepResult> results) {
        var sb = new StringBuilder();
        sb.append("Delegation completed for: ").append(goal).append("\n\n");
        for (int i = 0; i < results.size(); i++) {
            var r = results.get(i);
            sb.append("### Step ").append(i + 1).append(": ").append(r.tool)
                    .append(r.success ? " ✓" : " ✗").append("\n");
            sb.append(truncate(r.output, 5000)).append("\n\n");
        }
        return sb.toString();
    }

    private static String getStr(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v != null ? v.toString() : null;
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    // ── Inner types ──

    /** Parsed action from the local executor LLM. */
    private record ExecutorAction(boolean done, String summary, String tool, Map<String, Object> params) {
        static ExecutorAction done(String summary) {
            return new ExecutorAction(true, summary, null, Map.of());
        }
        static ExecutorAction invalid() {
            return new ExecutorAction(false, null, null, Map.of());
        }
    }

    /** Result of a single tool execution within a delegation. */
    private record StepResult(String tool, Map<String, Object> params, String output, boolean success) {}
}
