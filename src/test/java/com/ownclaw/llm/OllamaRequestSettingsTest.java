package com.ownclaw.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.config.SetupWizardService;
import com.ownclaw.observability.OpsService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every {@code /api/chat} this application sends carries the same context settings and streams:
 * the local provider's calls, the ops probe and the /setup speed sample. A request whose num_ctx
 * or shift differs from the loaded model's makes Ollama load the model again, which takes minutes
 * -- so a probe with settings of its own would stall the next real call -- and an unstreamed one
 * waits for the whole reply inside one read timeout, which a reply with no output limit can
 * outlast. The three senders build their bodies in their own classes, with their own HTTP
 * clients, so this runs them against a stub Ollama on the loopback interface and compares what
 * each one sent and what each one read.
 */
class OllamaRequestSettingsTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final String MODEL = "local-model:q4";
    /** Longer than any cut a reader might make, and streamed over several lines. */
    static final String PART = "The quick brown fox jumps over the lazy dog, twice and again. ";
    static final String REPLY = PART.repeat(6);

    private HttpServer server;
    private final List<JsonNode> chats = new ArrayList<>();

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    private static void respond(HttpExchange ex, String contentType, String body) throws IOException {
        byte[] out = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", contentType);
        ex.sendResponseHeaders(200, out.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(out); }
    }

    private String startOllama() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/tags", ex -> respond(ex, "application/json",
                "{\"models\":[{\"name\":\"" + MODEL + "\"}]}"));
        server.createContext("/api/ps", ex -> respond(ex, "application/json", "{\"models\":[]}"));
        server.createContext("/api/show", ex -> {
            ex.getRequestBody().readAllBytes();
            respond(ex, "application/json", "{\"template\":\"{{ .Messages }}\","
                    + "\"capabilities\":[\"completion\",\"tools\"],\"details\":{\"parameter_size\":\"35B\"},"
                    + "\"model_info\":{\"general.architecture\":\"qwen35moe\",\"qwen35moe.context_length\":262144}}");
        });
        server.createContext("/api/chat", ex -> {
            JsonNode body = JSON.readTree(ex.getRequestBody().readAllBytes());
            synchronized (this) { chats.add(body); }
            // Streamed, as every sender asks: the reply over three lines, then the counters.
            var lines = new StringBuilder();
            for (int i = 0; i < 3; i++) {
                lines.append("{\"message\":{\"role\":\"assistant\",\"content\":")
                        .append(JSON.writeValueAsString(PART + PART)).append("},\"done\":false}\n");
            }
            lines.append("{\"message\":{\"role\":\"assistant\",\"content\":\"\"},\"done\":true,\"done_reason\":\"stop\","
                    + "\"prompt_eval_count\":30,\"prompt_eval_duration\":1000000,\"eval_count\":2,\"eval_duration\":1000000}\n");
            respond(ex, "application/x-ndjson", lines.toString());
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Test
    @DisplayName("the provider, the ops probe and the /setup sample stream, with one num_ctx, truncate and shift off, no num_predict")
    void everySenderAgrees() throws Exception {
        var config = new OwnClawConfig();
        config.getExecutor().setUrl(startOllama() + "/");
        config.getExecutor().setModel(MODEL);
        var check = new LocalModelCheck(config, JSON);

        var answer = new OllamaProvider(config, JSON, check).chat(List.of(LlmMessage.user("hello")), new LlmRequestConfig(null, null, false));
        var probe = new OpsService(config, null, null, null, null, null, null, JSON, check).ollama();
        var welcome = new SetupWizardService(null, config, JSON, null, null, check).processStep(0, null);

        assertEquals(3, chats.size(), "one call each: " + chats);
        for (JsonNode body : chats) {
            assertTrue(body.path("stream").asBoolean(false), "streamed: " + body);
            assertEquals(262_144, body.path("options").path("num_ctx").asInt(), body.toString());
            assertFalse(body.path("options").has("num_predict"), body.toString());
            assertTrue(body.has("truncate") && !body.path("truncate").asBoolean(true), body.toString());
            assertTrue(body.has("shift") && !body.path("shift").asBoolean(true), body.toString());
        }
        assertEquals(MODEL, chats.get(1).path("model").asText());
        assertEquals(MODEL, chats.get(2).path("model").asText());

        // ...and each read the whole reply.
        assertEquals(REPLY, answer.content());
        assertEquals(REPLY, ((java.util.Map<?, ?>) probe.get("chatRoundTrip")).get("reply"),
                "the probe shows the reply whole, never a cut of it");
        assertTrue(welcome.message().contains("speed: 2000.0 tok/s"),
                "the sample read the streamed counters: " + welcome.message());
    }
}
