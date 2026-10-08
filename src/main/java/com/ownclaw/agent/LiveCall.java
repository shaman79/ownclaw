package com.ownclaw.agent;

import com.ownclaw.llm.LlmProgress;
import com.ownclaw.llm.LlmProvider;
import com.ownclaw.llm.LlmResponse;
import com.ownclaw.llm.RepeatedOutput;
import com.ownclaw.privacy.Redactor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A model call of a task while it is under way, as its reply streams in: which model and what
 * for, when it began, whether anything of the reply has arrived, how many characters of reasoning
 * and of answer, the tool calls the reply has begun, and the latest complete line of its
 * reasoning. It is what the owner is shown while he waits -- the loop sends it to his chat when
 * the call begins, every {@link AgentLoop#LIVE_EVERY_MS} while it runs and once when it ends
 * ({@link AgentContext#setCallWatch}), and a page that connects meanwhile is sent it at once --
 * and what the ops API shows of the task, with the end of each text ({@link #forOps}).
 * <p>
 * It is the call's progress hook. The task's own ({@link AgentContext#progress}) goes on doing
 * what it did -- each event is progress for the stall watchdog, Stop ends the call, attempts that
 * ended without a reply are billed -- and this keeps the live state beside it, from what each
 * event of the reply carried ({@link LlmProgress#received}). Made by {@link AgentContext#call},
 * and closed when the call has returned or failed.
 * <p>
 * And it ends a reply that has become a verbatim loop ({@link #looping}).
 * <p>
 * What the counts and the texts describe is the reply under way: a request sent again -- after
 * an overload, or to the model a refusal names -- is answered by a reply of its own, and the
 * state starts again with it ({@link #calling}). The time is the call's, from its beginning.
 */
public final class LiveCall implements LlmProgress, AutoCloseable {

    /**
     * How often a reply is checked for a loop: each time its reasoning, or its answer, has grown
     * by another 2,000 characters since that text was last checked. A check reads the whole text
     * so far, so it is not made at every event; 2,000 characters are about a minute of the local
     * model's writing and a few seconds of a cloud model's, which is how much longer a loop runs
     * than it would have to.
     */
    static final int LOOP_CHECK_EVERY = 2_000;

    /**
     * How much of the end of the text a check looks for in what came before it: 300 characters,
     * several sentences -- more than a line of a config file, a table row or a phrase a reply may
     * well repeat, so that only a stretch the model has written out word for word again matches.
     */
    static final int LOOP_WINDOW = 300;

    /**
     * How many times those last characters must appear in the reply's text so far, none
     * overlapping another, for the reply to be ended as a loop: 4. A reply that quotes a file whose
     * blocks repeat once or twice holds such a block two or three times, and goes on; a model that
     * has written the same stretch four times is in a loop, and would write it until its context
     * window was full -- hours, on the local model.
     * <p>
     * Nothing else bounds a reply: this application sets no limit of its own on its length, and
     * neither its length nor the time it takes ends a call that is not looping. Only the model's
     * own limits -- its maximum output, its context window -- the provider's limit on silence
     * between two events, and Stop do.
     */
    static final int LOOP_REPEATS = 4;

    /**
     * How much of the end of each text the ops API shows of a call under way ({@link #forOps}):
     * enough to see what the model is writing now. The whole text is the reply's, which the call
     * returns when it ends.
     */
    static final int OPS_TAIL = 2_000;

    /**
     * Told when a model call of a task begins; what it returns is run once the call has ended
     * ({@link #close}).
     */
    @FunctionalInterface
    public interface Watch {
        Runnable started(LiveCall call);

        /** A task nobody watches: a context made without the loop. */
        Watch NONE = call -> () -> { };
    }

    /**
     * One look at the call: the line the owner is shown, and the state it is drawn from -- the
     * same look, so the two cannot disagree.
     *
     * @param ended whether the call has ended: the line then says how long it ran
     */
    public record Shown(String text, Map<String, Object> data, boolean ended) {}

    private final AgentContext task;
    /** The task's own hook, which does what every model call of the task needs. */
    private final LlmProgress hook;
    private final String provider;
    private final String model;
    private final boolean local;
    private final String purpose;
    private final long startedAt = System.currentTimeMillis();
    /** What the watch said to run when the call ends; see {@link #close}. */
    private volatile Runnable whenEnded = () -> { };

    // The reply under way, guarded by this.
    private final StringBuilder reasoning = new StringBuilder();
    private final StringBuilder answer = new StringBuilder();
    /** How long each text was when it was last checked for a loop. */
    private int reasoningChecked, answerChecked;
    /** Where the reasoning's line that has not ended yet begins. */
    private int lineStart;
    /** The latest complete line of the reasoning, or null before one has ended. */
    private String line;
    private final List<String> calls = new ArrayList<>();
    /** The part the latest event carried, or null while nothing of the reply has arrived. */
    private Part last;
    private boolean closed;

    /**
     * @param hook    the task's own hook ({@link AgentContext#progress})
     * @param local   whether the local model answers the call
     * @param purpose what the call is for, as the owner reads it: "step 6", "step 6, delegation
     *                turn 1"
     */
    LiveCall(AgentContext task, LlmProgress hook, LlmProvider provider, boolean local, String purpose) {
        this.task = task;
        this.hook = hook;
        this.provider = provider.name();
        this.model = provider.model();
        this.local = local;
        this.purpose = purpose;
    }

    /** What to run once the call has ended: the watch's own end ({@link Watch#started}). */
    void whenEnded(Runnable end) {
        this.whenEnded = end;
    }

    public String purpose() { return purpose; }

    public boolean local() { return local; }

    @Override
    public void onProgress() {
        hook.onProgress();
    }

    /**
     * The task's hook is handed the cancel, as before. A request sent, or a wait to send one again
     * begun, means what arrives from here is another reply's: the state starts again.
     */
    @Override
    public void calling(Runnable cancel) {
        if (cancel != null) {
            synchronized (this) {
                reasoning.setLength(0);
                answer.setLength(0);
                reasoningChecked = answerChecked = lineStart = 0;
                line = null;
                calls.clear();
                last = null;
            }
        }
        hook.calling(cancel);
    }

    @Override
    public void billed(LlmResponse.Usage usage) {
        hook.billed(usage);
    }

    /**
     * Kept, then checked for a loop: a reply whose reasoning or answer has become one is ended
     * here, with {@link RepeatedOutput}. A tool call's arguments are the model's answer too, and
     * are checked with it.
     */
    @Override
    public synchronized void received(Part part, String text) {
        last = part;
        switch (part) {
            case REASONING -> {
                int from = reasoning.length();
                reasoning.append(text);
                endedLines(from);
            }
            case ANSWER, ARGUMENTS -> answer.append(text);
            case CALL -> calls.add(text);
        }
        if (reasoning.length() - reasoningChecked >= LOOP_CHECK_EVERY) {
            reasoningChecked = reasoning.length();
            looping(reasoning, "of reasoning");
        }
        if (answer.length() - answerChecked >= LOOP_CHECK_EVERY) {
            answerChecked = answer.length();
            looping(answer, "of its answer");
        }
    }

    /** The lines of the reasoning that a line break appended after {@code from} has ended. */
    private void endedLines(int from) {
        for (int at = reasoning.indexOf("\n", from); at >= 0; at = reasoning.indexOf("\n", at + 1)) {
            String ended = reasoning.substring(lineStart, at).strip();
            if (!ended.isEmpty()) line = ended;
            lineStart = at + 1;
        }
    }

    /**
     * Ends the call when the last {@link #LOOP_WINDOW} characters of {@code text} appear in it
     * {@link #LOOP_REPEATS} times or more, none overlapping another -- they themselves are one.
     * Counting stops at that many, so a check reads the text at most once.
     */
    private void looping(StringBuilder text, String which) {
        if (text.length() < LOOP_WINDOW) return;
        String end = text.substring(text.length() - LOOP_WINDOW);
        int times = 0;
        for (int at = text.indexOf(end); at >= 0 && times < LOOP_REPEATS; at = text.indexOf(end, at + LOOP_WINDOW)) {
            times++;
        }
        if (times < LOOP_REPEATS) return;
        throw new RepeatedOutput(provider, String.format(Locale.ROOT,
                "the %s model repeated the same %,d characters %d times, after %s and %,d characters %s",
                local ? "local" : "cloud", LOOP_WINDOW, times, elapsed(System.currentTimeMillis() - startedAt),
                text.length(), which));
    }

    /**
     * The call has ended, returned or failed: it is no longer the task's call under way, and the
     * watch's end is run, once.
     */
    @Override
    public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
        }
        task.callEnded(this);
        whenEnded.run();
    }

    /**
     * The call as the owner is shown it: "🏠 Local model · step 6, delegation turn 1 · reasoning ·
     * 23m 5s · 41,200 characters so far · “the latest line”", and the state that says so -- the
     * line whole, with the vault's values scrubbed from it, as everything shown to him is. Once
     * the call has ended: how long it ran and what it wrote, and no line.
     */
    public synchronized Shown shown() {
        long ms = System.currentTimeMillis() - startedAt;
        String phase = closed ? "ended after " + elapsed(ms) : phase();
        String said = closed || line == null ? null : Redactor.scrubVault(line, task.secretValues()).text();
        String summary = (local ? TaskChat.Actor.LOCAL : TaskChat.Actor.CLOUD).emoji + " "
                + (local ? "Local" : "Cloud") + " model · " + purpose + " · " + phase
                + (closed ? "" : " · " + elapsed(ms)) + written(!closed);
        var data = new LinkedHashMap<String, Object>();
        data.put("model", local ? "local" : "cloud");
        data.put("provider", provider);
        if (model != null) data.put("modelName", model);
        data.put("purpose", purpose);
        data.put("phase", phase);
        data.put("startedAt", startedAt);
        data.put("elapsedMs", ms);
        data.put("arrived", last != null);
        data.put("reasoningChars", reasoning.length());
        data.put("answerChars", answer.length());
        data.put("toolCalls", List.copyOf(calls));
        if (said != null) data.put("line", said);
        data.put("summary", summary);
        data.put("ended", closed);
        return new Shown(said == null ? summary : summary + " · “" + said + "”", data, closed);
    }

    /**
     * {@link #shown}'s state, with the last {@link #OPS_TAIL} characters of the reasoning and of
     * the answer, the vault's values scrubbed: what an operator reads of a call under way.
     */
    public synchronized Map<String, Object> forOps() {
        var out = new LinkedHashMap<>(shown().data());
        out.put("reasoningTail", Redactor.scrubVault(tail(reasoning), task.secretValues()).text());
        out.put("answerTail", Redactor.scrubVault(tail(answer), task.secretValues()).text());
        return out;
    }

    private static String tail(StringBuilder text) {
        return text.substring(Math.max(0, text.length() - OPS_TAIL));
    }

    /** What the reply is doing now, from the part its latest event carried. */
    private String phase() {
        if (last == null) return local ? "loading the model and reading the prompt" : "waiting for its first words";
        return switch (last) {
            case REASONING -> "reasoning";
            case ANSWER -> "writing the answer";
            case CALL, ARGUMENTS -> "calling " + (calls.isEmpty() ? "a tool" : calls.get(calls.size() - 1));
        };
    }

    /** " · 41,200 characters so far", or of both texts when both have some; nothing before any. */
    private String written(boolean soFar) {
        int r = reasoning.length(), a = answer.length();
        if (r == 0 && a == 0) return "";
        String counts = r > 0 && a > 0
                ? String.format(Locale.ROOT, "%,d characters of reasoning, %,d of answer", r, a)
                : String.format(Locale.ROOT, "%,d characters", r + a);
        return " · " + counts + (soFar ? " so far" : "");
    }

    /** "12s" under a minute, then as a task's times are written: "23m 5s". */
    static String elapsed(long ms) {
        return ms < 60_000 ? ms / 1000 + "s" : TaskRecord.duration(ms);
    }
}
