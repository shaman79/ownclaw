package com.ownclaw.llm;

import java.util.List;

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
    Integer readTimeoutSec,
    /**
     * Tools to offer natively on this call, or null to use the text protocol.
     * <p>
     * Per request rather than per provider because the tool set is dynamic: every tool is a
     * Python skill the agent wrote at runtime, so the list changes within a single session.
     */
    List<ToolSpec> tools
) {
    /**
     * Without native tools — which is every call site that existed before they did.
     * <p>
     * A delegating constructor rather than an edit to every call site, matching the shape
     * {@link LlmResponse} already uses for its optional components.
     */
    public LlmRequestConfig(String model, Double temperature, Integer maxTokens,
                            boolean jsonMode, Integer readTimeoutSec) {
        this(model, temperature, maxTokens, jsonMode, readTimeoutSec, null);
    }

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

    /** This request, but offering the model these tools natively. */
    public LlmRequestConfig withTools(List<ToolSpec> toolSpecs) {
        return new LlmRequestConfig(model, temperature, maxTokens, jsonMode, readTimeoutSec, toolSpecs);
    }

    /** Whether native tools are being offered on this call. */
    public boolean hasTools() {
        return tools != null && !tools.isEmpty();
    }
}
