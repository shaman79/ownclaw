package com.ownclaw.sandbox;

import com.ownclaw.agent.SkillManager;
import com.ownclaw.agent.tools.DynamicSkillRegistry;
import com.ownclaw.agent.tools.ToolRegistry;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.core.TaskQueue;
import com.ownclaw.interfaces.CommandHandler;
import com.ownclaw.skills.PythonEnvironmentService;
import com.ownclaw.users.CredentialVault;
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
 * The one rule for the name of a variable a skill is given beside the allow-list -- a credential
 * it declares -- and each place such a name is made or used holding to it: /cred set,
 * skill_create, SKILL.yaml, and the start of every process a skill's variables reach
 * (bubblewrap, the container runtime's CLI).
 */
class CredentialNameRuleTest {

    @TempDir Path tmp;

    private static final String REFUSED = SkillJail.nameRefusal("LD_PRELOAD");

    @Test
    @DisplayName("upper-case names are settable; names the loader, the C library or the container runtime reads are not")
    void theRule() {
        for (String name : List.of("SMTP_PASSWORD", "IMAP_USER", "API_TOKEN", "X1", "LDAP_PASSWORD",
                "DOCKERHUB_TOKEN", "HOME_ASSISTANT_TOKEN", "TMPDIR_X", "PATH", "PYTHONPATH", "VIRTUAL_ENV",
                "PYTHONIOENCODING", "PYTHONUTF8")) {
            assertTrue(SkillJail.settableName(name), name);
        }
        for (String name : List.of("LD_PRELOAD", "LD_LIBRARY_PATH", "LD_AUDIT", "MALLOC_ARENA_MAX",
                "GCONV_PATH", "GETCONF_DIR", "GLIBC_TUNABLES", "HOSTALIASES", "LOCALDOMAIN", "LOCPATH",
                "NIS_PATH", "NLSPATH", "RESOLV_HOST_CONF", "RES_OPTIONS", "TMPDIR", "TZDIR",
                "DOCKER_HOST", "CONTAINER_HOST", "CONTAINERS_CONF", "XDG_RUNTIME_DIR", "STORAGE_OPTS",
                "BUILDAH_ISOLATION", "PODMAN_USERNS", "_CONTAINERS_USERNS_CONFIGURED", "HTTP_PROXY",
                "HTTPS_PROXY", "NO_PROXY", "REGISTRY_AUTH_FILE", "HOME",
                "", "smtp_password", "Smtp_Password", "1KEY", "API-KEY", "A B", "_X")) {
            assertFalse(SkillJail.settableName(name), name);
        }
        assertFalse(SkillJail.settableName(null));
    }

    @Test
    @DisplayName("a process is not given a refused name; what the service puts there itself -- PATH, PYTHONPATH, VIRTUAL_ENV -- it is")
    void applyEnvironmentHoldsItsCallerToTheRule() {
        var e = assertThrows(IllegalArgumentException.class, () -> SkillJail.applyEnvironment(
                new ProcessBuilder("true"), Map.of("SMTP_PASSWORD", "s", "LD_PRELOAD", "/x.so")));
        assertEquals(REFUSED, e.getMessage());

        var pb = new ProcessBuilder("true");
        SkillJail.applyEnvironment(pb, Map.of("PATH", "/venv/bin:/usr/bin", "PYTHONPATH", "/target",
                "VIRTUAL_ENV", "/venv", "PYTHONUTF8", "1", "SMTP_PASSWORD", "s"));
        assertEquals("/venv/bin:/usr/bin", pb.environment().get("PATH"));
        assertEquals("/target", pb.environment().get("PYTHONPATH"));
        assertEquals("/venv", pb.environment().get("VIRTUAL_ENV"));
        assertEquals("s", pb.environment().get("SMTP_PASSWORD"));
    }

    @Test
    @DisplayName("/cred set refuses a reserved name, in any case, and says why; nothing is stored")
    void credSetRefusesAReservedName() {
        // No database: the name is refused before the vault reaches for one.
        var vault = new CredentialVault(null);
        var e = assertThrows(IllegalArgumentException.class, () -> vault.storeCredential("u1", " ld_preload ", "/x.so"));
        assertEquals(REFUSED, e.getMessage());

        var commands = new CommandHandler(null, null, null, null, vault, null,
                new TaskQueue(null, null, null, new OwnClawConfig(), null), null, null, null, null,
                null, null, JailTestKit.off());
        assertEquals(Optional.of("Not stored: " + REFUSED), commands.handle("u1", "/cred set ld_preload /x.so"));
    }

    @Test
    @DisplayName("skill_create refuses a reserved credential name before writing anything; a SKILL.yaml declaring one does not load")
    void aSkillCannotDeclareAReservedName() throws IOException {
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
        p.put("credentials", "[SMTP_USER, ld_preload]");
        assertEquals("ERROR: " + REFUSED, manager.createSkill(p));
        assertFalse(Files.exists(generated.resolve("mail_sender")), "something was written");
        assertEquals("ERROR: " + REFUSED, manager.patchCredentials("mail_sender", "SMTP_USER,LD_PRELOAD"));

        Path dir = Files.createDirectories(generated.resolve("mail_sender"));
        Files.writeString(dir.resolve("SKILL.yaml"),
                "name: mail_sender\ndescription: Sends mail.\ncredentials: [SMTP_USER, ld_preload]\n");
        Files.writeString(dir.resolve("skill.py"), "def run(params):\n    return {}\n");
        var e = assertThrows(IOException.class, () -> dynamic.loadSkill(dir));
        assertEquals("SKILL.yaml: " + REFUSED, e.getMessage());
    }
}
