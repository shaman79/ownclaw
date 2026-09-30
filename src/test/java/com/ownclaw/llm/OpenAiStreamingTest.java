package com.ownclaw.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ownclaw.config.OwnClawConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The OpenAI provider as the Chat Completions API streams to it: {@code data:} chunks, a usage
 * chunk, then {@code [DONE]} -- scripted, through the real provider and its real HTTP client.
 */
class OpenAiStreamingTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final String COMPLETIONS = "/v1/chat/completions";
    static final String SERVED = "gpt-5.2-2025-12-11";

    static String data(ObjectNode chunk) {
        return "data: " + chunk + "\n\n";
    }

    static ObjectNode chunk() {
        return JSON.createObjectNode().put("id", "chatcmpl-1").put("object", "chat.completion.chunk")
                .put("model", SERVED);
    }

    /** One choice with this delta, and a finish reason or none. */
    static String choice(ObjectNode delta, String finishReason) {
        ObjectNode c = chunk();
        ObjectNode choice = c.putArray("choices").addObject().put("index", 0);
        choice.set("delta", delta);
        if (finishReason == null) choice.putNull("finish_reason"); else choice.put("finish_reason", finishReason);
        return data(c);
    }

    static String content(String text) {
        return choice(JSON.createObjectNode().put("content", text), null);
    }

    static String toolFragment(int index, String id, String name, String arguments) {
        ObjectNode delta = JSON.createObjectNode();
        ObjectNode call = delta.putArray("tool_calls").addObject().put("index", index);
        if (id != null) call.put("id", id).put("type", "function");
        ObjectNode fn = call.putObject("function");
        if (name != null) fn.put("name", name);
        fn.put("arguments", arguments);
        return choice(delta, null);
    }

    static String finish(String reason) {
        return choice(JSON.createObjectNode(), reason);
    }

    static String usage(int prompt, int completion, int cached) {
        ObjectNode c = chunk();
        c.putArray("choices");
        c.putObject("usage").put("prompt_tokens", prompt).put("completion_tokens", completion)
                .put("total_tokens", prompt + completion)
                .putObject("prompt_tokens_details").put("cached_tokens", cached);
        return data(c);
    }

    static final String DONE = "data: [DONE]\n\n";

    static OpenAiProvider provider(FakeHttp http) {
        var config = new OwnClawConfig();
        config.getMentor().setApiKey("test-key");
        config.getMentor().setModel("gpt-5.2");
        return new OpenAiProvider(config, JSON, http.client());
    }

    static FakeHttp api(String stream) {
        return new FakeHttp().on(COMPLETIONS, 200, "text/event-stream", stream);
    }

    static final List<LlmMessage> ASK = List.of(LlmMessage.system("S"), LlmMessage.user("hello"));

    @Test
    @DisplayName("text comes out whole, cached prompt tokens are split out, and the served model is kept")
    void textAndUsage() {
        String stream = choice(JSON.createObjectNode().put("role", "assistant").put("content", ""), null)
                + content("Hello") + content(", world") + finish("stop") + usage(1200, 30, 1024) + DONE;
        var http = api(stream);
        var seen = new AtomicInteger();

        LlmResponse r = provider(http).chat(ASK, LlmRequestConfig.DEFAULT.withProgress(seen::incrementAndGet));

        assertEquals("Hello, world", r.content());
        assertEquals(176, r.promptTokens(), "uncached only");
        assertEquals(1024, r.cacheReadTokens());
        assertEquals(30, r.completionTokens());
        assertEquals(1200, r.billedInputTokens(), "no token counted twice");
        assertEquals("stop", r.stopReason());
        assertEquals(SERVED, r.model());
        assertSame(r, r.requireComplete("openai"));
        assertEquals(6, seen.get(), "every chunk, [DONE] included");
        assertEquals(http.opened.get(), http.closed.get());
    }

    @Test
    @DisplayName("what goes out: a stream with usage, and no output limit at all")
    void theRequest() throws Exception {
        var http = api(content("ok") + finish("stop") + usage(1, 1, 0) + DONE);
        provider(http).chat(ASK, LlmRequestConfig.DEFAULT);
        JsonNode body = JSON.readTree(http.to(COMPLETIONS).get(0).body());
        assertTrue(body.path("stream").asBoolean());
        assertTrue(body.path("stream_options").path("include_usage").asBoolean());
        assertFalse(body.has("max_completion_tokens"), "the model may write up to its own maximum");
        assertFalse(body.has("max_tokens"));
    }

    @Test
    @DisplayName("a tool call's arguments, streamed as fragments of a JSON string, are parsed whole")
    void fragmentedToolCall() {
        var http = api(toolFragment(0, "call_1", "shell_exec", "")
                + toolFragment(0, null, null, "{\"comm")
                + toolFragment(0, null, null, "and\": \"ls -la\", \"cwd\"")
                + toolFragment(0, null, null, ": \"/srv\"}")
                + finish("tool_calls") + usage(10, 5, 0) + DONE);

        LlmResponse r = provider(http).chat(ASK, LlmRequestConfig.DEFAULT);

        assertEquals(1, r.toolCalls().size());
        ToolCall call = r.toolCalls().get(0);
        assertEquals("call_1", call.id());
        assertEquals("shell_exec", call.name());
        assertEquals(Map.of("command", "ls -la", "cwd", "/srv"), call.arguments());
    }

    @Test
    @DisplayName("finish_reason length: cut off at the model's output limit, whose size OpenAI does not state")
    void length() {
        var http = api(content("a long answer tha") + finish("length") + usage(10, 5, 0) + DONE);
        LlmResponse r = provider(http).chat(ASK, LlmRequestConfig.DEFAULT);
        var e = assertThrows(OutputTruncated.class, () -> r.requireComplete("openai"));
        assertEquals(OutputTruncated.Limit.MAX_OUTPUT, e.limit());
        assertNull(e.tokens());
        assertEquals("[openai] the reply reached the model's maximum output and was cut off", e.getMessage());
    }

    @Test
    @DisplayName("finish_reason content_filter is a refusal")
    void contentFilter() {
        var http = api(finish("content_filter") + usage(10, 0, 0) + DONE);
        LlmResponse r = provider(http).chat(ASK, LlmRequestConfig.DEFAULT);
        assertTrue(r.refused());
        var e = assertThrows(ProviderRefused.class, () -> r.requireComplete("openai"));
        assertEquals("content_filter", e.stopReason());
        assertNull(e.category());
    }

    @Test
    @DisplayName("an error chunk mid-stream is an LlmException, not a short answer")
    void errorChunk() {
        ObjectNode error = JSON.createObjectNode();
        error.putObject("error").put("message", "the stream broke").put("type", "invalid_request_error");
        var http = api(content("par") + data(error));
        var e = assertThrows(LlmException.class, () -> provider(http).chat(ASK, LlmRequestConfig.DEFAULT));
        assertTrue(e.getMessage().contains("invalid_request_error: the stream broke"), e.getMessage());
        assertFalse(e.isRetryable());
    }

    @Test
    @DisplayName("a stream that ends before [DONE] is incomplete")
    void cutStream() {
        var http = api(content("half"));
        var e = assertThrows(LlmException.class, () -> provider(http).chat(ASK, LlmRequestConfig.DEFAULT));
        assertTrue(e.getMessage().contains("before [DONE]"), e.getMessage());
    }

    @Test
    @DisplayName("a prompt longer than the context window is the plain context-window message")
    void contextLengthExceeded() {
        var http = new FakeHttp().json(COMPLETIONS, 400, "{\"error\":{\"message\":\"This model's maximum context "
                + "length is 400000 tokens.\",\"type\":\"invalid_request_error\",\"code\":\"context_length_exceeded\"}}");
        var e = assertThrows(OutputTruncated.class, () -> provider(http).chat(ASK, LlmRequestConfig.DEFAULT));
        assertEquals(OutputTruncated.Limit.CONTEXT_WINDOW, e.limit());
        assertEquals("[openai] the conversation is longer than the model's context window", e.getMessage());
    }

    static final class Stopped extends RuntimeException {}

    @Test
    @DisplayName("what the progress hook throws reaches the caller unchanged, and the stream is closed")
    void theHookCanStopTheCall() {
        var http = api(content("a") + content("b") + content("c") + finish("stop") + usage(1, 3, 0) + DONE);
        var stop = new Stopped();
        var seen = new AtomicInteger();
        var thrown = assertThrows(Stopped.class, () -> provider(http).chat(ASK,
                LlmRequestConfig.DEFAULT.withProgress(() -> { if (seen.incrementAndGet() == 2) throw stop; })));
        assertSame(stop, thrown);
        assertEquals(2, seen.get());
        assertEquals(1, http.opened.get());
        assertEquals(1, http.closed.get());
    }

    @Test
    @DisplayName("the tools are offered one call at a time, as before")
    void tools() throws Exception {
        var http = api(finish("stop") + usage(1, 1, 0) + DONE);
        provider(http).chat(ASK, LlmRequestConfig.DEFAULT.withTools(List.of(
                new ToolSpec("shell_exec", "run", Map.of("type", "object")))));
        JsonNode body = JSON.readTree(http.to(COMPLETIONS).get(0).body());
        assertEquals("shell_exec", ((ArrayNode) body.path("tools")).get(0).path("function").path("name").asText());
        assertFalse(body.path("parallel_tool_calls").asBoolean(true));
    }
}
