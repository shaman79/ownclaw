package com.ownclaw.agent;

import com.ownclaw.agent.SkillSelfRepairTest.Fix;
import com.ownclaw.core.ResultDelivery;
import com.ownclaw.llm.Replies;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static com.ownclaw.agent.LoopRig.call;
import static com.ownclaw.agent.LoopRig.respond;
import static com.ownclaw.agent.SkillSelfRepairTest.DEGRADED;
import static com.ownclaw.agent.SkillSelfRepairTest.MENU;
import static com.ownclaw.agent.SkillSelfRepairTest.PROBLEM;
import static com.ownclaw.agent.SkillSelfRepairTest.TASK;
import static com.ownclaw.agent.SkillSelfRepairTest.problems;
import static com.ownclaw.agent.SkillSelfRepairTest.scriptFixAndCheck;
import static com.ownclaw.agent.SkillSelfRepairTest.verdict;
import static org.junit.jupiter.api.Assertions.*;

/**
 * What an unattended answer owes whoever reads it by code, through the real loop, the real task
 * queue and the real self-repair: a repair how its check went, a run what did not do its whole
 * job. Both are parameters of respond, and a model offered tools natively may end its turn in
 * prose, which carries neither. From 14 to 18 Nov 2026 three of four repairs that completed
 * ended so and were recorded unverified -- one had fixed its skill and said "the check run
 * confirmed it", the owner read "Changed, but not checked", and the skill's next two problems
 * were left to him. Such an answer is kept, the model is asked once, and the reply ends the task.
 */
class AnswerOwedTest {

    /** A repair's answer in prose: what it found, what it changed, and that the check passed. */
    static final String ANSWER = "The site answered HTTP 455 to the default client, so the skill fell back to a "
            + "logo and read nothing from it.\n\nIt now asks as a browser. Run again with debug on, both menus are "
            + "read: the check run confirmed it.";

    /** What a repair that owes its verdict is told, after "Not delivered yet: ". */
    static final String VERDICT_OWED = "Not delivered yet: this answer does not say how the check of your change went, "
            + "and code cannot tell: '" + MENU + "' ran after your last change and reported success, which a run that "
            + "leaves part of its job undone does too. Call respond with 'check' set to 'passed' -- run after your last "
            + "change, it did its whole job -- 'failed' -- it still did not -- or 'not_run'. Leave 'message' empty to "
            + "keep the answer you gave: it is then delivered whole, as you wrote it. A message you give replaces it.";

    /** What unattended work that answered in prose is told. */
    static final String PROBLEMS_OWED = "Not delivered yet: you answered in text, without calling respond, so the "
            + "answer could not say which tools did not do their whole job. Call respond with 'problems': an entry -- "
            + "'tool' its exact name, 'what' one sentence -- for every tool that ran in this task but did not do its "
            + "whole job (a part missing, a fallback taken, a warning or an error in its output, data stale or wrong, "
            + "even when it reported success), or an empty list when every tool did. Each skill listed is repaired "
            + "after this task. Leave 'message' empty to keep the answer you gave: it is then delivered whole, as you "
            + "wrote it. A message you give replaces it.";

    /** The model ends its turn with text and no tool call. */
    static LoopRig.Reply prose(String text) {
        return c -> Replies.of(text, 1_000, 100, 0, 0, "end_turn");
    }

    /** The model's reply holds nothing: no text and no tool call. */
    static LoopRig.Reply empty() {
        return c -> Replies.of("", 1_000, 0, 0, 0, "end_turn");
    }

    private Fix fix;

    @AfterEach
    void stopQueue() {
        if (fix != null) fix.rig.queue.stop();
    }

    /** What the cloud was sent with think call {@code n}, from 0. */
    private String sent(int n) {
        return String.join("\n", LoopRig.userParts(fix.rig.cloud.calls("think").get(n)));
    }

    private int thinks() {
        return fix.rig.cloud.calls("think").size();
    }

    /** The step rows of a task that say why they ran nothing, in order. */
    private List<String> reasons(String taskId) {
        return fix.rig.jdbc.queryForList("SELECT json_extract(details, '$.reason') FROM events WHERE event_type = "
                + "'step' AND task_id = ? AND json_extract(details, '$.reason') IS NOT NULL ORDER BY id", String.class, taskId);
    }

    /** The last thing the owner was told in the chat of scheduled results. */
    private String note() {
        return fix.told().get(fix.told().size() - 1);
    }

    // ───────────────────────── a repair's verdict ─────────────────────────

