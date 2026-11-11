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
 * The text a part is joined to is exactly the text the model was shown and goes on from: a part
 * cut off is kept, and sent back as the model's turn, without the whitespace it ends with, so the
 * model writes the line break and a line's whole indentation itself -- a cut inside a line's
 * indentation, kept as it fell, was joined as a line begun again and moved the line out of its
 * block. The model is asked to go on from the exact character where its turn stops, and mostly
 * does; read first, a seam is that, except where the part is plainly the model starting again:
 * <ul>
 *   <li>The fence opened again. Inside an open ```python fence, a part whose first line that is
 *   not blank is ```python (any case) loses that line and the blank lines before it -- and keeps
 *   a line break where the text so far ends on the fence's own line. A bare ``` is kept as the
 *   model wrote it: it is mostly the model closing the fence, and dropped, it put the prose after
 *   it into the code.</li>
 *   <li>Written again. When the part begins with the end of the text so far, from the beginning of
 *   one of its lines or from after that line's indentation -- the cut line written again, or the
 *   model gone back a line or more -- that much of the part is dropped: the longest such run. A
 *   part that begins with a line break writes nothing again -- it is the model going on after a
 *   line's end, the line break its own -- so of two equal lines in a row an exact continuation
 *   keeps both. A last line that is one or two backticks or quotes is the beginning of a fence or a
 *   triple quote, and is written again only when the part writes all three.</li>
 *   <li>Otherwise the part is the characters that come next, and is appended as it is.</li>
 * </ul>
 * When the code joined so fails its checks, the other readings of each seam are tried before a
 * repair is asked for ({@link #readings}): the part appended as written; the cut line alone begun
 * again; begun again from an empty line above; a lead-in before the fence opened again dropped; a
 * bare ``` taken for the fence opened again. Whatever no reading mends is the syntax check's to
 * find and the repairs' to mend.
 * <p>
 * In time linear in the two texts, either of which can be hundreds of thousands of characters:
 * the longest run is found with the prefix function of the part, never by trying length after
 * length.
 */
final class ReplySeam {

    private ReplySeam() {}

    /**
     * The text so far with {@code part} joined to it, read first.
     *
     * @param sofar the reply so far: every part before this one, joined
     * @param part  the next part, as the model wrote it
     */
    static String join(String sofar, String part) {
        if (part == null || part.isEmpty()) return sofar == null ? "" : sofar;
        if (sofar == null || sofar.isEmpty()) return part;
        String next = inPythonFence(sofar) ? afterReopenedFence(sofar, part) : part;
        return sofar + next.substring(restarted(sofar, next));
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

    private static boolean isPythonFence(String strippedLine) {
        return strippedLine.equalsIgnoreCase("```python");
    }

    /**
     * {@code part} without the ```python line it begins with, after blank lines, and without those
     * -- the fence opened again -- or {@code part} as it is.
     */
    static String afterReopenedFence(String sofar, String part) {
        int[] line = firstLineNotBlank(part);
        if (line == null || !isPythonFence(part.substring(line[0], line[1]).strip())) return part;
        return withLineBreak(sofar, part.substring(line[2]));
    }

    /**
     * {@code rest}, with a line break before it where the text so far ends on the ```python line
     * itself and {@code rest} does not begin with one: the code cannot stand on the fence's line.
     * Joined there without it, "```pythonimport json" left the code's extraction to begin at
     * {@code def run}, and the imports were gone.
     */
    private static String withLineBreak(String sofar, String rest) {
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
        int m = Math.min(sofar.length(), part.length());
        if (m == 0) return 0;
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
        for (int k = matched; k > 0; k = fail[k - 1]) {
            if (indented[m - k]) return k;
        }
        return 0;
    }

    /**
     * Another reading of a reply's parts: the reply joined with one seam read otherwise.
     *
     * @param part the part whose seam with the text before it is read otherwise: 2 for the first seam
     * @param how  how it is read, for the log
     * @param text the reply so read, every other seam read first
     */
    record Reading(int part, String how, String text) {}

    /**
     * The other readings of a reply written in {@code parts}, one seam at a time, in order: the
     * seams from the first on, and at each the part appended as written; the cut line alone begun
     * again; begun again from an empty line above, or from wherever the longest run begins;
     * inside an open ```python fence, everything through the part's first ```python line dropped
     * -- a lead-in before the fence opened again -- and a bare ``` taken for the fence opened again. Each differs from the reply read first, and from every reading before it. For
     * code that fails its checks read first: one that passes them is kept before a repair is asked
     * for -- a check of the code here, where a repair of a long skill is another generation in
     * parts.
     *
     * @param parts the parts as the model wrote them, each cut-off one without its trailing whitespace
     */
    static List<Reading> readings(List<String> parts) {
        int n = parts.size();
        var out = new ArrayList<Reading>();
        if (n < 2) return out;
        // The reply read first, up to each part.
        String[] before = new String[n];
        before[1] = parts.get(0);
        for (int i = 2; i < n; i++) before[i] = join(before[i - 1], parts.get(i - 1));
        var seen = new HashSet<String>();
        seen.add(join(before[n - 1], parts.get(n - 1)));
        for (int i = 1; i < n; i++) {
            for (var other : otherReadings(before[i], parts.get(i)).entrySet()) {
                String text = other.getKey();
                for (int j = i + 1; j < n; j++) text = join(text, parts.get(j));
                if (seen.add(text)) out.add(new Reading(i + 1, other.getValue(), text));
            }
        }
        return out;
    }

    /** The other readings of one seam, in order: the text so far with {@code part} joined so, and how. */
    private static LinkedHashMap<String, String> otherReadings(String sofar, String part) {
        var out = new LinkedHashMap<String, String>();
        out.putIfAbsent(sofar + part, "appended as written");
        boolean fenced = inPythonFence(sofar);
        String next = fenced ? afterReopenedFence(sofar, part) : part;
        int line = lastLineAgain(sofar, next);
        if (line > 0) out.putIfAbsent(sofar + next.substring(line), "the cut line begun again");
        int higher = restartedFromHigher(sofar, next);
        if (higher > line) out.putIfAbsent(sofar + next.substring(higher), "begun again from a line above");
        if (fenced) {
            String afterLeadIn = afterFirstPythonFence(sofar, part);
            if (afterLeadIn != null) {
                out.putIfAbsent(sofar + afterLeadIn.substring(restarted(sofar, afterLeadIn)),
                        "the lead-in before the fence opened again dropped");
            }
            int[] first = firstLineNotBlank(part);
            if (first != null && part.substring(first[0], first[1]).strip().equals("```")) {
                String rest = withLineBreak(sofar, part.substring(first[2]));
                out.putIfAbsent(sofar + rest.substring(restarted(sofar, rest)),
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
