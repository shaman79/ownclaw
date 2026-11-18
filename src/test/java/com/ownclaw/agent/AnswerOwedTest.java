package com.ownclaw.agent;

import com.ownclaw.agent.SkillSelfRepairTest.Fix;
import com.ownclaw.core.ResultDelivery;
import com.ownclaw.core.ScheduledTaskService;
import com.ownclaw.llm.EgressRefused;
import com.ownclaw.llm.LlmException;
import com.ownclaw.llm.LlmMessage;
import com.ownclaw.llm.LlmResponse;
import com.ownclaw.llm.Replies;
import com.ownclaw.observability.ChatStatusEmitter.StatusMessage;
import com.ownclaw.observability.TaskTraceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static com.ownclaw.agent.AssistantPartsTest.tool;
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
 * were left to him. Such an answer is kept, the model is asked once, and the reply ends the task
 * whatever it is.
 * <p>
 * "Whatever it is" is the half the first version did not keep. It went back into the loop's
 * steps with the answer "kept while nothing ran", and a reply that was no usable respond left
 * the task running: from there, its review (18 Nov 2026) showed, a line of the reply took the
 * answer's place, the answer was lost to a decline, or the task was handed to the local model,
 * whose sentence became a scheduled run's digest. The reviewer's probes are the tests here that
 * script a reply to the question which does not say what was asked.
 */
class AnswerOwedTest {

    /** A repair's answer in prose: what it found, what it changed, and that the check passed. */
    static final String ANSWER = "The site answered HTTP 455 to the default client, so the skill fell back to a "
            + "logo and read nothing from it.\n\nIt now asks as a browser. Run again with debug on, both menus are "
            + "read: the check run confirmed it.";

    /** How the question begins, for a repair and for a run. */
    static final String KEPT = "Your answer is kept as you wrote it, and it is what is delivered, whole, whatever you "
            + "reply to this. ";

    /** What a repair that owes its verdict is asked. */
    static final String VERDICT_OWED = KEPT + "It did not say how the check of your change went, and code cannot "
            + "tell: '" + MENU + "' ran after your last change and reported success, which a run that leaves part of "
            + "its job undone does too. Call respond once more, with 'check' set to 'passed' -- run after your last "
            + "change, it did its whole job -- 'failed' -- it still did not -- or 'not_run' -- you cannot tell from "
            + "the runs you made. Your reply to this ends the task, and only 'check' is read from it: 'message' may be "
            + "left empty, since whatever is put in it is not delivered, and a call of any other tool is not run.";

    /** What unattended work that answered in prose is asked. */
    static final String PROBLEMS_OWED = KEPT + "It came as text, without a call of respond, so it could not say which "
            + "tools did not do their whole job. Call respond once more, with 'problems': an entry -- 'tool' its exact "
            + "name, 'what' one sentence -- for every tool that ran in this task but did not do its whole job (a part "
            + "missing, a fallback taken, a warning or an error in its output, data stale or wrong, even when it "
            + "reported success), or an empty list when every tool did. Each skill listed is repaired after this "
            + "task. Your reply to this ends the task, and only 'problems' is read from it: 'message' may be left "
            + "empty, since whatever is put in it is not delivered, and a call of any other tool is not run.";

    /** What the task's step log keeps of each question: a line of code's, and not the question. */
    static final String VERDICT_NOTED = "The answer was kept as it was given, and the model asked once how the check "
            + "of its change went.";
    static final String PROBLEMS_NOTED = "The answer was kept as it was given, and the model asked once which tools "
            + "did not do their whole job.";

    /** The model ends its turn with text and no tool call. */
    static LoopRig.Reply prose(String text) {
        return c -> Replies.of(text, 1_000, 100, 0, 0, "end_turn");
    }

    /** The model's reply holds nothing: no text and no tool call. */
    static LoopRig.Reply empty() {
        return c -> Replies.of("", 1_000, 0, 0, 0, "end_turn");
    }

