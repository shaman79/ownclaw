package com.ownclaw.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownclaw.llm.EgressLedger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Why a cloud reply ended, from the ledger row to the task page where the owner is sent to look:
 * a refused request could not be told from an empty answer afterwards while nothing kept it.
 */
class ReplyEndedOnTaskPageTest {

    /** A ledger row written by the real ledger, read back by the real task-page builder. */
    private static Map<String, Object> call(String stopReason) {
        var details = new ArrayList<String>();
        var events = new EventLogService(null) {
            @Override
            public void log(String userId, String taskId, String type, String severity,
                            String summary, String json, int tokens) {
                details.add(json);
            }
        };
        new EventEgressLedger(events, new ObjectMapper()).record(new EgressLedger.Row("u1", "t1",
                "think", "anthropic", "claude-opus-5", EgressLedger.Decision.SENT, List.of(), 66_522,
                34, 284, 0, 1830, 29072, 0.03, 0, 0, null, stopReason));

        var row = new LinkedHashMap<String, Object>();
        row.put("id", 1L);
        row.put("timestamp", "2026-09-29 08:02:38");
        row.put("event_type", "egress");
        row.put("severity", "info");
        row.put("summary", "SENT think");
        row.put("details", details.get(0));
        @SuppressWarnings("unchecked")
        var calls = (List<Map<String, Object>>) TaskTraceService.build(List.of(row)).get("calls");
        return calls.get(0);
    }

    @Test
    @DisplayName("a refused call says so on the task page")
    void refusalIsShown() {
        assertEquals("refusal (cyber)", call("refusal (cyber)").get("stopReason"));
        assertEquals("end_turn", call("end_turn").get("stopReason"));
    }

    @Test
    @DisplayName("a row with no stop reason -- no reply, or written before it was kept -- shows none")
    void absentIsNull() {
        var c = call(null);
        assertTrue(c.containsKey("stopReason"));
        assertNull(c.get("stopReason"));
    }

    @Test
    @DisplayName("the page draws it as a \"Reply ended\" row")
    void thePageShowsIt() throws Exception {
        String page;
        try (var in = ReplyEndedOnTaskPageTest.class.getResourceAsStream("/static/index.html")) {
            assertNotNull(in, "static/index.html is not on the classpath");
            page = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        assertTrue(page.contains("if (c.stopReason) d.appendChild(tdRow('Reply ended', c.stopReason));"));
    }
}
