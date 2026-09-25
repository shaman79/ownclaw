package com.ownclaw.sandbox;

import com.ownclaw.sandbox.JailTestKit.Op;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The bubblewrap command line as data. The order of the mounts is the whole mechanism -- a bind
 * placed before the tmpfs is hidden by it, a remount placed before the binds takes the working
 * directory's write access with it -- so it is pinned here without running anything.
 */
class SkillJailArgsTest {

    private static final Path ROOT = Path.of("/opt/ownclaw");
    private static final Path SKILL = ROOT.resolve("skills/generated/pdf reader");
    private static final Path VENV = ROOT.resolve("skills/_envs/pdf reader");
    private static final Path HANDED = ROOT.resolve("data/uploads/9f2c_bank statement.pdf");
    private static final Path TMP = Path.of("/tmp/ownclaw-run-123");
    private static final Path RESOLVER = Path.of("/run/systemd/resolve");
    private static final List<Path> AUTHORED = List.of(
            SKILL.resolve("SKILL.yaml"), SKILL.resolve("skill.py"), SKILL.resolve("requirements.txt"));
    private static final List<String> COMMAND =
            List.of("/usr/bin/python3", SKILL.resolve("_runner_1a.py").toString());

    private static List<String> argv() {
        return argv(RESOLVER);
    }

    private static List<String> argv(Path resolver) {
        var readOnly = new java.util.ArrayList<>(AUTHORED);
        readOnly.add(VENV);
        readOnly.add(HANDED);
        return SkillJail.bwrapArgs("/usr/bin/bwrap", List.of(ROOT, SkillJail.JOURNAL), resolver, SKILL, readOnly,
                List.of(new SkillJail.Bind(ROOT.resolve(".ssh"), ROOT.resolve(".ssh"))), TMP, COMMAND);
    }

    private static int first(List<Op> ops, String flag, String... args) {
        return ops.stream().filter(o -> o.flag().equals(flag) && o.args().equals(List.of(args)))
                .mapToInt(Op::index).findFirst()
                .orElseThrow(() -> new AssertionError("missing " + flag + " " + List.of(args)));
    }

    @Test
    @DisplayName("the host goes in read-only first, and every hidden root's tmpfs lands on top of it")
    void hostThenTmpfs() {
        var ops = JailTestKit.ops(argv());
        int host = first(ops, "--ro-bind", "/", "/");
        int tmpfs = first(ops, "--tmpfs", ROOT.toString());
        assertTrue(host < tmpfs);
        assertEquals(1, ops.stream().filter(o -> o.flag().equals("--ro-bind")
                && o.args().equals(List.of("/", "/"))).count(), "the host is mounted exactly once");
    }

    @Test
    @DisplayName("the working directory and every read-only bind come after the tmpfs, the remount after them all")
    void bindsBetweenTmpfsAndRemount() {
        var ops = JailTestKit.ops(argv());
        int tmpfs = first(ops, "--tmpfs", ROOT.toString());
        int remount = first(ops, "--remount-ro", ROOT.toString());
        int work = first(ops, "--bind", SKILL.toString(), SKILL.toString());
        assertTrue(tmpfs < work && work < remount);
        // Every hidden root alike, the journal included.
        assertTrue(first(ops, "--tmpfs", SkillJail.JOURNAL.toString()) < work);
        assertTrue(first(ops, "--remount-ro", SkillJail.JOURNAL.toString()) > work);
        for (Op o : ops) {
            boolean skillBind = (o.flag().equals("--ro-bind") || o.flag().equals("--ro-bind-try"))
                    && !o.args().equals(List.of("/", "/")) && !o.args().get(0).equals(RESOLVER.toString());
            if (skillBind) {
                assertTrue(tmpfs < o.index() && o.index() < remount, "out of place: " + o);
            }
        }
        int lastBind = ops.stream().filter(o -> o.flag().contains("bind")).mapToInt(Op::index).max().orElseThrow();
        ops.stream().filter(o -> o.flag().equals("--remount-ro"))
                .forEach(o -> assertTrue(o.index() > lastBind, "remount before a bind: " + o));
    }

