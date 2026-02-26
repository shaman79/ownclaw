package com.ownclaw.agent.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * SQLite-backed implementation of AgentMemory.
 *
 * Episodic memory uses tag-based keyword matching for recall (lightweight, no embeddings).
 * This is effective for the single-user or small-scale scenario and avoids
 * external dependencies for vector search.
 *
 * Upgrade path: swap this for an embedding-based implementation when needed.
 */
@Component
public class SqliteAgentMemory implements AgentMemory {

    private static final Logger log = LoggerFactory.getLogger(SqliteAgentMemory.class);

    private final JdbcTemplate jdbc;

    public SqliteAgentMemory(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void storeEpisode(String userId, String taskId, String summary, boolean outcome, List<String> tags) {
        try {
            String tagStr = tags != null ? String.join(",", tags) : "";
            jdbc.update(
                    "INSERT INTO agent_memory (user_id, task_id, memory_type, content, outcome, tags, created_at) " +
                            "VALUES (?, ?, 'episode', ?, ?, ?, datetime('now'))",
                    userId, taskId, summary, outcome ? 1 : 0, tagStr
            );
            log.debug("Stored episode for user={} task={}", userId, taskId);
        } catch (Exception e) {
            log.warn("Failed to store episode: {}", e.getMessage());
        }
    }

    @Override
    public List<MemoryEntry> recallEpisodes(String userId, String query, int maxResults) {
        try {
            // Extract keywords from the query for tag matching
            List<String> keywords = extractKeywords(query);

            if (keywords.isEmpty()) {
                // No keywords — return most recent episodes
                return jdbc.query(
                        "SELECT id, content, outcome, tags, created_at FROM agent_memory " +
                                "WHERE user_id = ? AND memory_type = 'episode' " +
                                "ORDER BY created_at DESC LIMIT ?",
                        (rs, rowNum) -> new MemoryEntry(
                                rs.getString("id"),
                                rs.getString("content"),
                                rs.getInt("outcome") == 1,
                                parseTags(rs.getString("tags")),
                                rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").getTime() : 0
                        ),
                        userId, maxResults
                );
            }

            // Build a scoring query — episodes matching more keywords rank higher.
            // SQLite doesn't have full-text search without FTS extension, so we use LIKE.
            // We wrap in a subquery because SQLite rejects HAVING on non-aggregate queries.
            StringBuilder inner = new StringBuilder();
            inner.append("SELECT id, content, outcome, tags, created_at, (");
            List<Object> params = new ArrayList<>();

            for (int i = 0; i < keywords.size(); i++) {
                if (i > 0) inner.append(" + ");
                inner.append("(CASE WHEN (tags LIKE ? OR content LIKE ?) THEN 1 ELSE 0 END)");
                String pattern = "%" + keywords.get(i) + "%";
                params.add(pattern);
                params.add(pattern);
            }

            inner.append(") AS relevance FROM agent_memory ")
                    .append("WHERE user_id = ? AND memory_type = 'episode'");
            params.add(userId);

            String sql = "SELECT id, content, outcome, tags, created_at, relevance "
                    + "FROM (" + inner + ") "
                    + "WHERE relevance > 0 "
                    + "ORDER BY relevance DESC, created_at DESC "
                    + "LIMIT ?";
            params.add(maxResults);

            return jdbc.query(sql,
                    (rs, rowNum) -> new MemoryEntry(
                            rs.getString("id"),
                            rs.getString("content"),
                            rs.getInt("outcome") == 1,
                            parseTags(rs.getString("tags")),
                            rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").getTime() : 0
                    ),
                    params.toArray()
            );
        } catch (Exception e) {
            log.warn("Failed to recall episodes: {}", e.getMessage());
            return List.of();
        }
    }

    @Override
    public void storeFact(String userId, String key, String fact) {
        try {
            // Upsert: delete existing fact with same key, then insert
            jdbc.update(
                    "DELETE FROM agent_memory WHERE user_id = ? AND memory_type = 'fact' AND tags = ?",
                    userId, key
            );
            jdbc.update(
                    "INSERT INTO agent_memory (user_id, task_id, memory_type, content, outcome, tags, created_at) " +
                            "VALUES (?, '', 'fact', ?, 1, ?, datetime('now'))",
                    userId, fact, key
            );
            log.debug("Stored fact '{}' for user={}", key, userId);
        } catch (Exception e) {
            log.warn("Failed to store fact: {}", e.getMessage());
        }
    }

    @Override
    public List<MemoryEntry> getFacts(String userId) {
        try {
            return jdbc.query(
                    "SELECT id, content, outcome, tags, created_at FROM agent_memory " +
                            "WHERE user_id = ? AND memory_type = 'fact' " +
                            "ORDER BY created_at DESC",
                    (rs, rowNum) -> new MemoryEntry(
                            rs.getString("id"),
                            rs.getString("content"),
                            rs.getInt("outcome") == 1,
                            parseTags(rs.getString("tags")),
                            rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").getTime() : 0
                    ),
                    userId
            );
        } catch (Exception e) {
            log.warn("Failed to get facts: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Extract simple keywords from a query string for tag matching.
     * Filters out common stop words and short words.
     */
    private List<String> extractKeywords(String query) {
        if (query == null || query.isBlank()) return List.of();

        var stopWords = java.util.Set.of(
                "a", "an", "the", "is", "are", "was", "were", "be", "been", "being",
                "have", "has", "had", "do", "does", "did", "will", "would", "could",
                "should", "may", "might", "can", "shall", "to", "of", "in", "for",
                "on", "with", "at", "by", "from", "as", "into", "through", "during",
                "before", "after", "above", "below", "between", "and", "but", "or",
                "not", "no", "nor", "so", "yet", "both", "either", "neither", "each",
                "every", "all", "any", "few", "more", "most", "some", "such", "than",
                "too", "very", "just", "about", "up", "out", "if", "then", "else",
                "when", "where", "why", "how", "what", "which", "who", "whom", "this",
                "that", "these", "those", "i", "me", "my", "we", "our", "you", "your",
                "he", "him", "his", "she", "her", "it", "its", "they", "them", "their",
                "please", "want", "need", "like", "get", "make", "help"
        );

        return Arrays.stream(query.toLowerCase().split("[\\s,.;:!?()\\[\\]{}\"']+"))
                .filter(w -> w.length() > 2)
                .filter(w -> !stopWords.contains(w))
                .distinct()
                .limit(10)
                .collect(Collectors.toList());
    }

    private List<String> parseTags(String tagStr) {
        if (tagStr == null || tagStr.isBlank()) return List.of();
        return Arrays.asList(tagStr.split(","));
    }
}
