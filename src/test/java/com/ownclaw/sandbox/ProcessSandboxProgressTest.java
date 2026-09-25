package com.ownclaw.sandbox;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A skill's progress lines are never part of its result: the result is parsed from stdout, and
 * a progress line ahead of it was read as the result -- with no callback, which is how the
 * self-heal retry runs, a working skill that reports progress failed.
 */
class ProcessSandboxProgressTest {

    @TempDir Path tmp;

    @BeforeEach
    void python() {
        assumeTrue(Files.isExecutable(Path.of("/usr/bin/python3")), "python3 is not available");
    }

    @ParameterizedTest(name = "progress callback {0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("progress lines are left out of the result, with a progress callback or without one")
    void progressIsNeverTheResult(boolean callback) throws Exception {
        Path script = Files.writeString(tmp.resolve("_runner_progress.py"), String.join("\n",
                "import json",
                "print(json.dumps({'type': 'progress', 'message': 'half way', 'percent': 50}), flush=True)",
                "print(json.dumps({'output': 'done'}))",
                ""));
        var seen = new ArrayList<String>();
        var result = new ProcessSandbox(JailTestKit.off()).execute("python3", script, tmp,
                null, Map.of(), 30, callback ? (msg, pct) -> seen.add(msg + " " + pct) : null, List.of());

        assertTrue(result.isSuccess(), result.stderr());
        assertEquals("{\"output\": \"done\"}", result.stdout().strip());
        assertEquals(callback ? List.of("half way 50") : List.of(), seen);
        // Mutation: progress lines kept when there is no callback -> the progress dict is stdout's
        // first value, and the result parser reads it as the result.
    }
}
