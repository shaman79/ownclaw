package com.ownclaw.core;

import com.ownclaw.agent.AgentLoop;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.observability.ChatStatusEmitter;
import com.ownclaw.observability.ChatStatusEmitter.StatusMessage;
import com.ownclaw.observability.EventLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Priority-based task queue that serializes Ollama access across users.
 * Tasks are submitted and processed asynchronously in priority order.
 */
@Service
public class TaskQueue {

    private static final Logger log = LoggerFactory.getLogger(TaskQueue.class);

    private final AgentLoop agentLoop;
    private final EventLogService eventLog;
    private final ChatStatusEmitter statusEmitter;
    private final int maxQueuedTasks;

    private final PriorityBlockingQueue<QueuedTask> queue = new PriorityBlockingQueue<>();
    private final AtomicInteger queueSize = new AtomicInteger(0);
    private ExecutorService workerPool;

    public TaskQueue(AgentLoop agentLoop, EventLogService eventLog,
                     ChatStatusEmitter statusEmitter, OwnClawConfig config) {
        this.agentLoop = agentLoop;
        this.eventLog = eventLog;
        this.statusEmitter = statusEmitter;
        this.maxQueuedTasks = config.getQueue().getMaxQueuedTasks();
    }

    @PostConstruct
    public void start() {
        // Single worker thread — tasks are serialized (Ollama is the bottleneck).
        // LLM calls within AgentLoop run on the calling thread (blocking OK here).
        workerPool = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "task-queue-worker");
            t.setDaemon(true);
            return t;
        });

        workerPool.submit(this::processLoop);
        log.info("Task queue started");
    }

    @PreDestroy
    public void stop() {
        if (workerPool != null) {
            workerPool.shutdownNow();
        }
    }

    /**
     * Submit a task to the queue.
     *
     * @param userId  user who submitted the task
     * @param message the user's message
     * @param priority task priority (0 = highest, 2 = background)
     * @return a future that will contain the response (or an error message)
     */
    public CompletableFuture<String> submit(String userId, String message, int priority) {
        if (queueSize.get() >= maxQueuedTasks) {
            eventLog.warn(userId, null, "queue.full", "Queue full, task rejected");
            return CompletableFuture.completedFuture("System busy — please try again later.");
        }

        CompletableFuture<String> future = new CompletableFuture<>();
        QueuedTask task = new QueuedTask(userId, message, priority, System.currentTimeMillis(), future);
        queue.add(task);
        int pos = queueSize.incrementAndGet();

        if (pos > 1) {
            statusEmitter.emit(userId, StatusMessage.Type.QUEUED,
                    "Task queued (position " + pos + ")");
        }

        eventLog.info(userId, null, "task.queued",
                "Priority P" + priority + ", queue size " + pos);

        return future;
    }

    /** Submit with default priority (P1 = normal). */
    public CompletableFuture<String> submit(String userId, String message) {
        return submit(userId, message, 1);
    }

    private void processLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                QueuedTask task = queue.take();
                queueSize.decrementAndGet();

                try {
                    String response = agentLoop.execute(task.userId(), task.message());
                    task.future().complete(response);
                } catch (Exception e) {
                    log.error("Task processing failed for user {}: {}", task.userId(), e.getMessage(), e);
                    task.future().complete("Internal error: " + e.getMessage());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    public int getQueueSize() {
        return queueSize.get();
    }

    /**
     * A task waiting in the priority queue.
     */
    private record QueuedTask(
            String userId,
            String message,
            int priority,
            long enqueuedAt,
            CompletableFuture<String> future
    ) implements Comparable<QueuedTask> {

        @Override
        public int compareTo(QueuedTask other) {
            // Lower priority number = higher priority
            int cmp = Integer.compare(this.priority, other.priority);
            if (cmp != 0) return cmp;
            // Same priority: FIFO
            return Long.compare(this.enqueuedAt, other.enqueuedAt);
        }
    }
}
