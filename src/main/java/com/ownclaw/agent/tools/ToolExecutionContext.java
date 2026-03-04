package com.ownclaw.agent.tools;

import com.ownclaw.sandbox.SandboxManager;

/**
 * Execution context passed to every tool invocation.
 * Provides the tool with information about the current agent execution
 * without coupling it to the full AgentContext.
 *
 * @param userId           the user who initiated the task
 * @param taskId           unique identifier for the current task
 * @param workDir          the working directory for file operations (may be null)
 * @param cancelled        supplier to check if the task has been cancelled
 * @param progressCallback optional callback for reporting progress from long-running skills
 */
public record ToolExecutionContext(
        String userId,
        String taskId,
        String workDir,
        java.util.function.BooleanSupplier cancelled,
        SandboxManager.ProgressCallback progressCallback
) {
    /**
     * Convenience constructor without progress callback (backwards compatible).
     */
    public ToolExecutionContext(String userId, String taskId, String workDir,
                                java.util.function.BooleanSupplier cancelled) {
        this(userId, taskId, workDir, cancelled, null);
    }

    public boolean isCancelled() {
        return cancelled != null && cancelled.getAsBoolean();
    }
}
