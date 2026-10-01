package com.ownclaw.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownclaw.agent.tools.DynamicSkill;
import com.ownclaw.agent.tools.DynamicSkillRegistry;
import com.ownclaw.agent.tools.ToolExecutionContext;
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
import java.nio.file.attribute.PosixFilePermissions;
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
    @DisplayName("the name rule: a lowercase identifier the providers accept as a tool name")
    void theSkillNameRule() {
        String longest = "s" + "k".repeat(63);
        for (String ok : List.of("a", "web_fetch", "check_email_2", longest)) {
            assertTrue(SkillManager.isSkillName(ok), ok);
        }
        for (String bad : java.util.Arrays.asList(null, "", "Web_fetch", "9lives", "_x", "web-fetch",
                "web fetch", "wéb", longest + "s")) {
            assertFalse(SkillManager.isSkillName(bad), String.valueOf(bad));
        }
    }

    @Test
    @DisplayName("a 64-character name is created and offered; a 65-character one is refused plainly")
    void aNameTheProviderRefusesIsNotCreated() {
        // A longer name used to be accepted, then left out of every tools array without a word:
        // the skill existed and nothing could call it.
        String longest = "s" + "k".repeat(63);
        String result = create(longest, "The longest name a provider accepts.", WORKING_CODE);
        assertFalse(result.startsWith("ERROR"), result);
        assertEquals(List.of(longest), com.ownclaw.agent.tools.ToolSchemas.build(List.of(),
                registry.all(), List.of()).stream().map(com.ownclaw.llm.ToolSpec::name).toList());

        String tooLong = longest + "s";
        String refused = create(tooLong, "One character too many.", WORKING_CODE);
        assertTrue(refused.startsWith("ERROR: Invalid skill name") && refused.contains("64 characters"),
                refused);
        assertFalse(Files.exists(generated.resolve(tooLong)), "nothing is written");
        assertTrue(registry.find(tooLong).isEmpty());
    }

    @Test
    @DisplayName("a module with no run() is rejected")
    void moduleWithoutRunIsRejected() {
        String result = create("no_entry_point", "Has no run function.",
                "def helper():\n    return 1\n");
        assertTrue(result.startsWith("ERROR"), "expected rejection, got: " + result);
        assertTrue(registry.find("no_entry_point").isEmpty(), "must not be registered");
    }

    // ── requirements that cannot be installed ──

    /**
     * python3, except that no venv can be made and pip installs nothing -- a host without
     * python3-venv, and an index without the package the skill asked for. Each says why.
     */
    private Path pythonThatInstallsNothing() throws IOException {
        Path py = root.resolve("python-installs-nothing.sh");
        Files.writeString(py, String.join("\n",
                "#!/bin/sh",
                "if [ \"$1\" = \"-m\" ] && [ \"$2\" = \"venv\" ]; then",
                "  echo 'Error: ensurepip is not available (VENV-REASON)'; exit 1",
                "fi",
                "if [ \"$1\" = \"-m\" ] && [ \"$2\" = \"pip\" ] && [ \"$3\" = \"install\" ]; then",
                "  echo 'ERROR: No matching distribution found for yamlx (PIP-REASON)'; exit 1",
                "fi",
                "if [ \"$1\" = \"-m\" ] && [ \"$2\" = \"pip\" ]; then echo 'pip 25.0'; exit 0; fi",
                "exec python3 \"$@\"",
                ""));
        Files.setPosixFilePermissions(py, PosixFilePermissions.fromString("rwx------"));
        return py;
    }

    private PythonEnvironmentService envThatInstallsNothing() throws IOException {
        OwnClawConfig cfg = new OwnClawConfig();
        cfg.getSkills().setGeneratedPath(generated.toString());
        cfg.getSandbox().setPythonPath(pythonThatInstallsNothing().toString());
        var env = new PythonEnvironmentService(cfg);
        env.init();
        return env;
    }

    @Test
    @DisplayName("requirements that cannot be installed: the new skill's error says why, in pip's words")
    void aNewSkillSaysWhyItsRequirementsAreMissing() throws IOException {
        var env = envThatInstallsNothing();
        OwnClawConfig cfg = new OwnClawConfig();
        cfg.getSkills().setGeneratedPath(generated.toString());
        var sandbox = new ProcessSandbox(env, new ObjectMapper());
        var reg = new ToolRegistry(List.of());
        var mgr = new SkillManager(new DynamicSkillRegistry(cfg, reg, sandbox, null, env, null, null),
                reg, cfg, sandbox, env, null);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("name", "yaml_reader");
        p.put("description", "Reads a YAML file.");
        p.put("parameters", PARAMS);
        p.put("code", "import yamlx\ndef run(params):\n    return {}\n");
        p.put("requirements", "yamlx");

        String result = mgr.createSkill(p);

        assertTrue(result.startsWith("ERROR"), result);
        assertTrue(result.contains("No module named 'yamlx'"), result);
        assertTrue(result.contains("No matching distribution found for yamlx (PIP-REASON)"),
                "a wrong name, a wheel that needs a library and an index out of reach all read "
                        + "\"No module named\" -- only pip says which: " + result);
    }

    @Test
    @DisplayName("a run without its requirements fails with pip's reason, its self-heal's included")
    void aRunSaysWhyItsRequirementsAreMissing() throws Exception {
        var env = envThatInstallsNothing();
        Path dir = Files.createDirectories(generated.resolve("yaml_runner"));
        Files.writeString(dir.resolve("skill.py"), "import yamlx\ndef run(params):\n    return {}\n");
        Files.writeString(dir.resolve("requirements.txt"), "yamlx\n");
        var skill = new DynamicSkill("yaml_runner", "reads yaml", Map.of(), dir, false, false, 30,
                new ProcessSandbox(env, new ObjectMapper()), env, List.of(), null, List.of(), null,
                null, null);

        var r = skill.execute(Map.of(), new ToolExecutionContext("u1", "t1", null, () -> false,
                null, List.of()));

        assertFalse(r.success());
        assertTrue(r.output().startsWith("Skill error: No module named 'yamlx'"), r.output());
        assertTrue(r.output().contains("[installing yamlx failed: Failed to create venv: "
                + "Error: ensurepip is not available (VENV-REASON)"), r.output());
        assertTrue(r.output().contains("[requirements not installed: Failed to create venv: "), r.output());
        assertTrue(r.output().contains("No matching distribution found for yamlx (PIP-REASON)"), r.output());
    }
}
