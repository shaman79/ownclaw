package com.ownclaw.core;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks requests to stop tasks, per task and per user -- and who made each one.
 *
 * <p>Cancellation used to be keyed on the user alone: one flag, set by Stop, cleared at the
 * start of every task. With a single worker thread that was indistinguishable from correct,
 * because a user only ever had one task running. It stops being correct the moment two can run
 * at once — Stop would cancel both, and the flag cleared at the start of one task would discard
 * a cancellation aimed at the other.
 *
 * <p>So a task is cancellable by its own id, and there is a separate "stop everything this user
 * is running", which is what the Stop button does. Keeping both is deliberate: the per-task form
 * is what concurrency needs, and the user-wide form is what a person means when they press Stop
 * without choosing a task.
 *
 * <p>Every request says why, in words the task's ending shows the owner ("you pressed Stop", "a
 * stop request from the ops API"). Without it a Stop, an ops request and the stall watchdog all
 * ended on the same "Task was cancelled.", and the next turn could not tell which had happened.
 * The stall watchdog does not come through here: it marks the task itself
 * ({@code AgentContext.stall}), so the task ends STALLED with the watchdog's facts.
 */
@Service
public class TaskCancellationService {

    /** Tasks individually stopped, by task id, with why. The first request's why is kept. */
    private final Map<String, String> stoppedTasks = new ConcurrentHashMap<>();

    /**
     * When each user last stopped everything, and why.
     *
     * A time rather than a flag, because a flag has no way to end. Stop means "cancel what is
     * running now"; it cannot mean "and everything I start later". Clearing on start would let a
     * task that begins a moment after Stop swallow a cancellation aimed at the task still running
     * beside it, and NOT clearing would make Stop permanent. Comparing against when the task
     * began answers both — a task started after the Stop is simply not covered by it.
     */
    private final Map<String, Stop> stoppedAll = new ConcurrentHashMap<>();

    private record Stop(long atMs, String why) {}

    /** Stop one specific task, saying why. */
    public void request(String userId, String taskId, String why) {
        if (taskId != null) stoppedTasks.putIfAbsent(taskId, why);
    }

    /**
     * Stop everything this user is currently running, saying why.
     * <p>
     * What the Stop button does: a person pressing Stop without naming a task means "whatever is
     * going on, stop it".
     */
    public void requestAll(String userId, String why) {
        if (userId != null) stoppedAll.put(userId, new Stop(System.currentTimeMillis(), why));
    }

    /**
     * Why this task should stop, or null when nothing has asked it to.
     *
     * @param taskStartedAtMs when this task began; a user-wide Stop only covers tasks that were
     *                        already running when it was pressed
     */
    public String why(String userId, String taskId, long taskStartedAtMs) {
        String own = taskId == null ? null : stoppedTasks.get(taskId);
        if (own != null) return own;
        Stop all = userId == null ? null : stoppedAll.get(userId);
        return all != null && taskStartedAtMs <= all.atMs() ? all.why() : null;
    }

    /** Whether this task should stop, given when it started. */
    public boolean isCancelled(String userId, String taskId, long taskStartedAtMs) {
        return why(userId, taskId, taskStartedAtMs) != null;
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
        Stop all = userId == null ? null : stoppedAll.get(userId);
        return all == null ? null : all.atMs();
    }

    /**
     * Clear this task's own request at the start of a task, so a stale per-task request cannot
     * stop the work that follows it.
     * <p>
     * It does not touch the user-wide Stop, and must not: that is bounded by its time instead,
     * so a task starting now already falls outside it. Clearing it here would discard a Stop
     * that another task, still running, has not yet noticed — which is exactly the bug that
     * appears the moment more than one task can run at a time.
     */
    public void clear(String userId, String taskId) {
        if (taskId != null) stoppedTasks.remove(taskId);
    }

    /**
     * Thrown into a model call when its task has been stopped: the call's progress hook throws
     * it on the next event of the streamed reply, so a Stop or the stall watchdog ends the call
     * instead of waiting for it to finish. The loop turns it into the task's ending.
     */
    public static class TaskCancelledException extends RuntimeException {
        public TaskCancelledException(String taskId) {
            super("task " + taskId + " was stopped");
        }
    }
}
