package com.ownclaw.sandbox;

import com.ownclaw.config.OwnClawConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
 * credentials. The container never passes the jail, so what the jail refuses of a bind -- a
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
        return container.buildRunCommand("test-image", "ownclaw-run-test", "python3", dir.resolve("_runner_x.py"),
                dir, null);
    }

    private List<String> handing(Map<String, String> volumes) throws IOException {
        return container.buildRunCommand("test-image", "ownclaw-run-test", "python3",
                skillDir.resolve("_runner_x.py"), skillDir, volumes);
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
    @DisplayName("on the host's network the container may connect, not capture; it runs under a name of its own")
    void connectNotCapture() throws Exception {
        List<String> cmd = command(skillDir);
        int image = cmd.indexOf("test-image");
        for (String option : List.of("--network=host", "--cap-drop=NET_RAW", "--security-opt=no-new-privileges",
                "--name=ownclaw-run-test")) {
            int at = cmd.indexOf(option);
            assertTrue(at > 1 && at < image, option + " is not an option of the run: " + cmd);
        }
    }

    /**
     * A stand-in for the runtime's CLI: it answers `version`, notes the name each `kill` is
     * given (in {@code kills}) and the argv of each `run` (in {@code runArgv}), and runs
     * {@code runBody} for a run.
     */
    private ContainerSandbox fakeRuntime(String runBody) throws IOException {
        Path runtime = Files.createDirectories(tmp.resolve("bin")).resolve("fake-docker");
        Files.writeString(runtime, "#!/bin/sh\n"
                + "[ \"$1\" = version ] && exit 0\n"
                + "[ \"$1\" = kill ] && { echo \"$2\" >> '" + tmp.resolve("kills") + "'; exit 0; }\n"
                + "printf '%s\\n' \"$@\" > '" + tmp.resolve("runArgv") + "'\n"
                + runBody);
        Files.setPosixFilePermissions(runtime, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        var config = new OwnClawConfig();
        config.getSkills().setGeneratedPath(tmp.resolve("skills/generated").toString());
        config.getDatabase().setPath(tmp.resolve("data/ownclaw.db").toString());
        config.getSandbox().setContainerRuntime(runtime.toString());
        var fake = new ContainerSandbox(config);
        fake.init();
        assertEquals(runtime.toString(), fake.runtime());
        return fake;
    }

    /** The names the stand-in runtime was told to kill, and the one its run was given. */
    private void assertTheRunWasKilled() throws IOException {
        String name = Files.readAllLines(tmp.resolve("runArgv")).stream().filter(a -> a.startsWith("--name="))
                .map(a -> a.substring("--name=".length())).findFirst().orElseThrow();
        assertTrue(name.matches("ownclaw-run-[0-9a-f-]{36}"), name);
        assertEquals(List.of(name), Files.readAllLines(tmp.resolve("kills")), "the container was not killed");
    }

    @Test
    @DisplayName("a container run killed for stalling is killed in the runtime too, by its name")
    void aStalledRunKillsItsContainer() throws Exception {
        var fake = fakeRuntime("exec sleep 30\n");
        var result = fake.execute("test-image", "python3", skillDir.resolve("_runner_x.py"), skillDir, null,
                1, null, null);
        assertTrue(result.timedOut(), result.toString());
        assertTheRunWasKilled();
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
    @DisplayName("every start of the runtime CLI gets each daemon setting the service has, and nothing else of its environment")
    void everyStartTalksToTheSameDaemon() {
        // The run and the calls before it -- version, image inspect, build -- alike: an image
        // built on one daemon and run against another is not there.
        // Each by name: a name left out of RUNTIME_ENV is a daemon the CLI no longer finds.
        List<String> daemon = List.of("DOCKER_HOST", "DOCKER_CONTEXT", "DOCKER_CONFIG", "DOCKER_TLS_VERIFY",
                "DOCKER_CERT_PATH", "CONTAINER_HOST", "CONTAINERS_CONF", "XDG_RUNTIME_DIR");
        var service = new java.util.HashMap<String, String>();
        for (String name : daemon) service.put(name, "service-" + name);
        service.put("OWNCLAW_OPS_TOKEN", "canary-env-secret");
        Map<String, String> cli = ContainerSandbox.cli(List.of("docker", "version"), service).environment();
        for (String name : daemon) assertEquals("service-" + name, cli.get(name), name);
        for (String name : cli.keySet()) {
            assertTrue(SkillJail.ENV_ALLOWLIST.contains(name) || SkillJail.ENCODING.containsKey(name)
                    || ContainerSandbox.RUNTIME_ENV.contains(name),
                    "the runtime CLI was handed " + name);
        }
    }

    @Test
    @DisplayName("a container run gets its credentials on stdin and puts them in its own environment; the runtime CLI's environment and command line never hold one")
    void theCredentialsGoInOnStdin() throws Exception {
        assumeTrue(Files.isExecutable(Path.of("/usr/bin/python3")), "python3 is not available");
        // A stand-in for the runtime's CLI: it keeps its own environment and argv, and runs the
        // runner as the container would, from the skill directory mounted at /skill, with
        // nothing of its own environment.
        Path record = Files.createDirectories(tmp.resolve("record"));
        Path runtime = Files.createDirectories(tmp.resolve("bin")).resolve("fake-docker");
        Files.writeString(runtime, "#!/bin/sh\n"
                + "case \"$1\" in version|image) exit 0;; esac\n"
                + "tr '\\0' '\\n' < /proc/$$/environ > '" + record.resolve("env") + "'\n"
                + "printf '%s\\n' \"$@\" > '" + record.resolve("argv") + "'\n"
                + "for a in \"$@\"; do case \"$a\" in *:/skill) skill=\"${a%:/skill}\";; esac; done\n"
                + "for last in \"$@\"; do :; done\n"
                + "exec env -i PATH=/usr/bin:/bin /usr/bin/python3 \"$skill/${last#/skill/}\"\n");
        Files.setPosixFilePermissions(runtime, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        var config = new OwnClawConfig();
        config.getSkills().setGeneratedPath(tmp.resolve("skills/generated").toString());
        config.getDatabase().setPath(tmp.resolve("data/ownclaw.db").toString());
        config.getSandbox().setContainerRuntime(runtime.toString());
        var fake = new ContainerSandbox(config);
        fake.init();
        assertEquals(runtime.toString(), fake.runtime());

        Files.writeString(skillDir.resolve("skill.py"), """
                import json, os, subprocess
                def run(params):
                    child = subprocess.run(['sh', '-c', 'printf %s "$SKILL_TOKEN"'], capture_output=True, text=True)
                    return {'output': json.dumps({'token': os.environ.get('SKILL_TOKEN'), 'child': child.stdout,
                                                  'params': sorted(params)})}
                """);
        var vault = new com.ownclaw.users.CredentialVault(null) {
            @Override
            public Map<String, String> getCredentials(String userId, List<String> keys) {
                return Map.of("SKILL_TOKEN", "cred-canary-4c1");
            }
        };
        var skill = new com.ownclaw.agent.tools.DynamicSkill("nmap_scan", "scans", Map.of(), skillDir, false,
                false, 30, new ProcessSandbox(JailTestKit.off()),
                new com.ownclaw.skills.PythonEnvironmentService(config, JailTestKit.off()), List.of("SKILL_TOKEN"),
                vault, List.of("nmap"), null, fake, null);

        var result = skill.execute(Map.of("x", 1),
                new com.ownclaw.agent.tools.ToolExecutionContext("u1", "t1", null, () -> false));

        assertTrue(result.success(), result.output());
        var seen = new com.fasterxml.jackson.databind.ObjectMapper().readTree(result.output());
        assertEquals("cred-canary-4c1", seen.get("token").asText());
        assertEquals("cred-canary-4c1", seen.get("child").asText(), "a subprocess of the skill did not inherit it");
        assertEquals("[\"x\"]", seen.get("params").toString(), "the credentials are left in the skill's params");
        String cliEnv = Files.readString(record.resolve("env"));
        String argv = Files.readString(record.resolve("argv"));
        for (String leak : List.of("SKILL_TOKEN", "cred-canary-4c1")) {
            assertFalse(cliEnv.contains(leak), "the runtime CLI's environment holds " + leak + ": " + cliEnv);
            assertFalse(argv.contains(leak), "the run's command line holds " + leak + ": " + argv);
        }
    }

    @Test
    @DisplayName("one runner for both: progress forwarded from each, and every other line -- a need_input one too -- handed back as it was printed")
    void oneRunnerForAHostAndAContainerRun() throws Exception {
        assumeTrue(Files.isExecutable(Path.of("/usr/bin/python3")), "python3 is not available");
        String progress = "{\"type\":\"progress\",\"message\":\"half\",\"percent\":50}";
        String ask = "{\"type\":\"need_input\",\"question\":\"which one?\"}";
        var seen = new java.util.ArrayList<String>();

        Path script = Files.writeString(skillDir.resolve("_runner_ask.py"),
                "print('" + progress + "')\nprint('" + ask + "')\n");
        var host = new ProcessSandbox(JailTestKit.off()).execute("python3", script, skillDir, null, Map.of(), 60,
                (msg, pct) -> seen.add(msg), List.of());
        assertTrue(host.isSuccess(), host.toString());
        assertEquals(ask, host.stdout().strip());

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
        var container = fake.execute("test-image", "python3", script, skillDir, null, 60,
                (msg, pct) -> seen.add(msg), null);
        assertTrue(container.isSuccess(), container.toString());
        assertEquals(ask, container.stdout().strip());
        assertEquals(List.of("half", "half"), seen);
    }

    @ParameterizedTest(name = "fd {0}")
    @ValueSource(ints = {1, 2})
    @DisplayName("a container run printing past the output limit on either stream is killed, and its result says why")
    void pastTheOutputLimitIsKilled(int fd) throws Exception {
        // A run prints without end on the stream it is told, as a skill in the container would
        // through the CLI. One process throughout, with no child to hold the streams open once
        // it is killed.
        var fake = fakeRuntime("c=x; i=0; while [ $i -lt 16 ]; do c=\"$c$c\"; i=$((i+1)); done\n"
                + "n=0; while [ $n -le 256 ]; do printf '%s' \"$c\" >&" + fd + "; n=$((n+1)); done\n"
                + "exec sleep 30\n");

        long start = System.nanoTime();
        var result = fake.execute("test-image", "python3", skillDir.resolve("_runner_x.py"), skillDir, null,
                60, null, null);
        long ms = (System.nanoTime() - start) / 1_000_000;

        assertFalse(result.isSuccess(), "a run past the output limit succeeded");
        assertTrue(result.stderr().startsWith("Killed: the skill's output went past the 16 MB limit."),
                result.stderr().substring(0, Math.min(200, result.stderr().length())));
        assertTrue(ms < 20_000, "the run was left to go on for " + ms + " ms");
        assertTheRunWasKilled();
    }

    @Test
    @DisplayName("an image build printing past the output limit is stopped, and fails saying why")
    void aBuildPastTheOutputLimitFails() throws Exception {
        // `image inspect` finds nothing, so every candidate base image is built -- and prints
        // 17 MB of lines, then waits.
        Path runtime = Files.createDirectories(tmp.resolve("bin")).resolve("fake-docker");
        Files.writeString(runtime, "#!/bin/sh\n"
                + "[ \"$1\" = version ] && exit 0\n"
                + "[ \"$1\" = image ] && exit 1\n"
                + "yes building | head -c 17000000\n"
                + "exec sleep 30\n");
        Files.setPosixFilePermissions(runtime, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        var config = new OwnClawConfig();
        config.getSkills().setGeneratedPath(tmp.resolve("skills/generated").toString());
        config.getDatabase().setPath(tmp.resolve("data/ownclaw.db").toString());
        config.getSandbox().setContainerRuntime(runtime.toString());
        // Well above the 5 s between two looks: every build is killed at the limit long before.
        config.getSandbox().setStallTimeout(20);
        var fake = new ContainerSandbox(config);
        fake.init();

        long start = System.nanoTime();
        var e = assertThrows(IOException.class, () -> fake.ensureImage(List.of("nmap"), null, skillDir, null, null));
        long ms = (System.nanoTime() - start) / 1_000_000;
        assertTrue(e.getMessage().contains("Build output went past the 16 MB limit"), e.getMessage());
        assertTrue(ms < 60_000, "the builds were left to go on for " + ms + " ms");
    }

    @Test
    @DisplayName("the image sets what a run needs -- nmap told it has no raw sockets among it -- and the run command sets no variable at all")
    void theImageSetsWhatARunNeeds() throws Exception {
        // The stand-in keeps the Dockerfile of the build context it is given, its last argument.
        Path kept = tmp.resolve("Dockerfile.built");
        Path runtime = Files.createDirectories(tmp.resolve("bin")).resolve("fake-docker");
        Files.writeString(runtime, "#!/bin/sh\n"
                + "case \"$1\" in version) exit 0;; image) exit 1;; run) echo 'Python 3.12.0'; exit 0;; esac\n"
                + "for last in \"$@\"; do :; done\n"
                + "cp \"$last/Dockerfile\" '" + kept + "'\n");
        Files.setPosixFilePermissions(runtime, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        var config = new OwnClawConfig();
        config.getSkills().setGeneratedPath(tmp.resolve("skills/generated").toString());
        config.getDatabase().setPath(tmp.resolve("data/ownclaw.db").toString());
        config.getSandbox().setContainerRuntime(runtime.toString());
        var fake = new ContainerSandbox(config);
        fake.init();

        fake.ensureImage(List.of("nmap"), null, skillDir, null, null);

        List<String> dockerfile = Files.readAllLines(kept);
        for (String env : List.of("ENV PYTHONIOENCODING=utf-8", "ENV PYTHONUTF8=1", "ENV LD_LIBRARY_PATH=/usr/local/lib",
                "ENV NMAP_UNPRIVILEGED=1")) {
            assertTrue(dockerfile.contains(env), env + " is not in " + dockerfile);
        }
        List<String> run = command(skillDir);
        assertFalse(run.stream().skip(1).anyMatch(a -> a.equals("-e") || a.startsWith("--env")), "the run sets a variable: " + run);
    }

    @Test
    @DisplayName("with every thread of the common pool held, an image still builds: its output is read as it prints, and the image is checked")
    void aBuildIsReadWhateverThePoolIsDoing() throws Exception {
        // A build printing a line every 0.3 s for 6 s: unread, it looks stalled at the first
        // look, after 5 s, against a 3 s timeout. The check of the image prints its version.
        Path runtime = Files.createDirectories(tmp.resolve("bin")).resolve("fake-docker");
        Files.writeString(runtime, "#!/bin/sh\n"
                + "case \"$1\" in version) exit 0;; image) exit 1;; run) echo 'Python 3.12.0'; exit 0;; esac\n"
                + "i=0; while [ $i -lt 20 ]; do echo \"step $i\"; sleep 0.3; i=$((i+1)); done\n");
        Files.setPosixFilePermissions(runtime, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        var config = new OwnClawConfig();
        config.getSkills().setGeneratedPath(tmp.resolve("skills/generated").toString());
        config.getDatabase().setPath(tmp.resolve("data/ownclaw.db").toString());
        config.getSandbox().setContainerRuntime(runtime.toString());
        config.getSandbox().setStallTimeout(3);
        var fake = new ContainerSandbox(config);
        fake.init();

        int threads = java.util.concurrent.ForkJoinPool.getCommonPoolParallelism();
        var holding = new java.util.concurrent.CountDownLatch(threads);
        var release = new java.util.concurrent.atomic.AtomicBoolean();
        for (int i = 0; i < threads; i++) {
            java.util.concurrent.ForkJoinPool.commonPool().execute(() -> {
                holding.countDown();
                while (!release.get()) {
                    try { Thread.sleep(10); } catch (InterruptedException e) { return; }
                }
            });
        }
        try {
            assertTrue(holding.await(10, java.util.concurrent.TimeUnit.SECONDS), "the pool's threads were not all held");
            String tag = assertTimeoutPreemptively(java.time.Duration.ofSeconds(40),
                    () -> fake.ensureImage(List.of("nmap"), null, skillDir, null, null));
            assertTrue(tag.startsWith("localhost/ownclaw-skill-"), tag);
        } finally {
            release.set(true);
        }
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
