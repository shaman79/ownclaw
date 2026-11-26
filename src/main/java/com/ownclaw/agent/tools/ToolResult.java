package com.ownclaw.agent.tools;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The result returned by a tool execution.
 * <p>
 * The output is everything the tool produced. A skill's structured data used to travel beside it
 * in a map that no prompt, record or reference ever read; it is part of the output now (see
 * DynamicSkill.parseOutput), and the map is gone.
 *
 * @param success whether the tool completed successfully
 * @param output  what it produced, whole (included in agent context)
 * @param error   of a failure, what went wrong as the tool said it -- a skill's own error message
 *                and what it wrote to stderr, the names of the files it was handed taken out --
 *                never what it printed as output, the data it read; null when it said nothing of
 *                its own, or did not fail. The cloud is shown it where the output is private
 *                because of a file the task holds ({@code AgentContext#decide}).
 */
public record ToolResult(boolean success, String output, String error) {

    public ToolResult(boolean success, String output) {
        this(success, output, null);
    }

    /** One JSON value, and nothing but whitespace after it. */
    private static final ObjectReader ONE_VALUE = new ObjectMapper().reader()
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public static ToolResult success(String output) {
        return new ToolResult(true, output);
    }

    public static ToolResult failure(String output) {
        return new ToolResult(false, output);
    }

    /** A failure whose error, as {@code error} says it, is not all of its output. */
    public static ToolResult failure(String output, String error) {
        return new ToolResult(false, output, error == null || error.isBlank() ? null : error);
    }

    /**
     * {@code output} as a JSON object -- exactly one, with nothing after it -- or null when it is
     * not one. The one reading of a JSON output for everyone who reads one: Artifact.succeeded
     * and the descriptor, a {@code {{N.field}}} reference, and DynamicSkill, which keeps such an
     * output one object by adding what else the process wrote as keys rather than as text after
     * it. Two readings are how a skill's {@code "ok": false} was once found by one and missed by
     * the other.
     */
    public static ObjectNode jsonObject(String output) {
        if (output == null || output.isBlank()) return null;
        try {
            return ONE_VALUE.readTree(output) instanceof ObjectNode object ? object : null;
        } catch (Exception e) {
            return null;
        }
    }
}
