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
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Scheduled runs through the real scheduler on the migrated schema: delivered into the pinned chat
 * of scheduled results, and kept, listed and reported whole -- on every way a run ends, deferred or
 * recurring. Only the task queue is replaced, by one that hands back the result the test sets.
 */
class ScheduledDeliveryTest {

    static final String USER = "u1";

    /** Hands back {@link #result} for every run; runs nothing. */
    static final class Finished extends TaskQueue {
        AgentResult result;
        /** What happens while the "task" runs, before its result comes back. */
        Runnable duringRun = () -> {};

        Finished() {
            super(null, null, null, new OwnClawConfig(), null);
        }

        @Override
        public CompletableFuture<AgentResult> submit(String userId, String message, int priority) {
            duringRun.run();
            return CompletableFuture.completedFuture(result);
        }
    }

    /** Longer than any cut the scheduler ever made of a description (60 and 80 characters). */
    static final String LONG_TASK = "Every morning: collect the news from my feeds, " + "and more detail ".repeat(10);

    private JdbcTemplate jdbc;
    private ConversationService conversations;
    /** The conversation store the scheduler is given; a test may hand it one that fails. */
    private Function<JdbcTemplate, ConversationService> store = db -> new ConversationService(db, null);
    private final Finished queue = new Finished();
    private ScheduledTaskService scheduler;
    private final List<StatusMessage> emitted = new ArrayList<>();

    private void start(Path tmp) throws Exception {
        jdbc = MigratedDatabase.at(tmp.resolve("t.db"));
        conversations = store.apply(jdbc);
        var emitter = new ChatStatusEmitter();
        emitter.subscribe(USER, "web", emitted::add);
        scheduler = new ScheduledTaskService(jdbc, queue, emitter, new EventLogService(jdbc), conversations,
                new ResultDelivery(conversations, emitter));
    }

    private long due(String description) {
        return scheduler.scheduleDeferred(USER, description, Instant.now().minusSeconds(60));
    }

    /** A recurring task, made due now. */
    private long dueRecurring(String description, Integer maxRuns) {
        long id = scheduler.scheduleRecurring(USER, description, "0 0 3 * * *", maxRuns);
        jdbc.update("UPDATE scheduled_tasks SET next_run_at = ? WHERE id = ?",
                Instant.now().minusSeconds(60).toString(), id);
        return id;
    }

    private StatusMessage result() {
        return emitted.stream().filter(m -> m.type() == StatusMessage.Type.RESULT).findFirst().orElseThrow();
    }

    /** The texts of the status lines of one type, in order. */
    private List<String> lines(StatusMessage.Type type) {
        return emitted.stream().filter(m -> m.type() == type).map(StatusMessage::text).toList();
    }

    private Map<String, Object> task(long id) {
        return jdbc.queryForMap("SELECT status, run_count, last_result, last_error FROM scheduled_tasks WHERE id = ?", id);
    }

    @Test
    @DisplayName("a scheduled result goes to the pinned chat, not the chat that happens to be open, and is kept whole")
    void intoThePinnedChat(@TempDir Path tmp) throws Exception {
        start(tmp);
        String open = conversations.createSession(USER, "Router trouble");
        String digest = "Morning digest.\n" + "- an item of news worth reading in full\n".repeat(200);
        queue.result = AgentResult.completed(digest, new AgentTrajectory(), 3);
        String description = LONG_TASK;
        long id = due(description);

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
        assertEquals(List.of("Scheduled task firing: " + description), lines(StatusMessage.Type.SCHEDULED)
                .stream().filter(l -> l.startsWith("Scheduled task firing")).toList(), "each status line names it whole");
        assertEquals(List.of("Deferred task #" + id + " completed: " + description), lines(StatusMessage.Type.COMPLETED));
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
        long id = jdbc.queryForObject("SELECT id FROM scheduled_tasks", Long.class);
        assertEquals(List.of("Deferred task #" + id + " failed. " + ScheduledTaskService.WHERE_ITS_REPORT_IS),
                lines(StatusMessage.Type.FAILED), "the status line points at the report instead of repeating it: "
                        + "Telegram is sent both");
        assertTrue(emitted.stream().filter(m -> m.type() != StatusMessage.Type.RESULT)
                .noneMatch(m -> m.text().contains("step that ran")), "the ending is in the report alone");
    }

