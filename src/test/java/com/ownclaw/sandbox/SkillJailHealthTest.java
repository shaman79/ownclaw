package com.ownclaw.sandbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownclaw.agent.tools.DynamicSkillRegistry;
import com.ownclaw.agent.tools.ToolRegistry;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.conversation.MigratedDatabase;
import com.ownclaw.core.TaskQueue;
import com.ownclaw.interfaces.CommandHandler;
import com.ownclaw.observability.OpsService;
import com.ownclaw.users.AuthService;
import com.ownclaw.users.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The operator's view of the jail: checks.sandbox in the ops health, the self-test entry, the
 * notObserved wording on a task page and the /status line -- with the jail inactive, ready and
 * refusing. The owner decides what to install from this and verifies the install with it, so
 * each state must say what it is, why, and what to do next; and it must name HOME's dot entries
 * without ever showing what is in them.
 * <p>
 * Driven through the real SkillJail, OpsService and CommandHandler. The host's files (os-release,
 * the user-namespace sysctls, the AppArmor profile) come from a stand-in root on a temp dir, so
 * each host the hint must handle can be laid out; stand-in bubblewrap binaries give the states.
 */
class SkillJailHealthTest {

    @TempDir Path tmp;

    private JailTestKit.Mirror m;
    private Path host;
    private Path home;
    private Path record;
    private Path flag;
    private final AtomicLong now = new AtomicLong(1_000_000);
    private static final Path HOST_PROBE_FILE = Path.of("/tmp/ownclaw-jail-probe.txt");
    private boolean probeFileExisted;

    private static final List<String> CMD = List.of("python3", "/x/_runner.py");
    private static final ObjectMapper JSON = new ObjectMapper();

    @BeforeEach
    void setUp() throws IOException {
        m = JailTestKit.Mirror.create(tmp);
        record = Files.createDirectories(tmp.resolve("record"));
        flag = tmp.resolve("bwrap-fails");
        host = hostRoot("debian", "", "Debian GNU/Linux 12 (bookworm)", "absent", "1", "15000");
        home = Files.createDirectories(tmp.resolve("home"));
        Files.writeString(Files.createDirectories(home.resolve(".ssh")).resolve("id_ed25519"),
                "canary-private-key");
        Files.writeString(home.resolve(".netrc"), "machine example.invalid password canary-netrc");
        Files.createDirectories(home.resolve(".config/rclone"));
        Files.writeString(home.resolve("notes.txt"), "not a dot entry");
        probeFileExisted = Files.exists(HOST_PROBE_FILE);
    }

    @AfterEach
    void tidyHostTmp() throws IOException {
        // The pass-through stand-in runs the probe unjailed, so it writes into the real /tmp.
        if (!probeFileExisted) Files.deleteIfExists(HOST_PROBE_FILE);
    }

    /** A host root with the files the hint reads; a sysctl given as "absent" is not created. */
    private Path hostRoot(String id, String idLike, String pretty, String restrict, String clone,
                          String maxNs) throws IOException {
        Path root = Files.createDirectories(tmp.resolve("host-" + id + "-" + restrict + "-" + clone));
        Files.createDirectories(root.resolve("etc"));
        Files.writeString(root.resolve("etc/os-release"), "PRETTY_NAME=\"" + pretty + "\"\nID=" + id
                + (idLike.isEmpty() ? "" : "\nID_LIKE=" + idLike) + "\n# a comment=ignored\n");
        sysctl(root, "kernel/apparmor_restrict_unprivileged_userns", restrict);
        sysctl(root, "kernel/unprivileged_userns_clone", clone);
        sysctl(root, "user/max_user_namespaces", maxNs);
        return root;
    }

    private static void sysctl(Path root, String key, String value) throws IOException {
        if (value.equals("absent")) return;
        Path f = root.resolve("proc/sys/" + key);
        Files.createDirectories(f.getParent());
        Files.writeString(f, value + "\n");
    }

    private SkillJail jail(String mode, Path bwrap) {
        return new SkillJail(mode, bwrap.toString(), m.layout(), List.of(), now::get);
    }

    private Path absent() {
        return tmp.resolve("no/such/bwrap");
    }

