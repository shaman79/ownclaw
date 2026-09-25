package com.ownclaw.sandbox;

import com.ownclaw.config.OwnClawConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What a container run of a skill is given of its directory. The container never passes the
 * jail, so what the jail refuses of a bind -- a link, a hard link out, an authored file that is
 * not a regular file -- is refused here too, and the authored files go in read-only.
 */
class ContainerRunCommandTest {

    @TempDir Path tmp;

    private Path skillDir;
    private Path env;
    private final ContainerSandbox container = new ContainerSandbox(new OwnClawConfig());

    @BeforeEach
    void setUp() throws IOException {
        env = Files.writeString(tmp.resolve(".env"), "OWNCLAW_OPS_TOKEN=canary-env-secret\n");
        skillDir = Files.createDirectories(tmp.resolve("generated/nmap_scan"));
        Files.writeString(skillDir.resolve("SKILL.yaml"), "name: nmap_scan\n");
        Files.writeString(skillDir.resolve("skill.py"), "def run(params):\n    return {}\n");
    }

    private List<String> command(Path dir) throws IOException {
        return container.buildRunCommand("test-image", "python3", dir.resolve("_runner_x.py"), dir, Map.of(), null);
    }

    @Test
    @DisplayName("the skill directory goes in read-write, its three authored files read-only on top")
    void authoredFilesAreReadOnly() throws Exception {
        List<String> cmd = command(skillDir);

        assertTrue(cmd.contains(skillDir + ":/skill"), cmd.toString());
        for (String name : SkillJail.AUTHORED) {
            assertTrue(cmd.contains(skillDir.resolve(name) + ":/skill/" + name + ":ro"), "not read-only: " + name);
        }
        assertTrue(cmd.indexOf(skillDir + ":/skill") < cmd.indexOf(skillDir.resolve("skill.py") + ":/skill/skill.py:ro"));
        assertTrue(Files.isRegularFile(skillDir.resolve("requirements.txt"), LinkOption.NOFOLLOW_LINKS),
                "a skill without requirements gets an empty one, so it cannot write its own");
    }

    @Test
    @DisplayName("a skill directory that is a link is refused, and nothing is created through it")
    void aLinkedDirectoryIsRefused() throws Exception {
        Path elsewhere = Files.createDirectories(tmp.resolve("elsewhere"));
        Files.writeString(elsewhere.resolve("SKILL.yaml"), "name: x\n");
        Files.writeString(elsewhere.resolve("skill.py"), "x = 1\n");
        Path linked = Files.createSymbolicLink(tmp.resolve("generated/linked"), elsewhere);

        var e = assertThrows(IOException.class, () -> command(linked));
        assertTrue(e.getMessage().contains("is a link"), e.getMessage());
        assertFalse(Files.exists(elsewhere.resolve("requirements.txt")), "requirements.txt was created through the link");
    }

    @Test
    @DisplayName("a skill directory holding a hard link to a file outside it is refused")
    void aHardLinkOutIsRefused() throws Exception {
        Files.createLink(skillDir.resolve("cache.bin"), env);
        var e = assertThrows(IOException.class, () -> command(skillDir));
        assertTrue(e.getMessage().contains("cache.bin"), e.getMessage());
    }

    @Test
    @DisplayName("an authored file that is a link is refused, never mounted where it leads")
    void anAuthoredLinkIsRefused() throws Exception {
        Files.delete(skillDir.resolve("skill.py"));
        Files.createSymbolicLink(skillDir.resolve("skill.py"), env);
        var e = assertThrows(IOException.class, () -> command(skillDir));
        assertTrue(e.getMessage().contains("tampered"), e.getMessage());
    }
}
