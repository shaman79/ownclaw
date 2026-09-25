package com.ownclaw.sandbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Every run's output is read while it runs, however many run at once: a run's streams are read
 * on threads of their own, never on the common pool, where a blocking read holds a thread for as
 * long as its process runs.
 */
class ProcessSandboxConcurrentRunsTest {

    @TempDir Path tmp;

    /** Prints a line on {@code stream} at once and every 0.3 s for 6 s, then its result. */
    private Path script(String name, String stream) throws Exception {
        return Files.writeString(tmp.resolve("_runner_" + name + ".py"), String.join("\n",
                "import json, sys, time",
                "for i in range(20):",
                "    sys." + stream + ".write(json.dumps({'type': 'progress', 'message': 'step %d' % i}) + '\\n')",
                "    sys." + stream + ".flush()",
                "    time.sleep(0.3)",
                "print(json.dumps({'success': True, 'output': 'done'}))",
                ""));
    }

    @Test
    @DisplayName("more runs at once than the common pool has threads, printing on stdout or on stderr: each one's output arrives as it prints, and none is killed as stalled")
    void moreRunsThanThePoolHasThreads() throws Exception {
        assumeTrue(Files.isExecutable(Path.of("/usr/bin/python3")), "python3 is not available");
        // A run whose output nobody reads looks silent, and at the first look, after 5 s, it is
        // past the 3 s timeout. Half of them report progress on stdout; the other half print
        // only on stderr, as the logging module does, so each stream alone must be read.
        Path progressing = script("progress", "stdout");
        Path logging = script("logging", "stderr");
        int each = ForkJoinPool.getCommonPoolParallelism() + 1;
        var sandbox = new ProcessSandbox(JailTestKit.off());
        var progress = new ArrayList<AtomicInteger>();
        var results = new ArrayList<CompletableFuture<SandboxResult>>();
        for (int i = 0; i < 2 * each; i++) {
            var seen = new AtomicInteger();
            progress.add(seen);
            Path script = i % 2 == 0 ? progressing : logging;
            results.add(CompletableFuture.supplyAsync(() -> sandbox.execute("python3", script, tmp, null, Map.of(),
                    3, (msg, pct) -> seen.incrementAndGet(), List.of()), task -> Thread.ofVirtual().start(task)));
        }
        for (int i = 0; i < 2 * each; i++) {
            SandboxResult r = results.get(i).get(60, TimeUnit.SECONDS);
            assertFalse(r.timedOut(), "run " + i + " was killed as stalled while it printed: " + r);
            assertEquals(0, r.exitCode(), r.toString());
            if (i % 2 == 0) assertEquals(20, progress.get(i).get(), "run " + i + "'s progress did not all arrive");
            else assertEquals(20, r.stderr().lines().count(), "run " + i + "'s stderr did not all arrive");
        }
    }
}
