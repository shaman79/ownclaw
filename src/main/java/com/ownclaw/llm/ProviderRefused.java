package com.ownclaw.llm;

/**
 * The provider answered, and its answer was that it declines this request.
 * <p>
 * Not a failed call and not a malformed reply: Anthropic returns it as a normal HTTP 200 with
 * {@code stop_reason: "refusal"} in place of an answer, and OpenAI as
 * {@code finish_reason: "content_filter"}. On Anthropic it is a decline nothing re-ran: the
 * refusal's category has no fallback model, or the fallback model declined too, or Anthropic
 * skipped its fallback and named no model to retry on -- or the model it named, which
 * {@code AnthropicProvider} retries once, declined as well. Whatever content came with it is
 * empty or a partial answer to discard. Thrown by {@link LlmResponse#requireComplete}, so a caller
 * never mistakes it for the model's answer; {@link #reply()} keeps its tokens, since output
 * streamed before a refusal is billed.
 */
public final class ProviderRefused extends LlmException {

    /**
     * @param reply the refused reply: its stop reason is "refusal" or "content_filter". Said as
     *              {@link LlmResponse#stopDescription} says it, as the ledger and the task page do
     */
    public ProviderRefused(String provider, LlmResponse reply) {
        super(provider, "the model declined this request (stop reason: " + reply.stopDescription() + ")",
                0, null, reply);
    }

    /** "refusal" or "content_filter", as the provider said it. */
    public String stopReason() { return reply().stopReason(); }

    /** The refusal's category ("cyber", "bio", ...), or null when the provider named none. */
    public String category() { return reply().stopDetail(); }

    /**
     * Declined as reasoning extraction: Anthropic judged the step to be giving away the model's
     * hidden reasoning. The words a model is asked to write beside a tool call can set that off,
     * so the loop asks such a step again with none asked for ({@code AgentLoop}).
     */
    public boolean asReasoningExtraction() { return "reasoning_extraction".equals(category()); }
}
