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

    /** Users whose work has been cancelled wholesale, by user id. */
    private final Set<String> cancelledUsers = ConcurrentHashMap.newKeySet();

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
        if (userId != null) cancelledUsers.add(userId);
    }

    /** Whether this specific task should stop — either on its own account, or user-wide. */
    public boolean isCancelled(String userId, String taskId) {
        if (taskId != null && cancelledTasks.contains(taskId)) return true;
        return userId != null && cancelledUsers.contains(userId);
    }

    /**
     * Clear cancellation state at the start of a task, so a stale Stop cannot kill the work that
     * follows it.
     * <p>
     * Clearing the user-wide flag here is safe only while one task runs at a time. When a second
     * lane is added this must become task-scoped clearing alone — the user-wide flag will then
     * need its own lifetime, or a task starting will swallow a Stop meant for a task already
     * running. The second worker thread is the change that makes this urgent; it is noted here
     * because that is where it will be missed.
     */
    public void clear(String userId, String taskId) {
        if (taskId != null) cancelledTasks.remove(taskId);
        if (userId != null) cancelledUsers.remove(userId);
    }

    /** How many tasks are individually flagged — for diagnostics. */
    public int pendingCancellations() {
        return cancelledTasks.size() + cancelledUsers.size();
    }

    /** Exception thrown by the orchestrator when a task is cancelled mid-flight. */
    public static class TaskCancelledException extends RuntimeException {
        public TaskCancelledException(String userId) {
            super("Task cancelled by user " + userId);
        }
    }
}
