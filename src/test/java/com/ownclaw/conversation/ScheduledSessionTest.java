package com.ownclaw.conversation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The pinned chat that scheduled results are delivered into: one per user, listed first, and
 * never the chat anything falls back to -- it is open only when the owner opens it.
 */
class ScheduledSessionTest {

    private JdbcTemplate jdbc;
    private ConversationService conversations;

    private void start(Path tmp) throws Exception {
        jdbc = MigratedDatabase.at(tmp.resolve("t.db"));
        conversations = new ConversationService(jdbc);
    }

    private List<Object> listed() {
        return conversations.listSessions("u1", false).stream().map(s -> s.get("id")).toList();
    }

    @Test
    @DisplayName("one pinned chat per user, made on first use and never opened by it")
    void onePinnedChat(@TempDir Path tmp) throws Exception {
        start(tmp);
        String open = conversations.createSession("u1", "Router");

        String pinned = conversations.scheduledSession("u1");

        assertEquals(pinned, conversations.scheduledSession("u1"), "the same chat every time");
        assertNotEquals(pinned, conversations.scheduledSession("u2"), "every user has their own");
        var row = jdbc.queryForMap("SELECT title, kind FROM chat_sessions WHERE id = ?", pinned);
        assertEquals(Map.of("title", ConversationService.SCHEDULED_TITLE, "kind", "scheduled"), row);
        assertEquals(open, conversations.getCurrentSession("u1"), "the chat on screen stays the chat on screen");
    }

    @Test
    @DisplayName("the pinned chat is listed first, however long since it was used")
    void listedFirst(@TempDir Path tmp) throws Exception {
        start(tmp);
        String pinned = conversations.scheduledSession("u1");
        String older = conversations.createSession("u1", "Older");
        String newer = conversations.createSession("u1", "Newer");
        jdbc.update("UPDATE chat_sessions SET updated_at = datetime('now', '-3 days') WHERE id = ?", pinned);
        jdbc.update("UPDATE chat_sessions SET updated_at = datetime('now', '-1 day') WHERE id = ?", older);

        assertEquals(List.of(pinned, newer, older), listed());
        assertEquals("scheduled", conversations.listSessions("u1", false).get(0).get("kind"));
    }

    @Test
    @DisplayName("deleting the open chat falls back to a conversation, never to the pinned chat")
    void neverTheFallback(@TempDir Path tmp) throws Exception {
        start(tmp);
        String other = conversations.createSession("u1", "Other");
        String open = conversations.createSession("u1", "Open");
        String pinned = conversations.scheduledSession("u1");
        jdbc.update("UPDATE chat_sessions SET updated_at = datetime('now', '-1 day') WHERE id <> ?", pinned);

        conversations.deleteSession("u1", open);
        assertEquals(other, conversations.getCurrentSession("u1"),
                "the pinned chat is the most recent, and still not the one opened");

        conversations.deleteSession("u1", other);
        String fresh = conversations.getCurrentSession("u1");
        assertNotEquals(pinned, fresh, "with no conversation left, a new one is made");
        assertEquals("chat", jdbc.queryForObject("SELECT kind FROM chat_sessions WHERE id = ?", String.class, fresh));
    }

    @Test
    @DisplayName("an archived pinned chat is out of sight, so the next delivery makes a new one")
    void archivedIsNotDeliveredInto(@TempDir Path tmp) throws Exception {
        start(tmp);
        String pinned = conversations.scheduledSession("u1");

        conversations.archiveSession("u1", pinned);

        String next = conversations.scheduledSession("u1");
        assertNotEquals(pinned, next);
        assertEquals(List.of(next), listed(), "the one the owner can see");
    }

    @Test
    @DisplayName("deleting the pinned chat clears the old reports; the next delivery makes a new one")
    void deletedAndMadeAgain(@TempDir Path tmp) throws Exception {
        start(tmp);
        String pinned = conversations.scheduledSession("u1");
        conversations.saveMessage("u1", pinned, "assistant", "yesterday's digest");

        conversations.deleteSession("u1", pinned);
        String next = conversations.scheduledSession("u1");

        assertNotEquals(pinned, next);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM conversations", Integer.class));
        assertEquals(List.of(next), jdbc.queryForList(
                "SELECT id FROM chat_sessions WHERE kind = 'scheduled'", String.class));
    }

    @Test
    @DisplayName("two runs finishing at once get one pinned chat, not one each")
    void oneChatForTwoAtOnce(@TempDir Path tmp) throws Exception {
        MigratedDatabase.at(tmp.resolve("t.db"));
        var looked = new AtomicInteger();
        var secondLooked = new CountDownLatch(1);
        // The first to look finds no chat and, before making one, waits for the second to look --
        // or half a second, if the second cannot get in to look.
        var db = new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:" + tmp.resolve("t.db"))) {
            @Override
            public <T> List<T> queryForList(String sql, Class<T> type, Object... args) {
                List<T> found = super.queryForList(sql, type, args);
                if (sql.contains("kind = 'scheduled'") && looked.incrementAndGet() == 2) secondLooked.countDown();
                return found;
            }

            @Override
            public int update(String sql, Object... args) {
                if (sql.contains("INSERT INTO chat_sessions") && looked.get() == 1) {
                    try {
                        secondLooked.await(500, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                return super.update(sql, args);
            }
        };
        var conversations = new ConversationService(db);
        var runs = Executors.newFixedThreadPool(2);
        try {
            var first = runs.submit(() -> conversations.scheduledSession("u1"));
            var second = runs.submit(() -> conversations.scheduledSession("u1"));
            assertEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        } finally {
            runs.shutdown();
        }
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM chat_sessions WHERE kind = 'scheduled'", Integer.class));
    }
}
