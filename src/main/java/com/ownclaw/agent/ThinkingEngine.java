package com.ownclaw.agent;

import com.ownclaw.agent.tools.Tool;
import com.ownclaw.agent.tools.ToolRegistry;
import com.ownclaw.agent.tools.ToolSchemas;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.llm.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * The ThinkingEngine is the core reasoning component of the agent.
 * It assembles an LLM prompt from the current agent context, available tools,
 * and execution trajectory, then parses the LLM's response into an {@link AgentAction}.
 *
 * The engine is stateless — all state is carried in the {@link AgentContext}.
 */
@Component
public class ThinkingEngine {

    private static final Logger log = LoggerFactory.getLogger(ThinkingEngine.class);

    /**
     * Where the task ends in the first message of a think call, on every step, before what follows
     * it there -- on the first step, the per-step block (datetime, tools, user prefs):
     * AnthropicProvider splits there and puts an hour-long cache mark on the task, so later steps
     * read the tools, the system prompt and the task from the cache while they are unchanged
     * ({@link #buildAnthropicMessages}).
     */
    static final String CACHE_BOUNDARY_MARKER = com.ownclaw.llm.LlmMessage.CACHE_BOUNDARY;

    /**
     * The tool name of a step that produced no action the loop can run: a reply that was empty,
     * that is not an action, that never came because the call failed, or -- on unattended work
     * before anything ran -- that answered instead of doing the work. The action's params carry
     * what the model wrote, if it wrote anything ({@code message}); its reasoning is what the
     * model is told about it. {@link AgentLoop} runs nothing for it, records the step under
     * this name, and asks again.
     */
    static final String THINKING = "_thinking";

    private final ToolRegistry toolRegistry;
    private final OwnClawConfig config;
    private final LlmRouter llmRouter;

    public ThinkingEngine(ToolRegistry toolRegistry, OwnClawConfig config, LlmRouter llmRouter) {
        this.toolRegistry = toolRegistry;
        this.config = config;
        this.llmRouter = llmRouter;
    }

    /**
     * Full variant that returns the prompt, raw LLM output, and parsed action
     * for debug/observability use.
     */
    public ThinkResult decideNextActionFull(AgentContext context, LlmProvider provider) {
        // One decision, in one place. Everything downstream still receives an AgentAction, so
        // AgentLoop, AgentTrajectory and the eight special-action branches are untouched.
        StepMode mode = stepMode(context, provider);
        boolean nativeTools = mode.nativeTools();

        List<LlmMessage> messages = buildMessages(context, provider.name(), mode);

        LlmRequestConfig requestConfig = new LlmRequestConfig(
                null,   // use provider default model
                null,   // use provider default temperature
                // JSON mode is for the TEXT protocol. With native tools it is actively harmful:
                // it pushes the model to put JSON in the text body instead of emitting a
                // tool_use block, which is the one thing this change exists to stop.
                !nativeTools
        );
        // On whose behalf. Without this the gateway refuses the call -- which is the point:
        // a call site that forgets is stopped, not silently unscanned.
        requestConfig = requestConfig.withEgress(context.egress("think"));
        // At the task's thinking effort. On the local model, running the task itself, low means
        // answering without reasoning first.
        requestConfig = requestConfig.withEffort(context.options().effort());
        if (nativeTools) {
            requestConfig = requestConfig.withTools(toolsFor(context, mode));
        } else {
            // toolsFor is the only place the offered set is written, so without this a task
            // that starts on the native path and then falls back -- or that has native tools
            // switched off mid-task by the documented kill switch -- keeps the last
            // restriction forever, and AgentLoop refuses every registry tool for the rest of
            // the run. A kill switch that leaves the thing it killed in place is worse than
            // not having one.
            context.setOfferedTools(null);
        }

        // The task's progress hook keeps its stall watchdog from taking a long call for silence,
        // and lets its Stop end it; the call's own keeps what the owner is shown of it while it
        // runs, and ends a reply that has become a loop (LiveCall).
        LiveCall live = context.call(provider, mode.local(), context.atStep(null));
        try {
            LlmResponse response;
            try {
                response = provider.chat(messages, requestConfig.withProgress(live));
            } finally {
                live.close();
            }
            log.debug("ThinkingEngine LLM response ({} tokens): {}", response.totalTokens(),
                    truncate(response.content(), 200));

            // A refused reply, one cut off by a limit of the model, and one holding a tool call
            // that cannot be run never get this far: the provider path throws ProviderRefused,
            // OutputTruncated or MalformedToolCall for it (LlmResponse.requireComplete), so
            // everything below reads a whole reply.
            String text = response.content() == null ? "" : response.content();

            // No tool call came back, but tools were offered.
            //
            // On Anthropic that means the model chose to answer in prose, and treating it as a
            // final answer is right (unless it answers unattended work before any of it ran:
            // unlessAnsweredBeforeWork). On a local model it does NOT: Ollama reports a "tools"
            // capability per model, and a model that advertises it may still ignore the tools
            // array and emit the old JSON envelope as text. Mapping that straight to RESPOND
            // would deliver the raw JSON to the user as the answer. So a reply that is an
            // envelope and nothing else is read as the action it is; anything else is prose
            // (tryParseAction).
            if (nativeTools && !response.hasToolCalls() && !text.isBlank()) {
                AgentAction parsed = tryParseAction(text);
                if (parsed != null) {
                    log.info("protocol=native-but-text — the model ignored the tools array and "
                            + "emitted a text action; parsed it rather than delivering JSON.");
                } else {
                    log.info("protocol=native — answered directly with no tool call, provider={}",
                            provider.name());
                }
                return new ThinkResult(unlessAnsweredBeforeWork(parsed != null ? parsed
                                : new AgentAction(AgentAction.RESPOND, Map.of("message", text),
                                        "Answered directly without calling a tool"), context, mode),
                        messages, text, response);
            }

            // A native tool call is unambiguous: no parsing, so no parse failure. A step runs the
            // first; the loop tells the model of any others the reply made (AgentLoop.callsNotRun).
            if (nativeTools && response.hasToolCalls()) {
                var call = response.toolCalls().get(0);
                Map<String, Object> args = call.arguments() == null ? Map.of() : call.arguments();
                // Logged at INFO because otherwise there is no way to tell from outside which
                // protocol a step used: a correct answer looks identical either way, and the
                // token counts do not distinguish them. Without this the flag cannot be
                // verified in production at all, only assumed.
                log.info("Native tool call: {} ({} args) — protocol=native, provider={}",
                        call.name(), args.size(), provider.name());
                AgentAction called = new AgentAction(call.name(), args, text);
                return new ThinkResult(unlessAnsweredBeforeWork(called, context, mode), messages,
                        renderToolCallForDebug(response), response);
            }

            // Nothing came back: no text and no tool call. There is nothing to parse, so it is
            // not reported as a parse failure -- that told a model holding a tools array it had
            // broken the text envelope -- and no sentence is invented to stand for the reply.
            if (text.isBlank()) {
                return new ThinkResult(unusable("", emptyReply(response), "Continue from where the task stands."),
                        messages, text, response);
            }

            return new ThinkResult(unlessAnsweredBeforeWork(parseAction(text), context, mode), messages, text,
                    response);
        } catch (EgressRefused | ProviderRefused | OutputTruncated notToAskAgain) {
            // Not a step to ask again. The gateway refuses the same prompt again; the provider
            // declined to answer it; the reply or the conversation reached a limit of the model.
            // Asked again as steps that had gone wrong, a refusal and a cut-off came back the
            // same way until the owner was told "3 consecutive reasoning failures". The loop
            // ends the task on each, saying which -- but for a first refusal as reasoning
            // extraction, whose step it asks again with no words asked for beside the call.
            throw notToAskAgain;
        } catch (MalformedToolCall malformed) {
            // The reply came, whole, and was billed; a tool call in it cannot be run, because its
            // arguments are not a JSON object. Not an ending, and not a reply that "never came":
            // nothing ran, the model is shown what it wrote, the call as it wrote it included,
            // and it is asked again -- until steps in a row that produce nothing end the task
            // (AgentLoop.NOTHING_TO_RUN_IN_A_ROW).
            LlmResponse reply = malformed.reply();
            String text = reply.content() == null ? "" : reply.content();
            String wrote = (text.isBlank() ? "" : text + "\n\n") + reply.invalidToolCall();
            log.info("Task {}: the reply held a tool call that cannot be run ({} chars); asking again",
                    context.taskId(), wrote.length());
            return new ThinkResult(unusable(wrote, "Your previous reply held a tool call that cannot "
                            + "be run, so nothing was run.",
                    "Make the call again with its arguments as one JSON object."), messages, wrote, reply);
        } catch (LlmException e) {
            // A request the provider answered by refusing it as it stands -- a key it does not
            // take, a model it does not have, a request it cannot read -- is refused the same way
            // however often it is sent (LlmException#isRetryable), so it is not a step to ask
            // again either: the loop ends the task with the provider's own message. Sent twice
            // more, it ended "the model produced nothing" about a model that was never reached.
            // Nor is a cloud that cannot be reached (LlmException#unreachable): the loop moves the
            // task to the local model, or ends it saying neither can be reached. The local model
            // has no backoff and is on the LAN: its failed reply, a 5xx or a stream that broke
            // off -- a runner restarting -- is a step asked again, and only one it cannot connect
            // to at all ends the task.
            if (mode.local() ? e.getHttpStatus() != 0 && !e.isRetryable() || e.cannotConnect()
                    : e.getHttpStatus() != 0 || e.unreachable()) {
                throw e;
            }
            log.error("ThinkingEngine LLM call failed: {}", e.getMessage());
            // A reply that had become a loop was ended, and the owner is told, as he is shown the
            // reply while it runs: a step that goes on for minutes and then runs nothing says why.
            if (e instanceof RepeatedOutput looped) context.chat().repeated(looped, live, "the step ran nothing");
            return new ThinkResult(unusable("", "Your previous reply never came: the call to the "
                            + "model failed (" + e.getMessage() + "), so nothing was run.",
                    "Continue from where the task stands."), messages, "ERROR: " + e.getMessage(),
                    null, e.getMessage());
        }
    }

