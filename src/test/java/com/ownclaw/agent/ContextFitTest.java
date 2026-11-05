package com.ownclaw.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Folding a task's results to fit a model's window, and reading them back whole. */
class ContextFitTest {

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
        var first = ContextFit.plan(turns, Map.of(), 100_000, null, 0);
        assertEquals(List.of(1), List.copyOf(first.keySet()), "the largest: " + first.keySet());
        // Under 57,750 of 110,000 (a second round): the largest, then the older of the equals.
        var deeper = ContextFit.plan(turns, Map.of(), 110_000, null, 1);
        assertEquals(List.of(1, 0), List.copyOf(deeper.keySet()));
        // Already folded results are not folded again; a short one and the owner's message never.
        var rest = ContextFit.plan(turns, Map.of(0, first.get(1), 1, first.get(1), 2, first.get(1)), 100_000, null, 3);
        assertTrue(rest.isEmpty(), "nothing left to fold: " + rest.keySet());
        // Mutations: sort ascending -> the 10,000 ones first; fold byTheLoop turns -> the message.
    }

    @Test
    @DisplayName("a folded result shows its tool line, its first 2,000 characters, its size and how to read the rest")
    void shown() {
        // An emoji -- two chars -- across the 2,000th char: the preview ends after it, never inside.
        String output = "a".repeat(1_999) + "😀" + "b".repeat(5_000);
        var t = turn("read_access_log", output);
        String shown = ContextFit.shown(t, 4, 60_000);
        assertTrue(shown.startsWith("[read_access_log] OK (7ms)\n[Step 4's result is 7,001 characters: "), shown);
        assertTrue(shown.contains("read_result with step=4 reads any part of it -- from, a character offset, and "
                + "length; about 60,000 characters fit at a time now -- and with find, every place a text occurs in it.]"), shown);
        assertTrue(shown.contains("a".repeat(1_999) + "😀\n[… folded: characters 2,001-7,001 of step 4's result]"), shown);
        assertFalse(shown.contains("bb"), "only the beginning");
    }

    @Test
    @DisplayName("read_result reads any part, says what follows, and refuses an offset outside the result")
    void read() {
        String output = "0123456789".repeat(10);   // 100 characters
        assertEquals("Step 2's result, characters 10-20 of 100:\n0123456789\n[… characters 20-100 follow: "
                + "read_result step=2 from=20]", ContextFit.read(2, output, 10L, 10L, null, 0));
        assertEquals("Step 2's result, characters 90-100 of 100:\n0123456789",
                ContextFit.read(2, output, 90L, Long.MAX_VALUE, null, 0), "to the end, however long the ask");
        assertTrue(ContextFit.read(2, output, null, null, null, 30).startsWith("Step 2's result, characters 0-30 of 100:"),
                "what fits at a time, when no length is asked");
        assertTrue(ContextFit.read(2, output, null, null, null, 0).endsWith(output), "the rest of it, when nothing was folded");
        assertTrue(ContextFit.read(2, output, 101L, null, null, 0).startsWith("ERROR: from=101 is outside"));
        assertTrue(ContextFit.read(2, output, -1L, null, null, 0).startsWith("ERROR: from=-1 is outside"));
        assertTrue(ContextFit.read(2, output, 0L, 0L, null, 0).startsWith("ERROR: length must be a positive"));
        String emoji = "ab😀cd";
        assertEquals("Step 1's result, characters 2-4 of 6:\n😀\n[… characters 4-6 follow: read_result step=1 from=4]",
                ContextFit.read(1, emoji, 3L, 1L, null, 0), "never half a character");
    }

    @Test
    @DisplayName("find lists every place a text occurs, case aside, with its offset and the text around it in its line")
    void find() {
        String output = "header\n" + "x".repeat(400) + "Invoice 2024/17 paid" + "y".repeat(400) + "\nnext line: INVOICE 9\n";
        String found = ContextFit.read(3, output, null, null, "invoice", 0);
        assertTrue(found.startsWith("\"invoice\" occurs in 2 places in step 3's result"), found);
        int first = output.indexOf("Invoice");
        assertTrue(found.contains("\n@" + first + ": …" + "x".repeat(300) + "Invoice 2024/17 paid" + "y".repeat(287) + "…"),
                "300 characters either side, within the line: " + found);
        assertTrue(found.contains("\n@" + output.indexOf("INVOICE") + ": next line: INVOICE 9"), found);
        assertEquals("\"faktura\" does not occur in step 3's result (" + String.format(java.util.Locale.ROOT, "%,d",
                output.length()) + " characters, case aside).", ContextFit.read(3, output, null, null, "faktura", 0));
        assertTrue(ContextFit.read(3, "a.b", null, null, ".", 0).contains("occurs in 1 place"), "a text, not a pattern");
        assertTrue(ContextFit.read(3, "Škoda škoda", null, null, "ŠKODA", 0).contains("occurs in 2 places"), "any script");
    }

    @Test
    @DisplayName("read_result names the step it cannot read, and takes numbers as a model writes them")
    void readResultArguments() {
        var ctx = new AgentContext("u1", "t1", "go");
        ctx.trajectory().record(new AgentAction("ping", Map.of(), ""), AgentObservation.success("ping", "pong", Map.of(), 1));
        assertTrue(AgentLoop.readResult(Map.of(), ctx).startsWith("ERROR: 'step' is required"));
        assertEquals("ERROR: this task has no step 2; its steps so far are 1 to 1.", AgentLoop.readResult(Map.of("step", 2), ctx));
        assertTrue(AgentLoop.readResult(Map.of("step", "one"), ctx).startsWith("ERROR: step, from and length are whole numbers"));
        assertTrue(AgentLoop.readResult(Map.of("step", 1.5), ctx).startsWith("ERROR: step, from and length are whole numbers"));
        assertEquals("Step 1's result, characters 1-3 of 4:\non\n[… characters 3-4 follow: read_result step=1 from=3]",
                AgentLoop.readResult(Map.of("step", 1.0, "from", " 1 ", "length", "2"), ctx));
        assertEquals(60_000L, AgentLoop.wholeNumber("60,000"));
        assertNull(AgentLoop.wholeNumber(" "));
    }

    @Test
    @DisplayName("a folded result stays folded, step after step, and the fold measures the request the engine sent")
    void foldedOnce() {
        var ctx = new AgentContext("u1", "t1", "go");
        String big = "line of the log\n".repeat(5_000);
        ctx.trajectory().record(new AgentAction("read_log", Map.of(), ""), AgentObservation.success("read_log", big, Map.of(), 1));
        ctx.trajectory().record(new AgentAction("ping", Map.of(), ""), AgentObservation.success("ping", "pong", Map.of(), 1));
        assertEquals(ctx.trajectory().turns().get(0).observationText(), ctx.observationShown(0, ctx.trajectory().turns().get(0)));
        ctx.requestSize(big.length() + 10_000);
        assertEquals(List.of(1), ctx.fitToWindow(262_144));
        String shown = ctx.observationShown(0, ctx.trajectory().turns().get(0));
        assertTrue(shown.contains("[Step 1's result is 80,000 characters"), shown);
        assertTrue(ctx.foldOf(0).orElseThrow().chunk() >= ContextFit.PREVIEW);
        ctx.requestSize(12_500);
        assertEquals(List.of(), ctx.fitToWindow(262_144), "nothing left to fold: pong is too short");
        assertEquals(shown, ctx.observationShown(0, ctx.trajectory().turns().get(0)), "the same bytes as before");
    }

    @Test
    @DisplayName("a recall lists how each past task's request began: its first line, to 160 characters, never half a character")
    void beginning() {
        assertEquals("router audit", AgentLoop.beginning("Task: router audit\nSteps: 2"));
        assertEquals("no prefix here", AgentLoop.beginning("no prefix here"));
        String longLine = "Task: " + "a".repeat(159) + "😀" + "tail";
        assertEquals("a".repeat(159) + "😀…", AgentLoop.beginning(longLine));
        assertEquals("", AgentLoop.beginning(""));
    }
}
