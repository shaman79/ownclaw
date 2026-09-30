package com.ownclaw.core;

import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.observability.ChatStatusEmitter;
import com.ownclaw.observability.ChatStatusEmitter.StatusMessage;
import com.ownclaw.observability.EventLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages long-running tasks: tracks progress, heartbeats, and detects stalls.
 *
 * <h3>How it works</h3>
 * <ol>
 *   <li>When a skill starts executing and is expected to take a long time (or emits its first
 *       progress line), the task is registered here.</li>
 *   <li>The skill emits structured progress lines on stdout
 *       ({@code {"type":"progress","message":"Scanning 45/255 hosts","percent":18}}).
 *       The sandbox intercepts these, and for a tool the agent loop calls itself they reach
 *       {@link #reportProgress}; inside a delegation they are shown to the user directly.</li>
 *   <li>Each progress report also counts as a heartbeat.  If no heartbeat arrives within
 *       {@code stall-timeout} seconds, the task is marked as <b>stalled</b> and the user
 *       is notified.</li>
 *   <li>On completion (success or failure), the task is finalized: its result or error is
 *       stored whole in the task's row, and the user is told that it finished or failed, how
 *       long that text is and the task's id -- not the text itself.</li>
 * </ol>
 *
 * <p>Task state is persisted to the {@code long_running_tasks} table so that
 * progress survives restarts and can be queried later.</p>
 */
@Service
public class LongRunningTaskManager {

    private static final Logger log = LoggerFactory.getLogger(LongRunningTaskManager.class);

    private final JdbcTemplate jdbc;
    private final ChatStatusEmitter statusEmitter;
    private final EventLogService eventLog;
    private final OwnClawConfig config;

    /**
     * In-memory view of active tasks for fast heartbeat checking.
     * Key: taskId, Value: last heartbeat epoch-millis.
     */
    private final ConcurrentHashMap<String, TaskHeartbeat> activeHeartbeats = new ConcurrentHashMap<>();

    public LongRunningTaskManager(JdbcTemplate jdbc,
                                  ChatStatusEmitter statusEmitter,
                                  EventLogService eventLog,
                                  OwnClawConfig config) {
        this.jdbc = jdbc;
        this.statusEmitter = statusEmitter;
        this.eventLog = eventLog;
        this.config = config;
    }

    /**
     * On startup, mark any leftover "running" tasks from a previous crash as stalled.
     */
    @PostConstruct
    public void recoverStaleTasksOnStartup() {
        try {
            int updated = jdbc.update("""
                UPDATE long_running_tasks
                SET status = 'stalled', error_message = 'System restarted while task was running'
                WHERE status = 'running'
                """);
            if (updated > 0) {
                log.warn("Marked {} orphaned long-running tasks as stalled after restart", updated);
            }
        } catch (Exception e) {
            log.debug("Could not recover stale tasks (table may not exist yet): {}", e.getMessage());
        }
    }

    // ── Registration ──

    /**
     * Register a new long-running task.
     * <p>
     * The description is stored whole in the task's row, and only there: the event and the log
     * line name the task and its skill. The event's summary also goes to the log, and a log is
     * not where the owner's words belong.
     *
     * @param taskId      unique task identifier (from AgentContext)
     * @param userId      the owning user
     * @param description human-readable description of the task
     * @param skillName   the skill being executed (nullable)
     */
    public void register(String taskId, String userId, String description, String skillName) {
        jdbc.update("""
            INSERT OR REPLACE INTO long_running_tasks
                (task_id, user_id, description, skill_name, status, heartbeat_at)
            VALUES (?, ?, ?, ?, 'running', datetime('now'))
            """, taskId, userId, description, skillName);

        activeHeartbeats.put(taskId, new TaskHeartbeat(userId, System.currentTimeMillis()));

        eventLog.info(userId, taskId, "task.long_running.started",
                "Long-running task registered: " + skillName + " — task " + taskId);

        log.info("Long-running task registered: taskId={} user={} skill={}",
                taskId, userId, skillName);
    }

    // ── Progress reporting ──

    /**
     * Report progress from a running skill.  Also acts as a heartbeat.
     *
     * @param taskId  the task identifier
     * @param message human-readable progress message
     * @param percent completion percentage (0–100), or null if unknown
     */
    public void reportProgress(String taskId, String message, Integer percent) {
        // Update heartbeat
        TaskHeartbeat hb = activeHeartbeats.get(taskId);
        if (hb != null) {
            hb.lastHeartbeatMs = System.currentTimeMillis();
        }

        // Persist to DB. Progress from a task marked 'stalled' REVIVES it.
        //
        // "Stalled" is a guess made from silence, and a long sweep that reports once and then
        // works quietly for a quarter of an hour trips it legitimately. The guard used to be
        // status='running', so once the guess was made the row could never move again: later
        // progress updated nothing, the heartbeat entry had been dropped so isActive() was
        // false, and the call site therefore skipped completion. A task that recovered and
        // finished successfully stayed 'stalled' forever.
        jdbc.update("""
            UPDATE long_running_tasks
            SET progress_msg = ?, progress_pct = ?, heartbeat_at = datetime('now'),
                status = 'running',
                error_message = CASE WHEN status = 'stalled' THEN NULL ELSE error_message END
            WHERE task_id = ? AND status IN ('running', 'stalled')
            """, message, percent, taskId);

        // Emit to user chat
        if (hb != null) {
            String formatted = percent != null
                    ? message + " (" + percent + "%)"
                    : message;
            statusEmitter.emit(hb.userId, StatusMessage.Type.PROGRESS, formatted);
        }

        log.debug("Progress for task {}: {}% - {}", taskId, percent, message);
    }

    /**
     * Record a heartbeat without a progress message (keeps the task alive).
     */
    public void heartbeat(String taskId) {
        TaskHeartbeat hb = activeHeartbeats.get(taskId);
        if (hb != null) {
            hb.lastHeartbeatMs = System.currentTimeMillis();
        }
        jdbc.update("""
            UPDATE long_running_tasks
            SET heartbeat_at = datetime('now'), status = 'running'
            WHERE task_id = ? AND status IN ('running', 'stalled')
            """, taskId);
    }

    // ── Completion ──

    /**
     * Mark a task finished, whatever state it was left in.
     * <p>
     * Deliberately unguarded on status: a task that was wrongly declared stalled and then
     * finished must be able to say so.
     * <p>
     * The result is stored whole in the task's row. The owner's status line, the event and the
     * log say where it is and how long it is ({@link #reference}) rather than carrying it: a
     * whole tool output is no status line, and a cut one is a result with its end missing.
     */
    public void complete(String taskId, String resultSummary) {
        jdbc.update("""
            UPDATE long_running_tasks
            SET status = 'completed', completed_at = datetime('now'),
                result_summary = ?, progress_pct = 100
            WHERE task_id = ?
            """, resultSummary, taskId);

        String line = reference("finished", resultSummary, taskId);
        TaskHeartbeat hb = activeHeartbeats.remove(taskId);
        if (hb != null) {
            statusEmitter.emit(hb.userId, StatusMessage.Type.COMPLETED, line);
            eventLog.info(hb.userId, taskId, "task.long_running.completed", line);
        }
        log.info(line);
    }

    /**
     * Mark a task as failed. The error is stored whole in the task's row, and referred to like
     * a result (see {@link #complete}).
     */
    public void fail(String taskId, String errorMessage) {
        jdbc.update("""
            UPDATE long_running_tasks
            SET status = 'failed', completed_at = datetime('now'), error_message = ?
            WHERE task_id = ?
            """, errorMessage, taskId);

        String line = reference("failed", errorMessage, taskId);
        TaskHeartbeat hb = activeHeartbeats.remove(taskId);
        if (hb != null) {
            statusEmitter.emit(hb.userId, StatusMessage.Type.FAILED, line);
            eventLog.error(hb.userId, taskId, "task.long_running.failed", line);
        }
        log.error(line);
    }

    /** "Long-running task finished, 12,345 chars — task abc": where the text is, and its size. */
    static String reference(String how, String text, String taskId) {
        return String.format(Locale.ROOT, "Long-running task %s, %,d chars — task %s",
                how, text == null ? 0 : text.length(), taskId);
    }

    /**
     * Mark a task as cancelled.
     */
    public void cancel(String taskId) {
        jdbc.update("""
            UPDATE long_running_tasks
            SET status = 'cancelled', completed_at = datetime('now')
            WHERE task_id = ?
            """, taskId);

        TaskHeartbeat hb = activeHeartbeats.remove(taskId);
        if (hb != null) {
            statusEmitter.emit(hb.userId, StatusMessage.Type.WARNING,
                    "Long-running task cancelled.");
            eventLog.info(hb.userId, taskId, "task.long_running.cancelled", "Task cancelled by user");
        }
        log.info("Long-running task cancelled: taskId={}", taskId);
    }

    // ── Stall detection ──

    /**
     * Periodically checks for stalled tasks.
     * Runs every 30 seconds.  A task is considered stalled if its last heartbeat
     * is older than {@code stall-timeout} seconds.
     */
    @Scheduled(fixedDelayString = "${ownclaw.tasks.heartbeat-interval:30}000")
    public void detectStalledTasks() {
        int stallTimeoutSec = config.getTasks().getStallTimeout();
        long stallThresholdMs = System.currentTimeMillis() - (stallTimeoutSec * 1000L);

        for (var entry : activeHeartbeats.entrySet()) {
            String taskId = entry.getKey();
            TaskHeartbeat hb = entry.getValue();

            if (hb.lastHeartbeatMs < stallThresholdMs) {
                log.warn("Task {} stalled — no heartbeat for {}s (threshold: {}s)",
                        taskId,
                        (System.currentTimeMillis() - hb.lastHeartbeatMs) / 1000,
                        stallTimeoutSec);

                markStalled(taskId, hb.userId);
            }
        }
    }

    private void markStalled(String taskId, String userId) {
        jdbc.update("""
            UPDATE long_running_tasks
            SET status = 'stalled', error_message = 'No heartbeat received — task appears to be stalled'
            WHERE task_id = ? AND status = 'running'
            """, taskId);

        activeHeartbeats.remove(taskId);

        statusEmitter.emit(userId, StatusMessage.Type.WARNING,
                "⚠ Long-running task appears stalled (no progress for " +
                config.getTasks().getStallTimeout() + "s). The process may have hung.");

        eventLog.warn(userId, taskId, "task.long_running.stalled",
                "No heartbeat for " + config.getTasks().getStallTimeout() + "s");
    }

    // ── Queries ──

    /**
     * Check if a task is registered as long-running and still active.
     */
    public boolean isActive(String taskId) {
        return activeHeartbeats.containsKey(taskId);
    }

    // ── Internal ──

    /**
     * In-memory heartbeat tracker for fast stall detection.
     */
    private static class TaskHeartbeat {
        final String userId;
        volatile long lastHeartbeatMs;

        TaskHeartbeat(String userId, long lastHeartbeatMs) {
            this.userId = userId;
            this.lastHeartbeatMs = lastHeartbeatMs;
        }
    }
}
