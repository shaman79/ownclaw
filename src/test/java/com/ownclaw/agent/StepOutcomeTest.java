package com.ownclaw.agent;

import com.ownclaw.privacy.Label;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** What a step's row says about how it went — and what it must never hold. */
class StepOutcomeTest {

    private static Artifact artifact(Label label, String output, boolean success, boolean indexed) {
        return new Artifact(3, "web_fetch", Map.of(), Map.of(), output, success, label, List.of(), indexed);
    }

    private static Map<String, Object> outcome(AgentObservation obs, Optional<Artifact> claimed) {
        return outcome(obs, claimed, false, Map.of());
    }

    private static Map<String, Object> outcome(AgentObservation obs, Optional<Artifact> claimed,
                                               boolean afterPrivateRead, Map<String, String> secrets) {
        var d = new LinkedHashMap<String, Object>();
        AgentLoop.stepOutcome(d, obs, claimed, afterPrivateRead, secrets);
        return d;
    }

    @Test
    @DisplayName("a failed PRIVATE step records no excerpt")
    void privateFailuresHaveNoExcerpt() {
        var priv = artifact(Label.PRIVATE, "Traceback: mailbox someone@example.com", false, true);
        var d = outcome(AgentObservation.failure("imap_fetch", priv.describe(), 5), Optional.of(priv));
        assertFalse(d.containsKey("reason"), "its observation is only a descriptor, and even that stays out");
        assertEquals(true, d.get("indexed"));
    }

    @Test
    @DisplayName("a skill that reports ok:false is recorded as a failure, with how it failed")
    void reportedFailureHasItsReason() {
        String out = "{\"ok\": false, \"error\": \"SMTP connection error: timed out\"}";
        var pub = artifact(Label.PUBLIC, out, true, true);
        var d = outcome(AgentObservation.success("smtp_send_email", out, Map.of(), 5), Optional.of(pub));
        assertEquals(true, d.get("reportedFailure"));
        assertTrue(String.valueOf(d.get("reason")).contains("timed out"));
    }

    @Test
    @DisplayName("a success records neither a failure nor a reason")
    void successIsQuiet() {
        var pub = artifact(Label.PUBLIC, "{\"ok\": true}", true, false);
        var d = outcome(AgentObservation.success("x", "{\"ok\": true}", Map.of(), 5), Optional.of(pub));
        assertEquals(false, d.get("reportedFailure"), "always written, so a missing key means an old row");
        assertFalse(d.containsKey("reason"));
        assertEquals(false, d.get("indexed"));
    }

    @Test
    @DisplayName("a failed step with no result of its own -- a delegation, a missing tool -- keeps its reason")
    void failuresWithoutAnArtifact() {
        var d = outcome(AgentObservation.failure("delegate",
                "Delegation incomplete: Local LLM call failed: done_reason=length", 5), Optional.empty());
        assertTrue(String.valueOf(d.get("reason")).contains("done_reason=length"));
    }

    @Test
    @DisplayName("a delegation that failed after reading private data records no excerpt")
    void taintedDelegationHasNoExcerpt() {
        String failure = "Delegation incomplete: Local LLM call failed: [ollama] HTTP 500: "
                + "error parsing tool call: raw='{\"body\":\"Dear customer, your mortgage account 55-019\"}'";
        var d = outcome(AgentObservation.failure("delegate", failure, 5), Optional.empty(), true, Map.of());
        assertFalse(d.containsKey("reason"));
        assertTrue(outcome(AgentObservation.failure("delegate", failure, 5), Optional.empty())
                .containsKey("reason"), "an untainted one keeps its reason");
    }

    @Test
    @DisplayName("a vault value in a failure is scrubbed before it is stored")
    void secretsAreScrubbed() {
        String secret = "hunter2-very-secret-pass";
        String failure = "Traceback ... smtplib login failed for user:" + secret + "@smtp " + "x".repeat(500);
        var d = outcome(AgentObservation.failure("smtp_send_email", failure, 5), Optional.empty(), false,
                Map.of("SMTP_PASS", secret));
        assertFalse(String.valueOf(d.get("reason")).contains(secret));
        assertTrue(String.valueOf(d.get("reason")).contains("«vault:SMTP_PASS»"));
    }

    @Test
    @DisplayName("a real step row: after a private read a failed delegation keeps no excerpt; vault values never")
    void stepRowWiring() {
        var ctx = new AgentContext("u1", "t1", "check the bank mail");
        String secret = "hunter2-very-secret";
        ctx.setSecretValues(Map.of("IMAP_PASS", secret));
        var delegate = new AgentAction(AgentAction.DELEGATE, Map.of("goal", "summarise the mail"), "");
        String failure = "Local LLM call failed: login " + secret + " raw='Dear customer, account 55-019'";

        var before = AgentLoop.stepDetails(ctx, delegate, AgentObservation.failure("delegate", failure, 5), 1);
        assertTrue(before.containsKey("reason"));
        assertFalse(String.valueOf(before.get("reason")).contains(secret));
        assertEquals(false, before.get("reportedFailure"));

        ctx.markLocalTierReadPrivate();
        var after = AgentLoop.stepDetails(ctx, delegate, AgentObservation.failure("delegate", failure, 5), 2);
        assertFalse(after.containsKey("reason"), String.valueOf(after));
    }

    @Test
    @DisplayName("a public step whose failure quotes private text keeps no excerpt: the canary's own index decides")
    void quotedPrivateTextIsWithheld() {
        var ctx = new AgentContext("u1", "t1", "parse the statement");
        String statement = "Account CZ65 0800 0000 1920 0014 5399 balance 41200 CZK statement for September";
        ctx.privateIndex().addPrivate(1, statement);
        var parse = new AgentAction("json_parse_file", Map.of(), "");
        var quoted = AgentObservation.failure("json_parse_file", "JSONDecodeError while parsing: " + statement, 5);
        assertFalse(AgentLoop.stepDetails(ctx, parse, quoted, 3).containsKey("reason"));
        var plain = AgentObservation.failure("json_parse_file", "JSONDecodeError: Expecting value: line 1", 5);
        assertTrue(AgentLoop.stepDetails(ctx, parse, plain, 4).containsKey("reason"));
    }

    @Test
    @DisplayName("the excerpt is the head and the tail")
    void excerpt() {
        assertEquals("", AgentLoop.failureExcerpt(null));
        assertEquals("short", AgentLoop.failureExcerpt("short"));
        String long_ = "H".repeat(300) + "M".repeat(400) + "T".repeat(300);
        String e = AgentLoop.failureExcerpt(long_);
        assertEquals("H".repeat(200) + "\n…\n" + "T".repeat(200), e);
    }

    @Test
    @DisplayName("an attachment's row names it and its label, never its text")
    void attachmentRowHasNoText() {
        String marker = "IBAN-CZ65-0800-0000-1920-0014-5399-MARKER";
        var a = new Artifact(1, "attachment", Map.of(), Map.of(),
                "acct," + marker + ",41200", true, Label.PRIVATE, List.of("uploaded file"), true);
        var d = AgentLoop.attachmentDetails(a, "statement.csv");
        assertEquals("{{1}}", d.get("artifact"));
        assertEquals("statement.csv", d.get("name"), "the owner's task page shows which file");
        assertEquals("PRIVATE", d.get("label"));
        assertEquals(true, d.get("indexed"));
        assertFalse(String.valueOf(d).contains(marker));
    }
}
