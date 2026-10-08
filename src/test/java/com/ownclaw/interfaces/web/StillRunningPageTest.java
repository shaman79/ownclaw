package com.ownclaw.interfaces.web;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The page's side of a task it has heard nothing of for long: it asks the server whether the task
 * still runs, and keeps the spinner and Stop while it does, instead of taking the silence for the
 * task's end. Read from the page's source, whitespace collapsed; the page itself is driven in a
 * browser outside it.
 */
class StillRunningPageTest {

    static String page;

    @BeforeAll
    static void read() throws Exception {
        try (var in = StillRunningPageTest.class.getResourceAsStream("/static/index.html")) {
            assertNotNull(in, "static/index.html is not on the classpath");
            page = new String(in.readAllBytes(), StandardCharsets.UTF_8).replaceAll("\\s+", " ");
        }
    }

    /** The body of a function of the page, from its declaration to the next function declared at its level. */
    static String function(String name) {
        int start = page.indexOf("function " + name + "(");
        assertTrue(start >= 0, name + " is not on the page");
        int end = page.indexOf(" function ", start + 1);
        return page.substring(start, end < 0 ? page.length() : end);
    }

    @Test
    @DisplayName("silence asks the server, and does not end the working state by itself")
    void silenceAsks() {
        String thinking = function("setThinking");
        assertTrue(thinking.contains("thinkingSafetyTimer = setTimeout(stillRunning, THINKING_SAFETY_MS);"), thinking);
        assertFalse(thinking.contains("900000"), "no timer of its own that ends the working state: " + thinking);
        String reset = function("resetThinkingSafetyTimer");
        assertTrue(reset.contains("thinkingSafetyTimer = setTimeout(stillRunning, THINKING_SAFETY_MS);"), reset);
        assertFalse(reset.contains("setThinking(false)"), reset);

        String ask = function("stillRunning");
        assertTrue(ask.contains("askedStillRunning = true;"), ask);
        assertTrue(ask.contains("ws.send(JSON.stringify({ type: 'ping' }))"), ask);
        assertTrue(ask.contains("thinkingSafetyTimer = setTimeout(stillRunning, THINKING_SAFETY_MS);"),
                "a lost pong is asked about again: " + ask);
        assertFalse(ask.contains("setThinking(false)"), ask);
    }

    @Test
    @DisplayName("the pong ends the working state only when no task runs")
    void thePongAnswers() {
        assertTrue(page.contains("if (type === 'pong' && askedStillRunning && typeof data.taskRunning === 'boolean') { "
                + "askedStillRunning = false; if (!data.taskRunning) { setThinking(false); doneActivity(); } }"));
        assertTrue(function("setThinking").contains("askedStillRunning = false;"), "a task that ended answers the question");
    }
}
