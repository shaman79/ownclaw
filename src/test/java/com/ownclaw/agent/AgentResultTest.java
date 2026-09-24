package com.ownclaw.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The owner's text travels with the result as far as the screen that shows it. */
class AgentResultTest {

    @Test
    @DisplayName("stamping the task id keeps the owner's text")
    void theTaskIdStampKeepsTheOwnersText() {
        // The loop stamps the id after the answer is made; a stamp that dropped the owner's text
        // would deliver the note that the answer exists, and lose the answer.
        var r = AgentResult.completed(AgentLoop.PRIVATE_NOTE, new AgentTrajectory(), 1)
                .withOwnerText("the answer")
                .withTaskId("abcd1234");
        assertEquals("the answer", r.ownerText());
        assertEquals("the answer", r.shown());
        assertEquals(AgentLoop.PRIVATE_NOTE, r.response(), "everything else reads the note");
        assertEquals("abcd1234", r.taskId());

        var plain = AgentResult.completed("hello", new AgentTrajectory(), 1).withTaskId("abcd1234");
        assertNull(plain.ownerText());
        assertEquals("hello", plain.shown());
    }
}
