package com.ownclaw.agent;

import com.ownclaw.core.TaskCancellationService;
import com.ownclaw.llm.LlmMessage;
import com.ownclaw.llm.LlmProvider;
import com.ownclaw.llm.LlmRequestConfig;
import com.ownclaw.llm.LlmResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The local model works on one task at a time: a task takes it with its first local call and
 * keeps it until it ends; the next one's local work begins then, in turn -- attended before
 * unattended -- and a task waiting for it is neither silent to the watchdog nor deaf to a Stop.
 */
@org.junit.jupiter.api.Timeout(60)   // a task that never lets the local model go waits for ever: fail, not hang
class LocalLaneTurnsTest {

    final List<String> told = new CopyOnWriteArrayList<>();
    final LocalLane lane = new LocalLane((task, why) -> told.add(task.taskId() + ": " + why));
    final BlockingQueue<String> ran = new LinkedBlockingQueue<>();

    static AgentContext task(String id, boolean unattended) {
        var ctx = new AgentContext("u1", id, "work " + id);
        ctx.setUnattended(unattended);
        return ctx;
    }

    /** {@code task}'s local work, on a thread of its own: it says when it ran. */
    CompletableFuture<Void> localWork(AgentContext task) {
        return CompletableFuture.runAsync(() -> lane.foreground(task, () -> {
            ran.add(task.taskId());
            return null;
        }));
    }

    void nothingRuns() throws Exception {
        assertNull(ran.poll(200, TimeUnit.MILLISECONDS), "local work ran");
    }

    @Test
    @DisplayName("a task keeps the local model from its first local call to its end, the next one waits for that")
    void oneTaskAtATime() throws Exception {
        var a = task("A", false);
        var b = task("B", false);
        lane.foreground(a, () -> "first call");
        assertEquals("A", lane.owner());
        var bWork = localWork(b);
        nothingRuns();
        assertEquals(List.of("B: " + LocalLane.WAITING), told, "B's page is told why it waits");
        assertEquals("again", lane.foreground(a, () -> "again"), "A's later calls do not wait: it has the lane");
        lane.release("A");
        assertEquals("B", ran.poll(5, TimeUnit.SECONDS));
        bWork.get(5, TimeUnit.SECONDS);
        assertEquals("B", lane.owner(), "B keeps it until it ends, too");
        lane.release("B");
        assertNull(lane.owner());
    }

    @Test
    @DisplayName("attended work waiting goes before unattended work that asked first; each kind in the order it asked")
    void attendedFirst() throws Exception {
        lane.foreground(task("holder", false), () -> null);
        var digest = localWork(task("digest", true));
        Thread.sleep(100);
        var menu = localWork(task("menu", true));
        Thread.sleep(100);
        var question = localWork(task("question", false));
        Thread.sleep(100);
        nothingRuns();
        lane.release("holder");
        assertEquals("question", ran.poll(5, TimeUnit.SECONDS));
        question.get(5, TimeUnit.SECONDS);
        lane.release("question");
        assertEquals("digest", ran.poll(5, TimeUnit.SECONDS));
        digest.get(5, TimeUnit.SECONDS);
        lane.release("digest");
        assertEquals("menu", ran.poll(5, TimeUnit.SECONDS));
        menu.get(5, TimeUnit.SECONDS);
        lane.release("menu");
    }

    @Test
    @DisplayName("a task waiting for the local model is a call under way to the watchdog, and a Stop ends its wait")
    void aWaitIsNotSilenceAndAStopEndsIt() throws Exception {
        lane.foreground(task("holder", false), () -> null);
        var stop = new AtomicBoolean();
        var waiting = task("waiting", false);
        waiting.setExternalCancel(stop::get);
        var behind = task("behind", false);
        var work = localWork(waiting);
        Thread.sleep(150);
        var behindWork = localWork(behind);
        Thread.sleep(150);
        assertEquals(0, waiting.msSinceLastProgress(), "not silence: the watchdog would have stalled it");
        stop.set(true);
        waiting.interruptCall();
        var e = assertThrows(java.util.concurrent.ExecutionException.class, () -> work.get(5, TimeUnit.SECONDS));
        assertInstanceOf(TaskCancellationService.TaskCancelledException.class, e.getCause());
        nothingRuns();
        lane.release("holder");
        assertEquals("behind", ran.poll(5, TimeUnit.SECONDS), "the one behind it takes its place");
        behindWork.get(5, TimeUnit.SECONDS);
        lane.release("behind");
    }

    @Test
    @DisplayName("a summary of one task waits while another task has the local model, and makes way when one takes it")
    void summariesWaitForTheLane() throws Exception {
        var started = new CountDownLatch(1);
        var answered = new CopyOnWriteArrayList<String>();
        LlmProvider local = new LlmProvider() {
            public LlmResponse chat(List<LlmMessage> m, LlmRequestConfig c) {
                var ended = new CountDownLatch(1);
                c.progress().calling(ended::countDown);
                started.countDown();
                try {
                    if (ended.await(300, TimeUnit.MILLISECONDS)) {
                        c.progress().onProgress();   // ended: the hook says why
                    }
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                } finally {
                    c.progress().calling(null);
                }
                answered.add("summary");
                return com.ownclaw.llm.Replies.of("summary", 0, 0);
            }
            public boolean isAvailable() { return true; }
            public String name() { return "ollama"; }
        };
        lane.foreground(task("other", false), () -> null);
        var summary = CompletableFuture.supplyAsync(() -> {
            try {
                return lane.call(local, List.of(LlmMessage.user("You summarise")), "mine", () -> false).content();
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        });
        assertFalse(started.await(300, TimeUnit.MILLISECONDS), "a summary began while another task had the lane");
        lane.release("other");
        assertEquals("summary", summary.get(5, TimeUnit.SECONDS));
        assertEquals(List.of("summary"), answered);
    }

    @Test
    @DisplayName("a task that ends while it waits leaves the queue: the next one is not held up by it")
    void anEndedWaiterLeaves() throws Exception {
        lane.foreground(task("holder", false), () -> null);
        lane.release("gone");   // a task that never asked: nothing happens
        assertEquals("holder", lane.owner());
        lane.release("holder");
        assertNull(lane.owner());
        lane.foreground(task("next", false), () -> null);
        assertEquals("next", lane.owner());
        lane.release("next");
    }
}
