package com.ownclaw.sandbox;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Runs a skill's Python process. {@link ProcessSandbox} is the implementation; whether its
 * launches are isolated is decided by {@link SkillJail}.
 */
public interface SandboxManager {

    /**
     * Callback interface for receiving progress updates from a running skill.
     * Skills emit {@code {"type":"progress","message":"...","percent":N}} lines on stdout;
     * the sandbox detects these and forwards them through this callback.
     */
    @FunctionalInterface
    interface ProgressCallback {
        /**
         * Called when the skill emits a progress update.
         *
         * @param message human-readable progress message
         * @param percent completion percentage (0–100), or null if unknown
         */
        void onProgress(String message, Integer percent);
    }

    /**
     * Execute a Python script in a sandboxed environment.
     *
     * @param scriptPath   path to the skill.py script
     * @param workingDir   the skill's directory; writable, and when the skill is isolated the
     *                     only writable path besides its private /tmp
     * @param stdinJson    JSON input to pass via stdin
     * @param envVars      environment variables (credentials + config)
     * @param timeoutSec   stall timeout — process is killed if no output for this many seconds
     * @return execution result
     */
    SandboxResult execute(Path scriptPath, Path workingDir, String stdinJson,
                          Map<String, String> envVars, int timeoutSec);

    /**
     * Execute with a specific Python executable (for venv support).
     */
    default SandboxResult execute(String pythonPath, Path scriptPath, Path workingDir,
                                  String stdinJson, Map<String, String> envVars, int timeoutSec) {
        return execute(scriptPath, workingDir, stdinJson, envVars, timeoutSec);
    }

    /**
     * Execute with a specific Python executable and progress callback.
     * Progress lines emitted by the skill ({@code {"type":"progress",...}}) are intercepted
     * and forwarded via the callback instead of being included in stdout.
     *
     * @param progressCallback receives progress updates (may be null for no-op)
     */
    default SandboxResult execute(String pythonPath, Path scriptPath, Path workingDir,
                                  String stdinJson, Map<String, String> envVars, int timeoutSec,
                                  ProgressCallback progressCallback) {
        return execute(pythonPath, scriptPath, workingDir, stdinJson, envVars, timeoutSec);
    }

    /**
     * Execute with read-only access to host paths the skill would otherwise not see when it is
     * isolated -- its environment and the files this task was handed, each at its own path.
     * Implementations without isolation have nothing to open up and ignore the list.
     */
    default SandboxResult execute(String pythonPath, Path scriptPath, Path workingDir,
                                  String stdinJson, Map<String, String> envVars, int timeoutSec,
                                  ProgressCallback progressCallback, List<Path> readOnly) {
        return execute(pythonPath, scriptPath, workingDir, stdinJson, envVars, timeoutSec, progressCallback);
    }

    /**
     * Why every launch would be refused right now, if it would -- so a caller can stop before
     * doing work whose check it cannot run.
     */
    default Optional<String> unavailable() {
        return Optional.empty();
    }
}
