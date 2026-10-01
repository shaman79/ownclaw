package com.ownclaw.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ownclaw.config.OwnClawConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Anthropic provider as the API streams to it: server-sent events in, one reply out.
 * <p>
 * Every reply here is a scripted stream through the real provider and its real HTTP client, with
 * an interceptor standing in for the network -- the events are shaped as the Messages API sends
 * them, including the ones this code must pass over (pings, thinking blocks, a fallback block).
 */
class AnthropicStreamingTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final String MODELS = "/v1/models/claude-opus-5";
    static final String MESSAGES = "/v1/messages";
    static final String LIMITS =
            "{\"id\":\"claude-opus-5\",\"display_name\":\"Claude Opus 5\",\"max_input_tokens\":1000000,\"max_tokens\":128000}";
    static final String MODELS_4_8 = "/v1/models/claude-opus-4-8";
    static final String LIMITS_4_8 =
            "{\"id\":\"claude-opus-4-8\",\"display_name\":\"Claude Opus 4.8\",\"max_input_tokens\":1000000,\"max_tokens\":64000}";

    // ── the stream, event by event ──

    static String ev(ObjectNode data) {
        return "event: " + data.path("type").asText() + "\ndata: " + data + "\n\n";
    }

    static ObjectNode node(String type) {
        return JSON.createObjectNode().put("type", type);
    }

    static String start(String model, int input, int cacheWrite, int cacheRead) {
        ObjectNode d = node("message_start");
        ObjectNode m = d.putObject("message");
        m.put("id", "msg_1").put("type", "message").put("role", "assistant").put("model", model);
        m.putArray("content");
        m.putNull("stop_reason");
        m.putObject("usage").put("input_tokens", input).put("cache_creation_input_tokens", cacheWrite)
                .put("cache_read_input_tokens", cacheRead).put("output_tokens", 1);
        return ev(d);
    }

    static String blockStart(int index, ObjectNode block) {
        ObjectNode d = node("content_block_start").put("index", index);
        d.set("content_block", block);
        return ev(d);
    }

    static String delta(int index, ObjectNode delta) {
        ObjectNode d = node("content_block_delta").put("index", index);
        d.set("delta", delta);
        return ev(d);
    }

    static String blockStop(int index) {
        return ev(node("content_block_stop").put("index", index));
    }

    static String text(int index, String... pieces) {
        var sb = new StringBuilder(blockStart(index, node("text").put("text", "")));
        for (String p : pieces) sb.append(delta(index, node("text_delta").put("text", p)));
        return sb.append(blockStop(index)).toString();
    }

    static String tool(int index, String id, String name, String... jsonFragments) {
        ObjectNode block = node("tool_use").put("id", id).put("name", name);
        block.putObject("input");
        var sb = new StringBuilder(blockStart(index, block));
        for (String f : jsonFragments) sb.append(delta(index, node("input_json_delta").put("partial_json", f)));
        return sb.append(blockStop(index)).toString();
    }

    /** Adaptive thinking under the default "omitted" display: empty text, then a signature. */
    static String thinking(int index) {
        return blockStart(index, node("thinking").put("thinking", ""))
                + delta(index, node("thinking_delta").put("thinking", ""))
                + delta(index, node("signature_delta").put("signature", "EqQBCkYIBxgCKkA"))
                + blockStop(index);
    }

    static String ping() {
        return ev(node("ping"));
    }

    static String end(String stopReason, String category, int output) {
        ObjectNode d = node("message_delta");
        ObjectNode delta = d.putObject("delta").put("stop_reason", stopReason);
        delta.putNull("stop_sequence");
        if ("refusal".equals(stopReason)) {
            ObjectNode details = delta.putObject("stop_details").put("type", "refusal");
            if (category == null) details.putNull("category"); else details.put("category", category);
        }
        d.putObject("usage").put("output_tokens", output);
        return ev(d) + ev(node("message_stop"));
    }

    /** The last two events, with the message_delta's delta and usage as given. */
    static String stop(ObjectNode delta, ObjectNode usage) {
        ObjectNode d = node("message_delta");
        d.set("delta", delta);
        d.set("usage", usage);
        return ev(d) + ev(node("message_stop"));
    }

    /** A refusal's delta: its category and the model it names to retry on, either of them null. */
    static ObjectNode refusal(String category, String recommendedModel) {
        ObjectNode delta = JSON.createObjectNode().put("stop_reason", "refusal");
        delta.putNull("stop_sequence");
        ObjectNode details = delta.putObject("stop_details").put("type", "refusal");
        if (category == null) details.putNull("category"); else details.put("category", category);
        if (recommendedModel == null) details.putNull("recommended_model");
        else details.put("recommended_model", recommendedModel);
        return delta;
    }

    static ObjectNode outputTokens(int output) {
        return JSON.createObjectNode().put("output_tokens", output);
    }

    /** One entry of usage.iterations: "message" for a model that declined, "fallback_message" for the one that served. */
    static ObjectNode iteration(String type, String model, int input, int output) {
        return JSON.createObjectNode().put("type", type).put("model", model).put("input_tokens", input)
                .put("output_tokens", output).put("cache_creation_input_tokens", 0)
                .put("cache_read_input_tokens", 0);
    }

    static ObjectNode fallbackBlock(String from, String to) {
        ObjectNode fallback = node("fallback");
        fallback.putObject("from").put("model", from);
        fallback.putObject("to").put("model", to);
        return fallback;
    }

    static LlmResponse.Usage usage(String model, int input, int output) {
        return new LlmResponse.Usage(model, input, output, 0, 0);
    }

    static int events(String stream) {
        return stream.split("\nevent: ", -1).length;   // the first event has no newline before it
    }

    // ── the provider, over a fake network ──

    static FakeHttp api(String stream) {
        return new FakeHttp().json(MODELS, 200, LIMITS).on(MESSAGES, 200, "text/event-stream", stream);
    }

    static AnthropicProvider provider(FakeHttp http) {
        return provider(http, "claude-opus-5");
    }

    static AnthropicProvider provider(FakeHttp http, String model) {
        var config = new OwnClawConfig();
        config.getMentor().setAnthropicApiKey("test-key");
        config.getMentor().setAnthropicModel(model);
        return new AnthropicProvider(config, JSON, http.client());
    }

    static final List<LlmMessage> ASK = List.of(LlmMessage.system("S"), LlmMessage.user("check the network"));
    static final List<ToolSpec> TOOLS = List.of(new ToolSpec("shell_exec", "run a command",
            Map.of("type", "object", "properties", Map.of("command", Map.of("type", "string")))));

    @Test
    @DisplayName("text comes out whole, thinking and pings are passed over, and every token counter is kept")
    void textAndUsage() {
        String stream = start("claude-opus-5", 284, 1830, 29072) + ping() + thinking(0)
                + text(1, "The router ", "answers on ", "192.0.2.1.") + ping() + end("end_turn", null, 42);
        var http = api(stream);
        var seen = new AtomicInteger();

        LlmResponse r = provider(http).chat(ASK, new LlmRequestConfig(null, null, false).withProgress(seen::incrementAndGet));

        assertEquals("The router answers on 192.0.2.1.", r.content());
        assertFalse(r.hasToolCalls());
        assertEquals(284, r.promptTokens());
        assertEquals(42, r.completionTokens(), "message_delta's count is the final one");
        assertEquals(1830, r.cacheCreationTokens());
        assertEquals(29072, r.cacheReadTokens());
        assertEquals("end_turn", r.stopReason());
        assertEquals("end_turn", r.stopDescription());
        assertEquals("claude-opus-5", r.model());
        assertEquals(128_000, r.maxOutputTokens());
        assertEquals(1_000_000, r.contextWindow());
        assertSame(r, r.requireComplete("anthropic"));
        assertEquals(events(stream), seen.get(), "the hook hears every event, pings included");
        assertEquals(http.opened.get(), http.closed.get(), "every body read is closed");
    }

    @Test
    @DisplayName("what goes out: the model's own maximum, a stream, fallbacks with their beta, eager tool input")
    void theRequest() throws Exception {
        var http = api(start("claude-opus-5", 1, 0, 0) + text(0, "ok") + end("end_turn", null, 1));
        provider(http).chat(ASK, new LlmRequestConfig(null, null, false).withTools(TOOLS));

        var sent = http.to(MESSAGES).get(0);
        assertEquals(AnthropicProvider.FALLBACK_BETA + "," + AnthropicProvider.CONTEXT_WINDOW_BETA,
                sent.header("anthropic-beta"));
        assertEquals("server-side-fallback-2026-07-01", AnthropicProvider.FALLBACK_BETA);
        assertEquals("model-context-window-exceeded-2025-08-26", AnthropicProvider.CONTEXT_WINDOW_BETA,
                "a model older than 4.5 stops at its window instead of refusing input plus max_tokens over it");
        JsonNode body = JSON.readTree(sent.body());
        assertEquals(128_000, body.path("max_tokens").asInt(), "the Models API's max_tokens, not a number of ours");
        assertTrue(body.path("stream").asBoolean());
        assertEquals("default", body.path("fallbacks").asText());
        assertTrue(body.path("tools").get(0).path("eager_input_streaming").asBoolean());
        assertEquals("test-key", http.to(MODELS).get(0).header("x-api-key"));
    }

    @Test
    @DisplayName("a tool call's input, streamed in fragments that split words and escapes, is parsed whole")
    void fragmentedToolInput() {
        var http = api(start("claude-opus-5", 10, 0, 0) + text(0, "Checking.")
                + tool(1, "toolu_1", "shell_exec", "", "{\"comm", "and\": \"uname -a; echo \\\"", "done\\\"\", \"tim",
                        "eout\": 30, \"env\": {\"A\": [1, 2]}}")
                + end("tool_use", null, 20));

        LlmResponse r = provider(http).chat(ASK, new LlmRequestConfig(null, null, false).withTools(TOOLS));

        assertEquals("Checking.", r.content());
        assertEquals(1, r.toolCalls().size());
        ToolCall call = r.toolCalls().get(0);
        assertEquals("toolu_1", call.id());
        assertEquals("shell_exec", call.name());
        assertEquals("uname -a; echo \"done\"", call.arguments().get("command"));
        assertEquals(30, call.arguments().get("timeout"));
        assertEquals(Map.of("A", List.of(1, 2)), call.arguments().get("env"));
        assertEquals("tool_use", r.stopReason());
    }

    @Test
    @DisplayName("a tool call with no input streamed at all has empty arguments")
    void emptyToolInput() {
        var http = api(start("claude-opus-5", 10, 0, 0) + tool(0, "toolu_2", "respond") + end("tool_use", null, 5));
        assertEquals(Map.of(), provider(http).chat(ASK, new LlmRequestConfig(null, null, false)).toolCalls().get(0).arguments());
    }

    @Test
    @DisplayName("a complete reply whose tool input is not valid JSON offers no call, and the check refuses it")
    void malformedToolInput() {
        var http = api(start("claude-opus-5", 10, 0, 0)
                + tool(0, "toolu_3", "shell_exec", "{\"command\": \"say \"hi\"\"}") + end("tool_use", null, 9));
        LlmResponse r = provider(http).chat(ASK, new LlmRequestConfig(null, null, false));
        assertFalse(r.hasToolCalls(), "never a call with guessed arguments");
        var e = assertThrows(MalformedToolCall.class, () -> r.requireComplete("anthropic"));
        assertTrue(e.getMessage().contains("'shell_exec'"), e.getMessage());
        assertSame(r, e.reply(), "with the reply, whose tokens were billed");

        // Strict: a valid object followed by anything else is not read as the object alone.
        var trailing = api(start("claude-opus-5", 10, 0, 0)
                + tool(0, "toolu_5", "shell_exec", "{\"command\": \"ls\"} {\"command\": \"rm -rf /srv\"}")
                + end("tool_use", null, 9));
        LlmResponse t = provider(trailing).chat(ASK, new LlmRequestConfig(null, null, false));
        assertFalse(t.hasToolCalls());
        assertThrows(MalformedToolCall.class, () -> t.requireComplete("anthropic"));
    }

    @Test
    @DisplayName("a tool input cut off at the output limit is reported as the limit, not as bad JSON")
    void toolInputCutAtTheLimit() {
        var http = api(start("claude-opus-5", 10, 0, 0)
                + tool(0, "toolu_4", "write_file", "{\"path\": \"/tmp/x\", \"content\": \"line 1\\nline")
                + end("max_tokens", null, 128_000));
        LlmResponse r = provider(http).chat(ASK, new LlmRequestConfig(null, null, false));
        assertFalse(r.hasToolCalls(), "a half-written call is never offered to be run");
        var e = assertThrows(OutputTruncated.class, () -> r.requireComplete("anthropic"));
        assertEquals(OutputTruncated.Limit.MAX_OUTPUT, e.limit());
        assertEquals(128_000, e.tokens());
        assertEquals("[anthropic] the reply reached the model's maximum output of 128,000 tokens and was cut off",
                e.getMessage());
    }

    @Test
    @DisplayName("a refusal keeps its reason and category, and is never taken for an answer")
    void refusal() {
        var http = api(start("claude-opus-5", 0, 0, 0) + end("refusal", "cyber", 0));
        LlmResponse r = provider(http).chat(ASK, new LlmRequestConfig(null, null, false));

        assertTrue(r.refused());
        assertEquals("cyber", r.stopDetail());
        assertEquals("refusal (cyber)", r.stopDescription());
        var e = assertThrows(ProviderRefused.class, () -> r.requireComplete("anthropic"));
        assertEquals("refusal", e.stopReason());
        assertEquals("cyber", e.category());
        assertEquals("[anthropic] the model declined this request (stop reason: refusal (cyber))",
                e.getMessage(), "the stop said as the ledger and the task page say it");
    }

    @Test
    @DisplayName("a refusal with no category is still a refusal")
    void refusalWithoutCategory() {
        var http = api(start("claude-opus-5", 0, 0, 0) + end("refusal", null, 0));
        LlmResponse r = provider(http).chat(ASK, new LlmRequestConfig(null, null, false));
        assertEquals("refusal", r.stopDescription());
        assertNull(assertThrows(ProviderRefused.class, () -> r.requireComplete("anthropic")).category());
    }

    @Test
    @DisplayName("the context window filling up is named as the window, with its size")
    void contextWindowExceeded() {
        var http = api(start("claude-opus-5", 999_000, 0, 0) + text(0, "partial") + end("model_context_window_exceeded", null, 1000));
        LlmResponse r = provider(http).chat(ASK, new LlmRequestConfig(null, null, false));
        var e = assertThrows(OutputTruncated.class, () -> r.requireComplete("anthropic"));
        assertEquals(OutputTruncated.Limit.CONTEXT_WINDOW, e.limit());
        assertEquals("[anthropic] the conversation is longer than the model's 1,000,000-token context window",
                e.getMessage());
    }

    @Test
    @DisplayName("a prompt too long for the window is the same plain context-window message")
    void promptTooLong() {
        var http = new FakeHttp().json(MODELS, 200, LIMITS).json(MESSAGES, 400,
                "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\","
                        + "\"message\":\"prompt is too long: 1000512 tokens > 1000000 maximum\"}}");
        var e = assertThrows(OutputTruncated.class, () -> provider(http).chat(ASK, new LlmRequestConfig(null, null, false)));
        assertEquals(OutputTruncated.Limit.CONTEXT_WINDOW, e.limit());
        assertEquals(1_000_000, e.tokens());
        assertNull(e.reply(), "refused before any reply");
    }

    @Test
    @DisplayName("a request larger than the API takes is the same plain context-window message, not a failure to try again")
    void requestTooLarge() {
        var http = new FakeHttp().json(MODELS, 200, LIMITS).json(MESSAGES, 413,
                "{\"type\":\"error\",\"error\":{\"type\":\"request_too_large\","
                        + "\"message\":\"Request exceeds the maximum allowed number of bytes.\"}}");
        var e = assertThrows(OutputTruncated.class, () -> provider(http).chat(ASK, new LlmRequestConfig(null, null, false)));
        assertEquals(OutputTruncated.Limit.CONTEXT_WINDOW, e.limit());
        assertEquals("[anthropic] the conversation is longer than the model's 1,000,000-token context window",
                e.getMessage());
        assertEquals(1, http.to(MESSAGES).size(), "sent once");
        // Mutation: map only the 400 -> an LlmException with status 413, asked again by the loop.
    }

    @Test
    @DisplayName("a fallback block is passed over: the declined model's tool call is dropped, its text continued")
    void fallbackMidReply() {
        ObjectNode fallback = node("fallback");
        fallback.putObject("from").put("model", "claude-opus-5");
        fallback.putObject("to").put("model", "claude-opus-4-8");
        var http = api(start("claude-opus-5", 50, 0, 0)
                + text(0, "Here is the plan: ")
                + tool(1, "toolu_declined", "shell_exec", "{\"command\": \"nmap\"}")
                + blockStart(2, fallback) + blockStop(2)
                + text(3, "first check the logs.")
                + tool(4, "toolu_fallback", "shell_exec", "{\"command\": \"journalctl -n 50\"}")
                + end("tool_use", null, 60));

        LlmResponse r = provider(http).chat(ASK, new LlmRequestConfig(null, null, false));

        assertEquals("Here is the plan: first check the logs.", r.content(),
                "the fallback model continues the declined model's text");
        assertEquals(1, r.toolCalls().size());
        assertEquals("toolu_fallback", r.toolCalls().get(0).id(), "the declined model's call is not the reply's");
        assertEquals("claude-opus-4-8", r.model(), "the model that finished the reply");
    }

    @Test
    @DisplayName("a turn Anthropic sends straight to the fallback model (sticky routing) has no fallback block: message_start names the model")
    void stickyTurn() {
        var http = api(start("claude-opus-4-8", 50, 0, 0) + text(0, "answer") + end("end_turn", null, 3));
        LlmResponse r = provider(http).chat(ASK, new LlmRequestConfig(null, null, false));
        assertEquals("answer", r.content());
        assertEquals("claude-opus-4-8", r.model(), "not the requested claude-opus-5");
        assertEquals(List.of(usage("claude-opus-4-8", 50, 3)), r.usage(), "billed to the model that ran it");
    }

    @Test
    @DisplayName("a fallback part-way through: each attempt is billed at its own model, from usage.iterations")
    void aFallbackMidReplyBillsEveryAttempt() {
        ObjectNode total = JSON.createObjectNode().put("input_tokens", 412).put("output_tokens", 264);
        total.putArray("iterations").add(iteration("message", "claude-opus-5", 535, 900))
                .add(iteration("fallback_message", "claude-opus-4-8", 412, 264));
        var http = api(start("claude-opus-5", 535, 0, 0) + text(0, "Here is ")
                + blockStart(1, fallbackBlock("claude-opus-5", "claude-opus-4-8")) + blockStop(1)
                + text(2, "the plan.") + stop(JSON.createObjectNode().put("stop_reason", "end_turn"), total));

        LlmResponse r = provider(http).chat(ASK, new LlmRequestConfig(null, null, false));

        assertEquals("Here is the plan.", r.content());
        assertEquals("claude-opus-4-8", r.model());
        assertEquals(List.of(usage("claude-opus-5", 535, 900), usage("claude-opus-4-8", 412, 264)), r.usage(),
                "the top-level usage describes only the attempt that served");
        assertEquals(535 + 412, r.promptTokens());
        assertEquals(900 + 264, r.completionTokens(), "the declined attempt's output was billed too");
    }

    @Test
    @DisplayName("an earlier attempt that declined before writing anything is counted as unbilled: the reply does not say its category")
    void anEarlierDeclineBeforeOutputIsNotBilled() {
        ObjectNode total = JSON.createObjectNode().put("input_tokens", 412).put("output_tokens", 264);
        total.putArray("iterations").add(iteration("message", "claude-opus-5", 535, 0))
                .add(iteration("fallback_message", "claude-opus-4-8", 412, 264));
        var http = api(start("claude-opus-4-8", 412, 0, 0)
                + blockStart(0, fallbackBlock("claude-opus-5", "claude-opus-4-8")) + blockStop(0)
                + text(1, "answer") + stop(JSON.createObjectNode().put("stop_reason", "end_turn"), total));
        assertEquals(List.of(usage("claude-opus-4-8", 412, 264)),
                provider(http).chat(ASK, new LlmRequestConfig(null, null, false)).usage());
    }

    @Test
    @DisplayName("a refusal before any output is billed only in the categories Anthropic bills that in")
    void aRefusalBeforeAnyOutputIsBilledByItsCategory() {
        for (String category : new String[] {"cyber", "general_harms", null}) {
            LlmResponse r = provider(api(start("claude-opus-5", 412, 0, 0)
                    + stop(refusal(category, null), outputTokens(0)))).chat(ASK, new LlmRequestConfig(null, null, false));
            assertTrue(r.refused());
            assertEquals(List.of(), r.usage(), category + " is not billed before any output");
            assertEquals(0, r.promptTokens());
        }
        for (String category : new String[] {"bio", "frontier_llm", "reasoning_extraction"}) {
            LlmResponse r = provider(api(start("claude-opus-5", 412, 0, 0)
                    + stop(refusal(category, null), outputTokens(0)))).chat(ASK, new LlmRequestConfig(null, null, false));
            assertEquals(List.of(usage("claude-opus-5", 412, 0)), r.usage(), category + " is billed");
        }
        LlmResponse midStream = provider(api(start("claude-opus-5", 412, 0, 0) + text(0, "Sure, the")
                + stop(refusal("cyber", null), outputTokens(7)))).chat(ASK, new LlmRequestConfig(null, null, false));
        assertEquals(List.of(usage("claude-opus-5", 412, 7)), midStream.usage(),
                "output streamed before a refusal is billed, whatever the category");
    }

    @Test
    @DisplayName("a refusal Anthropic did not re-run, naming a model to retry on, is sent to that model once, without fallbacks")
    void aRefusalThatNamesAModelIsRetriedOnItOnce() throws Exception {
        // Declined while a tool call was still streaming: the one decline Anthropic's own
        // fallback does not re-run on a stream.
        String declined = start("claude-opus-5", 900, 0, 0)
                + blockStart(0, node("tool_use").put("id", "toolu_1").put("name", "respond"))
                + delta(0, node("input_json_delta").put("partial_json", "{\"message\": \"The scan found"))
                + blockStop(0) + stop(refusal("cyber", "claude-opus-4-8"), outputTokens(40));
        String answered = start("claude-opus-4-8", 900, 0, 0)
                + tool(0, "toolu_2", "respond", "{\"message\": \"Two hosts are up.\"}") + end("tool_use", null, 12);
        var http = new FakeHttp().json(MODELS, 200, LIMITS).json(MODELS_4_8, 200, LIMITS_4_8)
                .on(MESSAGES, 200, "text/event-stream", declined)
                .on(MESSAGES, 200, "text/event-stream", answered);
        var seen = new AtomicInteger();

        LlmResponse r = provider(http).chat(ASK, new LlmRequestConfig(null, null, false).withTools(TOOLS)
                .withProgress(seen::incrementAndGet));

        assertEquals("tool_use", r.stopReason());
        assertEquals("claude-opus-4-8", r.model());
        assertEquals(List.of("toolu_2"), r.toolCalls().stream().map(ToolCall::id).toList(),
                "the declined model's half-written call is not the reply's");
        assertEquals(List.of(usage("claude-opus-5", 900, 40), usage("claude-opus-4-8", 900, 12)), r.usage(),
                "both attempts were billed");
        var sent = http.to(MESSAGES);
        assertEquals(2, sent.size());
        JsonNode retry = JSON.readTree(sent.get(1).body());
        assertEquals("claude-opus-4-8", retry.path("model").asText());
        assertFalse(retry.has("fallbacks"), "a direct retry, so it cannot chain");
        assertEquals(64_000, retry.path("max_tokens").asInt(), "the retry model's own maximum");
        assertEquals(AnthropicProvider.CONTEXT_WINDOW_BETA, sent.get(1).header("anthropic-beta"));
        assertEquals(JSON.readTree(sent.get(0).body()).path("messages"), retry.path("messages"),
                "the same conversation");
        assertEquals(events(declined) + events(answered), seen.get(), "the hook heard both streams");
        assertEquals(http.opened.get(), http.closed.get());
    }

    @Test
    @DisplayName("a refusal that names no model to retry on stands: one request, and no answer")
    void aRefusalThatNamesNoModelStands() {
        var http = api(start("claude-opus-5", 900, 0, 0) + text(0, "Sure, the")
                + stop(refusal("cyber", null), outputTokens(3)));
        LlmResponse r = provider(http).chat(ASK, new LlmRequestConfig(null, null, false));
        assertTrue(r.refused());
        assertEquals(1, http.to(MESSAGES).size());
        assertThrows(ProviderRefused.class, () -> r.requireComplete("anthropic"));
    }

    @Test
    @DisplayName("the retry is made once: a refusal from the named model is the answer, billed for both attempts")
    void theRetryIsMadeOnce() {
        var http = new FakeHttp().json(MODELS, 200, LIMITS).json(MODELS_4_8, 200, LIMITS_4_8)
                .on(MESSAGES, 200, "text/event-stream", start("claude-opus-5", 900, 0, 0) + text(0, "Sure, the")
                        + stop(refusal("cyber", "claude-opus-4-8"), outputTokens(3)))
                .on(MESSAGES, 200, "text/event-stream", start("claude-opus-4-8", 900, 0, 0) + text(0, "I")
                        + stop(refusal("cyber", "claude-opus-4-7"), outputTokens(1)));
        LlmResponse r = provider(http).chat(ASK, new LlmRequestConfig(null, null, false));
        assertTrue(r.refused());
        assertEquals("claude-opus-4-8", r.model());
        assertEquals(2, http.to(MESSAGES).size(), "not a third time, though the second refusal names a model too");
        assertEquals(List.of(usage("claude-opus-5", 900, 3), usage("claude-opus-4-8", 900, 1)), r.usage());
    }

    @Test
    @DisplayName("a retry that fails leaves the refusal, with the tokens it was billed")
    void aFailedRetryLeavesTheRefusal() {
        var http = new FakeHttp().json(MODELS, 200, LIMITS).json(MODELS_4_8, 200, LIMITS_4_8)
                .on(MESSAGES, 200, "text/event-stream", start("claude-opus-5", 900, 0, 0) + text(0, "Sure, the")
                        + stop(refusal("cyber", "claude-opus-4-8"), outputTokens(3)))
                .json(MESSAGES, 400, "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"no\"}}");
        LlmResponse r = provider(http).chat(ASK, new LlmRequestConfig(null, null, false));
        assertTrue(r.refused(), "the decline is why there is no answer");
        assertEquals("claude-opus-5", r.model());
        assertEquals(List.of(usage("claude-opus-5", 900, 3)), r.usage());
        assertEquals(2, http.to(MESSAGES).size());
    }

    @Test
    @DisplayName("what the progress hook throws during the retry still reaches the caller unchanged")
    void theHookCanStopTheRetry() {
        String declined = start("claude-opus-5", 900, 0, 0) + text(0, "Sure, the")
                + stop(refusal("cyber", "claude-opus-4-8"), outputTokens(3));
        var http = new FakeHttp().json(MODELS, 200, LIMITS).json(MODELS_4_8, 200, LIMITS_4_8)
                .on(MESSAGES, 200, "text/event-stream", declined)
                .on(MESSAGES, 200, "text/event-stream", start("claude-opus-4-8", 900, 0, 0) + text(0, "ok") + end("end_turn", null, 1));
        var stop = new Stopped();
        var seen = new AtomicInteger();
        int firstOfTheRetry = events(declined) + 1;
        var thrown = assertThrows(Stopped.class, () -> provider(http).chat(ASK, new LlmRequestConfig(null, null, false)
                .withProgress(() -> { if (seen.incrementAndGet() == firstOfTheRetry) throw stop; })));
        assertSame(stop, thrown, "not taken for a failed retry");
        assertEquals(http.opened.get(), http.closed.get());
    }

    @Test
    @DisplayName("an event whose data is not JSON fails the call; it is never skipped")
    void anEventThatIsNotJsonFailsTheCall() {
        String cut = "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":1,"
                + "\"delta\":{\"type\":\"text_del\n\n";
        var http = api(start("claude-opus-5", 5, 0, 0) + text(0, "one ") + cut + text(2, "three")
                + end("end_turn", null, 3));
        var e = assertThrows(LlmException.class, () -> provider(http).chat(ASK, new LlmRequestConfig(null, null, false)));
        assertTrue(e.getMessage().contains("not JSON"), e.getMessage());
    }

    @Test
    @DisplayName("a stream that stops in the middle of its last event is incomplete, though that event was message_stop")
    void aLastEventCutOffIsIncomplete() {
        String whole = start("claude-opus-5", 5, 0, 0) + text(0, "all of it") + end("end_turn", null, 3);
        var http = api(whole.substring(0, whole.length() - 1));   // no blank line after message_stop
        var e = assertThrows(LlmException.class, () -> provider(http).chat(ASK, new LlmRequestConfig(null, null, false)));
        assertTrue(e.getMessage().contains("before message_stop"), e.getMessage());
    }

    @Test
    @DisplayName("a decline before any output: message_start already names the model that answered")
    void fallbackBeforeOutput() {
        ObjectNode fallback = node("fallback");
        fallback.putObject("from").put("model", "claude-opus-5");
        fallback.putObject("to").put("model", "claude-opus-4-8");
        var http = api(start("claude-opus-4-8", 50, 0, 0) + blockStart(0, fallback) + blockStop(0)
                + text(1, "answer") + end("end_turn", null, 3));
        LlmResponse r = provider(http).chat(ASK, new LlmRequestConfig(null, null, false));
        assertEquals("answer", r.content());
        assertEquals("claude-opus-4-8", r.model());
    }

    @Test
    @DisplayName("an error event mid-stream becomes an LlmException with the status the same error has as a response")
    void errorEventMidStream() {
        ObjectNode error = node("error");
        error.putObject("error").put("type", "invalid_request_error").put("message", "bad block");
        var http = api(start("claude-opus-5", 5, 0, 0) + text(0, "par") + ev(error));
        var seen = new AtomicInteger();

        var e = assertThrows(LlmException.class,
                () -> provider(http).chat(ASK, new LlmRequestConfig(null, null, false).withProgress(seen::incrementAndGet)));

        assertEquals(400, e.getHttpStatus());
        assertTrue(e.getMessage().contains("invalid_request_error: bad block"), e.getMessage());
        assertEquals(5, seen.get(), "every event up to the error was heard");
        assertEquals(http.opened.get(), http.closed.get());
    }

    @Test
    @DisplayName("an overload reported mid-stream is retried like HTTP 529")
    void overloadMidStreamIsRetryable() throws Exception {
        var e = AnthropicProvider.streamError(JSON.readTree(
                "{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}"));
        assertTrue(e.isOverloaded());
        assertTrue(e.isRetryable(), "RateLimitBackoff retries it");
        assertEquals(429, AnthropicProvider.streamError(JSON.readTree("{\"type\":\"rate_limit_error\"}")).getHttpStatus());
        assertFalse(AnthropicProvider.streamError(JSON.readTree("{\"type\":\"invalid_request_error\"}")).isRetryable());
    }

    @Test
    @DisplayName("a stream that stops before message_stop is incomplete, not a short answer")
    void cutStream() {
        var http = api(start("claude-opus-5", 5, 0, 0) + text(0, "half an ans"));
        var e = assertThrows(LlmException.class, () -> provider(http).chat(ASK, new LlmRequestConfig(null, null, false)));
        assertTrue(e.getMessage().contains("before message_stop"), e.getMessage());
    }

    /** What Stop, or the stall watchdog, throws from the hook. */
    static final class Stopped extends RuntimeException {}

    @Test
    @DisplayName("what the progress hook throws reaches the caller unchanged, and the stream is closed")
    void theHookCanStopTheCall() {
        var http = api(start("claude-opus-5", 5, 0, 0) + ping() + text(0, "a", "b", "c") + end("end_turn", null, 3));
        var stop = new Stopped();
        var seen = new AtomicInteger();
        LlmProgress hook = () -> {
            if (seen.incrementAndGet() == 4) throw stop;
        };

        var thrown = assertThrows(Stopped.class, () -> provider(http).chat(ASK, new LlmRequestConfig(null, null, false).withProgress(hook)));

        assertSame(stop, thrown, "not wrapped, not replaced");
        assertEquals(4, seen.get(), "nothing was read after it");
        assertEquals(1, http.to(MESSAGES).size(), "and the call was not retried");
        assertEquals(2, http.opened.get(), "the Models API's answer and the stream");
        assertEquals(2, http.closed.get(), "the stream was closed on the way out");
    }

    @Test
    @DisplayName("the Models API is asked once per model; a failed lookup fails the call and is asked again next time")
    void modelsLookup() {
        String stream = start("claude-opus-5", 1, 0, 0) + text(0, "ok") + end("end_turn", null, 1);
        var http = new FakeHttp()
                .json(MODELS, 404, "{\"type\":\"error\",\"error\":{\"type\":\"not_found_error\",\"message\":\"model: claude-opus-5\"}}")
                .json(MODELS, 200, LIMITS)
                .on(MESSAGES, 200, "text/event-stream", stream);
        var provider = provider(http);

        var e = assertThrows(LlmException.class, () -> provider.chat(ASK, new LlmRequestConfig(null, null, false)));
        assertEquals(404, e.getHttpStatus());
        assertTrue(e.getMessage().contains("Models API"), e.getMessage());
        assertTrue(http.to(MESSAGES).isEmpty(), "no call is made without the model's limits");

        assertEquals("ok", provider.chat(ASK, new LlmRequestConfig(null, null, false)).content(), "the next call asks again");
        assertEquals("ok", provider.chat(ASK, new LlmRequestConfig(null, null, false)).content());
        assertEquals(2, http.to(MODELS).size(), "and once it has an answer, keeps it");
        assertEquals(2, http.to(MESSAGES).size());
    }

    @Test
    @DisplayName("a Models API answer without the limits fails the call: there is no table to guess from")
    void modelsLookupWithoutLimits() {
        var http = new FakeHttp().json(MODELS, 200, "{\"id\":\"claude-opus-5\"}");
        var e = assertThrows(LlmException.class, () -> provider(http).chat(ASK, new LlmRequestConfig(null, null, false)));
        assertTrue(e.getMessage().contains("max_tokens"), e.getMessage());
    }

    // ── what an attempt that ended without a reply was billed ──

    /** A hook that throws {@code stop} on event {@code at} (0: never) and keeps what it is told was billed. */
    static final class StopsAt implements LlmProgress {
        final List<LlmResponse.Usage> billed = new java.util.ArrayList<>();
        private final int at;
        private final RuntimeException stop;
        private int seen;

        StopsAt(int at, RuntimeException stop) {
            this.at = at;
            this.stop = stop;
        }

        @Override
        public void onProgress() {
            if (++seen == at) throw stop;
        }

        @Override
        public void billed(LlmResponse.Usage usage) {
            billed.add(usage);
        }
    }

    @Test
    @DisplayName("a reply stopped part-way tells the hook what its stream said it was billed for, then ends with the stop")
    void aStoppedReplyReportsWhatItWasBilled() {
        var http = api(start("claude-opus-5", 120_000, 0, 80_000) + ping() + text(0, "a", "b", "c")
                + end("end_turn", null, 900));
        var stop = new Stopped();
        var hook = new StopsAt(4, stop);

        assertSame(stop, assertThrows(Stopped.class, () -> provider(http).chat(ASK, new LlmRequestConfig(null, null, false).withProgress(hook))));

        assertEquals(List.of(new LlmResponse.Usage("claude-opus-5", 120_000, 1, 0, 80_000)), hook.billed,
                "the prompt message_start said was read, and the output counted so far -- once");
        // Mutation: report nothing from a stream that ends without a reply -> billed is empty,
        // and the call's 200,000 input tokens are counted nowhere.
    }

    @Test
    @DisplayName("a reply cut off, or ended by an error event, tells the hook what it was billed for; a whole reply tells it nothing")
    void onlyAnAttemptWithoutAReplyReports() {
        ObjectNode error = node("error");
        error.putObject("error").put("type", "invalid_request_error").put("message", "bad block");
        for (String stream : List.of(start("claude-opus-5", 5, 0, 7) + text(0, "half an ans"),
                start("claude-opus-5", 5, 0, 7) + text(0, "par") + ev(error))) {
            var hook = new StopsAt(0, null);
            assertThrows(LlmException.class, () -> provider(api(stream)).chat(ASK, new LlmRequestConfig(null, null, false).withProgress(hook)));
            assertEquals(List.of(new LlmResponse.Usage("claude-opus-5", 5, 1, 0, 7)), hook.billed);
        }

        var whole = new StopsAt(0, null);
        LlmResponse r = provider(api(start("claude-opus-5", 5, 0, 7) + text(0, "all") + end("end_turn", null, 3)))
                .chat(ASK, new LlmRequestConfig(null, null, false).withProgress(whole));
        assertEquals(List.of(new LlmResponse.Usage("claude-opus-5", 5, 3, 0, 7)), r.usage());
        assertTrue(whole.billed.isEmpty(), "its counts are the reply's, and counted once, from it");

        var early = new StopsAt(1, new Stopped());
        assertThrows(Stopped.class, () -> provider(api(start("claude-opus-5", 5, 0, 7) + text(0, "x")
                + end("end_turn", null, 1))).chat(ASK, new LlmRequestConfig(null, null, false).withProgress(early)));
        assertTrue(early.billed.isEmpty(), "stopped before message_start: the stream had said nothing");
    }

    @Test
    @DisplayName("a stop during the retry on a named model tells the hook of the refusal it would have returned, and of the retry's own counts")
    void aStoppedRetryReportsBothAttempts() {
        String declined = start("claude-opus-5", 900, 0, 0) + text(0, "Sure, the")
                + stop(refusal("cyber", "claude-opus-4-8"), outputTokens(3));
        var http = new FakeHttp().json(MODELS, 200, LIMITS).json(MODELS_4_8, 200, LIMITS_4_8)
                .on(MESSAGES, 200, "text/event-stream", declined)
                .on(MESSAGES, 200, "text/event-stream", start("claude-opus-4-8", 900, 0, 0) + text(0, "ok")
                        + end("end_turn", null, 1));
        var stop = new Stopped();
        var hook = new StopsAt(events(declined) + 2, stop);   // just after the retry's message_start

        assertSame(stop, assertThrows(Stopped.class, () -> provider(http).chat(ASK, new LlmRequestConfig(null, null, false).withProgress(hook))));

        assertEquals(List.of(usage("claude-opus-4-8", 900, 1), usage("claude-opus-5", 900, 3)), hook.billed,
                "the retry's counts so far, then the refusal the stop took with it");
        // Mutation: let the stop through without telling the hook of the refusal -> only the
        // retry's counts.
    }
}
