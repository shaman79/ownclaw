package com.ownclaw.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ownclaw.config.OwnClawConfig;

import java.util.List;

/**
 * What the real Anthropic provider would send for messages a test captured -- from a test in
 * another package, which cannot reach {@code AnthropicProvider#requestBody} itself.
 */
public final class RequestBodies {

    private RequestBodies() {}

    /** The request body for {@code messages}, as {@code AnthropicProvider} builds it for {@code model}. */
    public static ObjectNode anthropic(List<LlmMessage> messages, LlmRequestConfig config, String model) {
        return new AnthropicProvider(new OwnClawConfig(), new ObjectMapper()).requestBody(messages, config, model, 128_000);
    }
}
