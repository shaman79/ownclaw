package com.ownclaw.agent;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.ownclaw.agent.tools.Tool;
import com.ownclaw.agent.tools.ToolParam;
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
    static final String CACHE_BOUNDARY_MARKER = "\n<!-- CACHE_BOUNDARY -->\n";

    /**
     * Ceiling on a single tool output sent to the cloud at full detail.
     *
     * Generous — about 3k tokens — because the recent turns are what the model reasons over and
     * clipping them too hard makes it ask for the same thing again, which costs more than it
     * saves. The point is only that there IS a ceiling.
     *
     * A local summary would be strictly better than head-and-tail here: it preserves meaning
     * rather than discarding the middle, and local tokens are free. It costs 60-133 seconds on
     * this hardware, which is unacceptable while a user is waiting — but work can now be
     * classified, and {@link AgentLoop#compressIfUnattended} uses LocalExecutor.summarizeIfLong
     * on exactly the runs where those seconds are free. This constant remains the ceiling for
     * the attended case, where there is no time to do better.
     */
    private static final int FULL_DETAIL_MAX_CHARS = 12_000;

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
    private final ToolSelector toolSelector;
    private final OwnClawConfig config;
    private final LlmRouter llmRouter;

    public ThinkingEngine(ToolRegistry toolRegistry, ToolSelector toolSelector, OwnClawConfig config, LlmRouter llmRouter) {
        this.toolRegistry = toolRegistry;
        this.toolSelector = toolSelector;
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
                8192,   // enough for structured action with long response messages
                // JSON mode is for the TEXT protocol. With native tools it is actively harmful:
                // it pushes the model to put JSON in the text body instead of emitting a
                // tool_use block, which is the one thing this change exists to stop.
                !nativeTools,
                null    // use provider default read timeout
        );
        if (nativeTools) {
            requestConfig = requestConfig.withTools(toolsFor(context, mode));
        }

        try {
            LlmResponse response = provider.chat(messages, requestConfig);
            log.debug("ThinkingEngine LLM response ({} tokens): {}", response.totalTokens(),
                    truncate(response.content(), 200));

            // A reply cut off by the output cap is truncated mid-JSON, so it fails to parse and
            // looks exactly like a malformed one. Both used to be retried with a byte-identical
            // prompt, which produced an identically truncated reply, until the run gave up and
            // threw away prose the model really had written. Say which it is, so the retry
            // carries information instead of repeating itself.
            // No tool call came back, but tools were offered.
            //
            // On Anthropic that means the model chose to answer in prose, and treating it as a
            // final answer is right. On a local model it does NOT: Ollama reports a "tools"
            // capability per model, and a model that advertises it may still ignore the tools
            // array and emit the old JSON envelope as text. Mapping that straight to RESPOND
            // would deliver the raw JSON to the user as the answer. So parse first, and only
            // treat it as prose when it genuinely is not an action -- which costs one cheap
            // parse attempt and removes a whole class of local-tier regression.
            if (nativeTools && !response.hasToolCalls()
                    && response.content() != null && !response.content().isBlank()
                    && !response.truncated()) {
                AgentAction parsed = tryParseAction(response.content());
                if (parsed != null) {
                    log.info("protocol=native-but-text — the model ignored the tools array and "
                            + "emitted a text action; parsed it rather than delivering JSON.");
                    log.debug("Native tools were offered but the model replied with a text "
                            + "action; parsed it rather than delivering the JSON as an answer.");
                    return new ThinkResult(parsed, messages, response.content(),
                            response.totalTokens(), response.promptTokens(),
                            response.completionTokens(), response.cacheCreationTokens(),
                            response.cacheReadTokens(), provider.model());
                }
                log.info("protocol=native — answered directly with no tool call, provider={}",
                        provider.name());
                AgentAction answer = new AgentAction(AgentAction.RESPOND,
                        Map.of("message", response.content()),
                        // Deliberately NOT one of the strings AgentLoop treats as a fallback:
                        // choosing to answer is not a reasoning failure.
                        "Answered directly without calling a tool");
                return new ThinkResult(answer, messages, response.content(),
                        response.totalTokens(), response.promptTokens(),
                        response.completionTokens(), response.cacheCreationTokens(),
                        response.cacheReadTokens(), provider.model());
            }

            // A native tool call is unambiguous: no parsing, so no parse failure.
            if (nativeTools && response.hasToolCalls()) {
                var call = response.toolCalls().get(0);
                // Logged at INFO because otherwise there is no way to tell from outside which
                // protocol a step used: a correct answer looks identical either way, and the
                // token counts do not distinguish them. Without this the flag cannot be
                // verified in production at all, only assumed.
                log.info("Native tool call: {} ({} args) — protocol=native, provider={}",
                        call.name(),
                        call.arguments() == null ? 0 : call.arguments().size(),
                        provider.name());
                AgentAction action = new AgentAction(call.name(),
                        call.arguments() == null ? Map.of() : call.arguments(),
                        response.content() == null ? "" : response.content());
                return new ThinkResult(action, messages,
                        renderToolCallForDebug(response), response.totalTokens(),
                        response.promptTokens(), response.completionTokens(),
                        response.cacheCreationTokens(), response.cacheReadTokens(),
                        provider.model());
            }

            if (response.truncated()) {
                log.warn("Model hit its output cap ({} completion tokens) and was cut off "
                        + "mid-answer. The action JSON is incomplete by construction.",
                        response.completionTokens());
                AgentAction cut = new AgentAction(AgentAction.RESPOND,
                        Map.of("message", "My answer ran past the length limit and was cut off. "
                                + "Here is what I had written:\n\n"
                                + salvagePartialMessage(response.content())),
                        "Output truncated at the max_tokens limit");
                return new ThinkResult(cut, messages, response.content(), response.totalTokens(),
                        response.promptTokens(), response.completionTokens(),
                        response.cacheCreationTokens(), response.cacheReadTokens(),
                        provider.model());
            }

            AgentAction action = parseAction(response.content());
            return new ThinkResult(action, messages, response.content(), response.totalTokens(),
                    response.promptTokens(), response.completionTokens(),
                    response.cacheCreationTokens(), response.cacheReadTokens(),
                    provider.model());
        } catch (LlmException e) {
            log.error("ThinkingEngine LLM call failed: {}", e.getMessage());
            AgentAction action = new AgentAction(AgentAction.RESPOND,
                    Map.of("message", "I encountered an error while reasoning about this task. Please try again."),
                    "LLM call failed: " + e.getMessage());
            return new ThinkResult(action, messages, "ERROR: " + e.getMessage(), 0);
        }
    }

    /**
     * Recover whatever prose survived a truncated action JSON.
     * <p>
     * The reply is cut off mid-structure, so it cannot be parsed — but the "message" field is
     * usually the longest thing in it and usually the part that got cut, which means most of
     * what the user actually wanted is sitting there. Returning it is strictly better than
     * discarding the whole reply and reporting a generic failure, which is what happened before.
     * <p>
     * Deliberately string surgery rather than a lenient parser: the input is known-invalid JSON,
     * and the goal is to salvage text for a human to read, not to reconstruct a valid action.
     */
    private String salvagePartialMessage(String raw) {
        if (raw == null || raw.isBlank()) return "(nothing was recovered)";
        int idx = raw.indexOf("\"message\"");
        if (idx < 0) return truncate(raw.strip(), 4000);
        int colon = raw.indexOf(':', idx);
        if (colon < 0) return truncate(raw.strip(), 4000);
        int quote = raw.indexOf('"', colon + 1);
        if (quote < 0) return truncate(raw.strip(), 4000);
        String tail = raw.substring(quote + 1);
        // Stop at the closing quote if the field happens to be complete; otherwise take the lot.
        int end = -1;
        for (int i = 0; i < tail.length(); i++) {
            if (tail.charAt(i) == '"' && (i == 0 || tail.charAt(i - 1) != '\\')) { end = i; break; }
        }
        String body = end >= 0 ? tail.substring(0, end) : tail;
        return truncate(body.replace("\\n", "\n").replace("\\\"", "\"").strip(), 4000);
    }

    /**
     * Build the full message list for the LLM.
     */
    private List<LlmMessage> buildMessages(AgentContext context, String providerName) {
        return buildMessages(context, providerName, new StepMode(false, false));
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
     * Build Anthropic-optimized message list with multi-turn trajectory.
     * <p>
     * Instead of a single trajectory summary message, each action/observation pair
     * becomes an alternating assistant/user turn. This enables Anthropic's prefix
     * caching: the stable conversation prefix (older turns) is cached at 10% cost,
     * and only the latest turn + dynamic context pay full price.
     * <p>
     * Dynamic content (datetime, tools, user prefs) is appended to the last user
     * message, keeping the system prompt 100% static for reliable caching.
     */
    /** Package-private so the parse-failure replay can be tested without a Spring context. */
    void buildAnthropicMessages(List<LlmMessage> messages, AgentContext context) {
        buildAnthropicMessages(messages, context, new StepMode(false, false));
    }

    void buildAnthropicMessages(List<LlmMessage> messages, AgentContext context, StepMode mode) {
        messages.add(LlmMessage.user(buildUserMessage(context)));

        AgentTrajectory trajectory = context.trajectory();

        // Separate the parse-failure turns from the real ones.
        //
        // These used to be dropped outright, as "noise without useful info". They are the
        // opposite: they carry the only correction the model ever gets. When it answers in prose
        // instead of the action JSON, AgentLoop records the raw output plus the required format
        // as a _thinking failure and retries — and on Anthropic, which is what production runs,
        // that feedback reached the model nowhere else. The system prompt is static by design,
        // the user message holds only the task, and the dynamic block never reads the
        // trajectory. So every retry sent a byte-identical prompt, drew the identical reply, and
        // the run aborted at three with "3 consecutive reasoning failures" — for a question the
        // model had answered correctly three times. Four of those are in this deployment's chat
        // history. The OpenAI path was unaffected because toPromptSummary keeps the last two
        // turns in full.
        List<AgentTrajectory.Turn> effectiveTurns = new ArrayList<>();
        AgentTrajectory.Turn lastParseFailure = null;
        int olderParseFailures = 0;
        for (var turn : trajectory.turns()) {
            if (!turn.observation().success() && "_thinking".equals(turn.observation().tool())) {
                if (lastParseFailure != null) olderParseFailures++;
                lastParseFailure = turn;
                continue;
            }
            effectiveTurns.add(turn);
        }

        if (effectiveTurns.isEmpty() && lastParseFailure == null) {
            // Genuine step 0: append dynamic context to the user message.
            LlmMessage lastMsg = messages.get(messages.size() - 1);
            messages.set(messages.size() - 1, LlmMessage.user(
                    lastMsg.content() + "\n\n---\n" + buildDynamicContext(context, mode)));
            return;
        }

        // Multi-turn: each action/observation becomes assistant/user message pair.
        // Last 2 turns get full output detail; older turns are compressed.
        // Same budget policy as the OpenAI path, rather than a hardcoded "last two turns".
        // Keeping a fixed count made "read three pages and compare them" impossible: the first
        // page was a 300-character stub by the time the third arrived.
        int fullDetailFrom = AgentTrajectory.firstTurnKeptInFull(
                effectiveTurns, AgentTrajectory.FULL_OUTPUT_BUDGET_CHARS);
        for (int i = 0; i < effectiveTurns.size(); i++) {
            var turn = effectiveTurns.get(i);
            boolean isFull = i >= fullDetailFrom;
            // Not necessarily the last message any more — a parse failure may follow.
            boolean isLast = i == effectiveTurns.size() - 1 && lastParseFailure == null;

            // Assistant turn: reconstructed action JSON (what the LLM "said")
            messages.add(LlmMessage.assistant(formatActionForMultiTurn(turn.action(), isFull)));

            // User turn: observation result
            String obsText = formatObservationForMultiTurn(turn, isFull);

            // Append dynamic context to the LAST observation only —
            // this keeps it out of the cached prefix while providing current info.
            if (isLast) {
                obsText += "\n\n---\n" + buildDynamicContext(context, mode);
            }
            messages.add(LlmMessage.user(obsText));
        }

        if (lastParseFailure != null) {
            appendParseFailure(messages, context, lastParseFailure, olderParseFailures, mode);
        }
    }

    /**
     * Replay the most recent parse failure as the exchange it actually was.
     * <p>
     * Deliberately NOT routed through {@link #formatActionForMultiTurn}. That would serialise the
     * fabricated fallback action the parser invented — a perfectly well-formed
     * {@code {"tool":"respond","params":{"message":"<the prose>"}}} — and present it to the model
     * as its own previous output, immediately followed by a user turn complaining that the output
     * could not be parsed. Showing a model a valid action and calling it invalid is worse than
     * showing it nothing: it teaches exactly the habit being corrected.
     * <p>
     * So the assistant turn is the raw text the model really produced, and the user turn is the
     * correction verbatim. Two messages, because the Messages API expects the roles to alternate.
     */
    private void appendParseFailure(List<LlmMessage> messages, AgentContext context,
                                    AgentTrajectory.Turn failure, int olderFailures,
                                    StepMode mode) {
        String raw = null;
        var params = failure.action() == null ? null : failure.action().params();
        if (params != null && params.get("message") != null) {
            raw = String.valueOf(params.get("message"));
        }
        messages.add(LlmMessage.assistant(
                raw == null || raw.isBlank() ? "(no parseable action was produced)" : raw));

        StringBuilder correction = new StringBuilder();
        if (olderFailures > 0) {
            correction.append("(plus ").append(olderFailures)
                    .append(" earlier parse failure").append(olderFailures == 1 ? "" : "s")
                    .append(" on this task)\n\n");
        }
        String obs = failure.observation() == null ? null : failure.observation().output();
        correction.append(obs == null || obs.isBlank()
                ? "Your previous output could not be parsed as an action."
                : obs);
        correction.append("\n\n---\n").append(buildDynamicContext(context, mode));
        messages.add(LlmMessage.user(correction.toString()));
    }

    /**
     * The tools the CLOUD model may call on this step.
     *
     * <p>On unattended work the registry is withheld, so the cloud can orchestrate but cannot
     * execute. That is the architecture the owner asked for — "cloud orchestrates, local
     * executes" — made structural instead of advisory.
     *
     * <p>It is structural because advice demonstrably does not work. Three successive prompt
     * formulations over seven months failed to get a single delegation chosen, and the reason is
     * visible in the tasks themselves: a scheduled description names the exact skills and the
     * exact order ("using daily_news_digest skill, then ... Use smtp_send_email"), so a specific
     * instruction outcompetes a general preference every time. Today both scheduled runs spent a
     * quarter of a million cloud tokens each on work with no judgement in it at all.
     *
     * <p>Only when the local model is actually reachable and advertises tool use. If it is down,
     * the cloud keeps the full set and the task runs exactly as it does today: a local tier that
     * is not answering must not become a reason for scheduled work to stop.
     *
     * <p>Attended chat is untouched. There the user IS waiting, a local step costs about a
     * minute, and the owner has been explicit that latency matters there and does not matter for
     * scheduled work.
     */
    /** Every skill by name and one line each: what exists, without the ability to call it. */
    private String skillCatalogue() {
        return toolRegistry.all().stream()
                .filter(t -> t != null && t.name() != null)
                .sorted((a, b) -> a.name().compareToIgnoreCase(b.name()))
                .map(t -> "- " + t.name() + ": " + truncate(t.description(), 110))
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

    private List<com.ownclaw.llm.ToolSpec> toolsFor(AgentContext context, StepMode mode) {
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
        // rebuild it. So delegate's description carries a catalogue: names and one line each,
        // which is knowledge without capability.
        var specs = new ArrayList<>(ToolSchemas.build(
                SpecialActionSchemas.ALL, List.of(), context.credentialKeys()));
        String catalogue = skillCatalogue();
        specs.replaceAll(spec -> {
            if (!AgentAction.DELEGATE.equals(spec.name())) return spec;
            return new com.ownclaw.llm.ToolSpec(spec.name(),
                    spec.description()
                            + "\n\nYou are orchestrating unattended work, so you cannot run "
                            + "skills yourself — this is how the work gets done. State the goal "
                            + "fully; the local model picks the tools. Skills available to it:\n"
                            + (catalogue.isBlank() ? "(none yet — use skill_create first)" : catalogue),
                    spec.inputSchema());
        });
        return specs;
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
            // Knowledge without capability. The cloud still needs to know a skill exists --
            // otherwise it reaches for skill_create to rebuild one it already owns -- but it is
            // no longer told it can call it, which is what made the prompt argue with the array.
            sb.append("## Skills on this machine\n");
            String catalogue = skillCatalogue();
            sb.append(catalogue.isBlank() ? "(none yet — use skill_create)\n" : catalogue + "\n");
            sb.append("You cannot call these yourself on this task. 'delegate' reaches all of "
                    + "them: state the goal in full and the local model picks the tools.\n");
        } else if (!mode.nativeTools()) {
            ToolSelector.Selection selection = selectToolsForPrompt(context);
            sb.append("## Tools\n");
            String manifest = toolRegistry.generateManifest(selection.detailed(), context.credentialKeys());
            sb.append(manifest).append("\n");
            if (!selection.otherNames().isEmpty()) {
                sb.append("\nAlso available, names only: ")
                  .append(formatNamePreview(selection.otherNames(), config.getMentor().getToolNamePreviewLimit()))
                  .append("\n");
            }
            if (manifest.isBlank()) {
                sb.append("No tools yet — use skill_create.\n");
            }
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
     * Select tools for prompt injection.
     *
     * <p>Default behavior uses the heuristic {@link ToolSelector}.
     * When enabled, step-0 selection can be delegated to the local LLM (Ollama)
     * to reduce prompt size while keeping relevant tools.
     */
    private ToolSelector.Selection selectToolsForPrompt(AgentContext context) {
        ToolSelector.Selection heuristic = toolSelector.select(context.originalMessage(), context.trajectory());

        // Only run local selection on step 0 (biggest prompt) and only if enabled.
        if (!context.trajectory().isEmpty()) return heuristic;
        if (!config.getMentor().isLocalToolSelection()) return heuristic;

        LlmProvider local = llmRouter.local();
        if (local == null || !local.isAvailable()) return heuristic;

        int maxTools = Math.max(1, config.getMentor().getLocalToolSelectionMaxTools());
        int candidateLimit = Math.max(maxTools, config.getMentor().getLocalToolSelectionCandidateLimit());

        try {
            List<Tool> all = toolRegistry.all().stream()
                    .sorted(Comparator.comparing(Tool::name))
                    .toList();

            // Cap candidate list deterministically to keep local prompt bounded.
            if (all.size() > candidateLimit) {
                all = all.subList(0, candidateLimit);
            }

            // The rule here used to read 'prefer task-specific tools over generic ones', which is
            // exactly backwards for a library that is supposed to be reused. A general tool
            // invoked with a parameter IS the reuse we want; a narrowly named one is the bloat we
            // are trying to stop. Ranking specific above general taught the selector to surface
            // web_search_bikes ahead of a general web search, and to surface one of eight IMAP
            // variants rather than the one that actually covers the case.
            String selectorSystem = "You are a tool selection assistant. "
                    + "Given a task and a list of available tools, choose the smallest useful set of tools. "
                    + "Return ONLY valid JSON: {\"tools\": [\"name\", ...]}. "
                    + "Rules: pick at most " + maxTools + " tools; only choose names that appear in the list; "
                    + "prefer general tools that take the specifics as parameters over narrowly "
                    + "named ones; if unsure, return an empty list.";

            String selectorUser = buildLocalToolSelectionUserPrompt(context.originalMessage(), all);
            List<LlmMessage> messages = List.of(
                    LlmMessage.system(selectorSystem),
                    LlmMessage.user(selectorUser)
            );

            // Small, structured response.
            LlmRequestConfig req = new LlmRequestConfig(
                    null,
                    0.0,
                    // Uncapped: local generation is free and a cap starves thinking models.
                    null,
                    true,
                    null
            );

            LlmResponse resp = local.chat(messages, req);
            Set<String> picked = parseSelectedToolNames(resp.content(), maxTools);
            if (picked.isEmpty()) return heuristic;

            List<Tool> detailed = new ArrayList<>();
            for (Tool t : toolRegistry.all()) {
                if (picked.contains(t.name())) {
                    detailed.add(t);
                }
            }

            // Ensure we don't exceed maxTools even if duplicates/extra names slip through.
            if (detailed.size() > maxTools) {
                detailed = detailed.subList(0, maxTools);
            }

            Set<String> detailedNames = detailed.stream().map(Tool::name).collect(java.util.stream.Collectors.toSet());
            List<String> otherNames = toolRegistry.all().stream()
                    .map(Tool::name)
                    .filter(n -> !detailedNames.contains(n))
                    .sorted()
                    .toList();

            return new ToolSelector.Selection(detailed, otherNames);
        } catch (Exception e) {
            log.debug("Local tool selection failed (non-fatal): {}", e.getMessage());
            return heuristic;
        }
    }

    private String buildLocalToolSelectionUserPrompt(String task, List<Tool> candidates) {
        var sb = new StringBuilder();
        sb.append("Task:\n").append(task == null ? "" : task).append("\n\n");
        sb.append("Available tools (name: short description | params):\n");

        for (Tool t : candidates) {
            sb.append("- ").append(t.name()).append(": ").append(truncate(t.description(), 160));
            Map<String, ToolParam> schema = t.inputSchema();
            if (schema != null && !schema.isEmpty()) {
                List<String> keys = schema.keySet().stream().sorted().toList();
                sb.append(" | params: ").append(String.join(", ", keys));
            }
            sb.append("\n");
        }
        sb.append("\nReturn JSON only.");
        return sb.toString();
    }

    private Set<String> parseSelectedToolNames(String raw, int maxTools) {
        if (raw == null || raw.isBlank()) return Set.of();
        try {
            String cleaned = LlmOutputUtils.stripCodeFences(raw.strip());
            JsonNode root = mapper.readTree(cleaned);
            JsonNode arr = root.path("tools");
            if (!arr.isArray()) return Set.of();
            Set<String> out = new LinkedHashSet<>();
            for (JsonNode n : arr) {
                if (n.isTextual()) {
                    String name = n.asText("").trim();
                    if (!name.isBlank()) out.add(name);
                }
                if (out.size() >= maxTools) break;
            }
            return out;
        } catch (Exception e) {
            return Set.of();
        }
    }

    private String formatNamePreview(List<String> names, int limit) {
        if (names == null || names.isEmpty()) return "";
        int capped = Math.max(0, limit);
        if (capped == 0) return "(" + names.size() + " omitted)";
        if (names.size() <= capped) return String.join(", ", names);
        List<String> head = names.subList(0, capped);
        return String.join(", ", head) + " … (+" + (names.size() - capped) + " more)";
    }

    /**
     * Format an agent action as a JSON string for multi-turn conversation.
     * Reconstructs what the LLM would have generated as its response.
     */
    private String formatActionForMultiTurn(AgentAction action, boolean fullDetail) {
        try {
            Map<String, Object> map = new LinkedHashMap<>();
            String reasoning = action.reasoning();
            if (reasoning != null && !reasoning.isBlank()) {
                if (!fullDetail && reasoning.length() > 200) {
                    reasoning = reasoning.substring(0, 200) + "...";
                }
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

    /**
     * Format a trajectory turn's observation for multi-turn conversation.
     */
    private String formatObservationForMultiTurn(AgentTrajectory.Turn turn, boolean fullDetail) {
        var sb = new StringBuilder();
        sb.append("[").append(turn.action().tool()).append("] ");
        sb.append(turn.observation().success() ? "OK" : "FAILED");
        sb.append(" (").append(turn.observation().durationMs()).append("ms)\n");

        String output = turn.observation().output();
        if (output != null && !output.isBlank()) {
            if (fullDetail && output.length() > FULL_DETAIL_MAX_CHARS) {
                // fullDetail used to mean "send the whole thing", with no ceiling at all. A tool
                // that returns a large file, a long page or a verbose command dump therefore went
                // to the cloud in full, on EVERY step for as long as it stayed in the two-turn
                // window. At 200 KB that is roughly 50k tokens a step — real money, repeatedly,
                // for output the model has already read once.
                //
                // Head and tail rather than a hard cut: the beginning says what the output is and
                // the end usually carries the result or the error.
                int half = FULL_DETAIL_MAX_CHARS / 2;
                sb.append(output, 0, half)
                        .append("\n...[").append(output.length())
                        .append(" chars total, middle omitted]...\n")
                        .append(output, output.length() - half, output.length());
            } else if (fullDetail || output.length() <= 300) {
                sb.append(output);
            } else {
                // Smart truncation: keep head + tail to preserve context from both ends
                int half = 150;
                sb.append(output, 0, half)
                        .append("\n...[" ).append(output.length()).append(" chars, middle omitted]...\n")
                        .append(output, output.length() - half, output.length());
            }
        }
        return sb.toString();
    }

    /**
     * Build the system prompt. This defines the agent's behavior, available tools,
     * and output format. Completely generic — no domain-specific content.
     */
    private String buildSystemPrompt(AgentContext context, String providerName) {
        return buildSystemPrompt(context, providerName, new StepMode(false, false));
    }

    /**
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
        // Anthropic: always use the full prompt — the static section is cached by
        // Anthropic's prompt caching (9200 tokens cached, read at 10% cost = ~920
        // effective tokens). The compact prompt broke caching: different prefix meant
        // step 2+ never read the cache created on step 1.
        // OpenAI: has no prompt caching, so the compact prompt on steps 2+ saves ~700
        // real tokens per step.
        if (!context.trajectory().isEmpty() && !"anthropic".equals(providerName)) {
            return buildCompactSystemPrompt(context);
        }

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
        sb.append("memory_manage(action=store|list|delete, [key], [content])\n\n");

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
        sb.append("  machine with the full tool set and your credentials, and costs nothing.\n");
        sb.append("  Best for: work on the local machine, LAN, servers and files, and long\n");
        sb.append("  mechanical sequences. Nothing leaves the host, so prefer it for private data.\n");
        sb.append("  Trade-off: roughly a minute per step, so prefer it when nobody is waiting;\n");
        sb.append("  do it yourself when the user is sitting in the chat expecting an answer.\n");
        sb.append("  goal* — what to achieve, stated fully; the local model works out the steps.\n");
        sb.append("  steps (optional): [{description, tool, params}] only when the order matters\n");
        sb.append("    and you already know it. Omit it rather than guess at params.\n");
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
        sb.append("Facts persist across conversations. 'Remember this' → store immediately.\n\n");

        // Output format
        sb.append("## Output\n");
        if (nativeTools) {
            sb.append("Call exactly one tool per step. Keep any text alongside it to a sentence "
                    + "or two — it is reasoning, not the answer.\n");
            sb.append("For respond: put the whole answer in the message argument.\n\n");
        } else {
            sb.append("Single JSON: {\"reasoning\": \"...\", \"tool\": \"name\", \"params\": {...}}\n");
            sb.append("CRITICAL: Keep 'reasoning' to 1-2 sentences. Long reasoning wastes tokens and risks truncation.\n");
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

        // Smart tool selection — include only relevant tools in detail
        ToolSelector.Selection selection = toolSelector.select(
                context.originalMessage(), context.trajectory());

        sb.append("## Available Tools\n");
        String manifest = toolRegistry.generateManifest(selection.detailed(), context.credentialKeys());
        sb.append(manifest).append("\n");

        // If some tools were omitted, list them by name so the LLM knows they exist
        if (!selection.otherNames().isEmpty()) {
            sb.append("\n## Other Available Tools (use by name if needed)\n");
            sb.append(String.join(", ", selection.otherNames())).append("\n");
        }

        if (manifest.isBlank()) {
            sb.append("\nNo tools yet. Use skill_create as first action.\n");
        }
        sb.append("\n");

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
     * Condensed system prompt for reasoning steps 2+.
     * Omits verbose behavioral guidelines, detailed parameter descriptions,
     * and instructional sections that the LLM has already seen on step 1.
     * Saves ~700 cloud tokens per step.
     */
    private String buildCompactSystemPrompt(AgentContext context) {
        var sb = new StringBuilder(2048);

        sb.append("Autonomous agent. Reason, pick tools, observe, repeat. Never refuse. skill_create for new capabilities.\n\n");

        // ── CACHE BOUNDARY ── static preamble above is cacheable
        sb.append(CACHE_BOUNDARY_MARKER);

        // Environment (always — dynamic datetime)
        sb.append("## Environment\n");
        sb.append("- Platform: ").append(detectPlatform()).append("\n");
        sb.append("- DateTime: ").append(LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MINUTES)
                .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)).append("\n\n");

        // User preferences (always — dynamic per user)
        if (context.userPreferences() != null && !context.userPreferences().isBlank()) {
            sb.append("## User Preferences\n");
            sb.append(context.userPreferences()).append("\n\n");
        }

        // Available Tools (always — dynamic tool selection based on trajectory)
        ToolSelector.Selection selection = toolSelector.select(
                context.originalMessage(), context.trajectory());
        sb.append("## Tools\n");
        String manifest = toolRegistry.generateManifest(selection.detailed(), context.credentialKeys());
        sb.append(manifest).append("\n");
        if (!selection.otherNames().isEmpty()) {
            sb.append("Also: ").append(String.join(", ", selection.otherNames())).append("\n");
        }
        if (manifest.isBlank()) {
            sb.append("No tools yet — use skill_create.\n");
        }
        sb.append("\n");

        // Compact special actions — parameter names only, one line each
        sb.append("## Actions\n");
        sb.append("respond(message) | ask_user(message)\n");
        sb.append("skill_create(name, description, parameters[JSON], [requirements], [credentials], [system_packages→container], [timeout])\n");
        sb.append("Fix skill: reuse SAME name. NEVER _v2/_fixed/_new.\n");
        sb.append("skill_manage(action=read|delete|list|analyze, [name])\n");
        sb.append("credential_manage(action=list|check, [key])\n");
        sb.append("memory_manage(action=store|list|delete, [key], [content])\n");
        sb.append("schedule_manage(action=schedule_once|schedule_recurring|list|cancel|pause|resume, [description], [time], [schedule], [max_runs], [task_id])\n");
        sb.append("delegate(goal, [steps], [checkpoints], [max_steps]) — hand a sub-goal to the FREE local model.\n");
        sb.append("  It runs its own loop with the full tool set and your credentials, on this machine.\n");
        sb.append("  Best for local/LAN/server work and private data. ~1 min per step, so prefer it when nobody is waiting.\n");
        sb.append("  Only 'goal' is required — omit steps rather than guess at params you cannot know yet.\n\n");

        // Problem-solving nudge (compact version of the full prompt's ## Problem Solving)
        sb.append("Stuck? Think deeper, search the internet, try a fundamentally different approach. Never repeat what failed.\n");
        sb.append("Need OS tools/binaries? system_packages in skill_create auto-installs any apt package in a container.\n\n");

        // Credential reminder in compact prompt
        List<String> vaultKeys = context.credentialKeys();
        if (!vaultKeys.isEmpty()) {
            sb.append("Vault: ").append(String.join(", ", vaultKeys)).append("\n");
        }
        sb.append("Credentials auto-injected. Only ask for missing ones. Declare in 'credentials' param.\n\n");

        // Output format (always needed)
        sb.append("Output: {\"reasoning\": \"...\", \"tool\": \"name\", \"params\": {...}}\n");

        // Delegation nudge — injected by AgentLoop when repetitive tool calls are detected
        Object nudge = context.metadata().get("delegationNudge");
        if (nudge instanceof String nudgeMsg && !nudgeMsg.isBlank()) {
            sb.append("\nCOST WARNING: ").append(nudgeMsg).append("\n");
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

        // Relevant past experiences from memory
        Object memories = context.metadata().get("relevantMemories");
        if (memories instanceof String memStr && !memStr.isBlank()) {
            sb.append("## Past Experience\n");
            sb.append(memStr).append("\n\n");
        }

        sb.append("## Task\n");
        sb.append(context.originalMessage());

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
     * Parse the LLM's JSON response into an AgentAction.
     * Handles common LLM output quirks (code fences, comments, extra text, etc.).
     */
    /**
     * Parse text as an action, or return null if it plainly is not one.
     * <p>
     * {@link #parseAction} can never say "this is not an action" — it falls back to RESPOND with
     * the raw text, which is the right answer for the text protocol and the wrong one when tools
     * were offered. There, prose means the model chose to answer, but a JSON envelope means a
     * local model ignored the tools array, and handing that envelope to the user as their answer
     * would be worse than either. This distinguishes the two.
     */
    AgentAction tryParseAction(String raw) {
        if (raw == null || raw.isBlank()) return null;
        Map<String, Object> parsed = tryParseJsonObject(LlmOutputUtils.stripCodeFences(raw.strip()));
        if (parsed == null) return null;
        Object tool = parsed.get("tool");
        if (tool == null || String.valueOf(tool).isBlank()) return null;
        return parseAction(raw);
    }

    AgentAction parseAction(String raw) {
        if (raw == null || raw.isBlank()) {
            return fallbackResponse("Empty response from reasoning engine.");
        }

        String cleaned = LlmOutputUtils.stripCodeFences(raw.strip());

        // Try to parse as JSON. Use Jackson's streaming parser to find the first
        // valid JSON object — handles nested braces, escaped chars, etc. correctly.
        // If no valid JSON object can be parsed, the LLM produced natural language
        // which is a direct response, not a parse failure.
        Map<String, Object> parsed = tryParseJsonObject(cleaned);
        if (parsed == null) {
            log.warn("ThinkingEngine: no valid JSON object in LLM response, treating as direct response");
            return new AgentAction(AgentAction.RESPOND,
                    Map.of("message", raw.strip()),
                    "LLM did not produce structured output; delivering raw response");
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
                return fallbackResponse("I had trouble deciding what to do. Let me try again.");
            }

            return new AgentAction(tool, params, reasoning != null ? reasoning : "");
        } catch (Exception e) {
            log.warn("ThinkingEngine: failed to parse LLM JSON output: {}", e.getMessage());
            // Last resort: treat it as a direct response
            return new AgentAction(AgentAction.RESPOND,
                    Map.of("message", raw.strip()),
                    "Failed to parse structured output; delivering raw response");
        }
    }

    private AgentAction fallbackResponse(String message) {
        return new AgentAction(AgentAction.RESPOND, Map.of("message", message), "Fallback response");
    }

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
