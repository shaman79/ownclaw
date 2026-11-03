package com.ownclaw.core;

import com.ownclaw.agent.AgentLoop;
import com.ownclaw.agent.AgentResult;
import com.ownclaw.agent.AgentTrajectory;
import com.ownclaw.agent.TaskChat;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.conversation.ChatOptions;
import com.ownclaw.conversation.MigratedDatabase;
import com.ownclaw.core.TaskQueue.Fate;
import com.ownclaw.observability.ChatStatusEmitter;
import com.ownclaw.observability.EventLogService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tasks of different chats side by side, one task at a time in each: what a message becomes, and
 * when it runs.
 */
class ParallelChatsTest {

    /** A loop whose every task runs until the test ends it, by its text. */
    static final class Held extends AgentLoop {
        final BlockingQueue<String> started = new LinkedBlockingQueue<>();
        final Map<String, CountDownLatch> ends = new ConcurrentHashMap<>();

        Held() {
            super(null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null);
        }

        void end(String text) {
            ends.computeIfAbsent(text, t -> new CountDownLatch(1)).countDown();
        }

        private AgentResult hold(String text) {
            started.add(text);
            try {
                assertTrue(ends.computeIfAbsent(text, t -> new CountDownLatch(1)).await(10, TimeUnit.SECONDS),
                        "the test never ended " + text);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            return AgentResult.completed("done: " + text, new AgentTrajectory(), 1);
        }

        @Override
        public AgentResult executeFull(String userId, String message, boolean unattended,
                                       String currentMessageId, List<String> attachmentIds,
                                       TaskChat.Channel channel, Inbox inbox, ChatOptions chosen) {
            return hold(message);
        }

        @Override
        public AgentResult executeFull(String userId, String message, boolean unattended,
                                       String currentMessageId, List<String> attachmentIds,
                                       TaskChat.Channel channel) {
            return hold(message);
        }
    }

    final Held loop = new Held();
    final OwnClawConfig config = new OwnClawConfig();
    final AtomicInteger rows = new AtomicInteger();
    TaskQueue queue;

    void start(Path tmp, int workers) throws Exception {
        config.getQueue().setMaxConcurrentTasks(workers);
        queue = new TaskQueue(loop, new EventLogService(MigratedDatabase.at(tmp.resolve("t.db"))),
                new ChatStatusEmitter(), config, new TaskCancellationService());
        queue.start();
    }

    @AfterEach
    void stop() {
        if (queue != null) queue.stop();
    }

    UserMessage message(String chat, String text) {
        return new UserMessage("u1", chat, "row" + rows.incrementAndGet(), text, List.of(),
                TaskChat.Channel.WEB, ChatOptions.NONE, r -> {});
    }

    String started() throws Exception {
        String text = loop.started.poll(5, TimeUnit.SECONDS);
        assertNotNull(text, "nothing started");
        return text;
    }

    void nothingStarts() throws Exception {
        assertNull(loop.started.poll(200, TimeUnit.MILLISECONDS), "something started");
    }

    @Test
    @DisplayName("a message in a chat where nothing runs starts beside a task of another chat")
    void twoChatsSideBySide(@TempDir Path tmp) throws Exception {
        start(tmp, 5);
        assertEquals(Fate.STARTED, queue.send(message("A", "audit the network"), false));
        assertEquals("audit the network", started());
        assertEquals(Fate.STARTED, queue.send(message("B", "what is the weather"), false),
                "chat B waits for nothing of chat A's");
        assertEquals("what is the weather", started());
        assertTrue(queue.isAttendedBusyFor("u1", "A") && queue.isAttendedBusyFor("u1", "B"));
        loop.end("what is the weather");
        for (int i = 0; i < 200 && queue.isAttendedBusyFor("u1", "B"); i++) Thread.sleep(10);
        assertFalse(queue.isAttendedBusyFor("u1", "B"), "chat B's page is idle once its task has ended");
        assertTrue(queue.isAttendedBusyFor("u1", "A"), "while chat A's still works");
        assertTrue(queue.isAttendedBusyFor("u1"), "and the user has work going on");
        loop.end("audit the network");
    }

    @Test
    @DisplayName("background work leaves a worker free when chats were busy first")
    void backgroundAfterChatsLeavesAWorkerFree(@TempDir Path tmp) throws Exception {
        start(tmp, 3);
        queue.send(message("A", "check the router"), false);
        assertEquals("check the router", started());
        queue.submit("u1", "morning digest", TaskQueue.BACKGROUND_PRIORITY);
        queue.submit("u1", "lunch menu", TaskQueue.BACKGROUND_PRIORITY);
        assertEquals("morning digest", started());
        nothingStarts();
        assertEquals(Fate.STARTED, queue.send(message("B", "what is the weather"), false),
                "the last worker is a chat's");
        assertEquals("what is the weather", started());
        loop.end("check the router");
        loop.end("what is the weather");
        assertEquals("lunch menu", started());
        loop.end("morning digest");
        loop.end("lunch menu");
    }

