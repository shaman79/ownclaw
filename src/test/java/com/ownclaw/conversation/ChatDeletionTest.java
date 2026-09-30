package com.ownclaw.conversation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The full-text index holds the user and assistant rows, and is told to remove only those: a
 * chat with a command's reply in it (a 'system' row, never indexed) could not be deleted --
 * SQLite refused with SQLITE_CORRUPT_VTAB -- and so neither could the pinned chat once a command
 * had been typed there.
 */
class ChatDeletionTest {

    private JdbcTemplate jdbc;
    private ConversationService conversations;

    private void start(Path tmp) throws Exception {
        jdbc = MigratedDatabase.at(tmp.resolve("t.db"));
        conversations = new ConversationService(jdbc, null);
    }

    private void indexIsWhole() {
        jdbc.update("INSERT INTO conversations_fts(conversations_fts) VALUES('integrity-check')");
    }

    @Test
    @DisplayName("a chat with a command's reply in it is deleted, and search goes on working")
    void aChatWithACommandReplyIsDeleted(@TempDir Path tmp) throws Exception {
        start(tmp);
        String other = conversations.createSession("u1", "Other");
        conversations.saveMessage("u1", other, "user", "router firmware question");
        String doomed = conversations.createSession("u1", "Doomed");
        conversations.saveMessage("u1", doomed, "user", "router is down");
        conversations.saveMessage("u1", doomed, "system", "Unknown command. Try /help");
        conversations.saveMessage("u1", doomed, "assistant", "the router restarted");

        conversations.deleteSession("u1", doomed);

        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM conversations WHERE session_id = ?", Integer.class, doomed));
        indexIsWhole();
        assertEquals(List.of(other), conversations.searchMessages("u1", "router").stream()
                .map(r -> r.get("session_id")).toList());
    }

    @Test
    @DisplayName("a changed message is found by its new text; a changed command reply stays out of the index")
    void anEditKeepsTheIndexRight(@TempDir Path tmp) throws Exception {
        start(tmp);
        String chat = conversations.createSession("u1", "Chat");
        conversations.saveMessage("u1", chat, "user", "first wording");
        conversations.saveMessage("u1", chat, "system", "a reply about wording");

        jdbc.update("UPDATE conversations SET content = 'second phrasing' WHERE role = 'user'");
        jdbc.update("UPDATE conversations SET content = 'another reply about wording' WHERE role = 'system'");

        indexIsWhole();
        assertEquals(1, conversations.searchMessages("u1", "phrasing").size());
        assertEquals(0, conversations.searchMessages("u1", "wording").size(),
                "the old words are gone, and a system row was never indexed");
    }
}
