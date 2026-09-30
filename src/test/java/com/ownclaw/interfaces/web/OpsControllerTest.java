package com.ownclaw.interfaces.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownclaw.agent.AgentAction;
import com.ownclaw.agent.AgentLoop;
import com.ownclaw.agent.AgentObservation;
import com.ownclaw.agent.AgentResult;
import com.ownclaw.agent.AgentTrajectory;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.conversation.ConversationService;
import com.ownclaw.conversation.MigratedDatabase;
import com.ownclaw.core.TaskQueue;
import com.ownclaw.observability.OpsService;
import com.ownclaw.users.AuthService;
import com.ownclaw.users.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The ops API through its controller: a chat turn is saved as the web chat saves one, step
 * outputs come back whole, an async run is kept until it is collected however many were
 * started, and the listings page with the offset and limit the caller asked for.
 */
class OpsControllerTest {

    /** When the tests' clock starts. Not zero: a run's end time of 0 means it is still running. */
    static final long T0 = Instant.parse("2026-09-30T12:00:00Z").toEpochMilli();

    /** A day, written out: the tests pin the duration itself, not whatever a constant says. */
    static final long DAY = 24L * 60 * 60 * 1000;

    /** An agent loop that runs nothing: it answers as scripted and notes what it was handed. */
    static final class ScriptedLoop extends AgentLoop {
        final JdbcTemplate jdbc;
        final AtomicInteger calls = new AtomicInteger();
        final List<String> currentMessageIds = new CopyOnWriteArrayList<>();
        final List<Boolean> unattended = new CopyOnWriteArrayList<>();
        /** The row each call was told it answers, as the database had it when the call began. */
        final List<List<Object>> rowAtCall = new CopyOnWriteArrayList<>();
        volatile CountDownLatch hold = new CountDownLatch(0);
        /** Thrown instead of answering: a RuntimeException or an Error. */
        volatile Throwable failWith;

        ScriptedLoop(JdbcTemplate jdbc) {
            super(null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null);
            this.jdbc = jdbc;
        }

        @Override
        public AgentResult executeFull(String userId, String message, boolean unattended,
                                       String currentMessageId, List<String> attachmentIds) {
            this.unattended.add(unattended);
            currentMessageIds.add(String.valueOf(currentMessageId));
            if (currentMessageId != null) {
                var row = jdbc.queryForMap(
                        "SELECT role, content, session_id FROM conversations WHERE id = ?", currentMessageId);
                rowAtCall.add(List.of(row.get("role"), row.get("content"), row.get("session_id")));
            }
            try {
                assertTrue(hold.await(10, TimeUnit.SECONDS), "the test never let the run go");
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            if (failWith instanceof RuntimeException e) throw e;
            if (failWith instanceof Error e) throw e;
            var trajectory = new AgentTrajectory();
            trajectory.record(new AgentAction("probe", Map.of(), "look"),
                    new AgentObservation("probe", true, "o".repeat(5000) + "END", Map.of(), 3));
            return AgentResult.completed("Noted: " + message, trajectory, 5)
                    .withTaskId(String.format("%08x", calls.incrementAndGet()))
                    .withOwnerText("Privately noted: " + message);
        }
    }

    record Setup(JdbcTemplate jdbc, ScriptedLoop loop, ConversationService conversations, AtomicLong clock,
                 OpsController ops) {}

    /** The controller over a migrated database and the real OpsService, on a clock the test moves. */
    static Setup setup(Path tmp) throws Exception {
        return setup(tmp, jdbc -> new ConversationService(jdbc));
    }

    /** The same, with the conversation store the test gives it. */
    static Setup setup(Path tmp, Function<JdbcTemplate, ConversationService> store) throws Exception {
        var jdbc = MigratedDatabase.at(tmp.resolve("t.db"));
        jdbc.update("INSERT INTO users (id, display_name) VALUES ('u1', 'Owner')");
        var loop = new ScriptedLoop(jdbc);
        var conversations = store.apply(jdbc);
        var clock = new AtomicLong(T0);
        var auth = new AuthService(jdbc, new UserRepository(jdbc), new OwnClawConfig());
        // A queue that is never started: tasks() reads only its counters. No local model is probed.
        var service = new OpsService(new OwnClawConfig(), jdbc, null, null,
                new TaskQueue(null, null, null, new OwnClawConfig(), null),
                auth, null, new ObjectMapper(), null);
        return new Setup(jdbc, loop, conversations, clock,
                new OpsController(service, loop, auth, null, null, null, null, conversations, clock::get));
    }

