package com.ownclaw.sandbox;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The accounts' /tmp is kept across restarts, and what no run has used for a week goes: an
 * entry at its top, with all it holds, by its last use -- read or written -- never by its age.
 * A file's status-change time cannot be set back, so "a week later" is the sweep's clock moved
 * on, and a use within the week is a time set ahead.
 */
class AccountTmpSweepTest {

    private Path base;
    private JailTestKit.Mirror m;
    private SkillJail jail;
    private Path tmp;
    /** The sweep's now: eight days from the test's. */
    private final long later = System.currentTimeMillis() + Duration.ofDays(8).toMillis();

    @BeforeEach
    void setUp() throws Exception {
        base = JailTestKit.visibleDir("ownclaw-sweep-");
        m = JailTestKit.Mirror.create(base);
        jail = new SkillJail("off", "/usr/bin/bwrap", m.layout(), List.of(), System::currentTimeMillis);
        tmp = jail.sharedTmp("owner1");
    }

    @AfterEach
    void cleanUp() {
        if (base != null) SkillJail.deleteTree(base);
    }

    /** Read (and written, when {@code written}) at {@code at}, by its times. */
    static void usedAt(Path p, long at, boolean written) throws Exception {
        Files.getFileAttributeView(p, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
                .setTimes(written ? FileTime.fromMillis(at) : null, FileTime.fromMillis(at), null);
    }

    @Test
    @DisplayName("an entry no run has used for a week goes, a directory with all it holds; one read or written within the week stays")
    void byLastUse() throws Exception {
        long yesterday = later - Duration.ofDays(1).toMillis();
        Path stale = Files.createDirectories(tmp.resolve("invoice_harvest/mail"));
        for (int i = 0; i < 50; i++) Files.writeString(stale.resolve("m" + i + ".eml"), "x");
        Path read = Files.createDirectory(tmp.resolve("household"));
        usedAt(Files.writeString(read.resolve("selection.json"), "{}"), yesterday, false);
        usedAt(Files.writeString(tmp.resolve("report.txt"), "r"), later - Duration.ofDays(6).toMillis(), true);
        Path deep = Files.createDirectories(tmp.resolve("deep/a/b/c"));
        Files.writeString(tmp.resolve("deep/old.txt"), "o");
        usedAt(Files.writeString(deep.resolve("used.txt"), "u"), yesterday, false);
        // Listing a directory sets its access time, so that time does not count.
        Path listed = Files.createDirectory(tmp.resolve("listed"));
        Files.writeString(listed.resolve("old.txt"), "o");
        usedAt(listed, yesterday, false);

        assertEquals(2, jail.sweepUnusedTmp(later));
        assertFalse(Files.exists(tmp.resolve("invoice_harvest")), "unused for a week, with all it holds");
        assertFalse(Files.exists(listed), "only listed");
        for (String kept : List.of("household/selection.json", "report.txt", "deep/old.txt", "deep/a/b/c/used.txt")) {
            assertTrue(Files.exists(tmp.resolve(kept)), kept);
        }
        assertEquals(0, jail.sweepUnusedTmp(System.currentTimeMillis()), "nothing is a week old today");
        try (var left = Files.list(m.root().resolve("skills"))) {
            assertTrue(left.noneMatch(p -> p.getFileName().toString().startsWith("_tmp-old-")), "removed, not only set aside");
        }
        // Mutations: count a directory's access time -> listed stays; count only the top entry's
        // times -> deep goes; compare with the creation time -> household goes.
    }

    @Test
    @DisplayName("a link is removed, never followed; what cannot be listed is kept; an account /tmp a run closed is opened first")
    void linksAndClosedDirectories() throws Exception {
        Path outside = Files.writeString(base.resolve("outside.txt"), "keep me");
        Files.createSymbolicLink(tmp.resolve("link"), outside);
        Path closed = Files.createDirectory(tmp.resolve("closed"));
        Files.writeString(closed.resolve("f"), "x");
        Files.setPosixFilePermissions(closed, PosixFilePermissions.fromString("-wx------"));
        Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("---------"));

        assertEquals(1, jail.sweepUnusedTmp(later));
        assertFalse(Files.exists(tmp.resolve("link"), LinkOption.NOFOLLOW_LINKS));
        assertEquals("keep me", Files.readString(outside), "the link was followed");
        assertTrue(Files.exists(closed, LinkOption.NOFOLLOW_LINKS), "what it holds cannot be seen, so it is kept");
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(tmp)));
        Files.setPosixFilePermissions(closed, PosixFilePermissions.fromString("rwx------"));
    }

    @Test
    @DisplayName("no account /tmp yet: nothing to sweep")
    void nothingYet() throws Exception {
        var fresh = JailTestKit.Mirror.create(JailTestKit.visibleDir(base, "fresh-"));
        var other = new SkillJail("off", "/usr/bin/bwrap", fresh.layout(), List.of(), System::currentTimeMillis);
        assertEquals(0, other.sweepUnusedTmp(later));
    }
}
