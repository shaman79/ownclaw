package com.ownclaw.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** How a recall lists past tasks, and how the ids to open are read from what the model wrote. */
class RecallListTest {

    @Test
    @DisplayName("a past task is listed by how its request began: its first line that is not blank, to 160 characters, never half a character")
    void beginning() {
        assertEquals("router audit", AgentLoop.beginning("Task: router audit\nSteps: 2"));
        assertEquals("no prefix here", AgentLoop.beginning("no prefix here"));
        assertEquals("the request on line two", AgentLoop.beginning("Task: \n\n  the request on line two\nSteps: 1"));
        String longLine = "Task: " + "a".repeat(159) + "😀" + "tail";
        assertEquals("a".repeat(159) + "😀…", AgentLoop.beginning(longLine));
        assertEquals("", AgentLoop.beginning(""));
    }

    @Test
    @DisplayName("open is read as ids, each once, as the numbers they are; anything else refuses, naming it")
    void openIds() {
        assertEquals(List.of("12", "15"), AgentLoop.openIds("12, 15"));
        assertEquals(List.of("12", "15", "7"), AgentLoop.openIds("id 12; #15 7"));
        assertEquals(List.of("4"), AgentLoop.openIds("4, 04"), "the same id twice");
        assertEquals(List.of("12", "3"), AgentLoop.openIds(List.of(12.0, "3")), "a list, a whole number");
        assertEquals(List.of("0"), AgentLoop.openIds("000"));
        for (Object bad : List.of("12 (router audit, 2026-09-29)", "{{3}}", "12.0", "twelve", List.of(1.5), -3)) {
            var e = assertThrows(IllegalArgumentException.class, () -> AgentLoop.openIds(bad), String.valueOf(bad));
            assertTrue(e.getMessage().endsWith("is not an id"), e.getMessage());
        }
        assertTrue(AgentLoop.asksToOpen("7"));
        assertFalse(AgentLoop.asksToOpen(" "));
        assertFalse(AgentLoop.asksToOpen(List.of()));
        assertFalse(AgentLoop.asksToOpen(null));
        // Mutation: split on everything but digits -> "12 (router audit, 2026-09-29)" opens 9 and 29.
    }
}
