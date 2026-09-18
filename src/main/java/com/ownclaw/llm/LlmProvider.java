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

    /**
     * The model this provider is configured to use by default, for cost attribution.
     * <p>
     * Needed because pricing is per model, not per provider: an Opus call and a Haiku call
     * through the same provider differ by roughly 15x on input. Defaults to null for any
     * implementation that does not track one, and unknown models are priced at zero rather
     * than guessed.
     */
    default String model() { return null; }
}
