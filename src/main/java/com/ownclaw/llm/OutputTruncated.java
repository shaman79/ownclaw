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
     * Whether a reply ran on until the context window was full: there is a reply, and the request
     * it came of was read whole -- one longer than the window is refused before any reply -- so the
     * reply filled the rest. A local generation is sent no output limit, and nothing of this
     * application ends a reply for its length or for repeating, so this is where a runaway ends.
     * Said so, with both sizes ({@link #describe}): "the conversation is longer than the window"
     * was not true of it.
     */
    public boolean replyRanOn() {
        return ranOn(limit, tokens, reply());
    }

    /**
     * Whether the reply, not the request, filled most of the window: it ran on ({@link #replyRanOn})
     * from a request under half of it. Folding the request then makes room only for the reply to
     * run on again, each round a whole window of generation, and a new chat would not help either:
     * nothing is folded, and the task ends as an error. From a request over half the window, the
     * request is folded and asked again as one too long would be -- the room it frees is the
     * reply's -- and once nothing is left to fold, a new chat is what makes room.
     */
    public boolean replyFilledTheWindow() {
        return replyRanOn() && reply().billedInputTokens() < tokens / 2;
    }

    private static boolean ranOn(Limit limit, Integer tokens, LlmResponse reply) {
        return limit == Limit.CONTEXT_WINDOW && reply != null && tokens != null && reply.billedInputTokens() > 0;
    }

    private static String describe(Limit limit, Integer tokens, LlmResponse reply) {
        if (ranOn(limit, tokens, reply)) {
            // Both sizes, so the reader sees which filled it: a reply of 261,244 tokens to a request of
            // 900 ran on; one of 1,000 to a request of 999,000 had little room.
            int wrote = reply.completionTokens();
            return wrote > 0
                    ? String.format(Locale.ROOT, "a request of %,d tokens and a reply of %,d tokens filled the "
                            + "model's %,d-token context window", reply.billedInputTokens(), wrote, tokens)
                    : String.format(Locale.ROOT, "a request of %,d tokens and its reply filled the model's %,d-token "
                            + "context window", reply.billedInputTokens(), tokens);
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
