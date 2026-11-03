package com.ownclaw.agent;

import com.ownclaw.llm.Replies;
import com.ownclaw.llm.ToolCall;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.ownclaw.agent.LoopRig.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * One native tool call runs per step. A reply that made more used to have the others dropped
 * with nobody told: the model believed them made.
 */
class CallsNotRunTest {

    private static Reply calls(ToolCall... made) {
        return c -> Replies.of("", 1_000, 100, 0, 0, "tool_use", List.of(made));
    }

    private static final String NOT_RUN = "Your reply made 3 tool calls, and a step runs one: the first. "
            + "These did not run:\n- ping {\"n\":2}\n- note {\"text\":\"the router answered\"}\n"
            + "Make them again, one per step, if they are still needed.";

    @Test
    @DisplayName("the calls a reply made beyond the first are named in the step's observation, and the model reads them")
    void theOthersAreReported(@TempDir Path tmp) throws Exception {
        var pinged = new CopyOnWriteArrayList<Object>();
        var rig = new LoopRig(tmp, List.of(AssistantPartsTest.tool("ping", List.of(), p -> {
            pinged.add(p.get("n"));
            return "pong";
        }), AssistantPartsTest.tool("note", List.of(), p -> "noted")));
        rig.cloud.think.add(calls(new ToolCall("a", "ping", Map.of("n", 1)),
                new ToolCall("b", "ping", Map.of("n", 2)),
                new ToolCall("c", "note", Map.of("text", "the router answered"))));
        rig.cloud.think.add(respond("Pinged."));

        AgentResult r = rig.turn(rig.chat.createSession("u1", "Ping"), "ping the router");

        assertEquals(AgentResult.TerminationReason.COMPLETED, r.terminationReason(), r.response());
        assertEquals(List.of(1), pinged, "one call ran: the first");
        assertEquals("pong\n\n" + NOT_RUN, r.trajectory().turns().get(0).observation().output());
        String next = String.join("\n", userParts(rig.cloud.calls("think").get(1)));
        assertTrue(next.contains(NOT_RUN), "the model is told: " + next);
        // Mutation: run get(0) and say nothing of the rest -> the observation is "pong" alone.
    }

    @Test
    @DisplayName("an answer that came with other calls is not delivered: it would end the task with them dropped")
    void anAnswerWithOtherCallsWaits(@TempDir Path tmp) throws Exception {
        var pinged = new CopyOnWriteArrayList<Object>();
        var rig = new LoopRig(tmp, List.of(AssistantPartsTest.tool("ping", List.of(), p -> {
            pinged.add(p.get("n"));
            return "pong";
        })));
        rig.cloud.think.add(calls(new ToolCall("a", AgentAction.RESPOND, Map.of("message", "Done.")),
                new ToolCall("b", "ping", Map.of("n", 3))));
        rig.cloud.think.add(call("ping", Map.of("n", 3)));
        rig.cloud.think.add(respond("Done, and pinged."));

        AgentResult r = rig.turn(rig.chat.createSession("u1", "Ping"), "ping it and tell me");

        assertEquals("Done, and pinged.", r.response());
        assertEquals(List.of(3), pinged, "the dropped call was made again, as its own step");
        String told = r.trajectory().turns().get(0).observation().output();
        assertTrue(told.startsWith("Not delivered: it came with other tool calls, and an answer or a question "
                + "ends the task."), told);
        assertTrue(told.endsWith("These did not run:\n- ping {\"n\":3}\nMake them again, one per step, if they "
                + "are still needed."), told);
        // Mutation: deliver the answer -> "Done." ends the task, and the ping is never made.
    }
}
