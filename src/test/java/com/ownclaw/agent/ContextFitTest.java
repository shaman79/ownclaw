package com.ownclaw.agent;

import com.ownclaw.privacy.Redactor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Folding a task's results to fit a model's window, and reading them back whole. */
class ContextFitTest {

    /** A result as it may be cut, no vault values in this task. */
    private static Redactor.Cuttable cut(String output) {
        return Redactor.cuttable(output, Map.of());
    }

    private static AgentTrajectory.Turn turn(String tool, String output) {
        return new AgentTrajectory.Turn(new AgentAction(tool, Map.of(), ""),
                AgentObservation.success(tool, output, Map.of(), 7));
    }

    @Test
    @DisplayName("each round aims under the window at two characters a token, and always under three quarters of what did not fit")
    void target() {
        assertEquals(750_000, ContextFit.target(1_000_000, null, 0), "no window named: three quarters");
        assertEquals(524_288, ContextFit.target(1_000_000, 262_144, 0), "the window, at two characters a token");
        assertEquals(75_000, ContextFit.target(100_000, 262_144, 0), "smaller than the request that did not fit, always");
        assertEquals((long) (524_288 * 0.7), ContextFit.target(1_000_000, 262_144, 1), "each further round lower");
        // Mutation: drop the three quarters -> a request already under the estimate folds nothing.
    }

    @Test
    @DisplayName("the largest results fold first, the older among equals, until the request is under the target; short ones never")
    void plan() {
        var turns = List.of(
                turn("a", "x".repeat(10_000)),
                turn("b", "y".repeat(50_000)),
                turn("c", "z".repeat(10_000)),
                turn("d", "w".repeat(3_000)),
                new AgentTrajectory.Turn(new AgentAction("_message", Map.of(), ""),
                        AgentObservation.success("_message", "m".repeat(90_000), Map.of(), 0)));
        // Under 75,000 of 100,000: folding the 50,000 one to its preview is enough.
        var first = ContextFit.plan(turns, Map.of(), 100_000, null, 0, ContextFitTest::cut);
        assertEquals(List.of(1), List.copyOf(first.keySet()), "the largest: " + first.keySet());
        // Under 57,750 of 110,000 (a second round): the largest, then the older of the equals.
        var deeper = ContextFit.plan(turns, Map.of(), 110_000, null, 1, ContextFitTest::cut);
        assertEquals(List.of(1, 0), List.copyOf(deeper.keySet()));
        // Already folded results are not folded again; a short one and the owner's message never.
        var rest = ContextFit.plan(turns, Map.of(0, first.get(1), 1, first.get(1), 2, first.get(1)), 100_000, null, 3, ContextFitTest::cut);
        assertTrue(rest.isEmpty(), "nothing left to fold: " + rest.keySet());
        // Mutations: sort ascending -> the 10,000 ones first; fold byTheLoop turns -> the message.
    }

    @Test
    @DisplayName("the chat folds too: a pasted log among its messages by size, then its oldest messages to a line; the newest two never")
    void chatPlan() {
        var chat = new java.util.ArrayList<String>();
        for (int i = 0; i < 40; i++) chat.add((i % 2 == 0 ? "USER: question " : "ASSISTANT: answer ") + i + " " + "x".repeat(3_000) + "\n");
        chat.set(5, "USER: here is the log\n" + "203.0.113.7 GET / 200\n".repeat(2_400));   // 52,800 characters
        long request = 210_000;
        // Under 157,500: the log by size, then the oldest message to a line.
        var plan = ContextFit.plan(List.of(), Map.of(), chat, Map.of(), request, null, 0, ContextFitTest::cut);
        assertEquals(java.util.Set.of(5, 0), plan.chat().keySet(), "the log, then the oldest: " + plan.chat().keySet());
        assertFalse(plan.chat().get(5).line());
        assertTrue(plan.chat().get(5).shown().startsWith("[Message 6 of 40 of this chat is 52,822 characters: "), plan.chat().get(5).shown());
        assertTrue(plan.chat().get(0).line());
        assertTrue(plan.chat().get(0).shown().startsWith("[Message 1 of 40, 3,018 characters, folded to a line; read_result "
                + "with message=1 reads it whole] USER: question 0 " + "x".repeat(83) + "…"), plan.chat().get(0).shown());
        assertEquals(List.of("2 messages of this chat (55,840 characters)"), plan.labels());
        // A long chat with nothing large: everything but the newest two to a line, when it must.
        var all = ContextFit.plan(List.of(), Map.of(), chat, Map.of(), 2_000_000, 10, 0, ContextFitTest::cut);
        assertEquals(38, all.chat().size());
        assertFalse(all.chat().containsKey(38) || all.chat().containsKey(39), "the newest two are kept whole");
        // Mutation: keep no newest messages -> 40 folded; fold the chat by size only -> nothing for the many short ones.
    }

