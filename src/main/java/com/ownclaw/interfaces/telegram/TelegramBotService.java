package com.ownclaw.interfaces.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.config.SetupWizardService;
import com.ownclaw.conversation.ConversationService;
import com.ownclaw.core.TaskQueue;
import com.ownclaw.interfaces.CommandHandler;
import com.ownclaw.observability.ChatStatusEmitter;
import com.ownclaw.observability.DebugSessionService;
import com.ownclaw.skillrunner.SkillInteractionHandler;
import com.ownclaw.users.UserRepository;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    private final ConversationService conversationService;
    private final SkillInteractionHandler interactionHandler;
    private final CommandHandler commandHandler;
    private final DebugSessionService debugService;
    private final ObjectMapper mapper;
    private final OkHttpClient httpClient;

    private volatile boolean running = false;
    private Thread pollingThread;
    private long lastUpdateId = 0;

    /** Maps Telegram chatId → userId for users that have interacted. */
    private final Map<String, Long> userChatIds = new ConcurrentHashMap<>();

    /**
     * Every message to Telegram is sent from this one thread, in the order it was handed over. A
     * wait Telegram asks for, or a retry after a failure, holds up the messages behind it and
     * nothing else. The sends used to run on the thread that asked for them: a status on the
     * thread of the task that emitted it, a result on the task queue's worker -- which slept out
     * every retry_after while all agent work, the web chat's included, waited on Telegram.
     */
    private final ExecutorService outbox = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "telegram-outbox");
        t.setDaemon(true);
        return t;
    });
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    @org.springframework.beans.factory.annotation.Autowired
    public TelegramBotService(OwnClawConfig ownClawConfig, TaskQueue taskQueue,
                              UserRepository userRepo,
                              ChatStatusEmitter statusEmitter, ObjectMapper mapper,
                              ConversationService conversationService,
                              SkillInteractionHandler interactionHandler,
                              SetupWizardService setupWizard,
                              CommandHandler commandHandler,
                              DebugSessionService debugService,
                              org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this(ownClawConfig, taskQueue, userRepo, statusEmitter, mapper, conversationService,
                interactionHandler, setupWizard, commandHandler, debugService, jdbc,
                new OkHttpClient.Builder()
                        .connectTimeout(10, TimeUnit.SECONDS)
                        .readTimeout(35, TimeUnit.SECONDS) // long poll timeout + buffer
                        .build());
    }

    /** With the HTTP client given: a test's, which answers for Telegram. */
    @SuppressWarnings("unused") // setupWizard injected to guarantee applyOverrides() runs first
    TelegramBotService(OwnClawConfig ownClawConfig, TaskQueue taskQueue,
                       UserRepository userRepo,
                       ChatStatusEmitter statusEmitter, ObjectMapper mapper,
                       ConversationService conversationService,
                       SkillInteractionHandler interactionHandler,
                       SetupWizardService setupWizard,
                       CommandHandler commandHandler,
                       DebugSessionService debugService,
                       org.springframework.jdbc.core.JdbcTemplate jdbc,
                       OkHttpClient httpClient) {
        this.jdbc = jdbc;
        this.config = ownClawConfig.getTelegram();
        this.taskQueue = taskQueue;
        this.userRepo = userRepo;
        this.statusEmitter = statusEmitter;
        this.conversationService = conversationService;
        this.interactionHandler = interactionHandler;
        this.commandHandler = commandHandler;
        this.debugService = debugService;
        this.mapper = mapper;
        this.httpClient = httpClient;
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

        // Register bot menu commands so users see a / menu in Telegram
        registerBotCommands(token);

        running = true;
        restoreKnownChats();
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

        // Only Telegram IDs the owner has linked (/user telegram) may use the bot. Accounts
        // are never created for whoever happens to message it: every account can run code.
        var linkedUser = userRepo.findByTelegramId(telegramUserId);
        if (linkedUser.isEmpty()) {
            log.warn("Ignoring Telegram message from unlinked id={} ({})", telegramUserId, firstName);
            sendMessage(chatId, "This bot is private. To get access, ask the owner to link your Telegram ID: "
                    + telegramUserId);
            return;
        }
        String userId = linkedUser.get();

        // Track chatId for this user, and remember it across restarts -- a private chat only
        // (its id is the sender's own): results, private answers included, go to this chat, and
        // a group he once wrote from would hand them to everyone in it.
        if (chatId == telegramUserId) rememberChat(userId, chatId);

        subscribe(userId);

        // ── /debug — toggle debug mode ──
        if (text.strip().equalsIgnoreCase("/debug")) {
            boolean enabled = debugService.toggle(userId);
            sendMessage(chatId, enabled
                    ? "\uD83D\uDC1B Debug mode ON — the web chat shows full prompts, raw LLM output, critic verdicts, and tool results."
                    : "\uD83D\uDC1B Debug mode OFF");
            return;
        }

        // Handle slash commands consistently with the Web UI
        if (text.startsWith("/")) {
            // "/cred set KEY VALUE" stays in the Telegram chat, on every device he is logged in
            // on, unless it is removed -- asked for first, whatever storing it then does.
            if (CommandHandler.carriesSecret(text)) deleteSecret(chatId, message.path("message_id").asLong());
            var cmdResult = commandHandler.handle(userId, text);
            if (cmdResult.isPresent()) {
                sendMessage(chatId, cmdResult.get());
                // Session commands: send active session info
                if (commandHandler.isSessionCommand(text)) {
                    String sessionId = conversationService.getCurrentSession(userId);
                    sendMessage(chatId, "\uD83D\uDCC2 Active session: " + sessionId);
                }
                return;
            }
            // Not a command, and nothing waits for an answer: say so, as the web chat does.
            // Handed to the agent, a mistyped "/creds set KEY VALUE" went to the cloud and into
            // the chat history, the chat's title, the events and the episodes.
            if (!interactionHandler.hasPending(userId)) {
                sendMessage(chatId, CommandHandler.UNKNOWN_COMMAND);
                return;
            }
        }

        // A question waiting for an answer -- the setup wizard's -- takes this message instead
        // of a new task.
        if (interactionHandler.hasPending(userId)) {
            boolean handled = interactionHandler.provideInput(userId, userId, text);
            if (!handled) {
                sendMessage(chatId, "No pending input request.");
            }
            return;
        }

        // Persist user message for conversation history
        String currentSessionId = conversationService.getCurrentSession(userId);
        conversationService.autoTitleIfNeeded(userId, currentSessionId, text);
        // The row id: the task reads the chat this row was saved in, up to it, and not the row
        // itself, which is its own text (ConversationService#contextOf).
        String currentMessageId =
                conversationService.saveMessage(userId, currentSessionId, "user", text);

        // The task runs on the queue; its answer is saved and sent here.
        taskQueue.submit(userId, text, 1, currentMessageId, java.util.List.of()).thenAccept(result -> {
            // Saved as the web chat saves an answer: the web chat shows it on reload, the private
            // answer included, and links it to what the task did. Saving is one half of
            // delivering it, and failing it must not also lose the other: it is still sent.
            try {
                conversationService.saveAnswer(userId, currentSessionId, result);
            } catch (Exception e) {
                log.warn("Could not save the answer for {}: {}", userId, e.getMessage());
            }
            // The owner's own answer, private text included: he decided Telegram gets it in full
            // -- in his private chat (its id is his own). Asked from a group, the group gets the
            // safe text. What is stored above for later turns is the safe text either way.
            sendMessage(chatId, chatId == telegramUserId ? result.shown() : result.response());
        });
    }

    /**
     * Forward this user's status stream to Telegram -- what is meant for him to read, as
     * {@link #forTelegram} says -- to the chat remembered for him, resolved at delivery time.
     * Subscribing again replaces this bot's listener and no other.
     */
    private void subscribe(String userId) {
        statusEmitter.subscribe(userId, this, msg -> {
            if (!running || !forTelegram(msg)) return;
            StringBuilder sb = new StringBuilder(telegramText(msg));
            // Append token/step stats if available
            Map<String, Object> data = msg.data();
            if (data != null) {
                Object cloud = data.get("cloudTokens");
                Object local = data.get("localTokens");
                Object steps = data.get("totalSteps");
                Object ok = data.get("successCount");
                if (steps != null || cloud != null) {
                    sb.append("\n");
                    if (steps != null) sb.append("Steps ").append(steps).append(" OK ").append(ok != null ? ok : 0).append(" | ");
                    if (cloud != null) sb.append("Cloud ").append(cloud);
                    if (local != null) sb.append(" Local ").append(local);
                }
            }
            // Resolve the chat at DELIVERY time, not from whichever message happened to create
            // this subscription. The lambda used to capture that message's chatId, so once a
            // user had written from a second chat everything kept going to the first.
            Long target = userChatIds.get(userId);
            if (isOwnersChat(userId, target, id -> userRepo.findByTelegramId(id))) {
                sendMessage(target, sb.toString());
            }
        });
    }

    /**
     * Whether a status message is sent to Telegram: what is meant for the owner to read --
     * results, questions, warnings and failures -- and not the queue, step and progress notices
     * of a running task, nor debug output. Those went out one message each, the twenty-second
     * heartbeats of a long code generation and every full prompt of debug mode included; a
     * part Telegram refuses for coming too fast is now waited for, not dropped, and a flood of
     * them would hold up the answer queued behind them.
     */
    static boolean forTelegram(ChatStatusEmitter.StatusMessage msg) {
        return switch (msg.type()) {
            case RESULT, NEED_INPUT, WARNING, FAILED -> true;
            default -> false;
        };
    }

    /**
     * Record a user's Telegram chat, in memory and on disk.
     * <p>
     * The mapping only existed once a message had arrived in this process. This service deploys
     * on every push and therefore restarts often, so after a restart a scheduled result had
     * nowhere to go: ResultDelivery emitted it, no Telegram subscription existed yet, and the
     * message was simply lost until the owner happened to write to the bot. Persisting the chat
     * lets the subscription be restored at startup.
     */
    private void rememberChat(String userId, long chatId) {
        Long previous = userChatIds.put(userId, chatId);
        if (previous != null && previous == chatId) return;
        try {
            jdbc.update("INSERT INTO system_settings (key, value) VALUES (?, ?) "
                            + "ON CONFLICT(key) DO UPDATE SET value = excluded.value",
                    "telegram.chat." + userId, String.valueOf(chatId));
        } catch (Exception e) {
            log.debug("Could not persist the Telegram chat for {}: {}", userId, e.getMessage());
        }
    }

    /**
     * Restore known chats at startup, each subscribed to its user's status stream, so unattended
     * results can be delivered before the user writes anything. The subscription used to be made
     * only when a message arrived, so after every restart -- every deploy -- results reached
     * Telegram only once he had written to the bot again.
     */
    private void restoreKnownChats() {
        try {
            for (var row : jdbc.queryForList(
                    "SELECT key, value FROM system_settings WHERE key LIKE 'telegram.chat.%'")) {
                String userId = String.valueOf(row.get("key")).substring("telegram.chat.".length());
                try {
                    userChatIds.put(userId, Long.parseLong(String.valueOf(row.get("value"))));
                    subscribe(userId);
                } catch (NumberFormatException ignored) { /* a corrupt row is not worth failing on */ }
            }
            if (!userChatIds.isEmpty()) {
                log.info("Restored {} Telegram chat(s); unattended results can be delivered "
                        + "without waiting for an inbound message.", userChatIds.size());
            }
        } catch (Exception e) {
            log.debug("Could not restore Telegram chats: {}", e.getMessage());
        }
    }

    /**
     * What Telegram is sent for a status message: for a result that carries the owner's private
     * text, that text -- the owner decided Telegram gets private answers in full; otherwise the
     * message as it is formatted everywhere.
     */
    public static String telegramText(ChatStatusEmitter.StatusMessage msg) {
        if (msg.type() == ChatStatusEmitter.StatusMessage.Type.RESULT && msg.data() != null
                && msg.data().get("ownerText") instanceof String owner) {
            return owner;
        }
        return msg.formatted();
    }

    /**
     * Whether a remembered chat may still be sent this user's results: it must be the private
     * chat of a Telegram id that is linked to this user now. Checked at delivery, so unlinking or
     * relinking stops the old chat at once, and a group remembered before this check never gets
     * one.
     */
    static boolean isOwnersChat(String userId, Long chat,
                                java.util.function.LongFunction<java.util.Optional<String>> linkedUser) {
        return chat != null && linkedUser.apply(chat).filter(userId::equals).isPresent();
    }

    /** Telegram refuses a message over 4,096 characters; a long answer goes in parts. */
    static final int TELEGRAM_MAX_CHARS = 4096;

    /**
     * The text in parts Telegram accepts: each at most {@code max} UTF-16 units, cut after a line
     * break where one is in the second half of the part, otherwise at the limit -- never between
     * the two halves of a surrogate pair, which would send half a character in each part. Put
     * together, the parts are the text exactly: nothing is stripped at a cut.
     */
    static List<String> telegramParts(String text, int max) {
        var parts = new java.util.ArrayList<String>();
        String rest = text == null ? "" : text;
        while (rest.length() > max) {
            int cut = rest.lastIndexOf('\n', max - 1) + 1;
            if (cut <= max / 2) {
                cut = max;
                if (Character.isHighSurrogate(rest.charAt(cut - 1))) cut--;
            }
            parts.add(rest.substring(0, cut));
            rest = rest.substring(cut);
        }
        parts.add(rest);
        return parts;
    }

    /** Hand a text to the outbox, which sends it in turn, in the parts Telegram accepts. */
    private void sendMessage(long chatId, String text) {
        outbox.execute(() -> deliver(chatId, text));
    }

    /**
     * Send a text in the parts Telegram accepts, in order, each until Telegram takes it.
     * <p>
     * A part Telegram does not take in the end -- it refused it, kept failing or could not be
     * reached -- ends the text there, and the owner is told what is missing. The parts after it
     * used to go out as if nothing were missing: an answer with a hole in the middle, and no word
     * of it.
     */
    private void deliver(long chatId, String text) {
        List<String> parts = telegramParts(text, TELEGRAM_MAX_CHARS);
        for (int i = 0; i < parts.size(); i++) {
            int status = send(chatId, parts.get(i));
            if (status / 100 == 2) continue;
            log.warn("Telegram did not take part {} of {} for chat {}: HTTP {}", i + 1, parts.size(), chatId, status);
            if (send(chatId, notTaken(i + 1, parts.size(), status)) / 100 != 2) {
                log.warn("Telegram did not take the notice of it either");
            }
            return;
        }
    }

    /** What the owner is told when Telegram did not take part {@code part} (from 1) of {@code of}. */
    static String notTaken(int part, int of, int status) {
        String why = status == 0 ? "it could not be reached" : "HTTP " + status;
        return "\u26a0\ufe0f Telegram did not take "
                + (of == 1 ? "a message for you (" + why + ")."
                           : "part " + part + " of " + of + " of a message for you (" + why
                                   + "), so the parts from there on were not sent.")
                + " Answers and results are kept in the web chat.";
    }

    /**
     * One sendMessage call, as plain text; Telegram's HTTP status, as {@link #call} gives it.
     * <p>
     * Not in Telegram's Markdown, which this text is not written in: it is the web chat's, where
     * an underscore inside a word is a letter. Telegram's takes every pair of underscores or
     * asterisks for italic or bold markers and drops them, so result 1 (smtp_send_email) arrived
     * as smtpsendemail and a network named Home_Net_5G as HomeNet5G -- and only a text whose
     * markers did not pair up was refused, and resent as it was.
     */
    private int send(long chatId, String text) {
        var payload = new java.util.LinkedHashMap<String, Object>();
        payload.put("chat_id", chatId);
        payload.put("text", text);
        return call("sendMessage", payload);
    }

    /**
     * Delete the message that holds a secret, in turn with what is sent to the chat -- so before
     * the command's reply -- and tell the owner if Telegram will not: a bot may delete a message
     * in a private chat, and in a group only as an admin.
     */
    private void deleteSecret(long chatId, long messageId) {
        outbox.execute(() -> {
            int status = call("deleteMessage", Map.of("chat_id", chatId, "message_id", messageId));
            if (status / 100 == 2) return;
            log.warn("Telegram deleteMessage failed: HTTP {}", status);
            deliver(chatId, "\u26a0\ufe0f I could not delete your message, and it holds the secret: "
                    + "delete it yourself.");
        });
    }

    /** How often a call is tried that fails on Telegram's side (5xx) or on the way to it. */
    private static final int TRIES = 3;

    /**
     * One Bot API call: Telegram's HTTP status, or 0 if Telegram could not be reached.
     * <p>
     * A 429 is waited out, for as long as Telegram's {@code retry_after} says, and the call made
     * again until it is taken. The parts of a long answer go out back to back, and a part
     * refused for going too fast used to be logged and counted as sent: the answer arrived with
     * a piece missing. A failure on Telegram's side (5xx) or on the way to it is tried again
     * after a second, then after two more; a third failure is the answer.
     */
    private int call(String method, Map<String, Object> payload) {
        try {
            Request request = new Request.Builder()
                    .url(apiUrl(method))
                    .post(RequestBody.create(mapper.writeValueAsString(payload),
                            MediaType.get("application/json")))
                    .build();
            int failures = 0;
            while (true) {
                long waitSeconds;
                try (Response response = httpClient.newCall(request).execute()) {
                    int status = response.code();
                    if (status == 429) waitSeconds = retryAfterSeconds(response);
                    else if (status >= 500 && ++failures < TRIES) waitSeconds = failures;
                    else return status;
                } catch (IOException e) {
                    if (++failures >= TRIES) {
                        log.warn("Telegram {} failed: {}", method, e.getMessage());
                        return 0;
                    }
                    waitSeconds = failures;
                }
                log.info("Telegram {} did not go through; sending it again in {}s", method, waitSeconds);
                Thread.sleep(waitSeconds * 1000);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Telegram {} not sent: interrupted while waiting to send it again", method);
            return 0;
        } catch (Exception e) {
            log.warn("Telegram {} failed: {}", method, e.getMessage());
            return 0;
        }
    }

    /** How long a 429 asks to wait: its {@code parameters.retry_after}, or a second if it names none. */
    private long retryAfterSeconds(Response response) {
        try {
            ResponseBody body = response.body();
            return body == null ? 1
                    : mapper.readTree(body.string()).path("parameters").path("retry_after").asLong(1);
        } catch (Exception e) {
            return 1;
        }
    }

    private String apiUrl(String method) {
        return "https://api.telegram.org/bot" + config.getBotToken() + "/" + method;
    }

    /** Register slash commands so Telegram shows a native “/” menu button. */
    private void registerBotCommands(String token) {
        try {
            var commands = List.of(
                    Map.of("command", "new",     "description", "Start a new chat session"),
                    Map.of("command", "cancel",  "description", "Cancel the running task"),
                    Map.of("command", "debug",   "description", "Toggle debug mode"),
                    Map.of("command", "history", "description", "List your chat sessions"),
                    Map.of("command", "help",    "description", "Show available commands"),
                    Map.of("command", "skills",  "description", "List available tools"),
                    Map.of("command", "status",  "description", "System status"),
                    Map.of("command", "tokens",  "description", "Token budget summary"),
                    Map.of("command", "log",     "description", "Recent events: /log [count|all] [page N]")
            );
            String json = mapper.writeValueAsString(Map.of("commands", commands));
            Request req = new Request.Builder()
                    .url("https://api.telegram.org/bot" + token + "/setMyCommands")
                    .post(RequestBody.create(json, MediaType.get("application/json")))
                    .build();
            try (Response resp = httpClient.newCall(req).execute()) {
                if (resp.isSuccessful()) {
                    log.info("Telegram bot menu registered ({} commands)", commands.size());
                } else {
                    log.warn("Failed to register Telegram bot commands: HTTP {}", resp.code());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to register Telegram bot commands: {}", e.getMessage());
        }
    }
}
