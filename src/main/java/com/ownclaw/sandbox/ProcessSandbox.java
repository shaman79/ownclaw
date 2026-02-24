package com.ownclaw.sandbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownclaw.skills.PythonEnvironmentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Development sandbox using Java ProcessBuilder.
 * Provides basic isolation via working-dir + timeout, but no namespace/filesystem isolation.
 * Used on Windows and for dev-mode on Linux. Production uses BubblewrapSandbox (Phase 3).
 */
@Component
public class ProcessSandbox implements SandboxManager {

    private static final Logger log = LoggerFactory.getLogger(ProcessSandbox.class);

    private final PythonEnvironmentService pythonEnv;
    private final ObjectMapper mapper;

    public ProcessSandbox(PythonEnvironmentService pythonEnv, ObjectMapper mapper) {
        this.pythonEnv = pythonEnv;
        this.mapper = mapper;
    }

    @Override
    public SandboxResult execute(Path scriptPath, Path workingDir, String stdinJson,
                                 Map<String, String> envVars, int timeoutSec) {
        return execute(pythonEnv.getSystemPython(), scriptPath, workingDir, stdinJson, envVars, timeoutSec);
    }

    @Override
    public SandboxResult execute(String python, Path scriptPath, Path workingDir,
                                 String stdinJson, Map<String, String> envVars, int timeoutSec) {
        long startTime = System.currentTimeMillis();

        ProcessBuilder pb = new ProcessBuilder(python, scriptPath.toAbsolutePath().toString());
        pb.directory(workingDir.toFile());
        pb.redirectErrorStream(false);

        // Inject env vars (credentials, config)
        Map<String, String> env = pb.environment();
        if (envVars != null) {
            env.putAll(envVars);
        }

        try {
            Process process = pb.start();

            // Write JSON input to stdin
            if (stdinJson != null) {
                try (OutputStream os = process.getOutputStream()) {
                    os.write(stdinJson.getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }
            }

            // Drain stdout and stderr concurrently to prevent pipe buffer deadlock.
            // If the child writes more than the OS pipe buffer (~4-64KB), it blocks
            // until someone reads the pipe. If we only read after waitFor(), deadlock.
            CompletableFuture<byte[]> stdoutFuture = CompletableFuture.supplyAsync(
                    () -> drainStream(process.getInputStream()));
            CompletableFuture<byte[]> stderrFuture = CompletableFuture.supplyAsync(
                    () -> drainStream(process.getErrorStream()));

            // Wait with timeout
            boolean finished = process.waitFor(timeoutSec, TimeUnit.SECONDS);
            long durationMs = System.currentTimeMillis() - startTime;

            if (!finished) {
                process.destroyForcibly();
                String partialStdout = new String(stdoutFuture.join(), StandardCharsets.UTF_8);
                String partialStderr = new String(stderrFuture.join(), StandardCharsets.UTF_8);
                log.warn("Sandbox timeout after {}s for {}", timeoutSec, scriptPath.getFileName());
                return new SandboxResult(-1, partialStdout, partialStderr, durationMs, true);
            }

            String stdout = new String(stdoutFuture.join(), StandardCharsets.UTF_8);
            String stderr = new String(stderrFuture.join(), StandardCharsets.UTF_8);
            int exitCode = process.exitValue();

            log.debug("Sandbox completed [exit={}] {} in {}ms", exitCode, scriptPath.getFileName(), durationMs);
            return new SandboxResult(exitCode, stdout, stderr, durationMs, false);

        } catch (IOException e) {
            long durationMs = System.currentTimeMillis() - startTime;
            String errMsg;
            if (e.getMessage() != null && e.getMessage().contains("Cannot run program")) {
                errMsg = "Python interpreter not found at '" + python + "'. "
                        + "Install Python 3 and configure ownclaw.sandbox.python-path, "
                        + "or run /setup to reconfigure.";
                log.error("Python not found: {}. Configure python-path or install Python 3.", python);
            } else {
                errMsg = "Sandbox I/O error: " + e.getMessage();
                log.error("Sandbox I/O error for {}: {}", scriptPath.getFileName(), e.getMessage());
            }
            return new SandboxResult(-1, "", errMsg, durationMs, false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            long durationMs = System.currentTimeMillis() - startTime;
            return new SandboxResult(-1, "", "Interrupted", durationMs, false);
        }
    }

    /** Read an InputStream fully into a byte array. Safe to call from a background thread. */
    private static byte[] drainStream(InputStream is) {
        try {
            ByteArrayOutputStream buf = new ByteArrayOutputStream(8192);
            byte[] chunk = new byte[8192];
            int n;
            while ((n = is.read(chunk)) != -1) {
                buf.write(chunk, 0, n);
            }
            return buf.toByteArray();
        } catch (IOException e) {
            return new byte[0];
        }
    }

    /**
     * Interactive execution: keeps the process alive for stdin/stdout exchanges.
     * Reads stdout line by line. On "need_input" JSON, calls inputCallback and writes response to stdin.
     * This enables skills to pause, ask the user a question, and continue.
     */
    @Override
    public SandboxResult executeInteractive(String python, Path scriptPath, Path workingDir,
                                            String stdinJson, Map<String, String> envVars,
                                            int timeoutSec,
                                            java.util.function.Function<String, String> inputCallback) {
        long startTime = System.currentTimeMillis();

        ProcessBuilder pb = new ProcessBuilder(python, scriptPath.toAbsolutePath().toString());
        pb.directory(workingDir.toFile());
        pb.redirectErrorStream(false);

        if (envVars != null) {
            pb.environment().putAll(envVars);
        }

        try {
            Process process = pb.start();

            // Write initial params but keep stdin open for follow-up input
            OutputStream stdin = process.getOutputStream();
            if (stdinJson != null) {
                stdin.write(stdinJson.getBytes(StandardCharsets.UTF_8));
                stdin.write('\n');
                stdin.flush();
            }

            // Drain stderr in background
            CompletableFuture<byte[]> stderrFuture = CompletableFuture.supplyAsync(
                    () -> drainStream(process.getErrorStream()));

                // Read stdout line by line
            StringBuilder allStdout = new StringBuilder();
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));

            long deadline = System.currentTimeMillis() + (timeoutSec * 1000L);
            String line;
            while ((line = reader.readLine()) != null) {
                allStdout.append(line).append('\n');

                if (System.currentTimeMillis() > deadline) {
                    process.destroyForcibly();
                    String stderr = new String(stderrFuture.join(), StandardCharsets.UTF_8);
                    long durationMs = System.currentTimeMillis() - startTime;
                    return new SandboxResult(-1, allStdout.toString(), stderr, durationMs, true);
                }

                // Check if this line is a need_input JSON
                String trimmed = line.trim();
                if (trimmed.startsWith("{")) {
                    try {
                        JsonNode node = mapper.readTree(trimmed);
                        if ("need_input".equals(node.path("type").asText(""))) {
                            String prompt = formatNeedInputPrompt(node);
                            String userResponse = inputCallback.apply(prompt);
                            String value = normalizeUserResponse(userResponse, node);

                            // Write response back to process stdin as JSON
                            String responseJson = mapper
                                    .writeValueAsString(Map.of("type", "user_input", "value",
                                            value != null ? value : ""));
                            stdin.write(responseJson.getBytes(StandardCharsets.UTF_8));
                            stdin.write('\n');
                            stdin.flush();
                        }
                    } catch (Exception e) {
                        log.debug("Non-JSON or parsing error in interactive line: {}", trimmed);
                    }
                }
            }

            // Close stdin since we're done
            try { stdin.close(); } catch (Exception ignored) {}

            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            long durationMs = System.currentTimeMillis() - startTime;

            if (!finished) {
                process.destroyForcibly();
                String stderr = new String(stderrFuture.join(), StandardCharsets.UTF_8);
                return new SandboxResult(-1, allStdout.toString(), stderr, durationMs, true);
            }

            String stderr = new String(stderrFuture.join(), StandardCharsets.UTF_8);
            int exitCode = process.exitValue();
            return new SandboxResult(exitCode, allStdout.toString(), stderr, durationMs, false);

        } catch (IOException e) {
            long durationMs = System.currentTimeMillis() - startTime;
            return new SandboxResult(-1, "", "Interactive sandbox I/O error: " + e.getMessage(), durationMs, false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            long durationMs = System.currentTimeMillis() - startTime;
            return new SandboxResult(-1, "", "Interrupted", durationMs, false);
        }
    }

    private String formatNeedInputPrompt(JsonNode node) {
        String prompt = node.path("prompt").asText("Input needed:");
        JsonNode options = node.get("options");
        if (options != null && options.isArray() && options.size() > 0) {
            StringBuilder sb = new StringBuilder(prompt);
            sb.append("\n\nOptions:\n");
            for (int i = 0; i < options.size(); i++) {
                JsonNode opt = options.get(i);
                String label;
                if (opt.isTextual()) {
                    label = opt.asText();
                } else {
                    label = opt.path("label").asText(opt.path("value").asText(""));
                    if (label.isBlank()) {
                        label = opt.toString();
                    }
                }
                sb.append(i + 1).append(". ").append(label).append('\n');
            }
            sb.append("\nReply with the number or the value.");
            return sb.toString();
        }
        return prompt;
    }

    private String normalizeUserResponse(String userResponse, JsonNode needInputNode) {
        if (userResponse == null) return "";
        String trimmed = userResponse.trim();
        if (trimmed.isEmpty()) return "";

        JsonNode options = needInputNode.get("options");
        if (options != null && options.isArray() && options.size() > 0) {
            try {
                int idx = Integer.parseInt(trimmed);
                if (idx >= 1 && idx <= options.size()) {
                    JsonNode opt = options.get(idx - 1);
                    if (opt.isTextual()) return opt.asText();
                    String value = opt.path("value").asText("");
                    if (!value.isBlank()) return value;
                    String label = opt.path("label").asText("");
                    if (!label.isBlank()) return label;
                    return opt.toString();
                }
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return trimmed;
    }
}
