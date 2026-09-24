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
    @DisplayName("the cloud path resolves once, refuses before running, and labels from what moved")
    void theCloudPathUsesTheOneResolver() throws IOException {
        // The delegation's side of this is driven for real in DelegationBehaviourTest. AgentLoop
        // is too heavy to construct in a unit test, so its ordering is pinned here: resolve,
        // refuse on failure, refuse a repeated side effect, and only then run -- with the label
        // taken from the resolver's own record of what it pulled in.
        String s = read("com.ownclaw.agent.AgentLoop");
        int start = s.indexOf("private AgentObservation executeTool(");
        assertTrue(start > 0, "executeTool was renamed; this test no longer guards anything");
        int end = s.indexOf("\n    private ", start + 10);
        String body = end > start ? s.substring(start, end) : s.substring(start);

        int resolve = body.indexOf("References.resolve(action.params(), context.artifacts())");
        int refuse = body.indexOf("if (!refs.ok())");
        int repeat = body.indexOf("LocalExecutor.sideEffectAlreadyDone(");
        int run = body.indexOf("tool.execute(");
        int label = body.indexOf("Artifact.labelFor(tool.requiredCredentials(), refs.used())");
        assertTrue(resolve > 0, "executeTool no longer resolves through References");
        assertTrue(refuse > resolve && refuse < run, "a refused reference must stop the call before it runs");
        assertTrue(body.substring(refuse, run).contains("return "), "...and actually return");
        assertTrue(repeat > refuse && repeat < run, "a repeated side effect must be refused before it runs");
        assertTrue(label > run, "the label must come from the resolver's record, not a re-parse");
        assertFalse(body.contains("resolved = LocalExecutor"), "a second resolver is back");
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

}
