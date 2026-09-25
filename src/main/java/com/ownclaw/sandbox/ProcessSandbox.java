package com.ownclaw.sandbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
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
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runs a skill's Python as a child process, with stall detection and progress forwarding.
 * Every launch goes through {@link SkillJail#prepare}: when the jail is READY the
 * process runs inside bubblewrap (the host read-only, the service's state hidden, its own
 * directory writable, a private /tmp); when it is inactive or off, unjailed; when it is
 * refusing, or cannot isolate this one launch, not at all. The environment is the jail's
 * allow-list either way.
 */
@Component
public class ProcessSandbox implements SandboxManager {

    private static final Logger log = LoggerFactory.getLogger(ProcessSandbox.class);

    /**
     * The most a run's stdout, or its stderr, may hold. Both are kept in memory for the result,
     * so without a bound a skill that prints without end takes the JVM's heap with it. Past it
     * the process is killed, and the result says why.
     */
    public static final int OUTPUT_LIMIT = 16 * 1024 * 1024;

    /**
     * {@code in}, ending after {@link #OUTPUT_LIMIT} bytes; when there is more, {@code overLimit}
     * is set and {@code process} killed.
     */
    public static InputStream limited(InputStream in, Process process, AtomicBoolean overLimit) {
        return new FilterInputStream(in) {
            private long left = OUTPUT_LIMIT;

            @Override
            public int read() throws IOException {
                byte[] one = new byte[1];
                return read(one, 0, 1) == -1 ? -1 : one[0] & 0xff;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (len == 0) return 0;
                if (left == 0) {
                    // Output that ends exactly at the limit is not over it: one more byte decides.
                    if (super.read() == -1) return -1;
                    overLimit.set(true);
                    process.destroyForcibly();
                    return -1;
                }
                int n = super.read(b, off, (int) Math.min(len, left));
                if (n > 0) left -= n;
                return n;
            }
        };
    }

    private static SandboxResult killedOverLimit(Path scriptPath, String stdout, String stderr, long durationMs) {
        log.warn("Sandbox killed {}: its output went past {} MB", scriptPath.getFileName(), OUTPUT_LIMIT >> 20);
        return new SandboxResult(-1, stdout, "Killed: the skill's output went past the "
                + (OUTPUT_LIMIT >> 20) + " MB limit.\n" + stderr, durationMs, false);
    }

    /**
     * A thread of its own for each task: where each of a run's output streams is read, as its
     * input is written on one. Not the common pool, whose few threads a blocking read holds for
     * as long as the process runs: with more runs at once than it has threads, a later run's
     * output went unread, and the stall timer killed it while it printed.
     */
    static final Executor OWN_THREAD = r -> Thread.ofVirtual().start(r);

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
        try (SkillJail.Launch launch = jail.prepare(
                List.of(python, scriptPath.toAbsolutePath().toString()), workingDir, readOnly)) {
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
     * Returns true if a stdout line looks like a progress JSON message.
     */
    private static boolean isProgressLine(String line) {
        String t = line.trim();
        return t.startsWith("{") && t.contains("\"progress\"");
    }

    /**
     * Start {@code pb} -- a skill's Python, jailed or not, or the container runtime's run of one
     * -- and see it through: {@code stdinJson} on its stdin, both streams read within
     * {@link #OUTPUT_LIMIT}, killed when it prints nothing for {@code timeoutSec}. With a
     * progress callback, progress lines the skill emits
     * ({@code {"type":"progress","message":"...","percent":N}}) are intercepted and forwarded
     * through it, and are NOT included in the final stdout; without one they stay in it.
     * <p>
     * Each stream is read on a thread of its own ({@link #OWN_THREAD}), started first; the input
     * is written last, on a thread of its own too: a process that never reads it and never exits
     * is then ended by the stall timeout like any other, and one that exits or closes its stdin
     * before reading all of it gives its own exit code and stderr as the result.
     *
     * @param alsoKill run after the process is killed for stalling or for its output: what else
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
        AtomicBoolean overLimit = new AtomicBoolean(false);
        CompletableFuture<byte[]> stderrFuture = CompletableFuture.supplyAsync(() ->
                drainStreamWithActivity(limited(process.getErrorStream(), process, overLimit), lastActivity),
                OWN_THREAD);

        // Read stdout line-by-line so a progress line is forwarded as it arrives.
        CompletableFuture<byte[]> stdoutFuture = CompletableFuture.supplyAsync(() -> {
            ByteArrayOutputStream buf = new ByteArrayOutputStream(8192);
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(limited(process.getInputStream(), process, overLimit),
                            StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lastActivity.set(System.currentTimeMillis());
                    String trimmed = line.trim();

                    if (progressCallback != null && isProgressLine(trimmed)) {
                        try {
                            JsonNode node = PROGRESS.readTree(trimmed);
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

                    buf.write(line.getBytes(StandardCharsets.UTF_8));
                    buf.write('\n');
                }
            } catch (IOException ignored) {}
            return buf.toByteArray();
        }, OWN_THREAD);

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
        if (overLimit.get()) {
            alsoKill.run();
            return killedOverLimit(scriptPath, stdout, stderr, durationMs);
        }

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
