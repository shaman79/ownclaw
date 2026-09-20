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
        /** Task-level timeout exceeded (legacy — prefer STALLED). */
        TIMEOUT,
        /** Task stalled — no progress for stall-timeout seconds. */
        STALLED,
        /** Unrecoverable error. */
        ERROR,
        /** Too many consecutive failures. */
        FAILURE_LIMIT,
        /**
         * The agent asked the user a question and is waiting for the answer.
         *
         * <p>Not a success and not a failure, which is exactly why it needed its own reason.
         * Asking a question used to return COMPLETED, so a task that had done nothing but ask
         * was recorded as having finished the job: the event log said "info", the status line
         * said COMPLETED, and the memory layer stored it as a worked example to imitate later.
         * On an unattended task — one started by the scheduler, with nobody at the chat — that
         * was worse than cosmetic: the question went nowhere, the task stopped, and the run was
         * filed as a success, so nothing ever flagged it.
         */
        NEEDS_INPUT
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

    public static AgentResult stalled(String response, AgentTrajectory trajectory, long durationMs) {
        return new AgentResult(false, response, trajectory, trajectory.size(), durationMs, TerminationReason.STALLED);
    }

    public static AgentResult error(String response, AgentTrajectory trajectory, long durationMs) {
        return new AgentResult(false, response, trajectory, trajectory.size(), durationMs, TerminationReason.ERROR);
    }

    /**
     * The agent asked the user something and stopped to wait for the answer.
     * <p>
     * {@code success} is false because the task is not done. Callers that show the user an
     * outcome should check {@link #awaitingUser()} first — "waiting for you" is not "failed",
     * and rendering it as a failure is the opposite error to the one this reason fixes.
     */
    public static AgentResult needsInput(String question, AgentTrajectory trajectory, long durationMs) {
        return new AgentResult(false, question, trajectory, trajectory.size(), durationMs, TerminationReason.NEEDS_INPUT);
    }

    /** Whether the task stopped to ask the user something, rather than succeeding or failing. */
    public boolean awaitingUser() {
        return terminationReason == TerminationReason.NEEDS_INPUT;
    }

    public static AgentResult failureLimit(String response, AgentTrajectory trajectory, long durationMs) {
        return new AgentResult(false, response, trajectory, trajectory.size(), durationMs, TerminationReason.FAILURE_LIMIT);
    }
}
