package com.ownclaw.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownclaw.agent.tools.DynamicSkillRegistry;
import com.ownclaw.agent.tools.ToolRegistry;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.sandbox.ProcessSandbox;
import com.ownclaw.skills.PythonEnvironmentService;
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
 * The guarantees around generating a skill, exercised against a real temp directory and a real
 * Python process — no mocks, because the thing being verified is precisely whether the code
 * <em>runs</em>, and a mocked sandbox would assert nothing.
 * <p>
 * This is the project's first test, and it guards the failure that cost the most: a regenerated
 * skill that cannot even import used to overwrite a working one in place, permanently, with
 * generated skills living outside the repository and therefore in no backup.
 */
class SkillLifecycleTest {

    @TempDir Path root;

    private ToolRegistry registry;
    private SkillManager manager;
    private Path generated;

    private static final String PARAMS =
            "{\"name\": {\"type\": \"string\", \"required\": true, \"description\": \"who to greet\"}}";

    private static final String WORKING_CODE =
            "def run(params):\n    return {'greeting': 'hello ' + str(params.get('name',''))}\n";

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
        DynamicSkillRegistry dynamic =
                new DynamicSkillRegistry(cfg, registry, sandbox, null, pyEnv, null, null);
        manager = new SkillManager(dynamic, registry, cfg, sandbox, pyEnv, null);
    }

    private String create(String name, String description, String code) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("name", name);
        p.put("description", description);
        p.put("parameters", PARAMS);
        p.put("code", code);
        return manager.createSkill(p);
    }

    @Test
    @DisplayName("a working skill is accepted and registered")
    void workingSkillIsRegistered() {
        String result = create("greet_user", "Return a greeting for a name.", WORKING_CODE);
        assertFalse(result.startsWith("ERROR"), result);
        assertTrue(registry.find("greet_user").isPresent(), "should be registered as a tool");
    }

    @Test
    @DisplayName("a skill importing a package nobody installed is rejected, not registered")
    void unimportableSkillIsRejected() {
        // Passes py_compile — it is perfectly valid Python. Only executing the import catches it,
        // which is why the old parse-only gate let this register and fail on first real use.
        String result = create("broken_import", "Imports something that does not exist.",
                "import totally_not_a_real_package_xyz\ndef run(params):\n    return {}\n");
        assertTrue(result.startsWith("ERROR"), "expected rejection, got: " + result);
        assertTrue(registry.find("broken_import").isEmpty(), "must not be registered");
    }

    @Test
    @DisplayName("a broken update does not destroy the working version")
    void brokenUpdateRestoresPreviousCode() throws IOException {
        create("greet_user", "Return a greeting for a name.", WORKING_CODE);
        Path codeFile = generated.resolve("greet_user").resolve("skill.py");
        String before = Files.readString(codeFile);

        String result = create("greet_user", "Return a greeting for a name.",
                "import another_missing_package_abc\ndef run(params):\n    return {}\n");

        assertTrue(result.startsWith("ERROR"), "expected rejection, got: " + result);
        assertEquals(before, Files.readString(codeFile),
                "the previous working code must be restored byte-for-byte");
        assertTrue(registry.find("greet_user").isPresent(), "must remain usable");
    }

    @Test
    @DisplayName("a valid update replaces the code")
    void validUpdateIsApplied() throws IOException {
        create("greet_user", "Return a greeting for a name.", WORKING_CODE);
        String result = create("greet_user", "Return a greeting, politely.",
                "def run(params):\n    return {'greeting': 'good day ' + str(params.get('name',''))}\n");

        assertFalse(result.startsWith("ERROR"), result);
        assertTrue(Files.readString(generated.resolve("greet_user").resolve("skill.py"))
                .contains("good day"), "new code should be on disk");
    }

    @Test
    @DisplayName("a module with no run() is rejected")
    void moduleWithoutRunIsRejected() {
        String result = create("no_entry_point", "Has no run function.",
                "def helper():\n    return 1\n");
        assertTrue(result.startsWith("ERROR"), "expected rejection, got: " + result);
        assertTrue(registry.find("no_entry_point").isEmpty(), "must not be registered");
    }
}
