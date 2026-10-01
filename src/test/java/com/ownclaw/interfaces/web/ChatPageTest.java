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
    @DisplayName("the page shows the chat the server has open: after New Chat, /new or /switch, results and answers are routed by it")
    void thePageFollowsTheOpenChat() {
        assertTrue(page.contains("if ((needsInitialLoad || data.activeSessionId !== displayedSessionId) "
                + "&& data.activeSessionId && currentView === 'chat') { loadSessionMessages(data.activeSessionId);"),
                "the chat session_info names as open is loaded when it is not the one on screen");
        assertTrue(page.contains("if (content && content !== displayedSessionId && currentView === 'chat') {"),
                "session_updated is measured against the chat on screen");
        String newChat = page.substring(page.indexOf("newChatBtn.addEventListener('click'"));
        newChat = newChat.substring(0, newChat.indexOf("});"));
        assertTrue(newChat.contains("clearMessages();") && newChat.contains("displayedSessionId = null;"),
                "the cleared pane shows no chat until the server names the new one: " + newChat);
        // Mutation: load only on (re)connect, as before -> after New Chat a result for the old
        // chat was drawn into the new one's pane, and the first answer there went unshown.
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

    @Test
    @DisplayName("a task's progress is drawn in its own chat, live and after a reload, compact and apart from the answer")
    void progressIsDrawnInItsChat() {
        assertTrue(page.contains("} else if (type === 'progress') {"), "a frame of its own");
        String handler = page.substring(page.indexOf("} else if (type === 'progress') {"));
        handler = handler.substring(0, handler.indexOf("} else if (type === 'pong'"));
        assertTrue(handler.contains("if (currentView === 'chat' && data.sessionId === displayedSessionId) { "
                + "addMsg('progress', content); }"), "only in the chat it belongs to: " + handler);
        assertFalse(handler.contains("setThinking(false)") || handler.contains("doneActivity()"),
                "progress is not the end of the work: " + handler);
        assertTrue(page.contains("var type = m.role === 'user' || m.role === 'system' || m.role === 'progress' "
                + "? m.role : 'response';"), "a saved progress row is drawn as one after a reload");
        assertTrue(page.contains(".msg.progress {"), "and styled as secondary");
        // Mutation: draw it as a response -> after a reload every step reads as an answer.
    }

    @Test
    @DisplayName("the task page still names an old row that ended on the step limit, which no task reaches now")
    void anOldStepLimitRowIsStillNamed() {
        assertTrue(page.contains("MAX_STEPS: 'Step limit reached'"), "rows from before 1 October 2026 keep it");
    }
}

