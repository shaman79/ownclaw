package com.ownclaw.sandbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runs a skill's Python as a child process, with stall detection and progress forwarding.
 * Every launch goes through {@link SkillJail#prepare}: when the jail is READY the
 * process runs inside bubblewrap (the host read-only, the service's state hidden, its own
 * directory writable, its account's /tmp or a fresh one); when it is inactive or off, unjailed; when it is
 * refusing, or cannot isolate this one launch, not at all. The environment is the jail's
 * allow-list either way.
 */
@Component
public class ProcessSandbox implements SandboxManager {

    private static final Logger log = LoggerFactory.getLogger(ProcessSandbox.class);

    /** Reads the progress lines a skill prints; nothing else. */
    private static final ObjectMapper PROGRESS = new ObjectMapper();

    private final SkillJail jail;

    public ProcessSandbox(SkillJail jail) {
        this.jail = jail;
    }

    @Override
    public Optional<String> unavailable() {
        return jail.unavailable();
    }

    @Override
    public SandboxResult execute(String python, Path scriptPath, Path workingDir,
                                 String stdinJson, Map<String, String> envVars, int timeoutSec,
                                 ProgressCallback progressCallback, List<Path> readOnly) {
        return execute(python, scriptPath, workingDir, stdinJson, envVars, timeoutSec, progressCallback,
                readOnly, null);
    }

    @Override
    public SandboxResult execute(String python, Path scriptPath, Path workingDir,
                                 String stdinJson, Map<String, String> envVars, int timeoutSec,
                                 ProgressCallback progressCallback, List<Path> readOnly, String tmpOwner) {
        try (SkillJail.Launch launch = jail.prepare(
                List.of(python, scriptPath.toAbsolutePath().toString()), workingDir, readOnly, tmpOwner)) {
            if (launch.refusal() != null) return SandboxResult.refused(launch.refusal());
            ProcessBuilder pb = new ProcessBuilder(launch.argv());
            pb.directory(workingDir.toFile());
            // Only the allow-listed environment, the encoding and what the caller sets. When
            // jailed this is bubblewrap's environment, which it hands to the skill unchanged
            // apart from PWD.
            SkillJail.applyEnvironment(pb, envVars);
            long startTime = System.currentTimeMillis();
            try {
                // A jailed run needs nothing more: killing bubblewrap ends its jail, and
                // Launch.close sees to what is left of it.
                return run(pb, scriptPath, stdinJson, timeoutSec, progressCallback, () -> { });
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
    }

    /**
     * A trimmed stdout line as a progress message -- a JSON object whose "type" is progress -- or
     * null when it is not one.
     */
    private static JsonNode progress(String line) {
        if (!line.startsWith("{") || !line.contains("\"progress\"")) return null;
        try {
            JsonNode node = PROGRESS.readTree(line);
            return "progress".equals(node.path("type").asText("")) ? node : null;
        } catch (IOException e) {
            log.debug("Failed to parse progress line: {}", line);
            return null;
        }
    }

    /**
     * Start {@code pb} -- a skill's Python, jailed or not, or the container runtime's run of one
     * -- and see it through: {@code stdinJson} on its stdin, both streams read whole, killed when
     * it prints nothing for {@code timeoutSec}. Progress lines
     * the skill emits ({@code {"type":"progress","message":"...","percent":N}}) are forwarded
     * through the callback when there is one -- a callback that throws is logged -- and are never
     * part of the final stdout, from which the result is parsed: a progress line ahead of the
     * result was read as the result, and a working skill failed its self-heal retry, which runs
     * with no callback. A result whose top-level "type" is progress is taken for a progress line
     * too; the skill contract reserves the key.
     * <p>
     * Each stream is read on a thread of its own ({@link StreamReaders}), started first; the input
     * is written last, on a thread of its own too: a process that never reads it and never exits
     * is then ended by the stall timeout like any other, and one that exits or closes its stdin
     * before reading all of it gives its own exit code and stderr as the result.
     *
     * @param alsoKill run after the process is killed for stalling: what else
     *                 must end with it
     * @throws IOException when it cannot be started
     */
    static SandboxResult run(ProcessBuilder pb, Path scriptPath, String stdinJson, int timeoutSec,
                             ProgressCallback progressCallback, Runnable alsoKill) throws IOException {
        long startTime = System.currentTimeMillis();
        Process process = pb.start();

        // Track last time any output was produced — used for stall detection.
        AtomicLong lastActivity = new AtomicLong(System.currentTimeMillis());

        // Drain stderr async to prevent pipe-buffer deadlock.
        CompletableFuture<byte[]> stderrFuture = StreamReaders.read(() ->
                drainStreamWithActivity(process.getErrorStream(), lastActivity));

        // Read stdout line-by-line so a progress line is forwarded as it arrives.
        CompletableFuture<byte[]> stdoutFuture = StreamReaders.read(() -> {
            ByteArrayOutputStream buf = new ByteArrayOutputStream(8192);
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lastActivity.set(System.currentTimeMillis());
                    String trimmed = line.trim();

                    JsonNode progress = progress(trimmed);
                    if (progress != null) {
                        if (progressCallback != null) {
                            String msg = progress.path("message").asText("Working...");
                            Integer pct = progress.has("percent") && !progress.get("percent").isNull()
                                    ? progress.get("percent").asInt() : null;
                            try {
                                progressCallback.onProgress(msg, pct);
                            } catch (RuntimeException e) {
                                log.warn("Progress callback failed: {}", e.toString());
                            }
                        }
                        continue; // never part of the result stdout
                    }

                    buf.write(line.getBytes(StandardCharsets.UTF_8));
                    buf.write('\n');
                }
            } catch (IOException ignored) {}
            return buf.toByteArray();
        });

        Thread.ofVirtual().name("skill-stdin-" + process.pid()).start(() -> writeInput(process, stdinJson));

        // Stall detection: no wall-clock timeout — process runs as long as it
        // produces output. Killed only if no stdout/stderr for timeoutSec seconds.
        boolean finished = waitForWithStallDetection(process, timeoutSec, lastActivity);
        long durationMs = System.currentTimeMillis() - startTime;

        if (!finished) {
            long stallSec = (System.currentTimeMillis() - lastActivity.get()) / 1000;
            process.destroyForcibly();
            alsoKill.run();
            try { process.waitFor(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            String stdout = new String(stdoutFuture.join(), StandardCharsets.UTF_8);
            String stderr = new String(stderrFuture.join(), StandardCharsets.UTF_8);
            log.warn("Sandbox stalled (no output for {}s) — killed {} after {}ms total",
                    stallSec, scriptPath.getFileName(), durationMs);
            return new SandboxResult(-1, stdout, stderr, durationMs, true);
        }

        String stdout = new String(stdoutFuture.join(), StandardCharsets.UTF_8);
        String stderr = new String(stderrFuture.join(), StandardCharsets.UTF_8);

        int exitCode = process.exitValue();
        log.debug("Sandbox completed [exit={}] {} in {}ms", exitCode, scriptPath.getFileName(), durationMs);
        return new SandboxResult(exitCode, stdout, stderr, durationMs, false);
    }

    /**
     * Give the process its input, if any, and close its stdin, so it sees the end of it. A
     * process that exits or closes its stdin first makes the write fail; its stdin is closed all
     * the same, and its own exit code and stderr say what happened.
     */
    private static void writeInput(Process process, String stdinJson) {
        try (OutputStream os = process.getOutputStream()) {
            if (stdinJson != null) os.write(stdinJson.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.debug("Process {} did not take all of its input: {}", process.pid(), e.getMessage());
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
    static boolean waitForWithStallDetection(Process process, int stallTimeoutSec,
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
