package com.ownclaw.conversation;

import com.ownclaw.config.OwnClawConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * An uploaded text file is read whole, whatever its size: the text is what the privacy canary
 * indexes, and a file over 100 KB used to come back as nothing at all.
 */
class FileTextTest {

    private FileStorageService files(Path tmp) throws Exception {
        var config = new OwnClawConfig();
        config.getDatabase().setPath(tmp.resolve("t.db").toString());
        Files.createDirectories(tmp.resolve("uploads"));
        return new FileStorageService(MigratedDatabase.at(tmp.resolve("t.db")), config);
    }

    @Test
    @DisplayName("a 300 KB text file is read whole")
    void aLargeTextIsReadWhole(@TempDir Path tmp) throws Exception {
        var files = files(tmp);
        String text = "2026-09-30;card payment;-412.00 CZK;Grocery store\n".repeat(7_000);
        assertTrue(text.length() > 300_000);
        String id = files.store("u1", "statement.csv", "text/csv",
                new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));

        assertEquals(text, files.readAsText(id));
    }

    @Test
    @DisplayName("a file that is not UTF-8 is no text, and its name is not logged saying so")
    void notUtf8(@TempDir Path tmp) throws Exception {
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(FileStorageService.class);
        logger.addAppender(appender);
        try {
            var files = files(tmp);
            String id = files.store("u1", "vypis_123456789.csv", "text/csv",
                    new ByteArrayInputStream(new byte[]{'a', (byte) 0xff, (byte) 0xfe, 'b'}));

            assertNull(files.readAsText(id));
            assertTrue(appender.list.stream().noneMatch(e -> e.getFormattedMessage().contains("123456789")
                    || (e.getThrowableProxy() != null && e.getThrowableProxy().getMessage() != null
                            && e.getThrowableProxy().getMessage().contains("123456789"))), "logged the name");
        } finally {
            logger.detachAppender(appender);
        }
    }
}
