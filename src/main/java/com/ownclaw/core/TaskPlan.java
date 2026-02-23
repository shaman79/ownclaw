package com.ownclaw.core;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.List;

/**
 * A structured execution plan produced by the Mentor (or retrieved from cache).
 * Represents a DAG of steps with dependencies and on-failure policies.
 *
 * <p>When the Mentor can answer the question directly from its system context
 * (e.g. current date/time), it returns a {@link #directAnswer} instead of steps.</p>
 */
public record TaskPlan(
    List<TaskStep> steps,
    boolean reviewResult,
    int maxRetries,
    @JsonIgnore String directAnswer
) {
    /** Canonical constructor for normal plans (no direct answer). */
    public TaskPlan(List<TaskStep> steps, boolean reviewResult, int maxRetries) {
        this(steps, reviewResult, maxRetries, null);
    }

    /** Create a plan that carries a direct answer — no skills needed. */
    public static TaskPlan directAnswer(String answer) {
        return new TaskPlan(List.of(), false, 0, answer);
    }

    /** True when the Mentor answered from knowledge (no skill execution needed). */
    @JsonIgnore
    public boolean isDirectAnswer() {
        return directAnswer != null && !directAnswer.isBlank();
    }

    /** Number of steps in the plan. */
    public int size() {
        return steps.size();
    }
}
