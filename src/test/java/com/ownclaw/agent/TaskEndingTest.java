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
import java.util.regex.Pattern;

import static com.ownclaw.agent.TaskRecordTest.Rows;
import static com.ownclaw.agent.TaskRecordTest.events;
import static com.ownclaw.agent.TaskRecordTest.result;
import static org.junit.jupiter.api.Assertions.*;

/**
 * How a task ends: why, what it did, what it produced and where it is, what next -- written by
 * code, one text for the owner's screens and the next turn, never holding a private result's
 * text, a vault value or a handle OwnClaw wrote.
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

    static int count(String text, String part) {
        return text.split(Pattern.quote(part), -1).length - 1;
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
    @DisplayName("an unattended run's report says where a reply reads it: on Telegram, where it is sent too, a message goes to the chat open on the web")
    void anUnattendedReportSaysWhereToReply() {
        var attended = TaskEnding.apply(ended(TerminationReason.MAX_STEPS, "it used all 20 steps a task may take"),
                auditTask(), Map.of()).response();
        assertTrue(attended.contains("**Next:** Reply **continue** to carry on: a new task starts from this message."),
                attended);
        for (TerminationReason reason : TerminationReason.values()) {
            if (reason == TerminationReason.COMPLETED || reason == TerminationReason.NEEDS_INPUT) continue;
            var ctx = auditTask();
            ctx.setUnattended(true);
            String text = TaskEnding.apply(ended(reason, "a reason for " + reason), ctx, Map.of()).response();
            String next = text.substring(text.indexOf("**Next:** "));
            assertTrue(next.contains(" in the web chat that holds this report "), reason + ": " + next);
        }
        var ctx = auditTask();
        ctx.setUnattended(true);
        String report = TaskEnding.apply(ended(TerminationReason.MAX_STEPS, "it used all 20 steps a task may take"),
                ctx, Map.of()).response();
        assertTrue(report.contains("**Next:** Reply **continue** in the web chat that holds this report to carry on: "
                + "a new task starts from this message."), report);
        // Mutation: one Next line for both -> a Telegram "continue" went to the open chat and
        // started a task that had never seen the report.
    }

    @Test
    @DisplayName("public results whole in the text later prompts read; private ones, and public ones repeating them, only on the owner's screen")
    void resultsGoWhereTheyMay() {
        var r = TaskEnding.apply(ended(TerminationReason.PRIVACY_BLOCKED, "blocked"), auditTask(), Map.of());
        String inventory = "192.0.2.1 main router, admin password «vault:OPENWRT_PASS»";
        assertTrue(r.response().contains("- result 1 (lan_inventory): 58 chars, public — in full below."), r.response());
        assertTrue(r.response().contains("**result 1 (lan_inventory):**\n\n" + inventory), r.response());
        assertTrue(r.response().contains("- result 4 (cat_report): " + String.format("%,d", AUDIT.length())
                + " chars, public — it repeats text of result 2 (openwrt_audit), which is private, so it is "
                + "shown to you only."), r.response());

        String scrubbedAudit = AUDIT.replace(PASSWORD, "«vault:OPENWRT_PASS»");
        String said = PrivateIndex.normalise(r.response());
        String audit = PrivateIndex.normalise(scrubbedAudit);
        for (int i = 0; i + PrivateIndex.WINDOW <= audit.length(); i++) {
            assertFalse(said.contains(audit.substring(i, i + PrivateIndex.WINDOW)), "the audit is in the response at " + i);
        }
        assertTrue(r.ownerText().startsWith(r.response()), "the owner reads the same ending, then his results");
        assertTrue(r.ownerText().contains(AgentLoop.PRIVATE_RESULT_HEADER + "**result 2 (openwrt_audit):**\n\n" + scrubbedAudit));
        assertTrue(r.ownerText().contains(AgentLoop.PRIVATE_RESULT_HEADER + "**result 4 (cat_report):**\n\n" + scrubbedAudit));
        assertTrue(r.response().endsWith("**Next:** Your next message starts a new task, which reads this message "
                + "but not the private results. Every step is on this task's page: task 03458f80."), r.response());
        // Mutations: send public results to the owner only -> no inventory in the response; skip
        // the canary check -> the audit, repeated by cat_report, is in the response.
    }

    @Test
    @DisplayName("a public result that a later private one quotes stays public: the gateway would send it, so the ending does")
    void theGatewaysRuleDecides() {
        String digest = "Morning digest: the backup finished at 04:10, two updates are waiting, disk 71% full.";
        var ctx = new AgentContext("u1", "d1d2d3d4", "send me the digest");
        ctx.addArtifact("daily_news_digest", Map.of(), Map.of(), digest, true, new Artifact.Decision(Label.PUBLIC, List.of()));
        ctx.addArtifact("smtp_send_email", Map.of(), Map.of(), "Sent to someone@example.org: " + digest
                + " (message id 4471-abc@example.org)", true, new Artifact.Decision(Label.PRIVATE, List.of("credentials (1)")));

        var r = TaskEnding.apply(ended(TerminationReason.MAX_STEPS, "it used all 20 steps a task may take"), ctx, Map.of());
        assertTrue(r.response().contains("- result 1 (daily_news_digest): " + digest.length()
                + " chars, public — in full below."), r.response());
        assertTrue(r.response().contains("**result 1 (daily_news_digest):**\n\n" + digest), r.response());
        assertFalse(r.response().contains("someone@example.org"), "the confirmation is private: " + r.response());
        // Mutation: ask the canary without the gateway's excuse -> the digest is withheld as
        // "repeating" the confirmation that quotes it.
    }

    @Test
    @DisplayName("a result that merely occurs in the text above is still shown: a count of 3 in a task of 3 steps")
    void aShortResultIsShown(@TempDir Path tmp) throws Exception {
        var ctx = new AgentContext("u1", "c0c0c0c0", "how many unread?");
        var trace = new Rows(events(tmp), ctx)
                .step(new AgentAction("count_unread", Map.of(), ""), result(ctx, "count_unread", "3", List.of(), 5))
                .step(new AgentAction("noop", Map.of(), ""), AgentObservation.success("noop", "nothing", Map.of(), 1))
                .step(new AgentAction("noop", Map.of(), ""), AgentObservation.success("noop", "nothing", Map.of(), 1))
                .trace();
        ctx.addArtifact("imap_count", Map.of(), Map.of(), "3", true,
                new Artifact.Decision(Label.PRIVATE, List.of("credentials (1)")));

        var r = TaskEnding.apply(ended(TerminationReason.MAX_STEPS, "it used all 3 steps a task may take"), ctx, trace);
        assertTrue(r.response().contains("**What it did** — 3 steps"), r.response());
        assertTrue(r.response().contains("- result 1 (count_unread): 1 chars, public — in full below."), r.response());
        assertTrue(r.response().contains("**result 1 (count_unread):**\n\n3\n\n**Next:**"), r.response());
        assertTrue(r.ownerText().endsWith(AgentLoop.PRIVATE_RESULT_HEADER + "**result 2 (imap_count):**\n\n3"), r.ownerText());
        // Mutation: "shown" as "somewhere in the text above" -> both counts are dropped.
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
        assertEquals(1, count(r.response(), "KeyError: 'uid'"), "once, under its step: " + r.response());
        assertTrue(r.response().contains("- result 1 (imap_fetch): failed, " + traceback.length()
                + " chars, public — shown above."), r.response());
        assertTrue(r.response().contains("- result 2 (port_probe): failed, 31 chars, public — in full below."), r.response());
        assertTrue(r.response().endsWith("**result 2 (port_probe), failed:**\n\nconnection refused on 192.0.2.7"
                + "\n\n**Next:** Reply **continue** to carry on: a new task starts from this message. Every step "
                + "is on this task's page: task a1b2c3d4."), r.response());
    }

    @Test
    @DisplayName("a failed result holding a vault value is found under its step as its row keeps it, scrubbed, and shown once")
    void aScrubbedFailureIsShownOnce(@TempDir Path tmp) throws Exception {
        var ctx = new AgentContext("u1", "b1b2b3b4", "log in to the router");
        ctx.setSecretValues(Map.of("OPENWRT_PASS", PASSWORD));
        String output = "login failed for admin:" + PASSWORD + " on 192.0.2.1";
        var failed = ctx.addArtifact("ssh_login", Map.of(), Map.of(), output, false,
                new Artifact.Decision(Label.PUBLIC, List.of()));
        var trace = new Rows(events(tmp), ctx).step(new AgentAction("ssh_login", Map.of(), ""),
                Artifact.asObservation(failed, com.ownclaw.agent.tools.ToolResult.failure(output), 5)).trace();

        var r = TaskEnding.apply(ended(TerminationReason.MAX_STEPS, "it used all 20 steps a task may take"), ctx, trace);
        assertEquals(1, count(r.response(), "login failed for admin:«vault:OPENWRT_PASS» on 192.0.2.1"), r.response());
        assertTrue(r.response().contains("public — shown above."), r.response());
        assertFalse(r.response().contains(PASSWORD));
        // Mutation: compare the unscrubbed text with the row -> the failure is printed twice.
    }

    @Test
    @DisplayName("no vault value in either text, and no handle in anything OwnClaw writes")
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
    @DisplayName("a result is quoted as it was written: a template's {{1}} is its text, while OwnClaw's own lines use words")
    void resultsAreQuotedAsWritten() {
        String template = "Hello {{1}}, your order {{2}} ships on {{3}}.";
        var ctx = new AgentContext("u1", "e1e2e3e4", "draft the shipping message");
        ctx.addArtifact("order_lookup", Map.of(), Map.of(), "order 4471 for Jana", true,
                new Artifact.Decision(Label.PUBLIC, List.of()));
        ctx.addArtifact("whatsapp_template", Map.of(), Map.of(), template, true,
                new Artifact.Decision(Label.PRIVATE, List.of("references {{1}}")));
        String reminder = "Reminder: {{1}} is due on {{2}}.";
        ctx.addArtifact("sms_template", Map.of(), Map.of(), reminder, true,
                new Artifact.Decision(Label.PUBLIC, List.of()));

        var r = TaskEnding.apply(ended(TerminationReason.MAX_STEPS, "it used all 20 steps a task may take"), ctx, Map.of());
        assertTrue(r.ownerText().endsWith("**result 2 (whatsapp_template):**\n\n" + template), r.ownerText());
        assertTrue(r.response().contains("**result 3 (sms_template):**\n\n" + reminder), r.response());
        assertTrue(r.response().contains("- result 2 (whatsapp_template): 45 chars, private (references result 1)"),
                r.response());
        // Mutation: rewrite the handles over the results too -> "Hello result 1, your order result 2 ...".
    }

    @Test
    @DisplayName("a finished answer is left as the model placed it -- but for vault values, scrubbed from both texts")
    void aFinishedAnswerKeepsItsWordsButNoVaultValue() {
        var ctx = auditTask();
        var done = AgentResult.completed("Here is the audit: {{2}}", new AgentTrajectory(), 1L);
        var same = TaskEnding.apply(done, ctx, Map.of());
        assertEquals("Here is the audit: {{2}}", same.response());
        assertNull(same.ownerText());
        assertEquals(TerminationReason.COMPLETED, same.terminationReason());

        var placed = AgentLoop.answerFor("{{2}}", ctx);
        var r = TaskEnding.apply(AgentResult.completed(placed.response(), new AgentTrajectory(), 1L)
                .withOwnerText(placed.ownerText()), ctx, Map.of());
        assertEquals(AgentLoop.PRIVATE_NOTE, r.response());
        assertEquals(AgentLoop.PRIVATE_RESULT_HEADER + AUDIT.replace(PASSWORD, "«vault:OPENWRT_PASS»"), r.ownerText());
        var inventory = AgentLoop.answerFor("{{1}}", ctx);
        assertEquals("192.0.2.1 main router, admin password «vault:OPENWRT_PASS»", TaskEnding.apply(
                AgentResult.completed(inventory.response(), new AgentTrajectory(), 1L), ctx, Map.of()).response());
        // Mutation: return a finished answer untouched -> the router password in both texts.
    }

    @Test
    @DisplayName("a question keeps the private answer it placed on the owner's screen, and does not repeat it")
    void aQuestionKeepsItsPrivateText() {
        var ctx = auditTask();
        var q = AgentLoop.answerFor("{{2}}", ctx);
        var r = TaskEnding.apply(AgentResult.needsInput(q.response(), new AgentTrajectory(), 1L)
                .withOwnerText(q.ownerText()), ctx, Map.of());
        assertTrue(r.response().startsWith(AgentLoop.PRIVATE_NOTE + "\n\n**What it did**"), r.response());
        assertTrue(r.ownerText().startsWith(AgentLoop.PRIVATE_RESULT_HEADER), r.ownerText());
        assertTrue(r.ownerText().contains("- result 2 (openwrt_audit): " + String.format("%,d", AUDIT.length())
                + " chars, private (credentials (2)) — shown above."), r.ownerText());
        String scrubbedAudit = AUDIT.replace(PASSWORD, "«vault:OPENWRT_PASS»");
        assertEquals(2, count(r.ownerText(), scrubbedAudit), "placed once, and repeated once by cat_report: " + r.ownerText());
    }

    @Test
    @DisplayName("a question that is a public result is not printed again below it")
    void aQuestionThatIsAResultIsNotRepeated() {
        var ctx = new AgentContext("u1", "f1f2f3f4", "which disks are nearly full?");
        String listing = "/dev/sda1 91% /\n/dev/sdb1 97% /srv\n/dev/sdc1 12% /backup\nWhich ones should I clean?";
        ctx.addArtifact("disk_usage", Map.of(), Map.of(), listing, true, new Artifact.Decision(Label.PUBLIC, List.of()));
        var q = AgentLoop.answerFor("{{1}}", ctx);
        var r = TaskEnding.apply(AgentResult.needsInput(q.response(), new AgentTrajectory(), 1L)
                .withOwnerText(q.ownerText()), ctx, Map.of());
        assertTrue(r.response().startsWith(listing + "\n\n**What it did**"), r.response());
        assertEquals(1, count(r.response(), listing), r.response());
        assertTrue(r.response().contains("- result 1 (disk_usage): " + listing.length() + " chars, public — shown above."),
                r.response());
        // Mutation: leave the question out of what counts as shown -> the listing twice.
    }

    @Test
    @DisplayName("a question names results in words -- the next turn carries it on -- and a result it shows that way is quoted as written below")
    void aQuestionIsInWordsAndItsResultAsWritten() {
        var ctx = new AgentContext("u1", "b2b2b2b2", "draft the shipping message");
        String template = "Hello {{1}}, your order {{2}} ships on {{3}}.";
        ctx.addArtifact("whatsapp_template", Map.of(), Map.of(), template, true, new Artifact.Decision(Label.PUBLIC, List.of()));

        var prose = AgentLoop.answerFor("Should I send {{1}} to Jana?", ctx);
        var asked = TaskEnding.apply(AgentResult.needsInput(prose.response(), new AgentTrajectory(), 1L)
                .withOwnerText(prose.ownerText()), ctx, Map.of());
        assertTrue(asked.response().startsWith("Should I send result 1 to Jana?\n\n**What it did**"), asked.response());

        var placed = AgentLoop.answerFor("{{1}}", ctx);
        var r = TaskEnding.apply(AgentResult.needsInput(placed.response(), new AgentTrajectory(), 1L)
                .withOwnerText(placed.ownerText()), ctx, Map.of());
        assertTrue(r.response().startsWith("Hello result 1, your order result 2 ships on result 3.\n\n**What it did**"),
                r.response());
        assertTrue(r.response().contains("- result 1 (whatsapp_template): 45 chars, public — in full below."), r.response());
        assertTrue(r.response().endsWith("**result 1 (whatsapp_template):**\n\n" + template), r.response());
        // Mutations: quote the question as written -> "Should I send {{1}}", a handle the next
        // task resolves to its own result; count a result shown when the question showed it
        // reworded -> the template is nowhere as written.
    }

    @Test
    @DisplayName("on a file task the local answer a question carries for the owner is not repeated below it")
    void aQuestionsLocalAnswerIsNotRepeated() {
        var ctx = new AgentContext("u1", "9a9a9a9a", "summarise this statement");
        ctx.addFile("f1", "", List.of("uploaded file", "application/pdf, 84211 bytes, no text read"));
        String answer = "Closing balance 48,213.07 CZK on 30 September.";
        ctx.addArtifact("local_answer", Map.of(), Map.of(), answer, true,
                new Artifact.Decision(Label.PRIVATE, List.of("given the file {{1}}")));
        var q = AgentLoop.answerFor("Which month should I compare it with?", ctx);
        var r = TaskEnding.apply(AgentResult.needsInput(q.response(), new AgentTrajectory(), 1L)
                .withOwnerText(q.ownerText()), ctx, Map.of());
        assertEquals(1, count(r.ownerText(), answer), r.ownerText());
        assertTrue(r.ownerText().contains("- result 2 (local_answer): 46 chars, private (given the file result 1) — shown above."),
                r.ownerText());
        assertFalse(r.response().contains(answer), "never in the text later prompts read");
        // Mutation: leave the carried local answers unmarked -> the answer twice.
    }

    @Test
    @DisplayName("a file the owner sent is described, never repeated: he has it")
    void anAttachmentIsDescribed() {
        var ctx = new AgentContext("u1", "a7a7a7a7", "what does my statement say?");
        String statement = "Closing balance 48,213.07 CZK on 30 September.";
        ctx.addFile("f1", statement, List.of("uploaded file", "text/plain, 46 bytes"));
        var r = TaskEnding.apply(ended(TerminationReason.ERROR, "the local model is not answering"), ctx, Map.of());
        assertTrue(r.response().contains("- result 1 (attachment): 46 chars, private (uploaded file; text/plain, 46 bytes)."),
                r.response());
        assertFalse(r.response().contains("48,213"));
        assertNull(r.ownerText(), "nothing to add for the owner: the file is his");
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
        assertTrue(TaskEnding.blocked(new EgressRefused("anthropic", 9, "ghost", 2, "user", 5), ctx)
                .startsWith("the privacy check refused the next request to the cloud model, so nothing was sent ("),
                "a result the task does not have is not looked up");
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
        assertTrue(s.response().contains("**What it produced:**\n- The skill openwrt_audit: written, and kept for later tasks."),
                s.response());
    }

    @Test
    @DisplayName("a skill written and then deleted is not kept, and its step says it was deleted")
    void aDeletedSkillIsNotKept(@TempDir Path tmp) throws Exception {
        var ctx = new AgentContext("u1", "de1e7ed0", "clean up the audit skill");
        var trace = new Rows(events(tmp), ctx)
                .step(new AgentAction(AgentAction.SKILL_CREATE, Map.of("name", "openwrt_audit"), ""),
                        AgentObservation.success(AgentAction.SKILL_CREATE, "Skill 'openwrt_audit' created and registered.", Map.of(), 9))
                .step(new AgentAction(AgentAction.SKILL_MANAGE, Map.of("action", "delete", "name", "openwrt_audit"), ""),
                        AgentObservation.success(AgentAction.SKILL_MANAGE, "Skill 'openwrt_audit' has been permanently deleted.",
                                Map.of(), 3))
                .trace();
        var r = TaskEnding.apply(ended(TerminationReason.MAX_STEPS, "it used all 2 steps a task may take"), ctx, trace);
        assertTrue(r.response().contains("\n2. ✓ skill_manage delete openwrt_audit · 3ms"), r.response());
        assertTrue(r.response().contains("**What it produced:** nothing."), r.response());
        // Mutations: drop the action from the row, or keep a deleted skill -> "kept for later tasks".
    }
}
