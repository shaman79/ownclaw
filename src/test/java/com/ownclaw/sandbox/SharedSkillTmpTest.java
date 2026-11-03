package com.ownclaw.sandbox;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * An account's /tmp, against the real bubblewrap: what one of its skills leaves there, the next
 * run of another of its skills reads -- the OpenWRT collect and digest skills, the audit
 * extractor and shell_exec's read-back pass files that way -- while another account's skills
 * and a check see a /tmp of their own. And a run that ends kills nothing of another run that
 * shares that /tmp.
 */
class SharedSkillTmpTest {

    private static final Path BWRAP = Path.of("/usr/bin/bwrap");

    private Path base;
    private JailTestKit.Mirror m;
    private SkillJail jail;

    @BeforeEach
    void requireBubblewrap() throws Exception {
        assumeTrue(Files.isExecutable(BWRAP), "bubblewrap is not installed");
        assumeTrue(exitsZero(BWRAP.toString(), "--unshare-all", "--share-net", "--die-with-parent",
                "--ro-bind", "/", "/", "--dev-bind", "/dev", "/dev", "--proc", "/proc", "--", "/bin/true"),
                "bubblewrap cannot create namespaces on this host");
        assumeTrue(exitsZero("python3", "--version"), "python3 is not available");
        base = JailTestKit.visibleDir("ownclaw-tmp-");
        m = JailTestKit.Mirror.create(base);
        // A skill of its own, as the production directory has them: its authored files in place.
        Files.writeString(m.otherSkill().resolve("SKILL.yaml"), "name: other_skill\n");
        jail = new SkillJail("required", BWRAP.toString(), m.layout(), List.of(), System::currentTimeMillis);
        assertEquals(SkillJail.State.READY, jail.status().state(), jail.status().reason());
    }

