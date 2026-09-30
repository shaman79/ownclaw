package com.ownclaw.llm;

import java.util.List;

/**
 * Per-request overrides for LLM calls.
 * Any {@code null} field means "use provider default from config."
 * <p>
 * There is no output limit here, deliberately: every provider asks for the model's own maximum,
 * so no call site can cap what the model may write.
 */
public record LlmRequestConfig(
    String model,
    Double temperature,
    boolean jsonMode,
    /**
     * Per-request HTTP read timeout override in seconds. {@code null} = use provider default.
     * Replies are streamed, so it bounds the silence between two events, not the whole call.
     */
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
    EgressContext egress,
    /** Told about every event of the streamed reply; never null (see {@link LlmProgress}). */
    LlmProgress progress
) {
    public LlmRequestConfig {
        progress = progress == null ? LlmProgress.NONE : progress;
    }

    /** Without native tools, a context or a progress hook. */
    public LlmRequestConfig(String model, Double temperature, boolean jsonMode, Integer readTimeoutSec) {
        this(model, temperature, jsonMode, readTimeoutSec, null, null, null);
    }

    /** With tools, without a context or a progress hook. */
    public LlmRequestConfig(String model, Double temperature, boolean jsonMode, Integer readTimeoutSec,
                            List<ToolSpec> tools) {
        this(model, temperature, jsonMode, readTimeoutSec, tools, null, null);
    }

    /** Use all defaults from the provider config. */
    public static final LlmRequestConfig DEFAULT = new LlmRequestConfig(null, null, false, null);

    /** This request, but offering the model these tools natively. */
    public LlmRequestConfig withTools(List<ToolSpec> toolSpecs) {
        return new LlmRequestConfig(model, temperature, jsonMode, readTimeoutSec, toolSpecs, egress, progress);
    }

    /** This request, made on behalf of a task. */
    public LlmRequestConfig withEgress(EgressContext egressContext) {
        return new LlmRequestConfig(model, temperature, jsonMode, readTimeoutSec, tools, egressContext, progress);
    }

    /** This request, telling {@code hook} about every event of the reply as it streams in. */
    public LlmRequestConfig withProgress(LlmProgress hook) {
        return new LlmRequestConfig(model, temperature, jsonMode, readTimeoutSec, tools, egress, hook);
    }

    /** Whether native tools are being offered on this call. */
    public boolean hasTools() {
        return tools != null && !tools.isEmpty();
    }
}