    /**
     * What a model is told of a reply with no text and no tool call: nothing was run, and how the
     * reply ended -- a thinking model can spend its turn reasoning and then stop. One sentence for
     * both loops that ask a model: a step of the task here, a turn of a delegation in
     * {@link LocalExecutor}.
     */
    static String emptyReply(LlmResponse reply) {
        String stop = reply.stopDescription();
        return "Your previous reply was empty" + (stop == null ? "" : " (stop reason: " + stop + ")")
                + ": no text and no tool call, so nothing was run.";
    }

    /**
     * A step that produced no action the loop can run, as {@link #THINKING}. What the model wrote
     * is kept whole in {@code message}, when it wrote anything; the reasoning is what it is told:
     * what happened, that reply quoted whole, and what to do now.
     */
    private static AgentAction unusable(String wrote, String what, String next) {
        boolean said = wrote != null && !wrote.isBlank();
        return new AgentAction(THINKING, said ? Map.of("message", wrote) : Map.of(),
                what + (said ? " It was:\n\n" + wrote + "\n\n" : "\n\n") + next);
    }

    /**
     * The action a reply stands for -- unless it answers restricted unattended work before
     * anything has run ({@link #nothingRanYet}): then it is {@link #answeredBeforeWork}, and the
     * model is asked again. One check for every channel a reply can finish through: prose, a
     * respond call, and a respond envelope written as text, which a local model orchestrating in
     * the cloud's place emits. Guarded one channel at a time, each guard left the others open:
     * the prose guard let through the respond call the native prompt teaches ("For respond: put
     * the whole answer in the message argument"), and a scheduled run was recorded green with
     * nothing run; the two guards together still let the envelope through.
     */
    private static AgentAction unlessAnsweredBeforeWork(AgentAction action, AgentContext context,
                                                        StepMode mode) {
        if (!action.isResponse() || !nothingRanYet(context, mode)) return action;
        log.info("Unattended task {}: refused to finish — nothing has run yet.", context.taskId());
        return answeredBeforeWork(action.responseText());
    }

    /**
     * An answer on restricted unattended work before anything has run. "I'll fetch today's news
     * digest first" is a plan, not an answer, and delivering it as COMPLETED is the specific way
     * withholding the registry would break the owner's morning email: the model cannot call the
     * skill, says what it would do, and the task ends successfully having done nothing.
     */
    private static AgentAction answeredBeforeWork(String answer) {
        return unusable(answer, "You described what you were going to do instead of doing it, "
                        + "and nothing has run yet.",
                "Nobody is waiting for this, so the work runs on the local model. Call "
                        + "'delegate' with the goal stated in full. Answer only once there is a "
                        + "result to report.");
    }

    /**
     * Package-private: the whole prompt, so a test can assert what the model is actually told.
     * <p>
     * The system prompt is the same static text for every provider, and every provider's last
     * user message ends with the same per-step block ({@link #buildDynamicContext}): the date, who
     * is waiting, what to write beside a call, the preferences, the tools where the prompt is
     * where they are learnt, the vault. The providers differ only in how the steps so far are
     * sent. That block used to be written twice, and the copy every provider but Anthropic got
     * had drifted: it never said whether anyone was waiting, which the static prompt tells the
     * model to decide by.
     */
    List<LlmMessage> buildMessages(AgentContext context, String providerName,
                                   StepMode mode) {
        // A step offered tools natively is offered skills by TOOLS messages: the task's first
        // ones are chosen on its first such step, before anything is rendered.
        if (mode.nativeTools() && context.toolsAdded() == null) context.seedTaskTools(firstTools(context));
        if (mode.nativeTools() && !mode.local()) context.cloudStep(mode.localFirst());
        List<LlmMessage> messages = new ArrayList<>();
        messages.add(LlmMessage.system(buildSystemPrompt(mode)));

        if ("anthropic".equals(providerName)) {
            // Anthropic: the steps replayed turn by turn, append-only for prefix caching.
            buildAnthropicMessages(messages, context, mode);
        } else {
            // Every other provider: the task, then every step in one history message. Where the
            // TOOLS messages sit does not matter to these providers, only their order
            // (ToolSpec#offered): after the task.
            String task = buildUserMessage(context);
            String step = "\n\n---\n" + buildDynamicContext(context, mode);
            AgentTrajectory trajectory = context.trajectory();
            if (trajectory.isEmpty()) {
                messages.add(LlmMessage.user(task + step));
                withToolsAdded(messages, toolsAdded(context, mode), 0, Integer.MAX_VALUE);
            } else {
                messages.add(LlmMessage.user(task));
                withToolsAdded(messages, toolsAdded(context, mode), 0, Integer.MAX_VALUE);
                messages.add(LlmMessage.user("## History\n"
                        + trajectory.toPromptSummary(!context.earlierWordsWithheld()) + step));
            }
        }

        return messages;
    }

