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
        int totalTokens,
        int promptTokens,
        int completionTokens,
        int cacheWriteTokens,
        int cacheReadTokens,
        String model
) {
    /**
     * For call sites with no usage breakdown (an error path, or a provider that reports none).
     * Pricing needs the components separately -- cached reads cost a tenth of base and cache
     * writes a quarter more -- so a single total cannot be costed.
     */
    public ThinkResult(AgentAction action, List<LlmMessage> promptMessages,
                       String rawLlmOutput, int totalTokens) {
        this(action, promptMessages, rawLlmOutput, totalTokens, 0, 0, 0, 0, null);
    }
}
