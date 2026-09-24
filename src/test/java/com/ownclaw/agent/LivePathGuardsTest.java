package com.ownclaw.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Two guards that sit on paths no unit test can reach — deep inside the delegation loop and
 * inside the cloud step loop, both of which need a live model to enter.
 * <p>
 * Read from the source, which is crude and says so. It is here because the alternative was
 * nothing, and nothing is how both of these came to be wrong in the first place: one gate went
 * onto two call sites that cannot be reached with an attachment while the reachable one kept
 * the bare check, and the reference guard was written for the delegation and never added to the
 * path the cloud itself calls tools on. Neither mistake is visible in a passing suite.
 */
class LivePathGuardsTest {

    private static String read(String className) throws IOException {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("src/main/java"))) {
            root = root.getParent();
        }
        assertNotNull(root, "src/main/java not found above " + Path.of("").toAbsolutePath());
        Path f = root.resolve("src/main/java").resolve(className.replace('.', '/') + ".java");
        assertTrue(Files.exists(f), f + " does not exist");
        return Files.readString(f);
    }

    @Test
    @DisplayName("no labelFor call weighs attachments — the rule was unreachable and is gone")
    void noCallSiteWeighsAttachments() throws IOException {
        // The previous version of this test asserted the opposite, and cemented a condition
        // that was false on every path: `isUnattended() && !attachmentIds().isEmpty()` where
        // attachments only ever arrive on attended chat. It would have failed the honest repair.
        for (String cls : new String[]{"com.ownclaw.agent.AgentLoop",
                                       "com.ownclaw.agent.LocalExecutor"}) {
            String s = read(cls);
            int at = 0;
            while ((at = s.indexOf("Artifact.labelFor(", at)) >= 0) {
                int end = s.indexOf(");", at);
                assertTrue(end > at, cls + ": labelFor call is not terminated");
                String call = s.substring(at, end);
                at = end;
                assertFalse(call.contains("attachmentIds()"),
                        cls + ": a labelFor call weighs attachments again. The attachment "
                                + "artifact is labelled where it is recorded and the reference "
                                + "clause carries it from there; a second rule here either "
                                + "cannot fire or takes \"summarise this file\" away:\n" + call);
            }
        }
    }

    @Test
    @DisplayName("the reference refusal only applies once the task has results to reference")
    void theRefusalDoesNotFireOnAFirstStep() throws IOException {
        String s = read("com.ownclaw.agent.AgentLoop");
        int at = s.indexOf("LocalExecutor.unresolvedRef(resolved");
        assertTrue(at > 0, "the guard moved; this test no longer checks it");
        String call = s.substring(at, Math.min(s.length(), at + 120));
        assertTrue(call.contains("artifacts().size()"),
                "the count is what separates a reference from a price, and it has to be passed. "
                        + "Skipping the check when the task had none was wrong the other way: it "
                        + "is exactly then that \"$1.body_text\" resolves to nothing and twelve "
                        + "literal characters went out as the body of an email:\n" + call);
    }

    @Test
    @DisplayName("attachments are claimed where they are registered, by no step")
    void attachmentsAreClaimedAtRegistration() throws IOException {
        String s = read("com.ownclaw.agent.AgentLoop");
        int start = s.indexOf("private void registerAttachments(");
        assertTrue(start > 0, "registerAttachments was renamed");
        int end = s.indexOf("\n    private ", start + 10);
        String body = end > start ? s.substring(start, end) : s.substring(start);
        assertTrue(body.contains("claimAllArtifacts()"),
                "an attachment artifact is recorded by no step, so the mark was still 0 when "
                        + "step 1 persisted and the first step that recorded nothing of its own "
                        + "claimed the attachment — the ops page then named it as the tool that "
                        + "step had run");
    }

    @Test
    @DisplayName("persistStep attributes an artifact only by claiming it")
    void stepAttributionGoesThroughTheClaim() throws IOException {
        // The unit tests cover AgentContext.claimArtifact, but the defect lived at the call
        // site: reverting this one line to context.lastArtifact().ifPresent(...) reproduces the
        // old always-true guard exactly, and the whole suite stayed green when a verifier tried
        // it. The mechanism has to be pinned where it is used, not only where it is defined.
        String s = read("com.ownclaw.agent.AgentLoop");
        int start = s.indexOf("private void persistStep(");
        assertTrue(start > 0, "persistStep was renamed; this test no longer guards it");
        int end = s.indexOf("\n    private ", start + 10);
        String body = end > start ? s.substring(start, end) : s.substring(start);

        int last = body.indexOf("lastArtifact()");
        assertTrue(last > 0, "persistStep no longer reads the task's last artifact");
        assertTrue(body.contains("claimArtifact("),
                "persistStep attributes an artifact to a step without claiming it, so a step "
                        + "that recorded nothing — a tool not found, a critic block — reports "
                        + "the previous step's handle, label and hash, and the ops page names a "
                        + "tool that never ran");
        assertTrue(body.indexOf("claimArtifact(", last) > last,
                "the claim has to filter the artifact, not run beside it");
    }

    @Test
    @DisplayName("neither refusal call site throws away the count, or skips the check")
    void bothRefusalCallSitesPassTheCount() throws IOException {
        // Both of these could be reverted with the whole suite staying green, a reviewer showed:
        // AgentLoop re-gaining "artifacts().isEmpty() ? null :" (which sends a first step's
        // "$1.body_text" out as twelve literal characters), and LocalExecutor passing 0 (which
        // treats every bare "$3" as a price and sends that instead).
        String loop = read("com.ownclaw.agent.AgentLoop");
        int at = loop.indexOf("String unresolved = ");
        assertTrue(at > 0, "the guard in executeTool moved");
        String stmt = loop.substring(at, loop.indexOf(';', at));
        assertFalse(stmt.contains("isEmpty()"), "the check is skipped again: " + stmt);
        assertTrue(stmt.contains("unresolvedRef(resolved, context.artifacts().size())"), stmt);

        String exec = read("com.ownclaw.agent.LocalExecutor");
        assertTrue(exec.contains("unresolvedRef(params, stepResults.size())"),
                "the delegation's copy of the guard must pass what it has produced");
    }

    @Test
    @DisplayName("a failed delegated step records its arguments as written, not as resolved")
    void usageRowsGetTheWrittenArguments() throws IOException {
        // The resolved map has every $N replaced by the artifact's bytes, so a failure wrote up
        // to 500 characters of the mailbox into skill_usage -- kept out of the repair prompt
        // only by the row's label, which has been wrong before. The cloud path already records
        // the written form; the two must match.
        String exec = read("com.ownclaw.agent.LocalExecutor");
        int at = exec.indexOf("curatorService.recordUsage(");
        assertTrue(at > 0, "the usage record moved");
        String call = exec.substring(at, exec.indexOf(';', at));
        assertTrue(call.contains("toolOk ? null : action.params"), call);
        assertFalse(call.contains("toolOk ? null : params,"), call);
    }

    @Test
    @DisplayName("a delegate step claims its artifacts only when the delegation actually ran")
    void delegateClaimIsConditional() throws IOException {
        String s = read("com.ownclaw.agent.AgentLoop");
        int branch = s.indexOf("if (action.isDelegate()) {", s.indexOf("private void persistStep("));
        assertTrue(branch > 0, "persistStep's delegate branch moved");
        String body = s.substring(branch, s.indexOf("} else if (!action.isSpecialAction())", branch));
        int guard = body.indexOf("if (arts != null)");
        int claim = body.indexOf("claimAllArtifacts()");
        assertTrue(guard > 0 && claim > guard,
                "a delegate step that never reached the executor recorded nothing; claiming "
                        + "there swallows an earlier artifact's attribution:\n" + body);
    }

    @Test
    @DisplayName("the canary normalises each part once, not once per hit")
    void theCanaryIsNotQuadratic() throws IOException {
        // Behaviourally identical, which is why reverting it left the suite green -- the cost is
        // the only difference: 7.8 seconds measured on one 130 KB part, on every step.
        String s = read("com.ownclaw.llm.CloudGateway");
        assertTrue(s.contains("firstHitInNormalised(normalised, from)"),
                "the per-hit loop must reuse the part's normalised text");
        assertFalse(s.contains("firstHitIn(part.text(), from)"));
    }

    @Test
    @DisplayName("both ops skill_usage reads carry the error the descriptor points at")
    void theOpsPointerIsTrue() throws IOException {
        // A PRIVATE descriptor says "text withheld; skill_usage row via ops". Twice that row was
        // served without the column, and nothing failed either time.
        String s = read("com.ownclaw.observability.OpsService");
        int from = 0, selects = 0;
        while ((from = s.indexOf("FROM skill_usage WHERE", from)) >= 0) {
            String select = s.substring(s.lastIndexOf("\"SELECT", from), from);
            if (select.contains("tool_name")) {
                selects++;
                assertTrue(select.contains("error"), "an ops read without the error column:\n" + select);
                assertTrue(select.contains("label"), select);
            }
            from++;
        }
        assertEquals(2, selects, "the task page and the forensics page");
    }

    @Test
    @DisplayName("executeTool refuses an unresolved reference before the tool runs")
    void unresolvedReferencesAreRefusedOnTheAttendedPath() throws IOException {
        String s = read("com.ownclaw.agent.AgentLoop");
        int start = s.indexOf("private AgentObservation executeTool(");
        assertTrue(start > 0, "executeTool was renamed; this test no longer guards anything");
        int end = s.indexOf("\n    private ", start + 10);
        String body = end > start ? s.substring(start, end) : s.substring(start);

        int substituted = body.indexOf("substituteRefs(");
        int guard = body.indexOf("unresolvedRef(");
        int ran = body.indexOf("tool.execute(");
        assertTrue(substituted > 0, "executeTool no longer substitutes references");
        assertTrue(ran > 0, "executeTool no longer runs the tool");
        assertTrue(guard > substituted && guard < ran,
                "unresolvedRef has to be checked after substitution and BEFORE the tool runs. "
                        + "An unresolvable $9 otherwise reaches the skill as those two literal "
                        + "characters — as a mail body, sent, and recorded as a success");
    }
}
