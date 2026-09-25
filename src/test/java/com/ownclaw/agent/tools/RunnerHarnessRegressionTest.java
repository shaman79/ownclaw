package com.ownclaw.agent.tools;

import com.ownclaw.agent.SkillManager;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.sandbox.JailTestKit;
import com.ownclaw.sandbox.ProcessSandbox;
import com.ownclaw.sandbox.SandboxManager;
import com.ownclaw.sandbox.SandboxResult;
import com.ownclaw.skills.PythonEnvironmentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * What runs is skill.py as its source says, not a {@code __pycache__/skill.*.pyc} left in the
 * skill's writable directory -- and the load check judges the same source the run executes. The
 * plant is first shown to work on plain Python -- otherwise "it did not change what ran" would
 * prove nothing -- and then run through the real harness. Apart from that the skill loads as it
 * always did: a module importlib made, its own directory first on sys.path. The result is read
 * from the line the runner prints last.
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

    /** The whole of the last run's stdout, as the sandbox returned it. */
    private String lastStdout;

    private String runSkill() {
        var cfg = new OwnClawConfig();
        cfg.getSandbox().setPythonPath("python3");
        var env = new PythonEnvironmentService(cfg, JailTestKit.off());
        var real = new ProcessSandbox(JailTestKit.off());
        SandboxManager recording = new SandboxManager() {
            @Override
            public SandboxResult execute(String python, Path script, Path dir, String in, Map<String, String> vars,
                                         int timeout, ProgressCallback cb, List<Path> ro) {
                SandboxResult r = real.execute(python, script, dir, in, vars, timeout, cb, ro);
                lastStdout = r.stdout();
                return r;
            }

            @Override
            public Optional<String> unavailable() {
                return real.unavailable();
            }
        };
        var skill = new DynamicSkill("harness_probe", "probe", Map.of(), skillDir, false, false, 30,
                recording, env, List.of(), null, List.of(), null, null, null);
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
    @DisplayName("skill.py written back in the same second at the same size runs as its source says, not as the stale pyc")
    void aStalePycOfTheSameSecondAndSizeDoesNotRun() throws Exception {
        // What a failed update and the restore of the previous version would leave: a pyc of one
        // version, and a source of the same size and whole-second mtime that says something else.
        Path code = skillDir.resolve("skill.py");
        Files.writeString(code, "def run(params):\n    return {'output': 'v1'}\n");
        Ran compiled = python("-c", "import importlib.util, py_compile, sys; py_compile.compile(sys.argv[1], "
                + "cfile=importlib.util.cache_from_source(sys.argv[1]), doraise=True, "
                + "invalidation_mode=py_compile.PycInvalidationMode.TIMESTAMP)", code.toString());
        assertEquals(0, compiled.exit, compiled.out);
        FileTime mtime = Files.getLastModifiedTime(code);
        Files.writeString(code, "def run(params):\n    return {'output': 'v2'}\n");
        Files.setLastModifiedTime(code, mtime);

        Ran control = python("-c", "import importlib.util, sys; s = importlib.util.spec_from_file_location('skill', "
                + "sys.argv[1]); m = importlib.util.module_from_spec(s); s.loader.exec_module(m); "
                + "print(m.run({})['output'])", code.toString());
        assertEquals("v1", control.out, "the stale pyc must fool the spec's loader, or this test proves nothing");

        assertEquals("v2", runSkill());
    }

    @Test
    @DisplayName("the load check judges skill.py's source, not a planted __pycache__/skill.*.pyc")
    void theLoadCheckIgnoresAPlantedPyc() throws Exception {
        var skills = skillManager();
        String working = "def run(params):\n    return {'output': 'working'}\n";
        assertFalse(skills.createSkill(skill("load_probe", working)).startsWith("ERROR"));
        Path code = tmp.resolve("generated/load_probe/skill.py");
        plantPyc(code);

        // Its source does not import; the pyc beside it would.
        String result = skills.createSkill(skill("load_probe", "import missing_package_xyz\n" + working));

        assertTrue(result.startsWith("ERROR") && result.contains("does not load"), result);
        assertEquals(working, Files.readString(code), "the previous version was not kept");
    }

    private SkillManager skillManager() {
        var cfg = new OwnClawConfig();
        cfg.getSkills().setGeneratedPath(tmp.resolve("generated").toString());
        cfg.getSandbox().setPythonPath("python3");
        var env = new PythonEnvironmentService(cfg, JailTestKit.off());
        var sandbox = new ProcessSandbox(JailTestKit.off());
        var tools = new ToolRegistry(List.of());
        return new SkillManager(new DynamicSkillRegistry(cfg, tools, sandbox, null, env, null, null),
                tools, cfg, sandbox, env, null);
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
    @DisplayName("the skill loads as it always did: __spec__ and importlib.util are there, its own modules come first")
    void theSkillLoadsAsItAlwaysDid() throws Exception {
        // A sibling named like a library module is what the skill imports, as it was before the
        // harness compiled skill.py itself.
        Files.writeString(skillDir.resolve("email.py"), "X = 'sibling'\n");
        String code = """
                import email, importlib, os
                ORIGIN = os.path.basename(__spec__.origin)
                def run(params):
                    return {'output': '|'.join([__spec__.name, ORIGIN, type(__loader__).__name__,
                            str(importlib.util.find_spec('json') is not None), email.X])}
                """;
        // The load check makes the module the same way, so reading __spec__ on import passes it.
        String created = skillManager().createSkill(skill("harness_probe", code));
        assertFalse(created.startsWith("ERROR"), created);
        assertEquals("skill|skill.py|SourceFileLoader|True|sibling", runSkill());
    }

    @Test
    @DisplayName("a skill may start worker processes: a worker that imports the runner again does not run a skill of its own")
    void aSpawnedWorkerDoesNotRunTheRunner() throws Exception {
        // spawn, as forkserver -- the default from Python 3.14 -- also does: each worker imports
        // the main script again, as __mp_main__, before it takes work.
        Files.writeString(skillDir.resolve("skill.py"), """
                import multiprocessing
                def run(params):
                    with multiprocessing.get_context('spawn').Pool(2) as pool:
                        return {'output': 'pool=' + str(pool.map(abs, [-1, -2]))}
                """);
        assertEquals("pool=[1, 2]", runSkill());
    }

    @Test
    @DisplayName("a JSON line a subprocess of the skill prints ahead of the result is not taken for it")
    void aSubprocessJsonLineIsNotTheResult() throws Exception {
        // The child writes to the runner's own stdout, which the runner does not capture.
        Files.writeString(skillDir.resolve("skill.py"), """
                import subprocess, sys
                def run(params):
                    subprocess.run([sys.executable, '-c', 'print(\\'{"status": "ok"}\\')'], check=True)
                    return {'output': 'the real result'}
                """);
        assertEquals("the real result", runSkill());
        assertTrue(lastStdout.startsWith("{\"status\": \"ok\"}"), "the child printed nothing first: " + lastStdout);
    }
}
