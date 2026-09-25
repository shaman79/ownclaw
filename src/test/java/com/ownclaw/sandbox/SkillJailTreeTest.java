package com.ownclaw.sandbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Walking a tree a skill could write: the hard-link check before a launch and the delete after
 * one. The skill decides what is in it -- directories left unreadable or unwritable, files that
 * another run removes while the walk is under way -- and neither walk may be thrown by that.
 */
class SkillJailTreeTest {

    @TempDir Path tmp;

    private static void chmod(Path p, String perms) throws IOException {
        Files.setPosixFilePermissions(p, PosixFilePermissions.fromString(perms));
    }

    @Test
    @DisplayName("deleteTree removes directories a run left unwritable, and a root it left closed")
    void deleteTreeOpensEachDirectoryBeforeListingIt() throws Exception {
        Path root = Files.createDirectories(tmp.resolve("run-tmp"));
        Path sealed = Files.createDirectories(root.resolve("a/sealed"));
        Files.writeString(sealed.resolve("inside.txt"), "x");
        chmod(sealed, "r-x------");
        chmod(root, "---------");

        assertTrue(SkillJail.deleteTree(root), "deleteTree said it left the tree behind");
        assertFalse(Files.exists(root, LinkOption.NOFOLLOW_LINKS), "deleteTree left the tree behind");
    }