    @Test
    @DisplayName("a repair that ends in prose after a check that passed is asked once, and 'check: passed' with an empty message makes it fixed: the owner's report is the prose answer, whole")
    void aProseEndingIsAskedForItsVerdict(@TempDir Path tmp) throws Exception {
        fix = new Fix(tmp);
        fix.rig.queue.start();
        fix.scriptMenu(true);
        scriptFixAndCheck(fix.rig, Map.of("debug", true), prose(ANSWER));
        fix.rig.cloud.think.add(verdict("", "passed"));
        fix.unattended(TASK);
        var row = fix.ended(fix.lastId());

        assertEquals("fixed", row.get("outcome"), String.valueOf(row.get("report")));
        assertEquals(ANSWER, row.get("report"), "the answer it gave, whole");
        assertEquals(1, ((Number) row.get("completed")).intValue());
        assertEquals(7, thinks(), "the run's two steps, the repair's four, and the one question");
        // What the model was told, where it is told every step that ran nothing: after its own answer.
        var asked = fix.rig.cloud.calls("think").get(6);
        assertTrue(sent(6).contains(VERDICT_OWED), sent(6));
        var respond = new java.util.LinkedHashMap<String, Object>();
        respond.put("tool", "respond");
        respond.put("params", Map.of("message", ANSWER));
        String replayed = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(respond);
        assertTrue(asked.messages().stream().anyMatch(m -> m.role() == com.ownclaw.llm.LlmMessage.Role.ASSISTANT
                && m.content().equals(replayed)), "its answer is replayed as the respond it stands for: " + asked.messages());
        // The owner: fixed and checked, with the answer as it was written.
        String note = note();
        assertTrue(note.contains("Fixed and checked: it was run again, and the problem is gone."), note);
        assertTrue(note.contains("What the repair said:\n\n" + ANSWER), note);
        assertFalse(note.contains("Not delivered yet"), note);
        // The task page shows the question as the step it was.
        assertEquals(List.of(VERDICT_OWED), reasons((String) row.get("repair_task_id")));
        // Mutation: the loop delivering an answer that owes something as it did (the "not the ending
        // yet" block dropped) -> unverified, "Changed, but not checked", and six think calls.
    }

    @Test
    @DisplayName("the same with 'check: failed': not fixed, and the report is the prose answer")
    void aFailedVerdictIsNotFixed(@TempDir Path tmp) throws Exception {
        String said = "I changed the headers and ran it again with debug on: Cafe Two is still blocked, so the "
                + "check failed.";
        fix = new Fix(tmp);
        fix.fixedOutput = DEGRADED;                 // changed, and run again it "works" as it did: degraded
        fix.rig.queue.start();
        fix.scriptMenu(true);
        scriptFixAndCheck(fix.rig, Map.of("debug", true), prose(said));
        fix.rig.cloud.think.add(verdict("", "failed"));
        fix.unattended(TASK);
        var row = fix.ended(fix.lastId());

        assertEquals("not_fixed", row.get("outcome"), String.valueOf(row.get("report")));
        assertEquals(said, row.get("report"));
        assertTrue(note().contains("Changed, but run again it still shows the problem."), note());
        // Mutation: the ending of a kept answer not reading the reply's check (setReportedCheck only
        // where the reply's own message is the answer) -> unverified.
    }

    @Test
    @DisplayName("a respond call that leaves 'check' out is asked the same way, and a message given again with the verdict is the answer")
    void aCallWithoutCheckIsAskedAndAMessageGivenAgainReplacesIt(@TempDir Path tmp) throws Exception {
        fix = new Fix(tmp);
        fix.rig.queue.start();
        fix.scriptMenu(true);
        scriptFixAndCheck(fix.rig, Map.of("debug", true), respond("Changed the headers and ran it."));
        fix.rig.cloud.think.add(verdict("It was blocked with HTTP 455; it asks as a browser now, and run again both "
                + "menus are read.", "passed"));
        fix.unattended(TASK);
        var row = fix.ended(fix.lastId());

        assertEquals("fixed", row.get("outcome"), String.valueOf(row.get("report")));
        assertEquals("It was blocked with HTTP 455; it asks as a browser now, and run again both menus are read.",
                row.get("report"), "the message given with the verdict");
        assertTrue(sent(6).contains(VERDICT_OWED), sent(6));
        // Mutation: the kept answer the task's answer whatever the reply writes (another = false) ->
        // the report is "Changed the headers and ran it.".
    }

    @Test
    @DisplayName("asked once: a second answer with no usable check ends the repair unverified as before, with the answer it gave first")
    void aSecondAnswerWithoutCheckEndsIt(@TempDir Path tmp) throws Exception {
        fix = new Fix(tmp);
        fix.rig.queue.start();
        fix.scriptMenu(true);
        scriptFixAndCheck(fix.rig, Map.of("debug", true), prose(ANSWER));
        fix.rig.cloud.think.add(prose("The check passed."));
        fix.unattended(TASK);
        var row = fix.ended(fix.lastId());

        assertEquals("unverified", row.get("outcome"));
        assertEquals(ANSWER, row.get("report"), "the answer it gave, not the reply that again said nothing code reads");
        assertEquals(7, thinks(), "asked once");
        String note = note();
        assertTrue(note.contains("Changed, but not checked: the repair did not run it again after its change and say "
                + "the check passed."), note);
        assertTrue(note.contains(ANSWER), note);
        // A check that is none of the three is no check either.
        assertNull(SkillSelfRepair.usableCheck("ok"));
        assertNull(SkillSelfRepair.usableCheck(true));
        assertNull(SkillSelfRepair.usableCheck(null));
        assertEquals("passed", SkillSelfRepair.usableCheck(" passed "));
        // Mutation: any message the reply writes taken for the answer (another without its test of
        // what the reply brought) -> the report is "The check passed.".
    }

