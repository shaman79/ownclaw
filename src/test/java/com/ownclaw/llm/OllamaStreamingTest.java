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
 * The Ollama provider as {@code /api/chat} streams to it: one JSON object per line, the last with
 * {@code "done": true}. The context window it sends is the model's own, read from
 * {@code /api/show} by {@link LocalModelCheck} -- both answered here by a fake network.
 */
class OllamaStreamingTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final String URL = "http://ollama.example.org:11434";
    static final String SHOW = "/api/show";
    static final String CHAT = "/api/chat";
    static final String MODEL = "local-model:q4";

    static String show(String architecture, int contextLength) {
        ObjectNode s = JSON.createObjectNode().put("template", "{{ .Messages }}");
        s.putArray("capabilities").add("completion").add("tools").add("thinking");
        s.putObject("model_info").put("general.architecture", architecture)
                .put(architecture + ".context_length", contextLength);
        return s.toString();
    }

    static String line(String content, String thinking) {
        ObjectNode l = JSON.createObjectNode().put("model", MODEL).put("created_at", "2026-09-30T08:00:00Z");
        ObjectNode m = l.putObject("message").put("role", "assistant").put("content", content);
        if (thinking != null) m.put("thinking", thinking);
        return l.put("done", false) + "\n";
    }

    static String toolLine(String name, Map<String, Object> arguments) {
        ObjectNode l = JSON.createObjectNode().put("model", MODEL);
        ObjectNode m = l.putObject("message").put("role", "assistant").put("content", "");
        ObjectNode fn = m.putArray("tool_calls").addObject().putObject("function").put("name", name);
        fn.set("arguments", JSON.valueToTree(arguments));
        return l.put("done", false) + "\n";
    }

    static String last(String doneReason, int prompt, int eval) {
        ObjectNode l = JSON.createObjectNode().put("model", MODEL);
        l.putObject("message").put("role", "assistant").put("content", "");
        return l.put("done", true).put("done_reason", doneReason).put("prompt_eval_count", prompt)
                .put("prompt_eval_duration", 1_000_000).put("eval_count", eval).put("eval_duration", 2_000_000)
                + "\n";
    }

    static OwnClawConfig config() {
        var config = new OwnClawConfig();
        config.getExecutor().setUrl(URL);
        config.getExecutor().setModel(MODEL);
        return config;
    }

    static OllamaProvider provider(OwnClawConfig config, FakeHttp http) {
        var check = new LocalModelCheck(config, JSON, http.client());
        return new OllamaProvider(config, JSON, check, http.client());
    }

    static FakeHttp ollama(String stream) {
        return new FakeHttp().json(SHOW, 200, show("qwen35moe", 262_144))
                .on(CHAT, 200, "application/x-ndjson", stream);
    }

    static final List<LlmMessage> ASK = List.of(LlmMessage.system("S"), LlmMessage.user("2+2?"));

    @Test
    @DisplayName("what goes out: the model's own context window, truncate and shift off, and no num_predict")
    void theRequest() throws Exception {
        var http = ollama(line("4", null) + last("stop", 20, 1));
        provider(config(), http).chat(ASK, new LlmRequestConfig(null, null, false));

        JsonNode body = JSON.readTree(http.to(CHAT).get(0).body());
        assertTrue(body.path("stream").asBoolean());
        assertEquals(262_144, body.path("options").path("num_ctx").asInt(),
                "qwen35moe.context_length from /api/show");
        assertFalse(body.path("options").has("num_predict"), "no output limit, ever");
        assertTrue(body.has("truncate") && !body.path("truncate").asBoolean(true),
                "Ollama must not drop the oldest messages silently");
        assertTrue(body.has("shift") && !body.path("shift").asBoolean(true));
        assertEquals(-1, body.path("keep_alive").asInt());
        assertFalse(body.has("think"), "the model's own default unless a call asks to answer directly");
    }

    @Test
    @DisplayName("a call that asks to answer directly sends think:false")
    void answeringDirectly() throws Exception {
        var http = ollama(line("4", null) + last("stop", 20, 1));
        provider(config(), http).chat(ASK, new LlmRequestConfig(null, null, false).answeringDirectly());
        JsonNode body = JSON.readTree(http.to(CHAT).get(0).body());
        assertTrue(body.has("think") && !body.path("think").asBoolean(true), body.toString());
    }

    @Test
    @DisplayName("the answer comes out whole, reasoning is not part of it, and the counters are kept")
    void answerAndCounts() {
        String stream = line("", "Two and ") + line("", "two.") + line("The answer", null) + line(" is 4.", null)
                + last("stop", 26, 12);
        var http = ollama(stream);
        var seen = new AtomicInteger();

        LlmResponse r = provider(config(), http).chat(ASK, new LlmRequestConfig(null, null, false).withProgress(seen::incrementAndGet));

        assertEquals("The answer is 4.", r.content());
        assertEquals(26, r.promptTokens());
        assertEquals(12, r.completionTokens());
        assertEquals("stop", r.stopReason());
        assertEquals(MODEL, r.model());
        assertEquals(262_144, r.contextWindow());
        assertNull(r.maxOutputTokens(), "no output limit was sent");
        assertEquals(5, seen.get(), "the hook hears every line");
        assertEquals(http.opened.get(), http.closed.get());
    }

    @Test
    @DisplayName("tool calls arrive whole, as objects, and pass the same strict parse")
    void toolCalls() {
        var http = ollama(toolLine("shell_exec", Map.of("command", "uptime")) + last("stop", 30, 8));
        LlmResponse r = provider(config(), http).chat(ASK, new LlmRequestConfig(null, null, false));
        assertEquals(1, r.toolCalls().size());
        assertEquals("shell_exec", r.toolCalls().get(0).name());
        assertEquals(Map.of("command", "uptime"), r.toolCalls().get(0).arguments());
    }

    @Test
    @DisplayName("done_reason length is the context window filling up, and the local path refuses it itself")
    void lengthIsTheWindow() {
        var http = ollama(line("", "thinking and thinking") + last("length", 200_000, 62_144));
        var e = assertThrows(OutputTruncated.class,
                () -> provider(config(), http).chat(ASK, new LlmRequestConfig(null, null, false)));
        assertEquals(OutputTruncated.Limit.CONTEXT_WINDOW, e.limit());
        assertEquals(262_144, e.tokens());
        assertEquals("[ollama] the conversation is longer than the model's 262,144-token context window",
                e.getMessage());
    }

    @Test
    @DisplayName("a prompt longer than num_ctx -- an error with truncate off -- is the plain context-window message")
    void promptTooLong() {
        var http = new FakeHttp().json(SHOW, 200, show("qwen35moe", 262_144)).json(CHAT, 400,
                "{\"error\":\"exceed_context_size_error: request (270000 tokens) exceeds the available context "
                        + "size (262144 tokens)\"}");
        var e = assertThrows(OutputTruncated.class,
                () -> provider(config(), http).chat(ASK, new LlmRequestConfig(null, null, false)));
        assertEquals(262_144, e.tokens());

        var other = new FakeHttp().json(SHOW, 200, show("qwen35moe", 262_144)).json(CHAT, 400,
                "{\"error\":\"the input length exceeds the context length\"}");
        assertThrows(OutputTruncated.class, () -> provider(config(), other).chat(ASK, new LlmRequestConfig(null, null, false)));
    }

    @Test
    @DisplayName("an error line mid-stream is an LlmException, and a stream with no last line is incomplete")
    void brokenStreams() {
        var http = ollama(line("par", null) + "{\"error\":\"model runner has unexpectedly stopped\"}\n");
        var e = assertThrows(LlmException.class, () -> provider(config(), http).chat(ASK, new LlmRequestConfig(null, null, false)));
        assertTrue(e.getMessage().contains("unexpectedly stopped"), e.getMessage());
        assertFalse(e instanceof OutputTruncated);

        var cut = ollama(line("half", null));
        var e2 = assertThrows(LlmException.class, () -> provider(config(), cut).chat(ASK, new LlmRequestConfig(null, null, false)));
        assertTrue(e2.getMessage().contains("\"done\": true"), e2.getMessage());
    }

    static final class Stopped extends RuntimeException {}

    @Test
    @DisplayName("what the progress hook throws reaches the caller unchanged, and the stream is closed")
    void theHookCanStopTheCall() {
        var http = ollama(line("a", null) + line("b", null) + line("c", null) + last("stop", 1, 3));
        var stop = new Stopped();
        var seen = new AtomicInteger();
        var thrown = assertThrows(Stopped.class, () -> provider(config(), http).chat(ASK,
                new LlmRequestConfig(null, null, false).withProgress(() -> { if (seen.incrementAndGet() == 2) throw stop; })));
        assertSame(stop, thrown);
        assertEquals(2, seen.get());
        assertEquals(http.opened.get(), http.closed.get(), "the /api/show answer and the stream, both closed");
    }

    @Test
    @DisplayName("each model's window is read once, for the model the call is made with")
    void windowPerModel() throws Exception {
        var http = new FakeHttp()
                .json(SHOW, 200, show("qwen35moe", 262_144))
                .json(SHOW, 200, show("llama", 32_768))
                .on(CHAT, 200, "application/x-ndjson", line("ok", null) + last("stop", 1, 1));
        var provider = provider(config(), http);

        provider.chat(ASK, new LlmRequestConfig(null, null, false));
        provider.chat(ASK, new LlmRequestConfig("small-model:latest", null, false));
        provider.chat(ASK, new LlmRequestConfig(null, null, false));

        var chats = http.to(CHAT);
        assertEquals(262_144, JSON.readTree(chats.get(0).body()).path("options").path("num_ctx").asInt());
        assertEquals(32_768, JSON.readTree(chats.get(1).body()).path("options").path("num_ctx").asInt());
        assertEquals(262_144, JSON.readTree(chats.get(2).body()).path("options").path("num_ctx").asInt());
        assertEquals(2, http.to(SHOW).size(), "the first model's window was kept, not read again");
    }

    @Test
    @DisplayName("a window that cannot be read fails the call, and the next call reads it again")
    void windowUnknown() {
        var http = new FakeHttp()
                .json(SHOW, 500, "{\"error\":\"busy\"}")
                .json(SHOW, 200, show("qwen35moe", 262_144))
                .on(CHAT, 200, "application/x-ndjson", line("ok", null) + last("stop", 1, 1));
        var provider = provider(config(), http);

        var e = assertThrows(LlmException.class, () -> provider.chat(ASK, new LlmRequestConfig(null, null, false)));
        assertTrue(e.getMessage().contains("context window"), e.getMessage());
        assertTrue(http.to(CHAT).isEmpty(), "nothing is sent without the window");
        assertEquals("ok", provider.chat(ASK, new LlmRequestConfig(null, null, false)).content());

        var noLength = new FakeHttp().json(SHOW, 200, "{\"template\":\"{{ .Messages }}\",\"model_info\":{}}");
        var e2 = assertThrows(LlmException.class, () -> provider(config(), noLength).chat(ASK, new LlmRequestConfig(null, null, false)));
        assertTrue(e2.getMessage().contains("no context length"), e2.getMessage());
    }

    @Test
    @DisplayName("a model the startup check substituted is sent its own window, read by that same check")
    void substituteGetsItsOwnWindow() throws Exception {
        var config = config();
        config.getExecutor().setModel("never-pulled:latest");
        var http = new FakeHttp()
                .json("/api/tags", 200, "{\"models\":[{\"name\":\"good:latest\"}]}")
                .json(SHOW, 200, show("gemma3", 131_072))
                .on(CHAT, 200, "application/x-ndjson", line("ok", null) + last("stop", 1, 1));
        var check = new LocalModelCheck(config, JSON, http.client());
        check.check();
        assertEquals("good:latest", config.getExecutor().getModel());

        new OllamaProvider(config, JSON, check, http.client()).chat(ASK, new LlmRequestConfig(null, null, false));

        JsonNode body = JSON.readTree(http.to(CHAT).get(0).body());
        assertEquals("good:latest", body.path("model").asText());
        assertEquals(131_072, body.path("options").path("num_ctx").asInt());
        assertEquals(1, http.to(SHOW).size(), "the check's own /api/show supplied it");
    }

    @Test
    @DisplayName("a server URL written with a trailing slash or a /v1 suffix works for every call: tags, show and chat")
    void aLooseUrlWorksEverywhere() {
        var config = config();
        config.getExecutor().setUrl(URL + "/v1/");
        var http = new FakeHttp()
                .json("/api/tags", 200, "{\"models\":[{\"name\":\"" + MODEL + "\"}]}")
                .json(SHOW, 200, show("qwen35moe", 262_144))
                .on(CHAT, 200, "application/x-ndjson", line("ok", null) + last("stop", 1, 1));
        var check = new LocalModelCheck(config, JSON, http.client());
        var status = check.status();
        assertTrue(status.ok(), status.detail());
        var provider = new OllamaProvider(config, JSON, check, http.client());
        assertTrue(provider.isAvailable());
        assertEquals("ok", provider.chat(ASK, new LlmRequestConfig(null, null, false)).content());
        assertEquals(List.of("/api/tags", "/api/show", "/api/tags", "/api/chat"),
                http.sent.stream().map(s -> s.request().url().encodedPath()).toList());

        assertEquals("http://ollama.example.org:11434", OllamaProvider.baseUrl("  http://ollama.example.org:11434//  "));
        assertEquals("http://ollama.example.org:11434", OllamaProvider.baseUrl("http://ollama.example.org:11434/v1"));
        assertEquals("http://ollama.example.org:11434/api/show",
                OllamaProvider.endpoint("http://ollama.example.org:11434/", "/api/show"));
    }

    @Test
    @DisplayName("the silence allowed before Ollama's first line covers a cold load and a prompt that fills the window")
    void theFirstLineMayBeLongInComing() throws Exception {
        // Ollama writes nothing until the model is loaded and the whole prompt read. Measured on
        // the production host: about 3 minutes to load, about 100 prompt tokens a second with the
        // model's 262,144-token window.
        var field = OllamaProvider.class.getDeclaredField("httpClient");
        field.setAccessible(true);
        long allowedMs = ((okhttp3.OkHttpClient) field.get(new OllamaProvider(config(), JSON, null))).readTimeoutMillis();
        long neededMs = (3 * 60 + 262_144 / 100) * 1000L;
        assertTrue(allowedMs >= neededMs, "a prompt that fits the window must not fail as a timeout: "
                + allowedMs + " ms allowed, " + neededMs + " ms needed");
    }

    @Test
    @DisplayName("a server that takes the connection and never answers is unavailable in the check's seconds, not the chat's hour")
    void aWedgedServerIsUnavailableInSeconds() throws Exception {
        // A delegation and every task with a file ask first, on the task's thread, and no hook
        // holds the probe's cancel: a probe that waits as a chat does holds everything behind it.
        try (var ollama = new WedgedOllama()) {
            long t0 = System.currentTimeMillis();
            boolean available = assertTimeoutPreemptively(java.time.Duration.ofSeconds(10),
                    () -> ollama.provider().isAvailable(), "the probe waited as a chat waits for its first line");
            assertFalse(available);
            assertTrue(System.currentTimeMillis() - t0 < 5_000, "the check's timeout, not the chat client's");
        }
        // ...and the check's own client, as production builds it, gives up in seconds.
        var field = LocalModelCheck.class.getDeclaredField("http");
        field.setAccessible(true);
        var http = (okhttp3.OkHttpClient) field.get(new LocalModelCheck(config(), JSON));
        assertTrue(http.connectTimeoutMillis() + http.readTimeoutMillis() <= 30_000,
                "a server that has stopped answering costs a probe seconds: " + http.connectTimeoutMillis()
                        + " + " + http.readTimeoutMillis() + " ms");
        // Mutation: probe with the provider's own client -> the probe waits up to an hour, and the
        // preemptive timeout fails the test.
    }

    @Test
    @DisplayName("the window is <general.architecture>.context_length, or nothing")
    void contextLengthIn() throws Exception {
        assertEquals(262_144, LocalModelCheck.contextLengthIn(JSON.readTree(show("qwen35moe", 262_144))));
        assertNull(LocalModelCheck.contextLengthIn(JSON.readTree("{\"model_info\":{\"llama.context_length\":8192}}")),
                "without the architecture there is no knowing which key is the window");
        assertNull(LocalModelCheck.contextLengthIn(JSON.readTree("{}")));
    }
}
