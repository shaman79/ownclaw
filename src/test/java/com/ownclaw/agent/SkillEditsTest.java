package com.ownclaw.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Edit blocks as text: how a reply is read for them and how they are applied to the code they were
 * written against ({@link SkillEdits}). The loop around them -- which text that is, what becomes of
 * the result, what the model is asked next -- is {@code SkillCodeEditsTest}'s.
 */
class SkillEditsTest {

    static final String CODE = String.join("\n",
            "import json",
            "",
            "LIMIT = 3",
            "",
            "def run(params):",
            "    items = params.get('items', [])",
            "    return {'output': json.dumps(items[:LIMIT]), 'success': True}");

    /** One block as a model writes it. */
    static String block(String search, String replace) {
        return SkillEdits.SEARCH + "\n" + search + (search.isEmpty() ? "" : "\n") + SkillEdits.DIVIDER + "\n"
                + replace + (replace.isEmpty() ? "" : "\n") + SkillEdits.REPLACE + "\n";
    }

    static SkillEdits.Applied applied(String code, String reply) {
        SkillEdits.Read read = SkillEdits.read(reply);
        assertTrue(read.marked(), "premise: a reply of edit blocks");
        assertNull(read.refused(), () -> "premise: well-formed: " + read.refused().why());
        return SkillEdits.apply(code, null, read.blocks());
    }

    @Test
    @DisplayName("a block replaces its lines and nothing else: every other character of the code is the one it was")
    void oneBlock() {
        SkillEdits.Applied out = applied(CODE, "The limit:\n\n" + block("LIMIT = 3", "LIMIT = 5") + "\n```requirements\n```\n");
        assertNull(out.refused());
        assertEquals(CODE.replace("LIMIT = 3", "LIMIT = 5"), out.code());
        assertEquals(1, out.blocks());
        assertEquals("1 edit (9 of its " + CODE.length() + " characters replaced by 9)", out.inWords());
        // Mutation: apply() joins the code's lines with an empty line between them -> every line break of the file is two.
    }

    @Test
    @DisplayName("several blocks are applied in the order given, each to the code as the blocks before it left it: lines added, lines deleted, a line a block before wrote changed again")
    void severalBlocksInOrder() {
        String reply = block("LIMIT = 3", "LIMIT = 5\nSTEP = 2")
                + block("STEP = 2", "STEP = 4")                                  // a line block 1 wrote
                + block("    items = params.get('items', [])", "")              // deleted, whole
                + block("def run(params):", "def run(params):\n    items = params.get('items') or []");
        SkillEdits.Applied out = applied(CODE, reply);
        assertNull(out.refused(), () -> out.refused().why());
        assertEquals(String.join("\n", "import json", "", "LIMIT = 5", "STEP = 4", "", "def run(params):",
                "    items = params.get('items') or []",
                "    return {'output': json.dumps(items[:LIMIT]), 'success': True}"), out.code());
        assertEquals(4, out.blocks());

        // The same blocks in another order are another change, or none: block 2 before block 1 looks
        // for a line nothing has written yet.
        SkillEdits.Applied swapped = applied(CODE, block("STEP = 2", "STEP = 4") + block("LIMIT = 3", "LIMIT = 5\nSTEP = 2"));
        assertNull(swapped.code(), "nothing applied");
        assertTrue(swapped.refused().told().startsWith("Block 1 was not applied: its SEARCH text is not in the code."),
                swapped.refused().told());
        // Mutation: look every block's lines up in the code as it was shown -> block 2's "STEP = 2" is never found.
    }

