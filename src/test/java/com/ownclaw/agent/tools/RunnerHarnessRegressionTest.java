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
 * prove nothing -- and then run through the real harness. The same for a module a run leaves in
 * the directory: nothing there is imported but skill.py. Apart from that the skill loads as a
 * module importlib made. What the harness gives it besides is here too: report_progress while
 * it loads, check and run alike, its own functions for worker processes, a module it misses
 * reported in the runner's own key, and a result read from the line the runner prints last.
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
                def run(params):
                    return {'output': 'source|' + json.dumps([1])}
                """);
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

    /** Every install the self-heal asked for, in order. */
    private final List<List<String>> installs = new ArrayList<>();

    private String runSkill() {
        ToolResult result = run();
        assertTrue(result.success(), result.output());
        return result.output();
    }

    private ToolResult run() {
        var cfg = new OwnClawConfig();
        cfg.getSandbox().setPythonPath("python3");
        var env = new PythonEnvironmentService(cfg, JailTestKit.off()) {
            @Override
            public void installPackages(Path dir, String name, List<String> packages,
                                        java.util.function.BooleanSupplier stopped, Runnable waiting) throws java.io.IOException {
                installs.add(packages);
                throw new java.io.IOException("not installed here: no index is reached from a test");
            }
        };
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
        return skill.execute(Map.of(), new ToolExecutionContext("u1", "t1", null, () -> false));
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
        assertEquals("source|[1]", runSkill());
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
                tools, cfg, sandbox, env, null, null);
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
    @DisplayName("the skill loads as a module importlib made -- __spec__, a source loader, importlib.util -- and a sibling named like a library module is not what it gets")
    void theSkillLoadsAsAModuleAndNoSiblingIsImported() throws Exception {
        Files.writeString(skillDir.resolve("email.py"), "X = 'sibling'\n");
        String code = """
                import email, importlib, os
                ORIGIN = os.path.basename(__spec__.origin)
                def run(params):
                    return {'output': '|'.join([__spec__.name, ORIGIN, str(isinstance(__loader__,
                            importlib.machinery.SourceFileLoader)), str(importlib.util.find_spec('json') is not None),
                            str(hasattr(email, 'X')), os.path.basename(os.path.dirname(email.__file__))])}
                """;
        // The load check makes the module the same way, so reading __spec__ on import passes it.
        String created = skillManager().createSkill(skill("harness_probe", code));
        assertFalse(created.startsWith("ERROR"), created);
        assertEquals("skill|skill.py|True|True|False|email", runSkill());
        // Mutation: the skill's directory left first on sys.path, as it was -> email is the sibling, with X.
    }

    @Test
    @DisplayName("a module a run leaves in the skill's directory is not imported by the next run: not by the runner, which imports json before skill.py does, and not by skill.py")
    void aModuleLeftInTheDirectoryIsNotImported() throws Exception {
        // What a run can leave: the directory is the skill's own, and writable by it.
        Files.writeString(skillDir.resolve("json.py"), "raise SystemExit('planted json ran')\n");
        Files.writeString(skillDir.resolve("requests.py"), "raise SystemExit('planted requests ran')\n");
        Files.writeString(skillDir.resolve("helper_mod.py"), "raise SystemExit('planted helper ran')\n");
        Path probe = Files.writeString(skillDir.resolve("_probe.py"), "import json\n");
        Ran control = python(probe.toString());
        assertTrue(control.out.contains("planted json ran"), "the plant must work on plain Python, or this test proves nothing: "
                + control.out);
        Files.delete(probe);

        Files.writeString(skillDir.resolve("skill.py"), """
                import json
                def run(params):
                    got = []
                    for name in ('requests', 'helper_mod'):
                        try:
                            got.append(name + ' from ' + __import__(name).__file__)
                        except ModuleNotFoundError:
                            got.append('no ' + name)
                    return {'output': json.dumps([1]) + '|' + '|'.join(got)}
                """);
        String out = runSkill();

        assertTrue(out.startsWith("[1]|"), out);
        assertFalse(out.contains(skillDir.getFileName().toString()), "a module came from the skill's directory: " + out);
        assertTrue(out.endsWith("|no helper_mod"), out);
        // Mutation: the skill's directory left on sys.path -> the runner's import json is the plant,
        // and the run ends with "planted json ran".
    }

    @Test
    @DisplayName("nor by the Python processes a run starts: a -c subprocess and a spawn worker would each put the working directory -- the skill's -- first")
    void nothingLeftInTheDirectoryRunsInTheRunsChildren() throws Exception {
        // A json.py for the -c subprocess, a threading.py for the worker's bootstrap: each marks
        // that it ran and ends its process, so a worker it ran in exits with an error.
        Files.writeString(skillDir.resolve("json.py"), "open('planted_ran', 'a').write('json ')\nraise SystemExit('planted json ran')\n");
        Files.writeString(skillDir.resolve("threading.py"), "open('planted_ran', 'a').write('threading ')\nraise SystemExit('planted threading ran')\n");
        Ran control = python("-c", "import json");
        assertTrue(control.out.contains("planted json ran"), "the plant must work on plain Python's -c, or this test proves nothing: "
                + control.out);
        Files.deleteIfExists(skillDir.resolve("planted_ran"));

        Files.writeString(skillDir.resolve("skill.py"), """
                import multiprocessing, subprocess, sys
                def child():
                    pass
                def run(params):
                    sub = subprocess.run([sys.executable, '-c', 'import json; print(json.dumps([1]))'],
                                         capture_output=True, text=True)
                    worker = multiprocessing.get_context('spawn').Process(target=child)
                    worker.start()
                    worker.join(60)
                    return {'output': sub.stdout.strip() + '|' + str(worker.exitcode)}
                """);
        String out = runSkill();

        assertTrue(out.startsWith("[1]|0"), out);
        assertFalse(Files.exists(skillDir.resolve("planted_ran")), "a plant ran in a child of the run");
        // Mutation: PYTHONSAFEPATH not in SkillJail.PYTHON_SETTINGS -> the -c subprocess imports the
        // planted json, the spawn worker the planted threading, and both mark it.
    }

    @Test
    @DisplayName("an uncaught ModuleNotFoundError is reported in the runner's own key, which a skill cannot set in what it returns -- and a module named only through importlib is not installed")
    void aMissingModuleIsReportedInTheRunnersKey() throws Exception {
        Files.writeString(skillDir.resolve("skill.py"), """
                import importlib
                def run(params):
                    importlib.import_module('nowhere_xyz.sub')
                """);
        ToolResult failed = run();

        assertFalse(failed.success());
        String line = lastStdout.strip().lines().reduce((a, b) -> b).orElse("");
        assertTrue(line.contains("\"" + DynamicSkill.MISSING_MODULE + "\": \"nowhere_xyz\""), line);
        assertFalse(failed.output().contains(DynamicSkill.MISSING_MODULE), failed.output());
        assertTrue(failed.output().contains("'nowhere_xyz' is not installed automatically"), failed.output());
        assertEquals(List.of(), installs, "named through importlib, not imported by skill.py: nothing is installed");

        Files.writeString(skillDir.resolve("skill.py"), """
                def run(params):
                    return {'success': False, 'output': 'no', '%s': 'chosen_by_the_skill'}
                """.formatted(DynamicSkill.MISSING_MODULE));
        run();
        assertFalse(lastStdout.contains("chosen_by_the_skill"), lastStdout);
        // Mutations: the runner not setting the key -> nothing names nowhere_xyz; not taking it out of
        // what the skill returned -> chosen_by_the_skill is in the result line.
    }

    @Test
    @DisplayName("a module skill.py imports from its own directory fails the run, and is never installed from an index under that name")
    void aSiblingModuleIsNotInstalled() throws Exception {
        Files.writeString(skillDir.resolve("helper_mod.py"), "VALUE = 'sibling'\n");
        Files.writeString(skillDir.resolve("skill.py"), "import helper_mod\ndef run(params):\n    return {'output': helper_mod.VALUE}\n");

        ToolResult failed = run();

        assertFalse(failed.success(), failed.output());
        assertTrue(failed.output().contains("'helper_mod' is a module in the skill's own directory"), failed.output());
        assertEquals(List.of(), installs);
    }

    @Test
    @DisplayName("a skill may hand its own function to worker processes, and no worker runs the runner again")
    void aSkillsOwnFunctionGoesToWorkers() throws Exception {
        // spawn, as forkserver -- the default from Python 3.14 -- also does: each worker imports
        // the main script again, as __mp_main__, before it takes work, and then skill.py by name
        // to find the function it is handed.
        Files.writeString(skillDir.resolve("skill.py"), """
                import multiprocessing
                def square(x):
                    return x * x
                def run(params):
                    out = []
                    for method in ('spawn', 'forkserver'):
                        with multiprocessing.get_context(method).Pool(2) as pool:
                            out.append(method + '=' + str(pool.map(square, [2, 3])))
                    return {'output': ' '.join(out)}
                """);
        assertEquals("spawn=[4, 9] forkserver=[4, 9]", runSkill());
        assertEquals(1, lastStdout.strip().lines().count(),
                "something besides the result was printed -- a worker ran the runner: " + lastStdout);
    }

    @Test
    @DisplayName("a skill that reports progress while it loads passes the load check, as it runs")
    void progressWhileLoadingPassesTheCheck() throws Exception {
        String code = "report_progress('warming up')\ndef run(params):\n    return {'output': 'loaded'}\n";
        String created = skillManager().createSkill(skill("harness_probe", code));
        assertFalse(created.startsWith("ERROR"), created);
        assertEquals("loaded", runSkill());
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
        // The result is the harness's last line; what the child printed before it is kept beside
        // it, as everything a skill prints is, never taken for it.
        String result = runSkill();
        assertTrue(result.startsWith("the real result"), result);
        assertTrue(result.contains("[skill stdout: {\"status\": \"ok\"}]"), result);
        assertTrue(lastStdout.startsWith("{\"status\": \"ok\"}"), "the child printed nothing first: " + lastStdout);
    }
}
