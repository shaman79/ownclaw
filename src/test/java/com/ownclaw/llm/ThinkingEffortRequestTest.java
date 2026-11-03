package com.ownclaw.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownclaw.config.OwnClawConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The owner's thinking effort as each provider renders it: Anthropic's output_config.effort, on a
 * model that takes one, at every level; and, at low, the local model asked not to reason first.
 */
class ThinkingEffortRequestTest {

    static final List<LlmMessage> ASK = List.of(LlmMessage.system("S"), LlmMessage.user("2+2?"));

    private final AnthropicProvider anthropic = new AnthropicProvider(new OwnClawConfig(), new ObjectMapper());

    private JsonNode body(String effort, String model) {
        return anthropic.requestBody(ASK, new LlmRequestConfig(null, null, false).withEffort(effort), model, 64_000);
    }

    @Test
    @DisplayName("Anthropic: each level is sent as output_config.effort, high too, to a model that takes one")
    void anthropicSendsTheLevel() {
        for (String level : List.of("low", "medium", "high")) {
            for (String model : List.of("claude-opus-5", "claude-opus-5-5", "claude-sonnet-5-5", "claude-fable-5-1")) {
                assertEquals(level, body(level, model).path("output_config").path("effort").asText(), model);
            }
        }
        JsonNode body = body("low", "claude-opus-5");
        assertEquals("default", body.path("fallbacks").asText(), "the rest of the request is as it was");
        assertEquals(1, body.path("output_config").size(), "only the effort: " + body.path("output_config"));
    }

    @Test
    @DisplayName("Anthropic: no effort for a model that takes none, nor for a call that carries none")
    void anthropicOmitsIt() {
        for (String model : List.of("claude-sonnet-4-5", "claude-haiku-4-5", "claude-sonnet-4-20250514",
                "claude-3-5-sonnet-20241022")) {
            assertFalse(body("low", model).has("output_config"), model);
        }
        assertFalse(anthropic.requestBody(ASK, new LlmRequestConfig(null, null, false), "claude-opus-5", 64_000)
                .has("output_config"), "a call without an effort is sent the model's own default");
    }

    @Test
    @DisplayName("the level stays with the request whatever else is set on it -- the gateway sets tools and a hook")
    void theLevelStaysWithTheRequest() {
        var request = new LlmRequestConfig(null, null, false).withEffort("low");
        for (LlmRequestConfig copy : List.of(request.withTools(List.of()), request.withEgress(null),
                request.withProgress(LlmProgress.NONE), request.answeringDirectly())) {
            assertEquals("low", copy.effort(), copy.toString());
        }
    }

    @Test
    @DisplayName("which models take an effort: Opus 4.5 on, Sonnet 4.6 on, every Fable and Mythos")
    void whichModelsTakeAnEffort() {
        for (String model : List.of("claude-opus-4-5", "claude-opus-4-5-20251101", "claude-opus-4-6",
                "claude-opus-4-7", "claude-opus-4-8", "claude-opus-5", "claude-opus-5-5", "claude-sonnet-4-6",
                "claude-sonnet-5", "claude-sonnet-5-5", "claude-fable-5", "claude-fable-5-1", "claude-mythos-5-1")) {
            assertTrue(AnthropicProvider.supportsEffort(model), model);
        }
        for (String model : List.of("claude-opus-4-1", "claude-opus-4-20250514", "claude-sonnet-4-5",
                "claude-sonnet-4-20250514", "claude-haiku-4-5", "claude-3-5-sonnet-20241022", "")) {
            assertFalse(AnthropicProvider.supportsEffort(model), model);
        }
        assertFalse(AnthropicProvider.supportsEffort(null));
    }

    @Test
    @DisplayName("Ollama: at low the local model is asked not to reason first; medium and high leave its default")
    void ollamaThinksAtItsDefaultUnlessLow() throws Exception {
        for (String level : List.of("low", "medium", "high")) {
            var http = OllamaStreamingTest.ollama(OllamaStreamingTest.line("4", null)
                    + OllamaStreamingTest.last("stop", 20, 1));
            OllamaStreamingTest.provider(OllamaStreamingTest.config(), http)
                    .chat(ASK, new LlmRequestConfig(null, null, false).withEffort(level));
            JsonNode sent = new ObjectMapper().readTree(http.to(OllamaStreamingTest.CHAT).get(0).body());
            if ("low".equals(level)) {
                assertTrue(sent.has("think") && !sent.path("think").asBoolean(true), sent.toString());
            } else {
                assertFalse(sent.has("think"), level + ": " + sent);
            }
        }
    }
}
