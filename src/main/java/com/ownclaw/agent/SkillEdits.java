package com.ownclaw.agent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * A change to an existing skill's code as the lines to replace, instead of the whole file written
 * again. Fixing a skill used to mean regenerating all of it: three repairs in four days of November
 * 2026 -- recognise a "closed" line in one restaurant's menu, skip a story an earlier section had
 * picked, retry differently after HTTP 429 -- each wrote the whole file again, 35,528, 22,632 and
 * 21,268 output tokens and 234, 172 and 154 seconds for a change of a few lines, $2.15 together and
 * a quarter of what the system spent in those days; and a regeneration before them had changed a
 * line nobody asked about, a browser's version string. So where a skill exists and its code is
 * written on the cloud, the model is asked for edit blocks ({@link #ASK}), and code applies them
 * ({@link #apply}): everything outside the blocks stays as it was, by construction.
 * <p>
 * A block is two runs of whole lines between three marker lines -- the conflict markers these
 * models write reliably:
 * <pre>
 * &lt;&lt;&lt;&lt;&lt;&lt;&lt; SEARCH
 * the lines to replace
 * =======
 * the lines that replace them
 * &gt;&gt;&gt;&gt;&gt;&gt;&gt; REPLACE
 * </pre>
 * Whole lines, compared character for character: Python reads a line's indentation, and a SEARCH
 * text found inside a longer line would put the lines that replace it at that line's depth, not
 * their own. The lines to replace must stand at exactly one place in the code as the blocks before
 * left it; the blocks are applied in the order given. Where one cannot be -- not there, there more
 * than once, nothing to look for, nothing changed, the same block twice, a marker line missing or
 * doubled -- none is applied, and the model is told which block and why ({@link Refusal}).
 * <p>
 * The lines a block names are lines of the code exactly as the cloud gateway sent it
 * ({@code EgressContext.SentAs}), and that is where they are looked for. It is neither the skill.py
 * on disk nor the copy kept as the model wrote it: the gateway's filter reads code as it reads any
 * text, and of a copy with no value of the user's in it, {@code ssid = params.get('ssid', '')} went
 * out as {@code ssid = <ssid_1>}, a comment's {@code password=...} as a removed secret, and a
 * browser's {@code Chrome/131.0.0.0} as {@code Chrome/<ip_1>} -- some of which nothing can put back.
 * So where the skill has such a copy, and the code sent has its lines one for one, the change is
 * carried out on the copy, line for line ({@link #apply}): a line no block changes is the skill's
 * own, not what the filter made of it on the way out. What becomes of the result -- the checks,
 * the user's values put back, the copy kept -- is what becomes of a reply's complete code
 * ({@code AgentLoop#generateSkillCode}).
 */
final class SkillEdits {

    private SkillEdits() {}

    /** The line that opens a block, and its lines to replace. */
    static final String SEARCH = "<<<<<<< SEARCH";
    /** The line between the lines to replace and the lines that replace them. */
    static final String DIVIDER = "=======";
    /** The line that closes a block. */
    static final String REPLACE = ">>>>>>> REPLACE";

    /**
     * The system prompt's line on what a reply holds, where the change is asked for as edit blocks:
     * in place of "Output: ```python fence + ```requirements fence."
     */
    static final String OUTPUT = "Output: edit blocks for the change (their format follows the code) + ```requirements "
            + "fence. The complete code in a ```python fence only when most of the file changes.\n\n";

    /**
     * What the request says under the code, where it used to say "Return complete fixed code.": the
     * format, stated whole, and that the complete code may be returned instead.
     */
    static final String ASK = "Return the change as edit blocks, not the whole file -- one block for each place that "
            + "changes:\n\n"
            + SEARCH + "\n"
            + "the lines to replace, copied exactly from the code above\n"
            + DIVIDER + "\n"
            + "the lines that replace them\n"
            + REPLACE + "\n\n"
            + "- The three marker lines exactly as shown, each alone on its line, from the first column. No ```python "
            + "fence around a block.\n"
            + "- SEARCH is whole lines of the code above, copied character for character with their indentation, and "
            + "must match exactly one place in the code: add the lines before or after it until it does.\n"
            + "- REPLACE is the whole lines that stand there afterwards. To add lines, repeat a neighbouring line in "
            + "SEARCH and in REPLACE; to delete lines, leave REPLACE empty.\n"
            + "- Any number of blocks, applied in the order given, each to the code as the blocks before it left it. "
            + "Everything outside the blocks stays exactly as it is.\n"
            + "- A word with a number in angle brackets, or a marker in « », stands for text you are not shown: copy "
            + "it exactly, and leave a line that holds one as it is unless the change is about that line.\n"
            + "- If most of the file changes, return the complete code in one ```python fence instead -- never both.\n"
            + "After the blocks, the ```requirements fence as asked -- also when nothing in it changes.\n";

    /**
     * What a reply of edit blocks cut off at the model's maximum output is followed by: the request
     * to go on, in the words of {@code AgentLoop#CONTINUE} without its code fence -- a reply of
     * blocks has none to close.
     */
    static final String CONTINUE = "Your reply was cut off at the model's maximum output. Continue it from "
            + "the exact character where it stops: repeat nothing already written, and do not explain. When the "
            + "edit blocks are complete, give the ```requirements fence as asked.";

    /** What every refusal ends with: that nothing was changed, and what to return. */
    static final String AGAIN = "Nothing was changed. Return all the edit blocks again, corrected -- every block, "
            + "each against the code as it is shown above -- or, when most of the file changes, the complete code "
            + "in one ```python fence. Then the ```requirements fence as asked.";

    /**
     * How many lines of the result a syntax error is shown with on either side of its own: where to
     * look, as a diff shows a change with three lines around it. Nothing is kept from the model by
     * it -- the code it was shown and the blocks it wrote are both in the request, whole.
     */
    private static final int AROUND = 3;

    /**
     * One block of a reply.
     *
     * @param n       which block of the reply, from 1: the place of its SEARCH line among them
     * @param search  the lines to replace
     * @param replace the lines that replace them; none deletes the lines
     */
    record Block(int n, List<String> search, List<String> replace) {
        boolean sameEdit(Block other) {
            return search.equals(other.search) && replace.equals(other.replace);
        }
    }

    /**
     * Why a reply's blocks were not applied -- none of them.
     *
     * @param key  what tells this failure when it comes back: its kind and the lines it is about,
     *             never a block's number, so the same lines not found in another block of the next
     *             reply are the same failure
     * @param why  the failure in one line, for the log
     * @param told what the model is told: which block, why, and what to return; null when asking
     *             again would not help -- the fault is not the reply's
     */
    record Refusal(String key, String why, String told) {
        private static Refusal of(String key, String why, String hint) {
            return new Refusal(key, why, why + (hint == null ? "" : " " + hint) + "\n\n" + AGAIN);
        }
    }

    /**
     * A reply read for edit blocks.
     *
     * @param marked  whether it holds a marker line of a block at all; one that holds none is no
     *                reply of edits, whatever else it is
     * @param blocks  its blocks, in order; those read before a malformed one too
     * @param rest    the reply without its blocks: where its ```requirements fence is read
     * @param refused why its blocks cannot be applied as they are written, or null
     */
    record Read(boolean marked, List<Block> blocks, String rest, Refusal refused) {
        /** What tells these edits from another reply's: the blocks, or the reply itself when it has none. */
        String key() {
            return blocks.isEmpty() ? "\0edits\0" + rest.strip() : keyOf(blocks);
        }
    }

    /**
     * What tells a reply's edits when they come back: its blocks' texts, in order -- never code, which
     * does not begin so, and the same whatever stands around the blocks.
     */
    private static String keyOf(List<Block> blocks) {
        var sb = new StringBuilder("\0edits");
        for (Block b : blocks) {
            sb.append('\0').append(String.join("\n", b.search())).append('\0').append(String.join("\n", b.replace()));
        }
        return sb.toString();
    }

    /**
     * What applying a reply's blocks gave.
     *
     * @param code     the code with every block applied, or null when none was
     * @param asShown  the code as the model was shown it with every block applied: the same lines
     *                 under the same numbers, each a line the gateway sent or one the model wrote
     *                 -- what the model can be told the lines of ({@link #around}). {@code code}
     *                 itself, where the change was carried out on what was sent
     * @param key      what tells these edits from another reply's ({@link Read#key})
     * @param blocks   how many blocks were applied
     * @param replaced the characters of the code they replaced, block by block: of the lines each
     *                 changes, not of those it repeats around them
     * @param written  the characters they put there
     * @param of       the characters of the code the change was carried out on
     * @param refused  why none was applied, or null
     */
    record Applied(String code, String asShown, String key, int blocks, int replaced, int written, int of,
                   Refusal refused) {
        static Applied refused(String key, Refusal why) {
            return new Applied(null, null, key, 0, 0, 0, 0, why);
        }

        /**
         * This, without the blank lines above and below the code -- as a reply's complete code is
         * kept, which is read out of its fence so. The same lines are cut from {@code code} and
         * from {@code asShown}, so a line keeps one number in both.
         */
        Applied withoutBlankLinesAround() {
            List<String> own = Arrays.asList(code.split("\n", -1)), sent = Arrays.asList(asShown.split("\n", -1));
            int from = 0, to = own.size();
            while (from < to && own.get(from).isBlank()) from++;
            while (to > from && own.get(to - 1).isBlank()) to--;
            return new Applied(String.join("\n", own.subList(from, to)), String.join("\n", sent.subList(from, to)), key,
                    blocks, replaced, written, of, refused);
        }

        /** "2 edits (84 of its 9,412 characters replaced by 211)" */
        String inWords() {
            return String.format(Locale.ROOT, "%d edit%s (%,d of its %,d characters replaced by %,d)", blocks,
                    blocks == 1 ? "" : "s", replaced, of, written);
        }
    }

    /**
     * The fault that is not the reply's: what the gateway sent the code as is not known, so there
     * is no text the lines its blocks name could be looked up in. Asking again would not help.
     */
    static Refusal notKnown() {
        return new Refusal("unknown", "what the code was sent to the model as is not known, so the lines its edit "
                + "blocks name cannot be looked up", null);
    }

    // ── reading a reply ──

    /**
     * {@code text} read for edit blocks. A marker line is one exactly, from the first column, blanks
     * after it aside; a line that only begins like one -- other words after the angle brackets, or
     * indented -- is neither a marker nor anybody's text, and the reply is refused for it: taken for
     * text, a block opened by "&lt;&lt;&lt;&lt;&lt;&lt;&lt; ORIGINAL" was no block, and its lines were prose. An
     * indented line of ======= is a block's own text -- a heading's underline in a docstring.
     * <p>
     * A reply that holds blocks and also code in a ```python fence outside them is refused: which of
     * the two is the change cannot be told ({@link #holdsCode}).
     */
    static Read read(String text) {
        String[] lines = (text == null ? "" : text).split("\n", -1);
        var blocks = new ArrayList<Block>();
        var outside = new ArrayList<String>();
        Refusal refused = null;
        boolean marked = false;
        int opened = 0;                 // the SEARCH lines so far: the open block's number
        List<String> search = null;     // the open block's lines to replace; null outside a block
        List<String> replace = null;    // and, from its ======= on, the lines that replace them
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String bare = line.stripTrailing();
            Refusal fault = null;
            if (bare.equals(SEARCH)) {
                marked = true;
                if (search != null) {
                    fault = Refusal.of("malformed:open", "Block " + opened + " is not closed: another " + SEARCH
                            + " line follows before its " + REPLACE + " line.", null);
                }
                opened++;
                search = new ArrayList<>();
                replace = null;
            } else if (search != null && bare.equals(DIVIDER)) {
                if (replace == null) {
                    replace = new ArrayList<>();
                } else {
                    fault = Refusal.of("malformed:divided", "Block " + opened + " holds two " + DIVIDER + " lines, and "
                            + "which of them divides it cannot be told.", "A line of exactly " + DIVIDER + " cannot "
                            + "stand in a block's text: keep that line out of the block, or return the complete code.");
                }
            } else if (bare.equals(REPLACE)) {
                marked = true;
                if (search == null) {
                    fault = Refusal.of("malformed:closed", "A " + REPLACE + " line stands where no block is open: "
                            + "every block begins with a " + SEARCH + " line.", null);
                } else if (replace == null) {
                    fault = Refusal.of("malformed:undivided", "Block " + opened + " has no " + DIVIDER + " line "
                            + "between its " + SEARCH + " line and its " + REPLACE + " line.", null);
                } else {
                    blocks.add(new Block(opened, List.copyOf(search), List.copyOf(replace)));
                }
                search = null;
                replace = null;
            } else if (likeAMarker(line)) {
                marked = true;
                fault = Refusal.of("malformed:marker", "Line " + (i + 1) + " of your reply begins like a marker line "
                        + "and is none.", "The three are exactly \"" + SEARCH + "\", \"" + DIVIDER + "\" and \"" + REPLACE
                        + "\", each alone on its line, from the first column.");
            } else if (search != null) {
                (replace == null ? search : replace).add(line);
            } else {
                outside.add(line);
            }
            if (refused == null) refused = fault;
        }
        if (search != null && refused == null) {
            refused = Refusal.of("malformed:unended", "Your reply ends inside block " + opened + ": no " + REPLACE
                    + " line closes it.", null);
        }
        String rest = String.join("\n", outside);
        if (!marked) return new Read(false, List.of(), rest, null);
        if (holdsCode(rest)) {
            refused = Refusal.of("both", "Your reply holds edit blocks and also code in a ```python fence outside "
                    + "them: which of the two is the change cannot be told.", "Return one of them only.");
        }
        return new Read(true, List.copyOf(blocks), rest, refused);
    }

    /** Whether {@code text} holds a marker line of a block: whether it is a reply of edits. */
    static boolean marked(String text) {
        return read(text).marked();
    }

    /** A line that begins, after any blanks, with seven angle brackets: meant as a marker line. */
    private static boolean likeAMarker(String line) {
        String t = line.strip();
        return t.startsWith("<<<<<<<") || t.startsWith(">>>>>>>");
    }

    /**
     * Whether {@code rest} -- a reply without its blocks -- holds code in a fence: a ```python,
     * ```py or ```python3 fence with a line in it that is not blank, or a bare fence around a module
     * ({@code def run(}). A ```python fence the model put around a block is empty here, and no code;
     * a ```requirements fence is none either.
     */
    static boolean holdsCode(String rest) {
        String[] lines = rest.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String open = lines[i].strip();
            if (!open.startsWith("```")) continue;
            var body = new StringBuilder();
            int j = i + 1;
            while (j < lines.length && !lines[j].strip().startsWith("```")) body.append(lines[j++]).append('\n');
            if (ReplySeam.isPythonFence(open) && !body.toString().isBlank()) return true;
            if (open.equals("```") && body.toString().contains("def run(")) return true;
            // The fence line it ends at closes it when it is a bare one; one that names what it
            // holds opens the next.
            i = j < lines.length && !lines[j].strip().equals("```") ? j - 1 : j;
        }
        return false;
    }

    // ── applying ──

    /**
     * The code with {@code blocks} applied in order. Each block's lines to replace are looked for in
     * {@code shown} -- the code as the model was shown it, as the blocks before left it -- and must
     * be exactly one run of whole lines there; they are replaced by the block's other lines. The
     * same as replacing, in the code with a line break before and after it, the lines to replace
     * framed so by the lines that replace them, wherever the first stand there once -- and refused
     * wherever they do not: not there, or at more than one place, places that overlap counted each.
     * <p>
     * Where {@code kept} is given -- the skill's code as the model wrote it, of which {@code shown}
     * is what the gateway sent -- and has as many lines as {@code shown}, the change is carried out
     * on it, at the same lines, and it is what is returned. The filter puts a placeholder or a
     * marker, neither of which holds a line break, where a stretch of text stood, and takes no line
     * break away unless it stood in such a stretch: the same number of lines is every line in its
     * place. So a line no block changes comes back as the skill has it, whatever it was sent as.
     * The lines a block repeats above and below its change, to say where it is, are no change, and
     * stay the skill's own too: only what lies between them is replaced. With fewer lines sent
     * than kept -- a stretch over several lines was taken out -- or no copy kept, the change is
     * carried out on the code as shown.
     * <p>
     * Refused too, before anything is looked for: a block with nothing to look for -- no line, or
     * only blank ones; a block that changes nothing; and a block that is an earlier block of the
     * same reply again -- applied twice, a block that keeps its line and adds one after it adds it
     * twice, and the result compiles.
     * <p>
     * All of them or none: the first block that cannot be applied refuses the reply, and what is
     * returned holds no code.
     *
     * @param kept the copy of the code kept as the model wrote it, when {@code shown} is that copy
     *             as it was sent; null when it is skill.py, the user's values in it -- put back
     *             into a text that holds them already, a placeholder's shape written there as text
     *             would become a value
     */
    static Applied apply(String shown, String kept, List<Block> blocks) {
        String key = keyOf(blocks);
        List<String> first = Arrays.asList(shown.split("\n", -1));
        List<String> lines = new ArrayList<>(first);
        List<String> own = kept == null ? null : new ArrayList<>(Arrays.asList(kept.split("\n", -1)));
        if (own != null && own.size() != lines.size()) own = null;
        int replaced = 0, written = 0;
        for (int b = 0; b < blocks.size(); b++) {
            Block block = blocks.get(b);
            String its = "Block " + block.n();
            String joined = String.join("\n", block.search());
            if (block.search().stream().allMatch(String::isBlank)) {
                return Applied.refused(key, Refusal.of("empty", its + " was not applied: its SEARCH text is empty, so "
                        + "nothing says which lines it replaces.", null));
            }
            if (block.search().equals(block.replace())) {
                return Applied.refused(key, Refusal.of("unchanged\0" + joined, its + " changes nothing: its REPLACE "
                        + "text is its SEARCH text.", "Leave such a block out."));
            }
            for (int e = 0; e < b; e++) {
                if (block.sameEdit(blocks.get(e))) {
                    return Applied.refused(key, Refusal.of("twice\0" + joined, its + " is block " + blocks.get(e).n()
                            + " again, the same SEARCH and REPLACE text.", "Each block is applied once, in the order "
                            + "given."));
                }
            }
            String where = b == 0 ? "the code" : "the code as the blocks before it left it";
            List<Integer> places = places(lines, block.search());
            if (places.isEmpty()) {
                return Applied.refused(key, Refusal.of("missing\0" + joined, its + " was not applied: its SEARCH text "
                        + "is not in " + where + ".", whyNotThere(block, lines, b == 0 ? null : first)));
            }
            if (places.size() > 1) {
                return Applied.refused(key, Refusal.of("several\0" + joined, its + " was not applied: its SEARCH text "
                        + "stands at " + places.size() + " places in " + where + ".", "Add the lines before or after it "
                        + "to SEARCH, and to REPLACE, until it matches exactly one place."));
            }
            // What it changes: the lines between those it repeats above and below.
            int size = block.search().size(), above = 0, below = 0;
            int most = Math.min(size, block.replace().size());
            while (above < most && block.search().get(above).equals(block.replace().get(above))) above++;
            while (below < most - above && block.search().get(size - 1 - below)
                    .equals(block.replace().get(block.replace().size() - 1 - below))) below++;
            List<String> changed = block.search().subList(above, size - below);
            List<String> change = block.replace().subList(above, block.replace().size() - below);
            int at = places.getFirst() + above;
            lines = spliced(lines, at, changed.size(), change);
            if (own != null) own = spliced(own, at, changed.size(), change);
            replaced += String.join("\n", changed).length();
            written += String.join("\n", change).length();
        }
        return new Applied(String.join("\n", own != null ? own : lines), String.join("\n", lines), key, blocks.size(),
                replaced, written, own != null ? kept.length() : shown.length(), null);
    }

    /** {@code lines} with the {@code count} lines from {@code at} on replaced by {@code change}. */
    private static List<String> spliced(List<String> lines, int at, int count, List<String> change) {
        var next = new ArrayList<String>(lines.size() - count + change.size());
        next.addAll(lines.subList(0, at));
        next.addAll(change);
        next.addAll(lines.subList(at + count, lines.size()));
        return next;
    }

    /** Where {@code search} stands in {@code lines} as a run of whole lines: the first line of each place. */
    private static List<Integer> places(List<String> lines, List<String> search) {
        var out = new ArrayList<Integer>();
        for (int i = 0; i + search.size() <= lines.size(); i++) {
            if (lines.subList(i, i + search.size()).equals(search)) out.add(i);
        }
        return out;
    }

    /**
     * What can be said of lines that are not in the code, so the next reply can be another: that
     * they stood in the code as shown and a block before changed the place; that they are lines of
     * it but for their indentation or the blanks at their ends, and which; that they stand there
     * only inside a line; or which of them is no line of the code at all.
     *
     * @param shown the code as it was shown, when blocks before this one have changed it; else null
     */
    private static String whyNotThere(Block block, List<String> lines, List<String> shown) {
        List<String> search = block.search();
        if (shown != null && !places(shown, search).isEmpty()) {
            return "It stood in the code as shown, and a block before it changed that place: each block is applied "
                    + "to the code as the blocks before it left it.";
        }
        List<String> code = shown != null ? shown : lines;
        List<String> stripped = search.stream().map(String::strip).toList();
        List<Integer> loose = places(code.stream().map(String::strip).toList(), stripped);
        if (loose.size() == 1) {
            int from = loose.getFirst() + 1, to = loose.getFirst() + search.size();
            return (from == to ? "Line " + from : "Lines " + from + " to " + to) + " of the code as shown "
                    + (from == to ? "is this line" : "are these lines") + " but for the indentation or the blanks at "
                    + "a line's end: copy " + (from == to ? "it" : "them") + " character for character.";
        }
        if (String.join("\n", lines).contains(String.join("\n", search))) {
            return "It stands there only inside a line: SEARCH is whole lines, each from its first character -- its "
                    + "indentation -- to its last.";
        }
        for (int k = 0; k < search.size(); k++) {
            if (!search.get(k).isBlank() && !lines.contains(search.get(k))) {
                return "Line " + (k + 1) + " of its SEARCH text is no line of the code: copy the lines from the code "
                        + "above, character for character.";
            }
        }
        return "Each of its lines is in the code, but not together in this order: copy the lines as they stand in "
                + "the code above.";
    }

    // ── a syntax error in the result ──

    /**
     * The lines of {@code code} -- what a reply's blocks gave, as the model was shown the code
     * ({@link Applied#asShown}) -- around its line {@code line}, each under its number in the
     * result: a syntax error there is told by the result's line numbers, which are those of no
     * text the model has seen. Never the lines of the skill's own copy: one the filter changes on
     * the way out is one the cloud has not read, and a value inside a longer word in it would go
     * as it stands in a text the gateway filters as prose. A line is shown only when
     * {@code sentAsItIs} -- the gateway's filter would send it unchanged and learn nothing from
     * it; one that would not is named by its number alone. Empty when the code has no such line.
     */
    static String around(String code, int line, Predicate<String> sentAsItIs) {
        String[] lines = code.split("\n", -1);
        if (line < 1 || line > lines.length) return "";
        var sb = new StringBuilder();
        for (int n = Math.max(1, line - AROUND); n <= Math.min(lines.length, line + AROUND); n++) {
            String text = lines[n - 1];
            sb.append(n).append(": ").append(text.isBlank() ? "" : sentAsItIs.test(text) ? text
                    : "(a line that names a value: not repeated here)").append('\n');
        }
        return sb.toString().stripTrailing();
    }
}
