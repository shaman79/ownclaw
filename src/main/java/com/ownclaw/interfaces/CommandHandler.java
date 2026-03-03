package com.ownclaw.interfaces;

import com.ownclaw.agent.tools.Tool;
import com.ownclaw.agent.tools.ToolRegistry;
import com.ownclaw.core.TaskQueue;
import com.ownclaw.core.TokenBudgetTracker;
import com.ownclaw.observability.EventLogService;
import com.ownclaw.users.CredentialGrantService;
import com.ownclaw.users.CredentialVault;
import org.springframework.stereotype.Service;

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
    private final EventLogService eventLog;
    private final TokenBudgetTracker budgetTracker;
    private final CredentialVault credentialVault;
    private final CredentialGrantService credentialGrants;
    private final TaskQueue taskQueue;

    public CommandHandler(ToolRegistry toolRegistry, EventLogService eventLog,
                          TokenBudgetTracker budgetTracker, CredentialVault credentialVault,
                          CredentialGrantService credentialGrants, TaskQueue taskQueue) {
        this.toolRegistry = toolRegistry;
        this.eventLog = eventLog;
        this.budgetTracker = budgetTracker;
        this.credentialVault = credentialVault;
        this.credentialGrants = credentialGrants;
        this.taskQueue = taskQueue;
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
            case "/tokens" -> Optional.of(budgetTracker.getUsageSummary(userId));
            default -> {
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
                - `/log` — Last 10 events
                - `/log errors` — Recent errors
                - `/log tokens` — Token usage today
                - `/tokens` — Token budget summary
                - `/skills` — List available tools
                - `/debug` — Toggle debug mode (Web UI only)
                - `/grant <tool> <credential>` — Grant credential access to a tool
                - `/revoke <tool>` — Revoke credential access
                - `/cred set <KEY> <VALUE>` — Store a credential
                - `/cred list` — List stored credential keys
                - `/cred delete <KEY>` — Delete a credential
                - `/setup` — Run setup wizard (Web UI only)
                - `/status` — System status
                - `/help` — This message""";
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
