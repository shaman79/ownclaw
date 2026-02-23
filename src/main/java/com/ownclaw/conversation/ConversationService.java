package com.ownclaw.conversation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Manages chat sessions and message persistence.
 * Integrates with ConversationCompressor for rolling history summarization.
 */
@Service
public class ConversationService {

    private final JdbcTemplate jdbc;
    private final ConversationCompressor compressor;

    public ConversationService(JdbcTemplate jdbc, ConversationCompressor compressor) {
        this.jdbc = jdbc;
        this.compressor = compressor;
    }

    /**
     * Save a message to the conversation store.
     * Triggers compression check after saving.
     */
    public void saveMessage(String userId, String sessionId, String role, String content) {
        jdbc.update("""
            INSERT INTO conversations (id, user_id, session_id, role, content)
            VALUES (?, ?, ?, ?, ?)
            """, UUID.randomUUID().toString(), userId, sessionId, role, content);

        // Trigger compression check asynchronously (non-blocking, errors non-fatal)
        compressor.compressIfNeeded(userId, sessionId);
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

    /**
     * Get or create the current session ID for a user.
     * Phase 1: single session per user (session = user ID).
     */
    public String getCurrentSession(String userId) {
        return userId; // Simplified for Phase 1
    }
}
