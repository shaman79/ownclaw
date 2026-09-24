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
 * @param ownerText      what the owner's own screen shows in place of {@code response}, or null
 *                       when that is the same text. Set only for an answer that holds private
 *                       data: {@code response} is then a note that the answer exists, and it is
 *                       what every other reader gets -- history, memory, Telegram, the scheduler.
 */
public record AgentResult(
        boolean success,
        String response,
        AgentTrajectory trajectory,
        int totalSteps,
        long totalDurationMs,
        TerminationReason terminationReason,
        String taskId,
        String ownerText
) {
    /** Without an id — every factory below builds this shape and the loop stamps it after. */
    public AgentResult(boolean success, String response, AgentTrajectory trajectory,
                       int totalSteps, long totalDurationMs, TerminationReason terminationReason) {
        this(success, response, trajectory, totalSteps, totalDurationMs, terminationReason, null, null);
    }

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
        /** The cloud request contained bytes of a PRIVATE artifact; nothing was sent. */
        PRIVACY_BLOCKED,
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

    /**
     * Which task produced this result, once stamped by the loop; null before that.
     * <p>
     * The result travelled without its own identity, so anything downstream that needed to look
     * up what the task had done could only ask "the most recent for this user" — which is a
     * different task as soon as two can overlap. Carrying the id removes the guess.
     */
    public String taskId() {
        return taskId;
    }

    /**
     * Stamp the id of the task that produced this.
     * <p>
     * Carries {@code ownerText}: the loop stamps the id after the answer is made, so dropping it
     * here would lose the owner's answer silently, with the note that it exists delivered in
     * its place.
     */
    public AgentResult withTaskId(String taskId) {
        return new AgentResult(success, response, trajectory, totalSteps, totalDurationMs,
                terminationReason, taskId, ownerText);
    }

    /** The same result, with the text only the owner's own screen shows. */
    public AgentResult withOwnerText(String ownerText) {
        return new AgentResult(success, response, trajectory, totalSteps, totalDurationMs,
                terminationReason, taskId, ownerText);
    }

    /**
     * What the owner's screen shows: the private answer when there is one, otherwise the
     * response. Only for that screen; anything that stores or forwards reads {@link #response}.
     */
    public String shown() {
        return ownerText != null ? ownerText : response;
    }

    /** Whether the task stopped to ask the user something, rather than succeeding or failing. */
    public boolean awaitingUser() {
        return terminationReason == TerminationReason.NEEDS_INPUT;
    }

    public static AgentResult failureLimit(String response, AgentTrajectory trajectory, long durationMs) {
        return new AgentResult(false, response, trajectory, trajectory.size(), durationMs, TerminationReason.FAILURE_LIMIT);
    }

    /** The gateway refused to send. Not a success, and not a reasoning failure either. */
    public static AgentResult privacyBlocked(String response, AgentTrajectory trajectory, long durationMs) {
        return new AgentResult(false, response, trajectory, trajectory.size(), durationMs, TerminationReason.PRIVACY_BLOCKED);
    }
}
