package com.ownclaw.sandbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownclaw.skills.PythonEnvironmentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runs a skill's Python as a child process, with stall detection, progress and need_input
 * handling. Every launch goes through {@link SkillJail#prepare}: when the jail is READY the
 * process runs inside bubblewrap (the host read-only, the service's state hidden, its own
 * directory writable, a private /tmp); when it is inactive or off, unjailed; when it is
 * refusing, or cannot isolate this one launch, not at all. The environment is the jail's
 * allow-list either way.
 */
@Component
public class ProcessSandbox implements SandboxManager {

    private static final Logger log = LoggerFactory.getLogger(ProcessSandbox.class);

    private final PythonEnvironmentService pythonEnv;
    private final ObjectMapper mapper;
    private final SkillJail jail;

    /** Without a jail: for tests and tools that build a sandbox by hand. */
    public ProcessSandbox(PythonEnvironmentService pythonEnv, ObjectMapper mapper) {
        this(pythonEnv, mapper, SkillJail.off());
    }

    @Autowired
    public ProcessSandbox(PythonEnvironmentService pythonEnv, ObjectMapper mapper, SkillJail jail) {
        this.pythonEnv = pythonEnv;
        this.mapper = mapper;
        this.jail = jail;
    }

    @Override
    public Optional<String> unavailable() {
        return jail.unavailable();
    }

    @Override
    public SandboxResult execute(Path scriptPath, Path workingDir, String stdinJson,
                                 Map<String, String> envVars, int timeoutSec) {
        return execute(pythonEnv.getSystemPython(), scriptPath, workingDir, stdinJson, envVars, timeoutSec);
    }

    @Override
    public SandboxResult execute(String python, Path scriptPath, Path workingDir,
                                 String stdinJson, Map<String, String> envVars, int timeoutSec) {
        return execute(python, scriptPath, workingDir, stdinJson, envVars, timeoutSec, null, List.of());
    }

    @Override
    public SandboxResult execute(String python, Path scriptPath, Path workingDir,
                                 String stdinJson, Map<String, String> envVars, int timeoutSec,
                                 ProgressCallback progressCallback) {
        return execute(python, scriptPath, workingDir, stdinJson, envVars, timeoutSec,
                progressCallback, List.of());
    }

    @Override
    public SandboxResult execute(String python, Path scriptPath, Path workingDir,
                                 String stdinJson, Map<String, String> envVars, int timeoutSec,
                                 ProgressCallback progressCallback, List<Path> readOnly) {
        try (SkillJail.Launch launch = jail.prepare(
                List.of(python, scriptPath.toAbsolutePath().toString()), workingDir, readOnly)) {
            if (launch.refusal() != null) return SandboxResult.refused(launch.refusal());
            return progressCallback == null
                    ? run(launch, python, scriptPath, workingDir, stdinJson, envVars, timeoutSec)
                    : runWithProgress(launch, python, scriptPath, workingDir, stdinJson, envVars,
                            timeoutSec, progressCallback);
        }
    }

    private SandboxResult run(SkillJail.Launch launch, String python, Path scriptPath, Path workingDir,
                              String stdinJson, Map<String, String> envVars, int timeoutSec) {
        long startTime = System.currentTimeMillis();

        ProcessBuilder pb = new ProcessBuilder(launch.argv());
        pb.directory(workingDir.toFile());
        pb.redirectErrorStream(false);

        // Only the allow-listed environment plus the skill's declared credentials. When jailed
        // this is bubblewrap's environment, which it hands to the skill unchanged.
        SkillJail.applyEnvironment(pb, envVars);

        try {
            Process process = pb.start();

            // Write JSON input to stdin
            if (stdinJson != null) {
                try (OutputStream os = process.getOutputStream()) {
                    os.write(stdinJson.getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }
            } else {
                // Close stdin immediately so the process sees EOF if it tries to read
                process.getOutputStream().close();
            }

            // Track last time any output was produced — used for stall detection.
            AtomicLong lastActivity = new AtomicLong(System.currentTimeMillis());

            // Drain stderr async to prevent pipe-buffer deadlock.
            CompletableFuture<byte[]> stderrFuture = CompletableFuture.supplyAsync(
                    () -> drainStreamWithActivity(process.getErrorStream(), lastActivity));

            // Read stdout line-by-line so we can detect need_input immediately.
            // If a skill emits need_input in a non-interactive run it will block forever
            // waiting for user input that will never come — kill it right away.
            AtomicBoolean needInputDetected = new AtomicBoolean(false);
            CompletableFuture<byte[]> stdoutFuture = CompletableFuture.supplyAsync(() -> {
                ByteArrayOutputStream buf = new ByteArrayOutputStream(8192);
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        lastActivity.set(System.currentTimeMillis());
                        buf.write(line.getBytes(StandardCharsets.UTF_8));
                        buf.write('\n');
                        if (isNeedInputLine(line)) {
                            needInputDetected.set(true);
                            process.destroyForcibly(); // fail fast — user input will never arrive
                            break;
                        }
                    }
                } catch (IOException ignored) {}
                return buf.toByteArray();
            });

            // Stall detection: no wall-clock timeout — process runs as long as it
            // produces output. Killed only if no stdout/stderr for timeoutSec seconds.
            boolean finished = waitForWithStallDetection(process, timeoutSec, lastActivity);
            long durationMs = System.currentTimeMillis() - startTime;

            if (!finished) {
                long stallSec = (System.currentTimeMillis() - lastActivity.get()) / 1000;
                process.destroyForcibly();
                try { process.waitFor(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                String stdout = new String(stdoutFuture.join(), StandardCharsets.UTF_8);
                String stderr = new String(stderrFuture.join(), StandardCharsets.UTF_8);
                log.warn("Sandbox stalled (no output for {}s) — killed {} after {}ms total",
                        stallSec, scriptPath.getFileName(), durationMs);
                return new SandboxResult(-1, stdout, stderr, durationMs, true);
            }

            String stdout = new String(stdoutFuture.join(), StandardCharsets.UTF_8);
            String stderr = new String(stderrFuture.join(), StandardCharsets.UTF_8);

            // need_input emitted → immediate failure (process was already killed above)
            if (needInputDetected.get()) {
                log.warn("Sandbox aborted — skill emitted need_input in non-interactive execution: {}",
                        scriptPath.getFileName());
                return new SandboxResult(-1, stdout, stderr, durationMs, true);
            }

            int exitCode = process.exitValue();
            log.debug("Sandbox completed [exit={}] {} in {}ms", exitCode, scriptPath.getFileName(), durationMs);
            return new SandboxResult(exitCode, stdout, stderr, durationMs, false);

        } catch (IOException e) {
            long durationMs = System.currentTimeMillis() - startTime;
            String errMsg;
            // Jailed, the program that could not start is bubblewrap, not Python.
            if (!launch.jailed() && e.getMessage() != null && e.getMessage().contains("Cannot run program")) {
                errMsg = "Python interpreter not found at '" + python + "'. "
                        + "Install Python 3 and configure ownclaw.sandbox.python-path, "
                        + "or run /setup to reconfigure.";
                log.error("Python not found: {}. Configure python-path or install Python 3.", python);
            } else {
                errMsg = "Sandbox I/O error: " + e.getMessage();
                log.error("Sandbox I/O error for {}: {}", scriptPath.getFileName(), e.getMessage());
            }
            return new SandboxResult(-1, "", errMsg, durationMs, false);
        }
    }

    /**
     * Returns true if a stdout line looks like a need_input JSON message.
     * Used to fail-fast in non-interactive execution before the process blocks forever.
     */
    private static boolean isNeedInputLine(String line) {
        String t = line.trim();
        return t.startsWith("{") && t.contains("\"need_input\"");
    }

    /**
     * Returns true if a stdout line looks like a progress JSON message.
     */
    private static boolean isProgressLine(String line) {
        String t = line.trim();
        return t.startsWith("{") && t.contains("\"progress\"");
    }

    /**
     * Execute with a progress callback.  Progress lines emitted by the skill
     * ({@code {"type":"progress","message":"...","percent":N}}) are intercepted
     * and forwarded via the callback.  They are NOT included in the final stdout.
     */
    private SandboxResult runWithProgress(SkillJail.Launch launch, String python, Path scriptPath,
                                          Path workingDir, String stdinJson,
                                          Map<String, String> envVars, int timeoutSec,
                                          ProgressCallback progressCallback) {
        long startTime = System.currentTimeMillis();

        ProcessBuilder pb = new ProcessBuilder(launch.argv());
        pb.directory(workingDir.toFile());
        pb.redirectErrorStream(false);

        SkillJail.applyEnvironment(pb, envVars);

        try {
            Process process = pb.start();

            // Write JSON input to stdin
            if (stdinJson != null) {
                try (OutputStream os = process.getOutputStream()) {
                    os.write(stdinJson.getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }
            } else {
                process.getOutputStream().close();
            }

            // Track last time any output was produced — used for stall detection.
            AtomicLong lastActivity = new AtomicLong(System.currentTimeMillis());

            // Drain stderr async
            CompletableFuture<byte[]> stderrFuture = CompletableFuture.supplyAsync(
                    () -> drainStreamWithActivity(process.getErrorStream(), lastActivity));

            // Read stdout line-by-line; intercept progress and need_input lines.
            AtomicBoolean needInputDetected = new AtomicBoolean(false);
            CompletableFuture<byte[]> stdoutFuture = CompletableFuture.supplyAsync(() -> {
                ByteArrayOutputStream buf = new ByteArrayOutputStream(8192);
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        lastActivity.set(System.currentTimeMillis());
                        String trimmed = line.trim();

                        // Intercept progress lines
                        if (isProgressLine(trimmed)) {
                            try {
                                JsonNode node = mapper.readTree(trimmed);
                                if ("progress".equals(node.path("type").asText(""))) {
                                    String msg = node.path("message").asText("Working...");
                                    Integer pct = node.has("percent") && !node.get("percent").isNull()
                                            ? node.get("percent").asInt() : null;
                                    progressCallback.onProgress(msg, pct);
                                    continue; // Don't include progress lines in the result stdout
                                }
                            } catch (Exception e) {
                                log.debug("Failed to parse progress line: {}", trimmed);
                            }
                        }

                        // Detect need_input in non-interactive mode
                        if (isNeedInputLine(trimmed)) {
                            needInputDetected.set(true);
                            process.destroyForcibly();
                            break;
                        }

                        buf.write(line.getBytes(StandardCharsets.UTF_8));
                        buf.write('\n');
                    }
                } catch (IOException ignored) {}
                return buf.toByteArray();
            });

            // Stall detection: no wall-clock timeout.
            boolean finished = waitForWithStallDetection(process, timeoutSec, lastActivity);
            long durationMs = System.currentTimeMillis() - startTime;

            if (!finished) {
                long stallSec = (System.currentTimeMillis() - lastActivity.get()) / 1000;
                process.destroyForcibly();
                try { process.waitFor(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                String stdout = new String(stdoutFuture.join(), StandardCharsets.UTF_8);
                String stderr = new String(stderrFuture.join(), StandardCharsets.UTF_8);
                log.warn("Sandbox stalled (no output for {}s) — killed {} after {}ms total",
                        stallSec, scriptPath.getFileName(), durationMs);
                return new SandboxResult(-1, stdout, stderr, durationMs, true);
            }

            String stdout = new String(stdoutFuture.join(), StandardCharsets.UTF_8);
            String stderr = new String(stderrFuture.join(), StandardCharsets.UTF_8);

            if (needInputDetected.get()) {
                log.warn("Sandbox aborted — need_input in non-interactive execution: {}",
                        scriptPath.getFileName());
                return new SandboxResult(-1, stdout, stderr, durationMs, true);
            }

            int exitCode = process.exitValue();
            log.debug("Sandbox completed [exit={}] {} in {}ms", exitCode, scriptPath.getFileName(), durationMs);
            return new SandboxResult(exitCode, stdout, stderr, durationMs, false);

        } catch (IOException e) {
            long durationMs = System.currentTimeMillis() - startTime;
            String errMsg = !launch.jailed() && e.getMessage() != null
                    && e.getMessage().contains("Cannot run program")
                    ? "Python interpreter not found at '" + python + "'."
                    : "Sandbox I/O error: " + e.getMessage();
            log.error("Sandbox error for {}: {}", scriptPath.getFileName(), errMsg);
            return new SandboxResult(-1, "", errMsg, durationMs, false);
        }
    }

    /**
     * Read an InputStream fully, updating lastActivity on each chunk.
     * This lets stall detection know the process is still producing output.
     */
    private static byte[] drainStreamWithActivity(InputStream is, AtomicLong lastActivity) {
        try {
            ByteArrayOutputStream buf = new ByteArrayOutputStream(8192);
            byte[] chunk = new byte[8192];
            int n;
            while ((n = is.read(chunk)) != -1) {
                lastActivity.set(System.currentTimeMillis());
                buf.write(chunk, 0, n);
            }
            return buf.toByteArray();
        } catch (IOException e) {
            return new byte[0];
        }
    }

    /**
     * Wait for a process to finish with stall detection instead of a hard wall-clock timeout.
     * The process is allowed to run indefinitely as long as it keeps producing output.
     * If no output (stdout or stderr) is produced for {@code stallTimeoutSec} seconds,
     * the process is considered stalled and this method returns false.
     *
     * @return true if the process finished normally, false if it stalled
     */
    private static boolean waitForWithStallDetection(Process process, int stallTimeoutSec,
                                                     AtomicLong lastActivity) {
        long stallTimeoutMs = stallTimeoutSec * 1000L;
        while (process.isAlive()) {
            try {
                // Poll every 5 seconds
                if (process.waitFor(5, TimeUnit.SECONDS)) {
                    return true; // process finished
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            long sinceLastOutput = System.currentTimeMillis() - lastActivity.get();
            if (sinceLastOutput > stallTimeoutMs) {
                return false; // stalled
            }
        }
        return true; // process finished
    }
}
