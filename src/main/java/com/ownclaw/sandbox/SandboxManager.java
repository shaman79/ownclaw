package com.ownclaw.sandbox;

import java.nio.file.Path;
import java.util.Map;

/**
 * Abstraction over sandbox implementations (ProcessBuilder dev, bubblewrap production).
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
     * @param workingDir   the skill's version directory (read-only context)
     * @param stdinJson    JSON input to pass via stdin
     * @param envVars      environment variables (credentials + config)
     * @param timeoutSec   hard timeout in seconds
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
     * Execute a skill interactively — keeps the process alive for stdin/stdout exchanges.
     * The inputCallback is called when the skill emits a need_input message;
     * it receives the prompt and should return the user's response.
     *
     * @param inputCallback function that receives a prompt and returns user input
     * @return execution result after the skill completes
     */
    default SandboxResult executeInteractive(String pythonPath, Path scriptPath, Path workingDir,
                                             String stdinJson, Map<String, String> envVars,
                                             int timeoutSec,
                                             java.util.function.Function<String, String> inputCallback) {
        // Default: non-interactive fallback
        return execute(pythonPath, scriptPath, workingDir, stdinJson, envVars, timeoutSec);
    }
}
