package com.ownclaw.agent.tools.impl;

import com.ownclaw.agent.tools.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Executes shell commands on the host system.
 * This is a fundamental tool that enables the agent to interact with the OS.
 */
@Component
public class ShellCommandTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(ShellCommandTool.class);
    private static final int DEFAULT_TIMEOUT_SEC = 60;
    private static final int MAX_OUTPUT_LENGTH = 50_000;

    @Override
    public String name() { return "shell_command"; }

    @Override
    public String description() {
        return "Execute a shell command on the host system and return its output. " +
                "Use for system operations, file manipulation, running programs, etc.";
    }

    @Override
    public Map<String, ToolParam> inputSchema() {
        var schema = new LinkedHashMap<String, ToolParam>();
        schema.put("command", ToolParam.required("string", "The shell command to execute"));
        schema.put("working_dir", ToolParam.optional("string", "Working directory for the command"));
        schema.put("timeout", ToolParam.optional("integer", "Timeout in seconds (default: 60)"));
        return schema;
    }

    @Override
    public boolean hasSideEffects() { return true; }

    @Override
    public int estimatedMaxDurationSeconds() { return DEFAULT_TIMEOUT_SEC; }

    @Override
    public ToolResult execute(Map<String, Object> params, ToolExecutionContext context) {
        String command = (String) params.get("command");
        if (command == null || command.isBlank()) {
            return ToolResult.failure("No command provided.");
        }

        String workDir = params.containsKey("working_dir") ? params.get("working_dir").toString() : null;
        int timeout = params.containsKey("timeout") ? toInt(params.get("timeout"), DEFAULT_TIMEOUT_SEC) : DEFAULT_TIMEOUT_SEC;

        try {
            ProcessBuilder pb = buildProcess(command);
            if (workDir != null && !workDir.isBlank()) {
                pb.directory(new java.io.File(workDir));
            }
            pb.redirectErrorStream(true);

            long startMs = System.currentTimeMillis();
            Process process = pb.start();

            // Read output
            String output;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                output = reader.lines().collect(Collectors.joining("\n"));
            }

            boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);
            long durationMs = System.currentTimeMillis() - startMs;

            if (!finished) {
                process.destroyForcibly();
                return ToolResult.failure("Command timed out after " + timeout + "s. Partial output:\n" +
                        truncate(output, MAX_OUTPUT_LENGTH),
                        Map.of("exit_code", -1, "timed_out", true, "duration_ms", durationMs));
            }

            int exitCode = process.exitValue();
            output = truncate(output, MAX_OUTPUT_LENGTH);

            if (exitCode == 0) {
                return ToolResult.success(output,
                        Map.of("exit_code", exitCode, "duration_ms", durationMs));
            } else {
                return ToolResult.failure("Command exited with code " + exitCode + ":\n" + output,
                        Map.of("exit_code", exitCode, "duration_ms", durationMs));
            }
        } catch (Exception e) {
            log.error("Shell command execution failed: {}", e.getMessage());
            return ToolResult.failure("Failed to execute command: " + e.getMessage());
        }
    }

    private ProcessBuilder buildProcess(String command) {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            return new ProcessBuilder("cmd", "/c", command);
        } else {
            return new ProcessBuilder("sh", "-c", command);
        }
    }

    private int toInt(Object value, int defaultValue) {
        if (value instanceof Number n) return n.intValue();
        try { return Integer.parseInt(value.toString()); } catch (Exception e) { return defaultValue; }
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "\n...[output truncated]";
    }
}
