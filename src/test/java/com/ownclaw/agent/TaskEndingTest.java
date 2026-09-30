package com.ownclaw.agent;

import com.ownclaw.agent.AgentResult.TerminationReason;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.llm.CloudGateway;
import com.ownclaw.llm.EgressRefused;
import com.ownclaw.llm.LlmMessage;
import com.ownclaw.llm.LlmRequestConfig;
import com.ownclaw.privacy.Label;
import com.ownclaw.privacy.PrivateIndex;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static com.ownclaw.agent.TaskRecordTest.Rows;
import static com.ownclaw.agent.TaskRecordTest.events;
import static com.ownclaw.agent.TaskRecordTest.result;
import static org.junit.jupiter.api.Assertions.*;

/**
 * How a task that did not finish ends: why, what it did, what it produced and where it is, what
 * next -- written by code, one text for the owner's screens and the next turn, never holding a
 * private result's text, a vault value or a handle.
 */
class TaskEndingTest {

    static final String PASSWORD = "hunter2-router-admin";
    static final String AUDIT = ("config wifi-iface 'guest'\n\toption key 'Tr0ub4dor-guest-psk-" + PASSWORD
            + "'\n\toption network 'guest'\n").repeat(20);

    /** A public inventory, a private audit, and a public report that repeats the audit. */
    static AgentContext auditTask() {
        var ctx = new AgentContext("u1", "03458f80", "what is the status?");
        ctx.setSecretValues(Map.of("OPENWRT_PASS", PASSWORD));
        ctx.addArtifact("lan_inventory", Map.of(), Map.of(), "192.0.2.1 main router, admin password "
                + PASSWORD, true, new Artifact.Decision(Label.PUBLIC, List.of()));
        ctx.addArtifact("openwrt_audit", Map.of(), Map.of(), AUDIT, true,
                new Artifact.Decision(Label.PRIVATE, List.of("credentials (2)")));
        ctx.addArtifact("write_text_file_verbatim", Map.of(), Map.of(), "{\"ok\": true, \"preview\": \""
                + AUDIT.substring(0, 200) + "\"}", true, new Artifact.Decision(Label.PRIVATE, List.of("references {{2}}")));
        ctx.addArtifact("cat_report", Map.of(), Map.of(), AUDIT, true, new Artifact.Decision(Label.PUBLIC, List.of()));
        ctx.addCloudTokens(324_866);
        return ctx;
    }

    static AgentResult ended(TerminationReason reason, String why) {
        return new AgentResult(false, why, new AgentTrajectory(), 5, 1000L, reason, null, null);
    }

    @Test
    @DisplayName("every ending but a finished answer says why, what ran, what it made and what next")
    void everyEndingSaysTheFourThings(@TempDir Path tmp) throws Exception {
        var ctx = auditTask();
        var trace = new Rows(events(tmp), ctx)
                .step(new AgentAction("lan_inventory", Map.of(), ""), result(ctx, "noop", "", List.of(), 2_100))
                .trace();
        for (TerminationReason reason : TerminationReason.values()) {
            if (reason == TerminationReason.COMPLETED) continue;
            var r = TaskEnding.apply(ended(reason, "a reason for " + reason), ctx, trace);
            String text = r.response();
            if (reason == TerminationReason.NEEDS_INPUT) {
                assertTrue(text.startsWith("a reason for NEEDS_INPUT\n\n**What it did**"), "the question stays on top: " + text);
                assertFalse(text.contains("**Next:**"), "answering the question is what is next");
            } else {
                assertTrue(text.startsWith("**Stopped:** a reason for " + reason + ".\n\n**What it did** — 1 step, "
                        + "324,866 cloud tokens, "), text);
                assertTrue(text.contains("**Next:** ") && text.endsWith(" Every step is on this task's page: task 03458f80."),
                        reason + ": " + text);
            }
            assertTrue(text.contains(":\n1. ✓ lan_inventory · 2.1s → result 5, 0 chars, public"), text);
            assertTrue(text.contains("\n- result 2 (openwrt_audit): " + String.format("%,d", AUDIT.length())
                    + " chars, private (credentials (2)) — shown to you only, never to the cloud model."), text);
            assertEquals(reason, r.terminationReason());
        }
    }

