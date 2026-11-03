package com.ownclaw.llm;

/**
 * The reply is whole, but a tool call in it cannot be run: its arguments are not a JSON object.
 * <p>
 * Anthropic does not check a tool input it streams (eager input streaming), and OpenAI's
 * arguments arrive as a string of JSON the model wrote, so this is the model's own text, parsed
 * strictly. Running the call without its arguments, or dropping it and taking the rest of the
 * reply for the answer, would both be the wrong thing. Thrown by
 * {@link LlmResponse#requireComplete} -- after the cloud gateway has written the call's ledger
 * row -- and {@link #reply()} keeps the reply's billed tokens.
 */
public final class MalformedToolCall extends LlmException {

    /**
     * @param reply the reply, whose {@link LlmResponse#invalidToolCall()} says which call, why,
     *              and what its arguments were -- the model's own text, so a log line that
     *              reports this exception gives its length, not its message
     */
    public MalformedToolCall(String provider, LlmResponse reply) {
        super(provider, reply.invalidToolCall(), 0, null, reply);
    }
}