    @Test
    @DisplayName("a block whose lines are not in the code is refused, and so is the whole reply: nothing is applied; the model is told which block, and what can be said of why")
    void notFound() {
        SkillEdits.Applied out = applied(CODE, block("LIMIT = 3", "LIMIT = 5") + block("LIMIT = 4", "LIMIT = 6"));
        assertNull(out.code(), "the first block was not applied either");
        assertEquals("Block 2 was not applied: its SEARCH text is not in the code as the blocks before it left it. "
                + "Line 1 of its SEARCH text is no line of the code: copy the lines from the code above, character for "
                + "character.\n\n" + SkillEdits.AGAIN, out.refused().told());

        // The lines are there, less their indentation: which lines of the code they are.
        SkillEdits.Applied dedented = applied(CODE, block("items = params.get('items', [])", "items = []"));
        assertTrue(dedented.refused().told().startsWith("Block 1 was not applied: its SEARCH text is not in the code. "
                + "Line 6 of the code as shown is this line but for the indentation or the blanks at a line's end: copy "
                + "it character for character."), dedented.refused().told());

        // Part of a line: taken, it would put the lines that replace it at that line's depth.
        SkillEdits.Applied inside = applied(CODE, block("json.dumps(items[:LIMIT])", "json.dumps(items)"));
        assertTrue(inside.refused().told().contains("It stands there only inside a line: SEARCH is whole lines"),
                inside.refused().told());

        // A line a block before it replaced: the blocks are applied in order.
        SkillEdits.Applied gone = applied(CODE, block("LIMIT = 3", "LIMIT = 5") + block("LIMIT = 3", "LIMIT = 7"));
        assertTrue(gone.refused().told().contains("It stood in the code as shown, and a block before it changed that "
                + "place"), gone.refused().told());
        // Mutation: skip a block that is not found and apply the others -> a change the model did not write as one.
    }

    @Test
    @DisplayName("lines that stand at more than one place are refused, with how many places and what to add; places that overlap count each")
    void moreThanOnce() {
        String code = "a = 1\nb = 2\na = 1\nc = 3\na = 1";
        SkillEdits.Applied out = applied(code, block("a = 1", "a = 2"));
        assertNull(out.code());
        assertEquals("Block 1 was not applied: its SEARCH text stands at 3 places in the code. Add the lines before or "
                + "after it to SEARCH, and to REPLACE, until it matches exactly one place.\n\n" + SkillEdits.AGAIN,
                out.refused().told());
        assertEquals(code.replaceFirst("a = 1\nc = 3", "a = 2\nc = 3"), applied(code, block("a = 1\nc = 3", "a = 2\nc = 3")).code(),
                "with the line after it, one place");

        assertTrue(applied("x\nx\nx", block("x\nx", "y")).refused().told().contains("stands at 2 places"),
                "two runs of two lines in three equal lines");
        // Mutation: replace the first place found -> the first "a = 1" of three is changed, whichever the model meant.
    }

    @Test
    @DisplayName("a block with nothing to look for, a block that changes nothing, and the same block twice are refused")
    void emptyUnchangedTwice() {
        assertEquals("Block 1 was not applied: its SEARCH text is empty, so nothing says which lines it replaces.\n\n"
                + SkillEdits.AGAIN, applied(CODE, block("", "LIMIT = 5")).refused().told());
        assertTrue(applied(CODE, block("\n   ", "LIMIT = 5")).refused().told().contains("its SEARCH text is empty"),
                "only blank lines");
        assertEquals("Block 2 changes nothing: its REPLACE text is its SEARCH text. Leave such a block out.\n\n"
                + SkillEdits.AGAIN, applied(CODE, block("LIMIT = 3", "LIMIT = 5") + block("import json", "import json"))
                .refused().told());

        // A block that keeps its line and adds one after it applies a second time, and the result compiles.
        String adds = block("LIMIT = 3", "LIMIT = 3\nSTEP = 2");
        SkillEdits.Applied twice = applied(CODE, adds + adds);
        assertNull(twice.code());
        assertEquals("Block 2 is block 1 again, the same SEARCH and REPLACE text. Each block is applied once, in the "
                + "order given.\n\n" + SkillEdits.AGAIN, twice.refused().told());
        // Mutation: no check for a block written twice -> "STEP = 2" stands twice in the skill.
    }

