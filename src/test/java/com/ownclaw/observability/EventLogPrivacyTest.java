package com.ownclaw.observability;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ownclaw.conversation.MigratedDatabase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** An event's summary is kept whole in its row and stays out of the application log. */
class EventLogPrivacyTest {

    @Test
    @DisplayName("the row keeps the whole summary; the log line says only how long it is, at every severity")
    void theSummaryIsNotLogged(@TempDir Path tmp) throws Exception {
        var jdbc = MigratedDatabase.at(tmp.resolve("t.db"));
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        var logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(EventLogService.class);
        logger.addAppender(appender);
        try {
            String summary = "Task #3 failed: **Stopped:** it used all 20 steps; the balance is 48,213.07 CZK. "
                    + "and more ".repeat(100);
            var events = new EventLogService(jdbc);
            // task_completed, logged at info, carries the owner's own message.
            events.info("u1", "abcd1234", "task_completed", summary);
            events.warn("u1", "abcd1234", "scheduled.failed", summary);
            events.error("u1", "abcd1234", "task_failed", summary);

            assertEquals(List.of(summary, summary, summary),
                    jdbc.queryForList("SELECT summary FROM events ORDER BY id", String.class));
            assertEquals(List.of("INFO [task_completed]", "WARN [scheduled.failed]", "ERROR [task_failed]"),
                    appender.list.stream().map(e -> e.getLevel() + " " + e.getFormattedMessage().split(" ")[0]).toList());
            for (ILoggingEvent e : appender.list) {
                String line = e.getFormattedMessage();
                assertFalse(line.contains("48,213.07"), line);
                assertTrue(line.endsWith(" u1 task=abcd1234: " + summary.length() + " chars"), line);
            }
        } finally {
            logger.detachAppender(appender);
        }
    }
}
