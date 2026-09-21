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
    int cacheReadTokens,
    String stopReason,
    java.util.List<ToolCall> toolCalls
) {
    /** Without native tool calls — every provider path that does not offer tools. */
    public LlmResponse(String content, int promptTokens, int completionTokens,
                       int cacheCreationTokens, int cacheReadTokens, String stopReason) {
        this(content, promptTokens, completionTokens, cacheCreationTokens, cacheReadTokens,
                stopReason, java.util.List.of());
    }

    /** Whether the model asked to call a tool. */
    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }

    /** For providers with no prompt cache, or calls that did not touch one. */
    public LlmResponse(String content, int promptTokens, int completionTokens) {
        this(content, promptTokens, completionTokens, 0, 0, null);
    }

    public LlmResponse(String content, int promptTokens, int completionTokens,
                       int cacheCreationTokens, int cacheReadTokens) {
        this(content, promptTokens, completionTokens, cacheCreationTokens, cacheReadTokens, null);
    }

    /**
     * Whether the model was cut off by the output limit rather than finishing.
     * <p>
     * Nothing read this before. The consequence was specific and expensive: an answer longer
     * than max_tokens comes back truncated mid-JSON, so parsing fails, and a truncated reply is
     * indistinguishable from a malformed one. Both were retried with a byte-identical prompt,
     * which produced an identically truncated reply, and after a few rounds the run aborted —
     * discarding prose the model had actually written. Knowing the difference turns "the model
     * returned nonsense" into "the answer did not fit", which has an obvious fix.
     */
    public boolean truncated() {
        return "max_tokens".equals(stopReason) || "length".equals(stopReason);
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