    @Test
    @DisplayName("the chat as the prompt shows it is the chat itself, byte for byte, until a message of it folds")
    void chatShown() {
        var ctx = new AgentContext("u1", "t1", "go");
        var chat = List.of("USER: hi\n", "ASSISTANT: hello\n[OwnClaw's record of task 1234abcd]\n", "USER: and now?\n",
                "USER: the router says password=hunter2abc99 now\n");
        ctx.setConversationSummary("### The conversation so far\n" + String.join("", chat).strip());
        ctx.setConversation(chat);
        assertSame(ctx.conversationSummary(), ctx.conversationShown());
        assertEquals("ERROR: this chat has no message 5; its messages before this task are 1 to 4.",
                AgentLoop.readResult(Map.of("message", 5), ctx));
        String read = AgentLoop.readResult(Map.of("message", 4, "from", 36), ctx);
        assertFalse(read.contains("hunter2") || read.contains("abc99"), "a chat message is cut with its secrets removed: " + read);
        assertEquals("Message 2 of this chat, characters 0-53 of 53:\n" + chat.get(1),
                AgentLoop.readResult(Map.of("message", 2), ctx));
    }

    @Test
    @DisplayName("a folded result shows its tool line, its first 2,000 characters, its size and how to read the rest")
    void shown() {
        // An emoji -- two chars -- across the 2,000th char: the preview ends after it, never inside.
        String output = "a".repeat(1_999) + "😀" + "b".repeat(5_000);
        var t = turn("read_access_log", output);
        String shown = ContextFit.shown(t, 4, cut(output));
        assertTrue(shown.startsWith("[read_access_log] OK (7ms)\n[Step 4's result is 7,001 characters: "), shown);
        assertTrue(shown.contains("read_result with step=4 reads any part of it -- from, a character offset, and "
                + "length, as much as fits when left out -- and with find, every place a text occurs in it.]"), shown);
        assertTrue(shown.contains("a".repeat(1_999) + "😀\n[… folded: characters 2,001-7,001 of step 4's result]"), shown);
        assertFalse(shown.contains("bb"), "only the beginning");
    }

    @Test
    @DisplayName("read_result reads any part, says what follows, and refuses an offset outside the result")
    void read() {
        String output = "abcdefghij".repeat(10);   // 100 characters
        assertEquals("Step 2's result, characters 10-20 of 100:\nabcdefghij\n[… characters 20-100 follow: "
                + "read_result step=2 from=20]", ContextFit.read(2, cut(output), 10L, 10L, null, 0));
        assertEquals("Step 2's result, characters 90-100 of 100:\nabcdefghij",
                ContextFit.read(2, cut(output), 90L, Long.MAX_VALUE, null, 0), "to the end, however long the ask");
        assertTrue(ContextFit.read(2, cut(output), null, null, null, 30).startsWith("Step 2's result, characters 0-30 of 100:"),
                "what fits at a time, when no length is asked");
        assertTrue(ContextFit.read(2, cut(output), null, null, null, 0).endsWith(output), "the rest of it, when nothing was folded");
        assertTrue(ContextFit.read(2, cut(output), 101L, null, null, 0).startsWith("ERROR: from=101 is outside"));
        assertTrue(ContextFit.read(2, cut(output), -1L, null, null, 0).startsWith("ERROR: from=-1 is outside"));
        assertTrue(ContextFit.read(2, cut(output), 0L, 0L, null, 0).startsWith("ERROR: length must be a positive"));
        String emoji = "ab😀cd";
        assertEquals("Step 1's result, characters 2-4 of 6:\n😀\n[… characters 4-6 follow: read_result step=1 from=4]",
                ContextFit.read(1, cut(emoji), 3L, 1L, null, 0), "never half a character");
    }

    @Test
    @DisplayName("find lists every place a text occurs, case aside, with its offset and the text around it in its line")
    void find() {
        String output = "header\n" + "x".repeat(400) + "Invoice 2024/17 paid" + "y".repeat(400) + "\nnext line: INVOICE 9\n";
        String found = ContextFit.read(3, cut(output), null, null, "invoice", 0);
        assertTrue(found.startsWith("\"invoice\" occurs in 2 places in step 3's result"), found);
        int first = output.indexOf("Invoice");
        assertTrue(found.contains("\n@" + first + ": …" + "x".repeat(300) + "Invoice 2024/17 paid" + "y".repeat(287) + "…"),
                "300 characters either side, within the line: " + found);
        assertTrue(found.contains("\n@" + output.indexOf("INVOICE") + ": next line: INVOICE 9"), found);
        assertEquals("\"faktura\" does not occur in step 3's result (" + String.format(java.util.Locale.ROOT, "%,d",
                output.length()) + " characters, case aside).", ContextFit.read(3, cut(output), null, null, "faktura", 0));
        assertTrue(ContextFit.read(3, cut("a.b"), null, null, ".", 0).contains("occurs in 1 place"), "a text, not a pattern");
        assertTrue(ContextFit.read(3, cut("Škoda škoda"), null, null, "ŠKODA", 0).contains("occurs in 2 places"), "any script");
    }

