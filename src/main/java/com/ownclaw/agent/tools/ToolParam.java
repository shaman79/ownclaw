package com.ownclaw.agent.tools;

/**
 * Describes a single tool parameter for schema generation.
 *
 * @param type        the data type (string, integer, boolean, number, array, object)
 * @param description human-readable description of what this parameter is
 * @param required    whether this parameter is required
 */
public record ToolParam(
        String type,
        String description,
        boolean required
) {
    public static ToolParam required(String type, String description) {
        return new ToolParam(type, description, true);
    }

    public static ToolParam optional(String type, String description) {
        return new ToolParam(type, description, false);
    }
}
