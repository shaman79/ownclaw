package com.ownclaw.sandbox;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A run's input goes in last, on a thread of its own: a process that exits without reading it
 * gives its own exit code and stderr, and one that never reads it is ended by the stall timeout.
 */
class ProcessSandboxInputTest {

    @TempDir Path tmp;

    /** More than a pipe holds (64 KiB), so the write cannot finish before the process reads. */
    private static final String BIG_INPUT = "{\"x\": \"" + "a".repeat(1 << 20) + "\"}";

    @BeforeEach
    void python() {
        assumeTrue(Files.isExecutable(Path.of("/usr/bin/python3")), "python3 is not available");
    }

    private SandboxResult run(String code, int timeoutSec) throws Exception {
        Path script = Files.writeString(tmp.resolve("_runner_input.py"), code);
        return new ProcessSandbox(JailTestKit.off()).execute("python3", script, tmp, BIG_INPUT, Map.of(),
                timeoutSec, null, List.of());
    }

    @Test
    @DisplayName("a process that exits without reading its input gives its own exit code and stderr")
    void exitingWithoutReading() throws Exception {
        var result = run("import sys\nsys.stderr.write('left early\\n')\nsys.exit(3)\n", 30);
        assertEquals(3, result.exitCode(), result.toString());
        assertEquals("left early", result.stderr().strip());
        assertFalse(result.timedOut());
    }

    @Test
    @DisplayName("a process that never reads its input, and prints nothing, is ended by the stall timeout")
    void neverReading() throws Exception {
        long start = System.nanoTime();
        var result = run("import time\ntime.sleep(60)\n", 1);
        long ms = (System.nanoTime() - start) / 1_000_000;
        assertTrue(result.timedOut(), result.toString());
        assertTrue(ms < 20_000, "the write held the run for " + ms + " ms");
    }
}
