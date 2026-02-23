package com.ownclaw.executor;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.llm.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tracks user preferences from interactions by accumulating observations
 * in the teaching_log table, and periodically distilling them (via local LLM)
 * into a compact preferences JSON injected into Executor prompts.
 *
 * Zero cloud cost — all distillation uses the local Ollama LLM.
 */
@Service
public class PreferencesManager {

    private static final Logger log = LoggerFactory.getLogger(PreferencesManager.class);
    private static final int DISTILL_THRESHOLD = 10; // min new observations before distilling
    private static final int MAX_PREFERENCES_TOKENS = 200; // approximate token budget for prefs

    private static final String DISTILL_PROMPT = """
            You are analyzing user interaction patterns to build a concise user profile.
            
            Given these observations about how a user interacts with an AI assistant,
            distill them into a compact JSON object of preferences. Merge similar observations
            and increase confidence for repeated patterns.
            
            CATEGORIES:
            - communication: language, tone, verbosity preferences
            - format: output format preferences (bullet points, code blocks, etc.)
            - behavior: how they use the system, common tasks
            - workflow: tools, frameworks, environments they use
            
            Return ONLY a JSON object with this structure:
            {
              "communication": {"key": "value", ...},
              "format": {"key": "value", ...},
              "behavior": {"key": "value", ...},
              "workflow": {"key": "value", ...}
            }
            
            Keep the total output under 200 tokens. Omit empty categories.
            """;

    private final JdbcTemplate jdbc;
    private final OllamaProvider ollama;
    private final OllamaSemaphore semaphore;
    private final OwnClawConfig config;
    private final ObjectMapper mapper;

    // In-memory cache to avoid repeated DB reads
    private final Map<String, String> prefsCache = new LinkedHashMap<>();
    private final Map<String, Long> lastDistillTime = new LinkedHashMap<>();

    public PreferencesManager(JdbcTemplate jdbc, OllamaProvider ollama,
                              OllamaSemaphore semaphore, OwnClawConfig config,
                              ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.ollama = ollama;
        this.semaphore = semaphore;
        this.config = config;
        this.mapper = mapper;
    }