    @AfterEach
    void cleanUp() {
        if (jail != null) jail.removeSharedTmp();
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

    /** Runs {@code code} in {@code dir} through the sandbox, for {@code owner}; its stdout. */
    private String run(Path dir, String code, String owner) throws Exception {
        Path script = dir.resolve("_runner_tmp_probe.py");
        Files.deleteIfExists(script);
        Files.writeString(script, code);
        var result = new ProcessSandbox(jail).execute("python3", script, dir, "{}", Map.of(), 30, null,
                List.of(), owner);
        assertTrue(result.isSuccess(), "run failed: " + result.stderr());
        return result.stdout().strip();
    }

    private static final String WRITE = """
            import os
            with open('/tmp/ow_perf_1.txt', 'w') as f:
                f.write('collected')
            with open('/var/tmp/ow_state.txt', 'w') as f:
                f.write('state')
            print('written')
            """;

    private static final String READ = """
            import os
            def read(p):
                try:
                    return open(p).read()
                except Exception:
                    return 'absent'
            print(read('/tmp/ow_perf_1.txt') + ' ' + read('/var/tmp/ow_state.txt'))
            """;

    @Test
    @DisplayName("a file one skill of an account leaves in /tmp, another of its skills reads on its next run")
    void anAccountsSkillsShareTheirTmp() throws Exception {
        assertEquals("written", run(m.skillDir(), WRITE, "owner1"));
        assertEquals("collected state", run(m.otherSkill(), READ, "owner1"),
                "the next skill of the account does not find what the first left");
        assertFalse(Files.exists(Path.of("/tmp/ow_perf_1.txt")) && Files.readString(Path.of("/tmp/ow_perf_1.txt")).equals("collected"),
                "the jailed write reached the host's /tmp");
    }

    @Test
    @DisplayName("another account's skills, and a check, see a /tmp of their own")
    void otherAccountsAndChecksDoNot() throws Exception {
        assertEquals("written", run(m.skillDir(), WRITE, "owner1"));
        assertEquals("absent absent", run(m.otherSkill(), READ, "owner2"), "another account sees the file");
        assertEquals("absent absent", run(m.otherSkill(), READ, null), "a check sees the account's /tmp");
    }

    @Test
    @DisplayName("from inside, no account's /tmp but its own is anywhere to be found")
    void noOtherAccountsTmpIsVisible() throws Exception {
        assertEquals("written", run(m.skillDir(), WRITE, "owner2"));
        Path root = jail.sharedTmp("owner2").getParent();
        String seen = run(m.otherSkill(), """
                import os
                hits = []
                for top in ['/tmp', '/var/tmp', '%s']:
                    for d, dirs, files in os.walk(top):
                        hits += [os.path.join(d, f) for f in files if f.startswith('ow_')]
                print(len(hits))
                """.formatted(root), "owner1");
        assertEquals("0", seen, "owner2's files are visible to owner1's skill");
    }

    @Test
    @DisplayName("a run that ends kills nothing of another run of the account, which shares its /tmp")
    void endingOneRunLeavesTheOtherRunning() throws Exception {
        Path tmp = jail.sharedTmp("owner1");
        Process first;
        try (var launch = jail.prepare(List.of("/bin/sh", "-c", "sleep 6; echo done > /tmp/first.txt"),
                m.skillDir(), List.of(), "owner1")) {
            assertTrue(launch.jailed(), String.valueOf(launch.refusal()));
            var pb = new ProcessBuilder(launch.argv()).directory(m.skillDir().toFile())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
            SkillJail.applyEnvironment(pb, Map.of());
            first = pb.start();
            first.getOutputStream().close();

            // A second run of the account, start to end, while the first one sleeps.
            assertEquals("written", run(m.otherSkill(), WRITE, "owner1"));
            assertTrue(first.isAlive(), "the second run's end killed the first run");
            assertTrue(first.waitFor(20, TimeUnit.SECONDS), "the first run did not finish");
        }
        assertEquals(0, first.exitValue());
        assertEquals("done", Files.readString(tmp.resolve("first.txt")).strip());
        assertTrue(Files.exists(tmp.resolve("ow_perf_1.txt")), "a run's close removed the account's /tmp");
    }

    @Test
    @DisplayName("the accounts' /tmp directories go when the service stops, and when it starts")
    void theyGoWhenTheServiceStopsAndStarts() throws Exception {
        Path tmp = jail.sharedTmp("owner1");
        assertEquals(m.root().resolve("skills/_tmp/owner1"), tmp, "not in the hidden skills directory");
        Files.writeString(tmp.resolve("left.txt"), "x");
        jail.removeSharedTmp();
        assertFalse(Files.exists(tmp.getParent()), "the accounts' /tmp outlived the service");

        Files.writeString(jail.sharedTmp("owner1").resolve("left.txt"), "x");
        var next = new SkillJail("required", BWRAP.toString(), m.layout(), List.of(), System::currentTimeMillis);
        next.init();
        assertFalse(Files.exists(tmp.resolve("left.txt")), "a start kept what the last run of the service left");
    }

    @Test
    @DisplayName("an account id that is not a plain name gets a hashed directory, never a path")
    void anOddAccountIdIsHashed() throws Exception {
        assertEquals("418c3eab", SkillJail.tmpName("418c3eab"));
        String odd = SkillJail.tmpName("../../etc");
        assertTrue(odd.matches("u-[0-9a-f]{32}"), odd);
        assertEquals(jail.sharedTmp("../../etc").getParent(), jail.sharedTmp("owner1").getParent());
    }

    @Test
    @DisplayName("Playwright's browsers under HOME are exposed read-only without being listed, once")
    void playwrightBrowsersAreExposedByDefault() {
        assertEquals(List.of("/opt/ownclaw/.cache/ms-playwright"),
                SkillJail.withDefaultExposes(List.of(), "/opt/ownclaw"));
        assertEquals(List.of("/opt/ownclaw/.ssh", "/opt/ownclaw/.cache/ms-playwright"),
                SkillJail.withDefaultExposes(List.of("/opt/ownclaw/.ssh", "/opt/ownclaw/.cache/ms-playwright"),
                        "/opt/ownclaw"));
        assertEquals(List.of(), SkillJail.withDefaultExposes(null, null));
    }
}
