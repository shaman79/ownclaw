package com.ownclaw.conversation;

import com.ownclaw.llm.LlmMessage;
import com.ownclaw.llm.LlmRequestConfig;
import com.ownclaw.llm.OllamaProvider;
import com.ownclaw.llm.OllamaSemaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Compresses conversation history to keep the LLM context window bounded.
 * Uses the local Executor LLM (zero cloud cost) to produce rolling summaries.
 *
 * <p>Strategy:
 * <ol>
 *   <li>Active context: last N messages kept in full</li>
 *   <li>Older messages: compressed into a rolling summary stored in session_summaries</li>
 *   <li>Compression triggers when message count in a session exceeds threshold</li>
 * </ol>
 */
@Service
public class ConversationCompressor {

    private static final Logger log = LoggerFactory.getLogger(ConversationCompressor.class);

    /** Messages to keep uncompressed (the "active window"). */
    private static final int ACTIVE_WINDOW = 10;
    /** Compress when session has this many uncompressed messages beyond the active window. */
    private static final int COMPRESS_THRESHOLD = 6;
    /** Max tokens for the compression LLM call. */
    private static final int MAX_SUMMARY_TOKENS = 512;

    private final JdbcTemplate jdbc;
    private final OllamaProvider ollama;
    private final OllamaSemaphore semaphore;

    public ConversationCompressor(JdbcTemplate jdbc, OllamaProvider ollama, OllamaSemaphore semaphore) {
        this.jdbc = jdbc;
        this.ollama = ollama;
        this.semaphore = semaphore;
    }

    /**
     * Check if a session needs compression and compress if so.
     * Called after saving each message. Only compresses when enough messages
     * have accumulated beyond the active window.
     *
     * @param userId    user ID
     * @param sessionId session ID
     */
    public void compressIfNeeded(String userId, String sessionId) {
        try {
            // Count total non-status messages in this session
            Integer totalCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM conversations WHERE user_id = ? AND session_id = ? AND role != 'status'",
                    Integer.class, userId, sessionId);

            if (totalCount == null || totalCount <= ACTIVE_WINDOW + COMPRESS_THRESHOLD) {
                return; // Not enough messages to warrant compression
            }

            // Get messages that are outside the active window (oldest first)
            int toCompress = totalCount - ACTIVE_WINDOW;
            List<Map<String, Object>> oldMessages = jdbc.queryForList("""
                    SELECT id, role, content FROM conversations
                    WHERE user_id = ? AND session_id = ? AND role != 'status'
                    ORDER BY timestamp ASC LIMIT ?
                    """, userId, sessionId, toCompress);

            if (oldMessages.isEmpty()) return;

            // Build the text to compress
            StringBuilder textToCompress = new StringBuilder();

            // Include existing summary if one exists
            List<Map<String, Object>> existingSummary = jdbc.queryForList(
                    "SELECT summary FROM session_summaries WHERE session_id = ? AND user_id = ?",
                    sessionId, userId);
            if (!existingSummary.isEmpty()) {
                textToCompress.append("PREVIOUS SUMMARY:\n")
                        .append(existingSummary.getFirst().get("summary"))
                        .append("\n\nNEW MESSAGES TO INCORPORATE:\n");
            }

            for (Map<String, Object> msg : oldMessages) {
                String role = (String) msg.get("role");
                String content = (String) msg.get("content");
                textToCompress.append(role.toUpperCase()).append(": ")
                        .append(truncate(content, 500)).append("\n");
            }

            // Compress via local LLM
            String compressed = compressWithLlm(textToCompress.toString());

            // Store/update the rolling summary
            jdbc.update("""
                    INSERT INTO session_summaries (session_id, user_id, summary, total_messages)
                    VALUES (?, ?, ?, ?)
                    ON CONFLICT(session_id) DO UPDATE SET
                        summary = excluded.summary,
                        total_messages = total_messages + excluded.total_messages,
                        last_updated = datetime('now')
                    """, sessionId, userId, compressed, oldMessages.size());

            // Delete the compressed messages from conversations table
            for (Map<String, Object> msg : oldMessages) {
                jdbc.update("DELETE FROM conversations WHERE id = ?", msg.get("id"));
            }

            log.info("Compressed {} messages for session {} -> {} chars summary",
                    oldMessages.size(), sessionId, compressed.length());

        } catch (Exception e) {
            log.warn("Conversation compression failed (non-fatal): {}", e.getMessage());
        }
    }

    /**
     * Get the rolling summary for a session (if any).
     *
     * @return the compressed summary text, or null if none exists
     */
    public String getSessionSummary(String userId, String sessionId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT summary FROM session_summaries WHERE session_id = ? AND user_id = ?",
                sessionId, userId);
        return rows.isEmpty() ? null : (String) rows.getFirst().get("summary");
    }

    /**
     * Use the local LLM to compress a conversation excerpt into a concise summary.
     */
    private String compressWithLlm(String conversationText) {
        String systemPrompt = """
                You are a conversation summarizer. Compress the conversation into a concise summary.
                
                Rules:
                - Preserve ALL key facts, decisions, user preferences, and task outcomes
                - If a PREVIOUS SUMMARY is provided, merge new information into it
                - Keep entity names, numbers, URLs, and specific details intact
                - Remove greetings, filler, redundancy
                - Output a single paragraph, max 200 words
                - Write in third person: "The user asked...", "The system executed..."
                """;

        // Truncate input to avoid overwhelming the local LLM
        String truncated = conversationText.length() > 4000
                ? conversationText.substring(0, 4000) + "\n... [truncated]"
                : conversationText;

        List<LlmMessage> messages = List.of(
                LlmMessage.system(systemPrompt),
                LlmMessage.user(truncated)
        );

        semaphore.acquire();
        try {
            var response = ollama.chat(messages, LlmRequestConfig.withMaxTokens(MAX_SUMMARY_TOKENS));
            return response.content();
        } finally {
            semaphore.release();
        }
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }
}
