package com.ownclaw.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * First-run setup wizard. Detects environment configuration, stores user-provided
 * settings in the system_settings table, and applies overrides to OwnClawConfig.
 *
 * Wizard flow (via WebSocket chat):
 *   1. Run diagnostics (Ollama, Python, OpenAI key, skills dir)
 *   2. Report findings, ask user for missing config
 *   3. Store answers in system_settings
 *   4. Mark setup complete
 */
@Service
public class SetupWizardService {

    private static final Logger log = LoggerFactory.getLogger(SetupWizardService.class);
    private static final String KEY_SETUP_COMPLETE = "setup_complete";

    private final JdbcTemplate jdbc;
    private final OwnClawConfig config;
    private final OkHttpClient http;
    private final ObjectMapper mapper;

    /** Cached diagnostic result, refreshed on demand. */
    private volatile DiagnosticResult lastDiagnostic;

    /** Models discovered during wizard Ollama URL step. */
    private volatile List<String> lastDiscoveredModels = List.of();

    public SetupWizardService(JdbcTemplate jdbc, OwnClawConfig config, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.config = config;
        this.mapper = mapper;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build();
    }

    @PostConstruct
    void init() {
        applyOverrides();
        lastDiagnostic = runDiagnostics();
        if (isSetupNeeded()) {
            log.info("First-run setup wizard pending — connect via WebUI to configure.");
        } else {
            log.info("Setup complete. Diagnostics: {}", lastDiagnostic);
        }
    }

    // ── Settings persistence ────────────────────────────────────────────

    public boolean isSetupNeeded() {
        return getSetting(KEY_SETUP_COMPLETE).isEmpty();
    }

    public void markComplete() {
        saveSetting(KEY_SETUP_COMPLETE, "true");
    }

    public Optional<String> getSetting(String key) {
        List<String> rows = jdbc.queryForList(
                "SELECT value FROM system_settings WHERE key = ?", String.class, key);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    public void saveSetting(String key, String value) {
        jdbc.update("""
                INSERT INTO system_settings (key, value) VALUES (?, ?)
                ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at = datetime('now')
                """, key, value);
    }

    // ── Environment Diagnostics ─────────────────────────────────────────

    public DiagnosticResult runDiagnostics() {
        boolean ollamaOk = checkOllama();
        String pythonPath = detectPython();
        String pythonVersion = pythonPath != null ? getPythonVersion(pythonPath) : null;
        boolean openAiKeySet = config.getMentor().getApiKey() != null
                && !config.getMentor().getApiKey().isBlank();

        var result = new DiagnosticResult(ollamaOk, config.getExecutor().getUrl(),
                pythonPath, pythonVersion, openAiKeySet);
        this.lastDiagnostic = result;
        return result;
    }

    public DiagnosticResult getLastDiagnostic() {
        return lastDiagnostic;
    }

    private boolean checkOllama() {
        String base = normalizeUrl(config.getExecutor().getUrl());
        try {
            Request req = new Request.Builder()
                    .url(base + "/api/tags")
                    .get().build();
            try (Response resp = http.newCall(req).execute()) {
                return resp.isSuccessful();
            }
        } catch (Exception e) {
            return false;
        }
    }

    /** List models available on the configured Ollama instance. */
    private List<String> listOllamaModels() {
        String base = normalizeUrl(config.getExecutor().getUrl());
        try {
            Request req = new Request.Builder()
                    .url(base + "/api/tags")
                    .get().build();
            try (Response resp = http.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) return List.of();
                JsonNode root = mapper.readTree(resp.body().string());
                JsonNode models = root.path("models");
                if (!models.isArray()) return List.of();
                var result = new ArrayList<String>();
                for (JsonNode m : models) {
                    String name = m.path("name").asText("");
                    if (!name.isBlank()) result.add(name);
                }
                return result;
            }
        } catch (Exception e) {
            log.debug("Failed to list Ollama models: {}", e.getMessage());
            return List.of();
        }
    }

