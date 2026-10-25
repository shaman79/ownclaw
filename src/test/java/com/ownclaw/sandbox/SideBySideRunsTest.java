package com.ownclaw.sandbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Two skill runs side by side -- tasks of different chats -- each have their output read while
 * they run: on a machine of four cores, whose common pool has three threads, the first run's two
 * readers left the second's output unread until the first ended.
 */
@Timeout(120)
class SideBySideRunsTest {

    @Test
    @DisplayName("a run beside a long one has its output read at once, on a machine of four cores")
    void aRunBesideAnotherIsRead(@TempDir Path dir) throws Exception {
        // The common pool of a four-core machine: three threads, the rest kept busy here.
        int spare = ForkJoinPool.getCommonPoolParallelism() - 3;
        var release = new CountDownLatch(1);
        var busy = new CountDownLatch(Math.max(0, spare));
        var holders = new ArrayList<CompletableFuture<Void>>();
        for (int i = 0; i < spare; i++) {
            holders.add(CompletableFuture.runAsync(() -> {
                busy.countDown();
                try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }));
        }
        assertTrue(busy.await(10, TimeUnit.SECONDS));
        try {
            Path slow = dir.resolve("slow.py");
            Files.writeString(slow, "import time\nfor i in range(24):\n    print('tick', i, flush=True)\n    time.sleep(0.5)\n");
            Path quick = dir.resolve("quick.py");
            Files.writeString(quick, "import sys\nsys.stdout.write('x' * 200000)\nsys.stdout.flush()\n");
            var sandbox = new ProcessSandbox(null, new ObjectMapper());
            var first = CompletableFuture.supplyAsync(() -> sandbox.execute("python3", slow, dir, null, Map.of(), 30),
                    Executors.newSingleThreadExecutor());
            Thread.sleep(1000);   // the first run's readers are reading

            long t0 = System.currentTimeMillis();
            SandboxResult second = sandbox.execute("python3", quick, dir, null, Map.of(), 8);
            long took = System.currentTimeMillis() - t0;

            assertFalse(second.timedOut(), "killed as stalled: its output was never read");
            assertEquals(200_000, second.stdout().strip().length(), "its whole output");
            assertTrue(took < 6000, "it waited for the first run: " + took + " ms");
            assertFalse(first.get(60, TimeUnit.SECONDS).timedOut());
        } finally {
            release.countDown();
        }
    }
}
