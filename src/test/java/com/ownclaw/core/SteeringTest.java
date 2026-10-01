package com.ownclaw.core;

import com.ownclaw.agent.AgentLoop;
import com.ownclaw.agent.AgentResult;
import com.ownclaw.agent.AgentTrajectory;
import com.ownclaw.agent.TaskChat;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.conversation.MigratedDatabase;
import com.ownclaw.core.TaskQueue.Fate;
import com.ownclaw.observability.ChatStatusEmitter;
import com.ownclaw.observability.ChatStatusEmitter.StatusMessage;
import com.ownclaw.observability.EventLogService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What the owner sends in a chat while a task runs, through the real queue: handed to the task
 * running in that chat, which reads it at a step, or queued as a task of its own -- and what the
 * task did not read when it ended runs after it, each message once. The agent loop is replaced by
 * one the test steps through: it reads its inbox when told to, and ends when told how.
 */
class SteeringTest {

    /** A task the test drives: each command is one step of it, or its end. */
    static final class Driven extends AgentLoop {
        final BlockingQueue<String> commands = new LinkedBlockingQueue<>();
        /** What happened, in order: a task started, read its inbox, or answered. */
        final BlockingQueue<String> events = new LinkedBlockingQueue<>();
        final List<String> ran = new CopyOnWriteArrayList<>();
        final List<String> rows = new CopyOnWriteArrayList<>();

        Driven() {
            super(null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null);
        }

