package com.ownclaw.agent;

import com.ownclaw.core.TaskCancellationService;
import com.ownclaw.llm.LlmMessage;
import com.ownclaw.llm.LlmProgress;
import com.ownclaw.llm.LlmProvider;
import com.ownclaw.llm.LlmRequestConfig;
import com.ownclaw.llm.LlmResponse;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * The local model as every task of the process shares it: Ollama serves one request at a time.
 * <p>
 * What a task waits on -- a delegation, a think or code call when the cloud is not available --
 * is foreground work ({@link #foreground}). The local model's summaries of private results
 * ({@link TaskChat}) are background work: written one at a time, for every task in the order
 * they were asked for, on one thread, and only while nothing is in the foreground. A summary under
 * way when foreground work begins is ended, and written again once the lane is free, so it never
 * holds up a task -- not its own, and not one that started after its task ended.
 */
final class LocalLane {

    /** Foreground work under way; guarded by this. */
    private int foreground;
    /** Ends the background call under way, or null; guarded by this. */
    private Runnable backgroundCall;
    /** Whether the task of the background call under way has been stopped; guarded by this. */
    private BooleanSupplier backgroundStopped;
    /** Whether foreground work ended the background call under way. */
    private volatile boolean preempted;
    /** Writes the background work, one at a time and in order; made for the first. */
    private ExecutorService background;

    /** Thrown into a background call that foreground work ended: it is made again. */
    private static final class Preempted extends RuntimeException {
        Preempted() {
            super("the local model was needed for a task", null, false, false);
        }
    }

    /**
     * Run work a task waits on. While it runs no background call begins, and the one under way,
     * if there is one, is ended to be made again afterwards.
     */
    <T> T foreground(Supplier<T> work) {
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

    /**
     * From background work: the call, made once nothing is in the foreground -- and made again
     * whenever foreground work ends it. A stop of its task ends the wait, the call itself on its
     * next event, and at once a call that has sent nothing yet ({@link #interruptStopped}).
     *
     * @throws TaskCancellationService.TaskCancelledException when its task is stopped
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
                // Foreground work or a stop that came before the call was under way ends it now.
                boolean end;
                synchronized (LocalLane.this) {
                    backgroundCall = cancel;
                    end = cancel != null && (foreground > 0 || stopped.getAsBoolean());
                    if (end && foreground > 0) preempted = true;
                }
                if (end) cancel.run();
            }
        };
        for (;;) {
            synchronized (this) {
                while (foreground > 0 && !stopped.getAsBoolean()) wait(1_000);
                if (stopped.getAsBoolean()) throw new TaskCancellationService.TaskCancelledException(taskId);
                preempted = false;
                backgroundStopped = stopped;
            }
            try {
                return local.chat(prompt, new LlmRequestConfig(null, null, false).withProgress(hook));
            } catch (Preempted again) {
                // Foreground work ended it: made again once the lane is free.
            } finally {
                synchronized (this) {
                    backgroundCall = null;
                    backgroundStopped = null;
                }
            }
        }
    }

    /**
     * End the background call under way if its task has been stopped -- whether that task is
     * still running or has ended. Told after every stop.
     */
    void interruptStopped() {
        Runnable cancel;
        synchronized (this) {
            cancel = backgroundCall != null && backgroundStopped != null && backgroundStopped.getAsBoolean()
                    ? backgroundCall : null;
        }
        if (cancel != null) cancel.run();
    }
}
