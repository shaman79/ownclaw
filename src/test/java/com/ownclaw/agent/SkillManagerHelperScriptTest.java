package com.ownclaw.agent;

import com.ownclaw.agent.tools.DynamicSkillRegistry;
import com.ownclaw.agent.tools.ToolExecutionContext;
import com.ownclaw.agent.tools.ToolRegistry;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.sandbox.JailTestKit;
import com.ownclaw.sandbox.ProcessSandbox;
import com.ownclaw.sandbox.SandboxManager;
import com.ownclaw.sandbox.SandboxResult;
import com.ownclaw.sandbox.SkillJail;
import com.ownclaw.skills.PythonEnvironmentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The scripts the JVM writes into a skill's own directory -- the load check, the runner -- and
 * what SkillManager does when the sandbox cannot run them. The directory is the skill's, and
 * writable by it: a link it left at a name the JVM writes to must not become a write through.
 */
class SkillManagerHelperScriptTest {

    @TempDir Path root;

    private Path generated;
    private OwnClawConfig cfg;

    private static final String PARAMS = "{\"name\": {\"type\": \"string\"}}";
    private static final String CODE = "def run(params):\n    return {'output': 'hello'}\n";

    @BeforeEach
    void setUp() throws IOException {
        generated = Files.createDirectories(root.resolve("generated"));
        cfg = new OwnClawConfig();
        cfg.getSkills().setGeneratedPath(generated.toString());
        cfg.getSandbox().setPythonPath("python3");
    }

    private record Manager(SkillManager skills, ToolRegistry tools, DynamicSkillRegistry dynamic) {}

    private Manager manager(SandboxManager sandbox, PythonEnvironmentService env) {
        var tools = new ToolRegistry(List.of());
        var dynamic = new DynamicSkillRegistry(cfg, tools, sandbox, null, env, null, null);
        return new Manager(new SkillManager(dynamic, tools, cfg, sandbox, env, null), tools, dynamic);
    }

