package com.ownclaw.agent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Where a reply cut off at the model's maximum output meets its continuation. A skill's code that
 * does not fit in one reply is written in parts, each the model's continuation of the one before
 * ({@code AgentLoop#generateSkillCode}), and joined here, one after the other.
 * <p>
 * A part cut off is kept in whole lines ({@link #wholeLines}): through its last line break, the
 * line it was cut in dropped, and sent back as the model's turn so -- without the whitespace it
 * ends with. The model is shown the end of a line, never a line cut through, and writes the line
 * it was cut in again, whole, from its first character: a cut inside a word or an indentation no
 * longer has to be read at all. Read through the middle of a line, "    s" continued with
 * "sh = ..." and with "ssh = ..." are the same bytes, and the seam joined one of them wrong. A part
 * that holds no whole line -- a single line longer than the model's whole output -- is kept whole,
 * and that seam falls inside its line.
 * <p>
 * The text a part is joined to is exactly the text the model was shown, with the line break the
 * model wrote after it where the part was kept in whole lines. The model is asked to go on from
 * the exact character where its turn stops, and mostly does; read first, a seam is that, except
 * where the part is plainly the model starting again:
 * <ul>
 *   <li>The fence opened again. Inside an open ```python fence, a part whose first line that is
 *   not blank is ```python, ```py or ```python3 (any case), or a bare ``` followed by lines of
 *   code that a later bare ``` closes, loses that line and the blank lines before it -- and what
 *   follows begins a line of its own, unless it writes the cut line again. A bare ``` followed by
 *   prose or by another kind of fence is kept as the model wrote it: it is the model closing the
 *   fence, and dropped, it put the prose after it into the code.</li>
 *   <li>Written again. When the part begins with the end of the text so far, from the beginning of
 *   one of its lines or from after that line's indentation -- the cut line written again, or the
 *   model gone back a line or more -- that much of the part is dropped: the longest such run. At a
 *   seam between whole lines the run is whole lines too: what follows it in the part begins a line.
 *   A part that begins with a line break writes nothing again -- it is the model going on after a
 *   line's end, the line break its own -- so of two equal lines in a row an exact continuation
 *   keeps both. A last line that is one or two backticks or quotes is the beginning of a fence or a
 *   triple quote, and is written again only when the part writes all three.</li>
 *   <li>Otherwise the part is what comes next: on the next line at a seam between whole lines --
 *   the line break before it is the model's own, written after the line it was shown -- and on the
 *   cut line at a seam inside one.</li>
 * </ul>
 * When the code joined so fails its checks, the other readings of each seam are tried before a
 * repair is asked for ({@link #readings}): the part appended as written; the cut line alone begun
 * again; begun again from an empty line above; a lead-in before the fence opened again dropped; a
 * bare ``` taken for the fence opened again. Whatever no reading mends is the syntax check's to
 * find and the repairs' to mend. No reading -- the first neither -- whose code ends at a seam while
 * more code follows it is taken ({@link #dropsCode}): its code compiles, and is the skill without
 * everything after the seam.
 * <p>
 * In time linear in the two texts, either of which can be hundreds of thousands of characters:
 * the longest run is found with the prefix function of the part, never by trying length after
 * length.
 */
final class ReplySeam {

    private ReplySeam() {}

    /**
     * A part cut off at the maximum output, as it is kept and joined: through its last line break
     * -- the line it was cut in dropped, the model writes it again whole -- without the whitespace
     * before that line break, and with the line break itself, as the model wrote it. A part that
     * holds no line with text before its last line break is kept whole, without the whitespace it
     * ends with. Sent back as the model's turn, either is without its trailing whitespace
     * ({@link String#stripTrailing}): exactly the beginning of the reply the model wrote, up to
     * the end of a line ({@code EgressContext.HandedOut}).
     */
    static String wholeLines(String text) {
        int last = text.lastIndexOf('\n');
        if (last >= 0) {
            String lines = text.substring(0, last).stripTrailing();
            if (!lines.isEmpty()) return lines + (last > 0 && text.charAt(last - 1) == '\r' ? "\r\n" : "\n");
        }
        return text.stripTrailing();
    }

    /** Whether {@code sofar} ends with a line break: the seam after it is between whole lines. */
    static boolean endsAtALine(String sofar) {
        return sofar.endsWith("\n");
    }

    /** {@code sofar} without the line break it ends with: the text the model was shown. */
    private static String shownOf(String sofar) {
        int cut = sofar.endsWith("\r\n") ? 2 : 1;
        return sofar.substring(0, sofar.length() - cut);
    }

    /** Whether {@code text} begins with the end of a line: blanks, then a line break. */
    static boolean beginsWithALineEnd(String text) {
        return beginsWithALineEnd(text, 0);
    }

    /** Whether {@code text} from {@code from} on begins with the end of a line: blanks, then a line break. */
    private static boolean beginsWithALineEnd(String text, int from) {
        int i = from;
        while (i < text.length() && blank(text.charAt(i))) i++;
        return i < text.length() && (text.charAt(i) == '\n' || text.startsWith("\r\n", i));
    }

    /**
     * The text so far with {@code part} joined to it, read first.
     *
     * @param sofar the reply so far: every part before this one, joined -- ending with a line break
     *              where the last of them was kept in whole lines ({@link #wholeLines})
     * @param part  the next part, as the model wrote it (or, cut off too, as it is kept)
     */
    static String join(String sofar, String part) {
        if (part == null || part.isEmpty()) return sofar == null ? "" : sofar;
        if (sofar == null || sofar.isEmpty()) return part;
        String reopened = inPythonFence(sofar) ? afterReopenedFence(sofar, part) : null;
        return reopened == null ? joinedAfter(sofar, part, false) : joinedAfter(sofar, reopened, true);
    }

    /**
     * The text so far with {@code next} -- a part, or what follows its fence opened again --
     * joined to it, read first. After a fence opened again, at a seam inside a line, what follows
     * begins a line of its own unless it writes the cut line again: the model that opens a code
     * block anew writes whole lines in it. Joined to the cut line, the line after a comment the
     * model was cut after went into the comment, and the code compiled without it.
     */
    private static String joinedAfter(String sofar, String next, boolean reopened) {
        if (endsAtALine(sofar)) {
            String shown = shownOf(sofar);
            // The line break the model writes after the line it was shown, and anything after it.
            if (beginsWithALineEnd(next)) return shown + next;
            int k = restartedWhole(shown, next);
            if (k > 0) return shown + next.substring(k);
            return sofar + next;
        }
        int k = restarted(sofar, next);
        String rest = next.substring(k);
        if (reopened && k == 0 && !rest.isEmpty() && !rest.startsWith("\n") && !rest.startsWith("\r\n")) {
            rest = (sofar.contains("\r\n") ? "\r\n" : "\n") + rest;
        }
        return sofar + rest;
    }

    /**
     * Whether {@code text} ends inside a ```python fence: its last fence line -- one that begins,
     * after any blanks, with ``` -- opens Python code. A line of the code's own that begins with
     * ``` -- Markdown in a string -- is taken for a fence, as the code's extraction takes it too
     * ({@code AgentLoop#extractPythonCode}).
     */
    static boolean inPythonFence(String text) {
        int end = text.length();
        while (true) {
            int start = text.lastIndexOf('\n', end - 1) + 1;
            String line = text.substring(start, end).strip();
            if (line.startsWith("```")) return isPythonFence(line);
            if (start == 0) return false;
            end = start - 1;
        }
    }

    /** A fence line that opens Python code: ```python, ```py or ```python3, in any case. */
    static boolean isPythonFence(String strippedLine) {
        if (!strippedLine.startsWith("```")) return false;
        return isPythonInfo(strippedLine.substring(3).strip());
    }

    private static boolean isPythonInfo(String info) {
        return info.equalsIgnoreCase("python") || info.equalsIgnoreCase("py") || info.equalsIgnoreCase("python3");
    }

    /**
     * What follows the fence {@code part} opens again, inside an open ```python fence -- the part
     * after its first line that is not blank, that line a ```python, ```py or ```python3 line, or a
     * bare ``` with code after it that a later bare ``` closes -- or null when it opens none. A bare
     * ``` with nothing after it but prose or another kind of fence is the model closing the fence,
     * and stays.
     */
    static String afterReopenedFence(String sofar, String part) {
        int[] line = firstLineNotBlank(part);
        if (line == null) return null;
        String first = part.substring(line[0], line[1]).strip();
        if (!isPythonFence(first) && !(first.equals("```") && closedBlockAfter(part, line[2]))) return null;
        return withLineBreak(sofar, part.substring(line[2]));
    }

    /**
     * Whether {@code text} from {@code from} on holds lines that are not blank and then a bare ```
     * line: a block of code closed, the fence before it a bare one.
     */
    private static boolean closedBlockAfter(String text, int from) {
        boolean code = false;
        int start = from;
        while (start < text.length()) {
            int newline = text.indexOf('\n', start);
            int end = newline < 0 ? text.length() : newline;
            String line = text.substring(start, end).strip();
            if (line.startsWith("```")) return code && line.equals("```");
            code |= !line.isEmpty();
            if (newline < 0) return false;
            start = newline + 1;
        }
        return false;
    }

    /** Whether the first line of {@code part} that is not blank is a fence line, of any kind. */
    private static boolean opensWithAFence(String part) {
        int[] line = firstLineNotBlank(part);
        return line != null && part.substring(line[0], line[1]).strip().startsWith("```");
    }

    /**
     * Whether the code extracted from {@code text} ({@code AgentLoop#extractPythonCode}) ends at
     * or before one of its {@code seams} while more code follows it: a ```python, ```py or
     * ```python3 block, or a bare-fenced block of lines that a bare ``` closes. Such a reading
     * holds the code only up to a seam -- the parts after it dropped, a fence opened again read as
     * the code's end -- and no check of the code can tell: what it holds compiles. One reply
     * with a second block of code after the skill's, in one part, is no such reading.
     */
    static boolean dropsCode(String text, int[] seams) {
        if (seams.length == 0) return false;
        String code = AgentLoop.extractPythonCode(text);
        if (code == null) return false;
        int at = text.indexOf(code);
        if (at < 0) return false;
        int end = at + code.length();
        int last = 0;
        for (int seam : seams) last = Math.max(last, seam);
        return end <= last && codeFollows(text, end);
    }

    /** Whether {@code text} after {@code from} holds a block of code: see {@link #dropsCode}. */
    private static boolean codeFollows(String text, int from) {
        int start = text.indexOf('\n', from);
        while (start >= 0 && start < text.length()) {
            start++;
            int newline = text.indexOf('\n', start);
            int end = newline < 0 ? text.length() : newline;
            String line = text.substring(start, end).strip();
            if (line.startsWith("```")) {
                if (isPythonFence(line)) return true;
                if (line.equals("```") && closedBlockAfter(text, newline < 0 ? end : newline + 1)) return true;
            }
            start = newline;
        }
        return false;
    }

    /**
     * {@code rest}, with a line break before it where the text so far ends on the ```python line
     * itself and {@code rest} does not begin with one: the code cannot stand on the fence's line.
     * Joined there without it, "```pythonimport json" left the code's extraction to begin at
     * {@code def run}, and the imports were gone.
     */
    private static String withLineBreak(String sofar, String rest) {
        if (endsAtALine(sofar)) return rest;
        String last = sofar.substring(sofar.lastIndexOf('\n') + 1).strip();
        boolean breaks = rest.startsWith("\n") || rest.startsWith("\r\n");
        return isPythonFence(last) && !rest.isEmpty() && !breaks ? "\n" + rest : rest;
    }

    /** The first line of {@code text} that is not blank: {start, end, where the next begins}, or null. */
    private static int[] firstLineNotBlank(String text) {
        int start = 0;
        while (start <= text.length()) {
            int newline = text.indexOf('\n', start);
            int end = newline < 0 ? text.length() : newline;
            if (!text.substring(start, end).isBlank()) return new int[] {start, end, newline < 0 ? end : newline + 1};
            if (newline < 0) return null;
            start = newline + 1;
        }
        return null;
    }

    /**
     * How many characters at the beginning of {@code part} are the text so far written again, read
     * first: the longest run that ends the text so far, begins at the beginning of one of its lines or
     * after that line's indentation, and begins the part -- the cut line written again, or the model
     * gone back to a line above it -- or 0. A part that begins with a line break writes nothing again:
     * the text so far ends without its trailing whitespace, so a model going on from the exact
     * character after a line's end writes that line break first, and of two equal lines in a row its
     * second is its next line, not the first one again. (Read without that, a run from an empty line
     * above took the second of two {@code lines.append('')} for the first written again, and the code
     * lost a line and still compiled.) A last line of one or two backticks or quotes is the beginning
     * of a fence or of a triple quote: the part writes it again only when it writes all three of them
     * there, and otherwise goes on from it -- "``requirements" after "`" is the fence's rest.
     */
    static int restarted(String sofar, String part) {
        if (part.startsWith("\n") || part.startsWith("\r\n")) return 0;
        int k = restartedFromHigher(sofar, part);
        int lineStart = sofar.lastIndexOf('\n') + 1;
        if (k == 0 || k > sofar.length() - lineStart) return k;
        int indent = lineStart;
        while (indent < sofar.length() && blank(sofar.charAt(indent))) indent++;
        String content = sofar.substring(indent);
        if (startOfARun(content)) {
            char c = content.charAt(0);
            int at = Math.max(0, k - content.length());
            int run = 0;
            while (at + run < part.length() && part.charAt(at + run) == c) run++;
            if (run < 3) return 0;
        }
        return k;
    }

    /**
     * {@link #restartedFromHigher} at a seam between whole lines: the longest run of the part that
     * is an end of {@code shown} from the beginning of one of its lines or after its indentation,
     * and that ends where a line of the part ends -- what follows it in the part begins with a line
     * break, or there is nothing after it -- or 0. A line the model was shown whole is written again
     * whole; a part that begins as the last line does and goes on along it is the next line.
     */
    static int restartedWhole(String shown, String part) {
        for (int k : runs(shown, part)) {
            if (k == part.length() || beginsWithALineEnd(part, k)) return k;
        }
        return 0;
    }

    /**
     * How many characters at the beginning of {@code part} are the text so far's last line written
     * again, from its beginning or from after its indentation, or 0: the cut line begun again and
     * nothing more, one of the other readings of a seam ({@link #readings}).
     */
    static int lastLineAgain(String sofar, String part) {
        int lineStart = sofar.lastIndexOf('\n') + 1;
        int indent = lineStart;
        while (indent < sofar.length() && blank(sofar.charAt(indent))) indent++;
        String line = sofar.substring(lineStart);
        String content = sofar.substring(indent);
        if (!line.isEmpty() && part.startsWith(line)) return line.length();
        if (!content.isEmpty() && part.startsWith(content)) return content.length();
        return 0;
    }

    /** One or two backticks, or one or two of the same quote: where a fence or a triple quote begins. */
    private static boolean startOfARun(String content) {
        if (content.isEmpty() || content.length() > 2) return false;
        char c = content.charAt(0);
        return (c == '`' || c == '"' || c == '\'') && content.chars().allMatch(ch -> ch == c);
    }

    /**
     * How many characters at the beginning of {@code part} are the end of {@code sofar} written
     * again, from the beginning of any of its lines -- an empty one too -- or from after that line's
     * indentation: the longest such run, or 0. Read first without a run that begins at an empty
     * line ({@link #restarted}); with one, it is among the other readings of a seam ({@link #readings}).
     * <p>
     * The prefix function of the part's first characters, run over as many of the last characters
     * of the text so far -- Knuth, Morris and Pratt's matcher -- ends on the longest beginning of
     * the part that is an end of the text so far; every shorter one is on that one's failure chain,
     * longest first. The run is the first on the chain that begins where only blanks stand before
     * it on its line.
     */
    static int restartedFromHigher(String sofar, String part) {
        int[] runs = runs(sofar, part);
        return runs.length == 0 ? 0 : runs[0];
    }

    /**
     * Every run {@link #restartedFromHigher} could take, longest first: each length k such that
     * the part's first k characters are the last k of {@code sofar}, beginning at the beginning of
     * one of its lines or after only blanks on it.
     */
    private static int[] runs(String sofar, String part) {
        int m = Math.min(sofar.length(), part.length());
        if (m == 0) return new int[0];
        // fail[i]: the longest beginning of part[0..i] that is also an end of it, itself excepted.
        int[] fail = new int[m];
        for (int i = 1, k = 0; i < m; i++) {
            while (k > 0 && part.charAt(i) != part.charAt(k)) k = fail[k - 1];
            if (part.charAt(i) == part.charAt(k)) k++;
            fail[i] = k;
        }
        int from = sofar.length() - m;
        int matched = 0;
        for (int i = from; i < sofar.length(); i++) {
            char c = sofar.charAt(i);
            while (matched > 0 && (matched == m || part.charAt(matched) != c)) matched = fail[matched - 1];
            if (part.charAt(matched) == c) matched++;
        }
        // For each of those last characters, whether only blanks stand before it on its line: the
        // beginning of a line, or of what follows its indentation. A line can begin before them.
        boolean[] indented = new boolean[m];
        boolean blanksOnly = true;
        for (int i = sofar.lastIndexOf('\n', from - 1) + 1; i < from; i++) {
            blanksOnly &= blank(sofar.charAt(i));
        }
        for (int i = from; i < sofar.length(); i++) {
            indented[i - from] = blanksOnly;
            char c = sofar.charAt(i);
            blanksOnly = c == '\n' || (blanksOnly && blank(c));
        }
        var out = new java.util.ArrayList<Integer>();
        for (int k = matched; k > 0; k = fail[k - 1]) {
            if (indented[m - k]) out.add(k);
        }
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    /**
     * A reading of a reply's parts: the reply joined with one seam read otherwise, or -- the
     * first, {@link #first} -- with every seam read first.
     *
     * @param part  the part whose seam with the text before it is read otherwise: 2 for the first
     *              seam; 0 for the reply read first
     * @param how   how it is read, for the log
     * @param text  the reply so read, every other seam read first
     * @param seams where in {@code text} each part after the first begins, in order
     */
    record Reading(int part, String how, String text, int[] seams) {
        /** Whether this reading holds the code only up to a seam while more code follows ({@link #dropsCode}). */
        boolean dropsCode() {
            return ReplySeam.dropsCode(text, seams);
        }
    }

    /** The reply written in {@code parts}, every seam read first ({@link #join}), and where its seams are. */
    static Reading first(List<String> parts) {
        var seams = new ArrayList<Integer>();
        String text = joinFrom(parts.get(0), parts, 1, seams);
        return new Reading(0, "joined", text, toArray(seams));
    }

    /** {@code text} with {@code parts} from {@code from} on joined to it, each seam's place added to {@code seams}. */
    private static String joinFrom(String text, List<String> parts, int from, List<Integer> seams) {
        for (int j = from; j < parts.size(); j++) {
            String joined = join(text, parts.get(j));
            seams.add(seamOf(text, joined));
            text = joined;
        }
        return text;
    }

    /** Where {@code joined} -- {@code sofar} with a part joined to it -- leaves the text so far. */
    private static int seamOf(String sofar, String joined) {
        int n = Math.min(sofar.length(), joined.length());
        int i = 0;
        while (i < n && sofar.charAt(i) == joined.charAt(i)) i++;
        return i;
    }

    private static int[] toArray(List<Integer> list) {
        return list.stream().mapToInt(Integer::intValue).toArray();
    }

    /**
     * The other readings of a reply written in {@code parts}, one seam at a time, in order: the
     * seams from the first on, and at each the part appended as written -- unless it begins with
     * a fence line, which appended would close the code at the seam; the cut line alone begun
     * again; begun again from an empty line above, or from wherever the longest run begins; inside
     * an open ```python fence, everything through the part's first ```python line dropped -- a
     * lead-in before the fence opened again -- a bare ``` taken for the fence opened again, and,
     * at a seam inside a line, what follows a fence opened again joined to the cut line. Each
     * differs from the reply read first, and from every reading before it. For code that fails its
     * checks read first: one that passes them is kept before a repair is asked for -- a check of
     * the code here, where a repair of a long skill is another generation in parts.
     *
     * @param parts the parts as the model wrote them, each cut-off one as it is kept ({@link #wholeLines})
     */
    static List<Reading> readings(List<String> parts) {
        int n = parts.size();
        var out = new ArrayList<Reading>();
        if (n < 2) return out;
        // The reply read first, up to each part, and its seams so far.
        String[] before = new String[n];
        var seamsBefore = new ArrayList<List<Integer>>();
        before[1] = parts.get(0);
        seamsBefore.add(List.of());
        seamsBefore.add(List.of());
        for (int i = 2; i < n; i++) {
            var seams = new ArrayList<>(seamsBefore.get(i - 1));
            before[i] = joinFrom(before[i - 1], parts.subList(0, i), i - 1, seams);
            seamsBefore.add(seams);
        }
        var seen = new HashSet<String>();
        seen.add(first(parts).text());
        for (int i = 1; i < n; i++) {
            for (var other : otherReadings(before[i], parts.get(i)).entrySet()) {
                var seams = new ArrayList<>(seamsBefore.get(i));
                seams.add(seamOf(before[i], other.getKey()));
                String text = joinFrom(other.getKey(), parts, i + 1, seams);
                if (seen.add(text)) out.add(new Reading(i + 1, other.getValue(), text, toArray(seams)));
            }
        }
        return out;
    }

    /** The other readings of one seam, in order: the text so far with {@code part} joined so, and how. */
    private static LinkedHashMap<String, String> otherReadings(String sofar, String part) {
        var out = new LinkedHashMap<String, String>();
        if (!opensWithAFence(part)) out.putIfAbsent(sofar + part, "appended as written");
        boolean fenced = inPythonFence(sofar);
        String reopened = fenced ? afterReopenedFence(sofar, part) : null;
        String next = reopened != null ? reopened : part;
        // At a seam between whole lines, a run is taken from the end of the line the model was shown.
        String base = endsAtALine(sofar) ? shownOf(sofar) : sofar;
        int line = lastLineAgain(base, next);
        if (line > 0) out.putIfAbsent(base + next.substring(line), "the cut line begun again");
        int higher = restartedFromHigher(base, next);
        if (higher > line) out.putIfAbsent(base + next.substring(higher), "begun again from a line above");
        if (reopened != null && !endsAtALine(sofar)) {
            out.putIfAbsent(sofar + reopened, "the fence opened again dropped, what follows on the cut line");
        }
        if (fenced) {
            String afterLeadIn = afterFirstPythonFence(sofar, part);
            if (afterLeadIn != null) {
                out.putIfAbsent(joinedAfter(sofar, afterLeadIn, true), "the lead-in before the fence opened again dropped");
            }
            int[] first = firstLineNotBlank(part);
            if (first != null && part.substring(first[0], first[1]).strip().equals("```")) {
                out.putIfAbsent(joinedAfter(sofar, withLineBreak(sofar, part.substring(first[2])), true),
                        "a bare fence taken as the fence opened again");
            }
        }
        out.remove(join(sofar, part));
        return out;
    }

    /** {@code part} after its first ```python line, wherever that stands -- a line break kept -- or null. */
    private static String afterFirstPythonFence(String sofar, String part) {
        int start = 0;
        while (start < part.length()) {
            int newline = part.indexOf('\n', start);
            int end = newline < 0 ? part.length() : newline;
            if (isPythonFence(part.substring(start, end).strip())) {
                return withLineBreak(sofar, newline < 0 ? "" : part.substring(newline + 1));
            }
            if (newline < 0) return null;
            start = newline + 1;
        }
        return null;
    }

    private static boolean blank(char c) {
        return c == ' ' || c == '\t';
    }
}
