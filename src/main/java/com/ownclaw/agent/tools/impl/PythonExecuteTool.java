package com.ownclaw.agent.tools.impl;

import com.ownclaw.agent.tools.*;
import com.ownclaw.sandbox.SandboxManager;
import com.ownclaw.sandbox.SandboxResult;
import com.ownclaw.skills.PythonEnvironmentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Executes arbitrary Python code in the sandbox.
 * This gives the agent the ability to write and run Python scripts dynamically
 * for data processing, calculations, web scraping, or any other task.
 */
@Component
public class PythonExecuteTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(PythonExecuteTool.class);
    private static final int DEFAULT_TIMEOUT_SEC = 60;

    private final SandboxManager sandbox;
    private final PythonEnvironmentService pythonEnv;

    public PythonExecuteTool(SandboxManager sandbox, PythonEnvironmentService pythonEnv) {
        this.sandbox = sandbox;
        this.pythonEnv = pythonEnv;
    }

    @Override
    public String name() { return "python_execute"; }

    @Override
    public String description() {
        return "Execute Python code in a sandboxed environment. " +
                "Write a complete Python script that prints its output to stdout. " +
                "Standard library is available. For additional packages, include them in the import " +
                "and the system will attempt to install them.";
    }

    @Override
    public Map<String, ToolParam> inputSchema() {
        var schema = new LinkedHashMap<String, ToolParam>();
        schema.put("code", ToolParam.required("string", "The Python code to execute"));
        schema.put("timeout", ToolParam.optional("integer", "Timeout in seconds (default: 60)"));
        return schema;
    }

    @Override
    public boolean hasSideEffects() { return true; }

    @Override
    public int estimatedMaxDurationSeconds() { return DEFAULT_TIMEOUT_SEC; }

    @Override
    public ToolResult execute(Map<String, Object> params, ToolExecutionContext context) {
        String code = (String) params.get("code");
        if (code == null || code.isBlank()) {
            return ToolResult.failure("No Python code provided.");
        }

        int timeout = params.containsKey("timeout") ? toInt(params.get("timeout"), DEFAULT_TIMEOUT_SEC) : DEFAULT_TIMEOUT_SEC;

        Path tempScript = null;
        try {
            // Write code to a temporary file
            tempScript = Files.createTempFile("ownclaw_py_", ".py");
            Files.writeString(tempScript, code, StandardCharsets.UTF_8);

            Path workDir = tempScript.getParent();
            String python = pythonEnv.getSystemPython();

            SandboxResult result = sandbox.execute(python, tempScript, workDir, null, Map.of(), timeout);

            Map<String, Object> structured = new LinkedHashMap<>();
            structured.put("exit_code", result.exitCode());
            structured.put("duration_ms", result.durationMs());
            structured.put("timed_out", result.timedOut());

            if (result.timedOut()) {
                String partialOutput = result.stdout().isBlank() ? "" : "\nPartial output:\n" + result.stdout();
                return ToolResult.failure("Python script timed out after " + timeout + "s." + partialOutput, structured);
            }

            if (result.isSuccess()) {
                String output = result.stdout();
                if (output.isBlank() && !result.stderr().isBlank()) {
                    // Some scripts write to stderr — include it
                    output = "stderr: " + result.stderr();
                }
                return ToolResult.success(output, structured);
            } else {
                // Combine stdout and stderr for error reporting
                String errorOutput = result.stderr().isBlank()
                        ? "Exit code: " + result.exitCode()
                        : result.stderr();
                if (!result.stdout().isBlank()) {
                    errorOutput = "stdout:\n" + result.stdout() + "\nstderr:\n" + errorOutput;
                }
                return ToolResult.failure(errorOutput, structured);
            }
        } catch (IOException e) {
            log.error("Python execute failed: {}", e.getMessage());
            return ToolResult.failure("Failed to execute Python code: " + e.getMessage());
        } finally {
            // Clean up temp script
            if (tempScript != null) {
                try { Files.deleteIfExists(tempScript); } catch (IOException ignored) {}
            }
        }
    }

    private int toInt(Object value, int defaultValue) {
        if (value instanceof Number n) return n.intValue();
        try { return Integer.parseInt(value.toString()); } catch (Exception e) { return defaultValue; }
    }
}
