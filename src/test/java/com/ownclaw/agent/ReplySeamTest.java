package com.ownclaw.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Where a reply cut off at the model's maximum output meets its continuation: the parts of a
 * skill's code joined one after the other, the way a model goes on -- from the exact character of
 * what it was shown, or from the beginning of the line it was cut off in, or with its fence opened
 * again -- and the other readings of a seam, for code that fails its checks read first.
 */
class ReplySeamTest {

    static final String HEAD = "```python\nimport json\n\ndef run(params):\n";

    @Test
    @DisplayName("a continuation from the exact character is appended as it is: mid-word, mid-line, or with the line break the model was not shown")
    void theNextCharacters() {
        assertEquals(HEAD + "    r = requests.get(url)\n", ReplySeam.join(HEAD + "    r = requests.ge", "t(url)\n"));
        // The part's "b" is the end of the text so far, but not from the line's beginning or indentation.
        assertEquals(HEAD + "    y = bb + 1", ReplySeam.join(HEAD + "    y = b", "b + 1"));
        assertEquals(HEAD + "    total = 0\n    return total\n", ReplySeam.join(HEAD + "    total = 0", "\n    return total\n"),
                "the line break and the indentation, which the model writes itself");
        // After whole lines (it ends with their line break), the part's first line break ends the line it was shown.
        assertEquals(HEAD + "    return 1\n\ndef other():\n", ReplySeam.join(HEAD + "    return 1\n", "\n\ndef other():\n"));
        // Mutation: take any end of the text so far, wherever it begins -> "y = b + 1", the b lost.
    }

    @Test
    @DisplayName("a part cut off is kept in whole lines: through its last line break, without the blanks before it; one with no whole line is kept whole")
    void wholeLines() {
        assertEquals("a\n    b\n", ReplySeam.wholeLines("a\n    b\n    ss"));
        assertEquals("a\n    b\n", ReplySeam.wholeLines("a\n    b   \n\n  \n    c"), "the blank lines before the cut one go");
        assertEquals("a\r\n", ReplySeam.wholeLines("a\r\nb"), "the line break as the model wrote it");
        assertEquals("abc", ReplySeam.wholeLines("abc   "), "no line break: the part whole");
        assertEquals("\n\n  x", ReplySeam.wholeLines("\n\n  x"), "no line with text before the last line break: whole");
        assertEquals("", ReplySeam.wholeLines(" \n \n"));
        assertEquals("", ReplySeam.wholeLines(""));
    }

    @Test
    @DisplayName("at a seam between whole lines: the exact continuation, the next line without its line break, the cut line or lines above again -- each the code as written")
    void aSeamBetweenWholeLines() {
        String whole = HEAD + "    host = params.get('host')\n\n    ssh = ['ssh', host]\n    return {'output': '', 'success': True}\n";
        String cut = HEAD + "    host = params.get('host')\n\n    s";
        String kept = ReplySeam.wholeLines(cut);
        String shown = kept.stripTrailing();
        assertEquals(HEAD + "    host = params.get('host')", shown);
        assertEquals(whole, ReplySeam.join(kept, whole.substring(shown.length())), "exact: the blank line kept");
        String nextLine = "    ssh = ['ssh', host]\n    return {'output': '', 'success': True}\n";
        assertEquals(whole.replace("\n\n    ssh", "\n    ssh"), ReplySeam.join(kept, nextLine), "the next line, the line break the model's own");
        assertEquals(whole.replace("\n\n    ssh", "\n    ssh"), ReplySeam.join(kept, "    host = params.get('host')\n" + nextLine),
                "the line it was shown again");
        assertEquals(whole.replace("\n\n    ssh", "\n    ssh"), ReplySeam.join(kept, "def run(params):\n    host = params.get('host')\n" + nextLine),
                "from a line above");
        // A part that begins as the last line does but goes on along it is the next line, not that line again.
        String x = HEAD + "    x = 1\n";
        assertEquals(x + "    x = 10\n", ReplySeam.join(x, "    x = 10\n"));
        // Of two equal lines in a row, the exact continuation keeps both.
        String two = HEAD + "    lines.append('')\n";
        assertEquals(two + "    lines.append('')\n", ReplySeam.join(two, "\n    lines.append('')\n"));
        // Mutation: join at a whole-line seam as at one inside a line -> "    s" + "sh" read as a restart.
    }

