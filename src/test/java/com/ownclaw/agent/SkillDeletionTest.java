package com.ownclaw.agent;

import com.ownclaw.agent.tools.DynamicSkill;
import com.ownclaw.agent.tools.DynamicSkillRegistry;
import com.ownclaw.agent.tools.ToolExecutionContext;
import com.ownclaw.agent.tools.ToolRegistry;
import com.ownclaw.agent.tools.ToolResult;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.sandbox.JailTestKit;
import com.ownclaw.sandbox.ProcessSandbox;
import com.ownclaw.skills.PythonEnvironmentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Deleting a skill removes a directory -- and environments -- that a run of it writes while it
 * goes on, and the delete opens directories with a chmod that follows a link. So a skill with a
 * run in flight is not deleted, and never leaves the registry on the way to that answer.
 */
class SkillDeletionTest {

    @TempDir Path root;

    private Path generated;
    private Path envs;
    private SkillManager manager;
    private RecordingRegistry dynamic;
    private ToolRegistry tools;
    private ProcessSandbox sandbox;
    private PythonEnvironmentService env;

    /** The registry, noting every unregistration. */
    private static final class RecordingRegistry extends DynamicSkillRegistry {
        final List<String> unregistered = new ArrayList<>();

        RecordingRegistry(OwnClawConfig cfg, ToolRegistry tools, ProcessSandbox sandbox, PythonEnvironmentService env) {
            super(cfg, tools, sandbox, null, env, null, null);
        }

        @Override
        public boolean unregister(String name) {
            unregistered.add(name);
            return super.unregister(name);
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(pythonAvailable(), "python3 is not available");
        generated = Files.createDirectories(root.resolve("skills/generated"));
        envs = root.resolve("skills/_envs");
        var cfg = new OwnClawConfig();
        cfg.getSkills().setGeneratedPath(generated.toString());
        cfg.getDatabase().setPath(root.resolve("data/ownclaw.db").toString());
        cfg.getSandbox().setPythonPath("python3");
        env = new PythonEnvironmentService(cfg, JailTestKit.off());
        env.init();
        sandbox = new ProcessSandbox(JailTestKit.off());
        tools = new ToolRegistry(List.of());
        dynamic = new RecordingRegistry(cfg, tools, sandbox, env);
        manager = new SkillManager(dynamic, tools, cfg, sandbox, env, null);
    }

    private static boolean pythonAvailable() {
        try {
            Process p = new ProcessBuilder("python3", "--version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private void create(String name, String code) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("name", name);
        p.put("description", "Waits, or not.");
        p.put("parameters", "{}");
        p.put("code", code);
        String result = manager.createSkill(p);
        assertFalse(result.startsWith("ERROR"), result);
    }

    @Test
    @DisplayName("a skill with a run in flight is not deleted, and never leaves the registry; once the run ends it is")
    void aRunningSkillIsNotDeleted() throws Exception {
        // Runs until told to stop, from its own directory, as a skill writing a cache would.
        create("slow_skill", """
                import os, time
                def run(params):
                    open('started', 'w').close()
                    while not os.path.exists('release'):
                        time.sleep(0.05)
                    return {'output': 'done'}
                """);
        Path dir = generated.resolve("slow_skill");
        Path envDir = Files.createDirectories(envs.resolve("slow_skill"));
        DynamicSkill skill = dynamic.getDynamic("slow_skill").orElseThrow();
        CompletableFuture<ToolResult> run = CompletableFuture.supplyAsync(
                () -> skill.execute(Map.of(), new ToolExecutionContext("u1", "t1", null, () -> false)));
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (!Files.exists(dir.resolve("started"))) {
                assertTrue(System.nanoTime() < deadline, "the run never started");
                Thread.sleep(20);
            }
            assertEquals(1, skill.inFlight());
            dynamic.unregistered.clear();   // creating it unregisters whatever was there before

            String refused = manager.deleteSkill("slow_skill");

            assertTrue(refused.startsWith("ERROR") && refused.contains("running"), refused);
            assertTrue(Files.exists(dir.resolve("skill.py")), "the directory of a running skill was deleted");
            assertTrue(Files.isDirectory(envDir), "the environment of a running skill was deleted");
            assertTrue(dynamic.isDynamic("slow_skill") && tools.find("slow_skill").isPresent());
            assertEquals(List.of(), dynamic.unregistered, "a running skill left the registry, if only for a moment");
        } finally {
            Files.createFile(dir.resolve("release"));
        }
        assertTrue(run.get(30, TimeUnit.SECONDS).success());

        String deleted = manager.deleteSkill("slow_skill");
        assertFalse(deleted.startsWith("ERROR"), deleted);
        assertFalse(Files.exists(dir));
        assertFalse(Files.exists(envDir));
        assertFalse(dynamic.isDynamic("slow_skill"));
    }

    @Test
    @DisplayName("a run that starts while the skill is being unregistered keeps it: registered again, nothing deleted")
    void aRunStartingDuringTheDeletionKeepsTheSkill() throws Exception {
        create("quick_skill", "def run(params):\n    return {'output': 'ok'}\n");
        DynamicSkill real = dynamic.getDynamic("quick_skill").orElseThrow();
        // The same skill, whose run starts between the check before it is unregistered and the
        // one after.
        DynamicSkill racing = new DynamicSkill(real.name(), real.description(), real.inputSchema(),
                real.skillDir(), false, false, 30, sandbox, env, List.of(), null, List.of(), null, null, null) {
            private int asked;

            @Override
            public int inFlight() {
                return asked++ == 0 ? 0 : 1;
            }
        };
        dynamic.register(racing);

        String refused = manager.deleteSkill("quick_skill");

        assertTrue(refused.startsWith("ERROR") && refused.contains("running"), refused);
        assertSame(racing, dynamic.getDynamic("quick_skill").orElseThrow(), "not registered again");
        assertTrue(tools.find("quick_skill").isPresent());
        assertTrue(Files.exists(real.skillDir().resolve("skill.py")), "deleted under a run that had started");
    }
}
