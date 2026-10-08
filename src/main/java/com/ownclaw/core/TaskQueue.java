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
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The task queue: tasks are submitted and run asynchronously, in priority order, one at a time
 * per lane -- one lane, unless {@code separate-background-lane} gives background work a second.
 * <p>
 * It serializes tasks, not Ollama. With one lane no two queued tasks run at once; with two, an
 * interactive and a background task can both call Ollama. Beside them the local model writes its
 * summaries of the private results of attended tasks, ended ones too -- but only while no task's
 * work is on it: a summary under way makes way for that, and is written again afterwards
 * ({@code LocalLane}). Work outside the queue -- an agent run from the ops or debug API, the ops
 * Ollama probe, the setup benchmark -- can call it beside a task. Nothing else serializes local
 * calls: the Ollama server decides whether requests that arrive together run side by side or one
 * after the other.
 * <p>
 * A message the owner sends in a chat while a task of that chat runs goes to that task
 * ({@link #send}), which reads it before its next step -- when it came from where the task did
 * ({@link Inbox#offer}); what the task has not read when it ends is queued here as tasks of their
 * own, each in the place it took when it was sent ({@link #runChat}).
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

    /** A message sent in a chat: interactive work. */
    private static final int CHAT_PRIORITY = 1;

    /** Interactive work. When lanes are off, everything goes here and nothing changes. */
    private final PriorityBlockingQueue<QueuedTask> interactiveQueue = new PriorityBlockingQueue<>();
    /** Background work, drained by its own thread only when lanes are enabled. */
    private final PriorityBlockingQueue<QueuedTask> backgroundQueue = new PriorityBlockingQueue<>();

    private final AtomicInteger queueSize = new AtomicInteger(0);
    /** Count, not a flag: with two lanes there can be two tasks in flight. */
    private final AtomicInteger running = new AtomicInteger(0);
    /**
     * The order work was sent in: of two tasks with the same priority, the one sent first runs
     * first -- a message handed to a running task that ended without reading it too, in the
     * place it took when it was sent ({@link #steer}).
     */
    private final AtomicLong added = new AtomicLong();
    private final boolean separateBackgroundLane;
    private ExecutorService workerPool;

    /**
     * The inbox of each chat task running now, by its chat ({@link #runChat}): where a message
     * sent in that chat goes, if the task takes it ({@link #steer}). By user and chat both, so no
     * message reaches another user's task.
     */
    private final Map<Chat, Inbox> inboxes = new ConcurrentHashMap<>();

    private record Chat(String userId, String sessionId) {}

    /**
     * What became of a message sent in a chat ({@link #send}), with the line its sender is shown:
     * on the web page under his message, on Telegram as a reply.
     */
    public enum Fate {
        /** Given to the task running in its chat, which reads it before its next step. */
        STEERED("→ To the running task: it reads your message when its current step finishes."),
        /** Queued behind work that runs or waits: a task of its own, after that work. */
        QUEUED("Queued: it runs as a task of its own after the work ahead of it."),
        /** Nothing ran or waited, and its task starts now: there is nothing to say. */
        STARTED(null),
        /** The queue was full: its answer says so, and there is nothing more to say. */
        REFUSED(null);

        private final String line;

        Fate(String line) {
            this.line = line;
        }

        /** The line shown to the sender, or null when there is nothing to say. */
        public String line() {
            return line;
        }
    }

    /** What the sender of a message the task did not read is told, once it is queued. */
    static final String UNREAD = "The task ended before it read your message: it runs as a task of its own.";

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
        // minutes blocked every interactive message behind it on the same thread. What that
        // leaves serialized is in the class comment.
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
     * Submit a task that comes from no chat: a scheduled run, or /bg.
     *
     * @param userId  user who submitted the task
     * @param message the user's message
     * @param priority task priority (0 = highest, 2 = background)
     * @return a future that will contain the response (or an error message)
     */
    public CompletableFuture<AgentResult> submit(String userId, String message, int priority) {
        CompletableFuture<AgentResult> future = new CompletableFuture<>();
        add(new QueuedTask(userId, message, priority, System.currentTimeMillis(), added.incrementAndGet(),
                future::complete, null));
        return future;
    }

    /** Submit with default priority (P1 = normal). */
    public CompletableFuture<AgentResult> submit(String userId, String message) {
        return submit(userId, message, 1);
    }

    /**
     * What a message the owner sends in a chat becomes -- the one decision, for the web chat and
     * Telegram: given to the task running in its chat ({@link #steer}), unless he asked for it to
     * be queued; otherwise queued as a task of its own, answered through
     * {@link UserMessage#answer} -- by "System busy" at once when the queue is full. Its user row
     * is saved before this is asked.
     *
     * @param queue the sender asked for a task of its own, after the work ahead of it
     */
    public Fate send(UserMessage message, boolean queue) {
        if (!queue && steer(message)) return Fate.STEERED;
        boolean behind = isBusyFor(message.userId());
        if (!enqueue(message, added.incrementAndGet())) return Fate.REFUSED;
        return behind ? Fate.QUEUED : Fate.STARTED;
    }

    /**
     * Give a message to the task running in its chat, which reads it before its next step. It
     * takes its place in the queue now, which it keeps if the task ends without reading it.
     * <p>
     * False -- the caller then runs the message as a task of its own -- when no task of this user
     * runs in that chat, when the one that did has just ended, and when the task does not take
     * it ({@link Inbox#offer}): a message from another channel than the task's, with files, or
     * sent with other options than the task's, and any sent after one of those.
     */
    public boolean steer(UserMessage message) {
        Inbox inbox = inboxes.get(new Chat(message.userId(), message.sessionId()));
        return inbox != null && inbox.offer(message, added.incrementAndGet());
    }

    /**
     * Queue a chat message as a task of its own, in its place among the work queued.
     *
     * @param order its place: taken when it was sent
     * @return false when the queue was full: it has been answered so
     */
    private boolean enqueue(UserMessage message, long order) {
        return add(new QueuedTask(message.userId(), message.text(), CHAT_PRIORITY, System.currentTimeMillis(),
                order, result -> answer(message, result), message));
    }

    /** @return false when the queue was full: the task has been answered so, and is not queued */
    private boolean add(QueuedTask task) {
        if (queueSize.get() >= maxQueuedTasks) {
            eventLog.warn(task.userId(), null, "queue.full", "Queue full, task rejected");
            // An outcome, not a sentence. Returned as a bare string, "System busy" was
            // indistinguishable from an answer: the scheduler filed the run as completed and
            // stored it as that run's result.
            task.done().accept(AgentResult.error(
                    "System busy — please try again later.", new AgentTrajectory(), 0));
            return false;
        }

        // With lanes off, background work stays in the interactive queue and the behaviour is
        // byte-for-byte what it was: one queue, one thread, priority order within it.
        boolean background = separateBackgroundLane && task.priority() >= BACKGROUND_PRIORITY;
        (background ? backgroundQueue : interactiveQueue).add(task);
        int pos = queueSize.incrementAndGet();

        if (pos > 1) {
            statusEmitter.emit(task.userId(), StatusMessage.Type.QUEUED,
                    "Task queued (position " + pos + ")", markOf(task));
        }

        eventLog.info(task.userId(), null, "task.queued",
                "Priority P" + task.priority() + ", queue size " + pos);
        return true;
    }

    /**
     * Run the task of a chat message on this thread and deliver its answer: the queue's worker
     * runs a queued message here, and the ops API a chat turn. While it runs, what is sent in its
     * chat goes to it, if it takes it ({@link #steer}); once it has ended, however it ended, and
     * its answer has been delivered, what it did not read is queued as tasks of their own, each in
     * the place it took when it was sent -- each answering its own row, its sender told -- so each
     * finds that answer in the chat it reads. Queued then, they are not work a Stop that ended the
     * task finds waiting: no message sent to a task is lost with it.
     *
     * @throws RuntimeException what the task threw, once "Internal error" has been delivered as
     *                          its answer
     */
    public AgentResult runChat(UserMessage message) {
        Chat chat = new Chat(message.userId(), message.sessionId());
        Inbox inbox = new Inbox(message.channel(), message.options());
        inboxes.put(chat, inbox);
        try {
            AgentResult result;
            try {
                result = agentLoop.executeFull(message.userId(), message.text(), false, message.messageId(),
                        message.attachmentIds(), message.channel(), inbox, message.options());
            } catch (RuntimeException e) {
                answer(message, AgentResult.error("Internal error: " + e.getMessage(), new AgentTrajectory(), 0));
                throw e;
            }
            answer(message, result);
            return result;
        } finally {
            // Closed before it leaves the map: a sender who still finds it is refused once what
            // the task did not read is queued, and one who does not find it comes after that.
            var queued = new ArrayList<UserMessage>();
            inbox.close((unread, order) -> {
                if (enqueue(unread, order)) queued.add(unread);
            });
            inboxes.remove(chat, inbox);
            for (UserMessage unread : queued) {
                statusEmitter.emit(unread.userId(), StatusMessage.Type.QUEUED, UNREAD,
                        Map.of("requeued", unread.messageId()));
            }
        }
    }

    /**
     * Hand a chat message's task its result. Whatever the delivery throws costs this answer and
     * nothing else: the delivery used to be a stage of the task's future, which kept what it
     * threw to itself, and here it would end the worker thread and every task after this one.
     */
    private static void answer(UserMessage message, AgentResult result) {
        try {
            message.answer().accept(result);
        } catch (Throwable t) {
            log.warn("The answer to a message of {} could not be delivered: {}", message.userId(), t.toString());
        }
    }

    /**
     * Whether this user has work running or waiting.
     * <p>
     * Needed because a browser that reconnects mid-task has no way to know one is in flight:
     * status messages are live-only and are not replayed, so a reload during a six-minute task
     * showed a completely idle chat, and the obvious conclusion was that the request had been
     * lost.
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

    /**
     * Whether this user has attended work running or waiting: a task somebody waits on, which a
     * page shows as its working state when it connects or switches chats. Unattended work -- a
     * scheduled run, /bg -- is left out: none of its statuses is shown as that state
     * (ChatStatusEmitter#BACKGROUND), so a spinner started for it would have nothing to stop it.
     */
    public boolean isAttendedBusyFor(String userId) {
        if (userId == null) return false;
        // The interactive queue only: the background queue holds unattended work alone.
        for (QueuedTask t : interactiveQueue) {
            if (userId.equals(t.userId()) && t.priority() < BACKGROUND_PRIORITY) return true;
        }
        return attendedUsers.contains(userId);
    }

    /** Users whose tasks are executing right now. */
    private final java.util.Set<String> runningUsers =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Users whose attended tasks are executing right now. Attended work runs on the interactive
     * lane alone, one task at a time, so the end of one cannot take out another still running.
     */
    private final java.util.Set<String> attendedUsers =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

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
                runningUsers.add(task.userId());
                boolean attended = task.priority() < BACKGROUND_PRIORITY;
                if (attended) attendedUsers.add(task.userId());

                try {
                    // Drop work that was already waiting when the user stopped everything: asked
                    // with when it was queued, not when it would start, which is after the Stop.
                    // The answer says who stopped it -- Stop, /cancel, the ops API.
                    String stoppedBy = cancellationService.why(task.userId(), null, task.enqueuedAt());
                    if (stoppedBy != null) {
                        log.info("Dropping a queued task of {}, waiting when it was stopped: {}",
                                task.userId(), stoppedBy);
                        task.done().accept(AgentResult.cancelled(
                                "**Stopped:** " + stoppedBy + ", while it was still waiting in the "
                                        + "queue: it never started.", new AgentTrajectory(), 0));
                        continue;
                    }

                    if (task.chat() != null) {
                        try {
                            runChat(task.chat());
                        } catch (RuntimeException e) {
                            failed(task, laneName, e);   // runChat has answered it, the error too
                        }
                        continue;
                    }
                    // Priority is the origin signal: the scheduler and /bg submit at 2, and
                    // nobody is waiting on that.
                    boolean unattended = task.priority() >= BACKGROUND_PRIORITY;
                    // The whole result, not only its text: the scheduler once kept only the
                    // response string, and so could not tell a finished job from one that gave
                    // up and recorded every run as completed.
                    task.done().accept(
                            agentLoop.executeFull(task.userId(), task.message(), unattended, null, List.of(), null));
                } catch (Exception e) {
                    failed(task, laneName, e);
                    task.done().accept(AgentResult.error(
                            "Internal error: " + e.getMessage(), new AgentTrajectory(), 0));
                } finally {
                    running.decrementAndGet();
                    runningUsers.remove(task.userId());
                    if (attended) attendedUsers.remove(task.userId());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /**
     * Log a task that threw, and tell its user. executeFull turns what fails inside the loop into
     * the task's ending; what fails before the loop starts reaches here, and without this notice
     * a crash would go unannounced.
     */
    private void failed(QueuedTask task, String laneName, Exception e) {
        log.error("Task processing failed on the {} lane for user {}: {}",
                laneName, task.userId(), e.getMessage(), e);
        statusEmitter.emit(task.userId(), StatusMessage.Type.FAILED, "An unexpected error occurred.",
                markOf(task));
    }

    /**
     * What marks a status about this task as unattended work's (ChatStatusEmitter#BACKGROUND):
     * null for attended work. Its statuses from here carry no task id for the emitter to know it
     * by -- queued, it has none yet, and failed here, the queue never learned it -- so the mark is
     * set here, by the rule the queue runs it by: its priority.
     */
    private static Map<String, Object> markOf(QueuedTask task) {
        return task.priority() >= BACKGROUND_PRIORITY ? Map.of(ChatStatusEmitter.BACKGROUND, true) : null;
    }

    public int getQueueSize() {
        return queueSize.get();
    }

    /**
     * A task waiting in the priority queue.
     *
     * @param enqueuedAt when it was queued: what a Stop is measured against
     * @param order      its place among the work of its priority: when it was sent ({@link #added})
     * @param done       what its result is handed to: the submitter's future, or the chat
     *                   message's answer
     * @param chat       the chat message it runs, or null for a task that comes from no chat
     */
    private record QueuedTask(
            String userId,
            String message,
            int priority,
            long enqueuedAt,
            long order,
            Consumer<AgentResult> done,
            UserMessage chat
    ) implements Comparable<QueuedTask> {

        @Override
        public int compareTo(QueuedTask other) {
            // Lower priority number = higher priority
            int cmp = Integer.compare(this.priority, other.priority);
            // Same priority: in the order sent
            return cmp != 0 ? cmp : Long.compare(this.order, other.order);
        }
    }
}
