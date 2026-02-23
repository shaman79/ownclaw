package com.ownclaw.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Writes and queries structured events to the events table.
 * Every significant system action goes through here.
 */
@Service
public class EventLogService {

    private static final Logger log = LoggerFactory.getLogger(EventLogService.class);

    private final JdbcTemplate jdbc;

    public EventLogService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Log a structured event.
     */
    public void log(String userId, String taskId, String eventType,
                    String severity, String summary, String detailsJson, int tokensUsed) {
        jdbc.update("""
            INSERT INTO events (user_id, task_id, event_type, severity, summary, details, tokens_used)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """, userId, taskId, eventType, severity, summary, detailsJson, tokensUsed);

        if ("error".equals(severity)) {
            log.error("[{}] {}: {}", eventType, userId, summary);
        } else if ("warn".equals(severity)) {
            log.warn("[{}] {}: {}", eventType, userId, summary);
        } else {
            log.info("[{}] {}: {}", eventType, userId, summary);
        }
    }

    /** Convenience: info event with no token cost. */
    public void info(String userId, String taskId, String eventType, String summary) {
        log(userId, taskId, eventType, "info", summary, null, 0);
    }

    /** Convenience: warn event. */
    public void warn(String userId, String taskId, String eventType, String summary) {
        log(userId, taskId, eventType, "warn", summary, null, 0);
    }

    /** Convenience: error event. */
    public void error(String userId, String taskId, String eventType, String summary) {
        log(userId, taskId, eventType, "error", summary, null, 0);
    }

    /** Last N events for a user. */
    public List<Map<String, Object>> recentEvents(String userId, int limit) {
        return jdbc.queryForList("""
            SELECT id, timestamp, task_id, event_type, severity, summary, tokens_used
            FROM events WHERE user_id = ? ORDER BY id DESC LIMIT ?
            """, userId, limit);
    }

    /** All events for a specific task. */
    public List<Map<String, Object>> taskEvents(String userId, String taskId) {
        return jdbc.queryForList("""
            SELECT id, timestamp, event_type, severity, summary, details, tokens_used
            FROM events WHERE user_id = ? AND task_id = ? ORDER BY id
            """, userId, taskId);
    }

    /** Recent errors for a user. */
    public List<Map<String, Object>> recentErrors(String userId, int limit) {
        return jdbc.queryForList("""
            SELECT id, timestamp, task_id, event_type, summary
            FROM events WHERE user_id = ? AND severity = 'error' ORDER BY id DESC LIMIT ?
            """, userId, limit);
    }

    /** Token usage summary for a user (today). */
    public Map<String, Object> tokenUsageToday(String userId) {
        return jdbc.queryForMap("""
            SELECT COALESCE(SUM(tokens_used), 0) AS total_tokens,
                   COUNT(*) AS total_events
            FROM events
            WHERE user_id = ? AND timestamp >= date('now')
            """, userId);
    }
}
