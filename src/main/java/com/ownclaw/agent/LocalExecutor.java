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

    /** What the local model may call: the tools this delegation is given, plus {@link #DONE}. */
    private List<ToolSpec> executorTools(AgentContext context, DelegationPlan plan) {
        return new ArrayList<>(ToolSchemas.build(List.of(DONE), offered(plan),
                context.credentialKeys()));
    }

    /**
     * The tools a delegation is given: the ones the cloud named, and any a plan step names --
     * or the whole registry when it named none that exist. Never skill_create.
     * <p>
     * Every definition sent costs the local model context it needs for the work. With the
     * whole registry (26 skills) the prompt was about 18,000 tokens of a 24,576-token window,
     * and on 2026-09-24 the morning menu delegation died on its second call with
     * done_reason=length after 5,947 tokens of thinking: there was no room left to answer in.
     * The cloud knows which tools the job needs, so it says, and only those are sent.
     */
    Collection<Tool> offered(DelegationPlan plan) {
        var all = toolRegistry.all().stream()
                .filter(t -> t != null && !"skill_create".equals(t.name()))
                .collect(Collectors.toList());
        var wanted = new java.util.HashSet<>(plan.tools());
        for (var st : plan.steps()) {
            if (st.tool() != null && !st.tool().isBlank()) wanted.add(st.tool());
        }
        var named = all.stream().filter(t -> wanted.contains(t.name())).collect(Collectors.toList());
        return named.isEmpty() ? all : named;
    }

    /** The names the cloud asked for that no tool has; said back to it, so it can correct them. */
    private List<String> unknownTools(DelegationPlan plan) {
        return plan.tools().stream()
                .filter(n -> "skill_create".equals(n) || toolRegistry.find(n).isEmpty())
                .toList();
    }

    /**
     * Execute a delegation plan using the local LLM.
     *
     * @param plan          the structured plan from the cloud LLM
     * @param parentContext the parent agent context (for userId, taskId, cancellation)
     * @return consolidated result string (success or error description)
     */
    public Outcome execute(DelegationPlan plan, AgentContext parentContext) {
        // A delegation sees only its own results. If the goal names an earlier one, the local
        // model would read {{3}} as ITS OWN third step -- which is exactly how a second
        // delegation once forwarded the first one's traceback as the body of the morning email.
        // So the references are taken out of what it is given, and the cloud is told why.
        int[] removed = {0};
        Outcome outcome = run(withoutOutsideReferences(plan, removed), parentContext);
        String notes = "";
        if (removed[0] > 0) {
            log.warn("Delegation goal named {} earlier result(s); removed — a delegation cannot see them.",
                    removed[0]);
            notes += OUTSIDE_REFERENCE_NOTE;
        }
        List<String> unknown = unknownTools(plan);
        if (!unknown.isEmpty()) {
            log.warn("Delegation asked for tools that do not exist: {}", unknown);
            notes += "NOTE: no tool is named " + String.join(", ", unknown) + ", so the delegation "
                    + "ran without " + (unknown.size() == 1 ? "it" : "them") + ". Use the exact "
                    + "names from your tool list.\n\n";
        }
        if (notes.isEmpty()) return outcome;
        return new Outcome(notes + outcome.text(), outcome.toolsRun(),
                outcome.stepCount(), outcome.ok(), outcome.produced());
    }

    private static final String OUTSIDE_REFERENCE_NOTE = "NOTE: the delegation's goal named "
            + "earlier results. A delegation starts with no results and cannot see earlier ones, "
            + "so those references were removed from what it was given. When you have a tool that "
            + "takes an earlier result, put {{N}} or {{N.field}} into that call yourself; otherwise "
            + "say in words what the delegation should fetch.\n\n";

    /** The plan with every reference replaced by a note; {@code removed[0]} counts them. */
    static DelegationPlan withoutOutsideReferences(DelegationPlan plan, int[] removed) {
        java.util.function.UnaryOperator<String> scrub = text -> {
            if (text == null) return null;
            var m = ArtifactRef.TOKEN.matcher(text);
            var sb = new StringBuilder();
            while (m.find()) {
                removed[0]++;
                m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(
                        "(an earlier result this delegation cannot see — get it again if needed)"));
            }
            m.appendTail(sb);
            return sb.toString();
        };
        var steps = new ArrayList<DelegationPlan.Step>();
        for (var st : plan.steps()) {
            // Step PARAMETERS are left alone: in a plan, {{1}} there means the plan's own step 1,
            // which is exactly how the delegation numbers. Only prose points outside.
            steps.add(new DelegationPlan.Step(scrub.apply(st.description()), st.tool(), st.params()));
        }
        var checkpoints = plan.checkpoints() == null ? List.<String>of()
                : plan.checkpoints().stream().map(scrub).toList();
        return new DelegationPlan(scrub.apply(plan.goal()), steps, checkpoints, plan.maxSteps(),
                plan.tools());
    }

    @SuppressWarnings("unchecked")
    private Outcome run(DelegationPlan plan, AgentContext parentContext) {
        LlmProvider localProvider = llmRouter.local();
        if (!localProvider.isAvailable()) {
            return Outcome.failed(
                    "ERROR: Local LLM (Ollama) is not available. Cannot execute delegation.");
        }

        // The tool manifest is the largest thing in this prompt and num_ctx is the binding
        // constraint on this hardware, so when the model can take tools as structure, send them
        // as structure and drop the prose copy.
        boolean nativeTools = localProvider.supportsTools();
        List<ToolSpec> specs = nativeTools ? executorTools(parentContext, plan) : null;
        log.info("Delegation protocol: {} ({} tools)", nativeTools ? "native" : "json-text",
                offered(plan).size());

        int maxSteps = plan.maxSteps() > 0 ? plan.maxSteps() : 10;
        // This delegation's own results, and the only ones the local model can name: {{1}} is
        // its first step, {{2}} its second -- which is what the system prompt tells it, and
        // what a small model does unprompted anyway. Numbering them task-wide instead made that
        // sentence false the moment a run delegated twice, and the second delegation's {{1}}
        // forwarded the first one's traceback as the body of the morning email. Every result is
        // still recorded on the task too, under the task-wide handle the cloud and the ledger see.
        List<Artifact> mine = new ArrayList<>();
        // Whether the local model has read private data yet -- in this delegation, or in an
        // earlier one of this task, which may have written what it read into a file or a note
        // that this one reads back. From then on every result is PRIVATE and not indexed; see
        // AgentContext.decide.
        boolean tainted = parentContext.localTierReadPrivate();
        List<LlmMessage> messages = new ArrayList<>();

        // System prompt with plan and tools
        messages.add(LlmMessage.system(buildExecutorSystemPrompt(plan, parentContext, nativeTools)));

        // Initial instruction. "Start with step 1" makes no sense without a step list.
        String opening = plan.steps().isEmpty()
                ? "Begin. Make the first tool call that moves toward the goal."
                : "Begin executing the plan. Start with step 1.";
        messages.add(LlmMessage.user(opening));

        statusEmitter.emit(parentContext.userId(), StatusMessage.Type.STEP,
                "Delegating to local LLM: " + truncate(plan.goal(), 100));

        for (int step = 0; step < maxSteps; step++) {
            if (parentContext.isCancelled()) {
                // Partial work is not worthless: it is the only record of what the local model
                // managed before the plug was pulled, and throwing it away is why a timed-out
                // delegation used to leave nothing behind at all.
                return partial("Task cancelled during delegation.", mine);
            }

            // Keep the conversation from outgrowing the window it has to answer in.
            trimHistory(messages, mine);

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
                return partial("Local LLM call failed: " + msg + hint, mine);
            }

            parentContext.addLocalTokens(response.totalTokens());
            // A local call came back, so this task is not stalled — whatever the loop does with
            // the answer. Marking progress only after a tool EXECUTED meant that a delegation
            // being corrected by its own guards looked identical to a hung one: each refusal
            // costs a full local call at 60-133 seconds, and six in a row reach the 600-second
            // watchdog with the task working normally. It would then be cancelled outright —
            // no email, and the valve never gets the chance to hand the registry back.
            parentContext.markProgress();
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
                    return partial("Local LLM returned empty response", mine);
                }
                // A tools-capable model can still answer in prose; the text parser is the
                // fallback, not dead code.
                action = parseExecutorAction(raw);
            }

            // Finishing is finishing, whichever shape it arrives in.
            //
            // When `done` became a tool, the native path learned to recognise it and the text
            // parser did not -- it only ever knew {"done": true}. So a model that wrote
            // {"tool": "done", ...} as text had its finish looked up in the registry, where
            // there is no such tool, and got "Tool 'done' not found". A real delegation did
            // this three times in a row and then died on max steps, having completed the work.
            // It had finished; there was no way to say so.
            action = normalizeDone(action);

            if (action.done) {
                log.info("Delegation completed after {} steps. Summary length: {}",
                        step + 1, action.summary != null ? action.summary.length() : 0);
                // If summary is empty, build one from collected results
                // The conclusion AND the rows it was drawn from. The cloud tier is kept for
                // its judgement, and a scheduled task shaped "fetch X, decide whether Y, act"
                // would otherwise have it judge on a small model's paraphrase of the evidence
                // -- today it reads up to 12,000 characters of the real output.
                // The claim and the evidence travel together. Without the ledger the cloud reads
                // a summary it cannot check, and scheduled_task_runs.last_result records the
                // claim alone -- so a false success is not even auditable afterwards.
                return completed(action.summary, plan.goal(),
                        mine);
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

            // One resolution of the arguments, used by every check below and by the call itself.
            // It was once computed twice, which is how a guard and the thing it guards drift
            // apart.
            References.Resolved refs = References.resolve(action.params, mine);
            Map<String, Object> params = refs.params();

            // If the model retyped an excerpt instead of referencing it, refuse before anything
            // is written or sent -- and before the status line claims the tool is running.
            // Cheap, deterministic, and it catches the exact failure observed in production.
            String retyped = retypedExcerpt(params);
            if (retyped != null) {
                log.warn("Delegation step {}: '{}' was retyped from an excerpt — refused.",
                        mine.size() + 1, retyped);
                messages.add(LlmMessage.assistant(raw));
                messages.add(LlmMessage.user("STOP. The '" + retyped + "' value you just wrote "
                        + "contains the marker saying the middle was omitted, which means you "
                        + "copied what was shown to you instead of the real text — most of it is "
                        + "missing. Make the WHOLE value of '" + retyped + "' the reference {{N}} "
                        + "for the step that produced it. Nothing else, no quotes around it, no "
                        + "text before or after it."));
                continue;
            }

            // A reference that could not be resolved: out of range, a missing field, a failed
            // result, or reference-shaped text that is not the whole value. Each of these used to
            // survive as literal text -- "$1.body" as the entire body of an email, sent, recorded
            // green, with not one line in the log. Refused here, before anything runs.
            if (!refs.ok()) {
                log.warn("Delegation step {}: '{}' — reference refused.", mine.size() + 1,
                        refs.refused());
                messages.add(LlmMessage.assistant(raw));
                messages.add(LlmMessage.user("Not run: the value of '" + refs.refused()
                        + "' would have been sent as literal text. " + refs.reason() + " "
                        + References.available(mine)));
                continue;
            }

            // A change is attempted once per delegation, and made once per task. The cloud path
            // has CriticAgent, which blocks an identical action after three tries; delegation
            // never reaches it, so the only bound here was max_steps -- and an SMTP timeout can
            // arrive AFTER the server accepted the message, so an unbounded retry of a "failed"
            // send delivered a copy each time. A failed change is retried by the next attempt at
            // the job (a new delegation, or the cloud once the fallback opens), where it is new;
            // one that SUCCEEDED (by Artifact.succeeded, which reads an "ok": false envelope) is
            // never repeated anywhere in the task. The earlier output is not shown: showing an
            // earlier delegation's output is how a PRIVATE result reached the local model and
            // then, reworded in its summary, the cloud.
            Tool target = toolRegistry.find(action.tool).orElse(null);
            Artifact triedHere = priorSideEffect(target, params, mine, false);
            Artifact doneInTask = priorSideEffect(target, params, parentContext.artifacts(), true);
            if (triedHere != null || doneInTask != null) {
                messages.add(LlmMessage.assistant(raw));
                messages.add(LlmMessage.user(triedHere != null
                        ? "Not run: you already made exactly this " + action.tool + " call in this "
                                + "delegation, and it " + (triedHere.succeeded() ? "succeeded" : "FAILED")
                                + ". A change is attempted once per delegation. Move on, or finish "
                                + "and say what happened."
                        : "Not run: an identical " + action.tool + " call already succeeded earlier "
                                + "in this task. It changes something, so it is never done twice. "
                                + "Move to the next step, or finish."));
                continue;
            }

            // One thing neither guard catches: a value the model WROTE ITSELF, that is neither
            // a reference nor a quoted excerpt -- its own paraphrase of a result, sent as if it
            // were the result. There is no honest test for that (composing text is often
            // exactly the job), so this does not block it. It leaves evidence, which is the
            // difference between a quality regression someone can find and one nobody can.
            String composed = composedPayload(action.tool, action.params, mine);
            if (composed != null) {
                log.warn("Delegation step {}: '{}' is {} characters the model wrote itself — "
                                + "refused; a result must be forwarded, not rewritten.",
                        mine.size() + 1, composed,
                        String.valueOf(action.params.get(composed)).length());
                messages.add(LlmMessage.assistant(raw));
                messages.add(LlmMessage.user("STOP. The '" + composed + "' value is text you "
                        + "wrote out yourself. A result must be forwarded exactly as it came, "
                        + "never rewritten — rewriting is how a date, a line or a whole section "
                        + "goes missing, and you cannot see what you dropped. "
                        + References.available(mine)
                        + " If none of them is what belongs here, this needs composing rather "
                        + "than forwarding, which is not your job: stop calling this tool and "
                        + "let the step fail."));
                continue;
            }

            // ACT: execute the tool
            statusEmitter.emit(parentContext.userId(), StatusMessage.Type.PROGRESS,
                    "Delegate: running " + action.tool + "...");

            long toolStartMs = System.currentTimeMillis();
            ToolResult result = executeToolDirect(action.tool, params, parentContext);
            long toolMs = System.currentTimeMillis() - toolStartMs;
            boolean toolOk = result.success();
            String toolResult = toolOk ? result.output() : "ERROR: " + result.output();

            // The label, from facts already at hand -- and the record on the task, which is
            // where the bytes live from now on. `params` is what actually ran (references
            // substituted); `action.params` is what the model typed, and is the only one ever
            // printed.
            // After the local model has read private data, nothing more of this delegation is
            // shown to the cloud: whatever it types can carry what it read, and a public tool that
            // echoes its input -- or a file written and then read back -- hands that straight
            // into the output. AgentContext.decide makes such a result PRIVATE and unindexed.
            Tool ran = toolRegistry.find(action.tool).orElse(null);
            Artifact.Decision decision = parentContext.decide(
                    ran == null ? List.of() : ran.requiredCredentials(), refs.used(), tainted);
            boolean wroteAfterPrivate = tainted;
            Artifact artifact = parentContext.addArtifact(action.tool, action.params, params,
                    toolResult, toolOk, decision);
            mine.add(artifact);
            if (artifact.isPrivate()) {
                tainted = true;
                parentContext.markLocalTierReadPrivate();
            }
            // Whether it WORKED, which is what everyone downstream asks: the model's feedback, the
            // usage row, the delegation's verdict. The harness's flag said SUCCESS for an SMTP
            // error wrapped as "ok": false, so the delegation reported ok and the fallback never
            // handed the job on -- no email, run recorded complete.
            boolean worked = artifact.succeeded();

            // Curation counts calls, and it only ever saw the cloud's. Once unattended work runs
            // here, a skill used every single morning looks untouched to maintenance -- which
            // retires skills for being unused. The telemetry has to follow the work.
            // The arguments as WRITTEN, never resolved -- the resolved map carries the bytes of
            // whatever {{N}} pointed at -- and in the task's numbering, because the repair prompt
            // reads these rows next to rows from the cloud path. The row's label is what keeps it
            // out of that prompt, and it is the artifact's label: PRIVATE for anything written
            // after the model read private data.
            // Arguments typed after the model read private data are not stored at all -- the
            // label alone kept them out of the repair prompt, and "that label has been wrong
            // before" is why this was a second defence to begin with.
            curatorService.recordUsage(action.tool, parentContext.userId(), parentContext.taskId(),
                    worked, toolMs,
                    worked || wroteAfterPrivate ? null : References.argsForTask(action.params, mine),
                    worked ? null : toolResult, artifact.label());

            log.info("Delegation step {} — {} {} (result: {} chars, {})",
                    step + 1, artifact.handle() + " " + action.tool, worked ? "OK" : "FAIL",
                    toolResult.length(), artifact.label());

            // OBSERVE: feed result back to local LLM
            messages.add(LlmMessage.assistant(raw));
            messages.add(LlmMessage.user(
                    // The handle every time -- a short result used to arrive without one, so the
                    // model had to count for itself, and counting is where {{1}} went wrong.
                    "Tool result " + ArtifactRef.handle(mine.size()) + " [" + action.tool + "] "
                    + (worked ? "SUCCESS" : "FAILED") + ":\n" +
                    feedback(toolResult, mine.size()) + "\n\n" +
                    "Continue with the next step, or if all steps are done, " +
                    (nativeTools
                            ? "call done and say what you did — the result above is passed on "
                                    + "verbatim, so do not retype it."
                            : "output {\"done\": true, \"summary\": \"what you did\"}.")));
        }

        // Hit max steps without "done"
        log.warn("Delegation hit max steps ({}) for goal: {}", maxSteps, plan.goal());
        return partial("Delegation reached max steps (" + maxSteps + ")",
                mine);
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

        // An array of names, or -- as a model will sometimes write it -- one string of them.
        List<String> tools = new ArrayList<>();
        Object toolsObj = params.get("tools");
        if (toolsObj instanceof List<?> toolList) {
            for (Object item : toolList) {
                if (item != null && !item.toString().isBlank()) tools.add(item.toString().strip());
            }
        } else if (toolsObj instanceof String s) {
            for (String name : s.split("[,\\s]+")) if (!name.isBlank()) tools.add(name);
        }

        return new DelegationPlan(goal, steps, checkpoints, maxSteps, tools);
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
            Collection<Tool> availableTools = offered(plan);
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
        sb.append("  parameter {{1}} for step 1's output, {{2}} for step 2's, and so on. It is\n");
        sb.append("  replaced with that step's exact text. If the result is JSON and you need\n");
        sb.append("  one field, use {{1.fieldname}} — e.g. {{1.body_text}} to put the text from\n");
        sb.append("  an envelope into an email body rather than the whole envelope.\n");
        sb.append("  Never retype a result: retyping is where a wrong date or a dropped line\n");
        sb.append("  comes from, and it costs you the whole output again.\n");
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
    private ToolResult executeToolDirect(String toolName, Map<String, Object> params, AgentContext context) {
        var toolOpt = toolRegistry.find(toolName);
        if (toolOpt.isEmpty()) {
            return ToolResult.failure("Tool '" + toolName + "' not found. Available: " +
                    String.join(", ", toolRegistry.names()));
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
            return tool.execute(params != null ? params : Map.of(), execCtx);
        } catch (Exception e) {
            log.error("Tool '{}' threw exception during delegation", toolName, e);
            return ToolResult.failure("Tool '" + toolName + "' threw exception: " + e.getMessage());
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

            // Parse tool call. A 'done' here is normalised by the caller, which keeps the
            // two protocols agreeing on what finishing looks like.
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

    /**
     * How many messages of conversation the executor carries forward.
     * <p>
     * The system prompt, the opening instruction, then this many of the most recent messages.
     * Eight is four exchanges: enough to see what was just tried and what came back, and
     * bounded so step nine costs what step three did.
     */
    private static final int HISTORY_TAIL = 8;

    /**
     * Drop the middle of the conversation, keeping a ledger of what it contained.
     * <p>
     * The real failure this prevents, from the 23 September menu run: by step 9 the history held
     * eight exchanges on top of the system prompt and 27 tool schemas, the prompt had nearly
     * filled a 24,576-token window, and the model spent the 4,954 tokens left on reasoning and
     * never answered. That delegation burned 407 seconds and then failed, and the cloud did the
     * work in fourteen. The owner's constraints rule out the other two levers — the local model
     * is to think freely and keep its full output budget — and VRAM rules out a bigger window,
     * so what has to shrink is the part nobody chose: the transcript.
     * <p>
     * Nothing is lost that matters, because results do not live here. The delegation's list
     * holds every output in full, {@code {{N}}} still resolves against it, and the ledger says which
     * numbers exist. That is the quiet dividend of passing by reference: the transcript can be
     * cut without cutting the data.
     */
    static void trimHistory(List<LlmMessage> messages, List<Artifact> done) {
        // system + opening instruction + the tail. Below that there is nothing to gain.
        if (messages.size() <= HISTORY_TAIL + 2) return;

        // The tail must begin with an assistant turn, or the roles stop alternating: after the
        // opening user message the pattern is assistant, user, assistant, user...
        int from = messages.size() - HISTORY_TAIL;
        if ((from - 2) % 2 != 0) from++;

        var ledger = new StringBuilder("Results available:\n");
        for (int i = 0; i < done.size(); i++) {
            ledger.append("  ").append(ArtifactRef.handle(i + 1)).append(" = ").append(done.get(i).tool())
                  .append(done.get(i).succeeded() ? " (ok, " : " (FAILED — not referenceable, ")
                  .append(done.get(i).output() == null ? 0 : done.get(i).output().length())
                  .append(" chars)\n");
        }
        ledger.append("A successful result's full output is still available by reference — {{1}}, {{2}}, and so "
                + "on, or {{N.field}} for a JSON result. The conversation above them has been dropped to "
                + "leave room to answer in; the results themselves have not.");

        // The ledger joins the opening instruction rather than following it, so the roles keep
        // alternating: system, user, assistant, user, ... Two user turns in a row is something
        // Ollama tolerates and other providers reject, and this loop should not depend on which.
        var kept = new ArrayList<LlmMessage>();
        kept.add(messages.get(0));
        kept.add(LlmMessage.user(messages.get(1).content() + "\n\n" + ledger));
        kept.addAll(messages.subList(from, messages.size()));
        messages.clear();
        messages.addAll(kept);
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
     * a reason to retype the result, because {@code {{N}}} carries the exact bytes — so the same
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
                + "\n\n" + OMISSION_MARKER + stepNumber + "}}⟧\n\n"
                + result.substring(result.length() - tail)
                + "\n\n[That is the beginning and the end of " + result.length() + " characters. "
                + "The complete, exact text is " + ArtifactRef.handle(stepNumber) + ": make "
                + ArtifactRef.handle(stepNumber) + " the WHOLE value of a parameter and it is "
                + "substituted verbatim. If the result is JSON and you want one field of it, use "
                + "{{" + stepNumber + ".fieldname}} the same way — e.g. {{" + stepNumber
                + ".body_text}} for the text inside an envelope. "
                + "You have not been shown the middle, so anything you type yourself will be "
                + "missing it.]";
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
    static final String OMISSION_MARKER = "⟦middle omitted — pass it on with {{";

    /**
     * Treat {@code {"tool": "done"}} as the finish it obviously is.
     * <p>
     * The summary may arrive under {@code summary}, or as {@code message}/{@code result} from a
     * model improvising the shape. Any of them beats failing to finish; an empty one is still
     * a finish, and {@link #buildConsolidatedResult} supplies the body.
     */
    static ExecutorAction normalizeDone(ExecutorAction action) {
        if (action == null || action.done || !"done".equals(action.tool)) return action;
        Map<String, Object> p = action.params == null ? Map.of() : action.params;
        Object summary = p.get("summary");
        if (summary == null) summary = p.get("message");
        if (summary == null) summary = p.get("result");
        return ExecutorAction.done(str(summary));
    }

    /**
     * Text long enough that writing it by hand means reproducing something.
     * <p>
     * A warning first, and that was not enough. On 23 September the news digest went out as
     * 2,073 characters the local model had written itself from a 2,745-character source: the
     * log said so and the owner still read a rewritten digest. Composing prose is judgement, and
     * judgement is the cloud's half of this architecture — so the local tier forwards results
     * and does not author them. If a goal genuinely needs text composed, this delegation fails
     * and the valve hands it to the tier that should have had it.
     */
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
    private String composedPayload(String tool, Map<String, Object> written,
                                   List<Artifact> done) {
        // The arguments as the MODEL WROTE them, before substitution. {{1.body_text}} is the
        // correct way to forward a field, and the substituted value never equals a whole step
        // output, so checking the resolved map reported every correct forward as a paraphrase --
        // which is how the one signal for a real paraphrase turns into noise. Raw, the two cases
        // separate themselves: "{{1.body_text}}" is fifteen characters and falls under the
        // threshold, while a composed two-thousand-character body is identical either way.
        if (written == null || done.isEmpty()) return null;
        var t = toolRegistry.find(tool).orElse(null);
        if (t == null || !t.hasSideEffects()) return null;
        for (var e : written.entrySet()) {
            if (!(e.getValue() instanceof String v) || v.length() < COMPOSED_WARN_CHARS) continue;
            // Equal to a step's output means it was copied perfectly, which is only wasteful.
            // A reference is the intended path and is short. Anything else is the model's own
            // prose standing in for a result.
            boolean isAPriorResult = done.stream().anyMatch(r -> v.equals(r.output()));
            if (!isAPriorResult) return e.getKey();
        }
        return null;
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
     * An earlier call anywhere in the task that already SUCCEEDED in doing exactly this, or null.
     * <p>
     * A delegation that fails on one step can still have sent the email on
     * another, and a second delegation retrying the job would send a second morning email. Only
     * a real success counts: a send that failed, including one reported as {@code "ok": false},
     * is exactly what a retry is for. Only a tool that declares side effects: a second read costs
     * nothing but time.
     */
    static Artifact sideEffectAlreadyDone(com.ownclaw.agent.tools.Tool tool,
                                          Map<String, Object> resolved, List<Artifact> task) {
        return priorSideEffect(tool, resolved, task, true);
    }

    /**
     * An earlier identical call to a side-effecting tool in {@code among}, or null. Identical
     * means the same tool and the same RESOLVED arguments; {@code successesOnly} decides whether
     * a failed attempt counts.
     */
    static Artifact priorSideEffect(com.ownclaw.agent.tools.Tool tool, Map<String, Object> resolved,
                                    List<Artifact> among, boolean successesOnly) {
        if (tool == null || !tool.hasSideEffects()) return null;
        Map<String, Object> args = resolved == null ? Map.of() : resolved;
        for (Artifact a : among) {
            if ((!successesOnly || a.succeeded()) && a.tool().equals(tool.name())
                    && Objects.equals(a.resolved() == null ? Map.of() : a.resolved(), args)) {
                return a;
            }
        }
        return null;
    }

    private static List<String> toolNames(List<Artifact> results) {
        return results.stream().map(r -> r.tool()).distinct().toList();
    }

    /** The ledger of what ran, appended to a claim so the claim can be checked. */
    private static String ledger(List<Artifact> results) {
        if (results.isEmpty()) {
            return "\n\n[No tool was executed during this delegation.]";
        }
        var sb = new StringBuilder("\n\n[Tools run: ");
        for (int i = 0; i < results.size(); i++) {
            if (i > 0) sb.append(", ");
            Artifact r = results.get(i);
            sb.append(r.handle()).append(' ').append(r.tool()).append(r.succeeded() ? " ok" : " FAILED");
            if (r.isPrivate()) sb.append(" (PRIVATE, ").append(r.output().length()).append(" chars withheld)");
        }
        sb.append("]");
        if (results.stream().anyMatch(Artifact::isPrivate)) {
            sb.append("\nPrivate results are not shown. When you have a tool that takes one, put its "
                    + "handle ({{N}} or {{N.field}}) as the whole value of that argument; a new "
                    + "delegation cannot see it.");
        }
        return sb.toString();
    }

    /**
     * A delegation that said it was done. Successful only if something actually ran.
     * <p>
     * Zero tools and a confident summary is the shape of a hallucinated success, and it used to
     * produce a successful step, a successful task and a scheduled run recorded as delivered —
     * with the registry withheld, the cloud has no instrument to check it with.
     */
    static Outcome completed(String localSummary, String goal, List<Artifact> results) {
        boolean anyFailed = results.stream().anyMatch(r -> !r.succeeded());
        boolean anyPrivate = results.stream().anyMatch(Artifact::isPrivate);
        // The local model's own prose is withheld when it has read private content: it is a
        // paraphrase of that content, and a paraphrase is the one thing the canary cannot see.
        // The descriptors, the ledger and the PUBLIC outputs remain, which is what the cloud
        // decides on. All-PUBLIC delegations read exactly as before.
        String summary;
        if (anyPrivate) {
            summary = "(local summary withheld — this delegation touched "
                    + results.stream().filter(Artifact::isPrivate).map(Artifact::handle)
                            .collect(Collectors.joining(", ")) + ")";
        } else {
            // In the task's numbering: the summary says "sent {{1}}" meaning the delegation's
            // first step, and the cloud reads task handles everywhere else.
            summary = localSummary == null || localSummary.isBlank() ? ""
                    : References.proseForTask(localSummary, results);
        }
        String body = buildConsolidatedResult(goal, results);
        summary = summary.isEmpty() ? body : summary + "\n\n---\n" + body;
        // A delegation can fail on one step and still have SENT THE EMAIL on another. Reporting
        // it failed is right -- the traceback is what feeds the repair loop -- but it also hands
        // the registry back and tells the cloud to finish the job, and "on failure, try a
        // fundamentally different approach" then means sending a second digest. CriticAgent
        // cannot stop it: the delegation's calls are not in the cloud's trajectory, so that send
        // is a first-time action. So what already succeeded goes FIRST, where head-and-tail
        // truncation cannot drop it, not as a tick buried in a ledger.
        String head = "";
        if (anyFailed && !results.isEmpty()) {
            String ran = results.stream().filter(Artifact::succeeded).map(r -> r.tool())
                    .distinct().collect(Collectors.joining(", "));
            if (!ran.isBlank()) {
                head = "ALREADY DONE — these succeeded and must NOT be repeated: " + ran
                        + ". Anything below that failed is what is left to do.\n\n";
            }
        }
        return new Outcome(head + summary + ledger(results) + verbatimFailures(results),
                toolNames(results), results.size(),
                // A step that threw means the cloud should have the registry back: rewriting a
                // skill from its traceback is the self-learning loop this project exists for,
                // and it cannot run through a paraphrase. Marking the delegation failed is what
                // restores the registry and engages the repair path.
                !results.isEmpty() && !anyFailed, List.copyOf(results));
    }

    /**
     * Failed steps at full length, appended after the summary.
     * <p>
     * A traceback is the whole evidence and it is short. Leaving it to the local model to copy
     * into its summary means the cloud is asked to rewrite Python from a small model's
     * description of a stack trace — on the one path where verbatim error text is worth more
     * than any summary.
     */
    static String verbatimFailures(List<Artifact> results) {
        if (results.stream().allMatch(Artifact::succeeded)) return "";
        var sb = new StringBuilder("\n\n--- Failed steps (verbatim) ---");
        for (Artifact r : results) {
            if (r.succeeded()) continue;
            // The arguments as WRITTEN, never as resolved: the resolved map carries the
            // substituted bytes of whatever {{N}} pointed at. Rewritten into the task's handles,
            // because the cloud reads them next to task handles and may copy them into a call of
            // its own. A PRIVATE step -- including anything after the model read private data --
            // shows neither its arguments nor its output.
            sb.append("\n[").append(r.handle()).append(' ').append(r.tool()).append("] ")
              .append(r.isPrivate() ? "(arguments withheld)"
                      : String.valueOf(References.argsForTask(r.written(), results)))
              .append("\n")
              .append(r.isPrivate() ? r.describe() : truncate(r.output(), 20_000));
        }
        return sb.toString();
    }

    private Outcome partial(String reason, List<Artifact> results) {
        return new Outcome(buildPartialResult(reason, results), toolNames(results),
                results.size(), false, List.copyOf(results));
    }

    private String buildPartialResult(String reason, List<Artifact> results) {
        var sb = new StringBuilder();
        sb.append("Delegation incomplete: ").append(reason).append("\n\n");
        if (!results.isEmpty()) {
            sb.append("Partial results collected:\n");
            for (int i = 0; i < results.size(); i++) {
                var r = results.get(i);
                sb.append(r.handle()).append(" [").append(r.tool()).append("] ")
                        .append(r.succeeded() ? "OK" : "FAIL").append(": ")
                        // Failures keep far more: a truncated traceback is a traceback that
                        // cannot be acted on, and this is the only copy that reaches the cloud.
                        .append(r.isPrivate() ? r.describe()
                                : truncate(r.output(), r.success() ? 2000 : 20_000)).append("\n");
            }
        }
        return sb.toString();
    }

    static String buildConsolidatedResult(String goal, List<Artifact> results) {
        var sb = new StringBuilder();
        sb.append("Delegation completed for: ").append(goal).append("\n\n");
        for (Artifact r : results) {
            if (r.isPrivate()) {
                // describe() already opens with the handle, the tool and the tick; prefixing
                // them again printed each twice on every private step of every delegation.
                sb.append("### ").append(r.describe()).append("\n\n");
            } else {
                sb.append("### ").append(r.handle()).append(": ").append(r.tool())
                        .append(r.succeeded() ? " ✓" : " ✗")
                        .append("\n").append(truncate(r.output(), 5000)).append("\n\n");
            }
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
    public record Outcome(String text, List<String> toolsRun, int stepCount, boolean ok,
                          List<Artifact> produced) {
        static Outcome failed(String text) { return new Outcome(text, List.of(), 0, false, List.of()); }
    }

    /** Parsed action from the local executor LLM. */
    record ExecutorAction(boolean done, String summary, String tool, Map<String, Object> params) {
        static ExecutorAction done(String summary) {
            return new ExecutorAction(true, summary, null, Map.of());
        }
        static ExecutorAction invalid() {
            return new ExecutorAction(false, null, null, Map.of());
        }
    }
}
