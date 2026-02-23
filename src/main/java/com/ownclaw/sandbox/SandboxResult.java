package com.ownclaw.sandbox;

/**
 * Result of executing a script in the sandbox.
 */
public record SandboxResult(
    int exitCode,
    String stdout,
    String stderr,
    long durationMs,
    boolean timedOut
) {
    public boolean isSuccess() {
        return exitCode == 0 && !timedOut;
    }
}
