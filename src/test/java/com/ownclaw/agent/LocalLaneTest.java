package com.ownclaw.agent;

import com.ownclaw.llm.LlmException;
import com.ownclaw.llm.LlmMessage;
import com.ownclaw.llm.LlmProvider;
import com.ownclaw.llm.LlmRequestConfig;
import com.ownclaw.llm.LlmResponse;
import com.ownclaw.llm.Replies;
import com.ownclaw.observability.ChatStatusEmitter.StatusMessage;
import com.ownclaw.observability.TaskTraceService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import static com.ownclaw.agent.LoopRig.*;
import static com.ownclaw.agent.ProgressMessagesTest.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The local model's summaries of private results are background work on a local model every task
 * shares, which serves one request at a time: they never hold up a task, they end with their
 * task -- the one under way is ended, the rest are never asked for -- and what one costs and
 * where it lands stay true when its reply comes back just as its task ends.
 */
class LocalLaneTest {

    /**
     * A local model that serves one request at a time, in the order asked, as Ollama does: a
     * summary takes {@code summaryMs} unless its call is ended first; a delegation's turn is
     * answered from a script, at once. What it finished is kept, in order.
     */
    static final class OneSlot implements LlmProvider {
        final ReentrantLock slot = new ReentrantLock(true);
        final List<String> finished = new CopyOnWriteArrayList<>();
        final CountDownLatch summaryStarted = new CountDownLatch(1);
        final Deque<String> turns = new ArrayDeque<>();
        final long summaryMs;

        OneSlot(long summaryMs, String... turns) {
            this.summaryMs = summaryMs;
            this.turns.addAll(List.of(turns));
        }

        public LlmResponse chat(List<LlmMessage> m, LlmRequestConfig c) {
            boolean summary = m.get(0).content().startsWith("You summarise");
            var ended = new CountDownLatch(1);
            c.progress().calling(ended::countDown);
            slot.lock();
            try {
                if (!summary) {
                    finished.add("turn");
                    synchronized (turns) {
                        return Replies.of(turns.isEmpty() ? DelegationBehaviourTest.done("done") : turns.poll(), 1, 1);
                    }
                }
                summaryStarted.countDown();
                if (ended.await(summaryMs, TimeUnit.MILLISECONDS)) {
                    finished.add("summary ended");
                    c.progress().onProgress();   // what the hook throws is what the caller gets
                    throw new LlmException("ollama", "Connection failed: Canceled", 0, null);
                }
                finished.add("summary");
                return Replies.of(SUMMARY, 600, 100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            } finally {
                slot.unlock();
                c.progress().calling(null);
            }
        }

        public boolean isAvailable() { return true; }
        public String name() { return "ollama"; }
    }

