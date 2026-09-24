package com.ownclaw.sandbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.skills.PythonEnvironmentService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The jail against the real bubblewrap, on a copy of the production layout: a skill run through
 * {@link ProcessSandbox} cannot read the database, .env, an upload it was not handed or another
 * skill, cannot reach the JVM through /proc, and still writes its own directory and reads /etc.
 * <p>
 * Every claim is checked twice with the same probe: unjailed first, where it must see all of it
 * -- otherwise "cannot see" would prove nothing -- and then jailed. Skipped where bubblewrap is
 * absent or cannot create namespaces (CI, a host with user namespaces blocked).
 * <p>
 * The copy is kept under /var/tmp, not in a JUnit temp directory: those are under /tmp, which the
 * jail replaces as a whole with the run's own, so everything kept there is out of sight whether or
 * not the tmpfs over the hidden roots did its job. /var/tmp the jail shows read-only, as it shows
 * /opt, and only the tmpfs can hide what is in it.
 */
class SkillJailIntegrationTest {

    private static final Path BWRAP = Path.of("/usr/bin/bwrap");

    private Path base;

    private JailTestKit.Mirror m;
    private final ObjectMapper mapper = new ObjectMapper();

    // Prints only facts about what it could reach, never contents it should not have.
    private static final String PROBE = """
            import json, os, socket, subprocess, sys
            p = json.loads(sys.stdin.read())
            def readable(path):
                try:
                    with open(path, 'rb') as f:
                        f.read(1)
                    return True
                except Exception:
                    return False
            def appendable(path):
                try:
                    with open(path, 'a') as f:
                        f.write('')
                    return True
                except Exception:
                    return False
            r = {}
            r['db'] = readable(p['db'])
            r['env'] = readable(p['env'])
            r['log'] = readable(p['log'])
            r['other_upload'] = readable(p['other_upload'])
            r['handed'] = open(p['handed']).read() if readable(p['handed']) else None
            r['other_skill'] = os.path.exists(p['other_skill'])
            r['uploads_listing'] = sorted(os.listdir(p['uploads'])) if os.path.isdir(p['uploads']) else None
            r['jvm_environ'] = readable('/proc/%d/environ' % p['jvm_pid'])
            r['jvm_root'] = os.path.exists('/proc/%d/root' % p['jvm_pid'])
            comms = []
            for d in os.listdir('/proc'):
                if d.isdigit():
                    try:
                        comms.append(open('/proc/%s/comm' % d).read().strip())
                    except Exception:
                        pass
            r['java_visible'] = any('java' in c for c in comms)
            r['ppid'] = os.getppid()
            r['own_dir_writable'] = appendable('written_by_skill.txt')
            r['skill_yaml_writable'] = appendable('SKILL.yaml')
            r['etc_readable'] = readable('/etc/passwd')
            r['jvm_tmp_canary'] = os.path.exists(p['jvm_canary'])
            r['tmp_writable'] = appendable(p['scratch'])
            s = socket.socket()
            s.settimeout(3)
            try:
                s.connect(('127.0.0.1', p['port']))
                r['loopback'] = True
            except Exception:
                r['loopback'] = False
            finally:
                s.close()
            r['cred'] = os.environ.get('SKILL_CRED')
            r['env_names'] = sorted(os.environ.keys())
            r['pwd'] = os.environ.get('PWD')
            if p.get('spawn'):
                # A grandchild that keeps stdout open: unjailed, the JVM would wait for it.
                subprocess.Popen(['sleep', '8'])
            print(json.dumps(r))
            """;

    @BeforeEach
    void requireBubblewrap() throws Exception {
        assumeTrue(Files.isExecutable(BWRAP), "bubblewrap is not installed");
        assumeTrue(exitsZero(BWRAP.toString(), "--unshare-all", "--share-net", "--die-with-parent",
                "--ro-bind", "/", "/", "--dev-bind", "/dev", "/dev", "--proc", "/proc", "--", "/bin/true"),
                "bubblewrap cannot create namespaces on this host");
        assumeTrue(exitsZero("python3", "--version"), "python3 is not available");
        Path outside = Path.of("/var/tmp");
        assumeTrue(Files.isDirectory(outside) && Files.isWritable(outside), "/var/tmp is not writable");
        base = Files.createTempDirectory(outside, "ownclaw-it-");
        m = JailTestKit.Mirror.create(base);
    }

