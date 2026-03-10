package com.ownclaw.core;

import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.conversation.ConversationService;
import com.ownclaw.observability.ChatStatusEmitter;
import com.ownclaw.observability.ChatStatusEmitter.StatusMessage;
import com.ownclaw.observability.EventLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Manages scheduled and deferred tasks.
 *
 * <h3>Task types</h3>
 * <ul>
 *   <li><b>Deferred</b> (one-shot): "Remind me in 2 hours" — fires once at the
 *       scheduled time, then moves to {@code completed}.</li>
 *   <li><b>Recurring</b>: "Backup notes every night at 3am" — fires on a cron
 *       schedule, automatically calculates the next run after each execution.</li>
 * </ul>
 *
 * <h3>How it works</h3>
 * <ol>
 *   <li>Tasks are created via {@link #scheduleDeferred} or {@link #scheduleRecurring}
 *       and persisted to the {@code scheduled_tasks} table.</li>
 *   <li>A periodic poller ({@link #pollDueTasks}) runs every N seconds (configurable)
 *       and finds tasks whose {@code next_run_at} has passed.</li>
 *   <li>Due tasks are submitted to the {@link TaskQueue} at P2 (background) priority.</li>
 *   <li>After execution, the result is recorded and (for recurring tasks) the next
 *       run time is computed from the cron expression.</li>
 * </ol>
 */
@Service
public class ScheduledTaskService {

    private static final Logger log = LoggerFactory.getLogger(ScheduledTaskService.class);

    private final JdbcTemplate jdbc;
    private final TaskQueue taskQueue;
    private final ChatStatusEmitter statusEmitter;
    private final EventLogService eventLog;
    private final ConversationService conversationService;
    private final OwnClawConfig config;

    // Track task submission timestamps for duration calculation
    private final Map<Long, Long> taskStartTimes = new java.util.concurrent.ConcurrentHashMap<>();

    // ── Natural language time patterns ──

    private static final Pattern DURATION_PATTERN = Pattern.compile(
            "(?:in\\s+)?(\\d+)\\s*(second|minute|hour|day|week|month)s?",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern AT_TIME_PATTERN = Pattern.compile(
            "(?:at\\s+)?(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern TOMORROW_PATTERN = Pattern.compile(
            "tomorrow(?:\\s+at\\s+(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?)?",
            Pattern.CASE_INSENSITIVE);

    public ScheduledTaskService(JdbcTemplate jdbc, TaskQueue taskQueue,
                                ChatStatusEmitter statusEmitter, EventLogService eventLog,
                                ConversationService conversationService, OwnClawConfig config) {
        this.jdbc = jdbc;
        this.taskQueue = taskQueue;
        this.statusEmitter = statusEmitter;
        this.eventLog = eventLog;
        this.conversationService = conversationService;
        this.config = config;
    }

    @PostConstruct
    public void init() {
        // Mark any tasks that were in a "running" state as failed on startup
        // (they were interrupted by a restart).
        int recovered = jdbc.update("""
            UPDATE scheduled_tasks SET status = 'active',
                   last_error = 'Server restarted during execution'
            WHERE status = 'running'
            """);
        if (recovered > 0) {
            log.info("Recovered {} scheduled tasks that were running during shutdown", recovered);
        }

        int active = countActiveAll();
        log.info("Scheduled task service started — {} active tasks", active);
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Creating tasks
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Schedule a one-shot deferred task.
     *
     * @param userId    the user who owns this task
     * @param description the message/task to execute when the time comes
     * @param runAt     when to execute
     * @return the created task ID
     */
    public long scheduleDeferred(String userId, String description, Instant runAt) {
        enforceLimit(userId);

        jdbc.update("""
            INSERT INTO scheduled_tasks (user_id, task_type, description, next_run_at, status)
            VALUES (?, 'deferred', ?, ?, 'active')
            """, userId, description, runAt.toString());

        long taskId = jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);

        eventLog.info(userId, null, "scheduled.created",
                "Deferred task #" + taskId + " scheduled for " + formatTime(runAt));
        statusEmitter.emit(userId, StatusMessage.Type.SCHEDULED,
                "Task scheduled for " + formatTime(runAt) + ": " + truncate(description, 80));

        log.info("Deferred task #{} created for user {} — runs at {}", taskId, userId, runAt);
        return taskId;
    }

    /**
     * Schedule a recurring task with a cron expression.
     *
     * @param userId         the user who owns this task
     * @param description    the message/task to execute each time
     * @param cronExpression Spring cron expression (6 fields: sec min hour day month weekday)
     * @param maxRuns        optional max number of executions (null = unlimited)
     * @return the created task ID
     */
    public long scheduleRecurring(String userId, String description, String cronExpression,
                                  Integer maxRuns) {
        enforceLimit(userId);

        // Validate the cron expression
        CronExpression cron = CronExpression.parse(cronExpression);
        LocalDateTime nextRun = cron.next(LocalDateTime.now());
        if (nextRun == null) {
            throw new IllegalArgumentException("Cron expression never fires: " + cronExpression);
        }
        Instant nextRunInstant = nextRun.atZone(ZoneId.systemDefault()).toInstant();

        jdbc.update("""
            INSERT INTO scheduled_tasks (user_id, task_type, description, cron_expression,
                                         next_run_at, max_runs, status)
            VALUES (?, 'recurring', ?, ?, ?, ?, 'active')
            """, userId, description, cronExpression, nextRunInstant.toString(), maxRuns);

        long taskId = jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);

        eventLog.info(userId, null, "scheduled.created",
                "Recurring task #" + taskId + " [" + cronExpression + "], next run: " + formatTime(nextRunInstant));
        statusEmitter.emit(userId, StatusMessage.Type.SCHEDULED,
                "Recurring task scheduled [" + cronExpression + "], next run: "
                        + formatTime(nextRunInstant) + " — " + truncate(description, 60));

        log.info("Recurring task #{} created for user {} — cron={}, next={}",
                taskId, userId, cronExpression, nextRunInstant);
        return taskId;
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Natural-language time parsing
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Parse a natural-language time expression into an {@link Instant}.
     * Supports: "in 30 minutes", "in 2 hours", "in 1 day", "tomorrow",
     * "tomorrow at 9am", "at 14:30".
     *
     * @return the parsed instant, or empty if the expression is not recognized
     */
    public Optional<Instant> parseTimeExpression(String expression) {
        if (expression == null || expression.isBlank()) return Optional.empty();
        String expr = expression.trim().toLowerCase();

        // "in N unit(s)"
        Matcher durMatch = DURATION_PATTERN.matcher(expr);
        if (durMatch.find()) {
            int amount = Integer.parseInt(durMatch.group(1));
            String unit = durMatch.group(2).toLowerCase();
            Instant result = switch (unit) {
                case "second" -> Instant.now().plus(amount, ChronoUnit.SECONDS);
                case "minute" -> Instant.now().plus(amount, ChronoUnit.MINUTES);
                case "hour"   -> Instant.now().plus(amount, ChronoUnit.HOURS);
                case "day"    -> Instant.now().plus(amount, ChronoUnit.DAYS);
                case "week"   -> Instant.now().plus(amount * 7L, ChronoUnit.DAYS);
                case "month"  -> Instant.now().plus(amount * 30L, ChronoUnit.DAYS);
                default -> null;
            };
            return Optional.ofNullable(result);
        }

        // "tomorrow" or "tomorrow at HH:mm"
        Matcher tomorrowMatch = TOMORROW_PATTERN.matcher(expr);
        if (tomorrowMatch.find()) {
            LocalDate tomorrow = LocalDate.now().plusDays(1);
            int hour = tomorrowMatch.group(1) != null ? Integer.parseInt(tomorrowMatch.group(1)) : 9;
            int minute = tomorrowMatch.group(2) != null ? Integer.parseInt(tomorrowMatch.group(2)) : 0;
            String ampm = tomorrowMatch.group(3);
            hour = adjustAmPm(hour, ampm);
            LocalDateTime dt = LocalDateTime.of(tomorrow, LocalTime.of(hour, minute));
            return Optional.of(dt.atZone(ZoneId.systemDefault()).toInstant());
        }

        // "at HH:mm" — today if in the future, otherwise tomorrow
        Matcher atMatch = AT_TIME_PATTERN.matcher(expr);
        if (atMatch.find()) {
            int hour = Integer.parseInt(atMatch.group(1));
            int minute = atMatch.group(2) != null ? Integer.parseInt(atMatch.group(2)) : 0;
            String ampm = atMatch.group(3);
            hour = adjustAmPm(hour, ampm);
            LocalDateTime dt = LocalDateTime.of(LocalDate.now(), LocalTime.of(hour, minute));
            if (dt.isBefore(LocalDateTime.now())) {
                dt = dt.plusDays(1);
            }
            return Optional.of(dt.atZone(ZoneId.systemDefault()).toInstant());
        }

        return Optional.empty();
    }

    /**
     * Try to parse a simple cron-like schedule description.
     * Examples: "every day at 3am" → "0 0 3 * * *"
     *           "every hour" → "0 0 * * * *"
     *           "every monday at 9:00" → "0 0 9 * * MON"
     * Falls back to treating it as a raw cron expression.
     *
     * @return a valid Spring cron expression, or empty if not parseable
     */
    public Optional<String> parseScheduleExpression(String expression) {
        if (expression == null || expression.isBlank()) return Optional.empty();
        String expr = expression.trim().toLowerCase();

        // "every N minutes/hours"
        Matcher durMatch = Pattern.compile("every\\s+(\\d+)\\s*(minute|hour|day|week)s?",
                Pattern.CASE_INSENSITIVE).matcher(expr);
        if (durMatch.find()) {
            int amount = Integer.parseInt(durMatch.group(1));
            String unit = durMatch.group(2).toLowerCase();
            return switch (unit) {
                case "minute" -> Optional.of("0 */" + amount + " * * * *");
                case "hour"   -> Optional.of("0 0 */" + amount + " * * *");
                case "day"    -> Optional.of("0 0 0 */" + amount + " * *");
                default -> Optional.empty();
            };
        }

        // "every minute" / "every hour" / "every day"
        if (expr.equals("every minute"))  return Optional.of("0 * * * * *");
        if (expr.equals("every hour"))    return Optional.of("0 0 * * * *");
        if (expr.equals("every day") || expr.equals("daily"))
            return Optional.of("0 0 0 * * *");

        // "every day at HH(:mm)?(am|pm)?"
        Matcher dailyAt = Pattern.compile(
                "every\\s+day\\s+at\\s+(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?",
                Pattern.CASE_INSENSITIVE).matcher(expr);
        if (dailyAt.find()) {
            int hour = Integer.parseInt(dailyAt.group(1));
            int minute = dailyAt.group(2) != null ? Integer.parseInt(dailyAt.group(2)) : 0;
            hour = adjustAmPm(hour, dailyAt.group(3));
            return Optional.of("0 " + minute + " " + hour + " * * *");
        }

        // "every <weekday> at HH(:mm)?"
        Matcher weeklyAt = Pattern.compile(
                "every\\s+(monday|tuesday|wednesday|thursday|friday|saturday|sunday|mon|tue|wed|thu|fri|sat|sun)" +
                "(?:\\s+at\\s+(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?)?",
                Pattern.CASE_INSENSITIVE).matcher(expr);
        if (weeklyAt.find()) {
            String day = weeklyAt.group(1).substring(0, 3).toUpperCase();
            int hour = weeklyAt.group(2) != null ? Integer.parseInt(weeklyAt.group(2)) : 0;
            int minute = weeklyAt.group(3) != null ? Integer.parseInt(weeklyAt.group(3)) : 0;
            hour = adjustAmPm(hour, weeklyAt.group(4));
            return Optional.of("0 " + minute + " " + hour + " * * " + day);
        }

        // Try as raw cron expression
        try {
            CronExpression.parse(expr);
            return Optional.of(expr);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private int adjustAmPm(int hour, String ampm) {
        if (ampm == null) return hour;
        if (ampm.equalsIgnoreCase("pm") && hour < 12) return hour + 12;
        if (ampm.equalsIgnoreCase("am") && hour == 12) return 0;
        return hour;
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Querying tasks
    // ──────────────────────────────────────────────────────────────────────

    /**
     * List scheduled tasks for a user, optionally filtering by status.
     */
    public List<Map<String, Object>> listTasks(String userId, String status) {
        if (status != null && !status.isBlank()) {
            return jdbc.queryForList("""
                SELECT * FROM scheduled_tasks
                WHERE user_id = ? AND status = ?
                ORDER BY next_run_at ASC
                """, userId, status);
        }
        return jdbc.queryForList("""
            SELECT * FROM scheduled_tasks
            WHERE user_id = ?
            ORDER BY CASE status
                WHEN 'active' THEN 0
                WHEN 'paused' THEN 1
                ELSE 2
            END, next_run_at ASC
            """, userId);
    }

    /**
     * Get a single task by ID (for the owning user).
     */
    public Optional<Map<String, Object>> getTask(String userId, long taskId) {
        var list = jdbc.queryForList("""
            SELECT * FROM scheduled_tasks WHERE id = ? AND user_id = ?
            """, taskId, userId);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    /**
     * Format a user-friendly summary of all active scheduled tasks.
     */
    public String formatTasksSummary(String userId) {
        var tasks = listTasks(userId, null);
        if (tasks.isEmpty()) return "No scheduled tasks.";

        var sb = new StringBuilder("### Scheduled Tasks\n");
        var fmt = DateTimeFormatter.ofPattern("MMM d, HH:mm").withZone(ZoneId.systemDefault());
        int n = 0;
        for (var t : tasks) {
            n++;
            String type = String.valueOf(t.get("task_type"));
            String status = String.valueOf(t.get("status"));
            String desc = truncate(String.valueOf(t.get("description")), 60);
            String nextRun = t.get("next_run_at") != null
                    ? fmt.format(Instant.parse(String.valueOf(t.get("next_run_at"))))
                    : "—";
            String cron = t.get("cron_expression") != null
                    ? " [" + t.get("cron_expression") + "]"
                    : "";
            int runs = ((Number) t.get("run_count")).intValue();
            String icon = switch (status) {
                case "active"    -> "🟢";
                case "paused"    -> "⏸️";
                case "completed" -> "✅";
                case "cancelled" -> "⛔";
                case "failed"    -> "❌";
                default -> "❓";
            };

            sb.append("  ").append(icon).append(" **#").append(t.get("id")).append("** ")
              .append(type.equals("recurring") ? "🔁" : "⏰").append(" ")
              .append(desc).append(cron)
              .append(" — next: ").append(nextRun)
              .append(runs > 0 ? " (ran " + runs + "×)" : "")
              .append(" [").append(status).append("]\n");

            if (n >= 20) {
                sb.append("  ... and more\n");
                break;
            }
        }
        return sb.toString();
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Managing tasks
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Cancel a scheduled task.
     */
    public boolean cancel(String userId, long taskId) {
        int rows = jdbc.update("""
            UPDATE scheduled_tasks SET status = 'cancelled', updated_at = datetime('now')
            WHERE id = ? AND user_id = ? AND status IN ('active', 'paused')
            """, taskId, userId);
        if (rows > 0) {
            eventLog.info(userId, null, "scheduled.cancelled", "Task #" + taskId + " cancelled");
            log.info("Scheduled task #{} cancelled by user {}", taskId, userId);
        }
        return rows > 0;
    }

    /**
     * Pause a scheduled task (it won't fire until resumed).
     */
    public boolean pause(String userId, long taskId) {
        int rows = jdbc.update("""
            UPDATE scheduled_tasks SET status = 'paused', updated_at = datetime('now')
            WHERE id = ? AND user_id = ? AND status = 'active'
            """, taskId, userId);
        if (rows > 0) {
            eventLog.info(userId, null, "scheduled.paused", "Task #" + taskId + " paused");
        }
        return rows > 0;
    }

    /**
     * Resume a paused scheduled task.
     */
    public boolean resume(String userId, long taskId) {
        int rows = jdbc.update("""
            UPDATE scheduled_tasks SET status = 'active', updated_at = datetime('now')
            WHERE id = ? AND user_id = ? AND status = 'paused'
            """, taskId, userId);
        if (rows > 0) {
            eventLog.info(userId, null, "scheduled.resumed", "Task #" + taskId + " resumed");
        }
        return rows > 0;
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Poller — checks for due tasks periodically
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Periodic poll for due tasks. Runs on a fixed delay defined by
     * {@code ownclaw.tasks.scheduler-poll-interval}.
     */
    @Scheduled(fixedDelayString = "${ownclaw.tasks.scheduler-poll-interval:30}000",
               initialDelayString = "${ownclaw.tasks.scheduler-poll-interval:30}000")
    public void pollDueTasks() {
        String now = Instant.now().toString();

        List<Map<String, Object>> dueTasks = jdbc.queryForList("""
            SELECT * FROM scheduled_tasks
            WHERE status = 'active' AND next_run_at <= ?
            ORDER BY next_run_at ASC
            LIMIT 10
            """, now);

        for (var task : dueTasks) {
            long taskId = ((Number) task.get("id")).longValue();
            String userId = String.valueOf(task.get("user_id"));
            String description = String.valueOf(task.get("description"));
            String taskType = String.valueOf(task.get("task_type"));

            log.info("Firing scheduled task #{} for user {} — {}", taskId, userId,
                    truncate(description, 50));

            // Mark as running to prevent re-pickup
            jdbc.update("""
                UPDATE scheduled_tasks SET status = 'running', updated_at = datetime('now')
                WHERE id = ? AND status = 'active'
                """, taskId);

            // Notify user
            statusEmitter.emit(userId, StatusMessage.Type.SCHEDULED,
                    "Scheduled task firing: " + truncate(description, 80));

            // Track start time for duration calculation
            taskStartTimes.put(taskId, System.currentTimeMillis());

            // Submit to task queue at P2 (background priority)
            taskQueue.submit(userId, description, 2)
                    .thenAccept(response -> onTaskCompleted(taskId, userId, taskType, description, response))
                    .exceptionally(ex -> {
                        onTaskFailed(taskId, userId, taskType, description, ex.getMessage());
                        return null;
                    });
        }
    }

    /**
     * Called when a scheduled task completes successfully.
     */
    private void onTaskCompleted(long taskId, String userId, String taskType,
                                 String description, String response) {
        int newRunCount = incrementRunCount(taskId);

        // Record full execution history
        recordRun(taskId, userId, description, taskType, "completed", response, null, newRunCount);

        if (taskType.equals("recurring")) {
            // Check if max runs reached
            var task = jdbc.queryForList(
                    "SELECT max_runs, cron_expression FROM scheduled_tasks WHERE id = ?", taskId);
            if (!task.isEmpty()) {
                Integer maxRuns = task.get(0).get("max_runs") != null
                        ? ((Number) task.get(0).get("max_runs")).intValue() : null;
                String cronExpr = String.valueOf(task.get(0).get("cron_expression"));

                if (maxRuns != null && newRunCount >= maxRuns) {
                    // Max runs reached — complete the task
                    jdbc.update("""
                        UPDATE scheduled_tasks SET status = 'completed',
                               last_run_at = datetime('now'), last_result = ?,
                               updated_at = datetime('now')
                        WHERE id = ?
                        """, truncate(response, 500), taskId);
                    statusEmitter.emit(userId, StatusMessage.Type.COMPLETED,
                            "Recurring task #" + taskId + " completed (max runs reached): "
                                    + truncate(description, 60));
                } else {
                    // Calculate next run
                    Instant nextRun = calculateNextRun(cronExpr);
                    jdbc.update("""
                        UPDATE scheduled_tasks SET status = 'active',
                               next_run_at = ?, last_run_at = datetime('now'),
                               last_result = ?, updated_at = datetime('now')
                        WHERE id = ?
                        """, nextRun.toString(), truncate(response, 500), taskId);
                    statusEmitter.emit(userId, StatusMessage.Type.SCHEDULED,
                            "Recurring task #" + taskId + " completed. Next run: "
                                    + formatTime(nextRun));
                }
            }
        } else {
            // Deferred — one-shot, mark completed
            jdbc.update("""
                UPDATE scheduled_tasks SET status = 'completed',
                       last_run_at = datetime('now'), last_result = ?,
                       updated_at = datetime('now')
                WHERE id = ?
                """, truncate(response, 500), taskId);
            statusEmitter.emit(userId, StatusMessage.Type.COMPLETED,
                    "Deferred task #" + taskId + " completed: " + truncate(description, 80));
        }

        eventLog.info(userId, null, "scheduled.completed",
                "Task #" + taskId + " completed (run #" + newRunCount + ")");

        // Post result to user's chat
        String sessionId = conversationService.getCurrentSession(userId);
        conversationService.saveMessage(userId, sessionId, "system",
                "📋 **Scheduled task completed** (#" + taskId + ")\n"
                        + "**Task:** " + description + "\n"
                        + "**Result:** " + truncate(response, 300));
    }

    /**
     * Called when a scheduled task fails.
     */
    private void onTaskFailed(long taskId, String userId, String taskType,
                              String description, String error) {
        int newRunCount = incrementRunCount(taskId);

        // Record full execution history
        recordRun(taskId, userId, description, taskType, "failed", null, error, newRunCount);

        if (taskType.equals("recurring")) {
            // For recurring tasks, try to schedule next run despite the failure
            var task = jdbc.queryForList(
                    "SELECT cron_expression FROM scheduled_tasks WHERE id = ?", taskId);
            if (!task.isEmpty()) {
                String cronExpr = String.valueOf(task.get(0).get("cron_expression"));
                Instant nextRun = calculateNextRun(cronExpr);
                jdbc.update("""
                    UPDATE scheduled_tasks SET status = 'active',
                           next_run_at = ?, last_run_at = datetime('now'),
                           last_error = ?, updated_at = datetime('now')
                    WHERE id = ?
                    """, nextRun.toString(), truncate(error, 500), taskId);
                statusEmitter.emit(userId, StatusMessage.Type.WARNING,
                        "Recurring task #" + taskId + " failed but will retry at "
                                + formatTime(nextRun) + ": " + truncate(error, 80));
            }
        } else {
            // Deferred — mark failed
            jdbc.update("""
                UPDATE scheduled_tasks SET status = 'failed',
                       last_run_at = datetime('now'), last_error = ?,
                       updated_at = datetime('now')
                WHERE id = ?
                """, truncate(error, 500), taskId);
            statusEmitter.emit(userId, StatusMessage.Type.FAILED,
                    "Deferred task #" + taskId + " failed: " + truncate(error, 80));
        }

        eventLog.warn(userId, null, "scheduled.failed",
                "Task #" + taskId + " failed: " + truncate(error, 100));

        // Notify in chat
        String sessionId = conversationService.getCurrentSession(userId);
        conversationService.saveMessage(userId, sessionId, "system",
                "❌ **Scheduled task failed** (#" + taskId + ")\n"
                        + "**Task:** " + description + "\n"
                        + "**Error:** " + truncate(error, 300));
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Helpers
    // ──────────────────────────────────────────────────────────────────────

    private Instant calculateNextRun(String cronExpression) {
        CronExpression cron = CronExpression.parse(cronExpression);
        LocalDateTime next = cron.next(LocalDateTime.now());
        return next != null
                ? next.atZone(ZoneId.systemDefault()).toInstant()
                : Instant.now().plus(1, ChronoUnit.DAYS); // fallback
    }

    private int incrementRunCount(long taskId) {
        jdbc.update("UPDATE scheduled_tasks SET run_count = run_count + 1 WHERE id = ?", taskId);
        Integer count = jdbc.queryForObject(
                "SELECT run_count FROM scheduled_tasks WHERE id = ?", Integer.class, taskId);
        return count != null ? count : 1;
    }

    private int countActiveAll() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM scheduled_tasks WHERE status IN ('active', 'paused')",
                Integer.class);
        return count != null ? count : 0;
    }

    private void enforceLimit(String userId) {
        int max = config.getTasks().getMaxScheduledTasksPerUser();
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM scheduled_tasks WHERE user_id = ? AND status IN ('active', 'paused')",
                Integer.class, userId);
        if (count != null && count >= max) {
            throw new IllegalStateException(
                    "Maximum scheduled tasks limit reached (" + max
                            + "). Cancel some existing tasks first.");
        }
    }

    private String formatTime(Instant instant) {
        return DateTimeFormatter.ofPattern("MMM d, HH:mm")
                .withZone(ZoneId.systemDefault())
                .format(instant);
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() > maxLen ? text.substring(0, maxLen) + "…" : text;
    }

    /**
     * Record a task execution run in the scheduled_task_runs history table.
     */
    private void recordRun(long taskId, String userId, String description, String taskType,
                           String status, String result, String error, int runNumber) {
        Long startTime = taskStartTimes.remove(taskId);
        Long durationMs = (startTime != null) ? System.currentTimeMillis() - startTime : null;
        try {
            jdbc.update("""
                INSERT INTO scheduled_task_runs
                    (task_id, user_id, description, task_type, status, result, error,
                     duration_ms, run_number, executed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, datetime('now'))
                """,
                taskId, userId, description, taskType, status,
                result, error, durationMs, runNumber);
        } catch (Exception e) {
            log.error("Failed to record task run for task #{}: {}", taskId, e.getMessage());
        }
    }

    /**
     * Get paginated execution history for a user's scheduled tasks.
     */
    public List<Map<String, Object>> getRunHistory(String userId, int limit, int offset) {
        return jdbc.queryForList("""
            SELECT r.*, t.cron_expression, t.next_run_at, t.max_runs,
                   t.status as task_status
            FROM scheduled_task_runs r
            LEFT JOIN scheduled_tasks t ON r.task_id = t.id
            WHERE r.user_id = ?
            ORDER BY r.executed_at DESC
            LIMIT ? OFFSET ?
            """, userId, limit, offset);
    }

    /**
     * Get execution history for a specific task.
     */
    public List<Map<String, Object>> getTaskRuns(String userId, long taskId, int limit) {
        return jdbc.queryForList("""
            SELECT * FROM scheduled_task_runs
            WHERE user_id = ? AND task_id = ?
            ORDER BY executed_at DESC
            LIMIT ?
            """, userId, taskId, limit);
    }

    /**
     * Get summary stats for a user's scheduled tasks.
     */
    public Map<String, Object> getTaskStats(String userId) {
        int totalRuns = Optional.ofNullable(jdbc.queryForObject(
                "SELECT COUNT(*) FROM scheduled_task_runs WHERE user_id = ?",
                Integer.class, userId)).orElse(0);
        int completedRuns = Optional.ofNullable(jdbc.queryForObject(
                "SELECT COUNT(*) FROM scheduled_task_runs WHERE user_id = ? AND status = 'completed'",
                Integer.class, userId)).orElse(0);
        int failedRuns = Optional.ofNullable(jdbc.queryForObject(
                "SELECT COUNT(*) FROM scheduled_task_runs WHERE user_id = ? AND status = 'failed'",
                Integer.class, userId)).orElse(0);
        int activeTasks = Optional.ofNullable(jdbc.queryForObject(
                "SELECT COUNT(*) FROM scheduled_tasks WHERE user_id = ? AND status IN ('active', 'paused')",
                Integer.class, userId)).orElse(0);
        return Map.of(
                "totalRuns", totalRuns,
                "completedRuns", completedRuns,
                "failedRuns", failedRuns,
                "activeTasks", activeTasks
        );
    }
}