    /** The progress row of {@code session} whose content starts with {@code head}, once it is there. */
    static Map<String, Object> awaitRowIn(LoopRig rig, String session, String head) throws InterruptedException {
        long until = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < until) {
            for (var row : progress(rig)) {
                if (session.equals(row.get("session_id")) && String.valueOf(row.get("content")).startsWith(head)) {
                    return row;
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError("no progress row starting " + head + " in " + progress(rig));
    }

    @Test
    @DisplayName("an ended task's summaries are not written: a later task's delegation has the local model to itself")
    void anEndedTasksSummariesAreNotWritten(@TempDir Path tmp) throws Exception {
        var local = new OneSlot(10_000, DelegationBehaviourTest.call("ping", Map.of("n", 1)),
                DelegationBehaviourTest.call("ping", Map.of("n", 2)),
                DelegationBehaviourTest.call("ping", Map.of("n", 3)), DelegationBehaviourTest.done("pinged"));
        var rig = new LoopRig(tmp, List.of(BANK, PING), 600, local);
        String session = rig.chat.createSession("u1", "Bank");
        for (int i = 1; i <= 3; i++) rig.cloud.think.add(call("bank_fetch", Map.of("month", i)));
        rig.cloud.think.add(c -> {
            assertTrue(local.summaryStarted.await(5, TimeUnit.SECONDS), "the first summary never began");
            return respond("Fetched.").answer(c);
        });
        rig.turn(session, "fetch my statements");

        rig.cloud.think.add(call(AgentAction.DELEGATE, Map.of("goal", "Ping the router three times.", "tools", "ping")));
        rig.cloud.think.add(respond("It answers."));
        AgentResult b = assertTimeoutPreemptively(java.time.Duration.ofSeconds(5),
                () -> rig.loop.executeFull("u1", "ping the router", true));
        Thread.sleep(300);

        assertEquals("It answers.", b.response());
        assertEquals(List.of("summary ended", "turn", "turn", "turn", "turn"), local.finished,
                "the one under way ended with its task, the other two never asked for");
        assertEquals(3, progress(rig).size(), "the three steps, no result row: " + progress(rig));
        // Mutation: let a task's end leave its summaries be -> the delegation waits out the
        // summary under way, and three summaries follow it.
    }

    @Test
    @DisplayName("a summary under way when a delegation starts is ended, and written again after it while its task runs")
    void aSummaryMakesWayForADelegation(@TempDir Path tmp) throws Exception {
        var local = new OneSlot(1_500, DelegationBehaviourTest.done("The balance is 48,213.07 CZK."));
        var rig = new LoopRig(tmp, List.of(BANK), 600, local);
        String session = rig.chat.createSession("u1", "Bank");
        rig.cloud.think.add(call("bank_fetch", Map.of()));
        rig.cloud.think.add(c -> {
            assertTrue(local.summaryStarted.await(5, TimeUnit.SECONDS), "the summary never began");
            return call(AgentAction.DELEGATE, Map.of("goal", "Read {{1}} and say the balance.")).answer(c);
        });
        rig.cloud.think.add(c -> {
            awaitRowIn(rig, session, ProgressMessagesTest.LOCAL + " Result 1 · bank_fetch");
            return respond("Done.").answer(c);
        });

        AgentResult r = rig.turn(session, "what is my balance?");

        assertEquals("Done.", r.response());
        assertEquals(List.of("summary ended", "turn", "summary"), local.finished,
                "the delegation did not wait for the summary, and the summary was written after it");
        assertNotNull(awaitRowIn(rig, session, ProgressMessagesTest.LOCAL + " Result 1 · bank_fetch").get("private_content"));
        // Mutation: only hold back summaries not yet begun -> the turn waits behind it.
    }

    @Test
    @DisplayName("a summary whose reply comes back as its task ends is counted -- per day, and on the task's page -- and not posted")
    void aLateReplyIsCountedNotPosted(@TempDir Path tmp) throws Exception {
        var begun = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        // A model that does not hear the cancel: its reply comes back after the task has ended.
        var local = new Local(c -> {
            begun.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS));
            return Replies.of(SUMMARY, 400, 30);
        });
        var rig = new LoopRig(tmp, List.of(BANK), 600, local);
        rig.cloud.think.add(call("bank_fetch", Map.of()));
        rig.cloud.think.add(c -> {
            assertTrue(begun.await(5, TimeUnit.SECONDS), "the summary never began");
            return respond("Fetched.").answer(c);
        });
        String session = rig.chat.createSession("u1", "Bank");

        AgentResult r = rig.turn(session, "fetch my statement");
        release.countDown();

        long until = System.currentTimeMillis() + 10_000;
        Map<String, Object> today;
        do {
            Thread.sleep(20);
            today = rig.events.tokenUsageDetailToday("u1");
        } while (((Number) today.get("local_tokens")).longValue() == 0 && System.currentTimeMillis() < until);
        assertEquals(430L, ((Number) today.get("local_tokens")).longValue(), today.toString());
        assertEquals(1L, ((Number) today.get("task_count")).longValue(), "one task, however its tokens are kept");
        var outcome = (Map<?, ?>) new TaskTraceService(rig.events).trace("u1", r.taskId()).orElseThrow().get("outcome");
        assertEquals(430L, ((Number) outcome.get("localTokens")).longValue(), outcome.toString());
        assertEquals(1, progress(rig).size(), "the step only: nothing below the answer " + progress(rig));
        // Mutation: count it only on the task's counter -> the ending was written: 0 local tokens.
    }

    @Test
    @DisplayName("a task's end ends the summary under way at once, and it says nothing")
    void theEndEndsTheSummaryUnderWay(@TempDir Path tmp) throws Exception {
        var calling = new CountDownLatch(1);
        var ended = new CountDownLatch(1);
        var local = new Local(c -> {
            c.progress().calling(ended::countDown);    // the call's cancel, as a provider hands it over
            calling.countDown();
            assertTrue(ended.await(10, TimeUnit.SECONDS), "nothing ended the call");
            c.progress().onProgress();                  // asked once more, as a provider does
            return Replies.of(SUMMARY, 400, 30);
        });
        var rig = new LoopRig(tmp, List.of(BANK), 600, local);
        rig.cloud.think.add(call("bank_fetch", Map.of()));
        rig.cloud.think.add(c -> {
            assertTrue(calling.await(5, TimeUnit.SECONDS), "the summary never began");
            return respond("Fetched.").answer(c);
        });
        String session = rig.chat.createSession("u1", "Bank");

        AgentResult r = rig.turn(session, "fetch my statement");

        assertEquals(AgentResult.TerminationReason.COMPLETED, r.terminationReason());
        assertTrue(ended.await(2, TimeUnit.SECONDS), "the task's end did not end it");
        Thread.sleep(300);
        assertEquals(1, progress(rig).size(), "the step only: " + progress(rig));
        // Mutation: end only the calls of stopped tasks -> it waits out its call, then posts.
    }

    @Test
    @DisplayName("a row for a chat the owner deleted while the task ran is neither saved nor shown")
    void noRowForADeletedChat(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of(PING));
        var seen = rig.statuses();
        String deleted = rig.chat.createSession("u1", "Router");
        rig.cloud.think.add(call("ping", Map.of("n", 1)));
        rig.cloud.think.add(c -> {
            rig.chat.deleteSession("u1", deleted);
            return call("ping", Map.of("n", 2)).answer(c);
        });
        rig.cloud.think.add(respond("It answers."));
        String row = rig.chat.saveMessage("u1", deleted, "user", "ping the router");

        rig.loop.executeFull("u1", "ping the router", false, row, List.of(), TaskChat.Channel.WEB);

        assertEquals(List.of(), rig.jdbc.queryForList("SELECT role, content FROM conversations WHERE session_id = ?",
                deleted), "nothing is saved into the deleted chat");
        var shown = seen.stream().filter(m -> m.type() == StatusMessage.Type.PROGRESS_MESSAGE).toList();
        assertEquals(1, shown.size(), "the step before the delete, and nothing after it: " + shown);
        // Mutation: save it unconditionally -> a row of no chat holds step 2.
    }
}
