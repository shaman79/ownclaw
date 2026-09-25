package com.ownclaw.sandbox;

import com.ownclaw.config.OwnClawConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * What a container run of a skill is given: its directory, the files it was handed, its
 * environment. The container never passes the jail, so what the jail refuses of a bind -- a
 * link, a hard link out, an authored file that is not a regular file, a bind reaching the
 * service's state -- is refused here too, and the authored files go in read-only.
 */
class ContainerRunCommandTest {

    @TempDir Path tmp;

    private Path skillDir;
    private Path env;
    private Path uploads;
    private ContainerSandbox container;

    @BeforeEach
    void setUp() throws IOException {
        env = Files.writeString(tmp.resolve(".env"), "OWNCLAW_OPS_TOKEN=canary-env-secret\n");
        skillDir = Files.createDirectories(tmp.resolve("skills/generated/nmap_scan"));
        Files.writeString(skillDir.resolve("SKILL.yaml"), "name: nmap_scan\n");
        Files.writeString(skillDir.resolve("skill.py"), "def run(params):\n    return {}\n");
        uploads = Files.createDirectories(tmp.resolve("data/uploads"));
        var config = new OwnClawConfig();
        config.getSkills().setGeneratedPath(tmp.resolve("skills/generated").toString());
        config.getDatabase().setPath(tmp.resolve("data/ownclaw.db").toString());
        container = new ContainerSandbox(config);
    }

    private List<String> command(Path dir) throws IOException {
        return container.buildRunCommand("test-image", "python3", dir.resolve("_runner_x.py"), dir, Map.of(), null);
    }

    private List<String> handing(Map<String, String> volumes) throws IOException {
        return container.buildRunCommand("test-image", "python3", skillDir.resolve("_runner_x.py"), skillDir,
                Map.of(), volumes);
    }

    @Test
    @DisplayName("the skill directory goes in read-write, its three authored files read-only on top")
    void authoredFilesAreReadOnly() throws Exception {
        List<String> cmd = command(skillDir);

        assertTrue(cmd.contains(skillDir + ":/skill"), cmd.toString());
        for (String name : SkillJail.AUTHORED) {
            assertTrue(cmd.contains(skillDir.resolve(name) + ":/skill/" + name + ":ro"), "not read-only: " + name);
        }
        assertTrue(cmd.indexOf(skillDir + ":/skill") < cmd.indexOf(skillDir.resolve("skill.py") + ":/skill/skill.py:ro"));
        assertTrue(Files.isRegularFile(skillDir.resolve("requirements.txt"), LinkOption.NOFOLLOW_LINKS),
                "a skill without requirements gets an empty one, so it cannot write its own");
    }

    @Test
    @DisplayName("a skill directory that is a link is refused, and nothing is created through it")
    void aLinkedDirectoryIsRefused() throws Exception {
        Path elsewhere = Files.createDirectories(tmp.resolve("elsewhere"));
        Files.writeString(elsewhere.resolve("SKILL.yaml"), "name: x\n");
        Files.writeString(elsewhere.resolve("skill.py"), "x = 1\n");
        Path linked = Files.createSymbolicLink(tmp.resolve("skills/generated/linked"), elsewhere);

        var e = assertThrows(IOException.class, () -> command(linked));
        assertTrue(e.getMessage().contains("is a link"), e.getMessage());
        assertFalse(Files.exists(elsewhere.resolve("requirements.txt")), "requirements.txt was created through the link");
    }

    @Test
    @DisplayName("a skill directory holding a hard link to a file outside it is refused")
    void aHardLinkOutIsRefused() throws Exception {
        Files.createLink(skillDir.resolve("cache.bin"), env);
        var e = assertThrows(IOException.class, () -> command(skillDir));
        assertTrue(e.getMessage().contains("has another name outside it (a hard link)"), e.getMessage());
    }