    @Test
    @DisplayName("public results whole in the text later prompts read; private ones, and public ones repeating them, only on the owner's screen")
    void resultsGoWhereTheyMay() {
        var r = TaskEnding.apply(ended(TerminationReason.PRIVACY_BLOCKED, "blocked"), auditTask(), Map.of());
        String inventory = "192.0.2.1 main router, admin password «vault:OPENWRT_PASS»";
        assertTrue(r.response().contains("- result 1 (lan_inventory): 58 chars, public — in full below."), r.response());
        assertTrue(r.response().contains("**result 1 (lan_inventory):**\n\n" + inventory), r.response());
        assertTrue(r.response().contains("- result 4 (cat_report): " + String.format("%,d", AUDIT.length())
                + " chars, public — it repeats text of a private result, so it is shown to you only."), r.response());

        String scrubbedAudit = AUDIT.replace(PASSWORD, "«vault:OPENWRT_PASS»");
        String said = PrivateIndex.normalise(r.response());
        String audit = PrivateIndex.normalise(scrubbedAudit);
        for (int i = 0; i + PrivateIndex.WINDOW <= audit.length(); i++) {
            assertFalse(said.contains(audit.substring(i, i + PrivateIndex.WINDOW)), "the audit is in the response at " + i);
        }
        assertTrue(r.ownerText().startsWith(r.response()), "the owner reads the same ending, then his results");
        assertTrue(r.ownerText().contains(AgentLoop.PRIVATE_RESULT_HEADER + "**result 2 (openwrt_audit):**\n\n" + scrubbedAudit));
        assertTrue(r.ownerText().contains(AgentLoop.PRIVATE_RESULT_HEADER + "**result 4 (cat_report):**\n\n" + scrubbedAudit));
        // Mutations: send public results to the owner only -> no inventory in the response; skip
        // the canary check -> the audit, repeated by cat_report, is in the response.
    }

    @Test
    @DisplayName("a failed result is shown once: under its step when the step says how it failed, in full below when none does")
    void failuresAreShownOnce(@TempDir Path tmp) throws Exception {
        var ctx = new AgentContext("u1", "a1b2c3d4", "check the mailbox");
        String traceback = "Traceback (most recent call last):\n  File \"skill.py\", line 3, in run\nKeyError: 'uid'";
        var failed = ctx.addArtifact("imap_fetch", Map.of(), Map.of(), traceback, false,
                new Artifact.Decision(Label.PUBLIC, List.of()));
        var trace = new Rows(events(tmp), ctx).step(new AgentAction("imap_fetch", Map.of(), ""),
                Artifact.asObservation(failed, com.ownclaw.agent.tools.ToolResult.failure(traceback), 5)).trace();
        // A result a delegation made: no step row says how it went.
        ctx.addArtifact("port_probe", Map.of(), Map.of(), "connection refused on 192.0.2.7", false,
                new Artifact.Decision(Label.PUBLIC, List.of()));

        var r = TaskEnding.apply(ended(TerminationReason.MAX_STEPS, "it used all 20 steps a task may take"), ctx, trace);
        assertEquals(1, r.response().split("KeyError: 'uid'", -1).length - 1, "once, under its step: " + r.response());
        assertTrue(r.response().contains("- result 1 (imap_fetch): failed, " + traceback.length()
                + " chars, public — shown above."), r.response());
        assertTrue(r.response().contains("- result 2 (port_probe): failed, 31 chars, public — in full below."), r.response());
        assertTrue(r.response().endsWith("**result 2 (port_probe), failed:**\n\nconnection refused on 192.0.2.7"
                + "\n\n**Next:** Reply **continue** to carry on: a new task starts from this message. Every step "
                + "is on this task's page: task a1b2c3d4."), r.response());
    }

    @Test
    @DisplayName("no vault value and no handle in either text, whatever held them")
    void noVaultValueAndNoHandle() {
        var r = TaskEnding.apply(ended(TerminationReason.MAX_STEPS, "it used all 20 steps a task may take"),
                auditTask(), Map.of());
        for (String text : List.of(r.response(), r.ownerText())) {
            assertFalse(text.contains(PASSWORD), text);
            assertFalse(ArtifactRef.TOKEN.matcher(text).find(), "a handle saved to the chat: " + text);
        }
        assertTrue(r.response().contains("private (references result 2)"), "a why names results in words: " + r.response());
    }

    @Test
    @DisplayName("a finished answer is left exactly as the model wrote it")
    void aFinishedAnswerIsUntouched() {
        var done = AgentResult.completed("Here is the audit: {{2}}", new AgentTrajectory(), 1L);
        assertSame(done, TaskEnding.apply(done, auditTask(), Map.of()));
    }