    @Test
    @DisplayName("a reply is read for blocks by its marker lines alone: prose, a ```requirements fence and a fence around a block are no part of one; a reply with no marker line is no reply of edits")
    void reading() {
        String reply = "Two changes.\n\n```python\n" + block("LIMIT = 3", "LIMIT = 5") + "```\n\nand\n\n"
                + SkillEdits.SEARCH + "  \n    return 1\n" + SkillEdits.DIVIDER + "\n" + SkillEdits.REPLACE + " \n"
                + "\n```requirements\nrequests\n```\n";
        SkillEdits.Read read = SkillEdits.read(reply);
        assertTrue(read.marked());
        assertNull(read.refused(), () -> read.refused().why());
        assertEquals(List.of(new SkillEdits.Block(1, List.of("LIMIT = 3"), List.of("LIMIT = 5")),
                new SkillEdits.Block(2, List.of("    return 1"), List.of())), read.blocks());
        assertEquals("requests", AgentLoop.extractRequirements(read.rest()));
        assertFalse(read.rest().contains("LIMIT"), "the blocks' lines are not the reply's: " + read.rest());

        // A block's own text may hold an indented line of =======: a heading's underline in a docstring.
        SkillEdits.Read docstring = SkillEdits.read(block("    Title", "    Title\n    ======="));
        assertNull(docstring.refused());
        assertEquals(List.of("    Title", "    ======="), docstring.blocks().getFirst().replace());

        SkillEdits.Read code = SkillEdits.read("```python\n" + CODE + "\n```\n\n```requirements\n```\n");
        assertFalse(code.marked(), "the complete code is no reply of edits");
        assertFalse(SkillEdits.read("Changes\n=======\nnone").marked(), "a line of ======= alone is a heading's underline");
        // Mutation: take a ```python fence around a block for code -> a reply of blocks in fences is refused as both.
    }

    @Test
    @DisplayName("a malformed block refuses the reply, saying what is wrong with which block: not closed, not divided, divided twice, closed without being opened, a marker line that is not one")
    void malformed() {
        String good = block("LIMIT = 3", "LIMIT = 5");
        assertEquals("Block 1 is not closed: another <<<<<<< SEARCH line follows before its >>>>>>> REPLACE line.",
                SkillEdits.read(SkillEdits.SEARCH + "\nLIMIT = 3\n" + SkillEdits.DIVIDER + "\nLIMIT = 5\n" + good).refused().why());
        assertEquals("Block 2 has no ======= line between its <<<<<<< SEARCH line and its >>>>>>> REPLACE line.",
                SkillEdits.read(good + SkillEdits.SEARCH + "\nimport json\n" + SkillEdits.REPLACE + "\n").refused().why());
        assertEquals("Block 1 holds two ======= lines, and which of them divides it cannot be told.",
                SkillEdits.read(block("Title\n=======", "Heading\n=======")).refused().why());
        assertEquals("A >>>>>>> REPLACE line stands where no block is open: every block begins with a <<<<<<< SEARCH line.",
                SkillEdits.read(good + SkillEdits.REPLACE + "\n").refused().why());
        assertEquals("Your reply ends inside block 2: no >>>>>>> REPLACE line closes it.",
                SkillEdits.read(good + SkillEdits.SEARCH + "\nimport json\n" + SkillEdits.DIVIDER + "\nimport os\n").refused().why());
        for (String notOne : List.of("<<<<<<< ORIGINAL", "<<<<<<< SEARCH skill.py", "  <<<<<<< SEARCH", ">>>>>>> UPDATED",
                "<<<<<<<SEARCH")) {
            SkillEdits.Read read = SkillEdits.read("Here:\n" + notOne + "\nLIMIT = 3\n" + SkillEdits.DIVIDER + "\nLIMIT = 5\n"
                    + SkillEdits.REPLACE + "\n");
            assertTrue(read.marked(), notOne + ": meant as a block, so read as one");
            assertEquals("Line 2 of your reply begins like a marker line and is none.", read.refused().why(), notOne);
            assertTrue(read.refused().told().contains("The three are exactly \"<<<<<<< SEARCH\", \"=======\" and "
                    + "\">>>>>>> REPLACE\", each alone on its line, from the first column."), read.refused().told());
        }
        // The first fault is the one told, and its kind is what comes back, whichever block it is in.
        assertEquals(SkillEdits.read(SkillEdits.SEARCH + "\na\n" + SkillEdits.REPLACE + "\n").refused().key(),
                SkillEdits.read(good + good + SkillEdits.SEARCH + "\nb\n" + SkillEdits.REPLACE + "\n").refused().key());
        // Mutation: take "<<<<<<< ORIGINAL" for prose -> the block is never opened, and its lines are the reply's text.
    }

