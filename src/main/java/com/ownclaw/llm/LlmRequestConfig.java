package com.ownclaw.llm;

/**
 * Per-request overrides for LLM calls.
 * Any {@code null} field means "use provider default from config."
 */
public record LlmRequestConfig(
    String model,
    Double temperature,
    Integer maxTokens,
    boolean jsonMode,
    /** Per-request HTTP read timeout override in seconds. {@code null} = use provider default. */
    Integer readTimeoutSec
) {
    /** Use all defaults from the provider config. */
    public static final LlmRequestConfig DEFAULT = new LlmRequestConfig(null, null, null, false, null);

    public static LlmRequestConfig withMaxTokens(int maxTokens) {
        return new LlmRequestConfig(null, null, maxTokens, false, null);
    }

    /** Force the provider to return valid JSON (OpenAI response_format: json_object). */
    public static LlmRequestConfig withJsonMode(int maxTokens) {
        return new LlmRequestConfig(null, null, maxTokens, true, null);
    }

    /** Create a config with a custom read timeout (in seconds) for long-running inference. */
    public static LlmRequestConfig withReadTimeout(int readTimeoutSec) {
        return new LlmRequestConfig(null, null, null, false, readTimeoutSec);
    }
}