    /**
     * Record an observation about user behavior.
     * If the same observation already exists (by category+content similarity),
     * increment its occurrences and update confidence.
     */
    public void observe(String userId, String observation, String category, String source) {
        try {
            // Check for existing similar observation (exact match on observation text)
            Integer existingId = jdbc.query(
                    "SELECT id FROM teaching_log WHERE user_id = ? AND observation = ? AND category = ?",
                    (rs, rowNum) -> rs.getInt("id"),
                    userId, observation, category
            ).stream().findFirst().orElse(null);

            if (existingId != null) {
                jdbc.update("""
                        UPDATE teaching_log SET occurrences = occurrences + 1,
                               confidence = MIN(1.0, confidence + 0.1),
                               updated_at = datetime('now')
                        WHERE id = ?""", existingId);
            } else {
                // Enforce max entries per user
                int maxEntries = config.getFeedback().getTeachingLogMaxEntries();
                Integer currentCount = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM teaching_log WHERE user_id = ?",
                        Integer.class, userId);
                if (currentCount != null && currentCount >= maxEntries) {
                    // Evict least-confident entry
                    jdbc.update("""
                            DELETE FROM teaching_log WHERE id = (
                                SELECT id FROM teaching_log WHERE user_id = ?
                                ORDER BY confidence ASC, updated_at ASC LIMIT 1
                            )""", userId);
                }

                jdbc.update("""
                        INSERT INTO teaching_log (user_id, observation, category, confidence, source)
                        VALUES (?, ?, ?, 0.5, ?)""",
                        userId, observation, category, source);
            }
        } catch (Exception e) {
            log.warn("Failed to record observation for {}: {}", userId, e.getMessage());
        }
    }

    /**
     * Get the compact preferences string for injection into Executor prompts.
     * Returns empty string if no preferences exist yet.
     */
    public String getPreferencesPrompt(String userId) {
        try {
            // Check cache
            if (prefsCache.containsKey(userId)) {
                return prefsCache.get(userId);
            }

            String prefs = jdbc.query(
                    "SELECT preferences FROM user_preferences WHERE user_id = ?",
                    (rs, rowNum) -> rs.getString("preferences"),
                    userId
            ).stream().findFirst().orElse(null);

            if (prefs == null || prefs.equals("{}")) {
                return "";
            }

            String prompt = "\nUSER PREFERENCES (learned from past interactions):\n" + prefs + "\n";
            prefsCache.put(userId, prompt);
            return prompt;

        } catch (Exception e) {
            log.debug("Failed to load preferences for {}: {}", userId, e.getMessage());
            return "";
        }
    }

    /**
     * Trigger distillation if enough new observations have accumulated.
     * Called after task completion. Uses local LLM (zero cloud cost).
     */
    public void distillIfNeeded(String userId) {
        try {
            // Rate-limit: don't distill more than once per 10 minutes
            Long lastTime = lastDistillTime.get(userId);
            if (lastTime != null && System.currentTimeMillis() - lastTime < 600_000) {
                return;
            }

            // Count observations since last distillation
            String lastDistilled = jdbc.query(
                    "SELECT last_distilled FROM user_preferences WHERE user_id = ?",
                    (rs, rowNum) -> rs.getString("last_distilled"),
                    userId
            ).stream().findFirst().orElse(null);

            String countSql = lastDistilled != null
                    ? "SELECT COUNT(*) FROM teaching_log WHERE user_id = ? AND updated_at > ?"
                    : "SELECT COUNT(*) FROM teaching_log WHERE user_id = ?";

            Integer newCount = lastDistilled != null
                    ? jdbc.queryForObject(countSql, Integer.class, userId, lastDistilled)
                    : jdbc.queryForObject(countSql, Integer.class, userId);

            if (newCount == null || newCount < DISTILL_THRESHOLD) {
                return;
            }

            distill(userId);

        } catch (Exception e) {
            log.warn("Distillation check failed for {}: {}", userId, e.getMessage());
        }
    }

    /**
     * Perform distillation: read all observations, ask local LLM to produce
     * compact preferences JSON, store in user_preferences table.
     */
    private void distill(String userId) {
        try {
            // Fetch all observations ordered by confidence
            List<String> observations = jdbc.query(
                    """
                    SELECT observation, category, confidence, occurrences
                    FROM teaching_log WHERE user_id = ?
                    ORDER BY confidence DESC, occurrences DESC""",
                    (rs, rowNum) -> String.format("[%s] (confidence:%.1f, seen:%dx) %s",
                            rs.getString("category"),
                            rs.getDouble("confidence"),
                            rs.getInt("occurrences"),
                            rs.getString("observation")),
                    userId
            );

            if (observations.isEmpty()) return;

            String observationText = String.join("\n", observations);
            log.info("Distilling {} observations for user {}", observations.size(), userId);

            // Also include current preferences if they exist, for merging
            String currentPrefs = jdbc.query(
                    "SELECT preferences FROM user_preferences WHERE user_id = ?",
                    (rs, rowNum) -> rs.getString("preferences"),
                    userId
            ).stream().findFirst().orElse(null);

            String userPrompt = "OBSERVATIONS:\n" + observationText;
            if (currentPrefs != null && !currentPrefs.equals("{}")) {
                userPrompt += "\n\nCURRENT PREFERENCES (merge with new observations):\n" + currentPrefs;
            }

            List<LlmMessage> messages = List.of(
                    LlmMessage.system(DISTILL_PROMPT),
                    LlmMessage.user(userPrompt)
            );

            String distilled;
            semaphore.acquire();
            try {
                LlmResponse response = ollama.chat(messages,
                        LlmRequestConfig.withMaxTokens(MAX_PREFERENCES_TOKENS));
                distilled = response.content().strip();
            } finally {
                semaphore.release();
            }

            String json = LlmOutputUtils.stripCodeFences(distilled);
            mapper.readTree(json); // validates it's proper JSON

            // Upsert into user_preferences
            int updated = jdbc.update("""
                    UPDATE user_preferences SET preferences = ?, version = version + 1,
                           last_distilled = datetime('now'), updated_at = datetime('now')
                    WHERE user_id = ?""", json, userId);

            if (updated == 0) {
                jdbc.update("""
                        INSERT INTO user_preferences (user_id, preferences, version, last_distilled)
                        VALUES (?, ?, 1, datetime('now'))""", userId, json);
            }

            // Invalidate cache
            prefsCache.remove(userId);
            lastDistillTime.put(userId, System.currentTimeMillis());

            log.info("Distilled preferences for user {} (v{})", userId,
                    updated > 0 ? "incremented" : "1");

        } catch (Exception e) {
            log.warn("Distillation failed for {}: {}", userId, e.getMessage());
        }
    }

    /**
     * Analyze a completed task interaction and extract observations.
     * Called after each task to learn from user behavior.
     */
    public void learnFromTask(String userId, String userMessage, String taskResult,
                              String taskId) {
        try {
            // Use local LLM to extract observations
            String systemPrompt = """
                    Analyze this user interaction and extract 0-3 observations about the user's
                    preferences or habits. Focus on patterns, not specific content.
                    
                    Return a JSON array of observations:
                    [{"observation": "...", "category": "communication|format|behavior|workflow"}]
                    
                    Return [] if nothing noteworthy. Be selective - only flag clear patterns.
                    """;

            String userPrompt = "USER MESSAGE:\n" + userMessage
                    + "\n\nSYSTEM RESPONSE:\n" + (taskResult.length() > 500
                    ? taskResult.substring(0, 500) + "..." : taskResult);

            List<LlmMessage> messages = List.of(
                    LlmMessage.system(systemPrompt),
                    LlmMessage.user(userPrompt)
            );

            String response;
            semaphore.acquire();
            try {
                LlmResponse llmResp = ollama.chat(messages,
                        LlmRequestConfig.withMaxTokens(256));
                response = llmResp.content().strip();
            } finally {
                semaphore.release();
            }

            String json = LlmOutputUtils.stripCodeFences(response);

            List<Map<String, String>> observations = mapper.readValue(json,
                    new TypeReference<List<Map<String, String>>>() {});

            for (Map<String, String> obs : observations) {
                String observation = obs.get("observation");
                String category = obs.getOrDefault("category", "behavior");
                if (observation != null && !observation.isBlank()) {
                    observe(userId, observation, category, taskId);
                }
            }

        } catch (Exception e) {
            log.debug("Learning from task failed (non-critical): {}", e.getMessage());
        }
    }
}
