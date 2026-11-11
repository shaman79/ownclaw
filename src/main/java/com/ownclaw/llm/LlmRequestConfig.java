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
    /**
     * Told about every event of the streamed reply, and handed the call's cancel while it runs;
     * never null (see {@link LlmProgress}).
     */
    LlmProgress progress,
    /**
     * Answer straight away, without reasoning first. Only the local provider reads it: a thinking
     * model on that host writes about 8 tokens a second, so minutes of reasoning ahead of a short
     * answer -- a summary, a reply about a result it was handed -- are minutes of waiting. Off
     * by default; the cloud providers ignore it.
     */
    boolean withoutThinking,
    /**
     * How much the model thinks before it answers: "low", "medium" or "high", the owner's
     * thinking effort, or null for the model's own default. Anthropic sends it as
     * {@code output_config.effort} to a model that takes one; the local provider reads "low" as
     * {@link #withoutThinking}; OpenAI ignores it.
     */
    String effort,
    /**
     * Hand the reply back as the model wrote it: the cloud gateway does not put the user's values
     * back where it names their placeholders, and remembers the text as one it handed out so
     * ({@link EgressContext}). For a caller that sends the reply back to the model as the model's
     * own turn -- a skill's code written in parts -- and puts the values back itself, once, in what
     * it keeps ({@link LlmProvider#restored}). A provider that writes no placeholders ignores it.
     */
    boolean asWritten
) {
    public LlmRequestConfig {
        progress = progress == null ? LlmProgress.NONE : progress;
    }

    /** Without native tools, a context or a progress hook. */
    public LlmRequestConfig(String model, Double temperature, boolean jsonMode) {
        this(model, temperature, jsonMode, null, null, null, false, null, false);
    }

    /** With tools, without a context or a progress hook. */
    public LlmRequestConfig(String model, Double temperature, boolean jsonMode, List<ToolSpec> tools) {
        this(model, temperature, jsonMode, tools, null, null, false, null, false);
    }

    /** This request, but offering the model these tools natively. */
    public LlmRequestConfig withTools(List<ToolSpec> toolSpecs) {
        return new LlmRequestConfig(model, temperature, jsonMode, toolSpecs, egress, progress, withoutThinking,
                effort, asWritten);
    }

    /** This request, made on behalf of a task. */
    public LlmRequestConfig withEgress(EgressContext egressContext) {
        return new LlmRequestConfig(model, temperature, jsonMode, tools, egressContext, progress, withoutThinking,
                effort, asWritten);
    }

    /** This request, telling {@code hook} about every event of the reply as it streams in. */
    public LlmRequestConfig withProgress(LlmProgress hook) {
        return new LlmRequestConfig(model, temperature, jsonMode, tools, egress, hook, withoutThinking, effort,
                asWritten);
    }

    /** This request, answered without reasoning first (see {@link #withoutThinking()}). */
    public LlmRequestConfig answeringDirectly() {
        return new LlmRequestConfig(model, temperature, jsonMode, tools, egress, progress, true, effort, asWritten);
    }

    /** This request, at this thinking effort (see {@link #effort()}). */
    public LlmRequestConfig withEffort(String level) {
        return new LlmRequestConfig(model, temperature, jsonMode, tools, egress, progress, withoutThinking, level,
                asWritten);
    }

    /** This request, its reply handed back as the model wrote it (see {@link #asWritten()}). */
    public LlmRequestConfig repliesAsWritten() {
        return new LlmRequestConfig(model, temperature, jsonMode, tools, egress, progress, withoutThinking, effort,
                true);
    }

    /** Whether native tools are being offered on this call. */
    public boolean hasTools() {
        return tools != null && !tools.isEmpty();
    }
}
