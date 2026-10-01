package com.ownclaw.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownclaw.conversation.MigratedDatabase;
import com.ownclaw.llm.EgressLedger;
import com.ownclaw.observability.EventEgressLedger;
import com.ownclaw.observability.EventLogService;
import com.ownclaw.observability.TaskTraceService;
import com.ownclaw.privacy.Label;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What a task did, from its rows as the task page parses them: the one record the owner, the task
 * page and the next turn read.
 */
class TaskRecordTest {

    static final ObjectMapper JSON = new ObjectMapper();

    /** A task's rows, written by the code that writes them in a run, and read back as the page reads them. */
    static final class Rows {
        final EventLogService events;
        final EventEgressLedger ledger;
        final AgentContext ctx;
        int step;

        Rows(EventLogService events, AgentContext ctx) {
            this.events = events;
            this.ledger = new EventEgressLedger(events, JSON);
            this.ctx = ctx;
        }

        Rows step(AgentAction action, AgentObservation obs) throws Exception {
            events.log(ctx.userId(), ctx.taskId(), "step", obs.success() ? "info" : "warn", action.tool(),
                    JSON.writeValueAsString(AgentLoop.stepDetails(ctx, action, obs, ++step)), 0);
            return this;
        }

        Rows call(String purpose, int completionTokens) {
            ledger.record(new EgressLedger.Row(ctx.userId(), ctx.taskId(), purpose, "anthropic", "claude-opus-5",
                    EgressLedger.Decision.SENT, List.of(), 100, 0, 3_000, completionTokens, 0, 0, 0.0, 0, 0, null, "end_turn"));
            return this;
        }

        Rows ended(String reason) {
            events.log(ctx.userId(), ctx.taskId(), "task_completed", "info", ctx.originalMessage(),
                    "{\"cloudTokens\":34848,\"localTokens\":0,\"steps\":" + step + ",\"durationMs\":65000,\"reason\":\""
                            + reason + "\"}", 0);
            return this;
        }

        Map<String, Object> trace() {
            return new TaskTraceService(events).trace(ctx.userId(), ctx.taskId()).orElse(Map.of());
        }
    }

    static EventLogService events(Path tmp) throws Exception {
        return new EventLogService(MigratedDatabase.at(tmp.resolve("t.db")));
    }

    /** A result recorded the way the loop records one, and its observation. */
    static AgentObservation result(AgentContext ctx, String tool, String output, List<String> credentials, long ms) {
        var a = ctx.addArtifact(tool, Map.of(), Map.of(), output, true, Artifact.labelFor(!credentials.isEmpty(), List.of()));
        return Artifact.asObservation(a, com.ownclaw.agent.tools.ToolResult.success(output), ms);
    }

    @Test
    @DisplayName("one line per step: what ran, how long, what it made and how it failed, repeats folded, no handle")
    void oneLinePerStep(@TempDir Path tmp) throws Exception {
        var ctx = new AgentContext("u1", "a1b2c3d4", "audit the routers");
        String traceback = "ERROR: Python syntax error:\n  File \"skill.py\", line 849\n    an =\nSyntaxError: invalid syntax (see {{1}})";
        var rows = new Rows(events(tmp), ctx)
                .step(new AgentAction("web_search", Map.of(), ""), result(ctx, "web_search", "the public page", List.of(), 2_100))
                .call("think", 900).call("codegen", 16_000).call("codegen", 16_000)
                .step(new AgentAction(AgentAction.SKILL_CREATE, Map.of("name", "openwrt_audit"), ""),
                        AgentObservation.failure(AgentAction.SKILL_CREATE, traceback, 562_000))
                .step(new AgentAction("openwrt_audit", Map.of(), ""),
                        result(ctx, "openwrt_audit", "x".repeat(174_617), List.of("OPENWRT_USER", "OPENWRT_PASS"), 80_000))
                .step(new AgentAction("_thinking", Map.of(), ""), AgentObservation.failure("_thinking", "the reply was empty", 0))
                .step(new AgentAction("_thinking", Map.of(), ""), AgentObservation.failure("_thinking", "the reply was empty", 0))
                .step(new AgentAction(AgentAction.SKILL_MANAGE, Map.of("action", "delete", "name", "Not A Name"), ""),
                        AgentObservation.success(AgentAction.SKILL_MANAGE, "deleted", Map.of(), 3));

        assertEquals(String.join("\n",
                "1. ✓ web_search · 2.1s → result 1, 15 chars, public",
                "2. ✗ skill_create openwrt_audit · 9m 22s · 2 codegen calls (32,000 output tokens) — ERROR: Python syntax error:",
                "  File \"skill.py\", line 849",
                "    an =",
                "SyntaxError: invalid syntax (see result 1)",
                "3. ✓ openwrt_audit · 1m 20s → result 2, 174,617 chars, private (personal source)",
                "4. ✗ _thinking — the reply was empty ×2",
                "5. ✓ skill_manage delete · 3ms"), TaskRecord.steps(rows.trace()));
        // Mutations: drop the skill from the row, drop the codegen calls, keep the handle, or
        // cut the reason -> this line no longer matches.
    }

