package com.ownclaw.agent;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
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
     * Marker inserted into system prompts to separate the static (cacheable) prefix
     * from the dynamic suffix (datetime, tools, user prefs). AnthropicProvider splits
     * on this marker to create two system content blocks — only the static prefix gets
     * cache_control, so the Anthropic prompt cache actually hits across requests.
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

    // Lenient mapper: tolerates common LLM JSON quirks.
    // - ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER: \' and other non-standard escapes
    // - ALLOW_UNQUOTED_FIELD_NAMES: {tool: "x"} instead of {"tool": "x"}
    // - ALLOW_SINGLE_QUOTES: {'tool': 'x'} instead of {"tool": "x"}
    // - ALLOW_TRAILING_COMMA: {"x": 1,} trailing commas in objects/arrays
    // These features are CRITICAL for the local LLM (qwen2.5:14b) which frequently
    // produces non-standard JSON.
    private static final ObjectMapper mapper = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER)
            .enable(JsonReadFeature.ALLOW_UNQUOTED_FIELD_NAMES)
            .enable(JsonReadFeature.ALLOW_SINGLE_QUOTES)
            .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
            .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
            .build();

    private final ToolRegistry toolRegistry;
    private final OwnClawConfig config;
    private final LlmRouter llmRouter;

    public ThinkingEngine(ToolRegistry toolRegistry, OwnClawConfig config, LlmRouter llmRouter) {
        this.toolRegistry = toolRegistry;
        this.config = config;
        this.llmRouter = llmRouter;
    }

    /**
     * Decide the next action for the agent based on its current context.
     *
     * @param context    the current agent context (includes trajectory, user message, etc.)
     * @param provider   the LLM provider to use for this reasoning step
     * @return the next action to take
     */
    public AgentAction decideNextAction(AgentContext context, LlmProvider provider) {
        return decideNextActionFull(context, provider).action();
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
                !nativeTools,
                null    // use provider default read timeout
        );
        // On whose behalf. Without this the gateway refuses the call -- which is the point:
        // a call site that forgets is stopped, not silently unscanned.
        requestConfig = requestConfig.withEgress(context.egress("think"));
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

        try {
            LlmResponse response = provider.chat(messages, requestConfig);
            log.debug("ThinkingEngine LLM response ({} tokens): {}", response.totalTokens(),
                    truncate(response.content(), 200));

            // A refused reply, or one cut off by a limit of the model, never gets this far: the
            // provider path throws ProviderRefused or OutputTruncated for it
            // (LlmResponse.requireComplete), so everything below reads a whole reply.
            String text = response.content() == null ? "" : response.content();

            // No tool call came back, but tools were offered.
            //
            // On Anthropic that means the model chose to answer in prose, and treating it as a
            // final answer is right. On a local model it does NOT: Ollama reports a "tools"
            // capability per model, and a model that advertises it may still ignore the tools
            // array and emit the old JSON envelope as text. Mapping that straight to RESPOND
            // would deliver the raw JSON to the user as the answer. So parse first, and only
            // treat it as prose when it genuinely is not an action -- which costs one cheap
            // parse attempt and removes a whole class of local-tier regression.
            if (nativeTools && !response.hasToolCalls() && !text.isBlank()) {
                AgentAction parsed = tryParseAction(text);
                if (parsed != null) {
                    log.info("protocol=native-but-text — the model ignored the tools array and "
                            + "emitted a text action; parsed it rather than delivering JSON.");
                    return result(parsed, messages, text, response, provider);
                }
                log.info("protocol=native — answered directly with no tool call, provider={}",
                        provider.name());
                // ...except when the registry is withheld and nothing has run yet. Then a prose
                // reply is a plan ("I'll fetch today's news digest first"), not an answer, and
                // delivering it as the final answer is how this change would quietly break the
                // owner's morning email: task COMPLETED, nothing done.
                AgentAction answer = nothingRanYet(context, mode)
                        ? answeredBeforeWork(text)
                        : new AgentAction(AgentAction.RESPOND, Map.of("message", text),
                                "Answered directly without calling a tool");
                return result(answer, messages, text, response, provider);
            }

            // A native tool call is unambiguous: no parsing, so no parse failure.
            if (nativeTools && response.hasToolCalls()) {
                var call = response.toolCalls().get(0);
                Map<String, Object> args = call.arguments() == null ? Map.of() : call.arguments();
                // The same guard, on the channel the prompt actually teaches. Under native
                // tools the system prompt says "For respond: put the whole answer in the message
                // argument" -- so a model that cannot run daily_news_digest says so by CALLING
                // respond, not by writing prose. That took the branch below, kept the model's
                // text as its reasoning, and AgentLoop returned COMPLETED: a scheduled run
                // recorded green with no email and nothing run. The prose guard covered the
                // less likely half.
                if (AgentAction.RESPOND.equals(call.name()) && nothingRanYet(context, mode)) {
                    log.info("Unattended task {}: refused to finish — nothing has run yet.",
                            context.taskId());
                    return result(answeredBeforeWork(String.valueOf(args.getOrDefault("message", ""))),
                            messages, renderToolCallForDebug(response), response, provider);
                }
                // Logged at INFO because otherwise there is no way to tell from outside which
                // protocol a step used: a correct answer looks identical either way, and the
                // token counts do not distinguish them. Without this the flag cannot be
                // verified in production at all, only assumed.
                log.info("Native tool call: {} ({} args) — protocol=native, provider={}",
                        call.name(), args.size(), provider.name());
                return result(new AgentAction(call.name(), args, text), messages,
                        renderToolCallForDebug(response), response, provider);
            }

            // Nothing came back: no text and no tool call. There is nothing to parse, so it is
            // not reported as a parse failure -- that told a model holding a tools array it had
            // broken the text envelope -- and no sentence is invented to stand for the reply.
            if (text.isBlank()) {
                String stop = response.stopDescription();
                return result(unusable("", "Your previous reply was empty"
                                + (stop == null ? "" : " (stop_reason: " + stop + ")")
                                + ": no text and no tool call, so nothing was run.",
                                "Continue from where the task stands."),
                        messages, text, response, provider);
            }

            return result(parseAction(text), messages, text, response, provider);
        } catch (EgressRefused | ProviderRefused | OutputTruncated notToAskAgain) {
            // Not a step to ask again. The gateway refuses the same prompt again; the provider
            // declined to answer it; the reply or the conversation reached a limit of the model.
            // Asked again as steps that had gone wrong, a refusal and a cut-off came back the
            // same way until the owner was told "3 consecutive reasoning failures". The loop
            // ends the task on each, saying which.
            throw notToAskAgain;
        } catch (LlmException e) {
            log.error("ThinkingEngine LLM call failed: {}", e.getMessage());
            return new ThinkResult(unusable("", "Your previous reply never came: the call to the "
                            + "model failed (" + e.getMessage() + "), so nothing was run.",
                    "Continue from where the task stands."), messages, "ERROR: " + e.getMessage(), 0);
        }
    }

    /** A step's result, with the tokens the reply was billed and the model that wrote it. */
    private static ThinkResult result(AgentAction action, List<LlmMessage> messages, String raw,
                                      LlmResponse response, LlmProvider provider) {
        // The model that wrote the reply, when the provider says: a declined request can be
        // answered by Anthropic's fallback model, and it is priced at that model's rates.
        return new ThinkResult(action, messages, raw, response.totalTokens(),
                response.promptTokens(), response.completionTokens(),
                response.cacheCreationTokens(), response.cacheReadTokens(),
                response.model() != null ? response.model() : provider.model());
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

    /** Package-private: the whole prompt, so a test can assert what the model is actually told. */
    List<LlmMessage> buildMessages(AgentContext context, String providerName,
                                   StepMode mode) {
        List<LlmMessage> messages = new ArrayList<>();
        messages.add(LlmMessage.system(buildSystemPrompt(context, providerName, mode)));

        if ("anthropic".equals(providerName)) {
            // Anthropic: multi-turn trajectory for prefix caching.
            // System prompt is static-only; dynamic context (datetime, tools) goes
            // in conversation messages so the system prompt never changes.
            buildAnthropicMessages(messages, context, mode);
        } else {
            // OpenAI / other: single trajectory message, dynamic content in system prompt
            messages.add(LlmMessage.user(buildUserMessage(context)));
            AgentTrajectory trajectory = context.trajectory();
            if (!trajectory.isEmpty()) {
                messages.add(LlmMessage.user(buildTrajectoryMessage(trajectory)));
            }
        }

        return messages;
    }

    /**
     * The task, then every step in the order it happened: the model's action as an assistant
     * turn, its result as the user turn after it -- each whole, PRIVATE results as the
     * descriptions they were recorded as.
     * <p>
     * A step the loop took itself ({@link AgentTrajectory.Turn#byTheLoop}) has no assistant turn:
     * the model did not write it. What the model was told about it -- a reply that could not be
     * used, a reflection -- joins the user turn it follows, in its place. So an assistant turn
     * holds nothing but what the model wrote, and the roles alternate as the Messages API
     * requires.
     * <p>
     * Every message but the last is the same bytes on every later step, so the conversation is
     * append-only and the provider's sliding cache breakpoints keep hitting. The last user turn
     * carries what changes on every step (the date, the tools on the text protocol, the vault),
     * behind the cache breakpoints. While no action has been replayed the task is the only
     * message, and the cache boundary marks where the task ends, so the task is cached on the
     * first call and read back on the next.
     */
    void buildAnthropicMessages(List<LlmMessage> messages, AgentContext context, StepMode mode) {
        String task = buildUserMessage(context);
        var user = new StringBuilder(task);
        boolean replayed = false;
        for (var turn : context.trajectory().turns()) {
            if (turn.byTheLoop()) {
                String told = turn.observation().output();
                if (told != null && !told.isBlank()) user.append("\n\n").append(told);
                continue;
            }
            messages.add(LlmMessage.user(user.toString()));
            messages.add(LlmMessage.assistant(formatActionForMultiTurn(turn.action())));
            user = new StringBuilder(formatObservationForMultiTurn(turn));
            replayed = true;
        }
        if (!replayed) user.insert(task.length(), CACHE_BOUNDARY_MARKER);
        user.append("\n\n---\n").append(buildDynamicContext(context, mode));
        messages.add(LlmMessage.user(user.toString()));
    }

    /** Every skill by name with its whole description: what exists, without the ability to call it. */
    private String skillCatalogue() {
        return toolRegistry.all().stream()
                .filter(t -> t != null && t.name() != null)
                .sorted((a, b) -> a.name().compareToIgnoreCase(b.name()))
                .map(t -> "- " + t.name() + ": " + t.description())
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    /**
     * What this step offers the model. Decided once and handed to every builder, because the
     * tools array and the prompt disagreeing is worse than either choice alone: the model is
     * told in prose that it owns a skill while the API says it does not, and which half wins
     * decides the run.
     */
    record StepMode(boolean nativeTools, boolean localFirst) {}

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

        // Gated on nativeTools, because withholding tools from an array nobody is reading
        // restricts nothing -- on the text protocol the manifest is the channel.
        boolean localFirst = nativeTools
                && config.getMentor().isLocalFirstUnattended()
                && context.isUnattended()
                && localTierReady(context)
                // The valve. If a delegation has already failed, the local tier has had its
                // turn and the registry comes back for the rest of the task. Without this, a
                // local model that cannot manage the work leaves the orchestrator re-delegating
                // into the step limit and the owner's morning email simply never arrives --
                // trading a token saving for a silently broken task.
                && !delegationFailed(context);

        return new StepMode(nativeTools, localFirst);
    }

    /**
     * The tools the CLOUD model may call on this step.
     *
     * <p>On unattended work the registry is withheld, so the cloud can orchestrate but cannot
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
     * <p>Attended chat is untouched. There the user IS waiting, a local step costs about a
     * minute, and the owner has been explicit that latency matters there and does not matter for
     * scheduled work.
     */
    List<com.ownclaw.llm.ToolSpec> toolsFor(AgentContext context, StepMode mode) {
        if (!mode.localFirst()) {
            context.setOfferedTools(null);
            return ToolSchemas.build(SpecialActionSchemas.ALL, toolRegistry.all(),
                    context.credentialKeys());
        }
        log.info("Unattended task {}: offering the cloud orchestration only — the registry is "
                        + "withheld, so mechanical work must be delegated to the local model.",
                context.taskId());
        context.setOfferedTools(SpecialActionSchemas.ALL.stream()
                .map(com.ownclaw.llm.ToolSpec::name)
                .collect(java.util.stream.Collectors.toUnmodifiableSet()));

        // The cloud cannot CALL the skills, but it still has to know they exist, or it will
        // write a goal that asks for something already built -- or reach for skill_create to
        // rebuild it. So delegate's description carries a catalogue: every skill's name and
        // description, which is knowledge without capability.
        var specs = new ArrayList<>(ToolSchemas.build(
                SpecialActionSchemas.ALL, List.of(), context.credentialKeys()));
        String catalogue = skillCatalogue();
        specs.replaceAll(spec -> {
            if (!AgentAction.DELEGATE.equals(spec.name())) return spec;
            return new com.ownclaw.llm.ToolSpec(spec.name(),
                    spec.description()
                            + "\n\nYou are orchestrating unattended work, so you cannot run "
                            + "skills yourself — this is how the work gets done. State the goal "
                            + "fully and name the skills it needs in 'tools'. Skills available "
                            + "to it:\n"
                            + (catalogue.isBlank() ? "(none yet — use skill_create first)" : catalogue),
                    spec.inputSchema());
        });
        return specs;
    }

    /**
     * Restricted unattended work where nothing has actually succeeded yet.
     * <p>
     * In that state an answer is a plan, not an answer — "I'll fetch today's news digest first"
     * — and delivering it ends the task COMPLETED having done nothing. Shared by both channels
     * the model can finish through, because covering one and not the other is what let this
     * through the first time.
     */
    private static boolean nothingRanYet(AgentContext context, StepMode mode) {
        return mode.localFirst() && context.trajectory().turns().stream()
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
            return mapper.writeValueAsString(out);
        } catch (Exception e) {
            return String.valueOf(response.content());
        }
    }

    /**
     * Build dynamic context string (datetime, tools, user preferences, vault).
     * For Anthropic, this goes in conversation messages instead of the system prompt
     * to keep the system prompt 100% static for caching.
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
        // The agent was asked to weigh latency against cost -- delegate to the free local model
        // when nobody is waiting, do it yourself when someone is -- and given no way to tell the
        // two apart, so it had to guess. It is not a guess: the origin of the task settles it.
        // The scheduler and /bg submit at background priority, a chat message does not, and
        // TaskQueue has already recorded which this is.
        //
        // Stating it plainly is what makes the trade-off actionable, and it is the whole reason
        // the local tier can carry real work without anyone noticing the latency.
        if (context.isUnattended()) {
            sb.append("- Attendance: NOBODY IS WAITING. This was started by the scheduler or sent "
                    + "to the background; the answer is delivered to the chat whenever it is "
                    + "ready. Minutes are free here. Prefer 'delegate' for anything the local "
                    + "model can do, especially work on this machine, the LAN or private data, "
                    + "and never stop to ask a question -- decide, and say which assumption you "
                    + "made.\n\n");
        } else {
            sb.append("- Attendance: THE USER IS WAITING in the chat right now. Favour the "
                    + "shortest path to a correct answer; a local delegation costs about a "
                    + "minute per step, so use it only when it genuinely saves more than it "
                    + "costs.\n\n");
        }

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
        if (mode.localFirst()) {
            // ...and only when the array is NOT being sent. With native tools, delegate's own
            // description already carries this catalogue (see the specs builder above), and the
            // paragraph the comment above makes about cost is the smaller half of it: rendering
            // the same text into a tool part AND into the newest user message forced the canary
            // to choose between refusing a copy the cloud is receiving anyway and excusing text
            // across parts. Excusing across parts turned out to be a leak — a short private
            // artifact quoted anywhere went out as soon as it appeared in some skill's example.
            // One copy, and the question does not arise.
            if (!mode.nativeTools()) {
                // Knowledge without capability. The cloud still needs to know a skill exists --
                // otherwise it reaches for skill_create to rebuild one it already owns -- but it
                // is not told it can call it, which is what made the prompt argue with the array.
                sb.append("## Skills on this machine\n");
                String catalogue = skillCatalogue();
                sb.append(catalogue.isBlank() ? "(none yet — use skill_create)\n" : catalogue + "\n");
                sb.append("You cannot call these yourself on this task. 'delegate' reaches all of "
                        + "them: state the goal in full and name the ones it needs in 'tools'.\n");
            }
        } else if (!mode.nativeTools()) {
            sb.append(toolsSection(context));
        } else if (toolRegistry.all().isEmpty()) {
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
     * An action as the assistant turn that replays it: what the model chose -- its reasoning, the
     * tool and the arguments as it wrote them, a reference as {{N}} and never the bytes it
     * resolves to -- as JSON.
     */
    private String formatActionForMultiTurn(AgentAction action) {
        try {
            Map<String, Object> map = new LinkedHashMap<>();
            String reasoning = action.reasoning();
            if (reasoning != null && !reasoning.isBlank()) {
                map.put("reasoning", reasoning);
            }
            map.put("tool", action.tool());
            if (action.params() != null && !action.params().isEmpty()) {
                map.put("params", action.params());
            }
            return mapper.writeValueAsString(map);
        } catch (Exception e) {
            return "{\"tool\": \"" + action.tool() + "\"}";
        }
    }

    /** A step's result as the user turn after it: which tool, how it went, and its output whole. */
    private String formatObservationForMultiTurn(AgentTrajectory.Turn turn) {
        var sb = new StringBuilder();
        sb.append("[").append(turn.action().tool()).append("] ");
        sb.append(turn.observation().success() ? "OK" : "FAILED");
        sb.append(" (").append(turn.observation().durationMs()).append("ms)\n");
        String output = turn.observation().output();
        if (output != null && !output.isBlank()) sb.append(output);
        return sb.toString();
    }

    /**
     * Build the system prompt. This defines the agent's behavior, available tools,
     * and output format. Completely generic — no domain-specific content. Sent whole on every
     * step, never shortened.
     *
     * @param mode when {@code nativeTools} is set, the action list and the JSON-envelope
     *             instruction are omitted. The tools array carries both, and this claim used to
     *             be false: the parameter was accepted and never read, so every native step also
     *             carried "Single JSON: {reasoning, tool, params}" -- an instruction to use the
     *             one protocol the tools array exists to replace, which is the mechanism by
     *             which a model talks its way back onto the text path.
     */
    private String buildSystemPrompt(AgentContext context, String providerName,
                                     StepMode mode) {
        boolean nativeTools = mode.nativeTools();
        var sb = new StringBuilder();

        sb.append("You are an autonomous agent. Reason, pick a tool, observe, repeat until done.\n\n");

        // ═══════════════════════════════════════════════════════════════════
        // STATIC SECTION — identical across all requests/tasks/steps.
        // AnthropicProvider caches everything up to CACHE_BOUNDARY_MARKER.
        // ═══════════════════════════════════════════════════════════════════

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
        sb.append("  Best for: work on the local machine, LAN, servers and files, and long\n");
        sb.append("  mechanical sequences. Nothing leaves the host, so prefer it for private data.\n");
        sb.append("  Trade-off: roughly a minute per step, so prefer it when nobody is waiting;\n");
        sb.append("  do it yourself when the user is sitting in the chat expecting an answer.\n");
        sb.append("  goal* — what to achieve, stated fully; the local model works out the steps.\n");
        sb.append("  steps (optional): [{description, tool, params}] only when the order matters\n");
        sb.append("    and you already know it. Omit it rather than guess at params.\n");
        sb.append("  tools: comma-separated exact names of the tools it will need. Only these,\n");
        sb.append("    and any the goal or an unattended (scheduled or /bg) task names, are loaded -- its context is small.\n");
        sb.append("  checkpoints | max_steps (default 10)\n\n");
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
        sb.append("Past tasks are not shown to you: memory_manage action=recall with a query returns every one that matches, in full.\n\n");

        // Output format
        sb.append("## Output\n");
        if (nativeTools) {
            sb.append("Call exactly one tool per step. Any text alongside it is reasoning, not the answer.\n");
            sb.append("For respond: put the whole answer in the message argument.\n\n");
        } else {
            sb.append("Single JSON: {\"reasoning\": \"...\", \"tool\": \"name\", \"params\": {...}}\n");
            sb.append("For respond: put ALL content in params.message, NOT in reasoning.\n\n");
        }

        // Behavioral guidelines + cost + self-improvement combined
        sb.append("## Rules\n");
        sb.append("- No tools needed → respond directly. Never fabricate outputs.\n");
        sb.append("- On failure: diagnose WHY, then try fundamentally different approach. Never repeat failed actions.\n");
        sb.append("- Skill errors: fix via skill_create (SAME name). Never _v2/_fixed.\n");
        sb.append("- Local/LAN/server work, or a long mechanical sequence nobody is waiting on → delegate it (free, stays on the host).\n");
        sb.append("- No suitable tool → create one. Poor results → read skill code, overwrite fix.\n");
        sb.append("- Explore thoroughly before 'not found'. Search the internet if stuck.\n");
        sb.append("- Respond in user's language. Search/selectors in content's language.\n");
        sb.append("- Outputs: clean text. Extract file content (PDF/DOCX/CSV), don't just report links.\n");
        sb.append("- Garbled text → encoding bug, fix the tool.\n");
        sb.append("- Partial USEFUL result beats empty failure. Each step must make new progress.\n");
        sb.append("- NEVER write multi-line code via shell_exec/python3 -c. Use skill_create.\n");
        sb.append("- Need an OS binary or system library? Add it to system_packages in skill_create. NEVER say a package is unavailable.\n");

        // Anthropic: return static-only system prompt. Dynamic content (datetime,
        // tools, user prefs) goes in conversation messages via buildAnthropicMessages()
        // to keep the system prompt identical across all steps — enabling both
        // system-level AND conversation-prefix caching.
        if ("anthropic".equals(providerName)) {
            return sb.toString();
        }

        // ═══════════════════════════════════════════════════════════════════
        // DYNAMIC SECTION — changes per request/task/step.
        // Everything below this marker is NOT cached by Anthropic.
        // ═══════════════════════════════════════════════════════════════════
        sb.append(CACHE_BOUNDARY_MARKER);

        // Environment context (dynamic — changes every request)
        sb.append("## Environment\n");
        sb.append("- Platform: ").append(detectPlatform()).append("\n");
        // Truncate to minute precision — seconds change between agent steps (which
        // happen seconds apart) and would invalidate the Anthropic conversation history
        // cache. Minute precision is stable enough for the LLM while maximizing cache hits.
        sb.append("- DateTime: ").append(LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MINUTES)
                .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)).append("\n\n");

        // User preferences (if any)
        if (context.userPreferences() != null && !context.userPreferences().isBlank()) {
            sb.append("## User Preferences\n");
            sb.append(context.userPreferences()).append("\n\n");
        }

        // The manifest only on the text protocol -- the same rule the Anthropic path follows in
        // buildDynamicContext, which this branch never got. With native tools the array already
        // carries every tool (and, local-first, delegate's description carries the catalogue),
        // so this was the catalogue a second time; and local-first it advertised as callable the
        // very skills the array withholds. The duplicate is what deadlocked a run on this
        // provider -- the default in application.yaml -- when a skill's own short output was
        // recorded PRIVATE and the canary found it here, in a part with no registry allowance.
        if (!mode.nativeTools()) {
            sb.append(toolsSection(context)).append("\n");
        }

        // Dynamic vault contents
        List<String> vaultKeys = context.credentialKeys();
        if (!vaultKeys.isEmpty()) {
            sb.append("Vault contains: ").append(String.join(", ", vaultKeys)).append("\n\n");
        }

        // Delegation nudge — injected by AgentLoop when repetitive tool calls are detected
        Object nudge = context.metadata().get("delegationNudge");
        if (nudge instanceof String nudgeMsg && !nudgeMsg.isBlank()) {
            sb.append("COST WARNING: ").append(nudgeMsg).append("\n\n");
        }

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
          .append("private results. To answer from a file, delegate and name the skill that reads it ")
          .append("(if none does, skill_create one that reads params._attached_files). The ")
          .append("delegation's answer comes back as a handle; make that handle the whole of ")
          .append("respond's message and its text is filled in on this machine for the user. ")
          .append("A file is handed only to this task: asking the user a question ends it, and ")
          .append("the reply is a new task without the file -- read it first.\n");
        return sb.toString();
    }

    /**
     * Build a message summarizing the trajectory of past actions in this execution.
     */
    private String buildTrajectoryMessage(AgentTrajectory trajectory) {
        var sb = new StringBuilder();
        sb.append("## History\n");
        sb.append(trajectory.toPromptSummary());
        sb.append("Next action? If done, use 'respond'.");
        return sb.toString();
    }

    // Regex that matches multi-line block comments in JSON
    private static final java.util.regex.Pattern BLOCK_COMMENT =
            java.util.regex.Pattern.compile("/\\*.*?\\*/", java.util.regex.Pattern.DOTALL);

    /**
     * Strip JavaScript-style comments from an LLM-produced JSON string.
     * LLMs sometimes add // annotations in JSON which Jackson rejects.
     */
    private String stripJsonComments(String json) {
        if (json == null) return json;
        // Remove block comments first, then line comments
        json = BLOCK_COMMENT.matcher(json).replaceAll("");
        // Only strip // comments that are NOT inside a quoted string.
        // Simple heuristic: split by lines and strip trailing // that aren't inside quotes.
        var sb = new StringBuilder();
        for (String line : json.split("\n", -1)) {
            sb.append(stripLineComment(line)).append('\n');
        }
        return sb.toString();
    }

    /** Remove trailing // comment from a single line, being careful not to strip inside string values. */
    private String stripLineComment(String line) {
        boolean inString = false;
        char prev = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"' && prev != '\\') {
                inString = !inString;
            } else if (!inString && c == '/' && prev == '/') {
                return line.substring(0, i - 1);
            }
            prev = c;
        }
        return line;
    }

    /**
     * Parse text as an action, or return null if it plainly is not one.
     * <p>
     * {@link #parseAction} reads the text protocol, where a reply that is not an action is a step
     * that produced nothing to run. When tools were offered that is wrong: there, prose means the
     * model chose to answer, but a JSON envelope means a local model ignored the tools array, and
     * handing that envelope to the user as their answer would be worse than either. This
     * distinguishes the two.
     */
    AgentAction tryParseAction(String raw) {
        if (raw == null || raw.isBlank()) return null;
        Map<String, Object> parsed = tryParseJsonObject(LlmOutputUtils.stripCodeFences(raw.strip()));
        if (parsed == null) return null;
        Object tool = parsed.get("tool");
        if (tool == null || String.valueOf(tool).isBlank()) return null;
        return parseAction(raw);
    }

    /**
     * Read a text-protocol reply -- never blank: an empty reply is recognised before this -- as
     * an action. Handles common LLM output quirks (code fences, comments, extra text, etc.). A
     * reply that is not an action is a {@link #THINKING} step that quotes it and restates the
     * format.
     */
    AgentAction parseAction(String raw) {
        String cleaned = LlmOutputUtils.stripCodeFences(raw.strip());

        // Try to parse as JSON. Use Jackson's streaming parser to find the first
        // valid JSON object — handles nested braces, escaped chars, etc. correctly.
        Map<String, Object> parsed = tryParseJsonObject(cleaned);
        if (parsed == null) {
            log.warn("ThinkingEngine: no valid JSON object in LLM response, so no action");
            return unusable(raw, "Your previous reply is not an action: there is no JSON object "
                    + "in it, so nothing was run.", ACTION_FORMAT);
        }

        try {

            // Try multiple field names that LLMs commonly use for tool selection
            String tool = getStringField(parsed, "tool");
            if (tool == null || tool.isBlank()) tool = getStringField(parsed, "action");
            if (tool == null || tool.isBlank()) tool = getStringField(parsed, "name");
            if (tool == null || tool.isBlank()) tool = getStringField(parsed, "function");
            if (tool == null || tool.isBlank()) tool = getStringField(parsed, "command");

            // Try nested structures: {"action": {"tool": "..."}}
            if ((tool == null || tool.isBlank()) && parsed.get("action") instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> actionMap = (Map<String, Object>) parsed.get("action");
                tool = getStringField(actionMap, "tool");
                if (tool == null || tool.isBlank()) tool = getStringField(actionMap, "name");
            }

            String reasoning = getStringField(parsed, "reasoning");
            if (reasoning == null || reasoning.isBlank()) reasoning = getStringField(parsed, "thought");
            if (reasoning == null || reasoning.isBlank()) reasoning = getStringField(parsed, "thoughts");
            if (reasoning == null || reasoning.isBlank()) reasoning = getStringField(parsed, "thinking");

            @SuppressWarnings("unchecked")
            Map<String, Object> params = parsed.containsKey("params") && parsed.get("params") instanceof Map
                    ? (Map<String, Object>) parsed.get("params")
                    : parsed.containsKey("parameters") && parsed.get("parameters") instanceof Map
                        ? (Map<String, Object>) parsed.get("parameters")
                        : parsed.containsKey("arguments") && parsed.get("arguments") instanceof Map
                            ? (Map<String, Object>) parsed.get("arguments")
                            : Map.of();

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

    /** The text protocol's action, restated to a model whose reply was not one. */
    private static final String ACTION_FORMAT = "Reply with one JSON object: {\"reasoning\": "
            + "\"...\", \"tool\": \"name\", \"params\": {...}}. To answer: {\"tool\": \"respond\", "
            + "\"params\": {\"message\": \"...\"}, \"reasoning\": \"...\"}.";

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

    /**
     * Try to parse the first valid JSON object from a string that may contain
     * surrounding natural language text. Uses Jackson's streaming parser to
     * correctly handle nested braces, escaped characters, and strings containing
     * braces — avoiding false matches on things like {CURRENT_YEAR}.
     *
     * Strategy:
     *   1. Try parsing the whole string as JSON (common case — LLM followed instructions).
     *   2. Try each '{' position as a potential JSON start; use Jackson's streaming
     *      parser which reads exactly one value and stops (tolerates trailing text).
     *   3. If nothing parses, return null (not a failure — LLM wrote prose).
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> tryParseJsonObject(String text) {
        if (text == null || text.isBlank()) return null;

        // Strip JS-style comments before any parsing attempt
        String stripped = stripJsonComments(text);

        // Fast path: entire string is valid JSON
        try {
            Object result = mapper.readValue(stripped, Object.class);
            if (result instanceof Map) return (Map<String, Object>) result;
        } catch (Exception ignored) {}

        // Scan for '{' and try parsing from each candidate position.
        // Jackson's streaming parser reads exactly one JSON value and stops,
        // so trailing text (natural language after the JSON) is not a problem.
        var factory = mapper.getFactory();
        int searchFrom = 0;
        while (searchFrom < stripped.length()) {
            int bracePos = stripped.indexOf('{', searchFrom);
            if (bracePos < 0) break;

            try (var parser = factory.createParser(stripped.substring(bracePos))) {
                Object result = mapper.readValue(parser, Object.class);
                if (result instanceof Map) return (Map<String, Object>) result;
            } catch (Exception ignored) {}

            searchFrom = bracePos + 1;
        }

        return null;
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "null";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
