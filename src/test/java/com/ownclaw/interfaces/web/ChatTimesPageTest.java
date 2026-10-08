package com.ownclaw.interfaces.web;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The page's side of when each row of a chat was saved: every row it draws -- a message, an
 * answer, a system line, a progress row -- shows it, from the history and live alike. There is no
 * JS engine in the suite, so the lines that decide are pinned as text, whitespace collapsed; the
 * page itself is driven in a browser outside it.
 */
class ChatTimesPageTest {

    static String page;

    @BeforeAll
    static void read() throws Exception {
        try (var in = ChatTimesPageTest.class.getResourceAsStream("/static/index.html")) {
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
    @DisplayName("a row of the history shows when it was saved, its timestamp read as UTC")
    void historyRowsShowTheirTimestamp() {
        String draw = function("drawRow");
        assertTrue(draw.contains("addProgress(m.content, m.progress, before, m.timestamp);")
                && draw.contains("addMsg(type, m.content, m.task_id, before, m.timestamp);"), draw);
        // SQLite's datetime('now') is UTC with no zone: read as local time, every row of a chat
        // in Prague would show two hours early.
        assertTrue(function("tdTime").contains("var d = new Date(String(s).replace(' ', 'T') + 'Z');"),
                function("tdTime"));
        assertTrue(function("rowTime").contains("return tdTime(timestamp) || (before ? null : new Date());"),
                "a saved row without one shows none, rather than the time it is drawn: " + function("rowTime"));
        // Mutation: drop + 'Z' -> the harness's 06:34:08 UTC row shows 06:34 in Prague, not 08:34.
    }

    @Test
    @DisplayName("a row drawn live shows the time its frame says it was saved, or this page's clock when it says none")
    void liveRowsShowTheirFramesTime() {
        assertTrue(page.contains("addMsg('input_request', content, data.taskId, null, data.timestamp);"), "a question");
        assertTrue(page.contains("addProgress(content, data.progress, null, data.timestamp);"), "a progress row");
        assertTrue(page.contains("addMsg('response', content, data.taskId, null, data.timestamp);"), "a result");
        assertTrue(page.contains("var drawnMsg = addMsg(type, content, data.taskId, null, data.timestamp);"),
                "an answer, a system line");
        assertTrue(function("send").contains("addMsg('user', display)"),
                "the owner's message, drawn as it is sent, on this page's clock");
        assertTrue(page.contains("var savedAt = tdTime(data.timestamp); "
                + "var sentAt = sentBubble ? sentBubble.querySelector('time.msg-time') : null; "
                + "if (sentAt && savedAt) showTime(sentAt, savedAt);"),
                "then the time it was saved, which its fate says, as a reload will show it");
        // Mutation: leave data.timestamp out of the answer's addMsg -> a live answer shows the
        // page's clock, a reload the time it was saved.
    }

    @Test
    @DisplayName("every kind of row is stamped in one place: the meta line under its content; a step at the end of its header")
    void everyRowIsStamped() {
        String add = function("addMsg");
        assertTrue(add.contains("var at = rowTime(timestamp, before);"), add);
        assertTrue(add.contains("var main = node('div', 'msg-main'); var meta = node('div', 'msg-meta'); "
                + "if (at) meta.appendChild(timeOf(at));"), "one meta line, for every kind of row drawn here: " + add);
        assertEquals(1, add.split("timeOf\\(", -1).length - 1, "and no other place for a time: " + add);
        String last = "if (meta.firstChild) main.appendChild(meta); div.appendChild(main);";
        assertTrue(add.contains(last), "under the content: " + add);
        for (String content : new String[] {"main.appendChild(bubble);", "main.appendChild(body);", "main.appendChild(words);"}) {
            assertTrue(add.indexOf(content) >= 0 && add.indexOf(content) < add.indexOf(last),
                    "a message's bubble, an answer's body, a system line's or a note's words, then the meta line: " + content);
        }
        String progress = function("addProgress");
        assertTrue(progress.contains("addMsg('progress', text, null, before, timestamp);"), "a note: " + progress);
        assertTrue(progress.contains("var at = rowTime(timestamp, before); if (at) head.appendChild(timeOf(at)); "
                + "main.appendChild(head);"), "the end of a step's header, its last item: " + progress);
        // Mutation: put an answer's time back at the start of its body -> the harness's answer shows
        // it in its top corner, its message under its bubble, and the placement check fails.
        assertTrue(function("timeOf").contains("return showTime(node('time', 'msg-time'), at);"),
                "a <time>, which no row's markdown makes: the one fate finds is the row's own");
        assertFalse(page.contains("TIME: ["), "the markdown allowlist lets through no <time>");
    }

    @Test
    @DisplayName("in the browser's own language: the time today, with the date on another day; the whole moment in the tooltip")
    void shownInTheBrowsersLanguage() {
        String show = function("showTime");
        assertTrue(show.contains("var shown = { hour: '2-digit', minute: '2-digit' }; "
                + "if (at.toDateString() !== now.toDateString()) { shown.day = 'numeric'; shown.month = 'numeric'; "
                + "if (at.getFullYear() !== now.getFullYear()) shown.year = 'numeric'; }"), show);
        assertTrue(show.contains("time.textContent = at.toLocaleString(undefined, shown);"), show);
        assertTrue(show.contains("time.title = at.toLocaleString(undefined, { dateStyle: 'full', timeStyle: 'medium' });"),
                "to the second: " + show);
        assertTrue(show.contains("time.dateTime = at.toISOString();"), show);
        assertFalse(show.matches(".*'(cs|en)(-[A-Z]{2})?'.*"), "no language of its own: " + show);
    }

    @Test
    @DisplayName("small and muted, under the row's content at its own edge: the owner's bubble's right, the column's left")
    void smallAndMuted() {
        assertTrue(page.contains(".msg-time { font-size: 11px; line-height: 16px; color: var(--text-3); "
                + "white-space: nowrap; font-variant-numeric: tabular-nums; user-select: none; }"));
        assertTrue(page.contains(".msg-meta { display: flex; flex-wrap: wrap; align-items: center; gap: 0 6px; "
                + "margin-top: 4px;"), "a line of its own, under the content");
        assertTrue(page.contains(".msg.user .msg-main { max-width: 72%; align-items: flex-end; }"),
                "under the owner's bubble, at its right edge");
        assertTrue(page.contains(".msg-meta > .msg-time:not(:last-child)::after { content: '\\00B7'; margin-left: 6px }"),
                "\"20:06 · What this task did\"");
        assertTrue(page.contains(".progress-head > .msg-time::before { content: ' \\00B7\\00A0' }"),
                "a step's, after a dot as its header's own items are");
        for (String old : new String[] {".msg.user > .msg-time", ".msg-body > .msg-time", "> .msg-time { float",
                ".progress-head > .msg-time { margin-left: auto"}) {
            assertFalse(page.contains(old), "no time beside a bubble, in a corner or at the far end of a header: " + old);
        }
        // Mutation: drop the user row's align-items: flex-end -> the time under the owner's bubble
        // starts at its left, and the harness's check of the right edge fails.
    }
}
