package com.ownclaw.agent;

/**
 * The final result of an agent execution.
 *
 * @param success        whether the task was completed successfully
 * @param response       the final response message to the user
 * @param trajectory     the full execution trajectory
 * @param totalSteps     number of action-observation cycles
 * @param totalDurationMs wall-clock time of the entire execution
 * @param terminationReason why the agent stopped (COMPLETED, CANCELLED, MAX_STEPS, TIMEOUT, ERROR)
 */
public record AgentResult(
        boolean success,
        String response,
        AgentTrajectory trajectory,
        int totalSteps,
        long totalDurationMs,
        TerminationReason terminationReason
) {
    public enum TerminationReason {
        /** Agent determined the task is complete and responded. */
        COMPLETED,
        /** The user cancelled the task. */
        CANCELLED,
        /** Maximum number of steps reached. */
        MAX_STEPS,
        /** Task-level timeout exceeded. */
        TIMEOUT,
        /** Unrecoverable error. */
        ERROR,
        /** Too many consecutive failures. */
        FAILURE_LIMIT
    }

    public static AgentResult completed(String response, AgentTrajectory trajectory, long durationMs) {
        return new AgentResult(true, response, trajectory, trajectory.size(), durationMs, TerminationReason.COMPLETED);
    }

    public static AgentResult cancelled(String response, AgentTrajectory trajectory, long durationMs) {
        return new AgentResult(false, response, trajectory, trajectory.size(), durationMs, TerminationReason.CANCELLED);
    }

    public static AgentResult maxSteps(String response, AgentTrajectory trajectory, long durationMs) {
        return new AgentResult(false, response, trajectory, trajectory.size(), durationMs, TerminationReason.MAX_STEPS);
    }

    public static AgentResult timeout(String response, AgentTrajectory trajectory, long durationMs) {
        return new AgentResult(false, response, trajectory, trajectory.size(), durationMs, TerminationReason.TIMEOUT);
    }

    public static AgentResult error(String response, AgentTrajectory trajectory, long durationMs) {
        return new AgentResult(false, response, trajectory, trajectory.size(), durationMs, TerminationReason.ERROR);
    }

    public static AgentResult failureLimit(String response, AgentTrajectory trajectory, long durationMs) {
        return new AgentResult(false, response, trajectory, trajectory.size(), durationMs, TerminationReason.FAILURE_LIMIT);
    }
}
