package com.ownclaw.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownclaw.agent.tools.DynamicSkillRegistry;
import com.ownclaw.agent.tools.SkillLocks;
import com.ownclaw.agent.tools.ToolRegistry;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.conversation.MigratedDatabase;
import com.ownclaw.observability.EventLogService;
import com.ownclaw.sandbox.ProcessSandbox;
import com.ownclaw.skills.PythonEnvironmentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Skills shared by tasks of different chats running side by side: a change of a skill waits for
 * its runs, a retirement skips one that runs, and a reload never leaves a skill missing for the
 * tasks looking it up while it re-reads the directory.
 */
@Timeout(60)
class ConcurrentSkillsTest {

    @TempDir Path root;
    ToolRegistry registry;
    DynamicSkillRegistry dynamic;
    SkillManager manager;

    @BeforeEach
    void setUp() throws IOException {
        Path generated = Files.createDirectories(root.resolve("generated"));
        OwnClawConfig cfg = new OwnClawConfig();
        cfg.getSkills().setGeneratedPath(generated.toString());
        cfg.getSandbox().setPythonPath("python3");
        PythonEnvironmentService pyEnv = new PythonEnvironmentService(cfg);
        pyEnv.init();
        ProcessSandbox sandbox = new ProcessSandbox(pyEnv, new ObjectMapper());
        registry = new ToolRegistry(List.of());
        dynamic = new DynamicSkillRegistry(cfg, registry, sandbox, null, pyEnv, null, null);
        manager = new SkillManager(dynamic, registry, cfg, sandbox, pyEnv, null, null);
    }

