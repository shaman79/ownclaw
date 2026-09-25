package com.ownclaw.sandbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
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
    @DisplayName("deleteTree removes directories a run left unreadable or unwritable, the root's own included")
    void deleteTreeOpensEachDirectoryBeforeListingIt() throws Exception {
        Path root = Files.createDirectories(tmp.resolve("run-tmp"));
        Path closed = Files.createDirectories(root.resolve("closed"));
        Files.writeString(closed.resolve("inside.txt"), "x");
        chmod(closed, "---------");
        Path writeOnly = Files.createDirectories(root.resolve("a/write-only"));
        Files.writeString(writeOnly.resolve("inside.txt"), "x");
        chmod(writeOnly, "-wx------");
        chmod(root, "---------");

        SkillJail.deleteTree(root);

        assertFalse(Files.exists(root, LinkOption.NOFOLLOW_LINKS), "deleteTree left the tree behind");
    }

    @Test
    @DisplayName("deleteTree unlinks a link and never enters it")
    void deleteTreeNeverEntersALink() throws Exception {
        Path elsewhere = Files.createDirectories(tmp.resolve("elsewhere"));
        Path kept = Files.writeString(elsewhere.resolve("kept.txt"), "x");
        chmod(elsewhere, "r-x------");
        Path root = Files.createDirectories(tmp.resolve("run-tmp"));
        Files.createSymbolicLink(root.resolve("out"), elsewhere);

        SkillJail.deleteTree(root);

        assertFalse(Files.exists(root, LinkOption.NOFOLLOW_LINKS));
        assertTrue(Files.exists(kept), "deleteTree deleted through a link");
        assertEquals("r-x------", PosixFilePermissions.toString(Files.getPosixFilePermissions(elsewhere)),
                "deleteTree opened a directory through a link");
        chmod(elsewhere, "rwx------");
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
    @DisplayName("a directory that cannot be opened is named in the refusal")
    void aDirectoryThatStaysUnreadableIsNamed() throws Exception {
        // Nothing in a skill's directory can be another user's, so borrow one of the host's.
        Path notOurs = Path.of("/root");
        assumeTrue(Files.isDirectory(notOurs) && !Files.isReadable(notOurs),
                "needs a directory this user can neither read nor open");
        assertEquals("unreadable directory /root in the skill's own directory /root",
                SkillJail.linkedOutside(notOurs));
    }
}
