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
import java.io.File;
import java.net.URI;
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
    private final com.ownclaw.agent.SkillCuratorService curatorService;
    private final ObjectMapper mapper;
    private final OkHttpClient http;
    private final Instant startedAt = Instant.now();

    public OpsService(OwnClawConfig config, JdbcTemplate jdbc, ToolRegistry toolRegistry,
                      DynamicSkillRegistry skillRegistry, TaskQueue taskQueue,
                      AuthService authService,
                      com.ownclaw.agent.SkillCuratorService curatorService,
                      ObjectMapper mapper) {
        this.config = config;
        this.jdbc = jdbc;
        this.toolRegistry = toolRegistry;
        this.skillRegistry = skillRegistry;
        this.taskQueue = taskQueue;
        this.authService = authService;
        this.curatorService = curatorService;
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
        out.put("deploy", deployFreshness());
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
            // NOT caps.contains("chat"): Ollama has no such capability. See LocalModelCheck.
            m.put("chatUsable", com.ownclaw.llm.LocalModelCheck.chatUsable(template, caps));
            m.put("supportsTools", caps.contains("tools"));
            m.put("supportsThinking", caps.contains("thinking"));
            m.put("templateLength", template.length());
            m.put("templateRendersMessages", template.contains(".Messages") || template.contains(".System"));
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
                    // 32 was not a budget, it was a trap: a thinking model spends its output on
                    // reasoning first, so it hit the cap mid-thought every time, returned an empty
                    // answer, and got reported as unable to follow a system message. 256 is enough
                    // for a short reasoning pass plus one word, and the probe still costs one call.
                    "options", Map.of("temperature", 0, "num_predict", 256)));
            String content = r.path("message").path("content").asText("");
            // A thinking model answers in two parts, so the canary may legitimately appear in
            // the reasoning when the budget ran out before the final answer.
            String thinking = r.path("message").path("thinking").asText("");
            int promptEval = r.path("prompt_eval_count").asInt(-1);
            String doneReason = r.path("done_reason").asText("");
            boolean honoured = (content + " " + thinking).toUpperCase(Locale.ROOT).contains(canary);

            // Whether the message list was rendered at all is a separate question from whether
            // the model obeyed it, and it has its own evidence: this probe sends a system message
            // and a user message that together come to roughly 30 tokens. A working template
            // reports about that. The bare "{{ .Prompt }}" fallback passes on the user content
            // alone -- "hello", one token -- and discards the rest, so the two cases are far
            // apart and not a judgement call.
            boolean rendered = promptEval >= 10;
            boolean ranOutThinking = "length".equals(doneReason) && content.isBlank() && !thinking.isBlank();

            m.put("ok", honoured);
            m.put("systemMessageHonoured", honoured);
            m.put("messagesRendered", rendered);
            m.put("promptEvalCount", promptEval);
            m.put("doneReason", doneReason);
            m.put("latencyMs", System.currentTimeMillis() - t0);
            m.put("reply", content.length() > 200 ? content.substring(0, 200) + "..." : content);
            m.put("thinkingChars", thinking.length());

            if (honoured && content.isBlank() && !thinking.isBlank()) {
                m.put("note", "The system message was followed, but the answer never arrived: the whole "
                        + "output budget went on reasoning. Local calls need a larger max_tokens for this "
                        + "model, not a different model.");
            } else if (!honoured && ranOutThinking && rendered) {
                // The case that produced a confidently wrong answer: a thinking model that never
                // reached its answer was reported as proof of a missing chat_template, sending
                // whoever read it to replace a model that renders messages perfectly well.
                m.put("diagnosis", "Inconclusive, and NOT a template problem: the message list rendered "
                        + "fine (promptEvalCount=" + promptEval + ", far above the ~1 a bare "
                        + "\"{{ .Prompt }}\" fallback would give), but this is a thinking model and it hit "
                        + "the output cap mid-reasoning, so there was no answer left to check the canary "
                        + "against. It says nothing bad about the model. Re-run after raising num_predict, "
                        + "or judge it on a real task instead.");
            } else if (!honoured && !rendered) {
                m.put("diagnosis", "The message list was NOT rendered: promptEvalCount=" + promptEval
                        + " for a prompt of roughly 30 tokens, so Ollama passed the user text through the "
                        + "bare \"{{ .Prompt }}\" fallback and discarded the system prompt and the roles. "
                        + "Every local job gets unrelated text. Usually the server is too old to read the "
                        + "GGUF's Jinja template -- upgrading Ollama has fixed exactly this on this "
                        + "deployment -- otherwise re-create the model with a Modelfile carrying the right "
                        + "template, or point the executor at one that renders messages.");
            } else if (!honoured) {
                m.put("diagnosis", "The message list rendered (promptEvalCount=" + promptEval + ") but the "
                        + "reply did not contain the canary, so the model saw the instruction and did not "
                        + "follow it. That is a model-quality signal, not a configuration fault: this is a "
                        + "deliberately pedantic instruction and some models answer conversationally "
                        + "instead. Judge it on a real task before replacing it.");
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
                        // DESC: this is an incident tool, and ORDER BY timestamp ASC with a LIMIT
                        // returned a user's OLDEST messages -- so the events, tool calls and memory
                        // blocks showed today while the conversation block showed their first ever
                        // exchanges, which reads as a conversation that stopped months ago.
                        + "FROM conversations WHERE user_id = ? ORDER BY timestamp DESC LIMIT " + cap, userId));
        out.put("sessions", jdbc.queryForList(
                "SELECT id, title, substr(preview,1,200) AS preview, created_at, updated_at, archived "
                        + "FROM chat_sessions WHERE user_id = ? ORDER BY created_at DESC LIMIT " + cap, userId));
        out.put("events", jdbc.queryForList(
                "SELECT timestamp, event_type, severity, task_id, summary, details, tokens_used "
                        + "FROM events WHERE user_id = ? ORDER BY timestamp DESC LIMIT " + cap, userId));
        out.put("toolCalls", jdbc.queryForList(
                "SELECT created_at, tool_name, task_id, success, duration_ms, label, "
                        + "substr(error,1,1000) AS error "
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
                // label and error included: a PRIVATE descriptor ends with "text withheld;
                // skill_usage row via ops", and this is that row. Without the error column the
                // pointer named a page that did not show it, leaving the owner no way at all to
                // read what a private step had actually returned.
                "SELECT created_at, user_id, tool_name, success, duration_ms, label, "
                        + "substr(error,1,4000) AS error "
                        + "FROM skill_usage WHERE task_id = ? ORDER BY created_at", taskId));
        out.put("memory", jdbc.queryForList(
                "SELECT created_at, user_id, memory_type, outcome, substr(content,1,400) AS content "
                        + "FROM agent_memory WHERE task_id = ? ORDER BY created_at", taskId));
        // What left this JVM for a cloud model on this task, from the ledger rows -- and the
        // artifacts the steps recorded. Headed "llmChannel" and not "left the machine": the
        // door covers the two LLM API endpoints from this process and nothing else, and a page
        // that said more would lie. notObserved names the channels it cannot see.
        out.put("llmChannel", egressSummary(taskId));
        out.put("artifacts", artifactsOf(taskId));
        out.put("notObserved", List.of(
                "a skill with requires_network can send its inputs, an attachment or its vault "
                        + "environment to any host; the sandbox has no egress policy yet",
                "OWNCLAW_EXECUTOR_URL is wherever the local model is; nothing asserts it is private",
                "the OpenAI availability probe sends the bearer key to /v1/models with no "
                        + "content and no ledger row"));
        return out;
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper DETAILS =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private Map<String, Object> egressSummary(String taskId) {
        var rows = jdbc.queryForList(
                "SELECT details FROM events WHERE task_id = ? AND event_type = 'egress'", taskId);
        long calls = 0, bytes = 0, prompt = 0, completion = 0, cacheRead = 0, cacheWrite = 0, scrubs = 0;
        double cost = 0;
        var decisions = new java.util.TreeMap<String, Integer>();
        for (var r : rows) {
            try {
                var d = DETAILS.readTree(String.valueOf(r.get("details")));
                calls++;
                bytes += d.path("bytesOut").asLong();
                prompt += d.path("promptTokens").asLong();
                completion += d.path("completionTokens").asLong();
                cacheRead += d.path("cacheReadTokens").asLong();
                cacheWrite += d.path("cacheWriteTokens").asLong();
                scrubs += d.path("scrubs").asLong();
                cost += d.path("costUsd").asDouble();
                decisions.merge(d.path("decision").asText("?"), 1, Integer::sum);
            } catch (Exception ignored) {
                // A row that cannot be parsed is still a row; count it as unknown.
                decisions.merge("unparsed", 1, Integer::sum);
            }
        }
        var out = new LinkedHashMap<String, Object>();
        out.put("calls", calls); out.put("bytesOut", bytes);
        out.put("promptTokens", prompt); out.put("completionTokens", completion);
        out.put("cacheReadTokens", cacheRead); out.put("cacheWriteTokens", cacheWrite);
        out.put("scrubs", scrubs); out.put("costUsd", Math.round(cost * 1_000_000) / 1_000_000.0);
        out.put("decisions", decisions);
        return out;
    }

    private List<Map<String, Object>> artifactsOf(String taskId) {
        var rows = jdbc.queryForList(
                "SELECT details FROM events WHERE task_id = ? AND event_type = 'step' ORDER BY timestamp", taskId);
        var out = new ArrayList<Map<String, Object>>();
        for (var r : rows) {
            try {
                var d = DETAILS.readTree(String.valueOf(r.get("details")));
                if (d.has("artifact")) {
                    var a = new LinkedHashMap<String, Object>();
                    a.put("handle", d.path("artifact").asText());
                    a.put("tool", d.path("tool").asText());
                    a.put("label", d.path("label").asText());
                    a.put("chars", d.path("chars").asLong());
                    a.put("sha256_16", d.path("sha256_16").asText());
                    if (d.has("why")) a.put("why", DETAILS.convertValue(d.get("why"), List.class));
                    out.add(a);
                }
                if (d.has("artifacts")) {
                    for (var x : d.get("artifacts")) {
                        var a = new LinkedHashMap<String, Object>();
                        a.put("handle", "$" + x.path("n").asInt());
                        a.put("tool", x.path("tool").asText());
                        a.put("label", x.path("label").asText());
                        a.put("chars", x.path("chars").asLong());
                        if (x.has("why")) a.put("why", DETAILS.convertValue(x.get("why"), List.class));
                        out.add(a);
                    }
                }
            } catch (Exception ignored) { }
        }
        return out;
    }

    /** The ledger, newest first. */
    public Map<String, Object> egress(int limit, String decision) {
        int cap = Math.max(1, Math.min(limit, 500));
        String sql = "SELECT timestamp, user_id, task_id, summary, details FROM events "
                + "WHERE event_type = 'egress'"
                + (decision == null || decision.isBlank() ? "" : " AND summary LIKE ?")
                + " ORDER BY timestamp DESC LIMIT " + cap;
        var rows = decision == null || decision.isBlank()
                ? jdbc.queryForList(sql)
                : jdbc.queryForList(sql, decision.trim().toUpperCase(java.util.Locale.ROOT) + " %");
        var out = new LinkedHashMap<String, Object>();
        out.put("rows", rows);
        out.put("note", "Sizes, kinds and hash prefixes of every part; tokens and cost. No content, "
                + "by construction: the ledger cannot become an audit copy.");
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

    /**
     * Tool sequences that keep succeeding together — candidates for one skill.
     * <p>
     * Reports only; it never writes a skill. See SkillCuratorService.consolidationCandidates.
     */
    public Map<String, Object> consolidationCandidates(int minLength, int minTasks) {
        var out = new LinkedHashMap<String, Object>();
        out.put("minLength", minLength);
        out.put("minTasks", minTasks);
        var found = curatorService.consolidationCandidates(minLength, minTasks);
        out.put("count", found.size());
        out.put("candidates", found);
        out.put("note", found.isEmpty()
                ? "Nothing yet — step history accumulates as tasks run."
                : "These runs of tools recur across separate tasks. Each is a candidate for a "
                  + "single skill taking the specifics as parameters. Detection only: nothing "
                  + "is created automatically.");
        return out;
    }

    /**
     * Registered skills that look like duplicates of one another.
     * <p>
     * The gate in CriticAgent stops NEW duplicates being created. It does nothing about the
     * ones already there — eight IMAP skills, four network scanners, two left-over _debug
     * artifacts — because a gate is a flow control and this is a stock problem.
     * <p>
     * Reports clusters, and only clusters. Merging is not something to do automatically: two
     * skills that look alike by name can differ in ways only their code shows, and collapsing
     * them on a name comparison would quietly delete behaviour something depends on. The point
     * is to make the pile visible so it can be dealt with deliberately.
     * <p>
     * Same two tests the creation gate uses, so what it reports and what it would block are the
     * same judgement: a prefix-sibling relationship, or Jaccard overlap of the name tokens.
     */
    public Map<String, Object> duplicateSkills(double threshold) {
        List<String> names = new ArrayList<>(toolRegistry.names());
        Collections.sort(names);

        // Union-find, so a chain of pairwise similarities becomes one group rather than three.
        Map<String, String> parent = new LinkedHashMap<>();
        for (String n : names) parent.put(n, n);
        java.util.function.Function<String, String> find = new java.util.function.Function<>() {
            @Override public String apply(String x) {
                while (!parent.get(x).equals(x)) { parent.put(x, parent.get(parent.get(x))); x = parent.get(x); }
                return x;
            }
        };
        for (int i = 0; i < names.size(); i++) {
            for (int j = i + 1; j < names.size(); j++) {
                String a = names.get(i).toLowerCase(), b = names.get(j).toLowerCase();
                boolean sibling = a.startsWith(b + "_") || b.startsWith(a + "_");
                if (sibling || com.ownclaw.agent.CriticAgent.tokenOverlap(a, b) >= threshold) {
                    parent.put(find.apply(names.get(i)), find.apply(names.get(j)));
                }
            }
        }
        Map<String, List<String>> groups = new LinkedHashMap<>();
        for (String n : names) groups.computeIfAbsent(find.apply(n), k -> new ArrayList<>()).add(n);

        var clusters = new ArrayList<Map<String, Object>>();
        for (var e : groups.entrySet()) {
            if (e.getValue().size() < 2) continue;
            clusters.add(new LinkedHashMap<>(Map.of(
                    "size", e.getValue().size(),
                    "skills", e.getValue())));
        }
        clusters.sort((a, b) -> ((Integer) b.get("size")) - ((Integer) a.get("size")));

        var out = new LinkedHashMap<String, Object>();
        out.put("threshold", threshold);
        out.put("totalSkills", names.size());
        out.put("clusters", clusters);
        out.put("redundant", clusters.stream().mapToInt(c -> ((Integer) c.get("size")) - 1).sum());
        out.put("note", clusters.isEmpty()
                ? "No name-similar clusters."
                : "Each cluster is probably one capability built more than once. Detection only — "
                  + "check what the code actually does before collapsing any of them, because "
                  + "names that look alike can hide behaviour that is not.");
        return out;
    }

    private String deployedCommit() {
        Path p = deployMarkerPath();
        if (p != null) {
            String s = readTextOrNull(p);
            if (s != null && !s.isBlank()) return s.trim().split("\\s+")[0];
        }
        return "unknown";
    }

    private Path deployMarkerPath() {
        for (Path p : List.of(Path.of("/opt/ownclaw/.deployed-commit"), Path.of(".deployed-commit"))) {
            if (Files.exists(p)) return p;
        }
        return null;
    }

    /**
     * Whether the commit in the deploy marker is actually the one running.
     * <p>
     * {@code deployedCommit} reads a marker file that deploy.sh writes <em>before</em> it
     * restarts the service, so between those two moments health reports a commit that is not
     * running yet. That is not a hypothetical: it caused two wrong conclusions in one session —
     * a database migration was reported missing when it had simply not been applied yet, and a
     * change was judged not to work when the test had run against the previous jar.
     * <p>
     * The marker's modification time versus this process's start time settles it. If the marker
     * is newer than the process, the running code predates it and a restart is still pending.
     */
    private Map<String, Object> deployFreshness() {
        var out = new LinkedHashMap<String, Object>();
        Path p = deployMarkerPath();
        if (p == null) {
            out.put("markerFound", false);
            return out;
        }
        out.put("markerFound", true);
        try {
            Instant markerAt = Files.getLastModifiedTime(p).toInstant();
            out.put("markerWrittenAt", markerAt.toString());
            boolean markerNewer = markerAt.isAfter(startedAt);

            // The marker alone is not enough, and trusting it produced a wrong answer a third
            // time: it said c0e333f while the process was demonstrably running the commit before
            // it, because deploy.sh records the commit when it swaps the JAR and a restart that
            // cannot happen yet (the agent was busy) leaves the previous JAR loaded. Comparing
            // the marker to the start time does not catch that -- the marker was older than this
            // process, so it looked settled.
            //
            // The JAR is the ground truth. This process is running the bytes that file held when
            // it started; if the file has changed since, the running code is not what is on disk,
            // whatever any marker says. It needs no cooperation from the deploy script, which is
            // the point -- every previous version of this check trusted something deploy.sh wrote.
            Path jar = launchedJar();
            Instant jarAt = jar == null ? null : Files.getLastModifiedTime(jar).toInstant();
            boolean jarNewer = jarAt != null && jarAt.isAfter(startedAt);
            // Say when this check could not run. Omitting the field is what hid the fact that it
            // was doing nothing at all in production.
            out.put("artifactModifiedAt", jarAt != null ? jarAt.toString()
                    : "unavailable — no launched JAR found (exploded classpath?), so this check "
                      + "is NOT protecting you and only the marker comparison applies");

            boolean stale = markerNewer || jarNewer;
            out.put("running", !stale);
            if (jarNewer) {
                out.put("note", "STALE — the deployed artifact has been replaced since this process "
                        + "started, so the reported commit is NOT the code running. A restart is "
                        + "needed to load it; deploy.sh defers the restart while a task is running. "
                        + "Verify a change by its behaviour, not by this commit.");
            } else if (markerNewer) {
                out.put("note", "RESTART PENDING — the marker was written after this process started, "
                        + "so the reported commit is NOT the code currently running.");
            } else {
                out.put("note", "The reported commit is the code currently running.");
            }
        } catch (Exception e) {
            out.put("running", "unknown");
            out.put("note", "Could not read the marker's timestamp: " + e.getMessage());
        }
        return out;
    }

    /**
     * The JAR this JVM was launched from, or null when there is not one (an exploded classpath
     * in development).
     * <p>
     * The first version of this asked the protection domain for its code source and called
     * {@code Path.of(uri)} on it. Under {@code java -jar} — which is how this actually runs —
     * Spring Boot's launcher reports {@code jar:file:/opt/ownclaw/ownclaw.jar!/BOOT-INF/classes!/},
     * whose scheme is {@code jar}, not {@code file}. {@code Path.of} throws on it, the catch
     * returned null, the caller omitted the field, and the freshness check silently degraded to
     * the marker-only behaviour it was written to replace. It shipped looking correct and did
     * nothing for a day. Hence two strategies and, above all, no silent null.
     */
    private Path launchedJar() {
        // 1. Under `java -jar x.jar` the class path is exactly that one jar. This is the normal
        //    production case and needs no URI parsing at all.
        String cp = System.getProperty("java.class.path", "");
        if (!cp.isBlank() && !cp.contains(File.pathSeparator) && cp.endsWith(".jar")) {
            Path p = Path.of(cp).toAbsolutePath();
            if (Files.isRegularFile(p)) return p;
        }
        // 2. Otherwise read the code source, tolerating a nested "jar:file:...!/..." URL by
        //    taking the part before the first "!/" separator.
        try {
            var src = OpsService.class.getProtectionDomain().getCodeSource();
            if (src == null || src.getLocation() == null) return null;
            String url = fileUrlOfContainingArchive(src.getLocation().toString());
            if (url == null) return null;
            Path p = Path.of(URI.create(url));
            return Files.isRegularFile(p) ? p : null;   // a directory means an exploded build
        } catch (Exception e) {
            log.debug("Could not locate the launched artifact: {}", e.toString());
            return null;
        }
    }

    /**
     * Reduce a code-source URL to the {@code file:} URL of the archive that contains it, or null
     * if it does not name one.
     * <p>
     * Package-private and separate so it can be tested, because this is the exact step that
     * failed: Spring Boot's nested launcher reports
     * {@code jar:file:/opt/ownclaw/ownclaw.jar!/BOOT-INF/classes!/}, and feeding that straight to
     * {@code Path.of} throws, which the caller turned into a silent null.
     */
    static String fileUrlOfContainingArchive(String url) {
        if (url == null) return null;
        if (url.startsWith("jar:")) url = url.substring(4);
        int bang = url.indexOf("!/");
        if (bang >= 0) url = url.substring(0, bang);
        return url.startsWith("file:") ? url : null;
    }
}
