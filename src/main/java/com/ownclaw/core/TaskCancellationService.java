package com.ownclaw.core;

import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tracks per-user cancellation requests.
 *
 * <p>When a user sends a "cancel" message over WebSocket, the flag for that user
 * is set to {@code true}. {@link TaskOrchestrator} checks the flag at each
 * inter-step checkpoint and aborts execution by throwing
 * {@link TaskCancelledException}. The flag is cleared at the start of every new
 * task so stale cancels don't affect subsequent work.
 */
@Service
public class TaskCancellationService {

    private final ConcurrentHashMap<String, AtomicBoolean> flags = new ConcurrentHashMap<>();

    /** Set the cancellation flag for the given user. */
    public void request(String userId) {
        flags.computeIfAbsent(userId, k -> new AtomicBoolean()).set(true);
    }

    /** Returns {@code true} if a cancellation has been requested for {@code userId}. */
    public boolean isCancelled(String userId) {
        AtomicBoolean flag = flags.get(userId);
        return flag != null && flag.get();
    }

    /**
     * Clear the cancellation flag for the given user.
     * Must be called at the start of each new task to prevent stale flags from
     * immediately cancelling the new work.
     */
    public void clear(String userId) {
        flags.remove(userId);
    }

    /** Exception thrown by the orchestrator when a task is cancelled mid-flight. */
    public static class TaskCancelledException extends RuntimeException {
        public TaskCancelledException(String userId) {
            super("Task cancelled by user " + userId);
        }
    }
}
