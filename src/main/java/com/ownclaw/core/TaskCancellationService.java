package com.ownclaw.core;

import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks cancellation requests, per task and per user.
 *
 * <p>Cancellation used to be keyed on the user alone: one flag, set by Stop, cleared at the
 * start of every task. With a single worker thread that was indistinguishable from correct,
 * because a user only ever had one task running. It stops being correct the moment two can run
 * at once — Stop would cancel both, and the flag cleared at the start of one task would discard
 * a cancellation aimed at the other.
 *
 * <p>So a task is now cancellable by its own id, and there is a separate "cancel everything this
 * user is running" which is what the Stop button still does today. Keeping both is deliberate:
 * the per-task form is what concurrency needs, and the user-wide form is what a person means
 * when they press Stop without choosing a task. Once the UI can attribute a task to a chat, it
 * can pass the id and stop only that one.
 *
 * <p>This ships before any second worker thread exists, on purpose. It changes no behaviour on
 * its own, and adding concurrency first would mean a window in which Stop silently did the wrong
 * thing.
 */
@Service
public class TaskCancellationService {

    /** Tasks individually cancelled, by task id. */
    private final Set<String> cancelledTasks = ConcurrentHashMap.newKeySet();

    /**
     * When each user last pressed Stop, as epoch millis.
     *
     * A timestamp rather than a flag, because a flag has no way to end. Stop means "cancel what
     * is running now"; it cannot mean "and everything I start later". With one worker the flag
     * was cleared by the next task and that distinction never surfaced. With two lanes it does:
     * clearing on start lets a task that begins a moment after Stop swallow a cancellation aimed
     * at the task still running beside it, and NOT clearing makes Stop permanent. Comparing
     * against when the task began answers both — a task started after the Stop is simply not
     * covered by it.
     */
    private final java.util.Map<String, Long> stoppedAt = new ConcurrentHashMap<>();

    /** Cancel one specific task. */
    public void request(String userId, String taskId) {
        if (taskId != null) cancelledTasks.add(taskId);
    }

    /**
     * Cancel everything this user is currently running.
     * <p>
     * What the Stop button does: a person pressing Stop without naming a task means "whatever is
     * going on, stop it".
     */
    public void requestAll(String userId) {
        if (userId != null) stoppedAt.put(userId, System.currentTimeMillis());
    }

    /**
     * Whether this specific task should stop.
     * <p>
     * Without a start time this cannot tell a Stop aimed at this task from one aimed at a task
     * that finished earlier, so it errs toward stopping: a user who pressed Stop wants things to
     * stop. Callers that know when their task began should use the three-argument form.
     */
    public boolean isCancelled(String userId, String taskId) {
        return isCancelled(userId, taskId, Long.MAX_VALUE);
    }

    /**
     * Whether this specific task should stop, given when it started.
     *
     * @param taskStartedAtMs when this task began; a user-wide Stop only covers tasks that were
     *                        already running when it was pressed
     */
    public boolean isCancelled(String userId, String taskId, long taskStartedAtMs) {
        if (taskId != null && cancelledTasks.contains(taskId)) return true;
        if (userId == null) return false;
        Long stop = stoppedAt.get(userId);
        return stop != null && taskStartedAtMs <= stop;
    }

    /**
     * When this user last pressed Stop, or null if they never have.
     * <p>
     * Exposed so the queue can drop work that was already waiting when Stop was pressed.
     * {@link #isCancelled} cannot answer that: it compares against when a task STARTED, and a
     * queued task starts after the Stop, so it looks like new work and runs. From the user's
     * side it is not new work — they queued it, then changed their mind, and watched it start
     * anyway.
     */
    public Long stoppedAt(String userId) {
        return userId == null ? null : stoppedAt.get(userId);
    }

    /**
     * Clear this task's own cancellation flag at the start of a task, so a stale per-task
     * cancellation cannot kill the work that follows it.
     * <p>
     * It does not touch the user-wide Stop, and must not: that is bounded by its timestamp
     * instead, so a task starting now already falls outside it. Clearing it here would discard a
     * Stop that another task, still running, has not yet noticed — which is exactly the bug that
     * appears the moment more than one task can run at a time.
     */
    public void clear(String userId, String taskId) {
        // Only this task's own flag. The user-wide Stop is NOT cleared here: it is bounded by
        // its timestamp instead, so a task starting now is already outside it. Clearing it would
        // discard a Stop that a task still running beside this one has not yet noticed.
        if (taskId != null) cancelledTasks.remove(taskId);
    }

    /** How many tasks are individually flagged — for diagnostics. */
    public int pendingCancellations() {
        return cancelledTasks.size() + stoppedAt.size();
    }

    /** Exception thrown by the orchestrator when a task is cancelled mid-flight. */
    public static class TaskCancelledException extends RuntimeException {
        public TaskCancelledException(String userId) {
            super("Task cancelled by user " + userId);
        }
    }
}
