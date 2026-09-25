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
 * What a container run of a skill is given: its directory, the files it was handed, its
 * environment. The container never passes the jail, so what the jail refuses of a bind -- a
 * link, a hard link out, an authored file that is not a regular file, a bind reaching the
 * service's state -- is refused here too, and the authored files go in read-only.
 */
class ContainerRunCommandTest {

    @TempDir Path tmp;

    private Path skillDir;
    private Path env;
    private Path uploads;
    private ContainerSandbox container;

    @BeforeEach
    void setUp() throws IOException {
        env = Files.writeString(tmp.resolve(".env"), "OWNCLAW_OPS_TOKEN=canary-env-secret\n");
        skillDir = Files.createDirectories(tmp.resolve("skills/generated/nmap_scan"));
        Files.writeString(skillDir.resolve("SKILL.yaml"), "name: nmap_scan\n");
        Files.writeString(skillDir.resolve("skill.py"), "def run(params):\n    return {}\n");
        uploads = Files.createDirectories(tmp.resolve("data/uploads"));
        var config = new OwnClawConfig();
        config.getSkills().setGeneratedPath(tmp.resolve("skills/generated").toString());
        config.getDatabase().setPath(tmp.resolve("data/ownclaw.db").toString());
        container = new ContainerSandbox(config);
    }

    private List<String> command(Path dir) throws IOException {
        return container.buildRunCommand("test-image", "python3", dir.resolve("_runner_x.py"), dir, Map.of(), null);
    }

    private List<String> handing(Map<String, String> volumes) throws IOException {
        return container.buildRunCommand("test-image", "python3", skillDir.resolve("_runner_x.py"), skillDir,
                Map.of(), volumes);
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
        Path linked = Files.createSymbolicLink(tmp.resolve("skills/generated/linked"), elsewhere);

        var e = assertThrows(IOException.class, () -> command(linked));
        assertTrue(e.getMessage().contains("is a link"), e.getMessage());
        assertFalse(Files.exists(elsewhere.resolve("requirements.txt")), "requirements.txt was created through the link");
    }

    @Test
    @DisplayName("a skill directory holding a hard link to a file outside it is refused")
    void aHardLinkOutIsRefused() throws Exception {
        Files.createLink(skillDir.resolve("cache.bin"), env);
        var e = assertThrows(IOException.class, () -> command(skillDir));
        assertTrue(e.getMessage().contains("has another name outside it (a hard link)"), e.getMessage());
    }

    @Test
    @DisplayName("a handed file goes in read-only at the path the skill was told; a missing one not at all")
    void aHandedFileIsReadOnlyAtItsPath() throws Exception {
        Path handed = Files.writeString(uploads.resolve("9f2c_statement.pdf"), "%PDF");
        Path gone = uploads.resolve("0000_gone.pdf");
        var volumes = new java.util.LinkedHashMap<String, String>();
        volumes.put(handed.toString(), "/uploads/9f2c_statement.pdf");
        volumes.put(gone.toString(), "/uploads/0000_gone.pdf");
        List<String> cmd = handing(volumes);
        assertTrue(cmd.contains(handed + ":/uploads/9f2c_statement.pdf:ro"), cmd.toString());
        assertFalse(cmd.stream().skip(1).anyMatch(a -> a.contains(gone.toString())), "a missing file was mounted: " + cmd);
    }

    @Test
    @DisplayName("a handed file that is a hard link to .env, or a link, is refused -- as the jail refuses it")
    void aTamperedHandedFileIsRefused() throws Exception {
        Path hard = Files.createLink(uploads.resolve("9f2c_statement.pdf"), env);
        var e = assertThrows(IOException.class, () -> handing(Map.of(hard.toString(), "/uploads/x.pdf")));
        assertTrue(e.getMessage().contains("hard link"), e.getMessage());

        Path soft = Files.createSymbolicLink(uploads.resolve("7a1b_statement.pdf"), env);
        e = assertThrows(IOException.class, () -> handing(Map.of(soft.toString(), "/uploads/y.pdf")));
        assertTrue(e.getMessage().contains("is a link"), e.getMessage());
    }

    @Test
    @DisplayName("a handed path that holds the service's state is refused")
    void aHandedPathReachingStateIsRefused() throws Exception {
        Path data = uploads.getParent();
        var e = assertThrows(IOException.class, () -> handing(Map.of(data.toString(), "/uploads/all")));
        assertTrue(e.getMessage().contains("would expose"), e.getMessage());
    }

    @Test
    @DisplayName("credentials go by name on the command line, their values only in the runtime's environment")
    void credentialsByNameOnly() throws Exception {
        var envVars = Map.of("SKILL_CRED", "cred-canary", "PATH", "/host/venv/bin", "PYTHONIOENCODING", "utf-8");
        List<String> cmd = container.buildRunCommand("test-image", "python3", skillDir.resolve("_runner_x.py"),
                skillDir, envVars, null);
        int e = cmd.indexOf("SKILL_CRED");
        assertTrue(e > 0 && cmd.get(e - 1).equals("-e"), cmd.toString());
        assertTrue(cmd.contains("PYTHONIOENCODING"), cmd.toString());
        // The runtime's own name comes first; it is detected at startup, and here there is none.
        assertFalse(cmd.stream().skip(1).anyMatch(a -> a.contains("cred-canary") || a.startsWith("PATH=")),
                cmd.toString());
        assertFalse(cmd.contains("PATH"), "a host PATH was named: " + cmd);

        Map<String, String> cli = ContainerSandbox.runProcess(cmd, envVars).environment();
        assertEquals("cred-canary", cli.get("SKILL_CRED"));
        assertNotEquals("/host/venv/bin", cli.get("PATH"), "the host venv's PATH replaced the CLI's own");
        for (String name : cli.keySet()) {
            assertTrue(SkillJail.ENV_ALLOWLIST.contains(name) || envVars.containsKey(name),
                    "the runtime CLI was handed " + name);
        }
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
