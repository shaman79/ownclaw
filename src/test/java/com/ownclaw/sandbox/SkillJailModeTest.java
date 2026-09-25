package com.ownclaw.sandbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What each mode does on each kind of host, driven through stand-in bubblewrap binaries and a
 * clock the test moves. The case that matters most is the first deploy: bubblewrap present but
 * blocked must leave skills running unjailed under {@code auto}, and must stop them under
 * {@code required} without starting anything.
 */
class SkillJailModeTest {

    @TempDir Path tmp;

    private JailTestKit.Mirror m;
    private Path record;
    private Path flag;
    private final AtomicLong now = new AtomicLong(1_000_000);
    private static final Path HOST_PROBE_FILE = Path.of("/tmp/ownclaw-jail-probe.txt");
    private boolean probeFileExisted;

    @BeforeEach
    void setUp() throws IOException {
        m = JailTestKit.Mirror.create(tmp);
        record = Files.createDirectories(tmp.resolve("record"));
        flag = tmp.resolve("bwrap-fails");
        probeFileExisted = Files.exists(HOST_PROBE_FILE);
    }

    @AfterEach
    void tidyHostTmp() throws IOException {
        // The pass-through stand-in runs the probe unjailed, so it writes into the real /tmp.
        if (!probeFileExisted) Files.deleteIfExists(HOST_PROBE_FILE);
    }

    private SkillJail jail(String mode, Path bwrap) {
        return new SkillJail(mode, bwrap.toString(), m.layout(), List.of(), now::get);
    }

    private Path switchable() throws IOException {
        return JailTestKit.switchable(tmp.resolve("bin/bwrap"), flag, record);
    }

    /** A script in the skill directory that leaves a marker if it ever runs. */
    private Path markerScript(Path marker) throws IOException {
        return Files.writeString(m.skillDir().resolve("_runner_t.py"),
                "open(" + pyString(marker) + ", 'w').write('ran')\nprint('ran')\n");
    }

