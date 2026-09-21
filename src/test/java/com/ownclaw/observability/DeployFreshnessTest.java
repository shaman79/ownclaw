package com.ownclaw.observability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Locating the artifact this process was launched from.
 * <p>
 * The deploy-freshness check exists because {@code deployedCommit} reads a marker that deploy.sh
 * writes before the service restarts, and has therefore reported a commit that was not running
 * three separate times. The fix was to compare the JAR's mtime against the process start time —
 * except the first version asked the protection domain for a {@code Path} and Spring Boot's
 * nested launcher hands back a {@code jar:file:...!/BOOT-INF/classes!/} URL, whose scheme is not
 * {@code file}. {@code Path.of} threw, the catch returned null, the field was omitted, and the
 * whole check silently did nothing in production for a day while looking correct.
 * <p>
 * So the URL reduction is pinned here. The other half — that {@code java -jar} puts exactly the
 * jar on {@code java.class.path} — was verified by launching a real jar.
 */
class DeployFreshnessTest {

    @Test
    @DisplayName("a Spring Boot nested jar URL reduces to the jar on disk")
    void springBootNestedUrl() {
        // The exact shape that broke it.
        assertEquals("file:/opt/ownclaw/ownclaw.jar",
                OpsService.fileUrlOfContainingArchive(
                        "jar:file:/opt/ownclaw/ownclaw.jar!/BOOT-INF/classes!/"));

        // Boot 3 also emits a single-bang form for a nested entry.
        assertEquals("file:/opt/ownclaw/ownclaw.jar",
                OpsService.fileUrlOfContainingArchive(
                        "jar:file:/opt/ownclaw/ownclaw.jar!/BOOT-INF/lib/spring-core.jar"));
    }

    @Test
    @DisplayName("a plain jar URL is already the answer")
    void plainJarUrl() {
        assertEquals("file:/opt/ownclaw/ownclaw.jar",
                OpsService.fileUrlOfContainingArchive("file:/opt/ownclaw/ownclaw.jar"));
    }

    @Test
    @DisplayName("an exploded build resolves to its directory, not null")
    void explodedClasspath() {
        // Still a file: URL — the caller rejects it by asking whether it is a regular file,
        // which keeps the "is it a directory" decision in one place.
        assertEquals("file:/home/dev/ownclaw/build/classes/java/main",
                OpsService.fileUrlOfContainingArchive("file:/home/dev/ownclaw/build/classes/java/main"));
    }

    @Test
    @DisplayName("anything that does not name a file is refused rather than guessed at")
    void nonFileUrls() {
        assertNull(OpsService.fileUrlOfContainingArchive(null));
        assertNull(OpsService.fileUrlOfContainingArchive("http://example.com/app.jar"));
        assertNull(OpsService.fileUrlOfContainingArchive("jrt:/java.base"));
        assertNull(OpsService.fileUrlOfContainingArchive(""));
    }
}
