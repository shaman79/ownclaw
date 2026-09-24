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
        var d = new LinkedHashMap<String, Object>();
        AgentLoop.stepOutcome(d, obs, claimed);
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
        assertFalse(d.containsKey("reportedFailure"));
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
        var a = new Artifact(1, "attachment:statement.csv", Map.of(), Map.of(),
                "acct," + marker + ",41200", true, Label.PRIVATE, List.of("attachment"), true);
        var d = AgentLoop.attachmentDetails(a);
        assertEquals("{{1}}", d.get("artifact"));
        assertEquals("PRIVATE", d.get("label"));
        assertEquals(true, d.get("indexed"));
        assertFalse(String.valueOf(d).contains(marker));
    }
}