    @Test
    @DisplayName("a chat turn: user row saved first, the task answers it, the answer saved as the web chat saves one")
    void aChatTurnIsSavedAsTheWebChatSavesOne(@TempDir Path tmp) throws Exception {
        var s = setup(tmp);
        String ownersChat = s.conversations().createSession("u1", "The owner's chat");

        Map<String, Object> first = body(s.ops().runAgent(
                Map.of("message", "remember 7", "userId", "u1", "sessionId", "new")));
        String chat = (String) first.get("sessionId");
        assertNotNull(chat, String.valueOf(first));
        assertNotEquals(ownersChat, chat);
        assertEquals("Ops check", title(s.jdbc(), chat));
        assertEquals(ownersChat, s.conversations().getCurrentSession("u1"),
                "the owner's open chat did not move: the web page files the next message there");
        assertEquals(List.of("user", "remember 7", chat), s.loop().rowAtCall.getFirst(),
                "the user row was in the chat before the task ran");
        assertEquals(List.of(false), s.loop().unattended, "a chat turn is attended");
        assertEquals("00000001", first.get("taskId"), "the task's own id, the one its chat row carries");

        @SuppressWarnings("unchecked")
        var steps = (List<Map<String, Object>>) first.get("steps");
        assertEquals("o".repeat(5000) + "END", steps.getFirst().get("output"), "whole");

        Map<String, Object> second = body(s.ops().runAgent(
                Map.of("message", "which number?", "userId", "u1", "sessionId", chat)));
        assertEquals(chat, second.get("sessionId"));

        var rows = s.jdbc().queryForList("SELECT id, role, content, private_content, metadata "
                + "FROM conversations WHERE session_id = ? ORDER BY rowid", chat);
        assertEquals(List.of("user", "assistant", "user", "assistant"),
                rows.stream().map(r -> r.get("role")).toList());
        assertEquals(List.of(rows.get(0).get("id"), rows.get(2).get("id")), s.loop().currentMessageIds,
                "each task was handed its own user row as the current message");
        assertEquals("Noted: remember 7", rows.get(1).get("content"));
        assertEquals("Privately noted: remember 7", rows.get(1).get("private_content"));
        assertEquals("{\"taskId\":\"00000001\"}", rows.get(1).get("metadata"));
        assertEquals("{\"taskId\":\"00000002\"}", rows.get(3).get("metadata"));

        // A chat still called "New Chat" is titled from the message, as the web chat titles it.
        String untitled = s.conversations().createSession("u1", "New Chat");
        body(s.ops().runAgent(Map.of("message", "plan the trip", "userId", "u1", "sessionId", untitled)));
        assertEquals("plan the trip", title(s.jdbc(), untitled));
    }

    @Test
    @DisplayName("a \"new\" chat is never made the open one, not even for a moment, and no chat is opened for an account with none open")
    void aNewChatIsNeverOpened(@TempDir Path tmp) throws Exception {
        var opened = new CopyOnWriteArrayList<String>();
        var s = setup(tmp, jdbc -> new ConversationService(jdbc) {
            @Override public void setActiveSession(String userId, String sessionId) {
                opened.add(sessionId);
                super.setActiveSession(userId, sessionId);
            }
        });

        String chat = (String) body(s.ops().runAgent(
                Map.of("message", "hi", "userId", "u1", "sessionId", "new"))).get("sessionId");

        assertEquals(List.of(), opened, "the web page files the owner's next message in the open chat");
        assertEquals(0, s.jdbc().queryForObject("SELECT COUNT(*) FROM active_session", Integer.class));
        assertEquals(List.of(chat), s.jdbc().queryForList("SELECT id FROM chat_sessions", String.class),
                "the ops check's chat alone: no empty chat was made to be the open one");
    }

