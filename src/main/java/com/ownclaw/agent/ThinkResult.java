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

    /**
     * Every token this call is charged for, cache included.
     * <p>
     * {@code totalTokens} is {@code prompt + completion} as the provider reports them, and on
     * Anthropic {@code input_tokens} counts only what was neither read from nor written to the
     * cache — cache reads and writes are reported separately and are additional. With caching
     * working as designed the cached prefix is the bulk of the prompt, so the counter the UI
     * shows, the figure written to token_usage and every budget ceiling were all reading a small
     * fraction of the real usage. A task billed for 60k tokens could report 9k.
     * <p>
     * The max() covers providers and error paths that report a total with no breakdown, where
     * the components are all zero and the total is the only honest number available.
     */
    public int billedTokens() {
        return Math.max(totalTokens,
                promptTokens + completionTokens + cacheWriteTokens + cacheReadTokens);
    }
}
