package com.ownclaw.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ownclaw.config.OwnClawConfig;

import java.util.List;

/**
 * What the real providers would send for messages a test captured -- from a test in another
 * package, which cannot reach {@code AnthropicProvider#requestBody} and its like itself.
 */
public final class RequestBodies {

    private RequestBodies() {}

    /** The request body for {@code messages}, as {@code AnthropicProvider} builds it for {@code model}. */
    public static ObjectNode anthropic(List<LlmMessage> messages, LlmRequestConfig config, String model) {
        return new AnthropicProvider(new OwnClawConfig(), new ObjectMapper()).requestBody(messages, config, model, 128_000);
    }

    /** The request body for {@code messages}, as {@code OpenAiProvider} builds it for {@code model}. */
    public static ObjectNode openai(List<LlmMessage> messages, LlmRequestConfig config, String model) {
        return new OpenAiProvider(new OwnClawConfig(), new ObjectMapper()).requestBody(messages, config, model);
    }

    /**
     * The body of the request {@code OllamaProvider} sends for {@code messages}: to a server that
     * is not there, which describes the model and answers with an empty reply.
     */
    public static JsonNode ollama(List<LlmMessage> messages, LlmRequestConfig config) throws Exception {
        var http = OllamaStreamingTest.ollama(OllamaStreamingTest.last("stop", 10, 1));
        OllamaStreamingTest.provider(OllamaStreamingTest.config(), http).chat(messages, config);
        return new ObjectMapper().readTree(http.to(OllamaStreamingTest.CHAT).get(0).body());
    }
}
