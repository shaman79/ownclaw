package com.ownclaw.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownclaw.agent.tools.Tool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.ownclaw.agent.LoopRig.call;
import static com.ownclaw.agent.LoopRig.respond;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A delegation of exact calls through the agent loop: what the cloud hands it, what the owner's
 * chat and the task page are shown, and what is refused before any call runs.
 */
class DelegationCallsLoopTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    static String session(LoopRig rig) {
        return rig.chat.createSession("u1", "Network");
    }

    /** A tool that records what it was handed and answers {@code output}. */
    static Tool recording(String name, List<Map<String, Object>> calls, String output) {
        return AssistantPartsTest.tool(name, List.of(), p -> {
            calls.add(p);
            return output;
        });
    }

    @Test
    @DisplayName("calls with no goal are a delegation: they run, the chat shows each as a call, the step row keeps them")
    void callsAloneRunThroughTheLoop(@TempDir Path tmp) throws Exception {
        var pinged = new CopyOnWriteArrayList<Map<String, Object>>();
        var rig = new LoopRig(tmp, List.of(recording("ping", pinged, "pong from 192.0.2.10")));
        rig.cloud.think.add(call(AgentAction.DELEGATE, Map.of("calls",
                List.of(Map.of("tool", "ping", "params", Map.of("host", "192.0.2.10"))))));
        rig.cloud.think.add(respond("It answers."));

        AgentResult r = rig.turn(session(rig), "is 192.0.2.10 up?");

        assertEquals(AgentResult.TerminationReason.COMPLETED, r.terminationReason(), r.response());
        assertEquals(List.of(Map.of("host", "192.0.2.10")), pinged);
        assertTrue(String.join("\n", LoopRig.userParts(rig.cloud.calls("think").get(1))).contains("pong from 192.0.2.10"),
                "the result came back to the cloud");
        var rows = rig.jdbc.queryForList("SELECT content, private_content FROM conversations WHERE role = 'progress' "
                + "ORDER BY id");
        assertTrue(rows.stream().anyMatch(row -> String.valueOf(row.get("content")).startsWith("🏠 Call 1 · ping")
                        && String.valueOf(row.get("private_content")).contains("192.0.2.10")),
                "the owner sees the call, with its arguments: " + rows);
        String step = rig.jdbc.queryForObject("SELECT details FROM events WHERE event_type = 'step' "
                + "AND json_extract(details, '$.tool') = 'delegate'", String.class);
        var details = JSON.readTree(step);
        assertFalse(details.has("goal"), "no blank goal: " + step);
        assertEquals("ping", details.path("calls").path(0).path("tool").asText(), step);
        assertTrue(details.path("calls").path(0).path("params").asText().contains("192.0.2.10"), step);
        assertTrue(details.path("success").asBoolean(), step);
        // Mutation: refuse a delegate with no goal, as before -> nothing runs.
    }

    @Test
    @DisplayName("a call holding a removed secret is refused before any call runs: the earlier ones would have run first")
    void aRemovedSecretStopsThemAll(@TempDir Path tmp) throws Exception {
        var sent = new CopyOnWriteArrayList<Map<String, Object>>();
        var pinged = new CopyOnWriteArrayList<Map<String, Object>>();
        var rig = new LoopRig(tmp, List.of(recording("ping", pinged, "pong"), recording("smtp_send_email", sent, "Sent")));
        rig.cloud.think.add(call(AgentAction.DELEGATE, Map.of("calls", List.of(
                Map.of("tool", "ping", "params", Map.of("host", "192.0.2.10")),
                Map.of("tool", "smtp_send_email", "params", Map.of("body", "password: «secret removed»"))))));
        rig.cloud.think.add(respond("I could not send it."));

        AgentResult r = rig.turn(session(rig), "ping it and mail me the password");

        assertEquals(AgentResult.TerminationReason.COMPLETED, r.terminationReason(), r.response());
        assertTrue(pinged.isEmpty(), "not even the first call ran");
        assertTrue(sent.isEmpty());
        assertTrue(String.join("\n", LoopRig.userParts(rig.cloud.calls("think").get(1)))
                .contains("Not started: a call holds a removed secret."), "and the cloud is told why");
    }

    @Test
    @DisplayName("calls refused before any of them ran are steps that ran nothing: three in a row end the task")
    void callsThatRanNothingAreCounted(@TempDir Path tmp) throws Exception {
        var sent = new CopyOnWriteArrayList<Map<String, Object>>();
        var rig = new LoopRig(tmp, List.of(recording("smtp_send_email", sent, "Sent")));
        for (int i = 0; i < 8; i++) {
            rig.cloud.think.add(call(AgentAction.DELEGATE, Map.of("calls",
                    List.of(Map.of("tool", "smtp_send_email", "params", Map.of("body", "{{7.body}}"))))));
        }

        AgentResult r = rig.turn(session(rig), "mail me result seven");

        assertEquals(AgentResult.TerminationReason.FAILURE_LIMIT, r.terminationReason(), r.response());
        assertEquals(3, rig.cloud.calls("think").size(), "three, then the task ends");
        assertTrue(sent.isEmpty());
        String step = rig.jdbc.queryForList("SELECT details FROM events WHERE event_type = 'step' "
                + "AND json_extract(details, '$.tool') = 'delegate'", String.class).get(0);
        assertTrue(JSON.readTree(step).path("notStarted").asBoolean(), "the page says nobody carried it out: " + step);
    }
}
