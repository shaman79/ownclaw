package com.ownclaw.interfaces.web;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The page's side of tabs that each work in their own chat: it names its chat in what it sends
 * and in its socket's address, keeps it for its reloads, and shows as working only what runs in
 * the chat on screen -- a status, a question or an answer of another chat's task marks that chat
 * in the list instead. Read from the page's source, whitespace collapsed (StillRunningPageTest).
 */
class TabChatPageTest {

    static String page;

    @BeforeAll
    static void read() throws Exception {
        try (var in = TabChatPageTest.class.getResourceAsStream("/static/index.html")) {
            assertNotNull(in, "static/index.html is not on the classpath");
            page = new String(in.readAllBytes(), StandardCharsets.UTF_8).replaceAll("\\s+", " ");
        }
        StillRunningPageTest.page = page;
    }

    static String function(String name) {
        return StillRunningPageTest.function(name);
    }

    /** The branch of the socket's handler for frames of this type, up to the next one's, of this type. */
    static String branch(String type, String next) {
        String from = page.substring(page.indexOf("} else if (type === '" + type + "') {"));
        return from.substring(0, from.indexOf("} else if (type === '" + next + "'"));
    }

    @Test
    @DisplayName("the tab names its chat: in its socket's address, its messages, its Stop and its pings")
    void theTabNamesItsChat() {
        String connect = function("connect");
        assertTrue(connect.contains("var tabChat = displayedSessionId || storedChat();")
                && connect.contains("var chatParam = '&tabs=1' + (tabChat ? '&chat=' + encodeURIComponent(tabChat) : '');")
                && connect.contains("'/ws/chat' + tokenParam + chatParam"), connect);
        assertTrue(function("send").contains("if (displayedSessionId) payload.sessionId = displayedSessionId;"));
        assertTrue(page.contains("ws.send(JSON.stringify({ type: 'cancel', sessionId: displayedSessionId }));"),
                "Stop stops the chat on screen");
        assertTrue(function("startHeartbeat").contains("ws.send(JSON.stringify({ type: 'ping', sessionId: displayedSessionId }))"));
        assertFalse(page.contains("JSON.stringify({ type: 'cancel' })") || page.contains("JSON.stringify({ type: 'ping' })"),
                "nothing is sent for no chat");
    }

    @Test
    @DisplayName("the tab keeps its chat for its reloads, in its own storage, and forgets it on logout")
    void theTabKeepsItsChat() {
        assertTrue(page.contains("function storedChat() { try { return sessionStorage.getItem(TAB_CHAT_KEY); } "
                + "catch (e) { return null; } }"), "blocked storage leaves the server to name the chat");
        assertTrue(function("loadSessionMessages").contains("if (sessionId !== displayedSessionId) leaveShownChat(); "
                + "displayedSessionId = sessionId; delete unreadSessions[sessionId]; rememberChat(sessionId); "
                + "updateSidebarActive(sessionId);"));
        assertTrue(function("logout").contains("rememberChat(null);"), "the next sign-in starts in a chat of its own");
        assertFalse(page.contains("localStorage.setItem(TAB_CHAT_KEY"), "one tab's chat is not every tab's");
    }

    @Test
    @DisplayName("the list highlights the chat on screen, not the account's open one, and marks the chats working")
    void theListIsTheTabs() {
        assertFalse(page.contains("activeSessionId ="), "no copy of the account's open chat decides anything here");
        String list = function("renderSessionList");
        assertTrue(list.contains("var isActive = (currentView === 'chat' && s.id === displayedSessionId);"), list);
        assertTrue(list.contains("+ (workingChats[s.id] ? ' working' : '')"), list);
        assertTrue(list.contains("if (currentView === 'tasks' || s.id !== displayedSessionId) {"), list);
        assertTrue(function("deleteSession").contains("var wasActive = (currentView === 'chat' && sessionId === displayedSessionId);"));
        assertTrue(page.contains("renderSessionList(data.sessions || []); setWorkingChats(data.workingChats);"),
                "session_info says which chats work");
        assertTrue(page.contains("if (type === 'pong') setWorkingChats(data.workingChats);"), "and each pong");
        assertTrue(page.contains(".session-item.working .session-meta::before {"), "drawn");
    }

