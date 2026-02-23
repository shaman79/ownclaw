package com.ownclaw.llm;

/**
 * Per-request overrides for LLM calls.
 * Any {@code null} field means "use provider default from config."
 */
public record LlmRequestConfig(
    String model,
    Double temperature,
    Integer maxTokens
) {
    /** Use all defaults from the provider config. */
    public static final LlmRequestConfig DEFAULT = new LlmRequestConfig(null, null, null);

    public static LlmRequestConfig withMaxTokens(int maxTokens) {
        return new LlmRequestConfig(null, null, maxTokens);
    }
}