    @Test
    @DisplayName("read_result names the step it cannot read, and takes numbers as a model writes them")
    void readResultArguments() {
        var ctx = new AgentContext("u1", "t1", "go");
        ctx.trajectory().record(new AgentAction("ping", Map.of(), ""), AgentObservation.success("ping", "pong", Map.of(), 1));
        assertTrue(AgentLoop.readResult(Map.of(), ctx).startsWith("ERROR: 'step' or 'message' is required"));
        assertEquals("ERROR: this task has no step 2; its steps so far are 1 to 1.", AgentLoop.readResult(Map.of("step", 2), ctx));
        assertTrue(AgentLoop.readResult(Map.of("step", "one"), ctx).startsWith("ERROR: step, message, from and length are whole numbers"));
        assertTrue(AgentLoop.readResult(Map.of("step", 1.5), ctx).startsWith("ERROR: step, message, from and length are whole numbers"));
        assertEquals("Step 1's result, characters 1-3 of 4:\non\n[… characters 3-4 follow: read_result step=1 from=3]",
                AgentLoop.readResult(Map.of("step", 1.0, "from", " 1 ", "length", "2"), ctx));
        ctx.trajectory().record(new AgentAction("uci_show", Map.of(), ""),
                AgentObservation.success("uci_show", "user=root password=hunter2abc99 ok", Map.of(), 1));
        String read = AgentLoop.readResult(Map.of("step", 2, "from", 19), ctx);
        assertFalse(read.contains("hunter2") || read.contains("abc99"), read);
        assertTrue(AgentLoop.readResult(Map.of("step", 2), ctx).contains("password=" + Redactor.SECRET_REMOVED));
        // Mutation: readResult cuts the output as it is -> from 19 reads hunter2abc99.
        assertEquals(60_000L, AgentLoop.wholeNumber("60,000"));
        assertNull(AgentLoop.wholeNumber(" "));
    }

    @Test
    @DisplayName("a folded result stays folded, step after step; the fold measures the request the engine sent, and reads what fits now")
    void foldedOnce() {
        var ctx = new AgentContext("u1", "t1", "go");
        String big = "line of the log\n".repeat(5_000);
        ctx.trajectory().record(new AgentAction("read_log", Map.of(), ""), AgentObservation.success("read_log", big, Map.of(), 1));
        ctx.trajectory().record(new AgentAction("ping", Map.of(), ""), AgentObservation.success("ping", "pong", Map.of(), 1));
        assertEquals(ctx.trajectory().turns().get(0).observationText(), ctx.observationShown(0, ctx.trajectory().turns().get(0)));
        assertEquals(0, ctx.readChunk(), "nothing folded: the rest of a result");
        ctx.requestSize(big.length() + 10_000);
        assertEquals(List.of(0), ctx.fitToWindow(262_144));
        String shown = ctx.observationShown(0, ctx.trajectory().turns().get(0));
        assertTrue(shown.contains("[Step 1's result is 80,000 characters"), shown);
        ctx.requestSize(12_500);
        assertEquals((262_144 * 2 - 12_500) / 2, ctx.readChunk(), "half the room the last request left, now");
        ctx.requestSize(524_000);
        assertEquals(ContextFit.FOLDABLE, ctx.readChunk(), "never less than a foldable result");
        ctx.requestSize(12_500);
        assertEquals(List.of(), ctx.fitToWindow(262_144), "nothing left to fold: pong is too short");
        assertEquals(shown, ctx.observationShown(0, ctx.trajectory().turns().get(0)), "the same bytes as before");
    }

    @Test
    @DisplayName("the rounds of one overflow aim lower each time; a request that fits starts the next overflow at the first again")
    void roundsPerOverflow() {
        var ctx = new AgentContext("u1", "t1", "go");
        for (int i = 0; i < 4; i++) {
            ctx.trajectory().record(new AgentAction("read_log", Map.of(), ""),
                    AgentObservation.success("read_log", "x".repeat(100_000), Map.of(), 1));
        }
        // 400,000 of results; no window named: each round aims under 0.75, then 0.525, of the request.
        ctx.requestSize(410_000);
        assertEquals(List.of(0, 1), ctx.fitToWindow(null), "under 307,500");
        ctx.fitted();
        ctx.requestSize(214_000);
        assertEquals(List.of(2), ctx.fitToWindow(null), "under 160,500, as a first round: one more is enough");
        // Mutation: never reset the rounds -> the second overflow aims under 0.525 of it and folds both.
    }

