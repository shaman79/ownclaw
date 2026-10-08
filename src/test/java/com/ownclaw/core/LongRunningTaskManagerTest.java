package com.ownclaw.core;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.conversation.MigratedDatabase;
import com.ownclaw.observability.ChatStatusEmitter;
import com.ownclaw.observability.EventLogService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A long-running skill's description, result and error are kept whole in its row; the owner's
 * status line, the event and the log say where they are and how long, instead of cutting them.
 */
class LongRunningTaskManagerTest {

    @TempDir Path tmp;

    static final String DESCRIPTION = "Scan every host on the home network, list its open ports "
            + "and flag anything that answers on telnet. ".repeat(12);
    static final String RESULT = "192.0.2.10 open: 22/ssh 80/http 443/https\n".repeat(900)
            + "SUMMARY: 900 hosts, none on telnet";
    static final String ERROR = "Traceback (most recent call last):\n"
            + "  File \"skill.py\", line 41, in scan\n".repeat(300) + "PermissionError: raw sockets need root";

    @Test
    @DisplayName("stored whole in the row; the owner is told where it is and how long")
    void storedWholeAndReferredTo() throws Exception {
        var jdbc = MigratedDatabase.at(tmp.resolve("t.db"));
        var emitter = new ChatStatusEmitter();
        var lines = new ArrayList<String>();
        emitter.subscribe("u1", "test", m -> lines.add(m.text()));
        var logs = new ListAppender<ILoggingEvent>();
        logs.start();
        List<Logger> watched = List.of((Logger) LoggerFactory.getLogger(LongRunningTaskManager.class),
                (Logger) LoggerFactory.getLogger(EventLogService.class));
        watched.forEach(l -> l.addAppender(logs));
        try {
            var manager = new LongRunningTaskManager(jdbc, emitter, new EventLogService(jdbc),
                    new OwnClawConfig());

            manager.register("t1", "u1", DESCRIPTION, "net_scan");
            manager.complete("t1", RESULT);
            manager.register("t2", "u1", DESCRIPTION, "net_scan");
            manager.fail("t2", ERROR);

            var done = jdbc.queryForMap("SELECT description, result_summary FROM long_running_tasks "
                    + "WHERE task_id = 't1'");
            assertEquals(DESCRIPTION, done.get("description"), "as given, whole");
            assertEquals(RESULT, done.get("result_summary"));
            assertEquals(ERROR, jdbc.queryForObject("SELECT error_message FROM long_running_tasks "
                    + "WHERE task_id = 't2'", String.class));

            assertTrue(lines.contains(String.format(Locale.ROOT, "Long-running task finished, %,d chars — task t1",
                    RESULT.length())), lines.toString());
            assertTrue(lines.contains(String.format(Locale.ROOT, "Long-running task failed, %,d chars — task t2",
                    ERROR.length())), lines.toString());

            // Neither the event summaries nor the log carry the text: an event's summary is
            // logged too, and a log is not where the owner's words or a tool's output belong.
            List<String> said = new ArrayList<>(jdbc.queryForList(
                    "SELECT summary FROM events WHERE task_id IN ('t1', 't2')", String.class));
            logs.list.forEach(e -> said.add(e.getFormattedMessage()));
            assertFalse(said.isEmpty());
            for (String s : said) {
                for (String text : List.of("home network", "22/ssh", "PermissionError")) {
                    assertFalse(s.contains(text), text + " in: " + s);
                }
            }
        } finally {
            watched.forEach(l -> l.detachAppender(logs));
        }
    }

    @Test
    @DisplayName("each status of a long-running skill is its task's: inside unattended work, marked as that work's")
    void statusesAreTheTasks() throws Exception {
        var jdbc = MigratedDatabase.at(tmp.resolve("t.db"));
        var emitter = new ChatStatusEmitter();
        var seen = new ArrayList<ChatStatusEmitter.StatusMessage>();
        emitter.subscribe("u1", "test", seen::add);
        var config = new OwnClawConfig();
        config.getTasks().setStallTimeout(-1);   // every task counts as stalled at once
        var manager = new LongRunningTaskManager(jdbc, emitter, new EventLogService(jdbc), config);

        emitter.inBackground("t4", () -> {
            manager.register("t4", "u1", DESCRIPTION, "net_scan");
            manager.reportProgress("t4", "Scanning 45/255 hosts", 18);
            manager.complete("t4", RESULT);
            manager.register("t4", "u1", DESCRIPTION, "net_scan");
            manager.fail("t4", ERROR);
            manager.register("t4", "u1", DESCRIPTION, "net_scan");
            manager.cancel("t4", "you pressed Stop");
            manager.register("t4", "u1", DESCRIPTION, "net_scan");
            manager.detectStalledTasks();
            return null;
        });

        assertEquals(List.of(ChatStatusEmitter.StatusMessage.Type.PROGRESS, ChatStatusEmitter.StatusMessage.Type.COMPLETED,
                        ChatStatusEmitter.StatusMessage.Type.FAILED, ChatStatusEmitter.StatusMessage.Type.WARNING,
                        ChatStatusEmitter.StatusMessage.Type.WARNING),
                seen.stream().map(ChatStatusEmitter.StatusMessage::type).toList());
        for (var s : seen) {
            assertEquals("t4", s.taskId(), s.toString());
            assertEquals(true, s.data().get(ChatStatusEmitter.BACKGROUND), s.toString());
        }
        // Mutation: emit any of them without the task's id -> a scheduled run's network scan shows
        // its progress in the chat on screen, and its end stops that chat's spinner.
    }

    @Test
    @DisplayName("a cancelled long-running task's event says who stopped its task, not always the user")
    void aCancelSaysWhy() throws Exception {
        var jdbc = MigratedDatabase.at(tmp.resolve("t.db"));
        var manager = new LongRunningTaskManager(jdbc, new ChatStatusEmitter(), new EventLogService(jdbc),
                new OwnClawConfig());
        manager.register("t3", "u1", DESCRIPTION, "net_scan");
        manager.cancel("t3", "the stall watchdog stopped it: no progress for 20m");
        assertEquals("Stopped: the stall watchdog stopped it: no progress for 20m", jdbc.queryForObject(
                "SELECT summary FROM events WHERE event_type = 'task.long_running.cancelled'", String.class));
    }
}
