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
        assertTrue(page.contains("if (listed) listed.classList.remove('unread');"),
                "and takes the mark off it in the list at once");
        String markUnread = page.substring(page.indexOf("function markUnread("));
        markUnread = markUnread.substring(0, markUnread.indexOf('}') + 1);
        assertTrue(markUnread.contains("unreadSessions[sessionId] = true;") && markUnread.contains("refreshSessionList();"),
                "marking a chat unread fetches the list again, as the pinned chat may be new: " + markUnread);
    }

    @Test
    @DisplayName("the run history pages on with one handler, so no page is fetched twice")
    void loadMoreHasOneHandler() {
        assertTrue(page.contains("loadMore.onclick = function() { loadMoreRuns(runs.length, container, loadMore); };"),
                "the first page's handler is the one loadMoreRuns replaces");
        assertTrue(page.contains("btn.onclick = function() { loadMoreRuns(offset + runs.length, container, btn); };"));
        assertFalse(page.contains("loadMore.addEventListener("), "a second handler beside it fetches a page again");
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
