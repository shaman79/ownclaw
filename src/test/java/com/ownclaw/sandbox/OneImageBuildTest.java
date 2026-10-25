package com.ownclaw.sandbox;

import com.ownclaw.config.OwnClawConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Two tasks asking for the same container image at once -- tasks of different chats run side by
 * side -- build it once: the second waits for the first's build and finds it built.
 */
@Timeout(60)
class OneImageBuildTest {

    @TempDir Path tmp;

    @Test
    @DisplayName("an image asked for by two tasks at once is built once")
    void builtOnce() throws Exception {
        Path builds = tmp.resolve("builds");
        Path runtime = tmp.resolve("docker.sh");
        Files.writeString(runtime, "#!/bin/sh\n"
                + "case \"$1\" in\n"
                + "  image) exit 1 ;;\n"
                + "  build) echo built >> '" + builds + "'; sleep 1; exit 0 ;;\n"
                + "  *) exit 0 ;;\n"
                + "esac\n");
        Files.setPosixFilePermissions(runtime, PosixFilePermissions.fromString("rwx------"));
        var sandbox = ContainerSandbox.withRuntimes(new OwnClawConfig(), runtime.toString(), null);

        var first = CompletableFuture.supplyAsync(() -> ensure(sandbox));
        var second = CompletableFuture.supplyAsync(() -> ensure(sandbox));
        assertEquals(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
        assertEquals(List.of("built"), Files.readAllLines(builds), "built more than once");
    }

    private String ensure(ContainerSandbox sandbox) {
        try {
            return sandbox.ensureImage(List.of("nmap"), null, tmp, null, null);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
