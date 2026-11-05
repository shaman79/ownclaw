package com.ownclaw.agent;

import com.ownclaw.privacy.Redactor;

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
 * the next and the provider's cache keeps working. The rounds count again from the first after a
 * request that fits: a later overflow is a new one.
 * <p>
 * What is shown of a folded result, and every part read_result returns, is cut from the result
 * with its secrets removed ({@link Redactor#cuttable}), never from the bytes as they are: the
 * gateway removes a secret it recognises, and a part cut through one -- the value after
 * "password=" without its key, a vault value without its first letter -- is no longer one it
 * recognises. A cut never falls inside an identifier either.
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
     * What a folded result is shown as from now on.
     *
     * @param shown the step's result as the prompts show it
     */
    record Fold(String shown) {}

    /**
     * The results to fold so that a request of {@code requestChars} fits, each with what it is
     * shown as. Empty when there is nothing left to fold. The task's own steps only: the chat's
     * messages are {@link #plan(List, Map, List, Map, long, Integer, int, java.util.function.Function)}'s.
     *
     * @param turns        the task's steps, in order
     * @param folded       the steps already folded, by index
     * @param requestChars the characters of the request that did not fit
     * @param window       the model's window in tokens, or null when the provider did not say
     * @param round        how many rounds of folding this overflow has had before this one
     * @param readable     a result as it may be cut ({@link Redactor#cuttable})
     */
    static Map<Integer, Fold> plan(List<AgentTrajectory.Turn> turns, Map<Integer, Fold> folded,
                                   long requestChars, Integer window, int round,
                                   java.util.function.Function<String, Redactor.Cuttable> readable) {
        return plan(turns, folded, List.of(), Map.of(), requestChars, window, round, readable).stepFolds();
    }

    /**
     * How a chat message is shown once folded: to its first characters, or to a line.
     *
     * @param shown the message as the prompt shows it, its line break at the end
     * @param line  folded to a line, which nothing folds further
     */
    record ChatFold(String shown, boolean line) {}

    /**
     * What one round folded.
     *
     * @param steps     the steps folded, by index from 0, largest first
     * @param stepFolds what each is shown as
     * @param chat      the chat messages folded, by index from 0, as each is shown now
     * @param labels    each fold as the owner is told it: "memory_manage (613,878 characters)"
     */
    record Folded(List<Integer> steps, Map<Integer, Fold> stepFolds, Map<Integer, ChatFold> chat,
                  List<String> labels) {
        boolean isEmpty() {
            return steps.isEmpty() && chat.isEmpty();
        }
    }

    /** The chat's newest messages that never fold to a line: the turn the task answers. */
    static final int NEWEST_KEPT = 2;

    /**
     * The results and chat messages to fold so that a request of {@code requestChars} fits.
     * First, by size, the largest of the task's results and the chat's messages, each to its
     * first {@link #PREVIEW} characters, the older first among equals -- a pasted log among the
     * chat's messages goes before a short result. Then, while it still does not fit, the chat's
     * messages from the oldest, each to one line: its number, its size and how it began, read
     * whole with read_result message=n -- a chat long for having many messages has none large
     * enough to fold by size. The newest {@link #NEWEST_KEPT} are never folded to a line.
     */
    static Folded plan(List<AgentTrajectory.Turn> turns, Map<Integer, Fold> folded,
                       List<String> chat, Map<Integer, ChatFold> chatFolded,
                       long requestChars, Integer window, int round,
                       java.util.function.Function<String, Redactor.Cuttable> readable) {
        long target = target(requestChars, window, round);
        // Each that can fold by size: a step (index >= 0) or a chat message (-1 - index), in the
        // order it was written -- the chat before the task -- so that the stable sort keeps the
        // older first among equals.
        record Candidate(int key, int length) {}
        var candidates = new ArrayList<Candidate>();
        for (int i = 0; i < chat.size(); i++) {
            if (!chatFolded.containsKey(i) && chat.get(i).length() > FOLDABLE) {
                candidates.add(new Candidate(-1 - i, chat.get(i).length()));
            }
        }
        for (int i = 0; i < turns.size(); i++) {
            var turn = turns.get(i);
            if (turn.byTheLoop() || folded.containsKey(i) || turn.observation() == null) continue;
            String output = turn.observation().output();
            if (output != null && output.length() > FOLDABLE) candidates.add(new Candidate(i, output.length()));
        }
        candidates.sort(Comparator.comparingInt(Candidate::length).reversed());
        var steps = new ArrayList<Integer>();
        var stepFolds = new LinkedHashMap<Integer, Fold>();
        var chatNow = new LinkedHashMap<Integer, ChatFold>();
        var labels = new ArrayList<String>();
        long size = requestChars;
        long chatChars = 0;
        int chatCount = 0;
        for (var c : candidates) {
            if (size <= target) break;
            if (c.key() >= 0) {
                var turn = turns.get(c.key());
                Fold f = new Fold(shown(turn, c.key() + 1, readable.apply(turn.observation().output())));
                steps.add(c.key());
                stepFolds.put(c.key(), f);
                labels.add(turn.action().tool() + " (" + count(c.length()) + " characters)");
                size -= c.length() - f.shown().length();
            } else {
                int i = -1 - c.key();
                ChatFold f = new ChatFold(shownMessage(i + 1, chat.size(), readable.apply(chat.get(i))), false);
                chatNow.put(i, f);
                chatChars += c.length();
                chatCount++;
                size -= c.length() - f.shown().length();
            }
        }
        // Then the chat's oldest messages, each to a line.
        for (int i = 0; i < chat.size() - NEWEST_KEPT && size > target; i++) {
            ChatFold was = chatNow.containsKey(i) ? chatNow.get(i) : chatFolded.get(i);
            if (was != null && was.line()) continue;
            String message = chat.get(i);
            ChatFold f = new ChatFold(messageLine(i + 1, chat.size(), readable.apply(message)), true);
            long shownBefore = was != null ? was.shown().length() : message.length();
            if (f.shown().length() >= shownBefore) continue;
            if (!chatNow.containsKey(i)) {
                chatChars += message.length();
                chatCount++;
            }
            chatNow.put(i, f);
            size -= shownBefore - f.shown().length();
        }
        if (chatCount > 0) {
            labels.add(chatCount + (chatCount == 1 ? " message" : " messages") + " of this chat ("
                    + count(chatChars) + " characters)");
        }
        return new Folded(List.copyOf(steps), stepFolds, chatNow, List.copyOf(labels));
    }

    /** A chat message folded to its first characters, a line break after it, as every message has. */
    static String shownMessage(int n, int of, Redactor.Cuttable readable) {
        String text = readable.text();
        int cut = Math.min(text.length(),
                text.offsetByCodePoints(0, Math.min(PREVIEW, text.codePointCount(0, text.length()))));
        cut = readable.to(cut);
        return "[Message " + n + " of " + of + " of this chat is " + count(text.length()) + " characters: too long "
                + "to show whole with the rest of this task in the model's context window, so it is folded and only "
                + "its first " + count(cut) + " are below. read_result with message=" + n + " reads any part of it.]\n"
                + text.substring(0, cut).stripTrailing() + "\n[… folded: characters " + count(cut) + "-"
                + count(text.length()) + " of message " + n + "]\n";
    }

    /** How much of a message's first line a chat message folded to a line shows. */
    static final int LINE = 100;

    /** A chat message folded to a line: its number, its size and how it began. */
    static String messageLine(int n, int of, Redactor.Cuttable readable) {
        String text = readable.text();
        // Its first line that is not blank, cut as every part is: never through an identifier.
        int start = 0;
        while (start < text.length() && Character.isWhitespace(text.charAt(start))) start++;
        int lineEnd = text.indexOf('\n', start);
        if (lineEnd < 0) lineEnd = text.length();
        String line = text.substring(start, lineEnd);
        String first = line.strip();
        if (line.codePointCount(0, line.length()) > LINE) {
            int end = Math.min(lineEnd, readable.to(start + line.offsetByCodePoints(0, LINE)));
            first = text.substring(start, end).strip() + (end < lineEnd ? "…" : "");
        }
        return "[Message " + n + " of " + of + ", " + count(text.length()) + " characters, folded to a line; "
                + "read_result with message=" + n + " reads it whole] " + first + "\n";
    }

    /**
     * Whether the reply filled the window, not the request: one of less than half the window was
     * read whole, and the reply ran on until the window was full -- a local generation with no
     * output limit. Folding the request then makes room for a reply that runs on again, each
     * round a whole window of generation, so the task ends as before.
     */
    static boolean replyFilledIt(com.ownclaw.llm.OutputTruncated cut) {
        return cut.reply() != null && cut.tokens() != null
                && cut.reply().billedInputTokens() > 0 && cut.reply().billedInputTokens() < cut.tokens() / 2;
    }

    /** The size a round aims under, in characters. */
    static long target(long requestChars, Integer window, int round) {
        long smaller = (long) (requestChars * SHRINK * Math.pow(AGAIN, round));
        if (window == null || window <= 0) return smaller;
        long fits = (long) (window * CHARS_PER_TOKEN * Math.pow(AGAIN, round));
        return Math.min(smaller, fits);
    }

    /**
     * How much of a result to read when the model does not say: half the room the last request
     * left under {@code budget}, the characters that fit by the last overflow; never less than
     * a foldable result, so that what is read can be folded in its turn.
     */
    static long chunk(long budget, long lastRequestChars) {
        return Math.max(FOLDABLE, (budget - lastRequestChars) / 2);
    }

    /**
     * A folded step's result as the prompts show it: the line naming the tool, how it went and
     * how long it took, as for any result; then what it is folded to.
     */
    static String shown(AgentTrajectory.Turn turn, int step, Redactor.Cuttable readable) {
        String text = readable.text();
        int cut = Math.min(text.length(),
                text.offsetByCodePoints(0, Math.min(PREVIEW, text.codePointCount(0, text.length()))));
        cut = readable.to(cut);
        var sb = new StringBuilder();
        sb.append('[').append(turn.action().tool()).append("] ")
          .append(turn.observation().success() ? "OK" : "FAILED")
          .append(" (").append(turn.observation().durationMs()).append("ms)\n");
        sb.append("[Step ").append(step).append("'s result is ").append(count(text.length()))
          .append(" characters: too long to show whole with the rest of this task in the model's context ")
          .append("window, so it is folded and only its first ").append(count(cut)).append(" are below. ")
          .append("Nothing was dropped: read_result with step=").append(step)
          .append(" reads any part of it -- from, a character offset, and length, as much as fits when ")
          .append("left out -- and with find, every place a text occurs in it.]\n");
        sb.append(text, 0, cut);
        sb.append("\n[… folded: characters ").append(count(cut)).append('-').append(count(text.length()))
          .append(" of step ").append(step).append("'s result]");
        return sb.toString();
    }

    /** How many characters a find shows on either side of each place it found, within its line. */
    static final int AROUND = 300;

    /**
     * read_result: part of step {@code step}'s result, or every place {@code find} occurs in it --
     * of the result as it may be cut, secrets removed ({@link Redactor#cuttable}).
     *
     * @param readable the step's result, as it may be cut
     * @param from     the character offset to read from; null for the start
     * @param length   how many characters to read; null for {@code chunk}
     * @param find     a text to look for, case aside; null or blank to read instead
     * @param chunk    what fits at a time now; 0 or less for the rest of the result
     */
    static String read(int step, Redactor.Cuttable readable, Long from, Long length, String find, long chunk) {
        return read(new Named("step " + step + "'s result", "step=" + step), readable, from, length, find, chunk);
    }

    /**
     * What read_result reads, as its answers name it.
     *
     * @param what "step 6's result", "result {{3}}"
     * @param args the arguments that name it again: "step=6", "result=3"
     */
    record Named(String what, String args) {
        String capitalised() {
            return Character.toUpperCase(what.charAt(0)) + what.substring(1);
        }
    }

    /** {@link #read(int, Redactor.Cuttable, Long, Long, String, long)}, of what {@code named} names. */
    static String read(Named named, Redactor.Cuttable readable, Long from, Long length, String find, long chunk) {
        String output = readable.text();
        int total = output.length();
        if (find != null && !find.isBlank()) return found(named, readable, find);
        long start = from == null ? 0 : from;
        if (start < 0 || start > total) {
            return "ERROR: from=" + start + " is outside " + named.what() + ", which has "
                    + count(total) + " characters (offsets 0 to " + total + ").";
        }
        long want = length != null ? length : chunk > 0 ? chunk : total - start;
        if (want <= 0) return "ERROR: length must be a positive number of characters.";
        int begin = readable.from(codePointStart(output, (int) start));
        int end = readable.to(codePointEnd(output, want >= total - begin ? total : (int) (begin + want)));
        var sb = new StringBuilder(named.capitalised()).append(", characters ")
                .append(count(begin)).append('-').append(count(end)).append(" of ").append(count(total))
                .append(":\n").append(output, begin, end);
        if (end < total) {
            sb.append("\n[… characters ").append(count(end)).append('-').append(count(total))
              .append(" follow: read_result ").append(named.args()).append(" from=").append(end).append(']');
        }
        return sb.toString();
    }

    /**
     * A delegation's read of a result as its conversation shows it once folded: its first
     * {@link #PREVIEW} characters and a note -- the same read reads it again.
     */
    static String foldedRead(String read) {
        int cut = Math.min(read.length(),
                read.offsetByCodePoints(0, Math.min(PREVIEW, read.codePointCount(0, read.length()))));
        return "[A read of " + count(read.length()) + " characters, folded to fit your context window: its first "
                + count(cut) + " are below. read_result with the same arguments reads it again.]\n"
                + read.substring(0, cut) + "\n[… folded]";
    }

    /**
     * A delegation's result as its conversation shows it once folded: the line its reply opened
     * with, then its first {@link #PREVIEW} characters and a note. The local model reads the rest
     * with read_result, and a tool it hands {{n}} is still handed the whole result.
     *
     * @param handle the result's handle in the delegation, from 1
     * @param head   the reply's first line as it was: "Tool result {{3}} [tool] SUCCESS:\n"
     */
    static String foldedResult(int handle, String head, Redactor.Cuttable readable) {
        String text = readable.text();
        int cut = Math.min(text.length(),
                text.offsetByCodePoints(0, Math.min(PREVIEW, text.codePointCount(0, text.length()))));
        cut = readable.to(cut);
        String h = com.ownclaw.agent.ArtifactRef.handle(handle);
        return head + "[Result " + h + " is " + count(text.length()) + " characters: too long to show whole with "
                + "the rest of this work in your context window, so it is folded and only its first " + count(cut)
                + " are below. Nothing was dropped: read_result with result=" + handle + " reads any part of it -- "
                + "from, a character offset, and length, as much as fits when left out -- and with find, every "
                + "place a text occurs in it. A tool you hand " + h + " is still given all of it.]\n"
                + text.substring(0, cut) + "\n[… folded: characters " + count(cut) + "-" + count(text.length())
                + " of result " + h + "]";
    }

    /**
     * Every place {@code find} occurs, with the text around it within its line: places whose
     * text meets or overlaps are shown once, together -- each shown on its own, a letter found
     * on every line of a long one repeated the line once for every time it was found.
     */
    private static String found(Named named, Redactor.Cuttable readable, String find) {
        String output = readable.text();
        Matcher m = Pattern.compile(Pattern.quote(find), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
                .matcher(output);
        var places = new StringBuilder();
        int n = 0;
        int a = -1;
        int b = -1;
        int lineStart = 0;
        int lineEnd = 0;
        var at = new ArrayList<Integer>();
        while (m.find()) {
            n++;
            int ls = m.start() == 0 ? 0 : output.lastIndexOf('\n', m.start() - 1) + 1;
            int le = output.indexOf('\n', m.end());
            if (le < 0) le = output.length();
            int na = readable.from(codePointStart(output, Math.max(ls, m.start() - AROUND)));
            int nb = readable.to(codePointEnd(output, Math.min(le, m.end() + AROUND)));
            if (a >= 0 && na <= b) {
                b = Math.max(b, nb);
                lineEnd = Math.max(lineEnd, le);
                at.add(m.start());
                continue;
            }
            if (a >= 0) place(places, output, a, b, lineStart, lineEnd, at);
            a = na;
            b = nb;
            lineStart = ls;
            lineEnd = le;
            at = new ArrayList<>(List.of(m.start()));
        }
        if (a >= 0) place(places, output, a, b, lineStart, lineEnd, at);
        if (n == 0) {
            return "\"" + find + "\" does not occur in " + named.what() + " (" + count(output.length())
                    + " characters, case aside).";
        }
        return "\"" + find + "\" occurs in " + count(n) + (n == 1 ? " place" : " places") + " in " + named.what()
                + " (" + count(output.length()) + " characters, case aside): the offset of each -- of the "
                + "first and last of places shown together -- then the text around it in its line. read_result "
                + "with from reads on from any offset." + places;
    }

    private static void place(StringBuilder places, String output, int a, int b, int lineStart, int lineEnd,
                              List<Integer> at) {
        places.append("\n@").append(at.getFirst());
        if (at.size() > 1) {
            places.append(" to @").append(at.getLast()).append(" (")
                  .append(String.format(Locale.ROOT, "%,d", at.size())).append(" places)");
        }
        places.append(": ").append(a > lineStart ? "…" : "").append(output, a, b).append(b < lineEnd ? "…" : "");
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
