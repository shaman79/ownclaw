package com.ownclaw.core;

import com.ownclaw.agent.TaskChat;
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
        return new UserMessage("u1", "chat", "row-" + text, text, List.of(), TaskChat.Channel.WEB, r -> { });
    }

    @Test
    @DisplayName("the task reads what was offered, in order, once; the close hands on the rest and takes nothing more")
    void readOnceThenHandedOn() {
        var inbox = new Inbox();
        assertTrue(inbox.offer(message("a")));
        assertTrue(inbox.offer(message("b")));
        assertEquals(List.of("a", "b"), inbox.drain().stream().map(UserMessage::text).toList());
        assertEquals(List.of(), inbox.drain(), "read once");

        assertTrue(inbox.offer(message("c")));
        assertTrue(inbox.offer(message("d")));
        var handedOn = new ArrayList<String>();
        List<UserMessage> unread = inbox.close(m -> handedOn.add(m.text()));
        assertEquals(List.of("c", "d"), handedOn, "what the task did not read, in order");
        assertEquals(List.of("c", "d"), unread.stream().map(UserMessage::text).toList());
        assertFalse(inbox.offer(message("e")), "an ended task takes nothing: the caller queues it");
        assertEquals(List.of(), inbox.drain());
        assertEquals(List.of(), inbox.close(m -> fail("handed on twice: " + m.text())));
    }

    @Test
    @DisplayName("offers racing the close on other threads: every message is read, or queued, exactly once, each sender's in order")
    void offerRacesClose() throws Exception {
        for (int round = 0; round < 2_000; round++) {
            var inbox = new Inbox();
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
                        if (!inbox.offer(m)) queued.add(m.text());
                    }
                }));
            }
            int stopAfter = round % 7;
            threads.add(Thread.ofPlatform().start(() -> {
                await(go);
                for (int i = 0; i < stopAfter; i++) inbox.drain().forEach(m -> read.add(m.text()));
                inbox.close(m -> queued.add(m.text()));
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
