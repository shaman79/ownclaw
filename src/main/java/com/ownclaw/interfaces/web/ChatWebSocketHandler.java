package com.ownclaw.interfaces.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownclaw.agent.TaskChat;
import com.ownclaw.config.SetupWizardService;
import com.ownclaw.conversation.ChatOptions;
import com.ownclaw.conversation.ConversationService;
import com.ownclaw.core.TaskCancellationService;
import com.ownclaw.core.TaskQueue;
import com.ownclaw.core.UserMessage;
import com.ownclaw.interfaces.CommandHandler;
import com.ownclaw.observability.ChatStatusEmitter;
import com.ownclaw.observability.DebugSessionService;
import com.ownclaw.skillrunner.SkillInteractionHandler;
import com.ownclaw.users.AuthService;
import com.ownclaw.users.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import jakarta.annotation.PreDestroy;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * WebSocket handler for the chat UI.
 * Each connected client is associated with a user and receives status messages + responses.
 */
@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ChatWebSocketHandler.class);
    private static final long WELCOME_THROTTLE_MS = 60_000;

    private final TaskQueue taskQueue;
    private final UserRepository userRepo;
    private final ConversationService conversationService;
    private final ChatStatusEmitter statusEmitter;
    private final CommandHandler commandHandler;
    private final SetupWizardService setupWizard;
    private final AuthService authService;
    private final SkillInteractionHandler interactionHandler;
    private final TaskCancellationService cancellationService;
    private final DebugSessionService debugService;
    private final ObjectMapper mapper;

    /** Active WebSocket sessions by user ID. */
    /**
     * Every open socket per user, not one.
     *
     * This was Map&lt;String, WebSocketSession&gt;, so opening a second tab overwrote the first and
     * only the most recently connected window was reachable. Everything delivered
     * asynchronously went through that single handle: the finished answer, session_updated,
     * setup-wizard prompts. The status stream never had the problem, because ChatStatusEmitter
     * is keyed per subscriber and fans out — so the visible symptom was a tab that showed the
     * whole trace and the COMPLETED line, and then never received the answer, which had gone to
     * a phone the owner had glanced at an hour earlier.
     *
     * It also made "tell every connection" impossible to write correctly, which is why the
     * session_updated broadcast added earlier today could not work.
     */
    private final Map<String, Set<WebSocketSession>> sessions = new ConcurrentHashMap<>();

    /** Prevents spamming the chat with repeated welcome messages on reconnect loops. */
    private final Map<String, Long> lastWelcomeAtMs = new ConcurrentHashMap<>();

    /** Tracks whether the /setup wizard is currently running for a user. */
    private final Map<String, AtomicBoolean> setupWizardRunning = new ConcurrentHashMap<>();

    /** Runs the setup wizard without blocking the WS handler thread. */
    private final ExecutorService wizardExecutor = Executors.newVirtualThreadPerTaskExecutor();

    @PreDestroy
    void shutdownWizardExecutor() {
        try {
            wizardExecutor.close();
        } catch (Exception ignored) {
        }
    }

    public ChatWebSocketHandler(TaskQueue taskQueue, UserRepository userRepo,
                                ConversationService conversationService,
                                ChatStatusEmitter statusEmitter,
                                CommandHandler commandHandler,
                                SetupWizardService setupWizard,
                                AuthService authService,
                                SkillInteractionHandler interactionHandler,
                                TaskCancellationService cancellationService,
                                DebugSessionService debugService,
                                ObjectMapper mapper) {
        this.taskQueue = taskQueue;
        this.userRepo = userRepo;
        this.conversationService = conversationService;
        this.statusEmitter = statusEmitter;
        this.commandHandler = commandHandler;
        this.setupWizard = setupWizard;
        this.authService = authService;
        this.interactionHandler = interactionHandler;
        this.cancellationService = cancellationService;
        this.debugService = debugService;
        this.mapper = mapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws IOException {
        // Authenticate: check for JWT token in query params
        String userId = resolveUserId(session);
        if (userId == null) {
            sendToSession(session, "system", "Authentication required. Please log in.");
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        session.getAttributes().put("userId", userId);
        // The chat this tab shows, as its address names it: a reconnecting page is told at once
        // what is going on in that chat, not in the one another tab opened last.
        // A page that works in a chat of its own says so (tabs=1): a page of an earlier version,
        // still open after a deploy, names no chat in what it sends and follows the account's
        // open chat, so it is never bound to one (bind).
        if ("1".equals(queryParam(session, "tabs"))) session.getAttributes().put(TABS, true);
        String named = queryParam(session, "chat");
        if (named != null && conversationService.hasChat(userId, named)) bind(session, named);
        sessions.computeIfAbsent(userId, u -> ConcurrentHashMap.newKeySet()).add(session);

        // Subscribe to status messages
        // Keyed on this session: a second tab adds a listener rather than replacing this
        // one, and closing a stale tab removes only its own.
        statusEmitter.subscribe(userId, session, msg -> {
            if (msg.type() == ChatStatusEmitter.StatusMessage.Type.DEBUG) {
                // Debug messages are rendered as full message blocks, not brief activity entries,
                // in the chat of the task they are about.
                sendToSession(session, "debug", msg.text(), chatOf(msg), msg.taskId(), null);
            } else if (msg.type() == ChatStatusEmitter.StatusMessage.Type.RESULT
                    || msg.type() == ChatStatusEmitter.StatusMessage.Type.PROGRESS_MESSAGE) {
                // The output of background work is a message, not a status. Rendered as a status
                // it would land in the collapsed activity strip, which is exactly how a finished
                // scheduled task managed to produce a full digest that nobody ever saw.
                // Sent as its own type, not as "response". The client stops the spinner on a
                // plain response, so a background digest landing during a six-minute question
                // ended that question's working state -- the same confusion the taskId fix
                // removed for statuses. "result" renders identically and touches nothing.
                // A private answer travels beside the safe text, for the owner's own screens --
                // this chat and Telegram (see TelegramBotService.telegramText); the text itself is
                // the note that the answer exists, for everything that stores or forwards it.
                // With the chat it was saved into, so a page showing another chat marks that one
                // instead of appending the result to the conversation on screen.
                // A running task's progress message is a message too, of the same shape: sent as
                // "progress", with its header as data for the page to draw, it is shown in its
                // chat, secondary to the answer, and leaves the activity strip and the working
                // state alone.
                Object owner = msg.data() == null ? null : msg.data().get("ownerText");
                Object chat = msg.data() == null ? null : msg.data().get("sessionId");
                var payload = new LinkedHashMap<String, Object>();
                payload.put("type", msg.type() == ChatStatusEmitter.StatusMessage.Type.RESULT ? "result" : "progress");
                payload.put("content", owner != null ? owner.toString() : msg.text());
                if (chat != null) payload.put("sessionId", chat.toString());
                if (msg.taskId() != null) payload.put("taskId", msg.taskId());
                if (msg.data() != null && msg.data().get("progress") != null) {
                    payload.put("progress", msg.data().get("progress"));
                }
                // The owner's rows a task has just read (TaskChat#read), for the page to mark.
                if (msg.data() != null && msg.data().get("read") != null) {
                    payload.put("read", msg.data().get("read"));
                }
                // When the row was saved, so the page shows the time a reload of the chat will.
                if (msg.data() != null && msg.data().get("timestamp") != null) {
                    payload.put("timestamp", msg.data().get("timestamp"));
                }
                send(session, payload);
            } else {
                // Include the raw status sub-type so the frontend can detect terminal statuses
                sendStatusToSession(session, msg);
            }
        });

        log.info("WebSocket connected: user={}", userId);

        // Send the active session info so the frontend can sync
        sendActiveSessionInfo(session, userId);

        // Check if first-run wizard is needed -- for the owner only, as /setup is: it sets the
        // cloud API keys, the local model's address and the bot token.
        if (setupWizard.isSetupNeeded() && authService.isOwner(userId)) {
            startSetupWizardIfNeeded(userId);
        } else {
            long now = System.currentTimeMillis();
            Long last = lastWelcomeAtMs.get(userId);
            if (last == null || (now - last) > WELCOME_THROTTLE_MS) {
                lastWelcomeAtMs.put(userId, now);
                // Name the two things a new session cannot otherwise discover. There are fourteen
                // slash commands and nothing in the UI mentioned that any exist; /cred matters
                // most, because it is the only way to store a secret that does NOT send it
                // through the cloud model and into plaintext conversation history — and the
                // agent itself tells people to use it.
                sendToSession(session, "system",
                        "**Connected to OwnClaw.** Send a message to get started.\n\n"
                        + "`/help` lists the available commands. "
                        + "Use `/cred set KEY VALUE` to store a secret — typing one into the "
                        + "chat sends it to the cloud model and saves it in history.");
            }
        }
    }

    /**
     * A message arrives in as many fragments as it takes, and is put together in
     * {@link #handleTextMessage}. Without this Tomcat closed the socket (1009) on any message
     * whose JSON passed its 8,192-character buffer -- a long paste the page had already drawn
     * as sent. A bigger buffer would be allocated for every open socket; fragments need none.
     */
    @Override
    public boolean supportsPartialMessages() {
        return true;
    }

    /** The fragments received so far of a socket's message, kept in the socket's attributes. */
    private static final String FRAGMENTS = "fragments";

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String userId = (String) session.getAttributes().get("userId");
        if (userId == null) return;

        // A socket's fragments arrive in order, and one message's end before the next begins.
        StringBuilder received = (StringBuilder) session.getAttributes().get(FRAGMENTS);
        if (!message.isLast()) {
            if (received == null) session.getAttributes().put(FRAGMENTS, received = new StringBuilder());
            received.append(message.getPayload());
            return;
        }
        session.getAttributes().remove(FRAGMENTS);
        String payload = received == null ? message.getPayload() : received.append(message.getPayload()).toString();
        String userMessage;

        // Accept plain text or JSON {"message": "...", "type": "...", "attachmentIds": [...],
        // "queue": true, "clientId": "...", "costMode": "...", "effort": "..."}: queue asks for a
        // task of its own instead of the running one (TaskQueue#send), clientId is the page's name
        // for the bubble it drew, which the frame saying what became of the message carries back,
        // and costMode and effort are what is chosen next to the message box, null for the default.
        String messageType = "message";
        java.util.List<String> attachmentIds = java.util.List.of();
        boolean queue = false;
        String clientId = null;
        // The chat the page shows -- the one this tab works in -- when the page names it.
        String tabChat = null;
        ChatOptions chosen = ChatOptions.NONE;
        try {
            JsonNode json = mapper.readTree(payload);
            messageType = json.has("type") ? json.path("type").asText("message") : "message";
            userMessage = json.has("message") ? json.path("message").asText() : payload;
            queue = json.path("queue").asBoolean(false);
            clientId = json.hasNonNull("clientId") ? json.path("clientId").asText() : null;
            tabChat = json.hasNonNull("sessionId") ? json.path("sessionId").asText() : null;
            chosen = new ChatOptions(json.hasNonNull("costMode") ? json.path("costMode").asText() : null,
                    json.hasNonNull("effort") ? json.path("effort").asText() : null);
            if (json.has("attachmentIds") && json.get("attachmentIds").isArray()) {
                var ids = new java.util.ArrayList<String>();
                for (JsonNode id : json.get("attachmentIds")) {
                    ids.add(id.asText());
                }
                attachmentIds = ids;
            }
        } catch (Exception e) {
            userMessage = payload;
        }
        // The defaults are the owner's -- the settings page and /local are owner only -- so another
        // account's chat runs on them: a choice it sends is not taken, nor kept for its chat.
        if (!chosen.equals(ChatOptions.NONE) && !authService.isOwner(userId)) chosen = ChatOptions.NONE;
        // A chat of this user's, or none: an id of another's names nothing here. One the tab
        // names that is gone -- deleted in another window -- is kept apart: a Stop there still
        // stops what runs in it, not everything, and a message for it starts a chat of its own,
        // rather than landing in a chat another window is working in.
        String gone = null;
        if (tabChat != null && !conversationService.hasChat(userId, tabChat)) {
            gone = tabChat;
            tabChat = null;
        }
        // Each tab works in its own chat. A frame naming none is in the chat its tab named last.
        if (tabChat != null) {
            bind(session, tabChat);
        } else {
            Object bound = session.getAttributes().get(TAB_CHAT);
            tabChat = namedChat(session, userId);
            if (tabChat == null && gone == null && bound != null) gone = bound.toString();
        }

        // Heartbeat ping: keep-alive for long-lived browser connections, sent as {type:'ping'}.
        // Do not treat as user input or command. A typed "ping" is a message like any other:
        // taken for the heartbeat, it was neither saved nor answered, and the page, which had
        // drawn it and started waiting, waited for nothing.
        if ("ping".equalsIgnoreCase(messageType)) {
            // With whether an attended task is running in the tab's chat, as session_info says
            // it: a page that has heard nothing of its task for a long time asks this rather than
            // take the silence for the task's end. And in which of the user's chats one is, for
            // the marks in its list of chats.
            var pong = new LinkedHashMap<String, Object>();
            pong.put("type", "pong");
            pong.put("content", "");
            pong.put("taskRunning", taskQueue.isAttendedBusyFor(userId, tabChat != null ? tabChat : gone));
            pong.put("workingChats", taskQueue.attendedBusyChats(userId));
            send(session, pong);
            return;
        }

        // The length only: the text can be "/cred set KEY VALUE" or a setup wizard's API key.
        log.debug("WS message from {}: {} chars", userId, userMessage.length());

        // Handle session switching via WebSocket
        if ("switch_session".equals(messageType)) {
            String targetSession = userMessage;
            if (targetSession != null && conversationService.hasChat(userId, targetSession)) {
                // This tab's chat now, and the account's open one: Telegram goes on in the chat
                // last opened here. The other tabs stay in theirs -- each names its own chat in
                // what it sends.
                bind(session, targetSession);
                conversationService.setActiveSession(userId, targetSession);
                // Told to every window all the same, which a page of this version reads as "the
                // list of chats changed". A page of an earlier one, which names no chat in what
                // it sends and has its messages filed in the account's open chat, follows it.
                sendToUser(userId, "session_updated", targetSession);
                sendActiveSessionInfo(session, userId);
            }
            return;
        }

        // Handle new session creation via WebSocket
        if ("new_session".equals(messageType)) {
            String title = (userMessage != null && !userMessage.isBlank()) ? userMessage : "New Chat";
            String created = conversationService.createSession(userId, title);
            if (created != null) bind(session, created);
            sendActiveSessionInfo(session, userId);
            // The other windows did not have the new chat in their list. A page of an earlier
            // version follows it, the same as an explicit switch (above).
            sendToUser(userId, "session_updated", created != null ? created : title);
            return;
        }

        // Cancel: user requested task interruption
        if ("cancel".equals(messageType)) {
            // Stop on a chat's page stops that chat's work: the task running in it, and what
            // waits there -- a chat deleted meanwhile too. Tasks of other chats go on. A page that
            // names no chat stops everything.
            String stopIn = tabChat != null ? tabChat : gone;
            if (stopIn != null) {
                cancellationService.requestChat(userId, stopIn, "you pressed Stop");
            } else {
                cancellationService.requestAll(userId, "you pressed Stop");
            }
            interactionHandler.cancelPending(userId);
            sendToSession(session, "system", "⏹ Cancellation requested.");
            return;
        }

        // A secret command anywhere but on its own -- "/bg /cred set KEY VALUE", "/queue then
        // /cred set ...", "store this: /cred set KEY VALUE" -- is answered and goes nowhere: not
        // into the chat, the queue, a running task or the agent, which would have been handed
        // the secret as text. A slash text's bubble is drawn here, masked; the page draws its own
        // for any other, on this screen alone.
        if (CommandHandler.misplacedSecret(userMessage)) {
            if (CommandHandler.isCommand(userMessage)) {
                sendToSession(session, "user", CommandHandler.displayed(userMessage));
            }
            sendToSession(session, "system", CommandHandler.MISPLACED_SECRET_ANSWER);
            return;
        }

        // "/queue <message>" queues its message, as the page's Queue button does. The page draws
        // no slash text: this is its bubble, as typed, under the page's name for it.
        String queued = CommandHandler.queued(userMessage);
        if (queued != null) {
            var bubble = new LinkedHashMap<String, Object>();
            bubble.put("type", "user");
            bubble.put("content", userMessage.trim());
            if (clientId != null) bubble.put("clientId", clientId);
            send(session, bubble);
            userMessage = queued;
            queue = true;
        }

        // A question waiting for an answer -- the setup wizard's -- takes this message.
        //
        // Asking "does it start with /" first would turn an answer that happens to begin with a
        // slash, a path say, into "Unknown command". So, as on Telegram: try the command, and if
        // it is not a recognised one and a question is waiting, it is the answer. That keeps
        // real commands working while a question waits, without deciding by punctuation what
        // the user meant.
        boolean waiting = queued == null && interactionHandler.hasPending(userId);
        // Blanks first, as the command grammar reads them (CommandHandler.unblanked): the page
        // trims what it sends, another client need not, a no-break or zero-width space survives
        // the page's trim, and " /cred set KEY VALUE" was no command -- it was saved and run as a
        // task, its secret handed to the cloud model.
        if (queued == null && CommandHandler.isCommand(userMessage)) {
            // Asking whether it is a command runs the command, so this answer is handed on and
            // the handler is not asked again.
            var handledAsCommand = commandHandler.handle(userId, userMessage, tabChat != null ? tabChat : gone);
            // The chat /new or /switch opened, read on the thread that ran it: the account's open
            // chat, read after, can already be another window's.
            String opened = CommandHandler.takeOpened();
            // The page does not draw a slash text itself; this is its bubble. A secret command's
            // secret is masked by the grammar that reads it, a well-formed one or not; a command
            // nobody knows shows its first word only, as nothing can say which part of
            // "/creds set KEY VALUE" is the secret.
            String typed = CommandHandler.unblanked(userMessage);
            sendToSession(session, "user", handledAsCommand.isPresent() || waiting
                    ? CommandHandler.displayed(typed)
                    : typed.split("(?U)[\\s\\p{Cf}]", 2)[0] + (typed.matches("(?U)[^\\s\\p{Cf}]+") ? "" : " \u2026"));
            if (handledAsCommand.isPresent() || !waiting) {
                handleCommand(userId, typed, handledAsCommand, opened, session);
                return;
            }
            // Not a command, and something is waiting for an answer: it is the answer.
        }
        if (waiting) {
            boolean handled = interactionHandler.provideInput(userId, userMessage);
            if (!handled) {
                sendToSession(session, "system", "No pending input request.");
            }
            return;
        }

        // Filed in the chat of the tab it was typed in -- the chat its page shows -- or, from a
        // page that names none, in the account's open chat. The chat last written in is the open
        // one: Telegram's messages go on there.
        String currentSessionId;
        if (tabChat != null) {
            currentSessionId = tabChat;
            conversationService.setActiveSession(userId, tabChat);
        } else if (gone != null) {
            currentSessionId = conversationService.createSession(userId, "New Chat");
            bind(session, currentSessionId);
        } else {
            currentSessionId = conversationService.getCurrentSession(userId);
        }
        // Auto-generate title from first user message in a session
        conversationService.autoTitleIfNeeded(userId, currentSessionId, userMessage);
        // What it was sent with is its chat's from now on: the page shows it when the chat is
        // opened again, and the chat's messages from Telegram run on it.
        conversationService.setChatOptions(userId, currentSessionId, chosen);

        // Persist user message BEFORE submitting to the agent loop so conversation
        // history is available when AgentLoop loads context for the LLM.
        String currentMessageId = conversationService.saveMessage(userId, currentSessionId, "user",
                userMessage, attachmentIds);
        // When it was saved, for the frame that says what became of it (below): the page shows it
        // on the bubble it drew as the message was sent, as a reload of the chat will.
        String sentAt = conversationService.timestampOf(currentMessageId);

        // Immediately refresh the sidebar so message count and preview update
        sendToSession(session, "session_updated", currentSessionId);

        // To the task running in this chat when it was asked from the web chat too, whose answer
        // comes here; otherwise a task of its own (TaskQueue#send).
        //
        // Deliberately NOT capturing `session` in the answer. A task takes minutes, and any laptop
        // sleep, Wi-Fi blip or proxy idle-timeout closes the socket that arrived with the
        // request. The client reconnects within seconds and registers a *new* session under
        // the same userId, but the old object stays closed forever -- so delivering to the
        // captured one meant sendToSession's `if (!session.isOpen()) return;` silently
        // dropped the finished answer. The status stream still showed the task completing,
        // because that path resolves the live socket, so the task looked successful and the
        // answer simply never arrived. It is persisted just above, so the only way to see it
        // was to switch chats and back. Resolve the socket at DELIVERY time instead, the way
        // sendSystemToUser already does.
        var sent = new UserMessage(userId, currentSessionId, currentMessageId, userMessage, attachmentIds,
                TaskChat.Channel.WEB, chosen, result -> {
                    // Two texts. The history every later prompt is built from gets the safe one;
                    // a private answer is kept beside it, for this chat and its reload only.
                    // Saving is one half of delivering it, and failing it must not also lose the
                    // other: the answer is still sent, without a time of its own.
                    String answeredAt = null;
                    try {
                        answeredAt = conversationService.timestampOf(
                                conversationService.saveAnswer(userId, currentSessionId, result));
                    } catch (Exception e) {
                        log.warn("Could not save the answer for {}: {}", userId, e.getMessage());
                    }
                    // A question is routed as a question, so the client can offer a reply box
                    // instead of presenting it as the finished answer.
                    sendToUser(userId, result.awaitingUser() ? "input_request" : "response",
                                result.shown(), currentSessionId, result.taskId(), answeredAt);
                    // The chat list has changed (count, preview), so every window fetches it
                    // again. The frame names the chat open now, which a window follows -- not the
                    // one this answer is saved in, which the owner may have left while the task
                    // ran: named, it took his page there, and what he typed next was saved into
                    // the chat he had opened instead.
                    try {
                        sendToUser(userId, "session_updated", conversationService.getCurrentSession(userId));
                    } catch (Exception e) {
                        log.warn("Could not tell {}'s windows the chat list changed: {}", userId, e.getMessage());
                    }
                });
        // No failure arrives here: the queue answers what breaks in a task with the task's ERROR
        // result, answered above like any other, and saving and sending an answer catch the
        // exceptions they throw. A handler for a failed future, writing "Something went wrong",
        // ran only on an Error thrown while an answer was sent, and saved that line beside it.
        TaskQueue.Fate fate = taskQueue.send(sent, queue);

        // What became of it, for the page to say under the bubble it drew -- with its chat, its
        // options and whether it was sent to be queued, from which the page reads where Send
        // reaches the running task: in the chat a message started or reached it in, with the
        // options it was sent with (Inbox#offer), until one sent there without Queue is not taken.
        var said = new LinkedHashMap<String, Object>();
        said.put("type", "fate");
        said.put("fate", fate.name().toLowerCase(java.util.Locale.ROOT));
        if (fate.line() != null) said.put("content", fate.line());
        said.put("messageId", currentMessageId);
        said.put("timestamp", sentAt);
        said.put("sessionId", currentSessionId);
        said.put("options", chosen);
        said.put("queue", queue);
        if (clientId != null) said.put("clientId", clientId);
        send(session, said);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String userId = (String) session.getAttributes().get("userId");
        if (userId != null) {
            // Two-argument remove: only drop the entry if it is still THIS socket.
            //
            // sessions is keyed by user, so a second tab overwrites the first. The old
            // remove(userId) then deleted whatever was there, which meant closing a STALE tab
            // tore down the LIVE one's delivery path: the running task kept going and its answer
            // was posted into a socket that no longer existed. The status stream survived that
            // already, because ChatStatusEmitter is keyed by subscriber, but this map was not.
            Set<WebSocketSession> open = sessions.get(userId);
            boolean wasCurrent = open != null && open.remove(session);
            if (open != null && open.isEmpty()) sessions.remove(userId);
            statusEmitter.unsubscribe(userId, session);
            // Pending input belongs to whoever is actually still connected. Cancelling it from a
            // closing stale tab would kill a prompt the live tab is waiting on.
            if (wasCurrent && (open == null || open.isEmpty())) {
                interactionHandler.cancelPending(userId);
            }
            // Pending input is only cancelled when the LAST window goes away; a stale tab
            // closing must not kill a prompt another window is answering.
            log.info("WebSocket disconnected: user={} ({} still open)",
                    userId, open == null ? 0 : open.size());
        }
    }

    /**
     * @param shared what the shared CommandHandler answered when handleTextMessage asked whether
     *               this is a command. That call already ran the command, so it is used here
     *               rather than asking again. Asking twice ran every shared command twice: /bg
     *               queued its task twice, /new created two chats, and /files rm deleted the file
     *               and then reported that there was no such file.
     */
    private void handleCommand(String userId, String command, Optional<String> shared, String opened,
                               WebSocketSession session) {
        String cmd = command.trim();
        String cmdLower = cmd.toLowerCase();

        // Web-only commands handled locally
        String response;
        if (cmdLower.equals("/setup")) {
            // Setup replaces the cloud API keys and provider, the local model's address and the
            // bot token -- what SettingsController lets the owner alone change.
            if (authService.isOwner(userId)) {
                startSetupWizardIfNeeded(userId);
                response = "";
            } else {
                response = "Only the owner can run setup.";
            }
        } else if (cmdLower.equals("/debug")) {
            boolean enabled = debugService.toggle(userId);
            response = enabled
                    ? "\uD83D\uDC1B Debug mode **ON** — you will see full prompts, raw LLM output, critic verdicts, and tool results."
                    : "\uD83D\uDC1B Debug mode **OFF**";
        } else if (cmdLower.equals("/status")) {
            // Shared status + Web-specific info
            response = shared.orElse("")
                    + " | Connected sockets: " + sessions.values().stream().mapToInt(java.util.Set::size).sum();
        } else {
            // The shared CommandHandler's answer; empty when it did not know the command
            response = shared.orElse(CommandHandler.UNKNOWN_COMMAND);
        }

        // Shown, not kept: a command and its reply are no part of the conversation -- the command
        // itself was never saved, and Telegram never kept either. Kept, the reply was read into
        // the later prompts of the chat as one of its messages: after /log all, every event
        // there is.
        if (response != null && !response.isBlank()) {
            sendToSession(session, "system", response);
        }

        // Session-management commands (/new, /switch) change the active session —
        // refresh the sidebar so the frontend sees the updated session list. The tab /new or
        // /switch was typed in is now in the chat it opened.
        if (commandHandler.isSessionCommand(command)) {
            if (opened != null) bind(session, opened);
            sendActiveSessionInfo(session, userId);
        }
    }

    private void startSetupWizardIfNeeded(String userId) {
        AtomicBoolean running = setupWizardRunning.computeIfAbsent(userId, ignored -> new AtomicBoolean(false));
        if (!running.compareAndSet(false, true)) {
            // Already running
            sendSystemToUser(userId, "Setup wizard is already running — reply to the latest prompt.");
            return;
        }

        // One question waits at a time: a second would take the first one's answer.
        if (interactionHandler.hasPending(userId)) {
            running.set(false);
            sendSystemToUser(userId, "Finish the current input prompt first, then run `/setup` again.");
            return;
        }

        wizardExecutor.submit(() -> {
            try {
                runSetupWizardConversation(userId);
            } finally {
                running.set(false);
            }
        });
    }

    private void runSetupWizardConversation(String userId) {
        int step = 0;
        var current = setupWizard.processStep(step, null);
        if (current.message() != null) {
            sendSystemToUser(userId, current.message());
        }

        while (!current.complete()) {
            String input;
            try {
                input = interactionHandler.requestInputSilent(userId);
            } catch (TimeoutException e) {
                sendSystemToUser(userId, "Setup wizard timed out waiting for input. Run `/setup` to try again.");
                return;
            } catch (ExecutionException e) {
                sendSystemToUser(userId, "Setup wizard was cancelled. Run `/setup` to start again.");
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                sendSystemToUser(userId, "Setup wizard interrupted. Run `/setup` to start again.");
                return;
            }

            step++;
            current = setupWizard.processStep(step, input);
            if (current.message() != null) {
                sendSystemToUser(userId, current.message());
            }
        }
    }

    private void sendSystemToUser(String userId, String message) {
        String sessionId = conversationService.getCurrentSession(userId);
        String savedAt = conversationService.timestampOf(
                conversationService.saveMessage(userId, sessionId, "system", message));

        sendToUser(userId, "system", message, null, null, savedAt);
    }

    private void sendStatusToSession(WebSocketSession session, ChatStatusEmitter.StatusMessage msg) {
        send(session, statusFrame(msg));
    }

    /** A status as the page reads it: a frame of its own, or what a session_info says is going on. */
    private static Map<String, Object> statusFrame(ChatStatusEmitter.StatusMessage msg) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "status");
        payload.put("content", msg.formatted());
        payload.put("status", msg.type().name().toLowerCase());
        // Carried so the client can attribute a step to a task once more than one can run, and
        // show it in that task's chat: tasks of different chats run side by side.
        if (msg.taskId() != null) payload.put("taskId", msg.taskId());
        String chat = chatOf(msg);
        if (chat != null) payload.put("sessionId", chat);
        if (msg.data() != null && !msg.data().isEmpty()) {
            payload.put("data", msg.data());
        }
        return payload;
    }

    /**
     * Send one frame to one socket -- the only place a frame is sent.
     * <p>
     * A socket takes one frame at a time: Tomcat throws IllegalStateException for a send made
     * while another is still being written, and this socket is written from several threads --
     * a task's statuses and its answer from the task queue, a pong or a command's reply from the
     * socket's own. So the sends to a socket take turns here. Whatever a send still throws -- a
     * socket closed since {@code isOpen()} was asked, a broken connection -- costs this frame and
     * nothing else: it used to escape into the caller, and the one that had just saved a task's
     * answer then recorded that the task had failed.
     */
    private void send(WebSocketSession session, Map<String, Object> payload) {
        if (!session.isOpen()) return;
        try {
            TextMessage frame = new TextMessage(mapper.writeValueAsString(payload));
            synchronized (session) {
                session.sendMessage(frame);
            }
        } catch (Exception e) {
            log.warn("Failed to send WS message: {}", e.getMessage());
        }
    }

    /**
     * Send to whichever socket the user holds <em>now</em>, rather than to one captured
     * earlier. Anything produced asynchronously — the result of a task that ran for minutes —
     * must go through here, because the socket that started the work is frequently not the
     * socket that is still connected when the work finishes. If the user is away entirely the
     * message is dropped, which is safe: everything sent this way is persisted first and the
     * client reloads history on connect.
     */
    private void sendToUser(String userId, String type, String content) {
        sendToUser(userId, type, content, null);
    }

    private void sendToUser(String userId, String type, String content, String sessionId) {
        sendToUser(userId, type, content, sessionId, null, null);
    }

    private void sendToUser(String userId, String type, String content, String sessionId,
                            String taskId, String timestamp) {
        Set<WebSocketSession> open = sessions.get(userId);
        if (open == null || open.isEmpty()) {
            log.debug("No live socket for {}; '{}' was persisted but not pushed", userId, type);
            return;
        }
        // Every window, not the newest one. A message that matters to the user matters in
        // whichever window they are actually looking at, and we cannot know which that is.
        int sent = 0;
        for (WebSocketSession live : open) {
            if (live.isOpen()) {
                sendToSession(live, type, content, sessionId, taskId, timestamp);
                sent++;
            }
        }
        if (sent == 0) {
            log.debug("All sockets for {} are closed; '{}' was persisted but not pushed", userId, type);
        }
    }

    private void sendToSession(WebSocketSession session, String type, String content) {
        sendToSession(session, type, content, null);
    }

    /**
     * @param sessionId the conversation this belongs to, when it matters
     *
     * An answer is saved into the chat it was asked from, and pushed to every window. A window
     * showing a different chat appended it anyway, so the reply appeared under a conversation it
     * has nothing to do with -- and replying there filed the follow-up in a third place. The
     * client can only avoid that if it is told which chat the message is for.
     */
    private void sendToSession(WebSocketSession session, String type, String content,
                               String sessionId) {
        sendToSession(session, type, content, sessionId, null, null);
    }

    /**
     * @param taskId    the agent task a message is the outcome of, so the chat can link to it
     * @param timestamp when the row the message shows was saved ({@link ConversationService#timestampOf}),
     *                  so the page shows the time a reload of the chat will; null for one not kept,
     *                  which the page shows with its own clock's
     */
    private void sendToSession(WebSocketSession session, String type, String content,
                               String sessionId, String taskId, String timestamp) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("type", type);
        payload.put("content", content);
        if (sessionId != null) payload.put("sessionId", sessionId);
        if (taskId != null) payload.put("taskId", taskId);
        if (timestamp != null) payload.put("timestamp", timestamp);
        send(session, payload);
    }

    /**
     * This server's run: when it started. A page that connected to an earlier run is the page
     * of an earlier version -- a deploy restarts the server, and a page left open keeps its old
     * code and only reconnects -- so it is told, and asks to be reloaded.
     */
    static final String VERSION = java.time.Instant.now().toString();

    /**
     * Send the active session info to the client.
     */
    private void sendActiveSessionInfo(WebSocketSession session, String userId) {
        try {
            String sessionId = chatOf(session, userId);
            var sessions = conversationService.listSessions(userId, false);
            // taskRunning: a reconnecting browser cannot otherwise know work is in flight.
            // Status messages are live-only and never replayed, so reloading during a long task
            // produced a completely idle chat with an enabled Send button -- which reads as "the
            // request was lost", and the obvious response is to send it again.
            // Attended work only: a scheduled run or /bg is nobody's working state. None of its
            // statuses touches that state (ChatStatusEmitter#BACKGROUND), so a spinner started
            // for it here, on every chat switch, would turn until the page's own timeout.
            // Of the chat the tab shows: tasks of different chats run side by side. And in which
            // of the user's chats one is, for the marks in the list of chats.
            boolean running = taskQueue.isAttendedBusyFor(userId, sessionId);
            var info = new LinkedHashMap<String, Object>();
            info.put("type", "session_info");
            info.put("activeSessionId", sessionId);
            info.put("sessions", sessions);
            info.put("taskRunning", running);
            info.put("workingChats", taskQueue.attendedBusyChats(userId));
            info.put("version", VERSION);
            // And what that task is doing now, as the status the page would otherwise wait for
            // says it: its model call under way, or the step it is on. Without it a reload showed
            // only that a task was running, for as long as the call ran -- over an hour, on
            // 2026-10-08, while the local model reasoned.
            ChatStatusEmitter.StatusMessage doing = running ? statusEmitter.doing(userId, sessionId) : null;
            if (doing != null) info.put("doing", statusFrame(doing));
            send(session, info);
        } catch (Exception e) {
            log.warn("Failed to send session info: {}", e.getMessage());
        }
    }

    /** The key of a socket's attribute naming the chat its tab shows. */
    static final String TAB_CHAT = "chat";

    /** The key of a socket's attribute set when its page works in a chat of its own (tabs=1). */
    static final String TABS = "tabs";

    /**
     * This socket's tab is in this chat from now on -- when its page works in a chat of its own.
     * A page of an earlier version follows the account's open chat, and bound to the chat it
     * switched to, it had what was typed there filed in that chat after it had followed another
     * window to a different one.
     */
    private static void bind(WebSocketSession session, String chat) {
        if (Boolean.TRUE.equals(session.getAttributes().get(TABS))) session.getAttributes().put(TAB_CHAT, chat);
    }

    /**
     * The chat this socket's tab named last -- in its address, or in a frame -- while it is still
     * one of the user's; null when it named none, or one deleted since. One deleted stays named
     * until the tab is in another: a Stop there stops what still runs in it.
     */
    private String namedChat(WebSocketSession session, String userId) {
        Object named = session.getAttributes().get(TAB_CHAT);
        if (named == null) return null;
        return conversationService.hasChat(userId, named.toString()) ? named.toString() : null;
    }

    /** The chat this socket's tab shows: the one it named last, or else the account's open chat. */
    private String chatOf(WebSocketSession session, String userId) {
        String named = namedChat(session, userId);
        return named != null ? named : conversationService.getCurrentSession(userId);
    }

    /** The chat a status is about, as its data names it ({@link ChatStatusEmitter#SESSION}); null for none. */
    private static String chatOf(ChatStatusEmitter.StatusMessage msg) {
        Object chat = msg.data() == null ? null : msg.data().get(ChatStatusEmitter.SESSION);
        return chat == null ? null : chat.toString();
    }

    /** A parameter of the socket's address, decoded; null when it has none. */
    private static String queryParam(WebSocketSession session, String name) {
        String query = session.getUri() != null ? session.getUri().getRawQuery() : null;
        if (query == null) return null;
        for (String param : query.split("&")) {
            if (param.startsWith(name + "=")) {
                String value = java.net.URLDecoder.decode(param.substring(name.length() + 1),
                        java.nio.charset.StandardCharsets.UTF_8);
                return value.isBlank() ? null : value;
            }
        }
        return null;
    }

    /**
     * Resolve user ID from JWT token in query params.
     * Returns null if not authenticated — caller must close the session.
     * Supports: ws://host/ws/chat?token=jwt_token
     */
    private String resolveUserId(WebSocketSession session) {
        String query = session.getUri() != null ? session.getUri().getQuery() : null;
        if (query != null) {
            for (String param : query.split("&")) {
                if (param.startsWith("token=")) {
                    String token = param.substring(6);
                    var userId = authService.validateToken(token);
                    if (userId.isPresent()) {
                        log.info("WebSocket authenticated via JWT: user={}", userId.get());
                        return userId.get();
                    }
                    log.warn("Invalid JWT token on WebSocket connect");
                }
            }
        }
        // No valid token — reject
        log.warn("WebSocket connection rejected: no valid authentication token");
        return null;
    }
}
