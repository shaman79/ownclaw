package com.ownclaw.conversation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Manages chat sessions and message persistence.
 * Supports multiple named sessions per user with search.
 * Integrates with ConversationCompressor for rolling history summarization.
 */
@Service
public class ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationService.class);

    private final JdbcTemplate jdbc;
    private final ConversationCompressor compressor;
    private final ExecutorService compressionExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "conversation-compressor");
                t.setDaemon(true);
                return t;
            });

    public ConversationService(JdbcTemplate jdbc, ConversationCompressor compressor) {
        this.jdbc = jdbc;
        this.compressor = compressor;
    }

    /**
     * Save a message to the conversation store.
     * Updates the session's updated_at timestamp and preview snippet.
     * Triggers compression check after saving.
     */
    public void saveMessage(String userId, String sessionId, String role, String content) {
        jdbc.update("""
            INSERT INTO conversations (id, user_id, session_id, role, content)
            VALUES (?, ?, ?, ?, ?)
            """, UUID.randomUUID().toString(), userId, sessionId, role, content);

        // Update session timestamp and preview (first user message becomes the preview)
        jdbc.update("""
            UPDATE chat_sessions SET updated_at = datetime('now'),
                preview = COALESCE(preview, CASE WHEN ? = 'user' THEN substr(?, 1, 120) ELSE preview END)
            WHERE id = ?
            """, role, content, sessionId);

        // Trigger compression check on a background thread so it never blocks
        // the caller (web thread or agent loop). The compressor may call the LLM
        // which can take minutes if Ollama is busy.
        compressionExecutor.execute(() -> {
            try {
                compressor.compressIfNeeded(userId, sessionId);
            } catch (Exception e) {
                log.warn("Background compression failed (non-fatal): {}", e.getMessage());
            }
        });
    }

    /**
     * Get the last N messages in a session (for LLM context).
     */
    public List<Map<String, Object>> getRecentMessages(String userId, String sessionId, int limit) {
        return jdbc.queryForList("""
            SELECT role, content, timestamp FROM conversations
            WHERE user_id = ? AND session_id = ?
            ORDER BY timestamp DESC LIMIT ?
            """, userId, sessionId, limit);
    }

    /**
     * Get the rolling summary for a session (compressed history of older messages).
     *
     * @return summary text, or null if no compression has occurred yet
     */
    public String getSessionSummary(String userId, String sessionId) {
        return compressor.getSessionSummary(userId, sessionId);
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
        String sessionId = UUID.randomUUID().toString().substring(0, 12);
        jdbc.update("""
            INSERT INTO chat_sessions (id, user_id, title) VALUES (?, ?, ?)
            """, sessionId, userId, title);
        setActiveSession(userId, sessionId);
        return sessionId;
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
     * List all sessions for a user, most recent first.
     * Returns id, title, preview, created_at, updated_at, message_count.
     */
    public List<Map<String, Object>> listSessions(String userId, boolean includeArchived) {
        String archiveFilter = includeArchived ? "" : "AND s.archived = 0 ";
        return jdbc.queryForList("""
            SELECT s.id, s.title, s.preview, s.created_at, s.updated_at, s.archived,
                   (SELECT COUNT(*) FROM conversations c WHERE c.session_id = s.id AND c.role IN ('user','assistant')) AS message_count
            FROM chat_sessions s
            WHERE s.user_id = ? %s
            ORDER BY s.updated_at DESC
            """.formatted(archiveFilter), userId);
    }

    /**
     * Get all messages in a session, chronological.
     */
    public List<Map<String, Object>> getSessionMessages(String userId, String sessionId) {
        return jdbc.queryForList("""
            SELECT role, content, timestamp FROM conversations
            WHERE user_id = ? AND session_id = ? AND role != 'status'
            ORDER BY timestamp ASC
            """, userId, sessionId);
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
     * Permanently delete a session and its messages.
     * If the deleted session was the active one, switches to the most recent remaining session.
     */
    public void deleteSession(String userId, String sessionId) {
        // Remove the active pointer first (references chat_sessions via FK)
        jdbc.update("DELETE FROM active_session WHERE user_id = ? AND session_id = ?",
                userId, sessionId);
        jdbc.update("DELETE FROM conversations WHERE user_id = ? AND session_id = ?",
                userId, sessionId);
        jdbc.update("DELETE FROM session_summaries WHERE user_id = ? AND session_id = ?",
                userId, sessionId);
        jdbc.update("DELETE FROM chat_sessions WHERE id = ? AND user_id = ?",
                sessionId, userId);

        // If there is no active session now, switch to the most recent remaining session
        List<String> active = jdbc.queryForList(
                "SELECT session_id FROM active_session WHERE user_id = ?",
                String.class, userId);
        if (active.isEmpty()) {
            List<String> remaining = jdbc.queryForList(
                    "SELECT id FROM chat_sessions WHERE user_id = ? AND archived = 0 ORDER BY updated_at DESC LIMIT 1",
                    String.class, userId);
            if (!remaining.isEmpty()) {
                setActiveSession(userId, remaining.getFirst());
            }
            // If no sessions remain, getCurrentSession() will create one when needed
        }
    }

    /**
     * Full-text search across all of a user's conversations.
     * Returns matching sessions with message snippets.
     */
    public List<Map<String, Object>> searchMessages(String userId, String query) {
        return jdbc.queryForList("""
            SELECT DISTINCT s.id AS session_id, s.title, s.updated_at,
                   snippet(conversations_fts, 0, '<mark>', '</mark>', '...', 40) AS snippet,
                   c.role, c.timestamp AS match_timestamp
            FROM conversations_fts fts
            JOIN conversations c ON c.rowid = fts.rowid
            JOIN chat_sessions s ON s.id = c.session_id
            WHERE conversations_fts MATCH ?
              AND c.user_id = ?
              AND s.archived = 0
            ORDER BY fts.rank
            LIMIT 30
            """, query, userId);
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
            // Generate title from first message: take first sentence or first 60 chars
            String title = generateTitle(firstMessage);
            jdbc.update("UPDATE chat_sessions SET title = ? WHERE id = ? AND user_id = ?",
                    title, sessionId, userId);
        }
    }

    private String generateTitle(String message) {
        if (message == null || message.isBlank()) return "New Chat";
        // Use first line or first sentence
        String title = message.split("[\\n.!?]")[0].trim();
        if (title.length() > 60) {
            title = title.substring(0, 57) + "...";
        }
        if (title.isBlank()) {
            title = message.substring(0, Math.min(60, message.length()));
        }
        return title;
    }
}
