package com.ownclaw.interfaces.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownclaw.agent.TaskChat;
import com.ownclaw.config.SetupWizardService;
import com.ownclaw.conversation.ConversationService;
import com.ownclaw.core.TaskCancellationService;
import com.ownclaw.core.TaskQueue;
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
        sessions.computeIfAbsent(userId, u -> ConcurrentHashMap.newKeySet()).add(session);

        // Subscribe to status messages
        // Keyed on this session: a second tab adds a listener rather than replacing this
        // one, and closing a stale tab removes only its own.
        statusEmitter.subscribe(userId, session, msg -> {
            if (msg.type() == ChatStatusEmitter.StatusMessage.Type.DEBUG) {
                // Debug messages are rendered as full message blocks, not brief activity entries
                sendToSession(session, "debug", msg.text());
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

        // Accept plain text or JSON {"message": "...", "type": "...", "attachmentIds": [...]}
        String messageType = "message";
        java.util.List<String> attachmentIds = java.util.List.of();
        try {
            JsonNode json = mapper.readTree(payload);
            messageType = json.has("type") ? json.path("type").asText("message") : "message";
            userMessage = json.has("message") ? json.path("message").asText() : payload;
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

        // Heartbeat ping: keep-alive for long-lived browser connections, sent as {type:'ping'}.
        // Do not treat as user input or command. A typed "ping" is a message like any other:
        // taken for the heartbeat, it was neither saved nor answered, and the page, which had
        // drawn it and started waiting, waited for nothing.
        if ("ping".equalsIgnoreCase(messageType)) {
            sendToSession(session, "pong", "");
            return;
        }

        // The length only: the text can be "/cred set KEY VALUE" or a setup wizard's API key.
        log.debug("WS message from {}: {} chars", userId, userMessage.length());

        // Handle session switching via WebSocket
        if ("switch_session".equals(messageType)) {
            String targetSession = userMessage;
            if (targetSession != null && !targetSession.isBlank()) {
                conversationService.setActiveSession(userId, targetSession);
                // Tell EVERY connection, not just the one that switched.
                //
                // The active session is per USER, not per connection, so switching in one tab
                // silently moved every other tab too -- they kept showing the old conversation
                // while anything typed into them was saved into the new one. The user then had a
                // message filed under a chat they were not looking at, with no indication it had
                // moved. Announcing it lets the other tabs follow.
                sendToUser(userId, "session_updated", targetSession);
                sendActiveSessionInfo(session, userId);
            }
            return;
        }

        // Handle new session creation via WebSocket
        if ("new_session".equals(messageType)) {
            String title = (userMessage != null && !userMessage.isBlank()) ? userMessage : "New Chat";
            String created = conversationService.createSession(userId, title);
            sendActiveSessionInfo(session, userId);
            // Creating a chat changes the ACCOUNT's active session, so the other windows are now
            // pointing at a chat the user has left -- and did not even have the new one in their
            // sidebar. Announcing it lets them follow, the same as an explicit switch.
            sendToUser(userId, "session_updated", created != null ? created : title);
            return;
        }

        // Cancel: user requested task interruption
        if ("cancel".equals(messageType)) {
            // Stop with no task named means "whatever is running, stop it".
            cancellationService.requestAll(userId, "you pressed Stop");
            interactionHandler.cancelPending(userId);
            sendToSession(session, "system", "⏹ Cancellation requested.");
            return;
        }

        // A question waiting for an answer -- the setup wizard's -- takes this message.
        //
        // Asking "does it start with /" first would turn an answer that happens to begin with a
        // slash, a path say, into "Unknown command". So, as on Telegram: try the command, and if
        // it is not a recognised one and a question is waiting, it is the answer. That keeps
        // real commands working while a question waits, without deciding by punctuation what
        // the user meant.
        boolean waiting = interactionHandler.hasPending(userId);
        if (userMessage.startsWith("/")) {
            // Asking whether it is a command runs the command, so this answer is handed on and
            // the handler is not asked again.
            var handledAsCommand = commandHandler.handle(userId, userMessage.trim());
            // The page does not draw a slash text itself; this is its bubble. A secret command's
            // secret is masked by the grammar that reads it, a well-formed one or not; a command
            // nobody knows shows its first word only, as nothing can say which part of
            // "/creds set KEY VALUE" is the secret.
            String typed = userMessage.trim();
            sendToSession(session, "user", handledAsCommand.isPresent() || waiting
                    ? CommandHandler.displayed(typed)
                    : typed.split("(?U)\\s", 2)[0] + (typed.matches("(?U)\\S+") ? "" : " \u2026"));
            if (handledAsCommand.isPresent() || !waiting) {
                handleCommand(userId, userMessage, handledAsCommand, session);
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

        // Auto-generate title from first user message in a session
        String currentSessionId = conversationService.getCurrentSession(userId);
        conversationService.autoTitleIfNeeded(userId, currentSessionId, userMessage);

        // Persist user message BEFORE submitting to the agent loop so conversation
        // history is available when AgentLoop loads context for the LLM.
        String currentMessageId = conversationService.saveMessage(userId, currentSessionId, "user",
                userMessage, attachmentIds);

        // Immediately refresh the sidebar so message count and preview update
        sendToSession(session, "session_updated", currentSessionId);

        // Submit to task queue.
        //
        // Deliberately NOT capturing `session` here. A task takes minutes, and any laptop
        // sleep, Wi-Fi blip or proxy idle-timeout closes the socket that arrived with the
        // request. The client reconnects within seconds and registers a *new* session under
        // the same userId, but the old object stays closed forever -- so delivering to the
        // captured one meant sendToSession's `if (!session.isOpen()) return;` silently
        // dropped the finished answer. The status stream still showed the task completing,
        // because that path resolves the live socket, so the task looked successful and the
        // answer simply never arrived. It is persisted just above, so the only way to see it
        // was to switch chats and back. Resolve the socket at DELIVERY time instead, the way
        // sendSystemToUser already does.
        taskQueue.submit(userId, userMessage, 1, currentMessageId, attachmentIds, TaskChat.Channel.WEB)
                .thenAccept(result -> {
                    // Two texts. The history every later prompt is built from gets the safe one;
                    // a private answer is kept beside it, for this chat and its reload only.
                    // Saving is one half of delivering it, and failing it must not also lose the
                    // other: the answer is still sent.
                    try {
                        conversationService.saveAnswer(userId, currentSessionId, result);
                    } catch (Exception e) {
                        log.warn("Could not save the answer for {}: {}", userId, e.getMessage());
                    }
                    // A question is routed as a question, so the client can offer a reply box
                    // instead of presenting it as the finished answer.
                    sendToUser(userId, result.awaitingUser() ? "input_request" : "response",
                                result.shown(), currentSessionId, result.taskId());
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
        // No failure arrives on the future: the queue returns what breaks in a task as the task's
        // ERROR result, answered above like any other, and saving and sending an answer catch the
        // exceptions they throw. A handler for a failed future, writing "Something went wrong",
        // ran only on an Error thrown while an answer was sent, and saved that line beside it.
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
    private void handleCommand(String userId, String command, Optional<String> shared,
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
        // refresh the sidebar so the frontend sees the updated session list.
        if (commandHandler.isSessionCommand(command)) {
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
        conversationService.saveMessage(userId, sessionId, "system", message);

        sendToUser(userId, "system", message);
    }

    private void sendStatusToSession(WebSocketSession session, ChatStatusEmitter.StatusMessage msg) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "status");
        payload.put("content", msg.formatted());
        payload.put("status", msg.type().name().toLowerCase());
        // Carried so the client can attribute a step to a task once more than one can run.
        if (msg.taskId() != null) payload.put("taskId", msg.taskId());
        if (msg.data() != null && !msg.data().isEmpty()) {
            payload.put("data", msg.data());
        }
        send(session, payload);
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
        sendToUser(userId, type, content, sessionId, null);
    }

    private void sendToUser(String userId, String type, String content, String sessionId,
                            String taskId) {
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
                sendToSession(live, type, content, sessionId, taskId);
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
        sendToSession(session, type, content, sessionId, null);
    }

    /** @param taskId the agent task a message is the outcome of, so the chat can link to it */
    private void sendToSession(WebSocketSession session, String type, String content,
                               String sessionId, String taskId) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("type", type);
        payload.put("content", content);
        if (sessionId != null) payload.put("sessionId", sessionId);
        if (taskId != null) payload.put("taskId", taskId);
        send(session, payload);
    }

    /**
     * Send the active session info to the client.
     */
    private void sendActiveSessionInfo(WebSocketSession session, String userId) {
        try {
            String sessionId = conversationService.getCurrentSession(userId);
            var sessions = conversationService.listSessions(userId, false);
            // taskRunning: a reconnecting browser cannot otherwise know work is in flight.
            // Status messages are live-only and never replayed, so reloading during a long task
            // produced a completely idle chat with an enabled Send button -- which reads as "the
            // request was lost", and the obvious response is to send it again.
            send(session, Map.of(
                    "type", "session_info",
                    "activeSessionId", sessionId,
                    "sessions", sessions,
                    "taskRunning", taskQueue.isBusyFor(userId)
            ));
        } catch (Exception e) {
            log.warn("Failed to send session info: {}", e.getMessage());
        }
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
