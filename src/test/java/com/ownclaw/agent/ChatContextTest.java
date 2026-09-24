package com.ownclaw.agent;

import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.conversation.ConversationCompressor;
import com.ownclaw.conversation.ConversationService;
import com.ownclaw.conversation.FileStorageService;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * The open chat goes into a task that came from it, and into nothing else: every scheduled run
 * used to carry it to the cloud on every call.
 */
class ChatContextTest {

    private record Db(ConversationService conversations, FileStorageService files) {}

    private static Db db(Path tmp) throws Exception {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:" + tmp.resolve("t.db")));
        jdbc.execute("""
            CREATE TABLE conversations (id TEXT PRIMARY KEY, user_id TEXT NOT NULL, session_id TEXT NOT NULL,
                role TEXT NOT NULL, content TEXT NOT NULL, compressed_content TEXT, tokens_used INTEGER DEFAULT 0,
                timestamp TEXT DEFAULT (datetime('now')), metadata TEXT, compressed INTEGER NOT NULL DEFAULT 0,
                private_content TEXT)""");
        jdbc.execute("""
            CREATE TABLE chat_sessions (id TEXT PRIMARY KEY, user_id TEXT NOT NULL, title TEXT NOT NULL DEFAULT 'New Chat',
                preview TEXT, created_at TEXT DEFAULT (datetime('now')), updated_at TEXT DEFAULT (datetime('now')),
                archived INTEGER DEFAULT 0)""");
        jdbc.execute("CREATE TABLE active_session (user_id TEXT PRIMARY KEY, session_id TEXT NOT NULL)");
        jdbc.execute("CREATE TABLE session_summaries (session_id TEXT PRIMARY KEY, user_id TEXT NOT NULL, summary TEXT NOT NULL)");
        jdbc.execute("CREATE TABLE file_attachments (id TEXT PRIMARY KEY, user_id TEXT, original_name TEXT, "
                + "stored_name TEXT, content_type TEXT, size_bytes INTEGER, uploaded_at TEXT)");
        jdbc.execute("CREATE TABLE message_attachments (message_id TEXT, file_id TEXT)");

        var config = new OwnClawConfig();
        config.getDatabase().setPath(tmp.resolve("t.db").toString());
        Files.createDirectories(tmp.resolve("uploads"));
        return new Db(new ConversationService(jdbc, new ConversationCompressor(jdbc, null, null)),
                new FileStorageService(jdbc, config));
    }

    @Test
    @DisplayName("a chat task sees the newest messages in full up to the window's size; older ones are left out")
    void theChatWindowIsBounded(@TempDir Path tmp) throws Exception {
        var db = db(tmp);
        String session = db.conversations().createSession("u1", "Long answers");
        for (int i = 0; i < 8; i++) {
            db.conversations().saveMessage("u1", session, i % 2 == 0 ? "user" : "assistant",
                    "MSG" + i + " " + "x".repeat(5_995));                // 6,000 characters each
        }
        var chat = new AgentContext("u1", "t1", "and next?");
        AgentLoop.loadConversationContext(chat, "u1", null, db.conversations(), db.files());
        String summary = chat.conversationSummary();
        for (int i = 4; i < 8; i++) assertTrue(summary.contains("MSG" + i + " "), "newest kept: MSG" + i);
        for (int i = 0; i < 4; i++) assertFalse(summary.contains("MSG" + i + " "), "older left out: MSG" + i);
        assertTrue(summary.contains("4 earlier messages are not shown"), summary.substring(0, 200));
        assertTrue(summary.length() < ConversationCompressor.ACTIVE_CHARS + 1_000, "bounded: " + summary.length());
        assertTrue(summary.indexOf("MSG4 ") < summary.indexOf("MSG7 "), "in order, oldest shown first");
    }

    @Test
    @DisplayName("a chat task sees the open chat; an unattended one does not")
    void onlyChatTasksSeeTheChat(@TempDir Path tmp) throws Exception {
        var db = db(tmp);
        var conversations = db.conversations();
        var files = db.files();

        String session = conversations.createSession("u1", "Backups");
        conversations.saveMessage("u1", session, "user", "The rclone unit is CHAT-ONLY-DETAIL on the NAS");
        conversations.saveMessage("u1", session, "assistant", "Noted.");

        var chat = new AgentContext("u1", "t1", "and the second VM?");
        AgentLoop.loadConversationContext(chat, "u1", null, conversations, files);
        assertNotNull(chat.conversationSummary());
        assertTrue(chat.conversationSummary().contains("CHAT-ONLY-DETAIL"));

        var scheduled = new AgentContext("u1", "t2", "Fetch the daily news digest and email it");
        scheduled.setUnattended(true);
        AgentLoop.loadConversationContext(scheduled, "u1", null, conversations, files);
        assertNull(scheduled.conversationSummary(), "a scheduled task is its own instruction");
    }

    @Test
    @DisplayName("a later turn is told a file was attached and an answer was private, never what either held")
    void laterTurnNamesNoFile(@TempDir Path tmp) throws Exception {
        var db = db(tmp);
        String session = db.conversations().createSession("u1", "Statements");
        String pdf = db.files().store("u1", "vypis_123456789.pdf", "application/pdf",
                new ByteArrayInputStream("%PDF-1.7 binary".getBytes(StandardCharsets.UTF_8)));
        db.conversations().saveMessage("u1", session, "user", "summarise this statement", List.of(pdf));
        db.conversations().saveMessage("u1", session, "assistant", AgentLoop.PRIVATE_NOTE, List.of(),
                "a1b2c3d4", AgentLoop.PRIVATE_HEADER + "Closing balance SECRET-48213 CZK.");
        db.conversations().saveMessage("u1", session, "assistant", "Done.");

        var next = new AgentContext("u1", "t2", "and last month's?");
        AgentLoop.loadConversationContext(next, "u1", null, db.conversations(), db.files());
        String summary = next.conversationSummary();
        assertNotNull(summary);
        assertTrue(summary.contains("[A file was attached here (application/pdf, 15 bytes)"), summary);
        assertFalse(summary.contains("vypis") || summary.contains("123456789"),
                "a statement's file name carries its account number, and this goes to the cloud: "
                        + summary);
        assertTrue(summary.contains("ASSISTANT: " + AgentLoop.PRIVATE_NOTE), summary);
        assertFalse(summary.contains("SECRET"), "the private answer went into the next prompt: " + summary);
        assertTrue(summary.contains("ASSISTANT: Done."), "a row with no private text is read as it was");
    }
}
