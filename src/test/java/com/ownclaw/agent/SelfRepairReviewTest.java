package com.ownclaw.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static com.ownclaw.agent.LoopRig.respond;
import static com.ownclaw.agent.SkillSelfRepairTest.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The defects an adversarial review of self-repair found (2026-11-13), each as the behaviour that
 * replaced it: every test here failed while its defect was there.
 */
class SelfRepairReviewTest {

    private Fix fix;

    @AfterEach
    void stop() {
        if (fix != null) fix.rig.queue.stop();
    }

    List<String> toldTo(String user) {
        return fix.rig.jdbc.queryForList("SELECT c.content FROM conversations c JOIN chat_sessions s "
                + "ON s.id = c.session_id WHERE s.kind = 'scheduled' AND c.role = 'assistant' AND s.user_id = ? "
                + "ORDER BY c.rowid", String.class, user);
    }

    // F1: a skill whose SKILL.yaml says nothing of side effects was run by a repair -- and so was any
    // other tool declared free of them.
    @Test
    @DisplayName("F1: a repair runs no tool but its own skill: one declaring nothing, or declaring no side effects, is refused")
    void noOtherToolRuns(@TempDir Path tmp) throws Exception {
        var moved = new java.util.concurrent.CopyOnWriteArrayList<java.util.Map<String, Object>>();
        var fetched = new java.util.concurrent.CopyOnWriteArrayList<java.util.Map<String, Object>>();
        fix = new Fix(tmp,
                skill("imap_move_to_trash_by_sender", null, List.of(), false, moved,
                        p -> com.ownclaw.agent.tools.ToolResult.success("{\"moved\": 12}")),
                skill("web_snapshot", false, List.of(), false, fetched,
                        p -> com.ownclaw.agent.tools.ToolResult.success("<html></html>")));
        fix.rig.queue.start();
        fix.scriptMenu(true);
        fix.rig.cloud.think.add(LoopRig.call("imap_move_to_trash_by_sender", java.util.Map.of("sender", "news@example.com")));
        fix.rig.cloud.think.add(LoopRig.call(AgentAction.SKILL_MANAGE, java.util.Map.of("action", "read", "name", MENU)));
        fix.rig.cloud.think.add(LoopRig.call("web_snapshot", java.util.Map.of("url", "https://example.com/menu")));
        fix.rig.cloud.think.add(LoopRig.call(MENU, java.util.Map.of("debug", true)));
        fix.rig.cloud.think.add(respond("Probed."));
        fix.unattended(TASK);
        var row = fix.ended(fix.lastId());
        assertEquals("Probed.", row.get("report"), "it ended by its answer");
        assertTrue(moved.isEmpty(), "the repair ran a skill that declares nothing: " + moved);
        assertTrue(fetched.isEmpty(), "the repair ran another tool: " + fetched);
        assertEquals(2, fix.menuCalls.size(), "its own skill, declared free of side effects, ran: " + fix.menuCalls);
    }

    // F1b: the skill under repair, declaring nothing, was replayed with the trigger's arguments, as the goal said.
    @Test
    @DisplayName("F1b: a skill under repair that declares nothing is never run in its repair, and its goal says not to")
    void undeclaredSkillIsNotReplayed(@TempDir Path tmp) throws Exception {
        var moved = new java.util.concurrent.CopyOnWriteArrayList<java.util.Map<String, Object>>();
        fix = new Fix(tmp, skill("imap_move_to_trash_by_sender", null, List.of(), false, moved,
                p -> com.ownclaw.agent.tools.ToolResult.success("{\"moved\": 12, \"warning\": \"3 left behind\"}")));
        fix.rig.queue.start();
        fix.rig.cloud.think.add(LoopRig.call("imap_move_to_trash_by_sender", java.util.Map.of("sender", "news@example.com")));
        fix.rig.cloud.think.add(problems("Done.", List.of(java.util.Map.of("tool", "imap_move_to_trash_by_sender",
                "what", "It left 3 mails behind."))));
        fix.rig.cloud.think.add(LoopRig.call("imap_move_to_trash_by_sender", java.util.Map.of("sender", "news@example.com")));
        fix.rig.cloud.think.add(respond("Read the code instead."));
        fix.unattended("Using imap_move_to_trash_by_sender, trash news@example.com mail.");
        var row = fix.ended(fix.lastId());
        String goal = (String) row.get("goal");
        assertFalse(goal.contains("again, with the arguments of the call above"), goal);
        assertTrue(goal.contains("3. Do not run it: its SKILL.yaml does not say has_side_effects: false"), goal);
        assertEquals("undeclared", row.get("side_effects"));
        assertEquals(1, moved.size(), "run by the task, never by its repair: " + moved);
    }