    private static Map<String, Object> skill(String name, String code) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("name", name);
        p.put("description", "Says hello.");
        p.put("parameters", PARAMS);
        p.put("code", code);
        return p;
    }

    private static List<String> names(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    /** Answers the syntax check with success, and records what the load check was given. */
    static final class Recording implements SandboxManager {
        final List<Path> scripts = new ArrayList<>();
        final List<List<Path>> readOnly = new ArrayList<>();
        private final SandboxResult loadCheck;

        Recording(SandboxResult loadCheck) {
            this.loadCheck = loadCheck;
        }

        @Override
        public SandboxResult execute(String python, Path script, Path dir, String in, Map<String, String> env,
                                     int timeout, ProgressCallback cb, List<Path> ro) {
            // Anything but the load check -- the syntax check before it -- passes.
            if (!script.getFileName().toString().startsWith("_load_check_")) {
                return new SandboxResult(0, "", "", 1, false);
            }
            scripts.add(script);
            readOnly.add(List.copyOf(ro));
            assertTrue(Files.isRegularFile(script), "the script exists while it runs");
            return loadCheck;
        }

        @Override
        public Optional<String> unavailable() {
            return Optional.empty();
        }
    }

    @Test
    @DisplayName("with every launch refused, createSkill writes, restores and quarantines nothing")
    void aRefusingSandboxTouchesNothing() throws Exception {
        // Required isolation on a host without bubblewrap: every launch is refused.
        var layout = new SkillJail.Layout(root, root.resolve("data/ownclaw.db"), generated, null);
        var jail = new SkillJail("required", root.resolve("no/bwrap").toString(), layout, List.of(),
                System::currentTimeMillis);
        assertEquals(SkillJail.State.REFUSING, jail.status().state());
        var env = new PythonEnvironmentService(cfg, jail);
        var m = manager(new ProcessSandbox(jail), env);

        Path existing = Files.createDirectories(generated.resolve("greet_user"));
        Files.writeString(existing.resolve("skill.py"), "# the working version\n");
        Files.writeString(existing.resolve("SKILL.yaml"), "name: greet_user\n");

        String created = m.skills().createSkill(skill("new_skill", CODE));
        String updated = m.skills().createSkill(skill("greet_user", "import missing_xyz\n" + CODE));

        for (String result : List.of(created, updated)) {
            assertTrue(result.startsWith("ERROR: isolation required but unavailable"), result);
        }
        assertEquals(List.of("greet_user"), names(generated), "nothing new under generated/");
        assertEquals(List.of("SKILL.yaml", "skill.py"), names(existing), "the existing skill was touched");
        assertEquals("# the working version\n", Files.readString(existing.resolve("skill.py")));
        assertFalse(Files.exists(root.resolve("quarantine")), "something was quarantined over an outage");
        var s = jail.status();
        assertEquals(0, s.isolatedRuns() + s.directRuns() + s.refusedRuns(), "a launch was even attempted");
    }

    @Test
    @DisplayName("a load check the jail refuses fails the skill: not registered unverified")
    void aRefusedLoadCheckFailsClosed() throws Exception {
        var refused = SandboxResult.refused("could not isolate this launch: test refusal");
        var m = manager(new Recording(refused), new PythonEnvironmentService(cfg, JailTestKit.off()));

        String result = m.skills().createSkill(skill("greet_user", CODE));

        assertTrue(result.startsWith("ERROR"), result);
        assertTrue(result.contains("test refusal"), result);
        assertTrue(m.tools().find("greet_user").isEmpty(), "registered although its check never ran");
        assertFalse(Files.exists(generated.resolve("greet_user")), "left in generated/ for the next boot to register");
    }

    @Test
    @DisplayName("the load check reads the skill's environment read-only, under a fresh name each time")
    void theLoadCheckGetsTheEnvironmentAndAFreshName() throws Exception {
        Path envDir = Files.createDirectories(root.resolve("_envs/greet_user"));
        var env = new PythonEnvironmentService(cfg, JailTestKit.off()) {
            @Override
            public PythonResolution resolveExecution(Path skillDir, String skillName) {
                return new PythonResolution("python3", Map.of(), envDir);
            }
        };
        var sandbox = new Recording(new SandboxResult(0, "", "", 1, false));
        var m = manager(sandbox, env);

        assertFalse(m.skills().createSkill(skill("greet_user", CODE)).startsWith("ERROR"));
        assertFalse(m.skills().createSkill(skill("greet_user", CODE)).startsWith("ERROR"));

        assertEquals(List.of(List.of(envDir), List.of(envDir)), sandbox.readOnly);
        assertEquals(2, sandbox.scripts.stream().distinct().count(), "the same name twice: " + sandbox.scripts);
        for (Path script : sandbox.scripts) {
            assertTrue(script.getFileName().toString().matches("_load_check_[0-9a-f]{32}\\.py"), script.toString());
            assertFalse(Files.exists(script), "the check script was left behind");
        }
    }

    @Test
    @DisplayName("links planted at the old load-check name and at runner-like names are never written through")
    void plantedLinksAreNotWrittenThrough() throws Exception {
        assumeTrue(pythonAvailable(), "python3 is not available");
        Path canary = Files.writeString(root.resolve("canary.txt"), "canary");
        Path dir = Files.createDirectories(generated.resolve("greet_user"));
        List<String> planted = List.of("_load_check.py", "_runner_.py", "_runner_0_0.py");
        for (String name : planted) Files.createSymbolicLink(dir.resolve(name), canary);

        var env = new PythonEnvironmentService(cfg, JailTestKit.off());
        env.init();
        var sandbox = new ProcessSandbox(JailTestKit.off());
        var m = manager(sandbox, env);

        String created = m.skills().createSkill(skill("greet_user", CODE));
        assertFalse(created.startsWith("ERROR"), created);
        var run = m.tools().find("greet_user").orElseThrow()
                .execute(Map.of(), new ToolExecutionContext("u1", "t1", null, () -> false));
        assertTrue(run.success(), run.output());
        assertEquals("hello", run.output());

        assertEquals("canary", Files.readString(root.resolve("canary.txt")), "a JVM write went through a link");
        for (String name : planted) assertTrue(Files.isSymbolicLink(dir.resolve(name)));
        assertEquals(List.of("SKILL.yaml", "_load_check.py", "_runner_.py", "_runner_0_0.py", "requirements.txt",
                "skill.py"), names(dir), "a helper script was left behind");
    }

    private static boolean pythonAvailable() {
        try {
            return new ProcessBuilder("python3", "--version").start().waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
