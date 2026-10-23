package com.ownclaw.core;

import com.ownclaw.conversation.MigratedDatabase;
import com.ownclaw.interfaces.CommandHandler;
import com.ownclaw.observability.ChatStatusEmitter;
import com.ownclaw.observability.EventLogService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * No scheduled task's text holds a secret command, wherever the text came from -- the page's
 * edit form, the agent's own schedule actions -- and a row made before that was refused is
 * paused, not run: run, it handed the secret to the model on every run.
 */
class ScheduledSecretTest {

    static final String SECRET_TASK = "check the router (/cred set ROUTER_PASSWORD hunter2-Xq9)";

    @Test
    @DisplayName("a task's text holding a secret command is refused when it is made and when it is edited")
    void refusedWhenMadeOrEdited(@TempDir Path tmp) throws Exception {
        var jdbc = MigratedDatabase.at(tmp.resolve("t.db"));
        var scheduler = new ScheduledTaskService(jdbc, null, new ChatStatusEmitter(), new EventLogService(jdbc), null, null);

        var e = assertThrows(IllegalArgumentException.class,
                () -> scheduler.scheduleDeferred("u1", SECRET_TASK, Instant.now().plusSeconds(3600)));
        assertEquals(CommandHandler.MISPLACED_SECRET_ANSWER, e.getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> scheduler.scheduleRecurring("u1", "/cred set K hunter2-Xq9", "0 0 3 * * *", null));

        long id = scheduler.scheduleDeferred("u1", "check the router", Instant.now().plusSeconds(3600));
        assertThrows(IllegalArgumentException.class, () -> scheduler.updateTask("u1", id, SECRET_TASK, null));
        assertEquals("check the router", jdbc.queryForObject(
                "SELECT description FROM scheduled_tasks WHERE id = ?", String.class, id));
        assertFalse(jdbc.queryForList("SELECT description FROM scheduled_tasks", String.class).toString()
                .contains("hunter2"));
    }

    @Test
    @DisplayName("a row already holding one is paused when it falls due, the owner told which by number, and nothing runs")
    void anOldRowIsPaused(@TempDir Path tmp) throws Exception {
        var jdbc = MigratedDatabase.at(tmp.resolve("t.db"));
        var emitter = new ChatStatusEmitter();
        var seen = new ArrayList<ChatStatusEmitter.StatusMessage>();
        emitter.subscribe("u1", "test", seen::add);
        // No task queue: a task handed on fails the test at taskQueue.submit.
        var scheduler = new ScheduledTaskService(jdbc, null, emitter, new EventLogService(jdbc), null, null);
        jdbc.update("INSERT INTO scheduled_tasks (user_id, task_type, description, next_run_at, status) "
                + "VALUES ('u1', 'deferred', ?, ?, 'active')", SECRET_TASK, Instant.now().minusSeconds(60).toString());

        scheduler.pollDueTasks();

        assertEquals("paused", jdbc.queryForObject("SELECT status FROM scheduled_tasks", String.class));
        assertEquals(1, seen.size(), String.valueOf(seen));
        assertTrue(seen.getFirst().text().contains("was paused, not run"), seen.getFirst().text());
        assertFalse(seen.getFirst().text().contains("hunter2"), seen.getFirst().text());
    }
}
