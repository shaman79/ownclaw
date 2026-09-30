package com.ownclaw.agent;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.ownclaw.agent.tools.*;
import com.ownclaw.llm.*;
import com.ownclaw.observability.ChatStatusEmitter;
import com.ownclaw.observability.ChatStatusEmitter.StatusMessage;
import com.ownclaw.privacy.PrivateIndex;
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

    /**
     * {@link #DONE} on a task holding the user's files. There the summary is not an outline for
     * the orchestrator: every result is withheld from it, so the summary is kept as the answer
     * the user reads (see {@link #recordAnswer}), and an outline would be all they got.
     */
    private static final ToolSpec DONE_FILE = new ToolSpec("done",
            "Call this when the goal is reached. Your summary is the answer the user reads; the "
                    + "orchestrator is never shown it or the results. Write it in full, copying "
                    + "figures, dates and names exactly as the tools returned them.",
            ToolSchemas.toJsonSchema(Map.of("summary",
                    ToolParam.required("string", "The answer for the user, in full."))));

    /** What the local model may call: the tools this delegation is given, plus {@code done}. */
    private List<ToolSpec> executorTools(AgentContext context, DelegationPlan plan, boolean fileTask) {
        return new ArrayList<>(ToolSchemas.build(List.of(fileTask ? DONE_FILE : DONE),
                offered(plan, context), context.credentialKeys()));
    }

    /**
     * The tools a delegation is given: the ones the cloud listed, any a plan step names, and any
     * whose exact name appears in the goal or -- for unattended work -- in the task's own
     * message; or the whole registry when that is none at all. Never skill_create.
     * <p>
     * Every definition sent costs the local model context it needs for the work. With the
     * whole registry (26 skills) the prompt was about 18,000 tokens of a 24,576-token window,
     * and on 2026-09-24 the morning menu delegation died on its second call with
     * done_reason=length after 5,947 tokens of thinking: there was no room left to answer in.
     * The cloud knows which tools the job needs, so it says. A scheduled task's own message
     * counts too, because it does not depend on anything the cloud writes: those name their
     * skills ("using daily_menu_fetcher, then ... via smtp_send_email"), so a list the cloud
     * forgot, or a goal it paraphrased, still narrows -- and a tool the task asks for is never
     * one the model cannot call. Only unattended: a chat message names tools in passing ("why
     * did smtp_send_email fail?", "do NOT use ..."), and there it would narrow to the wrong one.
     */
    Collection<Tool> offered(DelegationPlan plan, AgentContext context) {
        var all = toolRegistry.all().stream()
                .filter(t -> t != null && !"skill_create".equals(t.name()))
                .collect(Collectors.toList());
        var wanted = new java.util.HashSet<>(plan.tools());
        for (var st : plan.steps()) {
            if (st.tool() != null && !st.tool().isBlank()) wanted.add(st.tool());
        }
        String asked = (plan.goal() == null ? "" : plan.goal()) + "\n"
                + (context.isUnattended() && context.originalMessage() != null ? context.originalMessage() : "");
        var named = all.stream()
                .filter(t -> wanted.contains(t.name()) || namedIn(asked, t.name()))
                .collect(Collectors.toList());
        return named.isEmpty() ? all : named;
    }

    /** Whether the text names the tool as a whole word: web_fetch, not web_fetcher. */
    static boolean namedIn(String text, String name) {
        return java.util.regex.Pattern.compile("(?<![A-Za-z0-9_-])" + java.util.regex.Pattern.quote(name)
                + "(?![A-Za-z0-9_-])").matcher(text).find();
    }

    /** The names the cloud asked for that no tool has; said back to it, so it can correct them. */
    private List<String> unknownTools(DelegationPlan plan) {
        return plan.tools().stream()
                .filter(n -> "skill_create".equals(n) || toolRegistry.find(n).isEmpty())
                .distinct()
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
            boolean gotAll = offered(plan, parentContext).size() == toolRegistry.all().stream()
                    .filter(t -> t != null && !"skill_create".equals(t.name())).count();
            notes += "NOTE: no tool is named " + String.join(", ", unknown) + (gotAll
                    ? ", so it was given every tool. "
                    : ", so the delegation ran without " + (unknown.size() == 1 ? "it" : "them") + ". ")
                    + "Use the exact names from your tool list.\n\n";
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
        // A task holding the user's files: every result is PRIVATE (AgentContext.decide), so the
        // cloud cannot answer from them, and the local model's own answer is kept for the user
        // instead of being withheld and dropped. A delegation without files runs exactly as before.
        boolean fileTask = !parentContext.files().isEmpty();
        List<ToolSpec> specs = nativeTools ? executorTools(parentContext, plan, fileTask) : null;
        log.info("Delegation protocol: {} ({} tools)", nativeTools ? "native" : "json-text",
                offered(plan, parentContext).size());

        int maxSteps = plan.maxSteps() > 0 ? plan.maxSteps() : 10;
        // This delegation's own results, and the only ones the local model can name: {{1}} is
        // its first step, {{2}} its second -- which is what the system prompt tells it, and
        // what a small model does unprompted anyway. Numbering them task-wide instead made that
        // sentence false the moment a run delegated twice, and the second delegation's {{1}}
        // forwarded the first one's traceback as the body of the morning email. Every result is
        // still recorded on the task too, under the task-wide handle the cloud and the ledger see.
        // Whether the local model has read private data yet is the task's fact, not this loop's
        // (AgentContext.localTierReadPrivate): set the moment one of its results is PRIVATE, in
        // this delegation or in an earlier one of this task, which may have written what it read
        // into a file or a note that this one reads back. From then on every result is PRIVATE
        // and not indexed; see AgentContext.decide.
        List<Artifact> mine = new ArrayList<>();
        // The whole conversation, every turn of it, every result in full: nothing is dropped to
        // make room. A delegation that outgrows the model's context window gets a plain error
        // saying so (Ollama is asked never to drop messages itself), and fails like any other --
        // the cloud takes the work back.
        List<LlmMessage> messages = new ArrayList<>();

        // System prompt with plan and tools
        messages.add(LlmMessage.system(buildExecutorSystemPrompt(plan, parentContext, nativeTools,
                fileTask)));

        // Initial instruction. "Start with step 1" makes no sense without a step list.
        String opening = plan.steps().isEmpty()
                ? "Begin. Make the first tool call that moves toward the goal."
                : "Begin executing the plan. Start with step 1.";
        messages.add(LlmMessage.user(opening));

        statusEmitter.emit(parentContext.userId(), StatusMessage.Type.STEP,
                "Delegating to local LLM: " + plan.goal());

        // No output limit is sent (a request cannot carry one): Ollama generates until the model
        // stops or its context window -- the model's own -- is full. Capping output here used to
        // starve thinking models, which spend part of the budget reasoning before they answer.
        // format:json and tools are mutually exclusive in Ollama, so JSON mode is only asked for
        // on the text protocol, where it is what holds the output shape.
        // The task's own hook, as its think and code calls carry: every event of the streamed
        // reply shows the task is alive, so a generation that runs for minutes is not taken for
        // a stall; and a Stop or the watchdog ends the call -- mid-reply, or while the model is
        // still loading and has sent nothing -- rather than when the model finishes.
        LlmRequestConfig request = new LlmRequestConfig(null, null, !nativeTools, specs)
                .withProgress(parentContext.progress());

        for (int step = 0; step < maxSteps; step++) {
            if (parentContext.isCancelled()) {
                // Partial work is not worthless: it is the only record of what the local model
                // managed before the plug was pulled, and throwing it away is why a timed-out
                // delegation used to leave nothing behind at all.
                return partial("Task cancelled during delegation.", mine);
            }

            // THINK: ask local LLM for next action
            LlmResponse response;
            try {
                response = localProvider.chat(messages, request);
            } catch (Exception e) {
                // A reply that is no answer -- cut off at the window, or holding a tool call that
                // cannot be run -- was generated all the same, every token of it.
                if (e instanceof LlmException failed && failed.reply() != null) {
                    parentContext.addLocalTokens(failed.reply().totalTokens());
                }
                if (parentContext.isCancelled()) {
                    return partial("Task cancelled during delegation.", mine);
                }
                // A delegation that outgrew the local model's context window fails with
                // OutputTruncated, whose message names the window and its size.
                String msg = String.valueOf(e.getMessage());
                // After a private read the error can quote it -- a tool-call parse error echoes
                // the model's raw output -- so then neither the cloud nor the log gets the
                // message, only its type. OutputTruncated's message is written by the code (the
                // limit and its size, nothing of the reply), so it is shown whatever was read.
                if (parentContext.localTierReadPrivate() && !(e instanceof OutputTruncated)) {
                    String kept = e.getClass().getSimpleName()
                            + " (its text is kept out: this delegation had read private data)";
                    log.error("Local LLM call failed during delegation step {}: {}", step + 1, kept);
                    return partial("Local LLM call failed: " + kept, mine);
                }
                if (e instanceof MalformedToolCall) {
                    // Its message quotes the model's own arguments: the log gets their length,
                    // as it does for a reply that could not be parsed.
                    log.error("Local LLM call failed during delegation step {}: a tool call that "
                            + "cannot be run ({} chars)", step + 1, msg.length());
                } else {
                    log.error("Local LLM call failed during delegation step {}: {}", step + 1, msg, e);
                }
                return partial("Local LLM call failed: " + msg, mine);
            }

            parentContext.addLocalTokens(response.totalTokens());
            // A local call came back, so this task is not stalled — whatever the loop does with
            // the answer. Marking progress only after a tool EXECUTED meant that a delegation
            // being corrected by its own guards looked identical to a hung one: each refusal
            // costs a full local call at 60-133 seconds, and six in a row reach the 600-second
            // watchdog with the task working normally. It would then be cancelled outright —
            // no email, and the valve never gets the chance to hand the registry back.
            parentContext.markProgress();

            // No text and no tool call: a thinking model can spend its turn reasoning and then
            // stop. Nothing was run, and the model is told exactly that; its turn stays in the
            // transcript as the empty turn it was.
            if (!response.hasToolCalls()
                    && (response.content() == null || response.content().isBlank())) {
                log.warn("Delegation step {}: the local model's reply had no text and no tool "
                                + "call ({} tokens, stop reason {}).", step + 1,
                        response.completionTokens(), response.stopDescription());
                messages.add(LlmMessage.assistant(""));
                messages.add(LlmMessage.user("Your previous reply was empty"
                        + (response.stopReason() == null ? ""
                                : " (stop reason: " + response.stopDescription() + ")")
                        + ": no text and no tool call, so nothing was run. Continue from where "
                        + "the task stands."));
                continue;
            }

            // The turn: every tool call the model made, in its order -- or, on the text protocol,
            // the one action its text holds. A native tool call is the answer; content is then
            // usually empty and that is fine. A tools-capable model can still answer in prose;
            // the text parser is the fallback, not dead code.
            List<ExecutorAction> actions;
            String raw;
            if (response.hasToolCalls()) {
                actions = response.toolCalls().stream().map(LocalExecutor::actionOf).toList();
                raw = renderCalls(response.toolCalls());
            } else {
                raw = response.content();
                actions = List.of(parseExecutorAction(raw));
            }

            // Finishing is finishing, whichever shape it arrives in.
            //
            // When `done` became a tool, the native path learned to recognise it and the text
            // parser did not -- it only ever knew {"done": true}. So a model that wrote
            // {"tool": "done", ...} as text had its finish looked up in the registry, where
            // there is no such tool, and got "Tool 'done' not found". A real delegation did
            // this three times in a row and then died on max steps, having completed the work.
            // It had finished; there was no way to say so.
            actions = actions.stream().map(LocalExecutor::normalizeDone).toList();

            if (actions.size() == 1 && actions.get(0).done) {
                ExecutorAction action = actions.get(0);
                log.info("Delegation completed after {} steps. Summary length: {}",
                        step + 1, action.summary != null ? action.summary.length() : 0);
                // The conclusion AND the rows it was drawn from. The cloud tier is kept for its
                // judgement, and a scheduled task shaped "fetch X, decide whether Y, act" would
                // otherwise have it judge on a small model's paraphrase of the evidence.
                // The claim and the evidence travel together. Without the ledger the cloud reads
                // a summary it cannot check, and scheduled_task_runs.last_result records the
                // claim alone -- so a false success is not even auditable afterwards.
                // On a file task the summary is the user's answer, kept as a PRIVATE result of
                // its own, which the cloud can hand on by its handle without reading it.
                Artifact said = fileTask ? recordAnswer(parentContext, action.summary, mine) : null;
                return completed(action.summary, plan.goal(), mine, said);
            }

            // Every call of the turn, in its order, through the same guards, and none dropped:
            // the model is told about each -- what it returned, or why it did not run. Each is
            // resolved just before it runs, so a call can use a result from earlier in the turn.
            int before = mine.size();
            var replies = new ArrayList<String>();
            for (int i = 0; i < actions.size(); i++) {
                // Stop is looked at before every call, not only before every model call: pressed
                // while one call of the turn runs, it stops the ones after it -- the send after
                // the scan is exactly what Stop is for.
                if (parentContext.isCancelled()) {
                    return partial("Task cancelled during delegation.", mine);
                }
                ExecutorAction action = actions.get(i);
                String reply = action.done
                        ? "Not taken: done has to be the only call of its turn, so that your "
                                + "summary is written after you have seen what the other calls "
                                + "returned. Call it again, on its own, when the goal is reached."
                        : act(action, parentContext, mine, nativeTools);
                String name = action.done ? "done" : action.tool;
                replies.add(actions.size() == 1 ? reply : "[call " + (i + 1) + " of "
                        + actions.size() + (name == null || name.isBlank() ? "" : ": " + name)
                        + "] " + reply);
            }
            String told = String.join("\n\n", replies);
            if (mine.size() > before) {
                // "Passed on verbatim" is false for a private result: the user sees it only
                // through the summary, so the summary is where it has to be written.
                told += "\n\n" + (fileTask
                        ? "Continue, or " + (nativeTools
                                ? "call done with the answer for the user"
                                : "output {\"done\": true, \"summary\": \"the answer for the user\"}")
                                + " — what the tools return here is private and reaches the user "
                                + "only through your summary."
                        : "Continue with the next step, or if all steps are done, " + (nativeTools
                                ? "call done and say what you did — every result above is passed "
                                        + "on verbatim, so do not retype it."
                                : "output {\"done\": true, \"summary\": \"what you did\"}."));
            }
            messages.add(LlmMessage.assistant(raw));
            messages.add(LlmMessage.user(told));
        }

        // Hit max steps without "done"
        log.warn("Delegation hit max steps ({}) for goal: {}", maxSteps, plan.goal());
        return partial("Delegation reached max steps (" + maxSteps + ")",
                mine);
    }

    /**
     * One call of the model's turn, through every guard, and run if none refuses it. Returns what
     * the model is told about it: the result, or why the call did not run.
     */
    private String act(ExecutorAction action, AgentContext parentContext, List<Artifact> mine,
                       boolean nativeTools) {
        if (action.tool == null || action.tool.isBlank()) {
            // Local LLM produced something unparseable — tell it, so it can recover
            return nativeTools
                    ? "That was not a tool call. Call a tool to do the work, or call done "
                            + "with the summary if the goal is already reached."
                    : "Invalid output. You must respond with JSON: "
                            + "{\"tool\": \"name\", \"params\": {...}} to execute a tool, "
                            + "or {\"done\": true, \"summary\": \"...\"} when finished.";
        }

        // Prevent skill_create — local executor cannot create skills
        if ("skill_create".equals(action.tool)) {
            return "ERROR: skill_create is not available during delegation. "
                    + "Only existing tools can be used. Pick a different tool from the plan.";
        }

        // One resolution of the arguments, used by every check below and by the call itself.
        // It was once computed twice, which is how a guard and the thing it guards drift
        // apart.
        References.Resolved refs = References.resolve(action.params, mine);
        Map<String, Object> params = refs.params();

        // A reference that could not be resolved: out of range, a missing field, a failed
        // result, or reference-shaped text that is not the whole value. Each of these used to
        // survive as literal text -- "$1.body" as the entire body of an email, sent, recorded
        // green, with not one line in the log. Refused here, before anything runs.
        if (!refs.ok()) {
            log.warn("Delegation step {}: '{}' — reference refused.", mine.size() + 1,
                    refs.refused());
            return "Not run: the value of '" + refs.refused()
                    + "' would have been sent as literal text. " + refs.reason() + " "
                    + References.available(mine);
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
            return triedHere != null
                    ? "Not run: you already made exactly this " + action.tool + " call in this "
                            + "delegation, and it " + (triedHere.succeeded() ? "succeeded" : "FAILED")
                            + ". A change is attempted once per delegation. Move on, or finish "
                            + "and say what happened."
                    : "Not run: an identical " + action.tool + " call already succeeded earlier "
                            + "in this task. It changes something, so it is never done twice. "
                            + "Move to the next step, or finish.";
        }

        // One thing neither guard catches: a result typed out again by hand instead of passed
        // on by its reference -- which is where a date, a line or a whole section goes missing,
        // and the model cannot see what it dropped. Text it composes itself is its own to send.
        String copied = retyped(target, action.params, mine);
        if (copied != null) {
            log.warn("Delegation step {}: '{}' repeats part of an earlier result without being "
                    + "all of it — refused.", mine.size() + 1, copied);
            return "Not run: the '" + copied + "' value repeats part of an earlier result "
                    + "without being all of it — a copy typed out by hand, which is how a date, a "
                    + "line or a section goes missing. To pass a result on, make the WHOLE value "
                    + "its reference: it is substituted exactly. " + References.available(mine)
                    + " Text you compose yourself is fine; a partial copy of a result is not.";
        }

        // ACT: execute the tool
        statusEmitter.emit(parentContext.userId(), StatusMessage.Type.PROGRESS,
                "Delegate: running " + action.tool + "...");

        long toolStartMs = System.currentTimeMillis();
        ToolResult result = executeToolDirect(action.tool, params, parentContext);
        long toolMs = System.currentTimeMillis() - toolStartMs;
        // A finished call is progress. The calls of a turn run back to back, with no model call
        // between them to say the task is alive, and the stall watchdog would otherwise count
        // their run times together as silence.
        parentContext.markProgress();
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
        boolean wroteAfterPrivate = parentContext.localTierReadPrivate();
        Artifact.Decision decision = parentContext.decide(
                target == null ? List.of() : target.requiredCredentials(), refs.used(),
                wroteAfterPrivate, toolResult);
        Artifact artifact = parentContext.addArtifact(action.tool, action.params, params,
                toolResult, toolOk, decision);
        mine.add(artifact);
        if (artifact.isPrivate()) parentContext.markLocalTierReadPrivate();
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
                mine.size(), artifact.handle() + " " + action.tool, worked ? "OK" : "FAIL",
                toolResult.length(), artifact.label());

        // OBSERVE: the whole result, and its handle every time -- a short result used to arrive
        // without one, so the model had to count for itself, and counting is where {{1}} went
        // wrong.
        return "Tool result " + ArtifactRef.handle(mine.size()) + " [" + action.tool + "] "
                + (worked ? "SUCCESS" : "FAILED") + ":\n" + toolResult;
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

        // Comma-separated names, as the schema asks -- or a list, or a JSON array written as a
        // string, as a model will sometimes send. Tool names are [A-Za-z0-9_-], so anything
        // else separates them.
        List<String> tools = new ArrayList<>();
        Object toolsObj = params.get("tools");
        String listed = toolsObj instanceof List<?> l ? String.join(",", l.stream().map(String::valueOf).toList())
                : toolsObj instanceof String s ? s : "";
        for (String name : listed.split("[^A-Za-z0-9_-]+")) if (!name.isBlank()) tools.add(name);

        return new DelegationPlan(goal, steps, checkpoints, maxSteps, tools);
    }

    // ── Private helpers ──

    /**
     * Build the system prompt for the local executor.
     * Includes the plan, available tools, and constrained output format.
     */
    private String buildExecutorSystemPrompt(DelegationPlan plan, AgentContext context,
                                             boolean nativeTools, boolean fileTask) {
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
            Collection<Tool> availableTools = offered(plan, context);
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
        if (fileTask) {
            // The rule below is false here: a file task's results are withheld from the cloud,
            // so nothing is passed on underneath the summary, and the summary is what the user
            // reads. No file name is given: the prompt says only that the files are handed.
            sb.append("- The user's files are given to every tool you call (as _attached_files);\n");
            sb.append("  you do not pass them.\n");
            sb.append("- What the tools return here is private: the orchestrator is never shown it,\n");
            sb.append("  only told that your answer exists. Your done summary is the answer the user\n");
            sb.append("  reads — write it in full, and copy figures, dates and names exactly as the\n");
            sb.append("  tools returned them.\n");
        } else {
            sb.append("- Your summary says what you DID. Every tool result is passed on verbatim\n");
            sb.append("  underneath it, so never retype data — a date or number written from\n");
            sb.append("  memory is an error that was not in the data.\n");
        }
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
                // A skill's report_progress: shown to the owner, as the cloud path shows it, and
                // progress for the stall watchdog. With no callback the sandbox leaves the line in
                // the skill's stdout.
                (message, percent) -> {
                    context.markProgress();
                    statusEmitter.emitForTask(context.userId(), context.taskId(),
                            StatusMessage.Type.PROGRESS, "Delegate: " + toolName + ": " + message
                                    + (percent == null ? "" : " (" + percent + "%)"));
                },
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
            // Its length, not its text: on a task holding a file this prose is the answer, and
            // the log is read back through the ops API.
            log.warn("LocalExecutor: no JSON found in local LLM response ({} chars)", cleaned.length());
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
            // Not the message: a parser quotes the token it choked on, and on a file task that
            // token can be an account number the model copied from the file.
            log.warn("LocalExecutor: failed to parse local LLM JSON ({}, {} chars)",
                    e.getClass().getSimpleName(), cleaned.length());
            return ExecutorAction.invalid();
        }
    }

    /** A native tool call as an action: {@code done} finishes, any other name is a tool to run. */
    private static ExecutorAction actionOf(ToolCall call) {
        return "done".equals(call.name())
                ? ExecutorAction.done(str(call.arguments().get("summary")))
                : new ExecutorAction(false, null, call.name(), call.arguments());
    }

    /** The model's own tool calls, written back into the history it will read next turn. */
    private String renderCalls(List<ToolCall> calls) {
        var lines = new ArrayList<String>();
        for (ToolCall call : calls) {
            try {
                lines.add(mapper.writeValueAsString(Map.of(
                        "tool", call.name(), "params", call.arguments())));
            } catch (Exception e) {
                lines.add("{\"tool\": \"" + call.name() + "\"}");
            }
        }
        return String.join("\n", lines);
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

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
     * The argument of a side-effecting call that repeats part of an earlier result without being
     * all of it, or null.
     * <p>
     * That is a result typed out again by hand, the way a line or a date goes missing: on 23
     * September the news digest went out as 2,073 characters the local model had written itself
     * from a 2,745-character source. "Repeats" is the canary's own notion -- a
     * {@link PrivateIndex#WINDOW}-character run of a result's text, normalised the way the canary
     * normalises it -- applied to every result this delegation can reference. An argument
     * byte-equal to a result is a perfect copy and passes, however wasteful; so does text the
     * model composed itself, of any length: only a partial or altered copy is refused. A rewrite
     * that shares no such run with any result is, as far as this can tell, composition, and
     * passes too. Checked on the arguments as the model WROTE them, where a reference is a short
     * token that repeats nothing, and only where a reference could have been written instead: a
     * top-level string of a tool that changes something, against results that succeeded.
     */
    static String retyped(Tool tool, Map<String, Object> written, List<Artifact> done) {
        if (tool == null || !tool.hasSideEffects() || written == null) return null;
        List<Artifact> forwardable = done.stream().filter(Artifact::succeeded).toList();
        // The canary's index as the matcher, over results of a whole window or more: a shorter
        // one is registered as a whole string, and a run shorter than a window is not a repeat.
        var index = new PrivateIndex();
        for (int i = 0; i < forwardable.size(); i++) {
            String out = forwardable.get(i).output();
            if (PrivateIndex.normalise(out).length() >= PrivateIndex.WINDOW) {
                index.addPrivate(i + 1, out);
            }
        }
        if (index.isEmpty()) return null;
        for (var e : written.entrySet()) {
            if (!(e.getValue() instanceof String v)) continue;
            if (forwardable.stream().anyMatch(r -> v.equals(r.output()))) continue;
            if (index.firstHitIn(v) != null) return e.getKey();
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
        return completed(localSummary, goal, results, null);
    }

    /**
     * {@link #completed(String, String, List)}, with the local model's answer when one was kept.
     *
     * @param said the answer recorded by {@link #recordAnswer}, or null. The cloud is told its
     *             handle and size, never its text, and it joins what the delegation produced.
     */
    static Outcome completed(String localSummary, String goal, List<Artifact> results, Artifact said) {
        boolean anyFailed = results.stream().anyMatch(r -> !r.succeeded());
        boolean anyPrivate = results.stream().anyMatch(Artifact::isPrivate);
        String touched = results.stream().filter(Artifact::isPrivate).map(Artifact::handle)
                .collect(Collectors.joining(", "));
        // The local model's own prose is withheld when it has read private content: it is a
        // paraphrase of that content, and a paraphrase is the one thing the canary cannot see.
        // The descriptors, the ledger and the PUBLIC outputs remain, which is what the cloud
        // decides on. All-PUBLIC delegations read exactly as before.
        String summary;
        if (said != null) {
            // Withheld all the same, but not lost: it is the answer, and the cloud can deliver it
            // by its handle. Said here, because a handle the cloud was never told about is one it
            // cannot use.
            String k = said.handle();
            summary = "(The local model's answer is " + k + ": private, "
                    + String.format(Locale.ROOT, "%,d", said.output().length())
                    + " characters, written after reading " + touched + ". You are not shown it. "
                    + "To give it to the user, make " + k + " the whole of respond's message; its "
                    + "text is filled in on this machine. To send it somewhere, make " + k
                    + " the whole value of a tool argument.)";
        } else if (anyPrivate) {
            summary = "(local summary withheld — this delegation touched " + touched + ")";
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
        // is a first-time action. So what already succeeded goes FIRST, the first thing the
        // cloud reads, not a tick buried in a ledger.
        String head = "";
        if (anyFailed && !results.isEmpty()) {
            String ran = results.stream().filter(Artifact::succeeded).map(r -> r.tool())
                    .distinct().collect(Collectors.joining(", "));
            if (!ran.isBlank()) {
                head = "ALREADY DONE — these succeeded and must NOT be repeated: " + ran
                        + ". Anything below that failed is what is left to do.\n\n";
            }
        }
        // The answer is among what the delegation produced, so the step's artifacts and the task
        // page show it; the ledger and the verdict stay about the tools that ran.
        List<Artifact> produced = new ArrayList<>(results);
        if (said != null) produced.add(said);
        return new Outcome(head + summary + ledger(results) + verbatimFailures(results),
                toolNames(results), results.size(),
                // A step that threw means the cloud should have the registry back: rewriting a
                // skill from its traceback is the self-learning loop this project exists for,
                // and it cannot run through a paraphrase. Marking the delegation failed is what
                // restores the registry and engages the repair path.
                !results.isEmpty() && !anyFailed, List.copyOf(produced));
    }

    /**
     * Keep the local model's answer on a file task, as a PRIVATE result of the task; null when
     * there is none to keep -- it read nothing private, or wrote nothing.
     * <p>
     * Everything a file task's tools return is withheld from the cloud, so the cloud cannot
     * answer from it; the one text written from it is this summary, and it used to be withheld
     * and then dropped, which left the user with no answer at all. Kept, it is a handle the cloud
     * can deliver without reading. Indexed, so the canary looks for it in every later request of
     * the task. In the task's numbering, like any summary.
     */
    static Artifact recordAnswer(AgentContext context, String summary, List<Artifact> mine) {
        if (summary == null || summary.isBlank() || mine.stream().noneMatch(Artifact::isPrivate)) {
            return null;
        }
        String read = mine.stream().filter(Artifact::isPrivate).map(Artifact::handle)
                .collect(Collectors.joining(", "));
        String text = References.proseForTask(summary, mine);
        return context.addArtifact("local_answer", Map.of(), Map.of(), text, true,
                new Artifact.Decision(com.ownclaw.privacy.Label.PRIVATE,
                        List.of("written by the local model after reading " + read)));
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
              .append(r.isPrivate() ? r.describe() : r.output());
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
                        .append(r.isPrivate() ? r.describe() : r.output()).append("\n");
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
                        .append("\n").append(r.output()).append("\n\n");
            }
        }
        return sb.toString();
    }

    private static String getStr(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v != null ? v.toString() : null;
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