    @Test
    @DisplayName("a chat turn runs asynchronously too: the user row first, the answer when it ends")
    void anAsyncChatTurn(@TempDir Path tmp) throws Exception {
        var s = setup(tmp);
        s.loop().hold = new CountDownLatch(1);

        ResponseEntity<?> started = s.ops().runAgent(
                Map.of("message", "remember 7", "userId", "u1", "sessionId", "new", "async", true));
        assertEquals(202, started.getStatusCode().value());
        String runId = (String) body(started).get("runId");
        String chat = (String) body(started).get("sessionId");
        assertNotNull(chat, String.valueOf(body(started)));
        assertEquals(List.of("user"), roles(s.jdbc(), chat));
        assertEquals("running", body(s.ops().asyncRunResult(runId)).get("status"));

        s.loop().hold.countDown();
        Map<String, Object> done = collect(s.ops(), runId);
        assertEquals("done", done.get("status"), String.valueOf(done));
        assertEquals(chat, done.get("sessionId"));
        assertEquals(List.of("user", "assistant"), roles(s.jdbc(), chat));
        assertEquals(404, s.ops().asyncRunResult(runId).getStatusCode().value(), "collected once, then gone");
    }

    @Test
    @DisplayName("what cannot be a chat turn is refused before anything is saved or run")
    void whatCannotBeAChatTurnIsRefused(@TempDir Path tmp) throws Exception {
        var s = setup(tmp);
        String othersChat = s.conversations().createSession("u2", "Someone else's chat");

        for (Map<String, Object> request : List.<Map<String, Object>>of(
                Map.of("message", "hi", "userId", "u1", "sessionId", "new", "unattended", true),
                Map.of("message", "hi", "userId", "u1", "sessionId", "no-such-chat"),
                Map.of("message", "hi", "userId", "u1", "sessionId", othersChat),
                Map.of("message", "hi", "userId", "nobody", "sessionId", "new"))) {
            assertEquals(400, s.ops().runAgent(request).getStatusCode().value(), request.toString());
        }
        assertEquals(List.of(), s.loop().currentMessageIds, "nothing ran");
        assertEquals(0, s.jdbc().queryForObject("SELECT COUNT(*) FROM conversations", Integer.class));
        assertEquals(1, s.jdbc().queryForObject("SELECT COUNT(*) FROM chat_sessions", Integer.class));
    }

    @Test
    @DisplayName("a chat turn whose task throws is answered as the web chat answers it, and the caller still hears of the failure")
    void aTurnWhoseTaskThrows(@TempDir Path tmp) throws Exception {
        var s = setup(tmp);
        s.loop().failWith = new IllegalStateException("boom");

        ResponseEntity<?> failed = s.ops().runAgent(Map.of("message", "hi", "userId", "u1", "sessionId", "new"));
        assertEquals(500, failed.getStatusCode().value());
        assertEquals("IllegalStateException: boom", body(failed).get("error"));
        String chat = (String) body(failed).get("sessionId");
        assertEquals(List.of("user", "assistant"), roles(s.jdbc(), chat));
        // What TaskQueue completes a thrown web-chat task with, and the web chat saves.
        assertEquals("Internal error: boom", contents(s.jdbc(), chat).getLast());

        String runId = (String) body(s.ops().runAgent(
                Map.of("message", "hi again", "userId", "u1", "sessionId", chat, "async", true))).get("runId");
        Map<String, Object> ended = collect(s.ops(), runId);
        assertEquals("failed", ended.get("status"));
        assertEquals(chat, ended.get("sessionId"));
        assertEquals(List.of("hi", "Internal error: boom", "hi again", "Internal error: boom"),
                contents(s.jdbc(), chat));
    }

    @Test
    @DisplayName("an async run that dies of an Error ends as failed, not running forever")
    void anErrorEndsTheRun(@TempDir Path tmp) throws Exception {
        var s = setup(tmp);
        s.loop().failWith = new LinkageError("broken class");

        Map<String, Object> ended = collect(s.ops(), startRuns(s.ops(), 1).getFirst());
        assertEquals("failed", ended.get("status"));
        assertEquals("LinkageError: broken class", ended.get("error"));
    }