    @AfterEach
    void removeTheCopy() {
        if (base != null) SkillJail.deleteTree(base);
    }

    private static boolean exitsZero(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            p.getOutputStream().close();
            p.getInputStream().readAllBytes();
            return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private Path scratch;

    private JsonNode run(SkillJail jail, Path jvmCanary, int port, boolean spawn,
                         List<String> timing) throws Exception {
        Path script = m.skillDir().resolve("_runner_probe.py");
        Files.deleteIfExists(script);
        Files.writeString(script, PROBE);
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("db", m.db().toString());
        in.put("env", m.env().toString());
        in.put("log", m.logs().resolve("ownclaw.log").toString());
        in.put("other_upload", m.otherUpload().toString());
        in.put("handed", m.handed().toString());
        in.put("other_skill", m.otherSkill().resolve("skill.py").toString());
        in.put("uploads", m.uploads().toString());
        in.put("jvm_pid", ProcessHandle.current().pid());
        in.put("jvm_canary", jvmCanary.toString());
        in.put("port", port);
        in.put("spawn", spawn);
        in.put("scratch", scratch.toString());

        long start = System.nanoTime();
        var result = new ProcessSandbox(null, mapper, jail).execute("python3", script, m.skillDir(),
                mapper.writeValueAsString(in), Map.of("SKILL_CRED", "cred-canary"), 30, null,
                List.of(m.handed()));
        timing.add(String.valueOf(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)));
        assertTrue(result.isSuccess(), "probe failed: " + result.stderr());
        return mapper.readTree(result.stdout());
    }

    private static List<Path> runTmpDirs() throws IOException {
        try (Stream<Path> s = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return s.filter(p -> p.getFileName().toString().startsWith("ownclaw-run-")).toList();
        }
    }

