package com.ownclaw.sandbox;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Every jailed run holds a user namespace while it runs, and user.max_user_namespaces caps how
 * many the service's Linux user may hold. A self-check that finds none free says nothing about
 * whether the host can jail -- and a jailed skill can hold them all -- so it must refuse, never
 * have auto run skills unjailed. And the limit must be read as it is: the reader took the first
 * digit only, and a production host's ~3xxxx read as a limit of 3 (November 2026).
 */
class NamespaceShortageTest {

    @TempDir Path tmp;

    private JailTestKit.Mirror m;

    @BeforeEach
    void setUp() throws IOException {
        m = JailTestKit.Mirror.create(tmp);
    }

    private SkillJail shortJail(String mode, String limit) throws IOException {
        Path bwrap = JailTestKit.noFreeNamespace(tmp.resolve("bin/bwrap"));
        var jail = new SkillJail(mode, bwrap.toString(), m.layout(), List.of(), System::currentTimeMillis);
        Path host = tmp.resolve("host");
        Files.createDirectories(host.resolve("proc/sys/user"));
        Files.writeString(host.resolve("proc/sys/user/max_user_namespaces"), limit + "\n");
        jail.hostRoot = host;
        return jail;
    }

    @Test
    @DisplayName("a /proc/sys value is read whole, not its first digit")
    void aSysctlIsReadWhole() throws IOException {
        Path real = Path.of("/proc/sys/user/max_user_namespaces");
        assumeTrue(Files.isReadable(real), "no /proc/sys here");
        String expected = Files.readAllLines(real).get(0).strip();
        assumeTrue(expected.length() > 1, "a one-digit limit cannot tell");
        assertEquals(expected, SkillJail.sysctls(Path.of("/")).get("user.max_user_namespaces"));
    }

    @Test
    @DisplayName("bubblewrap's own no-namespace line is told from everything else")
    void theShortageIsToldApart() {
        String older = "bwrap: Creating new namespace failed: No space left on device";
        assertTrue(SkillJail.namespacesUsedUp(1, JailTestKit.NO_NAMESPACE));
        assertTrue(SkillJail.namespacesUsedUp(1, JailTestKit.NO_NAMESPACE + "\n"));
        assertTrue(SkillJail.namespacesUsedUp(1, older));
        assertFalse(SkillJail.namespacesUsedUp(0, JailTestKit.NO_NAMESPACE), "it ended well");
        assertFalse(SkillJail.namespacesUsedUp(1, "Traceback ...\n" + JailTestKit.NO_NAMESPACE));
        assertFalse(SkillJail.namespacesUsedUp(1, JailTestKit.NO_NAMESPACE + "\nTraceback ..."));
        assertFalse(SkillJail.namespacesUsedUp(1, "bwrap: setting up uid map: Permission denied"));
        assertFalse(SkillJail.namespacesUsedUp(1, null));
    }

    @Test
    @DisplayName("a self-check with no free namespace refuses, even under auto: it never runs skills unjailed")
    void aShortSelfCheckRefuses() throws Exception {
        var jail = shortJail("auto", "31845");
        var s = jail.status();
        assertEquals(SkillJail.State.REFUSING, s.state(), s.reason());
        assertTrue(s.reason().contains("ENOSPC"), s.reason());
        try (var l = jail.prepare(List.of("python3", "-c", "pass"), m.skillDir(), List.of())) {
            assertNotNull(l.refusal());
            assertFalse(l.jailed());
        }
    }

    @Test
    @DisplayName("a host whose limit is 0 has no user namespaces at all: that is the host, inactive under auto as before")
    void aLimitOfZeroIsTheHost() throws Exception {
        var jail = shortJail("auto", "0");
        var s = jail.status();
        assertEquals(SkillJail.State.INACTIVE, s.state(), s.reason());
        try (var l = jail.prepare(List.of("python3", "-c", "pass"), m.skillDir(), List.of())) {
            assertNull(l.refusal());
            assertFalse(l.jailed());
        }
    }

    @Test
    @DisplayName("a host with few namespaces is worth a word; a normal one, or an unknown one, is not")
    void fewNamespacesAreNoticed() {
        assertEquals(3, SkillJail.fewNamespaces(Map.of("user.max_user_namespaces", "3")));
        assertEquals(-1, SkillJail.fewNamespaces(Map.of("user.max_user_namespaces", "31845")));
        assertEquals(-1, SkillJail.fewNamespaces(Map.of("user.max_user_namespaces", "0")),
                "0 is no user namespaces at all, said elsewhere");
        assertEquals(-1, SkillJail.fewNamespaces(Map.of("user.max_user_namespaces", "absent")));
        assertEquals(-1, SkillJail.fewNamespaces(Map.of()));
    }
}
