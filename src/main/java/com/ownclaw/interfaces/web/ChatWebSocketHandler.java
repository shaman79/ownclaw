package com.ownclaw.interfaces.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
            } else if (msg.type() == ChatStatusEmitter.StatusMessage.Type.RESULT) {
                // The output of background work is a message, not a status. Rendered as a status
                // it would land in the collapsed activity strip, which is exactly how a finished
                // scheduled task managed to produce a full digest that nobody ever saw.
                // Sent as its own type, not as "response". The client stops the spinner on a
                // plain response, so a background digest landing during a six-minute question
                // ended that question's working state -- the same confusion the taskId fix
                // removed for statuses. "result" renders identically and touches nothing.
                sendToSession(session, "result", msg.text());
            } else if (msg.type() == ChatStatusEmitter.StatusMessage.Type.NEED_INPUT
                    && msg.taskId() == null) {
                // Only a LIVE prompt becomes a question bubble.
                //
                // Two different things emit NEED_INPUT. A skill blocking on an answer emits it
                // with no task id, and that genuinely is a question. The agent loop's closing
                // summary for a task that ended NEEDS_INPUT also emits it, attributed to the
                // task, and that is telemetry -- so the user was shown a second question-styled
                // bubble reading "waiting for your answer - 12,483 cloud tokens" underneath the
                // real question. The id is what tells them apart: a summary always has one, a
                // live prompt never does.

                // A question is not a status. Routed as a status it became one grey line in the
                // activity strip — which is collapsed by default and scrolls — while a skill
                // sat blocked behind a silent two-minute fuse. The client already renders an
                // "input_request" message type with its own styling; nothing on the server ever
                // emitted one, so that renderer was dead code and the feature was unusable
                // despite being marked complete in ARCHITECTURE.md.
                sendToSession(session, "input_request", msg.text());
            } else {
                // Include the raw status sub-type so the frontend can detect terminal statuses
                sendStatusToSession(session, msg);
            }
        });

        log.info("WebSocket connected: user={}", userId);

        // Send the active session info so the frontend can sync
        sendActiveSessionInfo(session, userId);

        // Check if first-run wizard is needed
        if (setupWizard.isSetupNeeded()) {
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

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String userId = (String) session.getAttributes().get("userId");
        if (userId == null) return;

        String payload = message.getPayload();
        String userMessage;

        // Accept plain text or JSON {"message": "...", "type": "...", "taskId": "...", "attachmentIds": [...]}
        String messageType = "message";
        String taskId = null;
        java.util.List<String> attachmentIds = java.util.List.of();
        try {
            JsonNode json = mapper.readTree(payload);
            messageType = json.has("type") ? json.path("type").asText("message") : "message";
            userMessage = json.has("message") ? json.path("message").asText() : payload;
            taskId = json.has("taskId") ? json.path("taskId").asText(null) : null;
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

        // Heartbeat ping: keep-alive for long-lived browser connections.
        // Do not treat as user input or command.
        if ("ping".equalsIgnoreCase(messageType) || "ping".equalsIgnoreCase(userMessage)) {
            sendToSession(session, "pong", "");
            return;
        }

        log.debug("WS message from {}: {}", userId, userMessage);

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
            conversationService.createSession(userId, title);
            sendActiveSessionInfo(session, userId);
            return;
        }

        // Handle input_response for skill interaction (need_input)
        if ("input_response".equals(messageType)) {
            boolean handled = interactionHandler.provideInput(userId, taskId, userMessage);
            if (!handled) {
                sendToSession(session, "system", "No pending input request.");
            }
            return;
        }

        // Cancel: user requested task interruption
        if ("cancel".equals(messageType)) {
            // Stop with no task named means "whatever is running, stop it".
            cancellationService.requestAll(userId);
            interactionHandler.cancelPending(userId);
            sendToSession(session, "system", "⏹ Cancellation requested.");
            return;
        }

        // Interactive skill input: if a skill is waiting for user input, treat this as the response.
        // (Commands still work while waiting.)
        if (!userMessage.startsWith("/") && interactionHandler.hasPending(userId)) {
            boolean handled = interactionHandler.provideInput(userId, taskId, userMessage);
            if (!handled) {
                sendToSession(session, "system", "No pending input request.");
            }
            return;
        }

        // Handle commands
        if (userMessage.startsWith("/")) {
            String sessionId = conversationService.getCurrentSession(userId);
            handleCommand(userId, sessionId, userMessage, session);
            return;
        }

        // Auto-generate title from first user message in a session
        String currentSessionId = conversationService.getCurrentSession(userId);
        conversationService.autoTitleIfNeeded(userId, currentSessionId, userMessage);

        // Persist user message BEFORE submitting to the agent loop so conversation
        // history is available when AgentLoop loads context for the LLM.
        conversationService.saveMessage(userId, currentSessionId, "user", userMessage, attachmentIds);

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
        taskQueue.submit(userId, userMessage)
                .thenAccept(result -> {
                    String response = result.response();
                    // Persist the assistant response for conversation history
                    conversationService.saveMessage(userId, currentSessionId, "assistant", response);
                    // A question is routed as a question, so the client can offer a reply box
                    // instead of presenting it as the finished answer.
                    sendToUser(userId, result.awaitingUser() ? "input_request" : "response", response);
                    // Notify frontend to refresh session list (title/preview may have changed)
                    sendToUser(userId, "session_updated", currentSessionId);
                })
                .exceptionally(ex -> {
                    log.error("Task failed for {}: {}", userId, ex.getMessage());
                    sendToUser(userId, "response", "Something went wrong: " + ex.getMessage());
                    return null;
                });
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

    private void handleCommand(String userId, String sessionId, String command,
                               WebSocketSession session) {
        String cmd = command.trim();
        String cmdLower = cmd.toLowerCase();

        // Web-only commands handled locally
        String response;
        if (cmdLower.equals("/setup")) {
            startSetupWizardIfNeeded(userId);
            response = "";
        } else if (cmdLower.equals("/debug")) {
            boolean enabled = debugService.toggle(userId);
            response = enabled
                    ? "\uD83D\uDC1B Debug mode **ON** — you will see full prompts, raw LLM output, critic verdicts, and tool results."
                    : "\uD83D\uDC1B Debug mode **OFF**";
        } else if (cmdLower.equals("/status")) {
            // Shared status + Web-specific info
            response = commandHandler.handle(userId, cmd).orElse("")
                    + " | Connected sockets: " + sessions.values().stream().mapToInt(java.util.Set::size).sum();
        } else {
            // Delegate to shared CommandHandler
            var result = commandHandler.handle(userId, cmd);
            response = result.orElse("Unknown command: " + command + ". Try /help");
        }

        if (response != null && !response.isBlank()) {
            conversationService.saveMessage(userId, sessionId, "system", response);
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

        // Avoid overlapping interactive flows (skill need_input, etc.).
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
        // A stable taskId so user replies can be routed without the client knowing it.
        String taskId = "setup";

        int step = 0;
        var current = setupWizard.processStep(step, null);
        if (current.message() != null) {
            sendSystemToUser(userId, current.message());
        }

        while (!current.complete()) {
            String input;
            try {
                input = interactionHandler.requestInputSilent(userId, taskId);
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
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", "status");
            payload.put("content", msg.formatted());
            payload.put("status", msg.type().name().toLowerCase());
            // Carried so the client can attribute a step to a task once more than one can run.
            if (msg.taskId() != null) payload.put("taskId", msg.taskId());
            if (msg.data() != null && !msg.data().isEmpty()) {
                payload.put("data", msg.data());
            }
            String json = mapper.writeValueAsString(payload);
            session.sendMessage(new TextMessage(json));
        } catch (Exception e) {
            log.warn("Failed to send status to WebSocket: {}", e.getMessage());
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
                sendToSession(live, type, content);
                sent++;
            }
        }
        if (sent == 0) {
            log.debug("All sockets for {} are closed; '{}' was persisted but not pushed", userId, type);
        }
    }

    private void sendToSession(WebSocketSession session, String type, String content) {
        if (!session.isOpen()) return;
        try {
            String json = mapper.writeValueAsString(Map.of("type", type, "content", content));
            session.sendMessage(new TextMessage(json));
        } catch (IOException e) {
            log.warn("Failed to send WS message: {}", e.getMessage());
        }
    }

    /**
     * Send the active session info to the client.
     */
    private void sendActiveSessionInfo(WebSocketSession session, String userId) {
        try {
            String sessionId = conversationService.getCurrentSession(userId);
            var sessions = conversationService.listSessions(userId, false);
            String json = mapper.writeValueAsString(Map.of(
                    "type", "session_info",
                    "activeSessionId", sessionId,
                    "sessions", sessions
            ));
            session.sendMessage(new TextMessage(json));
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
