package com.ownclaw.agent;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Text a delegation's local model would have to retype exactly: a block of lines written into
 * its plan. A delegate call that holds one is refused before anything runs, and the cloud is told
 * to hand the block over by name instead ({@link DelegationPlan#texts}), which code inserts.
 * <p>
 * On 2026-10-08 the cloud delegated the install of a watchdog script -- 33 lines, the script a
 * heredoc in them -- written into the goal, to the local model, which had to retype it into
 * openwrt_run_to_file's command. Replayed, it broke one line -- {@code if [ "$DEAD" = "1" ]; then} came back as
 * {@code "1"]} -- in five tool calls of seven, under every sampling setting tried; and two runs of
 * nine never made the call, copying the script into their reasoning again and again to check it,
 * as the production run did for two and a half hours. A prompt rule cannot make a small model
 * copy exactly; inserting the text by code needs no copy at all.
 * <p>
 * A block is what the plan marks as literal: in its prose -- the goal, a step's description, a
 * checkpoint -- a fenced code block or a shell heredoc; in a step's parameters, any value, which
 * the local model copies into its call as written. One is refused when it holds {@link #LINES}
 * lines or more. A block of one line is a command like any written inline, and those stay
 * allowed. From the second line on, the text has a layout the model must reproduce as well as
 * its characters -- line breaks, indentation, a terminator that has to stand alone on its line --
 * inside a JSON string, as escapes. Every line is one more place to get a character wrong, and a
 * name costs the cloud nothing to write, so the line is drawn where a block begins to have a
 * layout: at its second line.
 */
final class LiteralBlocks {

    private LiteralBlocks() {}

    /** The fewest lines in a block that is refused; see the class comment. */
    static final int LINES = 2;

    /**
     * A block found in a plan.
     *
     * @param kind  what it is, in words: "a heredoc", "a fenced code block", "a value"
     * @param where where in the plan: "the goal", "step 2's 'command' parameter"
     * @param lines how many lines it holds, its fences and its heredoc's opening and
     *              terminating lines not counted
     * @param first its first line -- for a heredoc, the line that opens it -- so the cloud can
     *              tell which block is meant
     */
    record Block(String kind, String where, int lines, String first) {}

    /** A fence: three or more backticks or tildes, indented by at most three spaces. */
    private static final Pattern FENCE = Pattern.compile("^ {0,3}(`{3,}|~{3,})(.*)$");

    /**
     * The opening of a heredoc: {@code <<} or {@code <<-}, then the delimiter word, bare or quoted
     * or escaped. Not a here-string ({@code <<<}).
     */
    private static final Pattern HEREDOC = Pattern.compile(
            "(?<!<)<<(?!<)-?[ \\t]*(?:'([A-Za-z_][A-Za-z0-9_]*)'|\"([A-Za-z_][A-Za-z0-9_]*)\"|\\\\?([A-Za-z_][A-Za-z0-9_]*))");

    /** The first block in the plan, in the order the local model reads it, or null when there is none. */
    static Block first(DelegationPlan plan) {
        Block b = in(plan.goal(), "the goal");
        if (b != null) return b;
        for (int i = 0; i < plan.steps().size(); i++) {
            var step = plan.steps().get(i);
            b = in(step.description(), "step " + (i + 1) + "'s description");
            if (b != null) return b;
            if (step.params() == null) continue;
            for (var e : step.params().entrySet()) {
                b = value(e.getValue(), "step " + (i + 1) + "'s '" + e.getKey() + "' parameter");
                if (b != null) return b;
            }
        }
        List<String> checkpoints = plan.checkpoints() == null ? List.of() : plan.checkpoints();
        for (int i = 0; i < checkpoints.size(); i++) {
            b = in(checkpoints.get(i), "checkpoint " + (i + 1));
            if (b != null) return b;
        }
        return null;
    }

    /** The first fenced code block or heredoc of {@link #LINES} lines or more in prose, or null. */
    static Block in(String prose, String where) {
        if (prose == null || prose.isEmpty()) return null;
        String[] lines = prose.split("\r?\n", -1);
        for (int i = 0; i < lines.length; i++) {
            Matcher fence = FENCE.matcher(lines[i]);
            // A backtick fence's info string holds no backtick: ```ls``` on a line is inline code.
            if (fence.matches() && !(fence.group(1).charAt(0) == '`' && fence.group(2).indexOf('`') >= 0)) {
                String mark = fence.group(1);
                int end = i + 1;
                // Unclosed, it runs to the end of the text, as a fence does in Markdown.
                while (end < lines.length && !closes(lines[end], mark)) end++;
                int body = end - i - 1;
                if (body >= LINES) {
                    return new Block("a fenced code block", where, body, firstLine(lines, i + 1, end));
                }
                i = end;
                continue;
            }
            Matcher heredoc = HEREDOC.matcher(lines[i]);
            if (heredoc.find()) {
                String delimiter = heredoc.group(1) != null ? heredoc.group(1)
                        : heredoc.group(2) != null ? heredoc.group(2) : heredoc.group(3);
                int end = i + 1;
                while (end < lines.length && !lines[end].strip().equals(delimiter)) end++;
                // No line ends it: a shift, or prose that mentions the operator, not a heredoc.
                if (end == lines.length) continue;
                int body = end - i - 1;
                if (body >= LINES) return new Block("a heredoc", where, body, lines[i].strip());
                i = end;
            }
        }
        return null;
    }

    /** A step parameter's value: a string of {@link #LINES} lines or more, at any depth, or null. */
    private static Block value(Object v, String where) {
        if (v instanceof String s) {
            String[] lines = s.split("\r?\n", -1);
            return lines.length >= LINES
                    ? new Block("a value", where, lines.length, firstLine(lines, 0, lines.length)) : null;
        }
        if (v instanceof Map<?, ?> m) {
            for (Object x : m.values()) {
                Block b = value(x, where);
                if (b != null) return b;
            }
        } else if (v instanceof Collection<?> c) {
            for (Object x : c) {
                Block b = value(x, where);
                if (b != null) return b;
            }
        }
        return null;
    }

    /** Whether the line closes a fence opened by {@code mark}: the same character, at least as many. */
    private static boolean closes(String line, String mark) {
        String s = line.strip();
        if (s.length() < mark.length()) return false;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) != mark.charAt(0)) return false;
        return true;
    }

    /** The first line of lines[from, to) with anything on it, stripped; "" when none has. */
    private static String firstLine(String[] lines, int from, int to) {
        for (int i = from; i < to; i++) if (!lines[i].isBlank()) return lines[i].strip();
        return "";
    }

    /**
     * What the model that delegated is told: what was found and where, why it is refused, and
     * exactly what to do instead. Nothing ran.
     */
    static String refusal(Block block) {
        return String.format(Locale.ROOT, "Not started: %s holds %s of %,d lines", block.where(),
                block.kind(), block.lines())
                + (block.first().isEmpty() ? "" : " (it begins: " + block.first() + ")")
                + ", which the local model would have to retype into its tool call -- and a small model "
                + "does not retype long text exactly: it changes a character here and there, and checks "
                + "its copy over and over. Hand it over by name instead: put the exact text in delegate's "
                + "'texts' -- \"texts\": {\"script\": \"<the whole text, every line, exactly as it is to be "
                + "used>\"} -- and write {{script}} where the text was (\"run {{script}} on each "
                + "host\"). The local model writes {{script}} in its tool call, and the text is put "
                + "there exactly when the call runs. Of a heredoc, take the whole command it belongs "
                + "to, not only its inside. Nothing was run.";
    }
}
