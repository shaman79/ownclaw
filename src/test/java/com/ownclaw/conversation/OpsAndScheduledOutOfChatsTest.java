package com.ownclaw.conversation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The chats ops checks start, and the scheduled results saved in the owner's chats before they had
 * a pinned chat of their own: out of the owner's list and conversations, by migration 023 for
 * those already there, and by {@link ConversationService#createOpsChat} for the checks to come.
 */
class OpsAndScheduledOutOfChatsTest {

    static final String MIGRATION = "023-ops-and-scheduled-out-of-chats.sql";

    private JdbcTemplate jdbc;

    private void session(String id, String user, String title, String kind, String updatedAt) {
        jdbc.update("INSERT INTO chat_sessions (id, user_id, title, kind, updated_at) VALUES (?, ?, ?, ?, ?)",
                id, user, title, kind, updatedAt);
    }

    private void row(String id, String user, String session, String role, String content, String metadata) {
        jdbc.update("INSERT INTO conversations (id, user_id, session_id, role, content, metadata) VALUES (?, ?, ?, ?, ?, ?)",
                id, user, session, role, content, metadata);
    }

    private String sessionOf(String row) {
        return jdbc.queryForObject("SELECT session_id FROM conversations WHERE id = ?", String.class, row);
    }

    private String metadataOf(String row) {
        return jdbc.queryForObject("SELECT metadata FROM conversations WHERE id = ?", String.class, row);
    }

    private List<String> pinned(String user) {
        return jdbc.queryForList("SELECT id FROM chat_sessions WHERE user_id = ? AND kind = 'scheduled'",
                String.class, user);
    }

    @Test
    @DisplayName("the scheduler's rows in the owner's chats move to the pinned chat, keeping where they were; the rest stay")
    void scheduledRowsMoveToThePinnedChat(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("t.db");
        jdbc = MigratedDatabase.before(file, MIGRATION);
        jdbc.update("INSERT INTO users (id, display_name) VALUES ('u1', 'Owner')");
        session("chat-a", "u1", "My server", "chat", "2026-09-29 06:35:36");
        session("chat-b", "u1", "Bikes", "chat", "2026-03-13 08:31:23");
        jdbc.update("INSERT INTO scheduled_task_runs (task_id, user_id, description, task_type, status, agent_task_id) "
                + "VALUES (8, 'u1', 'Fetch daily lunch menus', 'recurring', 'completed', 'a1b2c3d4')");

        row("r1", "u1", "chat-a", "user", "my server is slow", null);
        row("r2", "u1", "chat-a", "assistant", "**Scheduled task: Fetch daily lunch menus**\n\nThe menus.", "{\"taskId\":\"a1b2c3d4\"}");
        row("r3", "u1", "chat-a", "assistant", "**Scheduled task: Fetch daily news digest**\n\nEight articles.", null);
        row("r4", "u1", "chat-a", "assistant", "**Scheduled task did not finish: Fetch daily news digest**\n\nTimed out.", null);
        row("r5", "u1", "chat-b", "system", "📋 **Scheduled task completed** (#8)\n**Task:** menus\n**Result:** ok", null);
        row("r6", "u1", "chat-b", "system", "❌ **Scheduled task failed** (#8)\n**Task:** menus\n**Error:** down", null);
        row("r7", "u1", "chat-a", "progress", "**Step 2** -- reading the menus", "{\"taskId\":\"a1b2c3d4\",\"progress\":{}}");
        // Not the scheduler's: the owner's own words, an answer about a schedule, a scheduling reply.
        row("k1", "u1", "chat-a", "user", "**Scheduled task: why did it fail?**", null);
        row("k2", "u1", "chat-a", "assistant", "The **Scheduled task: Fetch daily lunch menus** runs at 8:30.", "{\"taskId\":\"ffff0000\"}");
        row("k3", "u1", "chat-b", "system", "Deferred task #3 scheduled for 2026-03-13 09:00", null);

        jdbc = MigratedDatabase.at(file);

        List<String> pins = pinned("u1");
        assertEquals(1, pins.size(), "a pinned chat is made for them, as the next delivery would make it");
        String pin = pins.getFirst();
        assertEquals(ConversationService.SCHEDULED_TITLE,
                jdbc.queryForObject("SELECT title FROM chat_sessions WHERE id = ?", String.class, pin));
        for (String moved : List.of("r2", "r3", "r4", "r7")) {
            assertEquals(pin, sessionOf(moved), moved);
            assertEquals("chat-a", jdbc.queryForObject("SELECT json_extract(metadata, '$.movedFrom') FROM conversations WHERE id = ?",
                    String.class, moved), "keeps the chat it was in: " + moved);
        }
        for (String moved : List.of("r5", "r6")) {
            assertEquals(pin, sessionOf(moved), moved);
            assertEquals("{\"movedFrom\":\"chat-b\"}", metadataOf(moved));
        }
        assertEquals("a1b2c3d4", jdbc.queryForObject("SELECT json_extract(metadata, '$.taskId') FROM conversations WHERE id = 'r2'",
                String.class), "and the task it links to");
        for (String kept : List.of("r1", "k1", "k2")) assertEquals("chat-a", sessionOf(kept), kept);
        assertEquals("chat-b", sessionOf("k3"));

        var conversations = new ConversationService(jdbc);
        assertEquals(6, conversations.getSessionMessages("u1", pin, null, null).messages().size(),
                "shown in the pinned chat");
        assertEquals(List.of("r1", "k1", "k2"), conversations.getSessionMessages("u1", "chat-a", null, null).messages()
                .stream().map(m -> String.valueOf(m.get("id"))).toList(), "and no longer in the owner's chat");
        // Mutation: drop the task-id match -> r7 stays in chat-a; drop a header -> its row stays.
    }

    @Test
    @DisplayName("a user's existing pinned chat takes them: no second one is made")
    void anExistingPinnedChatTakesThem(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("t.db");
        jdbc = MigratedDatabase.before(file, MIGRATION);
        jdbc.update("INSERT INTO users (id, display_name) VALUES ('u1', 'Owner')");
        session("chat-a", "u1", "My server", "chat", "2026-09-29 06:35:36");
        session("pin", "u1", ConversationService.SCHEDULED_TITLE, "scheduled", "2026-10-08 06:34:08");
        row("r1", "u1", "chat-a", "assistant", "**Scheduled task: Fetch daily lunch menus**\n\nThe menus.", null);
        row("p1", "u1", "pin", "assistant", "**Scheduled task: Fetch daily lunch menus**\n\nToday's.", null);

        jdbc = MigratedDatabase.at(file);

        assertEquals(List.of("pin"), pinned("u1"));
        assertEquals("pin", sessionOf("r1"));
        assertNull(metadataOf("p1"), "a row already in the pinned chat is not touched");
    }

    @Test
    @DisplayName("the chats ops checks started leave the owner's list; an open one gives way to the newest chat, or to none")
    void opsChatsLeaveTheList(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("t.db");
        jdbc = MigratedDatabase.before(file, MIGRATION);
        jdbc.update("INSERT INTO users (id, display_name) VALUES ('u1', 'Owner')");
        jdbc.update("INSERT INTO users (id, display_name) VALUES ('u2', 'Someone')");
        session("older", "u1", "Bikes", "chat", "2026-03-13 08:31:23");
        session("newest", "u1", "Routers", "chat", "2026-09-30 06:34:02");
        session("ops1", "u1", "Ops check", "chat", "2026-10-07 20:49:27");
        session("ops2", "u2", "Ops check", "chat", "2026-10-05 12:28:24");
        row("o1", "u1", "ops1", "user", "uptime of the routers", null);
        jdbc.update("INSERT INTO active_session (user_id, session_id) VALUES ('u1', 'ops1')");
        jdbc.update("INSERT INTO active_session (user_id, session_id) VALUES ('u2', 'ops2')");

        jdbc = MigratedDatabase.at(file);

        var conversations = new ConversationService(jdbc);
        assertEquals(List.of("newest", "older"), conversations.listSessions("u1", true).stream()
                .map(s -> String.valueOf(s.get("id"))).toList());
        assertEquals("ops", jdbc.queryForObject("SELECT kind FROM chat_sessions WHERE id = 'ops1'", String.class));
        assertEquals("ops1", sessionOf("o1"), "hidden, not deleted");
        assertEquals("newest", conversations.getCurrentSession("u1"), "the open chat is one the owner can see");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM active_session WHERE user_id = 'u2'", Integer.class),
                "none to give way to: the next message starts a chat");
    }

    @Test
    @DisplayName("a chat an ops check starts is of its own kind: not opened, not listed, not searched, still the check's to continue")
    void anOpsChatIsTheOperators(@TempDir Path tmp) throws Exception {
        jdbc = MigratedDatabase.at(tmp.resolve("t.db"));
        jdbc.update("INSERT INTO users (id, display_name) VALUES ('u1', 'Owner')");
        var conversations = new ConversationService(jdbc);
        String owners = conversations.createSession("u1", "Routers");
        conversations.saveMessage("u1", owners, "user", "the routers are slow");

        String ops = conversations.createOpsChat("u1");
        conversations.saveMessage("u1", ops, "user", "uptime of the routers");

        assertEquals(owners, conversations.getCurrentSession("u1"), "never the open chat");
        assertEquals(List.of(owners), conversations.listSessions("u1", true).stream()
                .map(s -> String.valueOf(s.get("id"))).toList());
        assertEquals(List.of(owners), conversations.searchMessages("u1", "routers").stream()
                .map(s -> String.valueOf(s.get("session_id"))).toList());
        assertTrue(conversations.hasChat("u1", ops));
        assertFalse(conversations.hasChat("u2", ops), "another user's is not");
        assertEquals(ConversationService.OPS_TITLE,
                jdbc.queryForObject("SELECT title FROM chat_sessions WHERE id = ?", String.class, ops));
    }

    @Test
    @DisplayName("the migration's pinned-chat title is the one deliveries look for")
    void theTitleIsTheDeliveries() {
        assertEquals("📌 Scheduled", ConversationService.SCHEDULED_TITLE,
                "023 writes it as char(128204) || ' Scheduled'");
    }
}
