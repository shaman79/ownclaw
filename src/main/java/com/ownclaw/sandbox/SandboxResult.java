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

    /** Nothing was started: the reason stands in stderr, where a caller already looks. */
    public static SandboxResult refused(String reason) {
        return new SandboxResult(-1, "", reason, 0, false);
    }
}