    @Test
    @DisplayName("a reply that holds edit blocks and also code in a ```python fence is refused: which of the two is the change cannot be told")
    void bothForms() {
        String reply = block("LIMIT = 3", "LIMIT = 5") + "\nThe whole file, to be safe:\n\n```python\n" + CODE + "\n```\n";
        SkillEdits.Read read = SkillEdits.read(reply);
        assertTrue(read.marked());
        assertEquals("Your reply holds edit blocks and also code in a ```python fence outside them: which of the two is "
                + "the change cannot be told. Return one of them only.\n\n" + SkillEdits.AGAIN, read.refused().told());
        assertTrue(SkillEdits.holdsCode("```\nimport json\n\ndef run(params):\n    return {}\n```"), "a bare fence around a module");
        assertFalse(SkillEdits.holdsCode("```python\n```\n\n```python\n\n```\n```requirements\nrequests\n```"),
                "the fences left of blocks written in them, and the requirements");
        assertFalse(SkillEdits.holdsCode("```\npip install requests\n```"), "a bare fence around something else");
        // Mutation: apply the blocks and drop the fenced code -> half of what the model wrote is used, unasked.
    }

    @Test
    @DisplayName("with the copy kept as the model wrote it, the lines are looked for in the code as it was sent and replaced in the copy, line for line: a line no block changes -- one a block repeats to say where it is, too -- is the copy's own, whatever it was sent as")
    void carriedOutOnTheKeptCopy() {
        String kept = String.join("\n", "import json", "UA = 'Chrome/131.0.0.0'", "LIMIT = 3", "", "def run(params):",
                "    ssid = params.get('ssid', '')", "    # set password=hunter2 in the config",
                "    return {'output': json.dumps([ssid, UA, LIMIT]), 'success': True}");
        // What the gateway made of it: a version read as an address, code read as a network's name, a comment as a secret.
        String shown = kept.replace("131.0.0.0", "<ip_1>").replace("params.get('ssid', '')", "<ssid_1>")
                .replace("password=hunter2", "password=«secret removed»");
        String reply = block("UA = 'Chrome/<ip_1>'\nLIMIT = 3", "UA = 'Chrome/<ip_1>'\nLIMIT = 5")      // repeats a line sent changed
                + block("    ssid = <ssid_1>\n    # set password=«secret removed» in the config",
                        "    ssid = <ssid_1>\n    names = []\n    # set password=«secret removed» in the config");
        SkillEdits.Read read = SkillEdits.read(reply);

        SkillEdits.Applied out = SkillEdits.apply(shown, kept, read.blocks());

        assertNull(out.refused(), () -> out.refused().why());
        assertEquals(kept.replace("LIMIT = 3", "LIMIT = 5").replace("    ssid = params.get('ssid', '')",
                "    ssid = params.get('ssid', '')\n    names = []"), out.code(),
                "the two changes, and every other line as the skill has it");
        assertEquals("2 edits (9 of its " + kept.length() + " characters replaced by 23)", out.inWords(),
                "what was changed, not the lines repeated around it");
        assertEquals(shown.replace("LIMIT = 3", "LIMIT = 5").replace("    ssid = <ssid_1>", "    ssid = <ssid_1>\n    names = []"),
                SkillEdits.apply(shown, null, read.blocks()).code(), "without a copy: the code as it was sent, changed");

        // A line the model does change is its line: what it wrote there, placeholders and all.
        assertEquals(kept.replace("UA = 'Chrome/131.0.0.0'", "UA = 'Firefox/<ip_1>'"),
                SkillEdits.apply(shown, kept, SkillEdits.read(block("UA = 'Chrome/<ip_1>'", "UA = 'Firefox/<ip_1>'")).blocks()).code());

        // Looked for in what was sent, never in the copy: its own line is one the model did not read.
        assertTrue(SkillEdits.apply(shown, kept, SkillEdits.read(block("UA = 'Chrome/131.0.0.0'", "UA = 'x'")).blocks())
                .refused().why().contains("is not in the code"));

        // Fewer lines sent than kept -- a stretch over two lines went out as one marker: no line has its place.
        String pem = kept + "\nKEY = \"\"\"-----BEGIN PRIVATE KEY-----\nMIIB\n-----END PRIVATE KEY-----\"\"\"";
        String pemShown = shown + "\nKEY = \"\"\"«secret removed»\"\"\"";
        assertEquals(pemShown.replace("LIMIT = 3", "LIMIT = 5"),
                SkillEdits.apply(pemShown, pem, SkillEdits.read(block("LIMIT = 3", "LIMIT = 5")).blocks()).code(),
                "carried out on the code as it was sent");
        // Mutation: carry the change out on the code as sent although a copy is kept -> "ssid = <ssid_1>" and the
        // removed secret are in the result, and nothing can be made of it.
        // Mutation: replace a block's whole run in the copy, the lines it repeats too -> "UA = 'Chrome/<ip_1>'" in it.
    }