    @Test
    @DisplayName("a handed file goes in read-only at the path the skill was told; a missing one not at all")
    void aHandedFileIsReadOnlyAtItsPath() throws Exception {
        Path handed = Files.writeString(uploads.resolve("9f2c_statement.pdf"), "%PDF");
        Path gone = uploads.resolve("0000_gone.pdf");
        var volumes = new java.util.LinkedHashMap<String, String>();
        volumes.put(handed.toString(), "/uploads/9f2c_statement.pdf");
        volumes.put(gone.toString(), "/uploads/0000_gone.pdf");
        List<String> cmd = handing(volumes);
        assertTrue(cmd.contains(handed + ":/uploads/9f2c_statement.pdf:ro"), cmd.toString());
        assertFalse(cmd.stream().skip(1).anyMatch(a -> a.contains(gone.toString())), "a missing file was mounted: " + cmd);
    }

    @Test
    @DisplayName("a handed file that is a hard link to .env, or a link, is refused -- as the jail refuses it")
    void aTamperedHandedFileIsRefused() throws Exception {
        Path hard = Files.createLink(uploads.resolve("9f2c_statement.pdf"), env);
        var e = assertThrows(IOException.class, () -> handing(Map.of(hard.toString(), "/uploads/x.pdf")));
        assertTrue(e.getMessage().contains("hard link"), e.getMessage());

        Path soft = Files.createSymbolicLink(uploads.resolve("7a1b_statement.pdf"), env);
        e = assertThrows(IOException.class, () -> handing(Map.of(soft.toString(), "/uploads/y.pdf")));
        assertTrue(e.getMessage().contains("is a link"), e.getMessage());
    }

    @Test
    @DisplayName("a handed path that holds the service's state is refused")
    void aHandedPathReachingStateIsRefused() throws Exception {
        Path data = uploads.getParent();
        var e = assertThrows(IOException.class, () -> handing(Map.of(data.toString(), "/uploads/all")));
        assertTrue(e.getMessage().contains("would expose"), e.getMessage());
    }

    @Test
    @DisplayName("credentials go by name on the command line, their values only in the runtime's environment")
    void credentialsByNameOnly() throws Exception {
        var envVars = Map.of("SKILL_CRED", "cred-canary", "PATH", "/host/venv/bin", "PYTHONIOENCODING", "utf-8");
        List<String> cmd = container.buildRunCommand("test-image", "python3", skillDir.resolve("_runner_x.py"),
                skillDir, envVars, null);
        int e = cmd.indexOf("SKILL_CRED");
        assertTrue(e > 0 && cmd.get(e - 1).equals("-e"), cmd.toString());
        assertTrue(cmd.contains("PYTHONIOENCODING"), cmd.toString());
        // The runtime's own name comes first; it is detected at startup, and here there is none.
        assertFalse(cmd.stream().skip(1).anyMatch(a -> a.contains("cred-canary") || a.startsWith("PATH=")),
                cmd.toString());
        assertFalse(cmd.contains("PATH"), "a host PATH was named: " + cmd);

        Map<String, String> cli = ContainerSandbox.cli(cmd, envVars).environment();
        assertEquals("cred-canary", cli.get("SKILL_CRED"));
        assertNotEquals("/host/venv/bin", cli.get("PATH"), "the host venv's PATH replaced the CLI's own");
        for (String name : cli.keySet()) {
            assertTrue(SkillJail.ENV_ALLOWLIST.contains(name) || envVars.containsKey(name)
                    || ContainerSandbox.RUNTIME_ENV.contains(name), "the runtime CLI was handed " + name);
        }
    }

    @Test
    @DisplayName("every start of the runtime CLI gets the daemon settings the service has, and nothing else of its environment")
    void everyStartTalksToTheSameDaemon() {
        // The run and the calls before it -- version, image inspect, build -- alike: an image
        // built on one daemon and run against another is not there.
        var service = Map.of("DOCKER_HOST", "unix:///x.sock", "DOCKER_TLS_VERIFY", "1",
                "XDG_RUNTIME_DIR", "/run/user/1000", "OWNCLAW_OPS_TOKEN", "canary-env-secret");
        for (Map<String, String> run : List.of(Map.<String, String>of(), Map.of("SKILL_CRED", "c"))) {
            Map<String, String> cli = ContainerSandbox.cli(List.of("docker", "version"), run, service).environment();
            assertEquals("unix:///x.sock", cli.get("DOCKER_HOST"));
            assertEquals("1", cli.get("DOCKER_TLS_VERIFY"));
            assertEquals("/run/user/1000", cli.get("XDG_RUNTIME_DIR"));
            for (String name : cli.keySet()) {
                assertTrue(SkillJail.ENV_ALLOWLIST.contains(name) || ContainerSandbox.RUNTIME_ENV.contains(name)
                        || run.containsKey(name), "the runtime CLI was handed " + name);
            }
        }
    }

