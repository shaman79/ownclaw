package com.ownclaw.skills;

import com.ownclaw.config.OwnClawConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A task whose skill's environment another task is setting up -- its first run, or a self-heal
 * installing a package -- is said to wait, which the stall watchdog counts as progress, and its
 * Stop ends the wait.
 */
@Timeout(60)
class EnvironmentWaitTest {

    @Test
    @DisplayName("a wait for a skill's environment another task sets up is said, and a Stop ends it")
    void aWaitIsSaidAndStopped(@TempDir Path root) throws Exception {
        OwnClawConfig cfg = new OwnClawConfig();
        cfg.getSkills().setGeneratedPath(Files.createDirectories(root.resolve("generated")).toString());
        cfg.getSandbox().setPythonPath("python3");
        var env = new PythonEnvironmentService(cfg);
        env.init();
        Path skillDir = Files.createDirectories(root.resolve("generated").resolve("greeter"));

        var other = env.envLock("greeter");
        var held = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var holder = new Thread(() -> {   // another task setting it up
            other.lock();
            held.countDown();
            try { release.await(); } catch (InterruptedException ignored) { } finally { other.unlock(); }
        });
        holder.start();
        assertTrue(held.await(5, TimeUnit.SECONDS));
        try {
            var said = new AtomicInteger();
            var stopped = new AtomicBoolean();
            var resolving = CompletableFuture.supplyAsync(() -> {
                try {
                    env.resolveExecution(skillDir, "greeter", stopped::get, said::incrementAndGet);
                    return "resolved";
                } catch (IllegalStateException e) {
                    return e.getMessage();
                }
            });
            for (int i = 0; i < 100 && said.get() == 0; i++) Thread.sleep(50);
            assertEquals(1, said.get(), "said once it waits");
            stopped.set(true);
            assertEquals("Stopped while another task set up the environment of skill 'greeter'",
                    resolving.get(5, TimeUnit.SECONDS));

            var installStopped = new AtomicBoolean(true);
            var installing = CompletableFuture.supplyAsync(() -> {
                try {
                    env.installPackages(skillDir, "greeter", List.of("requests"), installStopped::get, () -> {});
                    return "installed";
                } catch (IOException e) {
                    return e.getMessage();
                }
            });
            assertEquals("Stopped while another task set up the environment of skill 'greeter'",
                    installing.get(5, TimeUnit.SECONDS), "nothing installed while another task held it");
        } finally {
            release.countDown();
            holder.join(5000);
        }
    }
}
