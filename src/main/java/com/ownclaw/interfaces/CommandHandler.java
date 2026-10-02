package com.ownclaw.interfaces;

import com.ownclaw.agent.tools.Tool;
import com.ownclaw.agent.tools.ToolRegistry;
import com.ownclaw.config.LocalMode;
import com.ownclaw.conversation.ConversationService;
import com.ownclaw.core.ScheduledTaskService;
import com.ownclaw.core.TaskQueue;
import com.ownclaw.core.TokenBudgetTracker;
import com.ownclaw.observability.EventLogService;
import com.ownclaw.skillrunner.SkillInteractionHandler;
import com.ownclaw.users.AuthService;
import com.ownclaw.users.CredentialGrantService;
import com.ownclaw.users.CredentialVault;
import com.ownclaw.users.UserRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared command handler for slash commands.
 * Used by both the Web UI (ChatWebSocketHandler) and Telegram bot (TelegramBotService)
 * to ensure consistent behavior across interfaces.
 * <p>
 * Returns the command response text, or {@link Optional#empty()} if the input is not one of
 * these commands: the caller may have its own, may be waiting for an answer that starts with a
 * slash, or answers {@link #UNKNOWN_COMMAND}. It is never handed to the agent as a message.
 */
@Service
public class CommandHandler {

    private final ToolRegistry toolRegistry;
    private final ConversationService conversationService;
    private final EventLogService eventLog;
    private final TokenBudgetTracker budgetTracker;
    private final CredentialVault credentialVault;
    private final CredentialGrantService credentialGrants;
    private final TaskQueue taskQueue;
    private final ScheduledTaskService scheduledTaskService;
    private final AuthService authService;
    private final UserRepository userRepo;
    private final com.ownclaw.conversation.FileStorageService fileStorage;
    private final com.ownclaw.core.TaskCancellationService cancellationService;
    private final com.ownclaw.core.ResultDelivery resultDelivery;
    private final SkillInteractionHandler interactionHandler;
    private final LocalMode localMode;

    public CommandHandler(ToolRegistry toolRegistry, ConversationService conversationService,
                          EventLogService eventLog, TokenBudgetTracker budgetTracker,
                          CredentialVault credentialVault,
                          CredentialGrantService credentialGrants, TaskQueue taskQueue,
                          ScheduledTaskService scheduledTaskService,
                          AuthService authService, UserRepository userRepo,
                          com.ownclaw.conversation.FileStorageService fileStorage,
                          com.ownclaw.core.TaskCancellationService cancellationService,
                          com.ownclaw.core.ResultDelivery resultDelivery,
                          SkillInteractionHandler interactionHandler, LocalMode localMode) {
        this.toolRegistry = toolRegistry;
        this.conversationService = conversationService;
        this.eventLog = eventLog;
        this.budgetTracker = budgetTracker;
        this.credentialVault = credentialVault;
        this.credentialGrants = credentialGrants;
        this.taskQueue = taskQueue;
        this.scheduledTaskService = scheduledTaskService;
        this.authService = authService;
        this.userRepo = userRepo;
        this.fileStorage = fileStorage;
        this.cancellationService = cancellationService;
        this.resultDelivery = resultDelivery;
        this.interactionHandler = interactionHandler;
        this.localMode = localMode;
    }

    /**
     * Try to handle a slash command. Returns the response if it's a known command, or empty.
     *
     * @param userId  the user ID
     * @param message the raw message text
     * @return response text, or empty if not a command
     */
    public Optional<String> handle(String userId, String message) {
        if (message == null || !message.startsWith("/")) {
            return Optional.empty();
        }
        Matcher secret = SECRET_COMMAND.matcher(message);
        if (secret.matches()) {
            boolean cred = secret.group(1).toLowerCase(Locale.ROOT).startsWith("/cred");
            if (secret.group(2) == null) {
                // No name and secret to read: the command's own handler answers with its usage,
                // however the command was spaced.
                return Optional.of(cred ? handleCred(userId, "set " + secret.group(4))
                        : handleUser(userId, "add " + secret.group(4)));
            }
            return Optional.of(cred ? storeCredential(userId, secret.group(2), secret.group(3))
                    : addUser(userId, secret.group(2), secret.group(3)));
        }

        String command = message.trim().toLowerCase();

        return switch (command) {
            case "/help" -> Optional.of(helpText());
            case "/skills" -> Optional.of(skillsText());
            case "/cancel" -> {
                // /cancel was listed in this very help text and handled only by the Telegram
                // interface, so typing it in the web UI did nothing at all — it fell through to
                // the agent as an ordinary message. The Stop button worked; the documented
                // command did not.
                // A question waiting for an answer -- the setup wizard's -- is cancelled with it,
                // as Stop cancels it. Telegram had a /cancel of its own that did; this one did not,
                // and the wizard took the next message typed in the web chat as its answer: at
                // the cloud step, as the cloud API key.
                cancellationService.requestAll(userId, "you sent /cancel");
                interactionHandler.cancelPending(userId);
                yield Optional.of("Cancelling. A model call the task is waiting on ends at once, "
                        + "and a delegation stops before its next step; a skill that is running is "
                        + "not interrupted, so the task stops when it returns.");
            }
            case "/status" -> Optional.of(statusText());
            case "/tokens" -> {
                String budget = budgetTracker.getUsageSummary(userId);
                Map<String, Object> detail = eventLog.tokenUsageDetailToday(userId);
                long cloud = ((Number) detail.get("cloud_tokens")).longValue();
                long local = ((Number) detail.get("local_tokens")).longValue();
                yield Optional.of(String.format("%s\nCloud: %,d  |  Local: %,d", budget, cloud, local));
            }
            case "/history" -> Optional.of(handleHistory(userId));
            // A /queue with a message is no command here: the chat queues the message (queued).
            // One with nothing to run is told how to use it.
            case "/queue" -> Optional.of(QUEUE_USAGE);
            default -> {
                if (command.equals("/new") || command.startsWith("/new ")) {
                    yield Optional.of(handleNew(userId, message.trim()));
                }
                if (command.startsWith("/switch ")) {
                    yield Optional.of(handleSwitch(userId, message.trim().substring(8).strip()));
                }
                if (command.equals("/log") || command.startsWith("/log ")) {
                    yield Optional.of(handleLog(userId, command.substring(4).strip()));
                }
                if (command.startsWith("/grant ")) {
                    yield Optional.of(handleGrant(userId, message.trim().substring(7).strip()));
                }
                if (command.startsWith("/revoke ")) {
                    yield Optional.of(handleRevoke(userId, message.trim().substring(8).strip()));
                }
                if (command.startsWith("/cred ")) {
                    yield Optional.of(handleCred(userId, message.trim().substring(6).strip()));
                }
                if (command.equals("/files") || command.startsWith("/files ")) {
                    yield Optional.of(handleFiles(userId, message.trim()));
                }
                if (command.equals("/bg") || command.startsWith("/bg ")) {
                    yield Optional.of(handleBackground(userId, message.trim()));
                }
                if (command.startsWith("/schedule")) {
                    yield Optional.of(handleSchedule(userId, message.trim()));
                }
                if (command.equals("/local") || command.startsWith("/local ")) {
                    yield Optional.of(handleLocal(userId, command.substring(6).strip()));
                }
                if (command.equals("/user") || command.startsWith("/user ")) {
                    yield Optional.of(handleUser(userId, message.trim().substring(5).strip()));
                }
                // Not a recognized shared command — caller may handle interface-specific
                // commands (like /debug, /setup) or treat as unknown.
                yield Optional.empty();
            }
        };
    }

    /**
     * The two commands whose text carries a secret -- {@code /cred set KEY VALUE} and
     * {@code /user add NAME PASSWORD} -- read once: group 1 is the command, 2 the name, 3 the
     * secret. {@link #handle} takes the secret from this match and {@link #displayed} hides the
     * same span, so no text can be stored as a secret and then shown, logged or kept as typed.
     * A second reading of the same text differs at some space or letter case, and the secret
     * sits in the gap: the parse this replaces split on ' ' and routed on the lowercased text, so
     * "/cred&lt;NBSP&gt;set KEY VALUE" was no command at all and came back into the chat whole.
     * <p>
     * One of the two commands followed by anything else is read as well, as group 4: "/cred set
     * SMTP_PASS=hunter2", "/user add bob:pw", a value typed where the key goes. Nothing is stored
     * from it and it is answered with the usage, but it carries a secret as surely as the form
     * that works, so it is hidden and deleted the same way; shown as typed, the password stayed on
     * the screen and in the Telegram chat.
     * <p>
     * Neighbouring parts never match the same character, so a text splits one way only and a
     * long run of spaces cannot make the matcher try every split; the second reading is tried
     * only once the first has failed. The time is linear in the text.
     */
    private static final Pattern SECRET_COMMAND = Pattern.compile(
            "(/cred\\s+set|/user\\s+add)(?:\\s+(\\S+)\\s+(\\S.*)|\\s+(\\S.*))",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL | Pattern.UNICODE_CHARACTER_CLASS);

    /** {@code /queue <message>}: its message, read once (group 1). */
    private static final Pattern QUEUE_COMMAND = Pattern.compile("/queue(?:\\s+(.*))?",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL | Pattern.UNICODE_CHARACTER_CLASS);

    /** What a bare {@code /queue} is answered. */
    static final String QUEUE_USAGE = "Usage: `/queue <message>` — runs the message as a task of its own "
            + "after the work ahead of it, instead of handing it to the task running in this chat.";

    /**
     * The message of a {@code /queue <message>} command -- which a chat saves and queues as its
     * own task, after the work ahead of it, instead of handing it to the running task -- or null
     * when the text is no such command. One with nothing to run, or with a command where the
     * message goes, is none: a command is not sent to the agent as a message, and {@link #handle}
     * answers a bare {@code /queue} with its usage.
     */
    public static String queued(String text) {
        Matcher m = text == null ? null : QUEUE_COMMAND.matcher(text.strip());
        if (m == null || !m.matches() || m.group(1) == null) return null;
        String message = m.group(1).strip();
        return message.isEmpty() || message.startsWith("/") ? null : message;
    }

    /** What a chat shows for an unknown command. The text itself is not repeated: it may hold a secret. */
    public static final String UNKNOWN_COMMAND = "Unknown command. Try /help";

    /**
     * The text as a chat may show, log or keep it: a secret command's secret masked -- all that
     * follows the command when the grammar cannot read a name and a secret in it -- and anything
     * else as sent.
     */
    public static String displayed(String message) {
        Matcher m = message == null ? null : SECRET_COMMAND.matcher(message);
        if (m == null || !m.matches()) return message;
        return m.group(2) != null ? m.group(1) + " " + m.group(2) + " \u2022\u2022\u2022\u2022\u2022\u2022"
                : m.group(1) + " \u2026";
    }

    /**
     * Whether this text carries a secret: one {@link #handle} stores, or one typed into a secret
     * command it cannot read.
     */
    public static boolean carriesSecret(String message) {
        return message != null && SECRET_COMMAND.matcher(message).matches();
    }

    // ── Help ──

    /**
     * {@code /bg <task>} — run something without waiting for it.
     * <p>
     * Submitted at background priority, which means two things. It runs on the background lane
     * rather than ahead of your next message (when that lane is enabled), and it is marked
     * unattended: nobody is waiting on it (see AgentContext.isUnattended).
     * <p>
     * Returns as soon as it is queued. The result is saved into the chat this was typed in --
     * the one Telegram messages are saved in, when typed there -- and arrives on every interface
     * you have attached, so starting something here and reading the answer on Telegram works.
     */
    private String handleBackground(String userId, String fullMessage) {
        String task = fullMessage.length() > 3 ? fullMessage.substring(3).strip() : "";
        if (task.isBlank()) {
            return "Usage: `/bg <task>` — runs it in the background and tells you when it is done.\n"
                    + "Use it for anything you do not want to sit and wait for.";
        }
        // Where it was typed, read now: minutes later the open chat can be another one, and the
        // answer would be saved under a conversation it has nothing to do with.
        String sessionId = conversationService.getCurrentSession(userId);
        // The future used to be discarded, so this promise was never kept: the task ran, the
        // answer was produced, and nothing delivered it. Only the status lines appeared.
        taskQueue.submit(userId, task, TaskQueue.BACKGROUND_PRIORITY)
                .thenAccept(result -> resultDelivery.deliver(
                        userId, () -> sessionId, "Background task: " + task, result));
        return "Running in the background:\n> " + task
                + "\n\nYou will get the result here when it finishes — no need to wait.";
    }

    /**
     * {@code /files} — list uploaded files, {@code /files rm <id>} — delete one.
     * <p>
     * The endpoints behind this have existed since uploads were built and had no callers at
     * all, so files accumulated on disk with no way to see or remove them. That mattered more
     * once uploads started working again: they were broken by an undefined variable for long
     * enough that nobody noticed the other half was missing too.
     * <p>
     * A command rather than a panel, because this is housekeeping that is wanted occasionally,
     * and a list with a delete is the whole feature.
     */
    private String handleFiles(String userId, String fullMessage) {
        String arg = fullMessage.length() > 6 ? fullMessage.substring(6).strip() : "";

        if (arg.startsWith("rm ")) {
            String fileId = arg.substring(3).strip();
            if (fileId.isBlank()) return "Usage: `/files rm <id>`";
            boolean deleted = fileStorage.delete(userId, fileId);
            return deleted ? "Deleted `" + fileId + "`."
                           : "No file `" + fileId + "` belonging to you.";
        }

        var files = fileStorage.listUserFiles(userId);
        if (files.isEmpty()) return "No uploaded files.";

        long totalBytes = 0;
        var sb = new StringBuilder("### Uploaded files\n");
        for (var f : files) {
            Object sizeObj = f.get("size_bytes");
            long size = sizeObj instanceof Number n ? n.longValue() : 0;
            totalBytes += size;
            sb.append("- `").append(f.get("id")).append("` ")
              .append(f.get("original_name"))
              .append(" (").append(humanBytes(size)).append(", ")
              .append(f.get("uploaded_at")).append(")\n");
        }
        sb.append("\n").append(files.size()).append(" file(s), ")
          .append(humanBytes(totalBytes)).append(" total. Remove one with `/files rm <id>`.");
        return sb.toString();
    }

    private static String humanBytes(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return String.format("%.1f KB", b / 1024.0);
        return String.format("%.1f MB", b / (1024.0 * 1024));
    }

    /**
     * {@code /local [on|off]}: the owner's local-only switch ({@link LocalMode}), shown or set.
     * Setting it is the owner's: it decides where every account's tasks run.
     */
    private String handleLocal(String userId, String arg) {
        if (!arg.isEmpty()) {
            if (!authService.isOwner(userId)) return "Only the owner can switch the cloud model on or off.";
            switch (arg) {
                case "on" -> localMode.set(true);
                case "off" -> localMode.set(false);
                default -> {
                    return "Usage: `/local on` -- every task runs on the local model, no cloud model is "
                            + "called; `/local off` -- back to the cloud; `/local` -- which it is.";
                }
            }
        }
        return localMode.on()
                ? "Local only is **on**: no cloud model is called, and every task runs on the local model, "
                        + "best effort. `/local off` switches the cloud model back on."
                : "Local only is **off**: tasks run on the cloud model, and one whose cloud model can't be "
                        + "reached goes on on the local model by itself. `/local on` stops calling the cloud.";
    }

    private String helpText() {
        return """
                ### Commands
                - `/new [title]` — Start a new chat session
                - `/history` — List your chat sessions
                - `/switch <N>` — Switch to session N from history
                - `/log [count|all] [page N]` — Recent events, newest first (10 unless a count is given)
                - `/log errors [count|all] [page N]` — Recent errors, the same way
                - `/log tokens` — Token usage today
                - `/tokens` — Token budget summary
                - `/skills` — List available tools
                - `/bg <task>` — Run it in the background; the result comes back when ready
                - `/queue <message>` — Run it as a task of its own after the running one, instead of sending it to that task (the web chat's Queue button)
                - `/files` — List uploaded files (`/files rm <id>` to delete one)
                - `/debug` — Toggle debug mode
                - `/cancel` — Cancel the running task
                - `/grant <tool> <credential>` — Grant credential access to a tool
                - `/revoke <tool>` — Revoke credential access
                - `/cred set <KEY> <VALUE>` — Store a credential
                - `/cred list` — List stored credential keys
                - `/cred delete <KEY>` — Delete a credential
                - `/user list` — List accounts (owner only; also in Settings → Accounts)
                - `/user add <username> <password>` — Create an account (owner only)
                - `/user disable <username|id>` — Revoke an account's access (owner only)
                - `/user telegram <username> <telegram id>` — Let a Telegram ID use the bot (owner only)
                - `/schedule` — List scheduled/deferred tasks
                - `/schedule in <time> <task>` — Run a task after a delay
                - `/schedule every <schedule> : <task>` — Recurring task
                - `/schedule cancel|pause|resume <id>` — Manage tasks
                - `/setup` — Run setup wizard (Web UI only)
                - `/local [on|off]` — Local only: every task runs on the local model and no cloud model is called (switching is owner only)
                - `/status` — System status
                - `/help` — This message""";
    }

    // ── Accounts (owner only) ──

    private String handleUser(String userId, String args) {
        if (!authService.isOwner(userId)) {
            return "Only the owner can manage accounts.";
        }
        String[] parts = args.split("\\s+");
        String usage = "Usage: `/user list` | `/user add <username> <password>` | "
                + "`/user disable <username|id>` | `/user telegram <username> <telegram id>`";

        switch (parts[0].toLowerCase()) {
            case "list" -> {
                var sb = new StringBuilder("### Accounts\n");
                for (Map<String, Object> u : userRepo.listUsers()) {
                    String id = (String) u.get("id");
                    boolean hasPassword = ((Number) u.get("has_password")).intValue() != 0;
                    Object telegramId = u.get("telegram_id");
                    sb.append("- `").append(id).append("` **").append(u.get("display_name")).append("**");
                    if (authService.isOwner(id)) sb.append(" — owner");
                    sb.append(hasPassword ? " — web login" : "");
                    sb.append(telegramId != null ? " — Telegram " + telegramId : "");
                    if (!hasPassword && telegramId == null) sb.append(" — disabled");
                    sb.append(" — created ").append(u.get("created_at")).append("\n");
                }
                return sb.toString();
            }
            case "add" -> {
                return ADD_USAGE;   // a well-formed add is read by SECRET_COMMAND in handle()
            }
            case "disable" -> {
                if (parts.length != 2) return usage;
                try {
                    String target = authService.disableAccount(parts[1]);
                    return "\u2705 Account `" + target + "` disabled: it can no longer log in, its sessions are "
                            + "signed out and its Telegram ID is unlinked. Its data is kept.";
                } catch (IllegalArgumentException e) {
                    return "\u274C " + e.getMessage();
                }
            }
            case "telegram" -> {
                if (parts.length != 3) return usage;
                Optional<String> target = userRepo.findAccountByUsername(parts[1]);
                if (target.isEmpty()) return "\u274C No such account: " + parts[1];
                try {
                    userRepo.linkTelegram(target.get(), Long.parseLong(parts[2]));
                    return "\u2705 Telegram ID " + parts[2] + " can now use the bot as **" + parts[1] + "**.";
                } catch (NumberFormatException e) {
                    return "\u274C The Telegram ID must be a number.";
                } catch (DataAccessException e) {
                    return "\u274C That Telegram ID is already linked to another account (see /user list).";
                }
            }
            default -> {
                return usage;
            }
        }
    }

    private static final String ADD_USAGE =
            "Usage: `/user add <username> <password>` (password: at least 4 characters, no spaces)";

    /** A well-formed {@code /user add}, as SECRET_COMMAND read it. */
    private String addUser(String userId, String username, String password) {
        if (!authService.isOwner(userId)) {
            return "Only the owner can manage accounts.";
        }
        password = password.strip();
        if (!password.matches("(?U)\\S{4,}")) return ADD_USAGE;
        try {
            authService.register(username, password, userId);
            return "\u2705 Account **" + username + "** created.";
        } catch (IllegalArgumentException e) {
            return "\u274C " + e.getMessage();
        }
    }

    // ── Session management ──

    private String handleNew(String userId, String raw) {
        String title = raw.length() > 4 ? raw.substring(4).strip() : "";
        if (title.isEmpty()) title = "New Chat";
        String sessionId = conversationService.createSession(userId, title);
        return "\u2705 New session created: **" + title + "**";
    }

    private String handleHistory(String userId) {
        List<Map<String, Object>> sessions = conversationService.listSessions(userId, false);
        if (sessions.isEmpty()) return "No chat sessions found.";

        var sb = new StringBuilder("### Chat History\n");
        var fmt = DateTimeFormatter.ofPattern("MMM d, HH:mm").withZone(ZoneId.systemDefault());
        int n = 0;
        for (var s : sessions) {
            n++;
            String title = String.valueOf(s.get("title"));
            Object msgCount = s.get("message_count");
            Object updatedAt = s.get("updated_at");
            String timeStr = "";
            try {
                timeStr = " (" + fmt.format(Instant.parse(String.valueOf(updatedAt))) + ")";
            } catch (Exception ignored) { }
            sb.append("  **").append(n).append(".** ").append(title)
                    .append(" — ").append(msgCount).append(" msgs").append(timeStr).append("\n");
        }
        sb.append("\nUse `/switch <N>` to switch to a session.");
        return sb.toString();
    }

    private String handleSwitch(String userId, String arg) {
        int num;
        try {
            num = Integer.parseInt(arg);
        } catch (NumberFormatException e) {
            return "Usage: `/switch <number>` — use `/history` to see session numbers.";
        }
        List<Map<String, Object>> sessions = conversationService.listSessions(userId, false);
        if (num < 1 || num > sessions.size()) {
            return "Invalid session number. Use /history to see available sessions (1-" + sessions.size() + ").";
        }
        var target = sessions.get(num - 1);
        String sessionId = String.valueOf(target.get("id"));
        String title = String.valueOf(target.get("title"));
        conversationService.setActiveSession(userId, sessionId);
        return "\u2705 Switched to session: **" + title + "**";
    }

    /**
     * Returns true if the given command is a session-management command
     * ({@code /new}, {@code /history}, {@code /switch}) that may require
     * the caller to refresh session UI state.
     */
    public boolean isSessionCommand(String message) {
        if (message == null) return false;
        String cmd = message.trim().toLowerCase();
        return cmd.equals("/new") || cmd.startsWith("/new ")
                || cmd.equals("/history")
                || cmd.startsWith("/switch ");
    }

    // ── Skills ──

    private String skillsText() {
        var tools = toolRegistry.all();
        if (tools.isEmpty()) return "No tools loaded.";
        var sb = new StringBuilder("Available tools (" + tools.size() + "):\n");
        for (Tool tool : tools.stream().sorted(Comparator.comparing(Tool::name)).toList()) {
            sb.append("  - **").append(tool.name()).append("**")
                    .append(tool.requiresNetwork() ? " [network]" : "")
                    .append(tool.hasSideEffects() ? " [side-effects]" : "")
                    .append("\n");
        }
        return sb.toString();
    }

    // ── Status ──

    private String statusText() {
        return "Queue size: " + taskQueue.getQueueSize();
    }

    // ── Log ──

    /** Rows on a /log page when no count is given. Every row stays reachable: by count, by page, or all. */
    private static final int LOG_PAGE = 10;

    private static final String LOG_USAGE = "Usage: `/log [errors] [count|all] [page N]` — newest first, "
            + LOG_PAGE + " to a page unless a count is given. `/log tokens` — token usage today.";

    /**
     * {@code /log [errors] [count|all] [page N]} and {@code /log tokens}.
     * <p>
     * It showed the last ten events, or errors, and nothing older could be reached from the chat
     * at all -- which on Telegram, with no ops page at hand, was the only way to look.
     *
     * @param args the text after {@code /log}, lowercased
     */
    private String handleLog(String userId, String args) {
        if (args.equals("tokens")) {
            Map<String, Object> detail = eventLog.tokenUsageDetailToday(userId);
            String budget = budgetTracker.getUsageSummary(userId);
            long cloud = ((Number) detail.get("cloud_tokens")).longValue();
            long local = ((Number) detail.get("local_tokens")).longValue();
            long total = ((Number) detail.get("total_tokens")).longValue();
            long tasks = ((Number) detail.get("task_count")).longValue();
            return String.format("Token usage today:\n  Cloud: %,d  |  Local: %,d  |  Total: %,d\n  Tasks: %d\n  Budget: %s",
                    cloud, local, total, tasks, budget);
        }
        String[] words = args.isEmpty() ? new String[0] : args.split("\\s+");
        int i = 0;
        boolean errors = i < words.length && words[i].equals("errors");
        if (errors) i++;
        long count = LOG_PAGE;   // negative: every row
        long page = 1;
        try {
            if (i < words.length && words[i].equals("all")) {
                count = -1;
                i++;
            } else if (i < words.length && words[i].matches("\\d+")) {
                count = Long.parseLong(words[i++]);
            }
            if (i + 1 < words.length && words[i].equals("page") && words[i + 1].matches("\\d+")) {
                page = Long.parseLong(words[i + 1]);
                i += 2;
            }
        } catch (NumberFormatException e) {
            return LOG_USAGE;   // more digits than a long holds
        }
        if (i != words.length || count == 0 || page < 1 || (count < 0 && page > 1)) return LOG_USAGE;
        long offset;
        try {
            offset = count < 0 ? 0 : Math.multiplyExact(page - 1, count);
        } catch (ArithmeticException e) {
            return LOG_USAGE;
        }

        // One more than asked for, to know whether an older page exists.
        long fetch = count < 0 ? -1 : count + 1;
        List<Map<String, Object>> rows = errors
                ? eventLog.recentErrors(userId, fetch, offset)
                : eventLog.recentEvents(userId, fetch, offset);
        String what = errors ? "errors" : "events";
        if (rows.isEmpty()) {
            return page == 1 ? "No recent " + what + "." : "No " + what + " on page " + page + ".";
        }
        boolean older = count >= 0 && rows.size() > count;
        if (older) rows = rows.subList(0, (int) count);

        var sb = new StringBuilder(errors ? "Errors " : "Events ")
                .append(offset + 1).append('\u2013').append(offset + rows.size()).append(", newest first:\n");
        for (var e : rows) {
            String icon = switch (String.valueOf(e.get("severity"))) {
                case "error" -> "\u274c";
                case "warn" -> "\u26a0\ufe0f";
                default -> "\u2139\ufe0f";
            };
            sb.append("  ").append(icon).append(" [").append(e.get("timestamp")).append("] ")
                    .append(e.get("event_type")).append(": ").append(e.get("summary")).append('\n');
        }
        if (older) {
            sb.append("Older: `/log ").append(errors ? "errors " : "").append(count)
                    .append(" page ").append(page + 1).append('`');
        }
        return sb.toString();
    }

    // ── Credentials ──

    private String handleCred(String userId, String args) {
        if (args.isEmpty()) {
            return "Usage: `/cred set <KEY> <VALUE>` | `/cred list` | `/cred delete <KEY>`";
        }

        if (args.equals("list")) {
            List<String> keys = credentialVault.listCredentialKeys(userId);
            if (keys.isEmpty()) return "No credentials stored. Use `/cred set <KEY> <VALUE>` to store one.";
            var sb = new StringBuilder("\uD83D\uDD10 Stored credentials:\n");
            for (String key : keys) {
                sb.append("  \u2022 ").append(key).append("\n");
            }
            return sb.toString();
        }

        if (args.startsWith("delete ")) {
            String key = args.substring(7).strip().toUpperCase();
            if (key.isEmpty()) return "Usage: `/cred delete <KEY>`";
            credentialVault.deleteCredential(userId, key);
            return "\u274c Credential '" + key + "' deleted.";
        }

        return "Usage: `/cred set <KEY> <VALUE>` | `/cred list` | `/cred delete <KEY>`";
    }

    /** A well-formed {@code /cred set}, as SECRET_COMMAND read it. */
    private String storeCredential(String userId, String key, String value) {
        key = key.toUpperCase(Locale.ROOT);
        credentialVault.storeCredential(userId, key, value.strip());
        return "\u2705 Credential '" + key + "' stored (encrypted).";
    }

    // ── Scheduled Tasks ──

    private String handleSchedule(String userId, String rawCommand) {
        // /schedule → list all
        // /schedule list → list all
        // /schedule cancel <id> → cancel a task
        // /schedule pause <id> → pause a task
        // /schedule resume <id> → resume a task
        // /schedule in <time> <task> → deferred task
        // /schedule every <schedule> : <task> → recurring task
        // /schedule cron <expression> : <task> → raw cron task

        String args = rawCommand.length() > 9 ? rawCommand.substring(9).strip() : "";
        String argsLower = args.toLowerCase();

        if (args.isEmpty() || argsLower.equals("list")) {
            return scheduledTaskService.formatTasksSummary(userId);
        }

        if (argsLower.startsWith("cancel ")) {
            return handleScheduleCancel(userId, args.substring(7).strip());
        }
        if (argsLower.startsWith("pause ")) {
            return handleSchedulePause(userId, args.substring(6).strip());
        }
        if (argsLower.startsWith("resume ")) {
            return handleScheduleResume(userId, args.substring(7).strip());
        }

        if (argsLower.startsWith("in ")) {
            return handleScheduleDeferred(userId, args.substring(3).strip());
        }

        if (argsLower.startsWith("every ") || argsLower.startsWith("cron ")) {
            return handleScheduleRecurring(userId, args);
        }

        return """
                ### Schedule Commands
                - `/schedule` — List scheduled tasks
                - `/schedule in <time> <task>` — Run task after delay
                  - Example: `/schedule in 2 hours backup my notes`
                  - Example: `/schedule in 30 minutes check server status`
                - `/schedule every <schedule> : <task>` — Recurring task
                  - Example: `/schedule every day at 3am : backup my notes`
                  - Example: `/schedule every monday at 9:00 : send weekly report`
                - `/schedule cron <expr> : <task>` — Raw cron expression
                  - Example: `/schedule cron 0 0 3 * * * : nightly backup`
                - `/schedule cancel <id>` — Cancel a scheduled task
                - `/schedule pause <id>` — Pause a scheduled task
                - `/schedule resume <id>` — Resume a paused task""";
    }

    private String handleScheduleDeferred(String userId, String args) {
        // "<time> <task>": the time is the longest run of leading words, up to six, that is a
        // whole time expression -- "2 hours", where "2" alone is two o'clock -- and the task is
        // all that follows it, as typed.
        Instant runAt = null;
        String taskDesc = null;
        Matcher word = Pattern.compile("\\S+").matcher(args);
        for (int i = 0; i < 6 && word.find(); i++) {
            String rest = args.substring(word.end()).strip();
            var parsed = scheduledTaskService.parseTimeExpression(args.substring(0, word.end()));
            if (parsed.isPresent() && !rest.isEmpty()) {
                runAt = parsed.get();
                taskDesc = rest;
            }
        }

        if (runAt == null) {
            return "Could not parse time expression. Examples:\n"
                    + "  `/schedule in 2 hours check server status`\n"
                    + "  `/schedule in 30 minutes remind me to call John`\n"
                    + "  `/schedule in 1 day run backup`";
        }

        try {
            long id = scheduledTaskService.scheduleDeferred(userId, taskDesc, runAt);
            var fmt = java.time.format.DateTimeFormatter.ofPattern("MMM d, HH:mm")
                    .withZone(java.time.ZoneId.systemDefault());
            return "✅ Task **#" + id + "** scheduled for **" + fmt.format(runAt)
                    + "**: " + taskDesc;
        } catch (IllegalStateException e) {
            return "❌ " + e.getMessage();
        }
    }

    private String handleScheduleRecurring(String userId, String args) {
        // Parse: "every <schedule> : <task>" or "cron <expr> : <task>"
        int colonIdx = args.indexOf(':');
        if (colonIdx < 0) {
            return "Use `:` to separate the schedule from the task.\n"
                    + "Example: `/schedule every day at 3am : backup my notes`";
        }

        String schedulePart = args.substring(0, colonIdx).strip();
        String taskDesc = args.substring(colonIdx + 1).strip();

        if (taskDesc.isBlank()) {
            return "Task description is required after the `:`";
        }

        String cronExpr;
        if (schedulePart.toLowerCase().startsWith("cron ")) {
            cronExpr = schedulePart.substring(5).strip();
        } else {
            var parsed = scheduledTaskService.parseScheduleExpression(schedulePart);
            if (parsed.isEmpty()) {
                return "Could not parse schedule: \"" + schedulePart + "\"\n"
                        + "Examples: `every day at 3am`, `every monday at 9:00`, `every 30 minutes`";
            }
            cronExpr = parsed.get();
        }

        try {
            long id = scheduledTaskService.scheduleRecurring(userId, taskDesc, cronExpr, null);
            return "✅ Recurring task **#" + id + "** created [" + cronExpr + "]: " + taskDesc;
        } catch (IllegalArgumentException e) {
            return "❌ Invalid cron expression: " + e.getMessage();
        } catch (IllegalStateException e) {
            return "❌ " + e.getMessage();
        }
    }

    private String handleScheduleCancel(String userId, String idStr) {
        long id = parseTaskId(idStr);
        if (id < 0) return "Usage: `/schedule cancel <id>` — use `/schedule list` to see task IDs.";
        boolean ok = scheduledTaskService.cancel(userId, id);
        return ok ? "✅ Task #" + id + " cancelled."
                  : "❌ Task #" + id + " not found or already completed.";
    }

    private String handleSchedulePause(String userId, String idStr) {
        long id = parseTaskId(idStr);
        if (id < 0) return "Usage: `/schedule pause <id>`";
        boolean ok = scheduledTaskService.pause(userId, id);
        return ok ? "⏸️ Task #" + id + " paused."
                  : "❌ Task #" + id + " not found or not active.";
    }

    private String handleScheduleResume(String userId, String idStr) {
        long id = parseTaskId(idStr);
        if (id < 0) return "Usage: `/schedule resume <id>`";
        boolean ok = scheduledTaskService.resume(userId, id);
        return ok ? "▶️ Task #" + id + " resumed."
                  : "❌ Task #" + id + " not found or not paused.";
    }

    private long parseTaskId(String str) {
        try {
            String cleaned = str.replace("#", "").strip();
            return Long.parseLong(cleaned);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ── Grants ──

    private String handleGrant(String userId, String args) {
        String[] parts = args.split("\\s+", 2);
        if (parts.length < 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
            return "Usage: `/grant <tool_name> <credential_key>`";
        }
        String toolName = parts[0];
        String credential = parts[1].toUpperCase();
        if (toolRegistry.find(toolName).isEmpty()) return "Tool not found: " + toolName;
        credentialGrants.grantPermanent(userId, toolName, List.of(credential));
        return "\u2705 Permanent credential access granted for '" + toolName + "': " + credential;
    }

    private String handleRevoke(String userId, String toolName) {
        if (toolName.isEmpty()) return "Usage: `/revoke <tool_name>`";
        credentialGrants.resetGrants(userId, toolName);
        return "\u274c Credential grants revoked for '" + toolName + "'";
    }
}