    @Test
    @DisplayName("a skill cannot name a variable the runtime CLI reads itself: the name rule reserves each")
    void theRuntimeSettingsAreReserved() {
        for (String name : ContainerSandbox.RUNTIME_ENV) assertFalse(SkillJail.settableName(name), name);
    }

    @Test
    @DisplayName("a run whose variable the name rule refuses is not started, and says why")
    void aRefusedNameStartsNothing() throws Exception {
        for (String name : List.of("DOCKER_HOST", "DOCKER_CONTEXT", "DOCKER_CONFIG", "CONTAINER_HOST",
                "CONTAINERS_CONF", "REGISTRY_AUTH_FILE", "XDG_RUNTIME_DIR", "HTTP_PROXY", "HTTPS_PROXY",
                "NO_PROXY", "HOME", "STORAGE_OPTS", "_CONTAINERS_USERNS_CONFIGURED", "PODMAN_USERNS",
                "BUILDAH_ISOLATION", "LD_PRELOAD", "GCONV_PATH")) {
            var e = assertThrows(IllegalArgumentException.class,
                    () -> ContainerSandbox.cli(List.of("docker", "run"), Map.of(name, "x")), name);
            assertEquals(SkillJail.nameRefusal(name), e.getMessage());
        }
        // Through execute, with a stand-in runtime that notes every start it gets.
        Path calls = Files.createDirectories(tmp.resolve("calls"));
        Path runtime = Files.createDirectories(tmp.resolve("bin")).resolve("fake-docker");
        Files.writeString(runtime, "#!/bin/sh\necho \"$1\" >> '" + calls.resolve("log") + "'\nexit 0\n");
        Files.setPosixFilePermissions(runtime, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        var config = new OwnClawConfig();
        config.getSkills().setGeneratedPath(tmp.resolve("skills/generated").toString());
        config.getDatabase().setPath(tmp.resolve("data/ownclaw.db").toString());
        config.getSandbox().setContainerRuntime(runtime.toString());
        var fake = new ContainerSandbox(config);
        fake.init();
        assertEquals(runtime.toString(), fake.runtime());

        var e = assertThrows(IllegalArgumentException.class, () -> fake.execute("test-image", "python3",
                skillDir.resolve("_runner_x.py"), skillDir, null, Map.of("LD_PRELOAD", "/x.so"), 5, null, null));
        assertEquals(SkillJail.nameRefusal("LD_PRELOAD"), e.getMessage());
        assertFalse(Files.readAllLines(calls.resolve("log")).contains("run"), "the run was started");

        var ok = fake.execute("test-image", "python3", skillDir.resolve("_runner_x.py"), skillDir, null,
                Map.of("DOCKERHUB_TOKEN", "t", "SMTP_HOST", "h"), 5, null, null);
        assertTrue(ok.isSuccess(), ok.stderr());
        assertTrue(Files.readAllLines(calls.resolve("log")).contains("run"), "an ordinary credential was refused");
    }

    @Test
    @DisplayName("one runner for both: progress forwarded from each; a need_input line ends a host run at once, a container run hands it back")
    void oneRunnerForAHostAndAContainerRun() throws Exception {
        assumeTrue(Files.isExecutable(Path.of("/usr/bin/python3")), "python3 is not available");
        String progress = "{\"type\":\"progress\",\"message\":\"half\",\"percent\":50}";
        String ask = "{\"need_input\":\"which one?\"}";
        var seen = new java.util.ArrayList<String>();

        Path script = Files.writeString(skillDir.resolve("_runner_ask.py"), "import sys, time\n"
                + "print('" + progress + "')\nprint('" + ask + "')\nsys.stdout.flush()\ntime.sleep(10)\n");
        long start = System.nanoTime();
        var host = new ProcessSandbox(JailTestKit.off()).execute("python3", script, skillDir, null, Map.of(), 60,
                (msg, pct) -> seen.add(msg), List.of());
        assertTrue(host.timedOut(), "a need_input line did not end the host run: " + host);
        assertTrue(System.nanoTime() - start < 8_000_000_000L, "the host run was waited out");

        Path runtime = Files.createDirectories(tmp.resolve("bin")).resolve("fake-docker");
        Files.writeString(runtime, "#!/bin/sh\n[ \"$1\" = version ] && exit 0\n"
                + "printf '%s\\n' '" + progress + "' '" + ask + "'\n");
        Files.setPosixFilePermissions(runtime, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        var config = new OwnClawConfig();
        config.getSkills().setGeneratedPath(tmp.resolve("skills/generated").toString());
        config.getDatabase().setPath(tmp.resolve("data/ownclaw.db").toString());
        config.getSandbox().setContainerRuntime(runtime.toString());
        var fake = new ContainerSandbox(config);
        fake.init();
        var container = fake.execute("test-image", "python3", script, skillDir, null, Map.of(), 60,
                (msg, pct) -> seen.add(msg), null);
        assertTrue(container.isSuccess(), container.toString());
        assertEquals(ask, container.stdout().strip());
        assertEquals(List.of("half", "half"), seen);
    }

    @ParameterizedTest(name = "{0}, progress callback {1}")
    @CsvSource({"1, false", "2, false", "1, true", "2, true"})
    @DisplayName("a container run printing past the output limit on either stream is killed, and its result says why")
    void pastTheOutputLimitIsKilled(int fd, boolean progress) throws Exception {
        // A stand-in for the runtime's CLI: it answers `version`, and a run prints without end
        // on the stream it is told, as a skill in the container would through it.
        // One process throughout, with no child to hold the streams open once it is killed.
        Path runtime = Files.createDirectories(tmp.resolve("bin")).resolve("fake-docker");
        Files.writeString(runtime, "#!/bin/sh\n"
                + "[ \"$1\" = version ] && exit 0\n"
                + "c=x; i=0; while [ $i -lt 16 ]; do c=\"$c$c\"; i=$((i+1)); done\n"
                + "n=0; while [ $n -le 256 ]; do printf '%s' \"$c\" >&" + fd + "; n=$((n+1)); done\n"
                + "exec sleep 30\n");
        Files.setPosixFilePermissions(runtime, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        var config = new OwnClawConfig();
        config.getSkills().setGeneratedPath(tmp.resolve("skills/generated").toString());
        config.getDatabase().setPath(tmp.resolve("data/ownclaw.db").toString());
        config.getSandbox().setContainerRuntime(runtime.toString());
        var fake = new ContainerSandbox(config);
        fake.init();
        assertEquals(runtime.toString(), fake.runtime());

        long start = System.nanoTime();
        var result = fake.execute("test-image", "python3", skillDir.resolve("_runner_x.py"), skillDir, null,
                Map.of(), 60, progress ? (msg, pct) -> { } : null, null);
        long ms = (System.nanoTime() - start) / 1_000_000;

        assertFalse(result.isSuccess(), "a run past the output limit succeeded");
        assertTrue(result.stderr().startsWith("Killed: the skill's output went past the 16 MB limit."),
                result.stderr().substring(0, Math.min(200, result.stderr().length())));
        assertTrue(ms < 20_000, "the run was left to go on for " + ms + " ms");
    }

    @Test
    @DisplayName("an authored file that is a link is refused, never mounted where it leads")
    void anAuthoredLinkIsRefused() throws Exception {
        Files.delete(skillDir.resolve("skill.py"));
        Files.createSymbolicLink(skillDir.resolve("skill.py"), env);
        var e = assertThrows(IOException.class, () -> command(skillDir));
        assertTrue(e.getMessage().contains("tampered"), e.getMessage());
    }
}
