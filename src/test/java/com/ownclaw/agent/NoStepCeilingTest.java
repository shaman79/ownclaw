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
