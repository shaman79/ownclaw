package com.ownclaw.observability;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ownclaw.conversation.MigratedDatabase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** An event's summary is kept whole in its row and stays out of the application log. */
class EventLogPrivacyTest {

    @Test
    @DisplayName("the row keeps the whole summary; the log line says only how long it is")
    void theSummaryIsNotLogged(@TempDir Path tmp) throws Exception {
        var jdbc = MigratedDatabase.at(tmp.resolve("t.db"));
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        var logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(EventLogService.class);
        logger.addAppender(appender);
        try {
            String summary = "Task #3 failed: MAX_STEPS after 30 steps: the balance is 48,213.07 CZK. "
                    + "and more ".repeat(100);
            new EventLogService(jdbc).warn("u1", "abcd1234", "scheduled.failed", summary);

            assertEquals(summary, jdbc.queryForObject("SELECT summary FROM events", String.class));
            assertEquals(1, appender.list.size());
            String line = appender.list.get(0).getFormattedMessage();
            assertFalse(line.contains("48,213.07"), line);
            assertEquals("[scheduled.failed] u1 task=abcd1234: " + summary.length() + " chars", line);
        } finally {
            logger.detachAppender(appender);
        }
    }
}