    @Test
    @DisplayName("a repair that goes back to work when asked, and then answers in prose, ends with that answer: it was written after the work, and it is not asked a second time")
    void workAfterTheQuestionSupersedesTheKeptAnswer(@TempDir Path tmp) throws Exception {
        String later = "Run once more without debug it reads both menus as well, so the change holds.";
        fix = new Fix(tmp);
        fix.rig.queue.start();
        fix.scriptMenu(true);
        scriptFixAndCheck(fix.rig, Map.of("debug", true), prose(ANSWER));
        fix.rig.cloud.think.add(call(MENU, Map.of()));       // asked for its verdict, it runs the skill again
        fix.rig.cloud.think.add(prose(later));
        fix.unattended(TASK);
        var row = fix.ended(fix.lastId());

        assertEquals(later, row.get("report"), "what it wrote after its last run");
        assertEquals("unverified", row.get("outcome"), "it never said how the check went");
        assertEquals(8, thinks(), "asked once: the second prose answer ended it");
        assertEquals(List.of(VERDICT_OWED), reasons((String) row.get("repair_task_id")));
        // Mutation: the kept answer standing through work (another without "or the kept answer no
        // longer stands") -> the report is the answer it gave before it ran the skill again.
    }

    @Test
    @DisplayName("a repair whose run after the question fails is not fixed by code, and its report is what it wrote after that run")
    void aFailedRunAfterTheQuestionIsNotFixed(@TempDir Path tmp) throws Exception {
        String later = "Run once more it failed outright with a connection reset: the change does not hold.";
        fix = new Fix(tmp);
        fix.rig.queue.start();
        fix.scriptMenu(true);
        scriptFixAndCheck(fix.rig, Map.of("debug", true), prose(ANSWER));
        fix.rig.cloud.think.add(c -> {
            fix.menuFails.set(true);
            return call(MENU, Map.of("debug", true)).answer(c);
        });
        fix.rig.cloud.think.add(prose(later));
        fix.unattended(TASK);
        var row = fix.ended(fix.lastId());

        assertEquals(later, row.get("report"));
        assertEquals("not_fixed", row.get("outcome"), "by code: its last run after the change failed");
        assertTrue(note().contains("Changed, but run again it still shows the problem."), note());
        assertFalse(note().contains("the check run confirmed it"), "the owner reads the answer the failed run superseded: " + note());
        // Mutation: as aCallWithoutCheckIsAskedAndAMessageGivenAgainReplacesIt's -> the owner reads
        // "the check run confirmed it" under "run again it still shows the problem".
    }

    @Test
    @DisplayName("the check is awaited exactly where the outcome reads it: after a change, a run of the skill since that worked, and only for a skill the repair may run -- as it is declared now too")
    void theCheckIsAwaitedWhereTheOutcomeReadsIt() {
        var calls = new java.util.ArrayList<Map<String, Object>>();
        var free = SkillSelfRepairTest.skill(MENU, false, List.of(), false, calls,
                p -> com.ownclaw.agent.tools.ToolResult.success("ok"));
        // The skill as another task rewrote it while the repair ran: it now says it has side effects.
        var rewritten = SkillSelfRepairTest.skill(MENU, true, List.of(), false, calls,
                p -> com.ownclaw.agent.tools.ToolResult.success("ok"));
        var isPublic = new Artifact.Decision(com.ownclaw.privacy.Label.PUBLIC, List.of());
        var ctx = new AgentContext("u1", "t1", "Repair the skill '" + MENU + "'.");
        assertFalse(SkillSelfRepair.awaitsCheck(ctx, free), "no repair");
        ctx.setRepair(SkillSelfRepairTest.job(MENU, false));
        ctx.addArtifact(MENU, Map.of(), Map.of(), "{\"menus\": 2}", true, isPublic);
        assertFalse(SkillSelfRepair.awaitsCheck(ctx, free), "a run before any change checks nothing");
        ctx.skillChanged();
        assertFalse(SkillSelfRepair.awaitsCheck(ctx, free), "changed, and not run since");
        ctx.addArtifact(MENU, Map.of(), Map.of(), "Traceback", false, isPublic);
        assertFalse(SkillSelfRepair.awaitsCheck(ctx, free), "its run since failed: not fixed whatever it says");
        ctx.addArtifact("web_snapshot", Map.of(), Map.of(), "<html/>", true, isPublic);
        assertFalse(SkillSelfRepair.awaitsCheck(ctx, free), "another tool's run is no run of the skill");
        ctx.addArtifact(MENU, Map.of(), Map.of(), "{\"menus\": 2}", true, isPublic);
        assertTrue(SkillSelfRepair.awaitsCheck(ctx, free), "its last run since the change worked: only it can say");
        assertFalse(SkillSelfRepair.awaitsCheck(ctx, rewritten), "the outcome reads no check of a skill that may "
                + "not be run as it is declared now");
        ctx.skillChanged();
        assertFalse(SkillSelfRepair.awaitsCheck(ctx, free), "changed again, and not run since");
        // Mutation: awaitsCheck without its test of whether the skill may be run -> asked of the
        // rewritten skill, whose check the outcome never reads.
    }

