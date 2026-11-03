package com.ownclaw.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownclaw.config.OwnClawConfig;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every local call sends the settings for how the model chooses each next piece of its reply,
 * whole: the set for a reply it reasons on, or for one it gives straight away -- and never a
 * temperature a caller chose for the cloud. Sent to a stub Ollama on the loopback interface and
 * read back from the request it received.
 */
class OllamaSamplingTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final String MODEL = "local-model:q4";

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

    private OllamaProvider provider(OwnClawConfig config) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/tags", ex -> respond(ex, "application/json",
                "{\"models\":[{\"name\":\"" + MODEL + "\"}]}"));
        server.createContext("/api/ps", ex -> respond(ex, "application/json", "{\"models\":[]}"));
        server.createContext("/api/show", ex -> {
            ex.getRequestBody().readAllBytes();
            respond(ex, "application/json", "{\"template\":\"{{ .Messages }}\","
                    + "\"capabilities\":[\"completion\",\"tools\",\"thinking\"],\"details\":{\"parameter_size\":\"35B\"},"
                    + "\"model_info\":{\"general.architecture\":\"qwen35moe\",\"qwen35moe.context_length\":262144}}");
        });
        server.createContext("/api/chat", ex -> {
            JsonNode body = JSON.readTree(ex.getRequestBody().readAllBytes());
            synchronized (this) { chats.add(body); }
            respond(ex, "application/x-ndjson", "{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"done\":true,"
                    + "\"done_reason\":\"stop\",\"prompt_eval_count\":3,\"eval_count\":1}\n");
        });
        server.start();
        config.getExecutor().setUrl("http://127.0.0.1:" + server.getAddress().getPort());
        config.getExecutor().setModel(MODEL);
        return new OllamaProvider(config, JSON, new LocalModelCheck(config, JSON));
    }

    /** The sampling options of the last request, without num_ctx, which streamChat adds. */
    private Map<String, Object> sampling() {
        @SuppressWarnings("unchecked")
        Map<String, Object> options = JSON.convertValue(chats.getLast().path("options"), Map.class);
        options.remove("num_ctx");
        return options;
    }

    @Test
    @DisplayName("a reply reasoned on is sent the thinking settings whole, a caller's temperature not among them")
    void aReasonedReplyGetsTheThinkingSettings() throws Exception {
        var ollama = provider(new OwnClawConfig());
        var hello = List.of(LlmMessage.user("hello"));

        ollama.chat(hello, new LlmRequestConfig(null, null, false));
        assertEquals(Map.of("temperature", 1.0, "top_p", 0.95, "top_k", 20, "min_p", 0.0, "presence_penalty", 1.5),
                sampling(), "the publisher's thinking-mode settings: " + chats.getLast());
        assertFalse(chats.getLast().has("think"), "the model reasons first, as it does by default");

        ollama.chat(hello, new LlmRequestConfig(null, 0.2, false));
        assertEquals(1.0, sampling().get("temperature"),
                "0.2 is code generation's choice for the cloud; at 0.3 this model looped");
        // Mutation: send the request's temperature when there is one -> 0.2 here.
    }

    @Test
    @DisplayName("a reply given straight away -- asked for, or at thinking effort low -- is sent the answering settings")
    void aDirectReplyGetsTheAnsweringSettings() throws Exception {
        var ollama = provider(new OwnClawConfig());
        var hello = List.of(LlmMessage.user("hello"));
        var answering = Map.<String, Object>of("temperature", 0.7, "top_p", 0.8, "top_k", 20, "min_p", 0.0,
                "presence_penalty", 1.5);

        ollama.chat(hello, new LlmRequestConfig(null, null, false).answeringDirectly());
        assertEquals(answering, sampling());
        assertFalse(chats.getLast().path("think").asBoolean(true));

        ollama.chat(hello, new LlmRequestConfig(null, null, false).withEffort("low"));
        assertEquals(answering, sampling());

        ollama.chat(hello, new LlmRequestConfig(null, null, false).withEffort("high"));
        assertEquals(1.0, sampling().get("temperature"), "high reasons first: the thinking settings");
    }

    @Test
    @DisplayName("application.yaml's settings bind to the configuration, kebab-case names and all")
    void theYamlBinds() throws Exception {
        var sources = new org.springframework.boot.env.YamlPropertySourceLoader().load("application.yaml",
                new org.springframework.core.io.ClassPathResource("application.yaml"));
        var executor = new org.springframework.boot.context.properties.bind.Binder(
                org.springframework.boot.context.properties.source.ConfigurationPropertySources.from(sources))
                .bind("ownclaw.executor", OwnClawConfig.Executor.class)
                .get();
        var thinking = new com.fasterxml.jackson.databind.node.ObjectNode(JSON.getNodeFactory());
        executor.getThinking().into(thinking);
        var answering = new com.fasterxml.jackson.databind.node.ObjectNode(JSON.getNodeFactory());
        executor.getAnswering().into(answering);
        assertEquals("{\"temperature\":1.0,\"top_p\":0.95,\"top_k\":20,\"min_p\":0.0,\"presence_penalty\":1.5}",
                thinking.toString());
        assertEquals("{\"temperature\":0.7,\"top_p\":0.8,\"top_k\":20,\"min_p\":0.0,\"presence_penalty\":1.5}",
                answering.toString());
    }

    @Test
    @DisplayName("the settings are the configuration's: a value set there is sent, one left empty is not")
    void theSettingsAreConfigured() throws Exception {
        var config = new OwnClawConfig();
        config.getExecutor().setThinking(new OwnClawConfig.Sampling(0.9, null, 40, null, 1.0));
        var ollama = provider(config);

        ollama.chat(List.of(LlmMessage.user("hello")), new LlmRequestConfig(null, null, false));
        assertEquals(Map.of("temperature", 0.9, "top_k", 40, "presence_penalty", 1.0), sampling(),
                "no top_p or min_p: the model's own defaults apply to those");
    }
}
