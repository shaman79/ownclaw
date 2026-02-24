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
    @JsonIgnore String directAnswer,
    @JsonIgnore CreateSkillRequest createSkillRequest
) {
    /** Canonical constructor for normal plans (no direct answer). */
    public TaskPlan(List<TaskStep> steps, boolean reviewResult, int maxRetries) {
        this(steps, reviewResult, maxRetries, null, null);
    }

    /** Create a plan that carries a direct answer — no skills needed. */
    public static TaskPlan directAnswer(String answer) {
        return new TaskPlan(List.of(), false, 0, answer, null);
    }

    /** Create a plan that requests creating a missing skill (no steps yet). */
    public static TaskPlan createSkill(String name, String taskDescription) {
        return new TaskPlan(List.of(), false, 0, null, new CreateSkillRequest(name, taskDescription));
    }

    /** True when the Mentor answered from knowledge (no skill execution needed). */
    @JsonIgnore
    public boolean isDirectAnswer() {
        return directAnswer != null && !directAnswer.isBlank();
    }

    /** True when the Mentor requested creating a new skill instead of providing steps. */
    @JsonIgnore
    public boolean isCreateSkillRequest() {
        return createSkillRequest != null
                && createSkillRequest.name() != null
                && !createSkillRequest.name().isBlank();
    }

    /** Number of steps in the plan. */
    public int size() {
        return steps.size();
    }

    /** Payload for a create-skill request emitted by the Mentor. */
    public record CreateSkillRequest(String name, String taskDescription) {}
}