    @Test
    @DisplayName("a repair's respond requires 'check'; unattended work's 'problems' stays optional, and none listed means none")
    void checkIsRequiredInTheRepairsSchema() {
        assertEquals(List.of("message", SkillSelfRepair.CHECK), SpecialActionSchemas.RESPOND_REPAIR.inputSchema().get("required"));
        assertEquals(List.of("message"), SpecialActionSchemas.RESPOND_UNATTENDED.inputSchema().get("required"));
        // Mutation: 'check' optional in RESPOND_REPAIR, as it was -> required is ["message"].
    }

    @Test
    @DisplayName("where code decides alone, nothing is asked: a repair that changed nothing, one that never ran its change, and one whose run failed end by their prose answer")
    void nothingIsAskedWhereTheVerdictDecidesNothing(@TempDir Path tmp) throws Exception {
        // Changed nothing: unchanged whatever it says.
        fix = new Fix(tmp.resolve("unchanged"));
        fix.rig.queue.start();
        fix.scriptMenu(true);
        fix.rig.cloud.think.add(call(AgentAction.SKILL_MANAGE, Map.of("action", "read", "name", MENU)));
        fix.rig.cloud.think.add(prose("The site blocks every client it does not know; nothing in the skill can change that."));
        fix.unattended(TASK);
        var unchanged = fix.ended(fix.lastId());
        assertEquals("unchanged", unchanged.get("outcome"));
        assertEquals(4, thinks(), "asked how a check of no change went");
        fix.rig.queue.stop();

        // Changed, and never run after the change: unverified whatever it says.
        fix = new Fix(tmp.resolve("not-run"));
        fix.rig.queue.start();
        fix.scriptMenu(true);
        scriptFixAndCheck(fix.rig, null, prose("It asks as a browser now. I did not run it."));
        fix.unattended(TASK);
        var notRun = fix.ended(fix.lastId());
        assertEquals("unverified", notRun.get("outcome"));
        assertEquals(5, thinks(), "asked how a check it did not run went");
        fix.rig.queue.stop();

        // Changed, run, and the run failed: not fixed whatever it says.
        fix = new Fix(tmp.resolve("run-failed"));
        fix.rig.queue.start();
        fix.scriptMenu(true);
        scriptFixAndCheck(fix.rig, null, null);
        fix.rig.cloud.think.add(c -> {
            fix.menuFails.set(true);
            return call(MENU, Map.of("debug", true)).answer(c);
        });
        fix.rig.cloud.think.add(prose("It fails outright now."));
        fix.unattended(TASK);
        var failed = fix.ended(fix.lastId());
        assertEquals("not_fixed", failed.get("outcome"));
        assertEquals(6, thinks(), "asked how a check went that code saw fail");
        // Mutation: awaitsCheck true for every repair that may run its skill -> each of the three is
        // asked, and its script has no reply for the question.
    }

    @Test
    @DisplayName("a skill its repair may not run is not asked how a check went that could not be made")
    void aSkillThatIsNeverRunIsNotAsked(@TempDir Path tmp) throws Exception {
        var sent = new java.util.concurrent.CopyOnWriteArrayList<Map<String, Object>>();
        fix = new Fix(tmp, SkillSelfRepairTest.skill("menu_mailer", true, List.of("SMTP_PASS"), false, sent,
                p -> com.ownclaw.agent.tools.ToolResult.success("{\"ok\": true, \"warning\": \"one menu missing\"}")));
        fix.rig.queue.start();
        fix.rig.cloud.think.add(call("menu_mailer", Map.of()));
        fix.rig.cloud.think.add(problems("Sent, one menu missing.", List.of(Map.of("tool", "menu_mailer",
                "what", "It sent the mail without the second menu."))));
        fix.rig.cloud.think.add(call(AgentAction.SKILL_CREATE, Map.of("name", "menu_mailer",
                "description", "Mail the menus; wait for both.", "parameters", "{}")));
        fix.rig.cloud.codegen.add(SkillCodegenTest.finished(SkillCodegenTest.module("# v2")));
        fix.rig.cloud.think.add(prose("It mailed before the second menu arrived; now it waits for both."));
        fix.unattended("Using menu_mailer, mail me the menus.");
        var row = fix.ended(fix.lastId());

        assertEquals("unverified", row.get("outcome"));
        assertEquals(4, thinks());
        assertTrue(note().contains("Changed, but not run again to check it: it holds credentials (SMTP_PASS)"), note());
        // Mutation: every repair's answer without a check asked for one, whatever it could have
        // checked (awaitsCheck true) -> a fifth think call, and the script has no reply for it.
    }

