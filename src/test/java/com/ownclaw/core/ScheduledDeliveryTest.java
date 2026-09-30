package com.ownclaw.core;

import com.ownclaw.agent.AgentResult;
import com.ownclaw.agent.AgentTrajectory;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.conversation.ConversationService;
import com.ownclaw.conversation.MigratedDatabase;
import com.ownclaw.interfaces.web.ScheduledTaskController;
import com.ownclaw.observability.ChatStatusEmitter;
import com.ownclaw.observability.ChatStatusEmitter.StatusMessage;
import com.ownclaw.observability.EventLogService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Scheduled runs through the real scheduler on the migrated schema: delivered into the pinned chat
 * of scheduled results, and kept, listed and reported whole. Only the task queue is replaced, by
 * one that hands back the result the test sets.
 */
class ScheduledDeliveryTest {

    static final String USER = "u1";

    /** Hands back {@link #result} for every run; runs nothing. */
    static final class Finished extends TaskQueue {
        AgentResult result;

        Finished() {
            super(null, null, null, new OwnClawConfig(), null);
        }

        @Override
        public CompletableFuture<AgentResult> submit(String userId, String message, int priority) {
            return CompletableFuture.completedFuture(result);
        }
    }

    private JdbcTemplate jdbc;
    private ConversationService conversations;
    private final Finished queue = new Finished();
    private ScheduledTaskService scheduler;
    private final List<StatusMessage> emitted = new ArrayList<>();

    private void start(Path tmp) throws Exception {
        jdbc = MigratedDatabase.at(tmp.resolve("t.db"));
        conversations = new ConversationService(jdbc, null);
        var emitter = new ChatStatusEmitter();
        emitter.subscribe(USER, "web", emitted::add);
        scheduler = new ScheduledTaskService(jdbc, queue, emitter, new EventLogService(jdbc), conversations,
                new ResultDelivery(conversations, emitter));
    }

    private long due(String description) {
        return scheduler.scheduleDeferred(USER, description, Instant.now().minusSeconds(60));
    }

