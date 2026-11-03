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
     * @param config   per-request overrides (model, temperature, tools, progress hook, ...)
     * @return the provider's response -- through the cloud gateway or the local provider, only
     *         one that passed {@link LlmResponse#requireComplete}
     * @throws LlmException on network errors, rate limits, or invalid responses; a refused
     *         reply is a {@link ProviderRefused}, one cut off by a limit an {@link OutputTruncated},
     *         and one holding a tool call whose arguments do not parse a {@link MalformedToolCall}
     *         -- each with the reply, whose tokens were billed ({@link LlmException#reply()})
     */
    LlmResponse chat(List<LlmMessage> messages, LlmRequestConfig config);

    /**
     * Quick health check — can we reach the provider?
     */
    boolean isAvailable();

    /**
     * Whether this provider can be offered tools natively on the CURRENT model.
     * <p>
     * Defaults to false so a provider that has not been taught the protocol keeps the text
     * protocol rather than silently sending a field the API ignores. Per-model rather than
     * per-provider, because Ollama's answer depends on which model is loaded.
     */
    default boolean supportsTools() { return false; }

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
