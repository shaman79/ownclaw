package com.ownclaw.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** A file is read only by the local model, so without it a task holding one stops before the cloud. */
class StopWithoutLocalModelTest {

    private static AgentContext fileTask() {
        var ctx = new AgentContext("u1", "t1", "summarise this statement");
        ctx.addFile("f1", "", List.of("uploaded file", "application/pdf, 84211 bytes, not text or too large"));
        return ctx;
    }

    @Test
    @DisplayName("a file with the local model down ends the task and says why")
    void aFileWithTheLocalModelDownStops() {
        AgentResult r = AgentLoop.stopWithoutLocalModel(fileTask(), () -> false);
        assertNotNull(r);
        assertEquals(AgentResult.TerminationReason.ERROR, r.terminationReason());
        assertEquals(AgentLoop.LOCAL_DOWN_FOR_FILES, r.response());
    }

    @Test
    @DisplayName("a file with the local model up goes on")
    void aFileWithTheLocalModelUpGoesOn() {
        assertNull(AgentLoop.stopWithoutLocalModel(fileTask(), () -> true));
    }

    @Test
    @DisplayName("a task with no file goes on, and the local model is not even asked")
    void noFileNoProbe() {
        int[] probes = {0};
        assertNull(AgentLoop.stopWithoutLocalModel(new AgentContext("u1", "t1", "hello"),
                () -> { probes[0]++; return false; }));
        assertEquals(0, probes[0]);
    }
}