    @Test
    @DisplayName("the chats a page marks as working: those with attended work running or waiting in them, not background work's")
    void theWorkingChats(@TempDir Path tmp) throws Exception {
        start(tmp, 2);
        queue.submit("u1", "morning digest", TaskQueue.BACKGROUND_PRIORITY);
        assertEquals("morning digest", started());
        queue.send(message("A", "check the router"), false);
        assertEquals("check the router", started());
        queue.send(message("B", "what is the weather"), false);   // waits: every worker is busy
        nothingStarts();
        assertEquals(List.of("A", "B"), List.copyOf(queue.attendedBusyChats("u1")), "running, and waiting");
        assertEquals(List.of(), List.copyOf(queue.attendedBusyChats("u2")), "another user's chats are not his");
        loop.end("check the router");
        assertEquals("what is the weather", started());
        for (int i = 0; i < 200 && queue.attendedBusyChats("u1").contains("A"); i++) Thread.sleep(10);
        assertEquals(List.of("B"), List.copyOf(queue.attendedBusyChats("u1")), "chat A's task has ended");
        loop.end("what is the weather");
        loop.end("morning digest");
    }

    @Test
    @DisplayName("a message queued in a busy chat runs after its task, in order, while other chats run")
    void oneAtATimeInAChat(@TempDir Path tmp) throws Exception {
        start(tmp, 5);
        queue.send(message("A", "first in A"), false);
        assertEquals("first in A", started());
        assertEquals(Fate.QUEUED, queue.send(message("A", "second in A"), true));
        assertEquals(Fate.QUEUED, queue.send(message("A", "third in A"), true));
        assertEquals(Fate.STARTED, queue.send(message("B", "in B"), false));
        assertEquals("in B", started(), "chat B is not behind chat A's queue");
        nothingStarts();
        loop.end("first in A");
        assertEquals("second in A", started());
        nothingStarts();
        loop.end("second in A");
        assertEquals("third in A", started());
        loop.end("third in A");
        loop.end("in B");
    }

    @Test
    @DisplayName("scheduled and /bg work runs beside chats but never takes the last worker")
    void backgroundLeavesAWorkerFree(@TempDir Path tmp) throws Exception {
        start(tmp, 2);
        CompletableFuture<AgentResult> digest = queue.submit("u1", "morning digest", TaskQueue.BACKGROUND_PRIORITY);
        CompletableFuture<AgentResult> menu = queue.submit("u1", "lunch menu", TaskQueue.BACKGROUND_PRIORITY);
        assertEquals("morning digest", started());
        nothingStarts();
        assertEquals(Fate.STARTED, queue.send(message("A", "check the router"), false),
                "the second worker was kept for a chat");
        assertEquals("check the router", started());
        loop.end("morning digest");
        nothingStarts();   // the lunch menu would take the worker a message in another chat needs
        loop.end("check the router");
        assertEquals("lunch menu", started());
        loop.end("lunch menu");
        assertEquals("done: morning digest", digest.get(5, TimeUnit.SECONDS).response());
        assertEquals("done: lunch menu", menu.get(5, TimeUnit.SECONDS).response());
    }

    @Test
    @DisplayName("with every worker busy a message waits, and starts when one is free")
    void waitsForAWorker(@TempDir Path tmp) throws Exception {
        start(tmp, 1);
        queue.send(message("A", "in A"), false);
        assertEquals("in A", started());
        assertEquals(Fate.QUEUED, queue.send(message("B", "in B"), false), "one worker: B waits for it");
        nothingStarts();
        loop.end("in A");
        assertEquals("in B", started());
        loop.end("in B");
    }

    @Test
    @DisplayName("an ops chat turn waits for its chat's task, not beside it, and runs beside other chats")
    void anOpsTurnTakesItsChat(@TempDir Path tmp) throws Exception {
        start(tmp, 5);
        queue.send(message("A", "in A"), false);
        assertEquals("in A", started());
        var turn = CompletableFuture.supplyAsync(() -> queue.runChat(message("A", "ops turn in A")));
        nothingStarts();
        assertTrue(queue.isBusy());
        assertEquals(Fate.STARTED, queue.send(message("B", "in B"), false));
        assertEquals("in B", started());
        loop.end("in A");
        assertEquals("ops turn in A", started(), "after A's task, as a message queued in A would");
        assertEquals(Fate.QUEUED, queue.send(message("A", "queued behind the turn"), true),
                "the turn holds its chat while it runs");
        loop.end("ops turn in A");
        assertEquals("done: ops turn in A", turn.get(5, TimeUnit.SECONDS).response());
        assertEquals("queued behind the turn", started());
        loop.end("queued behind the turn");
        loop.end("in B");
    }
}
