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
    List<ToolSpec> tools,
    /**
     * On whose behalf this call is made, for the cloud gateway. Null means unclassified, and
     * an unclassified cloud call is refused — a local call ignores it.
     */
    EgressContext egress
) {
    /** Without native tools or a context — every call site that existed before either did. */
    public LlmRequestConfig(String model, Double temperature, Integer maxTokens,
                            boolean jsonMode, Integer readTimeoutSec) {
        this(model, temperature, maxTokens, jsonMode, readTimeoutSec, null, null);
    }

    /** With tools, without a context. */
    public LlmRequestConfig(String model, Double temperature, Integer maxTokens,
                            boolean jsonMode, Integer readTimeoutSec, List<ToolSpec> tools) {
        this(model, temperature, maxTokens, jsonMode, readTimeoutSec, tools, null);
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
        return new LlmRequestConfig(model, temperature, maxTokens, jsonMode, readTimeoutSec, toolSpecs, egress);
    }

    /** This request, made on behalf of a task. */
    public LlmRequestConfig withEgress(EgressContext egressContext) {
        return new LlmRequestConfig(model, temperature, maxTokens, jsonMode, readTimeoutSec, tools, egressContext);
    }

    /** Whether native tools are being offered on this call. */
    public boolean hasTools() {
        return tools != null && !tools.isEmpty();
    }
}