    /**
     * The task's tool additions a step's messages carry. None on the text protocol, nor while the
     * cloud orchestrates only: it runs no skill. On the cloud, none before the step it was first
     * offered skills to run ({@link AgentContext#skillsOfferedAfter}): the additions made before
     * it -- with the usual skills, on a task whose cloud orchestrated first and so had them
     * deferred -- are one addition there, after the steps the cloud has already read. Placed where
     * each was made, they were inserted into a conversation the provider had cached, and the
     * whole of it was written to the cache again.
     */
    private static List<AgentContext.ToolsAdded> toolsAdded(AgentContext context, StepMode mode) {
        var added = context.toolsAdded();
        if (added == null || mode.localFirst() || !mode.nativeTools()) return List.of();
        if (mode.local()) return added;
        Integer from = context.skillsOfferedAfter();
        if (from == null) return List.of();
        var first = new LinkedHashSet<String>(context.orchestratedFirst() ? context.usualTools() : List.of());
        var out = new ArrayList<AgentContext.ToolsAdded>();
        for (var addition : added) {
            if (addition.afterSteps() <= from) first.addAll(addition.names());
            else out.add(addition);
        }
        out.addFirst(new AgentContext.ToolsAdded(from, List.copyOf(first)));
        return out;
    }

    /**
     * The task, then every step in the order it happened: the model's action as an assistant
     * turn, its result as the user turn after it ({@link AgentTrajectory.Turn#observationText})
     * -- each whole, PRIVATE results as the descriptions they were recorded as.
     * <p>
     * A step the loop took itself ({@link AgentTrajectory.Turn#byTheLoop}) has no assistant turn:
     * the model did not write it. What the model was told about it -- a reply that could not be
     * used, a reflection, a message the user sent while the task worked -- joins the user turn it
     * follows, in its place, and the roles alternate
     * as the Messages API requires. So an assistant turn holds what the model wrote, with one
     * exception that byTheLoop names: the skill_create the loop runs at step 1 when
     * CapabilityResolver finds a missing capability is replayed as an action, and its arguments
     * and reasoning are the resolver's constants.
     * <p>
     * Every message but the last is the same bytes on every later step, so the conversation is
     * append-only and the provider's sliding cache breakpoint keeps hitting. The last user turn
     * carries what changes on every step (the date, the tools on the text protocol, the vault),
     * behind the cache breakpoints. The cache boundary marks where the task ends in the first
     * message, on every step: the task -- with the tools and the system prompt before it -- is
     * the stable prefix, which the provider caches for longer than the steps after it.
     */
    void buildAnthropicMessages(List<LlmMessage> messages, AgentContext context, StepMode mode) {
        var added = toolsAdded(context, mode);
        int next = 0;
        int steps = 0;
        var user = new StringBuilder(buildUserMessage(context)).append(CACHE_BOUNDARY_MARKER);
        for (var turn : context.trajectory().turns()) {
            if (turn.byTheLoop()) {
                String told = turn.observation().output();
                if (told != null && !told.isBlank()) user.append("\n\n").append(told);
                steps++;
                continue;
            }
            messages.add(LlmMessage.user(user.toString()));
            next = withToolsAdded(messages, added, next, steps);
            messages.add(LlmMessage.assistant(turn.actionText(!context.earlierWordsWithheld())));
            user = new StringBuilder(turn.observationText());
            steps++;
        }
        user.append("\n\n---\n").append(buildDynamicContext(context, mode));
        messages.add(LlmMessage.user(user.toString()));
        withToolsAdded(messages, added, next, Integer.MAX_VALUE);
    }

    /**
     * What this step offers the model. Decided once and handed to every builder, because the
     * tools array and the prompt disagreeing is worse than either choice alone: the model is
     * told in prose that it owns a skill while the API says it does not, and which half wins
     * decides the run.
     *
     * @param local the local model takes this step, running the task on its own
     *              ({@link LlmRouter#selectProvider}): the prompt says so, and nothing that holds
     *              only for the cloud
     */
    record StepMode(boolean nativeTools, boolean localFirst, boolean local) {}

    /**
     * Can the local tier be handed real work — asked once per task, then remembered.
     * <p>
     * Two conditions, deliberately evaluated together and in this order. {@code isAvailable()}
     * only proves the server answers {@code /api/tags}, and through the months the local tier
     * was broken it answered fine while every reply came back unrelated, because the model
     * could not be driven through {@code /api/chat} — so the first question is whether the
     * configured model is genuinely drivable. The second is whether it takes native tool calls,
     * because that is what makes a delegation reliable enough to be the only path; on the text
     * protocol it stays a preference, as it has been all along.
     * <p>
     * The order is not incidental: {@code status()} re-reads the model's capabilities and
     * refreshes the flag {@code supportsTools()} returns, which would otherwise still be
     * whatever was true at boot — and on this host an Ollama upgrade swaps the loaded model
     * often enough for that to matter.
     */
    boolean localTierReady(AgentContext context) {
        Boolean known = context.localTierReady();
        if (known != null) return known;
        boolean ready;
        try {
            var status = llmRouter.localStatus();
            ready = status.ok() && llmRouter.local().supportsTools();
            if (!ready) {
                log.info("Local tier cannot take delegated work ({}, tools={}); this task runs "
                                + "entirely on the cloud.",
                        status.detail(), llmRouter.local().supportsTools());
            }
        } catch (Exception e) {
            log.warn("Local tier health check failed: {}", e.toString());
            ready = false;
        }
        context.setLocalTierReady(ready);
        return ready;
    }

    StepMode stepMode(AgentContext context, LlmProvider provider) {
        boolean nativeTools = config.getMentor().isNativeTools() && provider.supportsTools();
        // A test that builds prompts by hand has no router, and its provider is the cloud.
        boolean local = llmRouter != null && llmRouter.isLocal(provider);

        // Gated on nativeTools, because withholding tools from an array nobody is reading
        // restricts nothing -- on the text protocol the manifest is the channel. Never for the
        // local model running the task itself: the registry would be withheld from the one
        // model that runs the tools, to make it hand the work to itself.
        //
        // Unattended work always (local-first-unattended); a chat when the owner chose cost over
        // speed (Cheaper, the slider's default or the chat's own): nobody, or nobody in a hurry,
        // waits on it.
        boolean localFirst = nativeTools && !local
                && (context.isUnattended() ? config.getMentor().isLocalFirstUnattended()
                        : context.options().preferCost())
                && localTierReady(context)
                // The valve. If a delegation has already failed, the local tier has had its
                // turn and the registry comes back for the rest of the task. Without this, a
                // local model that cannot manage the work leaves the orchestrator re-delegating
                // until the task is stopped, and the owner's morning email simply never arrives --
                // trading a token saving for a silently broken task.
                && !delegationFailed(context);

        return new StepMode(nativeTools, localFirst, local);
    }

