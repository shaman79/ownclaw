package com.ownclaw.llm;

/**
 * Unified LLM response across all providers.
 *
 * @param content              the generated text
 * @param promptTokens         input tokens as the provider reports them — see the caching note
 * @param completionTokens     output tokens
 * @param cacheCreationTokens  input tokens written to the prompt cache this call (0 if unsupported)
 * @param cacheReadTokens      input tokens served from the prompt cache this call (0 if unsupported)
 */
public record LlmResponse(
    String content,
    int promptTokens,
    int completionTokens,
    int cacheCreationTokens,
    int cacheReadTokens
) {
    /** For providers with no prompt cache, or calls that did not touch one. */
    public LlmResponse(String content, int promptTokens, int completionTokens) {
        this(content, promptTokens, completionTokens, 0, 0);
    }

    /**
     * Uncached input plus output. Kept for existing callers, but note it is NOT the billed
     * total on a provider with prompt caching — use {@link #billedInputTokens()} for that.
     */
    public int totalTokens() {
        return promptTokens + completionTokens;
    }

    /**
     * Every input token this call is charged for.
     * <p>
     * On Anthropic's Messages API {@code input_tokens} counts only the tokens that were neither
     * read from nor written to the cache; cache creation and cache reads are reported separately
     * and are additional. The provider was reading both of those fields, logging them, and then
     * dropping them on the floor — so the "cloud tokens" figure the UI showed was not the number
     * being billed, and on a cached conversation it could understate the input by most of the
     * prompt.
     * <p>
     * The three are also priced differently: cache writes cost about 1.25x the base input rate
     * and cache reads about 0.1x, so a cost calculation has to keep them apart rather than sum
     * them. This method is the honest token count; pricing multiplies the three components
     * separately.
     */
    public int billedInputTokens() {
        return promptTokens + cacheCreationTokens + cacheReadTokens;
    }

    /** True when this call touched a prompt cache in either direction. */
    public boolean usedCache() {
        return cacheCreationTokens > 0 || cacheReadTokens > 0;
    }
}
