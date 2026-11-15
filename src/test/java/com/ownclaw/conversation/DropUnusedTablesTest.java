package com.ownclaw.conversation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Migration 027 drops what no code uses -- six tables, two columns of conversations -- and it
 * runs on a database that holds the owner's chats. So it is run here on one that holds rows in
 * everything it touches, opened as production opens its own, foreign keys enforced: the chats
 * must come through whole, and with them what the full-text index knows of each.
 */
class DropUnusedTablesTest {

    static final String MIGRATION = "027-drop-unused-tables.sql";

    static final List<String> DROPPED_TABLES = List.of("task_state", "plan_cache", "teaching_log",
            "user_preferences", "generated_skills", "skill_repair_log");

    static final List<String> DROPPED_COLUMNS = List.of("compressed", "compressed_content");

    private String production;
    private JdbcTemplate jdbc;
    /** The columns of conversations that stay, as they were before the migration. */
    private List<String> kept;

    /**
     * The schema as 026 left it, opened as production opens it, with rows in every table that goes
     * and chats whose rows have values in both columns that go.
     */
    private void inUse(Path file) throws Exception {
        production = MigratedDatabase.productionParameters();
        jdbc = MigratedDatabase.before(file, MIGRATION, production);
        assertEquals(1, jdbc.queryForObject("PRAGMA foreign_keys", Integer.class), "as production: " + production);

        jdbc.update("INSERT INTO users (id, display_name) VALUES ('u1', 'Owner'), ('u2', 'Guest')");
        jdbc.update("INSERT INTO chat_sessions (id, user_id, title) VALUES "
                + "('routers', 'u1', 'Routers'), ('garden', 'u1', 'Garden'), ('theirs', 'u2', 'Theirs')");
        jdbc.update("INSERT INTO active_session (user_id, session_id) VALUES ('u1', 'garden')");

        row("r1", "u1", "routers", "user", "the attic router drops its uplink", 1, "uplink trouble",
                "{\"taskId\":\"a1b2c3d4\"}", null);
        // A row that is gone again: the rows after it have rowids that are not their position.
        row("r0", "u1", "routers", "user", "never mind", 0, null, null, null);
        row("r2", "u1", "routers", "assistant", "restart the attic router and watch the uplink", 1, null,
                null, "what only the owner reads");
        jdbc.update("DELETE FROM conversations WHERE id = 'r0'");
        row("r3", "u1", "routers", "system", "Unknown command. Try /help", 0, "a command", null, null);
        row("r4", "u1", "routers", "progress", "**Step 1** -- reading the log", 0, null,
                "{\"taskId\":\"a1b2c3d4\",\"progress\":{}}", null);
        row("r5", "u1", "garden", "user", "tomatoes need water at dusk", 0, null, null, null);
        row("r6", "u1", "garden", "assistant", "water the tomatoes after sunset", 0, "tomato care", null, null);
        row("r7", "u2", "theirs", "user", "my router is fine", 1, "fine", null, null);
        jdbc.update("INSERT INTO file_attachments (id, user_id, original_name, stored_name) "
                + "VALUES ('f1', 'u1', 'uplink.log', 'f1.log')");
        jdbc.update("INSERT INTO message_attachments (message_id, file_id) VALUES ('r1', 'f1')");

        jdbc.update("INSERT INTO task_state (task_id, user_id, status) VALUES ('t1', 'u1', 'queued')");
        jdbc.update("INSERT INTO plan_cache (cache_key, plan, skill_versions) VALUES ('k1', '{}', '{}')");
        jdbc.update("INSERT INTO teaching_log (user_id, observation) VALUES ('u1', 'prefers short answers')");
        jdbc.update("INSERT INTO user_preferences (user_id) VALUES ('u1')");
        jdbc.update("INSERT INTO generated_skills (skill_name, created_by) VALUES ('ping_host', 'u1')");
        jdbc.update("INSERT INTO skill_repair_log (skill_name, user_id) VALUES ('ping_host', 'u1')");
        jdbc.update("INSERT INTO session_summaries (session_id, user_id, summary) "
                + "VALUES ('routers', 'u1', 'the attic router and its uplink')");

        kept = new ArrayList<>(columns());
        assertTrue(kept.containsAll(DROPPED_COLUMNS), "026 has both columns: " + kept);
        kept.removeAll(DROPPED_COLUMNS);
    }