    /**
     * The tools the CLOUD model may call on this step.
     *
     * <p>On unattended work, and on a chat whose owner chose cost over speed (Cheaper, the
     * default: {@link #stepMode}), the registry is withheld, so the cloud can orchestrate but cannot
     * execute. That is the architecture the owner asked for — "cloud orchestrates, local
     * executes" — made structural instead of advisory.
     *
     * <p>It is structural because advice demonstrably does not work, though not in the way the
     * first version of this comment claimed. The orchestrator HAS been choosing delegation: the
     * scheduled runs on 19, 20 and 21 September each picked it at step one. Every one of them
     * failed, on a context window one step too small, after 107 to 325 seconds of trying. The
     * window was raised on the 21st, and on the 22nd — the first day native tool calling was
     * live — delegation was not chosen at all, and both runs spent a quarter of a million cloud
     * tokens each on work with no judgement in it.
     *
     * <p>So the record is: not one delegation has ever succeeded on a run that chose it by
     * itself. First it was chosen and broke; then it stopped being chosen. Meanwhile the task
     * descriptions name the exact skills and the exact order ("using daily_news_digest skill,
     * then ... Use smtp_send_email"), and a specific instruction outcompetes a general
     * preference. Rewriting them is not the fix either: an unattended task naming no skills at
     * all, pure local work, was still done by the cloud — skill_create, then shell_exec, no
     * delegation.
     *
     * <p>Only when the local model is genuinely usable and advertises tool use. If it is not,
     * the cloud keeps the full set and the task runs exactly as it does today: a local tier that
     * is not answering must not become a reason for scheduled work to stop.
     *
     * <p>A chat on Fastest is untouched: there the owner chose speed, and a local step costs
     * about a minute.
     *
     * <p>Withheld is not left out: on a task whose cloud model is asked to orchestrate first, every
     * skill is in the array from its first step to its last, deferred, and the array is the same
     * bytes before a delegation fails and after ({@link #toolset}). The tools are the front of the
     * provider's cached prefix: when the valve handed the registry back as an array of its own,
     * the whole conversation was written to the cache again -- $1.43 of one $4.60 task on
     * 2026-10-08. The valve now offers the skills by a TOOLS message after the steps so far
     * ({@link #toolsAdded}). What the loop lets the cloud call is {@link AgentContext#offeredTools}.
     */
    List<com.ownclaw.llm.ToolSpec> toolsFor(AgentContext context, StepMode mode) {
        if (!mode.local()) context.cloudStep(mode.localFirst());
        var tools = toolset(context, mode, !mode.local() && context.orchestratedFirst());
        if (!mode.localFirst()) {
            context.setOfferedTools(null);
            return tools;
        }
        log.info("Task {} ({}): offering the cloud orchestration only — the registry is "
                        + "withheld, so mechanical work must be delegated to the local model.",
                context.taskId(), context.isUnattended() ? "unattended" : "a chat on Cheaper");
        var specials = new ArrayList<>(SpecialActionSchemas.ALL);
        specials.add(SpecialActionSchemas.FIND_TOOLS);
        context.setOfferedTools(specials.stream()
                .map(com.ownclaw.llm.ToolSpec::name)
                .collect(java.util.stream.Collectors.toUnmodifiableSet()));
        return tools;
    }

    /**
     * What delegate's description adds on a task whose cloud orchestrates first. The cloud cannot
     * CALL the skills, but it still has to know they exist, or it will write a goal that asks for
     * something already built -- or reach for skill_create to rebuild it. So this names the
     * task's likeliest -- its first tools and the owner's usual ones, the same on every step of
     * the task -- and find_tools searches the rest. It used to carry every skill's whole
     * description: 114,293 characters of a scheduled run's every request. In the description, a
     * tool part, not the task's message: the canary reads the user parts for private text, and a
     * skill's own words are not that. Its words hold after a delegation has failed too, when the
     * skills are offered after all: the description is part of the cached prefix and does not
     * change.
     */
    private String orchestrationNote(AgentContext context) {
        var likeliest = new StringBuilder();
        var added = context.toolsAdded();
        var names = new LinkedHashSet<String>();
        if (added != null && !added.isEmpty()) names.addAll(added.getFirst().names());
        names.addAll(context.usualTools());
        for (String name : names) {
            toolRegistry.find(name).ifPresent(t -> likeliest.append("\n- ").append(t.name()).append(": ")
                    .append(ToolFinder.gist(t.description())));
        }
        return "\n\n" + (context.isUnattended() ? "You are orchestrating unattended work"
                        : "The owner has chosen cost over speed")
                + ", so while the local model can do the work you are not given the skills to run "
                + "yourself — this is how the work gets done. State the goal fully and name the skills "
                + "it needs in 'tools'; find_tools searches every skill."
                + (likeliest.isEmpty() ? "" : " The likeliest for this task:" + likeliest);
    }

    /**
     * The tools of a step that is offered skills, the cloud's and the local model's alike: the
     * special actions -- delegate described for the local model, a run of itself for private
     * data -- find_tools, the owner's usual skills, and every other skill deferred: sent, but read
     * by the model only from the TOOLS message that offers it ({@link #withToolsAdded}). The part
     * that is not deferred is the same from task to task while the usual skills are, so the
     * cloud's cached prefix is shared between tasks. Every skill's description used to be sent on every step: ~170,000
     * characters on the cloud, and most of a 42,000-token first prompt the local model reads at
     * about 90 tokens a second. A model can call only what it is offered: the local one, asked to
     * call a tool it was not given, was seen to call one it was -- get_weather, with a router's
     * address as the city -- so the prompt says to find a tool first.
     *
     * @param orchestrating the task's cloud model was asked to orchestrate first
     *                      ({@link AgentContext#orchestratedFirst}): delegate says so, and the usual
     *                      skills are deferred too -- offered with the rest by one TOOLS message on
     *                      the step after a delegation fails, if one does ({@link #toolsAdded})
     */
    private List<com.ownclaw.llm.ToolSpec> toolset(AgentContext context, StepMode mode, boolean orchestrating) {
        var specials = new ArrayList<com.ownclaw.llm.ToolSpec>();
        for (var spec : SpecialActionSchemas.ALL) {
            specials.add(!AgentAction.DELEGATE.equals(spec.name()) ? spec
                    : mode.local() ? new com.ownclaw.llm.ToolSpec(spec.name(),
                            SpecialActionSchemas.DELEGATE_ON_THE_LOCAL_MODEL, spec.inputSchema())
                    : orchestrating ? new com.ownclaw.llm.ToolSpec(spec.name(),
                            spec.description() + orchestrationNote(context), spec.inputSchema())
                    : spec);
        }
        specials.add(SpecialActionSchemas.FIND_TOOLS);
        Set<String> usual = Set.copyOf(context.usualTools());
        Comparator<Tool> byName = Comparator.comparing(Tool::name, String.CASE_INSENSITIVE_ORDER);
        var skills = new ArrayList<Tool>();
        toolRegistry.all().stream().filter(t -> usual.contains(t.name())).sorted(byName).forEach(skills::add);
        toolRegistry.all().stream().filter(t -> !usual.contains(t.name())).sorted(byName).forEach(skills::add);
        Set<String> special = new java.util.HashSet<>();
        specials.forEach(s -> special.add(s.name()));
        return ToolSchemas.inOrder(specials, skills, context.credentialKeys()).stream()
                .map(spec -> special.contains(spec.name()) || !orchestrating && usual.contains(spec.name())
                        ? spec : spec.asDeferred())
                .toList();
    }

    /**
     * The skills a task is first offered beyond the usual ones: those it has already run or
     * created -- the local model can take a task over from the cloud mid-way -- those the request
     * or the chat so far names, and find_tools's best few matches for the request ({@link #FIRST_MATCHES}).
     */
    private List<String> firstTools(AgentContext context) {
        var names = new ArrayList<String>();
        for (var turn : context.trajectory().turns()) {
            AgentAction ran = turn.action();
            if (ran == null) continue;
            if (toolRegistry.find(ran.tool()).isPresent()) names.add(ran.tool());
            // A skill the task created before this step: the resolver's own at step 1 among them.
            if (ran.isSkillCreate() && turn.observation() != null && turn.observation().success()) {
                names.add(AgentLoop.skillOf(ran));
            }
        }
        String said = context.originalMessage() + "\n"
                + (context.conversationSummary() == null ? "" : context.conversationSummary());
        for (Tool tool : toolRegistry.all()) {
            if (LocalExecutor.namedIn(said, tool.name())) names.add(tool.name());
        }
        ToolFinder.find(toolRegistry.all(), context.originalMessage(), 1).tools().stream()
                .limit(FIRST_MATCHES).forEach(tool -> names.add(tool.name()));
        return names;
    }

