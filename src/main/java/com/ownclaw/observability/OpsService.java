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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.ColumnMapRowMapper;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

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
            "value",
            // conversations.private_content is an answer written on this machine from the
            // owner's file and kept from the cloud; the ops API is read by sessions whose
            // model runs in the cloud.
            "private_content");

    /** Columns whose name says they hold secret material. Deliberately excludes a bare "key". */
    private static final Pattern SECRET_VALUE_COLUMN = Pattern.compile(
            "(?i)(secret|passwd|password|api[-_]?key|private[-_]?key|access[-_]?token|bearer)");

    /** Identifiers that may not appear in ops SQL at all (blocks aliasing around the above). */
    private static final Pattern SQL_FORBIDDEN = Pattern.compile(
            "(?i)\\b(password_hash|encryption_salt|encrypted_value|jwt_secret|vault_master_key"
                    + "|private_content|conversations|file_attachments|pragma|attach|detach|vacuum)\\b");

    private static final Pattern SQL_ALLOWED_START = Pattern.compile("(?is)^\\s*(select|with)\\b.*");

    /** system_settings holds the vault master key and the JWT secret in plaintext. */
    private static final String SETTINGS_TABLE = "system_settings";

    /** A generated skill is a single directory name — no separators, no traversal. */
    private static final Pattern SKILL_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}");

    /** A /logs cursor: which file, by the hash of its first line, and the line to read up to. */
    private static final Pattern LOG_CURSOR = Pattern.compile("([0-9a-f]{16}):(\\d+)");

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

    @Autowired
    public OpsService(OwnClawConfig config, JdbcTemplate jdbc, ToolRegistry toolRegistry,
                      DynamicSkillRegistry skillRegistry, TaskQueue taskQueue,
                      AuthService authService,
                      com.ownclaw.agent.SkillCuratorService curatorService,
                      ObjectMapper mapper) {
        // A cold Ollama load of a 20+ GB model can take minutes, so the diagnostic waits
        // longer than a normal call would. A hung Ollama therefore blocks one ops request
        // for up to this long; that is acceptable for a probe and is stated in the response.
        this(config, jdbc, toolRegistry, skillRegistry, taskQueue, authService, curatorService, mapper,
                new OkHttpClient.Builder()
                        .connectTimeout(5, TimeUnit.SECONDS)
                        .readTimeout(240, TimeUnit.SECONDS)
                        .build());
    }

    /** With the client the local-model probes go through, so a test can answer them. */
    OpsService(OwnClawConfig config, JdbcTemplate jdbc, ToolRegistry toolRegistry,
               DynamicSkillRegistry skillRegistry, TaskQueue taskQueue,
               AuthService authService,
               com.ownclaw.agent.SkillCuratorService curatorService,
               ObjectMapper mapper, OkHttpClient http) {
        this.config = config;
        this.jdbc = jdbc;
        this.toolRegistry = toolRegistry;
        this.skillRegistry = skillRegistry;
        this.taskQueue = taskQueue;
        this.authService = authService;
        this.curatorService = curatorService;
        this.mapper = mapper;
        this.http = http;
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
            m.put("reply", content);
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

    /**
     * The log, a page at a time from the newest end: the newest {@code lines} lines that pass
     * the filters, returned oldest first, read from the current file and then from every file
     * it was rolled into. A full page carries {@code next}, the cursor for the lines before it
     * (the page it returns can be empty); any other page was read down to the first line of the
     * oldest file, and its {@code next} is null -- except the page of a cursor that has to wait,
     * below. So every line of every file that can be read is reached, and a file that cannot be
     * is named in {@code unreadable}.
     * <p>
     * A cursor waits for its own file. If that file is on disk but no copy of it reads as far as
     * the cursor -- an archive read while logback was still writing it, or one whose writing
     * failed -- the page comes back empty with the same cursor as {@code next}: asking again
     * reads an archive finished since, and starting again without a cursor pages past one that
     * stays unreadable.
     */
    public Map<String, Object> logs(int lines, String grep, String level, String cursor) {
        if (lines < 1) throw new IllegalArgumentException("lines must be 1 or more, not " + lines);
        // A cursor names the file a previous page stopped in and the line it stopped before.
        String fromFile = null;
        long before = Long.MAX_VALUE;
        if (cursor != null && !cursor.isBlank()) {
            Matcher c = LOG_CURSOR.matcher(cursor.trim());
            if (!c.matches()) throw new IllegalArgumentException("Not a cursor /logs returned: " + cursor);
            fromFile = c.group(1);
            before = Long.parseLong(c.group(2));
        }

        var out = new LinkedHashMap<String, Object>();
        Path file = logFile();
        out.put("file", file.toString());
        if (!Files.isReadable(file)) {
            out.put("error", "Log file not readable. Set OWNCLAW_LOG_FILE, or check that "
                    + "logging.file.name points somewhere the service user can write.");
            return out;
        }
        List<Path> files;
        try {
            files = logFiles(file);
        } catch (IOException e) {
            out.put("error", "Could not list the log directory: " + e.getMessage());
            return out;
        }

        Predicate<String> keep = logFilter(grep, level);
        var page = new ArrayDeque<String>();
        var unreadable = new LinkedHashMap<String, String>();
        // The files read, by id. Logback's .tmp and the archive it writes from it hold the same
        // lines under the same id, and the first of them that reads is the one used.
        var read = new HashSet<String>();
        boolean cursorFileFound = false;
        String next = null;
        for (int i = 0; i < files.size() && page.size() < lines; i++) {
            Path f = files.get(i);
            try (BufferedReader r = openLog(f)) {
                String first = r.readLine();
                String id = logFileId(first);
                if (read.contains(id)) continue;
                long bound = Long.MAX_VALUE;
                if (fromFile != null) {
                    // A file newer than the cursor's was read by the pages before it.
                    if (!fromFile.equals(id)) continue;
                    cursorFileFound = true;
                    bound = before;
                }
                List<LogLine> found = lastLines(first, r, bound, lines - page.size(), keep);
                // Only a file read as far as it had to be is done with, the cursor's included.
                read.add(id);
                fromFile = null;
                for (int k = found.size() - 1; k >= 0; k--) page.addFirst(found.get(k).text());
                if (page.size() == lines) next = id + ":" + found.getFirst().number();
            } catch (IOException e) {
                // Named, not skipped silently. An archive logback is still writing reads only as
                // far as the writing has got; the .tmp it is written from, listed after it, is
                // read instead.
                unreadable.put(f.getFileName().toString(), String.valueOf(e.getMessage()));
            }
        }
        if (fromFile != null) {
            if (!cursorFileFound) {
                throw new IllegalArgumentException("The log file this cursor points into is not on disk "
                        + "any more (log retention removes the oldest) or could not be read"
                        + (unreadable.isEmpty() ? "" : " " + unreadable) + ". Start again without a cursor.");
            }
            next = cursor.trim();
        }

        out.put("sizeBytes", fileSize(file));
        out.put("files", files.stream()
                .map(f -> Map.<String, Object>of("name", f.getFileName().toString(), "sizeBytes", fileSize(f)))
                .toList());
        out.put("returned", page.size());
        out.put("filters", Map.of("grep", grep == null ? "" : grep, "level", level == null ? "" : level));
        if (!unreadable.isEmpty()) out.put("unreadable", unreadable);
        out.put("lines", new ArrayList<>(page));
        out.put("next", next);
        return out;
    }

    /** One line of a log file, with its number counted from the top of the file. */
    private record LogLine(long number, String text) {}

    /**
     * What a cursor names a log file by: the hash of its first line.
     * <p>
     * Not the name. A rollover renames the current file, so a cursor that named it would read
     * the new, nearly empty file after a rollover and page through the wrong lines. The first line
     * moves with the content, and lines are counted from the top, which appending does not
     * change -- so a cursor stays exact while the file grows and after it is rolled over.
     */
    private static String logFileId(String firstLine) {
        return sha256Hex((firstLine == null ? "" : firstLine).getBytes(StandardCharsets.UTF_8)).substring(0, 16);
    }

    /**
     * The current log file, then the files it was rolled into, newest first.
     * <p>
     * Logback rolls the current file into {@code <name>.<date>.<index>.gz} beside it (Spring
     * Boot's default pattern; application.yaml sets only the sizes and the history), so the
     * rolled files are every file there whose name extends the current one's. That includes
     * logback's {@code .tmp}: it renames the current file to one, writes the archive from it,
     * then deletes it, so until the archive is whole the .tmp is the copy that reads to its end.
     */
    private static List<Path> logFiles(Path current) throws IOException {
        String prefix = current.getFileName() + ".";
        var rolled = new ArrayList<Path>();
        try (var listing = Files.list(current.toAbsolutePath().getParent())) {
            listing.filter(p -> p.getFileName().toString().startsWith(prefix) && Files.isRegularFile(p))
                    .forEach(rolled::add);
        }
        // Newest first by when each was written; the name settles a tie. A file deleted since
        // the listing -- a .tmp whose archive is done, the oldest file under retention -- reads
        // as written at 0, so it goes last, and is named unreadable when it cannot be opened.
        var written = new HashMap<Path, Long>();
        for (Path p : rolled) written.put(p, p.toFile().lastModified());
        rolled.sort(Comparator.comparing((Path p) -> written.get(p))
                .thenComparing(p -> p.getFileName().toString()).reversed());
        var files = new ArrayList<Path>();
        files.add(current);
        files.addAll(rolled);
        return files;
    }

    /** A log file as text; a rolled one is gzip-compressed, which is logback's default. */
    private static BufferedReader openLog(Path file) throws IOException {
        InputStream in = Files.newInputStream(file);
        try {
            if (file.getFileName().toString().endsWith(".gz")) in = new GZIPInputStream(in);
            return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            in.close();
            throw e;
        }
    }

    /**
     * The last {@code want} lines that {@code keep} passes among a file's lines numbered below
     * {@code before}, given its first line and a reader positioned after it. Read front to back
     * -- a compressed file can be read no other way -- holding only the lines this page can
     * still return.
     */
    private static List<LogLine> lastLines(String first, BufferedReader rest, long before, int want,
                                           Predicate<String> keep) throws IOException {
        var found = new ArrayDeque<LogLine>();
        String line = first;
        for (long n = 0; line != null && n < before; n++, line = rest.readLine()) {
            if (!keep.test(line)) continue;
            if (found.size() == want) found.removeFirst();
            found.addLast(new LogLine(n, line));
        }
        return new ArrayList<>(found);
    }

    /** Non-blank lines holding the level and, case-insensitively, the grep text. */
    private static Predicate<String> logFilter(String grep, String level) {
        Pattern needle = (grep == null || grep.isBlank()) ? null
                : Pattern.compile(Pattern.quote(grep), Pattern.CASE_INSENSITIVE);
        String lvl = (level == null || level.isBlank()) ? null : level.trim().toUpperCase(Locale.ROOT);
        return line -> !line.isBlank()
                && (lvl == null || line.contains(lvl))
                && (needle == null || needle.matcher(line).find());
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

    // ────────────────────────────── paging ──────────────────────────────

    /**
     * Which rows of a listing a call returns: {@code limit} of them, from row {@code offset}.
     * Any limit may be asked for. A listing that goes on past its page says where the next page
     * starts, so every row stays reachable whatever the page size.
     */
    public record Page(long offset, int limit) {
        public Page {
            if (offset < 0) throw new IllegalArgumentException("offset must be 0 or more, not " + offset);
            if (limit < 1) throw new IllegalArgumentException("limit must be 1 or more, not " + limit);
        }
    }

    /** One page of rows, and the offset the next page starts at: null when these are the last. */
    private record Rows(List<Map<String, Object>> rows, Long nextOffset) {
        /** From the page's rows plus one: that extra row is how a next page is known to exist. */
        static Rows of(List<Map<String, Object>> fetched, Page page) {
            return fetched.size() > page.limit()
                    ? new Rows(fetched.subList(0, page.limit()), page.offset() + page.limit())
                    : new Rows(fetched, null);
        }
    }

    /**
     * A page of a query written here, whose ORDER BY ends in rowid: that makes the order total,
     * so the pages of a table that is not changing neither overlap nor skip a row.
     */
    private Rows rows(String sql, Page page, Object... args) {
        Object[] all = Arrays.copyOf(args, args.length + 2);
        all[args.length] = page.limit() + 1L;
        all[args.length + 1] = page.offset();
        return Rows.of(jdbc.queryForList(sql + " LIMIT ? OFFSET ?", all), page);
    }

    /** One section of a paged report; named in {@code more} when it goes on past this page. */
    private void section(Map<String, Object> out, List<String> more, String name, String sql,
                         Page page, Object... args) {
        Rows r = rows(sql, page, args);
        out.put(name, r.rows());
        if (r.nextOffset() != null) more.add(name);
    }

    /** Which sections go on past this page, and the offset their next page starts at. */
    private static void putMore(Map<String, Object> out, List<String> more, Page page) {
        out.put("more", more);
        out.put("nextOffset", more.isEmpty() ? null : page.offset() + page.limit());
    }

    /**
     * Run one read-only SELECT and return a page of its rows. Guards, in order: single
     * statement; must start with SELECT or WITH; must not name a secret column or a dangerous
     * statement keyword; the statement runs on a connection SQLite itself holds read-only, so
     * one that would change the database is refused whatever its text; and finally every
     * returned column whose name holds a secret is redacted, which also catches
     * {@code SELECT * FROM users}. The statement runs as written and the page is taken from its
     * rows in the order it returns them, so the query's own ORDER BY and LIMIT mean what they say.
     */
    public Map<String, Object> query(String sql, Page page) {
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
        out.put("sql", trimmed);
        try {
            Rows result = Rows.of(readOnly(trimmed, page), page);
            boolean redacted = false;
            var safe = new ArrayList<Map<String, Object>>(result.rows().size());
            for (Map<String, Object> row : result.rows()) {
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
            out.put("offset", page.offset());
            out.put("limit", page.limit());
            out.put("rowCount", safe.size());
            out.put("nextOffset", result.nextOffset());
            if (redacted) out.put("redactedColumns", true);
            out.put("rows", safe);
        } catch (Exception e) {
            out.put("error", String.valueOf(e.getMessage()));
        }
        return out;
    }

    /**
     * The rows of {@code page}, plus one, from a statement run on a connection SQLite holds
     * read-only: {@code PRAGMA query_only} makes SQLite refuse anything that would change the
     * database, and it is switched off again before the connection goes back to the pool.
     */
    private List<Map<String, Object>> readOnly(String sql, Page page) {
        return jdbc.execute((ConnectionCallback<List<Map<String, Object>>>) con -> {
            try (Statement st = con.createStatement()) {
                st.execute("PRAGMA query_only = ON");
                try (ResultSet rs = st.executeQuery(sql)) {
                    var columns = new ColumnMapRowMapper();
                    var fetched = new ArrayList<Map<String, Object>>();
                    for (long row = 0; fetched.size() <= page.limit() && rs.next(); row++) {
                        if (row >= page.offset()) fetched.add(columns.mapRow(rs, fetched.size()));
                    }
                    return fetched;
                }
            } finally {
                try (Statement off = con.createStatement()) {
                    off.execute("PRAGMA query_only = OFF");
                }
            }
        });
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
     * <p>
     * Each dated section is paged, newest first, with the same page: {@code more} names the
     * sections that go on past it and {@code nextOffset} is where their next page starts.
     */
    public Map<String, Object> forensics(String userId, Page page) {
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

        var more = new ArrayList<String>();
        section(out, more, "conversations",
                "SELECT timestamp, session_id, role, content, tokens_used "
                        // DESC: this is an incident tool, and ORDER BY timestamp ASC with a LIMIT
                        // returned a user's OLDEST messages -- so the events, tool calls and memory
                        // blocks showed today while the conversation block showed their first ever
                        // exchanges, which reads as a conversation that stopped months ago.
                        + "FROM conversations WHERE user_id = ? ORDER BY timestamp DESC, rowid DESC",
                page, userId);
        section(out, more, "sessions",
                "SELECT id, title, preview, created_at, updated_at, archived "
                        + "FROM chat_sessions WHERE user_id = ? ORDER BY created_at DESC, rowid DESC",
                page, userId);
        section(out, more, "events",
                "SELECT timestamp, event_type, severity, task_id, summary, details, tokens_used "
                        + "FROM events WHERE user_id = ? ORDER BY timestamp DESC, rowid DESC",
                page, userId);
        section(out, more, "toolCalls",
                "SELECT created_at, tool_name, task_id, success, duration_ms, label, error "
                        + "FROM skill_usage WHERE user_id = ? ORDER BY created_at DESC, rowid DESC",
                page, userId);
        section(out, more, "memory",
                "SELECT created_at, memory_type, outcome, tags, content "
                        + "FROM agent_memory WHERE user_id = ? ORDER BY created_at DESC, rowid DESC",
                page, userId);
        section(out, more, "scheduledTasks",
                "SELECT id, task_type, status, description, cron_expression, next_run_at, last_run_at, "
                        + "run_count, last_result "
                        + "FROM scheduled_tasks WHERE user_id = ? ORDER BY created_at DESC, rowid DESC",
                page, userId);
        section(out, more, "scheduledRuns",
                "SELECT task_id, executed_at, status, skills_used, duration_ms, result, error "
                        + "FROM scheduled_task_runs WHERE user_id = ? ORDER BY executed_at DESC, rowid DESC",
                page, userId);
        section(out, more, "attachments",
                "SELECT id, original_name, content_type, size_bytes, uploaded_at "
                        + "FROM file_attachments WHERE user_id = ? ORDER BY uploaded_at DESC, rowid DESC",
                page, userId);
        section(out, more, "longRunningTasks",
                "SELECT task_id, description, skill_name, status, started_at, completed_at, result_summary "
                        + "FROM long_running_tasks WHERE user_id = ? ORDER BY started_at DESC, rowid DESC",
                page, userId);
        section(out, more, "tokenUsage",
                "SELECT date, provider, tokens_used, requests, cost_usd "
                        + "FROM token_usage WHERE user_id = ? ORDER BY date DESC, rowid DESC",
                page, userId);
        // Keys only — values stay in the vault.
        out.put("credentialKeys", jdbc.queryForList(
                "SELECT credential_key, created_at, updated_at FROM credential_vault WHERE user_id = ?",
                userId));
        out.put("credentialGrants", jdbc.queryForList(
                "SELECT skill_name, credential, grant_type, granted_at FROM credential_grants WHERE user_id = ?",
                userId));
        putMore(out, more, page);
        return out;
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

    /** The queue, and each listing paged like the forensics sections: {@code more} names those that go on. */
    public Map<String, Object> tasks(Page page) {
        var out = new LinkedHashMap<String, Object>();
        out.put("queue", Map.of("busy", taskQueue.isBusy(), "queued", taskQueue.getQueueSize()));
        var more = new ArrayList<String>();
        section(out, more, "recentTasks",
                "SELECT timestamp, user_id, task_id, event_type, severity, summary, details, tokens_used "
                        + "FROM events ORDER BY timestamp DESC, rowid DESC", page);
        section(out, more, "longRunning",
                "SELECT task_id, user_id, description, status, progress_pct, progress_msg, "
                        + "heartbeat_at, started_at, completed_at FROM long_running_tasks "
                        + "ORDER BY started_at DESC, rowid DESC", page);
        section(out, more, "upcomingScheduled",
                "SELECT id, user_id, task_type, status, description, next_run_at, run_count "
                        + "FROM scheduled_tasks WHERE status = 'active' ORDER BY next_run_at, rowid", page);
        putMore(out, more, page);
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
                "SELECT created_at, user_id, tool_name, success, duration_ms, label, error "
                        + "FROM skill_usage WHERE task_id = ? ORDER BY created_at", taskId));
        out.put("memory", jdbc.queryForList(
                "SELECT created_at, user_id, memory_type, outcome, content "
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
                // By id: timestamps have one-second resolution, and a step and its neighbours
                // written in the same second came back in any order.
                "SELECT details FROM events WHERE task_id = ? AND event_type IN ('step','attachment') "
                        + "ORDER BY id", taskId);
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
                        a.put("handle", "{{" + x.path("n").asInt() + "}}");
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

    /** The ledger, newest first, a page at a time: {@code nextOffset} is null on the last page. */
    public Map<String, Object> egress(Page page, String decision) {
        String sql = "SELECT timestamp, user_id, task_id, summary, details FROM events "
                + "WHERE event_type = 'egress'"
                + (decision == null || decision.isBlank() ? "" : " AND summary LIKE ?")
                + " ORDER BY timestamp DESC, rowid DESC";
        Rows rows = decision == null || decision.isBlank()
                ? rows(sql, page)
                : rows(sql, page, decision.trim().toUpperCase(java.util.Locale.ROOT) + " %");
        var out = new LinkedHashMap<String, Object>();
        out.put("rows", rows.rows());
        out.put("nextOffset", rows.nextOffset());
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
            if (!resp.isSuccessful()) throw new IOException("HTTP " + resp.code() + ": " + body);
            return mapper.readTree(body);
        }
    }

    private JsonNode postJson(String url, Object payload) throws IOException {
        RequestBody body = RequestBody.create(mapper.writeValueAsString(payload),
                okhttp3.MediaType.get("application/json"));
        Request req = new Request.Builder().url(url).post(body).build();
        try (Response resp = http.newCall(req).execute()) {
            String text = resp.body() == null ? "" : resp.body().string();
            if (!resp.isSuccessful()) throw new IOException("HTTP " + resp.code() + ": " + text);
            return mapper.readTree(text);
        }
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
            return Files.isRegularFile(p) ? sha256Hex(Files.readAllBytes(p)) : "";
        } catch (IOException e) {
            return "";
        }
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every Java platform", e);
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
