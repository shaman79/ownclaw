package com.ownclaw.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Where a reply cut off at the model's maximum output meets its continuation: the parts of a
 * skill's code joined one after the other, the way a model goes on -- from the exact character, or
 * from the beginning of the line it was cut off in, or with its fence opened again.
 */
class ReplySeamTest {

    static final String HEAD = "```python\nimport json\n\ndef run(params):\n";

    @Test
    @DisplayName("a continuation from the exact character is appended as it is, in the middle of a word too")
    void theNextCharacters() {
        assertEquals(HEAD + "    r = requests.get(url)\n", ReplySeam.join(HEAD + "    r = requests.ge", "t(url)\n"));
        // The continuation's "b" is the end of the text so far, but in the middle of its line: no restart.
        assertEquals(HEAD + "    y = bb + 1", ReplySeam.join(HEAD + "    y = b", "b + 1"));
        assertEquals(HEAD + "    return 1\n\ndef other():\n", ReplySeam.join(HEAD + "    return 1\n", "\ndef other():\n"));
        // Mutation: take any end of the text so far, wherever it begins -> "y = b + 1", the b lost.
    }

    @Test
    @DisplayName("a line begun again -- from its beginning, after its indentation, or from a line higher up -- is there once")
    void aLineBegunAgain() {
        String sofar = HEAD + "    total = 0\n    x = compute(";
        String whole = HEAD + "    total = 0\n    x = compute(a, b)\n";
        assertEquals(whole, ReplySeam.join(sofar, "x = compute(a, b)\n"), "after the indentation");
        assertEquals(whole, ReplySeam.join(sofar, "    x = compute(a, b)\n"), "from the line's beginning");
        assertEquals(whole, ReplySeam.join(sofar, "    total = 0\n    x = compute(a, b)\n"), "from a line higher up");
        assertEquals(HEAD + "\treturn x\n", ReplySeam.join(HEAD + "\tret", "return x\n"), "after a tab");
    }

    @Test
    @DisplayName("cut right after the indentation, the line begun again keeps it once: the longest run is the one dropped")
    void cutAfterTheIndentation() {
        assertEquals(HEAD + "    return x\n", ReplySeam.join(HEAD + "    ", "    return x\n"));
        assertEquals(HEAD + "        return x\n", ReplySeam.join(HEAD + "    ", "        return x\n"),
                "begun again deeper: its own indentation");
        // Mutation: the shortest run on the failure chain -> one blank dropped, seven left.
    }

    @Test
    @DisplayName("inside an open ```python fence, a fence the continuation opens again goes, with the blank lines before it")
    void aFenceOpenedAgain() {
        String sofar = HEAD + "    total = 0\n";
        String whole = HEAD + "    total = 0\n    return total\n";
        assertEquals(whole, ReplySeam.join(sofar, "```python\n    return total\n"));
        assertEquals(whole, ReplySeam.join(sofar, "\n\n```\n    return total\n"), "after blank lines, a plain fence");
        assertEquals(whole, ReplySeam.join(sofar, "  \n```Python  \n    return total\n"));
        assertEquals(HEAD + "    x = compute(a)\n", ReplySeam.join(HEAD + "    x = compute(", "```python\n    x = compute(a)\n"),
                "and the line begun again after it");
        assertEquals(sofar + "```requirements\nrequests\n```\n", ReplySeam.join(sofar, "```requirements\nrequests\n```\n"),
                "a fence of another kind is the reply's own");
        // Mutation: no fence rule -> "```python" in the middle of the code.
    }

    @Test
    @DisplayName("outside an open ```python fence, a fence the continuation opens is the reply's own, and stays")
    void aFenceOfItsOwn() {
        assertEquals("Here is the skill.\n\n```python\nimport json\n",
                ReplySeam.join("Here is the skill.\n\n", "```python\nimport json\n"), "before the code");
        String closed = HEAD + "    return 1\n```\n\n";
        assertEquals(closed + "```requirements\nrequests\n```\n", ReplySeam.join(closed, "```requirements\nrequests\n```\n"),
                "after it");
        assertEquals(closed + "```python\nimport os\n", ReplySeam.join(closed, "```python\nimport os\n"));
        assertTrue(ReplySeam.inPythonFence(HEAD + "    x = 1"));
        assertTrue(ReplySeam.inPythonFence("```python"), "cut right after the fence");
        assertFalse(ReplySeam.inPythonFence(closed));
        assertFalse(ReplySeam.inPythonFence("```pyth"), "half a fence is no Python fence");
        assertFalse(ReplySeam.inPythonFence(HEAD + "    return 1\n```\n\n```requirements\nreq"));
        assertFalse(ReplySeam.inPythonFence(""));
    }

    @Test
    @DisplayName("begun again at the top and written otherwise, the file is appended for the syntax check to see; written again to the cut, it joins")
    void begunAgainAtTheTop() {
        String sofar = HEAD + "    total = 0\n    x = compute(";
        assertEquals(sofar + "import json\n\ndef run(params):\n    total = 1\n",
                ReplySeam.join(sofar, "```python\nimport json\n\ndef run(params):\n    total = 1\n"),
                "no end of the text so far begins it: appended, without the fence opened again");
        assertEquals(HEAD + "    total = 0\n    x = compute(a, b)\n",
                ReplySeam.join(sofar, "```python\nimport json\n\ndef run(params):\n    total = 0\n    x = compute(a, b)\n"),
                "the whole text so far is the run");
    }

    @Test
    @DisplayName("blanks are characters like any; a continuation that is only the end again, or only a fence, adds nothing")
    void blanksAndNothingNew() {
        String sofar = HEAD + "    x = compute(";
        assertEquals(sofar + "   \n", ReplySeam.join(sofar, "   \n"));
        assertEquals(sofar, ReplySeam.join(sofar, "x = compute("), "entirely the end written again");
        assertEquals(sofar, ReplySeam.join(sofar, "    x = compute("));
        assertEquals(HEAD + "    ", ReplySeam.join(HEAD + "    ", "    "), "the indentation again");
        assertEquals(sofar, ReplySeam.join(sofar, "```python\n"), "a fence opened again, and nothing else");
        assertEquals(sofar, ReplySeam.join(sofar, ""));
        assertEquals("import os\n", ReplySeam.join("", "import os\n"), "the first part is the text so far");
    }

    @Test
    @DisplayName("in linear time: a million characters each, every run on the failure chain to try, join at once")
    void inLinearTime() {
        String sofar = "a".repeat(1_000_000);
        String part = "a".repeat(999_999) + "b";
        String joined = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> ReplySeam.join(sofar, part));
        assertEquals(sofar.length() + part.length(), joined.length(), "runs in the middle of a line are no restart");

        String lines = "    x = 1\n".repeat(100_000);
        String again = assertTimeoutPreemptively(Duration.ofSeconds(10),
                () -> ReplySeam.join(lines, lines + "    y = 2\n"));
        assertEquals(lines + "    y = 2\n", again, "the whole text written again, then a line more");
        // Mutation: try each length from the longest with regionMatches -> about 10^12 comparisons
        // for the first pair, and the timeout.
    }
}
