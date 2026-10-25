package com.ownclaw.core;

import com.ownclaw.agent.AgentResult;
import com.ownclaw.agent.TaskChat;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.conversation.ChatOptions;
import com.ownclaw.conversation.MigratedDatabase;
import com.ownclaw.observability.ChatStatusEmitter;
import com.ownclaw.observability.EventLogService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A Stop of a chat stops that chat's work -- its running task, and what waits in it -- and the
 * work of the other chats goes on: tasks of different chats run side by side.
 */
class ChatStopTest {

    @Test
    @DisplayName("a chat's Stop stops what was asked in it before the Stop: not another chat's, not what comes after")
    void aChatStopIsTheChats() throws Exception {
        var cancellation = new TaskCancellationService();
        long before = System.currentTimeMillis() - 1;
        cancellation.requestChat("u1", "A", "you pressed Stop");
        assertEquals("you pressed Stop", cancellation.why("u1", "t1", "A", before));
        assertNull(cancellation.why("u1", "t2", "B", before), "chat B's task goes on");
        assertNull(cancellation.why("u2", "t3", "A", before), "another user's chat of the same id goes on");
        assertNull(cancellation.why("u1", "t4", null, before), "a task asked in no chat goes on");
        Thread.sleep(2);
        assertNull(cancellation.why("u1", "t5", "A", System.currentTimeMillis()),
                "what is asked in chat A after the Stop runs");
        cancellation.requestAll("u1", "you sent /cancel all");
        assertEquals("you sent /cancel all", cancellation.why("u1", "t2", "B", before), "everything");
    }

    @Test
    @DisplayName("what waits in a stopped chat is dropped when its turn comes; another chat's waiting work runs")
    void queuedWorkOfTheChatIsDropped(@TempDir Path tmp) throws Exception {
        var loop = new ParallelChatsTest.Held();
        var config = new OwnClawConfig();
        config.getQueue().setMaxConcurrentTasks(5);
        var cancellation = new TaskCancellationService();
        var queue = new TaskQueue(loop, new EventLogService(MigratedDatabase.at(tmp.resolve("t.db"))),
                new ChatStatusEmitter(), config, cancellation);
        queue.start();
        try {
            var waitingInA = new CompletableFuture<AgentResult>();
            var waitingInB = new CompletableFuture<AgentResult>();
            queue.send(message("A", "running in A", r -> {}), false);
            queue.send(message("B", "running in B", r -> {}), false);
            assertNotNull(loop.started.poll(5, TimeUnit.SECONDS));
            assertNotNull(loop.started.poll(5, TimeUnit.SECONDS));
            queue.send(message("A", "waiting in A", waitingInA::complete), true);
            queue.send(message("B", "waiting in B", waitingInB::complete), true);
            cancellation.requestChat("u1", "A", "you pressed Stop");
            loop.end("running in A");
            loop.end("running in B");
            AgentResult dropped = waitingInA.get(5, TimeUnit.SECONDS);
            assertEquals(AgentResult.TerminationReason.CANCELLED, dropped.terminationReason());
            assertTrue(dropped.response().startsWith("**Stopped:** you pressed Stop, while it was still waiting"),
                    dropped.response());
            assertEquals("waiting in B", loop.started.poll(5, TimeUnit.SECONDS), "chat B's work goes on");
            loop.end("waiting in B");
            assertEquals("done: waiting in B", waitingInB.get(5, TimeUnit.SECONDS).response());
        } finally {
            queue.stop();
        }
    }

    private static UserMessage message(String chat, String text, java.util.function.Consumer<AgentResult> answer) {
        return new UserMessage("u1", chat, "row-" + text, text, List.of(), TaskChat.Channel.WEB,
                ChatOptions.NONE, answer);
    }
}
