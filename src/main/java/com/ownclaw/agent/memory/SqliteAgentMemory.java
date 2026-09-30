package com.ownclaw.agent.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * SQLite-backed implementation of AgentMemory.
 *
 * Episodic memory is recalled by keyword: every episode that has a word of the query
 * (lightweight, no embeddings). This is effective for the single-user or small-scale scenario
 * and avoids external dependencies for vector search.
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
    public Recall recallEpisodes(String userId, String query) {
        var words = new ArrayList<String>();
        var skipped = new ArrayList<String>();
        for (String w : queryWords(query)) {
            (STOP_WORDS.contains(w) || w.codePointCount(0, w.length()) < 2 ? skipped : words).add(w);
        }
        if (words.isEmpty()) return new Recall(List.of(), List.copyOf(skipped), List.of());
        List<Predicate<String>> finders = words.stream().map(SqliteAgentMemory::finder).toList();
        // Scored here rather than in SQL: every episode is compared with every word -- LIKE with
        // two bound parameters per word runs into SQLite's parameter limit on a long query, and
        // lowercases only ASCII, so "Škoda" never matched "škoda". Newest first, and the sort is
        // stable, so among equals the newest stays first.
        List<MemoryEntry> episodes = jdbc.query(
                "SELECT id, content, outcome, tags, created_at FROM agent_memory "
                        + "WHERE user_id = ? AND memory_type = 'episode' ORDER BY created_at DESC, id DESC",
                (rs, rowNum) -> new MemoryEntry(
                        rs.getString("id"),
                        rs.getString("content"),
                        rs.getInt("outcome") == 1,
                        parseTags(rs.getString("tags")),
                        createdAt(rs)
                ),
                userId);
        record Scored(MemoryEntry entry, long relevance) {}
        return new Recall(List.copyOf(words), List.copyOf(skipped), episodes.stream()
                .map(e -> {
                    String text = (String.join(",", e.tags()) + " " + e.content()).toLowerCase(Locale.ROOT);
                    return new Scored(e, finders.stream().filter(f -> f.test(text)).count());
                })
                .filter(s -> s.relevance() > 0)
                .sorted(Comparator.comparingLong(Scored::relevance).reversed())
                .map(Scored::entry)
                .toList());
    }

    /**
     * Whether an episode's lowercased text has this word: anywhere for a word of three
     * characters or more -- "audit" finds "audited" -- and as a whole word for one of two, which
     * would be found inside too many others: "ap" is in "map", "ip" in "script".
     */
    private static Predicate<String> finder(String word) {
        if (word.codePointCount(0, word.length()) > 2) return text -> text.contains(word);
        Pattern whole = Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(word) + "(?![\\p{L}\\p{N}])");
        return text -> whole.matcher(text).find();
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
    public boolean deleteFact(String userId, String key) {
        try {
            int deleted = jdbc.update(
                    "DELETE FROM agent_memory WHERE user_id = ? AND memory_type = 'fact' AND tags = ?",
                    userId, key
            );
            if (deleted > 0) {
                log.debug("Deleted fact '{}' for user={}", key, userId);
            }
            return deleted > 0;
        } catch (Exception e) {
            log.warn("Failed to delete fact: {}", e.getMessage());
            return false;
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
                            createdAt(rs)
                    ),
                    userId
            );
        } catch (Exception e) {
            log.warn("Failed to get facts: {}", e.getMessage());
            return List.of();
        }
    }

    /** Words nearly every task has: looked for, they would find every episode. */
    private static final java.util.Set<String> STOP_WORDS = java.util.Set.of(
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

    /** The distinct words of a query, lowercased, in order. */
    private static List<String> queryWords(String query) {
        if (query == null || query.isBlank()) return List.of();
        return Arrays.stream(query.toLowerCase(Locale.ROOT).split("[\\s,.;:!?()\\[\\]{}\"']+"))
                .filter(w -> !w.isEmpty())
                .distinct()
                .toList();
    }

    /**
     * When the row was written: {@code datetime('now')} is UTC, stored as text. Read as a
     * timestamp it was taken for the JVM's local time.
     */
    private static long createdAt(java.sql.ResultSet rs) throws java.sql.SQLException {
        String at = rs.getString("created_at");
        return at == null ? 0 : java.time.LocalDateTime.parse(at.replace(' ', 'T'))
                .toInstant(java.time.ZoneOffset.UTC).toEpochMilli();
    }

    private List<String> parseTags(String tagStr) {
        if (tagStr == null || tagStr.isBlank()) return List.of();
        return Arrays.asList(tagStr.split(","));
    }
}
