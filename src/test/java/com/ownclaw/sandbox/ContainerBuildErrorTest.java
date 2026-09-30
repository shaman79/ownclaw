package com.ownclaw.sandbox;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ownclaw.agent.tools.DynamicSkill;
import com.ownclaw.agent.tools.ToolExecutionContext;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.skills.PythonEnvironmentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

    static final List<String> BASES = List.of("docker.io/library/python:3.11-slim",
            "docker.io/library/python:3.12-slim", "docker.io/library/python:3-slim",
            "docker.io/library/python:3.11", "docker.io/library/python:3");

    /** The line a failed build of {@code base} by the runtime {@code name} ends its log with. */
    static String lastLine(String name, String base) {
        return "E: Unable to locate package no-such-package (" + name + ", building on " + base + ")";
    }

    /**
     * A runtime whose every build fails: a long log, the failing step at its end naming the runtime
     * and the base image. With {@code stallOn} set, the build of that base prints one line and goes
     * quiet.
     */
    private Path runtime(String name, String stallOn) throws IOException {
        return script(name, "#!/bin/sh\n"
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
                + "    echo \"E: Unable to locate package no-such-package (" + name + ", building on $base)\"\n"
                + "    exit 100 ;;\n"
                + "  *) exit 1 ;;\n"
                + "esac\n");
    }

    private Path script(String name, String text) throws IOException {
        Path p = tmp.resolve(name + ".sh");
        Files.writeString(p, text);
        Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rwx------"));
        return p;
    }

    @Test
    @DisplayName("every base image's failure is reported, each build log whole")
    void everyCandidatesFullErrorIsReported() throws Exception {
        var sandbox = ContainerSandbox.withRuntimes(new OwnClawConfig(), runtime("docker", null).toString(), null);
        var progress = new ArrayList<String>();

        var e = assertThrows(IOException.class, () -> sandbox.ensureImage(List.of("nmap"), null,
                tmp, null, (message, percent) -> progress.add(message)));

        for (String base : BASES) {
            assertTrue(e.getMessage().contains("STEP 1/6: FROM " + base), base);
            assertTrue(e.getMessage().contains(lastLine("docker", base)), "the failing step sits at "
                    + "the end of the log, which a cut to its first 2,000 characters dropped -- and "
                    + "only the last image's log was kept: " + base);
        }
        assertTrue(progress.contains("📦 " + LONG_LINE), "each progress line whole");
    }

    @Test
    @DisplayName("when the other runtime fails too, both build logs are reported whole")
    void bothRuntimesLogsAreReported() throws Exception {
        var sandbox = ContainerSandbox.withRuntimes(new OwnClawConfig(),
                runtime("docker", null).toString(), runtime("podman", null).toString());

        var e = assertThrows(IOException.class,
                () -> sandbox.ensureImage(List.of("nmap"), null, tmp, null, null));

        assertTrue(e.getMessage().contains("Container image build failed with both runtimes."),
                e.getMessage());
        for (String base : BASES) {
            assertTrue(e.getMessage().contains(lastLine("docker", base)), "each log was cut to "
                    + "1,000 characters, and the reason is at its end: " + base);
            assertTrue(e.getMessage().contains(lastLine("podman", base)), base);
        }
    }

    @Test
    @DisplayName("a build that stalls reports what it had printed until then")
    void aStalledBuildKeepsItsLog() throws Exception {
        var config = new OwnClawConfig();
        config.getSandbox().setStallTimeout(1);
        var sandbox = ContainerSandbox.withRuntimes(config,
                runtime("docker", "docker.io/library/python:3.11-slim").toString(), null);

        var e = assertThrows(IOException.class,
                () -> sandbox.ensureImage(List.of("nmap"), null, tmp, null, null));

        assertTrue(e.getMessage().contains("docker.io/library/python:3.11-slim: Container image build "
                + "failed"), e.getMessage());
        assertTrue(e.getMessage().contains("Its output until then:\nSTEP 1/6: FROM "
                + "docker.io/library/python:3.11-slim"), e.getMessage());
    }

    @Test
    @DisplayName("an image that builds but cannot run python3 says what running it said")
    void anUnusableImageSaysWhy() throws Exception {
        Path noPython = script("docker", "#!/bin/sh\n"
                + "case \"$1\" in\n"
                + "  build) echo 'STEP 1/6: FROM base'; exit 0 ;;\n"
                + "  run) echo 'exec: \"python3\": executable file not found in $PATH'; exit 127 ;;\n"
                + "  *) exit 1 ;;\n"
                + "esac\n");
        var sandbox = ContainerSandbox.withRuntimes(new OwnClawConfig(), noPython.toString(), null);

        var e = assertThrows(IOException.class,
                () -> sandbox.ensureImage(List.of("nmap"), null, tmp, null, null));

        assertTrue(e.getMessage().contains("built but python3 is not usable inside it"), e.getMessage());
        assertTrue(e.getMessage().contains("'python3 --version' in it: exit 127: exec: \"python3\": "
                + "executable file not found in $PATH"), "the reason was only ever in a log line cut "
                + "to 500 characters: " + e.getMessage());
    }

    @Test
    @DisplayName("a failed build's logs reach the skill's result whole, and the application log none of them")
    void theBuildLogGoesToTheResultNotTheLog() throws Exception {
        var sandbox = ContainerSandbox.withRuntimes(new OwnClawConfig(), runtime("docker", null).toString(), null);
        Path dir = Files.createDirectories(tmp.resolve("skills/net_scan"));
        Files.writeString(dir.resolve("skill.py"), "def run(params):\n    return {}\n");
        var skill = new DynamicSkill("net_scan", "scan", Map.of(), dir, false, false, 30, null,
                new PythonEnvironmentService(new OwnClawConfig()), List.of(), null, List.of("nmap"), null,
                sandbox, null);
        var logs = new ListAppender<ILoggingEvent>();
        logs.start();
        var logger = (Logger) LoggerFactory.getLogger(DynamicSkill.class);
        logger.addAppender(logs);
        try {
            var r = skill.execute(Map.of(), new ToolExecutionContext("u1", "t1", null, () -> false,
                    null, List.of()));

            assertFalse(r.success());
            for (String base : BASES) assertTrue(r.output().contains(lastLine("docker", base)), base);
            assertTrue(logs.list.stream().anyMatch(event -> event.getFormattedMessage().startsWith(
                    "Dynamic skill 'net_scan' execution failed: All base image candidates failed. (")),
                    "the failure is logged, by its first line and its size");
            for (var event : logs.list) {
                assertFalse(event.getFormattedMessage().contains("Unable to locate package"),
                        "the ops API serves the log; five whole build logs are no log line: "
                                + event.getFormattedMessage().length() + " chars");
            }
        } finally {
            logger.detachAppender(logs);
        }
    }
}
