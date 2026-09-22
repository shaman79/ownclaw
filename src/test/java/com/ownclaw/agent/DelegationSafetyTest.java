package com.ownclaw.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The two guards that stand between forced delegation and a bad morning.
 * <p>
 * Once unattended work must go through the local model, every gate on the cloud path stops
 * applying to the work that actually runs. {@code AgentLoop} handles {@code delegate} and
 * continues before {@code CriticAgent} is consulted, and {@link LocalExecutor} calls
 * {@code tool.execute} straight off the registry. Two of those gates matter enough to rebuild
 * here rather than leave to {@code max_steps}:
 * <ul>
 *   <li>The critic blocks an identical action after three tries. Without it, a local model that
 *       is unsure whether {@code smtp_send_email} worked sends the owner ten copies.</li>
 *   <li>Success was "the result string does not start with ERROR", so a model that fetched
 *       nothing and wrote a confident summary produced a delivered scheduled run.</li>
 * </ul>
 */
class DelegationSafetyTest {

    private static LocalExecutor.StepResult step(String tool, Map<String, Object> params,
                                                 String output) {
        return new LocalExecutor.StepResult(tool, params, output, true);
    }

    // ── repeat suppression ──

    @Test
    @DisplayName("an identical earlier call is found, whenever it happened")
    void identicalCallIsFound() {
        var done = List.of(
                step("smtp_send_email", Map.of("to", "petr@example.com", "subject", "Digest"),
                        "Sent, message id 42"),
                step("daily_news_digest", Map.of(), "...headlines..."));

        assertEquals("Sent, message id 42",
                LocalExecutor.priorIdenticalOutput("smtp_send_email",
                        Map.of("to", "petr@example.com", "subject", "Digest"), done),
                "not only the immediately preceding call: a delegation is short, and re-sending "
                        + "the same email with one unrelated call in between is still sending it "
                        + "twice");
    }

    @Test
    @DisplayName("different arguments are a different call")
    void differentArgumentsAreNotARepeat() {
        var done = List.of(step("smtp_send_email", Map.of("to", "a@example.com"), "ok"));
        assertNull(LocalExecutor.priorIdenticalOutput("smtp_send_email",
                        Map.of("to", "b@example.com"), done),
                "two recipients is two emails, which is the goal, not a mistake");
    }

    @Test
    @DisplayName("no arguments and null arguments are the same call")
    void nullParamsMatchEmptyParams() {
        var done = List.of(step("publish_report", Map.of(), "published"));
        assertEquals("published",
                LocalExecutor.priorIdenticalOutput("publish_report", null, done),
                "a model that omits an empty argument object has not made a different call");
    }

    @Test
    @DisplayName("a call that never happened is not suppressed")
    void freshCallIsAllowed() {
        assertNull(LocalExecutor.priorIdenticalOutput("smtp_send_email",
                Map.of("to", "a@example.com"), List.of()));
    }

    // ── a claim of completion needs evidence ──

    @Test
    @DisplayName("finishing without running anything is not a success")
    void zeroStepsIsNotSuccess() {
        var outcome = LocalExecutor.completed(
                "I have fetched today's headlines and emailed the digest.", List.of());

        assertFalse(outcome.ok(),
                "the summary reads like a delivered job; nothing ran. With the registry "
                        + "withheld the cloud cannot check this, so it has to fail here");
        assertEquals(0, outcome.stepCount());
        assertTrue(outcome.text().contains("No tool was executed"),
                "and the reason has to be visible in what the cloud reads back");
    }

    @Test
    @DisplayName("a real delegation carries its ledger with the claim")
    void successCarriesEvidence() {
        var outcome = LocalExecutor.completed("Digest sent.", List.of(
                step("daily_news_digest", Map.of(), "...headlines..."),
                step("smtp_send_email", Map.of("to", "petr@example.com"), "Sent")));

        assertTrue(outcome.ok());
        assertEquals(2, outcome.stepCount());
        assertEquals(List.of("daily_news_digest", "smtp_send_email"), outcome.toolsRun(),
                "these names are what skills_used and skill curation record; without them a "
                        + "skill used every morning looks untouched to maintenance");
        assertTrue(outcome.text().startsWith("Digest sent."));
        assertTrue(outcome.text().contains("daily_news_digest ok"),
                "the claim and the evidence travel together, or a false success is not even "
                        + "auditable afterwards");
    }

    // ── passing a result on without retyping it ──

    @Test
    @DisplayName("a parameter that is exactly $1 becomes step 1's output")
    void referenceIsSubstituted() {
        var done = List.of(step("daily_news_digest", Map.of(), "DIGEST 2026-09-22\nline two"));
        var out = LocalExecutor.substituteRefs(
                Map.of("to", "petr@example.com", "body", "$1"), done);

        assertEquals("DIGEST 2026-09-22\nline two", out.get("body"),
                "the text the owner reads must never pass through the model as output tokens");
        assertEquals("petr@example.com", out.get("to"), "other parameters are untouched");
    }