    private static String pyString(Path p) {
        return "'" + p.toString().replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    private static final List<String> CMD = List.of("python3", "/x/_runner.py");

    @Test
    @DisplayName("auto, bubblewrap absent: inactive, and a skill runs unjailed")
    void autoAbsent() throws Exception {
        var jail = jail("auto", tmp.resolve("no/such/bwrap"));
        var s = jail.status();
        assertEquals(SkillJail.State.INACTIVE, s.state());
        assertTrue(s.reason().contains("not installed"), s.reason());
        assertTrue(jail.unavailable().isEmpty());

        try (var l = jail.prepare(CMD, m.skillDir(), List.of())) {
            assertFalse(l.jailed());
            assertNull(l.refusal());
            assertEquals(CMD, l.argv());
        }
        Path marker = tmp.resolve("marker");
        var result = new ProcessSandbox(null, new ObjectMapper(), jail)
                .execute("python3", markerScript(marker), m.skillDir(), null, Map.of(), 10);
        assertTrue(result.isSuccess(), result.stderr());
        assertTrue(Files.exists(marker));
        assertFalse(Files.exists(m.skillDir().resolve("requirements.txt")),
                "an inactive jail must not touch the skill directory");
    }

    @Test
    @DisplayName("auto, bubblewrap present but blocked: inactive with bubblewrap's own words, never a refusal")
    void autoPresentButBlocked() throws Exception {
        Files.createFile(flag);
        var jail = jail("auto", switchable());
        var s = jail.status();
        assertEquals(SkillJail.State.INACTIVE, s.state());
        assertTrue(s.reason().contains("bwrap: setting up uid map: Permission denied"), s.reason());
        assertTrue(jail.unavailable().isEmpty());
        try (var l = jail.prepare(CMD, m.skillDir(), List.of())) {
            assertEquals(CMD, l.argv());
            assertNull(l.refusal());
        }
    }

    @Test
    @DisplayName("required, bubblewrap present but blocked: refusing, with the same reason")
    void requiredPresentButBlocked() throws Exception {
        Files.createFile(flag);
        var jail = jail("required", switchable());
        assertEquals(SkillJail.State.REFUSING, jail.status().state());
        assertTrue(jail.unavailable().orElseThrow().contains("isolation required but unavailable"));
        assertTrue(jail.unavailable().orElseThrow().contains("uid map"));
    }

    @Test
    @DisplayName("a bubblewrap that runs but hides nothing is caught by the self-check and refused, even under auto")
    void passThroughRefused() throws Exception {
        var jail = jail("auto", JailTestKit.passThrough(tmp.resolve("bin/bwrap"), record));
        var s = jail.status();
        assertEquals(SkillJail.State.REFUSING, s.state(), s.reason());
        assertTrue(s.reason().contains(m.db().toString()), "should name the visible database: " + s.reason());
        assertFalse(s.selfCheck().get("hidden"));
        assertFalse(s.selfCheck().get("pidNamespace"));
        assertTrue(new ProcessSandbox(null, new ObjectMapper(), jail).unavailable().isPresent());
        try (var l = jail.prepare(CMD, m.skillDir(), List.of())) {
            assertNotNull(l.refusal());
            assertTrue(l.argv().isEmpty());
        }
    }

    @Test
    @DisplayName("required, bubblewrap absent: execute returns the refusal and starts no process")
    void requiredAbsentStartsNothing() throws Exception {
        var jail = jail("required", tmp.resolve("no/such/bwrap"));
        var sandbox = new ProcessSandbox(null, new ObjectMapper(), jail);
        Path marker = tmp.resolve("marker");
        Path script = markerScript(marker);

        var result = sandbox.execute("python3", script, m.skillDir(), null, Map.of(), 10);
        assertFalse(result.isSuccess());
        assertTrue(result.stderr().startsWith("isolation required but unavailable:"), result.stderr());
        var withProgress = sandbox.execute("python3", script, m.skillDir(), null, Map.of(), 10,
                (msg, pct) -> { }, List.of());
        assertFalse(withProgress.isSuccess());
        assertFalse(Files.exists(marker), "a refused launch must not run the script");
        assertTrue(sandbox.unavailable().isPresent());
        assertEquals(2, jail.status().refusedRuns());
    }

    @Test
    @DisplayName("off: bubblewrap is never even asked, and the command is unchanged")
    void offNeverTouchesBwrap() throws Exception {
        var jail = jail("off", JailTestKit.passThrough(tmp.resolve("bin/bwrap"), record));
        assertEquals(SkillJail.State.OFF, jail.status().state());
        try (var l = jail.prepare(CMD, m.skillDir(), List.of())) {
            assertEquals(CMD, l.argv());
            assertFalse(l.jailed());
        }
        assertFalse(Files.exists(record.resolve("argv")), "off must not run bubblewrap at all");
    }

    @Test
    @DisplayName("an unknown mode refuses rather than quietly running as auto")
    void unknownModeRefuses() throws Exception {
        var jail = jail("requierd", switchable());
        assertEquals(SkillJail.State.REFUSING, jail.status().state());
        assertTrue(jail.status().reason().contains("unknown"));
    }

    @Test
    @DisplayName("inactive becomes ready once bubblewrap works and a minute has passed -- not before")
    void inactiveToReadyAfterAMinute() throws Exception {
        Files.createFile(flag);
        var jail = jail("auto", switchable());
        assertEquals(SkillJail.State.INACTIVE, jail.status().state());

        Files.delete(flag);
        now.addAndGet(SkillJail.RECHECK_MS - 1);
        assertEquals(SkillJail.State.INACTIVE, jail.status().state());
        now.addAndGet(1);
        var s = jail.status();
        assertEquals(SkillJail.State.READY, s.state(), s.reason());
        assertFalse(s.selfCheck().containsValue(false), s.selfCheck().toString());
    }

    @Test
    @DisplayName("bubblewrap appearing is noticed at once, without waiting for the minute")
    void binaryAppears() throws Exception {
        Path bwrap = tmp.resolve("bin/bwrap");
        var jail = jail("auto", bwrap);
        assertEquals(SkillJail.State.INACTIVE, jail.status().state());
        JailTestKit.switchable(bwrap, flag, record);
        assertEquals(SkillJail.State.READY, jail.status().state());
    }

    @Test
    @DisplayName("a jail that was on and stops starting fails the run, then refuses at its next check, never runs unjailed")
    void aJailThatStopsStartingRefuses() throws Exception {
        var jail = jail("auto", switchable());
        var sandbox = new ProcessSandbox(null, new ObjectMapper(), jail);
        assertEquals(SkillJail.State.READY, jail.status().state());

        // The host changes under a jail that passed; READY is not re-examined on time alone.
        Files.createFile(flag);
        now.addAndGet(SkillJail.RECHECK_MS);
        assertEquals(SkillJail.State.READY, jail.status().state());

        Path marker = tmp.resolve("marker");
        var result = sandbox.execute("python3", markerScript(marker), m.skillDir(), null, Map.of(), 10);
        assertTrue(result.stderr().startsWith("bwrap:"), result.stderr());
        assertFalse(result.isSuccess(), "the failed run fails; it is never retried unjailed");
        assertFalse(Files.exists(marker));

        // The next check -- the operator's self-test forces one -- cannot start a jail. Before
        // the jail was ever on that would be INACTIVE; after, every later launch would run
        // unjailed. It refuses instead.
        var s = jail.recheck();
        assertEquals(SkillJail.State.REFUSING, s.state(), s.reason());
        assertTrue(jail.unavailable().orElseThrow().startsWith("skill isolation was on and cannot start a jail now"),
                jail.unavailable().toString());
        var next = sandbox.execute("python3", markerScript(marker), m.skillDir(), null, Map.of(), 10);
        assertFalse(next.isSuccess());
        assertFalse(Files.exists(marker), "a launch after the jail stopped starting ran unjailed");
        assertEquals(0, jail.status().directRuns());

        // It comes back on its own once bubblewrap works again.
        Files.delete(flag);
        now.addAndGet(SkillJail.RECHECK_MS);
        assertEquals(SkillJail.State.READY, jail.status().state());
    }

    @Test
    @DisplayName("bubblewrap removed after the jail was on: refusing until a restart, which starts inactive")
    void removedAfterReadyRefusesUntilRestart() throws Exception {
        Path bwrap = switchable();
        var jail = jail("auto", bwrap);
        assertEquals(SkillJail.State.READY, jail.status().state());

        Files.delete(bwrap);
        assertEquals(SkillJail.State.REFUSING, jail.status().state());
        try (var l = jail.prepare(CMD, m.skillDir(), List.of())) {
            assertNotNull(l.refusal());
            assertTrue(l.refusal().contains("not installed"), l.refusal());
            assertTrue(l.argv().isEmpty());
        }

        // A restart is a new jail that was never on here: inactive, as on any host without it.
        var restarted = jail("auto", bwrap);
        assertEquals(SkillJail.State.INACTIVE, restarted.status().state());
    }

    @Test
    @DisplayName("a run's /tmp is removed only once no process of its jail is left, and what is left is ended")
    void theRunTmpOutlivesNoProcessOfItsJail() throws Exception {
        var jail = jail("auto", switchable());
        assertEquals(SkillJail.State.READY, jail.status().state());
        var launch = jail.prepare(CMD, m.skillDir(), List.of());
        Path runTmp = launch.tmpDir();
        assertNotNull(runTmp);
        // What bubblewrap's own pid 1 is for a moment after bubblewrap returns: a process with
        // the run's argv, still able to write the run's /tmp.
        Process straggler = new ProcessBuilder("/bin/sh", "-c",
                "while :; do echo x > \"$1/still-writing\"; sleep 0.01; done", "sh", runTmp.toString())
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        try {
            while (!Files.exists(runTmp.resolve("still-writing"))) Thread.sleep(10);

            launch.close();

            assertFalse(straggler.isAlive(), "the run's /tmp was removed while a process of its jail ran");
            assertFalse(Files.exists(runTmp, LinkOption.NOFOLLOW_LINKS), "the run's /tmp was left behind");
        } finally {
            straggler.destroyForcibly();
        }
    }

    @Test
    @DisplayName("jailed: bubblewrap carries the command, credentials only in its environment, /tmp removed after")
    void readyWrapsTheLaunch() throws Exception {
        var jail = jail("auto", switchable());
        assertEquals(SkillJail.State.READY, jail.status().state());
        var sandbox = new ProcessSandbox(null, new ObjectMapper(), jail);
        Path script = Files.writeString(m.skillDir().resolve("_runner_t.py"), "print('ran')\n");

        var result = sandbox.execute("python3", script, m.skillDir(), null,
                Map.of("SKILL_TOKEN", "tok-canary-123"), 10, null, List.of(m.handed()));
        assertTrue(result.isSuccess(), result.stderr());
        assertEquals("ran", result.stdout().strip());

        List<String> args = Files.readAllLines(record.resolve("argv"));
        var full = new java.util.ArrayList<String>();
        full.add("bwrap");
        full.addAll(args);
        assertEquals(List.of("python3", script.toAbsolutePath().toString()), JailTestKit.command(full));
        assertFalse(String.join("\n", args).contains("tok-canary-123"), "a credential leaked into argv");
        var ops = JailTestKit.ops(full);
        for (Path ro : List.of(m.skillDir().resolve("SKILL.yaml"), m.skillDir().resolve("skill.py"),
                m.skillDir().resolve("requirements.txt"), m.handed())) {
            assertTrue(ops.stream().anyMatch(o -> o.flag().equals("--ro-bind")
                    && o.args().equals(List.of(ro.toString(), ro.toString()))), "not bound read-only: " + ro);
        }
        assertTrue(Files.isRegularFile(m.skillDir().resolve("requirements.txt"), LinkOption.NOFOLLOW_LINKS),
                "a skill without requirements gets an empty one, so it cannot write its own");
        assertEquals(0, Files.size(m.skillDir().resolve("requirements.txt")));
        String runTmp = ops.stream().filter(o -> o.flag().equals("--bind") && o.args().get(1).equals("/tmp"))
                .findFirst().orElseThrow().args().get(0);
        assertFalse(Files.exists(Path.of(runTmp)), "the run's /tmp must be removed when it ends");

        String env = Files.readString(record.resolve("env"));
        assertTrue(env.contains("SKILL_TOKEN=tok-canary-123"));
        for (String name : System.getenv().keySet()) {
            if (!SkillJail.ENV_ALLOWLIST.contains(name)) {
                assertFalse(env.lines().anyMatch(l -> l.startsWith(name + "=")),
                        name + " reached bubblewrap's environment");
            }
        }
        assertEquals(1, jail.status().isolatedRuns());
    }

    @Test
    @DisplayName("an authored file that is a link is refused and its target left alone")
    void tamperedAuthoredFile() throws Exception {
        var jail = jail("auto", switchable());
        assertEquals(SkillJail.State.READY, jail.status().state());
        Files.createSymbolicLink(m.skillDir().resolve("requirements.txt"), m.env());
        try (var l = jail.prepare(CMD, m.skillDir(), List.of())) {
            assertNotNull(l.refusal());
            assertTrue(l.refusal().contains("tampered"), l.refusal());
            assertTrue(l.argv().isEmpty());
        }
        assertEquals("OWNCLAW_OPS_TOKEN=canary-env-secret\n", Files.readString(m.env()));

        // A link to nothing yet is not a missing file: creating requirements.txt through it would
        // make its target, wherever the skill pointed it.
        Files.delete(m.skillDir().resolve("requirements.txt"));
        Path target = tmp.resolve("made-through-the-link");
        Files.createSymbolicLink(m.skillDir().resolve("requirements.txt"), target);
        try (var l = jail.prepare(CMD, m.skillDir(), List.of())) {
            assertNotNull(l.refusal());
        }
        assertFalse(Files.exists(target, LinkOption.NOFOLLOW_LINKS), "requirements.txt was created through a link");
    }

    @Test
    @DisplayName("a skill directory that is a link is refused, and nothing is created through it")
    void aLinkedSkillDirectoryIsRefused() throws Exception {
        var jail = jail("auto", switchable());
        assertEquals(SkillJail.State.READY, jail.status().state());
        // Where it leads, a complete skill: bound there, another skill's files would be this run's.
        Files.writeString(m.otherSkill().resolve("SKILL.yaml"), "name: other_skill\n");
        Path linked = Files.createSymbolicLink(m.generated().resolve("linked_skill"), m.otherSkill());
        try (var l = jail.prepare(CMD, linked, List.of())) {
            assertNotNull(l.refusal(), "a linked skill directory was bound where it leads");
            assertTrue(l.argv().isEmpty());
        }
        assertFalse(Files.exists(m.otherSkill().resolve("requirements.txt"), LinkOption.NOFOLLOW_LINKS),
                "requirements.txt was created through the link");
    }

    @Test
    @DisplayName("an environment that is a link -- to a directory holding a hard link to the database -- is refused")
    void aLinkedEnvironmentIsRefused() throws Exception {
        var jail = jail("auto", switchable());
        assertEquals(SkillJail.State.READY, jail.status().state());
        Path elsewhere = Files.createDirectories(tmp.resolve("elsewhere-env/lib"));
        Files.createLink(elsewhere.resolve("data.bin"), m.db());
        Path envDir = Files.createSymbolicLink(m.root().resolve("skills/_envs/probe_skill"), elsewhere.getParent());
        try (var l = jail.prepare(CMD, m.skillDir(), List.of(envDir))) {
            assertNotNull(l.refusal(), "the environment was bound where its link leads, database and all");
            assertTrue(l.argv().isEmpty());
        }
    }

    @Test
    @DisplayName("a bind that is a link outside skills/ is refused: a handed path leading to another skill's code")
    void aLinkOutsideSkillsIsRefused() throws Exception {
        var jail = jail("auto", switchable());
        assertEquals(SkillJail.State.READY, jail.status().state());
        // Not under skills/, and leading to nothing the protected-path test forbids: only the
        // link itself says it is one.
        Path alias = Files.createSymbolicLink(tmp.resolve("alias.py"), m.otherSkill().resolve("skill.py"));
        try (var l = jail.prepare(CMD, m.skillDir(), List.of(alias))) {
            assertNotNull(l.refusal(), "another skill's code was bound through a link");
            assertEquals("could not isolate this launch: " + alias + " is a link: tampered skill", l.refusal());
        }
    }

    @Test
    @DisplayName("a bind reached through a link below skills/ -- _envs/ swapped for one into backups/ -- is refused")
    void aLinkBelowSkillsIsRefused() throws Exception {
        var jail = jail("auto", switchable());
        assertEquals(SkillJail.State.READY, jail.status().state());
        Path backup = Files.createDirectories(m.root().resolve("backups/probe_skill"));
        Files.writeString(backup.resolve("ownclaw.db"), "canary-backup");
        Path envs = m.root().resolve("skills/_envs");
        Files.delete(envs);
        Files.createSymbolicLink(envs, m.root().resolve("backups"));
        // The environment itself is a directory; its parent is the link. The protected-path test
        // lets it through, as it lets a skill's own directory inside generated/ through.
        Path envDir = envs.resolve("probe_skill");
        try (var l = jail.prepare(CMD, m.skillDir(), List.of(envDir))) {
            assertNotNull(l.refusal(), "a backup was bound as the skill's environment");
            assertEquals("could not isolate this launch: " + envDir + " is reached through a link below "
                    + m.root().resolve("skills") + ": tampered skill", l.refusal());
        }
    }

    @Test
    @DisplayName("a file hard-linked from outside the skill's directory is refused; a link within it is not")
    void hardLinkFromOutsideRefused() throws Exception {
        var jail = jail("auto", switchable());
        assertEquals(SkillJail.State.READY, jail.status().state());
        Path mine = Files.writeString(m.skillDir().resolve("data.csv"), "a,b\n");
        Files.createLink(Files.createDirectories(m.skillDir().resolve("out")).resolve("copy.csv"), mine);
        try (var l = jail.prepare(CMD, m.skillDir(), List.of())) {
            assertNull(l.refusal(), l.refusal());
        }

        // Made before the jail existed, it would keep the database readable through the writable bind.
        Files.createLink(m.skillDir().resolve("cache.bin"), m.db());
        try (var l = jail.prepare(CMD, m.skillDir(), List.of())) {
            assertNotNull(l.refusal());
            assertTrue(l.refusal().contains("cache.bin"), l.refusal());
            assertTrue(l.argv().isEmpty());
        }
    }

    @Test
    @DisplayName("a hard link to the database in a read-only bind -- the skill's environment, a handed upload -- is refused")
    void hardLinkInAReadOnlyBindRefused() throws Exception {
        var jail = jail("auto", switchable());
        assertEquals(SkillJail.State.READY, jail.status().state());
        Path envDir = Files.createDirectories(m.root().resolve("skills/_envs/probe_skill/lib/site-packages"))
                .getParent().getParent();
        Files.writeString(envDir.resolve("lib/site-packages/mod.py"), "X = 1\n");
        try (var l = jail.prepare(CMD, m.skillDir(), List.of(envDir, m.handed()))) {
            assertNull(l.refusal(), l.refusal());
        }

        Path inEnv = Files.createLink(envDir.resolve("lib/site-packages/data.bin"), m.db());
        try (var l = jail.prepare(CMD, m.skillDir(), List.of(envDir))) {
            assertNotNull(l.refusal(), "a hard link to the database inside the environment was bound");
            assertTrue(l.refusal().contains("data.bin"), l.refusal());
            assertTrue(l.argv().isEmpty());
        }
        Files.delete(inEnv);

        // An upload whose file is also the database: bound read-only, it would be the database.
        Path upload = Files.createLink(m.uploads().resolve("statement.pdf"), m.db());
        try (var l = jail.prepare(CMD, m.skillDir(), List.of(upload))) {
            assertNotNull(l.refusal(), "a handed upload that is a hard link to the database was bound");
            assertTrue(l.refusal().contains("statement.pdf"), l.refusal());
        }
    }

    @Test
    @DisplayName("a directory the skill cannot list or enter refuses the launch, and is left as it is")
    void anUnreadableDirectoryIsRefusedAndLeftAsItIs() throws Exception {
        var jail = jail("auto", switchable());
        assertEquals(SkillJail.State.READY, jail.status().state());
        // What os.makedirs('cache', mode=0o300) leaves; one that can be listed but not entered;
        // and one that can do neither. Opening them to look was a chmod through whatever a
        // second run of the skill had swapped in by then.
        Path cache = Files.createDirectories(m.skillDir().resolve("cache"));
        Files.writeString(cache.resolve("page.html"), "<html/>");
        Path index = Files.createDirectories(m.skillDir().resolve("index"));
        Files.writeString(index.resolve("terms.txt"), "x");
        Path closed = Files.createDirectories(m.skillDir().resolve("closed"));
        var modes = Map.of(cache, "-wx------", index, "r--------", closed, "---------");
        try {
            for (var e : modes.entrySet()) {
                for (var other : modes.keySet()) {
                    Files.setPosixFilePermissions(other, PosixFilePermissions.fromString("rwx------"));
                }
                Files.setPosixFilePermissions(e.getKey(), PosixFilePermissions.fromString(e.getValue()));
                try (var l = jail.prepare(CMD, m.skillDir(), List.of())) {
                    assertNotNull(l.refusal(), "launched with " + e.getKey() + " at " + e.getValue());
                    assertEquals("could not isolate this launch: could not read " + e.getKey() + " under "
                            + m.skillDir() + ": a directory there cannot be listed or entered", l.refusal());
                    assertTrue(l.argv().isEmpty());
                }
                assertEquals(e.getValue(), PosixFilePermissions.toString(Files.getPosixFilePermissions(e.getKey())),
                        "the launch changed the mode of " + e.getKey());
            }
        } finally {
            for (var d : modes.keySet()) {
                Files.setPosixFilePermissions(d, PosixFilePermissions.fromString("rwx------"));
            }
        }
    }

    @Test
    @DisplayName("a mode-0300 directory in the skill's directory: the launch is refused and the mode stays 0300")
    void aMode0300DirectoryIsNotOpened() throws Exception {
        var jail = jail("auto", switchable());
        assertEquals(SkillJail.State.READY, jail.status().state());
        Path cache = Files.createDirectories(m.skillDir().resolve("cache"));
        Files.setPosixFilePermissions(cache, PosixFilePermissions.fromString("-wx------"));
        try {
            var result = new ProcessSandbox(null, new ObjectMapper(), jail)
                    .execute("python3", markerScript(tmp.resolve("marker")), m.skillDir(), null, Map.of(), 10);
            assertFalse(result.isSuccess());
            assertTrue(result.stderr().contains("could not read " + cache), result.stderr());
            assertFalse(Files.exists(tmp.resolve("marker")), "the skill ran");
            assertEquals("-wx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(cache)));
        } finally {
            Files.setPosixFilePermissions(cache, PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Test
    @DisplayName("a bind that would put protected state back on top of the tmpfs is refused")
    void bindsThatExposeStateRefused() throws Exception {
        var jail = jail("auto", switchable());
        assertEquals(SkillJail.State.READY, jail.status().state());
        Path linkToDb = Files.createSymbolicLink(m.uploads().resolve("statement.pdf"), m.db());

        for (List<Path> ro : List.of(List.of(m.db()), List.of(m.uploads()), List.of(m.env()),
                List.of(m.db().getParent()), List.of(linkToDb))) {
            try (var l = jail.prepare(CMD, m.skillDir(), ro)) {
                assertNotNull(l.refusal(), "bound " + ro);
            }
        }
        try (var l = jail.prepare(CMD, m.root(), List.of())) {
            assertNotNull(l.refusal(), "the whole service directory as the working directory");
        }
        try (var l = jail.prepare(CMD, m.skillDir(), List.of(m.handed()))) {
            assertNull(l.refusal(), l.refusal());
            assertTrue(l.jailed());
        }
    }
}
