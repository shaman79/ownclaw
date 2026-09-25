package com.ownclaw.sandbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Which directories disappear, and which exceptions the owner may open up. A root that is too
 * big blanks the system the skill needs; one that misses a symlink target leaves the database
 * readable at its real path; an expose entry that overlaps the data undoes the whole jail.
 */
class SkillJailRootsTest {

    @TempDir Path tmp;

    /**
     * {@code roots} and, where this host has them, journald's store and the crash dumps, in the
     * order the jail sorts them.
     */
    static List<Path> withHostStores(List<Path> roots) {
        var out = new java.util.ArrayList<>(roots);
        if (Files.isDirectory(SkillJail.JOURNAL)) out.add(SkillJail.JOURNAL);
        for (Path dumps : SkillJail.CRASH_DUMPS) if (Files.isDirectory(dumps)) out.add(dumps);
        return out.stream().sorted().toList();
    }

    @Test
    @DisplayName("only the outermost directory is hidden when it contains the rest")
    void outermostOnly() throws Exception {
        var m = JailTestKit.Mirror.create(tmp);
        assertEquals(withHostStores(List.of(m.root().toRealPath())), SkillJail.hiddenRoots(m.layout()));
    }

    @Test
    @DisplayName("a data directory symlinked elsewhere is hidden at its real path too")
    void symlinkedDataDir() throws Exception {
        Path root = Files.createDirectories(tmp.resolve("app"));
        Path elsewhere = Files.createDirectories(tmp.resolve("disk2/ownclaw-data"));
        Files.writeString(elsewhere.resolve("ownclaw.db"), "db");
        Files.createSymbolicLink(root.resolve("data"), elsewhere);
        Files.createDirectories(root.resolve("skills/generated"));
        var layout = new SkillJail.Layout(root, root.resolve("data/ownclaw.db"),
                root.resolve("skills/generated"), root.resolve("logs"));

        var roots = SkillJail.hiddenRoots(layout);
        assertTrue(roots.contains(root.toRealPath()), roots.toString());
        assertTrue(roots.contains(elsewhere.toRealPath()), "the real data directory must be hidden: " + roots);
        assertEquals(withHostStores(List.of(root.toRealPath(), elsewhere.toRealPath())), roots);
    }

    @Test
    @DisplayName("backups/ and repo/ in the working directory change nothing; linked elsewhere, each is hidden at its real path")
    void linkedBackupsAndRepo() throws Exception {
        var m = JailTestKit.Mirror.create(tmp);
        Files.createDirectories(m.root().resolve("backups"));
        Files.createDirectories(m.root().resolve("repo/.git"));
        var l = m.layout();
        // The command line a standard layout gets is the one it got before they were candidates.
        List<String> before = SkillJail.bwrapArgs("/usr/bin/bwrap", SkillJail.hiddenRoots(l.cwd(), l.dataDir(),
                l.skillsRoot(), l.envsRoot(), l.logDir(), SkillJail.JOURNAL, SkillJail.CRASH_DUMPS.get(0),
                SkillJail.CRASH_DUMPS.get(1)), null, m.skillDir(), List.of(),
                List.of(), tmp.resolve("run-tmp"), List.of("python3", "x.py"));
        List<String> now = SkillJail.bwrapArgs("/usr/bin/bwrap", SkillJail.hiddenRoots(l), null, m.skillDir(),
                List.of(), List.of(), tmp.resolve("run-tmp"), List.of("python3", "x.py"));
        assertEquals(before, now);
        assertEquals(withHostStores(List.of(m.root().toRealPath())), SkillJail.hiddenRoots(l));

        Path root = Files.createDirectories(tmp.resolve("app"));
        Path backups = Files.createDirectories(tmp.resolve("disk2/ownclaw-backups"));
        Path repo = Files.createDirectories(tmp.resolve("disk2/ownclaw-repo"));
        Files.createSymbolicLink(root.resolve("backups"), backups);
        Files.createSymbolicLink(root.resolve("repo"), repo);
        var linked = new SkillJail.Layout(root, root.resolve("data/ownclaw.db"), root.resolve("skills/generated"), null);
        assertEquals(withHostStores(List.of(root.toRealPath(), backups.toRealPath(), repo.toRealPath())),
                SkillJail.hiddenRoots(linked));
    }

    @Test
    @DisplayName("a log directory outside the working directory is its own root; a missing one hides nothing")
    void separateAndMissingRoots() throws Exception {
        var m = JailTestKit.Mirror.create(tmp);
        Path logs = Files.createDirectories(tmp.resolve("var-log-ownclaw"));
        var roots = SkillJail.hiddenRoots(m.root(), m.db().getParent(), m.generated().getParent(),
                m.generated().getParent().resolve("_envs"), logs);
        assertEquals(List.of(m.root().toRealPath(), logs.toRealPath()).stream().sorted().toList(), roots);

        var noLogs = SkillJail.hiddenRoots(m.root(), m.db().getParent(), m.generated().getParent(),
                null, tmp.resolve("never-created"));
        assertEquals(List.of(m.root().toRealPath()), noLogs);
    }

