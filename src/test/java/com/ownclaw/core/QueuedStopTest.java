package com.ownclaw.core;

import com.ownclaw.agent.AgentLoop;
import com.ownclaw.agent.AgentResult;
import com.ownclaw.agent.AgentTrajectory;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.conversation.MigratedDatabase;
import com.ownclaw.observability.ChatStatusEmitter;
import com.ownclaw.observability.EventLogService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Work still waiting in the queue when a stop comes never starts, and says who stopped it. */
class QueuedStopTest {

    @Test
    @DisplayName("a task still queued when /cancel came is dropped, and its text says /cancel stopped it")
    void aQueuedTaskSaysWhoStoppedIt(@TempDir Path tmp) throws Exception {
        var cancellation = new TaskCancellationService();
        var release = new CountDownLatch(1);
        var ran = new CopyOnWriteArrayList<String>();
        var loop = new AgentLoop(null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null) {
            @Override
            public AgentResult executeFull(String userId, String message, boolean unattended,
                                           String currentMessageId, List<String> attachmentIds,
                                           com.ownclaw.agent.TaskChat.Channel channel) {
                ran.add(message);
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return AgentResult.completed("done", new AgentTrajectory(), 1);
            }
        };
        var queue = new TaskQueue(loop, new EventLogService(MigratedDatabase.at(tmp.resolve("t.db"))),
                new ChatStatusEmitter(), new OwnClawConfig(), cancellation);
        queue.start();
        try {
            var first = queue.submit("u1", "first", 1, null, List.of(), null);
            long until = System.currentTimeMillis() + 5_000;
            while (ran.isEmpty() && System.currentTimeMillis() < until) Thread.sleep(5);
            assertFalse(ran.isEmpty(), "the first task never started");
            var second = queue.submit("u1", "second", 1, null, List.of(), null);
            cancellation.requestAll("u1", "you sent /cancel");
            release.countDown();

            assertEquals("done", first.get(5, TimeUnit.SECONDS).response());
            AgentResult dropped = second.get(5, TimeUnit.SECONDS);
            assertEquals(AgentResult.TerminationReason.CANCELLED, dropped.terminationReason());
            assertEquals("**Stopped:** you sent /cancel, while it was still waiting in the queue: it never "
                    + "started.", dropped.response());
            assertEquals(List.of("first"), ran, "the dropped task never ran");
            // Mutation: the old fixed text -> "when you pressed Stop" for a /cancel.
        } finally {
            queue.stop();
        }
    }
}
