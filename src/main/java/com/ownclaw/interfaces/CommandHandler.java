package com.ownclaw.interfaces;

import com.ownclaw.agent.tools.Tool;
import com.ownclaw.agent.tools.ToolRegistry;
import com.ownclaw.conversation.ConversationService;
import com.ownclaw.core.ScheduledTaskService;
import com.ownclaw.core.TaskQueue;
import com.ownclaw.core.TokenBudgetTracker;
import com.ownclaw.observability.EventLogService;
import com.ownclaw.users.CredentialGrantService;
import com.ownclaw.users.CredentialVault;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Shared command handler for slash commands.
 * Used by both the Web UI (ChatWebSocketHandler) and Telegram bot (TelegramBotService)
 * to ensure consistent behavior across interfaces.
 * <p>
 * Returns the command response text, or {@link Optional#empty()} if the input
 * is not a recognized command and should be routed to the agent.
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

    public CommandHandler(ToolRegistry toolRegistry, ConversationService conversationService,
                          EventLogService eventLog, TokenBudgetTracker budgetTracker,
                          CredentialVault credentialVault,
                          CredentialGrantService credentialGrants, TaskQueue taskQueue,
                          ScheduledTaskService scheduledTaskService) {
        this.toolRegistry = toolRegistry;
        this.conversationService = conversationService;
        this.eventLog = eventLog;
        this.budgetTracker = budgetTracker;
        this.credentialVault = credentialVault;
        this.credentialGrants = credentialGrants;
        this.taskQueue = taskQueue;
        this.scheduledTaskService = scheduledTaskService;
    }

    /**
     * Try to handle a slash command. Returns the response if it's a known command,
     * or empty if the message should be routed to the agent instead.
     *
     * @param userId  the user ID
     * @param message the raw message text
     * @return response text, or empty if not a command
     */
    public Optional<String> handle(String userId, String message) {
        if (message == null || !message.startsWith("/")) {
            return Optional.empty();
        }

        String command = message.trim().toLowerCase();

        return switch (command) {
            case "/help" -> Optional.of(helpText());
            case "/skills" -> Optional.of(skillsText());
            case "/status" -> Optional.of(statusText());
            case "/tokens" -> {
                String budget = budgetTracker.getUsageSummary(userId);
                Map<String, Object> detail = eventLog.tokenUsageDetailToday(userId);
                long cloud = ((Number) detail.get("cloud_tokens")).longValue();
                long local = ((Number) detail.get("local_tokens")).longValue();
                yield Optional.of(String.format("%s\nCloud: %,d  |  Local: %,d", budget, cloud, local));
            }
            case "/history" -> Optional.of(handleHistory(userId));
            default -> {
                if (command.equals("/new") || command.startsWith("/new ")) {
                    yield Optional.of(handleNew(userId, message.trim()));
                }
                if (command.startsWith("/switch ")) {
                    yield Optional.of(handleSwitch(userId, message.trim().substring(8).strip()));
                }
                if (command.startsWith("/log")) {
                    yield Optional.of(handleLog(userId, command));
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
                if (command.startsWith("/schedule")) {
                    yield Optional.of(handleSchedule(userId, message.trim()));
                }
                // Not a recognized shared command — caller may handle interface-specific
                // commands (like /debug, /setup) or treat as unknown.
                yield Optional.empty();
            }
        };
    }

    // ── Help ──

    private String helpText() {
        return """
                ### Commands
                - `/new [title]` — Start a new chat session
                - `/history` — List recent chat sessions
                - `/switch <N>` — Switch to session N from history
                - `/log` — Last 10 events
                - `/log errors` — Recent errors
                - `/log tokens` — Token usage today
                - `/tokens` — Token budget summary
                - `/skills` — List available tools
                - `/debug` — Toggle debug mode
                - `/cancel` — Cancel the running task
                - `/grant <tool> <credential>` — Grant credential access to a tool
                - `/revoke <tool>` — Revoke credential access
                - `/cred set <KEY> <VALUE>` — Store a credential
                - `/cred list` — List stored credential keys
                - `/cred delete <KEY>` — Delete a credential
                - `/schedule` — List scheduled/deferred tasks
                - `/schedule in <time> <task>` — Run a task after a delay
                - `/schedule every <schedule> : <task>` — Recurring task
                - `/schedule cancel|pause|resume <id>` — Manage tasks
                - `/setup` — Run setup wizard (Web UI only)
                - `/status` — System status
                - `/help` — This message""";
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
            if (n > 15) break; // cap at 15
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
            return "Usage: /switch <number> — use /history to see session numbers.";
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

    private String handleLog(String userId, String command) {
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
                Map<String, Object> detail = eventLog.tokenUsageDetailToday(userId);
                String budget = budgetTracker.getUsageSummary(userId);
                long cloud = ((Number) detail.get("cloud_tokens")).longValue();
                long local = ((Number) detail.get("local_tokens")).longValue();
                long total = ((Number) detail.get("total_tokens")).longValue();
                long tasks = ((Number) detail.get("task_count")).longValue();
                yield String.format("Token usage today:\n  Cloud: %,d  |  Local: %,d  |  Total: %,d\n  Tasks: %d\n  Budget: %s",
                        cloud, local, total, tasks, budget);
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

    // ── Credentials ──

    private String handleCred(String userId, String args) {
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
            String value = rest.substring(space + 1).strip();
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
        // Parse: "<time expression> <task description>"
        // Try to find where the time expression ends and the task begins.
        // Strategy: try progressively longer prefixes as time expressions.
        String[] words = args.split("\\s+");
        String timeExpr = null;
        String taskDesc = null;

        for (int i = 1; i <= Math.min(words.length - 1, 6); i++) {
            String candidate = String.join(" ", java.util.Arrays.copyOfRange(words, 0, i));
            var parsed = scheduledTaskService.parseTimeExpression(candidate);
            if (parsed.isPresent()) {
                timeExpr = candidate;
                taskDesc = String.join(" ", java.util.Arrays.copyOfRange(words, i, words.length));
            }
        }

        if (timeExpr == null || taskDesc == null || taskDesc.isBlank()) {
            return "Could not parse time expression. Examples:\n"
                    + "  `/schedule in 2 hours check server status`\n"
                    + "  `/schedule in 30 minutes remind me to call John`\n"
                    + "  `/schedule in 1 day run backup`";
        }

        var runAt = scheduledTaskService.parseTimeExpression(timeExpr);
        if (runAt.isEmpty()) {
            return "Could not parse time: \"" + timeExpr + "\"";
        }

        try {
            long id = scheduledTaskService.scheduleDeferred(userId, taskDesc, runAt.get());
            var fmt = java.time.format.DateTimeFormatter.ofPattern("MMM d, HH:mm")
                    .withZone(java.time.ZoneId.systemDefault());
            return "✅ Task **#" + id + "** scheduled for **" + fmt.format(runAt.get())
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
            return "Usage: /grant <tool_name> <credential_key>";
        }
        String toolName = parts[0];
        String credential = parts[1].toUpperCase();
        if (toolRegistry.find(toolName).isEmpty()) return "Tool not found: " + toolName;
        credentialGrants.grantPermanent(userId, toolName, List.of(credential));
        return "\u2705 Permanent credential access granted for '" + toolName + "': " + credential;
    }

    private String handleRevoke(String userId, String toolName) {
        if (toolName.isEmpty()) return "Usage: /revoke <tool_name>";
        credentialGrants.resetGrants(userId, toolName);
        return "\u274c Credential grants revoked for '" + toolName + "'";
    }
}
