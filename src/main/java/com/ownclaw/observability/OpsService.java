package com.ownclaw.observability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownclaw.agent.tools.DynamicSkill;
import com.ownclaw.agent.tools.DynamicSkillRegistry;
import com.ownclaw.agent.tools.ToolRegistry;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.core.TaskQueue;
import com.ownclaw.users.AuthService;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Read-only introspection for the ops API, plus a self-test suite.
 * <p>
 * Two rules hold everywhere in this class:
 * <ol>
 *   <li><b>No secret ever leaves.</b> Values are redacted by key name, SQL results are
 *       redacted by column name, and the raw SQL is additionally screened for the columns
 *       that hold secrets. Callers get "present"/"absent" and lengths, never values.</li>
 *   <li><b>Nothing mutates</b> unless the method name says so.</li>
 * </ol>
 */
@Service
public class OpsService {

    private static final Logger log = LoggerFactory.getLogger(OpsService.class);

    /** Config/setting keys whose values are never returned. */
    private static final Pattern SECRET_KEY = Pattern.compile(
            "(?i)(token|secret|password|passwd|api[-_]?key|\\bkey\\b|salt|hash|credential|cookie|authorization)");

    /** Result columns whose values are never returned, whatever the query looked like. */
    private static final Set<String> SECRET_COLUMNS = Set.of(
            "password_hash", "encryption_salt", "encrypted_value", "iv",
            // system_settings.value holds the vault master key, the JWT secret and the
            // provider API keys. Its "key" column is only a name and stays readable.
            "value");

    /** Columns whose name says they hold secret material. Deliberately excludes a bare "key". */
    private static final Pattern SECRET_VALUE_COLUMN = Pattern.compile(
            "(?i)(secret|passwd|password|api[-_]?key|private[-_]?key|access[-_]?token|bearer)");

    /** Identifiers that may not appear in ops SQL at all (blocks aliasing around the above). */
    private static final Pattern SQL_FORBIDDEN = Pattern.compile(
            "(?i)\\b(password_hash|encryption_salt|encrypted_value|jwt_secret|vault_master_key"
                    + "|pragma|attach|detach|vacuum)\\b");

    private static final Pattern SQL_ALLOWED_START = Pattern.compile("(?is)^\\s*(select|with)\\b.*");

    /** system_settings holds the vault master key and the JWT secret in plaintext. */
    private static final String SETTINGS_TABLE = "system_settings";

