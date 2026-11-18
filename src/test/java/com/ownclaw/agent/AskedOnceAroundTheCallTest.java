package com.ownclaw.agent;

import com.ownclaw.agent.SkillSelfRepairTest.Fix;
import com.ownclaw.llm.Replies;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static com.ownclaw.agent.LoopRig.call;
import static com.ownclaw.agent.SkillSelfRepairTest.MENU;
import static com.ownclaw.agent.SkillSelfRepairTest.PROBLEM;
import static com.ownclaw.agent.SkillSelfRepairTest.TASK;
import static com.ownclaw.agent.SkillSelfRepairTest.problems;
import static com.ownclaw.agent.SkillSelfRepairTest.scriptFixAndCheck;
import static com.ownclaw.agent.SkillSelfRepairTest.verdict;
import static org.junit.jupiter.api.Assertions.*;

/**
 * What stands around the one call a task is asked in for what its answer owed
 * ({@code AgentLoop#askedOnce}): the count of its tokens, the Stop that comes while its reply is on
 * the way, and the model it is made to. {@link AnswerOwedTest} holds what the reply may be; the
 * second review of the question (18 Nov 2026) found these four ways past "whatever the reply is"
 * that lie beside it.
 */
class AskedOnceAroundTheCallTest {

    static LoopRig.Reply prose(String text) {
        return c -> Replies.of(text, 1_000, 100, 0, 0, "end_turn");
    }

    private Fix fix;

    @AfterEach
    void stopQueue() {
        if (fix != null) fix.rig.queue.stop();
    }

    @Test
    @DisplayName("the asking call is declined and its tokens cannot be counted -- the database is busy: the run still ends completed with the answer it gave")
    void aCountThatFailsAfterADeclineKeepsTheAnswer(@TempDir Path tmp) throws Exception {
        fix = new Fix(tmp);
        fix.rig.jdbc.update("INSERT INTO users (id, display_name) VALUES ('u1', 'Owner')");
        fix.rig.cloud.think.add(call(MENU, Map.of()));
        fix.rig.cloud.think.add(prose(AnswerOwedTest.SAID));
        fix.rig.cloud.think.add(c -> {
            // From here the usage table cannot be written, as a database that is busy cannot.
            fix.rig.jdbc.execute("ALTER TABLE token_usage RENAME TO token_usage_gone");
            return TaskEndToEndTest.declined("cyber").answer(c);
        });
        AgentResult r = fix.unattended(TASK);
        assertEquals(AgentResult.TerminationReason.COMPLETED, r.terminationReason(), r.response());
        assertEquals(AnswerOwedTest.SAID, r.response());
        // Mutation: the count of a declined reply made without a try of its own -> "Stopped: an internal error".
    }

    @Test
    @DisplayName("the reply says what was asked and its tokens cannot be counted: what it said, which was paid for, is still read")
    void aCountThatFailsStillLeavesTheReplyRead(@TempDir Path tmp) throws Exception {
        fix = new Fix(tmp);
        fix.rig.jdbc.update("INSERT INTO users (id, display_name) VALUES ('u1', 'Owner')");
        fix.rig.cloud.think.add(call(MENU, Map.of()));
        fix.rig.cloud.think.add(prose(AnswerOwedTest.SAID));
        fix.rig.cloud.think.add(c -> {
            fix.rig.jdbc.execute("ALTER TABLE token_usage RENAME TO token_usage_gone");
            return problems("", List.of(Map.of("tool", MENU, "what", PROBLEM))).answer(c);
        });
        AgentResult r = fix.unattended(TASK);
        assertEquals(AgentResult.TerminationReason.COMPLETED, r.terminationReason(), r.response());
        assertEquals(1, fix.repairs().size(), "the problems the reply listed were not read");
        // Mutation: the reply read after its tokens are counted -> the count throws first, and no repair starts.
    }

    @Test
    @DisplayName("a Stop that comes while the reply is on its way: the run ends stopped, and what the reply listed starts no repair -- a stopped run never did")
    void aStoppedRunStartsNoRepairFromTheReply(@TempDir Path tmp) throws Exception {
        fix = new Fix(tmp);
        fix.rig.cloud.think.add(call(MENU, Map.of()));
        fix.rig.cloud.think.add(prose(AnswerOwedTest.SAID));
        fix.rig.cloud.think.add(c -> {
            fix.rig.cancellation.requestAll("u1", "you pressed Stop");
            return problems("", List.of(Map.of("tool", MENU, "what", PROBLEM))).answer(c);
        });
        AgentResult r = fix.unattended(TASK);
        assertEquals(AgentResult.TerminationReason.CANCELLED, r.terminationReason(), r.response());
        assertTrue(fix.repairs().isEmpty(), "a repair was started by a run the owner stopped: " + fix.repairs());
        assertFalse(r.response().contains("Self-repair started"), r.response());
        // Mutation: what the reply said put on the task before the Stop is looked at -> one repair, running.
    }

    @Test
    @DisplayName("the question is asked of the model that gave the answer: a cloud that reads as unavailable between the two does not hand it to the local model")
    void theQuestionIsNotHandedToTheLocalModel(@TempDir Path tmp) throws Exception {
        var local = LocalModeTest.localModel();
        fix = new Fix(tmp, null, registry -> null, local);
        fix.rig.queue.start();
        fix.scriptMenu(true);
        scriptFixAndCheck(fix.rig, Map.of("debug", true), c -> {
            fix.rig.cloud.available = false;        // the provider's availability check fails from here
            return prose(AnswerOwedTest.ANSWER).answer(c);
        });
        // Each model's word on the check, were it asked: the cloud's own, which made the repair,
        // and the local model's, which made none of it.
        fix.rig.cloud.think.add(verdict("", "failed"));
        local.think.add(verdict("", "passed"));
        fix.unattended(TASK);
        var row = fix.ended(fix.lastId());
        fix.rig.cloud.available = true;
        assertTrue(local.calls.isEmpty(), "the local model, which did none of the repair, was asked how its check went: "
                + local.calls.size() + " call(s), outcome " + row.get("outcome"));
        assertEquals("not_fixed", row.get("outcome"), "the word of the model that made the repair");
        // Mutation: the provider chosen again for the question -> the local model answers "passed", and the repair is fixed.
    }
}