    @Test
    @DisplayName("'/' or a directory holding the system is refused, by written or by real path")
    void systemRootsRefused() throws Exception {
        for (String p : List.of("/", "/usr", "/etc", "/proc", "/dev", "/tmp")) {
            Path system = Path.of(p);
            assertThrows(IllegalStateException.class,
                    () -> SkillJail.hiddenRoots(system, null, null, null, null), p);
        }
        // A data directory that is really a link to /etc.
        Path root = Files.createDirectories(tmp.resolve("app"));
        Files.createSymbolicLink(root.resolve("data"), Path.of("/etc"));
        assertThrows(IllegalStateException.class,
                () -> SkillJail.hiddenRoots(tmp.resolve("elsewhere"), root.resolve("data"), null, null, null));
    }

    @Test
    @DisplayName("a jail whose root would hide the system never becomes READY")
    void systemRootKeepsJailDown() throws Exception {
        Path record = Files.createDirectories(tmp.resolve("rec"));
        Path bwrap = JailTestKit.switchable(tmp.resolve("bin/bwrap"), tmp.resolve("no-flag"), record);
        var layout = new SkillJail.Layout(Path.of("/"), tmp.resolve("data/ownclaw.db"),
                tmp.resolve("skills/generated"), null);

        var auto = new SkillJail("auto", bwrap.toString(), layout, List.of(), new AtomicLong()::get).status();
        assertEquals(SkillJail.State.INACTIVE, auto.state());
        assertTrue(auto.reason().contains("refusing to hide /"), auto.reason());

        var required = new SkillJail("required", bwrap.toString(), layout, List.of(), new AtomicLong()::get);
        assertEquals(SkillJail.State.REFUSING, required.status().state());
        assertTrue(required.unavailable().isPresent());
    }

    @Test
    @DisplayName("journald's store is hidden whenever it exists; /run is never a hidden root")
    void theJournalIsHidden() throws Exception {
        assumeTrue(Files.isDirectory(SkillJail.JOURNAL), "no persistent journal on this host");
        var m = JailTestKit.Mirror.create(tmp);
        var roots = SkillJail.hiddenRoots(m.layout());
        assertTrue(roots.contains(SkillJail.JOURNAL), roots.toString());
        assertEquals(withHostStores(List.of(m.root().toRealPath())), roots);
        assertFalse(roots.stream().anyMatch(r -> r.startsWith("/run")), roots.toString());
    }

    @Test
    @DisplayName("the crash dumps are hidden wherever they exist")
    void theCrashDumpsAreHidden() throws Exception {
        var present = SkillJail.CRASH_DUMPS.stream().filter(Files::isDirectory).toList();
        assumeTrue(!present.isEmpty(), "no crash dump directory on this host");
        var roots = SkillJail.hiddenRoots(JailTestKit.Mirror.create(tmp).layout());
        assertTrue(roots.containsAll(present), roots.toString());
    }

    /** The command of the self-check's probe, from a jail over {@code m} that came up READY. */
    private List<String> probed(JailTestKit.Mirror m) throws Exception {
        Path record = Files.createDirectories(tmp.resolve("rec"));
        Path bwrap = JailTestKit.recordingEach(tmp.resolve("bin/bwrap"), record);
        var jail = new SkillJail("required", bwrap.toString(), m.layout(), List.of(), new AtomicLong()::get);
        assertEquals(SkillJail.State.READY, jail.status().state(), jail.status().reason());
        List<String> probe = JailTestKit.command(Files.readAllLines(record.resolve("call.0/argv")));
        assertTrue(probe.contains(SkillJail.PROBE_NAME), probe.toString());
        return probe;
    }

    @Test
    @DisplayName("the self-check proves docker's socket, the system bus and the users' runtime directories gone")
    void theSelfCheckProbesRun() throws Exception {
        List<String> probe = probed(JailTestKit.Mirror.create(tmp));
        for (String p : List.of("/run/docker.sock", "/run/dbus/system_bus_socket", "/run/user")) {
            assertTrue(probe.contains(p), "not probed: " + p + " in " + probe);
        }
    }

