package com.ownclaw.agent;

import com.ownclaw.agent.tools.*;
import com.ownclaw.llm.*;
import com.ownclaw.observability.ChatStatusEmitter;
import com.ownclaw.observability.ChatStatusEmitter.StatusMessage;
import com.ownclaw.privacy.PrivateIndex;
import com.ownclaw.privacy.Redactor;
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
 *   <li>System prompt provides the plan, the earlier results the goal names, and the tools</li>
 *   <li>Local LLM picks the next tool call following the plan</li>
 *   <li>Executor runs the tool and feeds result back</li>
 *   <li>Repeat, as many turns as the work takes, until the local model says "done" -- or the
 *       task is stopped, a local call fails, or {@link AgentLoop#NOTHING_TO_RUN_IN_A_ROW} turns
 *       in a row run nothing</li>
 * </ol>
 */
@Component
public class LocalExecutor {



    private static final Logger log = LoggerFactory.getLogger(LocalExecutor.class);

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
     * the orchestrator: every result is withheld from it, so the summary is the answer the user
     * reads -- the orchestrator's too, unless it quotes them (see {@link #recordAnswer}) -- and an
     * outline would be all they got.
     */
    private static final ToolSpec DONE_FILE = new ToolSpec("done",
            "Call this when the goal is reached. Your summary is the answer the user reads. The "
                    + "orchestrator is never shown the results, and reads your summary only when "
                    + "it quotes none of them. Write it in full, copying figures, dates and names "
                    + "exactly as the tools returned them.",
            ToolSchemas.toJsonSchema(Map.of("summary",
                    ToolParam.required("string", "The answer for the user, in full."))));

    /** What the local model may call: the tools this delegation is given, plus {@code done}. */
    private List<ToolSpec> executorTools(AgentContext context, DelegationPlan plan, List<Artifact> given,
                                         boolean fileTask) {
        return new ArrayList<>(ToolSchemas.build(List.of(fileTask ? DONE_FILE : DONE),
                offered(plan, given, context), context.credentialKeys()));
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
    Collection<Tool> offered(DelegationPlan plan, List<Artifact> given, AgentContext context) {
        // Handed results to read and asked to run nothing: it reads and answers, with done alone.
        // Offered the whole registry instead -- 35 tools, about 20,000 tokens of definitions -- the
        // local model spent about four minutes of every turn reading tools it did not need (about
        // 100 tokens a second on this host), and was drawn into calling them: on 2026-10-01 such
        // reads took 10 to 14 minutes, and one ended with no answer.
        if (readsWhatItWasGiven(plan, given)) return List.of();
        var all = toolRegistry.all().stream()
                .filter(t -> t != null && !"skill_create".equals(t.name()))
                .collect(Collectors.toList());
        var wanted = new java.util.HashSet<>(plan.tools());
        for (var st : plan.steps()) {
            if (st.tool() != null && !st.tool().isBlank()) wanted.add(st.tool());
        }
        // The calls ran before the model was given anything; it goes on from their results, and
        // may need the same tools again.
        for (var call : plan.calls()) wanted.add(call.tool());
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
     * @param billed        the asking task's account, handed each reply the local model was
     *                      billed for -- the one a cut-off or unusable call carries too -- and the
     *                      provider that served it
     * @return consolidated result string (success or error description)
     */
    public Outcome execute(DelegationPlan plan, AgentContext parentContext,
                           java.util.function.BiConsumer<LlmProvider, LlmResponse> billed) {
        return execute(plan, parentContext, billed, java.util.function.Supplier::get);
    }

    /**
     * As above, the local model taken through {@code lane} for the part of the delegation that
     * needs it: the plan's exact calls run first, by code, outside it ({@link #runCalls}). The
     * local model works on one task at a time and a task keeps it once taken, so a delegation of
     * calls alone that waited for it waited behind another chat's whole task -- 66 minutes on
     * 2026-11-10 -- for work that needs no model at all.
     *
     * @param lane runs the local model's part, once the local model is this task's
     */
    public Outcome execute(DelegationPlan plan, AgentContext parentContext,
                           java.util.function.BiConsumer<LlmProvider, LlmResponse> billed,
                           java.util.function.Function<java.util.function.Supplier<Outcome>, Outcome> lane) {
        // The earlier results the goal names are given to the delegation, whole: reading what the
        // cloud is shown only as a description is what the local model is for. A name that is no
        // result is taken out, and the cloud is told.
        Given given = given(plan, parentContext.artifacts());
        List<Artifact> ran = new ArrayList<>();
        Outcome outcome = runCalls(given.plan(), given.results(), parentContext, ran);
        if (outcome == null) {
            // Stopped after the calls ran -- before the local model was taken, or while waiting
            // for it behind another task: what ran is the cloud's to know, as a partial result.
            // Thrown on out of here instead, the step was never recorded and the calls' changes
            // were nowhere in what the cloud read.
            if (!ran.isEmpty() && parentContext.isCancelled()) {
                outcome = partial("Task cancelled during delegation.", ran);
            } else {
                try {
                    outcome = lane.apply(() -> run(given.plan(), given.results(), parentContext, billed, ran));
                } catch (com.ownclaw.core.TaskCancellationService.TaskCancelledException stopped) {
                    if (ran.isEmpty()) throw stopped;
                    outcome = partial("Task cancelled during delegation.", ran);
                }
            }
        }
        String notes = "";
        if (given.missing() > 0) {
            log.warn("Delegation goal named {} result(s) that do not exist; taken out.", given.missing());
            notes += MISSING_REFERENCE_NOTE;
        }
        List<String> unknown = unknownTools(plan);
        if (!unknown.isEmpty()) {
            log.warn("Delegation asked for tools that do not exist: {}", unknown);
            boolean gotAll = offered(plan, given.results(), parentContext).size() == toolRegistry.all().stream()
                    .filter(t -> t != null && !"skill_create".equals(t.name())).count();
            notes += "NOTE: no tool is named " + String.join(", ", unknown) + (gotAll
                    ? ", so it was given every tool. "
                    : ", so the delegation ran without " + (unknown.size() == 1 ? "it" : "them") + ". ")
                    + "Use the exact names from your tool list.\n\n";
        }
        if (notes.isEmpty()) return outcome;
        return new Outcome(notes + outcome.text(), outcome.toolsRun(),
                outcome.stepCount(), outcome.ok(), outcome.produced(), outcome.byCode());
    }

    private static final String MISSING_REFERENCE_NOTE = "NOTE: the delegation's goal named "
            + "results that do not exist, so those references were taken out of what it was given. "
            + "Name an earlier result by the handle an observation gave it ({{N}}); every result "
            + "the goal names is given to the delegation whole.\n\n";

    /**
     * A plan as the local model is given it, the earlier results it names, in the order it first
     * names them, and how many of its references name no result.
     */
    record Given(DelegationPlan plan, List<Artifact> results, int missing) {}

    /**
     * The plan as the local model is given it, with the earlier results it names. A well-formed
     * reference in its prose -- the goal, a step's description, a checkpoint -- to a result of
     * the task gives that result to the delegation, whole, in its prompt, and is written in
     * words ("result 4"). Left as {{4}}, the local model would read it as its own fourth result,
     * which is how a second delegation once forwarded the first one's traceback as the body of
     * the morning email. A reference to no result is taken out and counted. Step PARAMETERS are
     * left alone: in a plan, {{1}} there means the plan's own step 1, which is exactly how the
     * delegation numbers. So are the texts, and a text's {{name}} wherever it is written: it is
     * no result's handle, and the local model writes it in a call to insert the text. A call's
     * result named in the prose, {{call2}}, is the local model's own result 2: the calls run first,
     * and their results are its first ({@link #runCalls}). One naming no call is taken out and
     * counted. The calls themselves are left alone; code resolves them in the task's numbering.
     */
    static Given given(DelegationPlan plan, List<Artifact> results) {
        var named = new java.util.LinkedHashMap<Integer, Artifact>();
        int[] missing = {0};
        java.util.function.UnaryOperator<String> inWords = text -> {
            if (text == null) return null;
            var m = ArtifactRef.TOKEN.matcher(text);
            var sb = new StringBuilder();
            while (m.find()) {
                ArtifactRef ref = ArtifactRef.parse(m.group());
                String words;
                if (ref != null && ref.handle() <= results.size()) {
                    named.putIfAbsent(ref.handle(), results.get(ref.handle() - 1));
                    words = TaskRecord.inWords(m.group());
                } else {
                    missing[0]++;
                    words = "(a result that does not exist)";
                }
                m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(words));
            }
            m.appendTail(sb);
            // After the task's handles are in words, so a call's is not read as one of them.
            var c = CALL_REF.matcher(sb.toString());
            var out = new StringBuilder();
            while (c.find()) {
                int k = Integer.parseInt(c.group(1));
                String own;
                if (k >= 1 && k <= plan.calls().size()) {
                    own = "{{" + k + c.group(2).strip() + "}}";
                } else {
                    missing[0]++;
                    own = "(a result that does not exist)";
                }
                c.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(own));
            }
            c.appendTail(out);
            return out.toString();
        };
        String goal = inWords.apply(plan.goal());
        var steps = new ArrayList<DelegationPlan.Step>();
        for (var st : plan.steps()) {
            steps.add(new DelegationPlan.Step(inWords.apply(st.description()), st.tool(), st.params()));
        }
        var checkpoints = plan.checkpoints() == null ? List.<String>of()
                : plan.checkpoints().stream().map(inWords).toList();
        return new Given(new DelegationPlan(goal, steps, checkpoints, plan.tools(), plan.texts(), plan.calls()),
                List.copyOf(named.values()), missing[0]);
    }

    /**
     * The plan's exact calls, run by code in their order before the local model is given anything,
     * each through the same guards as a call the local model makes ({@link #act}) but the one
     * against retyping: the cloud wrote it whole, so nothing of it was copied by hand. Its
     * arguments are resolved as the cloud writes everything ({@link References#resolveExactly}):
     * {@code {{N}}} is the task's result N, and {@code {{call2}}} the result of call 2 of the
     * list ({@link #withCallHandles}). The results are also the local model's first, {@code {{1}}}
     * on, should it go on with a goal.
     * <p>
     * The local model used to type every call itself, at a few tokens a second, even one the cloud
     * had written out exactly -- most delegations' goals were such a list in prose. The daily news
     * run did 22 seconds of work in four minutes or more, and a copied JSON argument came back
     * broken twice on 2026-11-10. Run by code, the call is the one the cloud wrote.
     * <p>
     * They stop at the first that fails or is refused: a later call can need its result, and what
     * the cloud planned on no longer holds. The cloud is told what ran, what stopped them, and
     * that the rest did not run; the local model is not given the goal.
     *
     * @param mine the delegation's results, which the calls' join
     * @return how the delegation ends: with the calls when they are all of it, or when one of them
     *         stopped it; null when the local model is to go on with the goal
     */
    private Outcome runCalls(DelegationPlan plan, List<Artifact> given, AgentContext parentContext,
                             List<Artifact> mine) {
        List<DelegationPlan.Call> calls = plan.calls();
        if (calls.isEmpty()) return null;
        for (int i = 0; i < calls.size(); i++) {
            if (parentContext.isCancelled()) return byCode(partial("Task cancelled during delegation.", mine));
            DelegationPlan.Call call = calls.get(i);
            // {{call2}} as the task's handle of call 2's result, before anything else reads the call.
            String[] early = {null};
            Object params = withCallHandles(call.params(), mine, i, early);
            if (early[0] != null) {
                return callsStopped(plan, i, "call " + (i + 1) + " (" + call.tool() + ") was not run: '" + early[0]
                        + "' names a call that has not run before it. A call's result is named in a later call: "
                        + "{{call1}} in call 2 or after.", mine);
            }
            int had = mine.size();
            @SuppressWarnings("unchecked")
            Map<String, Object> args = (Map<String, Object>) params;
            String reply = act(new ExecutorAction(false, null, call.tool(), args), parentContext,
                    mine, given, true, plan, i + 1, null, true);
            if (mine.size() == had) {
                return callsStopped(plan, i, "call " + (i + 1) + " (" + call.tool() + ") was not run. " + reply, mine);
            }
            if (!mine.get(mine.size() - 1).succeeded()) {
                return callsStopped(plan, i, "call " + (i + 1) + " (" + call.tool() + ") failed: its error is "
                        + "under the failed steps below.", mine);
            }
        }
        if (!plan.goal().isBlank()) return null;
        log.info("Delegation completed: {} call(s) run by code, no goal for the local model.", calls.size());
        return byCode(completed("", callsRun(calls.size()), List.of(), mine, null));
    }

    /**
     * {@code {{call2}}} and {@code {{call2.field}}} in a call the cloud wrote: the result of call 2
     * of the same list. Everywhere else the cloud reads {@code {{N}}} as the task's result N --
     * every observation and descriptor says so -- and a call's results do not exist yet when it
     * writes them, so they have a name of their own, which code turns into the task's handle.
     * "call" followed by digits is no text's name ({@link #texts}).
     */
    static final java.util.regex.Pattern CALL_REF = java.util.regex.Pattern.compile(
            "\\{\\{[\\s\\u00A0\\u200B\\u200C\\u200D\\u2060\\uFEFF]*\\$?[\\s\\u00A0\\u200B\\u200C\\u200D\\u2060\\uFEFF]*(?i:call)(\\d{1,9})"
                    + "((?:\\.[^{}]*?)?)[\\s\\u00A0\\u200B\\u200C\\u200D\\u2060\\uFEFF]*\\}\\}");

    /**
     * {@code v} with each {@code {{callK}}} in it, at any depth, written as the task's handle of
     * call K's result -- K one of the {@code before} calls that ran, whose results begin
     * {@code ran}. One naming a call that has not run sets {@code early[0]} to it.
     */
    static Object withCallHandles(Object v, List<Artifact> ran, int before, String[] early) {
        if (v instanceof String s) {
            var m = CALL_REF.matcher(s);
            var sb = new StringBuilder();
            boolean any = false;
            while (m.find()) {
                int k = Integer.parseInt(m.group(1));
                if (k < 1 || k > before || k > ran.size()) {
                    early[0] = m.group().strip();
                    return v;
                }
                m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(
                        "{{" + ran.get(k - 1).n() + m.group(2).strip() + "}}"));
                any = true;
            }
            if (!any) return s;
            m.appendTail(sb);
            return sb.toString();
        }
        if (v instanceof Map<?, ?> map) {
            var out = new LinkedHashMap<String, Object>();
            for (var e : map.entrySet()) {
                out.put(String.valueOf(e.getKey()), withCallHandles(e.getValue(), ran, before, early));
                if (early[0] != null) return v;
            }
            return out;
        }
        if (v instanceof Collection<?> list) {
            var out = new ArrayList<Object>();
            for (Object x : list) {
                out.add(withCallHandles(x, ran, before, early));
                if (early[0] != null) return v;
            }
            return out;
        }
        return v;
    }

    /** The outcome as one the cloud's calls ended, the local model given nothing ({@link Outcome#byCode}). */
    private static Outcome byCode(Outcome o) {
        return new Outcome(o.text(), o.toolsRun(), o.stepCount(), o.ok(), o.produced(), true);
    }

    /**
     * What of the calls already succeeded, first, as {@link #completed} puts it: a send that went
     * out must not be sent again by a cloud told the calls stopped. Empty when none did.
     */
    private static String alreadyDone(List<Artifact> ran) {
        String done = ran.stream().filter(Artifact::succeeded).map(Artifact::tool).distinct()
                .collect(Collectors.joining(", "));
        return done.isBlank() ? "" : "ALREADY DONE — these succeeded and must NOT be repeated: " + done
                + ". What failed or did not run is what is left to do.\n\n";
    }

    /** What a delegation of calls alone was for, as its report names it. */
    private static String callsRun(int calls) {
        return (calls == 1 ? "the call" : "the " + calls + " calls") + " you gave, run in your order by code";
    }

    /**
     * The calls stopped at call {@code at} (from 0): what already succeeded first, so it is not
     * done twice ({@link #completed}), then why they stopped, what did not run, and every result.
     */
    private static Outcome callsStopped(DelegationPlan plan, int at, String why, List<Artifact> ran) {
        int after = plan.calls().size() - at - 1;
        String rest = after == 0 ? "" : " The " + (after == 1 ? "call" : after + " calls") + " after it did not run.";
        if (!plan.goal().isBlank()) rest += " The local model was not given the goal.";
        String text = alreadyDone(ran) + "Delegation stopped at call " + (at + 1) + " of " + plan.calls().size()
                + ": " + why + rest + "\n\n"
                + (ran.isEmpty() ? "" : "Results of the calls that ran:\n\n" + resultsForCloud(ran))
                + ledger(ran) + verbatimFailures(ran, true);
        log.warn("Delegation stopped at call {} of {}.", at + 1, plan.calls().size());
        return new Outcome(text, toolNames(ran), ran.size(), false, List.copyOf(ran), true);
    }

    /**
     * @param given the earlier results the goal names, which the local model reads in its prompt
     * @param ran   the results of the plan's calls, run by code before this ({@link #runCalls}):
     *              the local model's first results, {@code {{1}}} on, as if it had made the calls
     */
    private Outcome run(DelegationPlan plan, List<Artifact> given, AgentContext parentContext,
                        java.util.function.BiConsumer<LlmProvider, LlmResponse> billed, List<Artifact> ran) {
        LlmProvider localProvider = llmRouter.local();
        if (!localProvider.isAvailable()) {
            return ran.isEmpty()
                    ? Outcome.failed("ERROR: Local LLM (Ollama) is not available. Cannot execute delegation.")
                    : partial("the local model is not available, so the goal was not worked on. "
                            + "Your calls ran; their results are below.", ran);
        }
        // Reading a private result is reading private data: from here every result of the task's
        // delegations is PRIVATE (AgentContext.decide), and what the local model writes reaches
        // the cloud only when it quotes none of what it read (recordAnswer).
        if (given.stream().anyMatch(Artifact::isPrivate)) parentContext.markLocalTierReadPrivate();

        // The tool manifest is the largest thing in this prompt and num_ctx is the binding
        // constraint on this hardware, so when the model can take tools as structure, send them
        // as structure and drop the prose copy.
        boolean nativeTools = localProvider.supportsTools();
        // A task holding the user's files: every result is PRIVATE (AgentContext.decide), so the
        // cloud cannot answer from them, and the local model's summary is the user's answer -- the
        // model is told so. The user is given it whether or not the cloud places it, and when it
        // quotes the files it stays private (recordAnswer, AgentLoop.withLocalAnswers).
        boolean fileTask = !parentContext.files().isEmpty();
        List<ToolSpec> specs = nativeTools ? executorTools(parentContext, plan, given, fileTask) : null;
        log.info("Delegation protocol: {} ({} tools)", nativeTools ? "native" : "json-text",
                offered(plan, given, parentContext).size());

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
        List<Artifact> mine = new ArrayList<>(ran);
        // The whole conversation, every turn of it, every result in full: nothing is dropped to
        // make room. A turn that outgrows the model's context window gets a plain error saying so
        // (Ollama is asked never to drop messages itself); the largest results are then folded
        // and the turn asked again (Fit), and only when nothing is left to fold does the
        // delegation fail like any other -- the cloud takes the work back.
        List<LlmMessage> messages = new ArrayList<>();
        // Fitting the conversation to the model's window (Fit): this delegation's results fold
        // as the task's do, read back with read_result.
        var fit = new Fit();

        // System prompt with plan and tools
        messages.add(LlmMessage.system(buildExecutorSystemPrompt(plan, given, parentContext,
                nativeTools, fileTask)));

        if (ran.isEmpty()) {
            // Initial instruction. "Start with step 1" makes no sense without a step list.
            String opening = readsWhatItWasGiven(plan, given)
                    ? "Read the results you were given and answer the goal with done."
                    : plan.steps().isEmpty()
                    ? "Begin. Make the first tool call that moves toward the goal."
                    : "Begin executing the plan. Start with step 1.";
            messages.add(LlmMessage.user(opening));
        } else {
            // The calls as the model's own first turn, and what they returned as that turn's
            // results: the shape of every later turn, so they are its {{1}} on, read the way it
            // reads its own, and fold like them.
            messages.add(LlmMessage.user("The orchestrator's exact calls were run for you first, in its "
                    + "order. What they returned follows; then work toward the goal from there."));
            // In its own numbering.
            messages.add(LlmMessage.assistant(shownCalls(plan.calls(), ran, parentContext.artifacts()).text()));
            var replies = new ArrayList<String>();
            for (int i = 0; i < ran.size(); i++) {
                Artifact a = ran.get(i);
                String reply = toldResult(a, i + 1);
                replies.add(ran.size() == 1 ? reply
                        : "[call " + (i + 1) + " of " + ran.size() + ": " + a.tool() + "] " + reply);
                fit.replied(a, i + 1, reply);
            }
            messages.add(LlmMessage.user(String.join("\n\n", replies) + "\n\n" + goOn(fileTask, nativeTools)));
            fit.told(messages.size() - 1);
        }

        // A line for the owner, not the goal: that is the cloud's instructions to the local model,
        // which the task page shows whole, under the step.
        statusEmitter.emitForTask(parentContext.userId(), parentContext.taskId(), StatusMessage.Type.STEP,
                "Local model working…");

        // No output limit is sent (a request cannot carry one): Ollama generates until the model
        // stops or its context window -- the model's own -- is full. Capping output here used to
        // starve thinking models, which spend part of the budget reasoning before they answer.
        // format:json and tools are mutually exclusive in Ollama, so JSON mode is only asked for
        // on the text protocol, where it is what holds the output shape.
        // Each turn's call carries the task's own hook, as its think and code calls do
        // (AgentContext#call): while the call is under way the stall watchdog does not count the
        // time (AgentContext#msSinceLastProgress), so neither a generation that runs for minutes
        // nor the model loading and reading a long prompt, sending nothing, is taken for a stall;
        // a Stop ends the call -- mid-reply, or before the model has sent anything -- rather than
        // when the model finishes; and the owner is shown the reply as it grows (LiveCall).
        // At the task's thinking effort: at low the local model answers without reasoning first,
        // on every turn of the delegation.
        LlmRequestConfig request = new LlmRequestConfig(null, null, !nativeTools, specs)
                .withEffort(parentContext.options().effort());
        // Handed results to read and no tool to run: the work is reading them and answering, which
        // the model does well without reasoning first. With reasoning it spent eleven minutes on a
        // DHCP report on 2026-10-01 and then answered nothing; a delegation that runs tools keeps
        // it, since choosing and ordering calls is where the reasoning pays.
        if (readsWhatItWasGiven(plan, given)) request = request.answeringDirectly();

        // Turns in a row that ran nothing: no tool ran in them -- the reply was empty, held no tool
        // call, or every call it held was refused.
        int nothingInARow = 0;
        for (int turn = 1; ; turn++) {
            if (parentContext.isCancelled()) {
                // Partial work is not worthless: it is the only record of what the local model
                // managed before the plug was pulled, and throwing it away is why a timed-out
                // delegation used to leave nothing behind at all.
                return partial("Task cancelled during delegation.", mine);
            }

            // THINK: ask local LLM for next action
            LlmResponse response;
            LiveCall live = parentContext.call(localProvider, true, parentContext.atStep("delegation turn " + turn));
            try {
                response = localProvider.chat(messages, request.withProgress(live));
            } catch (Exception e) {
                // A reply that is no answer -- cut off at the window, or holding a tool call that
                // cannot be run -- was generated all the same, every token of it.
                if (e instanceof LlmException failed) billed.accept(localProvider, failed.reply());
                if (parentContext.isCancelled()) {
                    return partial("Task cancelled during delegation.", mine);
                }
                // Longer than the window: the largest results fold and the turn is asked again, as
                // the task's own steps do (AgentLoop) -- for as long as a result is left to fold.
                if (e instanceof OutputTruncated cut && cut.limit() == OutputTruncated.Limit.CONTEXT_WINDOW
                        && !ContextFit.replyFilledIt(cut)) {
                    List<String> folded = fit.fold(messages, specs, cut.tokens());
                    if (!folded.isEmpty()) {
                        log.warn("Delegation turn {}: the conversation did not fit the local model's context "
                                + "window ({}); folded {} and asking again", turn, cut.getMessage(), folded);
                        parentContext.chat().folded(folded, "the local model's");
                        parentContext.markProgress();
                        if (nativeTools && !fit.offered) {
                            specs = new ArrayList<>(specs);
                            specs.add(READ_RESULT);
                            request = request.withTools(specs);
                            fit.offered = true;
                        }
                        continue;
                    }
                }
                // A reply that ran on until it filled the window, with nothing left to fold, ended
                // there: the delegation fails, as it does on any failed call, and its work goes back
                // to the model that delegated it -- the cloud's, unless the task runs on the local
                // model. The owner, who watched it grow, is told why it stopped.
                if (e instanceof OutputTruncated cut && cut.replyRanOn()) {
                    parentContext.chat().ranOn(cut, live,
                            parentContext.options().localOnly() || parentContext.onLocal()
                                    ? "the delegation has failed" : "the cloud model takes over");
                }
                // A delegation that outgrew the local model's context window with nothing left to
                // fold fails with OutputTruncated, whose message names the window and its size.
                String msg = String.valueOf(e.getMessage());
                // After a private read the error can quote it -- a tool-call parse error echoes
                // the model's raw output -- so then neither the cloud nor the log gets the
                // message, only its type. The message of OutputTruncated is written by the code (a
                // limit and its size), nothing of the reply, so it is shown whatever was read.
                if (parentContext.localTierReadPrivate() && !(e instanceof OutputTruncated)) {
                    String kept = e.getClass().getSimpleName()
                            + " (its text is kept out: this delegation had read private data)";
                    log.error("Local LLM call failed during delegation turn {}: {}", turn, kept);
                    return partial("Local LLM call failed: " + kept, mine);
                }
                if (e instanceof MalformedToolCall) {
                    // Its message quotes the model's own arguments: the log gets their length,
                    // as it does for a reply that could not be parsed.
                    log.error("Local LLM call failed during delegation turn {}: a tool call that "
                            + "cannot be run ({} chars)", turn, msg.length());
                } else {
                    log.error("Local LLM call failed during delegation turn {}: {}", turn, msg, e);
                }
                return partial("Local LLM call failed: " + msg, mine);
            } finally {
                live.close();
            }

            billed.accept(localProvider, response);
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
                log.warn("Delegation turn {}: the local model's reply had no text and no tool "
                                + "call ({} tokens, stop reason {}).", turn,
                        response.completionTokens(), response.stopDescription());
                if (++nothingInARow == AgentLoop.NOTHING_TO_RUN_IN_A_ROW) return ranNothing(mine);
                messages.add(LlmMessage.assistant(""));
                messages.add(LlmMessage.user(ThinkingEngine.emptyReply(response)
                        + " Continue from where the task stands."));
                continue;
            }

            // The turn: every tool call the model made, in its order -- natively, or written as
            // text, on the text protocol or by a tools-capable model answering in text anyway,
            // which is what the text parser is there for. A native tool call is the answer; content
            // is then usually empty and that is fine.
            List<ExecutorAction> actions;
            String raw;
            // What the model wrote beside its native calls: the sentence the owner reads above the
            // turn's first call ({@link TaskChat#localTurn}). A turn written as text is its calls.
            String words = null;
            if (response.hasToolCalls()) {
                actions = response.toolCalls().stream().map(LocalExecutor::actionOf).toList();
                raw = renderCalls(response.toolCalls());
                words = response.content();
            } else {
                raw = response.content();
                actions = parseExecutorActions(raw);
                // Offered its tools as structure, a model that writes prose and calls nothing has
                // finished, and the prose is its answer. Read as "not a tool call", that answer was
                // thrown away and the turn counted as one that ran nothing; three such turns ended
                // the delegation with no answer at all. On the text protocol a call has to be
                // written as JSON, so prose there is still not a call.
                if (nativeTools && actions.size() == 1 && isNoCall(actions.get(0))) {
                    actions = List.of(ExecutorAction.done(raw.strip()));
                }
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
                log.info("Delegation completed after {} turns. Summary length: {}",
                        turn, action.summary != null ? action.summary.length() : 0);
                // The conclusion AND the rows it was drawn from. The cloud tier is kept for its
                // judgement, and a scheduled task shaped "fetch X, decide whether Y, act" would
                // otherwise have it judge on a small model's paraphrase of the evidence.
                // The claim and the evidence travel together. Without the ledger the cloud reads
                // a summary it cannot check, and scheduled_task_runs.last_result records the
                // claim alone -- so a false success is not even auditable afterwards.
                // A summary that quotes private data the model read is withheld from the cloud,
                // and kept as a PRIVATE result of its own, which the cloud can hand on by its
                // handle without reading it.
                Artifact said = recordAnswer(parentContext, action.summary, given, mine, fileTask);
                return completed(action.summary, plan.goal(), given, mine, said);
            }

            // Every call of the turn, in its order, through the same guards, and none dropped:
            // the model is told about each -- what it returned, or why it did not run. Each is
            // resolved just before it runs, so a call can use a result from earlier in the turn.
            // Such a reference is the number the model counted on when it wrote the turn, before
            // anything ran: a call that is refused leaves no result, and every result after it
            // would be numbered one lower than counted -- the page meant for one recipient mailed
            // to another, recorded as sent. So after a call that did not run, the rest of the
            // turn does not run either, and the model is told why.
            int before = mine.size();
            var replies = new ArrayList<String>();
            int didNotRun = 0;
            boolean read = false;
            for (int i = 0; i < actions.size(); i++) {
                // Stop is looked at before every call, not only before every model call: pressed
                // while one call of the turn runs, it stops the ones after it -- the send after
                // the scan is exactly what Stop is for.
                if (parentContext.isCancelled()) {
                    return partial("Task cancelled during delegation.", mine);
                }
                ExecutorAction action = actions.get(i);
                String reply;
                if (action.done) {
                    reply = "Not taken: done has to be the only call of its turn, so that your "
                            + "summary is written after you have seen what the other calls "
                            + "returned. Call it again, on its own, when the goal is reached.";
                } else if (didNotRun > 0) {
                    reply = "Not run: call " + didNotRun + " of this turn did not run, so the "
                            + "results after it would not have had the numbers you counted on "
                            + "when you wrote them. Make this call again if it is still needed, "
                            + "numbering from the results you have now.";
                } else if (Fit.READ.equals(action.tool)) {
                    // A read of a result is no result: nothing is numbered after it, and it ran.
                    reply = fit.read(action.params, mine, messages, specs);
                    fit.readReplied(reply);
                    read = true;
                } else {
                    int had = mine.size();
                    reply = act(action, parentContext, mine, given, nativeTools, plan, turn, words, false);
                    if (mine.size() == had) didNotRun = i + 1;
                    else {
                        words = null;   // said once, above the call that ran
                        fit.replied(mine.get(mine.size() - 1), mine.size(), reply);
                    }
                }
                String name = action.done ? "done" : action.tool;
                replies.add(actions.size() == 1 ? reply : "[call " + (i + 1) + " of "
                        + actions.size() + (name == null || name.isBlank() ? "" : ": " + name)
                        + "] " + reply);
            }
            // A turn in which no tool ran -- each of its calls was no call, or was refused -- ran
            // nothing, as an empty one did, whatever it named.
            if (mine.size() == before && !read) {
                // Which calls the turn held, by name only (a refusal's text can quote what was read):
                // without it, three turns that ran nothing could not be told apart afterwards.
                log.warn("Delegation turn {} ran nothing: {}", turn, actions.stream()
                        .map(a -> a.done ? "done" : isNoCall(a) ? "(no call)" : a.tool).toList());
                if (++nothingInARow == AgentLoop.NOTHING_TO_RUN_IN_A_ROW) return ranNothing(mine);
            } else {
                nothingInARow = 0;
            }
            String told = String.join("\n\n", replies);
            if (mine.size() > before) told += "\n\n" + goOn(fileTask, nativeTools);
            messages.add(LlmMessage.assistant(raw));
            messages.add(LlmMessage.user(told));
            fit.told(messages.size() - 1);
        }
    }

    /**
     * What the model is told after a turn whose calls ran. "Passed on verbatim" is false for a
     * private result: the user sees it only through the summary, so the summary is where it has
     * to be written.
     */
    private static String goOn(boolean fileTask, boolean nativeTools) {
        return fileTask
                ? "Continue, or " + (nativeTools
                        ? "call done with the answer for the user"
                        : "output {\"done\": true, \"summary\": \"the answer for the user\"}")
                        + " — what the tools return here is private and reaches the user "
                        + "only through your summary."
                : "Continue with the next step, or if all steps are done, " + (nativeTools
                        ? "call done and say what you did — every result above is passed "
                                + "on verbatim, so do not retype it."
                        : "output {\"done\": true, \"summary\": \"what you did\"}.");
    }

    /** A result as the model is told it, under its handle every time ({@link #act}). */
    private static String toldResult(Artifact result, int handle) {
        return "Tool result " + ArtifactRef.handle(handle) + " [" + result.tool() + "] "
                + (result.succeeded() ? "SUCCESS" : "FAILED") + ":\n" + result.output();
    }

    /**
     * The plan's calls as the model is shown them ({@code text}), and the results of the task shown
     * in them as marks ({@code marked}): made in one pass, so the guard that refuses a copied mark
     * ({@link #act}) knows exactly what the model saw.
     */
    record ShownCalls(String text, java.util.Set<Integer> marked) {}

    /**
     * The plan's calls written as the model's own turn, as {@link #renderCalls} writes its calls --
     * in its numbering, not the cloud's: a call's result, {{call2}}, is its own {{2}}, and a
     * result of the task code filled in, {{4}} to the cloud, is {@link #TASK_RESULT} 4: no
     * reference of its, and refused if it copies it into a call ({@link #act}). Shown as the cloud
     * wrote them, the model copied "{{1.text}}" into a call of its own and sent its own first
     * result -- the weather -- where the cloud had sent the task's first, a note; shown as
     * "result 1.text", it sent those words.
     * <p>
     * What code filled in is decided as code decided it, against the results each call saw when it
     * ran ({@link #seenBy}): a field named by digits, {{1.2026}}, was filled from the task's result
     * 1, and shown as written it was the model's own result 1 (review, 2026-11-11). What code did
     * not fill in -- a template's own text, {{ 3.6 * x }} -- went out as written and is shown so.
     *
     * @param ran  the calls' results, in their order: the first of what the delegation recorded
     * @param task the task's results, a list that only grows
     */
    static ShownCalls shownCalls(List<DelegationPlan.Call> calls, List<Artifact> ran, List<Artifact> task) {
        var marked = new java.util.HashSet<Integer>();
        var lines = new ArrayList<String>();
        for (int i = 0; i < calls.size(); i++) {
            DelegationPlan.Call call = calls.get(i);
            Object params = inOwnNumbering(call.params(), seenBy(i, ran, task), marked);
            try {
                lines.add(TextCalls.MAPPER.writeValueAsString(Map.of("tool", call.tool(), "params", params)));
            } catch (Exception e) {
                lines.add("{\"tool\": \"" + call.tool() + "\"}");
            }
        }
        return new ShownCalls(String.join("\n", lines), java.util.Set.copyOf(marked));
    }

    /**
     * The task's results call {@code i} was resolved against when it ran: those recorded before its
     * own -- the task's, then the results of the calls before it. A result's handle is its place in
     * the task's list, which only grows.
     */
    private static List<Artifact> seenBy(int i, List<Artifact> ran, List<Artifact> task) {
        int before = i < ran.size() ? ran.get(i).n() - 1 : task.size();
        return task.subList(0, Math.max(0, Math.min(before, task.size())));
    }

    /**
     * How a result of the task stands in a call the local model is shown ({@link #shownCalls}):
     * not a reference of its own, and a mark nobody writes, so a copy of it into a call is found and
     * refused rather than sent as words.
     */
    static final String TASK_RESULT = "\u27E6task result ";

    /** A copy of {@link #TASK_RESULT}'s mark, however written: brackets or none, escaped, any case. */
    static final java.util.regex.Pattern MARK_COPY = java.util.regex.Pattern.compile(
            "(?i)task[\\s\\u00A0]+result[\\s\\u00A0]+(\\d{1,9})");

    /**
     * A value of a call the cloud wrote, as the local model numbers ({@link #shownCalls}), with the
     * task's results it shows as marks added to {@code marked}. {@code seen} is what the call was
     * resolved against.
     */
    private static Object inOwnNumbering(Object v, List<Artifact> seen, java.util.Set<Integer> marked) {
        if (v instanceof String s) {
            var t = ArtifactRef.TOKEN.matcher(s);
            var shown = new StringBuilder();
            while (t.find()) {
                ArtifactRef ref = ArtifactRef.parse(t.group());
                boolean filled = References.fills(ref, seen);
                if (filled) marked.add(ref.handle());
                t.appendReplacement(shown, java.util.regex.Matcher.quoteReplacement(!filled ? t.group()
                        : TASK_RESULT + ref.handle() + (ref.field() == null ? "" : "." + ref.field()) + "\u27E7"));
            }
            t.appendTail(shown);
            String words = shown.toString();
            var m = CALL_REF.matcher(words);
            var sb = new StringBuilder();
            while (m.find()) {
                m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(
                        "{{" + Integer.parseInt(m.group(1)) + m.group(2).strip() + "}}"));
            }
            m.appendTail(sb);
            return sb.toString();
        }
        if (v instanceof Map<?, ?> map) {
            var out = new LinkedHashMap<String, Object>();
            map.forEach((k, x) -> out.put(String.valueOf(k), inOwnNumbering(x, seen, marked)));
            return out;
        }
        if (v instanceof Collection<?> list) {
            var out = new ArrayList<Object>();
            for (Object x : list) out.add(inOwnNumbering(x, seen, marked));
            return out;
        }
        return v;
    }

    /**
     * {@code {{call2}}} in a call the LOCAL model wrote, as its own {{2}}: the calls ran first, so
     * call K's result is its result K. One naming no call of the plan sets {@code bad[0]}.
     */
    static Object callsAsOwn(Object v, int calls, String[] bad) {
        if (v instanceof String s) {
            var m = CALL_REF.matcher(s);
            var sb = new StringBuilder();
            boolean any = false;
            while (m.find()) {
                int k = Integer.parseInt(m.group(1));
                if (k < 1 || k > calls) {
                    bad[0] = m.group().strip();
                    return v;
                }
                m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement("{{" + k + m.group(2).strip() + "}}"));
                any = true;
            }
            if (!any) return s;
            m.appendTail(sb);
            return sb.toString();
        }
        if (v instanceof Map<?, ?> map) {
            var out = new LinkedHashMap<String, Object>();
            for (var e : map.entrySet()) {
                out.put(String.valueOf(e.getKey()), callsAsOwn(e.getValue(), calls, bad));
                if (bad[0] != null) return v;
            }
            return out;
        }
        if (v instanceof Collection<?> list) {
            var out = new ArrayList<Object>();
            for (Object x : list) {
                out.add(callsAsOwn(x, calls, bad));
                if (bad[0] != null) return v;
            }
            return out;
        }
        return v;
    }

    /** read_result as a delegation's local model is offered it, once a result of it has folded. */
    static final ToolSpec READ_RESULT = new ToolSpec(Fit.READ,
            "Read part of one of your results, or find text in it. A result too long to show whole "
                    + "with the rest of this work in your context window is folded: you see its "
                    + "beginning and a note. Nothing of it was dropped: read the part you need, or find "
                    + "where something is first.",
            ToolSchemas.toJsonSchema(Map.of(
                    "result", ToolParam.required("integer", "The result's number: 3 for {{3}}."),
                    "from", ToolParam.optional("integer", "The character offset to read from; 0 when left out."),
                    "length", ToolParam.optional("integer", "How many characters to read; what fits when left out."),
                    "find", ToolParam.optional("string", "A text to look for instead of reading, case aside: "
                            + "every place it occurs, with its offset and the text around it."))));

    /**
     * A delegation's conversation fitted to the local model's window ({@link ContextFit}), as the
     * task's own steps are: when the model refuses a turn as longer than its window, the largest of
     * the delegation's results are folded -- each reply that carried one shows the line it opened
     * with, its first characters and a note -- and the turn is asked again, until nothing is left
     * to fold. A folded result stays folded. read_result reads any part of one; a tool handed
     * {{n}} is still handed all of it. The results the cloud handed the delegation are in its
     * instructions, and are not folded.
     * <p>
     * The local model reads its results as they are -- nothing of a delegation's conversation goes
     * to the cloud -- so a read is cut from the result itself, never through a character.
     */
    static final class Fit {
        static final String READ = "read_result";

        /**
         * A reply the conversation shows that can fold: a result's, or a read's -- reads pile up
         * as a large result is read through, and a delegation whose reads could not fold read
         * about one window of it and failed.
         */
        private static final class Part {
            final String label;
            final Integer handle;   // the result's, or null for a read
            final String body;      // what it shows: the result's output, or the read's text
            String reply;           // as the conversation shows it now
            int message = -1;
            boolean folded;

            Part(String label, Integer handle, String body, String reply) {
                this.label = label;
                this.handle = handle;
                this.body = body;
                this.reply = reply;
            }
        }

        /** Every reply told so far that can fold, in the order it was told. */
        private final List<Part> parts = new ArrayList<>();
        /** This turn's, until it is told ({@link #told}). */
        private final List<Part> turn = new ArrayList<>();
        /** Rounds of this overflow; none again once a turn's request fits ({@link #told}). */
        private int rounds;
        /** The characters that fit, by the last overflow; 0 before any. */
        private long budget;
        /** Whether read_result has been added to the tools offered natively. */
        boolean offered;

        /** A result's reply, as the turn tells it. */
        void replied(Artifact result, int handle, String reply) {
            turn.add(new Part("the result of " + result.tool(), handle, result.output(), reply));
        }

        /** A read's reply, as the turn tells it. */
        void readReplied(String reply) {
            turn.add(new Part("a read of a result", null, reply, reply));
        }

        /** The turn was told in message {@code index}: its replies are there. */
        void told(int index) {
            for (Part p : turn) p.message = index;
            parts.addAll(turn);
            turn.clear();
            rounds = 0;   // the turn's request fitted
        }

        /**
         * Fold the largest replies until the conversation fits ({@link ContextFit#target}); returns
         * each folded, by what it was and its size, none when nothing is left to fold.
         */
        List<String> fold(List<LlmMessage> messages, List<ToolSpec> specs, Integer window) {
            long size = ThinkingEngine.requestChars(messages, specs);
            long target = ContextFit.target(size, window, rounds);
            var candidates = new ArrayList<Part>();
            for (Part p : parts) {
                if (!p.folded && p.message >= 0 && p.body.length() > ContextFit.FOLDABLE) candidates.add(p);
            }
            // The largest first, the older among equals: the sort is stable.
            candidates.sort(java.util.Comparator.comparingInt((Part p) -> p.body.length()).reversed());
            var out = new ArrayList<String>();
            for (Part p : candidates) {
                if (size <= target) break;
                String content = messages.get(p.message).content();
                int pos = content.indexOf(p.reply);
                if (pos < 0) continue;
                String shown;
                if (p.handle != null) {
                    if (!p.reply.endsWith(p.body)) continue;
                    String head = p.reply.substring(0, p.reply.length() - p.body.length());
                    shown = ContextFit.foldedResult(p.handle, head, new com.ownclaw.privacy.Redactor.Cuttable(p.body, List.of()));
                } else {
                    shown = ContextFit.foldedRead(p.body);
                }
                messages.set(p.message, LlmMessage.user(content.substring(0, pos) + shown
                        + content.substring(pos + p.reply.length())));
                p.reply = shown;
                p.folded = true;
                size -= p.body.length() - ContextFit.PREVIEW;
                out.add(p.label + " (" + String.format(java.util.Locale.ROOT, "%,d", p.body.length()) + " characters)");
            }
            if (!out.isEmpty()) {
                budget = window != null && window > 0 ? (long) (window * ContextFit.CHARS_PER_TOKEN) : target;
                rounds++;
            }
            return out;
        }

        /** read_result in a delegation: part of one of its results, by its handle. */
        String read(Map<String, Object> params, List<Artifact> mine, List<LlmMessage> messages, List<ToolSpec> specs) {
            Long handle;
            Long from;
            Long length;
            try {
                handle = AgentLoop.wholeNumber(params.get("result"));
                from = AgentLoop.wholeNumber(params.get("from"));
                length = AgentLoop.wholeNumber(params.get("length"));
            } catch (NumberFormatException notANumber) {
                return "ERROR: result, from and length are whole numbers: " + notANumber.getMessage();
            }
            if (handle == null) return "ERROR: 'result' is required: the number of the result to read, 3 for {{3}}.";
            if (handle < 1 || handle > mine.size()) {
                return "ERROR: there is no result " + handle + (mine.isEmpty() ? " yet." : "; your results are 1 to "
                        + mine.size() + ".");
            }
            Object find = params.get("find");
            long chunk = budget <= 0 ? 0 : ContextFit.chunk(budget, ThinkingEngine.requestChars(messages, specs));
            int h = handle.intValue();
            return ContextFit.read(new ContextFit.Named("result " + ArtifactRef.handle(h), "result=" + h),
                    new com.ownclaw.privacy.Redactor.Cuttable(mine.get(h - 1).output(), List.of()), from, length,
                    find == null ? null : String.valueOf(find), chunk);
        }
    }

    /** A delegation whose local model ran nothing {@link AgentLoop#NOTHING_TO_RUN_IN_A_ROW} turns in a row. */
    private Outcome ranNothing(List<Artifact> mine) {
        log.warn("Delegation ended: {} turns in a row ran nothing.", AgentLoop.NOTHING_TO_RUN_IN_A_ROW);
        return partial("the local model produced nothing that could be run "
                + AgentLoop.NOTHING_TO_RUN_IN_A_ROW + " turns in a row.", mine);
    }

    /**
     * One call of the model's turn, through every guard, and run if none refuses it. Returns what
     * the model is told about it: the result, or why the call did not run.
     *
     * @param turn   the model's turn, or for a call run by code its number among the plan's calls
     * @param byCode one of the plan's calls, which the cloud wrote whole and code runs as written
     *               ({@link #runCalls}): nothing of it was typed by the local model, so it is not
     *               held to the rule against retyping, and the owner's chat shows it as the call
     *               it is
     */
    private String act(ExecutorAction action, AgentContext parentContext, List<Artifact> mine,
                       List<Artifact> given, boolean nativeTools, DelegationPlan plan, int turn,
                       String words, boolean byCode) {
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

        // A name no tool has runs nothing, and leaves no result. Recorded, the name would be the
        // result's tool in everything that names one -- the delegation's report to the cloud,
        // the owner's chat, the usage table, the log -- and a name the local model wrote after
        // reading private data can carry what it read. Only the model itself is told it.
        Tool target = toolRegistry.find(action.tool).orElse(null);
        if (target == null) {
            log.warn("Delegation {} {}: a call to a tool that does not exist — refused.",
                    byCode ? "call" : "turn", turn);
            return "Not run: there is no tool named '" + action.tool + "'. " + (byCode
                    ? "Use the exact name, as find_tools lists it."
                    : "Call one of the tools you were given, by its exact name.");
        }

        // A secret the filter removed from the cloud's view, copied from its goal into a call.
        if (Redactor.holdsRemovedSecret(action.params)) {
            log.warn("Delegation turn {}: a call that writes a removed secret — refused.", turn);
            return "Not run: an argument holds a removed secret. " + AgentLoop.REMOVED_SECRET;
        }

        // One resolution of the arguments, used by every check below and by the call itself.
        // It was once computed twice, which is how a guard and the thing it guards drift
        // apart. The texts the cloud handed the delegation are put in here too, by name: retyped,
        // a 33-line script install came back with a line broken in five calls of seven (2026-10-08).
        // A result of the task it was shown in a call that ran for it, copied -- the mark whole,
        // its words without the brackets, or the brackets escaped: no result of its own.
        if (!byCode && !plan.calls().isEmpty()) {
            var mark = MARK_COPY.matcher(String.valueOf(action.params));
            Integer shown = null;
            // Only a result it was shown as a mark: "the task result 3 days ago" in a mail is prose.
            // Worked out only when something reads like one: it reads the results the calls named.
            java.util.Set<Integer> marked = null;
            while (shown == null && mark.find()) {
                if (marked == null) marked = shownCalls(plan.calls(), mine, parentContext.artifacts()).marked();
                int k = Integer.parseInt(mark.group(1));
                if (marked.contains(k)) shown = k;
            }
            if (shown != null) {
                int n = shown;
                boolean isGiven = given.stream().anyMatch(a -> a.n() == n);
                return "Not run: 'task result " + n + "' stands for a result of the task, and it has no reference "
                        + "here. " + (isGiven
                                ? "Result " + n + " is given above: type its text exactly as given."
                                : "You were not given it: leave it out, and if the goal needs it, say so when you call done.");
            }
        }
        // The local model's {{call2}} is its own {{2}}: the calls ran first (callsAsOwn).
        if (!byCode && !plan.calls().isEmpty()) {
            String[] bad = {null};
            @SuppressWarnings("unchecked")
            Map<String, Object> own = (Map<String, Object>) callsAsOwn(action.params, plan.calls().size(), bad);
            if (bad[0] != null) {
                return "Not run: '" + bad[0] + "' names no call: the calls that ran for you are your results "
                        + ArtifactRef.handle(1) + " to " + ArtifactRef.handle(plan.calls().size()) + ".";
            }
            action = new ExecutorAction(action.done, action.summary, action.tool, own);
        } else if (!byCode) {
            String[] bad = {null};
            callsAsOwn(action.params, 0, bad);
            if (bad[0] != null) {
                return "Not run: '" + bad[0] + "' names a call, and no calls ran for you: write a result as "
                        + "{{N}}, N one of your results.";
            }
        }
        // A call the cloud wrote is resolved as it writes everything: {{N}} is the task's result N,
        // at any depth, and a reference that cannot be filled in refuses the call (resolveExactly).
        References.Resolved refs = byCode
                ? References.resolveExactly(action.params, parentContext.artifacts(), plan.texts())
                : References.resolveInText(action.params, mine, plan.texts());
        Map<String, Object> params = refs.params();

        // A reference that could not be resolved: out of range, a missing field, a failed
        // result, or malformed reference-shaped text. Each of these used to
        // survive as literal text -- "$1.body" as the entire body of an email, sent, recorded
        // green, with not one line in the log. Refused here, before anything runs.
        if (!refs.ok()) {
            log.warn("Delegation step {}: '{}' — reference refused.", mine.size() + 1,
                    refs.refused());
            // To the cloud, the reason alone: the list of what can be referenced names every field
            // of every result, a private one's included, which its descriptor withholds (a
            // contacts lookup keyed by people's names). The cloud has the descriptors.
            if (byCode) return "Not run: '" + refs.refused() + "' could not be filled in. " + refs.reason();
            return "Not run: the value of '" + refs.refused()
                    + "' would have been sent as literal text. " + refs.reason() + " "
                    + References.available(mine);
        }

        // A change is attempted once per delegation, and made once per task. The cloud path
        // has CriticAgent, which blocks an identical action after three tries; delegation
        // never reaches it, so nothing else bounds a retry here -- and an SMTP timeout can
        // arrive AFTER the server accepted the message, so an unbounded retry of a "failed"
        // send delivered a copy each time. A failed change is retried by the next attempt at
        // the job (a new delegation, or the cloud once the fallback opens), where it is new;
        // one that SUCCEEDED (by Artifact.succeeded, which reads an "ok": false envelope) is
        // never repeated anywhere in the task. The earlier output is not shown: showing an
        // earlier delegation's output is how a PRIVATE result reached the local model and
        // then, reworded in its summary, the cloud.
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
        // and the model cannot see what it dropped. Text it composes itself is its own to send,
        // quotes of what it read included. A result it was given is read in its prompt and has
        // no reference: typing it out is the only way to pass it on, so a copy of one is held to
        // the same rule. A call the cloud wrote was typed by nobody here.
        Retyped copied = byCode ? null : retyped(target, action.params, readBy(given, mine), planText(plan));
        if (copied != null) {
            log.warn("Delegation step {}: '{}' is most of an earlier result typed out by hand — refused.",
                    mine.size() + 1, copied.param());
            int own = mine.indexOf(copied.of());
            String handle = own < 0 ? null : copied.field() == null ? ArtifactRef.handle(own + 1)
                    : "{{" + (own + 1) + "." + copied.field() + "}}";
            String of = handle != null ? "your result " + handle
                    : "result " + copied.of().n() + ", which you were given,";
            return "Not run: the '" + copied.param() + "' value is most of " + of
                    + " typed out again by hand, and not all of it exactly -- which is how a date, a "
                    + "line or a section goes missing unseen. To pass a result of yours on unchanged, "
                    + "write its reference instead: as the whole value, or inside your own text, as in "
                    + "\"Good morning!\\n\\n{{1.body_text}}\" -- it is put there exactly. "
                    + References.available(mine)
                    + (given.isEmpty() ? "" : " A result you were given has no reference: pass it "
                            + "on whole, exactly as given, or not at all.")
                    + " To change part of it, change only that part, with the tool's own way to "
                    + "replace or add a part. If the tool has none, do not write it out by hand: "
                    + "call done and say so. Text you write yourself is fine, quotes of what you "
                    + "read included.";
        }

        // ACT: execute the tool -- said in the owner's chat first, with the model's words and
        // the call as it wrote it, so the work on the local tier is seen as it happens.
        if (byCode) parentContext.chat().ranCall(turn, target.name(), action.params);
        else parentContext.chat().localTurn(turn, target.name(), words, action.params);
        statusEmitter.emitForTask(parentContext.userId(), parentContext.taskId(), StatusMessage.Type.PROGRESS,
                "Delegate: running " + action.tool + "...");

        long toolStartMs = System.currentTimeMillis();
        // While it runs the stall watchdog does not count the task as silent, and its end marks
        // progress: the calls of a turn run back to back, with no model call between them to say
        // the task is alive, so their run times together used to be counted as silence.
        ToolResult result = parentContext.runningTool(() -> executeToolDirect(target, params, parentContext));
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
        boolean wroteAfterPrivate = parentContext.localTierReadPrivate();
        Artifact.Decision decision = parentContext.decide(target.requiredCredentials(), refs.used(),
                wroteAfterPrivate, toolResult);
        Artifact artifact = parentContext.addArtifact(target.name(), action.params, params,
                toolResult, toolOk, decision);
        mine.add(artifact);
        // No summary of it for the owner: the local model has just read it itself, and its turn
        // in the chat says what it is doing with it.
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
        // before" is why this was a second defence to begin with. The error is kept whole but
        // for vault values, as on the cloud's path (AgentLoop.executeTool).
        curatorService.recordUsage(action.tool, parentContext.userId(), parentContext.taskId(),
                worked, toolMs,
                worked || wroteAfterPrivate ? null
                        : byCode ? action.params : References.argsForTask(action.params, mine),
                worked ? null : Redactor.scrubVault(toolResult, parentContext.secretValues()).text(),
                artifact.label());

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
     *
     * @throws IllegalArgumentException when 'texts' is not an object of names to texts, with why,
     *                                  in words the model can act on
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

        // Comma-separated names, as the schema asks -- or a list, or a JSON array written as a
        // string, as a model will sometimes send. Tool names are [A-Za-z0-9_-], so anything
        // else separates them.
        List<String> tools = new ArrayList<>();
        Object toolsObj = params.get("tools");
        String listed = toolsObj instanceof List<?> l ? String.join(",", l.stream().map(String::valueOf).toList())
                : toolsObj instanceof String s ? s : "";
        for (String name : listed.split("[^A-Za-z0-9_-]+")) if (!name.isBlank()) tools.add(name);

        return new DelegationPlan(goal, steps, checkpoints, tools, texts(params.get("texts")),
                calls(params.get("calls")));
    }

    /**
     * The exact calls of a delegate action, in their order: a list of {@code {"tool": name,
     * "params": {...}}} -- or that list written as a JSON string, as a model will sometimes send
     * one. A call is run exactly as written, so one that is not in that shape is refused, never
     * guessed at: arguments under another key, or a tool named nowhere, would run a call nobody
     * wrote.
     */
    static List<DelegationPlan.Call> calls(Object value) {
        if (value == null) return List.of();
        Object list = value;
        if (value instanceof String s) {
            if (s.isBlank()) return List.of();
            try {
                list = TextCalls.MAPPER.readValue(s, Object.class);
            } catch (Exception e) {
                list = s;
            }
        }
        if (!(list instanceof List<?> items)) {
            throw new IllegalArgumentException("'calls' must be a list of calls, as in \"calls\": "
                    + "[{\"tool\": \"daily_news_digest\", \"params\": {}}, {\"tool\": \"smtp_send_email\", "
                    + "\"params\": {\"body\": \"{{1.body_text}}\"}}].");
        }
        var calls = new ArrayList<DelegationPlan.Call>();
        for (int i = 0; i < items.size(); i++) {
            String which = "call " + (i + 1);
            if (!(items.get(i) instanceof Map<?, ?> call)) {
                throw new IllegalArgumentException(which + " must be an object: {\"tool\": \"exact_name\", "
                        + "\"params\": {...}}.");
            }
            if (!(call.get("tool") instanceof String tool) || tool.isBlank()) {
                throw new IllegalArgumentException(which + " names no tool: write its exact name under \"tool\".");
            }
            Object args = call.get("params");
            if (args == null) {
                for (String other : List.of("arguments", "parameters", "args", "input")) {
                    if (call.containsKey(other)) {
                        throw new IllegalArgumentException(which + "'s arguments go under \"params\", not \""
                                + other + "\".");
                    }
                }
            }
            if (args instanceof String s && !s.isBlank()) {
                try {
                    args = TextCalls.MAPPER.readValue(s, Object.class);
                } catch (Exception notJson) {
                    // Refused just below, as any params that are not an object.
                }
            }
            var params = new LinkedHashMap<String, Object>();
            if (args instanceof Map<?, ?> map) {
                for (var e : map.entrySet()) params.put(String.valueOf(e.getKey()), e.getValue());
            } else if (args != null && !(args instanceof String s && s.isBlank())) {
                throw new IllegalArgumentException(which + "'s \"params\" must be an object of argument "
                        + "names to values.");
            }
            calls.add(new DelegationPlan.Call(tool.strip(), params));
        }
        return calls;
    }

    /**
     * The texts of a delegate action, by name, in its order: an object of names to texts -- or
     * that object written as a JSON string, as a model will sometimes send one. Every name is
     * {@link ArtifactRef#NAME}, and every text a string, taken exactly as written; anything else
     * is refused, never guessed at: a text is inserted exactly, so a number or an object turned
     * into text would be inserted as something nobody wrote.
     */
    static Map<String, String> texts(Object value) {
        if (value == null) return Map.of();
        Object object = value;
        if (value instanceof String s) {
            if (s.isBlank()) return Map.of();
            try {
                object = TextCalls.MAPPER.readValue(s, Object.class);
            } catch (Exception e) {
                object = s;
            }
        }
        if (!(object instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("'texts' must be an object of names to texts, as in "
                    + "\"texts\": {\"script\": \"the exact text\"}.");
        }
        var texts = new LinkedHashMap<String, String>();
        for (var e : map.entrySet()) {
            String name = String.valueOf(e.getKey());
            if (!ArtifactRef.NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("'" + name + "' cannot name a text: a name is a "
                        + "lowercase letter, then lowercase letters, digits and underscores, as in "
                        + "watchdog_script -- never a number, which is a result's handle.");
            }
            if (name.matches("(?i)call\\d+")) {
                throw new IllegalArgumentException("'" + name + "' cannot name a text: {{" + name
                        + "}} is how a call's result is named.");
            }
            if (!(e.getValue() instanceof String text)) {
                throw new IllegalArgumentException("texts." + name + " must be a string: the exact "
                        + "text, as it is to be inserted.");
            }
            // A text goes in exactly as written, and nothing inside it is filled in: a call's result
            // in it, or a result's field -- which no template's own placeholder is written as --
            // would go out as braces.
            var inText = CALL_REF.matcher(text);
            String held = inText.find() ? inText.group().strip() : null;
            if (held == null) {
                var token = ArtifactRef.TOKEN.matcher(text);
                while (token.find()) {
                    ArtifactRef ref = ArtifactRef.parse(token.group());
                    // A template's expression that starts with a number is no reference. A text has
                    // no results to ask, and goes in as written: a number alone in braces, {{2.0}},
                    // is taken as the reference it would be anywhere a result has that handle.
                    if (ref != null && ref.field() != null
                            && !References.templateText(token.group(), Integer.MAX_VALUE)) {
                        held = token.group().strip();
                        break;
                    }
                }
            }
            if (held != null) {
                // A number alone in braces is also how a template could write a constant: say how.
                String spaced = ArtifactRef.bareNumber(held) > 0
                        ? " If it is a template's own number, write it with a blank inside the braces, as {{ "
                                + held.substring(held.indexOf("{{") + 2, held.lastIndexOf("}}")) + " }}."
                        : "";
                throw new IllegalArgumentException("texts." + name + " holds a reference (" + held
                        + "), and a text is put in exactly as written, with nothing in it filled in. Put the "
                        + "reference in the call's own argument, as the whole value or inside its text." + spaced);
            }
            texts.put(name, text);
        }
        return texts;
    }

    // ── Private helpers ──

    /**
     * Build the system prompt for the local executor.
     * Includes the plan, available tools, and constrained output format.
     */
    private String buildExecutorSystemPrompt(DelegationPlan plan, List<Artifact> given,
                                             AgentContext context, boolean nativeTools,
                                             boolean fileTask) {
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
        // The owner reads the sentence above each call in the chat (TaskChat#localTurn). On the
        // text protocol a turn is one JSON object, with nothing beside it to write it in.
        boolean narrate = nativeTools && context.chat().watched();
        if (nativeTools) {
            sb.append("Call one tool per turn. When the goal is reached, call **done** with the\n");
            sb.append("full summary. Do not answer in prose instead of calling a tool — an answer\n");
            sb.append("nobody asked for ends nothing, and only **done** returns the work.\n");
            if (narrate) {
                sb.append("The owner follows your work in the chat: with each tool call, write one short\n");
                sb.append("sentence saying what you are doing and why, in the language of his request\n");
                sb.append("(at the end of this prompt).\n");
            }
            sb.append("\n");
        } else {
            sb.append("Tool call: {\"tool\": \"name\", \"params\": {...}}\n");
            sb.append("All done: {\"done\": true, \"summary\": \"consolidated results\"}\n");
            sb.append("ONE JSON object only. No extra text.\n\n");
        }

        // The plan
        sb.append("## Plan\n");
        sb.append("**Goal:** ").append(plan.goal()).append("\n\n");
        texts(sb, plan.texts());
        if (!plan.calls().isEmpty()) {
            int n = plan.calls().size();
            sb.append("**Already run for you:** the orchestrator's exact calls, in its order, before you\n");
            sb.append("started. What each returned is in the conversation below, as your result")
              .append(n == 1 ? " " + ArtifactRef.handle(1)
                      : "s " + ArtifactRef.handle(1) + " to " + ArtifactRef.handle(n))
              .append(":\n");
            for (int i = 0; i < n; i++) {
                sb.append(i + 1).append(". ").append(plan.calls().get(i).tool()).append("\n");
            }
            sb.append("In them, ").append(TASK_RESULT).append("N\u27E7 is a result of the task, not one of yours: it has\n");
            sb.append("no reference here.\n\n");
        }

        // Whole, on this machine: reading what the cloud is shown only as a description is what
        // the local model is for. Named in words, as the goal names them, so a handle never means
        // one of these and one of its own results at once.
        if (!given.isEmpty()) {
            sb.append("**Results you are given** (the earlier results the goal names; read them "
                    + "here -- they are not among your own numbered results):\n\n");
            for (Artifact a : given) {
                sb.append("### result ").append(a.n()).append(" (").append(a.tool())
                  .append(a.succeeded() ? "" : ", failed").append(")\n")
                  .append(a.output().isEmpty() ? "(no text)" : a.output()).append("\n\n");
            }
        }

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
            Collection<Tool> availableTools = offered(plan, given, context);
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
        if (!plan.texts().isEmpty()) {
            sb.append("- To use a text you were handed, write its handle, e.g. ")
              .append(ArtifactRef.handle(plan.texts().keySet().iterator().next()))
              .append(", in the argument --\n");
            sb.append("  as the whole value or inside it -- and the exact text is put there when the\n");
            sb.append("  call runs. Never type a text out yourself.\n");
        }
        if (!context.pasted().isEmpty()) {
            // The cloud model is shown the images the owner pasted; this model is not, and would
            // otherwise take the goal's "the screenshot" for something it can look at.
            sb.append("- The image").append(context.pasted().size() == 1 ? " the user pasted is" : "s the user pasted are")
              .append(" given to every tool you call (as _attached_files);\n");
            sb.append("  you cannot see images yourself: only a tool reads ").append(context.pasted().size() == 1 ? "it" : "them")
              .append(".\n");
        }
        if (fileTask) {
            // The rule below is false here: a file task's results are withheld from the cloud,
            // so nothing is passed on underneath the summary, and the summary is what the user
            // reads. No file name is given: the prompt says only that the files are handed.
            sb.append("- The user's files are given to every tool you call (as _attached_files);\n");
            sb.append("  you do not pass them.\n");
            sb.append("- What the tools return here is private: the orchestrator is never shown it.\n");
            sb.append("  Your done summary is the answer the user reads — write it in full, and copy\n");
            sb.append("  figures, dates and names exactly as the tools returned them. The orchestrator\n");
            sb.append("  reads it too, unless it quotes what the tools returned.\n");
        } else {
            sb.append("- Your summary says what you DID. Every tool result is passed on verbatim\n");
            sb.append("  underneath it, so never retype data — a date or number written from\n");
            sb.append("  memory is an error that was not in the data.\n");
        }
        sb.append("- No skill_create. Nobody is available to answer questions — decide and proceed.\n");

        if (narrate) {
            // Whole, for its language: the local model is not otherwise shown it.
            sb.append("\n## The owner's request\n");
            sb.append("Only for the language of your sentences; the work is the goal above.\n\n");
            sb.append(context.originalMessage()).append("\n");
        }
        return sb.toString();
    }

    /**
     * The texts the delegation is handed, each under its handle and its size, whole. The model
     * only has to write a handle, but a text can also be one to read -- a configuration to check
     * against, an error to look into -- and unread it is no use. Shown whole, it was not copied:
     * in a replay of the 2026-10-08 watchdog goal with the script handed over by name, ten seeds
     * with the script shown whole and ten with only its size and first line, every call the model
     * made held the handle, it never retyped the script into its reasoning, and it took about as
     * long either way.
     */
    private static void texts(StringBuilder sb, Map<String, String> texts) {
        if (texts.isEmpty()) return;
        sb.append("**Texts you are handed** -- each to be used exactly. Write its handle in a tool\n");
        sb.append("argument and the text is put there, exactly, when the call runs. It is shown here\n");
        sb.append("for you to read; never type it out.\n\n");
        for (var e : texts.entrySet()) {
            String text = e.getValue();
            long lines = text.lines().count();
            sb.append("### ").append(ArtifactRef.handle(e.getKey())).append(" (")
              .append(String.format(Locale.ROOT, "%,d characters in %,d line%s", text.length(), lines,
                      lines == 1 ? "" : "s"))
              .append(")\n").append(text).append("\n\n");
        }
    }

    /**
     * Execute a tool of the registry directly.
     * Simplified version of AgentLoop.executeTool() without LongRunningTaskManager.
     */
    private ToolResult executeToolDirect(Tool tool, Map<String, Object> params, AgentContext context) {
        String toolName = tool.name();
        // What the cloud path hands a skill (AgentContext#toolContext): the task's files, its
        // stop, and a report_progress that is progress for the stall watchdog -- shown to the
        // owner here as a delegation step's status line. With no callback the sandbox would
        // leave the line in the skill's stdout.
        ToolExecutionContext execCtx = context.toolContext((message, percent) ->
                statusEmitter.emitForTask(context.userId(), context.taskId(),
                        StatusMessage.Type.PROGRESS, "Delegate: " + toolName + ": " + message
                                + (percent == null ? "" : " (" + percent + "%)")));

        try {
            return tool.execute(params != null ? params : Map.of(), execCtx);
        } catch (Exception e) {
            log.error("Tool '{}' threw exception during delegation", toolName, e);
            return ToolResult.failure("Tool '" + toolName + "' threw exception: " + e.getMessage());
        }
    }

    /**
     * The calls a reply written as text holds: every JSON object in it that names a tool or
     * finishes ({@code {"done": true, ...}}), in its order -- the whole turn, as a native turn's
     * tool calls are ({@link TextCalls}). Read for its first object only, a turn that wrote the
     * fetch and then the send ran the fetch, and the send was neither run nor mentioned. An object
     * that names no tool is not a call: an empty {@code {}} in the prose before a call is not a
     * call that failed. Text with no call in it is read as one invalid call, and the model is
     * told that it was not a tool call.
     */
    static List<ExecutorAction> parseExecutorActions(String raw) {
        List<ExecutorAction> calls = TextCalls.objects(raw).stream()
                .map(LocalExecutor::actionOf)
                .filter(a -> a.done || (a.tool != null && !a.tool.isBlank()))
                .toList();
        if (calls.isEmpty()) {
            // Its length, not its text: on a task holding a file this prose is the answer, and
            // the log is read back through the ops API.
            log.warn("LocalExecutor: no tool call in the local LLM's text ({} chars)",
                    raw == null ? 0 : raw.length());
            return List.of(ExecutorAction.invalid());
        }
        return calls;
    }

    /**
     * One call written as text: {@code {"done": true, "summary": ...}} finishes; any other is the
     * tool it names, with its arguments. A {@code {"tool": "done"}} is a finish too, made one by
     * the caller ({@link #normalizeDone}), so the two protocols agree on what finishing looks like.
     */
    private static ExecutorAction actionOf(Map<String, Object> call) {
        Object done = call.get("done");
        if (done != null && ("true".equalsIgnoreCase(done.toString()) || Boolean.TRUE.equals(done))) {
            return ExecutorAction.done(str(call.get("summary")));
        }
        return new ExecutorAction(false, null, TextCalls.tool(call), TextCalls.params(call));
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
                lines.add(TextCalls.MAPPER.writeValueAsString(Map.of(
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
     * An argument that is most of a text the model read typed out again by hand ({@link #retyped}).
     *
     * @param param the argument
     * @param of    the result the text is, or is in
     * @param field the top-level field of a JSON result the text is, or null
     */
    record Retyped(String param, Artifact of, String field) {}

    /**
     * A text shorter than this (normalised) is a value to use -- a path, an id, a line -- not a
     * text to pass on, so a copy of it is no retyping: the model picking a path out of a short
     * listing is quoting it. A digest, a menu, a configuration file is a text.
     */
    static final int TEXT_LENGTH = 200;

    /**
     * The share of an argument, over the stretch from its first run copied from a text to its
     * last, that makes it that text's copy. Over the stretch, not the whole argument: words around
     * a copy -- an introduction, a second text -- do not make it less of one.
     */
    static final double MOSTLY_COPIED = 0.5;

    /** The share of a text a copy covers that makes it the text passed on, not quoted. */
    static final double MUCH_OF_IT = 0.4;

    /**
     * One text the model read: a result, or each string of a JSON result long enough to be one
     * ({@link #TEXT_LENGTH}) -- a digest's body_text, one message of a mail listing -- each judged
     * on its own. Judged as the raw JSON line, a lossy copy of one field was measured against the
     * whole envelope, escapes and an HTML twin included, and passed.
     *
     * @param field  the top-level field the text is the whole value of, or null
     * @param light  as written, but for line ends, trailing blanks and blank lines ({@link #light})
     * @param normal as the canary reads it ({@link PrivateIndex#normalise}), for finding its runs
     */
    private record Text(Artifact of, String field, String light, String normal) {}

    /**
     * The argument of a side-effecting call that is most of a text the model read typed out again
     * by hand, and not all of it exactly -- or null.
     * <p>
     * That is how a line or a date goes missing: on 23 September the news digest went out as
     * 2,073 characters the local model had typed itself from a 2,745-character source. A copy is
     * runs of {@link PrivateIndex#WINDOW} characters of a text, normalised the way the canary
     * normalises it. An argument is that text's copy when at least half of it is such runs where
     * the copy is ({@link #MOSTLY_COPIED}) and they cover a large part of the text
     * ({@link #MUCH_OF_IT}) -- and it is refused unless it holds the whole text exactly
     * ({@link #light}: line breaks, indentation and case as written, which a YAML file and a
     * script depend on), which loses nothing however much is around it. Checked against every
     * text this delegation has read ({@link Text}): those of the results it was given, which have
     * no reference and can only be passed on typed out, and of its own; on the arguments as the
     * model WROTE them, where a reference is a short token that copies nothing; and only where a
     * copy can do harm: a top-level string of a tool that changes something, against results that
     * succeeded.
     * <p>
     * It used to refuse any one run of a result, and that refused every edit: a script naming a
     * path and an entity it had read, an API body holding an entity id, a configuration change
     * written as the text to find and the text to put in its place. Four delegations of
     * 2026-11-08 to 2026-11-10 ended on three such refusals in a row, the battery's "stop the
     * charging" among them, and one reasoned itself into a loop trying to write an edit that
     * quoted nothing. Composed text passes, quotes of what was read included; a lossy copy of a
     * whole text does not.
     * <p>
     * A run is a text's only when the model had it from nowhere else. One it wrote itself, in an
     * argument of an earlier call, or was given in the plan, is its own however many results echo
     * it -- the allowance the canary makes for the cloud's own arguments (AgentContext's
     * Excuses), made here: a send echoes the subject it was given and a write the path. The
     * arguments of the call that made a result it was given are excused the same way: whoever
     * wrote them, they are not that result's data. An earlier argument that held a text whole is
     * that text typed out, not the model's words, and excuses none of it.
     *
     * @param done  the results the model has read: those it was given, then its own
     * @param given what the model was given to work from ({@link #planText})
     */
    static Retyped retyped(Tool tool, Map<String, Object> written, List<Artifact> done,
                           List<String> given) {
        if (tool == null || !tool.hasSideEffects() || written == null) return null;
        List<Artifact> forwardable = done.stream().filter(Artifact::succeeded).toList();
        var texts = new ArrayList<Text>();
        for (Artifact r : forwardable) texts.addAll(textsOf(r));
        if (texts.isEmpty()) return null;
        var plan = new ArrayList<Runs>();
        for (String text : given) plan.add(Runs.of(PrivateIndex.normalise(text)));
        // The model's earlier arguments, each with what it holds whole.
        var earlierLight = new ArrayList<String>();
        var earlierRuns = new ArrayList<Runs>();
        for (Artifact a : done) {
            for (Object value : a.written().values()) {
                String text = String.valueOf(value);
                earlierLight.add(light(text));
                earlierRuns.add(Runs.of(PrivateIndex.normalise(text)));
            }
        }
        for (var e : written.entrySet()) {
            if (!(e.getValue() instanceof String v)) continue;
            if (forwardable.stream().anyMatch(r -> v.equals(r.output()))) continue;
            String argument = PrivateIndex.normalise(v);
            if (argument.length() < PrivateIndex.WINDOW) continue;
            String lightArgument = light(v);
            // The texts it holds whole: nothing of them is lost, and their runs are no lossy copy of
            // another text that shares them -- a digest's body_text exactly is no copy of its
            // body_html twin with the tags gone.
            var whole = new ArrayList<Runs>();
            for (Text t : texts) if (lightArgument.contains(t.light())) whole.add(Runs.of(t.normal()));
            for (Text t : texts) {
                // The whole text exactly, as written: nothing of it is lost.
                if (lightArgument.contains(t.light())) continue;
                // Too short to cover MUCH_OF_IT of it: a copied run covers at most a window of it.
                if ((long) PrivateIndex.WINDOW * (argument.length() - PrivateIndex.WINDOW + 1)
                        < MUCH_OF_IT * t.normal().length()) {
                    continue;
                }
                var own = new ArrayList<>(plan);
                own.addAll(whole);
                for (int i = 0; i < earlierLight.size(); i++) {
                    if (!earlierLight.get(i).contains(t.light())) own.add(earlierRuns.get(i));
                }
                if (copiedMostOf(argument, t.normal(), h -> {
                    for (Runs r : own) if (r.has(h)) return true;
                    return false;
                })) {
                    return new Retyped(e.getKey(), t.of(), t.field());
                }
            }
        }
        return null;
    }

    /** The texts of a result ({@link Text}): its strings when it is JSON, else the whole of it. */
    private static List<Text> textsOf(Artifact r) {
        var texts = new ArrayList<Text>();
        com.fasterxml.jackson.databind.JsonNode json = null;
        String out = r.output();
        try {
            if (out != null && !out.isBlank()) json = TextCalls.MAPPER.readTree(out);
        } catch (Exception notJson) {
            // A text, whole.
        }
        if (json != null && (json.isObject() || json.isArray())) {
            if (json.isObject()) {
                json.fields().forEachRemaining(f -> {
                    if (f.getValue().isTextual()) addText(texts, r, f.getKey(), f.getValue().asText());
                    else collectTexts(f.getValue(), r, texts);
                });
            } else {
                collectTexts(json, r, texts);
            }
        } else if (out != null) {
            addText(texts, r, null, out);
        }
        return texts;
    }

    private static void collectTexts(com.fasterxml.jackson.databind.JsonNode node, Artifact r, List<Text> into) {
        if (node.isTextual()) addText(into, r, null, node.asText());
        else for (var x : node) collectTexts(x, r, into);
    }

    private static void addText(List<Text> into, Artifact r, String field, String text) {
        String normal = PrivateIndex.normalise(text);
        if (normal.length() >= TEXT_LENGTH) into.add(new Text(r, field, light(text), normal));
    }

    /**
     * A text as written, but for what no reader of it depends on: line ends (CRLF), blanks at the
     * end of a line, how many blank lines stand between two others, and Unicode's two spellings of
     * one character (NFC). Line breaks, indentation, case, and that a blank line is there at all
     * -- a paragraph break -- stay: a YAML file, a script and an email are changed by changing them,
     * and a copy that changed them is a copy that lost something.
     */
    static String light(String s) {
        String t = java.text.Normalizer.normalize(PrivateIndex.streamSafe(s), java.text.Normalizer.Form.NFC)
                .replace("\r\n", "\n").replace('\r', '\n');
        var sb = new StringBuilder();
        boolean blank = false;
        for (String line : t.split("\n", -1)) {
            // A no-break space typed as a space, wherever it stands: the model cannot see which it is.
            var spaced = new StringBuilder(line.length());
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                spaced.append(c != ' ' && Character.getType(c) == Character.SPACE_SEPARATOR ? ' ' : c);
            }
            // Blanks at the end stripped by a walk back: a pattern anchored at the end tried every
            // run of blanks inside the line, and 100,000 of them took 48 seconds.
            int end = spaced.length();
            while (end > 0 && (Character.isWhitespace(spaced.charAt(end - 1)) || Character.isSpaceChar(spaced.charAt(end - 1)))) end--;
            String l = spaced.substring(0, end);
            if (l.isEmpty()) {
                blank = sb.length() > 0;
                continue;
            }
            if (sb.length() > 0) sb.append(blank ? "\n\n" : "\n");
            sb.append(l);
            blank = false;
        }
        return sb.toString();
    }

    /** Runs of a text the model had from elsewhere, as sorted hashes: one array per text, looked up per window. */
    private record Runs(long[] sorted) {
        static Runs of(String normalised) {
            long[] r = runs(normalised);
            java.util.Arrays.sort(r);
            return new Runs(r);
        }

        boolean has(long h) {
            return java.util.Arrays.binarySearch(sorted, h) >= 0;
        }
    }

    /**
     * Whether {@code argument} is mostly a copy of {@code text}, both normalised: over the stretch
     * from its first run copied from the text to its last, at least {@link #MOSTLY_COPIED} is runs
     * of the text the model did not have from elsewhere ({@code own}), and they cover at least
     * {@link #MUCH_OF_IT} of the text. Whether it holds the text whole is the caller's question.
     * <p>
     * The shorter of the two is indexed and the longer read through window by window, so a large
     * result is never held as a table of its windows: a 10 MB one was a gigabyte of them.
     */
    static boolean copiedMostOf(String argument, String text, java.util.function.LongPredicate own) {
        int w = PrivateIndex.WINDOW;
        if (argument.length() < w || text.length() < w) return false;
        boolean indexText = text.length() <= argument.length();
        String small = indexText ? text : argument;
        String large = indexText ? argument : text;
        long[] smallRuns = runs(small);
        var first = new HashMap<Long, Integer>(smallRuns.length * 2);
        for (int i = 0; i < smallRuns.length; i++) first.putIfAbsent(smallRuns[i], i);
        // With the argument indexed, each of its runs is placed in the text once: a run that the
        // text repeats -- a line every block of a configuration shares -- would otherwise cover
        // every place it occurs, and a new block written into a repetitive file "covered" 80% of it.
        boolean[] placed = indexText ? null : new boolean[smallRuns.length];
        boolean[] copied = new boolean[argument.length()];
        boolean[] covered = new boolean[text.length()];
        long top = 1;
        for (int i = 1; i < w; i++) top *= RUN_BASE;
        long h = 0;
        for (int i = 0; i < w; i++) h = h * RUN_BASE + large.charAt(i);
        // Where the last copied run was on the indexed side: a copy goes on from there, so a run
        // that occurs more than once -- a repeated line -- is placed where the copy is, not at its
        // first occurrence, which would count a copy of a repetitive text as a small one.
        int last = -1;
        int from = Integer.MAX_VALUE;
        int to = -1;
        for (int p = 0; p + w <= large.length(); p++) {
            if (p > 0) h = (h - large.charAt(p - 1) * top) * RUN_BASE + large.charAt(p + w - 1);
            Integer q = last >= 0 && last + 1 < smallRuns.length && smallRuns[last + 1] == h
                    ? Integer.valueOf(last + 1) : first.get(h);
            // A hash is checked against the text it stands for; a run the model had from
            // elsewhere is its own, and asked only of a run that matched -- asked of every window,
            // it cost a binary search per text the model wrote, on every window of every result.
            if (q == null || !large.regionMatches(p, small, q, w) || own.test(h)
                    || placed != null && placed[q]) {
                last = -1;
                continue;
            }
            if (placed != null) placed[q] = true;
            int a = indexText ? p : q;
            int t = indexText ? q : p;
            java.util.Arrays.fill(copied, a, a + w, true);
            java.util.Arrays.fill(covered, t, t + w, true);
            from = Math.min(from, a);
            to = Math.max(to, a + w);
            last = q;
        }
        return to > 0 && count(copied) >= MOSTLY_COPIED * (to - from)
                && count(covered) >= MUCH_OF_IT * text.length();
    }

    private static final long RUN_BASE = 1_000_003L;

    /** The hash of each {@link PrivateIndex#WINDOW}-character run of {@code text}, by its offset. */
    private static long[] runs(String text) {
        int w = PrivateIndex.WINDOW;
        int n = text.length() - w + 1;
        if (n <= 0) return new long[0];
        long top = 1;
        for (int i = 1; i < w; i++) top *= RUN_BASE;
        long h = 0;
        for (int i = 0; i < w; i++) h = h * RUN_BASE + text.charAt(i);
        long[] out = new long[n];
        out[0] = h;
        for (int i = 1; i < n; i++) {
            h = (h - text.charAt(i - 1) * top) * RUN_BASE + text.charAt(i + w - 1);
            out[i] = h;
        }
        return out;
    }

    private static int count(boolean[] marks) {
        int n = 0;
        for (boolean m : marks) if (m) n++;
        return n;
    }

    /** What the local model is given to work from: the plan's text, and the texts it is handed. */
    static List<String> planText(DelegationPlan plan) {
        var given = new ArrayList<String>();
        given.add(plan.goal());
        given.addAll(plan.texts().values());
        for (var step : plan.steps()) {
            given.add(step.description());
            if (step.params() != null) {
                for (Object value : step.params().values()) given.add(String.valueOf(value));
            }
        }
        if (plan.checkpoints() != null) given.addAll(plan.checkpoints());
        // What the cloud wrote into its calls is given too, whether or not a result echoes it.
        for (var call : plan.calls()) {
            for (Object value : call.params().values()) given.add(String.valueOf(value));
        }
        return given;
    }

    /**
     * An earlier identical call to a side-effecting tool in {@code among}, or null. Identical
     * means the same tool and the same RESOLVED arguments; {@code successesOnly} decides whether
     * a failed attempt counts.
     * <p>
     * Asked of the whole task with {@code successesOnly}: a delegation that fails on one step can
     * still have sent the email on another, and a second delegation retrying the job would send a
     * second morning email. Only a real success counts there: a send that failed, including one
     * reported as {@code "ok": false}, is exactly what a retry is for. Only a tool that declares
     * side effects: a second read costs nothing but time.
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
                    + "handle ({{N}} or {{N.field}}) in that argument -- the whole value, or inside "
                    + "text -- and it is filled in when the call runs; a new delegation cannot see it.");
        }
        return sb.toString();
    }

    /**
     * A delegation that said it was done. Successful only if something actually ran, or it was
     * given results to read and wrote what it read in them.
     * <p>
     * Zero tools and a confident summary is the shape of a hallucinated success, and it used to
     * produce a successful step, a successful task and a scheduled run recorded as delivered —
     * with the registry withheld, the cloud has no instrument to check it with. A delegation
     * given results is not that: what it read is in its prompt, and the reading was the work.
     *
     * @param given the earlier results the goal named, which the local model read
     * @param said  the answer recorded by {@link #recordAnswer}, or null. Of a private one the
     *              cloud is told its handle and size, never its text; a released one it reads as
     *              the summary. Either joins what the delegation produced.
     */
    /** A delegation handed results to read that names no tool, in its list or in a step. */
    static boolean readsWhatItWasGiven(DelegationPlan plan, List<Artifact> given) {
        return !given.isEmpty() && plan.tools().isEmpty() && plan.calls().isEmpty()
                && plan.steps().stream().allMatch(st -> st.tool() == null || st.tool().isBlank());
    }

    static Outcome completed(String localSummary, String goal, List<Artifact> given,
                             List<Artifact> results, Artifact said) {
        boolean anyFailed = results.stream().anyMatch(r -> !r.succeeded());
        String summary;
        if (said != null && said.isPrivate()) {
            // The local model's answer quotes private data it read (recordAnswer): withheld, but
            // not lost -- it is the answer, and the cloud can deliver it by its handle. Said here,
            // because a handle the cloud was never told about is one it cannot use.
            String k = said.handle();
            summary = "(The local model's answer is " + k + ": private, "
                    + String.format(Locale.ROOT, "%,d", said.output().length())
                    + " characters -- it " + String.join("; ", said.why()) + ", which you are shown "
                    + "only as a description, so you are not shown it either. "
                    + "To give it to the user, make " + k + " the whole of respond's message; its "
                    + "text is filled in on this machine. To send it somewhere, put " + k
                    + " in a tool argument.)";
        } else {
            // Written after reading private data or not, a summary that quotes none of it is the
            // cloud's to read, through the gateway's filter like everything else. In the task's
            // numbering: the summary says "sent {{1}}" meaning the delegation's first step, and
            // the cloud reads task handles everywhere else.
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
        boolean worked = !results.isEmpty()
                || !given.isEmpty() && localSummary != null && !localSummary.isBlank();
        return new Outcome(head + summary + ledger(results) + verbatimFailures(results),
                toolNames(results), results.size(),
                // A step that threw means the cloud should have the registry back: rewriting a
                // skill from its traceback is the self-learning loop this project exists for,
                // and it cannot run through a paraphrase. Marking the delegation failed is what
                // restores the registry and engages the repair path.
                worked && !anyFailed, List.copyOf(produced));
    }

    /** What the local model read: the results it was given, then its own. */
    private static List<Artifact> readBy(List<Artifact> given, List<Artifact> mine) {
        List<Artifact> read = new ArrayList<>(given);
        read.addAll(mine);
        return read;
    }

    /**
     * Keep the local model's answer as a PRIVATE result of the task when it quotes private data
     * it read; on a file task, as a PUBLIC one when it does not; null otherwise -- it wrote
     * nothing, or off a file task, read nothing private or wrote an answer that quotes none of it.
     * <p>
     * What the local model writes after reading private data is labelled by its content, like
     * any result: PRIVATE when it repeats a run of a private result it read -- a window of 32
     * characters, a whole short value, or a word shaped like a credential
     * ({@link AgentContext#firstRunOf}, over every one of them, indexed for the canary or not) --
     * and otherwise the cloud's to read, through the gateway's filter like everything else. So
     * the cloud can ask a question about the owner's mail or file and be given the answer; a
     * digest that copies the messages out stays here. A paraphrase passes: that is the release.
     * Kept, a quoting answer is a handle the cloud can deliver without reading. On a file task
     * every answer is kept, released or not: it is the answer the task exists for, and the user
     * is given it whether or not the cloud places it (AgentLoop.withLocalAnswers). In the task's
     * numbering, like any summary.
     * <p>
     * Indexed on a file task, so the canary looks for it in every later request of the task:
     * there every result is private, and nothing the cloud is shown can repeat it but a leak.
     * Elsewhere not, like every other result that is PRIVATE because it repeats another
     * ({@link AgentContext#decide}): such an answer quotes what the cloud is shown -- the
     * arguments of a step that failed, which the report prints, or a page the cloud may fetch
     * again itself -- and indexed, the request carrying the report would be refused, and the
     * cloud's own fetch withheld.
     */
    static Artifact recordAnswer(AgentContext context, String summary, List<Artifact> given,
                                 List<Artifact> mine, boolean fileTask) {
        if (summary == null || summary.isBlank()) return null;
        List<Artifact> privateRead = readBy(given, mine).stream().filter(Artifact::isPrivate).toList();
        String text = References.proseForTask(summary, mine);
        PrivateIndex.Hit quoted = privateRead.isEmpty() ? null : context.firstRunOf(privateRead, text);
        if (quoted != null) {
            return context.addArtifact("local_answer", Map.of(), Map.of(), text, true,
                    new Artifact.Decision(com.ownclaw.privacy.Label.PRIVATE,
                            List.of("quotes {{" + quoted.handle() + "}}"), fileTask));
        }
        return fileTask ? context.addArtifact("local_answer", Map.of(), Map.of(), text, true,
                new Artifact.Decision(com.ownclaw.privacy.Label.PUBLIC, List.of(), false)) : null;
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
        return verbatimFailures(results, false);
    }

    /**
     * @param taskNumbered the results are of calls the cloud wrote, whose arguments are in the
     *                     task's numbering already ({@link #runCalls}); rewritten as the local
     *                     model's, {{4}} would be printed as whatever the fourth of them was
     */
    static String verbatimFailures(List<Artifact> results, boolean taskNumbered) {
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
                      : String.valueOf(taskNumbered ? r.written() : References.argsForTask(r.written(), results)))
              .append("\n")
              .append(r.isPrivate() ? r.describe() : r.output());
        }
        return sb.toString();
    }

    private Outcome partial(String reason, List<Artifact> results) {
        return new Outcome(buildPartialResult(reason, results), toolNames(results),
                results.size(), false, List.copyOf(results));
    }

    private static String buildPartialResult(String reason, List<Artifact> results) {
        return "Delegation incomplete: " + reason + "\n\n"
                + (results.isEmpty() ? "" : "Partial results collected:\n\n" + resultsForCloud(results));
    }

    static String buildConsolidatedResult(String goal, List<Artifact> results) {
        return "Delegation completed for: " + goal + "\n\n" + resultsForCloud(results);
    }

    /**
     * A delegation's results as its report shows them to the cloud, finished or not: a PRIVATE
     * one as its descriptor, a PUBLIC one whole, on the lines under its handle.
     * <p>
     * On lines of their own, as a think prompt shows a result
     * ({@code AgentTrajectory.Turn#observationText}), because that is the frame a result's label
     * is decided in: normalised, the line break before an output is a space, and the canary
     * strips the whitespace at the ends of what it asks about. The unfinished report put an
     * output after {@code "{{1}} [tool] OK: "}, and a private confirmation that quotes the output
     * after a colon -- "Sent to owner@example.org:" and the digest it sent -- holds the window
     * that starts at that colon: the request carrying the report was refused, and a delegation
     * that had sent the email ended the task there. A descriptor opens with the handle, the tool
     * and the tick itself; prefixing them printed each twice.
     */
    private static String resultsForCloud(List<Artifact> results) {
        var sb = new StringBuilder();
        for (Artifact r : results) {
            if (r.isPrivate()) {
                sb.append("### ").append(r.describe()).append("\n\n");
            } else {
                sb.append("### ").append(r.handle()).append(": ").append(r.tool())
                        .append(r.succeeded() ? " ✓" : " ✗")
                        .append("\n").append(r.output()).append("\n\n");
            }
        }
        return sb.toString();
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
     * @param byCode    it ended with the cloud's exact calls ({@link #runCalls}), the local model
     *                  given nothing: when one of them stopped it, that is the cloud's call to
     *                  fix -- a name or a field it got wrong -- and not the local tier failing, so
     *                  it does not hand the cloud the skills for the rest of the task
     *                  (ThinkingEngine#delegationFailed)
     */
    public record Outcome(String text, List<String> toolsRun, int stepCount, boolean ok,
                          List<Artifact> produced, boolean byCode) {
        public Outcome(String text, List<String> toolsRun, int stepCount, boolean ok, List<Artifact> produced) {
            this(text, toolsRun, stepCount, ok, produced, false);
        }

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

    /** Text that held no call: neither a finish nor a tool name. */
    private static boolean isNoCall(ExecutorAction a) {
        return !a.done && (a.tool == null || a.tool.isBlank());
    }
}
