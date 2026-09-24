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
    private static final List<Path> AUTHORED = List.of(
            SKILL.resolve("SKILL.yaml"), SKILL.resolve("skill.py"), SKILL.resolve("requirements.txt"));
    private static final List<String> COMMAND =
            List.of("/usr/bin/python3", SKILL.resolve("_runner_1a.py").toString());

    private static List<String> argv() {
        var readOnly = new java.util.ArrayList<>(AUTHORED);
        readOnly.add(VENV);
        readOnly.add(HANDED);
        return SkillJail.bwrapArgs("/usr/bin/bwrap", List.of(ROOT), SKILL, readOnly,
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
        for (Op o : ops) {
            boolean skillBind = (o.flag().equals("--ro-bind") || o.flag().equals("--ro-bind-try"))
                    && !o.args().equals(List.of("/", "/"));
            if (skillBind) {
                assertTrue(tmpfs < o.index() && o.index() < remount, "out of place: " + o);
            }
        }
        int lastBind = ops.stream().filter(o -> o.flag().contains("bind")).mapToInt(Op::index).max().orElseThrow();
        ops.stream().filter(o -> o.flag().equals("--remount-ro"))
                .forEach(o -> assertTrue(o.index() > lastBind, "remount before a bind: " + o));
    }

    @Test
    @DisplayName("the namespaces, /dev, /proc and the run's own /tmp are all there")
    void requiredFlags() {
        var argv = argv();
        var ops = JailTestKit.ops(argv);
        for (String f : List.of("--unshare-all", "--share-net", "--die-with-parent", "--new-session")) {
            assertTrue(argv.contains(f), f);
        }
        first(ops, "--dev-bind", "/dev", "/dev");
        first(ops, "--tmpfs", "/dev/shm");
        first(ops, "--proc", "/proc");
        first(ops, "--bind", TMP.toString(), "/tmp");
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
    @DisplayName("nothing about the environment is ever on the command line")
    void noEnvironmentInArgv() {
        for (String a : argv()) {
            assertFalse(a.startsWith("--setenv") || a.startsWith("--unsetenv") || a.startsWith("--clearenv"), a);
        }
    }

    @Test
    @DisplayName("read-only binds sit at their own paths; the database, .env and the uploads directory are never a source")
    void bindSources() {
        var ops = JailTestKit.ops(argv());
        var sources = new java.util.ArrayList<String>();
        for (Op o : ops) {
            if (o.flag().equals("--ro-bind") && !o.args().equals(List.of("/", "/"))) {
                assertEquals(o.args().get(0), o.args().get(1), "a read-only bind moved: " + o);
            }
            if (o.flag().contains("bind")) sources.add(o.args().get(0));
        }
        for (String forbidden : List.of(ROOT.resolve("data/ownclaw.db").toString(), ROOT.resolve(".env").toString(),
                ROOT.resolve("data/uploads").toString(), ROOT.resolve("data").toString(),
                ROOT.resolve("skills/generated").toString(), ROOT.toString())) {
            assertFalse(sources.contains(forbidden), forbidden + " is bound in");
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
