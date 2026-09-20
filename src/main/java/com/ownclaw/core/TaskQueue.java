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

    /** Priority 2 and above is background work — the scheduler and /bg submit at 2. */
    public static final int BACKGROUND_PRIORITY = 2;

    /** Interactive work. When lanes are off, everything goes here and nothing changes. */
    private final PriorityBlockingQueue<QueuedTask> interactiveQueue = new PriorityBlockingQueue<>();
    /** Background work, drained by its own thread only when lanes are enabled. */
    private final PriorityBlockingQueue<QueuedTask> backgroundQueue = new PriorityBlockingQueue<>();

    private final AtomicInteger queueSize = new AtomicInteger(0);
    /** Count, not a flag: with two lanes there can be two tasks in flight. */
    private final AtomicInteger running = new AtomicInteger(0);
    private final boolean separateBackgroundLane;
    private ExecutorService workerPool;

    public TaskQueue(AgentLoop agentLoop, EventLogService eventLog,
                     ChatStatusEmitter statusEmitter, OwnClawConfig config) {
        this.agentLoop = agentLoop;
        this.eventLog = eventLog;
        this.statusEmitter = statusEmitter;
        this.maxQueuedTasks = config.getQueue().getMaxQueuedTasks();
        this.separateBackgroundLane = config.getQueue().isSeparateBackgroundLane();
    }

    @PostConstruct
    public void start() {
        // One thread per lane. The original comment here said tasks are serialized because
        // "Ollama is the bottleneck" — that stopped being true when routing moved to
        // cloud-first, and the cost of keeping it was that a background task running for
        // minutes blocked every interactive message behind it on the same thread.
        //
        // Ollama really is still serialized, but by OllamaSemaphore rather than by starving
        // the whole system of workers: two lanes can both reach it, and the second waits.
        int threads = separateBackgroundLane ? 2 : 1;
        var counter = new AtomicInteger();
        workerPool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "task-queue-worker-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        });

        workerPool.submit(() -> processLoop(interactiveQueue, "interactive"));
        if (separateBackgroundLane) {
            workerPool.submit(() -> processLoop(backgroundQueue, "background"));
            log.info("Task queue started with separate interactive and background lanes");
        } else {
            log.info("Task queue started (single lane)");
        }
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
        // With lanes off, background work stays in the interactive queue and the behaviour is
        // byte-for-byte what it was: one queue, one thread, priority order within it.
        boolean background = separateBackgroundLane && priority >= BACKGROUND_PRIORITY;
        (background ? backgroundQueue : interactiveQueue).add(task);
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

    /**
     * Returns true if a task is currently being processed or waiting in the queue.
     * Used by the deploy script to avoid restarting during active work.
     */
    public boolean isBusy() {
        return running.get() > 0 || queueSize.get() > 0;
    }

    private void processLoop(PriorityBlockingQueue<QueuedTask> lane, String laneName) {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                QueuedTask task = lane.take();
                queueSize.decrementAndGet();
                running.incrementAndGet();

                try {
                    // Priority is the origin signal: the scheduler and /bg submit at 2,
                    // a chat message at 1. Nobody is waiting on the former.
                    boolean unattended = task.priority() >= BACKGROUND_PRIORITY;
                    String response = agentLoop.execute(task.userId(), task.message(), unattended);
                    task.future().complete(response);
                } catch (Exception e) {
                    log.error("Task processing failed on the {} lane for user {}: {}",
                            laneName, task.userId(), e.getMessage(), e);
                    task.future().complete("Internal error: " + e.getMessage());
                } finally {
                    running.decrementAndGet();
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