    @Test
    @DisplayName("a jailed skill sees only its own directory, its handed file and the read-only host")
    void jailedSkillSeesOnlyWhatItWasGiven() throws Exception {
        Path jvmCanary = Files.createTempFile("ownclaw-jvm-canary-", ".txt");
        scratch = Path.of("/tmp/ownclaw-it-scratch-" + ProcessHandle.current().pid() + ".txt");
        try (ServerSocket server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
            int port = server.getLocalPort();
            var timing = new ArrayList<String>();

            // Unjailed, the probe sees everything: the control that makes the jailed run mean something.
            JsonNode open = run(SkillJail.off(), jvmCanary, port, false, timing);
            for (String leak : List.of("db", "env", "log", "other_upload", "other_skill", "jvm_environ",
                    "java_visible", "jvm_tmp_canary", "skill_yaml_writable")) {
                assertTrue(open.get(leak).asBoolean(), "control run should see " + leak + ": " + open);
            }
            assertNotEquals(1, open.get("ppid").asInt());
            assertTrue(Files.exists(scratch), "the control run writes the host's /tmp");
            Files.delete(scratch);

            var jail = new SkillJail("auto", BWRAP.toString(), m.layout(), List.of(), System::currentTimeMillis);
            var status = jail.status();
            assertEquals(SkillJail.State.READY, status.state(), status.reason());
            assertFalse(status.selfCheck().containsValue(false), status.selfCheck().toString());
            assertEquals(List.of(m.root().toRealPath()), status.hiddenRoots());

            var before = runTmpDirs();
            JsonNode jailed = run(jail, jvmCanary, port, true, timing);

            for (String hidden : List.of("db", "env", "log", "other_upload", "other_skill")) {
                assertFalse(jailed.get(hidden).asBoolean(), hidden + " is visible inside the jail: " + jailed);
            }
            assertEquals("handed-content", jailed.get("handed").asText(), jailed.toString());
            assertEquals(List.of("handed.txt"),
                    mapper.convertValue(jailed.get("uploads_listing"), List.class),
                    "the uploads directory must show only the handed file");

            assertFalse(jailed.get("jvm_environ").asBoolean(), "the JVM's environment is reachable");
            assertFalse(jailed.get("jvm_root").asBoolean(), "the JVM's root is reachable");
            assertFalse(jailed.get("java_visible").asBoolean(), "the JVM is in the process list");
            assertEquals(1, jailed.get("ppid").asInt(), "a fresh PID namespace makes the parent pid 1");

            assertTrue(jailed.get("own_dir_writable").asBoolean());
            assertTrue(Files.exists(m.skillDir().resolve("written_by_skill.txt")),
                    "a write to the skill's own directory must reach the host");
            assertFalse(jailed.get("skill_yaml_writable").asBoolean(), "SKILL.yaml is writable inside the jail");
            assertTrue(jailed.get("etc_readable").asBoolean());
            assertTrue(jailed.get("tmp_writable").asBoolean());
            assertFalse(Files.exists(scratch), "a jailed write to /tmp reached the host's /tmp");
            assertFalse(jailed.get("jvm_tmp_canary").asBoolean(), "the JVM's /tmp is visible");
            assertTrue(jailed.get("loopback").asBoolean(), "the host network must stay shared");

            assertEquals("cred-canary", jailed.get("cred").asText());
            for (JsonNode n : jailed.get("env_names")) {
                String name = n.asText();
                // PWD is bubblewrap's own, set for --chdir; it is the skill directory, not the JVM's.
                assertTrue(SkillJail.ENV_ALLOWLIST.contains(name) || name.equals("SKILL_CRED")
                        || name.equals("PWD"), "unexpected variable inside the jail: " + name);
            }
            assertEquals(m.skillDir().toString(), jailed.get("pwd").asText());

            long jailedMs = Long.parseLong(timing.get(1));
            assertTrue(jailedMs < 4000, "a background grandchild held the run open for " + jailedMs + " ms");
            assertEquals(before, runTmpDirs(), "the run's private /tmp must be removed");
        } finally {
            Files.deleteIfExists(jvmCanary);
            Files.deleteIfExists(scratch);
        }
    }

    // Prints, for each path it is given, whether it could read a byte of it -- never the byte.
    private static final String READABLE = """
            import json, sys
            def readable(path):
                try:
                    with open(path, 'rb') as f:
                        f.read(1)
                    return True
                except Exception:
                    return False
            print(json.dumps({p: readable(p) for p in json.loads(sys.stdin.read())}))
            """;

    /** A service directory holding .env and one skill; its data lives wherever the test puts it. */
    private static Path skillUnder(Path root) throws IOException {
        Files.writeString(Files.createDirectories(root).resolve(".env"), "OWNCLAW_OPS_TOKEN=canary-env-secret\n");
        Path skillDir = Files.createDirectories(root.resolve("skills/generated/probe_skill"));
        Files.writeString(skillDir.resolve("SKILL.yaml"), "name: probe_skill\n");
        Files.writeString(skillDir.resolve("skill.py"), "def run(params):\n    return {}\n");
        return skillDir;
    }

    /** For each of {@code paths}, whether a skill run from {@code skillDir} through {@code jail} can read it. */
    private JsonNode readableFrom(SkillJail jail, Path skillDir, List<Path> paths) throws Exception {
        Path script = skillDir.resolve("_runner_readable.py");
        Files.deleteIfExists(script);
        Files.writeString(script, READABLE);
        var result = new ProcessSandbox(null, mapper, jail).execute("python3", script, skillDir,
                mapper.writeValueAsString(paths.stream().map(Path::toString).toList()), Map.of(), 30);
        assertTrue(result.isSuccess(), "probe failed: " + result.stderr());
        return mapper.readTree(result.stdout());
    }

