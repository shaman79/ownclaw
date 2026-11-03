package com.ownclaw.conversation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The chats ops checks start, and the scheduled results saved in the owner's chats before they had
 * a pinned chat of their own: out of the owner's list and conversations, by migration 023 for
 * those already there, and by {@link ConversationService#createOpsChat} for the checks to come.
 */
class OpsAndScheduledOutOfChatsTest {

    static final String MIGRATION = "023-ops-and-scheduled-out-of-chats.sql";

    private JdbcTemplate jdbc;

    private void user(String id) {
        jdbc.update("INSERT INTO users (id, display_name) VALUES (?, ?)", id, id);
    }

    private void session(String id, String user, String title, String kind, String updatedAt) {
        jdbc.update("INSERT INTO chat_sessions (id, user_id, title, kind, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)",
                id, user, title, kind, updatedAt, updatedAt);
    }

    private void open(String user, String session) {
        jdbc.update("INSERT INTO active_session (user_id, session_id) VALUES (?, ?)", user, session);
    }

    private void row(String id, String user, String session, String at, String role, String content, String metadata) {
        jdbc.update("INSERT INTO conversations (id, user_id, session_id, timestamp, role, content, metadata) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)", id, user, session, at, role, content, metadata);
    }

    private String sessionOf(String row) {
        return jdbc.queryForObject("SELECT session_id FROM conversations WHERE id = ?", String.class, row);
    }

    private String metadataOf(String row) {
        return jdbc.queryForObject("SELECT metadata FROM conversations WHERE id = ?", String.class, row);
    }

    private String movedFrom(String row) {
        return jdbc.queryForObject("SELECT json_extract(metadata, '$.movedFrom') FROM conversations WHERE id = ?",
                String.class, row);
    }

    private List<String> pinned(String user) {
        return jdbc.queryForList("SELECT id FROM chat_sessions WHERE user_id = ? AND kind = 'scheduled' AND archived = 0",
                String.class, user);
    }

    private List<String> ids(List<java.util.Map<String, Object>> rows, String column) {
        return rows.stream().map(r -> String.valueOf(r.get(column))).toList();
    }

    @Test
    @DisplayName("the scheduler's rows in the owner's chats move to the pinned chat, keeping where they were; the model's and the owner's stay")
    void scheduledRowsMoveToThePinnedChat(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("t.db");
        jdbc = MigratedDatabase.before(file, MIGRATION);
        user("u1");
        session("chat-a", "u1", "My server", "chat", "2026-10-05 08:00:00");
        session("chat-b", "u1", "Bikes", "chat", "2026-03-26 08:31:23");
        open("u1", "chat-a");
        jdbc.update("INSERT INTO scheduled_task_runs (task_id, user_id, description, task_type, status, agent_task_id) "
                + "VALUES (8, 'u1', 'Fetch daily lunch menus', 'recurring', 'completed', 'a1b2c3d4')");

        row("r1", "u1", "chat-a", "2026-09-29 06:00:00", "user", "my server is slow", null);
        row("r2", "u1", "chat-a", "2026-09-29 06:35:36", "assistant", "**Scheduled task: Fetch daily lunch menus**\n\nThe menus.", "{\"taskId\":\"a1b2c3d4\"}");
        row("r3", "u1", "chat-a", "2026-10-01 05:03:41", "assistant", "**Scheduled task: Fetch daily news digest**\n\nEight articles.", null);
        row("r4", "u1", "chat-a", "2026-09-21 05:05:00", "assistant", "**Scheduled task did not finish: Fetch daily news digest**\n\nTimed out.", null);
        row("r5", "u1", "chat-b", "2026-03-13 08:30:00", "system", "📋 **Scheduled task completed** (#8)\n**Task:** menus\n**Result:** ok", null);
        row("r6", "u1", "chat-b", "2026-03-26 08:30:00", "system", "❌ **Scheduled task failed** (#8)\n**Task:** menus\n**Error:** down", null);
        // The task's own progress row: no header, found by the task it belongs to.
        row("r7", "u1", "chat-a", "2026-09-29 06:33:00", "progress", "**Step 2** -- reading the menus", "{\"taskId\":\"a1b2c3d4\",\"progress\":{}}");
        row("r8", "u1", "chat-a", "2026-09-25 06:35:00", "assistant", "**Scheduled task: Fetch daily lunch menus**\n\nOld.", "[1]");
        row("r9", "u1", "chat-a", "2026-09-26 06:35:00", "assistant", "**Scheduled task: Fetch daily lunch menus**\n\nOlder.", "not json");
        // Not the scheduler's: the owner's own words, an answer about a schedule -- quoting a header,
        // opening with one after the scheduler stopped writing them, or in another case -- a
        // scheduling reply, and a March header at another time.
        row("k1", "u1", "chat-a", "2026-09-29 07:00:00", "user", "**Scheduled task: why did it fail?**", null);
        row("k2", "u1", "chat-a", "2026-09-29 07:01:00", "assistant", "The **Scheduled task: Fetch daily lunch menus** runs at 8:30.", "{\"taskId\":\"ffff0000\"}");
        row("k5", "u1", "chat-a", "2026-09-29 07:02:00", "assistant", "**scheduled TASK: Fetch daily lunch menus** is set up.", null);
        row("k4", "u1", "chat-a", "2026-10-05 08:00:00", "assistant", "**Scheduled task: Fetch daily lunch menus** -- runs weekdays at 08:30.", null);
        row("k3", "u1", "chat-b", "2026-03-13 08:00:00", "system", "Deferred task #3 scheduled for 2026-03-13 09:00", null);
        row("k6", "u1", "chat-b", "2026-09-25 08:00:00", "system", "📋 **Scheduled task completed** (#8) quoted", null);

        jdbc = MigratedDatabase.at(file);

        List<String> pins = pinned("u1");
        assertEquals(1, pins.size(), "a pinned chat is made for them, as the next delivery would make it");
        String pin = pins.getFirst();
        assertEquals(ConversationService.SCHEDULED_TITLE,
                jdbc.queryForObject("SELECT title FROM chat_sessions WHERE id = ?", String.class, pin),
                "the title deliveries look for");
        for (String moved : List.of("r2", "r3", "r4", "r7", "r8", "r9")) {
            assertEquals(pin, sessionOf(moved), moved);
            assertEquals("chat-a", movedFrom(moved), "keeps the chat it was in: " + moved);
        }
        for (String moved : List.of("r5", "r6")) {
            assertEquals(pin, sessionOf(moved), moved);
            assertEquals("{\"movedFrom\":\"chat-b\"}", metadataOf(moved));
        }
        assertEquals("a1b2c3d4", jdbc.queryForObject("SELECT json_extract(metadata, '$.taskId') FROM conversations WHERE id = 'r2'",
                String.class), "and the task it links to");
        assertEquals("{\"movedFrom\":\"chat-a\",\"metadata\":[1]}", metadataOf("r8"), "metadata that is no object is kept beside it");
        assertEquals("{\"movedFrom\":\"chat-a\",\"metadata\":\"not json\"}", metadataOf("r9"), "and metadata that is no JSON");

        var conversations = new ConversationService(jdbc);
        assertEquals(8, conversations.getSessionMessages("u1", pin, null, null).messages().size(),
                "shown in the pinned chat");
        assertEquals(List.of("r1", "k1", "k2", "k5", "k4"),
                ids(conversations.getSessionMessages("u1", "chat-a", null, null).messages(), "id"),
                "and no longer in the owner's chat");
        assertEquals(List.of("k3", "k6"), ids(conversations.getSessionMessages("u1", "chat-b", null, null).messages(), "id"));
    }

    @Test
    @DisplayName("a user's pinned chat takes them -- not an archived one, and no second is made")
    void anExistingPinnedChatTakesThem(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("t.db");
        jdbc = MigratedDatabase.before(file, MIGRATION);
        user("u1");
        session("chat-a", "u1", "My server", "chat", "2026-09-29 06:35:36");
        session("old-pin", "u1", ConversationService.SCHEDULED_TITLE, "scheduled", "2026-09-30 06:00:00");
        jdbc.update("UPDATE chat_sessions SET archived = 1 WHERE id = 'old-pin'");
        session("pin", "u1", ConversationService.SCHEDULED_TITLE, "scheduled", "2026-10-01 06:56:43");
        open("u1", "chat-a");
        row("a1", "u1", "chat-a", "2026-09-29 06:00:00", "user", "my server is slow", null);
        row("r1", "u1", "chat-a", "2026-09-29 06:35:36", "assistant", "**Scheduled task: Fetch daily lunch menus**\n\nThe menus.", null);
        row("p1", "u1", "pin", "2026-10-08 06:34:08", "assistant", "**Scheduled task: Fetch daily lunch menus**\n\nToday's.", null);

        jdbc = MigratedDatabase.at(file);

        assertEquals(List.of("pin"), pinned("u1"));
        assertEquals("pin", sessionOf("r1"));
        assertNull(metadataOf("p1"), "a row already in the pinned chat is not touched");
    }

    @Test
    @DisplayName("a chat left empty goes, with its summary -- but the open chat stays")
    void anEmptiedChatGoes(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("t.db");
        jdbc = MigratedDatabase.before(file, MIGRATION);
        user("u1");
        user("u2");
        session("only-results", "u1", "New Chat", "chat", "2026-10-01 05:03:41");
        session("kept", "u1", "Routers", "chat", "2026-09-30 06:34:02");
        open("u1", "kept");
        session("open-results", "u2", "New Chat", "chat", "2026-10-01 05:03:41");
        open("u2", "open-results");
        row("r1", "u1", "only-results", "2026-10-01 05:03:41", "assistant", "**Scheduled task: Fetch daily news digest**\n\nDigest.", null);
        row("r2", "u1", "kept", "2026-09-30 06:34:02", "assistant", "**Scheduled task: Fetch daily lunch menus**\n\nMenus.", null);
        row("a2", "u1", "kept", "2026-09-30 06:00:00", "user", "my routers", null);
        row("r3", "u2", "open-results", "2026-10-01 05:03:41", "assistant", "**Scheduled task: Fetch daily news digest**\n\nDigest.", null);
        jdbc.update("INSERT INTO session_summaries (session_id, user_id, summary) VALUES ('only-results', 'u1', 'a digest')");

        jdbc = MigratedDatabase.at(file);

        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM chat_sessions WHERE id = 'only-results'", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM session_summaries", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM chat_sessions WHERE id = 'kept'", Integer.class),
                "it keeps the owner's message");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM chat_sessions WHERE id = 'open-results'", Integer.class),
                "the open chat is where the next message goes");
        assertEquals(pinned("u1").getFirst(), sessionOf("r1"));
        assertEquals("only-results", movedFrom("r1"));
    }

    @Test
    @DisplayName("the chats ops checks started leave the owner's list -- but one the owner went on in, or has open, stays and is titled")
    void opsChatsLeaveTheList(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("t.db");
        jdbc = MigratedDatabase.before(file, MIGRATION);
        user("u1");
        session("older", "u1", "Bikes", "chat", "2026-03-13 08:31:23");
        session("ops1", "u1", "Ops check", "chat", "2026-10-07 21:15:16");
        session("ops-used", "u1", "Ops check", "chat", "2026-10-07 20:49:27");
        session("ops-open", "u1", "Ops check", "chat", "2026-10-06 09:43:56");
        open("u1", "ops-open");
        row("o1", "u1", "ops1", "2026-10-07 21:10:37", "user", "How long has this server been up?", null);
        row("u1a", "u1", "ops-used", "2026-10-01 05:35:51", "user", "  ", null);
        row("u1b", "u1", "ops-used", "2026-10-01 05:35:52", "user", "\r\n  I have several OpenWRT routers.  \r\nOne is wired.", null);
        row("u1c", "u1", "ops-used", "2026-10-07 20:00:00", "user", "zkontroluj roaming", null);
        row("u2a", "u1", "ops-open", "2026-10-06 09:43:44", "user", "uptime, please", null);

        jdbc = MigratedDatabase.at(file);

        var conversations = new ConversationService(jdbc);
        assertEquals(List.of("ops-used", "ops-open", "older"), ids(conversations.listSessions("u1", true), "id"));
        assertEquals("ops", jdbc.queryForObject("SELECT kind FROM chat_sessions WHERE id = 'ops1'", String.class));
        assertEquals("ops1", sessionOf("o1"), "hidden, not deleted");
        assertEquals("I have several OpenWRT routers.",
                jdbc.queryForObject("SELECT title FROM chat_sessions WHERE id = 'ops-used'", String.class),
                "the first line with text of its first message, as a new chat is titled");
        assertEquals(ConversationService.generateTitle("\r\n  I have several OpenWRT routers.  \r\nOne is wired."),
                jdbc.queryForObject("SELECT title FROM chat_sessions WHERE id = 'ops-used'", String.class));
        assertEquals("uptime, please", jdbc.queryForObject("SELECT title FROM chat_sessions WHERE id = 'ops-open'", String.class));
        assertEquals("ops-open", conversations.getCurrentSession("u1"), "the open chat is untouched");
    }

    @Test
    @DisplayName("a chat an ops check starts is of its own kind: not opened, not listed, not searched, still the check's to continue")
    void anOpsChatIsTheOperators(@TempDir Path tmp) throws Exception {
        jdbc = MigratedDatabase.at(tmp.resolve("t.db"));
        user("u1");
        var conversations = new ConversationService(jdbc);
        String owners = conversations.createSession("u1", "Routers");
        conversations.saveMessage("u1", owners, "user", "the routers are slow");

        String ops = conversations.createOpsChat("u1");
        conversations.saveMessage("u1", ops, "user", "uptime of the routers");

        assertEquals(owners, conversations.getCurrentSession("u1"), "never the open chat");
        assertEquals(List.of(owners), ids(conversations.listSessions("u1", true), "id"));
        assertEquals(List.of(owners), ids(conversations.searchMessages("u1", "routers"), "session_id"));
        assertTrue(conversations.hasChat("u1", ops));
        assertFalse(conversations.hasChat("u2", ops), "another user's is not");
        assertEquals(ConversationService.OPS_TITLE,
                jdbc.queryForObject("SELECT title FROM chat_sessions WHERE id = ?", String.class, ops));
    }
}
