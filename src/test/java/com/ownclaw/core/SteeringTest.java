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
        final List<TaskChat.Channel> channels = new CopyOnWriteArrayList<>();
        /** What each task was told it was sent with. */
        final List<ChatOptions> options = new CopyOnWriteArrayList<>();
        /** The inbox of the last task that had one. */
        volatile Inbox inbox;

        Driven() {
            super(null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null);
        }

        @Override
        public AgentResult executeFull(String userId, String message, boolean unattended,
                                       String currentMessageId, List<String> attachmentIds,
                                       TaskChat.Channel channel, Inbox inbox, ChatOptions chosen) {
            ran.add(message);
            rows.add(String.valueOf(currentMessageId));
            channels.add(channel);
            options.add(chosen);
            if (inbox != null) this.inbox = inbox;
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
    final OwnClawConfig config = new OwnClawConfig();
    final TaskCancellationService cancellation = new TaskCancellationService();
    final List<StatusMessage> statuses = new CopyOnWriteArrayList<>();
    final AtomicInteger rows = new AtomicInteger();
    TaskQueue queue;

    void start(Path tmp) throws Exception {
        var emitter = new ChatStatusEmitter();
        emitter.subscribe("u1", this, statuses::add);
        queue = new TaskQueue(loop, new EventLogService(MigratedDatabase.at(tmp.resolve("t.db"))), emitter,
                config, cancellation);
        queue.start();
    }

    @AfterEach
    void stop() {
        if (queue != null) queue.stop();
    }

    /** A message as a chat sends it, its row saved: its answer, when it gets one, is an event. */
    UserMessage message(String user, String chat, String text, List<String> files) {
        return message(user, chat, text, files, TaskChat.Channel.WEB);
    }

    UserMessage message(String user, String chat, String text, List<String> files, TaskChat.Channel channel) {
        return message(user, chat, text, files, channel, ChatOptions.NONE);
    }

    UserMessage message(String user, String chat, String text, List<String> files, TaskChat.Channel channel,
                        ChatOptions options) {
        return new UserMessage(user, chat, "row" + rows.incrementAndGet(), text, files, channel, options,
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
    @DisplayName("a message sent with other options than the running task's is a task of its own, on its options, and what follows it queues behind it")
    void otherOptionsAreNotSteered(@TempDir Path tmp) throws Exception {
        start(tmp);
        var cheaper = new ChatOptions("cheaper", null);
        var free = new ChatOptions("free", null);
        queue.send(message("u1", "A", "check the routers", List.of(), TaskChat.Channel.WEB, cheaper), false);
        assertEquals("started check the routers", next());
        assertEquals(Fate.STEERED, queue.send(message("u1", "A", "use the backup link", List.of(),
                TaskChat.Channel.WEB, cheaper), false), "sent with the task's own options");
        assertEquals(Fate.QUEUED, queue.send(message("u1", "A", "read my mail", List.of(), TaskChat.Channel.WEB,
                free), false), "sent on Free: the cloud model running the task must not read it");
        assertEquals(Fate.QUEUED, queue.send(message("u1", "A", "and summarise it", List.of(), TaskChat.Channel.WEB,
                cheaper), false), "after it, behind it");
        loop.commands.add("step");
        assertEquals("read [use the backup link]", next());
        for (int i = 0; i < 3; i++) loop.commands.add("end");
        assertEquals("answered check the routers: done: check the routers", next());
        assertEquals("started read my mail", next());
        assertEquals("answered read my mail: done: read my mail", next());
        assertEquals("started and summarise it", next());
        assertEquals("answered and summarise it: done: and summarise it", next());
        idle();
        assertEquals(List.of(cheaper, free, cheaper), loop.options, "each task runs on what its message was sent with");
        // Mutation: let the inbox take a message whatever its options -> "read my mail", sent on
        // Free, is read by the running task, on the cloud.
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

    @Test
    @DisplayName("a message the task did not read runs in the place it took when sent: before one queued after it")
    void anUnreadMessageKeepsItsPlace(@TempDir Path tmp) throws Exception {
        start(tmp);
        queue.send(message("A", "check the routers"), false);
        assertEquals("started check the routers", next());
        assertEquals(Fate.STEERED, queue.send(message("A", "FIRST: use the backup link"), false));
        assertEquals(Fate.QUEUED, queue.send(message("A", "SECOND: then check the printer"), true));
        for (int i = 0; i < 3; i++) loop.commands.add("end");
        assertEquals("answered check the routers: done: check the routers", next());
        assertEquals("started FIRST: use the backup link", next(), "sent first, it runs first");
        assertEquals("answered FIRST: use the backup link: done: FIRST: use the backup link", next());
        assertEquals("started SECOND: then check the printer", next());
        assertEquals("answered SECOND: then check the printer: done: SECOND: then check the printer", next());
        idle();
        // Mutation: queue it in a new place when the task ends -> SECOND runs first, and its task
        // reads FIRST as a question asked before the answer it came after.
    }

    @Test
    @DisplayName("a message sent just as a chat turn's task ends runs after what that task did not read: its inbox closes before it is let go")
    void aMessageSentAsTheTaskEndsRunsAfterWhatItDidNotRead(@TempDir Path tmp) throws Exception {
        start(tmp);
        // A chat turn of the ops API runs on its caller's thread, beside the queue's worker, which
        // waits idle and takes what is queued at once.
        var turn = Thread.ofPlatform().start(() -> queue.runChat(message("A", "check the routers")));
        assertEquals("started check the routers", next());
        Inbox inbox = loop.inbox;
        assertEquals(Fate.STEERED, queue.send(message("A", "FIRST: use the backup link"), false));
        synchronized (inbox) {
            // The task ends, and waits to close its inbox, which this thread holds.
            loop.commands.add("end");
            assertEquals("answered check the routers: done: check the routers", next());
            for (int i = 0; i < 1000 && turn.getState() != Thread.State.BLOCKED; i++) Thread.sleep(2);
            assertEquals(Thread.State.BLOCKED, turn.getState(), "the ending task waits on its inbox");
            queue.send(message("A", "SECOND: then the printer"), false);
            assertNull(loop.events.poll(200, TimeUnit.MILLISECONDS),
                    "nothing starts before what the task did not read is queued");
        }
        loop.commands.add("end");
        loop.commands.add("end");
        assertEquals("started FIRST: use the backup link", next());
        assertEquals("answered FIRST: use the backup link: done: FIRST: use the backup link", next());
        assertEquals("started SECOND: then the printer", next());
        assertEquals("answered SECOND: then the printer: done: SECOND: then the printer", next());
        turn.join(10_000);
        idle();
        // Mutation: let the inbox go before closing it -> SECOND finds no inbox, is queued at
        // once, and the idle worker runs it before FIRST is handed on.
    }

    @Test
    @DisplayName("after a message with files is queued, what follows it in the chat queues behind it: the running task never reads it first")
    void aFollowUpWaitsForTheFile(@TempDir Path tmp) throws Exception {
        start(tmp);
        queue.send(message("A", "check the routers"), false);
        assertEquals("started check the routers", next());
        assertEquals(Fate.QUEUED, queue.send(message("u1", "A", "FIRST: here is the router config", List.of("f1")), false));
        assertEquals(Fate.QUEUED, queue.send(message("A", "SECOND: compare it with the running one"), false));
        loop.commands.add("step");
        assertEquals("read []", next(), "not read without the file it is about");
        for (int i = 0; i < 3; i++) loop.commands.add("end");
        assertEquals("answered check the routers: done: check the routers", next());
        assertEquals("started FIRST: here is the router config", next());
        assertEquals("answered FIRST: here is the router config: done: FIRST: here is the router config", next());
        assertEquals("started SECOND: compare it with the running one", next());
        assertEquals("answered SECOND: compare it with the running one: done: SECOND: compare it with the running one",
                next());
        idle();
        // Mutation: refuse only the message with files -> the follow-up is read by the running
        // task, without the file, and the file's task runs without the follow-up.
    }

    @Test
    @DisplayName("a message from another channel than the running task's is a task of its own, answered where it came from")
    void anotherChannelIsATaskOfItsOwn(@TempDir Path tmp) throws Exception {
        start(tmp);
        queue.send(message("A", "check the routers"), false);
        assertEquals("started check the routers", next());
        assertEquals(Fate.QUEUED, queue.send(
                message("u1", "A", "is the printer working?", List.of(), TaskChat.Channel.TELEGRAM), false),
                "the web task's answer goes to the page alone");
        assertEquals(Fate.QUEUED, queue.send(message("A", "and the scanner?"), false), "sent after it, it runs after it");
        loop.commands.add("step");
        assertEquals("read []", next());
        for (int i = 0; i < 3; i++) loop.commands.add("end");
        assertEquals("answered check the routers: done: check the routers", next());
        assertEquals("started is the printer working?", next());
        assertEquals("answered is the printer working?: done: is the printer working?", next(),
                "answered by a task of its own, whose answer goes to Telegram");
        assertEquals(TaskChat.Channel.TELEGRAM, loop.channels.get(1));
        assertEquals("started and the scanner?", next());
        assertEquals("answered and the scanner?: done: and the scanner?", next());
        idle();

        queue.send(message("u1", "A", "check the routers", List.of(), TaskChat.Channel.TELEGRAM), false);
        assertEquals("started check the routers", next());
        assertEquals(Fate.STEERED, queue.send(
                message("u1", "A", "use the backup link", List.of(), TaskChat.Channel.TELEGRAM), false));
        assertEquals(Fate.QUEUED, queue.send(message("A", "and the printer?"), false),
                "a Telegram task's answer does not reach the page");
        loop.commands.add("step");
        assertEquals("read [use the backup link]", next());
        loop.commands.add("end");
        loop.commands.add("end");
        assertEquals("answered check the routers: done: check the routers", next());
        assertEquals("started and the printer?", next());
        assertEquals("answered and the printer?: done: and the printer?", next());
        idle();
        // Mutation: steer whatever channel it came from -> the Telegram question is read by the
        // web task, and Telegram is told only that the task got it.
    }

    @Test
    @DisplayName("a full queue refuses a message the task did not read: it is answered so, and its sender is not told it runs")
    void aFullQueueRefusesTheHandOn(@TempDir Path tmp) throws Exception {
        config.getQueue().setMaxQueuedTasks(1);
        start(tmp);
        queue.send(message("A", "check the routers"), false);
        assertEquals("started check the routers", next());
        assertEquals(Fate.QUEUED, queue.send(message("B", "waiting in B"), false));
        assertEquals(Fate.STEERED, queue.send(message("A", "use the backup link"), false));
        assertEquals(Fate.REFUSED, queue.send(message("C", "one too many"), false), "not queued: no line says it is");
        assertEquals("answered one too many: System busy — please try again later.", next());
        loop.commands.add("end");
        loop.commands.add("end");
        assertEquals("answered check the routers: done: check the routers", next());
        assertEquals("answered use the backup link: System busy — please try again later.", next());
        assertEquals("started waiting in B", next());
        assertEquals("answered waiting in B: done: waiting in B", next());
        idle();
        assertTrue(statuses.stream().noneMatch(s -> TaskQueue.UNREAD.equals(s.text())),
                "nothing says it runs as a task of its own: " + statuses);
        // Mutation: tell the sender of every message handed on -> "it runs as a task of its own"
        // beside "System busy".
    }
}