    @Test
    @DisplayName("a data directory linked outside the service directory is hidden at its real path too")
    void aLinkedDataDirectoryIsHiddenAtBothEnds() throws Exception {
        // Two hidden roots, the service directory sorting first. The self-check probes the paths
        // the service is configured with, which the first root already hides; only a tmpfs over
        // the second keeps the database unreadable at its real path.
        Path root = base.resolve("linked/opt-ownclaw");
        Path skillDir = skillUnder(root);
        Path realData = Files.createDirectories(base.resolve("linked/second-disk/ownclaw-data"));
        Files.writeString(realData.resolve("ownclaw.db"), "canary-db-secret");
        Files.createSymbolicLink(root.resolve("data"), realData);
        var layout = new SkillJail.Layout(root, root.resolve("data/ownclaw.db"), root.resolve("skills/generated"), null);
        List<Path> db = List.of(root.resolve("data/ownclaw.db"), realData.resolve("ownclaw.db"));

        JsonNode open = readableFrom(SkillJail.off(), skillDir, db);
        for (Path p : db) assertTrue(open.get(p.toString()).asBoolean(), "control run should read " + p);

        var jail = new SkillJail("required", BWRAP.toString(), layout, List.of(), System::currentTimeMillis);
        assertEquals(SkillJail.State.READY, jail.status().state(), jail.status().reason());
        assertEquals(List.of(root.toRealPath(), realData.toRealPath()), jail.status().hiddenRoots());
        JsonNode jailed = readableFrom(jail, skillDir, db);
        for (Path p : db) assertFalse(jailed.get(p.toString()).asBoolean(), p + " is readable inside the jail");
    }

    @Test
    @DisplayName("a data directory that appears after the jail came up is hidden from the next launch")
    void aDataDirectoryMadeLaterIsHidden() throws Exception {
        // The roots are worked out again for every launch: roots kept from the check that made
        // the jail READY would not include a directory that did not exist then.
        Path root = base.resolve("late/opt-ownclaw");
        Path skillDir = skillUnder(root);
        Path data = base.resolve("late/ownclaw-data");
        var layout = new SkillJail.Layout(root, data.resolve("ownclaw.db"), root.resolve("skills/generated"), null);
        var jail = new SkillJail("required", BWRAP.toString(), layout, List.of(), System::currentTimeMillis);
        assertEquals(SkillJail.State.READY, jail.status().state(), jail.status().reason());
        assertEquals(List.of(root.toRealPath()), jail.status().hiddenRoots(), "the data directory is not there yet");

        Path db = Files.writeString(Files.createDirectories(data).resolve("ownclaw.db"), "canary-db-secret");
        assertTrue(readableFrom(SkillJail.off(), skillDir, List.of(db)).get(db.toString()).asBoolean(),
                "control run should read the database");
        assertFalse(readableFrom(jail, skillDir, List.of(db)).get(db.toString()).asBoolean(),
                "a data directory made after the jail's check is readable inside the jail");
    }

