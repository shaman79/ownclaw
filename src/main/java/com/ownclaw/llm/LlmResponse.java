package com.ownclaw.llm;

/**
 * Unified LLM response across all providers.
 *
 * @param content              the generated text
 * @param promptTokens         input tokens as the provider reports them — see the caching note
 * @param completionTokens     output tokens
 * @param cacheCreationTokens  input tokens written to the prompt cache this call (0 if unsupported)
 * @param cacheReadTokens      input tokens served from the prompt cache this call (0 if unsupported)
 * @param stopReason           why the model stopped, as the provider said it ("end_turn",
 *                             "tool_use", "max_tokens", "refusal", "length", "stop", ...), or null
 * @param stopDetail           what the provider adds to that reason -- on Anthropic, the category
 *                             of a refusal ("cyber", ...), which it may leave out -- or null
 * @param model                the model that wrote the reply, as the provider named it -- not
 *                             necessarily the one asked for: Anthropic can hand a declined request
 *                             to a fallback model -- or null when the provider did not say
 * @param maxOutputTokens      the output limit the reply was written under (the model's own
 *                             maximum), or null when the request set none
 * @param contextWindow        the model's context window, or null when the provider does not
 *                             say what it is
 */
public record LlmResponse(
    String content,
    int promptTokens,
    int completionTokens,
    int cacheCreationTokens,
    int cacheReadTokens,
    String stopReason,
    java.util.List<ToolCall> toolCalls,
    String stopDetail,
    String model,
    Integer maxOutputTokens,
    Integer contextWindow
) {
    /** A reply described by its stop reason alone -- what a fake provider in a test returns. */
    public LlmResponse(String content, int promptTokens, int completionTokens,
                       int cacheCreationTokens, int cacheReadTokens, String stopReason,
                       java.util.List<ToolCall> toolCalls) {
        this(content, promptTokens, completionTokens, cacheCreationTokens, cacheReadTokens,
                stopReason, toolCalls, null, null, null, null);
    }

    /** Without native tool calls. */
    public LlmResponse(String content, int promptTokens, int completionTokens,
                       int cacheCreationTokens, int cacheReadTokens, String stopReason) {
        this(content, promptTokens, completionTokens, cacheCreationTokens, cacheReadTokens,
                stopReason, java.util.List.of());
    }

    /** For providers with no prompt cache, or calls that did not touch one. */
    public LlmResponse(String content, int promptTokens, int completionTokens) {
        this(content, promptTokens, completionTokens, 0, 0, null);
    }

    /** Whether the model asked to call a tool. */
    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }

    /**
     * Whether the provider declined the request: Anthropic's "refusal", OpenAI's
     * "content_filter". Such a reply is a normal HTTP 200 whose content is empty, or a partial
     * answer that must be discarded -- never an answer.
     */
    public boolean refused() {
        return "refusal".equals(stopReason) || "content_filter".equals(stopReason);
    }

    /** The stop reason as a person reads it, with its detail: "refusal (cyber)", "end_turn", or null. */
    public String stopDescription() {
        if (stopReason == null) return null;
        return stopDetail == null ? stopReason : stopReason + " (" + stopDetail + ")";
    }

    /**
     * This reply, if it is one the caller may use as the model's answer; otherwise the reason
     * it is not, thrown.
     * <p>
     * The one check, applied on every path that hands a reply to a caller: the cloud gateway
     * applies it after writing the call's ledger row, the local provider before it returns. A
     * refused reply throws {@link ProviderRefused}. A reply cut off by a limit throws
     * {@link OutputTruncated} naming the limit and its size: Anthropic's "max_tokens" is the
     * model's maximum output and its "model_context_window_exceeded" the context window; a
     * "length" (OpenAI, Ollama) is the output limit when the request set one and the context
     * window when it did not, which is always the case on Ollama, where no output limit is sent.
     * Before this, a cut-off reply looked exactly like a malformed one and was parsed, salvaged
     * or retried as if it were the model's whole answer.
     *
     * @param provider the provider's name, for the exception
     */
    public LlmResponse requireComplete(String provider) {
        LlmException notAnAnswer = incompleteness(provider);
        if (notAnAnswer != null) throw notAnAnswer;
        return this;
    }

    /** Whether {@link #requireComplete} lets this reply through. For the providers' own use. */
    boolean complete() {
        return incompleteness("") == null;
    }

    private LlmException incompleteness(String provider) {
        if (refused()) return new ProviderRefused(provider, this);
        if ("max_tokens".equals(stopReason)) {
            return new OutputTruncated(provider, OutputTruncated.Limit.MAX_OUTPUT, maxOutputTokens, this);
        }
        if ("model_context_window_exceeded".equals(stopReason)) {
            return new OutputTruncated(provider, OutputTruncated.Limit.CONTEXT_WINDOW, contextWindow, this);
        }
        if ("length".equals(stopReason)) {
            return maxOutputTokens == null && contextWindow != null
                    ? new OutputTruncated(provider, OutputTruncated.Limit.CONTEXT_WINDOW, contextWindow, this)
                    : new OutputTruncated(provider, OutputTruncated.Limit.MAX_OUTPUT, maxOutputTokens, this);
        }
        return null;
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
