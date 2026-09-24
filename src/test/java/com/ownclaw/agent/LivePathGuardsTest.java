package com.ownclaw.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
    @DisplayName("the cloud path resolves once, refuses and returns before running, and labels from what moved")
    void theCloudPathUsesTheOneResolver() throws IOException {
        // The delegation's side of this is driven for real in DelegationBehaviourTest. AgentLoop
        // is too heavy to construct in a unit test, so its ordering is pinned here: resolve,
        // refuse and return, and only then run -- with the label taken from the resolver's own
        // record of what it pulled in. (No never-twice check on this path: identical arguments
        // rarely match a delegation's send, and it refused legitimate repeats like a light
        // switched on, off and on again.)
        String s = read("com.ownclaw.agent.AgentLoop");
        int start = s.indexOf("private AgentObservation executeTool(");
        assertTrue(start > 0, "executeTool was renamed; this test no longer guards anything");
        int end = s.indexOf("\n    private ", start + 10);
        String body = end > start ? s.substring(start, end) : s.substring(start);

        int resolve = body.indexOf("References.resolve(action.params(), context.artifacts())");
        int refuse = body.indexOf("if (!refs.ok())");
        int run = body.indexOf("tool.execute(");
        int label = body.indexOf("context.decide(tool.requiredCredentials(), refs.used(), false)");
        assertTrue(resolve > 0, "executeTool no longer resolves through References");
        assertTrue(refuse > resolve && refuse < run, "a refused reference must stop the call before it runs");
        String refusal = body.substring(refuse, body.indexOf("\n        }\n", refuse));
        assertTrue(refusal.contains("return AgentObservation.failure("), "...and actually return: " + refusal);
        assertFalse(refusal.contains("available("),
                "the cloud's refusal must not list field names -- they can be private data");
        assertTrue(body.substring(refuse, run).contains("resolved = refs.params()"),
                "the call runs on the resolver's substituted arguments");
        assertTrue(label > run, "the label must come from the resolver's record, not a re-parse");
        assertFalse(body.contains("resolved = LocalExecutor"), "a second resolver is back");
    }

    @Test
    @DisplayName("attachments are claimed where they are registered, by no step")
    void attachmentsAreClaimedAtRegistration() throws IOException {
        String s = read("com.ownclaw.agent.AgentLoop");
        int start = s.indexOf("static void registerAttachments(");
        assertTrue(start > 0, "registerAttachments was renamed");
        int end = s.indexOf("\n    }\n", start);
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

    /** One branch of runLoop: from its opening line to the next top-level {@code if (action.}. */
    private static String runLoopBranch(String s, String opening) {
        int loop = s.indexOf("private AgentResult runLoop(");
        assertTrue(loop > 0, "runLoop was renamed; this test no longer guards it");
        int start = s.indexOf(opening, loop);
        assertTrue(start > 0, "runLoop no longer has " + opening);
        int end = s.indexOf("\n            if (action.", start + opening.length());
        return end > start ? s.substring(start, end) : s.substring(start);
    }

    @Test
    @DisplayName("respond and ask_user deliver only what answerFor made of the message")
    void answersGoThroughAnswerFor() throws IOException {
        // answerFor itself is driven in AnswerForTest; this pins that the two exits of the loop
        // use it. Either one returning action.responseText() delivers a private handle as the
        // literal "{{3}}" and drops the owner's answer.
        String s = read("com.ownclaw.agent.AgentLoop");
        for (String[] b : List.of(new String[]{"if (action.isResponse()) {", "AgentResult.completed("},
                new String[]{"if (action.isAskUser()) {", "AgentResult.needsInput("})) {
            String branch = runLoopBranch(s, b[0]);
            int answer = branch.indexOf("answerFor(action.responseText(), context)");
            int ret = branch.indexOf(b[1]);
            assertTrue(answer > 0, b[0] + " does not go through answerFor");
            assertTrue(ret > answer, b[0] + " returns before answerFor");
            assertEquals(ret, branch.lastIndexOf(b[1]), b[0] + " has a second way out");
            String refusal = branch.substring(answer, ret);
            assertTrue(refusal.contains("refusal() != null") && refusal.contains("continue;"),
                    b[0] + ": a refused answer must go back to the cloud, not out: " + refusal);
            String returned = branch.substring(ret, branch.indexOf(";", ret));
            assertFalse(returned.contains("responseText()"), "the message as written is returned: " + returned);
            assertTrue(returned.contains(".withOwnerText(") && returned.contains(".ownerText())"),
                    "the owner's text is dropped: " + returned);
        }
    }

    /** The whole statement a call starts, whitespace collapsed, from the call to its semicolon. */
    private static String call(String source, String from) {
        int start = source.indexOf(from);
        assertTrue(start > 0, "not found: " + from);
        return source.substring(start, source.indexOf(";", start)).replaceAll("\\s+", " ");
    }

    @Test
    @DisplayName("Telegram saves the two texts apart and is sent the owner's")
    void saveSitesKeepTheTwoTexts() throws IOException {
        // Only Telegram: no test runs TelegramBotService, while the web chat and the scheduler are
        // driven by PrivateAnswerInTheWebChatTest and ScheduledPrivateAnswerTest. The row's content
        // feeds every later prompt, the compressor and search, so it must be the safe text.
        String telegram = read("com.ownclaw.interfaces.telegram.TelegramBotService");
        assertEquals("conversationService.saveMessage(userId, currentSessionId, \"assistant\", "
                        + "result.response(), java.util.List.of(), result.taskId(), result.ownerText())",
                call(telegram, "conversationService.saveMessage(userId, currentSessionId, \"assistant\","));
        // Telegram is the owner's own channel and he decided it gets private answers in full;
        // what it stores for later turns is still the safe text (asserted above).
        assertTrue(call(telegram, "sendMessage(chatId, result.").endsWith("result.shown())"),
                "Telegram is sent the owner's text");
        assertTrue(telegram.contains("new StringBuilder(telegramText(msg))"),
                "a delivered result is sent through telegramText, which reads the owner's text");
        assertTrue(telegram.contains("if (isOwnersChat(userId, target, id -> userRepo.findByTelegramId(id)))"),
                "results go only to the owner's own, still-linked chat");
        assertTrue(telegram.contains("for (String part : telegramParts(text, TELEGRAM_MAX_CHARS))"),
                "a long answer is sent in parts instead of being refused whole");
    }

    @Test
    @DisplayName("every way a task ends passes through the local-answer fallback")
    void everyExitGivesTheLocalAnswer() throws IOException {
        // withLocalAnswers is driven in AnswerForTest; no test runs executeFull to its end, so
        // this pins the one line that applies it to whatever runLoop returned.
        assertTrue(read("com.ownclaw.agent.AgentLoop")
                        .contains("result = withLocalAnswers(runLoop(context), context).withTaskId(taskId);"),
                "executeFull applies the fallback to every result");

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