    /** A reply whose tool call cannot be run: its arguments end half-written, with words beside it. */
    static LoopRig.Reply malformed() {
        return c -> new LlmResponse("Running it once more to be sure.", List.of(), "the model's arguments for tool '"
                + MENU + "' are not a JSON object (Unexpected end-of-input):\n{\"debug\": ", "tool_use", null, null,
                null, null, List.of(new LlmResponse.Usage(null, 1_000, 100, 0, 0)));
    }

    /** A reply that ended at a limit of the model: {@code stop} is the provider's stop reason. */
    static LoopRig.Reply endedAt(String stop) {
        return c -> Replies.of("The check", 1_000, 100, 0, 0, stop);
    }

    /** The call fails with {@code failure}. */
    static LoopRig.Reply failing(RuntimeException failure) {
        return c -> {
            throw failure;
        };
    }

    /**
     * Every kind of reply to the question that does not say what was asked. In the loop's steps
     * each has a way of its own to go on -- a call runs, a question is told nobody can answer it,
     * an empty reply and a call that cannot be read are asked again, a decline and a provider that
     * cannot be reached move the task to the local model, a request too long is folded and sent
     * again -- and after the question none of those is taken.
     */
    static Stream<Arguments> repliesThatSayNothingAsked() {
        return Stream.of(
                Arguments.of("a call of the skill", call(MENU, Map.of("debug", true))),
                Arguments.of("a call of a tool that only reads", call(AgentAction.SKILL_MANAGE, Map.of("action", "read", "name", MENU))),
                Arguments.of("a call of a tool that does not exist", call("no_such_tool", Map.of())),
                Arguments.of("a question to the user", call(AgentAction.ASK_USER, Map.of("message", "Which check do you mean?"))),
                Arguments.of("text", prose("The check passed.")),
                Arguments.of("an empty reply", empty()),
                Arguments.of("a call that cannot be read", malformed()),
                Arguments.of("a reply declined as reasoning extraction", TaskEndToEndTest.declined("reasoning_extraction")),
                Arguments.of("a reply declined on a safety check", TaskEndToEndTest.declined("cyber")),
                Arguments.of("a provider that cannot be reached", LocalModeTest.noInternet()),
                Arguments.of("a call that failed on the way", failing(EmptyReplyTest.unreachable())),
                Arguments.of("a request the provider refuses as it stands", failing(new LlmException("anthropic", "HTTP 400: invalid_request_error", 400, null))),
                Arguments.of("a request the gateway does not send", failing(new EgressRefused("anthropic", 1, "mail_reader", 2, "user", 40))),
                Arguments.of("a reply cut off at the model's output limit", endedAt("max_tokens")),
                Arguments.of("a request longer than the model's window", endedAt("model_context_window_exceeded")),
                Arguments.of("a respond without the parameter", respond("The check passed.")),
                Arguments.of("a respond whose check is none of the three", verdict("Checked.", "ok")),
                Arguments.of("a call that breaks inside", failing(new IllegalStateException("the stream broke"))));
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

    /** The conversation think call {@code n} sent, from 0: its messages without the lists of tools added on the way. */
    private List<LlmMessage> conversation(int n) {
        return fix.rig.cloud.calls("think").get(n).messages().stream()
                .filter(m -> m.role() != LlmMessage.Role.TOOLS).toList();
    }

    private int thinks() {
        return fix.rig.cloud.calls("think").size();
    }

    /** The steps of a task as its page and its record read them. */
    private List<Map<String, Object>> steps(String taskId) {
        @SuppressWarnings("unchecked")
        var steps = (List<Map<String, Object>>) new TaskTraceService(fix.rig.events).trace("u1", taskId).orElseThrow().get("steps");
        return steps;
    }

    /** The last thing the owner was told in the chat of scheduled results. */
    private String note() {
        return fix.told().get(fix.told().size() - 1);
    }

    /** What stands in a request where the task gave the answer it is asked about. */
    private static String standIn() throws Exception {
        var respond = new java.util.LinkedHashMap<String, Object>();
        respond.put("tool", "respond");
        respond.put("params", Map.of("message", AgentLoop.ANSWER_KEPT));
        return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(respond);
    }

    // ───────────────────────── a repair's verdict ─────────────────────────

    @Test
    @DisplayName("a repair that ends in prose after a check that passed is asked once, and 'check: passed' makes it fixed: the owner's report is the prose answer, whole")
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
        assertTrue(sent(6).contains("[respond] OK (0ms)\n" + VERDICT_OWED), sent(6));
        // The owner: fixed and checked, with the answer as it was written.
        String note = note();
        assertTrue(note.contains("Fixed and checked: it was run again, and the problem is gone."), note);
        assertTrue(note.contains("What the repair said:\n\n" + ANSWER), note);
        assertFalse(note.contains("Your answer is kept"), note);
        // The repair's steps: the one that answered, a step that worked, with what was asked in a line.
        var answered = steps((String) row.get("repair_task_id")).getLast();
        assertEquals(AgentAction.RESPOND, answered.get("tool"));
        assertEquals(Boolean.TRUE, answered.get("ok"));
        assertEquals(VERDICT_NOTED, answered.get("reason"));
        // Mutation: the loop delivering an answer that owes something as it did (the respond branch
        // without its "if (owed != null) return askedOnce") -> unverified, "Changed, but not
        // checked", and six think calls.
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
        // Mutation: the reply's check not read (askedOnce without its setReportedCheck) -> unverified.
        // The same of 'problems' (without its setReportedProblems) -> aProseEndingIsAskedForProblems
        // starts no repair.
    }

