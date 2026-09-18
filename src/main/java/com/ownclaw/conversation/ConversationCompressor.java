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

    /** Shorter than this and the summary is treated as a failure, not a summary. */
    private static final int MIN_SUMMARY_CHARS = 40;

    /** Messages to keep uncompressed (the "active window"). */
    private static final int ACTIVE_WINDOW = 10;
    /** Compress when session has this many uncompressed messages beyond the active window. */
    private static final int COMPRESS_THRESHOLD = 6;
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
                    "SELECT COUNT(*) FROM conversations WHERE user_id = ? AND session_id = ? "
                            + "AND role != 'status' AND compressed = 0",
                    Integer.class, userId, sessionId);

            if (totalCount == null || totalCount <= ACTIVE_WINDOW + COMPRESS_THRESHOLD) {
                return; // Not enough messages to warrant compression
            }

            // Get messages that are outside the active window (oldest first)
            int toCompress = totalCount - ACTIVE_WINDOW;
            List<Map<String, Object>> oldMessages = jdbc.queryForList("""
                    SELECT id, role, content FROM conversations
                    WHERE user_id = ? AND session_id = ? AND compressed = 0 AND role != 'status'
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

            // Refuse to advance on a summary the local model did not really produce. This used
            // to run unconditionally, so an empty or failed summary still deleted the originals.
            // The local model on this deployment can fail in exactly that way (a model with no
            // chat template returns an empty message), which would silently destroy history.
            if (compressed == null || compressed.isBlank() || compressed.length() < MIN_SUMMARY_CHARS) {
                log.warn("Compression for session {} produced {} chars — keeping all {} messages "
                                + "uncompressed rather than advancing on a bad summary",
                        sessionId, compressed == null ? 0 : compressed.length(), oldMessages.size());
                return;
            }

            // Store/update the rolling summary
            jdbc.update("""
                    INSERT INTO session_summaries (session_id, user_id, summary, total_messages)
                    VALUES (?, ?, ?, ?)
                    ON CONFLICT(session_id) DO UPDATE SET
                        summary = excluded.summary,
                        total_messages = total_messages + excluded.total_messages,
                        last_updated = datetime('now')
                    """, sessionId, userId, compressed, oldMessages.size());

            // Mark, do not delete. getRecentMessages() (the LLM context) skips compressed rows,
            // so the prompt is unchanged; the UI, history and FTS search keep the originals.
            for (Map<String, Object> msg : oldMessages) {
                jdbc.update("UPDATE conversations SET compressed = 1 WHERE id = ?", msg.get("id"));
            }

            log.info("Compressed {} messages for session {} -> {} chars summary (originals kept)",
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
        String systemPrompt = "Compress to third-person summary (max 200 words, 1 paragraph). Preserve ALL facts, decisions, names, numbers, URLs. Merge with previous summary if present. Remove filler.";

        // Input limit matched to local LLM context window (16K tokens ≈ ~10K chars of mixed content)
        String truncated = conversationText.length() > 10000
                ? conversationText.substring(0, 5000)
                    + "\n...[middle omitted]...\n"
                    + conversationText.substring(conversationText.length() - 5000)
                : conversationText;

        List<LlmMessage> messages = List.of(
                LlmMessage.system(systemPrompt),
                LlmMessage.user(truncated)
        );

        semaphore.acquire();
        try {
            // Local generation is free, so no token cap is set: Ollama then generates until the model stops or
            // the context window fills. The real bounds are num_ctx and the 600 s read timeout. Capping output
            // here used to starve thinking models, which spend part of the budget reasoning before they
            // answer.
            var response = ollama.chat(messages, LlmRequestConfig.DEFAULT);
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