    @Test
    @DisplayName("a pinned chat that cannot be had just now costs the saved row, not the push or the run's record")
    void aLostChatLosesOnlyTheRow(@TempDir Path tmp) throws Exception {
        store = db -> new ConversationService(db, null) {
            @Override public synchronized String scheduledSession(String userId) {
                throw new org.springframework.dao.CannotAcquireLockException("[SQLITE_BUSY] The database file is locked");
            }
        };
        start(tmp);
        queue.result = AgentResult.completed("all quiet", new AgentTrajectory(), 1);
        long id = due("morning digest");

        scheduler.pollDueTasks();

        assertEquals("completed", task(id).get("status"), "finished, not left running");
        assertEquals(1, ((Number) task(id).get("run_count")).intValue(), "counted once");
        assertEquals("all quiet", task(id).get("last_result"));
        assertEquals(List.of("completed"), jdbc.queryForList("SELECT status FROM scheduled_task_runs", String.class));
        assertTrue(result().text().contains("all quiet"), "still sent: Telegram gets it, and the Tasks view keeps it");
        assertNull(result().data().get("sessionId"), "no chat to name");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM conversations", Integer.class));
    }

    @Test
    @DisplayName("a recurring run keeps its whole result, and its status lines name the whole task")
    void aRecurringRunIsKeptWhole(@TempDir Path tmp) throws Exception {
        start(tmp);
        String digest = "Morning digest.\n" + "- an item of news worth reading in full\n".repeat(200);
        queue.result = AgentResult.completed(digest, new AgentTrajectory(), 3);
        long id = dueRecurring(LONG_TASK, null);

        scheduler.pollDueTasks();

        assertEquals("active", task(id).get("status"));
        assertEquals(digest, task(id).get("last_result"), "whole, not its first 4,000 characters");
        assertTrue(String.valueOf(result().text()).startsWith("**Scheduled task: " + LONG_TASK + "**"), result().text());
        assertTrue(lines(StatusMessage.Type.SCHEDULED).stream().anyMatch(l -> l.startsWith("Recurring task scheduled [")
                && l.endsWith(" \u2014 " + LONG_TASK)), "created: " + lines(StatusMessage.Type.SCHEDULED));
        assertTrue(lines(StatusMessage.Type.SCHEDULED).contains("Scheduled task firing: " + LONG_TASK));
        assertTrue(lines(StatusMessage.Type.SCHEDULED).stream()
                .anyMatch(l -> l.startsWith("Recurring task #" + id + " completed. Next run: ")));
    }

    @Test
    @DisplayName("a recurring run that fails keeps its whole error and runs again; its status line points at the report")
    void aRecurringFailureIsKeptWhole(@TempDir Path tmp) throws Exception {
        start(tmp);
        String ending = "Stopped: the step limit.\n" + "- step that ran and what it found\n".repeat(40);
        queue.result = AgentResult.maxSteps(ending, new AgentTrajectory(), 30);
        long id = dueRecurring(LONG_TASK, null);

        scheduler.pollDueTasks();

        assertEquals("active", task(id).get("status"), "a recurring task runs again");
        assertEquals("MAX_STEPS after 0 steps: " + ending, task(id).get("last_error"), "whole, not its first 500");
        assertTrue(result().text().endsWith(ending), "the report is whole");
        List<String> warned = lines(StatusMessage.Type.WARNING);
        assertEquals(1, warned.size(), String.valueOf(warned));
        assertTrue(warned.get(0).startsWith("Recurring task #" + id + " failed and runs again at ")
                && warned.get(0).endsWith(". " + ScheduledTaskService.WHERE_ITS_REPORT_IS), warned.get(0));
    }

