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
 * shares, which serves one request at a time: they never hold up a task -- not their own, and
 * not one that runs after their task has ended -- and what they cost, where they land and how a
 * Stop reaches them stay true after their task has ended.
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
    @DisplayName("an ended task's summaries wait while a later task -- a scheduled run too -- delegates: none comes between its turns")
    void anEndedTasksSummariesWaitForALaterDelegation(@TempDir Path tmp) throws Exception {
        var local = new OneSlot(300, DelegationBehaviourTest.call("ping", Map.of("n", 1)),
                DelegationBehaviourTest.call("ping", Map.of("n", 2)),
                DelegationBehaviourTest.call("ping", Map.of("n", 3)), DelegationBehaviourTest.done("pinged"));
        var rig = new LoopRig(tmp, List.of(BANK, PING), 600, local);
        String session = rig.chat.createSession("u1", "Bank");
        for (int i = 1; i <= 3; i++) rig.cloud.think.add(call("bank_fetch", Map.of("month", i)));
        rig.cloud.think.add(respond("Fetched."));
        rig.turn(session, "fetch my statements");

        rig.cloud.think.add(call(AgentAction.DELEGATE, Map.of("goal", "Ping the router three times.", "tools", "ping")));
        rig.cloud.think.add(respond("It answers."));
        AgentResult b = rig.loop.executeFull("u1", "ping the router", true);
        for (int i = 1; i <= 3; i++) awaitRowIn(rig, session, "**Result " + i + " (bank_fetch)**");

        assertEquals("It answers.", b.response());
        var done = List.copyOf(local.finished);
        var turns = done.subList(done.indexOf("turn"), done.lastIndexOf("turn") + 1);
        assertTrue(turns.stream().allMatch("turn"::equals), "a summary came between the turns: " + done);
        assertEquals(4, turns.size(), done.toString());
        assertEquals(3, done.stream().filter("summary"::equals).count(), "each written after all: " + done);
        // Mutation: count delegations per task again -> the turns alternate with the summaries.
    }

    @Test
    @DisplayName("a summary under way when a delegation starts is ended, and written again after it")
    void aSummaryMakesWayForADelegation(@TempDir Path tmp) throws Exception {
        var local = new OneSlot(1_500, DelegationBehaviourTest.done("The balance is 48,213.07 CZK."));
        var rig = new LoopRig(tmp, List.of(BANK), 600, local);
        rig.cloud.think.add(call("bank_fetch", Map.of()));
        rig.cloud.think.add(c -> {
            assertTrue(local.summaryStarted.await(5, TimeUnit.SECONDS), "the summary never began");
            return call(AgentAction.DELEGATE, Map.of("goal", "Read {{1}} and say the balance.")).answer(c);
        });
        rig.cloud.think.add(respond("Done."));
        String session = rig.chat.createSession("u1", "Bank");

        AgentResult r = rig.turn(session, "what is my balance?");

        assertEquals("Done.", r.response());
        assertEquals(List.of("summary ended", "turn"), local.finished.subList(0, 2),
                "the delegation did not wait for the summary: " + local.finished);
        assertNotNull(awaitRowIn(rig, session, "**Result 1 (bank_fetch)**").get("private_content"));
        assertTrue(local.finished.contains("summary"), "and the summary was written afterwards: " + local.finished);
        // Mutation: only hold back summaries not yet begun -> the turn waits behind it.
    }

    @Test
    @DisplayName("the local tokens of a summary written after its task ended are counted: per day, and on the task's page")
    void aLateSummaryIsCounted(@TempDir Path tmp) throws Exception {
        var release = new CountDownLatch(1);
        var local = new Local(c -> {
            assertTrue(release.await(10, TimeUnit.SECONDS));
            return Replies.of(SUMMARY, 400, 30);
        });
        var rig = new LoopRig(tmp, List.of(BANK), 600, local);
        rig.cloud.think.add(call("bank_fetch", Map.of()));
        rig.cloud.think.add(respond("Fetched."));
        String session = rig.chat.createSession("u1", "Bank");

        AgentResult r = rig.turn(session, "fetch my statement");
        release.countDown();
        awaitRowIn(rig, session, "**Result 1 (bank_fetch)**");

        var today = rig.events.tokenUsageDetailToday("u1");
        assertEquals(430L, ((Number) today.get("local_tokens")).longValue(), today.toString());
        assertEquals(1L, ((Number) today.get("task_count")).longValue(), "one task, however its tokens are kept");
        var outcome = (Map<?, ?>) new TaskTraceService(rig.events).trace("u1", r.taskId()).orElseThrow().get("outcome");
        assertEquals(430L, ((Number) outcome.get("localTokens")).longValue(), outcome.toString());
        // Mutation: count it only on the task's counter -> the ending was written: 0 local tokens.
    }

    @Test
    @DisplayName("a Stop ends the summary under way of a task that has already ended")
    void aStopReachesAnEndedTasksSummary(@TempDir Path tmp) throws Exception {
        var calling = new CountDownLatch(1);
        var ended = new CountDownLatch(1);
        var local = new Local(c -> {
            c.progress().calling(ended::countDown);    // the call's cancel, as a provider hands it over
            calling.countDown();
            assertTrue(ended.await(10, TimeUnit.SECONDS), "the stop never ended the call");
            c.progress().onProgress();                  // asked once more, as a provider does
            return Replies.of(SUMMARY, 400, 30);
        });
        var rig = new LoopRig(tmp, List.of(BANK), 600, local);
        rig.cloud.think.add(call("bank_fetch", Map.of()));
        rig.cloud.think.add(respond("Fetched."));
        String session = rig.chat.createSession("u1", "Bank");

        AgentResult r = rig.turn(session, "fetch my statement");
        assertEquals(AgentResult.TerminationReason.COMPLETED, r.terminationReason());
        assertTrue(calling.await(5, TimeUnit.SECONDS), "the summary never began");
        rig.cancellation.requestAll("u1", "you pressed Stop");

        assertTrue(ended.await(2, TimeUnit.SECONDS), "the Stop did not end it");
        assertTrue(String.valueOf(awaitRowIn(rig, session, "**Result 1 (bank_fetch)**").get("content"))
                .endsWith("— the local model could not summarise it: the task was stopped."));
        // Mutation: end only the calls of tasks still running -> it waits out its call.
    }

    @Test
    @DisplayName("a summary for a chat the owner deleted meanwhile is neither saved nor shown")
    void noRowForADeletedChat(@TempDir Path tmp) throws Exception {
        var release = new CountDownLatch(1);
        var local = new Local(c -> {
            assertTrue(release.await(10, TimeUnit.SECONDS));
            return Replies.of(SUMMARY, 400, 30);
        });
        var rig = new LoopRig(tmp, List.of(BANK), 600, local);
        var seen = rig.statuses();
        String deleted = rig.chat.createSession("u1", "Bank");
        rig.cloud.think.add(call("bank_fetch", Map.of()));
        rig.cloud.think.add(respond("Fetched."));
        rig.turn(deleted, "fetch my statement");
        rig.chat.deleteSession("u1", deleted);

        // A second summary, in a chat that stays: written after the first, in order.
        String kept = rig.chat.createSession("u1", "Bank again");
        rig.cloud.think.add(call("bank_fetch", Map.of()));
        rig.cloud.think.add(respond("Fetched again."));
        rig.turn(kept, "fetch it again");
        release.countDown();
        awaitRowIn(rig, kept, "**Result 1 (bank_fetch)**");
        Thread.sleep(200);

        assertEquals(List.of(), rig.jdbc.queryForList("SELECT role, content FROM conversations WHERE session_id = ?",
                deleted), "nothing is saved into the deleted chat");
        assertTrue(seen.stream().noneMatch(m -> m.type() == StatusMessage.Type.PROGRESS_MESSAGE
                && m.text().startsWith("**Result") && deleted.equals(m.data().get("sessionId"))), "nor shown");
        // Mutation: save it unconditionally -> a row of no chat holds the bank summary.
    }
}