    /** Strip trailing slashes and /v1 suffix — Ollama native API has no /v1 prefix. */
    private static String normalizeUrl(String url) {
        if (url == null) return "";
        url = url.strip();
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        if (url.endsWith("/v1")) url = url.substring(0, url.length() - 3);
        return url;
    }

    private String detectPython() {
        // Try configured path first, then common candidates
        String configured = config.getSandbox().getPythonPath();
        List<String> candidates = new ArrayList<>();
        if (configured != null && !configured.isBlank()) candidates.add(configured);
        candidates.addAll(List.of("python3", "python", "py"));

        for (String cmd : candidates) {
            try {
                Process p = new ProcessBuilder(cmd, "--version")
                        .redirectErrorStream(true).start();
                boolean finished = p.waitFor(5, TimeUnit.SECONDS);
                if (finished && p.exitValue() == 0) return cmd;
            } catch (Exception ignored) {}
        }
        return null;
    }

    private String getPythonVersion(String pythonCmd) {
        try {
            Process p = new ProcessBuilder(pythonCmd, "--version")
                    .redirectErrorStream(true).start();
            if (p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0) {
                try (var reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    return reader.readLine();
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    // ── Config overrides (applied at startup + after wizard completes) ──

    public void applyOverrides() {
        getSetting("openai_api_key").ifPresent(key -> config.getMentor().setApiKey(key));
        getSetting("ollama_url").ifPresent(url -> config.getExecutor().setUrl(url));
        getSetting("ollama_model").ifPresent(model -> config.getExecutor().setModel(model));
        getSetting("python_path").ifPresent(path -> config.getSandbox().setPythonPath(path));
        getSetting("mentor_model").ifPresent(model -> config.getMentor().setModel(model));
        getSetting("telegram_bot_token").ifPresent(token -> config.getTelegram().setBotToken(token));
        getSetting("telegram_enabled").ifPresent(v -> config.getTelegram().setEnabled(Boolean.parseBoolean(v)));
    }

    // ── Wizard state machine (used by ChatWebSocketHandler) ─────────────

    /**
     * Process a wizard step. Returns the next message to show, or null if wizard is done.
     * The wizard is stateless per message — it uses the step counter stored in session attributes.
     */
    public WizardResponse processStep(int step, String userInput) {
        return switch (step) {
            case 0 -> buildWelcome();
            case 1 -> processOpenAiKey(userInput);
            case 2 -> processOllamaUrl(userInput);
            case 3 -> processOllamaModel(userInput);
            case 4 -> processPythonPath(userInput);
            case 5 -> processTelegram(userInput);
            case 6 -> finalizeSetup();
            default -> new WizardResponse(null, true);
        };
    }

    private static boolean isSkip(String input) {
        return input == null || input.isBlank() || "-".equals(input.strip());
    }

    private WizardResponse buildWelcome() {
        var d = lastDiagnostic != null ? lastDiagnostic : runDiagnostics();
        var sb = new StringBuilder();
        sb.append("Welcome to OwnClaw! Let's set up your environment.\n");
        sb.append("(Type - to skip a step and keep the current value)\n\n");
        sb.append("Current diagnostics:\n");
        sb.append("  ").append(d.openAiKeySet ? "✅" : "❌").append(" OpenAI API key: ")
                .append(d.openAiKeySet ? "configured" : "not set").append('\n');
        sb.append("  ").append(d.ollamaReachable ? "✅" : "❌").append(" Ollama (")
                .append(d.ollamaUrl).append("): ")
                .append(d.ollamaReachable ? "reachable" : "not reachable")
                .append(" | model: ").append(config.getExecutor().getModel()).append('\n');
        sb.append("  ").append(d.pythonPath != null ? "✅" : "❌").append(" Python: ")
                .append(d.pythonPath != null ? d.pythonVersion + " (" + d.pythonPath + ")" : "not found")
                .append('\n');
        boolean tgEnabled = config.getTelegram().isEnabled()
                && config.getTelegram().getBotToken() != null
                && !config.getTelegram().getBotToken().isBlank();
        sb.append("  ").append(tgEnabled ? "✅" : "⬜").append(" Telegram: ")
                .append(tgEnabled ? "enabled" : "disabled").append('\n');
        sb.append("\nStep 1/5: Enter your OpenAI API key");
        if (d.openAiKeySet) {
            sb.append(" (type - to keep current)");
        }
        sb.append(":");
        return new WizardResponse(sb.toString(), false);
    }

    private WizardResponse processOpenAiKey(String input) {
        if (!isSkip(input)) {
            saveSetting("openai_api_key", input.strip());
            config.getMentor().setApiKey(input.strip());
        }
        var sb = new StringBuilder();
        if (!isSkip(input)) {
            sb.append("✅ OpenAI API key saved.\n\n");
        }
        sb.append("Step 2/5: Ollama URL\n");
        sb.append("  Format: http://host:port  (e.g. http://localhost:11434)\n");
        sb.append("  No trailing slash, no /v1 suffix\n");
        sb.append("  Current: ").append(config.getExecutor().getUrl()).append('\n');
        sb.append("Enter new URL, or type - to keep current:");
        return new WizardResponse(sb.toString(), false);
    }

    private WizardResponse processOllamaUrl(String input) {
        if (!isSkip(input)) {
            String url = normalizeUrl(input);
            saveSetting("ollama_url", url);
            config.getExecutor().setUrl(url);
        }
        // Re-check Ollama with possibly new URL and list models
        boolean reachable = checkOllama();
        lastDiscoveredModels = reachable ? listOllamaModels() : List.of();
        var sb = new StringBuilder();
        sb.append(reachable ? "✅" : "⚠️").append(" Ollama at ")
                .append(config.getExecutor().getUrl()).append(": ")
                .append(reachable ? "reachable" : "not reachable (you can configure later with /setup)")
                .append("\n\n");

        sb.append("Step 3/5: Ollama model\n");
        sb.append("  Current: ").append(config.getExecutor().getModel()).append('\n');
        if (!lastDiscoveredModels.isEmpty()) {
            sb.append("  Available models:\n");
            for (int i = 0; i < lastDiscoveredModels.size(); i++) {
                sb.append("    ").append(i + 1).append(") ").append(lastDiscoveredModels.get(i)).append('\n');
            }
            sb.append("Enter model name or number, or type - to keep current:");
        } else if (reachable) {
            sb.append("  (no models found — pull one with: ollama pull <model>)\n");
            sb.append("Enter model name, or type - to keep current:");
        } else {
            sb.append("  (cannot list models — Ollama not reachable)\n");
            sb.append("Enter model name, or type - to keep current:");
        }
        return new WizardResponse(sb.toString(), false);
    }

    private WizardResponse processOllamaModel(String input) {
        if (!isSkip(input)) {
            String model = input.strip();
            // Allow selecting by number
            try {
                int idx = Integer.parseInt(model);
                if (idx >= 1 && idx <= lastDiscoveredModels.size()) {
                    model = lastDiscoveredModels.get(idx - 1);
                }
            } catch (NumberFormatException ignored) {}
            saveSetting("ollama_model", model);
            config.getExecutor().setModel(model);
        }

        var sb = new StringBuilder();
        sb.append("Ollama model: ").append(config.getExecutor().getModel()).append("\n\n");

        String detected = detectPython();
        sb.append("Step 4/5: Python path");
        if (detected != null) {
            sb.append(" (detected: ").append(detected).append(")\n");
            sb.append("Enter custom path, or type - to keep:");
        } else {
            sb.append(" (not found!)\nEnter the path to your Python 3 executable:");
        }
        return new WizardResponse(sb.toString(), false);
    }

    private WizardResponse processPythonPath(String input) {
        if (!isSkip(input)) {
            saveSetting("python_path", input.strip());
            config.getSandbox().setPythonPath(input.strip());
        }

        var sb = new StringBuilder();
        sb.append("Step 5/5: Telegram Bot (optional)\n\n");
        sb.append("To connect OwnClaw to Telegram:\n");
        sb.append("  1. Open Telegram and search for @BotFather\n");
        sb.append("  2. Send /newbot and follow the prompts to create a bot\n");
        sb.append("  3. BotFather will give you an API token like: 123456789:ABCdefGhIJKlmNoPQRsTUVwxyZ\n");
        sb.append("  4. Paste that token below\n\n");
        String current = config.getTelegram().getBotToken();
        boolean hasToken = current != null && !current.isBlank();
        if (hasToken) {
            sb.append("  Current token: ").append(current.substring(0, Math.min(10, current.length())))
                    .append("...\n");
        }
        sb.append("Enter your Telegram bot token, or type - to ")
                .append(hasToken ? "keep current" : "skip (Telegram disabled)").append(':');
        return new WizardResponse(sb.toString(), false);
    }

    private WizardResponse processTelegram(String input) {
        if (!isSkip(input)) {
            String token = input.strip();
            // Validate token via Telegram API
            String botName = checkTelegramToken(token);
            if (botName != null) {
                saveSetting("telegram_bot_token", token);
                saveSetting("telegram_enabled", "true");
                config.getTelegram().setBotToken(token);
                config.getTelegram().setEnabled(true);
                return finalizeSetup("\u2705 Telegram bot connected: @" + botName + "\n\n");
            } else {
                // Invalid token — save anyway but warn
                saveSetting("telegram_bot_token", token);
                saveSetting("telegram_enabled", "true");
                config.getTelegram().setBotToken(token);
                config.getTelegram().setEnabled(true);
                return finalizeSetup("\u26a0\ufe0f Token saved but could not verify via Telegram API. Check the token if bot doesn't respond.\n\n");
            }
        }
        // Skipped — disable if no token
        if (config.getTelegram().getBotToken() == null || config.getTelegram().getBotToken().isBlank()) {
            saveSetting("telegram_enabled", "false");
            config.getTelegram().setEnabled(false);
        }
        return finalizeSetup("");
    }

    /** Validate a Telegram bot token via getMe. Returns bot username or null on failure. */
    private String checkTelegramToken(String token) {
        try {
            Request req = new Request.Builder()
                    .url("https://api.telegram.org/bot" + token + "/getMe")
                    .get().build();
            try (Response resp = http.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) return null;
                JsonNode root = mapper.readTree(resp.body().string());
                if (!root.path("ok").asBoolean(false)) return null;
                return root.path("result").path("username").asText(null);
            }
        } catch (Exception e) {
            return null;
        }
    }

    private WizardResponse finalizeSetup() {
        return finalizeSetup("");
    }

    private WizardResponse finalizeSetup(String prefix) {
        markComplete();
        var d = runDiagnostics();
        var sb = new StringBuilder();
        sb.append(prefix);
        sb.append("Setup complete! \u2705\n\n");
        sb.append("Final configuration:\n");
        sb.append("  OpenAI key: ").append(d.openAiKeySet ? "configured" : "not set").append('\n');
        sb.append("  Ollama: ").append(d.ollamaReachable ? "reachable" : "not reachable")
                .append(" | model: ").append(config.getExecutor().getModel()).append('\n');
        sb.append("  Python: ").append(d.pythonPath != null ? d.pythonVersion : "not found").append('\n');
        boolean tgEnabled = config.getTelegram().isEnabled()
                && config.getTelegram().getBotToken() != null
                && !config.getTelegram().getBotToken().isBlank();
        sb.append("  Telegram: ").append(tgEnabled ? "enabled" : "disabled").append('\n');
        sb.append("\nYou can now start chatting. Type /help for available commands.");
        sb.append("\nTo re-run setup, type /setup");
        return new WizardResponse(sb.toString(), true);
    }

    // ── Value types ─────────────────────────────────────────────────────

    public record DiagnosticResult(
            boolean ollamaReachable,
            String ollamaUrl,
            String pythonPath,
            String pythonVersion,
            boolean openAiKeySet
    ) {}

    public record WizardResponse(String message, boolean complete) {}
}
