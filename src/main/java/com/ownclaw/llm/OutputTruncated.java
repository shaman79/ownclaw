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
        super(provider, describe(limit, tokens, reply), 0, null, reply);
        this.limit = limit;
        this.tokens = tokens;
    }

    public Limit limit() { return limit; }

    /** The limit's size in tokens, or null when the provider does not say what it is. */
    public Integer tokens() { return tokens; }

    /**
     * Whether the reply filled the context window, not the request: the request was read whole,
     * with room to spare -- under half the window, or shorter than the reply that came of it -- and
     * the reply ran on until the window was full: a local generation, which is sent no output limit,
     * that did not stop. Nothing of this application ends a reply for its length or for repeating,
     * so this is where a runaway ends. Folding the request makes room only for the reply to run on
     * again, each round a whole window of generation; and it is no conversation too long for the
     * window, which a new chat would answer.
     */
    public boolean replyFilledTheWindow() {
        return filledByReply(limit, tokens, reply());
    }

    private static boolean filledByReply(Limit limit, Integer tokens, LlmResponse reply) {
        if (limit != Limit.CONTEXT_WINDOW || reply == null || tokens == null) return false;
        int read = reply.billedInputTokens();
        return read > 0 && (read < tokens / 2 || reply.completionTokens() > read);
    }

    private static String describe(Limit limit, Integer tokens, LlmResponse reply) {
        if (filledByReply(limit, tokens, reply)) {
            return String.format(Locale.ROOT, "the reply ran on until it filled the model's %,d-token context "
                    + "window, from a request of %,d tokens", tokens, reply.billedInputTokens());
        }
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
