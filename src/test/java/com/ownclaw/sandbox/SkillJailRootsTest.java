package com.ownclaw.sandbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Which directories disappear, and which exceptions the owner may open up. A root that is too
 * big blanks the system the skill needs; one that misses a symlink target leaves the database
 * readable at its real path; an expose entry that overlaps the data undoes the whole jail.
 */
class SkillJailRootsTest {

    @TempDir Path tmp;

    @Test
    @DisplayName("only the outermost directory is hidden when it contains the rest")
    void outermostOnly() throws Exception {
        var m = JailTestKit.Mirror.create(tmp);
        assertEquals(List.of(m.root().toRealPath()), SkillJail.hiddenRoots(m.layout()));
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
        assertEquals(2, roots.size(), roots.toString());
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
    @DisplayName("expose entries that overlap the service's state are dropped; a HOME dotfile is kept")
    void exposeFiltering() {
        var layout = new SkillJail.Layout(Path.of("/opt/ownclaw"), Path.of("/opt/ownclaw/data/ownclaw.db"),
                Path.of("/opt/ownclaw/skills/generated"), Path.of("/opt/ownclaw/logs"));
        var kept = SkillJail.filterExpose(List.of(
                "/opt/ownclaw/data/x", "/opt/ownclaw/data/uploads/a.pdf", "/opt/ownclaw/.env",
                "/opt/ownclaw/skills/generated/other", "/opt/ownclaw/skills/_envs/x", "/opt/ownclaw/logs",
                "/opt/ownclaw/backups/b.tar", "/opt/ownclaw/repo/.git", "/opt/ownclaw", "/opt", "/",
                "/proc/1", "/tmp", "relative/.ssh", "  ",
                "/opt/ownclaw/.ssh", "/opt/ownclaw/.config/rclone/"), layout);
        assertEquals(List.of(
                new SkillJail.Bind(Path.of("/opt/ownclaw/.ssh"), Path.of("/opt/ownclaw/.ssh")),
                new SkillJail.Bind(Path.of("/opt/ownclaw/.config/rclone"), Path.of("/opt/ownclaw/.config/rclone"))),
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
