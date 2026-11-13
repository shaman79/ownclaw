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