    @Test
    @DisplayName("a finished task's record for a later task, and none for a task whose ending already carries it")
    void forLaterTask(@TempDir Path tmp) throws Exception {
        var events = events(tmp);
        var done = new AgentContext("u1", "a1b2c3d4", "check the network");
        var rows = new Rows(events, done)
                .step(new AgentAction("web_search", Map.of(), ""), result(done, "web_search", "the public page", List.of(), 5))
                .ended("COMPLETED");
        assertEquals("[OwnClaw's record of task a1b2c3d4, from its step log:\n"
                + "1. ✓ web_search · 5ms → result 1, 15 chars, public\n"
                + "It answered after 1m 5s, using 34,848 cloud tokens. Its results are not carried into this task: "
                + "only the answer above is.]", TaskRecord.forLaterTask("a1b2c3d4", rows.trace()));

        var stopped = new AgentContext("u1", "b2c3d4e5", "check the network");
        assertNull(TaskRecord.forLaterTask("b2c3d4e5", new Rows(events, stopped).ended("CANCELLED").trace()));
        assertNull(TaskRecord.forLaterTask("c3d4e5f6", Map.of()), "a task with no rows has no record");
    }

    @Test
    @DisplayName("handles become words; nothing left resolves as one")
    void inWords() {
        assertEquals("result 2 and result 2.body_text and result ?", TaskRecord.inWords("{{2}} and {{ 2.body_text }} and {{0}}"));
        assertFalse(ArtifactRef.TOKEN.matcher(TaskRecord.inWords("see {{12}}, {{$3}}")).find());
        assertEquals("{{?}} stays", TaskRecord.inWords("{{?}} stays"));
    }

    @Test
    @DisplayName("durations and token counts read the same everywhere")
    void formats() {
        assertEquals("450ms", TaskRecord.duration(450));
        assertEquals("2.1s", TaskRecord.duration(2_100));
        assertEquals("9m 22s", TaskRecord.duration(562_000));
        assertEquals("1h 2m 3s", TaskRecord.duration(3_723_000));
        assertEquals("324,866 cloud tokens + 1,200 local", TaskRecord.tokens(324_866, 1_200));
        assertEquals("0 cloud tokens", TaskRecord.tokens(0, 0));
    }

    @Test
    @DisplayName("a step row names the skill only when the name is one a skill may have")
    void theSkillIsNamedByTheOneRule() {
        var ctx = new AgentContext("u1", "a1b2c3d4", "x");
        var ok = AgentLoop.stepDetails(ctx, new AgentAction(AgentAction.SKILL_CREATE, Map.of("name", "openwrt_audit"), ""),
                AgentObservation.failure(AgentAction.SKILL_CREATE, "ERROR", 1), 1);
        assertEquals("openwrt_audit", ok.get("skill"));
        var bad = AgentLoop.stepDetails(ctx, new AgentAction(AgentAction.SKILL_MANAGE, Map.of("name", "rm -rf /"), ""),
                AgentObservation.failure(AgentAction.SKILL_MANAGE, "ERROR", 1), 2);
        assertFalse(bad.containsKey("skill"), "a name no skill can have is not recorded as one");
        var other = AgentLoop.stepDetails(ctx, new AgentAction("web_search", Map.of("name", "openwrt_audit"), ""),
                AgentObservation.failure("web_search", "ERROR", 1), 3);
        assertFalse(other.containsKey("skill"), "only skill_create and skill_manage name a skill");
        var managed = AgentLoop.stepDetails(ctx, new AgentAction(AgentAction.SKILL_MANAGE,
                Map.of("action", "delete", "name", "openwrt_audit"), ""),
                AgentObservation.success(AgentAction.SKILL_MANAGE, "deleted", Map.of(), 1), 4);
        assertEquals("openwrt_audit", managed.get("skill"), "skill_manage names its skill too");
        assertEquals("delete", managed.get("skillAction"));
        var unknown = AgentLoop.stepDetails(ctx, new AgentAction(AgentAction.SKILL_MANAGE,
                Map.of("action", "rm -rf", "name", "openwrt_audit"), ""),
                AgentObservation.failure(AgentAction.SKILL_MANAGE, "ERROR", 1), 5);
        assertFalse(unknown.containsKey("skillAction"), "an action skill_manage does not have is not recorded");
    }

    @Test
    @DisplayName("a private step's failure is not in its row, so not in any record")
    void privateFailuresStayOut(@TempDir Path tmp) throws Exception {
        var ctx = new AgentContext("u1", "a1b2c3d4", "x");
        var a = ctx.addArtifact("imap_fetch", Map.of(), Map.of(), "Traceback: mailbox someone@example.com", false,
                new Artifact.Decision(Label.PRIVATE, List.of("personal source")));
        var rows = new Rows(events(tmp), ctx).step(new AgentAction("imap_fetch", Map.of(), ""),
                AgentObservation.failure("imap_fetch", a.describe(), 5));
        String steps = TaskRecord.steps(rows.trace());
        assertFalse(steps.contains("someone@example.com"), steps);
        assertTrue(steps.startsWith("1. ✗ imap_fetch · 5ms → result 1"), steps);
    }
}