    @Test
    @DisplayName("$1 inside a larger string is left alone")
    void onlyWholeValuesAreReferences() {
        var done = List.of(step("x", Map.of(), "OUTPUT"));
        var out = LocalExecutor.substituteRefs(
                Map.of("command", "for f in *; do echo \"$1\"; done"), done);

        assertEquals("for f in *; do echo \"$1\"; done", out.get("command"),
                "shell is full of $1, and rewriting one inside a script would be a far worse "
                        + "bug than the one this fixes");
    }

    @Test
    @DisplayName("a reference to a step that has not run is left as written")
    void outOfRangeReferenceIsNotSubstituted() {
        var done = List.of(step("x", Map.of(), "OUTPUT"));
        assertEquals("$7", LocalExecutor.substituteRefs(Map.of("body", "$7"), done).get("body"),
                "silently substituting the wrong step would be worse than an obvious literal");
        assertEquals("$1", LocalExecutor.substituteRefs(Map.of("body", "$1"), List.of())
                        .get("body"),
                "and on the first step there is nothing to reference yet");
    }

    @Test
    @DisplayName("$0 and $abc are not references")
    void malformedReferencesAreLeftAlone() {
        var done = List.of(step("x", Map.of(), "OUTPUT"));
        assertEquals("$0", LocalExecutor.substituteRefs(Map.of("b", "$0"), done).get("b"));
        assertEquals("$abc", LocalExecutor.substituteRefs(Map.of("b", "$abc"), done).get("b"));
        assertEquals("$", LocalExecutor.substituteRefs(Map.of("b", "$"), done).get("b"));
    }

    // ── what the model is shown of a result ──

    @Test
    @DisplayName("a small result is shown in full")
    void smallResultsAreNotTouched() {
        String small = "Sent, message id 42";
        assertEquals(small, LocalExecutor.feedback(small, 1),
                "there is nothing to gain by hiding a short result, and the model reasons "
                        + "better with it in front of it");
    }

    @Test
    @DisplayName("a large result is excerpted and replaced with its reference")
    void largeResultsBecomeAReference() {
        String digest = "HEAD-" + "x".repeat(4000) + "-TAIL";
        String shown = LocalExecutor.feedback(digest, 1);

        assertTrue(shown.length() < digest.length() / 2,
                "a real delegation died with done_reason=length because step 1's result was "
                        + "fed back whole and the model then had to regenerate it");
        assertTrue(shown.startsWith("HEAD-"), "it still needs to know what it got");
        assertTrue(shown.endsWith("]"), "and how to move it");
        assertTrue(shown.contains("-TAIL"), "the tail says whether the result was complete");
        assertTrue(shown.contains("$1"), "the reference is the whole point");
        assertTrue(shown.contains(LocalExecutor.OMISSION_MARKER),
                "the marker is what makes a retyped excerpt detectable rather than silent");
    }

    @Test
    @DisplayName("a retyped excerpt is caught by its marker")
    void retypedExcerptIsDetected() {
        String shown = LocalExecutor.feedback("HEAD" + "x".repeat(4000) + "TAIL", 1);
        // Exactly what a real delegation did: copied what it was shown into the next call.
        var params = Map.<String, Object>of("path", "/tmp/out.txt", "content", shown);

        assertEquals("content", LocalExecutor.retypedExcerpt(params),
                "this reached a file as though it were the digest, and the delegation reported "
                        + "success — silent truncation is the one failure the cloud cannot see");
    }

    @Test
    @DisplayName("ordinary arguments are not mistaken for a retyped excerpt")
    void normalParamsAreNotFlagged() {
        assertNull(LocalExecutor.retypedExcerpt(
                Map.of("body", "Here is the digest, see attached.", "to", "petr@example.com")));
        assertNull(LocalExecutor.retypedExcerpt(Map.of("body", "$1")));
        assertNull(LocalExecutor.retypedExcerpt(Map.of()));
        assertNull(LocalExecutor.retypedExcerpt(null));
    }

    @Test
    @DisplayName("the excerpt is too small to be worth copying")
    void excerptIsSmall() {
        String shown = LocalExecutor.feedback("z".repeat(9000), 1);
        assertTrue(shown.length() < 1200,
                "1,500 characters was small enough to fail on context and large enough to be "
                        + "retyped — the worst of both");
        assertTrue(shown.contains("9000 characters"), "it still says how much there really is");
    }

    @Test
    @DisplayName("the reference number is the step that produced it")
    void referenceNumberMatchesTheStep() {
        String big = "y".repeat(3000);
        assertTrue(LocalExecutor.feedback(big, 3).contains("$3"));
        // ...and that is the number substituteRefs resolves against the same list.
        var done = List.of(step("a", Map.of(), "first"), step("b", Map.of(), "second"),
                step("c", Map.of(), big));
        assertEquals(big, LocalExecutor.substituteRefs(Map.of("body", "$3"), done).get("body"));
    }

    @Test
    @DisplayName("a failed tool is named as failed in the ledger")
    void failuresAreVisibleInTheLedger() {
        var outcome = LocalExecutor.completed("Done.", List.of(
                new LocalExecutor.StepResult("smtp_send_email", Map.of(), "ERROR: auth", false)));

        assertTrue(outcome.text().contains("smtp_send_email FAILED"),
                "a summary that says 'Done.' over a failed send is exactly what the ledger is "
                        + "there to contradict");
    }
}