    @Test
    @DisplayName("cut inside a line's indentation, the part kept without it and the model going on from what it was shown: the line at its own depth, at every depth of the cut")
    void cutInsideTheIndentation() {
        String block = HEAD + "    total = 0\n    for i in range(3):\n";
        String whole = block + "        total += i\n    return {'output': json.dumps(total), 'success': True}\n```\n";
        for (int blanks = 0; blanks <= 8; blanks++) {
            String kept = (block + " ".repeat(blanks)).stripTrailing();
            assertEquals(whole, ReplySeam.join(kept, whole.substring(kept.length())), "cut after " + blanks + " blanks");
        }
        // Mutation: keep the part as it was cut (its blanks) -> after 4 of 8 blanks the line is joined
        // at 4, out of the loop, and still compiles.
    }

    @Test
    @DisplayName("written again -- the cut line from its beginning or after its indentation, or from a line above -- it is there once")
    void writtenAgain() {
        String sofar = HEAD + "    total = 0\n    x = compute(";
        String whole = HEAD + "    total = 0\n    x = compute(a, b)\n";
        assertEquals(whole, ReplySeam.join(sofar, "x = compute(a, b)\n"), "after the indentation");
        assertEquals(whole, ReplySeam.join(sofar, "    x = compute(a, b)\n"), "from the line's beginning");
        assertEquals(HEAD + "\treturn x\n", ReplySeam.join(HEAD + "\tret", "return x\n"), "after a tab");
        assertEquals(whole, ReplySeam.join(sofar, "    total = 0\n    x = compute(a, b)\n"), "from a line above");
        String imports = "```python\nimport json\nimport os\ni";
        assertEquals("```python\nimport json\nimport os\nimport re\n", ReplySeam.join(imports, "import os\nimport re\n"),
                "from the line above, which begins as the cut line does: not the cut line again");
        // Mutation: only the cut line again -> "import os" twice, and it compiles.
    }

    @Test
    @DisplayName("of two equal lines in a row, an exact continuation keeps both, and so does the cut line written again")
    void twoEqualLinesInARow() {
        String sofar = HEAD + "    lines = []\n\n    lines.append('')";
        String rest = "\n    lines.append('')\n    return {'output': '\\n'.join(lines), 'success': True}\n```\n";
        assertEquals(sofar + rest, ReplySeam.join(sofar, rest), "exact: the second line is no restart");
        assertEquals(sofar + rest, ReplySeam.join(sofar, "    lines.append('')" + rest), "the cut line written again");
        assertEquals("\n    lines.append('')".length(), ReplySeam.restartedFromHigher(sofar, rest),
                "the run from the empty line above, which only another reading takes");
        // Mutation: a run from an empty line read first -> the exact continuation loses a line, and compiles.
    }

    @Test
    @DisplayName("a part that begins with a line break is the model going on after the line's end, even where it then writes the cut line again")
    void aLineBreakFirst() {
        String sofar = "```python\nimport time\n\nR";
        String part = "\nROUTERS = {}\n";
        assertEquals(sofar + part, ReplySeam.join(sofar, part),
                "the same bytes as an exact continuation after a line 'R': read as that");
        assertTrue(ReplySeam.readings(List.of(sofar, part)).stream()
                .anyMatch(r -> r.text().equals("```python\nimport time\n\nROUTERS = {}\n")),
                "and the cut line begun again after the line break is another reading, for when that fails its checks");
    }

    @Test
    @DisplayName("inside an open ```python fence, a ```python line the part opens with goes, blank lines and all -- the line break kept on the fence's own line")
    void aFenceOpenedAgain() {
        String sofar = HEAD + "    total = 0";
        String whole = HEAD + "    total = 0\n    return total\n";
        assertEquals(whole, ReplySeam.join(sofar, "```python\n\n    return total\n"));
        assertEquals(whole, ReplySeam.join(sofar, "\n\n```python\n\n    return total\n"), "after blank lines");
        assertEquals(whole, ReplySeam.join(sofar, "  \n```Python  \n\n    return total\n"));
        assertEquals(HEAD + "    x = compute(a)\n", ReplySeam.join(HEAD + "    x = compute(", "```python\n    x = compute(a)\n"),
                "and the line begun again after it");
        String opener = "Here is the skill:\n```python";
        assertEquals(opener + "\nimport json\n", ReplySeam.join(opener, "```python\nimport json\n"),
                "cut right after the opener: the code stays off the fence's line");
        assertEquals(opener + "\nimport json\n", ReplySeam.join(opener, "\n```python\nimport json\n"));
        assertEquals(opener + "\nimport json\n", ReplySeam.join(opener, "\nimport json\n"), "the exact continuation");
        // Mutation: no line break kept -> "```pythonimport json", and the imports are lost to the extraction.
    }