    @Test
    @DisplayName("a respond call that leaves 'check' out is asked the same way, and only 'check' is read from the reply: a message it gives again is not delivered, and the report stays the answer the repair gave")
    void aCallWithoutCheckIsAskedAndTheRepliesMessageIsNotRead(@TempDir Path tmp) throws Exception {
        fix = new Fix(tmp);
        fix.rig.queue.start();
        fix.scriptMenu(true);
        scriptFixAndCheck(fix.rig, Map.of("debug", true), respond("Changed the headers and ran it."));
        fix.rig.cloud.think.add(verdict("Passed.", "passed"));
        fix.unattended(TASK);
        var row = fix.ended(fix.lastId());

        assertEquals("fixed", row.get("outcome"), String.valueOf(row.get("report")));
        assertEquals("Changed the headers and ran it.", row.get("report"), "the answer it gave, not the reply's word");
        assertTrue(sent(6).contains(VERDICT_OWED), sent(6));
        assertFalse(note().contains("Passed."), note());
        // Mutation: the reply's message taken for the answer where it gives one (askedOnce completing
        // with answerFor of the reply's text when it is not blank) -> the report is "Passed.".
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("repliesThatSayNothingAsked")
    @DisplayName("whatever replies to a repair's question ends the repair: completed, its report the answer it gave, unverified as before there was a question -- after exactly one more call, with nothing the reply calls run, nothing asked again, and the local model asked nothing")
    void anyReplyEndsTheRepairWithTheAnswerItGave(String kind, LoopRig.Reply reply, @TempDir Path tmp) throws Exception {
        var local = LocalModeTest.localModel();     // there, and able to take a task over: it is not handed this one
        fix = new Fix(tmp, null, registry -> null, local);
        fix.rig.queue.start();
        fix.scriptMenu(true);
        scriptFixAndCheck(fix.rig, Map.of("debug", true), prose(ANSWER));
        fix.rig.cloud.think.add(reply);
        // What a step of the loop's would go on to, were the reply one of its own: none is asked for.
        fix.rig.cloud.think.add(verdict("Second thoughts.", "passed"));
        local.think.add(respond("The local model: it looks fine to me."));
        fix.unattended(TASK);
        var row = fix.ended(fix.lastId());

        assertEquals(1, ((Number) row.get("completed")).intValue(), kind + ": it ran to its answer");
        assertEquals(ANSWER, row.get("report"), kind);
        assertEquals("unverified", row.get("outcome"), kind);
        assertEquals(7, thinks(), kind + ": the run's two steps, the repair's four, and the one question");
        assertTrue(local.calls.isEmpty(), kind + ": the local model was asked about a repair it did not do");
        assertEquals(2, fix.menuCalls.size(), kind + ": the run's call and the repair's check -- none after the question");
        String note = note();
        assertTrue(note.contains("Changed, but not checked"), kind + ": " + note);
        assertTrue(note.contains("What the repair said:\n\n" + ANSWER), kind + ": " + note);
        assertFalse(note.contains("local model") || note.contains("**Stopped:**"), kind + ": " + note);
        // Mutations, each what a step of the loop's does with such a reply: the call it makes run
        // (askedOnce handing it to executeTool) -> the skill runs a third time; a reply that is no
        // respond asked for again -> eight think calls; the local model asked where the cloud gave
        // no reply (a call to it in askedOnce's catch) -> it is asked; the task ended as a step with
        // no reply ends (noAnswer there) -> the repair did not complete, and its report is
        // "**Stopped:** ..."; an error, or the gateway's refusal, let out of askedOnce -> the repair
        // ends on it.
    }

    @Test
    @DisplayName("the request that asks does not hold the answer: where the repair gave it stands a line of code's, the question follows it, and every message before is what the request before sent")
    void theQuestionsRequestDoesNotHoldTheAnswer(@TempDir Path tmp) throws Exception {
        fix = new Fix(tmp);
        fix.rig.queue.start();
        fix.scriptMenu(true);
        scriptFixAndCheck(fix.rig, Map.of("debug", true), prose(ANSWER));
        fix.rig.cloud.think.add(verdict("", "passed"));
        fix.unattended(TASK);
        assertEquals("fixed", fix.ended(fix.lastId()).get("outcome"));

        List<LlmMessage> answering = conversation(5), asking = conversation(6);
        String request = TaskEndToEndTest.all(asking);
        for (String sentence : List.of("The site answered HTTP 455 to the default client", "It now asks as a browser",
                "the check run confirmed it")) {
            assertFalse(request.contains(sentence), "the model is sent its own answer again: " + request);
        }
        // Two messages more than the request it answered: its answer's place, and the question.
        assertEquals(answering.size() + 2, asking.size());
        LlmMessage place = asking.get(asking.size() - 2), question = asking.getLast();
        assertEquals(LlmMessage.Role.ASSISTANT, place.role());
        assertEquals(standIn(), place.content());
        assertEquals(LlmMessage.Role.USER, question.role());
        assertEquals("[respond] OK (0ms)\n" + VERDICT_OWED, question.lasting());
        // And nothing before them is rewritten: the provider reads it from its cache.
        for (int i = 0; i < answering.size() - 1; i++) {
            assertEquals(answering.get(i).role(), asking.get(i).role(), "message " + i);
            assertEquals(answering.get(i).content(), asking.get(i).content(), "message " + i + " was rewritten");
        }
        LlmMessage newest = answering.getLast();
        assertNotNull(newest.lasting(), "the premise: the request before ended with its newest result");
        assertEquals(newest.lasting(), asking.get(answering.size() - 1).content(), "the result the request before cached");
        // Mutations: the answer's step recorded as the answer it was (askedOnce's stand-in holding
        // the kept answer's text) -> the request holds the answer, whole; the question recorded as
        // a step of the loop's own, which joins the turn before it (a tool named "_asked") -> the
        // result the request before cached is rewritten, and there is one message more, not two.
    }

    @Test
    @DisplayName("an answer that follows steps that ran nothing is asked about like any other: the reply ends the task with the answer whatever it is, so the question can cost it nothing")
    void anAnswerAfterStepsThatRanNothingIsAskedToo(@TempDir Path tmp) throws Exception {
        fix = new Fix(tmp);
        fix.rig.queue.start();
        fix.scriptMenu(true);
        scriptFixAndCheck(fix.rig, Map.of("debug", true), respond(""));     // not delivered: empty
        fix.rig.cloud.think.add(respond(""));                                // again
        fix.rig.cloud.think.add(prose(ANSWER));
        fix.rig.cloud.think.add(verdict("", "passed"));
        fix.unattended(TASK);
        var row = fix.ended(fix.lastId());

        assertEquals("fixed", row.get("outcome"));
        assertEquals(ANSWER, row.get("report"));
        assertEquals(9, thinks());
        // Mutation: the question left out after two steps that ran nothing, as it was while a third
        // would have ended the task (asked only while nothing.steps < NOTHING_TO_RUN_IN_A_ROW - 1)
        // -> eight think calls, and unverified.
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
        // A check that is none of the three is no check either.
        assertNull(SkillSelfRepair.usableCheck("ok"));
        assertNull(SkillSelfRepair.usableCheck(true));
        assertNull(SkillSelfRepair.usableCheck(null));
        assertEquals("passed", SkillSelfRepair.usableCheck(" passed "));
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
    @DisplayName("a repair whose skill_create left the skill's files as they were is not asked either: it is 'unchanged' whatever it says of a check, and its run was no run after a change")
    void aChangeThatLeftTheFilesAsTheyWereIsNotAsked(@TempDir Path tmp) throws Exception {
        fix = new Fix(tmp);
        // The write works, and leaves the authored files byte for byte as they were.
        fix.rig.skills.create = p -> "Skill '" + p.get("name") + "' updated and registered.";
        fix.rig.queue.start();
        fix.scriptMenu(true);
        scriptFixAndCheck(fix.rig, Map.of("debug", true), prose(ANSWER));
        fix.rig.cloud.think.add(verdict("", "passed"));         // what a question would be answered
        fix.unattended(TASK);
        var row = fix.ended(fix.lastId());

        assertEquals("unchanged", row.get("outcome"));
        assertEquals(ANSWER, row.get("report"));
        assertEquals(6, thinks(), "a billed question whose answer decides nothing");
        // Mutation: owed asking whatever the write left (its test of changedTheCode dropped) -> a
        // seventh think call.
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
    @DisplayName("the owner's Stop while the repair is being asked ends it as a Stop ends any task: stopped, no verdict, whatever it had answered and whatever the call brought back")
    void aStopWhileAskedIsAStop(@TempDir Path tmp) throws Exception {
        fix = new Fix(tmp);
        fix.rig.queue.start();
        fix.scriptMenu(true);
        scriptFixAndCheck(fix.rig, Map.of("debug", true), prose(ANSWER));
        fix.rig.cloud.think.add(c -> {
            fix.rig.cancellation.request("u1", (String) fix.repairRow(0).get("repair_task_id"), "you pressed Stop");
            return verdict("", "passed").answer(c);     // the reply was on its way already
        });
        fix.unattended(TASK);
        var row = fix.ended(fix.lastId());

        assertEquals("unverified", row.get("outcome"));
        assertEquals(0, ((Number) row.get("completed")).intValue(), "stopped is no verdict");
        assertTrue(String.valueOf(row.get("report")).startsWith("**Stopped:**"), String.valueOf(row.get("report")));
        assertTrue(note().contains("Changed, but the repair did not finish, so the change was not checked. The next "
                + "time the problem shows, a repair starts again."), note());
        // Mutation: the kept answer delivered whenever the call came back (askedOnce looking, after
        // the call, only at whether the call heard the Stop) -> the stopped repair is recorded
        // completed, and fixed.
    }

    @Test
    @DisplayName("a repair stopped by the time it answers is asked nothing: the Stop ends it there, with no call more and no line in its steps that says it was asked")
    void aTaskStoppedByTheTimeItAnswersIsNotAsked(@TempDir Path tmp) throws Exception {
        fix = new Fix(tmp);
        fix.rig.queue.start();
        fix.scriptMenu(true);
        scriptFixAndCheck(fix.rig, Map.of("debug", true), c -> {
            fix.rig.cancellation.request("u1", (String) fix.repairRow(0).get("repair_task_id"), "you pressed Stop");
            return prose(ANSWER).answer(c);             // the answer was on its way already
        });
        fix.rig.cloud.think.add(verdict("", "passed"));
        fix.unattended(TASK);
        var row = fix.ended(fix.lastId());

        assertEquals(0, ((Number) row.get("completed")).intValue(), "stopped is no verdict");
        assertTrue(String.valueOf(row.get("report")).startsWith("**Stopped:**"), String.valueOf(row.get("report")));
        assertEquals(6, thinks(), "asked although it was stopped");
        assertTrue(steps((String) row.get("repair_task_id")).stream().noneMatch(s -> AgentAction.RESPOND.equals(s.get("tool"))),
                "its steps say it was asked: " + steps((String) row.get("repair_task_id")));
        // Mutation: askedOnce asking without a look at the Stop first -> a seventh think call, and
        // a step that says the repair was asked how its check went.
    }

    // ───────────────────────── unattended work's problems ─────────────────────────

    static final String SAID = "Here are today's menus.\n\nCafe One: soup, schnitzel.\nCafe Two: could not be read.";

    static final String MAIL_TASK = "Read my mail and tell me what the bank wrote.";

    /** A private result: what a skill that holds the mailbox's password read. */
    static final String MAIL = "From the bank, 3 Nov: your statement is ready. Closing balance 48,213.07 in the "
            + "account ending 4417; the standing order of 12,000.00 to the landlord left on the first.";

    /** A rig whose {@code mail_reader} reads the mail, privately, counting its runs in {@code reads}. */
    private static Fix withMail(Path tmp, com.ownclaw.llm.LlmProvider local, AtomicInteger reads) throws Exception {
        var reader = tool("mail_reader", List.of("IMAP_PASS"), p -> {
            reads.incrementAndGet();
            return MAIL;
        });
        Fix f = local == null ? new Fix(tmp, reader) : new Fix(tmp, null, registry -> null, local, reader);
        f.rig.config.getPrivacy().setPersonalSources(List.of("IMAP_"));
        f.rig.jdbc.update("INSERT INTO users (id, display_name) VALUES ('u1', 'Owner')");
        f.rig.vault.storeCredential("u1", "IMAP_PASS", "pw-12345678");
        return f;
    }

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
        assertTrue(sent(2).contains("[respond] OK (0ms)\n" + PROBLEMS_OWED), sent(2));
        assertFalse(TaskEndToEndTest.all(fix.rig.cloud.calls("think").get(2).messages()).contains("Cafe One: soup"),
                "the model is sent its own answer again");
        var rows = fix.repairs();
        assertEquals(1, rows.size(), rows.toString());
        assertEquals("running", rows.get(0).get("outcome"));
        assertEquals(PROBLEM, rows.get(0).get("problem"));
        assertTrue(r.response().startsWith(SAID + "\n\n_🔧 Self-repair started for " + MENU), r.response());
        // Mutation: the engine not saying an answer came in prose (ThinkResult.inProse false) -> the
        // run ends on its second step, and nothing starts.
    }

    @Test
    @DisplayName("only 'problems' is read from the reply: a line written in its 'message' beside them is not delivered in place of the run's answer")
    void aMessageBesideTheProblemsIsNotDelivered(@TempDir Path tmp) throws Exception {
        fix = new Fix(tmp);
        fix.rig.cloud.think.add(call(MENU, Map.of()));
        fix.rig.cloud.think.add(prose(SAID));
        fix.rig.cloud.think.add(problems("No tool had a problem.", List.of()));
        AgentResult r = fix.unattended(TASK);

        assertEquals(SAID, r.response(), "the question cost the run its answer");
        assertNull(r.ownerText());
        assertTrue(fix.repairs().isEmpty(), fix.repairs().toString());
        // Mutation: as aCallWithoutCheckIsAskedAndTheRepliesMessageIsNotRead's -> the scheduled
        // run's whole result is "No tool had a problem.".
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("repliesThatSayNothingAsked")
    @DisplayName("whatever replies to an unattended run's question ends the run: completed with the answer it gave -- a private result it showed for the owner alone, as it was -- after exactly one more call, on the cloud, with nothing run and nothing repaired")
    void anyReplyEndsTheRunWithTheAnswerItGave(String kind, LoopRig.Reply reply, @TempDir Path tmp) throws Exception {
        var local = LocalModeTest.localModel();
        var reads = new AtomicInteger();
        fix = withMail(tmp, local, reads);
        fix.rig.cloud.think.add(call("mail_reader", Map.of()));
        fix.rig.cloud.think.add(prose("{{1}}"));
        fix.rig.cloud.think.add(reply);
        fix.rig.cloud.think.add(problems("Second thoughts.", List.of(Map.of("tool", "mail_reader", "what", "It read one mail."))));
        local.think.add(respond("The local model: I do not know what ran."));
        AgentResult r = fix.unattended(MAIL_TASK);

        assertEquals(AgentResult.TerminationReason.COMPLETED, r.terminationReason(), kind + ": " + r.response());
        assertEquals(AgentLoop.PRIVATE_NOTE, r.response(), kind);
        assertEquals(AgentLoop.PRIVATE_RESULT_HEADER + MAIL, r.ownerText(), kind + ": what the owner alone is shown");
        assertEquals(3, thinks(), kind + ": the run's two steps and the one question");
        assertTrue(local.calls.isEmpty(), kind + ": the local model was asked about a run it did not do");
        assertEquals(1, reads.get(), kind + ": nothing ran after the question");
        assertEquals(0, fix.menuCalls.size(), kind);
        assertTrue(fix.repairs().isEmpty(), kind + ": " + fix.repairs());
        // Mutations: each of anyReplyEndsTheRepairWithTheAnswerItGave's; and the kept answer
        // delivered without the text it had for the owner (askedOnce completing without
        // withOwnerText) -> he reads the note that a private answer exists, and not the mail.
    }

    @Test
    @DisplayName("work the cloud only orchestrates stays where it is too: a skill it calls itself in reply to the question is not run and not refused into another step, and the local model is asked nothing")
    void orchestratedWorkIsNotHandedToTheLocalModel(@TempDir Path tmp) throws Exception {
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
            rig.cloud.think.add(call(AgentAction.DELEGATE, Map.of("calls", List.of(Map.of("tool", MENU, "params", Map.of())))));
            rig.cloud.think.add(prose(SAID));
            rig.cloud.think.add(call(MENU, Map.of()));              // asked; it calls the skill itself, which it is not offered
            // In the reviewer's probe the next call was declined, the run moved to the local model,
            // and what it then wrote was the run's digest.
            rig.cloud.think.add(TaskEndToEndTest.declined("cyber"));
            local.think.add(respond("local model: I do not know what ran."));
            AgentResult r = rig.loop.executeFull("u1", TASK, true);

            assertEquals(AgentResult.TerminationReason.COMPLETED, r.terminationReason(), r.response());
            assertEquals(SAID, r.response());
            assertEquals(3, rig.cloud.calls("think").size());
            assertEquals(1, calls.size(), "the delegation ran the skill, and nothing ran it again");
            assertTrue(local.calls.isEmpty(), "moved to the local model: " + local.calls.size());
            // Mutation: a reply that is no respond asked for again, as a step that ran nothing is
            // (anyReplyEndsTheRepairWithTheAnswerItGave's second) -> a fourth think call: the one
            // that was declined, from where the run went to the local model.
        } finally {
            rig.queue.stop();
        }
    }

    @Test
    @DisplayName("a Stop while an unattended run is being asked ends it stopped, and a private result its answer had shown is given to the owner in full: the answer that showed it was not delivered")
    void aStopWhileAskedGivesTheOwnerWhatTheAnswerShowed(@TempDir Path tmp) throws Exception {
        fix = withMail(tmp, null, new AtomicInteger());
        fix.rig.cloud.think.add(call("mail_reader", Map.of()));
        fix.rig.cloud.think.add(prose("{{1}}"));
        fix.rig.cloud.think.add(c -> {
            fix.rig.cancellation.requestAll("u1", "you pressed Stop");
            c.progress().onProgress();              // a call that is streaming hears the Stop here
            return empty().answer(c);
        });
        AgentResult r = fix.unattended(MAIL_TASK);

        assertEquals(AgentResult.TerminationReason.CANCELLED, r.terminationReason(), r.response());
        assertEquals(3, thinks());
        assertFalse(r.response().contains("Closing balance"), "the record of the task holds a private result: " + r.response());
        assertNotNull(r.ownerText(), "the owner is given the private result nowhere: " + r.response());
        assertTrue(r.ownerText().contains(AgentLoop.PRIVATE_RESULT_HEADER + "**result 1 (mail_reader):**\n\n" + MAIL), r.ownerText());
        assertFalse(r.response().contains("shown above"), r.response());
        // Mutation: the results the kept answer showed left marked as shown (stoppedUnanswered
        // without nothingShown) -> the stopped ending says "shown above" of the mail, and it is
        // nowhere.
    }

    @Test
    @DisplayName("the question is the loop's own: the run's step log keeps a line of code's about it, as a step that worked -- not the question, which every later message of the chat of scheduled results would send the cloud again, and not a failed step")
    void theQuestionStaysOutOfTheRunsRecord(@TempDir Path tmp) throws Exception {
        fix = new Fix(tmp);
        fix.rig.jdbc.update("INSERT INTO users (id, display_name) VALUES ('u1', 'Owner')");
        var results = new LinkedBlockingQueue<StatusMessage>();
        fix.rig.emitter.subscribe("u1", results, m -> {
            if (m.type() == StatusMessage.Type.RESULT) results.add(m);
        });
        fix.rig.cloud.think.add(call(MENU, Map.of()));
        fix.rig.cloud.think.add(prose(SAID));
        fix.rig.cloud.think.add(problems("", List.of()));
        var scheduler = new ScheduledTaskService(fix.rig.jdbc, fix.rig.queue, fix.rig.emitter, fix.rig.events,
                fix.rig.chat, new ResultDelivery(fix.rig.chat, fix.rig.emitter));
        fix.rig.queue.start();
        scheduler.scheduleDeferred("u1", TASK, Instant.now().minusSeconds(60));
        scheduler.pollDueTasks();
        assertNotNull(results.poll(60, TimeUnit.SECONDS), "the scheduled result was not delivered");

        // The step log: the answer's step, a step that worked, with what was asked in a line.
        String run = fix.rig.jdbc.queryForObject("SELECT task_id FROM events WHERE event_type = 'step' AND "
                + "json_extract(details, '$.tool') = 'respond'", String.class);
        var steps = steps(run);
        assertEquals(List.of(MENU, AgentAction.RESPOND), steps.stream().map(s -> s.get("tool")).toList());
        assertEquals(Boolean.TRUE, steps.get(1).get("ok"), "the loop's own question is shown as a failure of the task");
        assertEquals(PROBLEMS_NOTED, steps.get(1).get("reason"));
        // And the page draws the line of a step that worked as a note, not as an error.
        String page;
        try (var in = AnswerOwedTest.class.getResourceAsStream("/static/index.html")) {
            assertNotNull(in, "static/index.html is not on the classpath");
            page = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        assertTrue(page.contains("if (s.reason) d.appendChild(node('div', 'task-result-content' + (s.ok ? '' : ' error'), s.reason));"));

        // A later message in that chat: the run's record goes to the cloud with it, and no question.
        fix.rig.cloud.think.add(respond("You are welcome."));
        fix.rig.turn(fix.rig.chat.scheduledSession("u1"), "thanks");
        var thinks = fix.rig.cloud.calls("think");
        String later = TaskEndToEndTest.all(thinks.get(thinks.size() - 1).messages());
        assertTrue(later.contains("2. ✓ respond — " + PROBLEMS_NOTED), "the premise: the record of the run is sent: " + later);
        for (String ofTheQuestion : List.of("Your answer is kept", "Call respond once more", "is read from it")) {
            assertFalse(later.contains(ofTheQuestion), "a later task is sent the question as part of the run's record: " + later);
        }
        // Mutations: the step's row keeping the question as its reason (noteAsked handed owed.asked)
        // -> every later message of the chat sends it; the row written as a step that failed
        // (noteAsked's observation a failure) -> the page counts a failed step in a run that had
        // none; the page drawing every step's reason as an error, as it did -> the line is gone.
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
        String asking = TaskEndToEndTest.all(fix.rig.cloud.calls("think").get(2).messages());
        assertFalse(asking.contains("Here are today's menus."), "the model is sent its own answer again: " + asking);
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
            assertFalse(second.contains("Your answer is kept"), "a plan was kept as the task's answer: " + second);
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