    private void row(String id, String user, String session, String role, String content,
                     int compressed, String compressedContent, String metadata, String privateContent) {
        jdbc.update("INSERT INTO conversations (id, user_id, session_id, role, content, compressed, "
                        + "compressed_content, metadata, private_content) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, user, session, role, content, compressed, compressedContent, metadata, privateContent);
    }

    private void migrate(Path file) throws Exception {
        jdbc = MigratedDatabase.at(file, production);
    }

    private List<String> columns() {
        return jdbc.queryForList("SELECT name FROM pragma_table_info('conversations')", String.class);
    }

    /** Every row of conversations with the rowid it is stored under, in the columns that stay. */
    private List<Map<String, Object>> chats() {
        return jdbc.queryForList("SELECT rowid AS stored_under, " + String.join(", ", kept)
                + " FROM conversations ORDER BY rowid");
    }

    /** The same, in every column the table has. */
    private List<Map<String, Object>> chatsWhole() {
        return jdbc.queryForList("SELECT rowid AS stored_under, * FROM conversations ORDER BY rowid");
    }

    /** The ids of the rows the full-text index finds for a word, read through the rowid it holds for each. */
    private List<String> found(String word) {
        return jdbc.queryForList("SELECT c.id FROM conversations_fts f JOIN conversations c ON c.rowid = f.rowid "
                + "WHERE conversations_fts MATCH ? ORDER BY c.id", String.class, '"' + word + '"');
    }

    private List<String> named(String type, String table) {
        return jdbc.queryForList("SELECT name FROM sqlite_master WHERE type = ? AND tbl_name = ? ORDER BY name",
                String.class, type, table);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private void sound() {
        jdbc.update("INSERT INTO conversations_fts(conversations_fts) VALUES('integrity-check')");
        assertEquals(List.of("ok"), jdbc.queryForList("PRAGMA integrity_check", String.class));
        assertEquals(List.of(), jdbc.queryForList("PRAGMA foreign_key_check"), "no row points at a missing one");
    }

    @Test
    @DisplayName("the six tables go, rows and indexes with them, and conversations loses the two columns and the index on one")
    void whatNothingUsesGoes(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("t.db");
        inUse(file);

        migrate(file);

        for (String table : DROPPED_TABLES) {
            assertEquals(List.of(), jdbc.queryForList("SELECT name FROM sqlite_master WHERE tbl_name = ?",
                    String.class, table), table + ", and every index on it");
        }
        List<String> columns = columns();
        for (String column : DROPPED_COLUMNS) assertFalse(columns.contains(column), column + " in " + columns);
        assertTrue(columns.containsAll(kept), "every other column stays: " + columns);
        List<String> indexes = named("index", "conversations");
        assertFalse(indexes.contains("idx_conversations_session_compressed"), indexes.toString());
        assertTrue(indexes.containsAll(List.of("idx_conv_user_session", "idx_conv_timestamp",
                "idx_conv_session_role", "idx_conv_session_role_ts")), "the indexes the chat queries use: " + indexes);
        assertTrue(named("trigger", "conversations").containsAll(List.of("conversations_fts_insert",
                "conversations_fts_update", "conversations_fts_delete")), "the full-text triggers");
        sound();
        // Mutation: leave 015's index in place -> SQLite refuses to drop the column it names.
    }

    @Test
    @DisplayName("session_summaries stays, emptied: the release before this one deletes from it with every chat it deletes")
    void theSummariesTableStays(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("t.db");
        inUse(file);

        migrate(file);

        assertEquals(0, count("session_summaries"), "nothing reads a summary, and nothing deletes one any more");
        // The statement that release runs, as it runs it, on the schema this one leaves it.
        assertEquals(0, jdbc.update("DELETE FROM session_summaries WHERE user_id = ? AND session_id = ?",
                "u1", "routers"));
        // Mutation: drop the table here -> a fallback to that release cannot delete a chat.
    }

    @Test
    @DisplayName("every chat row comes through as it was, under the rowid it had, and search finds what it found")
    void theChatsComeThrough(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("t.db");
        inUse(file);
        List<Map<String, Object>> before = chats();
        assertEquals(List.of(1, 3, 4, 5, 6, 7, 8),
                before.stream().map(r -> ((Number) r.get("stored_under")).intValue()).toList(),
                "seven rows, under rowids that are not their positions");

        migrate(file);

        assertEquals(before, chats(), "each row: its rowid, and every column that stays");
        assertEquals("what only the owner reads",
                jdbc.queryForObject("SELECT private_content FROM conversations WHERE id = 'r2'", String.class));
        // The index holds a rowid for each user and assistant row: it must still be that row's.
        assertEquals(List.of("r1", "r2", "r7"), found("router"));
        assertEquals(List.of("r1", "r2"), found("uplink"));
        assertEquals(List.of("r5", "r6"), found("tomatoes"));
        assertEquals(List.of(), found("command"), "a command's reply was never indexed");
        assertEquals(List.of(), found("reading"), "nor a progress row");
        // And the search the page runs, which reads each match's chat through that rowid.
        var conversations = new ConversationService(jdbc);
        var routers = conversations.searchMessages("u1", "router");
        assertEquals(List.of("routers"), routers.stream().map(r -> r.get("session_id")).toList(),
                "the owner's chat, and not the other account's");
        assertTrue(String.valueOf(routers.getFirst().get("snippet")).contains("attic"), routers.toString());
        assertEquals(List.of("garden"), conversations.searchMessages("u1", "tomatoes sunset").stream()
                .map(r -> r.get("session_id")).toList());
        assertEquals(1, count("message_attachments"), "the file sent with a message is still linked to it");
        sound();
    }

    @Test
    @DisplayName("afterwards a message is saved, changed and deleted as before, and search follows each")
    void chatsGoOnAfterwards(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("t.db");
        inUse(file);
        migrate(file);
        var conversations = new ConversationService(jdbc);

        String asked = conversations.saveMessage("u1", "routers", "user", "is the cellar switch a router too?");
        assertEquals(List.of(asked), found("cellar"), "a new message is indexed");
        assertEquals(4, found("router").size());

        jdbc.update("UPDATE conversations SET content = 'restart the attic gateway' WHERE id = 'r2'");
        assertEquals(List.of("r2"), found("gateway"), "a changed one is found by its new words");
        assertEquals(List.of("r1"), found("uplink"), "and no longer by the old ones");
        jdbc.update("UPDATE conversations SET content = 'Unknown command. Try /status' WHERE id = 'r3'");
        assertEquals(List.of(), found("status"), "a command's reply stays out of the index");

        jdbc.update("DELETE FROM conversations WHERE id = 'r5'");
        assertEquals(List.of("r6"), found("tomatoes"), "a deleted one is not found");
        // The chat with a command's reply, a progress row and a file sent in it, deleted as the page deletes it.
        conversations.deleteSession("u1", "routers");
        assertEquals(List.of("r7"), found("router"), "the other account's row is all that is left of the word");
        assertEquals(List.of("r6", "r7"), jdbc.queryForList("SELECT id FROM conversations ORDER BY id", String.class));
        assertEquals(0, count("message_attachments"));
        assertEquals(1, count("file_attachments"), "the file itself is kept");
        sound();
    }

    @Test
    @DisplayName("a new database, opened as production opens it, migrates through every changeset and has none of them")
    void aNewDatabaseNeverHasThem(@TempDir Path tmp) throws Exception {
        jdbc = MigratedDatabase.at(tmp.resolve("t.db"), MigratedDatabase.productionParameters());

        assertEquals(1, jdbc.queryForObject("PRAGMA foreign_keys", Integer.class));
        List<String> tables = jdbc.queryForList("SELECT name FROM sqlite_master WHERE type = 'table'", String.class);
        for (String table : DROPPED_TABLES) assertFalse(tables.contains(table), table + " in " + tables);
        assertTrue(tables.containsAll(List.of("conversations", "chat_sessions")), tables.toString());
        List<String> columns = columns();
        for (String column : DROPPED_COLUMNS) assertFalse(columns.contains(column), column + " in " + columns);
        // And it takes a chat: a user, a message, a search.
        jdbc.update("INSERT INTO users (id, display_name) VALUES ('u1', 'Owner')");
        var conversations = new ConversationService(jdbc);
        String chat = conversations.createSession("u1", "Routers");
        conversations.saveMessage("u1", chat, "user", "the attic router drops its uplink");
        assertEquals(List.of(chat), conversations.searchMessages("u1", "uplink").stream()
                .map(r -> r.get("session_id")).toList());
        sound();
    }

    @Test
    @DisplayName("refused by SQLite, the migration changes nothing: every table, both columns and every row are as they were")
    void aRefusalChangesNothing(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("t.db");
        inUse(file);
        // What no changeset made, and SQLite will not drop a column under: an index that names it.
        jdbc.execute("CREATE INDEX made_by_hand ON conversations (compressed_content)");
        List<Map<String, Object>> before = chatsWhole();

        Exception refused = assertThrows(Exception.class, () -> migrate(file));

        // Its last statement was the one refused: all before it had run, and are undone.
        assertTrue(refused.toString().contains("made_by_hand"), refused.toString());
        for (String table : DROPPED_TABLES) assertEquals(1, count(table), table + " and its row");
        assertEquals(1, count("session_summaries"), "the summary is still there");
        assertEquals(before, chatsWhole(), "every row, the two columns and their values included");
        assertTrue(named("index", "conversations").contains("idx_conversations_session_compressed"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM DATABASECHANGELOG WHERE FILENAME LIKE ?",
                Integer.class, "%" + MIGRATION), "not recorded as run, so the next start runs it again");
        sound();
        // The release before this one starts on that, and once the obstacle is gone this one does.
        jdbc.execute("DROP INDEX made_by_hand");
        migrate(file);
        assertEquals(List.of(), jdbc.queryForList("SELECT name FROM sqlite_master WHERE name = 'task_state'", String.class));
        // Mutation: two changesets, or one outside a transaction -> the tables are gone and the columns are not.
    }
}