    private Path switchable() throws IOException {
        return JailTestKit.switchable(tmp.resolve("bin/bwrap"), flag, record);
    }

    private Map<String, Object> health(SkillJail jail) throws IOException {
        var h = jail.health(host, home);
        // What the ops endpoint serves is this map as JSON: it must serialise, and it must not
        // carry anything that was inside HOME's dot entries.
        String json = JSON.writeValueAsString(h);
        for (String secret : List.of("canary-private-key", "canary-netrc", "id_ed25519", "rclone")) {
            assertFalse(json.contains(secret), secret + " leaked into health: " + json);
        }
        return h;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Map<String, Object> h, String key) {
        return (Map<String, Object>) h.get(key);
    }

    private OpsService ops(SkillJail jail) throws Exception {
        var config = new OwnClawConfig();
        // Nothing listens on port 1: the local-model probes fail at once instead of waiting.
        config.getExecutor().setUrl("http://127.0.0.1:1");
        var jdbc = MigratedDatabase.at(tmp.resolve("ops.db"));
        return new OpsService(config, jdbc, new ToolRegistry(List.of()),
                new DynamicSkillRegistry(config, null, null, null, null, null, null),
                new TaskQueue(null, null, null, config, null),
                new AuthService(jdbc, new UserRepository(jdbc), config), null, new ObjectMapper(), jail);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> sandboxCheck(OpsService ops) {
        return (Map<String, Object>) ((Map<String, Object>) ops.health().get("checks")).get("sandbox");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> selfTestEntry(OpsService ops) {
        var tests = (List<Map<String, Object>>) ops.selfTest().get("tests");
        return tests.stream().filter(t -> "sandbox.isolation".equals(t.get("name"))).findFirst()
                .orElseThrow(() -> new AssertionError("no sandbox.isolation entry in " + tests));
    }

    @SuppressWarnings("unchecked")
    private static String skillReach(OpsService ops) {
        return ((List<String>) ops.task("no-such-task").get("notObserved")).get(0);
    }

    private static String status(SkillJail jail) {
        var commands = new CommandHandler(null, null, null, null, null, null,
                new TaskQueue(null, null, null, new OwnClawConfig(), null), null, null, null, null,
                null, null, jail);
        return commands.handle("u1", "/status").orElseThrow();
    }

    @Test
    @DisplayName("inactive: skills can run, the reason and the install command are there, HOME's dot entries by name only")
    void inactive() throws Exception {
        var jail = jail("auto", absent());
        jail.prepare(CMD, m.skillDir(), List.of()).close();

        var h = health(jail);
        assertEquals("auto", h.get("mode"));
        assertEquals("INACTIVE", h.get("state"));
        assertEquals(false, h.get("isolated"));
        assertEquals(true, h.get("canRunSkills"));
        assertTrue(String.valueOf(h.get("reason")).contains("not installed"), String.valueOf(h.get("reason")));
        // What the jail will hide once it is on, readable before bubblewrap is installed.
        var roots = (List<?>) h.get("hiddenRoots");
        assertFalse(roots.isEmpty());
        for (Object r : roots) {
            assertTrue(r.equals(m.root().toString()) || r.equals(m.root().toRealPath().toString()), roots.toString());
        }
        assertEquals(absent().toString(), map(h, "bwrap").get("path"));
        assertEquals(false, map(h, "bwrap").get("present"));
        assertNull(map(h, "bwrap").get("version"));
        assertEquals(Map.of(), map(h, "selfCheck").get("results"));

        var runs = map(h, "runs");
        assertEquals(1L, runs.get("direct"));
        assertEquals(0L, runs.get("isolated"));
        assertEquals(0L, runs.get("refused"));
        assertNull(runs.get("readySince"));
        assertEquals(0L, runs.get("directSinceReady"), "no launch escaped a jail that was never on");

        var hostFacts = map(h, "host");
        assertEquals("Debian GNU/Linux 12 (bookworm)", hostFacts.get("os"));
        assertEquals(Map.of("kernel.apparmor_restrict_unprivileged_userns", "absent",
                "kernel.unprivileged_userns_clone", "1", "user.max_user_namespaces", "15000"),
                hostFacts.get("sysctls"));
        assertEquals(false, hostFacts.get("bwrapProfilePresent"));
        assertEquals(List.of(".config/", ".netrc", ".ssh/"), h.get("homeDotDirs"));
        assertEquals("sudo apt install bubblewrap", h.get("installHint"));

        assertTrue(jail.statusLine().startsWith(
                "Skill isolation: INACTIVE, skills run unisolated — bubblewrap is not installed"), jail.statusLine());

        // The same, as the owner reaches it.
        var ops = ops(jail);
        assertEquals("INACTIVE", sandboxCheck(ops).get("state"));
        assertEquals(true, sandboxCheck(ops).get("canRunSkills"));
        var entry = selfTestEntry(ops);
        assertEquals(true, entry.get("ok"), "not installed yet is where every host starts: " + entry);
        assertTrue(String.valueOf(entry.get("warning")).contains("until bubblewrap is installed"), entry.toString());
        String reach = skillReach(ops);
        assertTrue(reach.contains("Skill isolation is INACTIVE") && reach.contains("read the database"), reach);
        String status = status(jail);
        assertTrue(status.startsWith("Queue size: 0\nSkill isolation: INACTIVE"), status);
    }

    @Test
    @DisplayName("ready: isolated, every self-check item true, zero unisolated launches, and the step to make it required")
    void ready() throws Exception {
        var jail = jail("auto", switchable());
        try (var l = jail.prepare(CMD, m.skillDir(), List.of())) {
            assertTrue(l.jailed());
        }

        var h = health(jail);
        assertEquals("READY", h.get("state"));
        assertEquals(true, h.get("isolated"));
        assertEquals(true, h.get("canRunSkills"));
        assertNull(h.get("reason"));
        assertEquals(true, map(h, "bwrap").get("present"));
        assertEquals("bubblewrap 0.0-test", map(h, "bwrap").get("version"));
        @SuppressWarnings("unchecked")
        var results = (Map<String, Boolean>) map(h, "selfCheck").get("results");
        assertEquals(List.of("hidden", "pidNamespace", "sharedNetwork", "workDirWritable", "readOnlyBinds",
                "etcReadable", "privateTmp"), List.copyOf(results.keySet()));
        assertFalse(results.containsValue(false), results.toString());
        var runs = map(h, "runs");
        assertEquals(1L, runs.get("isolated"));
        assertEquals(0L, runs.get("direct"));
        assertNotNull(runs.get("readySince"));
        assertTrue(String.valueOf(h.get("installHint")).contains("OWNCLAW_SANDBOX_ISOLATION=required"),
                String.valueOf(h.get("installHint")));

        assertTrue(jail.statusLine().startsWith(
                "Skill isolation: ON (bubblewrap 0.0-test) — 0 unisolated launches since "), jail.statusLine());

        var ops = ops(jail);
        assertEquals("READY", sandboxCheck(ops).get("state"));
        var entry = selfTestEntry(ops);
        assertEquals(true, entry.get("ok"), entry.toString());
        assertNull(entry.get("warning"));
        String reach = skillReach(ops);
        assertTrue(reach.contains("Skill isolation is ON") && reach.contains("cannot read the database")
                && reach.contains("shares the host network on purpose"), reach);
        assertTrue(status(jail).contains("\nSkill isolation: ON (bubblewrap 0.0-test) — 0 unisolated launches"),
                status(jail));
    }

    @Test
    @DisplayName("launches that ran directly after the jail was first ready are counted, and fail the self-test")
    void unisolatedSinceReadyIsCounted() throws Exception {
        Files.createFile(flag);
        var jail = jail("auto", switchable());
        jail.prepare(CMD, m.skillDir(), List.of()).close();   // before it was ever on: not counted
        assertEquals(SkillJail.State.INACTIVE, jail.status().state());

        Files.delete(flag);
        now.addAndGet(SkillJail.RECHECK_MS);
        assertEquals(SkillJail.State.READY, jail.status().state());
        assertTrue(jail.statusLine().contains("— 0 unisolated launches since "), jail.statusLine());
        Object firstReady = map(health(jail), "runs").get("readySince");

        // The host changes under a jail that was on. The self-test looks now, not in a minute.
        Files.createFile(flag);
        var ops = ops(jail);
        var entry = selfTestEntry(ops);
        assertEquals("INACTIVE", entry.get("state"), entry.toString());
        assertEquals(false, entry.get("ok"), "a jail that was on and is not is a regression: " + entry);
        assertTrue(String.valueOf(entry.get("warning")).contains("was on at " + firstReady), entry.toString());
        jail.prepare(CMD, m.skillDir(), List.of()).close();

        Files.delete(flag);
        assertEquals(SkillJail.State.READY, jail.recheck().state());
        assertTrue(jail.statusLine().contains("— 1 unisolated launch since "), jail.statusLine());
        var runs = map(health(jail), "runs");
        assertEquals(2L, runs.get("direct"));
        assertEquals(1L, runs.get("directSinceReady"));
        assertEquals(firstReady, runs.get("readySince"), "counted from the first time it was on");
    }

    @Test
    @DisplayName("refusing: skills cannot run, the self-check says what it saw, and nothing tells the owner to reinstall")
    void refusing() throws Exception {
        var jail = jail("auto", JailTestKit.passThrough(tmp.resolve("bin/bwrap"), record));
        try (var l = jail.prepare(CMD, m.skillDir(), List.of())) {
            assertNotNull(l.refusal());
        }

        var h = health(jail);
        assertEquals("REFUSING", h.get("state"));
        assertEquals(false, h.get("isolated"));
        assertEquals(false, h.get("canRunSkills"));
        assertTrue(String.valueOf(h.get("reason")).contains(m.db().toString()), String.valueOf(h.get("reason")));
        assertEquals(false, ((Map<?, ?>) map(h, "selfCheck").get("results")).get("hidden"));
        assertEquals(true, map(h, "bwrap").get("present"));
        assertEquals(1L, map(h, "runs").get("refused"));
        assertTrue(String.valueOf(h.get("installHint")).startsWith("bubblewrap is installed but the jail is not working"),
                String.valueOf(h.get("installHint")));
        assertTrue(jail.statusLine().startsWith("Skill isolation: REFUSING every skill launch — self-check failed"),
                jail.statusLine());

        var ops = ops(jail);
        assertEquals(false, sandboxCheck(ops).get("canRunSkills"));
        assertEquals(false, selfTestEntry(ops).get("ok"));
        assertTrue(skillReach(ops).startsWith("skill isolation is REFUSING every skill launch now"), skillReach(ops));
        assertTrue(status(jail).contains("\nSkill isolation: REFUSING every skill launch"), status(jail));
    }

    @Test
    @DisplayName("required with bubblewrap absent: refusing, with the install command; a mistyped mode names the setting")
    void requiredAbsent() throws Exception {
        var h = health(jail("required", absent()));
        assertEquals("required", h.get("mode"));
        assertEquals("REFUSING", h.get("state"));
        assertEquals(false, h.get("canRunSkills"));
        assertTrue(String.valueOf(h.get("reason")).contains("not installed"));
        assertEquals("sudo apt install bubblewrap", h.get("installHint"));

        // A mistyped mode refuses; the next step is the setting, not an install.
        var typo = health(jail("requried", absent()));
        assertEquals(false, typo.get("canRunSkills"));
        assertTrue(String.valueOf(typo.get("installHint")).startsWith("fix the setting in .env"),
                String.valueOf(typo.get("installHint")));
    }

    @Test
    @DisplayName("an Ubuntu that restricts user namespaces with no bwrap profile: the hint says apt alone is not enough")
    void hintOnARestrictedUbuntu() throws Exception {
        host = hostRoot("ubuntu", "debian", "Ubuntu 24.04.1 LTS", "1", "1", "15000");
        var absentHint = String.valueOf(health(jail("auto", absent())).get("installHint"));
        assertTrue(absentHint.startsWith("sudo apt install bubblewrap -- but that alone is not enough here"), absentHint);
        assertTrue(absentHint.contains("kernel.apparmor_restrict_unprivileged_userns=1"), absentHint);
        assertTrue(absentHint.contains("nothing is written automatically"), absentHint);

        // Installed, and blocked exactly as that host blocks it: the reason is bubblewrap's own
        // words, and the hint is the decision, not the install command again.
        Files.createFile(flag);
        var blocked = health(jail("auto", switchable()));
        assertEquals("INACTIVE", blocked.get("state"));
        assertTrue(String.valueOf(blocked.get("reason")).contains("bwrap: setting up uid map: Permission denied"));
        assertTrue(String.valueOf(blocked.get("installHint")).startsWith("kernel.apparmor_restrict_unprivileged_userns=1"),
                String.valueOf(blocked.get("installHint")));

        // A bubblewrap that ran the probe to the end did create its namespaces, whatever the
        // settings say: what is wrong then is the jail, and the hint must not blame the host.
        var ranButLeaks = health(jail("auto", JailTestKit.passThrough(tmp.resolve("bin/leaky"), record)));
        assertEquals("REFUSING", ranButLeaks.get("state"));
        assertTrue(String.valueOf(ranButLeaks.get("installHint")).startsWith("bubblewrap is installed but"),
                String.valueOf(ranButLeaks.get("installHint")));

        // Where the apparmor package ships the profile, apt is the whole answer.
        Files.createDirectories(host.resolve("etc/apparmor.d"));
        Files.writeString(host.resolve(SkillJail.BWRAP_PROFILE), "profile bwrap /usr/bin/bwrap flags=(unconfined) {}\n");
        var withProfile = health(jail("auto", absent()));
        assertEquals(true, map(withProfile, "host").get("bwrapProfilePresent"));
        assertEquals("sudo apt install bubblewrap", withProfile.get("installHint"));
    }

    @Test
    @DisplayName("user namespaces switched off, or an unknown distribution: the hint says so")
    void hintOnOtherHosts() throws Exception {
        host = hostRoot("debian", "", "Debian GNU/Linux 10 (buster)", "absent", "0", "15000");
        var off = String.valueOf(health(jail("auto", absent())).get("installHint"));
        assertTrue(off.contains("unprivileged user namespaces are switched off"), off);

        host = Files.createDirectories(tmp.resolve("bare-host"));
        var h = health(jail("auto", absent()));
        assertEquals("unknown", map(h, "host").get("os"));
        assertEquals("install this distribution's bubblewrap package, so that " + absent() + " exists",
                h.get("installHint"));
    }

    @Test
    @DisplayName("off: skills run, nothing is hidden, and the hint says how to turn it on")
    void off() throws Exception {
        var jail = SkillJail.off();
        var h = jail.health(host, home);
        assertEquals("off", h.get("mode"));
        assertEquals("OFF", h.get("state"));
        assertEquals(true, h.get("canRunSkills"));
        assertEquals(false, h.get("isolated"));
        assertEquals(List.of(), h.get("hiddenRoots"));
        assertTrue(String.valueOf(h.get("installHint")).startsWith("none: isolation is switched off"));
        assertEquals("Skill isolation: OFF (ownclaw.sandbox.isolation=off)", jail.statusLine());
        assertTrue(skillReach(ops(jail)).contains("Skill isolation is OFF"));
    }

    @Test
    @DisplayName("with the real bubblewrap: ready on this host, and the host facts are this host's")
    void realBubblewrap() throws Exception {
        RealBubblewrap.assumeWorks();
        var jail = new SkillJail("auto", RealBubblewrap.PATH.toString(), m.layout(), List.of(),
                System::currentTimeMillis);
        var h = jail.health();
        assertEquals("READY", h.get("state"), String.valueOf(h.get("reason")));
        assertTrue(String.valueOf(map(h, "bwrap").get("version")).startsWith("bubblewrap "));
        assertFalse(((Map<?, ?>) map(h, "selfCheck").get("results")).containsValue(false));
        assertEquals(Files.isRegularFile(Path.of("/").resolve(SkillJail.BWRAP_PROFILE)),
                map(h, "host").get("bwrapProfilePresent"));
        assertEquals(java.util.Set.of("kernel.apparmor_restrict_unprivileged_userns",
                "kernel.unprivileged_userns_clone", "user.max_user_namespaces"),
                ((Map<?, ?>) map(h, "host").get("sysctls")).keySet());
    }
}
