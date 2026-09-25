package com.ownclaw.sandbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The real-bubblewrap tests look for what the jail hides in a directory it shows. One under a
 * temp directory, which the jail replaces with the run's own, would show them nothing and let
 * them pass whatever the jail did; so the kit refuses it, loudly.
 */
class JailTestKitTest {

    @TempDir Path tmp;

    @Test
    @DisplayName("a visible directory under the JVM's temp directory is refused, by its written path and by its real one")
    void aTempDirectoryIsNeverVisible() throws Exception {
        var e = assertThrows(AssertionError.class, () -> JailTestKit.visibleDir(tmp.resolve("build/jail-it"), "x-"));
        assertTrue(e.getMessage().contains("which the jail replaces"), e.getMessage());
        assertFalse(Files.exists(tmp.resolve("build")), "something was made there first");

        // A link from the project's build directory into it: fine by its written path, not by its real one.
        Path link = Files.createDirectories(Path.of("build").toAbsolutePath())
                .resolve("jail-it-link-" + ProcessHandle.current().pid());
        Files.createSymbolicLink(link, Files.createDirectories(tmp.resolve("elsewhere")));
        try {
            e = assertThrows(AssertionError.class, () -> JailTestKit.visibleDir(link, "x-"));
            assertTrue(e.getMessage().contains("which the jail replaces"), e.getMessage());
        } finally {
            Files.delete(link);
        }
        try (var left = Files.list(tmp.resolve("elsewhere"))) {
            assertEquals(0, left.count(), "a directory was made through the link");
        }

        Path shown = JailTestKit.visibleDir("kit-");
        try {
            assertTrue(Files.isDirectory(shown), shown.toString());
        } finally {
            Files.delete(shown);
        }
    }
}
