package com.ownclaw.conversation;

import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.users.UserRepository;
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
        conversations = new ConversationService(jdbc);
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

    /**
     * The schema with its foreign keys enforced, as production's datasource URL has them: a chat
     * with a file sent in it, open, and one other chat. Returns {owner, the file's chat, the other}.
     */
    private String[] chatWithAFile(Path tmp) throws Exception {
        MigratedDatabase.at(tmp.resolve("t.db"));
        jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:" + tmp.resolve("t.db") + "?foreign_keys=true"));
        assertEquals(1, jdbc.queryForObject("PRAGMA foreign_keys", Integer.class));
        conversations = new ConversationService(jdbc);
        String owner = new UserRepository(jdbc).createUser("owner", 4242L);
        var config = new OwnClawConfig();
        config.getDatabase().setPath(tmp.resolve("t.db").toString());
        Files.createDirectories(tmp.resolve("uploads"));
        String file = new FileStorageService(jdbc, config).store(owner, "statement.pdf", "application/pdf",
                new ByteArrayInputStream("closing balance".getBytes(StandardCharsets.UTF_8)));
        String other = conversations.createSession(owner, "Other");
        conversations.saveMessage(owner, other, "user", "router firmware question");
        String taxes = conversations.createSession(owner, "Taxes");
        conversations.saveMessage(owner, taxes, "user", "what does my statement say?", List.of(file));
        conversations.saveMessage(owner, taxes, "assistant", "the closing balance is in it");
        return new String[]{owner, taxes, other};
    }

    @Test
    @DisplayName("a chat a file was sent in is deleted where foreign keys are enforced; the file stays, and the next chat opens")
    void aChatWithAFileIsDeleted(@TempDir Path tmp) throws Exception {
        String[] c = chatWithAFile(tmp);

        conversations.deleteSession(c[0], c[1]);

        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM conversations WHERE session_id = ?", Integer.class, c[1]));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM message_attachments", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM file_attachments", Integer.class),
                "the file itself is kept, listed by /files");
        assertEquals(c[2], conversations.getCurrentSession(c[0]), "the remaining chat is the open one");
        indexIsWhole();
        // Mutation: leave the links in place -> SQLITE_CONSTRAINT_FOREIGNKEY, and the open chat
        // gone with nothing deleted.
    }

    @Test
    @DisplayName("a delete that fails leaves the chat as it was, the open one still open")
    void aFailedDeleteChangesNothing(@TempDir Path tmp) throws Exception {
        String[] c = chatWithAFile(tmp);
        jdbc.execute("CREATE TRIGGER refuse BEFORE DELETE ON chat_sessions BEGIN SELECT RAISE(ABORT, 'locked'); END");

        assertThrows(RuntimeException.class, () -> conversations.deleteSession(c[0], c[1]));

        assertEquals(c[1], conversations.getCurrentSession(c[0]), "the chat is still the open one");
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM conversations WHERE session_id = ?", Integer.class, c[1]));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM message_attachments", Integer.class));
        // Mutation: no transaction -> the open-chat pointer and the messages are gone, the chat
        // is not, and the next message goes to a new, empty chat.
    }
}
