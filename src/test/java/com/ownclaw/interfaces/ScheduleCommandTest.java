package com.ownclaw.interfaces;

import com.ownclaw.conversation.MigratedDatabase;
import com.ownclaw.core.ScheduledTaskService;
import com.ownclaw.observability.ChatStatusEmitter;
import com.ownclaw.observability.EventLogService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code /schedule in <time> <task>}, through the real command handler and scheduler on the
 * migrated schema: the task kept is everything typed after the time, and the time is a whole
 * expression -- not a number found somewhere in the text, nor a clock time that does not exist.
 */
class ScheduleCommandTest {

    private JdbcTemplate jdbc;
    private ScheduledTaskService scheduler;
    private CommandHandler commands;

    private void start(Path tmp) throws Exception {
        jdbc = MigratedDatabase.at(tmp.resolve("t.db"));
        scheduler = new ScheduledTaskService(jdbc, null, new ChatStatusEmitter(), new EventLogService(jdbc), null, null);
        commands = new CommandHandler(null, null, null, null, null, null, null, scheduler, null, null, null, null,
                null, null, null);
    }

    /** Schedule it, and return what was kept and how far ahead it runs. */
    private void schedules(String command, String task, Duration delay) {
        Instant before = Instant.now();
        String reply = commands.handle("u1", command).orElseThrow();
        assertTrue(reply.startsWith("✅ Task **#"), command + " -> " + reply);
        var row = jdbc.queryForMap("SELECT description, next_run_at FROM scheduled_tasks ORDER BY id DESC LIMIT 1");
        assertEquals(task, row.get("description"), command);
        assertTrue(reply.endsWith("**: " + task), reply);
        Instant runAt = Instant.parse(String.valueOf(row.get("next_run_at")));
        assertFalse(runAt.isBefore(before.plus(delay)) || runAt.isAfter(Instant.now().plus(delay)),
                command + " runs at " + runAt);
    }

    @Test
    @DisplayName("the task is all that follows the time, as typed: the help's own examples kept only their last word, or threw")
    void theTaskIsWhole(@TempDir Path tmp) throws Exception {
        start(tmp);
        schedules("/schedule in 2 hours backup my notes", "backup my notes", Duration.ofHours(2));
        schedules("/schedule in 30 minutes check server status", "check server status", Duration.ofMinutes(30));
        schedules("/schedule in 2 hours check server status", "check server status", Duration.ofHours(2));
        schedules("/schedule in 1 day run backup", "run backup", Duration.ofDays(1));
        schedules("/schedule in 1 minute morning digest MARK-sched", "morning digest MARK-sched", Duration.ofMinutes(1));
        schedules("/schedule in 10 minutes check that the nightly backup of my notes finished",
                "check that the nightly backup of my notes finished", Duration.ofMinutes(10));
        schedules("/schedule in 1 hour summarise my mail:\n- the urgent ones\n- then the rest",
                "summarise my mail:\n- the urgent ones\n- then the rest", Duration.ofHours(1));
        // Mutations: find() back in the duration pattern -> "notes"; the longest prefix without
        // whole matching -> "status"; no catch of the clock time -> "30" throws.
    }

    @Test
    @DisplayName("a time is the whole expression and one that exists: a number in the text, 30 o'clock or 14:75 is none")
    void aTimeIsAWholeExpression(@TempDir Path tmp) throws Exception {
        start(tmp);
        for (String time : new String[]{"in 30 minutes", "2 hours", "1 day", "tomorrow", "tomorrow at 9am", "at 14:30", "9pm"}) {
            assertTrue(scheduler.parseTimeExpression(time).isPresent(), time);
        }
        for (String notATime : new String[]{"2 hours backup", "2 hours backup my", "30", "45", "at 25:00", "14:75",
                "2026-10-02 09:00", "tomorrow at 30", "next tuesday at 3pm", "99999999999 minutes"}) {
            assertTrue(scheduler.parseTimeExpression(notATime).isEmpty(), notATime);
        }
        assertTrue(commands.handle("u1", "/schedule in 45 check the oven").orElseThrow()
                .startsWith("Could not parse time expression."), "said, not thrown");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM scheduled_tasks", Integer.class));
    }
}