    @Test
    @DisplayName("the namespaces, /dev, /proc and the run's own /tmp, also at /var/tmp, are all there")
    void requiredFlags() {
        var argv = argv();
        var ops = JailTestKit.ops(argv);
        for (String f : List.of("--unshare-all", "--share-net", "--die-with-parent", "--new-session")) {
            assertTrue(argv.contains(f), f);
        }
        // The jail's own message queues over the host's, which the /dev bind brings in.
        assertTrue(first(ops, "--dev-bind", "/dev", "/dev") < first(ops, "--mqueue", "/dev/mqueue"));
        first(ops, "--tmpfs", "/dev/shm");
        first(ops, "--proc", "/proc");
        first(ops, "--bind", TMP.toString(), "/tmp");
        first(ops, "--bind", TMP.toString(), "/var/tmp");
        first(ops, "--chdir", SKILL.toString());
        assertEquals("/usr/bin/bwrap", argv.get(0));
    }

    @Test
    @DisplayName("`--` stands immediately before the command, so the command is never read as an option")
    void doubleDashBeforeCommand() {
        var argv = argv();
        int n = argv.size() - COMMAND.size();
        assertEquals("--", argv.get(n - 1));
        assertEquals(COMMAND, argv.subList(n, argv.size()));
        assertEquals(COMMAND, JailTestKit.command(argv));
    }

    @Test
    @DisplayName("/run is emptied after the host goes in, only the resolver put back, and made read-only after every bind")
    void runHoldsOnlyTheResolver() {
        var ops = JailTestKit.ops(argv());
        int host = first(ops, "--ro-bind", "/", "/");
        int run = first(ops, "--tmpfs", "/run");
        int resolver = first(ops, "--ro-bind-try", RESOLVER.toString(), RESOLVER.toString());
        int remount = first(ops, "--remount-ro", "/run");
        assertTrue(host < run && run < resolver && resolver < remount);
        int lastBind = ops.stream().filter(o -> o.flag().contains("bind")).mapToInt(Op::index).max().orElseThrow();
        assertTrue(remount > lastBind, "/run made read-only before a bind");
        assertEquals(1, ops.stream().filter(o -> o.flag().equals("--tmpfs") && o.args().equals(List.of("/run"))).count());
        for (Op o : ops) {
            if (o.flag().contains("bind") && o.index() != resolver) {
                assertFalse(Path.of(o.args().get(1)).startsWith("/run"), "put back under /run: " + o);
            }
        }
    }

    @Test
    @DisplayName("with no resolver under /run, /run stays empty")
    void noResolverNoBind() {
        var ops = JailTestKit.ops(argv(null));
        first(ops, "--tmpfs", "/run");
        first(ops, "--remount-ro", "/run");
        assertFalse(ops.stream().anyMatch(o -> o.flag().contains("bind") && Path.of(o.args().get(1)).startsWith("/run")),
                ops.toString());
    }

    @Test
    @DisplayName("the resolver is its directory under /run, the file alone directly in /run, and nothing outside /run")
    void resolverDirectory() {
        // Paths that do not exist are taken as they are written.
        assertEquals(Path.of("/run/ownclaw-test-none/resolve"),
                SkillJail.resolver(Path.of("/run/ownclaw-test-none/resolve/stub-resolv.conf")));
        assertEquals(Path.of("/run/ownclaw-test-resolv.conf"), SkillJail.resolver(Path.of("/run/ownclaw-test-resolv.conf")));
        assertNull(SkillJail.resolver(Path.of("/etc/ownclaw-test-resolv.conf")));
        assertNull(SkillJail.resolver(Path.of("/runaway/resolv.conf")));
    }

    @Test
    @DisplayName("read-only binds sit at their own paths")
    void bindSources() {
        var ops = JailTestKit.ops(argv());
        var sources = new java.util.ArrayList<String>();
        for (Op o : ops) {
            if (o.flag().equals("--ro-bind") && !o.args().equals(List.of("/", "/"))) {
                assertEquals(o.args().get(0), o.args().get(1), "a read-only bind moved: " + o);
            }
            if (o.flag().contains("bind")) sources.add(o.args().get(0));
        }
        assertTrue(sources.contains(HANDED.toString()));
    }

    @Test
    @DisplayName("a path with spaces stays one argument")
    void spacesStayWhole() {
        var argv = argv();
        assertTrue(argv.contains(SKILL.toString()));
        assertTrue(argv.contains(HANDED.toString()));
        assertFalse(argv.contains("reader"));
        assertFalse(argv.contains("statement.pdf"));
    }
}
