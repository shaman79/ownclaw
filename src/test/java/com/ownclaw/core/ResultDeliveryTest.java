package com.ownclaw.core;

import com.ownclaw.agent.AgentAction;
import com.ownclaw.agent.AgentObservation;
import com.ownclaw.agent.AgentResult;
import com.ownclaw.agent.AgentTrajectory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The one line on a delivered result about what stayed on this machine. Worded as what was
 * checked, computed from the run, and silent where it cannot see.
 */
class ResultDeliveryTest {

    private static AgentResult runWith(List<Map<String, Object>> artifacts) {
        var t = new AgentTrajectory();
        var structured = Map.<String, Object>of("delegatedTools", List.of("x"), "artifacts", artifacts);
        t.record(new AgentAction(AgentAction.DELEGATE, Map.of("goal", "g"), ""),
                AgentObservation.success(AgentAction.DELEGATE, "done", structured, 10));
        return AgentResult.completed("ok", t, 10);
    }

    @Test
    @DisplayName("counts the results and names only the withheld ones")
    void countsAndNames() {
        String line = ResultDelivery.withheldLine(runWith(List.of(
                Map.of("n", 1, "tool", "daily_news_digest", "label", "PUBLIC", "chars", 3000),
                Map.of("n", 2, "tool", "smtp_send_email", "label", "PRIVATE", "chars", 180),
                Map.of("n", 3, "tool", "smtp_send_email", "label", "PRIVATE", "chars", 180))));

        assertTrue(line.contains("3 results, 1 withheld from the cloud (smtp_send_email)"), line);
    }

    @Test
    @DisplayName("an all-public run says none were withheld")
    void allPublic() {
        String line = ResultDelivery.withheldLine(runWith(List.of(
                Map.of("n", 1, "tool", "daily_news_digest", "label", "PUBLIC", "chars", 3000))));
        assertTrue(line.contains("1 result, 0 withheld from the cloud"), line);
        assertFalse(line.contains("("), "nothing to name");
    }

    @Test
    @DisplayName("a run with nothing recorded says nothing at all")
    void silentWhereItCannotSee() {
        assertEquals("", ResultDelivery.withheldLine(AgentResult.completed("ok", new AgentTrajectory(), 1)));
        assertEquals("", ResultDelivery.withheldLine(null));
    }
}