    @Test
    @DisplayName("a recurring task that reaches its last run keeps the whole result and says so, naming the whole task")
    void theLastRunIsKeptWhole(@TempDir Path tmp) throws Exception {
        start(tmp);
        String digest = "Morning digest.\n" + "- an item of news worth reading in full\n".repeat(200);
        queue.result = AgentResult.completed(digest, new AgentTrajectory(), 3);
        long id = dueRecurring(LONG_TASK, 1);

        scheduler.pollDueTasks();

        assertEquals("completed", task(id).get("status"));
        assertEquals(digest, task(id).get("last_result"));
        assertEquals(List.of("Recurring task #" + id + " completed (max runs reached): " + LONG_TASK),
                lines(StatusMessage.Type.COMPLETED));
    }

    @Test
    @DisplayName("a run that stopped to ask keeps the whole question: nobody was there to answer it")
    void aQuestionIsKeptWhole(@TempDir Path tmp) throws Exception {
        start(tmp);
        String question = "Which of these accounts should the report cover? " + "An account and its details. ".repeat(30);
        queue.result = AgentResult.needsInput(question, new AgentTrajectory(), 3);
        long id = due("summarise my accounts");

        scheduler.pollDueTasks();

        assertTrue(String.valueOf(task(id).get("last_error")).endsWith("nobody to answer: " + question),
                "whole, not its first 400 characters");
        assertTrue(result().text().endsWith(question));
    }

    @Test
    @DisplayName("a run whose schedule was deleted while it ran is still delivered under the whole task's name")
    void aDeletedScheduleIsDeliveredWhole(@TempDir Path tmp) throws Exception {
        start(tmp);
        queue.result = AgentResult.completed("all quiet", new AgentTrajectory(), 1);
        queue.duringRun = () -> jdbc.update("DELETE FROM scheduled_tasks");
        due(LONG_TASK);

        scheduler.pollDueTasks();

        assertEquals("**Scheduled task: " + LONG_TASK + "**\n\nall quiet",
                jdbc.queryForObject("SELECT content FROM conversations WHERE role = 'assistant'", String.class));
    }

    @Test
    @DisplayName("a run that failed while its schedule was deleted still delivers its report, once")
    void aDeletedScheduleStillReportsItsFailure(@TempDir Path tmp) throws Exception {
        start(tmp);
        queue.result = AgentResult.maxSteps("Stopped: the step limit.", new AgentTrajectory(), 30);
        queue.duringRun = () -> jdbc.update("DELETE FROM scheduled_tasks");
        due(LONG_TASK);

        scheduler.pollDueTasks();

        assertEquals(List.of("**Scheduled task did not finish: " + LONG_TASK + "**\n\n"
                        + "MAX_STEPS after 0 steps: Stopped: the step limit."),
                jdbc.queryForList("SELECT content FROM conversations WHERE role = 'assistant'", String.class));
        assertEquals(1, lines(StatusMessage.Type.RESULT).size(), "sent once");
    }

    @Test
    @DisplayName("a run the privacy check stopped is delivered with the owner's private text, and recorded partial")
    void aStoppedRunDeliversThePrivateText(@TempDir Path tmp) throws Exception {
        start(tmp);
        String secret = "Closing balance 48,213.07 CZK";
        queue.result = AgentResult.privacyBlocked("Stopped: result 2 is private and was not sent.",
                new AgentTrajectory(), 4).withOwnerText("Stopped: result 2 is private.\nPrivate: " + secret);
        due("summarise my statement");

        scheduler.pollDueTasks();

        var row = jdbc.queryForMap("SELECT content, private_content FROM conversations WHERE role = 'assistant'");
        assertFalse(String.valueOf(row.get("content")).contains(secret), "content feeds later prompts");
        assertTrue(String.valueOf(row.get("private_content")).contains(secret), "the owner is shown what it found");
        assertEquals(List.of("partial"), jdbc.queryForList("SELECT status FROM scheduled_task_runs", String.class));
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