    private StatusMessage result() {
        return emitted.stream().filter(m -> m.type() == StatusMessage.Type.RESULT).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("a scheduled result goes to the pinned chat, not the chat that happens to be open, and is kept whole")
    void intoThePinnedChat(@TempDir Path tmp) throws Exception {
        start(tmp);
        String open = conversations.createSession(USER, "Router trouble");
        String digest = "Morning digest.\n" + "- an item of news worth reading in full\n".repeat(200);
        queue.result = AgentResult.completed(digest, new AgentTrajectory(), 3);
        String description = "Every morning: collect the news from my feeds, " + "and more detail ".repeat(10);
        due(description);

        scheduler.pollDueTasks();

        String pinned = conversations.scheduledSession(USER);
        assertNotEquals(open, pinned);
        var saved = jdbc.queryForMap("SELECT session_id, content FROM conversations WHERE role = 'assistant'");
        assertEquals(pinned, saved.get("session_id"), "saved into the pinned chat");
        assertTrue(String.valueOf(saved.get("content")).startsWith("**Scheduled task: " + description + "**"),
                "the header names the whole task: " + saved);
        assertEquals(pinned, result().data().get("sessionId"), "the page is told which chat it is for");
        assertEquals(open, conversations.getCurrentSession(USER), "the open chat stays open");
        assertEquals(digest, jdbc.queryForObject("SELECT last_result FROM scheduled_tasks", String.class),
                "last_result is the whole result, not a summary of its start");
    }

    @Test
    @DisplayName("a run that did not finish is delivered whole, with the owner's private text beside it")
    void aFailureIsDeliveredWhole(@TempDir Path tmp) throws Exception {
        start(tmp);
        String ending = "Stopped: the step limit.\n" + "- step that ran and what it found\n".repeat(40);
        String secret = "Closing balance 48,213.07 CZK";
        queue.result = AgentResult.maxSteps(ending, new AgentTrajectory(), 30)
                .withOwnerText(ending + "\nPrivate: " + secret);
        due("summarise my statement");
        conversations.createSession(USER, "Something else, open");

        scheduler.pollDueTasks();

        var row = jdbc.queryForMap(
                "SELECT session_id, content, private_content FROM conversations WHERE role = 'assistant'");
        assertEquals(conversations.scheduledSession(USER), row.get("session_id"), "into the pinned chat, too");
        String content = String.valueOf(row.get("content"));
        assertTrue(content.contains(ending), "the whole ending, not its first 400 characters: " + content.length());
        assertFalse(content.contains(secret), "content feeds later prompts");
        assertTrue(String.valueOf(row.get("private_content")).contains(secret),
                "the owner's chat gets the private text of a failed run too");
        String lastError = jdbc.queryForObject("SELECT last_error FROM scheduled_tasks", String.class);
        assertTrue(lastError.endsWith(ending), "last_error whole: " + lastError.length());
        String logged = jdbc.queryForObject(
                "SELECT summary FROM events WHERE event_type = 'scheduled.failed'", String.class);
        assertTrue(logged.endsWith(ending), "the event says what it said, whole");
        assertTrue(emitted.stream().anyMatch(m -> m.type() == StatusMessage.Type.FAILED && m.text().endsWith(ending)),
                "and so does the status line");
    }

    @Test
    @DisplayName("the schedule list shows every task, each instruction whole, and there is no cap on how many")
    void everyTaskListedWhole(@TempDir Path tmp) throws Exception {
        start(tmp);
        String longOne = "Check the three backup targets and tell me which one failed, " + "with details ".repeat(20);
        for (int i = 0; i < 60; i++) {
            scheduler.scheduleDeferred(USER, i == 59 ? longOne : "task number " + i,
                    Instant.now().plusSeconds(3600 + i));
        }

        String list = scheduler.formatTasksSummary(USER);

        for (int i = 0; i < 59; i++) assertTrue(list.contains("task number " + i + " "), "task " + i);
        assertTrue(list.contains(longOne), "the 60th, whole");
        assertTrue(emitted.stream().anyMatch(m -> m.text().endsWith(longOne)), "its status line names it whole");
    }

    @Test
    @DisplayName("a task's runs are all reachable: every run, or a page of them")
    void runsArePaged(@TempDir Path tmp) throws Exception {
        start(tmp);
        queue.result = AgentResult.completed("ok", new AgentTrajectory(), 1);
        long id = scheduler.scheduleRecurring(USER, "ping", "0 0 3 * * *", null);
        for (int i = 0; i < 25; i++) {
            jdbc.update("UPDATE scheduled_tasks SET next_run_at = ?, status = 'active' WHERE id = ?",
                    Instant.now().minusSeconds(60).toString(), id);
            scheduler.pollDueTasks();
        }
        var controller = new ScheduledTaskController(scheduler);
        HttpServletRequest request = (HttpServletRequest) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{HttpServletRequest.class},
                (p, m, args) -> "getAttribute".equals(m.getName()) && "userId".equals(args[0]) ? USER : null);

        assertEquals(25, runs(controller.getTask(request, id, null, 0).getBody()).size(),
                "every run, where it used to be the newest 20");
        assertEquals(10, runs(controller.getTask(request, id, 10L, 10).getBody()).size());
        assertEquals(5, runs(controller.getTask(request, id, 10L, 20).getBody()).size(), "the last page");
        // Two hundred more, of another task, for the history of all of them.
        for (int i = 0; i < 200; i++) {
            jdbc.update("INSERT INTO scheduled_task_runs (task_id, user_id, description, task_type, status) "
                    + "VALUES (?, ?, 'older', 'deferred', 'completed')", id + 1, USER);
        }
        assertEquals(225, runs(controller.getRunHistory(request, null, 0).getBody()).size(), "every run");
        assertEquals(225, runs(controller.getRunHistory(request, 1000L, 0).getBody()).size(),
                "a page asked for is not lowered: it was cut at 200");
        assertEquals(25, runs(controller.getRunHistory(request, 50L, 200).getBody()).size(), "the last page");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> runs(Object body) {
        return (List<Map<String, Object>>) ((Map<String, Object>) body).get("runs");
    }
}