    @Test
    @DisplayName("property: carried out on a copy with the same number of lines, a block changes in it exactly the lines it changes in the code as sent, at the same place, and no other line of the copy")
    void theCopyChangesLineForLine() {
        var random = new Random(3L);
        String[] alphabet = {"a", "b", "c", "d"};
        int applied = 0;
        for (int round = 0; round < 40_000; round++) {
            var shown = new ArrayList<String>();
            var kept = new ArrayList<String>();
            for (int i = 2 + random.nextInt(7); i > 0; i--) {
                String line = alphabet[random.nextInt(alphabet.length)];
                shown.add(line);
                // Most lines went out as they are; some as another text, which is no line of the copy.
                kept.add(random.nextInt(4) == 0 ? "kept " + i : line);
            }
            var search = new ArrayList<String>();
            for (int i = random.nextInt(3); i >= 0; i--) search.add(alphabet[random.nextInt(alphabet.length)]);
            var replace = new ArrayList<String>();
            for (int i = random.nextInt(4); i > 0; i--) replace.add(alphabet[random.nextInt(alphabet.length)]);
            if (search.equals(replace)) continue;
            var block = List.of(new SkillEdits.Block(1, search, replace));
            SkillEdits.Applied onShown = SkillEdits.apply(String.join("\n", shown), null, block);
            SkillEdits.Applied onKept = SkillEdits.apply(String.join("\n", shown), String.join("\n", kept), block);
            String what = "shown " + shown + ", kept " + kept + ", search " + search + ", replace " + replace;
            if (onShown.code() == null) {
                assertNull(onKept.code(), what + ": refused in what was sent, applied to the copy");
                continue;
            }
            applied++;
            // Where the block stands in what was sent, and what of it is change: not the lines it repeats above and below.
            int at = 0;
            while (!shown.subList(at, at + search.size()).equals(search)) at++;
            int above = 0, below = 0, most = Math.min(search.size(), replace.size());
            while (above < most && search.get(above).equals(replace.get(above))) above++;
            while (below < most - above && search.get(search.size() - 1 - below).equals(replace.get(replace.size() - 1 - below))) below++;
            var expected = new ArrayList<>(kept.subList(0, at + above));
            expected.addAll(replace.subList(above, replace.size() - below));
            expected.addAll(kept.subList(at + search.size() - below, kept.size()));
            assertEquals(String.join("\n", expected), onKept.code(), what);
            // And it is the same change as in what was sent: there, the lines repeated are the same lines.
            var sent = new ArrayList<>(shown.subList(0, at + above));
            sent.addAll(replace.subList(above, replace.size() - below));
            sent.addAll(shown.subList(at + search.size() - below, shown.size()));
            assertEquals(String.join("\n", sent), onShown.code(), what);
        }
        assertTrue(applied > 5_000, applied + " applied");
        // Mutation: look the lines up in the copy -> a block the model wrote from what it read is not found, or found elsewhere.
    }

    /** {@code lines} with a line break before and after, as {@link SkillEdits#apply} is said to read them. */
    private static String framed(List<String> lines) {
        return "\n" + String.join("\n", lines) + "\n";
    }

