package com.ownclaw.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.conversation.ConversationService;
import com.ownclaw.conversation.MigratedDatabase;
import com.ownclaw.core.TaskQueue;
import com.ownclaw.users.AuthService;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.stream.IntStream;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The ops API is read by sessions whose model runs in the cloud, so the owner's private answers
 * are redacted from its SQL results and may not be named in its SQL at all. Everything else it
 * holds is reachable whole: listings are paged, never cut, and no column is shortened.
 */
class OpsServiceTest {

    static final String SECRET = "Closing balance 48,213.07 CZK";
    static final OpsService.Page FIRST_500 = new OpsService.Page(0, 500);

    /** A queue that is never started: tasks() reads only its counters. */
    static OpsService opsOn(JdbcTemplate jdbc) {
        return new OpsService(new OwnClawConfig(), jdbc, null, null,
                new TaskQueue(null, null, null, new OwnClawConfig(), null),
                new AuthService(jdbc, null, new OwnClawConfig()), null, new ObjectMapper());
    }

    @Test
    @DisplayName("the conversations table is refused whole: no renaming reaches the private text")
    void privateContentNeverLeavesThroughOps(@TempDir Path tmp) throws Exception {
        var jdbc = MigratedDatabase.at(tmp.resolve("t.db"));
        var conversations = new ConversationService(jdbc, null);
        String session = conversations.createSession("u1", "Statements");
        conversations.saveMessage("u1", session, "assistant", "[Private answer]", List.of(), "a1b2c3d4", SECRET);
        var ops = opsOn(jdbc);

        // Guarding the column was not enough: a CTE column list renames it without naming it.
        for (String sql : List.of("SELECT * FROM conversations",
                "SELECT private_content FROM conversations",
                "WITH c(a,b,c,d,e,f,g,h,i,j,k) AS (SELECT * FROM conversations) SELECT k FROM c",
                "SELECT * FROM main.\"Conversations\"",
                "SELECT x FROM (SELECT 1 AS x UNION SELECT * FROM [conversations])",
                "SELECT original_name FROM file_attachments")) {
            Map<String, Object> refused = ops.query(sql, FIRST_500);
            assertTrue(String.valueOf(refused.get("error")).contains("forbidden identifier"), sql + " -> " + refused);
        }
        // The search index holds only the safe text, and stays readable.
        Map<String, Object> fts = ops.query("SELECT content FROM conversations_fts", FIRST_500);
        assertNull(fts.get("error"), String.valueOf(fts));
        assertFalse(String.valueOf(fts).contains("48,213.07"), String.valueOf(fts));
    }

    @Test
    @DisplayName("paging kept every guard: one SELECT only, and secret columns come back redacted")
    void theGuardsStand(@TempDir Path tmp) throws Exception {
        var jdbc = MigratedDatabase.at(tmp.resolve("t.db"));
        jdbc.update("INSERT INTO system_settings (key, value) VALUES ('jwt_secret', 'not-for-the-cloud')");
        var ops = opsOn(jdbc);

        assertEquals("Only a single statement is allowed",
                ops.query("SELECT 1; SELECT 2", FIRST_500).get("error"));
        assertEquals("Only SELECT (or WITH ... SELECT) is allowed",
                ops.query("DELETE FROM events", FIRST_500).get("error"));
        Map<String, Object> settings = ops.query("SELECT key, value FROM system_settings", FIRST_500);
        assertEquals(true, settings.get("redactedColumns"), String.valueOf(settings));
        assertFalse(String.valueOf(settings).contains("not-for-the-cloud"), String.valueOf(settings));
    }

