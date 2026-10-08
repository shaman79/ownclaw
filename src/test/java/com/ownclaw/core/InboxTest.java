package com.ownclaw.core;

import com.ownclaw.agent.TaskChat;
import com.ownclaw.conversation.ChatOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** A running task's inbox: each message is read by the task or handed on, once, in order. */
class InboxTest {

    static UserMessage message(String text) {
        return message(text, TaskChat.Channel.WEB, List.of());
    }

    static UserMessage message(String text, TaskChat.Channel channel, List<String> files) {
        return new UserMessage("u1", "chat", "row-" + text, text, files, channel, ChatOptions.NONE, r -> { });
    }

    @Test
    @DisplayName("the task reads what was offered, in order, once; the close hands on the rest, each with its place, and takes nothing more")
    void readOnceThenHandedOn() {
        var inbox = new Inbox(TaskChat.Channel.WEB, ChatOptions.NONE);
        assertTrue(inbox.offer(message("a"), 1));
        assertTrue(inbox.offer(message("b"), 2));
        assertEquals(List.of("a", "b"), inbox.drain().stream().map(UserMessage::text).toList());
        assertEquals(List.of(), inbox.drain(), "read once");

        assertTrue(inbox.offer(message("c"), 5));
        assertTrue(inbox.offer(message("d"), 7));
        var handedOn = new ArrayList<String>();
        inbox.close((m, order) -> handedOn.add(m.text() + "@" + order));
        assertEquals(List.of("c@5", "d@7"), handedOn, "what the task did not read, in order, in the places they took when sent");
        assertFalse(inbox.offer(message("e"), 8), "an ended task takes nothing: the caller queues it");
        assertEquals(List.of(), inbox.drain());
        inbox.close((m, order) -> fail("handed on twice: " + m.text()));
    }

    @Test
    @DisplayName("a message from another channel, or with files, is not taken -- nor is anything after it -- and the task still reads what came before")
    void whatItCannotTakeEndsItsTaking() {
        for (UserMessage refused : List.of(message("from the phone", TaskChat.Channel.TELEGRAM, List.of()),
                message("from the ops API", TaskChat.Channel.OPS, List.of()),
                message("read this file", TaskChat.Channel.WEB, List.of("f1")))) {
            var inbox = new Inbox(TaskChat.Channel.WEB, ChatOptions.NONE);
            assertTrue(inbox.offer(message("before"), 1));
            assertFalse(inbox.offer(refused, 2), refused.text() + ": its answer would not reach its sender, or its files the task");
            assertFalse(inbox.offer(message("compare it with the running one"), 3),
                    "after " + refused.text() + ": queued behind it, not read before it runs");
            assertEquals(List.of("before"), inbox.drain().stream().map(UserMessage::text).toList());
            inbox.close((m, order) -> fail("nothing left to hand on: " + m.text()));
        }
        var telegram = new Inbox(TaskChat.Channel.TELEGRAM, ChatOptions.NONE);
        assertTrue(telegram.offer(message("use the backup link", TaskChat.Channel.TELEGRAM, List.of()), 1),
                "a task asked from Telegram takes what is sent from there");
        // Mutation: take a message from any channel -> a Telegram question read by a web task is
        // answered on the web page only, and Telegram never gets its answer.
    }

    @Test
    @DisplayName("offers racing the close on other threads: every message is read, or queued, exactly once, each sender's in order")
    void offerRacesClose() throws Exception {
        for (int round = 0; round < 2_000; round++) {
            var inbox = new Inbox(TaskChat.Channel.WEB, ChatOptions.NONE);
            var read = Collections.synchronizedList(new ArrayList<String>());
            // What runs as a task of its own, in the order it was queued: handed on by the close
            // (under the inbox's lock), or queued by a sender whose offer came after it.
            var queued = Collections.synchronizedList(new ArrayList<String>());
            int senders = 2, each = 40;
            var go = new CountDownLatch(1);
            var threads = new ArrayList<Thread>();
            for (int t = 0; t < senders; t++) {
                String who = "s" + t;
                threads.add(Thread.ofPlatform().start(() -> {
                    await(go);
                    for (int i = 0; i < each; i++) {
                        UserMessage m = message(who + ":" + i);
                        if (!inbox.offer(m, i)) queued.add(m.text());
                    }
                }));
            }
            int stopAfter = round % 7;
            threads.add(Thread.ofPlatform().start(() -> {
                await(go);
                for (int i = 0; i < stopAfter; i++) inbox.drain().forEach(m -> read.add(m.text()));
                inbox.close((m, order) -> queued.add(m.text()));
            }));
            go.countDown();
            for (Thread t : threads) t.join(10_000);

            var all = new ArrayList<String>(read);
            all.addAll(queued);
            assertEquals(senders * each, all.size(), "round " + round + ": none lost, none twice: " + all);
            assertEquals(senders * each, new HashSet<>(all).size(), "round " + round + ": none twice: " + all);
            for (int t = 0; t < senders; t++) {
                String who = "s" + t + ":";
                assertInOrder(read.stream().filter(x -> x.startsWith(who)).toList(), "round " + round + " read");
                assertInOrder(queued.stream().filter(x -> x.startsWith(who)).toList(), "round " + round + " queued");
            }
        }
        // Mutation: hand on outside the lock (close drains, unlocks, then hands on) -> a sender
        // refused after the close queues its message before the earlier ones the close holds,
        // and "queued" falls out of order in some round.
    }

    private static void assertInOrder(List<String> texts, String what) {
        int last = -1;
        for (String x : texts) {
            int i = Integer.parseInt(x.substring(x.indexOf(':') + 1));
            assertTrue(i > last, what + " out of order: " + texts);
            last = i;
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