    @Test
    @DisplayName("the question never costs a repair its answer: when the reply to it never comes, the repair ends completed with the answer it gave, and no other model is asked")
    void theReplyThatNeverComesLeavesTheAnswer(@TempDir Path tmp) throws Exception {
        var local = LocalModeTest.localModel();     // there, and able to take a task over: it is not handed this one
        fix = new Fix(tmp, null, registry -> null, local);
        fix.rig.queue.start();
        fix.scriptMenu(true);
        scriptFixAndCheck(fix.rig, Map.of("debug", true), prose(ANSWER));
        fix.rig.cloud.think.add(LocalModeTest.noInternet());    // the question's call
        fix.rig.cloud.think.add(LocalModeTest.noInternet());    // asked once more, as any broken connection is
        fix.unattended(TASK);
        var row = fix.ended(fix.lastId());

        assertEquals("unverified", row.get("outcome"), "as before there was a question");
        assertEquals(1, ((Number) row.get("completed")).intValue(), "it ran to its answer");
        assertEquals(ANSWER, row.get("report"));
        assertTrue(local.calls.isEmpty(), "the local model was asked about a repair it did not do: " + local.calls);
        String note = note();
        assertTrue(note.contains("Changed, but not checked"), note);
        assertTrue(note.contains("What the repair said:\n\n" + ANSWER), note);
        assertFalse(note.contains("Written by the local model") || note.contains("**Stopped:**"), note);
        // Mutations: the task's ending as the loop left it (orKeptAnswer returning it unchanged) -> the
        // report is "**Stopped:** the cloud model (anthropic) could not be used ...", told as a repair
        // that did not finish; the task free to move to the local model while its answer stands
        // (canGoLocal without that test) -> the local model is asked.
    }

    @Test
    @DisplayName("replies to the question that hold nothing end the repair with the answer it gave, not as a task that produced nothing")
    void emptyRepliesLeaveTheAnswer(@TempDir Path tmp) throws Exception {
        fix = new Fix(tmp);
        fix.rig.queue.start();
        fix.scriptMenu(true);
        scriptFixAndCheck(fix.rig, Map.of("debug", true), prose(ANSWER));
        fix.rig.cloud.think.add(empty());
        fix.rig.cloud.think.add(empty());
        fix.unattended(TASK);
        var row = fix.ended(fix.lastId());

        assertEquals("unverified", row.get("outcome"));
        assertEquals(1, ((Number) row.get("completed")).intValue());
        assertEquals(ANSWER, row.get("report"));
        // Mutation: as theReplyThatNeverComesLeavesTheAnswer's first -> "The model produced nothing
        // that could be run or delivered 3 times in a row."
    }

    @Test
    @DisplayName("a question to the user in reply, which nobody can answer, ran nothing: the answer the repair gave still stands when its next reply brings no check")
    void aQuestionInReplyLeavesTheAnswerStanding(@TempDir Path tmp) throws Exception {
        fix = new Fix(tmp);
        fix.rig.queue.start();
        fix.scriptMenu(true);
        scriptFixAndCheck(fix.rig, Map.of("debug", true), prose(ANSWER));
        fix.rig.cloud.think.add(call(AgentAction.ASK_USER, Map.of("message", "Which check do you mean?")));
        fix.rig.cloud.think.add(prose("The check passed."));
        fix.unattended(TASK);
        var row = fix.ended(fix.lastId());

        assertEquals(ANSWER, row.get("report"), "the answer it gave: nothing ran after it");
        assertEquals("unverified", row.get("outcome"));
        assertEquals(8, thinks());
        // Mutation: the step that tells unattended work nobody can answer not counted as one that
        // ran nothing (its context.ranNothing() dropped) -> the report is "The check passed.".
    }

    @Test
    @DisplayName("a repair that answers the question only with questions to a user who is not there ends completed with the answer it gave, not waiting for an answer nobody gives")
    void questionsInReplyDoNotCostTheAnswer(@TempDir Path tmp) throws Exception {
        fix = new Fix(tmp);
        fix.rig.queue.start();
        fix.scriptMenu(true);
        scriptFixAndCheck(fix.rig, Map.of("debug", true), prose(ANSWER));
        fix.rig.cloud.think.add(call(AgentAction.ASK_USER, Map.of("message", "Which check do you mean?")));
        fix.rig.cloud.think.add(call(AgentAction.ASK_USER, Map.of("message", "Which check do you mean?")));
        fix.unattended(TASK);
        var row = fix.ended(fix.lastId());

        assertEquals(ANSWER, row.get("report"));
        assertEquals("unverified", row.get("outcome"));
        assertEquals(1, ((Number) row.get("completed")).intValue(), "it ran to its answer");
        // Mutation: a task left waiting for the user although its answer stands (orKeptAnswer passing
        // NEEDS_INPUT on) -> the repair is told as one that did not finish, its report the question.
    }

