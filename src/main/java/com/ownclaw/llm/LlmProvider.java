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
     * {@code asWritten} -- text the caller built from replies to calls made with {@code config},
     * which asked for them as the model wrote them ({@link LlmRequestConfig#asWritten}): a skill's
     * code, its parts joined -- with the user's values put back where it names their placeholders,
     * once. Put back in each reply, a placeholder the cut between two fell in was whole in neither,
     * and its quotes could stand in another; put back twice, a placeholder's shape the user wrote
     * as text would turn into a value. The cloud gateway puts them back; a provider that hands back
     * no placeholders returns the text as it is.
     */
    default String restored(String asWritten, LlmRequestConfig config) { return asWritten; }

    /**
     * Whether a request's thinking effort ({@link LlmRequestConfig#effort}) changes what this
     * provider sends: asking again at another effort is then another request, and not the same one
     * again. False unless a provider says otherwise.
     */
    default boolean takesEffort() { return false; }

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

    /**
     * The configured model's context window in tokens, when this provider already knows it without
     * asking anyone -- Anthropic's from the Models API answer it keeps -- or null. Read to tell
     * which per-request image count the API takes (100 on a 200k window, 600 on a larger one), so
     * it never makes a call of its own.
     */
    default Integer knownContextWindow() { return null; }

    /**
     * The configured model's context window in tokens, asking the provider's own API when it is not
     * known yet -- Anthropic's Models API -- or null when it cannot be told. Read before a request is
     * written whose shape depends on it (the per-request image count), so that the first request of
     * a process is shaped as every later one is.
     *
     * @param progress the hook the lookup's call reports to, and is stopped by
     */
    default Integer contextWindow(LlmProgress progress) { return knownContextWindow(); }
}
