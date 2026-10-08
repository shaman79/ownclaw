package com.ownclaw.conversation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownclaw.agent.AgentResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Manages chat sessions and message persistence.
 * Supports multiple named sessions per user with search.
 */
@Service
public class ConversationService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbc;

    public ConversationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Save a message to the conversation store.
     * Updates the session's updated_at timestamp and preview.
     *
     * @return the generated message ID
     */
    public String saveMessage(String userId, String sessionId, String role, String content) {
        return saveMessage(userId, sessionId, role, content, List.of());
    }

    /**
     * Save a message with optional file attachments.
     *
     * @return the generated message ID
     */
    public String saveMessage(String userId, String sessionId, String role, String content,
                              List<String> attachmentIds) {
        return saveMessage(userId, sessionId, role, content, attachmentIds, null);
    }

    /**
     * @param taskId the agent task this message is the outcome of, or null. Stored in the
     *               metadata column (JSON), only when it is a well-formed 8-character task id.
     */
    public String saveMessage(String userId, String sessionId, String role, String content,
                              List<String> attachmentIds, String taskId) {
        return saveMessage(userId, sessionId, role, content, attachmentIds, taskId, null);
    }

    /**
     * @param content        the cloud-safe text. Everything that feeds a prompt reads this
     *                       column: a chat task's context ({@link #contextOf}), search, the
     *                       preview.
     * @param privateContent what only the owner's chat shows in its place, or null. Read back by
     *                       {@link #getSessionMessages} alone, so a reader added later gets the
     *                       safe text unless it asks for this one by name.
     */
    public String saveMessage(String userId, String sessionId, String role, String content,
                              List<String> attachmentIds, String taskId, String privateContent) {
        String messageId = UUID.randomUUID().toString();
        jdbc.update("""
            INSERT INTO conversations (id, user_id, session_id, role, content, metadata, private_content)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """, messageId, userId, sessionId, role, content, metadataOf(taskId), privateContent);

        // Link file attachments to this message
        if (attachmentIds != null) {
            for (String fileId : attachmentIds) {
                jdbc.update("INSERT OR IGNORE INTO message_attachments (message_id, file_id) VALUES (?, ?)",
                        messageId, fileId);
            }
        }
        touch(sessionId, role, content);
        return messageId;
    }

    /**
     * Save a running task's progress row ({@code TaskChat}), as {@link #saveMessage} saves a row
     * of role {@code progress} -- but only while its chat exists. A task can work for many
     * minutes after its message was saved; a row saved into a chat the owner had deleted in the
     * meantime was a row of no chat, which no page showed and no delete reached.
     *
     * @param header what the row is about, as data ({@code TaskChat.Header}): kept in the row's
     *               metadata beside its task, so the page draws it again after a reload
     * @return whether it was saved: false when the chat is gone
     */
    public boolean saveProgress(String userId, String sessionId, String content, String taskId,
                                String privateContent, Map<String, Object> header) {
        var metadata = new LinkedHashMap<String, Object>();
        if (isTaskId(taskId)) metadata.put("taskId", taskId);
        metadata.put("progress", header);
        String json;
        try {
            json = JSON.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("a progress row's metadata is not JSON: " + e.getOriginalMessage(), e);
        }
        int saved = jdbc.update("""
            INSERT INTO conversations (id, user_id, session_id, role, content, metadata, private_content)
            SELECT ?, ?, ?, 'progress', ?, ?, ?
            WHERE EXISTS (SELECT 1 FROM chat_sessions WHERE id = ? AND user_id = ?)
            """, UUID.randomUUID().toString(), userId, sessionId, content, json,
                privateContent, sessionId, userId);
        if (saved == 0) return false;
        touch(sessionId, "progress", content);
        return true;
    }

    /** A row's metadata (JSON): its task, when it has a well-formed 8-character task id. */
    private static String metadataOf(String taskId) {
        return isTaskId(taskId) ? "{\"taskId\":\"" + taskId + "\"}" : null;
    }

    private static boolean isTaskId(String taskId) {
        return taskId != null && taskId.matches("[0-9a-f]{8}");
    }

    /**
     * Update the chat's timestamp and preview after a row is saved in it (the first user message,
     * whole, is the preview; the sidebar lays it out).
     */
    private void touch(String sessionId, String role, String content) {
        jdbc.update("""
            UPDATE chat_sessions SET updated_at = datetime('now'),
                preview = COALESCE(preview, CASE WHEN ? = 'user' THEN ? ELSE preview END)
            WHERE id = ?
            """, role, content, sessionId);
    }

    /**
     * Save a task's answer as the assistant row of the chat turn it answers: the safe text as the
     * content, the owner's private text beside it, and the task id, which links the answer to what
     * the task did. The one rule for what a turn's answer saves: the web chat, Telegram and the
     * ops API's chat turns all save theirs here.
     *
     * @return the new row's id
     */
    public String saveAnswer(String userId, String sessionId, AgentResult result) {
        return saveMessage(userId, sessionId, "assistant", result.response(), List.of(),
                result.taskId(), result.ownerText());
    }

    /**
     * A chat task's context: the conversation of the session {@code messageId} was saved in,
     * oldest first, each message whole, with the task each answer came from -- every question
     * asked before that message, and every answer given so far. Not that message itself, which
     * is the task's own text.
     * <p>
     * The session is the one the task's message belongs to, not whichever chat is open now: the
     * owner can switch chats while a task waits in the queue. A question typed after this one is
     * left out -- it is its own task, which runs next and reads this one's answer; read here, it
     * was presented as already asked, before the question being answered. An answer is read
     * whenever it came: the one to a question queued before this one arrives while this task
     * waits, and it is what the owner's follow-up builds on.
     * <p>
     * Only the conversation: a {@code system} row is a command's reply or the setup wizard's
     * prompt -- /files lists uploaded files by name, and a file's name can carry an account
     * number -- and no part of what is being continued; a {@code progress} row is the owner's
     * view of a task at work ({@code TaskChat}), and its private text a summary only he may read.
     * Rows an earlier summariser marked {@code compressed} are read like any other; they were
     * marked, never changed.
     */
    public List<Map<String, Object>> contextOf(String userId, String messageId) {
        return jdbc.queryForList("""
            SELECT id, role, content, timestamp,
                   CASE WHEN json_valid(metadata) THEN json_extract(metadata, '$.taskId') END AS task_id
            FROM conversations
            WHERE user_id = ? AND id != ?
              AND session_id = (SELECT session_id FROM conversations WHERE id = ? AND user_id = ?)
              AND (role = 'assistant'
                   OR role = 'user' AND rowid < (SELECT rowid FROM conversations WHERE id = ? AND user_id = ?))
            ORDER BY timestamp ASC, rowid ASC
            """, userId, messageId, messageId, userId, messageId, userId);
    }

    /** The chat a message of this user was saved in, or null when he has no such message. */
    public String sessionOf(String userId, String messageId) {
        List<String> session = jdbc.queryForList(
                "SELECT session_id FROM conversations WHERE id = ? AND user_id = ?",
                String.class, messageId, userId);
        return session.isEmpty() ? null : session.getFirst();
    }

    // ── Session Management ──

    /**
     * Get or create the current session ID for a user.
     * Returns the active session, or creates a new one if none exists.
     */
    public String getCurrentSession(String userId) {
        List<String> active = jdbc.queryForList(
                "SELECT session_id FROM active_session WHERE user_id = ?",
                String.class, userId);
        if (!active.isEmpty()) {
            return active.getFirst();
        }
        // No active session — create one
        return createSession(userId, "New Chat");
    }

    /**
     * Create a new chat session and make it the active session.
     *
     * @return the new session ID
     */
    public String createSession(String userId, String title) {
        String sessionId = insertSession(userId, title, "chat");
        setActiveSession(userId, sessionId);
        return sessionId;
    }

    /** The title of a chat an ops check starts. */
    public static final String OPS_TITLE = "Ops check";

    /**
     * A chat for an ops check to run in (POST /api/ops/agent/run with sessionId "new"): the
     * operator's, not the owner's. Of kind 'ops', which the owner's chat list and search leave
     * out ({@link #listSessions}, {@link #searchMessages}) -- each check used to start a chat in
     * the owner's list. Never the open one, so what the owner types next is still filed in the
     * chat that is open.
     *
     * @return the new session ID
     */
    public String createOpsChat(String userId) {
        return insertSession(userId, OPS_TITLE, "ops");
    }

    /** Whether this user has this chat, of any kind: one an ops check runs in included. */
    public boolean hasChat(String userId, String sessionId) {
        return !jdbc.queryForList("SELECT 1 FROM chat_sessions WHERE id = ? AND user_id = ?",
                Integer.class, sessionId, userId).isEmpty();
    }

    /** A new chat of this kind; never the open one. Every chat is created here. */
    private String insertSession(String userId, String title, String kind) {
        String sessionId = UUID.randomUUID().toString().substring(0, 12);
        jdbc.update("""
            INSERT INTO chat_sessions (id, user_id, title, kind) VALUES (?, ?, ?, ?)
            """, sessionId, userId, title, kind);
        return sessionId;
    }

    /** The title of the pinned chat that scheduled results are delivered into. */
    static final String SCHEDULED_TITLE = "📌 Scheduled";

    /**
     * The user's pinned chat for the results of scheduled runs, created on first use.
     * <p>
     * A scheduled result used to be saved into whichever chat was open when the run finished, so
     * the morning digest and the lunch menu turned up in the middle of an unrelated conversation
     * and went into the prompt of its next turn. They are kept here instead, in one chat of their
     * own. Getting it never makes it the open chat: it opens when the owner opens it. Deleting it
     * clears the old reports, and the next delivery creates a fresh one. Synchronized, so two
     * runs finishing together cannot create two.
     */
    public synchronized String scheduledSession(String userId) {
        List<String> pinned = jdbc.queryForList("""
            SELECT id FROM chat_sessions
            WHERE user_id = ? AND kind = 'scheduled' AND archived = 0
            ORDER BY created_at, rowid LIMIT 1
            """, String.class, userId);
        if (!pinned.isEmpty()) return pinned.getFirst();
        return insertSession(userId, SCHEDULED_TITLE, "scheduled");
    }

    /**
     * Switch the active session for a user.
     */
    public void setActiveSession(String userId, String sessionId) {
        jdbc.update("""
            INSERT INTO active_session (user_id, session_id) VALUES (?, ?)
            ON CONFLICT(user_id) DO UPDATE SET session_id = excluded.session_id
            """, userId, sessionId);
    }

    /**
     * List the user's chats: the pinned chat of scheduled results first, then the rest, most
     * recent first -- not the chats ops checks ran in ({@link #createOpsChat}). Returns id, title,
     * preview, created_at, updated_at, archived, kind, message_count.
     */
    public List<Map<String, Object>> listSessions(String userId, boolean includeArchived) {
        String archiveFilter = includeArchived ? "" : "AND s.archived = 0 ";
        return jdbc.queryForList("""
            SELECT s.id, s.title, s.preview, s.created_at, s.updated_at, s.archived, s.kind,
                   (SELECT COUNT(*) FROM conversations c WHERE c.session_id = s.id AND c.role IN ('user','assistant')) AS message_count
            FROM chat_sessions s
            WHERE s.user_id = ? AND s.kind != 'ops' %s
            ORDER BY s.kind = 'scheduled' DESC, s.updated_at DESC
            """.formatted(archiveFilter), userId);
    }

    /**
     * Rows of a chat, oldest first, and whether the chat has rows earlier than these.
     */
    public record Page(List<Map<String, Object>> messages, boolean hasMore) {}

    /**
     * Get the messages in a session, oldest first -- for the owner's own chat, on reload: all of
     * them, or a page of them.
     * <p>
     * A page is the newest {@code limit} rows before the row {@code before} names, or of the
     * whole chat when it names none. The web page draws the newest page first, then asks for the
     * page before the oldest row it has drawn, so every row is in exactly one page. Rows are
     * ordered by timestamp, which has seconds, and the rows of one second -- a running task saves
     * its progress rows seconds apart or less -- in the order they were saved (rowid), so a page
     * boundary between two rows of one second neither repeats nor skips either. A row id not of
     * this chat gives an empty page.
     * <p>
     * The one reader of private_content: a private answer shows here as it did live, and
     * nowhere else. A row without one, including every row from before the column, shows its
     * content. A progress row comes with its header as data ({@link #saveProgress}), as it came
     * live; one from before the header was kept has none, and shows its content as it is. Each
     * row comes with its id, as a live frame names it: the page marks a message the running task
     * has read, or handed on, by its row.
     *
     * @param before the id of the row the page ends before, or null for the newest rows
     * @param limit  how many rows the page holds at most, or null for every row before
     *               {@code before}
     */
    public Page getSessionMessages(String userId, String sessionId, String before, Integer limit) {
        // One row past the page, when it has a size, says whether earlier rows exist; a limit of
        // -1 is none.
        List<Map<String, Object>> rows = new ArrayList<>(jdbc.queryForList("""
            SELECT id, role, COALESCE(private_content, content) AS content, timestamp,
                   CASE WHEN json_valid(metadata) THEN json_extract(metadata, '$.taskId') END AS task_id,
                   CASE WHEN json_valid(metadata) THEN json_extract(metadata, '$.progress') END AS progress
            FROM conversations
            WHERE user_id = ? AND session_id = ? AND role != 'status'
              AND (? IS NULL OR (timestamp, rowid) <
                   (SELECT timestamp, rowid FROM conversations WHERE id = ? AND session_id = ?))
            ORDER BY timestamp DESC, rowid DESC
            LIMIT ?
            """, userId, sessionId, before, before, sessionId, limit == null ? -1 : limit + 1));
        boolean hasMore = limit != null && rows.size() > limit;
        if (hasMore) rows.removeLast();
        Collections.reverse(rows);
        for (var row : rows) {
            if (row.get("progress") instanceof String header) row.put("progress", headerOf(header));
        }
        return new Page(rows, hasMore);
    }

    /** A progress row's header, as the JSON it was saved as; null when it does not read as one. */
    private static JsonNode headerOf(String json) {
        try {
            JsonNode header = JSON.readTree(json);
            return header.isObject() ? header : null;
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /**
     * What this user's chat has chosen of the owner's defaults: {@link ChatOptions#NONE} for a
     * chat that has chosen nothing, a new one included, and for one that is not this user's.
     */
    public ChatOptions chatOptions(String userId, String sessionId) {
        List<ChatOptions> chosen = jdbc.query(
                "SELECT cost_mode, thinking_effort FROM chat_sessions WHERE id = ? AND user_id = ?",
                (rs, n) -> new ChatOptions(rs.getString("cost_mode"), rs.getString("thinking_effort")),
                sessionId, userId);
        return chosen.isEmpty() ? ChatOptions.NONE : chosen.getFirst();
    }

    /** Make what a message was sent with its chat's choice, for the chat's next messages. This user's chat only. */
    public void setChatOptions(String userId, String sessionId, ChatOptions options) {
        jdbc.update("UPDATE chat_sessions SET cost_mode = ?, thinking_effort = ? WHERE id = ? AND user_id = ?",
                options.costMode(), options.effort(), sessionId, userId);
    }

    /**
     * Rename a session.
     */
    public void renameSession(String userId, String sessionId, String newTitle) {
        jdbc.update("UPDATE chat_sessions SET title = ? WHERE id = ? AND user_id = ?",
                newTitle, sessionId, userId);
    }

    /**
     * Archive (soft-delete) a session.
     */
    public void archiveSession(String userId, String sessionId) {
        jdbc.update("UPDATE chat_sessions SET archived = 1 WHERE id = ? AND user_id = ?",
                sessionId, userId);
        // If this was the active session, clear it
        jdbc.update("DELETE FROM active_session WHERE user_id = ? AND session_id = ?",
                userId, sessionId);
    }

    /**
     * Permanently delete a session, its messages and their links to the files sent with them;
     * the files themselves stay, listed by /files. All of it or nothing, in one transaction.
     * If the deleted session was the active one, switches to the most recent remaining
     * conversation; the pinned chat of scheduled results is never chosen for it.
     * <p>
     * A message's links to its files hold a foreign key to it, which production enforces: left in
     * place, they made every chat a file had been sent in undeletable. The open-chat pointer, the
     * first thing deleted, was committed alone all the same, so once the open chat had failed to
     * be deleted, the owner's next message went to a new, empty chat.
     */
    public void deleteSession(String userId, String sessionId) {
        new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())).executeWithoutResult(tx -> {
            // Remove the active pointer first (references chat_sessions via FK)
            jdbc.update("DELETE FROM active_session WHERE user_id = ? AND session_id = ?",
                    userId, sessionId);
            jdbc.update("DELETE FROM message_attachments WHERE message_id IN "
                    + "(SELECT id FROM conversations WHERE user_id = ? AND session_id = ?)", userId, sessionId);
            jdbc.update("DELETE FROM conversations WHERE user_id = ? AND session_id = ?",
                    userId, sessionId);
            jdbc.update("DELETE FROM session_summaries WHERE user_id = ? AND session_id = ?",
                    userId, sessionId);
            jdbc.update("DELETE FROM chat_sessions WHERE id = ? AND user_id = ?",
                    sessionId, userId);

            // If there is no active session now, switch to the most recent remaining conversation --
            // never the pinned chat of scheduled results, which is open only when the owner opens it.
            List<String> active = jdbc.queryForList(
                    "SELECT session_id FROM active_session WHERE user_id = ?",
                    String.class, userId);
            if (active.isEmpty()) {
                List<String> remaining = jdbc.queryForList(
                        "SELECT id FROM chat_sessions WHERE user_id = ? AND archived = 0 AND kind = 'chat' "
                                + "ORDER BY updated_at DESC LIMIT 1",
                        String.class, userId);
                if (!remaining.isEmpty()) {
                    setActiveSession(userId, remaining.getFirst());
                }
                // If no sessions remain, getCurrentSession() will create one when needed
            }
        });
    }

    /**
     * Full-text search across a user's chats -- not those ops checks ran in: every session with a
     * match, once, best match first, each with a snippet of its best-matching message.
     * <p>
     * Grouped here rather than in the page. The query used to return the 30 best-matching
     * MESSAGES and the page kept one per session, so a chat with 30 matches hid every other chat
     * that matched at all. SQLite runs snippet() only in a plain full-text scan -- not beside a
     * window function or an aggregate -- so the best message of each session is picked first,
     * and a second full-text scan, joined to those rows, makes a snippet for them alone. 64
     * tokens is the most snippet() accepts.
     */
    public List<Map<String, Object>> searchMessages(String userId, String query) {
        String match = ftsQuery(query);
        return jdbc.queryForList("""
            WITH best AS (
                SELECT rid, rank FROM (
                    SELECT fts.rowid AS rid, fts.rank AS rank,
                           row_number() OVER (PARTITION BY c.session_id ORDER BY fts.rank) AS n
                    FROM conversations_fts fts
                    JOIN conversations c ON c.rowid = fts.rowid
                    JOIN chat_sessions s ON s.id = c.session_id
                    WHERE conversations_fts MATCH ?
                      AND c.user_id = ?
                      AND s.archived = 0
                      AND s.kind != 'ops')
                WHERE n = 1)
            SELECT s.id AS session_id, s.title, s.updated_at,
                   snippet(conversations_fts, 0, '<mark>', '</mark>', '...', 64) AS snippet,
                   c.role, c.timestamp AS match_timestamp
            FROM best
            JOIN conversations_fts ON conversations_fts.rowid = best.rid
            JOIN conversations c ON c.rowid = best.rid
            JOIN chat_sessions s ON s.id = c.session_id
            WHERE conversations_fts MATCH ?
            ORDER BY best.rank
            """, match, userId, match);
    }

    /**
     * Turn what someone typed into a safe FTS5 MATCH expression.
     * <p>
     * The raw string was passed straight through, and MATCH is an expression language: a bare
     * {@code .}, {@code '} or {@code -} is a syntax error, and SQLite answers with an exception
     * rather than with no results. The sidebar search fires on every keystroke, so typing an
     * ordinary thing -- {@code claw.example.com}, {@code don't}, or the name of a skill such as
     * {@code web_search_bikes} -- produced a 500 partway through the word. Searching for this
     * system's own vocabulary was reliably broken.
     * <p>
     * Every term is quoted, which makes it a literal rather than syntax, and embedded quotes are
     * doubled. Terms are ANDed so a multi-word search narrows, which is what people expect.
     */
    static String ftsQuery(String raw) {
        if (raw == null || raw.isBlank()) return "\"\"";
        var terms = new java.util.ArrayList<String>();
        for (String term : raw.trim().split("\\s+")) {
            // Keep only what FTS5 tokenises; a term of pure punctuation matches nothing anyway.
            String cleaned = term.replaceAll("[^\\p{L}\\p{N}_'-]", " ").trim();
            if (cleaned.isEmpty()) continue;
            terms.add('"' + cleaned.replace("\"", "\"\"") + '"');
        }
        return terms.isEmpty() ? "\"\"" : String.join(" AND ", terms);
    }

    /**
     * Auto-generate a session title from the first user message.
     * Called after the first message in a new session.
     */
    public void autoTitleIfNeeded(String userId, String sessionId, String firstMessage) {
        List<String> currentTitle = jdbc.queryForList(
                "SELECT title FROM chat_sessions WHERE id = ? AND user_id = ?",
                String.class, sessionId, userId);
        if (!currentTitle.isEmpty() && "New Chat".equals(currentTitle.getFirst())) {
            String title = generateTitle(firstMessage);
            jdbc.update("UPDATE chat_sessions SET title = ? WHERE id = ? AND user_id = ?",
                    title, sessionId, userId);
        }
    }

    /**
     * The first line of the message that has text on it, whole; the sidebar lays it out. It used
     * to end at the first '.', so "Check 192.0.2.1" became "Check 192", and was cut at 60.
     */
    static String generateTitle(String message) {
        if (message == null || message.isBlank()) return "New Chat";
        return message.strip().lines().findFirst().orElseThrow().strip();
    }
}
