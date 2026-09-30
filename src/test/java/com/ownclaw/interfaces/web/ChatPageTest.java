package com.ownclaw.interfaces.web;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The page's side of delivery and listing. There is no JS engine in the suite, so the lines that
 * decide are pinned as text, whitespace collapsed.
 */
class ChatPageTest {

    static String page;

    @BeforeAll
    static void read() throws Exception {
        try (var in = ChatPageTest.class.getResourceAsStream("/static/index.html")) {
            assertNotNull(in, "static/index.html is not on the classpath");
            page = new String(in.readAllBytes(), StandardCharsets.UTF_8).replaceAll("\\s+", " ");
        }
    }

    @Test
    @DisplayName("a delivered result is appended only in its own chat; otherwise that chat is marked unread")
    void resultsGoToTheirChat() {
        assertTrue(page.contains("if (type === 'result') { if (currentView === 'chat' && data.sessionId === "
                + "displayedSessionId) { addMsg('response', content, data.taskId); } else { "
                + "markUnread(data.sessionId); } return; }"), "the routing of a result");
        assertTrue(page.contains("(s.kind === 'scheduled' ? ' pinned' : '') + (unreadSessions[s.id] ? ' unread' : '')"),
                "the pinned chat and an unread one are marked in the list");
        assertTrue(page.contains("displayedSessionId = sessionId; delete unreadSessions[sessionId];"),
                "opening a chat reads it");
    }

    @Test
    @DisplayName("the page shows every search hit the server grouped, and a task's request whole")
    void nothingCutOnThePage() {
        assertFalse(page.contains("seen[r.session_id]"), "the server groups by chat; the page drops nothing");
        assertFalse(page.contains("tdClip("), "the request of a task is shown whole");
        assertTrue(page.contains("'<div class=\"session-title\" title=\"' + title + '\">'"),
                "a title cut by the one-line layout is whole on hover");
    }
}
