package com.ownclaw.llm;

import java.util.Locale;

/**
 * The conversation or the reply did not fit a limit of the model, so there is no whole answer.
 * <p>
 * Either the reply reached the model's maximum output and was cut off, or the conversation --
 * with the reply, or already before it -- is longer than the model's context window. Those are
 * the model's own limits, not settings of this application, and sending the same request again
 * reaches them again. Thrown by {@link LlmResponse#requireComplete} for a reply that stopped at a
 * limit, and by the providers for a request the provider refused as too long for the window --
 * which has no {@link #reply()}.
 */
public final class OutputTruncated extends LlmException {

    /** Which limit was reached. */
    public enum Limit { MAX_OUTPUT, CONTEXT_WINDOW }

    private final Limit limit;
    private final Integer tokens;

    /**
     * @param limit  which limit was reached
     * @param tokens its size in tokens, or null when the provider does not say what it is
     * @param reply  the reply that was cut off, or null when the request was refused as too long
     *               before any reply
     */
    public OutputTruncated(String provider, Limit limit, Integer tokens, LlmResponse reply) {
        super(provider, describe(limit, tokens), 0, null, reply);
        this.limit = limit;
        this.tokens = tokens;
    }

    public Limit limit() { return limit; }

    /** The limit's size in tokens, or null when the provider does not say what it is. */
    public Integer tokens() { return tokens; }

    private static String describe(Limit limit, Integer tokens) {
        return switch (limit) {
            case MAX_OUTPUT -> tokens == null
                    ? "the reply reached the model's maximum output and was cut off"
                    : String.format(Locale.ROOT,
                            "the reply reached the model's maximum output of %,d tokens and was cut off", tokens);
            case CONTEXT_WINDOW -> tokens == null
                    ? "the conversation is longer than the model's context window"
                    : String.format(Locale.ROOT,
                            "the conversation is longer than the model's %,d-token context window", tokens);
        };
    }
}
