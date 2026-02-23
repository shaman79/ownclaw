package com.ownclaw.llm;

/**
 * Unified LLM response across all providers.
 */
public record LlmResponse(
    String content,
    int promptTokens,
    int completionTokens
) {
    public int totalTokens() {
        return promptTokens + completionTokens;
    }
}