    @Test
    @DisplayName("the owner's Stop while the repair is being asked ends it as a Stop ends any task: stopped, no verdict, whatever it had answered")
    void aStopWhileAskedIsAStop(@TempDir Path tmp) throws Exception {
        fix = new Fix(tmp);
        fix.rig.queue.start();
        fix.scriptMenu(true);
        scriptFixAndCheck(fix.rig, Map.of("debug", true), prose(ANSWER));
        fix.rig.cloud.think.add(c -> {
            fix.rig.cancellation.request("u1", (String) fix.repairRow(0).get("repair_task_id"), "you pressed Stop");
            return empty().answer(c);
        });
        fix.unattended(TASK);
        var row = fix.ended(fix.lastId());

        assertEquals("unverified", row.get("outcome"));
        assertEquals(0, ((Number) row.get("completed")).intValue(), "stopped is no verdict");
        assertTrue(String.valueOf(row.get("report")).startsWith("**Stopped:**"), String.valueOf(row.get("report")));
        assertTrue(note().contains("Changed, but the repair did not finish, so the change was not checked. The next "
                + "time the problem shows, a repair starts again."), note());
        // Mutation: a Stop overridden by the answer the task is keeping (orKeptAnswer taking CANCELLED
        // for an ending to replace) -> the stopped repair is recorded completed, and judged.
    }

    @Test
    @DisplayName("an answer that follows two steps that ran nothing is not asked about: one more would end the task, so it is the ending, as it was")
    void notAskedWhenTheQuestionWouldEndTheTask(@TempDir Path tmp) throws Exception {
        fix = new Fix(tmp);
        fix.rig.queue.start();
        fix.scriptMenu(true);
        scriptFixAndCheck(fix.rig, Map.of("debug", true), respond(""));     // not delivered: empty
        fix.rig.cloud.think.add(respond(""));                                // again
        fix.rig.cloud.think.add(prose(ANSWER));
        fix.unattended(TASK);
        var row = fix.ended(fix.lastId());

        assertEquals("unverified", row.get("outcome"));
        assertEquals(ANSWER, row.get("report"));
        assertEquals(8, thinks());
        assertTrue(reasons((String) row.get("repair_task_id")).stream().noneMatch(r -> r.startsWith("Not delivered yet")),
                "a question nobody was asked is on the record: " + reasons((String) row.get("repair_task_id")));
        // Mutation: the question asked however many steps ran nothing before it -> a third such step
        // is recorded, "Not delivered yet: ...", with no call to answer it.
    }

    // ───────────────────────── unattended work's problems ─────────────────────────

    static final String SAID = "Here are today's menus.\n\nCafe One: soup, schnitzel.\nCafe Two: could not be read.";

    @Test
    @DisplayName("an unattended run that ends in prose is asked once, and the problems it then lists start a repair; its answer is the prose, whole")
    void aProseEndingIsAskedForProblems(@TempDir Path tmp) throws Exception {
        fix = new Fix(tmp);
        fix.rig.cloud.think.add(call(MENU, Map.of()));
        fix.rig.cloud.think.add(prose(SAID));
        fix.rig.cloud.think.add(problems("", List.of(Map.of("tool", MENU, "what", PROBLEM))));
        AgentResult r = fix.unattended(TASK);

        assertTrue(r.success(), r.response());
        assertEquals(3, thinks());
        assertTrue(sent(2).contains(PROBLEMS_OWED), sent(2));
        var rows = fix.repairs();
        assertEquals(1, rows.size(), rows.toString());
        assertEquals("running", rows.get(0).get("outcome"));
        assertEquals(PROBLEM, rows.get(0).get("problem"));
        assertTrue(r.response().startsWith(SAID + "\n\n_🔧 Self-repair started for " + MENU), r.response());
        // Mutation: the engine not saying an answer came in prose (ThinkResult.inProse false) -> the
        // run ends on its second step, and nothing starts.
    }

    @Test
    @DisplayName("asked once: a second answer in prose ends the run with the first, and nothing is repaired -- as before")
    void aSecondProseAnswerEndsTheRun(@TempDir Path tmp) throws Exception {
        fix = new Fix(tmp);
        fix.rig.cloud.think.add(call(MENU, Map.of()));
        fix.rig.cloud.think.add(prose(SAID));
        fix.rig.cloud.think.add(prose("Every tool did its whole job."));
        AgentResult r = fix.unattended(TASK);

        assertEquals(SAID, r.response());
        assertEquals(3, thinks());
        assertTrue(fix.repairs().isEmpty(), fix.repairs().toString());
        // Mutation: as aSecondAnswerWithoutCheckEndsIt's -> the run's answer is "Every tool did its whole job.".
    }

