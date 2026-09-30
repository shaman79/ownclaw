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

    static int events(String stream) {
        return stream.split("\nevent: ", -1).length;   // the first event has no newline before it
    }

    // ── the provider, over a fake network ──

    static FakeHttp api(String stream) {
        return new FakeHttp().json(MODELS, 200, LIMITS).on(MESSAGES, 200, "text/event-stream", stream);
    }

    static AnthropicProvider provider(FakeHttp http) {
        var config = new OwnClawConfig();
        config.getMentor().setAnthropicApiKey("test-key");
        config.getMentor().setAnthropicModel("claude-opus-5");
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

        LlmResponse r = provider(http).chat(ASK, LlmRequestConfig.DEFAULT.withProgress(seen::incrementAndGet));

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
        provider(http).chat(ASK, LlmRequestConfig.DEFAULT.withTools(TOOLS));

        var sent = http.to(MESSAGES).get(0);
        assertEquals(AnthropicProvider.FALLBACK_BETA, sent.header("anthropic-beta"));
        assertEquals("server-side-fallback-2026-07-01", AnthropicProvider.FALLBACK_BETA);
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

        LlmResponse r = provider(http).chat(ASK, LlmRequestConfig.DEFAULT.withTools(TOOLS));

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
        assertEquals(Map.of(), provider(http).chat(ASK, LlmRequestConfig.DEFAULT).toolCalls().get(0).arguments());
    }

    @Test
    @DisplayName("a complete reply whose tool input is not valid JSON is an error, never a call with guessed arguments")
    void malformedToolInput() {
        var http = api(start("claude-opus-5", 10, 0, 0)
                + tool(0, "toolu_3", "shell_exec", "{\"command\": \"say \"hi\"\"}") + end("tool_use", null, 9));
        var e = assertThrows(LlmException.class, () -> provider(http).chat(ASK, LlmRequestConfig.DEFAULT));
        assertFalse(e instanceof OutputTruncated || e instanceof ProviderRefused);
        assertTrue(e.getMessage().contains("'shell_exec'"), e.getMessage());

        // Strict: a valid object followed by anything else is not read as the object alone.
        var trailing = api(start("claude-opus-5", 10, 0, 0)
                + tool(0, "toolu_5", "shell_exec", "{\"command\": \"ls\"} {\"command\": \"rm -rf /srv\"}")
                + end("tool_use", null, 9));
        assertThrows(LlmException.class, () -> provider(trailing).chat(ASK, LlmRequestConfig.DEFAULT));
    }

    @Test
    @DisplayName("a tool input cut off at the output limit is reported as the limit, not as bad JSON")
    void toolInputCutAtTheLimit() {
        var http = api(start("claude-opus-5", 10, 0, 0)
                + tool(0, "toolu_4", "write_file", "{\"path\": \"/tmp/x\", \"content\": \"line 1\\nline")
                + end("max_tokens", null, 128_000));
        LlmResponse r = provider(http).chat(ASK, LlmRequestConfig.DEFAULT);
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
        LlmResponse r = provider(http).chat(ASK, LlmRequestConfig.DEFAULT);

        assertTrue(r.refused());
        assertEquals("cyber", r.stopDetail());
        assertEquals("refusal (cyber)", r.stopDescription());
        var e = assertThrows(ProviderRefused.class, () -> r.requireComplete("anthropic"));
        assertEquals("refusal", e.stopReason());
        assertEquals("cyber", e.category());
        assertEquals("[anthropic] the model declined this request (stop reason: refusal, category: cyber)",
                e.getMessage());
    }

    @Test
    @DisplayName("a refusal with no category is still a refusal")
    void refusalWithoutCategory() {
        var http = api(start("claude-opus-5", 0, 0, 0) + end("refusal", null, 0));
        LlmResponse r = provider(http).chat(ASK, LlmRequestConfig.DEFAULT);
        assertEquals("refusal", r.stopDescription());
        assertNull(assertThrows(ProviderRefused.class, () -> r.requireComplete("anthropic")).category());
    }

    @Test
    @DisplayName("the context window filling up is named as the window, with its size")
    void contextWindowExceeded() {
        var http = api(start("claude-opus-5", 999_000, 0, 0) + text(0, "partial") + end("model_context_window_exceeded", null, 1000));
        LlmResponse r = provider(http).chat(ASK, LlmRequestConfig.DEFAULT);
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
        var e = assertThrows(OutputTruncated.class, () -> provider(http).chat(ASK, LlmRequestConfig.DEFAULT));
        assertEquals(OutputTruncated.Limit.CONTEXT_WINDOW, e.limit());
        assertEquals(1_000_000, e.tokens());
        assertNull(e.reply(), "refused before any reply");
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

        LlmResponse r = provider(http).chat(ASK, LlmRequestConfig.DEFAULT);

        assertEquals("Here is the plan: first check the logs.", r.content(),
                "the fallback model continues the declined model's text");
        assertEquals(1, r.toolCalls().size());
        assertEquals("toolu_fallback", r.toolCalls().get(0).id(), "the declined model's call is not the reply's");
        assertEquals("claude-opus-4-8", r.model(), "the model that finished the reply");
    }

    @Test
    @DisplayName("a decline before any output: message_start already names the model that answered")
    void fallbackBeforeOutput() {
        ObjectNode fallback = node("fallback");
        fallback.putObject("from").put("model", "claude-opus-5");
        fallback.putObject("to").put("model", "claude-opus-4-8");
        var http = api(start("claude-opus-4-8", 50, 0, 0) + blockStart(0, fallback) + blockStop(0)
                + text(1, "answer") + end("end_turn", null, 3));
        LlmResponse r = provider(http).chat(ASK, LlmRequestConfig.DEFAULT);
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
                () -> provider(http).chat(ASK, LlmRequestConfig.DEFAULT.withProgress(seen::incrementAndGet)));

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
        var e = assertThrows(LlmException.class, () -> provider(http).chat(ASK, LlmRequestConfig.DEFAULT));
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

        var thrown = assertThrows(Stopped.class, () -> provider(http).chat(ASK, LlmRequestConfig.DEFAULT.withProgress(hook)));

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

        var e = assertThrows(LlmException.class, () -> provider.chat(ASK, LlmRequestConfig.DEFAULT));
        assertEquals(404, e.getHttpStatus());
        assertTrue(e.getMessage().contains("Models API"), e.getMessage());
        assertTrue(http.to(MESSAGES).isEmpty(), "no call is made without the model's limits");

        assertEquals("ok", provider.chat(ASK, LlmRequestConfig.DEFAULT).content(), "the next call asks again");
        assertEquals("ok", provider.chat(ASK, LlmRequestConfig.DEFAULT).content());
        assertEquals(2, http.to(MODELS).size(), "and once it has an answer, keeps it");
        assertEquals(2, http.to(MESSAGES).size());
    }

    @Test
    @DisplayName("a Models API answer without the limits fails the call: there is no table to guess from")
    void modelsLookupWithoutLimits() {
        var http = new FakeHttp().json(MODELS, 200, "{\"id\":\"claude-opus-5\"}");
        var e = assertThrows(LlmException.class, () -> provider(http).chat(ASK, LlmRequestConfig.DEFAULT));
        assertTrue(e.getMessage().contains("max_tokens"), e.getMessage());
    }
}