    @Test
    @DisplayName("a bare ``` the part begins with is kept as written: it is mostly the model closing the fence")
    void aBareFenceIsKept() {
        String sofar = HEAD + "    return {'output': json.dumps(1), 'success': True}";
        String closes = "\n```\n\nThe skill returns 1 as JSON.\n\n```requirements\nrequests\n```\n";
        assertEquals(sofar + closes, ReplySeam.join(sofar, closes));
        String atOnce = "```\n\n```requirements\nrequests\n```\n";
        assertEquals(sofar + atOnce, ReplySeam.join(sofar, atOnce));
        assertEquals(sofar + "```requirements\nrequests\n```\n", ReplySeam.join(sofar, "```requirements\nrequests\n```\n"),
                "a fence of another kind is the reply's own");
        // Mutation: drop a bare ``` too -> the note after the code lands in the code.
    }

    @Test
    @DisplayName("outside an open ```python fence, a fence the part opens is the reply's own, and stays")
    void aFenceOfItsOwn() {
        assertEquals("Here is the skill.\n```python\nimport json\n",
                ReplySeam.join("Here is the skill.", "\n```python\nimport json\n"), "before the code");
        String closed = HEAD + "    return 1\n```";
        assertEquals(closed + "\n\n```requirements\nrequests\n```\n", ReplySeam.join(closed, "\n\n```requirements\nrequests\n```\n"));
        assertTrue(ReplySeam.inPythonFence(HEAD + "    x = 1"));
        assertTrue(ReplySeam.inPythonFence("```python"), "cut right after the fence");
        assertFalse(ReplySeam.inPythonFence(closed));
        assertFalse(ReplySeam.inPythonFence("```pyth"), "half a fence is no Python fence");
        assertFalse(ReplySeam.inPythonFence(HEAD + "    return 1\n```\n\n```requirements\nreq"));
        assertFalse(ReplySeam.inPythonFence(""));
        // Mutation: drop a reopened fence outside the code too -> the code loses its own fence.
    }

    @Test
    @DisplayName("cut in the first backticks of a fence or quotes of a triple quote, the part is that run's rest -- unless it writes all three again")
    void fencesAndTripleQuotes() {
        String code = HEAD + "    return 1\n```\n\n";
        assertEquals(code + "```requirements\nrequests\n```\n", ReplySeam.join(code + "`", "``requirements\nrequests\n```\n"));
        assertEquals(code + "```requirements\nrequests\n```\n", ReplySeam.join(code + "``", "`requirements\nrequests\n```\n"));
        assertEquals(code + "```requirements\nrequests\n```\n", ReplySeam.join(code + "`", "```requirements\nrequests\n```\n"),
                "the fence written again");
        String doc = HEAD + "    \"\"\"Run one command.\"\"\"\n";
        assertEquals(doc, ReplySeam.join(HEAD + "    \"", "\"\"Run one command.\"\"\"\n"));
        assertEquals(doc, ReplySeam.join(HEAD + "    \"\"", "\"Run one command.\"\"\"\n"));
        assertEquals(doc, ReplySeam.join(HEAD + "    \"", "    \"\"\"Run one command.\"\"\"\n"), "the line written again");
        assertEquals(doc, ReplySeam.join(HEAD + "    \"", "\"\"\"Run one command.\"\"\"\n"), "after its indentation");
        // Mutation: no run rule -> "``requirements", and the requirements are lost without a word.
    }

    @Test
    @DisplayName("blanks are characters like any; a part that is only the cut line again, or only a fence, adds nothing")
    void blanksAndNothingNew() {
        String sofar = HEAD + "    x = compute(";
        assertEquals(sofar + "   \n", ReplySeam.join(sofar, "   \n"));
        assertEquals(sofar, ReplySeam.join(sofar, "x = compute("), "the cut line again, after its indentation");
        assertEquals(sofar, ReplySeam.join(sofar, "    x = compute("), "and from its beginning");
        assertEquals(sofar, ReplySeam.join(sofar, "```python\n"), "a fence opened again, and nothing else");
        assertEquals(sofar, ReplySeam.join(sofar, ""));
        assertEquals("import os\n", ReplySeam.join("", "import os\n"), "the first part is the text so far");
    }

