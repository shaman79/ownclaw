package com.ownclaw.sandbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A skill's output is held in memory for its result, so it is bounded: a skill that prints past
 * {@link ProcessSandbox#OUTPUT_LIMIT} on either stream is killed, and says so -- with and without
 * a progress callback, which read stdout by different code.
 */
class ProcessSandboxOutputLimitTest {

    @TempDir Path tmp;

    @BeforeEach
    void python() {
        assumeTrue(Files.isExecutable(Path.of("/usr/bin/python3")), "python3 is not available");
    }

    private SandboxResult run(String stream, boolean progress, long bytes, int sleepAfter) throws Exception {
        Path script = Files.writeString(tmp.resolve("_runner_loud.py"), String.join("\n",
                "import sys, time",
                "out = sys." + stream,
                "chunk = 'x' * 65536",
                "left = " + bytes,
                "while left > 0:",
                "    out.write(chunk[:min(left, len(chunk))])",
                "    left -= len(chunk)",
                "out.flush()",
                "time.sleep(" + sleepAfter + ")",
                ""));
        return new ProcessSandbox(null, new ObjectMapper(), SkillJail.off()).execute("python3", script, tmp,
                null, Map.of(), 60, progress ? (msg, pct) -> { } : null, List.of());
    }

    @ParameterizedTest(name = "{0}, progress callback {1}")
    @CsvSource({"stdout, false", "stderr, false", "stdout, true", "stderr, true"})
    @DisplayName("a skill printing past the limit on either stream is killed, and its result says why")
    void pastTheLimitIsKilled(String stream, boolean progress) throws Exception {
        long start = System.nanoTime();
        var result = run(stream, progress, ProcessSandbox.OUTPUT_LIMIT + 1L, 30);
        long ms = (System.nanoTime() - start) / 1_000_000;

        assertFalse(result.isSuccess(), "a run past the output limit succeeded");
        assertTrue(result.stderr().startsWith("Killed: the skill's output went past the 16 MB limit."),
                result.stderr().substring(0, Math.min(200, result.stderr().length())));
        assertTrue(ms < 20_000, "the skill was left to run on for " + ms + " ms");
        String kept = stream.equals("stdout") ? result.stdout() : result.stderr();
        assertTrue(kept.length() <= ProcessSandbox.OUTPUT_LIMIT + 100, "held " + kept.length() + " chars");
    }

    @Test
    @DisplayName("output that ends exactly at the limit is not over it")
    void exactlyTheLimitIsKept() throws Exception {
        var result = run("stdout", false, ProcessSandbox.OUTPUT_LIMIT, 0);
        assertTrue(result.isSuccess(), result.stderr());
        assertEquals(ProcessSandbox.OUTPUT_LIMIT, result.stdout().strip().length());
    }
}
