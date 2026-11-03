package com.ownclaw.agent;

import com.ownclaw.llm.LlmMessage;
import com.ownclaw.llm.LlmProvider;
import com.ownclaw.llm.LlmRequestConfig;
import com.ownclaw.llm.LlmResponse;
import com.ownclaw.llm.Replies;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The local model's summaries of private results, of tasks running side by side: the task that
 * has the local model has its own written while it has it, whatever another task asked for
 * before; and the model writes one at a time.
 */
@Timeout(60)
class LaneSummariesTest {

    /** A local model keeping what each call was about, and how many ran at once at most. */
    static final class Local implements LlmProvider {
        final BlockingQueue<String> ran = new LinkedBlockingQueue<>();
        final AtomicInteger inCall = new AtomicInteger();
        final AtomicInteger most = new AtomicInteger();
        volatile long takes;

        public LlmResponse chat(List<LlmMessage> m, LlmRequestConfig c) {
            most.accumulateAndGet(inCall.incrementAndGet(), Math::max);
            try {
                if (takes > 0) Thread.sleep(takes);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                inCall.decrementAndGet();
            }
            ran.add(m.get(0).content());
            return Replies.of("ok", 0, 0);
        }

        public boolean isAvailable() { return true; }
        public String name() { return "ollama"; }
    }

    private static Runnable summary(LocalLane lane, Local local, String task, AtomicBoolean ended) {
        return () -> {
            try {
                lane.call(local, List.of(LlmMessage.user(task)), task, ended::get);
            } catch (Exception ignored) {
                // ended with its task
            }
        };
    }

    @Test
    @DisplayName("the task that has the local model has its summary written, with another task's asked for before it")
    void theHoldersSummaryIsWritten() throws Exception {
        var lane = new LocalLane();
        var local = new Local();
        lane.foreground(new AgentContext("u1", "Y", "y"), () -> null);   // Y has the lane until it ends
        var yEnded = new AtomicBoolean();
        var zEnded = new AtomicBoolean();
        lane.background("Z", summary(lane, local, "Z", zEnded));
        lane.background("Y", summary(lane, local, "Y", yEnded));

        assertEquals("Y", local.ran.poll(5, TimeUnit.SECONDS), "Y's own summary, while Y has the lane");
        assertNull(local.ran.poll(300, TimeUnit.MILLISECONDS), "Z's waits for the lane");
        yEnded.set(true);
        lane.release("Y");
        assertEquals("Z", local.ran.poll(5, TimeUnit.SECONDS), "and is written once Y has ended");
        zEnded.set(true);
        lane.release("Z");
    }

    @Test
    @DisplayName("summaries of tasks side by side take turns: the local model writes one at a time")
    void summariesTakeTurns() throws Exception {
        var lane = new LocalLane();
        var local = new Local();
        local.takes = 100;
        var ended = new AtomicBoolean();
        var written = new CountDownLatch(4);
        for (String task : List.of("A", "B", "C", "D")) {
            lane.background(task, () -> {
                summary(lane, local, task, ended).run();
                written.countDown();
            });
        }
        assertTrue(written.await(20, TimeUnit.SECONDS));
        assertEquals(4, local.ran.size());
        assertEquals(1, local.most.get(), "two summaries were written at once");
        for (String task : List.of("A", "B", "C", "D")) lane.release(task);
    }
}
