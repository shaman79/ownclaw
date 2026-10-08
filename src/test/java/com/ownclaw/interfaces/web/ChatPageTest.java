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
                + "displayedSessionId) { addMsg('response', content, data.taskId, null, data.timestamp); } else { "
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
    @DisplayName("a chat opens on its newest page, at the bottom, and draws each earlier page above as the owner scrolls up to it")
    void aChatIsDrawnAPageAtATime() {
        String load = page.substring(page.indexOf("function loadSessionMessages(sessionId) {"));
        load = load.substring(0, load.indexOf(".catch("));
        assertTrue(load.contains("fetch('/api/chats/' + sessionId + '/messages?limit=' + HISTORY_PAGE, {"),
                "the newest page: " + load);
        assertTrue(load.contains("drawRow(m, thinkingEl); }); if (data.hasMore) holdEarlier(sessionId, msgs[0].id);"),
                "drawn, with a line for the rows before them: " + load);
        assertTrue(load.contains("following = true; scrollPane(messagesEl.scrollHeight);"),
                "and landed at the bottom at once: " + load);
        assertTrue(load.endsWith("if (lastTask) restoreTaskStats(lastTask, sessionId); loadEarlierNearTop(); }) "),
                "a page that does not fill the pane cannot be scrolled up from: the next is asked for at once: " + load);

        assertTrue(page.contains("messagesEl.addEventListener('scroll', loadEarlierNearTop);"));
        assertTrue(page.contains("function loadEarlierNearTop() { if (messagesEl.scrollTop < messagesEl.clientHeight) loadEarlier(); }"),
                "scrolled to within a screen of the top");
        assertTrue(page.contains("messagesEl.insertBefore(line, messagesEl.querySelector('.msg'));"),
                "the line that says earlier rows are loading stands above the drawn rows");

        String earlier = page.substring(page.indexOf("function loadEarlier() {"));
        earlier = earlier.substring(0, earlier.indexOf("function scrollPane(top) {"));
        assertTrue(earlier.contains("if (!page || page.loading) return; page.loading = true;"), "one page in flight: " + earlier);
        assertTrue(earlier.contains("'&before=' + encodeURIComponent(page.before)"), "the page before the oldest drawn row");
        assertTrue(earlier.contains(".then(function(data) { if (earlier !== page) return;")
                        && earlier.contains(".catch(function(e) { if (earlier !== page) return;"),
                "not into a chat drawn again, or left, since: " + earlier);
        assertTrue(earlier.contains("var oldest = messagesEl.querySelector('.msg'); var from = oldest.getBoundingClientRect().top;")
                && earlier.contains("msgs.forEach(function(m) { drawRow(m, oldest); });")
                && earlier.contains("scrollPane(messagesEl.scrollTop + oldest.getBoundingClientRect().top - from); loadEarlierNearTop();"),
                "drawn above, the rows on screen staying where they were, and the next asked for when still near the top: " + earlier);
        assertTrue(earlier.contains("earlier = null; if (data.hasMore) holdEarlier(page.sessionId, msgs[0].id);"),
                "on until the first row of the chat: " + earlier);
        assertTrue(earlier.contains("if (run && lastMsgSender === (run.classList.contains('user') ? 'user' : 'ai')) { "
                        + "run.classList.remove('first-in-run'); } if (following) lastMsgSender = following;"),
                "a sender's run of messages reads as one across a page boundary: " + earlier);
        assertTrue(earlier.contains("page.line.textContent = 'Could not load earlier messages: '"),
                "a page that failed says so, where the rows it held would be: " + earlier);

        assertTrue(page.contains("if (before) { messagesEl.insertBefore(div, before); return div; } "
                + "messagesEl.insertBefore(div, thinkingEl);"), "a saved row is drawn where it belongs, and scrolls nothing");
        assertTrue(page.contains("if (before) { messagesEl.insertBefore(div, before); return; } "
                + "messagesEl.insertBefore(div, thinkingEl); scrollBottom(false);"), "a saved progress row too");
        String clear = page.substring(page.indexOf("function clearMessages() {"));
        clear = clear.substring(0, clear.indexOf("function renderSessionList("));
        assertTrue(clear.contains("earlier = null;"), "a pane cleared holds no chat's earlier rows: " + clear);
        // Mutation: forget the scroll position -> each earlier page pushes the rows being read
        // down by its height; drop the stale check -> a page of the chat left is drawn into the
        // next one opened.
    }

    @Test
    @DisplayName("a task's progress is drawn in its own chat, live and after a reload, compact and apart from the answer")
    void progressIsDrawnInItsChat() {
        assertTrue(page.contains("} else if (type === 'progress') {"), "a frame of its own");
        String handler = page.substring(page.indexOf("} else if (type === 'progress') {"));
        handler = handler.substring(0, handler.indexOf("} else if (type === 'pong'"));
        assertTrue(handler.contains("if (currentView === 'chat' && data.sessionId === displayedSessionId) { "
                + "addProgress(content, data.progress, null, data.timestamp); }"), "only in the chat it belongs to: " + handler);
        assertFalse(handler.contains("setThinking(false)") || handler.contains("doneActivity()"),
                "progress is not the end of the work: " + handler);
        assertTrue(page.contains("if (m.role === 'progress') { addProgress(m.content, m.progress, before, m.timestamp); return; }"),
                "a saved progress row is drawn as one after a reload, with the header it was saved with");
        assertTrue(page.contains(".msg.progress {"), "and styled as secondary");
        // Mutation: draw it as a response -> after a reload every step reads as an answer.
    }

    @Test
    @DisplayName("the token counters survive a reload: a chat's latest task is read back from the server")
    void countersAreReadBackOnLoad() {
        String load = page.substring(page.indexOf("function loadSessionMessages(sessionId) {"));
        load = load.substring(0, load.indexOf("function clearMessages()"));
        assertTrue(load.contains("if (m.task_id) lastTask = m.task_id;")
                && load.contains("if (lastTask) restoreTaskStats(lastTask, sessionId);"),
                "loading a chat asks for its latest task's totals");
        String restore = page.substring(page.indexOf("function restoreTaskStats(taskId, sessionId) {"));
        restore = restore.substring(0, restore.indexOf("/** Update the live token counter"));
        assertTrue(restore.contains("fetch('/api/tasks/' + taskId"), "from the task API");
        assertTrue(restore.contains("var cloud = t.recorded ? tdBilled(totals) : tdSum(steps, 'cloudTokens');")
                && restore.contains("var local = outcome ? (outcome.localTokens || 0) : tdSum(steps, 'localTokens');"),
                "with the task page's own totals");
        assertTrue(restore.contains("updateStats({") && restore.contains("updateTokens({"), "into both counters");
        assertTrue(restore.contains("displayedSessionId !== sessionId"), "not into a chat opened since");
        // Mutation: drop the call -> the counters read zero after a reload, as before.
    }

    @Test
    @DisplayName("a progress row's header is the line the server wrote, its emoji drawn as a chip for who acts; a row without one is its text")
    void progressHeadersAreChips() {
        String draw = page.substring(page.indexOf("function addProgress(text, header, before, timestamp) {"));
        draw = draw.substring(0, draw.indexOf("// Chat messages are persisted"));
        assertTrue(draw.contains("if (!header || (header.actor !== 'cloud' && header.actor !== 'local')) { "
                + "addMsg('progress', text, null, before, timestamp); return; }"), "a row saved before headers were kept: " + draw);
        assertTrue(draw.contains("var line = lineBreak < 0 ? text : text.slice(0, lineBreak); "
                + "var space = line.indexOf(' '); "
                + "var chip = node('span', 'progress-chip ' + header.actor, line.slice(0, space) + ' ' + header.actor);"),
                "the chip: the line's emoji, styled by who acts: " + draw);
        assertTrue(draw.contains("head.appendChild(node('span', 'progress-what', line.slice(space + 1)));"),
                "then the rest of the line as the server wrote it, which Telegram shows too: " + draw);
        for (String field : new String[] {"header.step", "header.turn", "header.result", "header.tool",
                "header.elapsedMs", "header.costUsd", "formatDuration", "formatUsd"}) {
            assertFalse(draw.contains(field), "one renderer of the header, the server's: the page does not "
                    + "write it again from the data (" + field + ")");
        }
        assertTrue(draw.contains("var body = lineBreak < 0 ? '' : text.slice(lineBreak + 1).trim();"),
                "the rest of the text is drawn under the header");
        assertTrue(draw.contains("b.innerHTML = renderMarkdown(body);"), "through the one sanitising renderer");
        assertTrue(page.contains(".progress-chip.cloud {") && page.contains(".progress-chip.local {"));
        // Mutation: format the time and cost from the data again -> the page says $0.0075 where
        // the row and Telegram say $0.01; draw the whole text under the chip -> the header twice.
    }

    @Test
    @DisplayName("while a task runs Send stays enabled -- Enter too -- with Queue and Stop beside it")
    void sendStaysEnabledWhileATaskRuns() {
        String thinking = page.substring(page.indexOf("function setThinking(on) {"));
        thinking = thinking.substring(0, thinking.indexOf("function resetThinkingSafetyTimer()"));
        assertFalse(thinking.contains("sendBtn.disabled"), "Send is never disabled: " + thinking);
        assertTrue(thinking.contains("stopBtn.classList.toggle('visible', on);") && thinking.contains("updateComposer(); }"),
                "Stop shows while it runs, and Queue where Send reaches it: " + thinking);
        assertFalse(page.contains("busy"), "nothing holds back Enter while a task runs");
        assertTrue(page.contains("sendBtn.addEventListener('click', function() { send(false); });")
                && page.contains("queueBtn.addEventListener('click', function() { send(true); });"));
        assertTrue(page.contains("if (e.key === 'Enter' && !e.shiftKey && hardKeyboard) { e.preventDefault(); "
                + "// What Send does, in every state: while a task runs, the message goes to it. send(false); }"));
        assertTrue(page.contains("<button id=\"queue\""), "the Queue button");
        String send = page.substring(page.indexOf("function send(queued) {"));
        send = send.substring(0, send.indexOf("function markFate("));
        assertTrue(send.contains("var payload = { message: text, clientId: clientId, costMode: options.costMode, "
                + "effort: options.effort }; if (queued) payload.queue = true;"), send);
        assertTrue(send.contains("if (!thinkingEl.classList.contains('active')) {"),
                "a message sent while a task runs keeps that task's trace: " + send);
        // Mutation: disable Send in setThinking again -> nothing can be sent while a task runs.
    }

    @Test
    @DisplayName("a message sent while a task runs says under its bubble what became of it: handed to the task, read, or queued")
    void theBubbleSaysWhatBecameOfIt() {
        assertTrue(page.contains("} else if (type === 'fate') {"), "a frame of its own");
        String fate = page.substring(page.indexOf("} else if (type === 'fate') {"));
        fate = fate.substring(0, fate.indexOf("} else if (type === 'status') {"));
        assertTrue(fate.contains("var sentBubble = sentBubbles[data.clientId];")
                && fate.contains("sentBubble.dataset.messageId = data.messageId;")
                && fate.contains("markFate(sentBubble, content);"), "the server's line, under the page's bubble: " + fate);
        assertTrue(page.contains("(data.read || []).forEach(function(id) { markFate(bubbleOf(id), 'Read by the task.'); });"),
                "the progress row that says the task read it marks it read");
        assertTrue(page.contains("if (data.data && data.data.requeued) markFate(bubbleOf(data.data.requeued), content);"),
                "a message the task ended without reading says it runs as its own task");
        assertTrue(page.contains("if (type === 'user' && data.clientId) sentBubbles[data.clientId] = drawnMsg;"),
                "a typed /queue is drawn by the server's echo, and its fate goes under that");
    }

    @Test
    @DisplayName("Send promises the running task, and Queue shows, only in the chat where the server said Send reaches it")
    void steeringIsPromisedOnlyWhereItHappens() {
        String composer = page.substring(page.indexOf("function updateComposer() {"));
        composer = composer.substring(0, composer.indexOf("function resetThinkingSafetyTimer()"));
        assertTrue(composer.contains("var steering = thinkingEl.classList.contains('active') "
                        + "&& steeredChatId !== null && steeredChatId === displayedSessionId "
                        + "&& chosen.costMode === steeredOptions.costMode && chosen.effort === steeredOptions.effort; "
                        + "queueBtn.classList.toggle('visible', steering); inputEl.placeholder = steering "
                        + "? 'Send to the running task, or Queue it for after...' : idlePlaceholder;"),
                "while a task runs elsewhere, or takes nothing from here, nothing is promised: " + composer);
        assertTrue(page.contains("if (data.fate === 'started' || data.fate === 'steered') { steeredChatId = data.sessionId; "
                        + "steeredOptions = data.options; } else if (!data.queue && data.sessionId === steeredChatId) "
                        + "steeredChatId = null; updateComposer();"),
                "the fate of a message names the chat where Send reaches the task, or says it takes nothing more");
        assertTrue(page.contains("if (!on) { stopPressed = false; steeredChatId = null; }"), "and no task runs once it ends");
        assertTrue(page.contains("if (listed) listed.classList.remove('unread'); updateComposer();"),
                "opening another chat says what Send does there");
        assertTrue(page.contains("displayedSessionId = null; updateComposer();"), "as New Chat does");
        // Mutation: show Queue whenever a task runs -> in another chat, Send queues while the
        // composer says it goes to the running task.
    }

    @Test
    @DisplayName("a message drawn from the history is named by its row, so a task still running marks it read after a reload")
    void historyBubblesAreNamedByTheirRows() {
        assertTrue(page.contains("var drawn = addMsg(type, m.content, m.task_id, before, m.timestamp);"), "the bubble a reload draws");
        assertTrue(page.contains("if (type === 'user') drawn.dataset.messageId = m.id;"),
                "named as bubbleOf finds it");
        assertTrue(page.contains("'.msg.user[data-message-id=\"' + CSS.escape(messageId) + '\"]'"));
        // Mutation: draw the history's bubbles unnamed -> after a reload no read or requeued frame
        // finds its message, and none says what became of it.
    }

    @Test
    @DisplayName("a queued task that starts after the running one gets its own trace; a command's reply leaves a running task's working state")
    void aQueuedTaskStartsItsOwnTrace() {
        assertTrue(page.contains("if (traceTaskId && traceTaskId !== data.taskId) resetActivity(); "
                + "activeTaskId = traceTaskId = data.taskId;"), "claimed with a fresh trace");
        assertTrue(page.contains("function resetActivity() { traceTaskId = null;"));
        assertTrue(page.contains("if (type === 'response' || (type === 'system' && !activeTaskId)) { setThinking(false);"),
                "a command typed while a task runs is answered, and the task goes on");
    }

    @Test
    @DisplayName("a status of unattended work is shown in the pinned chat's activity strip alone, and never as the working state")
    void unattendedWorkIsShownApart() {
        String status = page.substring(page.indexOf("} else if (type === 'status') {"));
        status = status.substring(0, status.indexOf("} else if (type === 'input_request') {"));
        String apart = "if (data.data && data.data.background) { showBackgroundStatus(data, content); return; }";
        assertTrue(status.contains(apart), "a frame the server marked goes apart: " + status);
        assertTrue(status.indexOf(apart) < status.indexOf("activeTaskId = traceTaskId = data.taskId;")
                && status.indexOf(apart) < status.indexOf("setThinking("), "before it can claim the working state");

        String shown = page.substring(page.indexOf("function showBackgroundStatus(data, content) {"));
        shown = shown.substring(0, shown.indexOf("function checkAuth()"));
        assertTrue(shown.contains("var shown = currentView === 'chat' && !thinkingEl.classList.contains('active') "
                + "&& !!sessionListEl.querySelector('.session-item.pinned[data-session-id=\"' + displayedSessionId + '\"]');"),
                "drawn only in the pinned chat of scheduled results, while nothing asked here runs: " + shown);
        assertTrue(shown.contains("if (!shown) { if (data.taskId && data.taskId === traceTaskId) resetActivity(); return; }"),
                "and its trace, left on screen in another chat, goes: " + shown);
        assertTrue(shown.contains("if (data.taskId && data.taskId !== traceTaskId) { if (traceTaskId) resetActivity(); "
                + "traceTaskId = data.taskId; }"), "a trace of its own: " + shown);
        assertTrue(shown.contains("if (sub === 'completed' || sub === 'failed' || sub === 'rollback' || sub === 'need_input') "
                + "doneActivity();"), "its strip ends with it: " + shown);
        for (String state : new String[] {"setThinking(", "activeTaskId", "resetThinkingSafetyTimer(", "stopBtn"}) {
            assertFalse(shown.contains(state), "the working state is not its own (" + state + "): " + shown);
        }
        // Mutation: drop the branch -> a morning run's steps fill the open chat's strip and start
        // its spinner again; draw them in any chat -> the same, with the spinner left alone.
    }

    @Test
    @DisplayName("a chat opens on its newest message and follows what arrives, a long answer too, until the owner scrolls up")
    void theChatFollowsItsNewestMessage() {
        String load = page.substring(page.indexOf("function loadSessionMessages(sessionId) {"));
        load = load.substring(0, load.indexOf("function clearMessages()"));
        assertTrue(load.contains("following = true; scrollPane(messagesEl.scrollHeight);"),
                "once its newest messages are drawn, at once and following: " + load);

        String scroll = page.substring(page.indexOf("function scrollBottom(force) {"));
        scroll = scroll.substring(0, scroll.indexOf("inputEl.addEventListener('input'"));
        assertTrue(scroll.contains("if (currentView !== 'chat') return; if (force) following = true; "
                + "else if (!following) return;"), "whether it follows, as the owner's scrolling left it: " + scroll);
        assertFalse(scroll.contains("nearBottom"), "not measured once the new message is in: " + scroll);
        assertTrue(scroll.contains("messagesEl.addEventListener('scroll', function() { var top = messagesEl.scrollTop; "
                + "if (messagesEl.scrollHeight - top - messagesEl.clientHeight < 80) following = true; "
                + "else if (top < lastScrollTop) following = false; lastScrollTop = top; });"),
                "scrolled up it stops, back at the bottom it follows again: " + scroll);
        assertTrue(scroll.contains("new ResizeObserver(function() { scrollBottom(false); }).observe(messagesEl);"),
                "and the strip or the composer taking room does not push the newest message out of view");
        // Mutation: measure after adding again -> an answer taller than 80px arrives out of sight;
        // drop the forced scroll on load -> a chat with no message of the owner's opens wherever
        // the one before it was left.
    }

    @Test
    @DisplayName("the task page shows a delegation's goal, which the chat no longer does")
    void theTaskPageShowsTheGoal() {
        assertTrue(page.contains("if (s.goal) { d.appendChild(tdRow('Goal', 'what the cloud asked the local model to do')); "
                + "d.appendChild(node('div', 'task-result-content', s.goal)); }"), "whole, as text");
    }

    @Test
    @DisplayName("the task page still names an old row that ended on the step limit, which no task reaches now")
    void anOldStepLimitRowIsStillNamed() {
        assertTrue(page.contains("MAX_STEPS: 'Step limit reached'"), "rows from before 1 October 2026 keep it");
    }

    @Test
    @DisplayName("a page left open across a restart is told OwnClaw was updated, and offered a reload")
    void aStalePageIsToldToReload() {
        assertTrue(page.contains("if (serverVersion && data.version !== serverVersion) showUpdated(); "
                + "serverVersion = serverVersion || data.version;"), "the run it first connected to is kept");
        String shown = page.substring(page.indexOf("function showUpdated() {"));
        assertTrue(shown.contains("reload.addEventListener('click', function() { location.reload(); });"), shown);
    }
}

