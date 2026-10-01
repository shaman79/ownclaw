package com.ownclaw.agent;

import com.ownclaw.conversation.ConversationService;
import com.ownclaw.core.TaskCancellationService;
import com.ownclaw.llm.CloudGateway;
import com.ownclaw.llm.LlmException;
import com.ownclaw.llm.LlmMessage;
import com.ownclaw.llm.LlmProgress;
import com.ownclaw.llm.LlmProvider;
import com.ownclaw.llm.LlmRequestConfig;
import com.ownclaw.llm.LlmResponse;
import com.ownclaw.llm.OutputTruncated;
import com.ownclaw.observability.ChatStatusEmitter;
import com.ownclaw.observability.ChatStatusEmitter.StatusMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * The chat an attended task reports its progress in while it works: a message before each step
 * of the loop, one before each tool call of a delegation, and the local model's summary of each
 * private result. Each is a row of role {@code progress} in the chat the task's own message was
 * saved in, never the answer, which is delivered as before.
 * <p>
 * A progress row is the owner's view of a task at work. No prompt reads one:
 * {@link ConversationService#contextOf} reads user and assistant rows only, and the full-text
 * index holds no other. Its content is what the cloud could be shown -- the code's header, the
 * cloud's own words beside its call, or a note that a summary exists -- and a summary of a
 * private result, which only the owner may read, is the row's private content, which only the
 * owner's own chat reads back.
 * <p>
 * A scheduled or background run has none ({@link #NONE}): nobody waits there, and its report is
 * delivered when it is done.
 */
public final class TaskChat {

    private static final Logger log = LoggerFactory.getLogger(TaskChat.class);

    /** Where the task's message came from, which decides where its progress is shown. */
    public enum Channel {
        /** The web chat: rows saved, and shown live in the page. */
        WEB,
        /** Telegram: rows saved, shown live in the page, and sent to Telegram. */
        TELEGRAM,
        /** A chat turn of the ops API: rows saved, nothing else. */
        OPS
    }

    /** The chat of a task that has none: nothing is posted, and nothing is summarised. */
    static final TaskChat NONE = new TaskChat(null, null, null, null, null, null, null);

    private final AgentContext task;
    private final String sessionId;
    private final Channel channel;
    private final ConversationService conversations;
    private final ChatStatusEmitter emitter;
    private final LlmProvider local;
    private final BiConsumer<LlmProvider, LlmResponse> billed;

    /** Writes the summaries, one at a time and in order, beside the task; made for the first. */
    private ExecutorService summaries;
    /** Delegations of this task under way; see {@link #whileDelegating}. */
    private int delegations;
    /** Ends the summary call under way, while there is one. */
    private volatile Runnable summaryCall;

    /**
     * @param task     the task, whose stop ends its summaries
     * @param local    the local model, which writes the summaries
     * @param billed   the task's account, handed each summary's reply with the provider that
     *                 served it
     */
    TaskChat(AgentContext task, String sessionId, Channel channel, ConversationService conversations,
             ChatStatusEmitter emitter, LlmProvider local, BiConsumer<LlmProvider, LlmResponse> billed) {
        this.task = task;
        this.sessionId = sessionId;
        this.channel = channel;
        this.conversations = conversations;
        this.emitter = emitter;
        this.local = local;
        this.billed = billed;
    }

    /**
     * Before a step of the loop runs: its header, and what the model wrote beside the call -- its
     * reasoning, on either protocol -- when it wrote anything. Nothing else: no parameters, no
     * results.
     *
     * @param narration the model's words, or null for a step no model chose
     */
    void step(int step, AgentAction action, String narration) {
        if (sessionId == null) return;
        String words = narration == null ? "" : TaskRecord.inWords(
                CloudGateway.scrub(narration, task.secretValues()).text()).strip();
        post("**" + header(step, action, task.elapsedMs(), task.cloudCostUsd()) + "**"
                + (words.isEmpty() ? "" : "\n\n" + words), null);
    }

    /**
     * "Step 7 · openwrt_run · 4m 12s · $1.23": the step, the tool -- with the skill's name for a
     * skill_create -- the time the task has run, and what its cloud calls have cost so far.
     */
    static String header(int step, AgentAction action, long elapsedMs, double costUsd) {
        String skill = action.isSkillCreate() ? AgentLoop.skillOf(action) : null;
        return "Step " + step + " · " + action.tool() + (skill == null ? "" : " " + skill)
                + " · " + TaskRecord.duration(elapsedMs)
                + " · " + String.format(Locale.ROOT, "$%.2f", costUsd);
    }

    /** Before a delegation's tool call runs: the local model's turn and the tool. */
    void localTurn(int turn, String tool) {
        if (sessionId == null) return;
        post("**Local model · turn " + turn + " · " + tool + "**", null);
    }

    /**
     * A private result: the local model summarises it for the owner, beside the task and in
     * order, and the summary is posted when it is written -- or, when it cannot be, why not.
     * The cloud's words cannot describe a private result: it is shown a description of it.
     */
    void privateResult(Artifact result) {
        if (sessionId == null) return;
        synchronized (this) {
            if (summaries == null) {
                summaries = Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "summaries-" + task.taskId());
                    t.setDaemon(true);
                    return t;
                });
            }
            summaries.execute(() -> summarise(result));
        }
    }

    /**
     * Run a delegation of this task. While one runs, no summary is begun: the local model serves
     * one request at a time, and a summary taken first would hold up the delegation's own turns.
     */
    <T> T whileDelegating(Supplier<T> delegation) {
        if (sessionId == null) return delegation.get();
        synchronized (this) {
            delegations++;
        }
        try {
            return delegation.get();
        } finally {
            synchronized (this) {
                delegations--;
                notifyAll();
            }
        }
    }

    /** End the summary call under way, if there is one. */
    void interrupt() {
        Runnable cancel = summaryCall;
        if (cancel != null) cancel.run();
    }

    /**
     * The task has ended: the summaries already asked for are still written, and then the thread
     * that writes them ends. A stop ends them sooner.
     */
    synchronized void close() {
        if (summaries != null) summaries.shutdown();
    }

    private void summarise(Artifact result) {
        String name = "Result " + result.n() + " (" + result.tool() + ")";
        String why = null;
        String summary = null;
        try {
            awaitNoDelegation();
            if (task.isCancelled()) {
                why = "the task was stopped";
            } else if (local == null || !local.isAvailable()) {
                why = "the local model is not answering";
            } else {
                LlmResponse reply = local.chat(prompt(result),
                        new LlmRequestConfig(null, null, false).withProgress(hook()));
                billed.accept(local, reply);
                summary = reply.content() == null ? "" : reply.content().strip();
                if (summary.isEmpty()) why = "the local model wrote nothing";
            }
        } catch (TaskCancellationService.TaskCancelledException stopped) {
            why = "the task was stopped";
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            why = "it was interrupted";
        } catch (OutputTruncated cut) {
            billed.accept(local, cut.reply());
            // Written by the code: the limit and its size, nothing of the result.
            why = cut.getMessage();
        } catch (LlmException e) {
            billed.accept(local, e.reply());
            why = failed(e);
        } catch (RuntimeException e) {
            why = failed(e);
        }
        if (why != null) {
            post("**" + name + "** — the local model could not summarise it: " + why + ".", null);
            return;
        }
        post("**" + name + "** — summarised by your local model; private, shown only to you.",
                "**" + name + "** — summarised by your local model, not seen by the cloud:\n\n"
                        + CloudGateway.scrub(summary, task.secretValues()).text());
    }

    /**
     * Its type, never its message: a failure while the local model read a private result can
     * quote it, and this line is the row's cloud-safe content. The log has the type too.
     */
    private String failed(Exception e) {
        log.warn("Task {}: a private result could not be summarised: {}", task.taskId(),
                e.getClass().getSimpleName());
        return "it failed (" + e.getClass().getSimpleName() + ")";
    }

    /** Wait while a delegation of this task runs (see {@link #whileDelegating}), or until it is stopped. */
    private synchronized void awaitNoDelegation() throws InterruptedException {
        while (delegations > 0 && !task.isCancelled()) wait(1_000);
    }

    /**
     * The summary call's hook, with the task's stop semantics: once the task is stopped, the next
     * event of the reply ends the call, and a call that has not sent anything is ended at once
     * ({@link #interrupt}). Not the task's own hook: that one is the stall watchdog's measure of
     * the task, and a summary is not the task's work.
     */
    private LlmProgress hook() {
        return new LlmProgress() {
            @Override
            public void onProgress() {
                if (task.isCancelled()) throw new TaskCancellationService.TaskCancelledException(task.taskId());
            }

            @Override
            public void calling(Runnable cancel) {
                summaryCall = cancel;
                if (cancel != null && task.isCancelled()) cancel.run();
            }
        };
    }

    /** What the local model is asked: the owner's message, for its language, and the result, whole. */
    private List<LlmMessage> prompt(Artifact result) {
        return List.of(
                LlmMessage.system("You summarise one result of a tool for the owner of this "
                        + "machine, who reads it in the chat while the task runs. Say in plain "
                        + "words what the result shows; if it is an error, what went wrong. Write "
                        + "in the language of the owner's message, and say only what the result "
                        + "says."),
                LlmMessage.user("The owner's message:\n" + task.originalMessage()
                        + "\n\nResult " + result.n() + ", from the tool " + result.tool()
                        + (result.succeeded() ? "" : ", which failed") + ":\n" + result.output()));
    }

    /**
     * Save the row in the task's chat, then show it: in the page, with its chat and task, and in
     * Telegram for a task that came from there. A row that cannot be saved is still shown.
     */
    private void post(String content, String ownerText) {
        try {
            conversations.saveMessage(task.userId(), sessionId, "progress", content, List.of(),
                    task.taskId(), ownerText);
        } catch (RuntimeException e) {
            log.warn("Task {}: a progress message could not be saved: {}", task.taskId(), e.getMessage());
        }
        if (channel == Channel.OPS) return;
        var data = new HashMap<String, Object>();
        data.put("sessionId", sessionId);
        if (ownerText != null) data.put("ownerText", ownerText);
        if (channel == Channel.TELEGRAM) data.put("telegram", true);
        emitter.emitForTask(task.userId(), task.taskId(), StatusMessage.Type.PROGRESS_MESSAGE, content, data);
    }
}
