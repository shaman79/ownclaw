package com.ownclaw.agent.tools;

/**
 * Execution context passed to every tool invocation.
 * Provides the tool with information about the current agent execution
 * without coupling it to the full AgentContext.
 *
 * @param userId    the user who initiated the task
 * @param taskId    unique identifier for the current task
 * @param workDir   the working directory for file operations (may be null)
 * @param cancelled supplier to check if the task has been cancelled
 */
public record ToolExecutionContext(
        String userId,
        String taskId,
        String workDir,
        java.util.function.BooleanSupplier cancelled
) {
    public boolean isCancelled() {
        return cancelled != null && cancelled.getAsBoolean();
    }
}
