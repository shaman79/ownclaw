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
    Map<String, Object> params
) {
    /** Backwards-compatible constructor (no skill/params context). */
    public StepResult(int stepId, boolean success, String output, int exitCode, long durationMs) {
        this(stepId, success, output, exitCode, durationMs, null, null);
    }

    public static StepResult success(int stepId, String output, long durationMs) {
        return new StepResult(stepId, true, output, 0, durationMs, null, null);
    }

    public static StepResult failure(int stepId, String output, int exitCode, long durationMs) {
        return new StepResult(stepId, false, output, exitCode, durationMs, null, null);
    }

    public static StepResult skipped(int stepId) {
        return new StepResult(stepId, false, "skipped", -1, 0, null, null);
    }

    /** Whether this step was skipped due to a condition not being met. */
    public boolean isSkipped() {
        return !success && "skipped".equals(output);
    }

    /** Create a result with full context (skill name + params). */
    public static StepResult successWithContext(int stepId, String output, long durationMs,
                                                 String skillName, Map<String, Object> params) {
        return new StepResult(stepId, true, output, 0, durationMs, skillName, params);
    }

    public static StepResult failureWithContext(int stepId, String output, int exitCode, long durationMs,
                                                 String skillName, Map<String, Object> params) {
        return new StepResult(stepId, false, output, exitCode, durationMs, skillName, params);
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
