package com.ownclaw.agent;

/**
 * Where a reply cut off at the model's maximum output meets its continuation. A skill's code that
 * does not fit in one reply is written in parts, each the model's continuation of the one before
 * ({@code AgentLoop#generateSkillCode}), and joined here, one after the other. The model is asked
 * to go on from the exact character where its reply stopped, and mostly does; this is the code
 * that makes the join right when it does not quite:
 * <ul>
 *   <li>A fence opened again. Cut off inside its ```python fence, the model can begin its
 *   continuation by opening the fence once more, as a reply of its own would. So, inside an open
 *   ```python fence, a continuation whose first line that is not blank is a fence opening code --
 *   ``` or ```python -- loses that line and the blank lines before it.</li>
 *   <li>A line begun again. The model can go back to the beginning of the line it was cut off in
 *   -- "x = compute(a, b)" after "    x = compute(" -- or of one a few lines higher. When the
 *   continuation begins with the end of the text so far, from the beginning of one of its lines
 *   or from after that line's indentation, that much of the continuation is the text so far
 *   written again: the longest such run is dropped.</li>
 *   <li>Otherwise the continuation is the characters that come next, and is appended as it is:
 *   "requests.ge" and "t(url)" are "requests.get(url)".</li>
 * </ul>
 * A seam that still breaks the code -- the model began again at the top of the file and wrote it
 * otherwise, say -- is the syntax check's to find and the repairs' to mend, as any broken code is.
 * <p>
 * In time linear in the two texts, either of which can be hundreds of thousands of characters:
 * the run is found with the prefix function of the continuation, never by trying length after
 * length.
 */
final class ReplySeam {

    private ReplySeam() {}

    /**
     * The text so far with {@code part} joined to it.
     *
     * @param sofar the reply so far: every part before this one, joined
     * @param part  the next part, as the model wrote it
     */
    static String join(String sofar, String part) {
        if (part == null || part.isEmpty()) return sofar == null ? "" : sofar;
        if (sofar == null || sofar.isEmpty()) return part;
        String next = inPythonFence(sofar) ? withoutOpeningFence(part) : part;
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
            if (line.startsWith("```")) return line.equalsIgnoreCase("```python");
            if (start == 0) return false;
            end = start - 1;
        }
    }

    /**
     * {@code part} without the fence it opens with: when its first line that is not blank is
     * ``` or ```python, what follows that line; otherwise {@code part} as it is.
     */
    static String withoutOpeningFence(String part) {
        int start = 0;
        while (true) {
            int newline = part.indexOf('\n', start);
            int end = newline < 0 ? part.length() : newline;
            String line = part.substring(start, end).strip();
            if (!line.isEmpty()) {
                if (!line.equals("```") && !line.equalsIgnoreCase("```python")) return part;
                return newline < 0 ? "" : part.substring(newline + 1);
            }
            if (newline < 0) return part;
            start = newline + 1;
        }
    }

    /**
     * How many characters at the beginning of {@code part} are the end of {@code sofar} written
     * again, from the beginning of one of its lines or from after that line's indentation: the
     * longest such run, or 0 when there is none.
     * <p>
     * The prefix function of the part's first characters, run over as many of the last characters
     * of the text so far -- Knuth, Morris and Pratt's matcher -- ends on the longest beginning of
     * the part that is an end of the text so far; every shorter one is on that one's failure chain,
     * longest first. The run is the first on the chain that begins where only blanks stand before
     * it on its line.
     */
    static int restarted(String sofar, String part) {
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

    private static boolean blank(char c) {
        return c == ' ' || c == '\t';
    }
}
