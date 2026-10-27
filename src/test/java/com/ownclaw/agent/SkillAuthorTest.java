package com.ownclaw.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.ownclaw.agent.tools.DynamicSkillRegistry;
import com.ownclaw.agent.tools.ToolRegistry;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.sandbox.ProcessSandbox;
import com.ownclaw.skills.PythonEnvironmentService;
import com.ownclaw.users.AuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Who may change a skill. A skill's code is global -- every account's tasks are offered it -- and
 * it runs with the vault values and the files of whoever runs it: overwritten by another account,
 * the owner's next run of it would hand that account's code his credentials. So the account that
 * first wrote a skill is its author, kept in its SKILL.yaml, and only it and the owner may change
 * or delete it; a skill written before authors were kept is the owner's; a new name is anyone's.
 */
class SkillAuthorTest {

    static final String OWNER = "owner";

    @TempDir Path root;
    private Path generated;
    private ToolRegistry registry;
    private SkillManager manager;

    private static final String PARAMS =
            "{\"name\": {\"type\": \"string\", \"required\": true, \"description\": \"who to greet\"}}";

    @BeforeEach
    void setUp() throws IOException {
        generated = Files.createDirectories(root.resolve("generated"));
        OwnClawConfig cfg = new OwnClawConfig();
        cfg.getSkills().setGeneratedPath(generated.toString());
        cfg.getSandbox().setPythonPath("python3");
        PythonEnvironmentService pyEnv = new PythonEnvironmentService(cfg);
        pyEnv.init();
        ProcessSandbox sandbox = new ProcessSandbox(pyEnv, new ObjectMapper());
        registry = new ToolRegistry(List.of());
        var auth = new AuthService(null, null, new OwnClawConfig()) {
            @Override public boolean isOwner(String userId) { return OWNER.equals(userId); }
        };
        manager = new SkillManager(new DynamicSkillRegistry(cfg, registry, sandbox, null, pyEnv, null, null),
                registry, cfg, sandbox, pyEnv, null, auth);
    }

    private static Map<String, Object> skill(String name, String greeting) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("name", name);
        p.put("description", "Return a greeting for a name.");
        p.put("parameters", PARAMS);
        p.put("code", "def run(params):\n    return {'greeting': '" + greeting + "'}\n");
        return p;
    }

    private String create(String requester, String name, String greeting) {
        return manager.createSkill(skill(name, greeting), requester, () -> false, () -> {});
    }

    private String delete(String requester, String name) {
        return manager.deleteSkill(name, requester, () -> false, () -> {});
    }

    private String authorOf(String name) throws IOException {
        Map<String, Object> yaml = new ObjectMapper(new YAMLFactory()).readValue(
                generated.resolve(name).resolve("SKILL.yaml").toFile(), new TypeReference<>() {});
        Object author = yaml.get("author");
        return author == null ? null : author.toString();
    }

    private String code(String name) throws IOException {
        return Files.readString(generated.resolve(name).resolve("skill.py"));
    }

    @Test
    @DisplayName("a skill keeps the account that first wrote it; another account's change is refused, its author's and the owner's are not")
    void onlyItsAuthorOrTheOwnerChangesIt() throws IOException {
        assertFalse(create("kid", "greet", "hello").startsWith("ERROR"));
        assertEquals("kid", authorOf("greet"), "its author is kept in its SKILL.yaml");

        String refused = create("other", "greet", "pwned");
        assertTrue(refused.startsWith("ERROR: the skill 'greet' belongs to another account"), refused);
        assertTrue(code("greet").contains("'hello'"), "another account's code was written over it");
        assertEquals("kid", authorOf("greet"));

        assertFalse(create("kid", "greet", "hi again").startsWith("ERROR"), "its author changes it");
        assertTrue(code("greet").contains("'hi again'"));

        assertFalse(create(OWNER, "greet", "repaired").startsWith("ERROR"), "the owner repairs it");
        assertTrue(code("greet").contains("'repaired'"));
        assertEquals("kid", authorOf("greet"), "the owner repairing it does not take it over");
    }

    @Test
    @DisplayName("a skill written before authors were kept is the owner's: another account may not change it, the owner may")
    void anAuthorlessSkillIsTheOwners() throws IOException {
        assertFalse(manager.createSkill(skill("legacy", "old")).startsWith("ERROR"),
                "the owner's pages and maintenance write a skill with no author");
        assertNull(authorOf("legacy"));

        String refused = create("kid", "legacy", "pwned");
        assertTrue(refused.startsWith("ERROR: the skill 'legacy' belongs to another account"), refused);
        assertTrue(code("legacy").contains("'old'"));

        assertFalse(create(OWNER, "legacy", "new").startsWith("ERROR"));
        assertEquals(OWNER, authorOf("legacy"), "changed by the owner, it is recorded as the owner's");
    }

    @Test
    @DisplayName("deleting another account's skill is refused; its author may delete it")
    void onlyItsAuthorOrTheOwnerDeletesIt() throws IOException {
        create("kid", "greet", "hello");
        String refused = delete("other", "greet");
        assertTrue(refused.startsWith("ERROR: the skill 'greet' belongs to another account, so it cannot be deleted"),
                refused);
        assertTrue(registry.find("greet").isPresent(), "it is still there");
        assertTrue(Files.exists(generated.resolve("greet").resolve("skill.py")));

        assertFalse(delete("kid", "greet").startsWith("ERROR"), "its author deletes it");
        assertTrue(registry.find("greet").isEmpty());

        create("kid", "greet2", "hello");
        assertFalse(delete(OWNER, "greet2").startsWith("ERROR"), "the owner deletes any");
    }

    @Test
    @DisplayName("a new name is anyone's to take")
    void aNewNameIsAnyones() throws IOException {
        assertFalse(create("other", "fresh", "hi").startsWith("ERROR"));
        assertEquals("other", authorOf("fresh"));
        assertTrue(registry.find("fresh").isPresent());
    }
}