        @Override
        public AgentResult executeFull(String userId, String message, boolean unattended,
                                       String currentMessageId, List<String> attachmentIds,
                                       TaskChat.Channel channel, Inbox inbox) {
            ran.add(message);
            rows.add(String.valueOf(currentMessageId));
            events.add("started " + message + (inbox == null ? " (no inbox)" : ""));
            while (true) {
                String command;
                try {
                    command = commands.poll(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
                if (command == null) throw new AssertionError("the test never ended " + message);
                switch (command) {
                    case "step" -> events.add("read " + inbox.drain().stream().map(UserMessage::text).toList());
                    case "end" -> { return AgentResult.completed("done: " + message, new AgentTrajectory(), 1); }
                    case "stopped" -> { return AgentResult.cancelled("you pressed Stop", new AgentTrajectory(), 1); }
                    case "fail" -> throw new IllegalStateException("broke");
                    default -> throw new AssertionError(command);
                }
            }
        }
    }

    final Driven loop = new Driven();
    final TaskCancellationService cancellation = new TaskCancellationService();
    final List<StatusMessage> statuses = new CopyOnWriteArrayList<>();
    final AtomicInteger rows = new AtomicInteger();
    TaskQueue queue;

    void start(Path tmp) throws Exception {
        var emitter = new ChatStatusEmitter();
        emitter.subscribe("u1", this, statuses::add);
        queue = new TaskQueue(loop, new EventLogService(MigratedDatabase.at(tmp.resolve("t.db"))), emitter,
                new OwnClawConfig(), cancellation);
        queue.start();
    }

    @AfterEach
    void stop() {
        if (queue != null) queue.stop();
    }

    /** A message as a chat sends it, its row saved: its answer, when it gets one, is an event. */
    UserMessage message(String user, String chat, String text, List<String> files) {
        return new UserMessage(user, chat, "row" + rows.incrementAndGet(), text, files, TaskChat.Channel.WEB,
                r -> loop.events.add("answered " + text + ": " + r.response()));
    }

    UserMessage message(String chat, String text) {
        return message("u1", chat, text, List.of());
    }

    String next() throws Exception {
        String event = loop.events.poll(10, TimeUnit.SECONDS);
        assertNotNull(event, "nothing happened");
        return event;
    }

    void idle() throws Exception {
        for (int i = 0; i < 200 && queue.isBusy(); i++) Thread.sleep(10);
        assertFalse(queue.isBusy());
        Thread.sleep(50);
        assertNull(loop.events.poll(), "nothing more happens");
    }

    @Test
    @DisplayName("a message sent while a task of its chat runs goes to it: read at its next step, not run again")
    void aMessageSteersTheRunningTask(@TempDir Path tmp) throws Exception {
        start(tmp);
        assertEquals(Fate.STARTED, queue.send(message("A", "check the routers"), false));
        assertEquals("started check the routers", next());

        assertEquals(Fate.STEERED, queue.send(message("A", "use the backup link"), false));
        loop.commands.add("step");
        assertEquals("read [use the backup link]", next());
        loop.commands.add("step");
        assertEquals("read []", next(), "read once");
        loop.commands.add("end");
        assertEquals("answered check the routers: done: check the routers", next());
        idle();
        assertEquals(List.of("check the routers"), loop.ran, "the task answers both; nothing runs twice");
    }

    @Test
    @DisplayName("a message the task ended without reading runs after it, as a task of its own answering its own row")
    void anUnreadMessageRunsAfter(@TempDir Path tmp) throws Exception {
        start(tmp);
        queue.send(message("A", "check the routers"), false);
        assertEquals("started check the routers", next());
        loop.commands.add("step");
        assertEquals("read []", next());

        UserMessage late = message("A", "and the printer");
        assertEquals(Fate.STEERED, queue.send(late, false), "the task still runs: it is offered");
        loop.commands.add("end");
        loop.commands.add("end");
        assertEquals("answered check the routers: done: check the routers", next());
        assertEquals("started and the printer", next(), "after the answer, which its task reads");
        assertEquals("answered and the printer: done: and the printer", next());
        idle();
        assertEquals(List.of("check the routers", "and the printer"), loop.ran);
        assertEquals(late.messageId(), loop.rows.get(1), "its own row is the message its task answers");
        assertTrue(statuses.stream().anyMatch(s -> s.type() == StatusMessage.Type.QUEUED
                        && TaskQueue.UNREAD.equals(s.text()) && late.messageId().equals(s.data().get("requeued"))),
                "its sender is told, under his message: " + statuses);
        // Mutation: close the inbox without handing on what it holds -> the printer is never run.
    }

    @Test
    @DisplayName("queued, or sent in another chat, a message runs as a task of its own after the running one, in order")
    void queuedAndOtherChatsRunAfterInOrder(@TempDir Path tmp) throws Exception {
        start(tmp);
        queue.send(message("A", "first"), false);
        assertEquals("started first", next());
        assertEquals(Fate.QUEUED, queue.send(message("A", "queued in A"), true));
        assertEquals(Fate.QUEUED, queue.send(message("B", "sent in B"), false));
        assertEquals(Fate.QUEUED, queue.send(message("A", "queued again"), true));
        assertEquals(Fate.STEERED, queue.send(message("A", "steering A"), false));
        loop.commands.add("step");
        assertEquals("read [steering A]", next());
        for (int i = 0; i < 4; i++) loop.commands.add("end");
        assertEquals("answered first: done: first", next());
        assertEquals("started queued in A", next());
        assertEquals("answered queued in A: done: queued in A", next());
        assertEquals("started sent in B", next());
        assertEquals("answered sent in B: done: sent in B", next());
        assertEquals("started queued again", next());
        assertEquals("answered queued again: done: queued again", next());
        idle();
    }

    @Test
    @DisplayName("a message never reaches another user's task, and one with files is a task of its own")
    void othersAndFilesAreNotSteered(@TempDir Path tmp) throws Exception {
        start(tmp);
        queue.send(message("A", "check the routers"), false);
        assertEquals("started check the routers", next());
        assertNotEquals(Fate.STEERED, queue.send(message("u2", "A", "not yours", List.of()), false),
                "the same chat id, another user");
        assertEquals(Fate.QUEUED, queue.send(message("u1", "A", "read this file", List.of("f1")), false),
                "files become a task's own files when it starts");
        loop.commands.add("step");
        assertEquals("read []", next());
        for (int i = 0; i < 3; i++) loop.commands.add("end");
        assertEquals("answered check the routers: done: check the routers", next());
        assertEquals("started not yours", next());
        assertEquals("answered not yours: done: not yours", next());
        assertEquals("started read this file", next());
        assertEquals("answered read this file: done: read this file", next());
        idle();
    }

    @Test
    @DisplayName("a scheduled or background task has no inbox: what is sent while it runs is queued, as before")
    void unattendedWorkHasNoInbox(@TempDir Path tmp) throws Exception {
        start(tmp);
        var digest = queue.submit("u1", "the morning digest", TaskQueue.BACKGROUND_PRIORITY);
        assertEquals("started the morning digest (no inbox)", next());
        assertEquals(Fate.QUEUED, queue.send(message("A", "hello"), false));
        loop.commands.add("end");
        loop.commands.add("end");
        assertEquals("done: the morning digest", digest.get(10, TimeUnit.SECONDS).response());
        assertEquals("started hello", next());
        assertEquals("answered hello: done: hello", next());
        idle();
    }

    @Test
    @DisplayName("a task that breaks, or is stopped, hands on what it did not read: each runs after it, once")
    void everyEndingHandsOn(@TempDir Path tmp) throws Exception {
        start(tmp);
        queue.send(message("A", "check the routers"), false);
        assertEquals("started check the routers", next());
        queue.send(message("A", "unread when it broke"), false);
        loop.commands.add("fail");
        loop.commands.add("end");
        assertEquals("answered check the routers: Internal error: broke", next());
        assertEquals("started unread when it broke", next());
        assertEquals("answered unread when it broke: done: unread when it broke", next());
        idle();

        queue.send(message("A", "audit the network"), false);
        assertEquals("started audit the network", next());
        assertEquals(Fate.QUEUED, queue.send(message("A", "queued before the Stop"), true));
        assertEquals(Fate.STEERED, queue.send(message("A", "unread when it stopped"), false));
        cancellation.requestAll("u1", "you pressed Stop");
        Thread.sleep(5);   // a Stop covers what was queued by its millisecond; a task takes longer to end
        loop.commands.add("stopped");
        loop.commands.add("end");
        assertEquals("answered audit the network: you pressed Stop", next());
        assertEquals("answered queued before the Stop: **Stopped:** you pressed Stop, while it was still "
                + "waiting in the queue: it never started.", next(), "waiting work, as Stop has always dropped");
        assertEquals("started unread when it stopped", next(),
                "sent to the task, it was no waiting work: it is queued when the task ends, and runs");
        assertEquals("answered unread when it stopped: done: unread when it stopped", next());
        idle();
    }
}
