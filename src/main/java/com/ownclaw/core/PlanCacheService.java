package com.ownclaw.core;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.skills.SkillManifest;
import com.ownclaw.skills.SkillModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Caches task plans by normalized task description hash.
 * Avoids LLM calls for recurring/identical tasks.
 * Cache invalidation: skill version change, repeated failures, TTL, or user /replan.
 */
@Service
public class PlanCacheService {

    private static final Logger log = LoggerFactory.getLogger(PlanCacheService.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final SkillManifest skillManifest;
    private final int ttlHours;

    public PlanCacheService(JdbcTemplate jdbc, ObjectMapper mapper,
                            SkillManifest skillManifest, OwnClawConfig config) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.skillManifest = skillManifest;
        this.ttlHours = config.getPlanCache().getTtlHours();
    }

    /** Look up a cached plan for a task description. Returns empty if miss or invalidated. */
    public Optional<TaskPlan> lookup(String taskDescription) {
        String key = hashKey(taskDescription);

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT plan, skill_versions, created_at FROM plan_cache WHERE cache_key = ?", key);

        if (rows.isEmpty()) return Optional.empty();

        Map<String, Object> row = rows.getFirst();
        String createdAt = (String) row.get("created_at");

        // TTL check (convert hours to fractional days for julianday comparison)
        double ttlDays = ttlHours / 24.0;
        int expired = jdbc.queryForObject(
                "SELECT CASE WHEN julianday('now') - julianday(?) > ? THEN 1 ELSE 0 END",
                Integer.class, createdAt, ttlDays);
        if (expired == 1) {
            evict(key);
            log.debug("Plan cache TTL expired for key {}", key);
            return Optional.empty();
        }

        // Skill version check
        try {
            Map<String, Integer> cachedVersions = mapper.readValue(
                    (String) row.get("skill_versions"), new TypeReference<>() {});
            for (var entry : cachedVersions.entrySet()) {
                Optional<SkillModel> current = skillManifest.findByName(entry.getKey());
                if (current.isEmpty() || current.get().version() != entry.getValue()) {
                    evict(key);
                    log.info("Plan cache invalidated: skill {} version changed", entry.getKey());
                    return Optional.empty();
                }
            }

            TaskPlan plan = mapper.readValue((String) row.get("plan"), TaskPlan.class);

            // Reject empty plans that may have been cached erroneously
            if (plan.steps().isEmpty()) {
                evict(key);
                log.info("Plan cache evicted: cached plan has 0 steps");
                return Optional.empty();
            }

            // Update hit count and last_used
            jdbc.update("UPDATE plan_cache SET hit_count = hit_count + 1, last_used = datetime('now') WHERE cache_key = ?", key);
            log.debug("Plan cache hit for key {}", key);
            return Optional.of(plan);

        } catch (Exception e) {
            log.warn("Failed to deserialize cached plan: {}", e.getMessage());
            evict(key);
            return Optional.empty();
        }
    }

    /** Store a plan in the cache. */
    public void store(String taskDescription, TaskPlan plan) {
        String key = hashKey(taskDescription);
        try {
            String planJson = mapper.writeValueAsString(plan);

            // Collect skill versions used in the plan
            Map<String, Integer> versions = plan.steps().stream()
                    .map(s -> skillManifest.findByName(s.skill()))
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .collect(Collectors.toMap(SkillModel::name, SkillModel::version, (a, b) -> a));
            String versionsJson = mapper.writeValueAsString(versions);

            jdbc.update("""
                    INSERT INTO plan_cache (cache_key, plan, skill_versions)
                    VALUES (?, ?, ?)
                    ON CONFLICT(cache_key) DO UPDATE SET
                        plan = excluded.plan,
                        skill_versions = excluded.skill_versions,
                        hit_count = 0,
                        last_used = datetime('now'),
                        created_at = datetime('now')
                    """, key, planJson, versionsJson);

            log.debug("Cached plan for key {}", key);
        } catch (Exception e) {
            log.warn("Failed to cache plan: {}", e.getMessage());
        }
    }

    /** Evict a plan by task description. */
    public void evictByTask(String taskDescription) {
        evict(hashKey(taskDescription));
    }

    private void evict(String key) {
        jdbc.update("DELETE FROM plan_cache WHERE cache_key = ?", key);
    }

    /**
     * Hash a task description to a cache key.
     * Includes OS name so platform-specific plans (shell syntax) are never reused cross-platform.
     */
    private String hashKey(String taskDescription) {
        String os = System.getProperty("os.name", "Unknown").toLowerCase();
        String normalized = os + "::" + taskDescription.strip().toLowerCase().replaceAll("\\s+", " ");
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(normalized.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            // SHA-256 is always available — this can't happen
            return normalized;
        }
    }
}