    // F2: a repair's skill_create could add credentials and network the skill never had, then run it.
    @Test
    @DisplayName("F2: a repair's skill_create that grants its skill a credential or network is refused")
    void noGrants(@TempDir Path tmp) throws Exception {
        fix = new Fix(tmp);
        fix.rig.queue.start();
        fix.scriptMenu(true);
        fix.rig.cloud.think.add(LoopRig.call(AgentAction.SKILL_CREATE, java.util.Map.of("name", MENU, "description", "x",
                "parameters", "{}", "credentials", "SMTP_PASS,ROUTER_PIN", "requires_network", true)));
        fix.rig.cloud.think.add(LoopRig.call(MENU, java.util.Map.of()));
        fix.rig.cloud.think.add(respond("Done."));
        fix.unattended(TASK);
        fix.ended(fix.lastId());
        assertTrue(fix.created.isEmpty(), "granted: " + fix.created);
        assertEquals(0, fix.rig.cloud.calls("codegen").size(), "refused before any code was written");
        var thinks = fix.rig.cloud.calls("think");
        String told = String.join("\n", LoopRig.userParts(thinks.get(thinks.size() - 1)));
        assertTrue(told.contains("grants it nothing it did not have: the credentials SMTP_PASS, ROUTER_PIN"), told);
    }

    // F5: a repair that never got to try counted as "changed nothing", and the skill was left to the owner for good.
    @Test
    @DisplayName("F5: a repair cut short by an outage is no verdict: the owner is told it did not finish, and the next problem starts a repair")
    void outageIsNoVerdict(@TempDir Path tmp) throws Exception {
        fix = new Fix(tmp);
        fix.rig.queue.start();
        fix.scriptMenu(true);
        for (int i = 0; i < 2; i++) {
            fix.rig.cloud.think.add(c -> {
                throw new com.ownclaw.llm.LlmException("anthropic", "Connection failed: api.example.com", 0,
                        new java.net.UnknownHostException("api.example.com"));
            });
        }
        fix.unattended(TASK);
        var first = fix.ended(fix.lastId());
        assertEquals("not_finished", first.get("outcome"), String.valueOf(first.get("report")));
        String note = fix.told().get(fix.told().size() - 1);
        assertTrue(note.contains("The repair did not finish, and changed nothing"), note);
        assertTrue(note.contains("a repair starts again"), note);

        fix.scriptMenu(true);
        fix.rig.cloud.think.add(respond("Blocked; nothing to change."));
        AgentResult again = fix.unattended(TASK);
        assertTrue(again.response().contains("Self-repair started for " + MENU), again.response());
        assertEquals("unchanged", fix.ended(fix.lastId()).get("outcome"));
    }

    @Test
    @DisplayName("F5: a repair the owner stops before it changes anything is no verdict either")
    void stoppedIsNoVerdict(@TempDir Path tmp) throws Exception {
        fix = new Fix(tmp);
        fix.rig.queue.start();
        fix.scriptMenu(true);
        fix.rig.cloud.think.add(c -> {
            fix.rig.cancellation.requestAll("u1", "the owner pressed Stop");
            return LoopRig.call(AgentAction.SKILL_MANAGE, java.util.Map.of("action", "read", "name", MENU)).answer(c);
        });
        fix.unattended(TASK);
        var first = fix.ended(fix.lastId());
        assertEquals("stopped", first.get("outcome"), String.valueOf(first.get("report")));
        assertTrue(fix.told().get(fix.told().size() - 1).contains("Stopped before it changed the skill"));

        fix.scriptMenu(true);
        fix.rig.cloud.think.add(respond("Blocked; nothing to change."));
        AgentResult again = fix.unattended(TASK);
        assertTrue(again.response().contains("Self-repair started for " + MENU), again.response());
        assertEquals("unchanged", fix.ended(fix.lastId()).get("outcome"));
    }

