package com.ownclaw.llm;

import java.util.List;

/**
 * Abstraction over LLM providers (Ollama, OpenAI, etc.).
 * Each provider converts messages to its own API format and parses the response.
 */
public interface LlmProvider {

    /**
     * Send a chat completion request with the given messages and optional overrides.
     *
     * @param messages ordered conversation (system, user, assistant, ...)
     * @param config   per-request overrides (model, temperature, max tokens)
     * @return the provider's response
     * @throws LlmException on network errors, rate limits, or invalid responses
     */
    LlmResponse chat(List<LlmMessage> messages, LlmRequestConfig config);

    /**
     * Quick health check — can we reach the provider?
     */
    boolean isAvailable();

    /**
     * Human-readable name for logging (e.g. "ollama", "openai").
     */
    String name();
}
