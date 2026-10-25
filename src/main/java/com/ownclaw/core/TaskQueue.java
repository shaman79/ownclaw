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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * The task queue: tasks are submitted and run asynchronously, in priority order, on a pool of
 * {@code max-concurrent-tasks} workers -- one task at a time per chat, tasks of different chats
 * side by side. A task that comes from no chat, a scheduled run or /bg, is a chat of its own: it
 * runs beside the chats, but never takes the last worker, so a message sent in a chat always
 * finds one. With {@code max-concurrent-tasks: 1} every task runs after the one before it, as
 * they all did until there was more than one worker.
 * <p>
 * It does not serialize the local model: the agent loop's local lane does, one task at a time,
 * from a task's first local call to its end ({@code LocalLane}).
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
    /** How many tasks run at once, at most: one per chat, of different chats. */
    private final int workers;

    /** Priority 2 and above is background work — the scheduler and /bg submit at 2. */
    public static final int BACKGROUND_PRIORITY = 2;

    /** A message sent in a chat: interactive work. */
    private static final int CHAT_PRIORITY = 1;

    /**
     * Everything below is guarded by it: what waits, in the order it runs in -- priority, then the
     * order it was sent in -- and what runs, by chat.
     */
    private final Object lock = new Object();
    private final TreeSet<QueuedTask> waiting = new TreeSet<>();
    /** The chats a task runs in now: a chat's next task waits for it. */
    private final Map<Object, QueuedTask> runningByChat = new HashMap<>();
    /** Tasks the pool runs now, and of them background ones. */
    private int runningOnPool;
    private int runningBackground;
    /** Chat turns the ops API runs on its own thread now (#runChat): work going on, too. */
    private int runningOutside;
    private boolean stopped;

    /**
     * The order work was sent in: of two tasks with the same priority, the one sent first runs
     * first -- a message handed to a running task that ended without reading it too, in the
     * place it took when it was sent ({@link #steer}).
     */
    private final AtomicLong added = new AtomicLong();
    private ExecutorService workerPool;

    /**
     * The inbox of each chat task running now, by its chat ({@link #runChat}): where a message
     * sent in that chat goes, if the task takes it ({@link #steer}). By user and chat both, so no
     * message reaches another user's task.
     */
    private final Map<Chat, Inbox> inboxes = new ConcurrentHashMap<>();

    /** A chat, by its user and its id: what one task at a time runs in. */
    public record Chat(String userId, String sessionId) {}

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
        this.workers = Math.max(1, config.getQueue().getMaxConcurrentTasks());
    }

    @PostConstruct
    public void start() {
        var counter = new AtomicInteger();
        workerPool = Executors.newFixedThreadPool(workers, r -> {
            Thread t = new Thread(r, "task-queue-worker-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        log.info("Task queue started: {} worker(s), one task at a time per chat", workers);
    }

    @PreDestroy
    public void stop() {
        synchronized (lock) {
            stopped = true;
        }
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
                future::complete, null, new Object()));
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
     * is saved before this is asked. Queued it is only when it waits: behind a task of its own
     * chat, or for a worker; one sent in a chat where nothing runs starts beside the others.
     *
     * @param queue the sender asked for a task of its own, after the work ahead of it
     */
    public Fate send(UserMessage message, boolean queue) {
        if (!queue && steer(message)) return Fate.STEERED;
        QueuedTask task = enqueue(message, added.incrementAndGet());
        if (task == null) return Fate.REFUSED;
        synchronized (lock) {
            return waiting.contains(task) ? Fate.QUEUED : Fate.STARTED;
        }
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
     * @return the task queued, or null when the queue was full: it has been answered so
     */
    private QueuedTask enqueue(UserMessage message, long order) {
        var task = new QueuedTask(message.userId(), message.text(), CHAT_PRIORITY, System.currentTimeMillis(),
                order, result -> answer(message, result), message, new Chat(message.userId(), message.sessionId()));
        return add(task) ? task : null;
    }

    /** @return false when the queue was full: the task has been answered so, and is not queued */
    private boolean add(QueuedTask task) {
        int ahead;
        synchronized (lock) {
            if (waiting.size() >= maxQueuedTasks) {
                ahead = -1;
            } else {
                waiting.add(task);
                ahead = 0;
            }
        }
        if (ahead < 0) {
            eventLog.warn(task.userId(), null, "queue.full", "Queue full, task rejected");
            // An outcome, not a sentence. Returned as a bare string, "System busy" was
            // indistinguishable from an answer: the scheduler filed the run as completed and
            // stored it as that run's result.
            task.done().accept(AgentResult.error(
                    "System busy — please try again later.", new AgentTrajectory(), 0));
            return false;
        }
        dispatch();

        // Said only of work that waits -- behind its chat's task, or for a worker -- and how much
        // waits ahead of it.
        int position = -1;
        boolean behindItsChat = false;
        synchronized (lock) {
            if (waiting.contains(task)) {
                position = waiting.headSet(task).size() + 1;
                behindItsChat = runningByChat.containsKey(task.chatKey());
            }
        }
        if (position > 0) {
            statusEmitter.emit(task.userId(), StatusMessage.Type.QUEUED, behindItsChat
                    ? "Task queued: it runs after the task running in this chat."
                    : "Task queued (position " + position + "): every worker is busy.", markOf(task));
        }
        eventLog.info(task.userId(), null, "task.queued", "Priority P" + task.priority()
                + (position > 0 ? ", waiting at position " + position : ", started at once"));
        return true;
    }

    /**
     * Start what can start: in order, each task whose chat runs nothing, while a worker is free --
     * and background work only while another worker stays free for a chat.
     */
    private void dispatch() {
        var starting = new ArrayList<QueuedTask>();
        synchronized (lock) {
            if (stopped || workerPool == null) return;
            for (QueuedTask task : waiting) {
                if (runningOnPool + starting.size() >= workers) break;
                if (runningByChat.containsKey(task.chatKey())) continue;
                boolean background = task.priority() >= BACKGROUND_PRIORITY;
                if (background && workers > 1 && runningBackground >= workers - 1) continue;
                starting.add(task);
                runningByChat.put(task.chatKey(), task);
                if (background) runningBackground++;
            }
            waiting.removeAll(starting);
            runningOnPool += starting.size();
        }
        for (QueuedTask task : starting) {
            try {
                workerPool.execute(() -> runOnPool(task));
            } catch (RejectedExecutionException shutDown) {
                finished(task, true);
            }
        }
    }

    /** A task of the pool, run on its worker; its chat is let go, and what waits started, after it. */
    private void runOnPool(QueuedTask task) {
        try {
            // Drop work that was already waiting when the user stopped everything, or its chat:
            // asked with when it was queued, not when it would start, which is after the Stop. The
            // answer says who stopped it -- Stop, /cancel, the ops API.
            String stoppedBy = cancellationService.why(task.userId(), null,
                    task.chat() == null ? null : task.chat().sessionId(), task.enqueuedAt());
            if (stoppedBy != null) {
                log.info("Dropping a queued task of {}, waiting when it was stopped: {}",
                        task.userId(), stoppedBy);
                task.done().accept(AgentResult.cancelled(
                        "**Stopped:** " + stoppedBy + ", while it was still waiting in the "
                                + "queue: it never started.", new AgentTrajectory(), 0));
                return;
            }
            if (task.chat() != null) {
                try {
                    runChatHeld(task.chat());
                } catch (RuntimeException e) {
                    failed(task, e);   // runChatHeld has answered it, the error too
                }
                return;
            }
            // Priority is the origin signal: the scheduler and /bg submit at 2, and nobody is
            // waiting on that.
            boolean unattended = task.priority() >= BACKGROUND_PRIORITY;
            // The whole result, not only its text: the scheduler once kept only the response
            // string, and so could not tell a finished job from one that gave up and recorded
            // every run as completed.
            task.done().accept(
                    agentLoop.executeFull(task.userId(), task.message(), unattended, null, List.of(), null));
        } catch (Exception e) {
            failed(task, e);
            task.done().accept(AgentResult.error(
                    "Internal error: " + e.getMessage(), new AgentTrajectory(), 0));
        } finally {
            finished(task, true);
        }
    }

    /** {@code task} has ended: its chat is let go, and what waits for a worker or for it starts. */
    private void finished(QueuedTask task, boolean onPool) {
        synchronized (lock) {
            runningByChat.remove(task.chatKey(), task);
            if (onPool) {
                runningOnPool--;
                if (task.priority() >= BACKGROUND_PRIORITY) runningBackground--;
            } else {
                runningOutside--;
            }
            lock.notifyAll();
        }
        dispatch();
    }

    /**
     * Run the task of a chat message on this thread -- the ops API's chat turn -- once no task of
     * its chat runs: it waits for the one that does, as a message queued in that chat would. Not
     * beside it: the second task would take the chat's inbox from the first, and would not read
     * the first one's answer.
     *
     * @throws RuntimeException what the task threw, once "Internal error" has been delivered as
     *                          its answer
     */
    public AgentResult runChat(UserMessage message) {
        var held = new QueuedTask(message.userId(), message.text(), CHAT_PRIORITY, System.currentTimeMillis(),
                added.incrementAndGet(), result -> {}, message, new Chat(message.userId(), message.sessionId()));
        synchronized (lock) {
            while (runningByChat.containsKey(held.chatKey()) || waitingIn(held.chatKey())) {
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted while waiting for the chat's task to end", e);
                }
            }
            runningByChat.put(held.chatKey(), held);
            runningOutside++;
        }
        try {
            return runChatHeld(message);
        } finally {
            finished(held, false);
        }
    }

    /** Whether a task of this chat waits in the queue. Under {@link #lock}. */
    private boolean waitingIn(Object chatKey) {
        for (QueuedTask t : waiting) {
            if (chatKey.equals(t.chatKey())) return true;
        }
        return false;
    }

    /**
     * Run the task of a chat message, its chat held, on this thread, and deliver its answer. While
     * it runs, what is sent in its chat goes to it, if it takes it ({@link #steer}); once it has
     * ended, however it ended, and its answer has been delivered, what it did not read is queued
     * as tasks of their own, each in the place it took when it was sent -- each answering its own
     * row, its sender told -- so each finds that answer in the chat it reads. Queued then, they
     * are not work a Stop that ended the task finds waiting: no message sent to a task is lost
     * with it. They wait for the chat, which is let go only after them.
     */
    private AgentResult runChatHeld(UserMessage message) {
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
                if (enqueue(unread, order) != null) queued.add(unread);
            });
            inboxes.remove(chat, inbox);
            for (UserMessage unread : queued) {
                statusEmitter.emit(unread.userId(), StatusMessage.Type.QUEUED, UNREAD,
                        Map.of("requeued", unread.messageId(), "sessionId", String.valueOf(unread.sessionId())));
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
     * Whether this user has work running or waiting, in any chat.
     * <p>
     * Needed because a browser that reconnects mid-task has no way to know one is in flight:
     * status messages are live-only and are not replayed, so a reload during a six-minute task
     * showed a completely idle chat, and the obvious conclusion was that the request had been
     * lost.
     */
    public boolean isBusyFor(String userId) {
        if (userId == null) return false;
        synchronized (lock) {
            for (QueuedTask t : waiting) {
                if (userId.equals(t.userId())) return true;
            }
            for (QueuedTask t : runningByChat.values()) {
                if (userId.equals(t.userId())) return true;
            }
        }
        return false;
    }

    /**
     * Whether this user has attended work running or waiting: a task somebody waits on, which a
     * page shows as its working state when it connects or switches chats. Unattended work -- a
     * scheduled run, /bg -- is left out: none of its statuses is shown as that state
     * (ChatStatusEmitter#BACKGROUND), so a spinner started for it would have nothing to stop it.
     */
    public boolean isAttendedBusyFor(String userId) {
        return attendedBusy(userId, null);
    }

    /** {@link #isAttendedBusyFor(String)}, in one chat: what that chat's page shows as working. */
    public boolean isAttendedBusyFor(String userId, String sessionId) {
        return sessionId == null ? isAttendedBusyFor(userId) : attendedBusy(userId, new Chat(userId, sessionId));
    }

    private boolean attendedBusy(String userId, Chat chat) {
        if (userId == null) return false;
        synchronized (lock) {
            for (QueuedTask t : waiting) {
                if (attendedOf(t, userId, chat)) return true;
            }
            for (QueuedTask t : runningByChat.values()) {
                if (attendedOf(t, userId, chat)) return true;
            }
        }
        return false;
    }

    private static boolean attendedOf(QueuedTask t, String userId, Chat chat) {
        return userId.equals(t.userId()) && t.priority() < BACKGROUND_PRIORITY
                && (chat == null || chat.equals(t.chatKey()));
    }

    /**
     * Returns true if a task is currently being processed or waiting in the queue -- the ops API's
     * chat turns among them. Used by the deploy script to avoid restarting during active work.
     */
    public boolean isBusy() {
        synchronized (lock) {
            return runningOnPool > 0 || runningOutside > 0 || !waiting.isEmpty();
        }
    }

    /**
     * Log a task that threw, and tell its user. executeFull turns what fails inside the loop into
     * the task's ending; what fails before the loop starts reaches here, and without this notice
     * a crash would go unannounced.
     */
    private void failed(QueuedTask task, Exception e) {
        log.error("Task processing failed for user {}: {}", task.userId(), e.getMessage(), e);
        statusEmitter.emit(task.userId(), StatusMessage.Type.FAILED, "An unexpected error occurred.",
                markOf(task));
    }

    /**
     * What a status about this task from here says of it: unattended work's mark
     * (ChatStatusEmitter#BACKGROUND), and the chat of a chat message's task, so a page shows it in
     * that chat alone. Its statuses from here carry no task id for the emitter to know it by --
     * queued, it has none yet, and failed here, the queue never learned it -- so they are set
     * here, by the rule the queue runs it by.
     */
    private static Map<String, Object> markOf(QueuedTask task) {
        var mark = new LinkedHashMap<String, Object>();
        if (task.priority() >= BACKGROUND_PRIORITY) mark.put(ChatStatusEmitter.BACKGROUND, true);
        if (task.chat() != null && task.chat().sessionId() != null) mark.put("sessionId", task.chat().sessionId());
        return mark.isEmpty() ? null : mark;
    }

    /** How many tasks wait. */
    public int getQueueSize() {
        synchronized (lock) {
            return waiting.size();
        }
    }

    /**
     * A task waiting in the queue.
     *
     * @param enqueuedAt when it was queued: what a Stop is measured against
     * @param order      its place among the work of its priority: when it was sent ({@link #added})
     * @param done       what its result is handed to: the submitter's future, or the chat
     *                   message's answer
     * @param chat       the chat message it runs, or null for a task that comes from no chat
     * @param chatKey    what one task at a time runs in: the message's {@link Chat}, or an object
     *                   of its own for a task that comes from no chat
     */
    private record QueuedTask(
            String userId,
            String message,
            int priority,
            long enqueuedAt,
            long order,
            Consumer<AgentResult> done,
            UserMessage chat,
            Object chatKey
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