    @Test
    @DisplayName("a respond call that lists no problems says there are none, as it always did: it is not asked")
    void aRespondCallWithoutProblemsIsNotAsked(@TempDir Path tmp) throws Exception {
        fix = new Fix(tmp);
        fix.rig.cloud.think.add(call(MENU, Map.of()));
        fix.rig.cloud.think.add(respond(SAID));
        AgentResult r = fix.unattended(TASK);

        assertEquals(SAID, r.response());
        assertEquals(2, thinks());
        assertTrue(fix.repairs().isEmpty(), fix.repairs().toString());
        // Mutation: every unattended answer without 'problems' asked, a respond call's too (owed
        // without its test of prose) -> a third think call, and the script has no reply for it.
    }

    @Test
    @DisplayName("an unattended run in which no skill of the account's ran is not asked: its problems could name nothing")
    void notAskedWhereNoSkillRan(@TempDir Path tmp) throws Exception {
        fix = new Fix(tmp);
        fix.rig.cloud.think.add(call(AgentAction.SKILL_MANAGE, Map.of("action", "list")));
        fix.rig.cloud.think.add(prose("You have two skills."));
        AgentResult r = fix.unattended("List my skills.");

        assertEquals("You have two skills.", r.response());
        assertEquals(2, thinks());
        // Mutation: owed without its test that a skill of the account's ran -> a third think call.
    }

    @Test
    @DisplayName("a chat's prose answer is never asked: its owner reads it himself -- whatever ran, and with self-repair on")
    void aChatIsNeverAsked(@TempDir Path tmp) throws Exception {
        fix = new Fix(tmp);
        String session = fix.rig.chat.createSession("u1", "Lunch");
        fix.rig.cloud.think.add(call(MENU, Map.of()));
        fix.rig.cloud.think.add(prose(SAID));
        AgentResult r = fix.rig.turn(session, "What's for lunch today?");

        assertEquals(AgentResult.TerminationReason.COMPLETED, r.terminationReason(), r.response());
        assertEquals(SAID, r.response());
        assertEquals(2, thinks());
        assertTrue(fix.repairs().isEmpty());
        // Mutation: owed asking of attended work too (readsProblems without its test of unattended)
        // -> a third think call, and the script has no reply for it.
    }

    @Test
    @DisplayName("an ops run's prose answer is not asked either: its problems start no repair")
    void anOpsRunIsNotAsked(@TempDir Path tmp) throws Exception {
        fix = new Fix(tmp);
        fix.rig.cloud.think.add(call(MENU, Map.of()));
        fix.rig.cloud.think.add(prose(SAID));
        AgentResult r = fix.rig.loop.executeFull("u1", TASK, true, null, List.of(), TaskChat.Channel.OPS);

        assertEquals(SAID, r.response());
        assertEquals(2, thinks());
        // Mutation: owed asking of an ops run too (its test of readsProblems without the one of ops)
        // -> a third think call, and the script has no reply for it.
    }

    @Test
    @DisplayName("switched off, nothing is asked: nothing would read the answer")
    void switchedOffNothingIsAsked(@TempDir Path tmp) throws Exception {
        fix = new Fix(tmp);
        fix.rig.config.getSelfRepair().setEnabled(false);
        fix.rig.cloud.think.add(call(MENU, Map.of()));
        fix.rig.cloud.think.add(prose(SAID));
        AgentResult r = fix.unattended(TASK);

        assertEquals(SAID, r.response());
        assertEquals(2, thinks());
        // Mutation: owed asking with self-repair off (its test of readsProblems without the switch)
        // -> a third think call, and the script has no reply for it.
    }

    @Test
    @DisplayName("an error while working out what an answer owes costs the task nothing: it is asked nothing, and ends with its answer")
    void anErrorDecidingAsksNothing(@TempDir Path tmp) throws Exception {
        fix = new Fix(tmp);
        fix.rig.cloud.think.add(call(MENU, Map.of()));
        fix.rig.cloud.think.add(c -> {
            // From here, reading whose skill it is fails, as a disk that cannot be read would.
            fix.rig.skills.changeable = (name, user) -> {
                throw new IllegalStateException("SKILL.yaml cannot be read");
            };
            return prose(SAID).answer(c);
        });
        AgentResult r = fix.unattended(TASK);

        assertEquals(AgentResult.TerminationReason.COMPLETED, r.terminationReason(), r.response());
        assertEquals(SAID, r.response());
        assertEquals(2, thinks());
        // Mutation: owed letting the error out -> the task ends "an internal error:
        // IllegalStateException", and the answer it had given is lost.
    }

    @Test
    @DisplayName("a question to the user is no answer: unattended work that asks twice ends waiting, and is asked nothing about problems")
    void aQuestionIsNotAnAnswer(@TempDir Path tmp) throws Exception {
        fix = new Fix(tmp);
        fix.rig.cloud.think.add(call(MENU, Map.of()));
        fix.rig.cloud.think.add(call(AgentAction.ASK_USER, Map.of("message", "Which restaurant?")));
        fix.rig.cloud.think.add(call(AgentAction.ASK_USER, Map.of("message", "Which restaurant?")));
        AgentResult r = fix.unattended(TASK);

        assertEquals(AgentResult.TerminationReason.NEEDS_INPUT, r.terminationReason(), r.response());
        assertEquals(3, thinks());
        assertTrue(r.response().startsWith("Which restaurant?"), r.response());
        // Mutation: a question taken where an answer is (the loop's respond branch taking ask_user
        // too) -> the first question ends the task COMPLETED, as its answer.
    }