    @Test
    @DisplayName("a question keeps the private answer it placed on the owner's screen, and does not repeat it")
    void aQuestionKeepsItsPrivateText() {
        var ctx = auditTask();
        var asked = AgentResult.needsInput(AgentLoop.PRIVATE_NOTE, new AgentTrajectory(), 1L)
                .withOwnerText(AgentLoop.PRIVATE_RESULT_HEADER + AUDIT + "\n\nWhich router first?");
        var r = TaskEnding.apply(asked, ctx, Map.of());
        assertTrue(r.response().startsWith(AgentLoop.PRIVATE_NOTE + "\n\n**What it did**"), r.response());
        assertTrue(r.ownerText().startsWith(AgentLoop.PRIVATE_RESULT_HEADER), r.ownerText());
        assertTrue(r.ownerText().contains("- result 2 (openwrt_audit): " + String.format("%,d", AUDIT.length())
                + " chars, private (credentials (2)) — shown above."), r.ownerText());
    }

    @Test
    @DisplayName("a privacy block says which result, why it is private, and where its text was found")
    void aBlockIsExplained() {
        var ctx = auditTask();
        assertEquals("the next request to the cloud model held text of result 3 (write_text_file_verbatim), "
                        + "which is private (references {{2}}); it was found in a user message, so nothing was sent",
                TaskEnding.blocked(new EgressRefused("anthropic", 3, "write_text_file_verbatim", 2, "user", 3720), ctx));
        assertEquals("the next request to the cloud model still held the value of your credential OPENWRT_PASS "
                        + "after it was scrubbed, so nothing was sent",
                TaskEnding.blocked(new EgressRefused("anthropic", 0, "vault:OPENWRT_PASS", 1, "user", 9), ctx));
        assertTrue(TaskEnding.blocked(new EgressRefused("anthropic"), ctx)
                .startsWith("the privacy check refused the next request to the cloud model, so nothing was sent"));
    }

    @Test
    @DisplayName("the gateway's refusal names the tool of the result it found and the part it found it in")
    void theGatewayNamesTheResult() {
        var ctx = auditTask();
        var config = new OwnClawConfig();
        config.getMentor().setProvider("anthropic");
        var cloud = new LoopRig.Cloud();
        var gateway = new CloudGateway(cloud, cloud, config, null, null);
        var refused = assertThrows(EgressRefused.class, () -> gateway.chat(
                List.of(LlmMessage.system("S"), LlmMessage.user("here it is: " + AUDIT.substring(0, 120))),
                LlmRequestConfig.DEFAULT.withEgress(ctx.egress("think"))));
        assertEquals(2, refused.handle());
        assertEquals("openwrt_audit", refused.tool(), "the tool, not the literal \"artifact\"");
        assertEquals("user", refused.partKind());
        assertTrue(refused.getMessage().contains("PRIVATE artifact {{2}} (openwrt_audit)"), refused.getMessage());
    }

    @Test
    @DisplayName("a task that ran nothing says so, and the skills a task wrote are listed as kept")
    void nothingRanAndSkillsKept(@TempDir Path tmp) throws Exception {
        var empty = new AgentContext("u1", "abcd1234", "x");
        var r = TaskEnding.apply(ended(TerminationReason.CANCELLED, "you pressed Stop"), empty, Map.of());
        assertTrue(r.response().startsWith("**Stopped:** you pressed Stop.\n\n**What it did** — 0 steps, "
                + "0 cloud tokens, "), r.response());
        assertTrue(r.response().contains(": no step finished.\n\n**What it produced:** nothing."), r.response());
        assertNull(r.ownerText(), "nothing private, so one text");

        var built = new AgentContext("u1", "bcde2345", "x");
        var trace = new Rows(events(tmp), built)
                .step(new AgentAction(AgentAction.SKILL_CREATE, Map.of("name", "openwrt_audit"), ""),
                        AgentObservation.success(AgentAction.SKILL_CREATE, "Skill created", Map.of(), 9))
                .trace();
        var s = TaskEnding.apply(ended(TerminationReason.MAX_STEPS, "it used all 20 steps a task may take"), built, trace);
        assertTrue(s.response().contains("**What it produced:**\n- The skill openwrt_audit: created, and kept for later tasks."),
                s.response());
    }
}