    @Test
    @DisplayName("a directory below the root that the service user cannot read is left, and the rest removed")
    void deleteTreeLeavesWhatItCannotOpen() throws Exception {
        // Opening it for its owner would take a chmod by path, which follows a link; through its
        // parent the JDK has to open it first, which a directory without read permission refuses.
        Path root = Files.createDirectories(tmp.resolve("run-tmp"));
        Files.writeString(root.resolve("own.txt"), "x");
        Path closed = Files.createDirectories(root.resolve("closed"));
        Files.writeString(closed.resolve("inside.txt"), "x");
        chmod(closed, "-wx------");
        try {
            assertFalse(SkillJail.deleteTree(root), "deleteTree said the tree is gone");

            assertTrue(Files.exists(closed, LinkOption.NOFOLLOW_LINKS));
            assertEquals("-wx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(closed)));
            assertFalse(Files.exists(root.resolve("own.txt")), "the rest of the tree was left too");
        } finally {
            chmod(closed, "rwx------");
        }
    }

    @Test
    @DisplayName("deleteTree unlinks a link, at the top or further down, and never enters it")
    void deleteTreeNeverEntersALink() throws Exception {
        // Everything below the root is opened and removed by name through its parent, refusing a
        // link, so what a link leads to is never listed, opened for its owner or emptied.
        Path elsewhere = Files.createDirectories(tmp.resolve("elsewhere"));
        Path kept = Files.writeString(elsewhere.resolve("kept.txt"), "x");
        Path deep = Files.writeString(Files.createDirectories(elsewhere.resolve("sub")).resolve("deep.txt"), "x");
        chmod(elsewhere, "r-x------");
        Path root = Files.createDirectories(tmp.resolve("run-tmp"));
        Files.writeString(Files.createDirectories(root.resolve("a")).resolve("own.txt"), "x");
        Files.createSymbolicLink(root.resolve("out"), elsewhere);
        Files.createSymbolicLink(root.resolve("a/b"), elsewhere);

        assertTrue(SkillJail.deleteTree(root));

        assertFalse(Files.exists(root, LinkOption.NOFOLLOW_LINKS));
        assertTrue(Files.exists(kept) && Files.exists(deep), "deleteTree deleted through a link");
        assertEquals("r-x------", PosixFilePermissions.toString(Files.getPosixFilePermissions(elsewhere)),
                "deleteTree opened a directory through a link");
        chmod(elsewhere, "rwx------");
    }

    @Test
    @DisplayName("deleteTree stops " + SkillJail.DELETE_DEPTH + " directories down and returns, leaving what is deeper")
    void deleteTreeStopsAtItsDepth() throws Exception {
        // Each level holds an open directory and a frame of the walk: a run's endless chain
        // must not take either from the JVM.
        Path root = Files.createDirectories(tmp.resolve("run-tmp"));
        Path level = root;
        for (int i = 0; i <= SkillJail.DELETE_DEPTH + 1; i++) {
            Files.writeString(level.resolve("f"), "x");
            level = Files.createDirectory(level.resolve("d"));
        }
        Path last = root.resolve("d/".repeat(SkillJail.DELETE_DEPTH - 1));
        Path tooDeep = last.resolve("d");

        SkillJail.deleteTree(root);

        assertFalse(Files.exists(root.resolve("f")));
        assertFalse(Files.exists(last.resolve("f")), "the deepest level it enters is emptied");
        assertTrue(Files.exists(tooDeep.resolve("f")), "a level deeper than the limit was entered");
    }

    @Test
    @DisplayName("on a file system without link counts (Windows) the hard-link check finds nothing, and does not fail")
    void noLinkCountsNoRefusal() throws Exception {
        // A zip file system has no "unix" view, as Windows' has none: a container run there
        // checks its binds all the same.
        try (var fs = java.nio.file.FileSystems.newFileSystem(tmp.resolve("fs.zip"), java.util.Map.of("create", "true"))) {
            Path dir = Files.createDirectories(fs.getPath("/skill"));
            Files.writeString(dir.resolve("skill.py"), "x");
            assertFalse(fs.supportedFileAttributeViews().contains("unix"));
            assertNull(SkillJail.linkedOutside(dir));
        }
    }

    @Test
    @DisplayName("names another run removes while the hard-link check walks are skipped, not a refusal")
    void namesRemovedMidWalkAreSkipped() throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("skill"));
        for (int i = 0; i < 800; i++) {
            Path run = Files.createDirectories(dir.resolve("run-" + i));
            for (int j = 0; j < 3; j++) Files.writeString(run.resolve("_runner_" + j + ".py"), "x");
        }
        var failure = new AtomicReference<Throwable>();
        Thread remover = new Thread(() -> {
            try {
                for (int i = 0; i < 800; i++) {
                    Path run = dir.resolve("run-" + i);
                    for (int j = 0; j < 3; j++) Files.delete(run.resolve("_runner_" + j + ".py"));
                    Files.delete(run);
                }
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        remover.start();
        do {
            assertNull(SkillJail.linkedOutside(dir));
        } while (remover.isAlive());
        remover.join();
        assertNull(failure.get(), String.valueOf(failure.get()));
        assertNull(SkillJail.linkedOutside(dir));
    }

    @Test
    @DisplayName("a directory another run removes between the check reading it and listing it is skipped, not a refusal")
    void directoriesRemovedMidWalkAreSkipped() throws Exception {
        // A run making and removing work directories while a second launch of the same skill is
        // checked: one gone by the time it is listed is not an unreadable one.
        Path dir = Files.createDirectories(tmp.resolve("skill"));
        var stop = new AtomicBoolean();
        var failure = new AtomicReference<Throwable>();
        Thread churn = new Thread(() -> {
            try {
                for (long n = 0; !stop.get(); n++) {
                    Files.createDirectory(dir.resolve("work-" + n));
                    if (n >= 20) Files.delete(dir.resolve("work-" + (n - 20)));
                }
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        churn.start();
        try {
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (System.nanoTime() < end) assertNull(SkillJail.linkedOutside(dir));
        } finally {
            stop.set(true);
            churn.join();
        }
        assertNull(failure.get(), String.valueOf(failure.get()));
    }

    @Test
    @DisplayName("a bound directory that cannot be opened is named in the refusal")
    void aDirectoryThatStaysUnreadableIsNamed() throws Exception {
        // Nothing in a skill's directory can be another user's, so borrow one of the host's.
        Path notOurs = Path.of("/root");
        assumeTrue(Files.isDirectory(notOurs) && !Files.isReadable(notOurs),
                "needs a directory this user can neither read nor open");
        assertEquals("/root cannot be listed or entered, so what it holds cannot be checked",
                SkillJail.linkedOutside(notOurs));
    }

    @Test
    @DisplayName("the answer names the bound directory, never a name the walk found in it")
    void noWalkedNameInTheAnswer() throws Exception {
        // A second run can swap a directory for a link while the walk is under way, and the
        // names found past it are then another directory's.
        Path outside = Files.writeString(tmp.resolve("outside.txt"), "x");
        Path dir = Files.createDirectories(tmp.resolve("skill"));
        Files.createLink(Files.createDirectories(dir.resolve("sub")).resolve("found-name.txt"), outside);
        assertEquals("a file under " + dir + " has another name outside it (a hard link): tampered skill",
                SkillJail.linkedOutside(dir));

        SkillJail.deleteTree(dir.resolve("sub"));
        Path closed = Files.createDirectories(dir.resolve("closed-name"));
        chmod(closed, "---------");
        try {
            assertEquals("a directory under " + dir + " cannot be listed or entered, so what it holds cannot be checked",
                    SkillJail.linkedOutside(dir));
        } finally {
            chmod(closed, "rwx------");
        }
    }
}