    @Test
    @DisplayName("db/query, tasks and egress: any limit, and pages that reach every row once")
    void pagesReachEveryRow(@TempDir Path tmp) throws Exception {
        var jdbc = MigratedDatabase.at(tmp.resolve("t.db"));
        insertEvents(jdbc, 1203, i -> "SENT " + i);
        var ops = opsOn(jdbc);
        String sql = "SELECT id FROM events ORDER BY id";

        // More than the 500 rows that used to be the most any listing returned.
        Map<String, Object> big = ops.query(sql, new OpsService.Page(0, 1000));
        assertEquals(1000, rows(big).size());
        assertEquals(1000L, big.get("nextOffset"));
        assertEquals(sql, big.get("sql"), "run as written: nothing appended");
        assertEquals(1203, list(ops.egress(new OpsService.Page(0, 1203), null), "rows").size());
        assertEquals(700, list(ops.tasks(new OpsService.Page(0, 700)), "recentTasks").size());

        var ids = new ArrayList<Integer>();
        Long offset = 0L;
        for (int pages = 1; offset != null; pages++) {
            assertTrue(pages <= 5, "1,203 rows are five pages of 250, not more");
            Map<String, Object> page = ops.query(sql, new OpsService.Page(offset, 250));
            rows(page).forEach(r -> ids.add(((Number) r.get("id")).intValue()));
            offset = (Long) page.get("nextOffset");
        }
        assertEquals(IntStream.rangeClosed(1, 1203).boxed().toList(), ids);

        // A page that ends on the last row says so; the query's own LIMIT means what it says.
        assertNull(ops.query(sql, new OpsService.Page(0, 1203)).get("nextOffset"));
        Map<String, Object> own = ops.query(sql + " LIMIT 3", new OpsService.Page(0, 2));
        assertEquals(2, rows(own).size());
        assertEquals(2L, own.get("nextOffset"));
        Map<String, Object> rest = ops.query(sql + " LIMIT 3", new OpsService.Page(2, 2));
        assertEquals(List.of(3), rows(rest).stream().map(r -> ((Number) r.get("id")).intValue()).toList());
        assertNull(rest.get("nextOffset"));
    }

