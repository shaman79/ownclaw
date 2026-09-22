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
    private final SkillCuratorService curatorService;

    public LocalExecutor(LlmRouter llmRouter, ToolRegistry toolRegistry,
                         ChatStatusEmitter statusEmitter, SkillCuratorService curatorService) {
        this.llmRouter = llmRouter;
        this.toolRegistry = toolRegistry;
        this.statusEmitter = statusEmitter;
        this.curatorService = curatorService;
    }

    /**
     * Finishing is a tool call like any other, so the model has one output format, not two.
     * <p>
     * On the text protocol it had to emit {@code {"tool":...}} for work and {@code {"done":...}}
     * for the end, and a model that gets the second shape wrong burns every remaining step.
     */
    private static final ToolSpec DONE = new ToolSpec("done",
            "Call this when the goal is reached. Say what you DID and what each step returned "
                    + "in outline — do not retype the data. Every tool result is passed on "
                    + "underneath your summary, verbatim and in full, so copying it gains "
                    + "nothing and retyping a date, a number or a name from memory introduces "
                    + "an error that was not in the data.",
            ToolSchemas.toJsonSchema(Map.of("summary",
                    ToolParam.required("string",
                            "What you did, and what came back, in outline. Not a copy of it."))));

    /** What the local model may call: the registry, minus skill_create, plus {@link #DONE}. */
    private List<ToolSpec> executorTools(AgentContext context) {
        var tools = toolRegistry.all().stream()
                .filter(t -> t != null && !"skill_create".equals(t.name()))
                .collect(Collectors.toList());
        var specs = new ArrayList<>(ToolSchemas.build(List.of(DONE), tools,
                context.credentialKeys()));
        return specs;
    }

    /**
     * Execute a delegation plan using the local LLM.
     *
     * @param plan          the structured plan from the cloud LLM
     * @param parentContext the parent agent context (for userId, taskId, cancellation)
     * @return consolidated result string (success or error description)
     */
    public Outcome execute(DelegationPlan plan, AgentContext parentContext) {
        LlmProvider localProvider = llmRouter.local();
        if (!localProvider.isAvailable()) {
            return Outcome.failed(
                    "ERROR: Local LLM (Ollama) is not available. Cannot execute delegation.");
        }

        // The tool manifest is the largest thing in this prompt and num_ctx is the binding
        // constraint on this hardware, so when the model can take tools as structure, send them
        // as structure and drop the prose copy.
        boolean nativeTools = localProvider.supportsTools();
        List<ToolSpec> specs = nativeTools ? executorTools(parentContext) : null;
        log.info("Delegation protocol: {} ({} tools)", nativeTools ? "native" : "json-text",
                specs == null ? toolRegistry.all().size() : specs.size());

        int maxSteps = plan.maxSteps() > 0 ? plan.maxSteps() : 10;
        List<StepResult> stepResults = new ArrayList<>();
        List<LlmMessage> messages = new ArrayList<>();

        // System prompt with plan and tools
        messages.add(LlmMessage.system(buildExecutorSystemPrompt(plan, parentContext, nativeTools)));

        // Initial instruction. "Start with step 1" makes no sense without a step list.
        messages.add(LlmMessage.user(plan.steps().isEmpty()
                ? "Begin. Make the first tool call that moves toward the goal."
                : "Begin executing the plan. Start with step 1."));

        statusEmitter.emit(parentContext.userId(), StatusMessage.Type.STEP,
                "Delegating to local LLM: " + truncate(plan.goal(), 100));

        for (int step = 0; step < maxSteps; step++) {
            if (parentContext.isCancelled()) {
                // Partial work is not worthless: it is the only record of what the local model
                // managed before the plug was pulled, and throwing it away is why a timed-out
                // delegation used to leave nothing behind at all.
                return partial("Task cancelled during delegation.", stepResults);
            }

            // THINK: ask local LLM for next action
            LlmResponse response;
            try {

                // Local generation is free, so no token cap is set: Ollama then generates until the model stops or
                // the context window fills. The real bounds are num_ctx and the 600 s read timeout. Capping output
                // here used to starve thinking models, which spend part of the budget reasoning before they
                // answer.
                // format:json and tools are mutually exclusive in Ollama, so JSON mode is
                // only asked for on the text protocol, where it is what holds the output shape.
                response = localProvider.chat(messages,
                        new LlmRequestConfig(null, null, null, !nativeTools, null, specs));
            } catch (Exception e) {
                // Name the two failures that actually happen, because the orchestrator reads this
                // string and guesses otherwise -- it reported "local LLM token limits" when the
                // real answer was a context window one step too small, which points at the model
                // instead of at one config line.
                String msg = String.valueOf(e.getMessage());
                String hint = "";
                if (msg.contains("exceed_context_size") || msg.contains("exceeds the available context")) {
                    hint = " The local context window is too small for this delegation prompt"
                            + " (tool manifest plus results so far). Raise"
                            + " ownclaw.executor.context-window / OWNCLAW_EXECUTOR_CONTEXT."
                            + " This is a configuration limit, not a fault in the model or the goal.";
                } else if (msg.contains("whole output budget on reasoning")) {
                    hint = " The prompt nearly filled the context window, so almost nothing was"
                            + " left to answer with and the model spent it reasoning. Same fix:"
                            + " raise the local context window.";
                }
                log.error("Local LLM call failed during delegation step {}: {}{}",
                        step + 1, msg, hint, e);
                return partial("Local LLM call failed: " + msg + hint, stepResults);
            }

            parentContext.addLocalTokens(response.totalTokens());
            String raw = response.content();

            // A native tool call is the answer; content is then usually empty and that is fine.
            ExecutorAction action;
            if (response.hasToolCalls()) {
                ToolCall call = response.toolCalls().get(0);
                action = "done".equals(call.name())
                        ? ExecutorAction.done(str(call.arguments().get("summary")))
                        : new ExecutorAction(false, null, call.name(), call.arguments());
                raw = renderCall(call);
            } else {
                if (raw == null || raw.isBlank()) {
                    return partial("Local LLM returned empty response", stepResults);
                }
                // A tools-capable model can still answer in prose; the text parser is the
                // fallback, not dead code.
                action = parseExecutorAction(raw);
            }

            if (action.done) {
                log.info("Delegation completed after {} steps. Summary length: {}",
                        step + 1, action.summary != null ? action.summary.length() : 0);
                // If summary is empty, build one from collected results
                // The conclusion AND the rows it was drawn from. The cloud tier is kept for
                // its judgement, and a scheduled task shaped "fetch X, decide whether Y, act"
                // would otherwise have it judge on a small model's paraphrase of the evidence
                // -- today it reads up to 12,000 characters of the real output.
                String body = buildConsolidatedResult(plan.goal(), stepResults);
                String summary = action.summary == null || action.summary.isBlank()
                        ? body
                        : action.summary + "\n\n---\n" + body;
                // The claim and the evidence travel together. Without the ledger the cloud reads
                // a summary it cannot check, and scheduled_task_runs.last_result records the
                // claim alone -- so a false success is not even auditable afterwards.
                return completed(summary, stepResults);
            }

            if (action.tool == null || action.tool.isBlank()) {
                // Local LLM produced something unparseable — try to recover
                messages.add(LlmMessage.assistant(raw));
                messages.add(LlmMessage.user(nativeTools
                        ? "That was not a tool call. Call a tool to do the work, or call done "
                                + "with the summary if the goal is already reached."
                        : "Invalid output. You must respond with JSON: "
                                + "{\"tool\": \"name\", \"params\": {...}} to execute a tool, "
                                + "or {\"done\": true, \"summary\": \"...\"} when finished."));
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

            // A repeat of a call that already happened, on a tool that changes something.
            //
            // The cloud path has CriticAgent, which blocks an identical action after three
            // tries. Delegation runs before the critic and never reaches it, so the only bound
            // here is max_steps -- and the model doing the work is the weaker one, which is the
            // whole premise. An unsure model that re-sends smtp_send_email would send the
            // owner ten copies of the same email. Re-sending an identical side-effecting call
            // is never what was wanted, so hand back what it already returned instead.
            //
            // One resolution of the arguments first, used by every check below and by the call
            // itself. It was being computed twice, which is how a guard and the thing it
            // guards drift apart.
            Map<String, Object> params = substituteRefs(action.params, stepResults);

            // If the model retyped an excerpt instead of referencing it, refuse before anything
            // is written or sent -- and before the status line claims the tool is running.
            // Cheap, deterministic, and it catches the exact failure observed in production.
            String retyped = retypedExcerpt(params);
            if (retyped != null) {
                log.warn("Delegation step {}: '{}' was retyped from an excerpt — refused.",
                        stepResults.size() + 1, retyped);
                messages.add(LlmMessage.assistant(raw));
                messages.add(LlmMessage.user("STOP. The '" + retyped + "' value you just wrote "
                        + "contains the marker saying the middle was omitted, which means you "
                        + "copied what was shown to you instead of the real text — most of it is "
                        + "missing. Make the WHOLE value of '" + retyped + "' the reference $N "
                        + "for the step that produced it. Nothing else, no quotes around it, no "
                        + "text before or after it."));
                continue;
            }

            // (The repeat check the comment above describes.)
            String repeated = repeatedSideEffect(action, params, stepResults);
            if (repeated != null) {
                messages.add(LlmMessage.assistant(raw));
                messages.add(LlmMessage.user("You already called " + action.tool
                        + " with exactly these arguments, and it returned:\n"
                        + truncate(repeated, 4000)
                        + "\n\nUse that result. Do not call it again — it changes something, "
                        + "so a second identical call does it twice. Move to the next step, or "
                        + "finish."));
                continue;
            }

            // One thing neither guard catches: a value the model WROTE ITSELF, that is neither
            // a reference nor a quoted excerpt -- its own paraphrase of a result, sent as if it
            // were the result. There is no honest test for that (composing text is often
            // exactly the job), so this does not block it. It leaves evidence, which is the
            // difference between a quality regression someone can find and one nobody can.
            warnIfComposed(action.tool, params, stepResults);

            // ACT: execute the tool
            statusEmitter.emit(parentContext.userId(), StatusMessage.Type.PROGRESS,
                    "Delegate: running " + action.tool + "...");

            long toolStartMs = System.currentTimeMillis();
            String toolResult = executeToolDirect(action.tool, params, parentContext);
            long toolMs = System.currentTimeMillis() - toolStartMs;
            boolean toolOk = !toolResult.startsWith("ERROR");

            // Curation counts calls, and it only ever saw the cloud's. Once unattended work runs
            // here, a skill used every single morning looks untouched to maintenance -- which
            // retires skills for being unused. The telemetry has to follow the work.
            curatorService.recordUsage(action.tool, parentContext.userId(), parentContext.taskId(),
                    toolOk, toolMs, toolOk ? null : params, toolOk ? null : toolResult);

            stepResults.add(new StepResult(action.tool, params, toolResult, toolOk));

            // The stall watchdog measures time since the last progress, and a delegation used to
            // report none until it finished. At roughly a minute a local step, a ten-step
            // delegation reaches the 600-second timeout and is killed for stalling while it is
            // working normally. A completed tool call IS progress; say so, and the watchdog
            // goes back to measuring what it was built to measure.
            parentContext.markProgress();

            log.info("Delegation step {} — {} {} (result: {} chars)",
                    step + 1, action.tool, toolOk ? "OK" : "FAIL", toolResult.length());

            // OBSERVE: feed result back to local LLM
            messages.add(LlmMessage.assistant(raw));
            messages.add(LlmMessage.user(
                    "Tool result [" + action.tool + "] " + (toolOk ? "SUCCESS" : "FAILED") + ":\n" +
                    feedback(toolResult, stepResults.size()) + "\n\n" +
                    "Continue with the next step, or if all steps are done, " +
                    (nativeTools
                            ? "call done and say what you did — the result above is passed on "
                                    + "verbatim, so do not retype it."
                            : "output {\"done\": true, \"summary\": \"what you did\"}.")));
        }

        // Hit max steps without "done"
        log.warn("Delegation hit max steps ({}) for goal: {}", maxSteps, plan.goal());
        return partial("Delegation reached max steps (" + maxSteps + ")", stepResults);
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
    private String buildExecutorSystemPrompt(DelegationPlan plan, AgentContext context,
                                             boolean nativeTools) {
        var sb = new StringBuilder(4096);

        // The header used to say "Follow the plan exactly. No planning authority." unconditionally,
        // which is incoherent when no steps were supplied — now the normal case, because the
        // orchestrator usually cannot know a step's params before the previous step has run. Say
        // which mode this is, so the model either follows a plan or works one out, and never sits
        // waiting for a plan that is not coming.
        if (plan.steps().isEmpty()) {
            sb.append("TASK EXECUTOR. You have a goal and the tools to reach it. Work out the steps\n");
            sb.append("yourself, one tool call at a time, using each result to decide the next.\n");
            sb.append("You are running on the target machine: local files, the LAN and the servers\n");
            sb.append("here are reachable, and the credentials listed below are already loaded.\n\n");
        } else {
            sb.append("TASK EXECUTOR. Follow the plan below. Chain results between steps.\n");
            sb.append("The steps are the order to work in; adapt params to what earlier steps returned.\n\n");
        }
        sb.append("## Output\n");
        if (nativeTools) {
            sb.append("Call one tool per turn. When the goal is reached, call **done** with the\n");
            sb.append("full summary. Do not answer in prose — an answer nobody asked for ends\n");
            sb.append("nothing, and only **done** returns the work.\n\n");
        } else {
            sb.append("Tool call: {\"tool\": \"name\", \"params\": {...}}\n");
            sb.append("All done: {\"done\": true, \"summary\": \"consolidated results\"}\n");
            sb.append("ONE JSON object only. No extra text.\n\n");
        }

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

        // The tool list. On the native protocol the provider already has it as schema, and
        // repeating it here would cost the context window twice for the same information.
        if (!nativeTools) {
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
        }

        sb.append("\n## Rules\n");
        if (plan.steps().isEmpty()) {
            sb.append("- Take one step at a time and let each result inform the next.\n");
            sb.append("- Stop as soon as the goal is met; do not pad the work.\n");
        } else {
            sb.append("- Execute steps in order. On failure, note error and continue.\n");
            sb.append("- Chain previous results into subsequent steps.\n");
        }
        // It used to say "the final summary must contain ALL collected data", which asked a
        // small model to retype everything it had just read. The first delegated news digest
        // came back headed 2025-07-10 for a run on 2026-09-22 -- the skill had returned the
        // right date, and the summary invented a wrong one. The results are now carried out
        // verbatim underneath the summary, so there is nothing to gain by copying them and a
        // whole class of fabrication to lose.
        sb.append("- To pass an earlier step's output on unchanged, make the WHOLE value of the\n");
        sb.append("  parameter $1 for step 1's output, $2 for step 2's, and so on. It is\n");
        sb.append("  replaced with that step's exact text. Never retype a result: retyping is\n");
        sb.append("  where a wrong date or a dropped line comes from, and it costs you the\n");
        sb.append("  whole output again.\n");
        sb.append("- Your summary says what you DID. Every tool result is passed on verbatim\n");
        sb.append("  underneath it, so never retype data — a date or number written from\n");
        sb.append("  memory is an error that was not in the data.\n");
        sb.append("- No skill_create. Nobody is available to answer questions — decide and proceed.\n");

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
                context::isCancelled,
                null,
                // The four-argument constructor defaults these to empty, so a delegated skill
                // could not see a file the task was given. That was survivable while delegation
                // was the road not taken; it is not once unattended work runs through here.
                context.attachmentIds()
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

    /** The model's own tool call, written back into the history it will read next turn. */
    private String renderCall(ToolCall call) {
        try {
            return mapper.writeValueAsString(Map.of(
                    "tool", call.name(), "params", call.arguments()));
        } catch (Exception e) {
            return "{\"tool\": \"" + call.name() + "\"}";
        }
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    /**
     * The output of an earlier identical call to a side-effecting tool, or null if this call is
     * new. Identical means same tool and same arguments; a read-only tool is never blocked,
     * because calling one twice costs nothing but time and the goal may genuinely need it.
     */
    private String repeatedSideEffect(ExecutorAction action, Map<String, Object> params,
                                      List<StepResult> done) {
        var tool = toolRegistry.find(action.tool).orElse(null);
        if (tool == null || !tool.hasSideEffects()) return null;
        return priorIdenticalOutput(action.tool, params, done);
    }

    /**
     * How much of a result the model is shown before it is handed a reference instead.
     * <p>
     * Chosen because the failure it exists to stop is not hypothetical: a real delegation died
     * on step 2 with {@code done_reason=length} after 23,121 characters of thinking, because
     * step 1's 2,905-character digest had been fed back in full, and the model then had to
     * generate the whole thing AGAIN into the next tool call. Both ends squeezed a 24,576-token
     * window until nothing was left to answer with.
     */
    private static final int FEEDBACK_FULL_CHARS = 1500;

    /**
     * A tool result as the model should see it: in full when it is small, and otherwise an
     * excerpt plus the reference that moves the real thing.
     * <p>
     * Capping what the model reads is only half of it. The other half is that it no longer has
     * a reason to retype the result, because {@code $N} carries the exact bytes — so the same
     * change relieves the context window and removes the corruption it was fabricating dates
     * into.
     * <p>
     * Not applied to the local model's <em>reasoning</em>, which the owner wants unconstrained,
     * and not a token cap. This is about what goes IN.
     */
    static String feedback(String result, int stepNumber) {
        if (result == null) return "";
        if (result.length() <= FEEDBACK_FULL_CHARS) return result;
        // Small enough to say what came back, too small to be worth copying. The first version
        // showed 1,500 characters and asked the model not to retype them; it retyped them --
        // annotation and all -- straight into the next tool call, and the file it wrote was
        // half a digest with this sentence in the middle of it. Telling a model not to do
        // something it can do is the whole mistake this change exists to stop making.
        int head = 400;
        int tail = 150;
        return result.substring(0, head)
                + "\n\n" + OMISSION_MARKER + stepNumber + "⟧\n\n"
                + result.substring(result.length() - tail)
                + "\n\n[That is the beginning and the end of " + result.length() + " characters. "
                + "The complete, exact text is $" + stepNumber + ": make $" + stepNumber
                + " the WHOLE value of a parameter and it is substituted verbatim. You have not "
                + "been shown the middle, so anything you type yourself will be missing it.]";
    }

    /**
     * The canary. Its presence in a tool argument proves the model is retyping an excerpt.
     * <p>
     * Structural, because the instruction was not enough: a delegation wrote this very sentence
     * into a file as though it were part of the digest, and reported success. Silent truncation
     * of the owner's morning email is a worse failure than the crash it replaced, and it is the
     * one failure the cloud cannot catch — the summary looks right and the ledger says the tool
     * ran.
     */
    static final String OMISSION_MARKER = "⟦middle omitted — pass it on with $";

    /** Text long enough that writing it by hand means reproducing something. */
    private static final int COMPOSED_WARN_CHARS = 600;

    /**
     * Note a large text argument the model typed out itself on a tool that changes something.
     * <p>
     * Deliberately a warning and not a refusal. Writing prose into an email is a legitimate
     * thing for a delegation to do, and a rule that guessed at the difference would block real
     * work — the owner's standing objection to lists of do's and don'ts. But when the owner's
     * digest arrives paraphrased instead of forwarded, this line is what makes the cause
     * findable in a log rather than a mystery.
     */
    private void warnIfComposed(String tool, Map<String, Object> params,
                                List<StepResult> done) {
        if (params == null || done.isEmpty()) return;
        var t = toolRegistry.find(tool).orElse(null);
        if (t == null || !t.hasSideEffects()) return;
        for (var e : params.entrySet()) {
            if (!(e.getValue() instanceof String v) || v.length() < COMPOSED_WARN_CHARS) continue;
            boolean isAPriorResult = done.stream().anyMatch(r -> v.equals(r.output));
            if (!isAPriorResult) {
                log.warn("Delegation: '{}' was given {} characters in '{}' that the model wrote "
                                + "itself — not $N, and not equal to any step's output. If this "
                                + "was meant to forward a result, it is a paraphrase of one.",
                        tool, v.length(), e.getKey());
            }
        }
    }

    /** The parameter that is quoting an excerpt back at us, or null when none is. */
    static String retypedExcerpt(Map<String, Object> params) {
        if (params == null) return null;
        for (var e : params.entrySet()) {
            if (e.getValue() instanceof String v && v.contains(OMISSION_MARKER)) {
                return e.getKey();
            }
        }
        return null;
    }

    /**
     * Replace a parameter whose whole value is {@code $1}, {@code $2}... with that step's exact
     * output.
     * <p>
     * The scheduled digest chains {@code daily_news_digest} into {@code smtp_send_email}, so the
     * text the owner reads passes through the model as output tokens — three thousand characters
     * it has to retype perfectly, every morning. It does not: the first delegated digest was
     * headed 2025-07-10 for a run made on 2026-09-22, a date the skill had returned correctly.
     * A model cannot corrupt what it never retypes.
     * <p>
     * Only when the value is <em>exactly</em> the reference, never a substring. Shell commands
     * are full of {@code $1} and rewriting one inside a script would be a far worse bug than the
     * one this fixes.
     */
    static Map<String, Object> substituteRefs(Map<String, Object> params,
                                              List<StepResult> done) {
        if (params == null || params.isEmpty() || done.isEmpty()) {
            return params == null ? Map.of() : params;
        }
        var out = new LinkedHashMap<String, Object>(params);
        for (var e : out.entrySet()) {
            if (!(e.getValue() instanceof String v)) continue;
            String t = v.strip();
            if (t.length() < 2 || t.charAt(0) != '$') continue;
            int n;
            try {
                n = Integer.parseInt(t.substring(1));
            } catch (NumberFormatException ex) {
                continue;
            }
            if (n >= 1 && n <= done.size()) {
                e.setValue(done.get(n - 1).output);
            }
        }
        return out;
    }

    /** The output of an earlier call with the same name and the same arguments, or null. */
    static String priorIdenticalOutput(String tool, Map<String, Object> params,
                                       List<StepResult> done) {
        Map<String, Object> args = params == null ? Map.of() : params;
        for (StepResult r : done) {
            if (r.tool.equals(tool)
                    && Objects.equals(r.params == null ? Map.of() : r.params, args)) {
                return r.output;
            }
        }
        return null;
    }

    private static List<String> toolNames(List<StepResult> results) {
        return results.stream().map(r -> r.tool).distinct().toList();
    }

    /** The ledger of what ran, appended to a claim so the claim can be checked. */
    private static String ledger(List<StepResult> results) {
        if (results.isEmpty()) {
            return "\n\n[No tool was executed during this delegation.]";
        }
        var sb = new StringBuilder("\n\n[Tools run: ");
        for (int i = 0; i < results.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(results.get(i).tool).append(results.get(i).success ? " ok" : " FAILED");
        }
        return sb.append("]").toString();
    }

    /**
     * A delegation that said it was done. Successful only if something actually ran.
     * <p>
     * Zero tools and a confident summary is the shape of a hallucinated success, and it used to
     * produce a successful step, a successful task and a scheduled run recorded as delivered —
     * with the registry withheld, the cloud has no instrument to check it with.
     */
    static Outcome completed(String summary, List<StepResult> results) {
        boolean anyFailed = results.stream().anyMatch(r -> !r.success);
        return new Outcome(summary + ledger(results) + verbatimFailures(results),
                toolNames(results), results.size(),
                // A step that threw means the cloud should have the registry back: rewriting a
                // skill from its traceback is the self-learning loop this project exists for,
                // and it cannot run through a paraphrase. Marking the delegation failed is what
                // restores the registry and engages the repair path.
                !results.isEmpty() && !anyFailed);
    }

    /**
     * Failed steps at full length, appended after the summary.
     * <p>
     * A traceback is the whole evidence and it is short. Leaving it to the local model to copy
     * into its summary means the cloud is asked to rewrite Python from a small model's
     * description of a stack trace — on the one path where verbatim error text is worth more
     * than any summary.
     */
    private static String verbatimFailures(List<StepResult> results) {
        var failed = results.stream().filter(r -> !r.success).toList();
        if (failed.isEmpty()) return "";
        var sb = new StringBuilder("\n\n--- Failed steps (verbatim) ---");
        for (StepResult r : failed) {
            sb.append("\n[").append(r.tool).append("] ").append(r.params).append("\n")
              .append(truncate(r.output, 20_000));
        }
        return sb.toString();
    }

    private Outcome partial(String reason, List<StepResult> results) {
        return new Outcome(buildPartialResult(reason, results), toolNames(results),
                results.size(), false);
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
                        // Failures keep far more: a truncated traceback is a traceback that
                        // cannot be acted on, and this is the only copy that reaches the cloud.
                        .append(truncate(r.output, r.success ? 2000 : 20_000)).append("\n");
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

    /**
     * Summarize text using the local LLM if it exceeds maxLen, otherwise return as-is.
     * Falls back to smart truncation (head + tail) if the LLM is unavailable.
     */
    /**
     * Compress long text with the local model, falling back to head-and-tail truncation.
     * <p>
     * Public because AgentLoop is now the caller. This sat private and unused since it was
     * written: the job it does — turning a large tool result into something small without
     * throwing the middle away — is worth a local call only where the 60-133 seconds does not
     * land on someone waiting, and until there was a notion of unattended work there was
     * nowhere safe to call it from.
     */
    public String summarizeIfLong(String text, int maxLen) {
        if (text == null) return "";
        if (text.length() <= maxLen) return text;

        try {
            LlmProvider local = llmRouter.local();
            if (local.isAvailable()) {
                List<LlmMessage> msgs = List.of(
                        LlmMessage.system("Summarize preserving ALL key facts, data, numbers, URLs. Output ONLY the summary."),
                        LlmMessage.user(text.length() > 12000 ? text.substring(0, 12000) : text)
                );
                // Length is asked for in the prompt, not enforced by a token cap: a cap would be
                // spent on reasoning first and leave no summary at all.
                var response = local.chat(msgs, LlmRequestConfig.DEFAULT);
                if (response.content() != null && !response.content().isBlank()) {
                    return "[summarized] " + response.content();
                }
            }
        } catch (Exception e) {
            log.debug("Summarization failed, using smart truncation: {}", e.getMessage());
        }

        // Fallback: keep head + tail for context
        int half = maxLen / 2;
        return text.substring(0, half) + "\n...[" + text.length() + " chars, middle omitted]...\n"
                + text.substring(text.length() - half);
    }

    // ── Inner types ──

    /**
     * What a delegation actually did, not just what it says it did.
     * <p>
     * This used to be a String, and AgentLoop decided success by checking whether that string
     * started with "ERROR". So a local model that fetched nothing and called done with a
     * confident summary produced a successful step, a successful task and a scheduled run
     * recorded as delivered. The cloud could not check it either -- with the registry withheld
     * it has no instrument but another delegation. Carrying the ledger out makes the claim
     * auditable and makes "zero tools ran" a fact rather than an inference.
     *
     * @param text      the summary, or the error, as before
     * @param toolsRun  which registry tools actually executed, in order, with repeats collapsed
     * @param stepCount how many tool calls ran
     * @param ok        whether this counts as a successful delegation
     */
    public record Outcome(String text, List<String> toolsRun, int stepCount, boolean ok) {
        static Outcome failed(String text) { return new Outcome(text, List.of(), 0, false); }
    }

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
    record StepResult(String tool, Map<String, Object> params, String output, boolean success) {}
}
