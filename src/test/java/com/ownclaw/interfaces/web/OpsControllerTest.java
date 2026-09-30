package com.ownclaw.interfaces.web;

import com.ownclaw.agent.AgentAction;
import com.ownclaw.agent.AgentLoop;
import com.ownclaw.agent.AgentObservation;
import com.ownclaw.agent.AgentResult;
import com.ownclaw.agent.AgentTrajectory;
import com.ownclaw.conversation.ConversationService;
import com.ownclaw.conversation.MigratedDatabase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * POST /api/ops/agent/run: a chat turn is saved as the web chat saves one, step outputs come
 * back whole, and an async run is kept until it is collected, however many were started.
 */
class OpsControllerTest {

    /** An agent loop that runs nothing: it answers as scripted and notes what it was handed. */
    static final class ScriptedLoop extends AgentLoop {
        final JdbcTemplate jdbc;
        final AtomicInteger calls = new AtomicInteger();
        final List<String> currentMessageIds = new CopyOnWriteArrayList<>();
        final List<Boolean> unattended = new CopyOnWriteArrayList<>();
        /** The row each call was told it answers, as the database had it when the call began. */
        final List<List<Object>> rowAtCall = new CopyOnWriteArrayList<>();
        volatile CountDownLatch hold = new CountDownLatch(0);
        volatile RuntimeException failWith;

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
            if (failWith != null) throw failWith;
            var trajectory = new AgentTrajectory();
            trajectory.record(new AgentAction("probe", Map.of(), "look"),
                    new AgentObservation("probe", true, "o".repeat(5000) + "END", Map.of(), 3));
            return AgentResult.completed("Noted: " + message, trajectory, 5)
                    .withTaskId(String.format("%08x", calls.incrementAndGet()))
                    .withOwnerText("Privately noted: " + message);
        }
    }

    record Setup(JdbcTemplate jdbc, ScriptedLoop loop, ConversationService conversations, OpsController ops) {}

    static Setup setup(Path tmp) throws Exception {
        var jdbc = MigratedDatabase.at(tmp.resolve("t.db"));
        var loop = new ScriptedLoop(jdbc);
        var conversations = new ConversationService(jdbc, null);
        return new Setup(jdbc, loop, conversations,
                new OpsController(null, loop, null, null, null, null, null, conversations));
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
        assertEquals("Ops check",
                s.jdbc().queryForObject("SELECT title FROM chat_sessions WHERE id = ?", String.class, chat));
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
                Map.of("message", "hi", "userId", "u1", "sessionId", othersChat))) {
            assertEquals(400, s.ops().runAgent(request).getStatusCode().value(), request.toString());
        }
        assertEquals(List.of(), s.loop().currentMessageIds, "nothing ran");
        assertEquals(0, s.jdbc().queryForObject("SELECT COUNT(*) FROM conversations", Integer.class));
        assertEquals(1, s.jdbc().queryForObject("SELECT COUNT(*) FROM chat_sessions", Integer.class));

        // A task that throws: the answer is missing, and the response says which chat to look at.
        s.loop().failWith = new IllegalStateException("boom");
        ResponseEntity<?> failed = s.ops().runAgent(Map.of("message", "hi", "userId", "u1", "sessionId", "new"));
        assertEquals(500, failed.getStatusCode().value());
        assertEquals(List.of("user"), roles(s.jdbc(), (String) body(failed).get("sessionId")));
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

        // Two left uncollected, and a third waited for: once it has ended, so have they.
        List<String> uncollected = startRuns(s.ops(), 3);
        assertEquals("done", collect(s.ops(), uncollected.get(2)).get("status"));
        s.loop().hold = new CountDownLatch(1);
        String running = startRuns(s.ops(), 1).getFirst();
        long now = System.currentTimeMillis();
        long day = OpsController.UNCOLLECTED_KEPT.toMillis();
        s.ops().dropUncollected(now + day - 60_000);
        assertEquals("done", body(s.ops().asyncRunResult(uncollected.get(0))).get("status"), "under a day: kept");
        s.ops().dropUncollected(now + day + 60_000);
        assertEquals(404, s.ops().asyncRunResult(uncollected.get(1)).getStatusCode().value(),
                "a day after it ended");
        assertEquals("running", body(s.ops().asyncRunResult(running)).get("status"), "never while it runs");
        s.loop().hold.countDown();
        assertEquals("done", collect(s.ops(), running).get("status"));
    }

    // ── helpers ──

    @SuppressWarnings("unchecked")
    static Map<String, Object> body(ResponseEntity<?> response) {
        return (Map<String, Object>) response.getBody();
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
