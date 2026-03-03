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
import java.util.Map;
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
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

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
        sessions.put(userId, session);

        // Subscribe to status messages
        statusEmitter.subscribe(userId, msg -> {
            if (msg.type() == ChatStatusEmitter.StatusMessage.Type.DEBUG) {
                // Debug messages are rendered as full message blocks, not brief activity entries
                sendToSession(session, "debug", msg.text());
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
                sendToSession(session, "system", "**Connected to OwnClaw.** Send a message to get started.");
            }
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String userId = (String) session.getAttributes().get("userId");
        if (userId == null) return;

        String payload = message.getPayload();
        String userMessage;

        // Accept plain text or JSON {"message": "...", "type": "...", "taskId": "..."}
        String messageType = "message";
        String taskId = null;
        try {
            JsonNode json = mapper.readTree(payload);
            messageType = json.has("type") ? json.path("type").asText("message") : "message";
            userMessage = json.has("message") ? json.path("message").asText() : payload;
            taskId = json.has("taskId") ? json.path("taskId").asText(null) : null;
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
            cancellationService.request(userId);
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
        conversationService.saveMessage(userId, currentSessionId, "user", userMessage);

        // Immediately refresh the sidebar so message count and preview update
        sendToSession(session, "session_updated", currentSessionId);

        // Submit to task queue
        taskQueue.submit(userId, userMessage)
                .thenAccept(response -> {
                    // Persist the assistant response for conversation history
                    conversationService.saveMessage(userId, currentSessionId, "assistant", response);
                    sendToSession(session, "response", response);
                    // Notify frontend to refresh session list (title/preview may have changed)
                    sendToSession(session, "session_updated", currentSessionId);
                })
                .exceptionally(ex -> {
                    log.error("Task failed for {}: {}", userId, ex.getMessage());
                    sendToSession(session, "response", "Something went wrong: " + ex.getMessage());
                    return null;
                });
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String userId = (String) session.getAttributes().get("userId");
        if (userId != null) {
            sessions.remove(userId);
            statusEmitter.unsubscribe(userId);
            interactionHandler.cancelPending(userId);
            log.info("WebSocket disconnected: user={}", userId);
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
                    + " | Connected sessions: " + sessions.size();
        } else {
            // Delegate to shared CommandHandler
            var result = commandHandler.handle(userId, cmd);
            response = result.orElse("Unknown command: " + command + ". Try /help");
        }

        if (response != null && !response.isBlank()) {
            conversationService.saveMessage(userId, sessionId, "system", response);
            sendToSession(session, "system", response);
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

        WebSocketSession ws = sessions.get(userId);
        if (ws != null && ws.isOpen()) {
            sendToSession(ws, "system", message);
        }
    }

    private void sendStatusToSession(WebSocketSession session, ChatStatusEmitter.StatusMessage msg) {
        try {
            String json = mapper.writeValueAsString(Map.of(
                    "type", "status",
                    "content", msg.formatted(),
                    "status", msg.type().name().toLowerCase()
            ));
            session.sendMessage(new TextMessage(json));
        } catch (Exception e) {
            log.warn("Failed to send status to WebSocket: {}", e.getMessage());
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
