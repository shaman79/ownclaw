package com.ownclaw.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * How much tool output survives into the next prompt.
 * <p>
 * The rule used to be "the last two turns in full, everything older crushed to 150 characters of
 * head and 150 of tail". That makes a whole class of task impossible rather than merely lossy:
 * "read these three pages and compare them" cannot work, because by the time the third page
 * arrives the first is a 300-character stub and the model is comparing summaries it was never
 * given. It was wasteful in the other direction too — two turns holding a 200 KB page each were
 * re-sent in full on every step.
 */
class ObservationRetentionTest {

    private static AgentTrajectory withOutputs(int... sizes) {
        var t = new AgentTrajectory();
        for (int i = 0; i < sizes.length; i++) {
            t.record(new AgentAction("fetch_page", Map.of("n", i), "read page " + i),
                    AgentObservation.success("fetch_page", "x".repeat(sizes[i]), Map.of(), 10));
        }
        return t;
    }

    private static int firstKept(AgentTrajectory t, int budget) {
        return AgentTrajectory.firstTurnKeptInFull(t.turns(), budget);
    }

    @Test
    @DisplayName("three pages that fit the budget are all kept in full")
    void threePagesFit() {
        var t = withOutputs(10_000, 10_000, 10_000);
        assertEquals(0, firstKept(t, 60_000),
                "all three must survive, or 'compare these three pages' cannot be answered");
    }

    @Test
    @DisplayName("the old fixed rule would have stubbed the first page")
    void theOldRuleWasTheBug() {
        var t = withOutputs(10_000, 10_000, 10_000);
        int oldRule = Math.max(0, t.turns().size() - 2);   // what the code used to do
        assertEquals(1, oldRule, "the old rule kept only the last two");
        assertTrue(firstKept(t, 60_000) < oldRule, "the budget keeps strictly more");
    }

    @Test
    @DisplayName("past the budget the OLDEST output is what gets dropped")
    void oldestIsDroppedFirst() {
        var t = withOutputs(50_000, 50_000, 50_000);
        int first = firstKept(t, 60_000);
        assertTrue(first > 0, "not everything can be kept at this size");
        assertEquals(2, first, "only the newest fits, and it is the newest that is kept");
    }

    @Test
    @DisplayName("the newest turn is always kept, however large")
    void newestAlwaysSurvives() {
        var t = withOutputs(500_000);
        assertEquals(0, firstKept(t, 60_000),
                "a model that cannot see the result of the step it just took cannot take the next");
        var t2 = withOutputs(10, 500_000);
        assertEquals(1, firstKept(t2, 60_000), "the huge newest turn is kept; the old one is not");
    }

    @Test
    @DisplayName("many small outputs all survive")
    void manySmallSurvive() {
        var t = withOutputs(100, 100, 100, 100, 100, 100, 100, 100);
        assertEquals(0, firstKept(t, 60_000), "nothing here comes close to the budget");
    }

    @Test
    @DisplayName("an empty trajectory and null outputs do not blow up")
    void degenerateInputs() {
        assertEquals(-1, AgentTrajectory.firstTurnKeptInFull(List.of(), 60_000),
                "no turns: nothing to keep");
        var t = new AgentTrajectory();
        t.record(new AgentAction("noop", Map.of(), "r"),
                AgentObservation.success("noop", null, Map.of(), 1));
        assertEquals(0, firstKept(t, 60_000));
    }

    @Test
    @DisplayName("the rendered prompt actually contains all three pages")
    void renderedPromptKeepsThem() {
        var t = new AgentTrajectory();
        for (String marker : List.of("ALPHA", "BETA", "GAMMA")) {
            t.record(new AgentAction("fetch_page", Map.of(), "read " + marker),
                    AgentObservation.success("fetch_page", marker + "-" + "y".repeat(5_000), Map.of(), 10));
        }
        String prompt = t.toPromptSummary();
        for (String marker : List.of("ALPHA", "BETA", "GAMMA")) {
            assertTrue(prompt.contains(marker), marker + " was dropped from the prompt");
        }
        assertFalse(prompt.contains("middle omitted"),
                "nothing needed truncating at this size: " + prompt.length() + " chars");
    }
}