    // F6: progress was any change of the code's hash, so A -> B -> A repaired for ever.
    @Test
    @DisplayName("F6: a repair that brings back a version the skill had since its last clean run is no progress: the next problem is left to the owner")
    void aCycleIsNoProgress(@TempDir Path tmp) throws Exception {
        fix = new Fix(tmp);
        fix.fixedOutput = DEGRADED;
        fix.rig.queue.start();
        for (String v : new String[] {"# A", "# B", "# A"}) {
            fix.scriptMenu(true);
            fix.rig.cloud.think.add(LoopRig.call(AgentAction.SKILL_CREATE, java.util.Map.of("name", MENU,
                    "description", "retry " + v, "parameters", "{}")));
            fix.rig.cloud.codegen.add(SkillCodegenTest.finished(SkillCodegenTest.module(v)));
            fix.rig.cloud.think.add(LoopRig.call(MENU, java.util.Map.of()));
            fix.rig.cloud.think.add(verdict("Still blocked.", "failed"));
            fix.unattended(TASK);
            assertEquals("not_fixed", fix.ended(fix.lastId()).get("outcome"));
        }
        var rows = fix.repairs();
        assertEquals(rows.get(0).get("code_after"), rows.get(2).get("code_after"), "A again");

        // The fourth day: no fourth repair.
        fix.scriptMenu(true);
        AgentResult r = fix.unattended(TASK);
        rows = fix.repairs();
        assertEquals(4, rows.size(), rows.toString());
        assertEquals("gave_up", rows.get(3).get("outcome"));
        assertEquals(0, fix.rig.queue.getQueueSize());
        assertTrue(r.response().contains("brought back a version of the skill it had had before"), r.response());
        String note = fix.told().get(fix.told().size() - 1);
        assertTrue(note.contains("Self-repair could not fix " + MENU), note);
        assertTrue(note.contains("brought back a version of the skill it had had before"), note);
    }

    // 10: the hash was skill.py's alone, and a change by another task during the repair counted as the repair's.
    @Test
    @DisplayName("10: a change another task makes while the repair runs is not the repair's")
    void anotherTasksChangeIsNotTheRepairs(@TempDir Path tmp) throws Exception {
        fix = new Fix(tmp);
        fix.rig.queue.start();
        fix.scriptMenu(true);
        fix.rig.cloud.think.add(c -> {
            fix.code.put(MENU, "def run(params):\n    return {}  # changed by another task\n");
            return LoopRig.call(AgentAction.SKILL_MANAGE, java.util.Map.of("action", "read", "name", MENU)).answer(c);
        });
        fix.rig.cloud.think.add(respond("Nothing to change."));
        fix.unattended(TASK);
        var row = fix.ended(fix.lastId());
        assertEquals("unchanged", row.get("outcome"));
        assertEquals(row.get("code_before"), row.get("code_after"));
    }

