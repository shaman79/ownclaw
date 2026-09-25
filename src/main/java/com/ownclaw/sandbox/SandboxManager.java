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
     * Run a skill's Python script.
     *
     * @param pythonPath       the interpreter: the system one, or the skill's environment's
     * @param scriptPath       the script to run
     * @param workingDir       the skill's directory; writable, and when the skill is isolated the
     *                         only writable path besides its private /tmp
     * @param stdinJson        JSON input to pass via stdin; null for none
     * @param envVars          the variables the service sets beside the allow-list and the
     *                         encoding: the skill's environment (PATH or PYTHONPATH). Never a
     *                         credential: those come with the input on stdin, so that they are
     *                         never in the environment of a process outside the jail
     * @param timeoutSec       stall timeout — the process is killed if it prints nothing for this
     *                         many seconds
     * @param progressCallback receives the progress lines the skill emits
     *                         ({@code {"type":"progress",...}}); null for none. They are never
     *                         part of stdout either way
     * @param readOnly         host paths the skill may read when it is isolated, each at its own
     *                         path -- its environment and the files this task was handed
     * @return execution result
     */
    SandboxResult execute(String pythonPath, Path scriptPath, Path workingDir, String stdinJson,
                          Map<String, String> envVars, int timeoutSec, ProgressCallback progressCallback,
                          List<Path> readOnly);

    /**
     * Why every launch would be refused right now, if it would -- so a caller can stop before
     * doing work whose check it cannot run.
     */
    Optional<String> unavailable();
}
