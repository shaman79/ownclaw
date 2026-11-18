package com.ownclaw.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownclaw.agent.tools.Tool;
import com.ownclaw.observability.TaskTraceService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.ownclaw.agent.DelegationBehaviourTest.UNCOUNTED;
import static com.ownclaw.agent.DelegationBehaviourTest.executor;
import static com.ownclaw.agent.DelegationBehaviourTest.task;
import static com.ownclaw.agent.DelegationCallsTest.c;
import static com.ownclaw.agent.DelegationCallsTest.calls;
import static com.ownclaw.agent.LoopRig.call;
import static com.ownclaw.agent.LoopRig.respond;
import static com.ownclaw.agent.TaskRecordTest.Rows;
import static com.ownclaw.agent.TaskRecordTest.events;
import static org.junit.jupiter.api.Assertions.*;

/**
 * What a later turn of a chat reads of a delegation that failed: how it failed, and no result of a
 * call that worked. The step's row keeps the delegation's whole report as its reason, for the
 * owner's task page -- and the record a later task is given under the task's answer, whose own
 * closing words are that results are not carried over, used to print that reason whole.
 * <p>
 * Production, 2026-11-15: one failed delegation's reason was 273,970 characters -- the listing of
 * every Home Assistant entity, which a call that had worked returned. Every later turn of that
 * chat began by sending it: a 320,174-character first block, 178,000 tokens written to the cache,
 * $1.46 before any work.
 */
class FailedDelegationRecordTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** What a call that worked returned: every line of it carries the mark. */
    static final String LISTING;
    static {
        var sb = new StringBuilder();
        for (int i = 0; i < 400; i++) sb.append(String.format("LISTINGMARKER entity %04d is on\n", i));
        LISTING = sb.toString().strip();
    }
    static final String MARK = "LISTINGMARKER";
    static final String UNAVAILABLE = "HTTP 503 from the notification service";
    static final String RECORD_ENDS = "only the answer above is.]";

    static Tool entities() {
        return AssistantPartsTest.tool("ha_entities", List.of(), p -> LISTING);
    }

    static Tool notifier() {
        return TaskEndToEndTest.failing("notify", UNAVAILABLE);
    }

    /** The calls the cloud wrote: the listing, then a notification that fails. */
    static LoopRig.Reply listThenNotify() {
        return call(AgentAction.DELEGATE, Map.of("calls", List.of(
                Map.of("tool", "ha_entities", "params", Map.of()),
                Map.of("tool", "notify", "params", Map.of("message", "{{call1}}")))));
    }

    /** The same work handed to the local model as a goal. */
    static LoopRig.Reply listThenNotifyByGoal() {
        return call(AgentAction.DELEGATE, Map.of("goal", "List the entities with ha_entities, then send their "
                + "number with notify.", "tools", "ha_entities, notify"));
    }

    /** The record of the task as a request of a later turn carries it under the task's answer. */
    static String recordIn(String sent, String taskId) {
        int from = sent.indexOf("[OwnClaw's record of task " + taskId);
        assertTrue(from >= 0, "no record of task " + taskId + " in: " + sent);
        return sent.substring(from, sent.indexOf(RECORD_ENDS, from) + RECORD_ENDS.length());
    }

    /** The delegate step of the task, as the owner's task page is given it. */
    static Map<String, Object> pageStep(LoopRig rig, String taskId) {
        var trace = new TaskTraceService(rig.events).trace("u1", taskId).orElseThrow();
        for (Object s : (List<?>) trace.get("steps")) {
            if (s instanceof Map<?, ?> step && AgentAction.DELEGATE.equals(step.get("tool"))) {
                @SuppressWarnings("unchecked")
                var typed = (Map<String, Object>) step;
                return typed;
            }
        }
        throw new AssertionError("no delegate step in " + trace.get("steps"));
    }

    /**
     * A chat whose first task delegated, the delegation failed, and the task then answered; and
     * what its later turns are sent of that task. Asserts the whole rule, for whichever shape of
     * report the delegation wrote.
     *
     * @param how          how it failed, as the delegation said it apart from its report
     * @param fromAnOldRow the same read out of the reason alone, as a row written before that has it
     */
    static void laterTurnsReadHowItFailed(LoopRig rig, String how, String fromAnOldRow) throws Exception {
        String session = rig.chat.createSession("u1", "Home");
        rig.cloud.think.add(respond("The notification could not be sent."));
        AgentResult first = rig.turn(session, "list the entities and notify me");
        assertEquals(AgentResult.TerminationReason.COMPLETED, first.terminationReason(), first.response());
        int asked = rig.cloud.calls("think").size();
        assertTrue(TaskEndToEndTest.sentTo(rig, asked - 1).contains(LISTING),
                "the premise: the task itself read the delegation's whole report, the listing in it");

        // The owner's task page, and the row it is read from: the reason whole.
        Map<String, Object> step = pageStep(rig, first.taskId());
        assertEquals(false, step.get("ok"));
        assertTrue(String.valueOf(step.get("reason")).contains(LISTING), "the task page lost the result: " + step.get("reason"));
        assertEquals(how, step.get("howFailed"));
        JsonNode row = JSON.readTree(rig.jdbc.queryForObject("SELECT details FROM events WHERE event_type = 'step' "
                + "AND task_id = ? AND json_extract(details, '$.tool') = 'delegate'", String.class, first.taskId()));
        assertTrue(row.path("reason").asText().contains(LISTING), "the row lost the result");

        // The next turn: how it failed, and nothing of the listing.
        rig.cloud.think.add(respond("It failed with a 503."));
        rig.turn(session, "why did it fail?");
        String second = recordIn(TaskEndToEndTest.sentTo(rig, asked), first.taskId());
        assertFalse(second.contains(MARK), "a result of a call that worked is in the record: " + second);
        assertTrue(second.contains(", public — " + TaskRecord.inWords(how) + "\nIt answered after "), second);
        assertFalse(TaskEndToEndTest.sentTo(rig, asked).contains(MARK), "the listing reached the next turn some other way");

        // The turn after it: the same bytes, the block of the chat the cloud has cached.
        rig.cloud.think.add(respond("Yes."));
        rig.turn(session, "is that all?");
        assertEquals(second, recordIn(TaskEndToEndTest.sentTo(rig, asked + 1), first.taskId()));

        // A row from before a delegation said how it failed apart: the reason alone.
        assertEquals(1, rig.jdbc.update("UPDATE events SET details = json_remove(details, '$.howFailed') "
                + "WHERE event_type = 'step' AND task_id = ? AND json_extract(details, '$.howFailed') IS NOT NULL",
                first.taskId()));
        rig.cloud.think.add(respond("Still yes."));
        rig.turn(session, "and now?");
        String old = recordIn(TaskEndToEndTest.sentTo(rig, asked + 2), first.taskId());
        assertFalse(old.contains(MARK), "a result of a call that worked is in the record of an old row: " + old);
        assertTrue(old.contains(", public — " + TaskRecord.inWords(fromAnOldRow) + "\nIt answered after "), old);
        if (how.equals(fromAnOldRow)) assertEquals(second, old, "the old row's record, byte for byte the new one's");
        assertTrue(String.valueOf(pageStep(rig, first.taskId()).get("reason")).contains(LISTING));
    }

    // ───────────────────────── the three shapes of a failed delegation's report ─────────────────────────

    static final String STOPPED = "ALREADY DONE — these succeeded and must NOT be repeated: ha_entities. What failed or "
            + "did not run is what is left to do.\n\n"
            + "Delegation stopped at call 2 of 2: call 2 (notify) failed: its error is under the failed steps below.\n\n"
            + "[Tools run: {{1}} ha_entities ok, {{2}} notify FAILED]\n\n"
            + "--- Failed steps (verbatim) ---\n[{{2}} notify] {message={{1}}}\nERROR: " + UNAVAILABLE;

    @Test
    @DisplayName("calls that stopped: a later turn reads what was done, where they stopped, the ledger and the failed call's error -- not the result of the call that worked; the task page keeps the whole report")
    void callsThatStopped(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of(entities(), notifier()));
        rig.cloud.think.add(listThenNotify());

        laterTurnsReadHowItFailed(rig, STOPPED, STOPPED);
        // Mutations: the record's line prints the row's reason, as before -> the listing is in the
        // next turn's first block; a delegation's reason read whole where its row has nothing
        // else (no withoutResults) -> the old row's record holds the listing; the loop not handing
        // the step how it failed, or the task's trace not reading it from the row -> the step has
        // no howFailed.
    }

    static final String TOOLS_AND_FAILURES = "[Tools run: {{1}} ha_entities ok, {{2}} notify FAILED]\n\n"
            + "--- Failed steps (verbatim) ---\n[{{2}} notify] {message=400 entities}\nERROR: " + UNAVAILABLE;

    static final String FINISHED = "ALREADY DONE — these succeeded and must NOT be repeated: ha_entities. Anything below "
            + "that failed is what is left to do.\n\n"
            + "Listed the entities; the notification failed.\n\n" + TOOLS_AND_FAILURES;

    @Test
    @DisplayName("a delegation the local model finished with a failed step: a later turn reads its summary, the ledger and the failed step's error -- not the results under them")
    void finishedWithAFailedStep(@TempDir Path tmp) throws Exception {
        var local = new DelegationFitTest.Local();
        local.says(DelegationBehaviourTest.call("ha_entities", Map.of()));
        local.says(DelegationBehaviourTest.call("notify", Map.of("message", "400 entities")));
        local.says(DelegationBehaviourTest.done("Listed the entities; the notification failed."));
        var rig = new LoopRig(tmp, List.of(entities(), notifier()), 600, local);
        rig.cloud.think.add(listThenNotifyByGoal());

        laterTurnsReadHowItFailed(rig, FINISHED, FINISHED);
        // Mutations: as callsThatStopped's; and the reader leaving in the rule that set the summary
        // off from its results -> the old row's record ends the summary with "---".
    }

    static final String UNFINISHED = "Delegation incomplete: the local model produced nothing that could be run 3 turns "
            + "in a row.";

    @Test
    @DisplayName("a delegation that did not finish: a later turn reads why, which tools ran and the failed step's error; from an old row, why -- its results ran to the report's end")
    void didNotFinish(@TempDir Path tmp) throws Exception {
        var local = new DelegationFitTest.Local();
        local.says(DelegationBehaviourTest.call("ha_entities", Map.of()));
        local.says(DelegationBehaviourTest.call("notify", Map.of("message", "400 entities")));
        for (int i = 0; i < AgentLoop.NOTHING_TO_RUN_IN_A_ROW; i++) local.says("");
        var rig = new LoopRig(tmp, List.of(entities(), notifier()), 600, local);
        rig.cloud.think.add(listThenNotifyByGoal());

        laterTurnsReadHowItFailed(rig, UNFINISHED + "\n\n" + TOOLS_AND_FAILURES, UNFINISHED);
        // Mutation: a delegation that did not finish says only why, as its report does (partial) ->
        // the record names no tool that ran, and not the failed step's error.
    }

    // ───────────────────────── the writer and the reader of a report ─────────────────────────

    /** The three reports, written by the real delegation. */
    static List<LocalExecutor.Outcome> reports(Tool listing, Tool failing) {
        var stopped = executor(new DelegationBehaviourTest.Scripted(), new DelegationBehaviourTest.Usage(), listing, failing)
                .execute(calls("", c(listing.name(), Map.of()), c(failing.name(), Map.of("message", "{{call1}}"))),
                        task(), UNCOUNTED);
        var finished = executor(new DelegationBehaviourTest.Scripted(
                        DelegationBehaviourTest.call(listing.name(), Map.of()),
                        DelegationBehaviourTest.call(failing.name(), Map.of("message", "400 entities")),
                        DelegationBehaviourTest.done("Listed the entities; the notification failed.")),
                new DelegationBehaviourTest.Usage(), listing, failing)
                .execute(DelegationBehaviourTest.plan("list and notify"), task(), UNCOUNTED);
        var unfinished = executor(new DelegationBehaviourTest.Scripted(
                        DelegationBehaviourTest.call(listing.name(), Map.of()),
                        DelegationBehaviourTest.call(failing.name(), Map.of("message", "400 entities")), "", "", ""),
                new DelegationBehaviourTest.Usage(), listing, failing)
                .execute(DelegationBehaviourTest.plan("list and notify"), task(), UNCOUNTED);
        return List.of(stopped, finished, unfinished);
    }

    @Test
    @DisplayName("how a delegation failed is made where its report is written, of the report's own parts; read out of the report again, by the lines its writer marks the results with, it is the same")
    void theWriterAndTheReaderAgree() {
        var reports = reports(entities(), notifier());
        for (var report : reports) {
            assertFalse(report.ok());
            assertTrue(report.text().contains(LISTING), "the cloud is shown every result: " + report.text());
            assertFalse(report.howFailed().contains(MARK), report.howFailed());
            assertFalse(TaskRecord.withoutResults(report.text()).contains(MARK), TaskRecord.withoutResults(report.text()));
        }
        assertEquals(STOPPED, reports.get(0).howFailed());
        assertEquals(STOPPED, TaskRecord.withoutResults(reports.get(0).text()));
        assertEquals(FINISHED, reports.get(1).howFailed());
        assertEquals(FINISHED, TaskRecord.withoutResults(reports.get(1).text()));
        assertEquals(UNFINISHED + "\n\n" + TOOLS_AND_FAILURES, reports.get(2).howFailed());
        assertEquals(UNFINISHED, TaskRecord.withoutResults(reports.get(2).text()));
        // The lines the reader looks for are in the reports the writer writes.
        assertTrue(reports.get(0).text().contains("\n\n" + LocalExecutor.RESULTS_OF_CALLS + "\n\n### {{1}}: ha_entities ✓\n"));
        assertTrue(reports.get(1).text().contains(LocalExecutor.SUMMARY_RULE + LocalExecutor.COMPLETED_FOR + "list and notify\n\n### "));
        assertTrue(reports.get(2).text().contains("\n\n" + LocalExecutor.PARTIAL_RESULTS + "\n\n### {{1}}: ha_entities ✓\n"));
        assertTrue(reports.get(0).text().contains("\n\n" + LocalExecutor.TOOLS_RUN + "{{1}} ha_entities ok, "));
        // A delegation that worked says nothing of failing.
        assertNull(executor(new DelegationBehaviourTest.Scripted(), new DelegationBehaviourTest.Usage(), entities())
                .execute(calls("", c("ha_entities", Map.of())), task(), UNCOUNTED).howFailed());
        // Mutations: how the stopped calls failed made of their whole report (callsStopped) -> it
        // holds the listing; the reader leaving the summary's rule in -> the two differ by "---".
    }

    /** A listing that quotes the report of calls that stopped: two headers, a ledger, the failed steps. */
    static final String QUOTES_A_STOP = String.join("\n", "LISTINGMARKER before", LocalExecutor.RESULTS_OF_CALLS, "",
            LocalExecutor.COMPLETED_FOR + "something else", "", "### {{9}}: other ✓", "LISTINGMARKER inside", "",
            LocalExecutor.TOOLS_RUN + "{{9}} other ok]", "", "--- Failed steps (verbatim) ---", "[{{9}} other] {}",
            "LISTINGMARKER after");

    static final String STOPPED_BEFORE_ITS_RESULTS = "ALREADY DONE — these succeeded and must NOT be repeated: "
            + "ha_entities. What failed or did not run is what is left to do.\n\nDelegation stopped at call 2 of 2: "
            + "call 2 (notify) failed: its error is under the failed steps below.";

    @Test
    @DisplayName("a result that itself holds the lines a report marks its results with leaves nothing of itself in the record: said apart it changes nothing, and read out of an old row's reason it can only take more out")
    void aResultThatLooksLikeAReport() {
        Tool listing = AssistantPartsTest.tool("ha_entities", List.of(), p -> QUOTES_A_STOP);
        var reports = reports(listing, notifier());

        // Said apart, it was never read out of the report: the result's lines cannot reach it.
        assertEquals(STOPPED, reports.get(0).howFailed());
        assertEquals(FINISHED, reports.get(1).howFailed());
        // Read out of the report: from the first line that opens the results, which the writer
        // puts before any result, to the last that opens a ledger, which it puts after every one.
        assertEquals(STOPPED, TaskRecord.withoutResults(reports.get(0).text()));
        assertEquals(FINISHED, TaskRecord.withoutResults(reports.get(1).text()));
        assertEquals(UNFINISHED, TaskRecord.withoutResults(reports.get(2).text()));

        // A ledger's opening words in the error of the call that failed, which stands after the
        // real ledger: more is taken out -- the real ledger, and the error up to that line.
        Tool failing = TaskEndToEndTest.failing("notify", "the service answered:\n" + LocalExecutor.TOOLS_RUN
                + "{{9}} other ok]\nand closed the connection");
        var stopped = reports(listing, failing).get(0);
        assertEquals(STOPPED_BEFORE_ITS_RESULTS + "\n\n[Tools run: {{9}} other ok]\nand closed the connection",
                TaskRecord.withoutResults(stopped.text()));
        assertTrue(stopped.howFailed().contains("[Tools run: {{1}} ha_entities ok, {{2}} notify FAILED]")
                && stopped.howFailed().contains("ERROR: the service answered:"), "said apart, nothing is lost: "
                + stopped.howFailed());

        // A result that quotes a report that did not finish: such a report has no ledger to end its
        // results, so wherever its header stands the results are taken to run to the end.
        Tool unfinishedQuoted = AssistantPartsTest.tool("ha_entities", List.of(), p -> String.join("\n",
                "LISTINGMARKER before", LocalExecutor.PARTIAL_RESULTS, "", "### {{9}}: other ✓", "LISTINGMARKER after"));
        var quoting = reports(unfinishedQuoted, notifier());
        assertEquals(STOPPED_BEFORE_ITS_RESULTS, TaskRecord.withoutResults(quoting.get(0).text()));
        assertEquals(UNFINISHED, TaskRecord.withoutResults(quoting.get(2).text()));
        assertEquals(STOPPED, quoting.get(0).howFailed());
        // Mutation: end the results at the FIRST line that opens a ledger -> everything of the
        // listing after its own "[Tools run: " line stays in the record of an old row.
    }

    @Test
    @DisplayName("no result is left in an old row's record whatever the report holds: a header's words in the error before the results and a ledger's in a result, at once")
    void twoSuchLinesAtOnce() {
        // The local model's call fails with an error that quotes a header; the result it had
        // already read quotes a ledger.
        var local = new com.ownclaw.llm.LlmProvider() {
            int asked;
            public com.ownclaw.llm.LlmResponse chat(List<com.ownclaw.llm.LlmMessage> m, com.ownclaw.llm.LlmRequestConfig c) {
                if (asked++ == 0) return com.ownclaw.llm.Replies.of(DelegationBehaviourTest.call("ha_entities", Map.of()), 1, 1);
                throw new IllegalStateException("the server said:\n" + LocalExecutor.RESULTS_OF_CALLS + "\nnone");
            }
            public boolean isAvailable() { return true; }
            public String name() { return "scripted"; }
        };
        Tool listing = AssistantPartsTest.tool("ha_entities", List.of(), p -> QUOTES_A_STOP);
        var report = executor(local, new DelegationBehaviourTest.Usage(), listing)
                .execute(DelegationBehaviourTest.plan("list the entities"), task(), UNCOUNTED);

        assertTrue(report.text().startsWith("Delegation incomplete: Local LLM call failed: the server said:\n"
                + LocalExecutor.RESULTS_OF_CALLS + "\nnone\n\n" + LocalExecutor.PARTIAL_RESULTS + "\n\n### {{1}}: "
                + "ha_entities ✓\nLISTINGMARKER before\n"), report.text());
        assertEquals("Delegation incomplete: Local LLM call failed: the server said:",
                TaskRecord.withoutResults(report.text()));
        assertFalse(report.howFailed().contains(MARK), report.howFailed());
        // Mutation: take a report's results to run to its end only where the line that opens an
        // unfinished report's results is the first header -> they end at the listing's own
        // "[Tools run: " line, and everything of the listing after it stays.
    }

    @Test
    @DisplayName("a report with no results in it is how the delegation failed, whole: one refused before anything ran, one that ran no tool, one that named no goal")
    void aReportWithNoResults() {
        var notifier = notifier();
        var refused = executor(new DelegationBehaviourTest.Scripted(), new DelegationBehaviourTest.Usage(), notifier)
                .execute(calls("", c("notify", Map.of("message", "{{7.count}}"))), task(), UNCOUNTED);
        assertTrue(refused.text().startsWith("Delegation stopped at call 1 of 1: call 1 (notify) was not run."), refused.text());
        assertEquals(refused.text(), TaskRecord.withoutResults(refused.text()));
        assertEquals(refused.text().replace("\n\n\n\n", "\n\n"), refused.howFailed());

        var ranNothing = executor(new DelegationBehaviourTest.Scripted(DelegationBehaviourTest.done("All done.")),
                new DelegationBehaviourTest.Usage(), notifier).execute(DelegationBehaviourTest.plan("notify me"), task(), UNCOUNTED);
        assertFalse(ranNothing.ok());
        assertEquals("All done.\n\n---\nDelegation completed for: notify me\n\n\n\n" + LocalExecutor.NO_TOOL_RAN, ranNothing.text());
        assertEquals(ranNothing.text(), TaskRecord.withoutResults(ranNothing.text()), "it ran no tool: there is no result in it");
        assertEquals("All done.\n\n" + LocalExecutor.NO_TOOL_RAN, ranNothing.howFailed());

        String noGoal = "ERROR: delegate needs a 'goal', or exact 'calls' to run.";
        assertEquals(noGoal, TaskRecord.withoutResults(noGoal));
        // Mutation: a finished report with no ledger of tools cut at its header like any other ->
        // the line that says no tool ran is gone from ranNothing's.
    }

    // ───────────────────────── the rows ─────────────────────────

    static AgentAction delegate() {
        return new AgentAction(AgentAction.DELEGATE, Map.of("goal", "list and notify"), "");
    }

    /** A failed delegation's observation, as the loop records one: the report, and how it failed apart. */
    static AgentObservation failed(LocalExecutor.Outcome report) {
        var structured = new LinkedHashMap<String, Object>();
        structured.put(AgentLoop.HOW_FAILED, report.howFailed());
        return AgentObservation.failure(AgentAction.DELEGATE, report.text(), structured, 1_200);
    }

    @Test
    @DisplayName("a delegation that failed after the local model read private data keeps neither its reason nor how it failed; its line says only that it failed")
    void aPrivateStepSaysNothing(@TempDir Path tmp) throws Exception {
        var ctx = new AgentContext("u1", "a1b2c3d4", "list the entities");
        ctx.markLocalTierReadPrivate();
        var report = reports(entities(), notifier()).get(1);
        var rows = new Rows(events(tmp), ctx).step(delegate(), failed(report));

        Map<?, ?> step = (Map<?, ?>) ((List<?>) rows.trace().get("steps")).get(0);
        assertNull(step.get("reason"));
        assertNull(step.get("howFailed"), "what the reason is withheld for, this is withheld for");
        assertEquals("1. ✗ delegate · 1.2s", TaskRecord.steps(rows.trace()));
        // Mutation: stepOutcome writes how it failed whatever it does with the reason -> the line
        // carries what the local model wrote after reading private data.
    }

    @Test
    @DisplayName("how a delegation failed is scrubbed of the vault's values as its reason is, and is not kept when it holds what the gateway would not send")
    void theAccountIsScrubbedAsTheReasonIs() {
        String secret = "notify-canary-pass-88";
        var ctx = new AgentContext("u1", "t1", "list the entities");
        ctx.setSecretValues(Map.of("NOTIFY_PASS", secret));
        var report = reports(entities(), TaskEndToEndTest.failing("notify", "HTTP 401: " + secret + " was refused")).get(0);
        assertTrue(report.howFailed().contains(secret), "the premise: the failed call's error quotes it");

        var details = AgentLoop.stepDetails(ctx, delegate(), failed(report), 1);

        assertFalse(String.valueOf(details.get("reason")).contains(secret));
        assertFalse(String.valueOf(details.get(AgentLoop.HOW_FAILED)).contains(secret), String.valueOf(details));
        assertTrue(String.valueOf(details.get(AgentLoop.HOW_FAILED)).endsWith("ERROR: HTTP 401: «vault:NOTIFY_PASS» was refused"),
                String.valueOf(details.get(AgentLoop.HOW_FAILED)));

        // A private result of the task, which the failed call's error quotes: nothing of it is kept.
        var quoting = new AgentContext("u1", "t2", "parse the statement");
        String statement = "Account CZ65 0800 0000 1920 0014 5399 balance 41200 CZK statement for September";
        quoting.egress("test").index().addPrivate(1, statement);
        var leaking = reports(entities(), TaskEndToEndTest.failing("notify", "could not send: " + statement)).get(0);
        var kept = AgentLoop.stepDetails(quoting, delegate(), failed(leaking), 1);
        assertFalse(kept.containsKey("reason") || kept.containsKey(AgentLoop.HOW_FAILED), String.valueOf(kept));
        // Asked of how it failed by itself too: it is the report's parts joined anew.
        var apart = new LinkedHashMap<String, Object>();
        apart.put(AgentLoop.HOW_FAILED, "Delegation incomplete: could not send: " + statement);
        var alone = AgentLoop.stepDetails(quoting, delegate(),
                AgentObservation.failure(AgentAction.DELEGATE, "Delegation incomplete: could not send.", apart, 5), 2);
        assertTrue(alone.containsKey("reason"), String.valueOf(alone));
        assertFalse(alone.containsKey(AgentLoop.HOW_FAILED), String.valueOf(alone));
        // Mutations: how it failed stored as the delegation wrote it -> the vault's value is in the
        // row, and in every later turn of the chat; not asked the gateway's question by itself ->
        // the last row keeps the statement's line.
    }

    @Test
    @DisplayName("any other step's reason is its own error and stays whole, whatever its lines read like")
    void otherStepsAreLeftAlone(@TempDir Path tmp) throws Exception {
        var ctx = new AgentContext("u1", "a1b2c3d4", "build the report");
        String error = "ERROR: the export stopped.\n" + LocalExecutor.PARTIAL_RESULTS + "\n\nrow 1\nrow 2\n\n"
                + LocalExecutor.TOOLS_RUN + "none]";
        var rows = new Rows(events(tmp), ctx).step(new AgentAction("report_builder", Map.of(), ""),
                AgentObservation.failure("report_builder", error, 5));
        assertEquals("1. ✗ report_builder · 5ms — " + error, TaskRecord.steps(rows.trace()));
        assertEquals(java.util.Set.of(error), TaskRecord.reasons(rows.trace()));
        // Mutation: take the results out of every failed step's reason -> "row 1" and "row 2" are gone.
    }

    @Test
    @DisplayName("a task that did not finish says how its delegation failed in the step's line, and shows the result of the call that worked once, in full, under what it produced")
    void anEndingShowsTheResultOnce(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of(entities(), notifier()));
        rig.cloud.think.add(listThenNotify());
        for (int i = 0; i < AgentLoop.NOTHING_TO_RUN_IN_A_ROW; i++) {
            rig.cloud.think.add(c -> com.ownclaw.llm.Replies.of("", 300, 0, 0, 0, "end_turn"));
        }

        AgentResult r = rig.turn(rig.chat.createSession("u1", "Home"), "list the entities and notify me");

        assertEquals(AgentResult.TerminationReason.FAILURE_LIMIT, r.terminationReason(), r.response());
        assertTrue(r.response().contains(" — " + TaskRecord.inWords(STOPPED) + "\n2. ✗ "), r.response());
        assertTrue(r.response().contains("\n- result 1 (ha_entities): "
                + String.format(java.util.Locale.ROOT, "%,d", LISTING.length()) + " chars, public — in full below."),
                r.response());
        assertEquals(1, TaskEndingTest.count(r.response(), LISTING), "once, where the ending shows its results");
        // Mutation: the record's line prints the row's reason -> the listing is in the ending twice,
        // and in every later turn that reads the ending.
    }
}
