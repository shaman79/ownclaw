package com.ownclaw.interfaces.web;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * How the rows of a chat are laid out: one content column on the assistant's side, the rhythm of
 * runs, a task's link in the answer's meta line, long content scrolling inside its row. There is no
 * layout engine in the suite, so the rules that decide are pinned as text, whitespace collapsed; the
 * page itself is measured in a browser outside it.
 */
class ChatLayoutPageTest {

    static String page;

    @BeforeAll
    static void read() throws Exception {
        try (var in = ChatLayoutPageTest.class.getResourceAsStream("/static/index.html")) {
            assertNotNull(in, "static/index.html is not on the classpath");
            page = new String(in.readAllBytes(), StandardCharsets.UTF_8).replaceAll("\\s+", " ");
        }
    }

    @Test
    @DisplayName("answers, steps, notes and system lines share one content column: where an answer's bubble starts beside its avatar")
    void oneContentColumn() {
        assertTrue(page.contains(".msg-avatar { width: 30px; height: 30px;"), "the avatar is 30px wide");
        assertTrue(page.contains(".msg.response, .msg.error, .msg.question, .msg.input_request, .msg.debug "
                + "{ display: flex; gap: 12px;"), "with 12px beside it");
        assertTrue(page.contains(".msg.system, .msg.progress { padding-left: 42px;"),
                "so a secondary row starts 42px in, where the bubble does");
        assertTrue(page.contains(".msg.system > .msg-main, .msg.progress > .msg-main { border-left: 2px solid var(--border-mid);"),
                "behind one rule, its meta line too");
        assertFalse(page.contains("classList.add('short')"), "no centred pill for a short system line");
        assertFalse(page.contains(".msg.progress { width: auto;"), "a step no longer runs to the pane's right edge");
        // Mutation: drop the padding -> steps and system lines start 42px left of the answers, and
        // the harness's check of one column fails.
    }

    @Test
    @DisplayName("the rows of one side close together, more room where the speaker changes")
    void theRhythmOfRuns() {
        assertTrue(page.contains(".msg + .msg { margin-top: 8px }"), "within a run");
        assertTrue(page.contains(".msg.user + .msg:not(.user), .msg:not(.user) + .msg.user { margin-top: 24px }"),
                "where the owner's side and the assistant's meet, a task's steps after his message too");
        assertFalse(page.contains(".msg.first-in-run { margin-top"),
                "first-in-run keeps the avatar and the corners; the room comes from the sides alone");
        assertFalse(page.contains(".msg.system { align-self: center;"), "a system line is not set apart in the middle");
    }

    @Test
    @DisplayName("an answer of a task says what it did in its meta line, after its time, never inside the markdown")
    void theTaskLinkIsInTheMetaLine() {
        String add = page.substring(page.indexOf("function addMsg("));
        add = add.substring(0, add.indexOf(" function addProgress("));
        assertTrue(add.contains("var trace = node('button', 'msg-trace-link', 'What this task did \\u2192');"), add);
        assertTrue(add.contains("meta.appendChild(trace);"), "in the meta line: " + add);
        assertFalse(add.contains("body.appendChild(trace)"), "not in the bubble: " + add);
        assertTrue(add.indexOf("if (at) meta.appendChild(timeOf(at));") < add.indexOf("meta.appendChild(trace);"),
                "after the time: " + add);
        assertTrue(page.contains(".msg-trace-link { margin: -6px 0; padding: 6px 0;"),
                "a finger's room on a phone, without making the line taller");
    }

    @Test
    @DisplayName("long content scrolls inside its row: code, tables and unbroken words never push the page sideways")
    void longContentStaysInItsRow() {
        assertTrue(page.contains(".msg-main { display: flex; flex-direction: column; min-width: 0; }"),
                "a row's content may be narrower than its widest line");
        assertTrue(page.contains(".msg-avatar + .msg-main { flex: 1 }"), "an answer takes the column beside the avatar");
        assertTrue(page.contains(".msg { animation: msgIn .2s cubic-bezier(.16,1,.3,1) both; overflow-wrap: anywhere;"),
                "an unbroken word breaks");
        assertTrue(page.contains(".md table { border-collapse: collapse; margin: 8px 0; width: 100%; display: block; overflow-x: auto }")
                && page.contains("overflow-wrap: normal; word-break: normal }"),
                "a table scrolls in its row, its cells broken between words only");
        assertTrue(page.contains(".md pre, .md table { scrollbar-width: thin;"), "on a thin, dark bar");
        // Mutation: drop min-width: 0 -> a long code line widens the answer past the screen at 375px.
    }
}
