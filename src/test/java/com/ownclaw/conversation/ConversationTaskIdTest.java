package com.ownclaw.conversation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A chat row comes back on reload with what links it: an answer the task that produced it, and
 * every row the id a live frame names it by.
 */
class ConversationTaskIdTest {

    @Test
    @DisplayName("a saved answer comes back with its task id; anything malformed is not stored")
    void taskIdRoundTrips(@TempDir Path tmp) {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:" + tmp.resolve("t.db")));
        jdbc.execute("""
            CREATE TABLE conversations (id TEXT PRIMARY KEY, user_id TEXT NOT NULL, session_id TEXT NOT NULL,
                role TEXT NOT NULL, content TEXT NOT NULL, compressed_content TEXT, tokens_used INTEGER DEFAULT 0,
                timestamp TEXT DEFAULT (datetime('now')), metadata TEXT, private_content TEXT)""");
        jdbc.execute("CREATE TABLE chat_sessions (id TEXT PRIMARY KEY, updated_at TEXT, preview TEXT)");
        var conversations = new ConversationService(jdbc);

        conversations.saveMessage("u1", "s1", "assistant", "the menu", List.of(), "a1b2c3d4");
        conversations.saveMessage("u1", "s1", "assistant", "old answer");
        conversations.saveMessage("u1", "s1", "assistant", "odd", List.of(), "ABCDEFGH");

        // All three share a timestamp, so look them up by content rather than by position.
        var byContent = new java.util.HashMap<Object, Object>();
        conversations.getSessionMessages("u1", "s1", null, null).messages()
                .forEach(m -> byContent.put(m.get("content"), m.get("task_id")));
        assertEquals(3, byContent.size());
        assertEquals("a1b2c3d4", byContent.get("the menu"));
        assertNull(byContent.get("old answer"));
        assertNull(byContent.get("odd"), "only a well-formed task id is stored");
    }

    @Test
    @DisplayName("each row comes back with the id it was saved under")
    void rowsComeBackWithTheirIds(@TempDir Path tmp) throws Exception {
        var conversations = new ConversationService(MigratedDatabase.at(tmp.resolve("t.db")));
        String chat = conversations.createSession("u1", "Network");
        String asked = conversations.saveMessage("u1", chat, "user", "use the backup link");
        String answered = conversations.saveMessage("u1", chat, "assistant", "Switched.", List.of(), "a1b2c3d4");
        // Saved in one second, they share a timestamp: matched by content, not by position.
        var ids = new java.util.HashMap<Object, Object>();
        conversations.getSessionMessages("u1", chat, null, null).messages()
                .forEach(m -> ids.put(m.get("content"), m.get("id")));
        assertEquals(java.util.Map.of("use the backup link", asked, "Switched.", answered), ids,
                "what the page names a bubble by, as the read frame names it");
        // Mutation: leave the id out -> a bubble drawn after a reload is never marked read.
    }
}
