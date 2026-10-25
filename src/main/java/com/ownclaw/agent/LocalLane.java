package com.ownclaw.agent;

import com.ownclaw.core.TaskCancellationService;
import com.ownclaw.llm.LlmMessage;
import com.ownclaw.llm.LlmProgress;
import com.ownclaw.llm.LlmProvider;
import com.ownclaw.llm.LlmRequestConfig;
import com.ownclaw.llm.LlmResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * The local model as every task of the process shares it: it works on one task at a time.
 * <p>
 * A task takes the lane with its first local call -- a delegation, a think or code call when the
 * cloud is not available -- and keeps it until it ends ({@link #release}): the next task's local
 * work begins when the first task has finished, not between its calls, where it would take the
 * model's loaded context from it and, with another model, load that for minutes. A task that
 * needs the lane while another has it waits, in turn -- attended work before unattended, each in
 * the order it asked -- and the wait is a call under way for its task: the stall watchdog does
 * not take it for silence, and a Stop ends it ({@link AgentContext#progress}).
 * <p>
 * What a task waits on is foreground work ({@link #foreground}). The local model's summaries of
 * private results ({@link TaskChat}) are background work: written one at a time, for every task
 * in the order they were asked for, on one thread, and only while nothing is in the foreground
 * and no other task has the lane. A summary under way when foreground work begins, or another
 * task takes the lane, is ended, and written again once the lane is free, so it never holds up a
 * task; and a summary ends with its task ({@link #interruptStopped}).
 */
final class LocalLane {

    /** The task that has the lane, or null; guarded by this. */
    private String owner;
    /** The tasks waiting for it, in the order they take it; guarded by this. */
    private final List<Waiting> waiting = new ArrayList<>();
    /** Foreground work under way; guarded by this. */
    private int foreground;
    /** Ends the background call under way, or null; guarded by this. */
    private Runnable backgroundCall;
    /** The task of the background call under way; guarded by this. */
    private String backgroundTask;
    /** Whether the background call under way is over: its task stopped or ended; guarded by this. */
    private BooleanSupplier backgroundStopped;
    /** Whether foreground work, or another task taking the lane, ended the background call under way. */
    private volatile boolean preempted;
    /** Writes the background work, one at a time and in order; made for the first. */
    private ExecutorService background;
    /** Told when a task begins to wait for the lane, with the task: its page is told why it waits. */
    private final BiConsumer<AgentContext, String> waits;

    private record Waiting(String taskId, boolean attended) {}

    /** What a task waiting for the lane is told. */
    static final String WAITING = "Waiting for the local model: another task is using it, and it works on "
            + "one task at a time.";

    LocalLane() {
        this((context, why) -> {});
    }

    LocalLane(BiConsumer<AgentContext, String> waits) {
        this.waits = waits;
    }

    /** Thrown into a background call that foreground work ended: it is made again. */
    private static final class Preempted extends RuntimeException {
        Preempted() {
            super("the local model was needed for a task", null, false, false);
        }
    }

    /**
     * Run work {@code task} waits on, the lane taken for it first ({@link #take}). While it runs no
     * background call begins, and the one under way, if there is one, is ended to be made again
     * afterwards.
     *
     * @throws TaskCancellationService.TaskCancelledException when the task is stopped while it waits
     */
    <T> T foreground(AgentContext task, Supplier<T> work) {
        take(task);
        Runnable cancel;
        synchronized (this) {
            foreground++;
            cancel = backgroundCall;
            if (cancel != null) preempted = true;
        }
        if (cancel != null) cancel.run();
        try {
            return work.get();
        } finally {
            synchronized (this) {
                foreground--;
                notifyAll();
            }
        }
    }

    /**
     * Take the lane for {@code task}, unless it has it: at once when it is free and nobody waits,
     * otherwise once the tasks ahead of it have finished. A background call of another task under
     * way is ended as the lane is taken.
     *
     * @throws TaskCancellationService.TaskCancelledException when the task is stopped while it waits
     */
    void take(AgentContext task) {
        String id = task.taskId();
        var me = new Waiting(id, !task.isUnattended());
        synchronized (this) {
            if (id.equals(owner)) return;
            if (owner == null && waiting.isEmpty()) {
                claim(id);
                return;
            }
            int at = waiting.size();
            if (me.attended()) {
                while (at > 0 && !waiting.get(at - 1).attended()) at--;
            }
            waiting.add(at, me);
        }
        waits.accept(task, WAITING);
        LlmProgress hook = task.progress();
        // A call under way for the task's watchdog and Stop: woken by the cancel, it asks again.
        hook.calling(() -> {
            synchronized (LocalLane.this) {
                LocalLane.this.notifyAll();
            }
        });
        try {
            synchronized (this) {
                while (owner != null || waiting.get(0) != me) {
                    if (task.isCancelled()) {
                        waiting.remove(me);
                        notifyAll();
                        throw new TaskCancellationService.TaskCancelledException(id);
                    }
                    wait(1_000);
                }
                waiting.remove(0);
                claim(id);
            }
        } catch (InterruptedException e) {
            synchronized (this) {
                waiting.remove(me);
                notifyAll();
            }
            Thread.currentThread().interrupt();
            throw new TaskCancellationService.TaskCancelledException(id);
        } finally {
            hook.calling(null);
        }
    }

    /** The lane is {@code taskId}'s now; another task's background call under way is ended. Under this. */
    private void claim(String taskId) {
        owner = taskId;
        if (backgroundCall != null && !taskId.equals(backgroundTask)) {
            preempted = true;
            // A cancel only marks the call ended and takes no lock: run here, under this.
            backgroundCall.run();
        }
    }

    /**
     * {@code taskId} has ended: the lane, if it had it, goes to the task first in turn, and it no
     * longer waits for it. Told by every task as it ends, whether it took the lane or not.
     */
    synchronized void release(String taskId) {
        if (taskId == null) return;
        if (taskId.equals(owner)) owner = null;
        waiting.removeIf(w -> w.taskId().equals(taskId));
        notifyAll();
    }

    /** The task that has the lane, or null. */
    synchronized String owner() {
        return owner;
    }

    /** Queue background work: it runs on the lane's one thread, after what was queued before it. */
    synchronized void background(Runnable job) {
        if (background == null) {
            background = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "local-model-background");
                t.setDaemon(true);
                return t;
            });
        }
        background.execute(job);
    }

    /** Whether a background call of {@code taskId} has to wait or end: foreground work, or another task's lane. Under this. */
    private boolean blocked(String taskId) {
        return foreground > 0 || owner != null && !owner.equals(taskId);
    }

    /**
     * From background work of {@code taskId}: the call, made once nothing is in the foreground and
     * no other task has the lane -- and made again whenever foreground work or another task taking
     * the lane ends it. When {@code stopped} turns true -- its task was stopped, or has ended --
     * that ends the wait, the call itself on its next event, and at once a call that has sent
     * nothing yet ({@link #interruptStopped}).
     *
     * @throws TaskCancellationService.TaskCancelledException when {@code stopped} is true
     */
    LlmResponse call(LlmProvider local, List<LlmMessage> prompt, String taskId, BooleanSupplier stopped)
            throws InterruptedException {
        LlmProgress hook = new LlmProgress() {
            @Override
            public void onProgress() {
                if (stopped.getAsBoolean()) throw new TaskCancellationService.TaskCancelledException(taskId);
                if (preempted) throw new Preempted();
            }

            @Override
            public void calling(Runnable cancel) {
                // Foreground work, another task's lane or a stop that came before the call was
                // under way ends it now.
                boolean end;
                synchronized (LocalLane.this) {
                    backgroundCall = cancel;
                    boolean blocked = blocked(taskId);
                    end = cancel != null && (blocked || stopped.getAsBoolean());
                    if (end && blocked) preempted = true;
                }
                if (end) cancel.run();
            }
        };
        for (;;) {
            synchronized (this) {
                while (blocked(taskId) && !stopped.getAsBoolean()) wait(1_000);
                if (stopped.getAsBoolean()) throw new TaskCancellationService.TaskCancelledException(taskId);
                preempted = false;
                backgroundStopped = stopped;
                backgroundTask = taskId;
            }
            try {
                return local.chat(prompt, new LlmRequestConfig(null, null, false).withProgress(hook)
                        .answeringDirectly());
            } catch (Preempted again) {
                // Foreground work or another task's lane ended it: made again once the lane is free.
            } finally {
                synchronized (this) {
                    backgroundCall = null;
                    backgroundStopped = null;
                    backgroundTask = null;
                }
            }
        }
    }

    /**
     * End the background call under way if its task has been stopped or has ended, and wake the
     * waits of stopped tasks: told after every stop, and by a task's chat as the task ends
     * ({@link TaskChat#close}).
     */
    void interruptStopped() {
        Runnable cancel;
        synchronized (this) {
            cancel = backgroundCall != null && backgroundStopped != null && backgroundStopped.getAsBoolean()
                    ? backgroundCall : null;
            notifyAll();
        }
        if (cancel != null) cancel.run();
    }
}
