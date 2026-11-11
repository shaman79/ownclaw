package com.ownclaw.agent;

import com.ownclaw.llm.LlmProgress;
import com.ownclaw.llm.LlmProvider;
import com.ownclaw.llm.LlmResponse;
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
 * It ends nothing. No reply is ended for its length or for what it holds -- its reasoning, its
 * answer, a tool call's arguments: a runaway is seen as it grows, its characters so far in the
 * owner's chat ({@link #shown}), and Stop ends it; otherwise the model's own limits do -- its
 * maximum output, its context window ({@code OutputTruncated#replyFilledTheWindow}). It used to
 * end a reply whose text repeated: four copies of any 300 characters, and for a tool call ten
 * checks in a row that found some -- about 18,000 characters of records. Each ended data: a table,
 * a grid of zeros, a dashboard's cards, a script the model drafts in its reasoning before calling.
 * No length and no count of repeats tells data from a loop, and the owner ruled such caps out
 * (2026-11-11).
 * <p>
 * What the counts and the texts describe is the reply under way: a request sent again -- after
 * an overload, or to the model a refusal names -- is answered by a reply of its own, and the
 * state starts again with it ({@link #calling}). The time is the call's, from its beginning.
 */
public final class LiveCall implements LlmProgress, AutoCloseable {

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

    /**
     * What the call is doing, and for how long: "10m 3s, reasoning, 42,000 characters so far", or
     * "10m 3s, loading the model and reading the prompt, nothing written yet".
     */
    public synchronized String doing() {
        int chars = reasoning.length() + answer.length();
        return elapsed(System.currentTimeMillis() - startedAt) + ", " + phase() + ", "
                + (chars == 0 ? "nothing written yet" : String.format(Locale.ROOT, "%,d characters so far", chars));
    }

    /** "23m 5s and 41,200 characters": how long the call has run and how much it has written. */
    public synchronized String ranFor() {
        return elapsed(System.currentTimeMillis() - startedAt) + " and "
                + String.format(Locale.ROOT, "%,d characters", reasoning.length() + answer.length());
    }

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
                lineStart = 0;
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

    /** Kept, to be shown: nothing a reply holds ends it. */
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
        out.put("reasoningTail", tail(Redactor.scrubVault(wholeLines(reasoning), task.secretValues()).text()));
        out.put("answerTail", tail(Redactor.scrubVault(wholeLines(answer), task.secretValues()).text()));
        return out;
    }

    /**
     * What a text holds of whole lines while the call runs, and all of it once it has ended: a
     * value the model is still writing matches no form of it, and its beginning was shown.
     */
    private String wholeLines(StringBuilder text) {
        if (closed) return text.toString();
        int end = text.lastIndexOf("\n");
        return end < 0 ? "" : text.substring(0, end + 1);
    }

    /**
     * The end of a text already scrubbed. Cut first, a value that straddled the cut was no longer
     * whole, and its end was shown.
     */
    private static String tail(String text) {
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
