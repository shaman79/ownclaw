package com.ownclaw.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.ownclaw.conversation.ConversationService;
import com.ownclaw.core.TaskCancellationService;
import com.ownclaw.privacy.Redactor;
import com.ownclaw.llm.LlmException;
import com.ownclaw.llm.LlmMessage;
import com.ownclaw.llm.LlmProvider;
import com.ownclaw.llm.LlmResponse;
import com.ownclaw.llm.OutputTruncated;
import com.ownclaw.observability.ChatStatusEmitter;
import com.ownclaw.observability.ChatStatusEmitter.StatusMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * The chat an attended task reports its progress in while it works: a message before each step
 * of the loop, one before each tool call of a delegation, a note after a step whose result the
 * privacy filter changes before the cloud model reads it, the local model's summary of each
 * private result the cloud's own calls produced, and one when the task reads what the owner sent
 * it while it worked. Each is a row of role {@code progress} in the
 * chat the task's own message was saved in, never the answer, which is delivered as before.
 * <p>
 * Every row but the filter's note opens with its {@link Header}: who acts -- the cloud or the
 * local model -- and where the task stands. The row's content is that header as one line, with
 * what the cloud could be shown under it: the cloud's own words beside its call, or a note that a
 * summary exists or why there is none. What only the owner may read -- a summary of a private
 * result, and the local model's words and calls, written after it may have read private data --
 * is the row's private content, which only the owner's own chat reads back.
 * <p>
 * A progress row is the owner's view of a task at work. No prompt reads one:
 * {@link ConversationService#contextOf} reads user and assistant rows only, and the full-text
 * index holds no other. A row is saved only while its chat exists: one posted after the owner
 * deleted the chat is neither saved nor shown.
 * <p>
 * The summaries are the local model's background work ({@link LocalLane}): they never hold up a
 * task, and they end with it. Nothing of a task is posted once it has ended ({@link #close}), so
 * no row lands below its answer or among the next task's.
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

    /** Who acts in a row: the page draws it as a chip, plain text shows its emoji. */
    enum Actor {
        CLOUD("cloud", "☁️"),   // ☁️
        LOCAL("local", "🏠");    // 🏠

        final String id;
        final String emoji;

        Actor(String id, String emoji) {
            this.id = id;
            this.emoji = emoji;
        }
    }

    /**
     * What a row is about: who acts, the step of the loop, the local model's turn or the result,
     * the tool -- with the skill's name for a skill_create -- how long the task has run and what
     * its cloud calls have cost so far. Stored and forwarded as one line ({@link #line}), which
     * the page shows too, and as data ({@link #data}) in the row's metadata and its live frame,
     * whose actor styles the chip the page draws in place of the line's emoji.
     *
     * @param kind  "step", "turn" or "result"
     * @param skill the skill a skill_create writes, or null
     */
    record Header(Actor actor, String kind, int n, String tool, String skill, long elapsedMs,
                  double costUsd) {

        /** "☁️ Step 7 · openwrt_run · 4m 12s · $1.23" */
        String line() {
            return actor.emoji + " " + Character.toUpperCase(kind.charAt(0)) + kind.substring(1) + " " + n
                    + " · " + tool + (skill == null ? "" : " " + skill)
                    + " · " + TaskRecord.duration(elapsedMs)
                    + " · " + String.format(Locale.ROOT, "$%.2f", costUsd);
        }

        /** {"actor": "cloud", "step": 7, "tool": "openwrt_run", "elapsedMs": ..., "costUsd": ...} */
        Map<String, Object> data() {
            var data = new LinkedHashMap<String, Object>();
            data.put("actor", actor.id);
            data.put(kind, n);
            data.put("tool", tool);
            if (skill != null) data.put("skill", skill);
            data.put("elapsedMs", elapsedMs);
            data.put("costUsd", costUsd);
            return data;
        }
    }

    /** The chat of a task that has none: nothing is posted, and nothing is summarised. */
    static final TaskChat NONE = new TaskChat(null, null, null, null, null, null, null, null);

    private final AgentContext task;
    private final String sessionId;
    private final Channel channel;
    private final ConversationService conversations;
    private final ChatStatusEmitter emitter;
    private final LlmProvider local;
    private final LocalLane lane;
    private final BiConsumer<LlmProvider, LlmResponse> billed;

    /** Whether the task has ended ({@link #close}); set under this, as rows are posted. */
    private volatile boolean ended;

    /**
     * @param task     the task, whose stop or end ends its summaries
     * @param local    the local model, which writes the summaries
     * @param lane     the local model as every task shares it, on which the summaries are
     *                 background work
     * @param billed   the task's account, handed each summary's reply with the provider that
     *                 served it
     */
    TaskChat(AgentContext task, String sessionId, Channel channel, ConversationService conversations,
             ChatStatusEmitter emitter, LlmProvider local, LocalLane lane,
             BiConsumer<LlmProvider, LlmResponse> billed) {
        this.task = task;
        this.sessionId = sessionId;
        this.channel = channel;
        this.conversations = conversations;
        this.emitter = emitter;
        this.local = local;
        this.lane = lane;
        this.billed = billed;
    }

    /** Whether the owner follows this task in a chat: whether anything is posted at all. */
    boolean watched() {
        return sessionId != null;
    }

    /**
     * Before a step of the loop runs: its header, and what the model wrote beside the call -- its
     * reasoning, on either protocol -- when it wrote anything. Nothing else: no parameters, no
     * results. The cloud's words are the row's content; the local model's, written when the cloud
     * is not available and after it may have read private data, are its private content, as a
     * delegation's turns are, and its content is the header.
     *
     * @param narration the model's words, or null for a step no model chose
     * @param local     whether the local model does the step: it chose it, the cloud not being
     *                  available, or -- a step no model chose -- writes its code
     */
    void step(int step, AgentAction action, String narration, boolean local) {
        if (sessionId == null) return;
        String words = narration == null ? "" : TaskRecord.inWords(
                Redactor.scrubVault(narration, task.secretValues()).text()).strip();
        Header header = header(local ? Actor.LOCAL : Actor.CLOUD, "step", step, action.tool(),
                action.isSkillCreate() ? AgentLoop.skillOf(action) : null);
        if (local) post(header, "", words.isEmpty() ? null : words);
        else post(header, words, null);
    }

    /**
     * Before a delegation's tool call runs: the local model's turn and the tool, and for the
     * owner alone what the model wrote beside the call and the call itself ({@link #readable}).
     * The local model may have read private data, so both are the row's private content; its
     * content is the header.
     *
     * @param words the model's words beside the call, or null
     * @param args  the arguments as the model wrote them: a reference is its handle, never the
     *              result it stands for
     */
    void localTurn(int turn, String tool, String words, Map<String, Object> args) {
        if (sessionId == null) return;
        var owner = new ArrayList<String>();
        if (words != null && !words.isBlank()) owner.add(words.strip());
        String call = readable(args);
        if (!call.isEmpty()) owner.add(call);
        post(header(Actor.LOCAL, "turn", turn, tool, null), "", owner.isEmpty() ? null
                : Redactor.scrubVault(String.join("\n\n", owner), task.secretValues()).text());
    }

    /**
     * After a step whose result the gateway's filter changes before the cloud model reads it:
     * how many secrets it removes and identifiers it replaces -- counts, nothing of what they are.
     */
    void filtered(Redactor.Tally taken) {
        if (sessionId == null || !taken.any()) return;
        note("🔒 For the cloud model: " + described(taken) + ".");
    }

    /** "2 secrets removed, 9 identifiers replaced" -- each part only when it is not zero. */
    static String described(Redactor.Tally t) {
        var parts = new java.util.ArrayList<String>();
        if (t.secretsRemoved() > 0) {
            parts.add(t.secretsRemoved() + (t.secretsRemoved() == 1 ? " secret" : " secrets") + " removed");
        }
        if (t.identifiersReplaced() > 0) {
            parts.add(t.identifiersReplaced() + (t.identifiersReplaced() == 1 ? " identifier" : " identifiers")
                    + " replaced");
        }
        return String.join(", ", parts);
    }

    /**
     * Before a step of the loop: the task has read the messages the owner sent it while it worked
     * ({@code TaskQueue#steer}), which are part of it from this step on. The row quotes none of
     * them -- each is in the chat already, as his own row -- and its live frame names their rows,
     * so the page can mark them read.
     *
     * @param local whether the local model does the step, as {@link #step} says
     */
    void read(int step, List<String> messageIds, boolean local) {
        if (sessionId == null) return;
        int n = messageIds.size();
        post(header(local ? Actor.LOCAL : Actor.CLOUD, "step", step, "your message", null),
                (n == 1 ? "Read your message" : "Read your " + n + " messages")
                        + ": part of the task from this step on.", null, messageIds);
    }

    /**
     * A private result of the cloud's own call: the local model summarises it for the owner, as
     * background work in the order asked ({@link LocalLane}), and the summary is posted when it
     * is written -- or, when it cannot be, why not. The cloud's words cannot describe a private
     * result: it is shown a description of it. A delegation's results get none: the local model
     * made them, and its turns say what it was doing.
     */
    void privateResult(Artifact result) {
        if (sessionId == null) return;
        lane.background(() -> summarise(result));
    }

    /**
     * The task has ended, and its answer is about to be delivered: nothing of it is posted from
     * now on. The summaries not yet begun are not written, and the one under way is ended
     * ({@link LocalLane#interruptStopped}).
     */
    void close() {
        if (sessionId == null) return;
        synchronized (this) {
            ended = true;
        }
        lane.interruptStopped();
    }

    /** Whether the summaries are over: the task was stopped, or has ended. */
    private boolean stopped() {
        return ended || task.isCancelled();
    }

    private void summarise(Artifact result) {
        if (stopped()) return;
        String why = null;
        String summary = null;
        try {
            if (local == null || !local.isAvailable()) {
                why = "the local model is not answering";
            } else {
                LlmResponse reply = lane.call(local, prompt(result), task.taskId(), this::stopped);
                billed.accept(local, reply);
                summary = reply.content() == null ? "" : reply.content().strip();
                if (summary.isEmpty()) why = "the local model wrote nothing";
            }
        } catch (TaskCancellationService.TaskCancelledException over) {
            // Ended with its task, stopped or done: there is nothing to say after it.
            return;
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
        Header header = header(Actor.LOCAL, "result", result.n(), result.tool(), null);
        if (why != null) {
            post(header, "Not summarised: " + why + ".", null);
            return;
        }
        post(header, "A private summary, shown only to you.",
                Redactor.scrubVault(summary, task.secretValues()).text());
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

    /** A header for a row posted now: the task's time and cost as they stand. */
    private Header header(Actor actor, String kind, int n, String tool, String skill) {
        return new Header(actor, kind, n, tool, skill, task.elapsedMs(), task.cloudCostUsd());
    }

    /**
     * A call's arguments as the owner reads them: a command as a shell block, then each other
     * argument by name -- a value of one line as code on its line, a longer one or a structure as
     * a block of its own. Every value whole.
     */
    static String readable(Map<String, Object> args) {
        if (args == null || args.isEmpty()) return "";
        var parts = new ArrayList<String>();
        var oneLiners = new ArrayList<String>();
        var blocks = new ArrayList<String>();
        boolean command = args.get("command") instanceof String;
        if (command) parts.add(block(String.valueOf(args.get("command")), "sh"));
        for (var e : args.entrySet()) {
            if (command && e.getKey().equals("command")) continue;
            Object value = e.getValue();
            boolean structure = value instanceof Map || value instanceof List;
            String text = structure ? json(value) : String.valueOf(value);
            if (!structure && text.indexOf('\n') < 0) {
                oneLiners.add("- " + e.getKey() + ": " + span(text));
            } else {
                blocks.add(e.getKey() + ":\n\n" + block(text, structure ? "json" : ""));
            }
        }
        if (!oneLiners.isEmpty()) parts.add(String.join("\n", oneLiners));
        parts.addAll(blocks);
        return String.join("\n\n", parts);
    }

    private static String json(Object value) {
        try {
            return TextCalls.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return String.valueOf(value);
        }
    }

    /** Text as a code span: fenced by more backticks than any run inside it, so it stays literal. */
    private static String span(String text) {
        if (text.isEmpty()) return "\"\"";
        String ticks = "`".repeat(longestRun(text) + 1);
        String pad = text.startsWith("`") || text.endsWith("`") ? " " : "";
        return ticks + pad + text + pad + ticks;
    }

    /** Text as a fenced block, fenced by more backticks than any run inside it. */
    private static String block(String text, String language) {
        String fence = "`".repeat(Math.max(3, longestRun(text) + 1));
        return fence + language + "\n" + text + "\n" + fence;
    }

    private static int longestRun(String text) {
        int longest = 0, run = 0;
        for (int i = 0; i < text.length(); i++) {
            run = text.charAt(i) == '`' ? run + 1 : 0;
            longest = Math.max(longest, run);
        }
        return longest;
    }

    /**
     * Save the row in the task's chat, then show it: in the page, with its chat, task and header,
     * and in Telegram for a task that came from there. Its content is the header's line with
     * {@code body} under it; {@code ownerBody}, when there is one, goes under the header in the
     * row's private content instead. A row for a chat the owner has deleted is neither saved nor
     * shown; one that cannot be saved for another reason is still shown; and none is either once
     * the task has ended.
     */
    /**
     * A line about the task that is no step of it -- the filter's counts -- posted without a
     * header, so it is not read as one more step. The page draws a row without one as its text.
     */
    private void note(String content) {
        synchronized (this) {
            if (ended) return;
            try {
                if (!conversations.saveProgress(task.userId(), sessionId, content, task.taskId(), null, null)) {
                    return;
                }
            } catch (RuntimeException e) {
                log.warn("Task {}: a progress message could not be saved: {}", task.taskId(), e.getMessage());
            }
            if (channel == Channel.OPS) return;
            var data = new HashMap<String, Object>();
            data.put("sessionId", sessionId);
            if (channel == Channel.TELEGRAM) data.put("telegram", true);
            emitter.emitForTask(task.userId(), task.taskId(), StatusMessage.Type.PROGRESS_MESSAGE, content, data);
        }
    }

    private void post(Header header, String body, String ownerBody) {
        post(header, body, ownerBody, null);
    }

    /** @param read the owner's rows the task has just read ({@link #read}), named in the live frame; or null */
    private void post(Header header, String body, String ownerBody, List<String> read) {
        String content = header.line() + (body.isEmpty() ? "" : "\n\n" + body);
        String ownerText = ownerBody == null ? null : header.line() + "\n\n" + ownerBody;
        synchronized (this) {
            if (ended) return;
            try {
                if (!conversations.saveProgress(task.userId(), sessionId, content, task.taskId(), ownerText,
                        header.data())) {
                    return;
                }
            } catch (RuntimeException e) {
                log.warn("Task {}: a progress message could not be saved: {}", task.taskId(), e.getMessage());
            }
            if (channel == Channel.OPS) return;
            var data = new HashMap<String, Object>();
            data.put("sessionId", sessionId);
            data.put("progress", header.data());
            if (ownerText != null) data.put("ownerText", ownerText);
            if (read != null) data.put("read", read);
            if (channel == Channel.TELEGRAM) data.put("telegram", true);
            emitter.emitForTask(task.userId(), task.taskId(), StatusMessage.Type.PROGRESS_MESSAGE, content, data);
        }
    }
}
