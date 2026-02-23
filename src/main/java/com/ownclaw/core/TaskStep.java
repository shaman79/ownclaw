package com.ownclaw.core;

import java.util.List;
import java.util.Map;

/**
 * A single step in a {@link TaskPlan}.
 */
public record TaskStep(
    int id,
    String skill,
    Map<String, Object> params,
    List<Integer> dependsOn,
    String condition,         // nullable — condition expression (see ConditionEvaluator)
    OnFail onFail,
    boolean reversible
) {
    public enum OnFail {
        REPORT, SKIP, RETRY;

        public static OnFail fromString(String s) {
            if (s == null) return SKIP;  // default: resilient
            return switch (s.toLowerCase()) {
                case "report" -> REPORT;
                case "retry" -> RETRY;
                default -> SKIP;
            };
        }
    }
}
