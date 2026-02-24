package com.ownclaw.interfaces.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.config.SetupWizardService;
import com.ownclaw.core.TaskQueue;
import com.ownclaw.observability.ChatStatusEmitter;
import com.ownclaw.skillrunner.SkillInteractionHandler;
import com.ownclaw.users.UserRepository;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Telegram bot that uses the HTTP Bot API directly (no heavy SDK).
 * Long-polls for updates and routes messages through the TaskQueue.
 */
@Service
public class TelegramBotService {

    private static final Logger log = LoggerFactory.getLogger(TelegramBotService.class);

    private final OwnClawConfig.Telegram config;
    private final TaskQueue taskQueue;
    private final UserRepository userRepo;
    private final ChatStatusEmitter statusEmitter;
    private final SkillInteractionHandler interactionHandler;
    private final ObjectMapper mapper;
    private final OkHttpClient httpClient;

    private volatile boolean running = false;
    private Thread pollingThread;
    private long lastUpdateId = 0;

    @SuppressWarnings("unused") // setupWizard injected to guarantee applyOverrides() runs first
    public TelegramBotService(OwnClawConfig ownClawConfig, TaskQueue taskQueue,
                              UserRepository userRepo,
                              ChatStatusEmitter statusEmitter, ObjectMapper mapper,
                              SkillInteractionHandler interactionHandler,
                              SetupWizardService setupWizard) {
        this.config = ownClawConfig.getTelegram();
        this.taskQueue = taskQueue;
        this.userRepo = userRepo;
        this.statusEmitter = statusEmitter;
        this.interactionHandler = interactionHandler;
        this.mapper = mapper;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(35, TimeUnit.SECONDS) // long poll timeout + buffer
                .build();
    }

    @PostConstruct
    public void start() {
        if (!config.isEnabled()) {
            log.info("Telegram bot disabled in config");
            return;
        }
        String token = config.getBotToken();
        if (token == null || token.isBlank()) {
            log.warn("Telegram bot token not configured — bot disabled");
            return;
        }

        // Log token prefix for debugging (safe — only the numeric bot-id part)
        String tokenPrefix = token.contains(":") ? token.substring(0, token.indexOf(':')) : "(no colon in token)";
        log.info("Telegram bot token prefix: {}, length: {}", tokenPrefix, token.length());

        // Validate token via getMe before starting poll loop
        String botName = validateToken(token);
        if (botName == null) {
            log.error("Telegram bot token is INVALID (getMe returned error). Fix the token and restart.");
            return;
        }
        log.info("Telegram bot verified: @{}", botName);

        running = true;
        pollingThread = new Thread(this::pollLoop, "telegram-poller");
        pollingThread.setDaemon(true);
        pollingThread.start();
        log.info("Telegram bot polling started");
    }

    /** Call getMe to validate the token. Returns bot username or null. */
    private String validateToken(String token) {
        try {
            Request req = new Request.Builder()
                    .url("https://api.telegram.org/bot" + token + "/getMe")
                    .get().build();
            try (Response resp = httpClient.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) {
                    log.error("Telegram getMe failed: HTTP {} — URL: {}", resp.code(),
                            req.url().toString().replaceAll("bot[^/]+", "bot***"));
                    return null;
                }
                JsonNode root = mapper.readTree(resp.body().string());
                if (!root.path("ok").asBoolean(false)) return null;
                return root.path("result").path("username").asText(null);
            }
        } catch (Exception e) {
            log.error("Telegram getMe exception: {}", e.getMessage());
            return null;
        }
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (pollingThread != null) pollingThread.interrupt();
    }

    /** Restart the bot — called after setup wizard saves a new token. */
    public void restart() {
        stop();
        start();
    }

    private void pollLoop() {
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                pollUpdates();
            } catch (Exception e) {
                if (running) {
                    log.warn("Telegram polling error: {}. Retrying in 5s...", e.getMessage());
                    try { Thread.sleep(5000); } catch (InterruptedException ie) { break; }
                }
            }
        }
    }

    private void pollUpdates() throws IOException, InterruptedException {
        String url = apiUrl("getUpdates") + "?timeout=30&offset=" + (lastUpdateId + 1);
        Request request = new Request.Builder().url(url).get().build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                log.warn("Telegram getUpdates failed: HTTP {}. Backing off 10s...", response.code());
                Thread.sleep(10_000);
                return;
            }

            JsonNode root = mapper.readTree(response.body().string());
            if (!root.path("ok").asBoolean(false)) return;

            for (JsonNode update : root.path("result")) {
                lastUpdateId = Math.max(lastUpdateId, update.path("update_id").asLong());
                handleUpdate(update);
            }
        }
    }

    private void handleUpdate(JsonNode update) {
        JsonNode message = update.path("message");
        if (message.isMissingNode()) return;

        long chatId = message.path("chat").path("id").asLong();
        long telegramUserId = message.path("from").path("id").asLong();
        String text = message.path("text").asText("");
        String firstName = message.path("from").path("first_name").asText("User");

        if (text.isBlank()) return;

        // Resolve or create user
        String userId = userRepo.findByTelegramId(telegramUserId)
                .orElseGet(() -> userRepo.createUser(firstName, telegramUserId));

        // Subscribe to status messages for this user → send to Telegram
        statusEmitter.subscribe(userId, msg -> sendMessage(chatId, msg.formatted()));

        // Interactive skill input: if a skill is waiting for user input, route this message
        // to the pending need_input prompt instead of starting a new task.
        if (interactionHandler.hasPending(userId)) {
            boolean handled = interactionHandler.provideInput(userId, null, text);
            if (!handled) {
                sendMessage(chatId, "No pending input request.");
            }
            return;
        }

        // Submit to task queue — orchestrator handles conversation persistence
        taskQueue.submit(userId, text).thenAccept(response -> {
            sendMessage(chatId, response);
        });
    }

    private void sendMessage(long chatId, String text) {
        try {
            String json = mapper.writeValueAsString(java.util.Map.of(
                    "chat_id", chatId,
                    "text", text,
                    "parse_mode", "Markdown"
            ));

            Request request = new Request.Builder()
                    .url(apiUrl("sendMessage"))
                    .post(RequestBody.create(json, MediaType.get("application/json")))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    log.warn("Telegram sendMessage failed: HTTP {}", response.code());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to send Telegram message: {}", e.getMessage());
        }
    }

    private String apiUrl(String method) {
        return "https://api.telegram.org/bot" + config.getBotToken() + "/" + method;
    }
}
