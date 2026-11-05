package com.ownclaw.agent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fitting a task's conversation into the context window of the model it goes to, without
 * dropping anything.
 * <p>
 * A model rejects a conversation longer than its window: Ollama with an error (it is asked never
 * to drop messages itself), Anthropic with "prompt is too long", or a reply cut off where the
 * window filled. That used to end the task. On 2026-11-04 one result -- a recall of 613,878
 * characters -- took a chat's request to 938 KB; the local model it fell back to has a
 * 262,144-token window, and the task ended after two hours of work.
 * <p>
 * Now the loop folds results instead and asks again. A folded result is shown as its first
 * {@link #PREVIEW} characters and a note naming its size; the rest is not gone. The bytes stay
 * in the trajectory, and {@code read_result} returns any part of them, or every place a text
 * occurs in them ({@link #read}). The largest results are folded first, since they free the
 * most; older first among equals. Only the results of steps are folded -- not the task's message,
 * the chat so far, or what the model wrote.
 * <p>
 * Each round aims under a size worked out from the window the provider named, at
 * {@link #CHARS_PER_TOKEN} characters a token (fewer than most text has, so the estimate errs
 * towards folding), and never above {@link #SHRINK} of the request that did not fit, so every
 * round makes the request smaller. A request that still does not fit folds more on the next
 * round, until nothing is left to fold: then the task ends as it did before. A folded result
 * stays folded for the rest of the task, so the conversation is the same bytes from one step to
 * the next and the provider's cache keeps working.
 */
final class ContextFit {

    private ContextFit() {}

    /** How much of a folded result is shown: its first characters, enough to see what it is. */
    static final int PREVIEW = 2_000;

    /**
     * Characters a token, low: Czech and JSON tokenize at 2.5-4, English prose at about 4. Low
     * means the estimate of what fits is small, and a round folds more rather than too little.
     */
    static final double CHARS_PER_TOKEN = 2.0;

    /** Each round's request is at most this share of the one that did not fit. */
    static final double SHRINK = 0.75;

    /** And each round after the first aims this much lower again: the estimate was too high. */
    static final double AGAIN = 0.7;

    /** A result this short is not worth folding: the preview and the note are most of it. */
    static final int FOLDABLE = 2 * PREVIEW;

    /**
     * What a folded result is shown as, and how much of it the model was told to read at a time.
     *
     * @param shown the step's result as the prompts show it from now on
     * @param chunk characters that fit at a time when it was folded, read_result's default length
     */
    record Fold(String shown, long chunk) {}

    /**
     * The results to fold so that a request of {@code requestChars} fits, each with what it is
     * shown as. Empty when there is nothing left to fold.
     *
     * @param turns        the task's steps, in order
     * @param folded       the steps already folded, by index
     * @param requestChars the characters of the request that did not fit
     * @param window       the model's window in tokens, or null when the provider did not say
     * @param round        how many rounds of folding this task has had before this one
     */
    static Map<Integer, Fold> plan(List<AgentTrajectory.Turn> turns, Map<Integer, Fold> folded,
                                   long requestChars, Integer window, int round) {
        long target = target(requestChars, window, round);
        record Candidate(int index, int length) {}
        var candidates = new ArrayList<Candidate>();
        for (int i = 0; i < turns.size(); i++) {
            var turn = turns.get(i);
            if (turn.byTheLoop() || folded.containsKey(i) || turn.observation() == null) continue;
            String output = turn.observation().output();
            if (output != null && output.length() > FOLDABLE) candidates.add(new Candidate(i, output.length()));
        }
        // The largest first, the older first among equals: the sort is stable.
        candidates.sort(Comparator.comparingInt(Candidate::length).reversed());
        var chosen = new ArrayList<Candidate>();
        long size = requestChars;
        for (var c : candidates) {
            if (size <= target) break;
            chosen.add(c);
            size -= c.length() - PREVIEW;
        }
        // What is left of the target once these are folded is what a read can take: half of
        // it, so that two reads in a row still fit. Never less than a preview's worth.
        long chunk = Math.max(PREVIEW, (target - size) / 2);
        var out = new LinkedHashMap<Integer, Fold>();
        for (var c : chosen) {
            var turn = turns.get(c.index());
            out.put(c.index(), new Fold(shown(turn, c.index() + 1, chunk), chunk));
        }
        return out;
    }

    /** The size a round aims under, in characters. */
    static long target(long requestChars, Integer window, int round) {
        long smaller = (long) (requestChars * SHRINK * Math.pow(AGAIN, round));
        if (window == null || window <= 0) return smaller;
        long fits = (long) (window * CHARS_PER_TOKEN * Math.pow(AGAIN, round));
        return Math.min(smaller, fits);
    }

    /**
     * A folded step's result as the prompts show it: the line naming the tool, how it went and
     * how long it took, as for any result; then what it is folded to.
     */
    static String shown(AgentTrajectory.Turn turn, int step, long chunk) {
        String output = turn.observation().output();
        int cut = output.offsetByCodePoints(0, Math.min(PREVIEW, output.codePointCount(0, output.length())));
        var sb = new StringBuilder();
        sb.append('[').append(turn.action().tool()).append("] ")
          .append(turn.observation().success() ? "OK" : "FAILED")
          .append(" (").append(turn.observation().durationMs()).append("ms)\n");
        sb.append("[Step ").append(step).append("'s result is ").append(count(output.length()))
          .append(" characters: too long to show whole with the rest of this task in the model's context ")
          .append("window, so it is folded and only its first ").append(count(cut)).append(" are below. ")
          .append("Nothing was dropped: read_result with step=").append(step)
          .append(" reads any part of it -- from, a character offset, and length; about ").append(count(chunk))
          .append(" characters fit at a time now -- and with find, every place a text occurs in it.]\n");
        sb.append(output, 0, cut);
        sb.append("\n[… folded: characters ").append(count(cut)).append('-').append(count(output.length()))
          .append(" of step ").append(step).append("'s result]");
        return sb.toString();
    }

    /** How many characters a find shows on either side of each place it found, within its line. */
    static final int AROUND = 300;

    /**
     * read_result: part of step {@code step}'s result, or every place {@code find} occurs in it.
     *
     * @param output  the step's result, whole
     * @param from    the character offset to read from; null for the start
     * @param length  how many characters to read; null for {@code chunk}
     * @param find    a text to look for, case aside; null or blank to read instead
     * @param chunk   what fits at a time, as the fold said; 0 or less for the rest of the result
     */
    static String read(int step, String output, Long from, Long length, String find, long chunk) {
        int total = output.length();
        if (find != null && !find.isBlank()) return found(step, output, find);
        long start = from == null ? 0 : from;
        if (start < 0 || start > total) {
            return "ERROR: from=" + start + " is outside step " + step + "'s result, which has "
                    + count(total) + " characters (offsets 0 to " + total + ").";
        }
        long want = length != null ? length : chunk > 0 ? chunk : total - start;
        if (want <= 0) return "ERROR: length must be a positive number of characters.";
        int begin = codePointStart(output, (int) start);
        int end = codePointEnd(output, want >= total - begin ? total : (int) (begin + want));
        var sb = new StringBuilder("Step ").append(step).append("'s result, characters ")
                .append(count(begin)).append('-').append(count(end)).append(" of ").append(count(total))
                .append(":\n").append(output, begin, end);
        if (end < total) {
            sb.append("\n[… characters ").append(count(end)).append('-').append(count(total))
              .append(" follow: read_result step=").append(step).append(" from=").append(end).append(']');
        }
        return sb.toString();
    }

    /** Every place {@code find} occurs, with the text around it, within its line. */
    private static String found(int step, String output, String find) {
        Matcher m = Pattern.compile(Pattern.quote(find), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
                .matcher(output);
        var places = new StringBuilder();
        int n = 0;
        while (m.find()) {
            n++;
            int lineStart = m.start() == 0 ? 0 : output.lastIndexOf('\n', m.start() - 1) + 1;
            int lineEnd = output.indexOf('\n', m.end());
            if (lineEnd < 0) lineEnd = output.length();
            int a = codePointStart(output, Math.max(lineStart, m.start() - AROUND));
            int b = codePointEnd(output, Math.min(lineEnd, m.end() + AROUND));
            places.append("\n@").append(m.start()).append(": ")
                  .append(a > lineStart ? "…" : "").append(output, a, b).append(b < lineEnd ? "…" : "");
        }
        if (n == 0) {
            return "\"" + find + "\" does not occur in step " + step + "'s result (" + count(output.length())
                    + " characters, case aside).";
        }
        return "\"" + find + "\" occurs in " + n + (n == 1 ? " place" : " places") + " in step " + step
                + "'s result (" + count(output.length()) + " characters, case aside): its offset, then the text "
                + "around it in its line. read_result with from reads on from any offset." + places;
    }

    /** {@code at}, or the start of the character it falls inside when it splits a surrogate pair. */
    private static int codePointStart(String s, int at) {
        if (at > 0 && at < s.length() && Character.isLowSurrogate(s.charAt(at))
                && Character.isHighSurrogate(s.charAt(at - 1))) {
            return at - 1;
        }
        return at;
    }

    /** {@code at}, or the end of the character it falls inside when it splits a surrogate pair. */
    private static int codePointEnd(String s, int at) {
        return codePointStart(s, at) == at ? at : at + 1;
    }

    private static String count(long n) {
        return String.format(Locale.ROOT, "%,d", n);
    }
}