    @Test
    @DisplayName("an async run is kept until it is collected, however many start after it; a day if never")
    void asyncRunsAreKeptUntilCollected(@TempDir Path tmp) throws Exception {
        var s = setup(tmp);
        List<String> ids = startRuns(s.ops(), 17);
        // One thread runs them in order, so once the last has ended every one before it has.
        assertEquals("done", collect(s.ops(), ids.getLast()).get("status"));
        for (int i = 0; i < 16; i++) {
            Map<String, Object> run = body(s.ops().asyncRunResult(ids.get(i)));
            assertEquals("done", run.get("status"), "run " + i + " of seventeen is still there");
            assertEquals("Noted: run " + i, run.get("response"));
            assertFalse(run.containsKey("sessionId"), "not a chat turn");
            assertEquals(404, s.ops().asyncRunResult(ids.get(i)).getStatusCode().value(),
                    "collected once, then gone");
        }

        // Two left uncollected, and a third waited for: once it has ended, so have they, at T0.
        List<String> uncollected = startRuns(s.ops(), 3);
        assertEquals("done", collect(s.ops(), uncollected.get(2)).get("status"));
        s.loop().hold = new CountDownLatch(1);
        String running = startRuns(s.ops(), 1).getFirst();

        s.clock().set(T0 + DAY);
        assertEquals("done", body(s.ops().asyncRunResult(uncollected.get(0))).get("status"),
                "a day after it ended: kept");
        s.clock().set(T0 + DAY + 1);
        assertEquals(404, s.ops().asyncRunResult(uncollected.get(1)).getStatusCode().value(),
                "past a day: dropped");
        assertEquals("running", body(s.ops().asyncRunResult(running)).get("status"), "never while it runs");
        s.loop().hold.countDown();
        assertEquals("done", collect(s.ops(), running).get("status"));
    }

    @Test
    @DisplayName("db/query, forensics, tasks and egress page through the controller with the offset and limit asked for")
    void listingsPageThroughTheController(@TempDir Path tmp) throws Exception {
        var s = setup(tmp);
        s.jdbc().batchUpdate("INSERT INTO events (user_id, task_id, event_type, severity, summary) "
                        + "VALUES ('u1', 'abcd1234', 'egress', 'info', ?)",
                IntStream.range(0, 1203).mapToObj(i -> new Object[]{"SENT " + i}).toList());
        String sql = "SELECT id FROM events ORDER BY id";

        Map<String, Object> page = body(s.ops().query(Map.of("sql", sql)));
        assertEquals(500, list(page, "rows").size(), "no limit in the body: the default page");
        assertEquals(500L, page.get("nextOffset"));
        Map<String, Object> big = body(s.ops().query(Map.of("sql", sql, "limit", 1000)));
        assertEquals(1000, list(big, "rows").size(), "past the default, as asked");
        assertEquals(1000L, big.get("nextOffset"));
        Map<String, Object> rest = body(s.ops().query(Map.of("sql", sql, "offset", 1000L, "limit", 1000)));
        assertEquals(IntStream.rangeClosed(1001, 1203).boxed().toList(),
                list(rest, "rows").stream().map(r -> ((Number) r.get("id")).intValue()).toList());
        assertNull(rest.get("nextOffset"));

        // Newest first, so from row 1200 each listing holds the three oldest.
        List<String> oldest = List.of("SENT 2", "SENT 1", "SENT 0");
        assertEquals(oldest, summaries(body(s.ops().forensics("u1", 1200, 1000)), "events"));
        assertEquals(oldest, summaries(body(s.ops().tasks(1200, 1000)), "recentTasks"));
        assertEquals(oldest, summaries(body(s.ops().egress(1200, 1000, null)), "rows"));
        assertEquals(1000, list(body(s.ops().egress(0, 1000, null)), "rows").size());
    }

