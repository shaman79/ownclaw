package com.ownclaw.core;

import com.ownclaw.agent.AgentResult;
import com.ownclaw.agent.AgentTrajectory;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.conversation.ConversationService;
import com.ownclaw.conversation.MigratedDatabase;
import com.ownclaw.observability.ChatStatusEmitter;
import com.ownclaw.observability.ChatStatusEmitter.StatusMessage;
import com.ownclaw.observability.EventLogService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A scheduled run whose answer is private, delivered by the real scheduler: to the owner's chat
 * beside the safe text, and nowhere else -- not the chat row's content, not the run's record,
 * not last_result, all of which later prompts, ops and the schedule list read.
 * <p>
 * Driven through pollDueTasks on the migrated schema. Only the task queue is replaced, by one
 * that hands back a finished result.
 */
class ScheduledPrivateAnswerTest {

    static final String NOTE = "[Private answer: sent to you only, never to the cloud model.]";
    static final String SECRET = "Closing balance 48,213.07 CZK";

    /** Hands back a finished private answer for every run; runs nothing. */
    static final class Finished extends TaskQueue {
        /** What happens while the "task" runs, before its result comes back. */
        Runnable duringRun = () -> {};

        Finished() {
            super(null, null, null, new OwnClawConfig(), null);
        }

        @Override
        public CompletableFuture<AgentResult> submit(String userId, String message, int priority) {
            duringRun.run();
            return CompletableFuture.completedFuture(
                    AgentResult.completed(NOTE, new AgentTrajectory(), 1)
                            .withOwnerText("**Private:**\n\n" + SECRET)
                            .withTaskId("a1b2c3d4"));
        }
    }

    private JdbcTemplate jdbc;
    private Finished queue;
    private ScheduledTaskService scheduler;
    private final List<StatusMessage> results = new ArrayList<>();

    private void start(Path tmp) throws Exception {
        jdbc = MigratedDatabase.at(tmp.resolve("t.db"));
        var conversations = new ConversationService(jdbc, null);
        var emitter = new ChatStatusEmitter();
        emitter.subscribe("u1", "web", m -> {
            if (m.type() == StatusMessage.Type.RESULT) results.add(m);
        });
        queue = new Finished();
        scheduler = new ScheduledTaskService(jdbc, queue, emitter, new EventLogService(jdbc),
                conversations, new OwnClawConfig(), null, null, new ResultDelivery(conversations, emitter));
        scheduler.scheduleDeferred("u1", "summarise the statement in my inbox",
                Instant.now().minusSeconds(60));
    }

    /** The one delivered row: its private text reaches the owner, its content does not carry it. */
    private void assertDeliveredToTheOwnerAlone() {
        var rows = jdbc.queryForList(
                "SELECT content, private_content FROM conversations WHERE role = 'assistant'");
        assertEquals(1, rows.size(), String.valueOf(rows));
        String content = String.valueOf(rows.get(0).get("content"));
        assertTrue(content.contains(NOTE), content);
        assertFalse(content.contains(SECRET), "content feeds every later prompt: " + content);
        assertTrue(String.valueOf(rows.get(0).get("private_content")).contains(SECRET),
                "the owner's chat shows the answer on reload: " + rows.get(0));

        assertEquals(1, results.size());
        assertFalse(results.get(0).text().contains(SECRET), "Telegram formats the text");
        assertTrue(String.valueOf(results.get(0).data().get("ownerText")).contains(SECRET),
                "the web chat is sent the answer beside it");
    }

    @Test
    @DisplayName("a scheduled run's private answer reaches the owner's chat, and no record of the run")
    void aScheduledPrivateAnswerReachesOnlyTheOwner(@TempDir Path tmp) throws Exception {
        start(tmp);

        scheduler.pollDueTasks();

        assertDeliveredToTheOwnerAlone();
        String run = String.valueOf(jdbc.queryForList("SELECT result FROM scheduled_task_runs"));
        assertTrue(run.contains(NOTE), "the run is recorded: " + run);
        assertFalse(run.contains(SECRET), "ops and the run history read this: " + run);
        String last = String.valueOf(jdbc.queryForList("SELECT last_result FROM scheduled_tasks"));
        assertFalse(last.contains(SECRET), "the schedule list reads this: " + last);
    }

    @Test
    @DisplayName("a run whose schedule was deleted while it ran still delivers its private answer")
    void aDeletedScheduleStillDeliversThePrivateAnswer(@TempDir Path tmp) throws Exception {
        start(tmp);
        // The owner deletes the schedule while its run is in flight, so the run count cannot be
        // updated -- the path that delivers the result anyway, and returns early.
        queue.duringRun = () -> jdbc.update("DELETE FROM scheduled_tasks");

        scheduler.pollDueTasks();

        assertDeliveredToTheOwnerAlone();
    }
}