    /**
     * How many of find_tools's best matches for the request a task starts with. A skill's
     * description runs to 8,500 characters, and eight of them were ~15,000 tokens of a cloud
     * task's first request -- and minutes of the local model's first read -- whether or not the
     * task used one. find_tools gives the rest when the model asks.
     */
    static final int FIRST_MATCHES = 3;

    /**
     * The TOOLS messages of a task, in place: each after the user message that holds the steps
     * there were when its tools were offered ({@link AgentContext.ToolsAdded}), from the
     * {@code next} addition on, up to {@code steps}. None on local-first work, whose cloud calls
     * no skill.
     *
     * @return the index of the next addition not yet placed
     */
    private static int withToolsAdded(List<LlmMessage> messages, List<AgentContext.ToolsAdded> added,
                                      int next, int steps) {
        while (next < added.size() && added.get(next).afterSteps() <= steps) {
            var names = added.get(next++).names();
            if (!names.isEmpty()) messages.add(LlmMessage.toolsAdded(names));
        }
        return next;
    }

    /**
     * Restricted unattended work where nothing has actually succeeded yet.
     * <p>
     * In that state an answer is a plan, not an answer — "I'll fetch today's news digest first"
     * — and delivering it ends the task COMPLETED having done nothing. Asked of every channel the
     * model can finish through, in {@link #unlessAnsweredBeforeWork}.
     */
    private static boolean nothingRanYet(AgentContext context, StepMode mode) {
        // Unattended only: on a chat, a question that needs no skill is answered at once.
        return mode.localFirst() && context.isUnattended() && context.trajectory().turns().stream()
                .noneMatch(t -> t.observation() != null && t.observation().success());
    }

    /** Whether the local tier has already been given this task and could not finish a step. */
    private static boolean delegationFailed(AgentContext context) {
        return context.trajectory().turns().stream().anyMatch(t ->
                t.action() != null && AgentAction.DELEGATE.equals(t.action().tool())
                        && t.observation() != null && !t.observation().success());
    }

    /** What the debug panel shows for a native call, where there is no raw JSON to display. */
    private String renderToolCallForDebug(com.ownclaw.llm.LlmResponse response) {
        try {
            var out = new LinkedHashMap<String, Object>();
            if (response.content() != null && !response.content().isBlank()) {
                out.put("reasoning", response.content());
            }
            var calls = new java.util.ArrayList<Map<String, Object>>();
            for (var c : response.toolCalls()) {
                calls.add(Map.of("tool", String.valueOf(c.name()),
                        "params", c.arguments() == null ? Map.of() : c.arguments()));
            }
            out.put("toolCalls", calls);
            return TextCalls.MAPPER.writeValueAsString(out);
        } catch (Exception e) {
            return String.valueOf(response.content());
        }
    }

    /**
     * The per-step block (datetime, attendance, what to write beside a call, preferences, tools,
     * vault, what next): what changes from one step to the next, at the end of the last user
     * message for every provider ({@link #buildMessages}), so the system prompt stays the same
     * bytes and is cached. What changes partway through a task is said here too -- the words
     * asked for beside a call once the provider has declined a step as reasoning extraction, the
     * skills given once a delegation has failed -- for the same reason: the tools and the system
     * prompt are the front of the provider's cached prefix, and a change there writes the whole
     * conversation to the cache again. Only a task the local model takes over has another system
     * prompt, the local model's own.
     */
    private String buildDynamicContext(AgentContext context, StepMode mode) {
        var sb = new StringBuilder();

        sb.append("## Environment\n");
        sb.append("- Platform: ").append(detectPlatform()).append("\n");
        sb.append("- DateTime: ").append(LocalDateTime.now()
                .truncatedTo(java.time.temporal.ChronoUnit.MINUTES)
                .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)).append("\n");

        // Whether anyone is waiting for this answer.
        //
        // The agent weighs time against cost, and was given no way to tell whether anyone is
        // waiting, so it had to guess. It is not a guess: the origin of the task settles it.
        // The scheduler and /bg submit at background priority, a chat message does not, and
        // TaskQueue has already recorded which this is.
        //
        // A waiting user is a fact, not a ban on the local model. Told "use it only when it
        // genuinely saves more than it costs", attended work never delegated: the cloud wrote
        // three skills in seventeen minutes to read router data it could not see, which the
        // local model reads directly. So the local model's speed is stated as the facts it is,
        // and the delegate description and the rules say where it is the right tool.
        if (mode.local()) {
            sb.append("- Model: you are the local model, running this task on your own: ")
              .append(context.onLocal() ? "the cloud model could not be used -- " + context.onLocalBecause()
                      : context.options().localOnly() ? "the owner has switched the cloud model off"
                      : "no cloud model is configured")
              .append(". Do what you can with the tools you have, and say what you could not do.\n");
        }
        if (context.isUnattended()) {
            sb.append("- Attendance: NOBODY IS WAITING. This was started by the scheduler or sent "
                    + "to the background; the answer is delivered to the chat whenever it is "
                    + "ready. Minutes are free here. "
                    + (mode.local() ? "" : "Prefer 'delegate' for anything the local "
                    + "model can do, especially work on this machine, the LAN or private data, and ")
                    + (mode.local() ? "Never" : "never") + " stop to ask a question -- decide, and "
                    + "say which assumption you made.\n");
        } else {
            sb.append("- Attendance: THE USER IS WAITING in the chat right now"
                    + (context.declinedAsReasoning() ? "." : ", and reads what you write beside "
                    + "each call as you go.")
                    + (mode.local() ? "" : mode.localFirst() ? " The owner has chosen cost over "
                    + "speed, so the skills run on the local model: delegate the work. It reads "
                    + "about 100 tokens a second and writes about 8, so a delegation takes minutes."
                    : " Delegate where the local model is "
                    + "the right tool -- private data, and tool calls on this machine, the LAN "
                    + "and its servers -- knowing its speed: it reads about 100 tokens a second "
                    + "and writes about 8, so a delegation that reads a long result or writes a "
                    + "long answer takes minutes.")
                    + "\n");
        }
        // The valve has opened on a task whose cloud orchestrated first (stepMode): the skills
        // came back by a TOOLS message, and delegate's description, which is cached, still says
        // why they were not given.
        if (!mode.local() && mode.nativeTools() && !mode.localFirst() && context.orchestratedFirst()) {
            sb.append("- Skills: a delegation of this task has failed, so from here you are given the "
                    + "skills to run yourself as well.\n");
        }
        sb.append(beside(mode.nativeTools(), context.declinedAsReasoning())).append("\n\n");

        if (context.userPreferences() != null && !context.userPreferences().isBlank()) {
            sb.append("## Preferences\n");
            sb.append(context.userPreferences()).append("\n\n");
        }

