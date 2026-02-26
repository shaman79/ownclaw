package com.ownclaw.agent.tools;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownclaw.sandbox.SandboxManager;
import com.ownclaw.sandbox.SandboxResult;
import com.ownclaw.skills.PythonEnvironmentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * A dynamically created Python-based tool, loaded from disk.
 * Treated identically to any other tool — the agent sees no distinction.
 *
 * <p>Each dynamic skill is stored as:
 * <pre>
 * skills/generated/{name}/
 *   SKILL.yaml        — metadata (name, description, parameters, etc.)
 *   skill.py           — Python implementation (stdin JSON → stdout JSON)
 *   requirements.txt   — optional pip dependencies
 * </pre>
 *
 * <p>Python contract: skill.py reads a JSON object from stdin containing the
 * tool parameters, and prints a JSON object to stdout:
 * {@code {"success": true/false, "output": "text", "data": {}}}
 *
 * <p>This class is NOT a Spring component — instances are created by
 * {@link DynamicSkillRegistry} and registered into {@link ToolRegistry} at runtime.
 */
public class DynamicSkill implements Tool {

    private static final Logger log = LoggerFactory.getLogger(DynamicSkill.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final String name;
    private final String description;
    private final Map<String, ToolParam> parameters;
    private final Path skillDir;
    private final boolean requiresNetwork;
    private final boolean hasSideEffects;
    private final int timeoutSec;
    private final SandboxManager sandbox;
    private final PythonEnvironmentService pythonEnv;

    public DynamicSkill(String name, String description, Map<String, ToolParam> parameters,
                        Path skillDir, boolean requiresNetwork, boolean hasSideEffects,
                        int timeoutSec, SandboxManager sandbox, PythonEnvironmentService pythonEnv) {
        this.name = name;
        this.description = description;
        this.parameters = parameters;
        this.skillDir = skillDir;
        this.requiresNetwork = requiresNetwork;
        this.hasSideEffects = hasSideEffects;
        this.timeoutSec = timeoutSec;
        this.sandbox = sandbox;
        this.pythonEnv = pythonEnv;
    }

    @Override public String name() { return name; }
    @Override public String description() { return description; }
    @Override public Map<String, ToolParam> inputSchema() { return parameters; }
    @Override public boolean requiresNetwork() { return requiresNetwork; }
    @Override public boolean hasSideEffects() { return hasSideEffects; }
    @Override public int estimatedMaxDurationSeconds() { return timeoutSec; }

    /** The directory containing this skill's files. */
    public Path skillDir() { return skillDir; }

    @Override
    public ToolResult execute(Map<String, Object> params, ToolExecutionContext context) {
        Path scriptPath = skillDir.resolve("skill.py");
        if (!Files.exists(scriptPath)) {
            return ToolResult.failure("Skill script not found: " + scriptPath);
        }

        try {
            // Resolve Python (creates venv + installs requirements if needed)
            var resolution = pythonEnv.resolveExecution(skillDir, name);

            // Serialize input parameters as JSON for stdin
            String inputJson = mapper.writeValueAsString(params != null ? params : Map.of());

            Map<String, String> envVars = new HashMap<>(resolution.extraEnv());

            SandboxResult result = sandbox.execute(
                    resolution.python(), scriptPath, skillDir,
                    inputJson, envVars, timeoutSec
            );

            if (result.timedOut()) {
                return ToolResult.failure("Skill '" + name + "' timed out after " + timeoutSec + "s.");
            }

            if (result.isSuccess()) {
                return parseOutput(result.stdout(), result.stderr());
            } else {
                String error = result.stderr().isBlank()
                        ? "Exit code: " + result.exitCode()
                        : result.stderr();
                return ToolResult.failure(error);
            }
        } catch (Exception e) {
            log.error("Dynamic skill '{}' execution failed: {}", name, e.getMessage());
            return ToolResult.failure("Execution error: " + e.getMessage());
        }
    }

    /**
     * Parse the Python script's stdout into a ToolResult.
     * Expected JSON: {"success": true, "output": "text", "data": {}}
     * Falls back to treating stdout as plain text if not valid JSON.
     *
     * @param stdout the script's standard output
     * @param stderr the script's standard error (included in failure messages for diagnostics)
     */
    private ToolResult parseOutput(String stdout, String stderr) {
        if (stdout == null || stdout.isBlank()) {
            String detail = (stderr != null && !stderr.isBlank())
                    ? "stderr: " + stderr.strip()
                    : "The skill's run() function may not be returning/printing output.";
            return ToolResult.failure("Tool produced no output. " + detail
                    + " Use skill_manage(action='read') to inspect the code and skill_create to fix it.");
        }

        try {
            Map<String, Object> parsed = mapper.readValue(stdout.strip(), new TypeReference<>() {});
            boolean success = Boolean.TRUE.equals(parsed.get("success"));
            String output = parsed.containsKey("output") ? String.valueOf(parsed.get("output")) : stdout;

            // Treat empty output content as failure even if success=true
            if (success && (output == null || output.isBlank() || "null".equals(output))) {
                String hint = (stderr != null && !stderr.isBlank())
                        ? " stderr: " + stderr.strip()
                        : "";
                return ToolResult.failure(
                        "Tool returned success but with empty output — this usually means the skill " +
                        "code has a bug (e.g. missing return, wrong variable, unhandled error)." + hint
                        + " Use skill_manage(action='read') to inspect and skill_create to fix it.");
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> data = parsed.containsKey("data") && parsed.get("data") instanceof Map
                    ? (Map<String, Object>) parsed.get("data")
                    : Map.of();

            // Append stderr as a warning if present on a successful result
            if (success && stderr != null && !stderr.isBlank()) {
                output = output + "\n[stderr warning: " + stderr.strip() + "]";
            }

            return success ? ToolResult.success(output, data) : ToolResult.failure(output, data);
        } catch (Exception e) {
            // Not valid JSON — treat raw stdout as a successful text result
            return ToolResult.success(stdout.strip());
        }
    }
}