    @Test
    @DisplayName("on the text protocol an answer that names no tool is asked the same way, and a respond that lists no problems is not")
    void theTextProtocolIsAskedToo(@TempDir Path tmp) throws Exception {
        fix = new Fix(tmp);
        fix.rig.config.getMentor().setNativeTools(false);
        fix.rig.cloud.think.add(c -> Replies.of("{\"tool\": \"" + MENU + "\", \"params\": {}}", 100, 10));
        fix.rig.cloud.think.add(c -> Replies.of("{\"message\": \"Here are today's menus.\"}", 100, 10));
        fix.rig.cloud.think.add(c -> Replies.of("{\"tool\": \"respond\", \"params\": {\"message\": \"\", \"problems\": "
                + "[{\"tool\": \"" + MENU + "\", \"what\": \"" + PROBLEM + "\"}]}}", 100, 10));
        AgentResult r = fix.unattended(TASK);

        assertEquals(3, thinks());
        assertTrue(sent(2).contains(PROBLEMS_OWED), sent(2));
        assertEquals(1, fix.repairs().size());
        assertTrue(r.response().startsWith("Here are today's menus.\n\n_🔧 Self-repair started for " + MENU), r.response());

        // A respond that names its tool and lists nothing: none, and not asked.
        fix.rig.cloud.think.add(c -> Replies.of("{\"tool\": \"" + MENU + "\", \"params\": {}}", 100, 10));
        fix.rig.cloud.think.add(c -> Replies.of("{\"tool\": \"respond\", \"params\": {\"message\": \"Menus.\"}}", 100, 10));
        fix.unattended(TASK);
        assertEquals(5, thinks());
        // Mutation: the text protocol's answers never said to be in prose (the engine's last return
        // with inProse false) -> the run ends on its second reply, and nothing starts.
    }

    @Test
    @DisplayName("unattended work that answers before anything ran is told to do the work first, as before; the answer it gives after the work is the one asked about -- and a skill run by a delegation is a skill that ran")
    void answeringBeforeWorkKeepsItsPrecedence(@TempDir Path tmp) throws Exception {
        var local = LocalModeTest.localModel();
        var calls = new java.util.concurrent.CopyOnWriteArrayList<Map<String, Object>>();
        // A local tier that can take delegated work: the cloud orchestrates, and is given no skill to run.
        var rig = new LoopRig(tmp, List.of(SkillSelfRepairTest.skill(MENU, false, List.of(), false, calls,
                p -> com.ownclaw.agent.tools.ToolResult.success(DEGRADED))), 600, local,
                (registry, config, llm) -> new ThinkingEngine(registry, config, llm) {
                    @Override
                    boolean localTierReady(AgentContext context) {
                        return true;
                    }
                });
        rig.skills.source = name -> "def run(params):\n    return {}\n";
        var repair = new SkillSelfRepair(rig.jdbc, rig.config, rig.skills, rig.registry, rig.queue,
                new ResultDelivery(rig.chat, rig.emitter), rig.chat, rig.events, ZoneId.of("UTC"));
        rig.loop.setSelfRepair(repair);
        try {
            rig.cloud.think.add(prose("I'll fetch today's menus first."));
            rig.cloud.think.add(call(AgentAction.DELEGATE, Map.of("calls", List.of(Map.of("tool", MENU, "params", Map.of())))));
            rig.cloud.think.add(prose(SAID));
            rig.cloud.think.add(problems("", List.of()));
            AgentResult r = rig.loop.executeFull("u1", TASK, true);

            var thinks = rig.cloud.calls("think");
            assertEquals(4, thinks.size());
            String second = String.join("\n", LoopRig.userParts(thinks.get(1)));
            assertTrue(second.contains("You described what you were going to do instead of doing it, and nothing has "
                    + "run yet."), second);
            assertFalse(second.contains("Not delivered yet"), "a plan was kept as the task's answer: " + second);
            assertEquals(1, calls.size(), "the delegation ran the skill");
            assertTrue(String.join("\n", LoopRig.userParts(thinks.get(3))).contains(PROBLEMS_OWED),
                    String.join("\n", LoopRig.userParts(thinks.get(3))));
            assertEquals(SAID, r.response(), "an empty list: nothing to repair, and the answer as it was given");
            assertEquals(0, rig.jdbc.queryForObject("SELECT COUNT(*) FROM skill_repairs", Integer.class));
            // Mutation: an answer before any work let through (nothingRanYet false) -> the plan ends
            // the task on its first step.
        } finally {
            rig.queue.stop();
        }
    }
}
