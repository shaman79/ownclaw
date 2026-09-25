package com.ownclaw.users;

import com.ownclaw.agent.SkillManager;
import com.ownclaw.agent.tools.DynamicSkillRegistry;
import com.ownclaw.agent.tools.ToolRegistry;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.core.TaskQueue;
import com.ownclaw.interfaces.CommandHandler;
import com.ownclaw.sandbox.JailTestKit;
import com.ownclaw.sandbox.ProcessSandbox;
import com.ownclaw.skills.PythonEnvironmentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The one rule for a credential's name -- the name of a variable in the skill's own environment
 * -- and the one parser for the names a skill declares, each place a name is made holding to
 * them: /cred set, skill_create, SKILL.yaml.
 */
class CredentialNameRuleTest {

    @TempDir Path tmp;

    private static final String REFUSED = CredentialVault.nameRefusal("API-KEY");

    @Test
    @DisplayName("a name is upper-case letters, digits and underscores, starting with a letter")
    void theRule() {
        for (String name : List.of("SMTP_PASSWORD", "IMAP_USER", "API_TOKEN", "X1", "LD_PRELOAD", "DOCKER_HOST",
                "HOME", "PATH")) {
            assertTrue(CredentialVault.validName(name), name);
        }
        for (String name : List.of("", "smtp_password", "Smtp_Password", "1KEY", "API-KEY", "A B", "_X", "A.B",
                "A=B")) {
            assertFalse(CredentialVault.validName(name), name);
        }
        assertFalse(CredentialVault.validName(null));
    }

    @Test
    @DisplayName("one grammar: a string, a list and a nested list of the same names parse alike")
    void oneGrammar() {
        List<String> names = List.of("IMAP_HOST", "IMAP_PORT", "SMTP_USER");
        assertEquals(names, DynamicSkillRegistry.credentialNames("[imap_host, IMAP_PORT ,smtp_user]"));
        assertEquals(names, DynamicSkillRegistry.credentialNames(List.of("imap_host", List.of("[IMAP_PORT]"), "smtp_user")));
        assertEquals(names, DynamicSkillRegistry.credentialNames(List.of("imap_host, IMAP_PORT", "smtp_user")));
        assertEquals(List.of(), DynamicSkillRegistry.credentialNames(null));
        assertEquals(List.of(), DynamicSkillRegistry.credentialNames(" , []"));
        var e = assertThrows(IllegalArgumentException.class,
                () -> DynamicSkillRegistry.credentialNames(List.of("SMTP_USER", List.of("api-key"))));
        assertEquals(REFUSED, e.getMessage());
    }

    @Test
    @DisplayName("/cred set refuses a name that breaks the rule, in any case, and says why; nothing is stored")
    void credSetRefusesABadName() {
        // No database: the name is refused before the vault reaches for one.
        var vault = new CredentialVault(null);
        var e = assertThrows(IllegalArgumentException.class, () -> vault.storeCredential("u1", " api-key ", "x"));
        assertEquals(REFUSED, e.getMessage());

        var commands = new CommandHandler(null, null, null, null, vault, null,
                new TaskQueue(null, null, null, new OwnClawConfig(), null), null, null, null, null,
                null, null, JailTestKit.off());
        assertEquals(Optional.of("Not stored: " + REFUSED), commands.handle("u1", "/cred set api-key x"));
    }

    @Test
    @DisplayName("skill_create refuses a bad credential name before writing anything; a SKILL.yaml declaring one does not load")
    void aSkillCannotDeclareABadName() throws IOException {
        Path generated = Files.createDirectories(tmp.resolve("skills/generated"));
        var cfg = new OwnClawConfig();
        cfg.getSkills().setGeneratedPath(generated.toString());
        cfg.getDatabase().setPath(tmp.resolve("data/ownclaw.db").toString());
        cfg.getSandbox().setPythonPath("python3");
        var jail = JailTestKit.off();
        var env = new PythonEnvironmentService(cfg, jail);
        var sandbox = new ProcessSandbox(jail);
        var tools = new ToolRegistry(List.of());
        var dynamic = new DynamicSkillRegistry(cfg, tools, sandbox, null, env, null, null);
        var manager = new SkillManager(dynamic, tools, cfg, sandbox, env, null);

        Map<String, Object> p = new LinkedHashMap<>();
        p.put("name", "mail_sender");
        p.put("description", "Sends mail.");
        p.put("parameters", "{}");
        p.put("code", "def run(params):\n    return {}\n");
        p.put("credentials", "[SMTP_USER, api-key]");
        assertEquals("ERROR: " + REFUSED, manager.createSkill(p));
        assertFalse(Files.exists(generated.resolve("mail_sender")), "something was written");

        Path dir = Files.createDirectories(generated.resolve("mail_sender"));
        Files.writeString(dir.resolve("SKILL.yaml"),
                "name: mail_sender\ndescription: Sends mail.\ncredentials: [SMTP_USER, api-key]\n");
        Files.writeString(dir.resolve("skill.py"), "def run(params):\n    return {}\n");
        var e = assertThrows(IOException.class, () -> dynamic.loadSkill(dir));
        assertEquals("SKILL.yaml: " + REFUSED, e.getMessage());
    }
}
