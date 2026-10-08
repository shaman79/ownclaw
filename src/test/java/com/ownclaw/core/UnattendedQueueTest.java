package com.ownclaw.core;

import com.ownclaw.agent.AgentLoop;
import com.ownclaw.agent.AgentResult;
import com.ownclaw.agent.AgentTrajectory;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.conversation.MigratedDatabase;
import com.ownclaw.observability.ChatStatusEmitter;
import com.ownclaw.observability.ChatStatusEmitter.StatusMessage;
import com.ownclaw.observability.EventLogService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What the queue says of unattended work -- a scheduled run, /bg -- before it has a task id to be
 * known by is marked as that work's, as the emitter marks what it says once it runs; and only
 * attended work counts as the working state a page is told of when it connects or switches chats.
 */
class UnattendedQueueTest {

    /** A loop whose tasks wait for the test to let them finish; "boom" throws. */
    static final class Loop extends AgentLoop {
        final BlockingQueue<String> started = new LinkedBlockingQueue<>();
        volatile CountDownLatch release = new CountDownLatch(1);

        Loop() {
            super(null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null);
        }

        @Override
        public AgentResult executeFull(String userId, String message, boolean unattended,
                                       String currentMessageId, List<String> attachmentIds,
                                       com.ownclaw.agent.TaskChat.Channel channel) {
            started.add(message);
            if ("boom".equals(message)) throw new IllegalStateException("database is locked");
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return AgentResult.completed("done", new AgentTrajectory(), 1);
        }
    }

    private final ChatStatusEmitter emitter = new ChatStatusEmitter();
    private final List<StatusMessage> seen = new CopyOnWriteArrayList<>();

    private TaskQueue queue(Path tmp, AgentLoop loop) throws Exception {
        emitter.subscribe("u1", "test", seen::add);
        return new TaskQueue(loop, new EventLogService(MigratedDatabase.at(tmp.resolve("t.db"))), emitter,
                new OwnClawConfig(), new TaskCancellationService());
    }

    private static boolean marked(StatusMessage s) {
        return s.data() != null && Boolean.TRUE.equals(s.data().get(ChatStatusEmitter.BACKGROUND));
    }

    @Test
    @DisplayName("a scheduled or /bg task's place in the queue is said marked; a waiting chat task's is not, and only that counts as work going on")
    void queuedWork(@TempDir Path tmp) throws Exception {
        TaskQueue queue = queue(tmp, new Loop());   // not started: everything waits
        queue.submit("u1", "morning digest", TaskQueue.BACKGROUND_PRIORITY);
        queue.submit("u1", "lunch menu", TaskQueue.BACKGROUND_PRIORITY);
        assertTrue(queue.isBusyFor("u1"));
        assertFalse(queue.isAttendedBusyFor("u1"), "two scheduled runs waiting are nobody's working state");

        queue.submit("u1", "check the router", 1);
        assertTrue(queue.isAttendedBusyFor("u1"), "a task asked for, waiting behind them, is");

        assertEquals(List.of("Task queued (position 2)", "Task queued (position 3)"),
                seen.stream().map(StatusMessage::text).toList());
        assertTrue(marked(seen.get(0)), "the scheduled run's: " + seen.get(0));
        assertNull(seen.get(1).data(), "the attended task's, as it always was: " + seen.get(1));
        // Mutation: emit QUEUED unmarked again -> a scheduled run queued behind another started the
        // spinner of whatever chat was open, and nothing of that run stops it now.
    }

    @Test
    @DisplayName("running work counts as a page's working state only when it is attended; a failure of unattended work is said marked")
    void runningWork(@TempDir Path tmp) throws Exception {
        var loop = new Loop();
        TaskQueue queue = queue(tmp, loop);
        queue.start();
        try {
            var digest = queue.submit("u1", "morning digest", TaskQueue.BACKGROUND_PRIORITY);
            assertEquals("morning digest", loop.started.poll(5, TimeUnit.SECONDS));
            assertTrue(queue.isBusyFor("u1"));
            assertFalse(queue.isAttendedBusyFor("u1"), "a scheduled run under way is nobody's working state");
            loop.release.countDown();
            digest.get(5, TimeUnit.SECONDS);

            loop.release = new CountDownLatch(1);
            var asked = queue.submit("u1", "check the router", 1);
            assertEquals("check the router", loop.started.poll(5, TimeUnit.SECONDS));
            assertTrue(queue.isAttendedBusyFor("u1"), "a task asked for, under way, is");
            loop.release.countDown();
            asked.get(5, TimeUnit.SECONDS);
            long until = System.currentTimeMillis() + 5_000;
            while (queue.isAttendedBusyFor("u1") && System.currentTimeMillis() < until) Thread.sleep(5);
            assertFalse(queue.isAttendedBusyFor("u1"), "and is not once it has ended");

            queue.submit("u1", "boom", TaskQueue.BACKGROUND_PRIORITY).get(5, TimeUnit.SECONDS);
            queue.submit("u1", "boom", 1).get(5, TimeUnit.SECONDS);
            var failures = seen.stream().filter(s -> s.type() == StatusMessage.Type.FAILED).toList();
            assertEquals(2, failures.size(), seen.toString());
            assertTrue(marked(failures.get(0)), "the scheduled run's: " + failures.get(0));
            assertNull(failures.get(1).data(), "the attended task's, as it always was: " + failures.get(1));
            // Mutation: never add attended work to the running set -> a task asked for and under
            // way is not counted, and a page reconnecting mid-task shows an idle chat.
        } finally {
            queue.stop();
        }
    }
}
