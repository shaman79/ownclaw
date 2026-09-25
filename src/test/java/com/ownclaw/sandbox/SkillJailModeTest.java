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
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What each mode does on each kind of host, driven through stand-in bubblewrap binaries and a
 * clock the test moves. The case that matters most is the first deploy: bubblewrap present but
 * blocked must leave skills running exactly as before under {@code auto}, and must stop them
 * under {@code required} without starting anything.
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
    @DisplayName("auto, bubblewrap absent: inactive, and a skill runs exactly as it did before")
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
        var interactive = sandbox.executeInteractive("python3", script, m.skillDir(), null, Map.of(), 10,
                prompt -> "");
        assertFalse(interactive.isSuccess());
        assertFalse(Files.exists(marker), "a refused launch must not run the script");
        assertTrue(sandbox.unavailable().isPresent());
        assertEquals(3, jail.status().refusedRuns());
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
    @DisplayName("a run that bubblewrap itself failed forces a recheck, at most once a minute")
    void bwrapFailureForcesRecheck() throws Exception {
        var jail = jail("auto", switchable());
        var sandbox = new ProcessSandbox(null, new ObjectMapper(), jail);
        assertEquals(SkillJail.State.READY, jail.status().state());

        // The host changes under a jail that passed; READY is not re-examined on time alone.
        Files.createFile(flag);
        now.addAndGet(SkillJail.RECHECK_MS);
        assertEquals(SkillJail.State.READY, jail.status().state());

        var result = sandbox.execute("python3", markerScript(tmp.resolve("marker")), m.skillDir(),
                null, Map.of(), 10);
        assertTrue(result.stderr().startsWith("bwrap:"), result.stderr());
        assertFalse(result.isSuccess(), "the failed run fails; it is never retried unjailed");
        assertFalse(Files.exists(tmp.resolve("marker")));
        assertEquals(SkillJail.State.INACTIVE, jail.status().state());
    }

    @Test
    @DisplayName("the forced recheck waits out the minute since the last one")
    void recheckIsRateLimited() throws Exception {
        var jail = jail("auto", switchable());
        assertEquals(SkillJail.State.READY, jail.status().state());
        Files.createFile(flag);
        var launch = jail.prepare(CMD, m.skillDir(), List.of());
        launch.close();
        jail.afterRun(launch, "bwrap: execvp python3: No such file or directory");
        assertEquals(SkillJail.State.READY, jail.status().state());
        now.addAndGet(SkillJail.RECHECK_MS);
        assertEquals(SkillJail.State.INACTIVE, jail.status().state());
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
