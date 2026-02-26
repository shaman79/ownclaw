package com.ownclaw.agent.tools;

import java.util.List;
import java.util.Map;

/**
 * The result returned by a tool execution.
 *
 * @param success    whether the tool completed successfully
 * @param output     human-readable output (included in agent context)
 * @param structured machine-readable structured data (for downstream tools or the agent to parse)
 * @param artifacts  any produced artifacts (file paths, URLs, etc.)
 * @param metadata   additional metadata (timing, resource usage, etc.)
 */
public record ToolResult(
        boolean success,
        String output,
        Map<String, Object> structured,
        List<String> artifacts,
        Map<String, Object> metadata
) {
    public static ToolResult success(String output) {
        return new ToolResult(true, output, Map.of(), List.of(), Map.of());
    }

    public static ToolResult success(String output, Map<String, Object> structured) {
        return new ToolResult(true, output, structured, List.of(), Map.of());
    }

    public static ToolResult success(String output, Map<String, Object> structured, List<String> artifacts) {
        return new ToolResult(true, output, structured, artifacts, Map.of());
    }

    public static ToolResult failure(String output) {
        return new ToolResult(false, output, Map.of(), List.of(), Map.of());
    }

    public static ToolResult failure(String output, Map<String, Object> structured) {
        return new ToolResult(false, output, structured, List.of(), Map.of());
    }
}
