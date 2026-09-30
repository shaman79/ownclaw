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

    // ── a change is never made twice in one task ──

    /** A tool that declares side effects, which is all the guard asks of it. */
    private static com.ownclaw.agent.tools.Tool sideEffecting(String name) {
        return new com.ownclaw.agent.tools.Tool() {
            public String name() { return name; }
            public String description() { return name; }
            public Map<String, com.ownclaw.agent.tools.ToolParam> inputSchema() { return Map.of(); }
            public boolean hasSideEffects() { return true; }
            public com.ownclaw.agent.tools.ToolResult execute(Map<String, Object> p,
                    com.ownclaw.agent.tools.ToolExecutionContext c) { return null; }
        };
    }

    @Test
    @DisplayName("an identical earlier success is found, whenever it happened")
    void identicalCallIsFound() {
        var sent = step("smtp_send_email", Map.of("to", "petr@example.com", "subject", "Digest"),
                "Sent, message id 42");
        var done = List.of(sent, step("daily_news_digest", Map.of(), "...headlines..."));
        assertSame(sent, LocalExecutor.sideEffectAlreadyDone(sideEffecting("smtp_send_email"),
                        Map.of("to", "petr@example.com", "subject", "Digest"), done),
                "not only the immediately preceding call: re-sending the same email with one "
                        + "unrelated call in between is still sending it twice");
    }

    @Test
    @DisplayName("different arguments are a different call")
    void differentArgumentsAreNotARepeat() {
        var done = List.of(step("smtp_send_email", Map.of("to", "a@example.com"), "ok"));
        assertNull(LocalExecutor.sideEffectAlreadyDone(sideEffecting("smtp_send_email"),
                        Map.of("to", "b@example.com"), done),
                "two recipients is two emails, which is the goal, not a mistake");
    }

    @Test
    @DisplayName("no arguments and null arguments are the same call")
    void nullParamsMatchEmptyParams() {
        var done = List.of(step("publish_report", Map.of(), "published"));
        assertNotNull(LocalExecutor.sideEffectAlreadyDone(sideEffecting("publish_report"), null, done),
                "a model that omits an empty argument object has not made a different call");
    }

    @Test
    @DisplayName("a call that never happened is not suppressed, and neither is a read")
    void freshCallIsAllowed() {
        assertNull(LocalExecutor.sideEffectAlreadyDone(sideEffecting("smtp_send_email"),
                Map.of("to", "a@example.com"), List.of()));
        var read = new com.ownclaw.agent.tools.Tool() {
            public String name() { return "web_fetch"; }
            public String description() { return "read"; }
            public Map<String, com.ownclaw.agent.tools.ToolParam> inputSchema() { return Map.of(); }
            public com.ownclaw.agent.tools.ToolResult execute(Map<String, Object> p,
                    com.ownclaw.agent.tools.ToolExecutionContext c) { return null; }
        };
        assertNull(LocalExecutor.sideEffectAlreadyDone(read, Map.of(),
                List.of(step("web_fetch", Map.of(), "page"))), "a second read costs only time");
    }

    @Test
    @DisplayName("a send reported as ok:false did not happen, and may be retried")
    void anOkFalseEnvelopeIsNotASuccess() {
        // The production smtp_send_email reports every SMTP error as success with "ok": false
        // inside. Trusting the success flag told the model the send had happened and refused
        // the retry that would have delivered it: no email, and the run recorded complete.
        var failedSend = step("smtp_send_email", Map.of("to", "petr@example.com"),
                "{\"ok\": false, \"error\": \"SMTP connection error: timed out\"}");
        assertFalse(failedSend.succeeded());
        assertNull(LocalExecutor.sideEffectAlreadyDone(sideEffecting("smtp_send_email"),
                Map.of("to", "petr@example.com"), List.of(failedSend)));
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
    @DisplayName("text is text: shell, code and templates are never touched or refused")
    void onlyWholeValuesAreReferences() {
        var done = List.of(step("x", Map.of(), "OUTPUT"));
        for (String text : List.of("for f in *; do echo \"$1\"; done", "Here is the menu: {{1}}",
                "rf\"\\d{{4}}-\\d{{2}}\"", "\\frac{{1}}{{2}}", "int a[2][2] = {{1,2},{3,4}};",
                "Hello {{1}}, your order {{2}} ships today", "{{ 0 if is_state('x','on') else 1 }}")) {
            assertEquals(text, sub(Map.of("command", text), done).get("command"), text);
        }
        // Known limit, chosen over refusing all of the above: a reference inside a sentence goes
        // out as written. It is visible in what arrives, which the refusals were not.
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

    // ── a result is passed on by reference, not typed out again ──

    /** A digest whose every 32-character run is its own: distinct headlines, distinct numbers. */
    static String digest() {
        var sb = new StringBuilder("📰 Digest — 2026-09-22\n");
        String[] topics = {"tram line", "council budget", "river level", "rail strike",
                "museum reopening", "bridge repair", "school funding", "heat record"};
        for (int i = 1; i <= 40; i++) {
            sb.append(i).append(". Prague ").append(topics[i % topics.length]).append(": item ")
              .append(1000 + i * 37).append(" reported at ").append(String.format("%02d:%02d", i % 24, i % 60))
              .append('\n');
        }
        return sb.toString();
    }

    /** Text the model wrote itself: nothing in it is a run of {@link #digest()}. */
    static String composed(int length) {
        var sb = new StringBuilder("Dobré ráno, Petře!\n");
        String[] words = {"weather", "stays", "mild", "in", "Brno", "while", "the", "orchard",
                "harvest", "begins", "early", "this", "autumn", "with", "plums", "and", "pears"};
        for (int i = 0; sb.length() < length; i++) {
            sb.append(words[i % words.length]).append(i % 7 == 0 ? ".\n" : " ");
        }
        return sb.toString();
    }

    @Test
    @DisplayName("a partial or altered copy of an earlier result is refused on a tool that changes something")
    void aRetypedResultIsRefused() {
        String digest = digest();
        var done = List.of(step("daily_news_digest", Map.of(), digest));
        var smtp = sideEffecting("smtp_send_email");

        String half = digest.substring(0, digest.length() / 2);
        assertEquals("body", LocalExecutor.retyped(smtp,
                        Map.of("to", "petr@example.com", "body", half), done),
                "a copy that stops halfway is the morning email with its end missing");
        assertEquals("body", LocalExecutor.retyped(smtp,
                        Map.of("body", digest.replace("2026-09-22", "2025-07-10")), done),
                "one wrong date in an otherwise perfect copy is still a copy typed out by hand");
        assertEquals("body", LocalExecutor.retyped(smtp,
                        Map.of("body", composed(400) + "\n" + half), done),
                "and so is one wrapped in a greeting");
    }

    @Test
    @DisplayName("an exact copy, a reference, and composed text of any length are not refused")
    void copiesReferencesAndCompositionPass() {
        String digest = digest();
        var done = List.of(step("daily_news_digest", Map.of(), digest));
        var smtp = sideEffecting("smtp_send_email");

        assertNull(LocalExecutor.retyped(smtp, Map.of("body", digest), done),
                "byte-identical: it paid for the output tokens, but nothing was invented");
        assertNull(LocalExecutor.retyped(smtp, Map.of("body", "{{1}}"), done));
        assertNull(LocalExecutor.retyped(smtp, Map.of("body", composed(5_000)), done),
                "the local model may write: there is no length at which composing becomes copying");
        assertNull(LocalExecutor.retyped(smtp,
                        Map.of("body", "Headline «" + digest.substring(30, 61) + "»"), done),
                "31 characters of it is a quote, not a copy: shorter than the canary's window");
    }

    @Test
    @DisplayName("only where a reference could be written instead: a change, a forwardable result")
    void retypingIsJudgedOnlyWhereAReferenceWorks() {
        String digest = digest();
        String half = digest.substring(0, digest.length() / 2);
        var read = new com.ownclaw.agent.tools.Tool() {
            public String name() { return "web_search"; }
            public String description() { return "read"; }
            public Map<String, com.ownclaw.agent.tools.ToolParam> inputSchema() { return Map.of(); }
            public com.ownclaw.agent.tools.ToolResult execute(Map<String, Object> p,
                    com.ownclaw.agent.tools.ToolExecutionContext c) { return null; }
        };
        assertNull(LocalExecutor.retyped(read, Map.of("q", half),
                List.of(step("daily_news_digest", Map.of(), digest))), "a read changes nothing");
        assertNull(LocalExecutor.retyped(sideEffecting("smtp_send_email"), Map.of("body", half),
                        List.of(new Artifact("daily_news_digest", Map.of(), digest, false))),
                "a failed result cannot be referenced, so quoting it is the only way to pass it on");
        assertNull(LocalExecutor.retyped(sideEffecting("smtp_send_email"),
                        Map.of("recipients", List.of(half)), List.of(step("daily_news_digest", Map.of(), digest))),
                "nor can a reference sit inside a list");
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

    // ── what the cloud reads of a delegation: every public output whole ──

    @Test
    @DisplayName("a completed delegation carries each public output whole, and each failure")
    void delegationOutputsAreWhole() {
        String big = digest().repeat(20) + "THE LAST LINE OF THE DIGEST";
        String trace = "Traceback (most recent call last):\n" + "  File \"skill.py\", line 9\n".repeat(900)
                + "KeyError: 'menu'";
        var fetched = new Artifact(1, "daily_news_digest", Map.of(), Map.of(), big, true,
                com.ownclaw.privacy.Label.PUBLIC, List.of());
        var broken = new Artifact(2, "web_fetch_and_parse", Map.of(), Map.of(), trace, false,
                com.ownclaw.privacy.Label.PUBLIC, List.of());

        var outcome = LocalExecutor.completed("Fetched.", "fetch", List.of(fetched, broken));

        assertTrue(big.length() > 20_000 && trace.length() > 20_000);
        assertTrue(outcome.text().contains(big),
                "the cloud judges the delegation on this; its end is evidence like its start");
        assertTrue(LocalExecutor.verbatimFailures(List.of(fetched, broken)).endsWith(trace),
                "a traceback over 20,000 characters used to lose its exception line");
        assertTrue(outcome.text().endsWith(LocalExecutor.verbatimFailures(List.of(fetched, broken))));
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
        assertTrue(outcome.text().contains("When you have a tool that takes one"),
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
    @DisplayName("failed steps print the arguments as written, in the task's numbering")
    void failuresPrintWrittenParams() {
        // To the local model {{1}} was its first step; the cloud reads task handles everywhere
        // else in this text and may copy the arguments into a call of its own.
        var digest = new Artifact(5, "daily_news_digest", Map.of(), Map.of(),
                "{\"body_text\":\"THE WHOLE SUBSTITUTED DIGEST\"}", true,
                com.ownclaw.privacy.Label.PUBLIC, List.of());
        var send = new Artifact(6, "smtp_send_email", Map.of("body", "{{1.body_text}}"),
                Map.of("body", "THE WHOLE SUBSTITUTED DIGEST"), "ERROR: auth", false,
                com.ownclaw.privacy.Label.PUBLIC, List.of());

        String failures = LocalExecutor.verbatimFailures(List.of(digest, send));
        assertTrue(failures.contains("{{5.body_text}}"), failures);
        assertFalse(failures.contains("{{1.body_text}}"), "never the delegation's own numbering");
        assertFalse(failures.contains("THE WHOLE SUBSTITUTED DIGEST"),
                "and never the bytes the reference pulled in");
    }

    @Test
    @DisplayName("only real references are renumbered; template text is shown as it ran")
    void onlyReferencesAreRenumbered() {
        var first = new Artifact(5, "digest", Map.of(), Map.of(), "{\"body_text\":\"x\"}", true,
                com.ownclaw.privacy.Label.PUBLIC, List.of());
        var written = Map.<String, Object>of("body", "{{1.body_text}}",
                "template", "Hello {{1}}, your order {{2}} ships today");
        var out = References.argsForTask(written, List.of(first));
        assertEquals("{{5.body_text}}", out.get("body"));
        assertEquals("Hello {{1}}, your order {{2}} ships today", out.get("template"),
                "the failure evidence has to describe the call that actually ran");
        assertEquals("sent {{5}}, then {{?}}", References.proseForTask("sent {{1}}, then {{3}}", List.of(first)),
                "a handle the delegation has no result for cannot resolve task-wide to someone else's");
    }

    @Test
    @DisplayName("the ledger the cloud reads says FAILED for an ok:false step")
    void theLedgerUsesTheHonestVerdict() {
        var send = new Artifact(3, "smtp_send_email", Map.of(), Map.of(),
                "{\"ok\": false, \"error\": \"timed out\"}", true,
                com.ownclaw.privacy.Label.PRIVATE, List.of("credentials (1)"));
        var outcome = LocalExecutor.completed("sent", "send", List.of(send));
        assertTrue(outcome.text().contains("{{3}} smtp_send_email FAILED"), outcome.text());
        assertFalse(outcome.ok());
    }

    @Test
    @DisplayName("the local summary reaches the cloud in the task's numbering")
    void theSummaryIsRenumbered() {
        var digest = new Artifact(7, "daily_news_digest", Map.of(), Map.of(), "digest", true,
                com.ownclaw.privacy.Label.PUBLIC, List.of());
        var outcome = LocalExecutor.completed("Forwarded {{1}} to Petr.", "send it", List.of(digest));
        assertTrue(outcome.text().startsWith("Forwarded {{7}} to Petr."), outcome.text());
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
    @DisplayName("the goal loses references to earlier results; a plan's own step parameters keep theirs")
    void theGoalScrubTouchesProseOnly() {
        var plan = new DelegationPlan("Email {{3.body_text}} to Petr",
                List.of(new DelegationPlan.Step("fetch the menu", "daily_menu_fetcher", Map.of()),
                        new DelegationPlan.Step("send {{3}}", "smtp_send_email",
                                Map.of("body", "{{1.body_text}}"))),
                List.of(), 6);
        int[] removed = {0};
        var own = LocalExecutor.withoutOutsideReferences(plan, removed);

        assertFalse(own.goal().contains("{{3"), own.goal());
        assertFalse(own.steps().get(1).description().contains("{{3"));
        assertEquals("{{1.body_text}}", own.steps().get(1).params().get("body"),
                "in a plan, {{1}} means the plan's own step 1 -- exactly how the delegation numbers");
        assertEquals(2, removed[0]);
    }

    @Test
    @DisplayName("a refusal carries no field names — the cloud reads it")
    void refusalsListNothing() {
        var priv = new Artifact(1, "contacts", Map.of(), Map.of(),
                "{\"jana.novakova.private@example.com\":\"x\"}", true,
                com.ownclaw.privacy.Label.PRIVATE, List.of("credentials (1)"));
        var r = References.resolve(Map.of("body", "{{1.nope}}"), List.of(priv));
        assertFalse(r.ok());
        assertFalse(r.reason().contains("jana.novakova"),
                "a key can be data; on the cloud path this listed every one of them, uncut");
    }

    @Test
    @DisplayName("a field named by its position resolves; a shortened name never does")
    void aFieldResolvesByItsPosition() {
        // A name of 32 characters or more could be a window of private text, so the descriptor
        // offers such a field by its position. Its exact name resolves too; a guess never does.
        var done = List.of(step("render", Map.of(),
                "{\"ok\":true,\"rendered_html_for_email_body_with_css\":\"<p>Polévka</p>\"}"));
        assertEquals(Map.of("body", "<p>Polévka</p>"), sub(Map.of("body", "{{1.#2}}"), done));
        assertEquals(Map.of("body", "<p>Polévka</p>"),
                sub(Map.of("body", "{{1.rendered_html_for_email_body_with_css}}"), done));
        assertEquals("body", refusedParam(Map.of("body", "{{1.rendered_html_for_email…}}"), done),
                "matching by a visible beginning is gone: two keys sharing it made it a guess");
        assertEquals("body", refusedParam(Map.of("body", "{{1.#3}}"), done),
                "a position the result does not have");
    }

    @Test
    @DisplayName("prices are not references and references are not prices")
    void pricesAndReferencesCannotBeConfused() {
        var done = List.of(step("a", Map.of(), "{\"body_text\":\"x\"}"));
        for (String money : List.of("$50", "$5.50", "$1.99", "$1.234,56", "$5.00/kg", "$1.jpg",
                "$5.50 Polévka" + System.lineSeparator() + "Hlavní chod")) {
            assertNull(refusedParam(Map.of("amount", money), done), money);
        }
        assertEquals("body", refusedParam(Map.of("body", "{{1.rendered_html_for_ema…}}"), done),
                "a cut name with no match is refused, not sent");
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
