package com.ownclaw.users;

import com.ownclaw.agent.SkillManager;
import com.ownclaw.agent.tools.DynamicSkillRegistry;
import com.ownclaw.agent.tools.ToolRegistry;
import com.ownclaw.config.OwnClawConfig;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The one rule for a credential's name -- the name of the variable a skill reads it from -- held
 * where a name is stored (/cred set) or declared (skill_create, SKILL.yaml), and the one parser for
 * the names a skill declares. A value typed where the name goes was stored as the name, and the names
 * the vault holds go to the cloud model with every prompt.
 */
class CredentialNameRuleTest {

    @TempDir Path tmp;

    private static final String REFUSED = CredentialVault.NAME_RULE;

    @Test
    @DisplayName("a name is upper-case letters, digits and underscores, starting with a letter")
    void theRule() {
        for (String name : List.of("SMTP_PASSWORD", "SSH_PRIVATE_KEY", "IMAP_USER", "X1", "A_")) {
            assertTrue(CredentialVault.validName(name), name);
        }
        for (String name : List.of("", "smtp_password", "1KEY", "API-KEY", "A B", "_X", "A.B", "A=B",
                "SMTP_PASS=HUNTER2", "K\u0000", "Ä")) {
            assertFalse(CredentialVault.validName(name), name);
        }
        assertFalse(CredentialVault.validName(null));
    }

    @Test
    @DisplayName("the vault refuses a bad name before it reaches for the database, and lower case is upper-cased first")
    void theVaultRefuses() {
        var vault = new CredentialVault(null);
        var e = assertThrows(IllegalArgumentException.class, () -> vault.storeCredential("u1", " api-key ", "x"));
        assertEquals(CredentialVault.NAME_RULE, e.getMessage());
        // A good name, lower-cased, passes the rule and goes on to the database -- here none.
        var past = assertThrows(RuntimeException.class, () -> vault.storeCredential("u1", "smtp_pass", "x"));
        assertEquals("Failed to store credential", past.getMessage());
    }

    @Test
    @DisplayName("what a credential cannot be: a name in mixed case, a value with a NUL, a value holding another secret command")
    void refusals() {
        assertNull(CredentialVault.refusal("smtp_pass", "x"));
        assertNull(CredentialVault.refusal("SMTP_PASS", "line one\nline two"));
        assertEquals(CredentialVault.NAME_RULE, CredentialVault.refusal("Tr0ub4dor", "OPENWRT_PASS"));
        assertEquals(CredentialVault.NAME_RULE, CredentialVault.refusal("Smtp_Pass", "x"));
        assertNotNull(CredentialVault.refusal("K", "hunter2\u0000x"));
        assertNotNull(CredentialVault.refusal("A", "x\n/cred set B y"), "two commands in one message");
        assertNotNull(CredentialVault.refusal("A", "x\n  /USER add bob pw"));
        assertNull(CredentialVault.refusal("A", "x\n/credentials are kept elsewhere"), "a word that starts so");
        var vault = new CredentialVault(null);
        assertThrows(IllegalArgumentException.class, () -> vault.storeCredential("u1", "A", "x\n/cred set B y"));
    }

    @Test
    @DisplayName("/cred set refuses a bad name, stores nothing, and does not repeat what was typed")
    void credSetRefuses() {
        List<String> stored = new ArrayList<>();
        var vault = new CredentialVault(null) {
            @Override public void storeCredential(String userId, String key, String value) {
                stored.add(key);
            }
        };
        var commands = new CommandHandler(null, null, null, null, vault, null, null, null, null,
                null, null, null, null, null, null, null);

        assertEquals(Optional.of("Not stored: " + CredentialVault.NAME_RULE),
                commands.handle("u1", "/cred set api-key x"));
        Optional<String> typo = commands.handle("u1", "/cred set SMTP_PASS=hunter2 x");
        assertEquals(Optional.of("Not stored: " + CredentialVault.NAME_RULE), typo);
        assertFalse(typo.get().toLowerCase(Locale.ROOT).contains("hunter2"));
        assertTrue(stored.isEmpty(), "stored " + stored);

        assertTrue(commands.handle("u1", "/cred set ssh_private_key line one\nline two").get().contains("stored"));
        assertEquals(List.of("SSH_PRIVATE_KEY"), stored);
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
        var manager = new SkillManager(dynamic, tools, cfg, sandbox, env, null, null);

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