        // The full manifest on EVERY step, not just step 0.
        //
        // This block used to send descriptions once, at step 0, and names only from step 1
        // onwards — the comment claimed the descriptions stayed available "cached in prior
        // turns", but the message list is rebuilt from scratch on every call and the dynamic
        // block is attached to the newest message, so the step-0 manifest is simply gone by
        // step 1. From then on the agent could see that it owned imap_unread_summarizer but
        // not what it did.
        //
        // That matters most at exactly the wrong moment: skill_create is almost never the
        // first action, it happens at step 2 or later after something else has failed. So the
        // decision to build a new capability was being taken with the least information the
        // agent ever has, which is a large part of how the library reached 31 skills with
        // eight of them doing IMAP.
        //
        // It costs a few thousand tokens per step, and it is not cached — the dynamic block
        // hangs off the newest message, which is outside the Anthropic cache breakpoints by
        // design. That is the right trade: rebuilding a capability the agent already owns
        // costs far more than describing it. If the cost ever bites, the fix is to consolidate
        // the library rather than to hide it again — a manifest too big to send is a signal
        // that the library needs curating.
        // ...unless the tools array already carries it. Then this block is the same information
        // a second time, and the worse copy: the array is inside the Anthropic cache prefix and
        // is read at a tenth of the price, while this hangs off the newest message and is paid
        // in full on every single step. Sending both was costing the manifest twice per step.
        // On local-first work, which only native tools can be (stepMode), the array withholds
        // the skills, and delegate's own description names the likeliest (orchestrationNote):
        // not here as well. Rendering the same text into a tool part AND into the newest user
        // message forced the canary to choose between refusing a copy the cloud is receiving
        // anyway and excusing text across parts. Excusing across parts turned out to be a leak —
        // a short private artifact quoted anywhere went out as soon as it appeared in some
        // skill's example. One copy, and the question does not arise.
        if (!mode.nativeTools()) {
            sb.append(toolsSection(context));
        } else if (!mode.localFirst() && toolRegistry.all().isEmpty()) {
            sb.append("No tools yet — use skill_create.\n");
        }

        List<String> vaultKeys = context.credentialKeys();
        if (!vaultKeys.isEmpty()) {
            sb.append("\nVault: ").append(String.join(", ", vaultKeys)).append("\n");
        }

        if (!context.trajectory().isEmpty()) {
            sb.append("\nNext action? If done, use 'respond'.");
        }

        // Delegation nudge — injected by AgentLoop when repetitive tool calls are detected
        Object nudge = context.metadata().get("delegationNudge");
        if (nudge instanceof String nudgeMsg && !nudgeMsg.isBlank()) {
            sb.append("\n\nCOST WARNING: ").append(nudgeMsg);
        }

