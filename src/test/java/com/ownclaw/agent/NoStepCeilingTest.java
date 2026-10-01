package com.ownclaw.agent;

import com.ownclaw.agent.tools.ToolResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.ownclaw.agent.LoopRig.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A task takes as many steps as its work does. On 1 October 2026 a task twenty successful steps
 * into the router fixes the owner had asked for ended "used all 20 steps a task may take"; the
 * steps a task, a delegation or a skill's attempts may take are no longer counted. What ends one
 * is the work being done, a stop, or steps in a row that ran nothing.
 */
class NoStepCeilingTest {

    @Test
    @DisplayName("a task that needs thirty successful steps takes them, and answers")
    void thirtyStepsComplete(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of(AssistantPartsTest.tool("ping", List.of(), p -> "pong " + p.get("n"))));
        for (int i = 1; i <= 30; i++) rig.cloud.think.add(call("ping", Map.of("n", i)));
        rig.cloud.think.add(respond("All thirty hosts answered."));

        AgentResult r = rig.turn(rig.chat.createSession("u1", "Hosts"), "ping the thirty hosts");

        assertEquals(AgentResult.TerminationReason.COMPLETED, r.terminationReason(), r.response());
        assertEquals("All thirty hosts answered.", r.response());
        assertEquals(31, rig.cloud.calls("think").size(), "every step asked for, and the answer");
        assertEquals(30, r.trajectory().turns().stream().filter(t -> "ping".equals(t.action().tool())
                && t.observation().success()).count());
        // Mutation: bound the loop at twenty steps again -> it ends before the answer.
    }

    @Test
    @DisplayName("a delegation that needs fifteen local turns takes them, and is done")
    void fifteenLocalTurnsComplete() {
        var ping = new DelegationBehaviourTest.FakeTool("ping", false, List.of(),
                p -> ToolResult.success("pong " + p.get("n")));
        var script = new ArrayList<String>();
        for (int i = 1; i <= 15; i++) script.add(DelegationBehaviourTest.call("ping", Map.of("n", i)));
        script.add(DelegationBehaviourTest.done("pinged fifteen hosts"));
        var llm = new DelegationBehaviourTest.Scripted(script.toArray(String[]::new));

        var outcome = DelegationBehaviourTest.executor(llm, new DelegationBehaviourTest.Usage(), ping)
                .execute(DelegationBehaviourTest.plan("ping the fifteen hosts"), DelegationBehaviourTest.task(),
                        DelegationBehaviourTest.UNCOUNTED);

        assertTrue(outcome.ok(), outcome.text());
        assertEquals(15, ping.calls.size());
        assertEquals(15, outcome.stepCount());
        assertTrue(outcome.text().contains("pinged fifteen hosts"), outcome.text());
        // Mutation: bound the turns at ten again -> "Delegation incomplete", five hosts unpinged.
    }

    @Test
    @DisplayName("a delegation's turns that ran nothing end it three in a row, never scattered")
    void aDelegationEndsOnlyOnThreeInARow() {
        var ping = new DelegationBehaviourTest.FakeTool("ping", false, List.of(), p -> ToolResult.success("pong"));
        var script = new ArrayList<String>();
        for (int i = 0; i < 5; i++) {
            script.add("I will ping the host now.");   // prose: no tool call in it
            script.add("");                           // an empty turn
            script.add(DelegationBehaviourTest.call("ping", Map.of("n", i)));
        }
        script.add(DelegationBehaviourTest.done("pinged"));
        var scattered = new DelegationBehaviourTest.Scripted(script.toArray(String[]::new));

        var done = DelegationBehaviourTest.executor(scattered, new DelegationBehaviourTest.Usage(), ping)
                .execute(DelegationBehaviourTest.plan("ping"), DelegationBehaviourTest.task(), DelegationBehaviourTest.UNCOUNTED);

        assertTrue(done.ok(), "ten turns ran nothing, never three in a row: " + done.text());
        assertEquals(5, ping.calls.size());

        var stuck = new DelegationBehaviourTest.Scripted(DelegationBehaviourTest.call("ping", Map.of()),
                "", "Let me think about it.", "", DelegationBehaviourTest.done("never reached"));
        var ended = DelegationBehaviourTest.executor(stuck, new DelegationBehaviourTest.Usage(), ping)
                .execute(DelegationBehaviourTest.plan("ping"), DelegationBehaviourTest.task(), DelegationBehaviourTest.UNCOUNTED);

        assertFalse(ended.ok());
        assertTrue(ended.text().startsWith("Delegation incomplete: the local model produced nothing that could "
                + "be run 3 turns in a row."), ended.text());
        assertEquals(4, stuck.calls.size(), "it was not asked a fifth time");
        // Mutation: count them across the delegation -> the scattered one ends on its third.
    }

    @Test
    @DisplayName("a delegation whose every call is refused ran nothing: three such turns in a row end it")
    void refusedTurnsRanNothing() {
        var ping = new DelegationBehaviourTest.FakeTool("ping", false, List.of(), p -> ToolResult.success("pong"));
        var send = new DelegationBehaviourTest.FakeTool("send_mail", true, List.of(), p -> ToolResult.success("sent"));
        // Each refused before it runs: a reference to no result, a name no tool has, skill_create,
        // and a change already made in this delegation.
        var refused = Map.of(
                "reference", DelegationBehaviourTest.call("ping", Map.of("host", "{{9}}")),
                "unknown tool", DelegationBehaviourTest.call("read_file", Map.of()),
                "skill_create", DelegationBehaviourTest.call("skill_create", Map.of("name", "reader")),
                "repeated change", DelegationBehaviourTest.call("send_mail", Map.of("to", "owner@example.org")));
        for (var kind : refused.entrySet()) {
            var script = new ArrayList<String>();
            for (int i = 0; i < 40; i++) script.add(kind.getValue());
            var llm = new DelegationBehaviourTest.Scripted(script.toArray(String[]::new));

            var outcome = DelegationBehaviourTest.executor(llm, new DelegationBehaviourTest.Usage(), ping, send)
                    .execute(DelegationBehaviourTest.plan("ping and mail"), DelegationBehaviourTest.task(),
                            DelegationBehaviourTest.UNCOUNTED);

            assertFalse(outcome.ok(), kind.getKey());
            assertTrue(outcome.text().startsWith("Delegation incomplete: the local model produced nothing that "
                    + "could be run 3 turns in a row."), kind.getKey() + ": " + outcome.text());
            int ran = "repeated change".equals(kind.getKey()) ? 1 : 0;
            assertEquals(3 + ran, llm.calls.size(), kind.getKey() + ": asked no more after the third");
            assertEquals(ran, send.calls.size() + ping.calls.size(), kind.getKey() + ": what ran");
            send.calls.clear();
            ping.calls.clear();
        }
        // Mutation: count only the turns that name no tool -> each runs until the script ends.
    }

    @Test
    @DisplayName("a call the critic blocks ran nothing: blocked again and again, the task ends, and the chat shows only the steps that ran")
    void blockedStepsRanNothing(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of(AssistantPartsTest.tool("ping", List.of(), p -> "the router answered")));
        for (int i = 0; i < 40; i++) rig.cloud.think.add(call("ping", Map.of("host", "192.0.2.1")));

        AgentResult r = rig.turn(rig.chat.createSession("u1", "Router"), "ping the router");

        assertEquals(AgentResult.TerminationReason.FAILURE_LIMIT, r.terminationReason(), r.response());
        assertEquals(6, rig.cloud.calls("think").size(), "three ran, then three were blocked in a row");
        var told = r.trajectory().turns().stream().map(t -> t.observation().output()).toList();
        assertTrue(told.get(3).startsWith("BLOCKED: ") && told.get(4).contains("WARNING: one more step like this"),
                told.toString());
        var rows = ProgressMessagesTest.progress(rig).stream().map(row -> String.valueOf(row.get("content"))).toList();
        assertEquals(3, rows.size(), "a row for each step that ran, none for a blocked one: " + rows);
        // Mutation: leave a blocked step out of the count -> it runs until the script ends;
        // post the row before the critique -> six rows.
    }

    @Test
    @DisplayName("failed calls never stop later ones: after three failures in a row, the next commands run")
    void failuresDoNotBlockLaterCalls(@TempDir Path tmp) throws Exception {
        var router = new DelegationBehaviourTest.FakeTool("openwrt_run", false, List.of(), p -> {
            String cmd = String.valueOf(p.get("cmd"));
            return cmd.startsWith("bad") ? ToolResult.failure("uci: Invalid argument: " + cmd)
                    : ToolResult.success("ok " + cmd);
        });
        var rig = new LoopRig(tmp, List.of(router));
        for (int i = 1; i <= 3; i++) rig.cloud.think.add(call("openwrt_run", Map.of("cmd", "bad " + i)));
        for (int i = 1; i <= 6; i++) rig.cloud.think.add(call("openwrt_run", Map.of("cmd", "set rule " + i)));
        rig.cloud.think.add(respond("The rules are set."));

        AgentResult r = rig.turn(rig.chat.createSession("u1", "Router"), "fix the router");

        assertEquals(AgentResult.TerminationReason.COMPLETED, r.terminationReason(), r.response());
        assertEquals(9, router.calls.size(), "every command ran, the six after the failures too");
        assertTrue(r.trajectory().turns().stream().noneMatch(t -> t.observation().output().startsWith("BLOCKED")));
        // Mutation: block after five failures in a row, a reflection counted as one -> the
        // fourth command onwards is refused.
    }

    @Test
    @DisplayName("a tool used often is not warned about: how much a task uses a tool is no measure of a loop")
    void noWarningForUsingAToolOften(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of(AssistantPartsTest.tool("openwrt_run", List.of(), p -> "ok " + p.get("cmd"))));
        for (int i = 1; i <= 14; i++) rig.cloud.think.add(call("openwrt_run", Map.of("cmd", "set rule " + i)));
        rig.cloud.think.add(respond("All fourteen rules are set."));

        AgentResult r = rig.turn(rig.chat.createSession("u1", "Router"), "set the rules");

        assertEquals(AgentResult.TerminationReason.COMPLETED, r.terminationReason(), r.response());
        var told = r.trajectory().turns().stream().map(t -> t.observation().output()).toList();
        assertEquals(14, told.size());
        assertTrue(told.stream().noneMatch(t -> t.contains("SYSTEM")), told.toString());
        // Mutation: warn from the eleventh use -> the twelfth result onwards carries it.
    }

    @Test
    @DisplayName("skill_create failing again and again for one name is never refused; each failure lists the earlier errors")
    void aFailingSkillIsNeverRefused(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of());
        int[] attempt = {0};
        rig.skills.create = p -> "ERROR: attempt " + (++attempt[0]) + " broke: ModuleNotFoundError 'pyroute" + attempt[0] + "'";
        Map<String, Object> spec = Map.of("name", "router_fix", "description", "Apply the router fixes.",
                "parameters", "{}");
        for (int i = 0; i < 6; i++) {
            rig.cloud.think.add(call(AgentAction.SKILL_CREATE, spec));
            rig.cloud.codegen.add(SkillCodegenTest.finished(SkillCodegenTest.module("")));
        }
        rig.cloud.think.add(respond("It cannot be built here."));

        AgentResult r = rig.turn(rig.chat.createSession("u1", "Router"), "fix the router");

        assertEquals(AgentResult.TerminationReason.COMPLETED, r.terminationReason(), r.response());
        assertEquals(6, attempt[0], "every attempt was made: none was refused");
        assertEquals(6, rig.cloud.calls("codegen").size());
        var told = r.trajectory().turns().stream()
                .filter(t -> AgentAction.SKILL_CREATE.equals(t.action().tool()))
                .map(t -> t.observation().output()).toList();
        assertFalse(told.get(0).contains("failed "), "the first has nothing earlier to list: " + told.get(0));
        String last = told.get(5);
        assertTrue(last.startsWith("ERROR: attempt 6 broke"), last);
        assertTrue(last.contains("skill_create for 'router_fix' failed 5 times before in this task. Change "
                + "the approach"), last);
        for (int i = 1; i <= 5; i++) {
            assertEquals(1, count(last, "ModuleNotFoundError 'pyroute" + i + "'"),
                    "attempt " + i + "'s error, once -- not again inside the next one's: " + last);
            assertTrue(last.contains("--- attempt " + i + " ---\nERROR: attempt " + i + " broke"), last);
        }
        // Mutation: list each earlier observation whole -> attempt 1's error is repeated
        // inside every later one; refuse the fourth -> attempt[0] stops at three.
    }

    private static int count(String text, String part) {
        int n = 0;
        for (int i = text.indexOf(part); i >= 0; i = text.indexOf(part, i + 1)) n++;
        return n;
    }
}