    @Test
    @DisplayName("a status, a question or an answer of another chat's task marks that chat, and leaves the chat on screen alone")
    void anotherChatsWorkIsMarked() {
        String show = function("showStatus");
        int background = show.indexOf("showBackgroundStatus(data, content); return; }");
        int routed = show.indexOf("if (data.sessionId && data.sessionId !== displayedSessionId) return;");
        int working = show.indexOf("setThinking(true)");
        assertTrue(background >= 0 && routed > background && working > routed,
                "unattended work as before; another chat's status touches nothing here: " + show);
        assertTrue(show.contains("if (data.sessionId) markWorking(data.sessionId, !ending);"), show);

        String question = branch("input_request", "debug");
        assertTrue(question.indexOf("markUnread(data.sessionId); return; }") < question.indexOf("setThinking(false);"),
                "a question in another chat ends nothing here: " + question);
        assertTrue(branch("debug", "progress").contains("if (!data.sessionId || data.sessionId === displayedSessionId) addMsg('debug', content);"));
        assertTrue(branch("progress", "pong").contains("if (data.sessionId && data.sessionId !== displayedSessionId) "
                + "markWorking(data.sessionId, true); else resetThinkingSafetyTimer();"));

        String elsewhere = page.substring(page.indexOf("if (data.sessionId && data.sessionId !== displayedSessionId) { "
                + "// That chat's task has ended"));
        elsewhere = elsewhere.substring(0, elsewhere.indexOf("return;"));
        assertFalse(elsewhere.contains("setThinking(false)") || elsewhere.contains("doneActivity()"),
                "an answer in another chat does not end the task on screen: " + elsewhere);
        assertTrue(elsewhere.contains("markUnread(data.sessionId);")
                && elsewhere.contains("showElsewhere('An answer arrived in another chat.', data.sessionId);"), elsewhere);
        assertFalse(page.contains("showConnBanner('An answer") || page.contains("showConnBanner('A question"),
                "the connection banner stays until a reconnect");
        assertTrue(function("loadSessionMessages").contains("hideElsewhere(sessionId);"), "opening that chat ends the notice");
        assertTrue(function("markWorking").contains("if (id && !on) delete keptStates[id];"),
                "what was kept of a chat whose task ended is no state of its next task's");
        assertTrue(function("setWorkingChats").contains("if (!workingChats[id]) delete keptStates[id];"));
    }

    @Test
    @DisplayName("a fate naming another chat takes the tab there only while the chat it was sent from is still on screen")
    void aFateMovesOnlyAnUnmovedTab() {
        assertTrue(function("send").contains("sentFrom[clientId] = displayedSessionId;"));
        String fate = branch("fate", "status");
        assertTrue(fate.contains("var sentIn = sentFrom[data.clientId]; delete sentFrom[data.clientId];")
                && fate.contains("if (data.sessionId && data.sessionId !== displayedSessionId && sentIn === displayedSessionId "
                + "&& currentView === 'chat' && ws && ws.readyState === 1) { switchToSession(data.sessionId); }"), fate);
    }

    @Test
    @DisplayName("leaving a chat keeps its working state, and the chat opened shows its own, as the server says it")
    void theWorkingStateIsTheShownChats() {
        String leave = function("leaveShownChat");
        assertTrue(leave.indexOf("keepShownState();") < leave.indexOf("setThinking(false);"), leave);
        assertTrue(leave.contains("resetActivity();"), leave);
        String resume = function("resumeShownChat");
        assertTrue(resume.contains("setThinking(true); if (kept && kept.startedAt) { thinkingStartedAt = kept.startedAt;"),
                "since when it runs, not since it was opened again: " + resume);
        assertTrue(resume.contains("steeredChatId = displayedSessionId; steeredOptions = kept.steered;"), resume);
        String newChat = page.substring(page.indexOf("newChatBtn.addEventListener('click'"));
        newChat = newChat.substring(0, newChat.indexOf("});"));
        assertTrue(newChat.contains("leaveShownChat(); clearMessages();"), newChat);
        assertTrue(page.contains("// Kept for the reconnect, whose session_info says whether the task still runs. "
                + "keepShownState(); setThinking(false);"));
        assertTrue(page.contains("} else { delete keptStates[displayedSessionId]; }"), "a task that ended meanwhile keeps nothing");
    }
}
