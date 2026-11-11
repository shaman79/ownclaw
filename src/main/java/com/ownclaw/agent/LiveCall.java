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
     * How many checks in a row must find a tool call's text repeating ({@link #repeatingSince})
     * before the reply is ended: 10, where reasoning and an answer take one. A call's text is data,
     * and data repeats -- a grid of zeros in a script, a table's rows, a dashboard's cards that differ
     * in one line -- where prose does not. But data ends, and a check whose new text is past it finds
     * nothing, while a loop goes on and is found at every check. A check also finds data that ended
     * within its new text, so a stretch of repeated data is found by the checks it spans and one
     * more -- the first some 1,500 characters into it: ten in a row let some 18,000 characters of
     * records repeat -- records whose differences lie 330 or more identical characters apart, such as
     * 44 records of 400 characters or 260 identical 68-character rows -- before the call is ended,
     * and a loop is ended some 20,000 characters after it began: minutes of the local model's
     * writing, where a loop in a call ran for the hour the read timeout allowed, and, streamed, would
     * run until the context window was full. Ended wrongly, a call's work goes back to the model that
     * delegated it; that is the cost of data longer than this. The dashboard configuration retyped on
     * 2026-11-10 -- 98 cards, 37,600 characters, more written out -- runs whole: its cards differ
     * every few lines.
     */
    static final int LOOP_CALL_CHECKS = 10;

    /**
     * How far apart the stretches a check of a call's text looks for end, through the text written
     * since the check before: every 25 characters. A loop that varies a little each round -- a card
     * whose entity line changes -- repeats only the stretches that lie wholly in what stays the same,
     * and only looking at every place in the new text, not at its last characters alone, finds one
     * wherever in the round the check falls. 25 apart, it finds a loop whose part that stays the same
     * is 25 characters longer than {@link #LOOP_WINDOW}.
     */
    static final int LOOP_STRIDE = 25;

    /**
     * How far back from a stretch its earlier copies are looked for, at a check of a call's text:
     * 32,000 characters, four rounds of a loop up to some 8,000 characters long. A longer round is
     * found by the check every text gets, of its last characters through all of it.
     */
    static final int LOOP_REACH = 32_000;

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
    /** The checks in a row that found a tool call's text repeating ({@link #LOOP_CALL_CHECKS}). */
    private int callRepeats;
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
                reasoningChecked = answerChecked = lineStart = callRepeats = 0;
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
     * are checked with it -- ended only when the checks find them repeating {@link #LOOP_CALL_CHECKS}
     * times in a row, as data that repeats is not.
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
            if (repeating(reasoning)) throw looped(reasoning, "of reasoning");
        }
        if (answer.length() - answerChecked >= LOOP_CHECK_EVERY) {
            int since = answerChecked;
            answerChecked = answer.length();
            boolean inACall = part == Part.ARGUMENTS || part == Part.CALL;
            if (!inACall) {
                callRepeats = 0;
                if (repeating(answer)) throw looped(answer, "of its answer");
            } else if (repeating(answer) || repeatingSince(answer, since)) {
                if (++callRepeats >= LOOP_CALL_CHECKS) {
                    throw looped(answer, "of its answer, in a tool call it went on repeating");
                }
            } else {
                callRepeats = 0;
            }
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
     * Whether the last {@link #LOOP_WINDOW} characters of {@code text} appear in it
     * {@link #LOOP_REPEATS} times or more, none overlapping another -- they themselves are one. One
     * pass of a rolling hash over the text ({@link #hash}), each hit confirmed character by character
     * and the next looked for past it, so a check costs the same whatever the text holds. Looked for
     * with indexOf, the stretch was matched afresh at every place, and through nearly all of itself
     * wherever the text is mostly one character -- runs of spaces: half a second a check, measured.
     */
    private static boolean repeating(StringBuilder text) {
        int n = text.length();
        if (n < LOOP_WINDOW) return false;
        int last = n - LOOP_WINDOW;
        long wanted = hash(text, last);
        int times = 0;
        int at = 0;
        long h = hash(text, 0);
        while (true) {
            if (h == wanted && same(text, at, last)) {
                if (++times >= LOOP_REPEATS) return true;
                at += LOOP_WINDOW;   // the next copy may not overlap this one
                if (at > last) return false;
                h = hash(text, at);
                continue;
            }
            if (at >= last) return false;
            h = rolled(h, text, at);
            at++;
        }
    }

    /**
     * Whether a stretch of {@link #LOOP_WINDOW} characters ending anywhere in the text written since
     * {@code since} -- every {@link #LOOP_STRIDE} characters of it -- appears {@link #LOOP_REPEATS}
     * times or more, none overlapping another, within the {@link #LOOP_REACH} characters before it.
     * Whether a check finds a loop does not depend on where in the loop's round it falls: looking at
     * the text's last characters alone, a loop whose rounds differ in one line was found only by the
     * checks that fell in what stays the same, and enough such checks in a row came, measured, after
     * 300,000 characters or never. Only the last {@code 2 * LOOP_CHECK_EVERY} characters are looked
     * through when more came at once -- a native call's arguments arrive whole.
     * <p>
     * A check costs the same whatever the text's length and whatever it holds: every window of the
     * text it looks through is hashed once ({@link #hash}), the windows whose hash is a stretch's are
     * kept by where they begin, and each stretch's earlier copies are found among those, each
     * confirmed character by character.
     */
    static boolean repeatingSince(StringBuilder text, int since) {
        int n = text.length();
        int from = Math.max(Math.max(since, n - 2 * LOOP_CHECK_EVERY), LOOP_WINDOW);
        if (from > n) return false;
        int count = (n - from) / LOOP_STRIDE + 1;
        int[] stretch = new int[count];        // where each stretch begins
        long[] stretchHash = new long[count];
        for (int k = 0; k < count; k++) {
            stretch[k] = n - k * LOOP_STRIDE - LOOP_WINDOW;
            stretchHash[k] = hash(text, stretch[k]);
        }
        long[] wanted = java.util.Arrays.stream(stretchHash).sorted().distinct().toArray();
        int[][] where = new int[wanted.length][];
        int[] found = new int[wanted.length];
        int start = Math.max(0, from - LOOP_WINDOW - LOOP_REACH);
        long h = hash(text, start);
        for (int at = start; ; at++) {
            int w = java.util.Arrays.binarySearch(wanted, h);
            if (w >= 0) {
                if (where[w] == null) where[w] = new int[16];
                else if (found[w] == where[w].length) where[w] = java.util.Arrays.copyOf(where[w], found[w] * 2);
                where[w][found[w]++] = at;
            }
            if (at >= n - LOOP_WINDOW) break;
            h = rolled(h, text, at);
        }
        for (int k = 0; k < count; k++) {
            int w = java.util.Arrays.binarySearch(wanted, stretchHash[k]);
            int[] positions = where[w];
            int reach = stretch[k] - LOOP_REACH;
            int times = 1;
            int misses = 0;
            int i = latest(positions, found[w], stretch[k] - LOOP_WINDOW);
            while (times < LOOP_REPEATS && i >= 0 && positions[i] >= reach) {
                if (same(text, positions[i], stretch[k])) {
                    times++;
                    i = latest(positions, i, positions[i] - LOOP_WINDOW);
                } else if (++misses > HASH_MISSES) {
                    break;
                } else {
                    i--;
                }
            }
            if (times >= LOOP_REPEATS) return true;
        }
        return false;
    }

    /** How many windows whose hash only matches a stretch's are passed over before it is let go. */
    private static final int HASH_MISSES = 8;

    /** The last index below {@code size} whose position is {@code atMost} or less, or -1. */
    private static int latest(int[] positions, int size, int atMost) {
        int lo = 0, hi = size - 1, best = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (positions[mid] <= atMost) {
                best = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return best;
    }

    /** The rolling hash's base: odd, so multiplying by it loses nothing modulo 2^64. */
    private static final long BASE = 0x9E3779B97F4A7C15L;

    /** {@link #BASE} to the power {@code LOOP_WINDOW - 1}: what a window's first character weighs. */
    private static final long FIRST_WEIGHT;

    static {
        long w = 1;
        for (int i = 1; i < LOOP_WINDOW; i++) w *= BASE;
        FIRST_WEIGHT = w;
    }

    /** The hash of the {@link #LOOP_WINDOW} characters from {@code at}. */
    private static long hash(CharSequence text, int at) {
        long h = 0;
        for (int i = at; i < at + LOOP_WINDOW; i++) h = h * BASE + text.charAt(i);
        return h;
    }

    /** The hash of the window one character on from the one at {@code at}, whose hash is {@code h}. */
    private static long rolled(long h, CharSequence text, int at) {
        return (h - text.charAt(at) * FIRST_WEIGHT) * BASE + text.charAt(at + LOOP_WINDOW);
    }

    /** Whether the windows at {@code a} and {@code b} hold the same text. */
    private static boolean same(CharSequence text, int a, int b) {
        if (a == b) return true;
        for (int i = 0; i < LOOP_WINDOW; i++) if (text.charAt(a + i) != text.charAt(b + i)) return false;
        return true;
    }

    /** The reply's end, a loop: what was seen, in figures, and nothing of the text itself. */
    private RepeatedOutput looped(StringBuilder text, String which) {
        return new RepeatedOutput(provider, String.format(Locale.ROOT,
                "the %s model repeated the same %,d characters %d times, after %s and %,d characters %s",
                local ? "local" : "cloud", LOOP_WINDOW, LOOP_REPEATS, elapsed(System.currentTimeMillis() - startedAt),
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
