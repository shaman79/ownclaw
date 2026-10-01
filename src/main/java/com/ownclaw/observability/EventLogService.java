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
     * <p>
     * The summary is kept whole in the row and not repeated in the application log, which gets
     * its length: a summary can be the owner's own message (task_completed) or a scheduled run's
     * whole ending, and the log is read back through the ops API. The row is where to read it.
     */
    public void log(String userId, String taskId, String eventType,
                    String severity, String summary, String detailsJson, int tokensUsed) {
        jdbc.update("""
            INSERT INTO events (user_id, task_id, event_type, severity, summary, details, tokens_used)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """, userId, taskId, eventType, severity, summary, detailsJson, tokensUsed);

        int chars = summary == null ? 0 : summary.length();
        if ("error".equals(severity)) {
            log.error("[{}] {} task={}: {} chars", eventType, userId, taskId, chars);
        } else if ("warn".equals(severity)) {
            log.warn("[{}] {} task={}: {} chars", eventType, userId, taskId, chars);
        } else {
            log.info("[{}] {} task={}: {} chars", eventType, userId, taskId, chars);
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

    /**
     * A page of a user's events, newest first.
     *
     * @param limit  how many; a negative limit is every row from {@code offset} on
     * @param offset how many of the newest to skip
     */
    public List<Map<String, Object>> recentEvents(String userId, long limit, long offset) {
        return jdbc.queryForList("""
            SELECT id, timestamp, task_id, event_type, severity, summary, tokens_used
            FROM events WHERE user_id = ? ORDER BY id DESC LIMIT ? OFFSET ?
            """, userId, limit, offset);
    }

    /** All events for a specific task. */
    public List<Map<String, Object>> taskEvents(String userId, String taskId) {
        return jdbc.queryForList("""
            SELECT id, timestamp, event_type, severity, summary, details, tokens_used
            FROM events WHERE user_id = ? AND task_id = ? ORDER BY id
            """, userId, taskId);
    }

    /** A page of a user's errors, newest first; {@code limit} and {@code offset} as in {@link #recentEvents}. */
    public List<Map<String, Object>> recentErrors(String userId, long limit, long offset) {
        return jdbc.queryForList("""
            SELECT id, timestamp, task_id, event_type, severity, summary
            FROM events WHERE user_id = ? AND severity = 'error' ORDER BY id DESC LIMIT ? OFFSET ?
            """, userId, limit, offset);
    }

    /**
     * The event type of the local tokens billed to a task after its ending was recorded: a
     * summary of one of its private results whose reply came back just as it ended. Its details hold
     * {@code localTokens}, as an ending's do.
     */
    public static final String TOKENS_AFTER_END = "tokens_after_end";

    /**
     * Detailed token usage for today, broken down by cloud vs local: from task_completed events,
     * and the local tokens billed after a task's ending ({@link #TOKENS_AFTER_END}).
     */
    public Map<String, Object> tokenUsageDetailToday(String userId) {
        return jdbc.queryForMap("""
            SELECT COALESCE(SUM(tokens_used), 0) AS total_tokens,
                   COALESCE(SUM(json_extract(details, '$.cloudTokens')), 0) AS cloud_tokens,
                   COALESCE(SUM(json_extract(details, '$.localTokens')), 0) AS local_tokens,
                   COALESCE(SUM(event_type = 'task_completed'), 0) AS task_count
            FROM events
            WHERE user_id = ? AND event_type IN ('task_completed', ?)
              AND timestamp >= date('now')
            """, userId, TOKENS_AFTER_END);
    }

    /**
     * Token usage for one task, or for whatever this user finished last if no id is given.
     * <p>
     * The id matters. Without it this returns the newest task_completed row for the user, which
     * is a different task as soon as two can overlap — a scheduled digest finishing while a chat
     * message is also running would record the chat's token counts against the digest's run.
     * Even single-threaded it was wrong whenever delivery of one result took long enough for the
     * next task to finish first.
     */
    public long[] completedTaskTokens(String userId, String taskId) {
        try {
            var row = taskId != null
                    ? jdbc.queryForMap("""
                SELECT COALESCE(json_extract(details, '$.cloudTokens'), 0) AS cloud,
                       COALESCE(json_extract(details, '$.localTokens'), 0) AS local
                FROM events
                WHERE user_id = ? AND event_type = 'task_completed' AND task_id = ?
                ORDER BY id DESC LIMIT 1
                """, userId, taskId)
                    : jdbc.queryForMap("""
                SELECT COALESCE(json_extract(details, '$.cloudTokens'), 0) AS cloud,
                       COALESCE(json_extract(details, '$.localTokens'), 0) AS local
                FROM events
                WHERE user_id = ? AND event_type = 'task_completed'
                ORDER BY id DESC LIMIT 1
                """, userId);
            return new long[]{
                    ((Number) row.get("cloud")).longValue(),
                    ((Number) row.get("local")).longValue()
            };
        } catch (Exception e) {
            return new long[]{0, 0};
        }
    }
}
