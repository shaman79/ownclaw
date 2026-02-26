package com.ownclaw.agent;

import java.util.Map;

/**
 * The result returned by a tool execution, observed by the agent.
 *
 * @param tool       name of the tool that produced this observation
 * @param success    whether the tool execution succeeded
 * @param output     human-readable output summary
 * @param structured machine-readable structured data (may be empty)
 * @param durationMs wall-clock time of the tool execution
 */
public record AgentObservation(
        String tool,
        boolean success,
        String output,
        Map<String, Object> structured,
        long durationMs
) {
    public static AgentObservation success(String tool, String output, Map<String, Object> structured, long durationMs) {
        return new AgentObservation(tool, true, output, structured, durationMs);
    }

    public static AgentObservation failure(String tool, String output, long durationMs) {
        return new AgentObservation(tool, false, output, Map.of(), durationMs);
    }

    public static AgentObservation failure(String tool, String output, Map<String, Object> structured, long durationMs) {
        return new AgentObservation(tool, false, output, structured, durationMs);
    }
}
