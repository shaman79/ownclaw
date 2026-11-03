package com.ownclaw.agent;

import com.ownclaw.llm.LlmMessage;
import com.ownclaw.llm.LlmResponse;

import java.util.List;

/**
 * The full result of a single thinking step — carries both the parsed action
 * and the raw inputs/outputs for debug visibility.
 *
 * @param reply      the reply the step was billed for, or null when none came (the call failed).
 *                   The loop counts and prices it like every other model call made for the task:
 *                   every billed attempt, cache included, each at the rates of the model that ran
 *                   it.
 * @param callFailed how the call failed, when it did -- the provider's message -- or null when a
 *                   reply came: what the task's ending says, where the step records what the
 *                   model is told about it
 */
public record ThinkResult(
        AgentAction action,
        List<LlmMessage> promptMessages,
        String rawLlmOutput,
        LlmResponse reply,
        String callFailed
) {
    /** A step whose call brought a reply. */
    public ThinkResult(AgentAction action, List<LlmMessage> promptMessages, String rawLlmOutput,
                       LlmResponse reply) {
        this(action, promptMessages, rawLlmOutput, reply, null);
    }

    /** Input and output tokens as the provider reported them; 0 when no reply came. */
    public int totalTokens() {
        return reply == null ? 0 : reply.totalTokens();
    }
}
