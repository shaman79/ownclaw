package com.ownclaw.llm;

import java.util.List;
import java.util.function.ToIntFunction;

/**
 * Unified LLM response across all providers.
 *
 * @param content          the generated text
 * @param toolCalls        the tool calls the model asked for, each with arguments that parsed
 * @param invalidToolCall  why a tool call the model wrote is not among {@code toolCalls} -- its
 *                         arguments are not a JSON object -- with those arguments as it wrote
 *                         them, or null when every call parsed
 * @param stopReason       why the model stopped, as the provider said it ("end_turn",
 *                         "tool_use", "max_tokens", "refusal", "length", "stop", ...), or null
 * @param stopDetail       what the provider adds to that reason -- on Anthropic, the category
 *                         of a refusal ("cyber", ...), which it may leave out -- or null
 * @param model            the model that wrote the reply, as the provider named it -- not
 *                         necessarily the one asked for: Anthropic can hand a declined request
 *                         to a fallback model -- or null when the provider did not say
 * @param maxOutputTokens  the output limit the reply was written under (the model's own
 *                         maximum), or null when the request set none
 * @param contextWindow    the model's context window, or null when the provider does not
 *                         say what it is
 * @param usage            every attempt this reply was billed for, each with the model that ran
 *                         it; the token counts below are their sums
 */
public record LlmResponse(
    String content,
    List<ToolCall> toolCalls,
    String invalidToolCall,
    String stopReason,
    String stopDetail,
    String model,
    Integer maxOutputTokens,
    Integer contextWindow,
    List<Usage> usage
) {
    /**
     * What one attempt at a reply was billed for, and the model that ran it (null when the
     * provider did not say). A reply is usually one attempt. Anthropic bills more than one when a
     * model declines part-way and its fallback model finishes the reply, and this application's
     * own retry on the model a refusal names adds another.
     *
     * @param promptTokens input tokens as the provider reports them -- see the caching note
     */
    public record Usage(String model, int promptTokens, int completionTokens,
                        int cacheCreationTokens, int cacheReadTokens) {}

    public LlmResponse {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        usage = usage == null ? List.of() : List.copyOf(usage);
    }

    /** Whether the model asked to call a tool. */
    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }

    /** Input tokens billed for this reply, over every attempt, as the provider reports them. */
    public int promptTokens() { return sum(Usage::promptTokens); }

    /** Output tokens billed for this reply, over every attempt. */
    public int completionTokens() { return sum(Usage::completionTokens); }

    /** Input tokens written to the prompt cache, over every attempt (0 if unsupported). */
    public int cacheCreationTokens() { return sum(Usage::cacheCreationTokens); }

    /** Input tokens served from the prompt cache, over every attempt (0 if unsupported). */
    public int cacheReadTokens() { return sum(Usage::cacheReadTokens); }

    private int sum(ToIntFunction<Usage> counter) {
        int total = 0;
        for (Usage u : usage) total += counter.applyAsInt(u);
        return total;
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
     * model's maximum output and its "model_context_window_exceeded" the context window. A
     * "length" is the context window when no output limit was sent and the window is known --
     * Ollama, which is always sent the model's own window and never an output limit -- and
     * otherwise the model's maximum output: OpenAI states neither, and no output limit is sent
     * to it either, so its "length" is taken to be the model's own maximum. A reply that is
     * whole but holds a tool call whose arguments did not parse throws {@link MalformedToolCall}:
     * running the call without them, or dropping it and taking the text for the answer, would
     * both be wrong. Each carries this reply, so its billed tokens can still be counted.
     *
     * @param provider the provider's name, for the exception
     */
    public LlmResponse requireComplete(String provider) {
        if (refused()) throw new ProviderRefused(provider, this);
        if ("max_tokens".equals(stopReason)) {
            throw new OutputTruncated(provider, OutputTruncated.Limit.MAX_OUTPUT, maxOutputTokens, this);
        }
        if ("model_context_window_exceeded".equals(stopReason)) {
            throw new OutputTruncated(provider, OutputTruncated.Limit.CONTEXT_WINDOW, contextWindow, this);
        }
        if ("length".equals(stopReason)) {
            throw maxOutputTokens == null && contextWindow != null
                    ? new OutputTruncated(provider, OutputTruncated.Limit.CONTEXT_WINDOW, contextWindow, this)
                    : new OutputTruncated(provider, OutputTruncated.Limit.MAX_OUTPUT, maxOutputTokens, this);
        }
        if (invalidToolCall != null) throw new MalformedToolCall(provider, this);
        return this;
    }

    /**
     * Uncached input plus output. Kept for existing callers, but note it is NOT the billed
     * total on a provider with prompt caching — use {@link #billedInputTokens()} for that.
     */
    public int totalTokens() {
        return promptTokens() + completionTokens();
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
        return promptTokens() + cacheCreationTokens() + cacheReadTokens();
    }

    /** True when this call touched a prompt cache in either direction. */
    public boolean usedCache() {
        return cacheCreationTokens() > 0 || cacheReadTokens() > 0;
    }
}