    @Test
    @DisplayName("a page is refused, not bent, when its offset or limit makes no sense")
    void aPageOutOfRangeIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new OpsService.Page(-1, 10));
        assertThrows(IllegalArgumentException.class, () -> new OpsService.Page(0, 0));
    }

    @Test
    @DisplayName("forensics: every section paged with the same page, and no column cut")
    void forensicsPagesAndCutsNothing(@TempDir Path tmp) throws Exception {
        var jdbc = MigratedDatabase.at(tmp.resolve("t.db"));
        jdbc.update("INSERT INTO users (id, display_name) VALUES ('u1', 'someone')");
        String longText = "x".repeat(5000) + "END";
        for (int i = 0; i < 3; i++) {
            jdbc.update("INSERT INTO skill_usage (tool_name, user_id, task_id, success, label, error) "
                    + "VALUES ('probe', 'u1', 't" + i + "', 0, 'PUBLIC', ?)", longText + i);
        }
        var conversations = new ConversationService(jdbc, null);
        String session = conversations.createSession("u1", "A chat");
        conversations.saveMessage("u1", session, "user", longText);
        jdbc.update("UPDATE chat_sessions SET preview = ? WHERE id = ?", longText, session);
        jdbc.update("INSERT INTO agent_memory (user_id, memory_type, content) VALUES ('u1', 'episode', ?)", longText);
        jdbc.update("INSERT INTO scheduled_tasks (user_id, description, next_run_at, last_result) "
                + "VALUES ('u1', 'daily', '2026-10-01T06:00:00Z', ?)", longText);
        jdbc.update("INSERT INTO scheduled_task_runs (task_id, user_id, description, task_type, status, result, error) "
                + "VALUES (1, 'u1', 'daily', 'recurring', 'failed', ?, ?)", longText, longText);
        jdbc.update("INSERT INTO long_running_tasks (task_id, user_id, description, result_summary) "
                + "VALUES ('lr1', 'u1', 'long', ?)", longText);
        var ops = opsOn(jdbc);

        Map<String, Object> first = ops.forensics("u1", new OpsService.Page(0, 2));
        assertEquals(2, list(first, "toolCalls").size());
        assertEquals(List.of("toolCalls"), first.get("more"));
        assertEquals(2L, first.get("nextOffset"));
        assertEquals(longText + "2", list(first, "toolCalls").getFirst().get("error"), "newest first, whole");
        assertEquals(longText, list(first, "conversations").getFirst().get("content"));
        assertEquals(longText, list(first, "sessions").getFirst().get("preview"));
        assertEquals(longText, list(first, "memory").getFirst().get("content"));
        assertEquals(longText, list(first, "scheduledTasks").getFirst().get("last_result"));
        assertEquals(longText, list(first, "scheduledRuns").getFirst().get("result"));
        assertEquals(longText, list(first, "scheduledRuns").getFirst().get("error"));
        assertEquals(longText, list(first, "longRunningTasks").getFirst().get("result_summary"));

        Map<String, Object> second = ops.forensics("u1", new OpsService.Page(2, 2));
        assertEquals(List.of(longText + "0"),
                list(second, "toolCalls").stream().map(r -> r.get("error")).toList());
        assertEquals(List.of(), second.get("more"));
        assertNull(second.get("nextOffset"));
        assertTrue(list(second, "conversations").isEmpty(), "past its last row, a section is empty");
    }

    @Test
    @DisplayName("tasks and the egress ledger page newest first to their last row; the task view cuts nothing")
    void tasksAndEgressPage(@TempDir Path tmp) throws Exception {
        var jdbc = MigratedDatabase.at(tmp.resolve("t.db"));
        insertEvents(jdbc, 7, i -> (i % 2 == 0 ? "SENT " : "REFUSED ") + i);
        var ops = opsOn(jdbc);

        Map<String, Object> ledger = ops.egress(new OpsService.Page(0, 5), null);
        assertEquals(5, list(ledger, "rows").size());
        assertEquals(5L, ledger.get("nextOffset"));
        Map<String, Object> end = ops.egress(new OpsService.Page(5, 5), null);
        assertEquals(List.of("REFUSED 1", "SENT 0"),
                list(end, "rows").stream().map(r -> r.get("summary")).toList());
        assertNull(end.get("nextOffset"));
        Map<String, Object> sent = ops.egress(new OpsService.Page(0, 3), "sent");
        assertEquals(List.of("SENT 6", "SENT 4", "SENT 2"),
                list(sent, "rows").stream().map(r -> r.get("summary")).toList());
        assertEquals(3L, sent.get("nextOffset"));

        Map<String, Object> tasks = ops.tasks(new OpsService.Page(4, 4));
        assertEquals(List.of("SENT 2", "REFUSED 1", "SENT 0"),
                list(tasks, "recentTasks").stream().map(r -> r.get("summary")).toList());
        assertEquals(List.of(), tasks.get("more"));
        assertNull(tasks.get("nextOffset"));
        assertEquals(List.of("recentTasks"), ops.tasks(new OpsService.Page(0, 4)).get("more"));

        String longText = "y".repeat(6000) + "END";
        jdbc.update("INSERT INTO skill_usage (tool_name, user_id, task_id, success, label, error) "
                + "VALUES ('probe', 'u1', 'abcd1234', 0, 'PRIVATE', ?)", longText);
        jdbc.update("INSERT INTO agent_memory (user_id, task_id, memory_type, content) "
                + "VALUES ('u1', 'abcd1234', 'episode', ?)", longText);
        Map<String, Object> task = ops.task("abcd1234");
        assertEquals(longText, list(task, "toolCalls").getFirst().get("error"));
        assertEquals(longText, list(task, "memory").getFirst().get("content"));
    }

    @Test
    @DisplayName("/logs reads the current file and every rolled one, a cursor at a time, to the first line")
    void logsReachEveryLine(@TempDir Path tmp) throws Exception {
        Path current = tmp.resolve("ownclaw.log");
        // Past the 4 MB that was the only part of the current file ever read.
        String pad = " " + "p".repeat(1500);
        writeLines(current, "cur", 3000, pad);
        gzipLines(tmp.resolve("ownclaw.log.2026-09-29.0.gz"), "mid", 3000, -3_600_000);
        gzipLines(tmp.resolve("ownclaw.log.2026-09-28.0.gz"), "old", 3000, -7_200_000);
        // logback's rename in progress: its lines reach the archive once that is written.
        Files.writeString(tmp.resolve("ownclaw.log.2026-09-29.1123456789.tmp"), "tmp-0000\n");
        var ops = opsOn(null);

        withLogFile(current, () -> {
            // More than the 2,000 lines that used to be the most one call returned.
            Map<String, Object> first = ops.logs(2500, null, null, null);
            List<String> firstLines = strings(first, "lines");
            assertEquals(2500, firstLines.size());
            assertEquals("cur-0500" + pad, firstLines.getFirst(), "oldest first within a page");
            assertEquals("cur-2999" + pad, firstLines.getLast());
            assertEquals(3, ((List<?>) first.get("files")).size(), "the current file and two rolled ones");

            // Page back to the first line of the oldest file.
            var all = new ArrayList<String>();
            String cursor = null;
            int pages = 0;
            do {
                assertTrue(++pages <= 10, "a cursor that does not move on");
                Map<String, Object> page = ops.logs(1000, null, null, cursor);
                all.addAll(0, strings(page, "lines"));
                cursor = (String) page.get("next");
            } while (cursor != null);
            var expected = new ArrayList<String>();
            for (String prefix : List.of("old", "mid", "cur")) {
                for (int i = 0; i < 3000; i++) {
                    expected.add(String.format("%s-%04d", prefix, i) + (prefix.equals("cur") ? pad : ""));
                }
            }
            assertEquals(expected, all, "every line, once, in order, and nothing from the .tmp");
            assertEquals(10, pages, "nine full pages, then the empty one that has reached the start");

            // grep runs across the rolled files too.
            Map<String, Object> grep = ops.logs(200, "MID-12", null, null);
            assertEquals(IntStream.range(1200, 1300).mapToObj(i -> "mid-" + i).toList(), strings(grep, "lines"));
            assertNull(grep.get("next"), "not a full page: the oldest file was read");
        });
    }

    @Test
    @DisplayName("a /logs cursor follows its lines when the current file rolls over between pages")
    void aCursorSurvivesRollover(@TempDir Path tmp) throws Exception {
        Path current = tmp.resolve("ownclaw.log");
        writeLines(current, "cur", 50, "");
        gzipLines(tmp.resolve("ownclaw.log.2026-09-29.0.gz"), "mid", 5, -3_600_000);
        // Not a gzip file: named as unreadable, and the pages go on past it.
        Path broken = tmp.resolve("ownclaw.log.2026-09-28.0.gz");
        Files.writeString(broken, "not gzip");
        Files.setLastModifiedTime(broken, FileTime.fromMillis(System.currentTimeMillis() - 7_200_000));
        var ops = opsOn(null);

        withLogFile(current, () -> {
            Map<String, Object> newest = ops.logs(10, null, null, null);
            assertEquals("cur-0040", strings(newest, "lines").getFirst());
            String cursor = (String) newest.get("next");

            // Roll over: the current file's lines move to a new archive, and a new file starts.
            gzipLines(tmp.resolve("ownclaw.log.2026-09-30.0.gz"), "cur", 50, 0);
            writeLines(current, "new", 3, "");

            Map<String, Object> next = ops.logs(50, null, null, cursor);
            var expected = new ArrayList<String>();
            IntStream.range(0, 5).forEach(i -> expected.add(String.format("mid-%04d", i)));
            IntStream.range(0, 40).forEach(i -> expected.add(String.format("cur-%04d", i)));
            assertEquals(expected, strings(next, "lines"));
            assertNull(next.get("next"));
            assertEquals(List.of("ownclaw.log.2026-09-28.0.gz"),
                    List.copyOf(((Map<?, ?>) next.get("unreadable")).keySet()));

            // Refused, not guessed: a cursor this endpoint did not issue, or whose file is gone.
            assertThrows(IllegalArgumentException.class, () -> ops.logs(10, null, null, "cur-0040"));
            assertThrows(IllegalArgumentException.class,
                    () -> ops.logs(10, null, null, "0123456789abcdef:10"));
            assertThrows(IllegalArgumentException.class, () -> ops.logs(0, null, null, null));
        });
    }

    @Test
    @DisplayName("the local-model probe shows the model's reply and Ollama's errors whole")
    void theProbeCutsNothing() {
        String tagsError = "t".repeat(1000) + " tags-end";
        String showError = "s".repeat(1000) + " show-end";
        String reply = "r".repeat(1000) + " reply-end";
        var mapper = new ObjectMapper();
        Interceptor ollama = chain -> {
            String path = chain.request().url().encodedPath();
            int code = 200;
            String body;
            switch (path) {
                case "/api/tags" -> { code = 500; body = tagsError; }
                case "/api/ps" -> body = "{\"models\":[]}";
                case "/api/show" -> { code = 500; body = showError; }
                case "/api/chat" -> body = mapper.writeValueAsString(Map.of(
                        "message", Map.of("content", reply), "prompt_eval_count", 30, "done_reason", "stop"));
                default -> throw new IOException("not an Ollama call: " + path);
            }
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(code).message("fake")
                    .body(ResponseBody.create(body, MediaType.get("application/json"))).build();
        };
        var config = new OwnClawConfig();
        config.getExecutor().setUrl("http://ollama.example.org");
        var ops = new OpsService(config, null, null, null, null, null, null, mapper,
                new OkHttpClient.Builder().addInterceptor(ollama).build());

        Map<String, Object> probe = ops.ollama();
        assertEquals("HTTP 500: " + tagsError, probe.get("error"));
        assertEquals("HTTP 500: " + showError, probe.get("modelInfoError"));
        assertEquals(reply, ((Map<?, ?>) probe.get("chatRoundTrip")).get("reply"));
    }

    // ── helpers ──

    interface Body { void run() throws Exception; }

    /** OpsService reads the log's path from this property, the way Spring Boot passes it on. */
    static void withLogFile(Path file, Body body) throws Exception {
        String before = System.getProperty("LOG_FILE");
        System.setProperty("LOG_FILE", file.toString());
        try {
            body.run();
        } finally {
            if (before == null) System.clearProperty("LOG_FILE");
            else System.setProperty("LOG_FILE", before);
        }
    }

    static void writeLines(Path file, String prefix, int count, String pad) throws IOException {
        var sb = new StringBuilder();
        for (int i = 0; i < count; i++) sb.append(String.format("%s-%04d", prefix, i)).append(pad).append('\n');
        Files.writeString(file, sb);
    }

    /** A rolled file as logback leaves it: gzip-compressed, written {@code ageMs} from now. */
    static void gzipLines(Path file, String prefix, int count, long ageMs) throws IOException {
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(file))) {
            for (int i = 0; i < count; i++) {
                out.write((String.format("%s-%04d", prefix, i) + "\n").getBytes(StandardCharsets.UTF_8));
            }
        }
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + ageMs));
    }

    static void insertEvents(JdbcTemplate jdbc, int count, IntFunction<String> summary) {
        jdbc.batchUpdate("INSERT INTO events (user_id, task_id, event_type, severity, summary) "
                        + "VALUES ('u1', 'abcd1234', 'egress', 'info', ?)",
                IntStream.range(0, count).mapToObj(i -> new Object[]{summary.apply(i)}).toList());
    }

    static List<Map<String, Object>> rows(Map<String, Object> result) {
        return list(result, "rows");
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> list(Map<String, Object> result, String key) {
        assertNull(result.get("error"), String.valueOf(result.get("error")));
        return (List<Map<String, Object>>) result.get(key);
    }

    @SuppressWarnings("unchecked")
    static List<String> strings(Map<String, Object> result, String key) {
        assertNull(result.get("error"), String.valueOf(result.get("error")));
        return (List<String>) result.get(key);
    }
}