    private static Map<String, Object> skill(String name) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("name", name);
        p.put("description", "Says hello.");
        p.put("parameters", "{\"name\": {\"type\": \"string\", \"description\": \"who\"}}");
        p.put("code", "def run(params):\n    return {'greeting': 'hello'}\n");
        return p;
    }

    private String create(String name) {
        return manager.createSkill(skill(name));
    }

    @Test
    @DisplayName("a skill_create or a delete of a skill waits for its run under way, and a retirement skips it")
    void changesWaitForRuns() throws Exception {
        assertTrue(create("greeter").contains("registered"));
        var running = SkillLocks.of("greeter").readLock();
        running.lock();   // a run of it, under way
        CompletableFuture<String> recreate = CompletableFuture.supplyAsync(() -> create("greeter"));
        CompletableFuture<String> delete;
        try {
            assertThrows(TimeoutException.class, () -> recreate.get(500, TimeUnit.MILLISECONDS),
                    "it rewrote the files of a skill that was running");
            assertTrue(dynamic.retire("greeter", "unused").isEmpty(), "it moved a skill that was running");
            delete = CompletableFuture.supplyAsync(() -> manager.deleteSkill("greeter"));
            assertThrows(TimeoutException.class, () -> delete.get(300, TimeUnit.MILLISECONDS),
                    "it deleted a skill that was running");
        } finally {
            running.unlock();
        }
        assertTrue(recreate.get(30, TimeUnit.SECONDS).contains("registered"));
        delete.get(30, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("a run of a skill waits for a skill_create of it under way")
    void runsWaitForAChange() throws Exception {
        assertTrue(create("greeter").contains("registered"));
        var changing = SkillLocks.of("greeter").writeLock();
        changing.lock();
        CompletableFuture<Boolean> run;
        try {
            run = CompletableFuture.supplyAsync(() -> dynamic.getDynamic("greeter").orElseThrow()
                    .execute(Map.of(), new com.ownclaw.agent.tools.ToolExecutionContext("u1", "t1", null, () -> false))
                    .success());
            assertThrows(TimeoutException.class, () -> run.get(500, TimeUnit.MILLISECONDS),
                    "it ran a skill whose files were being written");
        } finally {
            changing.unlock();
        }
        assertTrue(run.get(30, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("a reload never leaves a skill on disk missing for a task looking it up meanwhile")
    void aReloadLeavesNoGap() throws Exception {
        assertTrue(create("greeter").contains("registered"));
        var stop = new AtomicBoolean();
        var missing = new AtomicInteger();
        var lookups = new AtomicInteger();
        var looking = CompletableFuture.runAsync(() -> {
            while (!stop.get()) {
                if (registry.find("greeter").isEmpty()) missing.incrementAndGet();
                lookups.incrementAndGet();
            }
        });
        for (int i = 0; i < 30; i++) dynamic.reload();
        stop.set(true);
        looking.get(5, TimeUnit.SECONDS);
        assertTrue(lookups.get() > 100, "the look-ups ran: " + lookups);
        assertEquals(0, missing.get(), "a skill on disk was missing during a reload");
        assertTrue(registry.find("greeter").isPresent());
    }

    @Test
    @DisplayName("a run without a task id is given no tokens: the user's newest task is another chat's")
    void noTaskIdNoTokens(@TempDir Path tmp) throws Exception {
        var events = new EventLogService(MigratedDatabase.at(tmp.resolve("t.db")));
        events.log("u1", "other-chat-task", "task_completed", "INFO", "done",
                "{\"cloudTokens\": 41000, \"localTokens\": 7}", 0);
        assertArrayEquals(new long[]{0, 0}, events.completedTaskTokens("u1", null));
        assertArrayEquals(new long[]{41_000, 7}, events.completedTaskTokens("u1", "other-chat-task"));
    }

    @Test
    @DisplayName("a task waiting for another's run of a skill it changes is said to wait, and its Stop ends the wait with nothing written")
    void aWaitIsSaidAndStopped() throws Exception {
        assertTrue(create("greeter").contains("registered"));
        var running = SkillLocks.of("greeter").readLock();
        running.lock();   // another task's run of it, under way
        var said = new AtomicInteger();
        var stopped = new AtomicBoolean();
        try {
            CompletableFuture<String> recreate = CompletableFuture.supplyAsync(
                    () -> manager.createSkill(skill("greeter"), null, stopped::get, said::incrementAndGet));
            for (int i = 0; i < 100 && said.get() == 0; i++) Thread.sleep(50);
            assertEquals(1, said.get(), "said once it waits, which the stall watchdog counts as progress");
            stopped.set(true);
            String result = recreate.get(5, TimeUnit.SECONDS);
            assertTrue(result.startsWith("ERROR: Stopped while a run of skill 'greeter' was under way"), result);
        } finally {
            running.unlock();
        }

        var changing = SkillLocks.of("greeter").writeLock();
        changing.lock();   // another task's skill_create of it, under way
        var progress = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var runStopped = new AtomicBoolean();
        try {
            CompletableFuture<com.ownclaw.agent.tools.ToolResult> run = CompletableFuture.supplyAsync(
                    () -> dynamic.getDynamic("greeter").orElseThrow().execute(Map.of(),
                            new com.ownclaw.agent.tools.ToolExecutionContext("u1", "t1", null, runStopped::get,
                                    (message, percent) -> progress.add(message))));
            for (int i = 0; i < 100 && progress.isEmpty(); i++) Thread.sleep(50);
            assertEquals(List.of("Waiting: skill 'greeter' is being changed by another task"), progress);
            runStopped.set(true);
            assertFalse(run.get(5, TimeUnit.SECONDS).success(), "a Stop ends the run's wait too");
        } finally {
            changing.unlock();
        }
    }

    @Test
    @DisplayName("a reload leaves a skill a task is writing as it is registered: it is neither read half written nor quarantined")
    void aReloadLeavesASkillBeingWritten() throws Exception {
        assertTrue(create("greeter").contains("registered"));
        Path dir = root.resolve("generated").resolve("greeter");
        var changing = SkillLocks.of("greeter").writeLock();
        CompletableFuture<Void> reload;
        changing.lock();   // a skill_create writing it now, its manifest half written
        try {
            Files.writeString(dir.resolve("SKILL.yaml"), "name: greeter\nparameters: [unclosed");
            reload = CompletableFuture.runAsync(dynamic::reload);
            reload.get(10, TimeUnit.SECONDS);
            assertTrue(Files.isDirectory(dir), "quarantined from under the skill_create writing it");
            assertTrue(registry.find("greeter").isPresent(), "unregistered while it was written");
        } finally {
            changing.unlock();
        }
    }
}
