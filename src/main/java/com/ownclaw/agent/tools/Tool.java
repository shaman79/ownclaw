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
     * Whether {@link #inputSchema} is all the tool declares. False for a skill whose declaration
     * of its parameters could not be read in full ({@code DynamicSkillRegistry#readParameters}):
     * the names read are then not known to be all it takes, so a call of it is not refused for a
     * name outside them, and a model that is listed them is told the list may not be whole.
     */
    default boolean declaredInFull() { return true; }

    /**
     * Whether the tool's own code reads a parameter of this name, declared or not: a skill reads
     * an alias, or an optional setting, that its declaration never mentions. A call that writes
     * such a name is not refused for it ({@code LocalExecutor#misfit}) -- the name is not one the
     * tool would ignore. False where that is not known, as for a tool whose code cannot be read.
     */
    default boolean readsParameter(String name) { return false; }

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
     * What the tool declares of its side effects, in three states: true, false -- declared free of
     * them -- or null when it declares nothing. {@link #hasSideEffects} reads null as false, which is
     * how a tool is treated everywhere it is only a hint; a self-repair runs a skill only on an
     * explicit false ({@code SkillSelfRepair#outOfScope}), because the flag is the model's own
     * optional claim and a skill that moves mail can say nothing.
     */
    default Boolean declaredSideEffects() { return hasSideEffects() ? Boolean.TRUE : null; }

    /**
     * Estimated maximum duration in seconds. Helps the agent reason about timeouts.
     */
    default int estimatedMaxDurationSeconds() { return 30; }

    /**
     * Credential keys required by this tool (e.g. IMAP_HOST, IMAP_PASS).
     * Empty list means no credentials needed.
     */
    default List<String> requiredCredentials() { return List.of(); }

    /**
     * Those of {@link #requiredCredentials} this user has not allowed this tool to read. With any,
     * a call of it for them is not run: asked before the call by whoever makes it, and decided
     * again where the credentials are read (CredentialVault#forSkill). None for a tool that reads
     * no credential.
     */
    default List<String> ungranted(String userId) { return List.of(); }
}