    private static int count(String text, String part) {
        int n = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + 1)) n++;
        return n;
    }

    @Test
    @DisplayName("property: applying a block is the plain replacement of its lines, framed by line breaks, in the code framed so, when they stand there exactly once -- and refused when they stand there not at all or more than once")
    void appliesAsPlainReplacement() {
        var random = new Random(20261118L);
        // Few different lines, so that runs repeat, overlap and miss: one of them another's with an indentation,
        // one empty.
        String[] alphabet = {"a", "b", "    a", ""};
        int applied = 0, missing = 0, several = 0;
        for (int round = 0; round < 40_000; round++) {
            var code = new ArrayList<String>();
            for (int i = random.nextInt(7); i >= 0; i--) code.add(alphabet[random.nextInt(alphabet.length)]);
            var search = new ArrayList<String>();
            for (int i = random.nextInt(2); i >= 0; i--) search.add(alphabet[random.nextInt(alphabet.length)]);
            var replace = new ArrayList<String>();
            for (int i = random.nextInt(3); i > 0; i--) replace.add(alphabet[random.nextInt(alphabet.length)]);
            if (search.stream().allMatch(String::isBlank) || search.equals(replace)) continue;   // refused before looking

            String text = String.join("\n", code);
            SkillEdits.Applied out = SkillEdits.apply(text, null, List.of(new SkillEdits.Block(1, search, replace)));
            int places = count(framed(code), framed(search));
            String what = "code " + code + ", search " + search + ", replace " + replace;
            if (places == 1) {
                String replaced = framed(code).replace(framed(search), replace.isEmpty() ? "\n" : framed(replace));
                String expected = replaced.length() < 2 ? "" : replaced.substring(1, replaced.length() - 1);
                assertNull(out.refused(), what);
                assertEquals(expected, out.code(), what);
                applied++;
            } else {
                assertNull(out.code(), what + ": applied with " + places + " places");
                assertTrue(out.refused().key().startsWith(places == 0 ? "missing" : "several"), what + ": " + out.refused().why());
                if (places > 1) assertTrue(out.refused().why().contains("stands at " + places + " places"), out.refused().why());
                if (places == 0) missing++; else several++;
            }
        }
        assertTrue(applied > 5_000 && missing > 5_000 && several > 3_000,
                "each case met often: " + applied + " applied, " + missing + " missing, " + several + " at several places");
        // Mutation: compare the lines without their indentation -> "a" matches "    a", and the property fails.
        // Mutation: count places that do not overlap only -> ["a","a"] in ["a","a","a"] is one place, and applied.
    }

    @Test
    @DisplayName("property: blocks are applied in order -- the same as applying the first, then the rest to what it gave; and a reply one of whose blocks is refused gives no code at all")
    void appliesInOrder() {
        var random = new Random(7L);
        String[] alphabet = {"a", "b", "c", "d", "e"};
        int both = 0, refused = 0;
        for (int round = 0; round < 40_000; round++) {
            var code = new ArrayList<String>();
            for (int i = 3 + random.nextInt(6); i > 0; i--) code.add(alphabet[random.nextInt(alphabet.length)]);
            var blocks = new ArrayList<SkillEdits.Block>();
            for (int n = 1; n <= 2 + random.nextInt(2); n++) {
                blocks.add(new SkillEdits.Block(n, List.of(alphabet[random.nextInt(alphabet.length)]),
                        List.of(alphabet[random.nextInt(alphabet.length)], alphabet[random.nextInt(alphabet.length)])));
            }
            String text = String.join("\n", code);
            SkillEdits.Applied all = SkillEdits.apply(text, null, blocks);
            // One at a time, each to what the one before it gave.
            String step = text;
            for (int i = 0; i < blocks.size() && step != null; i++) {
                boolean again = blocks.subList(0, i).stream().anyMatch(blocks.get(i)::sameEdit);
                step = again ? null : SkillEdits.apply(step, null, List.of(blocks.get(i))).code();
            }
            assertEquals(step, all.code(), "code " + code + ", blocks " + blocks);
            if (step == null) refused++; else both++;
        }
        assertTrue(both > 1_000 && refused > 1_000, both + " applied, " + refused + " refused");
        // Mutation: return the code as far as the blocks applied -> a refused reply changes the skill half way.
    }

    @Test
    @DisplayName("a syntax error in the result is shown with the result's own lines around it, each under its number there; a line the filter would change is named by its number alone")
    void aroundAnError() {
        String result = String.join("\n", "import json", "NET = '<ssid_1>'", "", "def run(params):", "    x = (1,",
                "    return {'output': json.dumps(x), 'success': True}", "", "# the end", "# of it");
        assertEquals("2: (a line that names a value: not repeated here)\n3: \n4: def run(params):\n5:     x = (1,\n"
                + "6:     return {'output': json.dumps(x), 'success': True}\n7: \n8: # the end",
                SkillEdits.around(result, 5, line -> !line.contains("<ssid_1>")));
        assertEquals("1: import json\n2: NET = '<ssid_1>'\n3: \n4: def run(params):", SkillEdits.around(result, 1, line -> true),
                "from the first line");
        assertEquals("", SkillEdits.around(result, 10, line -> true), "no such line");
        assertEquals("", SkillEdits.around(result, 0, line -> true));
        // Mutation: number the lines shown from 1 -> the model is told line 5, and shown it as line 4.
    }
}
