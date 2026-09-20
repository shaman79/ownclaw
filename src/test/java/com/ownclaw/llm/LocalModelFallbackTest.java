package com.ownclaw.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownclaw.config.OwnClawConfig;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What the system does when the model it was told to use cannot be driven.
 * <p>
 * The answer used to be: log an error and carry on failing every local call. That is how this
 * deployment spent months with a dead local tier while a model that worked sat installed on the
 * same host. These tests run against a stub Ollama rather than mocks of our own classes, because
 * the thing being checked is how real {@code /api/tags} and {@code /api/show} payloads are
 * interpreted — the shapes below are the ones this deployment actually returned.
 */
class LocalModelFallbackTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    // ── stub Ollama ──────────────────────────────────────────────────────────

    /** A model as /api/show describes it. */
    private record Stub(String template, java.util.List<String> capabilities) {}

    /** The configured GGUF on this deployment before the Ollama upgrade: no usable template. */
    private static Stub noTemplate() {
        return new Stub("{{ .Prompt }}", java.util.List.of("completion"));
    }

    /** A model Ollama can drive: a Jinja chat template out of the GGUF metadata. */
    private static Stub jinjaChat() {
        return new Stub("{%- if messages %}{{ add_generation_prompt }}{%- endif %}",
                java.util.List.of("tools", "thinking", "completion"));
    }

    /** Renders messages, but advertises no tool use. */
    private static Stub goTemplateOnly() {
        return new Stub("{{ .System }} {{ .Prompt }}", java.util.List.of("completion"));
    }

    private static Stub embedding() {
        return new Stub("", java.util.List.of("embedding"));
    }

    private String startOllama(Map<String, Stub> models) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/tags", ex -> {
            var list = new java.util.ArrayList<Map<String, Object>>();
            for (String name : models.keySet()) list.add(Map.of("name", name));
            respond(ex, mapper.writeValueAsString(Map.of("models", list)));
        });
        server.createContext("/api/show", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String want = mapper.readTree(body).path("model").asText();
            Stub stub = models.get(want);
            if (stub == null) {
                respond(ex, 404, "{\"error\":\"model not found\"}");
                return;
            }
            respond(ex, mapper.writeValueAsString(
                    Map.of("template", stub.template(), "capabilities", stub.capabilities())));
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void respond(com.sun.net.httpserver.HttpExchange ex, String body) throws IOException {
        respond(ex, 200, body);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange ex, int code, String body) throws IOException {
        byte[] out = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(code, out.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(out); }
    }

    private LocalModelCheck checkWith(String url, String configuredModel, OwnClawConfig config) {
        config.getExecutor().setUrl(url);
        config.getExecutor().setModel(configuredModel);
        return new LocalModelCheck(config, new ObjectMapper());
    }

    // ── the behaviour ────────────────────────────────────────────────────────

    @Test
    @DisplayName("an undriveable model is replaced by one that works")
    void substitutesWhenConfiguredModelCannotChat() throws Exception {
        var models = new LinkedHashMap<String, Stub>();
        models.put("broken-gguf:Q4_K_M", noTemplate());
        models.put("works:latest", jinjaChat());
        String url = startOllama(models);

        var config = new OwnClawConfig();
        LocalModelCheck check = checkWith(url, "broken-gguf:Q4_K_M", config);
        check.check();

        assertEquals("works:latest", config.getExecutor().getModel(),
                "the local tier should be running on the model that works, not the one that cannot");
        assertEquals("broken-gguf:Q4_K_M", check.substitutedFrom(),
                "the abandoned model has to be recorded, or the swap is invisible");
        assertTrue(check.status().detail().contains("substituted for"),
                "status() must disclose the substitution — silently swapping models is worse "
                        + "than stopping: " + check.status().detail());
    }

    @Test
    @DisplayName("a model that is not installed is replaced too")
    void substitutesWhenConfiguredModelIsMissing() throws Exception {
        var models = new LinkedHashMap<String, Stub>();
        models.put("works:latest", jinjaChat());
        String url = startOllama(models);

        var config = new OwnClawConfig();
        LocalModelCheck check = checkWith(url, "never-pulled:latest", config);
        check.check();

        assertEquals("works:latest", config.getExecutor().getModel());
        assertEquals("never-pulled:latest", check.substitutedFrom());
    }

    @Test
    @DisplayName("when nothing installed can chat, the configuration is left alone")
    void doesNotSubstituteWhenNothingIsUsable() throws Exception {
        var models = new LinkedHashMap<String, Stub>();
        models.put("broken-a", noTemplate());
        models.put("broken-b", noTemplate());
        String url = startOllama(models);

        var config = new OwnClawConfig();
        LocalModelCheck check = checkWith(url, "broken-a", config);
        check.check();

        assertEquals("broken-a", config.getExecutor().getModel(),
                "swapping one dead model for another would only make the fault harder to find");
        assertNull(check.substitutedFrom());
    }

    @Test
    @DisplayName("an embedding model is never chosen as the executor")
    void skipsEmbeddingModels() throws Exception {
        var models = new LinkedHashMap<String, Stub>();
        models.put("broken", noTemplate());
        models.put("embed-only", embedding());
        String url = startOllama(models);

        var config = new OwnClawConfig();
        LocalModelCheck check = checkWith(url, "broken", config);
        check.check();

        assertEquals("broken", config.getExecutor().getModel(),
                "an embedding model renders no conversation whatever its capabilities say");
    }

    @Test
    @DisplayName("the choice is the same on every boot, and prefers tool use")
    void choiceIsDeterministicAndPrefersCapability() throws Exception {
        // /api/tags order deliberately puts the weaker model first: a fallback that follows
        // whatever order the server happened to return is its own bug.
        var models = new LinkedHashMap<String, Stub>();
        models.put("broken", noTemplate());
        models.put("plain:latest", goTemplateOnly());
        models.put("capable:latest", jinjaChat());
        String url = startOllama(models);

        for (int boot = 0; boot < 3; boot++) {
            var config = new OwnClawConfig();
            LocalModelCheck check = checkWith(url, "broken", config);
            check.check();
            assertEquals("capable:latest", config.getExecutor().getModel(),
                    "boot " + boot + ": the tool-capable model should win, every time");
        }
    }

    @Test
    @DisplayName("a working configuration is left untouched")
    void leavesAGoodModelAlone() throws Exception {
        var models = new LinkedHashMap<String, Stub>();
        models.put("good:latest", jinjaChat());
        models.put("other:latest", jinjaChat());
        String url = startOllama(models);

        var config = new OwnClawConfig();
        LocalModelCheck check = checkWith(url, "good:latest", config);
        check.check();

        assertEquals("good:latest", config.getExecutor().getModel());
        assertNull(check.substitutedFrom());
        assertTrue(check.status().ok(), check.status().detail());
    }
}