    @Test
    @DisplayName("the self-check proves what is in journald's stores gone, not the directories a tmpfs leaves")
    void theSelfCheckProbesTheJournal() throws Exception {
        var inside = new java.util.ArrayList<Path>();
        for (Path store : List.of(SkillJail.JOURNAL, Path.of("/run/log/journal"))) {
            if (Files.isDirectory(store) && Files.isReadable(store)) {
                try (var s = Files.list(store)) {
                    inside.addAll(s.toList());
                }
            }
        }
        assumeTrue(!inside.isEmpty(), "no journal on this host");
        List<String> probe = probed(JailTestKit.Mirror.create(tmp));
        for (Path p : inside) assertTrue(probe.contains(p.toString()), "not probed: " + p + " in " + probe);
        assertFalse(probe.contains(SkillJail.JOURNAL.toString()), "the store itself stays, as the tmpfs's mount point");
    }

    @Test
    @DisplayName("the self-check proves what is in the crash dump directories gone")
    void theSelfCheckProbesTheCrashDumps() throws Exception {
        var inside = new java.util.ArrayList<Path>();
        for (Path dumps : SkillJail.CRASH_DUMPS) {
            if (Files.isDirectory(dumps) && Files.isReadable(dumps)) {
                try (var s = Files.list(dumps)) {
                    inside.addAll(s.toList());
                }
            }
        }
        assumeTrue(!inside.isEmpty(), "no crash dumps on this host");
        List<String> probe = probed(JailTestKit.Mirror.create(tmp));
        for (Path p : inside) assertTrue(probe.contains(p.toString()), "not probed: " + p + " in " + probe);
    }

    @Test
    @DisplayName("the self-check proves what is in backups/ and repo/ gone, not the directories a tmpfs leaves")
    void theSelfCheckProbesBackupsAndRepo() throws Exception {
        var m = JailTestKit.Mirror.create(tmp);
        Path backup = Files.writeString(Files.createDirectories(m.root().resolve("backups"))
                .resolve("ownclaw-db-pre-reset.db"), "canary-backup");
        Path git = Files.createDirectories(m.root().resolve("repo/.git"));
        List<String> probe = probed(m);
        for (Path p : List.of(backup, git)) assertTrue(probe.contains(p.toString()), "not probed: " + p + " in " + probe);
        assertFalse(probe.contains(m.root().resolve("backups").toString()), probe.toString());
        assertFalse(probe.contains(m.root().resolve("repo").toString()), probe.toString());
    }

    @Test
    @DisplayName("expose entries that overlap the service's state are dropped; a HOME dotfile is kept")
    void exposeFiltering() {
        var layout = new SkillJail.Layout(Path.of("/opt/ownclaw"), Path.of("/opt/ownclaw/data/ownclaw.db"),
                Path.of("/opt/ownclaw/skills/generated"), Path.of("/opt/ownclaw/logs"));
        var kept = SkillJail.filterExpose(List.of(
                "/opt/ownclaw/data/x", "/opt/ownclaw/data/uploads/a.pdf", "/opt/ownclaw/.env",
                "/opt/ownclaw/skills/generated/other", "/opt/ownclaw/skills/_envs/x", "/opt/ownclaw/logs",
                "/opt/ownclaw/backups/b.tar", "/opt/ownclaw/repo/.git", "/opt/ownclaw", "/opt", "/",
                "/proc/1", "/tmp", "/var/tmp", "/var", "relative/.ssh", "  ",
                "/var/crash", "/var/crash/_usr_bin_x.1000.crash", "/var/lib/systemd/coredump",
                "/var/log", "/var/log/journal", "/run", "/run/dbus", "/run/user/1000", "/run/log",
                "/opt/ownclaw/.ssh", "/opt/ownclaw/.config/rclone/", "/run/ownclaw-test-cups"), layout);
        assertEquals(List.of(
                new SkillJail.Bind(Path.of("/opt/ownclaw/.ssh"), Path.of("/opt/ownclaw/.ssh")),
                new SkillJail.Bind(Path.of("/opt/ownclaw/.config/rclone"), Path.of("/opt/ownclaw/.config/rclone")),
                new SkillJail.Bind(Path.of("/run/ownclaw-test-cups"), Path.of("/run/ownclaw-test-cups"))),
                kept);
    }

    @Test
    @DisplayName("an expose entry that is a link into the data is dropped by its real path")
    void exposeLinkIntoData() throws Exception {
        var m = JailTestKit.Mirror.create(tmp);
        Files.createSymbolicLink(m.root().resolve(".config"), m.db().getParent());
        Files.createDirectories(m.root().resolve(".ssh"));
        var kept = SkillJail.filterExpose(List.of(m.root().resolve(".config").toString(),
                m.root().resolve(".ssh").toString()), m.layout());
        assertEquals(List.of(m.root().resolve(".ssh").toRealPath()),
                kept.stream().map(SkillJail.Bind::source).toList());
    }
}