    // F3: a local-only repair's report went into the next repair's goal, which went to the cloud.
    @Test
    @DisplayName("F3: a local-only repair's report is never handed to a repair that may use the cloud")
    void localOnlyReportStaysLocal(@TempDir Path tmp) throws Exception {
        var local = LocalModeTest.localModel();
        fix = new Fix(tmp, null, registry -> null, local);
        fix.fixedOutput = DEGRADED;
        fix.rig.queue.start();
        local.think.add(LoopRig.call(MENU, java.util.Map.of()));
        local.think.add(problems("Cafe Two's menu could not be read.", List.of(java.util.Map.of("tool", MENU, "what", PROBLEM))));
        local.think.add(LoopRig.call(AgentAction.SKILL_CREATE, java.util.Map.of("name", MENU, "description", "retry",
                "parameters", "{}")));
        local.codegen.add(SkillCodegenTest.finished(SkillCodegenTest.module("# v2")));
        local.think.add(respond("Changed headers. LOCAL-ONLY-MARKER-7731 was in what I read."));
        fix.rig.loop.executeFull("u1", TASK, true, null, List.of(), null, null,
                new com.ownclaw.conversation.ChatOptions("free", null));
        var first = fix.ended(fix.lastId());
        assertEquals("unverified", first.get("outcome"));
        assertEquals(1, ((Number) first.get("local_only")).intValue());

        // The next run is on the cloud (the default): the problem again, and a repair that may use it.
        fix.scriptMenu(true);
        fix.rig.cloud.think.add(respond("Nothing more."));
        fix.unattended(TASK);
        var second = fix.ended(fix.lastId());
        assertEquals(0, ((Number) second.get("local_only")).intValue());
        assertEquals(first.get("id"), second.get("previous_id"), "it follows on from the local-only repair");
        String goal = (String) second.get("goal");
        assertFalse(goal.contains("LOCAL-ONLY-MARKER-7731"), goal);
        assertTrue(goal.contains("It ran on the local model only, so its report stays on this machine."), goal);
        assertTrue(fix.rig.cloud.calls.stream().noneMatch(c -> String.join("\n", LoopRig.userParts(c))
                .contains("LOCAL-ONLY-MARKER-7731")), "sent to the cloud");
    }

    // F4: repair records were looked up by skill name alone: one account was told another's report.
    @Test
    @DisplayName("F4: another account's repair that changed nothing neither stops this account's repair nor is told to it")
    void crossAccountReport(@TempDir Path tmp) throws Exception {
        fix = new Fix(tmp);
        fix.rig.queue.start();
        fix.scriptMenu(true);
        fix.rig.cloud.think.add(respond("U1-ONLY-REPORT: the menu for u1's office at Example Street 5 is blocked."));
        fix.unattended(TASK);
        assertEquals("unchanged", fix.ended(fix.lastId()).get("outcome"));

        fix.scriptMenu(true);
        fix.rig.cloud.think.add(respond("u2's repair: nothing to change."));
        AgentResult r = fix.rig.loop.executeFull("u2", TASK, true);
        var second = fix.ended(fix.lastId());
        assertEquals("u2", second.get("user_id"));
        assertNotNull(second.get("repair_task_id"), "u2's own repair started: " + fix.repairs());
        assertNull(second.get("previous_id"));
        assertTrue(r.response().contains("Self-repair started for " + MENU), r.response());
        assertTrue(toldTo("u2").stream().noneMatch(t -> t.contains("U1-ONLY-REPORT")), toldTo("u2").toString());
        assertFalse(String.valueOf(second.get("goal")).contains("U1-ONLY-REPORT"));
    }

    // F4b: the next repair of another account was given the first account's report in its goal.
    @Test
    @DisplayName("F4b: another account's repair that changed the code is not handed to this account's repair")
    void crossAccountGoal(@TempDir Path tmp) throws Exception {
        fix = new Fix(tmp);
        fix.fixedOutput = DEGRADED;
        fix.rig.queue.start();
        fix.scriptMenu(true);
        scriptFixAndCheck(fix.rig, null, respond("U1-ONLY-REPORT about u1's run."));
        fix.unattended(TASK);
        assertEquals("unverified", fix.ended(fix.lastId()).get("outcome"));
        fix.scriptMenu(true);
        fix.rig.cloud.think.add(respond("ok"));
        fix.rig.loop.executeFull("u2", TASK, true);
        var second = fix.ended(fix.lastId());
        assertEquals("u2", second.get("user_id"));
        assertNull(second.get("previous_id"));
        assertFalse(String.valueOf(second.get("goal")).contains("U1-ONLY-REPORT"), String.valueOf(second.get("goal")));
    }
}
