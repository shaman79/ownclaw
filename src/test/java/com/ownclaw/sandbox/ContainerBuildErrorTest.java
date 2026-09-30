package com.ownclaw.sandbox;

import com.ownclaw.config.OwnClawConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What a failed image build tells the skill: every base image's own build log, whole. The
 * runtime is a shell script standing in for docker -- no image is pulled, nothing is built.
 */
class ContainerBuildErrorTest {

    @TempDir Path tmp;

    /** A progress line longer than the 120 characters a line was once cut to. */
    static final String LONG_LINE = "Downloading layer sha256:" + "0123456789abcdef".repeat(12)
            + " 48.2MB/48.2MB";

    /**
     * A runtime whose every build fails: a long log, the failing step at its end naming the base
     * image. With {@code stallOn} set, the build of that base prints one line and goes quiet.
     */
    private Path runtime(String stallOn) throws IOException {
        String script = "#!/bin/sh\n"
                + "case \"$1\" in\n"
                + "  build)\n"
                + "    for last; do :; done\n"
                + "    base=$(sed -n 's/^FROM //p' \"$last/Dockerfile\")\n"
                + "    echo \"STEP 1/6: FROM $base\"\n"
                + (stallOn == null ? "" : "    [ \"$base\" = '" + stallOn + "' ] && exec sleep 30\n")
                + "    echo '" + LONG_LINE + "'\n"
                + "    i=1\n"
                + "    while [ $i -le 60 ]; do\n"
                + "      echo \"Get:$i http://deb.example.org/debian bookworm/main amd64 pkg-$i [$((i * 1000)) B]\"\n"
                + "      i=$((i + 1))\n"
                + "    done\n"
                + "    echo \"E: Unable to locate package no-such-package (building on $base)\"\n"
                + "    exit 100 ;;\n"
                + "  *) exit 1 ;;\n"
                + "esac\n";
        Path p = tmp.resolve("runtime.sh");
        Files.writeString(p, script);
        Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rwx------"));
        return p;
    }

    @Test
    @DisplayName("every base image's failure is reported, each build log whole")
    void everyCandidatesFullErrorIsReported() throws Exception {
        var sandbox = new ContainerSandbox(new OwnClawConfig(), runtime(null).toString());
        var progress = new ArrayList<String>();

        var e = assertThrows(IOException.class, () -> sandbox.ensureImage(List.of("nmap"), null,
                tmp, null, (message, percent) -> progress.add(message)));

        List<String> bases = List.of("docker.io/library/python:3.11-slim",
                "docker.io/library/python:3.12-slim", "docker.io/library/python:3-slim",
                "docker.io/library/python:3.11", "docker.io/library/python:3");
        for (String base : bases) {
            assertTrue(e.getMessage().contains("STEP 1/6: FROM " + base), base);
            assertTrue(e.getMessage().contains("E: Unable to locate package no-such-package (building on "
                    + base + ")"), "the failing step sits at the end of the log, which a cut to its "
                    + "first 2,000 characters dropped -- and only the last image's log was kept: " + base);
        }
        assertTrue(progress.contains("📦 " + LONG_LINE), "each progress line whole");
    }

    @Test
    @DisplayName("a build that stalls reports what it had printed until then")
    void aStalledBuildKeepsItsLog() throws Exception {
        var config = new OwnClawConfig();
        config.getSandbox().setStallTimeout(1);
        var sandbox = new ContainerSandbox(config,
                runtime("docker.io/library/python:3.11-slim").toString());

        var e = assertThrows(IOException.class,
                () -> sandbox.ensureImage(List.of("nmap"), null, tmp, null, null));

        assertTrue(e.getMessage().contains("docker.io/library/python:3.11-slim: Container image build "
                + "failed"), e.getMessage());
        assertTrue(e.getMessage().contains("Its output until then:\nSTEP 1/6: FROM "
                + "docker.io/library/python:3.11-slim"), e.getMessage());
    }
}
