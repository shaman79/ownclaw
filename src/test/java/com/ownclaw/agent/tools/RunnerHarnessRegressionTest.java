package com.ownclaw.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownclaw.agent.SkillManager;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.sandbox.ProcessSandbox;
import com.ownclaw.skills.PythonEnvironmentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * What runs is skill.py as its source says, with the standard library as Python ships it: not a
 * {@code __pycache__/skill.*.pyc} left in the skill's writable directory, and not a module named
 * like the library's ({@code json.py}, {@code csv.py}) left beside it -- and the load check judges
 * the same source the run executes. Each plant is first shown to work on plain Python -- otherwise
 * "it did not change what ran" would prove nothing -- and then run through the real harness.
 */
class RunnerHarnessRegressionTest {

    @TempDir Path tmp;

    private Path skillDir;

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(pythonAvailable(), "python3 is not available");
        skillDir = Files.createDirectories(tmp.resolve("generated/harness_probe"));
        Files.writeString(skillDir.resolve("skill.py"), """
                import json
                import helper_mod
                def run(params):
                    return {'output': 'source|' + json.dumps([1]) + '|' + helper_mod.VALUE}
                """);
        Files.writeString(skillDir.resolve("helper_mod.py"), "VALUE = 'sibling ok'\n");
    }

    private record Ran(int exit, String out) {}

    private boolean pythonAvailable() {
        try {
            return python("--version").exit == 0;
        } catch (Exception | AssertionError e) {
            return false;
        }
    }

    private Ran python(String... args) throws Exception {
        var cmd = new ArrayList<String>();
        cmd.add("python3");
        cmd.addAll(List.of(args));
        var pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        if (skillDir != null) pb.directory(skillDir.toFile());
        Process p = pb.start();
        p.getOutputStream().close();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        assertTrue(p.waitFor(30, TimeUnit.SECONDS));
        return new Ran(p.exitValue(), out);
    }

    private String runSkill() {
        var cfg = new OwnClawConfig();
        cfg.getSandbox().setPythonPath("python3");
        var env = new PythonEnvironmentService(cfg);
        var skill = new DynamicSkill("harness_probe", "probe", Map.of(), skillDir, false, false, 30,
                new ProcessSandbox(env, new ObjectMapper()), env, List.of(), null, List.of(), null, null, null);
        var result = skill.execute(Map.of(), new ToolExecutionContext("u1", "t1", null, () -> false));
        assertTrue(result.success(), result.output());
        return result.output();
    }

    /**
     * Leave a pyc of other code where importlib looks for {@code skillPy}'s, and show it fools an
     * importlib load. An unchecked-hash pyc: importlib uses it without looking at the source at all.
     */
    private void plantPyc(Path skillPy) throws Exception {
        Path evil = Files.writeString(tmp.resolve("evil.py"), "def run(params):\n    return {'output': 'from pyc'}\n");
        Ran planted = python("-c", "import importlib.util, py_compile, sys; py_compile.compile(sys.argv[1], "
                + "cfile=importlib.util.cache_from_source(sys.argv[2]), doraise=True, "
                + "invalidation_mode=py_compile.PycInvalidationMode.UNCHECKED_HASH)",
                evil.toString(), skillPy.toString());
        assertEquals(0, planted.exit, planted.out);

        Ran control = python("-c", "import importlib.util, sys; s = importlib.util.spec_from_file_location('skill', "
                + "sys.argv[1]); m = importlib.util.module_from_spec(s); s.loader.exec_module(m); "
                + "print(m.run({})['output'])", skillPy.toString());
        assertEquals("from pyc", control.out, "the plant must fool an importlib load, or this test proves nothing");
    }

    @Test
    @DisplayName("a planted __pycache__/skill.*.pyc does not replace skill.py")
    void aPlantedPycDoesNotRun() throws Exception {
        plantPyc(skillDir.resolve("skill.py"));
        assertEquals("source|[1]|sibling ok", runSkill());
    }

    @Test
    @DisplayName("the load check judges skill.py's source, not a planted __pycache__/skill.*.pyc")
    void theLoadCheckIgnoresAPlantedPyc() throws Exception {
        var cfg = new OwnClawConfig();
        cfg.getSkills().setGeneratedPath(tmp.resolve("generated").toString());
        cfg.getSandbox().setPythonPath("python3");
        var env = new PythonEnvironmentService(cfg);
        var sandbox = new ProcessSandbox(env, new ObjectMapper());
        var tools = new ToolRegistry(List.of());
        var skills = new SkillManager(new DynamicSkillRegistry(cfg, tools, sandbox, null, env, null, null),
                tools, cfg, sandbox, env, null);
        String working = "def run(params):\n    return {'output': 'working'}\n";
        assertFalse(skills.createSkill(skill("load_probe", working)).startsWith("ERROR"));
        Path code = tmp.resolve("generated/load_probe/skill.py");
        plantPyc(code);

        // Its source does not import; the pyc beside it would.
        String result = skills.createSkill(skill("load_probe", "import missing_package_xyz\n" + working));

        assertTrue(result.startsWith("ERROR") && result.contains("does not load"), result);
        assertEquals(working, Files.readString(code), "the previous version was not kept");
    }

    private static Map<String, Object> skill(String name, String code) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("name", name);
        p.put("description", "Loads, or does not.");
        p.put("parameters", "{}");
        p.put("code", code);
        return p;
    }

    @Test
    @DisplayName("a json.py or csv.py in the skill directory shadows neither the harness's modules nor the skill's")
    void aShadowModuleDoesNotRun() throws Exception {
        Files.writeString(skillDir.resolve("json.py"), """
                def dumps(*args, **kwargs):
                    return '{"success": true, "output": "shadow json"}'
                def loads(*args, **kwargs):
                    return {}
                """);
        // json is imported by the harness before the skill, and a module is imported once: the
        // skill gets the harness's json whatever sys.path says by then. csv the harness never
        // imports, so only the skill directory's place at the END of sys.path keeps the skill's
        // import of it the library's.
        Files.writeString(skillDir.resolve("csv.py"), "QUOTE_ALL = 'shadow csv'\n");
        Files.writeString(skillDir.resolve("skill.py"), """
                import csv
                import json
                import helper_mod
                def run(params):
                    return {'output': 'source|' + json.dumps([1]) + '|' + helper_mod.VALUE + '|' + str(csv.QUOTE_ALL)}
                """);
        Path probe = Files.writeString(skillDir.resolve("probe_path.py"), "import json\nprint(json.__file__)\n");

        Ran control = python(probe.toString());
        assertEquals(skillDir.resolve("json.py").toRealPath().toString(), Path.of(control.out).toRealPath().toString(),
                "a script's own directory must come first on plain Python, or this test proves nothing");

        assertEquals("source|[1]|sibling ok|1", runSkill(),
                "the standard library's json and csv, and the skill's sibling module still imported");
    }
}