    @Test
    @DisplayName("the other readings, one seam at a time: appended, the cut line alone again, begun again higher up, a lead-in before the fence dropped, a bare fence as reopened")
    void otherReadings() {
        String sofar = HEAD + "    total = 0\n    x = compute(";
        String higher = "    total = 0\n    x = compute(a, b)\n";
        var read = ReplySeam.readings(List.of(sofar, higher));
        assertEquals(List.of("appended as written"), read.stream().map(ReplySeam.Reading::how).toList(),
                "the run from a line above read first: appended is the other");
        assertEquals(sofar + higher, read.get(0).text());
        assertEquals(2, read.get(0).part());
        String twice = "```python\nimport json\nimport os\ni";
        assertTrue(ReplySeam.readings(List.of(twice, "import os\nimport re\n")).stream()
                .anyMatch(r -> r.how().equals("the cut line begun again")
                        && r.text().equals("```python\nimport json\nimport os\nimport os\nimport re\n")));

        String leadIn = "Continuing from where it stopped:\n\n```python\n    x = compute(a, b)\n";
        var lead = ReplySeam.readings(List.of(sofar, leadIn));
        assertTrue(lead.stream().anyMatch(r -> r.text().equals(HEAD + "    total = 0\n    x = compute(a, b)\n")
                && r.how().equals("the lead-in before the fence opened again dropped")), lead.toString());

        String bare = "```\n    x = compute(a, b)\n";
        var asOpened = ReplySeam.readings(List.of(sofar, bare));
        assertTrue(asOpened.stream().anyMatch(r -> r.text().equals(HEAD + "    total = 0\n    x = compute(a, b)\n")
                && r.how().equals("a bare fence taken as the fence opened again")), asOpened.toString());

        String restated = "x = compute(a, b)\n";
        var appended = ReplySeam.readings(List.of(sofar, restated));
        assertEquals(sofar + restated, appended.get(0).text(), "the restart read first, so appended is the first other");

        // One seam at a time, every other seam read first, and none read twice or as the first reading.
        var three = ReplySeam.readings(List.of(sofar, higher.stripTrailing(), "\n    return x\n"));
        assertEquals(2, three.get(0).part());
        String first = ReplySeam.join(ReplySeam.join(sofar, higher.stripTrailing()), "\n    return x\n");
        assertTrue(three.stream().noneMatch(r -> r.text().equals(first)));
        assertEquals(three.size(), three.stream().map(ReplySeam.Reading::text).distinct().count());
        assertEquals(List.of(), ReplySeam.readings(List.of(sofar)), "one part has no seam");
    }

    @Test
    @DisplayName("in linear time: a million characters each, every run on the failure chain to try, join at once")
    void inLinearTime() {
        String sofar = "a".repeat(1_000_000);
        String part = "a".repeat(999_999) + "b";
        int run = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> ReplySeam.restartedFromHigher(sofar, part));
        assertEquals(0, run, "runs in the middle of a line are no restart");
        String joined = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> ReplySeam.join(sofar, part));
        assertEquals(sofar.length() + part.length(), joined.length());

        String lines = "    x = 1\n".repeat(100_000);
        int whole = assertTimeoutPreemptively(Duration.ofSeconds(10),
                () -> ReplySeam.restartedFromHigher(lines.stripTrailing(), lines + "    y = 2\n"));
        assertEquals(lines.stripTrailing().length(), whole, "the whole text written again, from the top");
        String again = assertTimeoutPreemptively(Duration.ofSeconds(10),
                () -> ReplySeam.join(lines.stripTrailing(), lines + "    y = 2\n"));
        assertEquals(lines + "    y = 2\n", again, "the whole text written again, then a line more");
        var read = assertTimeoutPreemptively(Duration.ofSeconds(10),
                () -> ReplySeam.readings(List.of(lines.stripTrailing(), lines + "    y = 2\n")));
        assertTrue(read.stream().anyMatch(r -> r.how().equals("appended as written")));
        // Mutation: try each length from the longest with regionMatches -> about 10^12 comparisons
        // for the first pair, and the timeout.
    }
}
