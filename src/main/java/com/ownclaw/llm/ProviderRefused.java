package com.ownclaw.llm;

/**
 * The provider answered, and its answer was that it declines this request.
 * <p>
 * Not a failed call and not a malformed reply: Anthropic returns it as a normal HTTP 200 with
 * {@code stop_reason: "refusal"} in place of an answer (after its server-side fallback, when the
 * request asked for one, declined too), and OpenAI as {@code finish_reason: "content_filter"}.
 * Whatever content came with it is empty or a partial answer to discard. Thrown by
 * {@link LlmResponse#requireComplete}, so a caller never mistakes it for the model's answer.
 */
public final class ProviderRefused extends LlmException {

    private final LlmResponse reply;

    /** @param reply the refused reply: its stop reason is "refusal" or "content_filter" */
    public ProviderRefused(String provider, LlmResponse reply) {
        super(provider, "the model declined this request (stop reason: " + reply.stopReason()
                + (reply.stopDetail() == null ? "" : ", category: " + reply.stopDetail()) + ")");
        this.reply = reply;
    }

    /** "refusal" or "content_filter", as the provider said it. */
    public String stopReason() { return reply.stopReason(); }

    /** The refusal's category ("cyber", "bio", ...), or null when the provider named none. */
    public String category() { return reply.stopDetail(); }

    /**
     * The refused reply, for its token counts -- output streamed before a refusal is billed.
     * Its content is not an answer.
     */
    public LlmResponse reply() { return reply; }
}
