package com.ownclaw.core;

import java.util.Map;

/**
 * Result of executing a single plan step.
 */
public record StepResult(
    int stepId,
    boolean success,
    String output,
    int exitCode,
    long durationMs,
    String skillName,
    Map<String, Object> params,
    /**
     * When {@code true}, the failure is definitively unrecoverable at the plan level
     * (e.g. wrong input params, bad URL format) and plan execution must abort immediately.
     * Independent downstream steps should NOT continue — the plan's intent is broken.
     */
    boolean terminal
) {
    /** Backwards-compatible constructor (no skill/params context, non-terminal). */
    public StepResult(int stepId, boolean success, String output, int exitCode, long durationMs) {
        this(stepId, success, output, exitCode, durationMs, null, null, false);
    }

    public static StepResult success(int stepId, String output, long durationMs) {
        return new StepResult(stepId, true, output, 0, durationMs, null, null, false);
    }

    public static StepResult failure(int stepId, String output, int exitCode, long durationMs) {
        return new StepResult(stepId, false, output, exitCode, durationMs, null, null, false);
    }

    /**
     * A definitive, plan-level failure that requires immediate abort.
     * Use for failures where the plan's intent is fundamentally wrong and
     * no independent downstream step can produce a meaningful result.
     * Examples: bad_params (wrong input type/format), data_format.
     */
    public static StepResult definitiveFailure(int stepId, String output, int exitCode, long durationMs) {
        return new StepResult(stepId, false, output, exitCode, durationMs, null, null, true);
    }

    public static StepResult skipped(int stepId) {
        return new StepResult(stepId, false, "skipped", -1, 0, null, null, false);
    }

    /** Whether this step was skipped due to a condition not being met. */
    public boolean isSkipped() {
        return !success && "skipped".equals(output);
    }

    /** Create a result with full context (skill name + params). */
    public static StepResult successWithContext(int stepId, String output, long durationMs,
                                                 String skillName, Map<String, Object> params) {
        return new StepResult(stepId, true, output, 0, durationMs, skillName, params, false);
    }

    public static StepResult failureWithContext(int stepId, String output, int exitCode, long durationMs,
                                                 String skillName, Map<String, Object> params) {
        return new StepResult(stepId, false, output, exitCode, durationMs, skillName, params, false);
    }

    /** Human-readable label for this step, e.g. "http_request(url=https://example.com)". */
    public String label() {
        if (skillName == null) return "Step " + stepId;
        if (params == null || params.isEmpty()) return skillName;
        // Pick the most informative param (url > query > path > command > first)
        for (String key : new String[]{"url", "query", "path", "command"}) {
            Object v = params.get(key);
            if (v != null) {
                String vs = v.toString();
                if (vs.length() > 80) vs = vs.substring(0, 77) + "...";
                return skillName + "(" + key + "=" + vs + ")";
            }
        }
        var first = params.entrySet().iterator().next();
        String vs = first.getValue().toString();
        if (vs.length() > 60) vs = vs.substring(0, 57) + "...";
        return skillName + "(" + first.getKey() + "=" + vs + ")";
    }
}
