package com.ownclaw.agent;

import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.conversation.FileStorageService;
import com.ownclaw.observability.EventLogService;
import com.ownclaw.privacy.Label;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The files sent with a message, registered for real: the file table and the uploads directory
 * on SQLite, the events table the task page reads.
 * <p>
 * The ids come from the browser. What reaches a skill, and what the label weighs, is only what
 * survives registration -- and the file's name, which can itself be what the file holds, stays
 * in the owner's events row.
 */
class AttachmentRegistrationTest {

    static final String NAME = "vypis_123456789.csv";

    /** The file table, the uploads directory and the events table, on one SQLite file. */
    record Harness(FileStorageService files, EventLogService events) {
        static Harness in(Path tmp) throws Exception {
            var jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:" + tmp.resolve("t.db")));
            jdbc.execute("CREATE TABLE file_attachments (id TEXT PRIMARY KEY, user_id TEXT, original_name TEXT, "
                    + "stored_name TEXT, content_type TEXT, size_bytes INTEGER, uploaded_at TEXT)");
            jdbc.execute("""
                CREATE TABLE events (
                    id INTEGER PRIMARY KEY AUTOINCREMENT, timestamp TEXT DEFAULT (datetime('now')),
                    user_id TEXT NOT NULL, task_id TEXT, event_type TEXT NOT NULL, severity TEXT NOT NULL,
                    summary TEXT NOT NULL, details TEXT, tokens_used INTEGER DEFAULT 0)""");
            var config = new OwnClawConfig();
            config.getDatabase().setPath(tmp.resolve("t.db").toString());
            Files.createDirectories(tmp.resolve("uploads"));
            return new Harness(new FileStorageService(jdbc, config), new EventLogService(jdbc));
        }

        String upload(String userId, String name, String contentType, String body) throws Exception {
            return files.store(userId, name, contentType,
                    new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        }
    }

    @Test
    @DisplayName("only this user's files are registered: each PRIVATE, indexed, and named by handle only")
    void onlyOwnFilesAreRegisteredAndNoneByName(@TempDir Path tmp) throws Exception {
        var h = Harness.in(tmp);
        String statement = "date,amount,counterparty\n2026-09-01,-1200,UNIQUE-COUNTERPARTY-7f3a9c21-rent\n";
        String own = h.upload("u1", NAME, "text/csv", statement);
        String foreign = h.upload("u2", "someone_elses.csv", "text/csv", "not yours\n");
        String missing = UUID.randomUUID().toString();

        var ctx = new AgentContext("u1", "t1", "summarise this statement");
        assertFalse(ctx.isUnattended(), "attended chat, where files used to be PUBLIC");
        AgentLoop.registerAttachments(ctx, List.of(own, foreign, missing), h.files(), h.events());

        assertEquals(List.of(own), ctx.attachmentIds(),
                "another user's file and an id with no file are never handed to a skill");
        assertEquals(1, ctx.artifacts().size());
        Artifact a = ctx.artifacts().get(0);
        assertEquals("{{1}}", a.handle());
        assertEquals("attachment", a.tool());
        assertEquals(Label.PRIVATE, a.label(), "PRIVATE whoever is watching");
        assertTrue(a.indexed());
        assertEquals(statement, a.output());
        assertNotNull(ctx.privateIndex().firstHitIn("…" + statement.substring(20, 60) + "…"),
                "a text upload is in the canary");

        for (String part : List.of(a.tool(), String.valueOf(a.why()), String.valueOf(a.written()),
                String.valueOf(a.resolved()), a.describe())) {
            assertFalse(part.contains("vypis") || part.contains("123456789"),
                    "the name is in an artifact field: " + part);
        }

        var rows = h.events().taskEvents("u1", "t1");
        assertEquals(1, rows.size(), "one row per registered file");
        String details = String.valueOf(rows.get(0).get("details"));
        assertTrue(details.contains("\"name\":\"" + NAME + "\""), "the owner's page names it: " + details);
        assertFalse(details.contains("UNIQUE-COUNTERPARTY"), "metadata only, never the text");
        assertFalse(details.contains("someone_elses"));
        assertEquals("attachment PRIVATE", rows.get(0).get("summary"));
    }

    @Test
    @DisplayName("a file that is not text is registered with no text, and says so by type and size")
    void aFileThatIsNotTextHasNoText(@TempDir Path tmp) throws Exception {
        var h = Harness.in(tmp);
        String pdf = h.upload("u1", "vypis_123456789.pdf", "application/pdf", "%PDF-1.7 binary");

        var ctx = new AgentContext("u1", "t1", "summarise this statement");
        AgentLoop.registerAttachments(ctx, List.of(pdf), h.files(), h.events());

        Artifact a = ctx.files().get(0);
        assertEquals("", a.output(), "the bytes are read by a skill, not carried here");
        assertEquals(List.of("uploaded file", "application/pdf, 15 bytes, no text read (not text, over 100 KB, or not UTF-8)"), a.why());
        assertEquals(Map.of("fileId", pdf), a.written());
    }
}