    @Test
    @DisplayName("an offset, a limit or a /logs cursor that makes no sense is answered 400 with the reason")
    void whatMakesNoSenseIs400(@TempDir Path tmp) throws Exception {
        var s = setup(tmp);
        var ops = s.ops();
        Map<Supplier<ResponseEntity<?>>, String> refused = Map.of(
                () -> ops.query(Map.of("sql", "SELECT 1", "offset", -1)), "offset must be 0 or more, not -1",
                () -> ops.query(Map.of("sql", "SELECT 1", "limit", 0)), "limit must be 1 or more, not 0",
                () -> ops.query(Map.of("sql", "SELECT 1", "limit", 1.5)), "must be whole numbers",
                () -> ops.query(Map.of("sql", "SELECT 1", "limit", "all")), "must be whole numbers",
                () -> ops.forensics("u1", -1, 200), "offset must be 0 or more, not -1",
                () -> ops.tasks(0, 0), "limit must be 1 or more, not 0",
                () -> ops.egress(-1, 50, null), "offset must be 0 or more, not -1",
                () -> ops.logs(0, null, null, null), "lines must be 1 or more, not 0");
        for (var call : refused.entrySet()) {
            ResponseEntity<?> answer = answer(ops, call.getKey());
            assertEquals(400, answer.getStatusCode().value(), call.getValue());
            assertTrue(String.valueOf(body(answer).get("error")).contains(call.getValue()),
                    call.getValue() + " -> " + body(answer));
        }
        assertEquals(200, answer(ops, () -> ops.tasks(0, 1)).getStatusCode().value(), "a page that makes sense");

        // A cursor whose file retention has removed.
        Path log = tmp.resolve("ownclaw.log");
        Files.writeString(log, "one line\n");
        String before = System.getProperty("LOG_FILE");
        System.setProperty("LOG_FILE", log.toString());
        try {
            ResponseEntity<?> gone = answer(ops, () -> ops.logs(10, null, null, "0123456789abcdef:10"));
            assertEquals(400, gone.getStatusCode().value());
            assertTrue(String.valueOf(body(gone).get("error")).contains("not on disk"), String.valueOf(body(gone)));
        } finally {
            if (before == null) System.clearProperty("LOG_FILE");
            else System.setProperty("LOG_FILE", before);
        }
    }

    // ── helpers ──

    /**
     * What Spring MVC answers for a call to this controller: the handler's own response or, when
     * it throws, the response of the {@code @ExceptionHandler} Spring picks for the exception.
     */
    static ResponseEntity<?> answer(OpsController ops, Supplier<ResponseEntity<?>> call) throws Exception {
        try {
            return call.get();
        } catch (RuntimeException e) {
            Method handler = new ExceptionHandlerMethodResolver(OpsController.class).resolveMethod(e);
            if (handler == null) throw e;
            return (ResponseEntity<?>) handler.invoke(ops, e);
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> body(ResponseEntity<?> response) {
        return (Map<String, Object>) response.getBody();
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> list(Map<String, Object> result, String key) {
        assertNull(result.get("error"), String.valueOf(result.get("error")));
        return (List<Map<String, Object>>) result.get(key);
    }

    static List<Object> summaries(Map<String, Object> result, String key) {
        return list(result, key).stream().map(r -> r.get("summary")).toList();
    }

    /** Start {@code count} async runs, not chat turns, and return their ids. */
    static List<String> startRuns(OpsController ops, int count) {
        var ids = new ArrayList<String>();
        for (int i = 0; i < count; i++) {
            ids.add((String) body(ops.runAgent(Map.of("message", "run " + i, "userId", "u1", "async", true)))
                    .get("runId"));
        }
        return ids;
    }

    static List<Object> roles(JdbcTemplate jdbc, String chat) {
        return jdbc.queryForList("SELECT role FROM conversations WHERE session_id = ? ORDER BY rowid", chat)
                .stream().map(r -> r.get("role")).toList();
    }

    static List<String> contents(JdbcTemplate jdbc, String chat) {
        return jdbc.queryForList("SELECT content FROM conversations WHERE session_id = ? ORDER BY rowid",
                String.class, chat);
    }

    static String title(JdbcTemplate jdbc, String chat) {
        return jdbc.queryForObject("SELECT title FROM chat_sessions WHERE id = ?", String.class, chat);
    }

    /** Poll until the run has ended; the response that says so is its collection. */
    static Map<String, Object> collect(OpsController ops, String runId) throws InterruptedException {
        long until = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < until) {
            Map<String, Object> b = body(ops.asyncRunResult(runId));
            if (!"running".equals(b.get("status"))) return b;
            Thread.sleep(10);
        }
        return fail("run " + runId + " did not end");
    }
}