    @Test
    @DisplayName("no part read or shown holds a secret, whole or in part: the result is cut with its secrets removed")
    void secretsAreNeverCut() {
        String config = "x".repeat(5_000) + "\nconfig wifi-iface 'default'\n\toption key 'Zq7vLm93pR-fake'\n"
                + "login password=hunter2abc99 ok\nvault: Kolibri-2291-Secret\n";
        var readable = Redactor.cuttable(config, Map.of("WIFI_PASS", "Kolibri-2291-Secret"));
        assertFalse(readable.text().contains("Zq7vLm93pR") || readable.text().contains("hunter2abc99")
                || readable.text().contains("olibri-2291"), readable.text().substring(5_000));
        // Every offset, every length: no read carries a piece of any of them.
        String all = readable.text();
        for (long from = 4_990; from < all.length(); from++) {
            for (long length : List.of(1L, 3L, 9L, 40L)) {
                String part = ContextFit.read(1, readable, from, length, null, 0);
                for (String secret : List.of("Zq7v", "q7vL", "hunter", "nter2", "olibri", "2291-S")) {
                    assertFalse(part.contains(secret), from + "+" + length + ": " + part);
                }
            }
        }
        String found = ContextFit.read(1, readable, null, null, "password=", 0);
        assertTrue(found.contains("password=" + Redactor.SECRET_REMOVED), found);
        // Mutation: cut the bytes as they are -> from just after "password=" reads hunter2abc99.
    }

    @Test
    @DisplayName("a cut never falls inside an identifier: a part grows to hold the whole of it")
    void identifiersAreNotCut() {
        String text = "y".repeat(100) + " write to petr.novak@example.org today";
        var readable = cut(text);
        int at = text.indexOf("novak");
        String part = ContextFit.read(1, readable, (long) at, 3L, null, 0);
        assertTrue(part.contains("\npetr.novak@example.org\n") || part.endsWith("\npetr.novak@example.org"), part);
        String long_ = "z".repeat(1_990) + " petr.novak@example.org " + "z".repeat(5_000);
        String preview = ContextFit.shown(turn("t", long_), 1, cut(long_));
        assertTrue(preview.contains("z".repeat(1_990) + " petr.novak@example.org\n[… folded"), preview);
    }

    @Test
    @DisplayName("a cut never falls inside one of the user's own named values: an SSID the placeholder table knows")
    void namedValuesAreNotCut() {
        var redactor = new Redactor(null);
        redactor.filter("u1", "config wifi-iface\n\toption ssid 'KazdaHome5G'\n", Map.of(), new Redactor.Tally());
        String log = "roamed to KazdaHome5G then left";
        var readable = redactor.cuttable("u1", log, Map.of());
        String part = ContextFit.read(1, readable, (long) log.indexOf("Home5G"), 4L, null, 0);
        assertTrue(part.contains("\nKazdaHome5G"), part);
        assertFalse(part.contains("\nHome5G"), part);
        // Mutation: cut without the user's table -> "Home5G", which the gateway no longer knows.
    }

    @Test
    @DisplayName("the read budget is the model's that overflowed: on another model a read takes the rest until that one overflows")
    void budgetPerModel() {
        var ctx = new AgentContext("u1", "t1", "go");
        ctx.trajectory().record(new AgentAction("read_log", Map.of(), ""),
                AgentObservation.success("read_log", "x".repeat(100_000), Map.of(), 1));
        ctx.requestSize(110_000, "anthropic/claude-opus-5-5");
        assertEquals(List.of(0), ctx.fitToWindow(30_000));
        ctx.requestSize(12_000, "anthropic/claude-opus-5-5");
        assertEquals((60_000 - 12_000) / 2, ctx.readChunk());
        ctx.requestSize(12_000, "ollama/qwen3");
        assertEquals(0, ctx.readChunk(), "another model's window is not this one's");
        // Mutation: keep the budget across models -> 24,000 on the local model too.
    }

    @Test
    @DisplayName("find shows places whose text meets once: a letter on every line of one long line is that line once")
    void findMerges() {
        String line = "abcde".repeat(24_000);   // 120,000 characters, one line
        String found = ContextFit.read(1, cut(line), null, null, "e", 0);
        assertTrue(found.startsWith("\"e\" occurs in 24,000 places in step 1's result"), found.substring(0, 80));
        assertTrue(found.contains("\n@4 to @119999 (24,000 places): abcde"), found.substring(0, 400));
        assertTrue(found.length() < line.length() + 1_000, "the line once, not once for every e: " + found.length());
        // Mutation: show every place on its own -> about 14,000,000 characters.
    }
}