    /** A generated skill is a single directory name — no separators, no traversal. */
    private static final Pattern SKILL_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}");

    private static final int MAX_ROWS = 500;
    private static final int MAX_LOG_LINES = 2000;
    /** Only the tail of the log file is read, so a rotated 20 MB file is never loaded whole. */
    private static final long LOG_TAIL_BYTES = 4L * 1024 * 1024;

    private final OwnClawConfig config;
    private final JdbcTemplate jdbc;
    private final ToolRegistry toolRegistry;
    private final DynamicSkillRegistry skillRegistry;
    private final TaskQueue taskQueue;
    private final AuthService authService;
    private final ObjectMapper mapper;
    private final OkHttpClient http;
    private final Instant startedAt = Instant.now();

    public OpsService(OwnClawConfig config, JdbcTemplate jdbc, ToolRegistry toolRegistry,
                      DynamicSkillRegistry skillRegistry, TaskQueue taskQueue,
                      AuthService authService, ObjectMapper mapper) {
        this.config = config;
        this.jdbc = jdbc;
        this.toolRegistry = toolRegistry;
        this.skillRegistry = skillRegistry;
        this.taskQueue = taskQueue;
        this.authService = authService;
        this.mapper = mapper;
        // A cold Ollama load of a 20+ GB model can take minutes, so the diagnostic waits
        // longer than a normal call would. A hung Ollama therefore blocks one ops request
        // for up to this long; that is acceptable for a probe and is stated in the response.
        this.http = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(240, TimeUnit.SECONDS)
                .build();
    }

    // ────────────────────────────── health ──────────────────────────────

    public Map<String, Object> ping() {
        var out = new LinkedHashMap<String, Object>();
        out.put("ok", true);
        out.put("service", "ownclaw");
        out.put("uptimeSeconds", Duration.between(startedAt, Instant.now()).toSeconds());
        out.put("startedAt", startedAt.toString());
        out.put("deployedCommit", deployedCommit());
        return out;
    }

    public Map<String, Object> health() {
        var out = new LinkedHashMap<String, Object>(ping());
        var checks = new LinkedHashMap<String, Object>();

        checks.put("database", check(() -> {
            Integer one = jdbc.queryForObject("SELECT 1", Integer.class);
            var m = new LinkedHashMap<String, Object>();
            m.put("ok", Integer.valueOf(1).equals(one));
            m.put("path", config.getDatabase().getPath());
            m.put("sizeBytes", fileSize(Path.of(config.getDatabase().getPath())));
            m.put("journalMode", jdbc.queryForObject("PRAGMA journal_mode", String.class));
            m.put("foreignKeys", jdbc.queryForObject("PRAGMA foreign_keys", Integer.class));
            return m;
        }));

        checks.put("cloudLlm", cloudSummary());
        checks.put("localLlm", ollamaSummary());

        checks.put("queue", Map.of(
                "busy", taskQueue.isBusy(),
                "queued", taskQueue.getQueueSize()));

        checks.put("tools", Map.of(
                "registered", toolRegistry.all().size(),
                "dynamicSkills", skillRegistry.allDynamic().size(),
                "builtIn", toolRegistry.all().size() - skillRegistry.allDynamic().size()));

        checks.put("jvm", jvm());
        checks.put("logFile", logFileInfo());
        checks.put("owner", authService.ownerId().map(id -> (Object) id).orElse("none"));

        out.put("checks", checks);
        return out;
    }

    private Map<String, Object> jvm() {
        Runtime rt = Runtime.getRuntime();
        var m = new LinkedHashMap<String, Object>();
        m.put("heapUsedMb", (rt.totalMemory() - rt.freeMemory()) / 1_048_576);
        m.put("heapMaxMb", rt.maxMemory() / 1_048_576);
        m.put("threads", Thread.activeCount());
        m.put("javaVersion", System.getProperty("java.version"));
        // A steadily climbing count here is the known heartbeat-executor leak.
        m.put("llmHeartbeatThreads", countThreads("llm-heartbeat"));
        return m;
    }

    private static int countThreads(String namePrefix) {
        Thread[] all = new Thread[Thread.activeCount() * 2 + 16];
        int n = Thread.enumerate(all);
        int count = 0;
        for (int i = 0; i < n; i++) {
            if (all[i] != null && all[i].getName().startsWith(namePrefix)) count++;
        }
        return count;
    }

    private Map<String, Object> cloudSummary() {
        var m = new LinkedHashMap<String, Object>();
        var mentor = config.getMentor();
        m.put("provider", mentor.getProvider());
        m.put("openaiModel", mentor.getModel());
        m.put("openaiKey", keyState(mentor.getApiKey()));
        m.put("anthropicModel", mentor.getAnthropicModel());
        m.put("anthropicKey", keyState(mentor.getAnthropicApiKey()));
        return m;
    }

    /** Never the key: just whether one is configured and how long it is. */
    private static Map<String, Object> keyState(String key) {
        boolean present = key != null && !key.isBlank();
        return Map.of("present", present, "length", present ? key.trim().length() : 0);
    }

    // ────────────────────────────── local model ──────────────────────────────

    private Map<String, Object> ollamaSummary() {
        var m = new LinkedHashMap<String, Object>();
        String url = config.getExecutor().getUrl();
        String want = config.getExecutor().getModel();
        m.put("url", url);
        m.put("configuredModel", want);
        try {
            JsonNode tags = getJson(url + "/api/tags");
            List<String> installed = new ArrayList<>();
            for (JsonNode n : tags.path("models")) installed.add(n.path("name").asText());
            m.put("reachable", true);
            m.put("installedModels", installed);
            m.put("configuredModelInstalled", installed.contains(want));
        } catch (Exception e) {
            m.put("reachable", false);
            m.put("error", String.valueOf(e.getMessage()));
        }
        return m;
    }

    /**
     * Full local-tier probe: what is installed, what is loaded, and whether the configured
     * model can actually be driven through {@code /api/chat}.
     */
    public Map<String, Object> ollama() {
        var out = new LinkedHashMap<String, Object>(ollamaSummary());
        String url = config.getExecutor().getUrl();
        String model = config.getExecutor().getModel();

        try {
            JsonNode ps = getJson(url + "/api/ps");
            var loaded = new ArrayList<Map<String, Object>>();
            for (JsonNode n : ps.path("models")) {
                loaded.add(Map.of("name", n.path("name").asText(),
                        "sizeBytes", n.path("size").asLong(),
                        "expiresAt", n.path("expires_at").asText("")));
            }
            out.put("loaded", loaded);
        } catch (Exception e) {
            out.put("loadedError", String.valueOf(e.getMessage()));
        }

        try {
            JsonNode show = postJson(url + "/api/show", Map.of("model", model));
            var caps = new ArrayList<String>();
            for (JsonNode c : show.path("capabilities")) caps.add(c.asText());
            String template = show.path("template").asText("");
            var m = new LinkedHashMap<String, Object>();
            m.put("capabilities", caps);
            m.put("supportsChat", caps.contains("chat"));
            m.put("supportsTools", caps.contains("tools"));
            m.put("templateLength", template.length());
            m.put("templateLooksUnusable", template.trim().equals("{{ .Prompt }}"));
            m.put("parameterSize", show.path("details").path("parameter_size").asText(""));
            out.put("modelInfo", m);
        } catch (Exception e) {
            out.put("modelInfoError", String.valueOf(e.getMessage()));
        }

        out.put("chatRoundTrip", chatRoleTest(url, model));
        return out;
    }

    /**
     * Does the configured model honour a system message through {@code /api/chat}?
     * <p>
     * A model whose Ollama template is a bare {@code {{ .Prompt }}} silently discards the
     * message structure: the system instruction never reaches it and {@code prompt_eval_count}
     * comes back far smaller than the prompt. Every local job in this system depends on roles
     * working, so this is checked explicitly rather than assumed.
     */
    private Map<String, Object> chatRoleTest(String url, String model) {
        var m = new LinkedHashMap<String, Object>();
        String canary = "BANANA-" + Integer.toHexString(model.hashCode()).toUpperCase(Locale.ROOT);
        try {
            long t0 = System.currentTimeMillis();
            JsonNode r = postJson(url + "/api/chat", Map.of(
                    "model", model,
                    "stream", false,
                    "messages", List.of(
                            Map.of("role", "system",
                                    "content", "Reply with exactly the word " + canary + " and nothing else."),
                            Map.of("role", "user", "content", "hello")),
                    "options", Map.of("temperature", 0, "num_predict", 32)));
            String content = r.path("message").path("content").asText("");
            int promptEval = r.path("prompt_eval_count").asInt(-1);
            boolean honoured = content.toUpperCase(Locale.ROOT).contains(canary);

            m.put("ok", honoured);
            m.put("systemMessageHonoured", honoured);
            m.put("promptEvalCount", promptEval);
            m.put("doneReason", r.path("done_reason").asText(""));
            m.put("latencyMs", System.currentTimeMillis() - t0);
            m.put("reply", content.length() > 200 ? content.substring(0, 200) + "..." : content);
            if (!honoured) {
                m.put("diagnosis", "The model did not follow a system message. If templateLooksUnusable "
                        + "is true, or promptEvalCount is far below the prompt size, this model has no chat "
                        + "template and /api/chat discards roles — every local job will misbehave. Pull a "
                        + "chat-capable model or wrap this one in a Modelfile with a proper template.");
            }
        } catch (Exception e) {
            m.put("ok", false);
            m.put("error", String.valueOf(e.getMessage()));
            if (e instanceof java.io.InterruptedIOException
                    || String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT).contains("timeout")) {
                m.put("diagnosis", "Timed out. The usual cause is a cold model load — a 20+ GB model "
                        + "can take minutes to become resident. Check GET /api/ops/ollama 'loaded', "
                        + "warm the model, then run this again. A repeat timeout on a warm model means "
                        + "the host is too slow for the local tier at this model size.");
            }
        }
        return m;
    }

    // ────────────────────────────── config ──────────────────────────────

    /** Effective configuration with every secret-looking value replaced. */
    public Map<String, Object> config() {
        var out = new LinkedHashMap<String, Object>();
        out.put("note", "Secret values are never returned. Keys matching " + SECRET_KEY.pattern()
                + " are reported as present/absent only.");
        out.put("effective", redactTree(mapper.convertValue(config, Map.class)));

        // Stored settings override the YAML at runtime, so timestamps matter for tamper checks.
        var settings = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT key, updated_at, length(value) AS value_length FROM " + SETTINGS_TABLE
                        + " ORDER BY updated_at DESC")) {
            settings.add(Map.of(
                    "key", String.valueOf(row.get("key")),
                    "updatedAt", String.valueOf(row.get("updated_at")),
                    "valueLength", row.get("value_length")));
        }
        out.put("storedSettings", settings);
        out.put("storedSettingsNote",
                "Values withheld: this table holds the vault master key, the JWT secret and the "
                        + "provider API keys in plaintext. Compare updatedAt against an incident window.");
        return out;
    }

    @SuppressWarnings("unchecked")
    private Object redactTree(Object node) {
        if (node instanceof Map<?, ?> map) {
            var out = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String key = String.valueOf(e.getKey());
                Object value = e.getValue();
                if (SECRET_KEY.matcher(key).find()) {
                    boolean present = value != null && !String.valueOf(value).isBlank();
                    out.put(key, present ? "«set, " + String.valueOf(value).length() + " chars»" : "«unset»");
                } else {
                    out.put(key, redactTree(value));
                }
            }
            return out;
        }
        if (node instanceof List<?> list) {
            var out = new ArrayList<>();
            for (Object o : list) out.add(redactTree(o));
            return out;
        }
        return node;
    }

    // ────────────────────────────── logs ──────────────────────────────

    public Map<String, Object> logs(int lines, String grep, String level) {
        int want = Math.max(1, Math.min(lines, MAX_LOG_LINES));
        var out = new LinkedHashMap<String, Object>();
        Path file = logFile();
        out.put("file", file.toString());

        if (!Files.isReadable(file)) {
            out.put("error", "Log file not readable. Set OWNCLAW_LOG_FILE, or check that "
                    + "logging.file.name points somewhere the service user can write.");
            return out;
        }

        List<String> tail;
        try {
            tail = tailLines(file, want, grep, level);
        } catch (IOException e) {
            out.put("error", "Could not read log file: " + e.getMessage());
            return out;
        }
        out.put("sizeBytes", fileSize(file));
        out.put("returned", tail.size());
        out.put("filters", Map.of("grep", grep == null ? "" : grep, "level", level == null ? "" : level));
        out.put("lines", tail);
        return out;
    }

    private List<String> tailLines(Path file, int want, String grep, String level) throws IOException {
        long size = Files.size(file);
        long from = Math.max(0, size - LOG_TAIL_BYTES);
        byte[] buf;
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            raf.seek(from);
            buf = new byte[(int) Math.min(LOG_TAIL_BYTES, size - from)];
            raf.readFully(buf);
        }
        String[] all = new String(buf, StandardCharsets.UTF_8).split("\n");
        Pattern needle = (grep == null || grep.isBlank()) ? null
                : Pattern.compile(Pattern.quote(grep), Pattern.CASE_INSENSITIVE);
        String lvl = (level == null || level.isBlank()) ? null : level.trim().toUpperCase(Locale.ROOT);

        var kept = new ArrayDeque<String>();
        // Walk backwards so filtering returns the newest matches, not the oldest.
        for (int i = all.length - 1; i >= 0 && kept.size() < want; i--) {
            String line = all[i];
            if (line.isBlank()) continue;
            if (lvl != null && !line.contains(lvl)) continue;
            if (needle != null && !needle.matcher(line).find()) continue;
            kept.addFirst(line);
        }
        // The first line of the buffer is usually a partial line; drop it unless we read from 0.
        if (from > 0 && !kept.isEmpty() && kept.size() == want) kept.pollFirst();
        return new ArrayList<>(kept);
    }

    private Path logFile() {
        String configured = System.getProperty("LOG_FILE");
        if (configured == null || configured.isBlank()) configured = System.getenv("OWNCLAW_LOG_FILE");
        if (configured == null || configured.isBlank()) configured = "./logs/ownclaw.log";
        return Path.of(configured);
    }

    private Map<String, Object> logFileInfo() {
        Path f = logFile();
        return Map.of("path", f.toString(),
                "readable", Files.isReadable(f),
                "sizeBytes", fileSize(f));
    }

    // ────────────────────────────── database ──────────────────────────────

    public Map<String, Object> tables() {
        var rows = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> t : jdbc.queryForList(
                "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name")) {
            String name = String.valueOf(t.get("name"));
            var entry = new LinkedHashMap<String, Object>();
            entry.put("table", name);
            try {
                entry.put("rows", jdbc.queryForObject("SELECT COUNT(*) FROM \"" + name + "\"", Long.class));
            } catch (Exception e) {
                entry.put("rows", -1);
                entry.put("error", String.valueOf(e.getMessage()));
            }
            if (SETTINGS_TABLE.equals(name)) entry.put("note", "values withheld by the ops API");
            rows.add(entry);
        }
        return Map.of("tables", rows);
    }

    /**
     * Run one read-only SELECT. Guards, in order: single statement; must start with SELECT or
     * WITH; must not name a secret column or a dangerous statement keyword; result capped; and
     * finally every returned column whose name holds a secret is redacted, which also catches
     * {@code SELECT * FROM users}.
     */
    public Map<String, Object> query(String sql, Integer limit) {
        var out = new LinkedHashMap<String, Object>();
        if (sql == null || sql.isBlank()) {
            return Map.of("error", "sql is required");
        }
        String trimmed = sql.trim();
        while (trimmed.endsWith(";")) trimmed = trimmed.substring(0, trimmed.length() - 1).trim();

        if (trimmed.contains(";")) {
            return Map.of("error", "Only a single statement is allowed");
        }
        if (!SQL_ALLOWED_START.matcher(trimmed).matches()) {
            return Map.of("error", "Only SELECT (or WITH ... SELECT) is allowed");
        }
        var forbidden = SQL_FORBIDDEN.matcher(trimmed);
        if (forbidden.find()) {
            return Map.of("error", "Query names a forbidden identifier: " + forbidden.group(1));
        }

        log.info("Ops SQL: {}", trimmed);
        int cap = limit == null ? MAX_ROWS : Math.max(1, Math.min(limit, MAX_ROWS));
        String effective = trimmed.toLowerCase(Locale.ROOT).contains(" limit ")
                ? trimmed
                : trimmed + " LIMIT " + cap;

        try {
            List<Map<String, Object>> rows = jdbc.queryForList(effective);
            boolean redacted = false;
            var safe = new ArrayList<Map<String, Object>>(rows.size());
            for (Map<String, Object> row : rows) {
                var clean = new LinkedHashMap<String, Object>();
                for (Map.Entry<String, Object> e : row.entrySet()) {
                    if (isSecretColumn(e.getKey())) {
                        clean.put(e.getKey(), e.getValue() == null ? null : "«redacted»");
                        redacted = true;
                    } else {
                        clean.put(e.getKey(), e.getValue());
                    }
                }
                safe.add(clean);
            }
            out.put("sql", effective);
            out.put("rowCount", safe.size());
            out.put("truncated", safe.size() >= cap);
            if (redacted) out.put("redactedColumns", true);
            out.put("rows", safe);
        } catch (Exception e) {
            out.put("sql", effective);
            out.put("error", String.valueOf(e.getMessage()));
        }
        return out;
    }

    private static boolean isSecretColumn(String column) {
        String c = column == null ? "" : column.toLowerCase(Locale.ROOT);
        return SECRET_COLUMNS.contains(c) || SECRET_VALUE_COLUMN.matcher(c).find();
    }

    // ────────────────────────────── accounts and forensics ──────────────────────────────

    public Map<String, Object> users() {
        var rows = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> u : jdbc.queryForList("""
                SELECT id, display_name, telegram_id, created_at, updated_at,
                       password_hash IS NOT NULL AS has_password
                FROM users ORDER BY created_at, rowid
                """)) {
            String id = String.valueOf(u.get("id"));
            boolean webLogin = ((Number) u.get("has_password")).intValue() != 0;
            Object telegram = u.get("telegram_id");
            var m = new LinkedHashMap<String, Object>();
            m.put("id", id);
            m.put("username", u.get("display_name"));
            m.put("owner", authService.isOwner(id));
            m.put("webLogin", webLogin);
            m.put("telegramId", telegram);
            m.put("disabled", !webLogin && telegram == null);
            m.put("createdAt", u.get("created_at"));
            rows.add(m);
        }
        return Map.of("users", rows);
    }

    /**
     * Everything this instance recorded about one account: what they asked, which tools ran,
     * what was remembered, whether they left a scheduled task behind, and what they spent.
     * Built for answering "who is this account and what did it do".
     */
    public Map<String, Object> forensics(String userId, int limit) {
        int cap = Math.max(1, Math.min(limit, MAX_ROWS));
        log.info("Ops forensics requested for user={}", userId);
        var out = new LinkedHashMap<String, Object>();
        out.put("userId", userId);

        List<Map<String, Object>> who = jdbc.queryForList("""
                SELECT id, display_name, telegram_id, created_at, updated_at,
                       password_hash IS NOT NULL AS has_password
                FROM users WHERE id = ?
                """, userId);
        if (who.isEmpty()) {
            return Map.of("error", "No such user id: " + userId,
                    "hint", "GET /api/ops/users lists ids");
        }
        out.put("account", who.getFirst());
        out.put("isOwner", authService.isOwner(userId));

        out.put("conversations", jdbc.queryForList(
                "SELECT timestamp, session_id, role, substr(content,1,600) AS content, tokens_used "
                        + "FROM conversations WHERE user_id = ? ORDER BY timestamp LIMIT " + cap, userId));
        out.put("sessions", jdbc.queryForList(
                "SELECT id, title, substr(preview,1,200) AS preview, created_at, updated_at, archived "
                        + "FROM chat_sessions WHERE user_id = ? ORDER BY created_at LIMIT " + cap, userId));
        out.put("events", jdbc.queryForList(
                "SELECT timestamp, event_type, severity, task_id, summary, details, tokens_used "
                        + "FROM events WHERE user_id = ? ORDER BY timestamp DESC LIMIT " + cap, userId));
        out.put("toolCalls", jdbc.queryForList(
                "SELECT created_at, tool_name, task_id, success, duration_ms "
                        + "FROM skill_usage WHERE user_id = ? ORDER BY created_at DESC LIMIT " + cap, userId));
        out.put("memory", jdbc.queryForList(
                "SELECT created_at, memory_type, outcome, tags, substr(content,1,400) AS content "
                        + "FROM agent_memory WHERE user_id = ? ORDER BY created_at DESC LIMIT " + cap, userId));
        out.put("scheduledTasks", jdbc.queryForList(
                "SELECT id, task_type, status, description, cron_expression, next_run_at, last_run_at, "
                        + "run_count, substr(last_result,1,300) AS last_result "
                        + "FROM scheduled_tasks WHERE user_id = ? ORDER BY created_at DESC LIMIT " + cap, userId));
        out.put("scheduledRuns", jdbc.queryForList(
                "SELECT task_id, executed_at, status, skills_used, duration_ms, "
                        + "substr(result,1,300) AS result, substr(error,1,300) AS error "
                        + "FROM scheduled_task_runs WHERE user_id = ? ORDER BY executed_at DESC LIMIT " + cap,
                userId));
        out.put("attachments", jdbc.queryForList(
                "SELECT id, original_name, content_type, size_bytes, uploaded_at "
                        + "FROM file_attachments WHERE user_id = ? ORDER BY uploaded_at DESC LIMIT " + cap, userId));
        out.put("longRunningTasks", jdbc.queryForList(
                "SELECT task_id, description, skill_name, status, started_at, completed_at, "
                        + "substr(result_summary,1,300) AS result_summary "
                        + "FROM long_running_tasks WHERE user_id = ? ORDER BY started_at DESC LIMIT " + cap,
                userId));
        out.put("tokenUsage", jdbc.queryForList(
                "SELECT date, provider, tokens_used, requests, cost_usd "
                        + "FROM token_usage WHERE user_id = ? ORDER BY date DESC LIMIT " + cap, userId));
        // Keys only — values stay in the vault.
        out.put("credentialKeys", jdbc.queryForList(
                "SELECT credential_key, created_at, updated_at FROM credential_vault WHERE user_id = ?",
                userId));
        out.put("credentialGrants", jdbc.queryForList(
                "SELECT skill_name, credential, grant_type, granted_at FROM credential_grants WHERE user_id = ?",
                userId));
        return out;
    }

    /**
     * The task id of the most recent task recorded for a user.
     * <p>
     * {@code AgentResult} carries no task id, so after an ops-triggered run this is how the
     * run is correlated with {@code events}, {@code skill_usage} and the
     * {@code Task <id> step N} log lines. (The old debug API invented a random id instead,
     * which matched nothing.)
     */
    public String latestTaskId(String userId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT task_id FROM events WHERE user_id = ? AND task_id IS NOT NULL AND task_id <> '' "
                        + "ORDER BY timestamp DESC, id DESC LIMIT 1", userId);
        return rows.isEmpty() ? null : String.valueOf(rows.getFirst().get("task_id"));
    }

    // ────────────────────────────── skills ──────────────────────────────

    /**
     * Every generated skill with its file metadata and a content hash, so an unexpected
     * modification is visible. Pass {@code name} to get the source of one skill.
     */
    public Map<String, Object> skills(String name) {
        Path root = Path.of(config.getSkills().getGeneratedPath());
        var out = new LinkedHashMap<String, Object>();
        out.put("generatedPath", root.toString());
        out.put("globalDirectoryWarning",
                "Generated skills are not per-user: every account sees and can overwrite every skill, "
                        + "and a skill runs with the requesting user's vault credentials.");

        if (name != null && !name.isBlank()) {
            // A skill name is one directory name, never a path. Rejecting separators outright
            // is stronger than trying to sanitise them.
            if (!SKILL_NAME.matcher(name).matches()) {
                return Map.of("error", "Invalid skill name: expected " + SKILL_NAME.pattern());
            }
            Path dir = root.resolve(name).normalize();
            if (!dir.startsWith(root.normalize())) {
                return Map.of("error", "Invalid skill name");
            }
            // normalize() is lexical, so it would not notice a symlink planted inside the
            // directory. Resolve it for real before reading anything.
            try {
                if (Files.exists(dir) && !dir.toRealPath().startsWith(root.toRealPath())) {
                    return Map.of("error", "Skill path escapes the skills directory");
                }
            } catch (IOException e) {
                return Map.of("error", "Cannot resolve skill path: " + e.getMessage());
            }
            var one = new LinkedHashMap<String, Object>();
            one.put("name", name);
            one.put("yaml", readTextOrNull(dir.resolve("SKILL.yaml")));
            one.put("code", readTextOrNull(dir.resolve("skill.py")));
            one.put("requirements", readTextOrNull(dir.resolve("requirements.txt")));
            out.put("skill", one);
            return out;
        }

        var rows = new ArrayList<Map<String, Object>>();
        if (Files.isDirectory(root)) {
            try (var dirs = Files.list(root)) {
                for (Path dir : dirs.filter(Files::isDirectory).sorted().toList()) {
                    Path code = dir.resolve("skill.py");
                    var m = new LinkedHashMap<String, Object>();
                    m.put("name", dir.getFileName().toString());
                    m.put("codeSizeBytes", fileSize(code));
                    m.put("codeModifiedAt", modifiedAt(code));
                    m.put("codeSha256", sha256(code));
                    m.put("registered", skillRegistry.getDynamic(dir.getFileName().toString()).isPresent());
                    rows.add(m);
                }
            } catch (IOException e) {
                out.put("error", String.valueOf(e.getMessage()));
            }
        } else {
            out.put("note", "No generated skills directory on disk");
        }
        out.put("skills", rows);

        var registered = new ArrayList<Map<String, Object>>();
        for (DynamicSkill s : skillRegistry.allDynamic()) {
            registered.add(Map.of(
                    "name", s.name(),
                    "requiresNetwork", s.requiresNetwork(),
                    "hasSideEffects", s.hasSideEffects(),
                    "credentials", s.requiredCredentials(),
                    "systemPackages", s.systemPackages()));
        }
        out.put("registered", registered);
        return out;
    }

    // ────────────────────────────── tasks ──────────────────────────────

    public Map<String, Object> tasks(int limit) {
        int cap = Math.max(1, Math.min(limit, MAX_ROWS));
        var out = new LinkedHashMap<String, Object>();
        out.put("queue", Map.of("busy", taskQueue.isBusy(), "queued", taskQueue.getQueueSize()));
        out.put("recentTasks", jdbc.queryForList(
                "SELECT timestamp, user_id, task_id, event_type, severity, summary, details, tokens_used "
                        + "FROM events ORDER BY timestamp DESC LIMIT " + cap));
        out.put("longRunning", jdbc.queryForList(
                "SELECT task_id, user_id, description, status, progress_pct, progress_msg, "
                        + "heartbeat_at, started_at, completed_at FROM long_running_tasks "
                        + "ORDER BY started_at DESC LIMIT " + cap));
        out.put("upcomingScheduled", jdbc.queryForList(
                "SELECT id, user_id, task_type, status, description, next_run_at, run_count "
                        + "FROM scheduled_tasks WHERE status = 'active' ORDER BY next_run_at LIMIT " + cap));
        return out;
    }

    public Map<String, Object> task(String taskId) {
        var out = new LinkedHashMap<String, Object>();
        out.put("taskId", taskId);
        out.put("events", jdbc.queryForList(
                "SELECT timestamp, user_id, event_type, severity, summary, details, tokens_used "
                        + "FROM events WHERE task_id = ? ORDER BY timestamp", taskId));
        out.put("toolCalls", jdbc.queryForList(
                "SELECT created_at, user_id, tool_name, success, duration_ms "
                        + "FROM skill_usage WHERE task_id = ? ORDER BY created_at", taskId));
        out.put("memory", jdbc.queryForList(
                "SELECT created_at, user_id, memory_type, outcome, substr(content,1,400) AS content "
                        + "FROM agent_memory WHERE task_id = ? ORDER BY created_at", taskId));
        out.put("note", "Per-step think/act/observe detail is not persisted by this build; "
                + "use /api/ops/logs?grep=Task+" + taskId + " for the step trail.");
        return out;
    }

    // ────────────────────────────── self-test ──────────────────────────────

    /** Structured pass/fail across the moving parts, so a regression is one call away. */
    public Map<String, Object> selfTest() {
        var results = new ArrayList<Map<String, Object>>();

        results.add(test("database.read", () -> {
            Long users = jdbc.queryForObject("SELECT COUNT(*) FROM users", Long.class);
            return Map.of("ok", true, "userCount", users);
        }));

        results.add(test("database.writable", () -> {
            // Non-destructive: a transaction that is always rolled back.
            Boolean ok = jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>) con -> {
                boolean previous = con.getAutoCommit();
                con.setAutoCommit(false);
                try (var st = con.createStatement()) {
                    st.executeUpdate("CREATE TABLE IF NOT EXISTS ops_write_probe (probed_at TEXT)");
                    st.executeUpdate("INSERT INTO ops_write_probe (probed_at) VALUES (datetime('now'))");
                    return true;
                } finally {
                    con.rollback();
                    con.setAutoCommit(previous);
                }
            });
            return Map.of("ok", Boolean.TRUE.equals(ok), "note", "write probed inside a rolled-back transaction");
        }));

        results.add(test("tools.registered", () -> {
            int total = toolRegistry.all().size();
            int dynamic = skillRegistry.allDynamic().size();
            var m = new LinkedHashMap<String, Object>();
            m.put("ok", total > 0);
            m.put("total", total);
            m.put("dynamic", dynamic);
            m.put("builtIn", total - dynamic);
            if (total - dynamic == 0) {
                m.put("warning", "No built-in tools: every capability must be generated as Python first.");
            }
            return m;
        }));

        results.add(test("cloudLlm.configured", () -> {
            var summary = cloudSummary();
            String provider = String.valueOf(summary.get("provider"));
            @SuppressWarnings("unchecked")
            var key = (Map<String, Object>) summary.get(
                    "anthropic".equalsIgnoreCase(provider) ? "anthropicKey" : "openaiKey");
            var m = new LinkedHashMap<String, Object>(summary);
            m.put("ok", Boolean.TRUE.equals(key.get("present")));
            if (!Boolean.TRUE.equals(key.get("present"))) {
                m.put("warning", "Active provider " + provider + " has no API key configured.");
            }
            return m;
        }));

        results.add(test("localLlm.chatRoundTrip", () -> {
            var m = new LinkedHashMap<String, Object>(
                    chatRoleTest(config.getExecutor().getUrl(), config.getExecutor().getModel()));
            m.putIfAbsent("ok", false);
            return m;
        }));

        results.add(test("localLlm.modelInstalled", () -> {
            var summary = ollamaSummary();
            var m = new LinkedHashMap<String, Object>(summary);
            m.put("ok", Boolean.TRUE.equals(summary.get("configuredModelInstalled")));
            return m;
        }));

        results.add(test("skills.directoryWritable", () -> {
            Path root = Path.of(config.getSkills().getGeneratedPath());
            return Map.of("ok", Files.isDirectory(root) && Files.isWritable(root),
                    "path", root.toString(),
                    "exists", Files.isDirectory(root));
        }));

        results.add(test("logs.readable", () -> {
            Path f = logFile();
            return Map.of("ok", Files.isReadable(f), "path", f.toString(), "sizeBytes", fileSize(f));
        }));

        long failed = results.stream().filter(r -> !Boolean.TRUE.equals(r.get("ok"))).count();
        var out = new LinkedHashMap<String, Object>();
        out.put("ok", failed == 0);
        out.put("passed", results.size() - failed);
        out.put("failed", failed);
        out.put("tests", results);
        return out;
    }

    // ────────────────────────────── helpers ──────────────────────────────

    private interface Probe {
        Map<String, Object> run() throws Exception;
    }

    private Map<String, Object> test(String name, Probe probe) {
        var m = new LinkedHashMap<String, Object>();
        m.put("name", name);
        long t0 = System.currentTimeMillis();
        try {
            Map<String, Object> r = probe.run();
            m.putAll(r);
            m.putIfAbsent("ok", true);
        } catch (Exception e) {
            m.put("ok", false);
            m.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        m.put("durationMs", System.currentTimeMillis() - t0);
        return m;
    }

    private Map<String, Object> check(Probe probe) {
        return test("check", probe);
    }

    private JsonNode getJson(String url) throws IOException {
        Request req = new Request.Builder().url(url).get().build();
        try (Response resp = http.newCall(req).execute()) {
            String body = resp.body() == null ? "" : resp.body().string();
            if (!resp.isSuccessful()) throw new IOException("HTTP " + resp.code() + ": " + truncate(body, 200));
            return mapper.readTree(body);
        }
    }

    private JsonNode postJson(String url, Object payload) throws IOException {
        RequestBody body = RequestBody.create(mapper.writeValueAsString(payload),
                okhttp3.MediaType.get("application/json"));
        Request req = new Request.Builder().url(url).post(body).build();
        try (Response resp = http.newCall(req).execute()) {
            String text = resp.body() == null ? "" : resp.body().string();
            if (!resp.isSuccessful()) throw new IOException("HTTP " + resp.code() + ": " + truncate(text, 200));
            return mapper.readTree(text);
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private static String readTextOrNull(Path p) {
        try {
            return Files.isReadable(p) ? Files.readString(p) : null;
        } catch (IOException e) {
            return "«unreadable: " + e.getMessage() + "»";
        }
    }

    private static long fileSize(Path p) {
        try {
            return Files.isRegularFile(p) ? Files.size(p) : -1;
        } catch (IOException e) {
            return -1;
        }
    }

    private static String modifiedAt(Path p) {
        try {
            return Files.isRegularFile(p) ? Files.getLastModifiedTime(p).toInstant().toString() : "";
        } catch (IOException e) {
            return "";
        }
    }

    private static String sha256(Path p) {
        try {
            if (!Files.isRegularFile(p)) return "";
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(Files.readAllBytes(p));
            var sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private String deployedCommit() {
        for (Path p : List.of(Path.of("/opt/ownclaw/.deployed-commit"), Path.of(".deployed-commit"))) {
            String s = readTextOrNull(p);
            if (s != null && !s.isBlank()) return s.trim().split("\\s+")[0];
        }
        return "unknown";
    }
}
