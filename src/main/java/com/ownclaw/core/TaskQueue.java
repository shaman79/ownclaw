package com.ownclaw.core;

import com.ownclaw.agent.AgentLoop;
import com.ownclaw.agent.AgentResult;
import com.ownclaw.agent.AgentTrajectory;
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
import java.util.List;

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
    private final TaskCancellationService cancellationService;
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
                     ChatStatusEmitter statusEmitter, OwnClawConfig config,
                     TaskCancellationService cancellationService) {
        this.agentLoop = agentLoop;
        this.eventLog = eventLog;
        this.statusEmitter = statusEmitter;
        this.cancellationService = cancellationService;
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
    public CompletableFuture<AgentResult> submit(String userId, String message, int priority) {
        return submit(userId, message, priority, null, List.of());
    }

    /**
     * @param currentMessageId the chat row this task answers, so the loop can skip exactly it
     *                         and no other; null for a scheduled or background run
     * @param attachmentIds    the files sent with the message, bound to this task explicitly
     *                         rather than guessed from the newest chat row
     */
    public CompletableFuture<AgentResult> submit(String userId, String message, int priority,
                                                 String currentMessageId, List<String> attachmentIds) {
        if (queueSize.get() >= maxQueuedTasks) {
            eventLog.warn(userId, null, "queue.full", "Queue full, task rejected");
            // An outcome, not a sentence. Returned as a bare string, "System busy" was
            // indistinguishable from an answer: the scheduler filed the run as completed and
            // stored it as that run's result.
            return CompletableFuture.completedFuture(AgentResult.error(
                    "System busy — please try again later.", new AgentTrajectory(), 0));
        }

        CompletableFuture<AgentResult> future = new CompletableFuture<>();
        QueuedTask task = new QueuedTask(userId, message, priority, System.currentTimeMillis(), future,
                currentMessageId, attachmentIds == null ? List.of() : List.copyOf(attachmentIds));
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
    public CompletableFuture<AgentResult> submit(String userId, String message) {
        return submit(userId, message, 1);
    }

    /**
     * Returns true if a task is currently being processed or waiting in the queue.
     * Used by the deploy script to avoid restarting during active work.
     */
    /**
     * Whether this user has work running or waiting.
     * <p>
     * Needed because a browser that reconnects mid-task has no way to know one is in flight:
     * status messages are live-only and are not replayed, so a reload during a six-minute task
     * showed a completely idle chat with an enabled Send button, and the obvious conclusion was
     * that the request had been lost.
     */
    public boolean isBusyFor(String userId) {
        if (userId == null) return false;
        for (var q : java.util.List.of(interactiveQueue, backgroundQueue)) {
            for (QueuedTask t : q) {
                if (userId.equals(t.userId())) return true;
            }
        }
        return runningUsers.contains(userId);
    }

    /** Users whose tasks are executing right now. */
    private final java.util.Set<String> runningUsers =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    public boolean isBusy() {
        return running.get() > 0 || queueSize.get() > 0;
    }

    private void processLoop(PriorityBlockingQueue<QueuedTask> lane, String laneName) {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                QueuedTask task = lane.take();
                queueSize.decrementAndGet();
                running.incrementAndGet();
                runningUsers.add(task.userId());

                try {
                    // Drop work that was already waiting when the user pressed Stop.
                    //
                    // isCancelled() compares against when a task STARTED, and a queued task
                    // starts after the Stop, so it reads as new work and runs. The user does not
                    // see it that way: they queued it, changed their mind, pressed Stop, and
                    // watched it start regardless.
                    Long stoppedAt = cancellationService.stoppedAt(task.userId());
                    if (stoppedAt != null && task.enqueuedAt() <= stoppedAt) {
                        log.info("Dropping queued task for {} — it was waiting when Stop was pressed",
                                task.userId());
                        task.future().complete(AgentResult.cancelled(
                                "Cancelled before it started — it was still queued when you "
                                        + "pressed Stop.", new AgentTrajectory(), 0));
                        continue;
                    }

                    // Priority is the origin signal: the scheduler and /bg submit at 2,
                    // a chat message at 1. Nobody is waiting on the former.
                    boolean unattended = task.priority() >= BACKGROUND_PRIORITY;
                    // executeFull, not execute: execute() returns the response string and throws
                    // the outcome away. That is where the scheduler lost the ability to tell a
                    // finished job from one that gave up, and so recorded every run as completed.
                    task.future().complete(
                            agentLoop.executeFull(task.userId(), task.message(), unattended,
                                    task.currentMessageId(), task.attachmentIds()));
                } catch (Exception e) {
                    log.error("Task processing failed on the {} lane for user {}: {}",
                            laneName, task.userId(), e.getMessage(), e);
                    // execute() used to emit this before swallowing the exception; executeFull
                    // lets it out, so the notice has to happen here or a crash goes unannounced.
                    statusEmitter.emit(task.userId(), StatusMessage.Type.FAILED,
                            "An unexpected error occurred.");
                    task.future().complete(AgentResult.error(
                            "Internal error: " + e.getMessage(), new AgentTrajectory(), 0));
                } finally {
                    running.decrementAndGet();
                    runningUsers.remove(task.userId());
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
            CompletableFuture<AgentResult> future,
            String currentMessageId,
            List<String> attachmentIds
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
