package com.ownclaw.agent;

import com.ownclaw.llm.LlmMessage;

import java.util.List;

/**
 * The full result of a single thinking step — carries both the parsed action
 * and the raw inputs/outputs for debug visibility.
 */
public record ThinkResult(
        AgentAction action,
        List<LlmMessage> promptMessages,
        String rawLlmOutput,
        int totalTokens
) {
}