        return sb.toString();
    }

    /**
     * Every tool with its whole description, for the text protocol, where the prompt is the only
     * place the model learns what it can call. Native tools carry the same in the tools array.
     */
    private String toolsSection(AgentContext context) {
        String manifest = toolRegistry.generateManifest(toolRegistry.all(), context.credentialKeys());
        return "## Tools\n" + (manifest.isBlank() ? "No tools yet — use skill_create." : manifest) + "\n";
    }

    /**
     * Build the system prompt. This defines the agent's behavior, available tools,
     * and output format. Completely generic — no domain-specific content. Sent whole on every
     * step, never shortened, and the same for every provider, task and step: what changes goes in
     * the per-step block at the end of the last user message ({@link #buildDynamicContext}), so
     * the provider's prompt cache holds all of this. So it says nothing that holds only while the
     * cloud orchestrates, nor what to write beside a call, which changes in a task the provider
     * has stopped as reasoning extraction: changed here, it wrote the whole conversation to the
     * cache again -- $1.40 of one task on 2026-10-08. The one exception is the local model's own
     * prompt ({@link StepMode#local}).
     *
     * @param mode when {@code nativeTools} is set, the action list and the JSON-envelope
     *             instruction are omitted. The tools array carries both, and this claim used to
     *             be false: the parameter was accepted and never read, so every native step also
     *             carried "Single JSON: {reasoning, tool, params}" -- an instruction to use the
     *             one protocol the tools array exists to replace, which is the mechanism by
     *             which a model talks its way back onto the text path.
     */
    private String buildSystemPrompt(StepMode mode) {
        boolean nativeTools = mode.nativeTools();
        var sb = new StringBuilder();

        sb.append("You are an autonomous agent. Reason, pick a tool, observe, repeat until done.\n\n");

        // Identity
        sb.append("## Identity\n");
        sb.append("Personal agent running locally. Full system access via Python skills.\n");
        sb.append("ANY system dependency is installable — use system_packages in skill_create.\n");
        sb.append("NEVER refuse or claim a package/tool is unavailable. Use skill_create for any missing capability.\n\n");

        // NOTE: CapabilityResolver hints are handled deterministically in AgentLoop.runLoop()
        // at step 0 — the hint bypasses the ThinkingEngine entirely and synthesizes the
        // skill_create action without any LLM call. By the time the ThinkingEngine runs
        // (step 1+), the skill is already created and visible in the trajectory.

        // Special actions (static — tool descriptions never change).
        //
        // Under native tools this whole block is SpecialActionSchemas restated as prose. Sending
        // both describes every action twice, and the two copies then have to be kept in step by
        // hand -- which they already were not: the prose said skill_create's code is generated
        // for you, the schema demanded you write it.
        if (!nativeTools) {
        sb.append("## Actions\n\n");
        sb.append("respond(message): Final answer.\n\n");
        sb.append("ask_user(message): Ask ONLY when info is missing. Never ask permission — just act.\n\n");

        sb.append("skill_create: Create/update Python skill (code AUTO-GENERATED — specify WHAT not HOW).\n");
        sb.append("  THINK FIRST: anticipate edge cases, required imports, error handling. Rework burns tokens.\n");
        sb.append("  Local execution, full system access. To fix: reuse SAME name (overwrites). NEVER _v2/_fixed/_new.\n");
        sb.append("  name*: lowercase id | description*: behavior spec + edge cases + output format\n");
        sb.append("  parameters*: JSON {key: {type, description, required}} | requirements: pip pkgs (one/line)\n");
        sb.append("  requires_network | has_side_effects | timeout: max secs (default 30)\n");
        sb.append("  credentials: comma-separated vault keys, auto-injected as env vars. NEVER pass values directly.\n");
        sb.append("  system_packages: space-separated apt pkg names \u2192 auto-installed in a container. Use for ANY needed OS binary or library.\n");
        sb.append("  container_image: Docker base image. Optional \u2014 system picks a sensible default and auto-recovers if unavailable.\n\n");

        sb.append("skill_manage(action=read|delete|list|analyze, [name])\n");
        sb.append("credential_manage(action=list|check, [key])\n");
        sb.append("memory_manage(action=store|list|delete|recall, [key], [content], [query])\n");
        sb.append("  recall: every past task whose record matches 'query', each in full, most relevant first\n\n");

        sb.append("schedule_manage:\n");
        sb.append("  action=schedule_once|schedule_recurring|list|cancel|pause|resume\n");
        sb.append("  description: task message | time: natural language | schedule: natural language or Spring cron\n");
        sb.append("  max_runs | task_id (for cancel/pause/resume)\n\n");

        // 'steps' was advertised as required, which is why this was never once used in seven
        // months of production. Pre-specifying every tool AND its params demands foreknowledge
        // the orchestrator almost never has, because each step's params come from the previous
        // step's output. The executor never needed it: it runs its own think-act-observe loop
        // with the whole tool manifest. So it takes a goal, and steps are a hint.
        sb.append("delegate: hand a sub-goal to the local model. It runs its own loop on this\n");
        sb.append("  machine with the tools you name and your credentials, and costs nothing.\n");
        sb.append("  It reads what you cannot: every earlier result the goal names by its handle\n");
        sb.append("  ({{N}}) is given to it whole -- a private result or a file you are shown only\n");
        sb.append("  as a description. It sees no other earlier result.\n");
        sb.append("  Best for: private data -- to read, summarise, search, compare or answer a\n");
        sb.append("  question about it -- and tool calls on this machine, the LAN and its servers.\n");
        sb.append("  What it reads stays on this machine. Its answer comes back to you -- as a\n");
        sb.append("  handle to pass on when it quotes private data.\n");
        sb.append("  Speed: it reads about 100 tokens a second and writes about 8, so reading a\n");
        sb.append("  long result or writing a long answer takes minutes.\n");
        sb.append("  goal* — what to achieve, stated fully, with the handle of each earlier\n");
        sb.append("    result it should read; the local model works out the steps.\n");
        sb.append("  steps (optional): [{description, tool, params}] only when the order matters\n");
        sb.append("    and you already know it. Omit it rather than guess at params.\n");
        sb.append("  tools: comma-separated exact names of the tools it will need. Only these,\n");
        sb.append("    and any the goal or an unattended (scheduled or /bg) task names, are loaded -- every tool\n");
        sb.append("    definition takes room in its context that the work needs.\n");
        sb.append("  checkpoints: what to verify before it says it is done\n\n");
        }

        // Credential rules
        sb.append("## Credentials\n");
        sb.append("Vault values auto-injected as env vars into skills declaring them.\n");
        sb.append("- Declare in skill_create 'credentials' param (exact vault key names).\n");
        sb.append("- All present → create and run. Don't ask user.\n");
        sb.append("- NEVER ask the user to paste a secret to you, and never put one in an action.\n");
        sb.append("  To add or fix one, tell the user to type: /cred set KEY value\n");
        sb.append("  That writes straight to the vault without the value passing through you.\n");
        sb.append("- Only mention credentials NOT already in the vault.\n\n");

        sb.append("## Memory\n");
        sb.append("Facts persist across conversations. 'Remember this' → store immediately.\n");
        sb.append("Of past tasks you are shown only this chat's, under Prior Context: memory_manage action=recall with a query returns every past task that matches, in full.\n\n");

        // Output format. What to write beside a call is the per-step block's (beside).
        sb.append("## Output\n");
        if (nativeTools) {
            sb.append("Call exactly one tool per step.\n");
            sb.append("For respond: put the whole answer in the message argument.\n\n");
        } else {
            sb.append("Single JSON: {\"tool\": \"name\", \"params\": {...}}\n");
            sb.append("For respond: put ALL content in params.message, NOT in reasoning.\n\n");
        }

        // Behavioral guidelines + cost + self-improvement combined
        sb.append("## Rules\n");
        sb.append("- No tools needed → respond directly. Never fabricate outputs.\n");
        sb.append("- On failure: diagnose WHY, then try fundamentally different approach. Never repeat failed actions.\n");
        sb.append("- Skill errors: fix via skill_create (SAME name). Never _v2/_fixed.\n");
        sb.append("- Private data -- a result you are shown only as a description, a file the user sent -- is read by the local model: to read, summarise, search, compare or answer a question about it, delegate and name its handle in the goal.\n");
        if (!mode.local()) sb.append("- What you are shown has secrets removed («secret removed», «vault:KEY») and identifiers -- e-mail addresses, phone numbers, account and card numbers, MAC addresses, public IP addresses, SSIDs, client hostnames -- written as placeholders -- <email_N>, <ssid_N> and so on, N a number. Write a placeholder wherever you mean its value, in arguments, code and answers alike: it is replaced with the real value on this machine.\n");
        if (!mode.local()) sb.append("- Work on this machine, the LAN or its servers that is a sequence of tool calls the local model can run → delegate it (free, stays on the host).\n");
        // True whether the cloud is given skills to run or orchestrates only (toolsFor), so that
        // the prompt is the same before a delegation fails and after.
        if (nativeTools) sb.append("- You are not given every skill, and you can call only the tools you are given: find_tools searches every skill by English keywords and says what it finds. Search before you create a skill.\n");
        sb.append("- No suitable tool → create one. Poor results → read skill code, overwrite fix.\n");
        sb.append("- A skill is for a deterministic program: parsing at scale, changing configuration, repeated runs. Never create one only to read or summarise data -- delegate that.\n");
        sb.append("- Explore thoroughly before 'not found'. Search the internet if stuck.\n");
        sb.append("- Respond in user's language. Search/selectors in content's language.\n");
        sb.append("- Outputs: clean text. Extract file content (PDF/DOCX/CSV), don't just report links.\n");
        sb.append("- Garbled text → encoding bug, fix the tool.\n");
        sb.append("- Partial USEFUL result beats empty failure. Each step must make new progress.\n");
        sb.append("- NEVER write multi-line code via shell_exec/python3 -c. Use skill_create.\n");
        sb.append("- Need an OS binary or system library? Add it to system_packages in skill_create. NEVER say a package is unavailable.\n");

        return sb.toString();
    }

    /**
     * Build the user message containing the original request and conversation context.
     */
    private String buildUserMessage(AgentContext context) {
        var sb = new StringBuilder();

        // Conversation summary for context
        if (context.conversationSummary() != null && !context.conversationSummary().isBlank()) {
            sb.append("## Prior Context\n");
            sb.append(context.conversationSummary()).append("\n\n");
        }

        sb.append("## Task\n");
        sb.append(context.originalMessage());

        String files = filesSection(context);
        if (!files.isEmpty()) sb.append("\n\n").append(files);

        return sb.toString();
    }

    /**
     * The files sent with this message, as the cloud may know them: a handle, a type and a size.
     * <p>
     * Facts, not a rule. The guard is {@link AgentContext#decide}, which makes every result of a
     * task holding a file PRIVATE whatever the cloud does; this says so, so that it plans for a
     * result it will not read instead of asking for one and getting a description back. Built
     * from the handle and the type-and-size line only -- never the name, which can itself be what
     * the file holds. In the user message, so the cached system prompt and tools stay the same.
     */
    static String filesSection(AgentContext context) {
        List<Artifact> files = context.files();
        if (files.isEmpty()) return "";
        var sb = new StringBuilder("## Files sent with this message\n");
        for (Artifact f : files) {
            sb.append("- ").append(f.handle());
            if (f.why().size() > 1) sb.append(": ").append(f.why().get(1));
            sb.append('\n');
        }
        sb.append("These files are private: you are not shown their names or their contents. ")
          .append("Every skill run in this task is given them as params._attached_files, a list of ")
          .append("{id, name, content_type, path, container_path}, so every result of this task is ")
          .append("private too and reaches you only as a description. Only the local model reads ")
          .append("private results. To answer from a file, delegate: name its handle in the goal, and ")
          .append("a text file is given to the local model whole; for one with no text read, name the ")
          .append("skill that reads it (if none does, skill_create one that parses it from ")
          .append("params._attached_files -- parsing is what a skill is for). The ")
          .append("delegation's answer comes back to you, or -- when it quotes the file -- as a ")
          .append("handle: make that handle the whole of respond's message and its text is ")
          .append("filled in on this machine for the user. ")
          .append("A file is handed only to this task: asking the user a question ends it, and ")
          .append("the reply is a new task without the file -- read it first.\n");
        return sb.toString();
    }

    /**
     * Parse text as an action when the whole of it is one, or return null.
     * <p>
     * {@link #parseAction} reads the text protocol, where a reply that is not an action is a step
     * that produced nothing to run. When tools were offered that is wrong: there, prose means the
     * model chose to answer, but a JSON envelope means a local model ignored the tools array, and
     * handing that envelope to the user as their answer would be worse than either. This
     * distinguishes the two, and the envelope is the whole reply -- one code fence around it
     * forgiven. An answer that quotes a call among its prose is the answer: read for its first
     * object with a "tool" key, "the call looks like this: {...}" ran the example -- an email to
     * the example's address -- and the answer it was part of never reached the owner.
     */
    AgentAction tryParseAction(String raw) {
        if (raw == null || raw.isBlank()) return null;
        Map<String, Object> parsed = wholeJsonObject(raw);
        if (parsed == null) return null;
        Object tool = parsed.get("tool");
        if (tool == null || String.valueOf(tool).isBlank()) return null;
        return parseAction(raw);
    }

    /** The text as one JSON object, when that object is all of it but a code fence; else null. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> wholeJsonObject(String text) {
        String json = LlmOutputUtils.stripCodeFences(text.strip());
        try (var parser = TextCalls.MAPPER.getFactory().createParser(json)) {
            Object value = TextCalls.MAPPER.readValue(parser, Object.class);
            return value instanceof Map && parser.nextToken() == null ? (Map<String, Object>) value : null;
        } catch (Exception notOneObject) {
            return null;
        }
    }

    /**
     * Read a text-protocol reply -- never blank: an empty reply is recognised before this -- as
     * an action. Handles common LLM output quirks (code fences, comments, extra text, etc.). A
     * reply that is not an action is a {@link #THINKING} step that quotes it and restates the
     * format.
     */
    AgentAction parseAction(String raw) {
        String cleaned = LlmOutputUtils.stripCodeFences(raw.strip());

        // The first JSON object in it, read as the local tier reads a call (TextCalls).
        Map<String, Object> parsed = TextCalls.firstObject(cleaned);
        if (parsed == null) {
            log.warn("ThinkingEngine: no valid JSON object in LLM response, so no action");
            return unusable(raw, "Your previous reply is not an action: there is no JSON object "
                    + "in it, so nothing was run.", ACTION_FORMAT);
        }

        try {

            // The tool and its arguments, under whichever names the model used for them.
            String tool = TextCalls.tool(parsed);

            String reasoning = getStringField(parsed, "reasoning");
            if (reasoning == null || reasoning.isBlank()) reasoning = getStringField(parsed, "thought");
            if (reasoning == null || reasoning.isBlank()) reasoning = getStringField(parsed, "thoughts");
            if (reasoning == null || reasoning.isBlank()) reasoning = getStringField(parsed, "thinking");

            Map<String, Object> params = TextCalls.params(parsed);

            if (tool == null || tool.isBlank()) {
                // If there's a "message" field at root level, treat as response
                String message = getStringField(parsed, "message");
                if (message == null || message.isBlank()) message = getStringField(parsed, "response");
                if (message == null || message.isBlank()) message = getStringField(parsed, "content");
                if (message == null || message.isBlank()) message = getStringField(parsed, "text");

                // Handle "status report" pattern: {"ok": false, "error": "...", "next_step": "..."}
                // The LLM is producing diagnostic JSON instead of a tool call — still useful content
                if (message == null || message.isBlank()) {
                    String error = getStringField(parsed, "error");
                    String reason = getStringField(parsed, "reason");
                    String nextStep = getStringField(parsed, "next_step");
                    Object errors = parsed.get("errors");
                    StringBuilder statusReport = new StringBuilder();
                    if (error != null && !error.isBlank()) statusReport.append(error);
                    if (reason != null && !reason.isBlank()) statusReport.append(reason);
                    if (errors instanceof List<?> errList && !errList.isEmpty()) {
                        statusReport.append(errList.stream()
                                .map(Object::toString)
                                .collect(java.util.stream.Collectors.joining("; ")));
                    }
                    if (nextStep != null && !nextStep.isBlank()) {
                        statusReport.append("\n Next step: ").append(nextStep);
                    }
                    if (!statusReport.isEmpty()) {
                        message = statusReport.toString();
                    }
                }

                if (message != null && !message.isBlank()) {
                    return new AgentAction(AgentAction.RESPOND, Map.of("message", message),
                            reasoning != null ? reasoning : "Direct response");
                }
                log.warn("ThinkingEngine: no 'tool' field in parsed JSON. Keys present: {}",
                        parsed.keySet());
                log.warn("ThinkingEngine: raw parsed JSON: {}",
                        truncate(cleaned, 500));
                return unusable(raw, "Your previous reply is not an action: its JSON names no "
                        + "tool, so nothing was run.", ACTION_FORMAT);
            }

            return new AgentAction(tool, params, reasoning != null ? reasoning : "");
        } catch (Exception e) {
            log.warn("ThinkingEngine: failed to parse LLM JSON output: {}", e.getMessage());
            return unusable(raw, "Your previous reply could not be read as an action ("
                    + e.getMessage() + "), so nothing was run.", ACTION_FORMAT);
        }
    }

    /**
     * What the words beside a call are for, on both protocols: the owner reads them in the chat
     * as the task goes ({@code TaskChat}), so they say what is happening, in his language. A
     * progress update about the work, not the model's reasoning: asked for "what you are doing
     * now and why", with no length given, claude-opus-5 wrote out its reasoning beside the call,
     * and Anthropic declined the reply part-way as reasoning extraction
     * ({@code stop_details.category} "reasoning_extraction"), which ended the owner's task on
     * 2026-10-01. No length is asked for either way: a sentence count is a cap. A step declined so
     * all the same is asked again with nothing asked for beside the call ({@code AgentLoop},
     * {@link #beside}). Only what is new: on 2026-10-07 every update of a 30-step router task
     * restated the same suspicion, so different checks read as the same step done again.
     */
    static final String NARRATION = "write it for them, in their language, as a progress update on "
            + "the work -- what the last result showed and what you are doing next, only what is "
            + "new since your last update -- not your reasoning.";

    /**
     * What to write beside a call, on either protocol: a line of the per-step block, not of the
     * system prompt, so that in a task the provider has declined as reasoning extraction it can
     * ask for nothing -- and the request no longer asks for words anywhere -- while the system
     * prompt the provider has cached stays the same bytes.
     *
     * @param quiet the provider has declined a step of this task as reasoning extraction
     *              ({@link AgentContext#declinedAsReasoning})
     */
    static String beside(boolean nativeTools, boolean quiet) {
        if (quiet) {
            return nativeTools ? "- Beside your call: write nothing."
                    : "- Beside your call: nothing -- the JSON has only \"tool\" and \"params\".";
        }
        return nativeTools
                ? "- Beside your call: when the user is waiting, the text you write beside a tool call "
                        + "is shown to them live in the chat: " + NARRATION
                : "- Beside your call: write \"reasoning\" first in the JSON. When the user is waiting, "
                        + "'reasoning' is shown to them live in the chat: " + NARRATION;
    }

    /**
     * The text protocol's action, restated to a model whose reply was not one: what makes it an
     * action, without the 'reasoning' the per-step block asks for ({@link #beside}) -- or, in a
     * task the provider declined as reasoning extraction, does not.
     */
    private static final String ACTION_FORMAT = "Reply with one JSON object: {\"tool\": \"name\", "
            + "\"params\": {...}}. To answer: {\"tool\": \"respond\", \"params\": {\"message\": \"...\"}}.";

    private String getStringField(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value != null ? value.toString() : null;
    }

    private String detectPlatform() {
        String os = System.getProperty("os.name", "unknown").toLowerCase();
        if (os.contains("win")) return "Windows";
        if (os.contains("mac")) return "macOS";
        if (os.contains("linux")) return "Linux";
        return os;
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "null";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