    @Test
    @DisplayName("a venv built through PythonEnvironmentService lands on the host; nothing else it tries to write does")
    void provisioningWritesOnlyItsOwnEnvironment() throws Exception {
        // A stand-in python, so no index is needed. It has to be visible inside the jail, and
        // /tmp there is the run's own, so it lives under /var/tmp. It notes, in its working
        // directory, what it could reach -- facts only, never contents.
        Path outside = Path.of("/var/tmp");
        assumeTrue(Files.isDirectory(outside) && Files.isWritable(outside), "/var/tmp is not writable");
        Path dir = Files.createTempDirectory(outside, "ownclaw-it-python-");
        try {
            Path data = m.db().getParent();
            String script = "#!/bin/sh\n"
                    + "if [ \"$1\" = --version ]; then echo 'Python 3.0-test'; exit 0; fi\n"
                    + "{\n"
                    + "  for p in '" + m.db() + "' '" + m.env() + "' '" + m.otherUpload() + "' '"
                    + m.otherSkill() + "'; do [ -e \"$p\" ] && echo \"visible=$p\"; done\n"
                    + "  [ -r '" + m.skillDir().resolve("requirements.txt") + "' ] && echo requirements=readable\n"
                    + "  (echo x > '" + data.resolve("planted") + "') 2>/dev/null && echo data=writable\n"
                    + "  (echo x > '" + m.skillDir().resolve("planted") + "') 2>/dev/null && echo skilldir=writable\n"
                    + "  (echo x >> '" + m.skillDir().resolve("requirements.txt") + "') 2>/dev/null"
                    + " && echo requirements=writable\n"
                    + "  echo \"ppid=$PPID\"\n"
                    + "} >> observed\n"
                    + "if [ \"$1\" = -m ] && [ \"$2\" = venv ]; then mkdir -p \"$3/bin\" && cp \"$0\" \"$3/bin/python\"; fi\n"
                    + "exit 0\n";
            Path python = Files.writeString(dir.resolve("python"), script);
            Files.setPosixFilePermissions(python, PosixFilePermissions.fromString("rwxr-xr-x"));
            Files.writeString(m.skillDir().resolve("requirements.txt"), "requests\n");

            var jail = new SkillJail("required", BWRAP.toString(), m.layout(), List.of(), System::currentTimeMillis);
            assertEquals(SkillJail.State.READY, jail.status().state(), jail.status().reason());
            var cfg = new OwnClawConfig();
            cfg.getSkills().setGeneratedPath(m.generated().toString());
            cfg.getDatabase().setPath(m.db().toString());
            cfg.getSandbox().setPythonPath(python.toString());
            var svc = new PythonEnvironmentService(cfg, jail);
            svc.init();

            var res = svc.resolveExecution(m.skillDir(), "probe_skill");

            Path venv = m.root().resolve("skills/_envs/probe_skill");
            assertEquals(venv, res.envDir(), String.valueOf(svc.getLastProvisionError(m.skillDir(), "probe_skill")));
            assertTrue(Files.isRegularFile(venv.resolve("bin/python")), "the venv must land on the host");
            assertTrue(Files.isRegularFile(venv.resolve(".req_hash"), LinkOption.NOFOLLOW_LINKS));
            List<String> seen = Files.readAllLines(venv.resolve("observed"));
            assertEquals(3, seen.stream().filter(l -> l.startsWith("ppid=")).count(),
                    "venv, pip --version and pip install, each jailed: " + seen);
            assertTrue(seen.stream().filter(l -> l.startsWith("ppid=")).allMatch(l -> l.equals("ppid=1")),
                    "a fresh PID namespace makes the parent pid 1: " + seen);
            assertTrue(seen.contains("requirements=readable"), seen.toString());
            for (String line : seen) {
                assertFalse(line.startsWith("visible="), "provisioning saw " + line);
                assertFalse(line.endsWith("=writable"), "provisioning could write: " + line);
            }
            assertFalse(Files.exists(data.resolve("planted")), "a provisioning write reached data/");
            assertFalse(Files.exists(m.skillDir().resolve("planted")), "a provisioning write reached the skill");
            assertEquals("requests\n", Files.readString(m.skillDir().resolve("requirements.txt")));
        } finally {
            SkillJail.deleteTree(dir);
        }
    }

    @Test
    @DisplayName("the self-check passes on a real host and needs no network beyond the host's own")
    void selfCheckOnRealBubblewrap() throws Exception {
        var jail = new SkillJail("required", BWRAP.toString(), m.layout(), List.of(), System::currentTimeMillis);
        var s = jail.status();
        assertEquals(SkillJail.State.READY, s.state(), s.reason());
        assertTrue(s.version().startsWith("bubblewrap"), s.version());
        assertEquals(List.of("hidden", "pidNamespace", "sharedNetwork", "workDirWritable", "readOnlyBinds",
                "etcReadable", "privateTmp"), List.copyOf(s.selfCheck().keySet()));
        try (Stream<Path> left = Files.list(m.root().resolve("skills"))) {
            assertTrue(left.noneMatch(p -> p.getFileName().toString().startsWith(".jail-probe-")),
                    "the probe directory must be removed");
        }
    }
}
