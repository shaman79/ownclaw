package com.ownclaw.agent;

import com.ownclaw.llm.LlmMessage;
import com.ownclaw.llm.LlmResponse;

import java.util.List;

/**
 * The full result of a single thinking step — carries both the parsed action
 * and the raw inputs/outputs for debug visibility.
 *
 * @param reply the reply the step was billed for, or null when none came (the call failed). The
 *              loop counts and prices it like every other model call made for the task: every
 *              billed attempt, cache included, each at the rates of the model that ran it.
 */
public record ThinkResult(
        AgentAction action,
        List<LlmMessage> promptMessages,
        String rawLlmOutput,
        LlmResponse reply
) {
    /** Input and output tokens as the provider reported them; 0 when no reply came. */
    public int totalTokens() {
        return reply == null ? 0 : reply.totalTokens();
    }
}
