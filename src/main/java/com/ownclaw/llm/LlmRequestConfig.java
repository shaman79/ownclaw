package com.ownclaw.llm;

/**
 * Per-request overrides for LLM calls.
 * Any {@code null} field means "use provider default from config."
 */
public record LlmRequestConfig(
    String model,
    Double temperature,
    Integer maxTokens,
    boolean jsonMode
) {
    /** Use all defaults from the provider config. */
    public static final LlmRequestConfig DEFAULT = new LlmRequestConfig(null, null, null, false);

    public static LlmRequestConfig withMaxTokens(int maxTokens) {
        return new LlmRequestConfig(null, null, maxTokens, false);
    }

    /** Force the provider to return valid JSON (OpenAI response_format: json_object). */
    public static LlmRequestConfig withJsonMode(int maxTokens) {
        return new LlmRequestConfig(null, null, maxTokens, true);
    }
}
