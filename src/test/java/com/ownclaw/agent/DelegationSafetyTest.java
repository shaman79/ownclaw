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

    private static Artifact step(String tool, Map<String, Object> params, String output) {
        return new Artifact(tool, params, output, true);
    }

    /** Resolve and require success: the substituted arguments. */
    private static Map<String, Object> sub(Map<String, Object> written, List<Artifact> done) {
        var r = References.resolve(written, done);
        assertTrue(r.ok(), "refused: " + r.reason());
        return r.params();
    }

    /** The parameter the resolver refused, or null when the call may run. */
    private static String refusedParam(Map<String, Object> written, List<Artifact> done) {
        return References.resolve(written, done).refused();
    }

    /** A PUBLIC artifact with a task-wide handle — the shape the store produces. */
    private static Artifact numbered(int n, String tool, String output) {
        return new Artifact(n, tool, Map.of(), Map.of(), output, true,
                com.ownclaw.privacy.Label.PUBLIC, List.of());
    }

    private static Artifact privateStep(int n, String tool, String output, boolean ok) {
        return new Artifact(n, tool, Map.of(), Map.of(), output, ok,
                com.ownclaw.privacy.Label.PRIVATE, List.of("credentials: SMTP_PASS"));
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
                "I have fetched today's headlines and emailed the digest.", "send the digest", List.of());

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
        var outcome = LocalExecutor.completed("Digest sent.", "send it", List.of(
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
    @DisplayName("a parameter that is exactly {{1}} becomes step 1's output")
    void referenceIsSubstituted() {
        var done = List.of(step("daily_news_digest", Map.of(), "DIGEST 2026-09-22\nline two"));
        var out = sub(
                Map.of("to", "petr@example.com", "body", "{{1}}"), done);

        assertEquals("DIGEST 2026-09-22\nline two", out.get("body"),
                "the text the owner reads must never pass through the model as output tokens");
        assertEquals("petr@example.com", out.get("to"), "other parameters are untouched");
    }

    @Test
    @DisplayName("{{1.field}} takes one field out of a JSON result")
    void fieldReferenceIsResolved() {
        // Exactly what daily_news_digest returns, and exactly what smtp_send_email needs out
        // of it: the text, not the envelope around the text.
        var done = List.of(step("daily_news_digest", Map.of(),
                "{\"ok\":true,\"date\":\"2026-09-22\",\"body_text\":\"Digest line one\\nline two\"}"));

        var out = sub(Map.of("body", "{{1.body_text}}"), done);
        assertEquals("Digest line one\nline two", out.get("body"),
                "whole-output substitution would email the owner raw JSON, and retyping is the "
                        + "failure everything here exists to prevent");

        assertEquals("2026-09-22",
                sub(Map.of("subject", "{{1.date}}"), done).get("subject"));
    }

    @Test
    @DisplayName("{{1}} still gives the whole output when no field is named")
    void wholeOutputStillWorks() {
        String json = "{\"ok\":true,\"body_text\":\"text\"}";
        var done = List.of(step("x", Map.of(), json));
        assertEquals(json, sub(Map.of("c", "{{1}}"), done).get("c"));
    }

    @Test
    @DisplayName("a non-string field is serialised rather than dropped")
    void nonStringFieldsSurvive() {
        var done = List.of(step("x", Map.of(), "{\"count\":7,\"items\":[1,2]}"));
        assertEquals("7", sub(Map.of("n", "{{1.count}}"), done).get("n"));
        assertEquals("[1,2]",
                sub(Map.of("n", "{{1.items}}"), done).get("n"));
    }

    @Test
    @DisplayName("an unresolvable field is refused, not sent as text and not silently emptied")
    void missingFieldIsRefused() {
        // It used to be left as the literal "$1.body_text" on the theory that a visible token
        // is better than an empty email. It is -- and a refusal is better than both: the literal
        // went out as the body, the tool reported success, and the run was recorded green.
        var done = List.of(step("x", Map.of(), "{\"ok\":true}"));
        assertEquals("b", refusedParam(Map.of("b", "{{1.body_text}}"), done));
        var plain = List.of(step("x", Map.of(), "not json at all"));
        assertEquals("b", refusedParam(Map.of("b", "{{1.body_text}}"), plain));
        assertEquals("b", refusedParam(Map.of("b", "{{1.}}"), done), "an empty field");
    }

    @Test
    @DisplayName("shell text is never touched, and a reference inside text is refused")
    void onlyWholeValuesAreReferences() {
        var done = List.of(step("x", Map.of(), "OUTPUT"));
        assertEquals("for f in *; do echo \"$1\"; done",
                sub(Map.of("command", "for f in *; do echo \"$1\"; done"), done).get("command"),
                "shell is full of $1, and rewriting one inside a script would be a far worse "
                        + "bug than the one this fixes -- the marker is {{N}} now, which shell is not");
        assertEquals("body", refusedParam(Map.of("body", "Here is the menu: {{1}}"), done),
                "substituting inside text is how a summary becomes a quotation; leaving it is how "
                        + "an email arrives with a template token in it");
    }

    @Test
    @DisplayName("a reference to a step that has not run is refused")
    void outOfRangeReferenceIsRefused() {
        var done = List.of(step("x", Map.of(), "OUTPUT"));
        assertEquals("body", refusedParam(Map.of("body", "{{7}}"), done),
                "silently substituting the wrong step would be worse, and sending the literal is "
                        + "what used to happen");
        assertEquals("body", refusedParam(Map.of("body", "{{1}}"), List.of()),
                "and on the first step there is nothing to reference yet");
    }

    @Test
    @DisplayName("dollar signs are money again; a malformed {{…}} is refused")
    void malformedReferences() {
        var done = List.of(step("x", Map.of(), "OUTPUT"));
        for (String money : List.of("$0", "$abc", "$", "$50", "$5.50", "$1")) {
            assertEquals(money, sub(Map.of("b", money), done).get("b"), money);
        }
        assertEquals("b", refusedParam(Map.of("b", "{{0}}"), done), "handles start at 1");
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
        assertTrue(shown.contains("{{1}}"), "the reference is the whole point");
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
        assertNull(LocalExecutor.retypedExcerpt(Map.of("body", "{{1}}")));
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
        assertTrue(LocalExecutor.feedback(big, 3).contains("{{3}}"));
        // ...and that is the number the resolver uses against the same list.
        var done = List.of(step("a", Map.of(), "first"), step("b", Map.of(), "second"),
                step("c", Map.of(), big));
        assertEquals(big, sub(Map.of("body", "{{3}}"), done).get("body"));
    }

    // ── finishing has to work in both protocols ──

    @Test
    @DisplayName("a text action naming the done tool is a finish, not a tool call")
    void textDoneIsAFinish() {
        // Exactly what a real delegation emitted, three times, before dying on max steps with
        // the work already complete: "Tool 'done' not found".
        var action = LocalExecutor.normalizeDone(new LocalExecutor.ExecutorAction(
                false, null, "done", Map.of("summary", "Digest written to the file.")));

        assertTrue(action.done(), "it said it was finished; nothing else was going to happen");
        assertEquals("Digest written to the file.", action.summary());
    }

    @Test
    @DisplayName("a summary under another name still finishes")
    void improvisedSummaryKeysAreAccepted() {
        assertEquals("all done", LocalExecutor.normalizeDone(new LocalExecutor.ExecutorAction(
                false, null, "done", Map.of("message", "all done"))).summary());
        assertEquals("all done", LocalExecutor.normalizeDone(new LocalExecutor.ExecutorAction(
                false, null, "done", Map.of("result", "all done"))).summary());
        assertTrue(LocalExecutor.normalizeDone(new LocalExecutor.ExecutorAction(
                        false, null, "done", Map.of())).done(),
                "an empty summary is still a finish — the ledger supplies the body");
    }

    @Test
    @DisplayName("a real tool call is left alone")
    void ordinaryActionsAreUntouched() {
        var call = new LocalExecutor.ExecutorAction(
                false, null, "smtp_send_email", Map.of("to", "petr@example.com"));
        assertSame(call, LocalExecutor.normalizeDone(call));
        assertNull(LocalExecutor.normalizeDone(null));
    }

    // ── a reference that resolves to nothing must not be sent ──

    @Test
    @DisplayName("an unresolved reference is caught before it reaches a tool")
    void unresolvedReferenceIsDetected() {
        // The symmetrical failure to the retyped excerpt, and it had no guard: the owner got an
        // email whose entire body was the seven characters "$1.body", sent and recorded green.
        var done = List.of(step("a", Map.of(), "{\"body_text\":\"x\"}"), step("b", Map.of(), "y"),
                step("c", Map.of(), "z"), step("d", Map.of(), "w"));
        assertEquals("body", refusedParam(Map.of("to", "petr@example.com", "body", "{{1.body}}"), done));
        assertEquals("body", refusedParam(Map.of("body", "{{2.body_text}}"), done));
        assertEquals("body", refusedParam(Map.of("body", "{{9}}"), done));
    }

    @Test
    @DisplayName("a resolved reference is not mistaken for an unresolved one")
    void resolvedReferencesAreClean() {
        var done = List.of(step("daily_news_digest", Map.of(),
                "{\"ok\":true,\"body_text\":\"the digest\"}"));
        assertNull(refusedParam(Map.of("body", "{{1.body_text}}"), done),
                "it resolved, so what is left is content, not a reference");
        assertNull(refusedParam(Map.of("body", "Costs $5 and $10"), done), "money is not a reference");
        assertNull(refusedParam(Map.of("command", "echo \"$1\" | wc -c"), done));
        assertNull(refusedParam(Map.of(), done));
        assertNull(refusedParam(null, done));
    }

    // ── a failed delegation must not invite the work to be done twice ──

    @Test
    @DisplayName("a failure that already sent the email says so first")
    void failureNamesWhatAlreadySucceeded() {
        var outcome = LocalExecutor.completed("Could not verify.", "send it", List.of(
                step("daily_news_digest", Map.of(), "...digest..."),
                step("smtp_send_email", Map.of("to", "petr@example.com"), "Sent, id 42"),
                new Artifact("verify_delivery", Map.of(), "ERROR: no such tool",
                        false)));

        assertFalse(outcome.ok(), "a failed step still hands the registry back");
        assertTrue(outcome.text().startsWith("ALREADY DONE"),
                "the cloud is told to try a different approach on a failure, and the different "
                        + "approach is sending the owner a second digest — so what already "
                        + "happened has to be the first thing it reads, not a tick in a ledger "
                        + "that head-and-tail truncation can drop");
        assertTrue(outcome.text().contains("smtp_send_email"));
        assertFalse(outcome.text().contains("verify_delivery,"),
                "only what succeeded is listed as done");
    }

    @Test
    @DisplayName("a clean delegation is not prefixed with a warning about itself")
    void successHasNoAlreadyDoneHeader() {
        var outcome = LocalExecutor.completed("Digest sent.", "send it", List.of(
                step("smtp_send_email", Map.of(), "Sent")));
        assertTrue(outcome.ok());
        assertTrue(outcome.text().startsWith("Digest sent."));
    }

    @Test
    @DisplayName("a failure with nothing successful carries no misleading header")
    void allFailedHasNoHeader() {
        var outcome = LocalExecutor.completed("Nothing worked.", "send it", List.of(
                new Artifact("x", Map.of(), "ERROR: boom", false)));
        assertFalse(outcome.text().startsWith("ALREADY DONE"));
    }

    // ── the local tier forwards results, it does not author them ──

    @Test
    @DisplayName("a reference is short, so it is never mistaken for composed prose")
    void referencesAreBelowTheThreshold() {
        // The refusal keys on length, and every intended path is far under it: "{{1}}" is five
        // characters and "{{1.body_text}}" is fifteen.
        assertTrue("{{1.body_text}}".length() < 600);
        assertTrue("{{1}}".length() < 600);
    }

    @Test
    @DisplayName("forwarding a result exactly is not composing")
    void anExactCopyIsNotComposed() {
        String digest = "D".repeat(2000);
        var done = List.of(step("daily_news_digest", Map.of(), digest));
        // Wasteful -- it paid 2,000 output tokens to move something $1 would have moved -- but
        // byte-identical, so nothing was invented and nothing is refused.
        assertTrue(done.stream().anyMatch(r -> digest.equals(r.output())));
    }

    // ── the transcript must not outgrow the window it has to answer in ──

    private static List<com.ownclaw.llm.LlmMessage> conversation(int exchanges) {
        var m = new java.util.ArrayList<com.ownclaw.llm.LlmMessage>();
        m.add(com.ownclaw.llm.LlmMessage.system("EXECUTOR PROMPT"));
        m.add(com.ownclaw.llm.LlmMessage.user("Begin."));
        for (int i = 0; i < exchanges; i++) {
            m.add(com.ownclaw.llm.LlmMessage.assistant("call " + i));
            m.add(com.ownclaw.llm.LlmMessage.user("result " + i));
        }
        return m;
    }

    @Test
    @DisplayName("a short delegation is left exactly as it is")
    void shortHistoryIsUntouched() {
        var m = conversation(3);
        int before = m.size();
        LocalExecutor.trimHistory(m, List.of(step("a", Map.of(), "x")));
        assertEquals(before, m.size(), "there is nothing to gain below the threshold");
    }

    @Test
    @DisplayName("a long delegation keeps the system prompt, the goal, a ledger and the tail")
    void longHistoryIsTrimmed() {
        // The real shape of the 23 September menu run, which died at step 9 with the prompt
        // nearly filling the window and 19,326 characters spent on thinking.
        var m = conversation(8);
        var done = List.of(step("restaurant_url_finder", Map.of(), "urls"),
                step("web_fetch_and_parse", Map.of(), "x".repeat(4279)),
                new Artifact("web_fetch_and_parse", Map.of(), "ERR", false));

        LocalExecutor.trimHistory(m, done);

        assertTrue(m.size() < conversation(8).size(), "it has to shrink, that is the point");
        assertEquals(com.ownclaw.llm.LlmMessage.Role.SYSTEM, m.get(0).role(),
                "the executor prompt is not optional");
        assertTrue(m.get(1).content().startsWith("Begin."), "nor is the goal");
        assertTrue(m.get(m.size() - 1).content().startsWith("result 7"),
                "the most recent exchange is what it is answering about");

        for (int i = 1; i < m.size(); i++) {
            assertNotEquals(m.get(i).role(), m.get(i - 1).role(),
                    "roles must alternate — two user turns in a row is something Ollama "
                            + "tolerates and other providers reject");
        }
    }

    @Test
    @DisplayName("the results survive the trim, because they never lived in the transcript")
    void trimKeepsTheReferences() {
        var m = conversation(8);
        // To the local model a handle is a position in its own delegation's list.
        var done = List.of(numbered(1, "daily_news_digest", "D".repeat(3000)),
                numbered(2, "smtp_send_email", "sent"));

        LocalExecutor.trimHistory(m, done);
        String ledger = m.get(1).content();

        assertTrue(ledger.contains("{{1}} = daily_news_digest"), "it must know what {{1}} is");
        assertTrue(ledger.contains("{{2}} = smtp_send_email"));
        assertTrue(ledger.contains("3000 chars"), "and how much is behind the reference");
        assertTrue(ledger.contains("have not"),
                "a model that believes the results are gone will try to reconstruct them");

        // The point: {{1}} still resolves after the conversation carrying it was dropped.
        assertEquals("D".repeat(3000),
                sub(Map.of("body", "{{1}}"), done).get("body"));
    }

    @Test
    @DisplayName("a failed earlier step is named as failed in the ledger")
    void trimLedgerNamesFailures() {
        var m = conversation(8);
        LocalExecutor.trimHistory(m, List.of(
                new Artifact("web_fetch_and_parse", Map.of(), "ERR", false)));
        assertTrue(m.get(1).content().contains("FAILED"),
                "otherwise the model retries something it has no idea already broke");
    }

    // ── what the cloud reads of a delegation that touched private data ──

    @Test
    @DisplayName("a private result is rendered as its descriptor and the local summary is withheld")
    void privateResultsAreDescribedNotShown() {
        String digest = "📰 Digest — 2026-09-23\n" + "line ".repeat(200);
        var pub = new Artifact(1, "daily_news_digest", Map.of(), Map.of(), digest, true,
                com.ownclaw.privacy.Label.PUBLIC, List.of());
        var priv = privateStep(2, "smtp_send_email", "Sent, message id 42", true);

        var outcome = LocalExecutor.completed("Local prose about the mailbox", "send it",
                List.of(pub, priv));

        assertTrue(outcome.ok());
        assertTrue(outcome.text().contains("📰 Digest — 2026-09-23"), "the public result, in full");
        assertTrue(outcome.text().contains("{{2}} smtp_send_email"), "the private one by its task handle");
        assertTrue(outcome.text().contains("PRIVATE"));
        assertFalse(outcome.text().contains("message id 42"), "and never by content");
        assertFalse(outcome.text().contains("Local prose"),
                "the local model's prose is a paraphrase of what it read, and a paraphrase is the "
                        + "one thing the canary cannot see");
        assertTrue(outcome.text().contains("withheld"));
        assertTrue(outcome.text().contains("To pass one on"),
                "and the cloud is told how to move it without reading it");
    }

    @Test
    @DisplayName("an all-public delegation reads exactly as before, summary included")
    void allPublicIsUnchanged() {
        var outcome = LocalExecutor.completed("Digest sent.", "send it", List.of(
                step("daily_news_digest", Map.of(), "the digest"),
                step("smtp_send_email", Map.of("to", "petr@example.com"), "Sent")));

        assertTrue(outcome.text().startsWith("Digest sent."));
        assertTrue(outcome.text().contains("the digest"));
        assertFalse(outcome.text().contains("withheld"));
    }

    @Test
    @DisplayName("a private failure keeps its traceback off the cloud; a public one keeps it verbatim")
    void privateFailureIsDescribed() {
        var priv = privateStep(1, "imap_fetch", "Traceback: petr@x SMTP AUTH failed", false);
        var pub = new Artifact(2, "web_fetch_and_parse", Map.of("url", "https://x"),
                Map.of("url", "https://x"), "Traceback: KeyError 'menu'", false,
                com.ownclaw.privacy.Label.PUBLIC, List.of());

        var outcome = LocalExecutor.completed("", "fetch", List.of(priv, pub));

        assertFalse(outcome.text().contains("petr@x"));
        assertTrue(outcome.text().contains("{{1}}"));
        assertTrue(outcome.text().contains("✗"));
        assertTrue(outcome.text().contains("KeyError 'menu'"),
                "the public traceback is the self-repair loop's evidence and stays verbatim");
    }

    @Test
    @DisplayName("failed steps print the arguments as written, never the substituted bytes")
    void failuresPrintWrittenParams() {
        var a = new Artifact(2, "smtp_send_email", Map.of("body", "{{1.body_text}}"),
                Map.of("body", "THE WHOLE SUBSTITUTED DIGEST"), "ERROR: auth", false,
                com.ownclaw.privacy.Label.PUBLIC, List.of());

        var outcome = LocalExecutor.completed("", "send", List.of(a));
        assertTrue(outcome.text().contains("{{1.body_text}}"));
        assertFalse(outcome.text().contains("THE WHOLE SUBSTITUTED DIGEST"),
                "the resolved map carries the bytes of whatever {{N}} pointed at");
    }

    @Test
    @DisplayName("the local tier keeps full access: a reference into a PRIVATE artifact still resolves")
    void privateStillResolvesLocally() {
        var priv = privateStep(1, "imap_fetch", "{\"body_text\":\"the mail\"}", true);
        assertEquals("the mail",
                sub(Map.of("body", "{{1.body_text}}"), List.of(priv)).get("body"),
                "private means withheld from the cloud, not from the machine it lives on");
    }

    @Test
    @DisplayName("a side effect that succeeded anywhere in the task is found — and only a success")
    void sideEffectsAreNeverRepeatedInATask() {
        // The within-delegation repeat shows the model what the earlier call returned. Across
        // delegations it must not: that is how a PRIVATE result from outside a delegation reached
        // the local model and then, paraphrased, the cloud. So the task-wide check only answers
        // "already done", and it is the same check the cloud path uses.
        var smtp = new com.ownclaw.agent.tools.Tool() {
            public String name() { return "smtp_send_email"; }
            public String description() { return "send"; }
            public Map<String, com.ownclaw.agent.tools.ToolParam> inputSchema() { return Map.of(); }
            public boolean hasSideEffects() { return true; }
            public com.ownclaw.agent.tools.ToolResult execute(Map<String, Object> p,
                    com.ownclaw.agent.tools.ToolExecutionContext c) { return null; }
        };
        var args = Map.<String, Object>of("to", "petr@example.com", "body", "menu");
        var sent = new Artifact(3, "smtp_send_email", args, args, "Sent", true,
                com.ownclaw.privacy.Label.PRIVATE, List.of());
        var failed = new Artifact(4, "smtp_send_email", args, args, "ERROR: 421", false,
                com.ownclaw.privacy.Label.PRIVATE, List.of());

        assertSame(sent, LocalExecutor.sideEffectAlreadyDone(smtp, args, List.of(failed, sent)));
        assertNull(LocalExecutor.sideEffectAlreadyDone(smtp, args, List.of(failed)),
                "a send that failed is exactly what a retry is for");
        assertNull(LocalExecutor.sideEffectAlreadyDone(smtp,
                Map.of("to", "someone@else.cz", "body", "menu"), List.of(sent)),
                "different arguments are a different change");
    }

    @Test
    @DisplayName("a failed tool is named as failed in the ledger")
    void failuresAreVisibleInTheLedger() {
        var outcome = LocalExecutor.completed("Done.", "send it", List.of(
                new Artifact("smtp_send_email", Map.of(), "ERROR: auth", false)));

        assertTrue(outcome.text().contains("smtp_send_email FAILED"),
                "a summary that says 'Done.' over a failed send is exactly what the ledger is "
                        + "there to contradict");
    }

    @Test
    @DisplayName("a name the descriptor cut short still resolves, by its visible prefix")
    void aTruncatedFieldNameResolves() {
        // The cut is not optional: a field name of 32 characters would itself be a window of the
        // private text, so the descriptor would leak and then refuse the call carrying it. The
        // cloud copies what it is shown, so what it is shown has to resolve.
        var done = List.of(step("render", Map.of(),
                "{\"rendered_html_for_email_body_with_css\":\"<p>Polévka</p>\"}"));
        assertEquals(Map.of("body", "<p>Polévka</p>"),
                sub(Map.of("body", "{{1.rendered_html_for_email…}}"), done));

        // Only when it is unambiguous — a guess here picks somebody's data.
        var twin = List.of(step("render", Map.of(),
                "{\"rendered_html_for_email\":\"A\",\"rendered_html_for_export\":\"B\"}"));
        assertEquals("body", refusedParam(Map.of("body", "{{1.rendered_html_for_e…}}"), twin),
                "two keys share the prefix, so it is refused");
    }

    @Test
    @DisplayName("prices are not references and references are not prices")
    void pricesAndReferencesCannotBeConfused() {
        // Five review rounds went into telling "$5.50" from "$1.body_text". The marker ended it.
        var done = List.of(step("a", Map.of(), "{\"body_text\":\"x\"}"));
        for (String money : List.of("$50", "$5.50", "$1.99", "$1.234,56", "$5.00/kg",
                "$5.50 Polévka" + System.lineSeparator() + "Hlavní chod")) {
            assertNull(refusedParam(Map.of("amount", money), done), money);
        }
        assertEquals("body", refusedParam(Map.of("body", "{{1.rendered_html_for_ema…}}"), done),
                "a cut name with no match is refused, not sent");
        assertEquals("body", refusedParam(Map.of("body", "$1.body_text"), done),
                "the old syntax, from habit or a stored lesson, is refused rather than sent");
    }

    @Test
    @DisplayName("a private step is described once in the consolidated result, not twice")
    void privateStepsAreNotDoublePrefixed() {
        var priv = new Artifact(1, "imap_unread_summarizer", Map.of(), Map.of(), "{\"ok\":true}",
                true, com.ownclaw.privacy.Label.PRIVATE, List.of("credentials (3)"));
        String text = LocalExecutor.buildConsolidatedResult("fetch mail", List.of(priv));
        assertEquals(1, text.split("imap_unread_summarizer", -1).length - 1,
                "the descriptor already names the handle and the tool: " + text);
        assertTrue(text.contains("### {{1}} imap_unread_summarizer ✓ — PRIVATE"), text);
    }

    @Test
    @DisplayName("a failed PRIVATE step contributes neither its output nor its arguments")
    void privateFailuresWithholdTheirArgumentsToo() {
        var pub = new Artifact(1, "daily_news_digest", Map.of("topic", "rust"), Map.of(),
                "ERROR: feed timed out", false, com.ownclaw.privacy.Label.PUBLIC, List.of());
        var priv = new Artifact(2, "smtp_send_email",
                Map.of("to", "ucetni@firma.cz", "body", "Faktura 2026-09 od Novák s.r.o."),
                Map.of(), "Traceback: smtplib.SMTPAuthenticationError", false,
                com.ownclaw.privacy.Label.PRIVATE, List.of("credentials (1)"));

        String text = LocalExecutor.verbatimFailures(List.of(pub, priv));

        assertTrue(text.contains("feed timed out"), "a public failure IS the repair evidence");
        assertTrue(text.contains("topic"), "together with the arguments that produced it");
        assertTrue(text.contains("(arguments withheld)"));
        assertFalse(text.contains("ucetni@firma.cz"),
                "a tainted step's arguments are what the local model wrote AFTER reading private "
                        + "content — the recipient it was given, the body it forwarded — so "
                        + "printing them hands the cloud exactly what the descriptor two lines "
                        + "further up is withholding");
        assertFalse(text.contains("Faktura 2026-09"));
    }
}
