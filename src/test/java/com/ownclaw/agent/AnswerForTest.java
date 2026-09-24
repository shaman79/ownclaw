package com.ownclaw.agent;

import com.ownclaw.privacy.Label;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.ownclaw.agent.AgentLoop.PRIVATE_HEADER;
import static com.ownclaw.agent.AgentLoop.PRIVATE_NOTE;
import static org.junit.jupiter.api.Assertions.*;

/**
 * What the cloud writes as its answer, and what is delivered: through the one resolver, with a
 * private answer filled in on this machine and kept apart from the text everything else reads.
 */
class AnswerForTest {

    static final List<String> PDF = List.of("uploaded file",
            "application/pdf, 84211 bytes, no text read (not text, over 100 KB, or not UTF-8)");
    static final String ANSWER = "Closing balance 48,213.07 CZK on 30 September.";

    private static Artifact.Decision label(Label label) {
        return new Artifact.Decision(label, List.of("test"));
    }

    /** {{1}} a public page, {{2}} a private result. */
    private static AgentContext twoResults() {
        var ctx = new AgentContext("u1", "t1", "what came in?");
        ctx.addArtifact("web_fetch", Map.of(), Map.of(), "The public page.", true, label(Label.PUBLIC));
        ctx.addArtifact("imap_fetch", Map.of(), Map.of(), "{\"body_text\":\"the mail\"}", true,
                label(Label.PRIVATE));
        return ctx;
    }

    /** A task holding a PDF: {{1}} the file, {{2}} what a skill read from it, {{3}} the local answer. */
    private static AgentContext fileTask() {
        var ctx = new AgentContext("u1", "t1", "summarise this statement");
        ctx.addFile("f1", "", PDF);
        ctx.addArtifact("pdf_text", Map.of(), Map.of(), "statement text", true,
                new Artifact.Decision(Label.PRIVATE, List.of("given the file {{1}}"), false));
        ctx.addArtifact("local_answer", Map.of(), Map.of(), ANSWER, true, label(Label.PRIVATE));
        return ctx;
    }

    @Test
    @DisplayName("a whole private handle is filled in for the owner; everything else gets the note")
    void aPrivateHandleIsTheOwnersText() {
        var a = AgentLoop.answerFor("{{2}}", twoResults());
        assertNull(a.refusal());
        assertEquals(PRIVATE_NOTE, a.response());
        assertEquals(AgentLoop.PRIVATE_RESULT_HEADER + "{\"body_text\":\"the mail\"}", a.ownerText(),
                "a skill's result, not the local model's writing");
    }

    @Test
    @DisplayName("a whole public handle is delivered as its text, which the cloud already had")
    void aPublicHandleIsItsText() {
        var a = AgentLoop.answerFor("{{1}}", twoResults());
        assertNull(a.refusal());
        assertEquals("The public page.", a.response());
        assertNull(a.ownerText());
    }

    @Test
    @DisplayName("a handle inside prose is text, delivered as written")
    void aHandleInProseIsText() {
        var a = AgentLoop.answerFor("Here: {{2}}", twoResults());
        assertNull(a.refusal());
        assertEquals("Here: {{2}}", a.response());
        assertNull(a.ownerText());
    }

    @Test
    @DisplayName("a handle that cannot be delivered is refused, so the cloud can try again")
    void undeliverableHandlesAreRefused() {
        assertNotNull(AgentLoop.answerFor("{{9}}", twoResults()).refusal(), "out of range");

        var failed = new AgentContext("u1", "t1", "x");
        failed.addArtifact("pdf_text", Map.of(), Map.of(), "Traceback: boom", false, label(Label.PRIVATE));
        assertNotNull(AgentLoop.answerFor("{{1}}", failed).refusal(), "a FAILED result");

        var pdf = new AgentContext("u1", "t1", "summarise this statement");
        pdf.addFile("f1", "", PDF);
        String refusal = AgentLoop.answerFor("{{1}}", pdf).refusal();
        assertNotNull(refusal, "a PDF's {{1}} has no text, and an empty answer is no answer");
        assertTrue(refusal.contains("{{1}} has no text to show") && refusal.contains("Delegate"), refusal);
    }

    @Test
    @DisplayName("on a file task the local answer reaches the owner even when the cloud leaves it out")
    void theLocalAnswerIsNotLeftToTheCloud() {
        var a = AgentLoop.answerFor("Done.", fileTask());
        assertNull(a.refusal());
        assertEquals("Done.\n\n" + PRIVATE_HEADER + ANSWER, a.ownerText());
        assertEquals("Done.\n\n" + PRIVATE_NOTE, a.response());

        var placed = AgentLoop.answerFor("{{3}}", fileTask());
        assertEquals(PRIVATE_NOTE, placed.response());
        assertEquals(PRIVATE_HEADER + ANSWER, placed.ownerText());
        assertEquals(1, placed.ownerText().split(java.util.regex.Pattern.quote(ANSWER), -1).length - 1,
                "placed by the cloud, it is not appended a second time");
    }

    @Test
    @DisplayName("every local answer reaches the owner, oldest first; the one the cloud placed is not repeated")
    void everyLocalAnswerArrives() {
        var ctx = fileTask();                                    // {{3}} the first answer
        ctx.addArtifact("local_answer", Map.of(), Map.of(), "The second half.", true,
                label(Label.PRIVATE));                           // {{4}}
        var done = AgentLoop.answerFor("Done.", ctx);
        assertEquals("Done.\n\n" + PRIVATE_HEADER + ANSWER + "\n\n" + PRIVATE_HEADER + "The second half.",
                done.ownerText());
        var placed = AgentLoop.answerFor("{{3}}", ctx);
        assertEquals(PRIVATE_HEADER + ANSWER + "\n\n" + PRIVATE_HEADER + "The second half.", placed.ownerText());
    }

    @Test
    @DisplayName("a task that ends without respond -- step limit, cloud errors -- still gives the owner the local answer")
    void everyExitCarriesTheLocalAnswer() {
        var ctx = fileTask();
        var stopped = AgentResult.maxSteps("Reached the step limit. Last result: {{2}}", new AgentTrajectory(), 5);
        var r = AgentLoop.withLocalAnswers(stopped, ctx);
        assertEquals(AgentLoop.ENDED_WITH_AN_ANSWER + "\n\n" + PRIVATE_HEADER + ANSWER, r.ownerText());
        assertEquals(AgentLoop.ENDED_WITH_AN_ANSWER + "\n\n" + PRIVATE_NOTE, r.response(),
                "not the progress note addressed to the cloud, with its stale {{2}}");
        assertEquals(AgentResult.TerminationReason.MAX_STEPS, r.terminationReason());

        var answered = AgentLoop.withLocalAnswers(stopped.withOwnerText("already given"), ctx);
        assertEquals("already given", answered.ownerText(), "answerFor already handled it");
        var noFile = new AgentContext("u1", "t2", "x");
        noFile.addArtifact("local_answer", Map.of(), Map.of(), ANSWER, true, label(Label.PRIVATE));
        assertNull(AgentLoop.withLocalAnswers(stopped, noFile).ownerText());
    }

    @Test
    @DisplayName("without a file, nothing is appended")
    void noFileNoFallback() {
        var ctx = new AgentContext("u1", "t1", "x");
        ctx.addArtifact("local_answer", Map.of(), Map.of(), ANSWER, true, label(Label.PRIVATE));
        var a = AgentLoop.answerFor("Done.", ctx);
        assertEquals("Done.", a.response());
        assertNull(a.ownerText());
    }
}
