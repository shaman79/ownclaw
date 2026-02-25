package com.ownclaw.interfaces.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownclaw.config.SetupWizardService;
import com.ownclaw.conversation.ConversationService;
import com.ownclaw.core.TaskQueue;
import com.ownclaw.core.TokenBudgetTracker;
import com.ownclaw.observability.ChatStatusEmitter;
import com.ownclaw.observability.EventLogService;
import com.ownclaw.skills.SkillManifest;
import com.ownclaw.skills.SkillModel;
import com.ownclaw.skillrunner.SkillInteractionHandler;
import com.ownclaw.users.AuthService;
import com.ownclaw.users.CredentialGrantService;
import com.ownclaw.users.CredentialVault;
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
import java.util.List;
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
    private final EventLogService eventLog;
    private final CredentialGrantService credentialGrants;
    private final CredentialVault credentialVault;
    private final SkillManifest skillManifest;
    private final TokenBudgetTracker budgetTracker;
    private final SetupWizardService setupWizard;
    private final AuthService authService;
    private final SkillInteractionHandler interactionHandler;
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
                                ChatStatusEmitter statusEmitter, EventLogService eventLog,
                                CredentialGrantService credentialGrants,
                                CredentialVault credentialVault,
                                SkillManifest skillManifest,
                                TokenBudgetTracker budgetTracker,
                                SetupWizardService setupWizard,
                                AuthService authService,
                                SkillInteractionHandler interactionHandler,
                                ObjectMapper mapper) {
        this.taskQueue = taskQueue;
        this.userRepo = userRepo;
        this.conversationService = conversationService;
        this.statusEmitter = statusEmitter;
        this.eventLog = eventLog;
        this.credentialGrants = credentialGrants;
        this.credentialVault = credentialVault;
        this.skillManifest = skillManifest;
        this.budgetTracker = budgetTracker;
        this.setupWizard = setupWizard;
        this.authService = authService;
        this.interactionHandler = interactionHandler;
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
        statusEmitter.subscribe(userId, msg -> sendToSession(session, "status", msg.formatted()));

        log.info("WebSocket connected: user={}", userId);

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

        // Handle input_response for skill interaction (need_input)
        if ("input_response".equals(messageType)) {
            boolean handled = interactionHandler.provideInput(userId, taskId, userMessage);
            if (!handled) {
                sendToSession(session, "system", "No pending input request.");
            }
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

        // Submit to task queue — orchestrator handles conversation persistence
        taskQueue.submit(userId, userMessage)
                .thenAccept(response -> sendToSession(session, "response", response))
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
        // Delegate to a command handler (inline for Phase 1)
        String response = switch (command.trim().toLowerCase()) {
            case "/help" -> """
                    ### Commands
                    - `/log` — Last 10 events
                    - `/log errors` — Recent errors
                    - `/log tokens` — Token usage today
                    - `/tokens` — Token budget summary
                    - `/skills` — List available skills
                    - `/grant <skill>` — Grant credential access to a skill
                    - `/revoke <skill>` — Revoke credential access
                    - `/cred set <KEY> <VALUE>` — Store a credential
                    - `/cred list` — List stored credential keys
                    - `/cred delete <KEY>` — Delete a credential
                    - `/setup` — Run setup wizard
                    - `/status` — System status
                    - `/help` — This message""";
            case "/setup" -> {
                startSetupWizardIfNeeded(userId);
                yield "";
            }
            case "/status" -> "Queue size: " + taskQueue.getQueueSize()
                    + " | Connected sessions: " + sessions.size();
            default -> {
                if (command.startsWith("/log")) {
                    yield handleLogCommand(userId, command);
                }
                if (command.startsWith("/grant ")) {
                    yield handleGrantCommand(userId, command.substring(7).strip());
                }
                if (command.startsWith("/revoke ")) {
                    yield handleRevokeCommand(userId, command.substring(8).strip());
                }
                if (command.startsWith("/cred ")) {
                    yield handleCredCommand(userId, command.substring(6).strip());
                }
                if (command.equals("/tokens")) {
                    yield budgetTracker.getUsageSummary(userId);
                }
                if (command.equals("/skills")) {
                    yield handleSkillsCommand();
                }
                yield "Unknown command: " + command + ". Try /help";
            }
        };

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

    private String handleLogCommand(String userId, String command) {
        String sub = command.length() > 4 ? command.substring(4).strip().toLowerCase() : "";

        return switch (sub) {
            case "errors" -> {
                List<Map<String, Object>> errors = eventLog.recentErrors(userId, 10);
                if (errors.isEmpty()) yield "No recent errors.";
                var sb = new StringBuilder("Recent errors:\n");
                for (var e : errors) {
                    sb.append("  [").append(e.get("timestamp")).append("] ")
                            .append(e.get("event_type")).append(": ").append(e.get("summary")).append('\n');
                }
                yield sb.toString();
            }
            case "tokens" -> {
                Map<String, Object> usage = eventLog.tokenUsageToday(userId);
                yield "Token usage today: " + usage.get("total_tokens")
                        + " tokens across " + usage.get("total_events") + " events";
            }
            default -> {
                List<Map<String, Object>> events = eventLog.recentEvents(userId, 10);
                if (events.isEmpty()) yield "No recent events.";
                var sb = new StringBuilder("Last 10 events:\n");
                for (var e : events) {
                    String sev = String.valueOf(e.get("severity"));
                    String icon = switch (sev) {
                        case "error" -> "\u274c";
                        case "warn" -> "\u26a0\ufe0f";
                        default -> "\u2139\ufe0f";
                    };
                    sb.append("  ").append(icon).append(" [").append(e.get("timestamp")).append("] ")
                            .append(e.get("event_type")).append(": ").append(e.get("summary")).append('\n');
                }
                yield sb.toString();
            }
        };
    }

    private String handleGrantCommand(String userId, String skillName) {
        if (skillName.isEmpty()) return "Usage: /grant <skill_name>";
        Optional<SkillModel> skill = skillManifest.findByName(skillName);
        if (skill.isEmpty()) return "Skill not found: " + skillName;
        List<String> creds = skill.get().credentials();
        if (creds.isEmpty()) return "Skill '" + skillName + "' does not require any credentials.";
        credentialGrants.grantPermanent(userId, skillName, creds);
        return "\u2705 Permanent credential access granted for '" + skillName + "': " + String.join(", ", creds);
    }

    private String handleRevokeCommand(String userId, String skillName) {
        if (skillName.isEmpty()) return "Usage: /revoke <skill_name>";
        credentialGrants.resetGrants(userId, skillName);
        return "\u274c Credential grants revoked for '" + skillName + "'";
    }

    private String handleSkillsCommand() {
        List<SkillModel> skills = skillManifest.allSkills();
        if (skills.isEmpty()) return "No skills loaded.";
        var sb = new StringBuilder("Available skills (" + skills.size() + "):\n");
        for (var skill : skills) {
            sb.append("  - **").append(skill.name()).append("**: ")
                    .append(skill.summary() != null ? skill.summary() : "(no description)")
                    .append(" [").append(String.join(", ", skill.keywords())).append("]\n");
        }
        return sb.toString();
    }

    private String handleCredCommand(String userId, String args) {
        if (args.isEmpty()) {
            return "Usage: /cred set <KEY> <VALUE> | /cred list | /cred delete <KEY>";
        }

        if (args.equals("list")) {
            List<String> keys = credentialVault.listCredentialKeys(userId);
            if (keys.isEmpty()) return "No credentials stored. Use /cred set <KEY> <VALUE> to store one.";
            var sb = new StringBuilder("\uD83D\uDD10 Stored credentials:\n");
            for (String key : keys) {
                sb.append("  \u2022 ").append(key).append("\n");
            }
            return sb.toString();
        }

        if (args.startsWith("set ")) {
            String rest = args.substring(4).strip();
            int space = rest.indexOf(' ');
            if (space < 1) return "Usage: /cred set <KEY> <VALUE>";
            String key = rest.substring(0, space).toUpperCase();
            String value = rest.substring(space + 1);
            credentialVault.storeCredential(userId, key, value);
            return "\u2705 Credential '" + key + "' stored (encrypted).";
        }

        if (args.startsWith("delete ")) {
            String key = args.substring(7).strip().toUpperCase();
            if (key.isEmpty()) return "Usage: /cred delete <KEY>";
            credentialVault.deleteCredential(userId, key);
            return "\u274c Credential '" + key + "' deleted.";
        }

        return "Usage: /cred set <KEY> <VALUE> | /cred list | /cred delete <KEY>";
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
