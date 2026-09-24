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

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The open chat goes into a task that came from it, and into nothing else: every scheduled run
 * used to carry it to the cloud on every call.
 */
class ChatContextTest {

    @Test
    @DisplayName("a chat task sees the open chat; an unattended one does not")
    void onlyChatTasksSeeTheChat(@TempDir Path tmp) {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:" + tmp.resolve("t.db")));
        jdbc.execute("""
            CREATE TABLE conversations (id TEXT PRIMARY KEY, user_id TEXT NOT NULL, session_id TEXT NOT NULL,
                role TEXT NOT NULL, content TEXT NOT NULL, compressed_content TEXT, tokens_used INTEGER DEFAULT 0,
                timestamp TEXT DEFAULT (datetime('now')), metadata TEXT, compressed INTEGER NOT NULL DEFAULT 0)""");
        jdbc.execute("""
            CREATE TABLE chat_sessions (id TEXT PRIMARY KEY, user_id TEXT NOT NULL, title TEXT NOT NULL DEFAULT 'New Chat',
                preview TEXT, created_at TEXT DEFAULT (datetime('now')), updated_at TEXT DEFAULT (datetime('now')),
                archived INTEGER DEFAULT 0)""");
        jdbc.execute("CREATE TABLE active_session (user_id TEXT PRIMARY KEY, session_id TEXT NOT NULL)");
        jdbc.execute("CREATE TABLE session_summaries (session_id TEXT PRIMARY KEY, user_id TEXT NOT NULL, summary TEXT NOT NULL)");
        jdbc.execute("CREATE TABLE file_attachments (id TEXT PRIMARY KEY, user_id TEXT, original_name TEXT, "
                + "stored_name TEXT, content_type TEXT, size_bytes INTEGER, uploaded_at TEXT)");
        jdbc.execute("CREATE TABLE message_attachments (message_id TEXT, file_id TEXT)");

        var conversations = new ConversationService(jdbc, new ConversationCompressor(jdbc, null, null));
        var config = new OwnClawConfig();
        config.getDatabase().setPath(tmp.resolve("t.db").toString());
        var files = new FileStorageService(jdbc, config);

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
}
