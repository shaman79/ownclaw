package com.ownclaw.agent.tools;

import java.util.List;
import java.util.Map;

/**
 * A tool is a discrete capability the agent can invoke.
 * Tools are discovered at runtime and presented to the LLM as available actions.
 *
 * Each tool declares its own schema (parameter descriptions and types)
 * so the agent can reason about what parameters to provide.
 */
public interface Tool {

    /**
     * Unique name of this tool, used by the LLM to select it.
     * Must be a simple identifier (lowercase, underscores allowed).
     */
    String name();

    /**
     * Human-readable description of what this tool does.
     * This is included in the system prompt for the LLM.
     */
    String description();

    /**
     * JSON-schema-like description of the expected input parameters.
     * Keys are parameter names, values are description strings.
     * This is included in the system prompt for the LLM.
     */
    Map<String, ToolParam> inputSchema();

    /**
     * Execute the tool with the given parameters.
     *
     * @param params   the parameters provided by the agent
     * @param context  execution context (userId, taskId, etc.)
     * @return the result of the tool execution
     */
    ToolResult execute(Map<String, Object> params, ToolExecutionContext context);

    /**
     * Whether this tool requires network access. Allows the agent to know
     * if connectivity is needed.
     */
    default boolean requiresNetwork() { return false; }

    /**
     * Whether this tool can have side effects (write files, send emails, etc.).
     * Informational — helps the critic evaluate risk before execution.
     */
    default boolean hasSideEffects() { return false; }

    /**
     * Estimated maximum duration in seconds. Helps the agent reason about timeouts.
     */
    default int estimatedMaxDurationSeconds() { return 30; }

    /**
     * Credential keys required by this tool (e.g. IMAP_HOST, IMAP_PASS).
     * Empty list means no credentials needed.
     */
    default List<String> requiredCredentials() { return List.of(); }
}
