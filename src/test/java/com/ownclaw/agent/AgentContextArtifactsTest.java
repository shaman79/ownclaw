package com.ownclaw.agent;

import com.ownclaw.privacy.Label;
import com.ownclaw.privacy.PrivateIndex;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The task-scoped store: numbering, indexing, and the one judgement call in the whole design —
 * which canary hits are material the cloud was already given.
 */
class AgentContextArtifactsTest {

    private static final Artifact.Decision PUBLIC = new Artifact.Decision(Label.PUBLIC, List.of());
    private static final Artifact.Decision PRIVATE =
            new Artifact.Decision(Label.PRIVATE, List.of("credentials: IMAP_PASS"));

    private static String prose(int chars, long seed) {
        var r = new Random(seed);
        var sb = new StringBuilder();
        String[] w = {"invoice", "Novák", "platba", "quarterly", "Bratčice", "ledger", "due"};
        while (sb.length() < chars) sb.append(w[r.nextInt(w.length)]).append(r.nextInt(9999)).append(' ');
        return sb.substring(0, chars);
    }

    private static final String NL = System.lineSeparator();

    private static AgentContext task(String message) {
        return new AgentContext("u1", "t1", message);
    }

    private static Artifact add(AgentContext ctx, String tool, String output, Artifact.Decision d) {
        return ctx.addArtifact(tool, Map.of(), Map.of(), output, true, d);
    }

    @Test
    @DisplayName("artifacts are numbered task-wide, in order, and never renumbered")
    void numbering() {
        var ctx = task("send the digest");
        assertEquals("{{1}}", add(ctx, "a", "x", PUBLIC).handle());
        assertEquals("{{2}}", add(ctx, "b", "y", PRIVATE).handle());
        assertEquals("{{3}}", add(ctx, "c", "z", PUBLIC).handle());
        assertEquals(List.of(1, 2, 3), ctx.artifacts().stream().map(Artifact::n).toList(),
                "{{3}} has to mean one thing to the cloud's descriptor, the egress ledger and the "
                        + "events row. (Inside a delegation the local model counts its own steps "
                        + "from {{1}}; it never sees these task-wide handles.)");
    }

    @Test
    @DisplayName("a PRIVATE artifact is indexed for the canary; a PUBLIC one is not")
    void onlyPrivateIsIndexed() {
        var ctx = task("send the digest");
        String pub = prose(400, 1), priv = prose(400, 2);
        add(ctx, "digest", pub, PUBLIC);
        add(ctx, "imap_fetch", priv, PRIVATE);

        assertNotNull(ctx.privateIndex().firstHitIn("…" + priv.substring(100, 150) + "…"));
        assertNull(ctx.privateIndex().firstHitIn("…" + pub.substring(100, 150) + "…"),
                "public bytes may go; indexing them would refuse the digest every morning");
    }

    // ── which hits are allowed ──

    @Test
    @DisplayName("a private result that quotes the task text is not a leak of the task text")
    void quotingTheTaskIsAllowed() {
        String message = "Forward the invoice from " + prose(60, 3) + " to accounting";
        var ctx = task(message);
        var priv = add(ctx, "imap_fetch", "Found it: " + message, PRIVATE);

        String window = PrivateIndex.normalise(message).substring(10, 50);
        assertTrue(ctx.isAllowedLeak(priv.n(), window),
                "the cloud wrote the task text; sending it back is not a disclosure");
    }

    @Test
    @DisplayName("a public result recorded AFTER a private one, repeating it, is laundering — refused")
    void launderingIsRefused() {
        var ctx = task("send it");
        String secret = prose(400, 4);
        var priv = add(ctx, "imap_fetch", secret, PRIVATE);
        add(ctx, "summarise", "Summary: " + secret.substring(0, 120), PUBLIC);   // $2, after

        String window = PrivateIndex.normalise(secret).substring(20, 60);
        assertFalse(ctx.isAllowedLeak(priv.n(), window),
                "a skill that echoes what it was given, or a summary the local model wrote, is "
                        + "the private content in a public wrapper; whitelisting it lets the "
                        + "leak through as 'already public'");
    }

    @Test
    @DisplayName("a public result recorded BEFORE the private one that echoes it is allowed")
    void echoOfEarlierPublicIsAllowed() {
        var ctx = task("send it");
        String digest = prose(400, 5);
        add(ctx, "daily_news_digest", digest, PUBLIC);                          // $1
        var smtp = add(ctx, "smtp_send_email", "Sent: " + digest, PRIVATE);     // $2 quotes $1

        String window = PrivateIndex.normalise(digest).substring(20, 60);
        assertTrue(ctx.isAllowedLeak(smtp.n(), window),
                "the smtp confirmation quotes the public digest it just sent; the digest was "
                        + "already the cloud's to read");
    }

    @Test
    @DisplayName("what the cloud itself wrote is not a leak back to the cloud")
    void theCloudsOwnWritingIsAllowed() {
        var ctx = task("send the digest");
        String subject = "Faktura 2026-09 od dodavatele Novák s.r.o. splatná 15. října";
        // The cloud typed this into a tool call; the renderer replays it verbatim as an
        // assistant turn, which the gateway then scans.
        ctx.trajectory().record(new AgentAction("smtp_send_email",
                Map.of("subject", subject), "sending the invoice"),
                AgentObservation.success("smtp_send_email", "sent", Map.of(), 10));
        var priv = add(ctx, "smtp_send_email", "Sent '" + subject + "' to petr", PRIVATE);

        assertTrue(ctx.isAllowedLeak(priv.n(), PrivateIndex.normalise(subject).substring(0, 40)),
                "a skill that echoes an argument it was given would otherwise make the next "
                        + "prompt unsendable, after the email had gone out");
        assertTrue(ctx.isAllowedLeak(priv.n(), PrivateIndex.normalise("sending the invoice")),
                "the reasoning too — the renderer replays that as well");
    }

    @Test
    @DisplayName("a skill's own source is not a leak of what that skill returned")
    void theProducersSourceIsAllowed() {
        var ctx = task("send the digest");
        String line = "    server.login(os.environ['SMTP_USER'], os.environ['SMTP_PASS'])";
        ctx.setSkillSource(name -> "smtp_send_email".equals(name)
                ? "import smtplib" + NL + line + NL : null);
        // A Python traceback quotes the line that threw, so the failure carries the source.
        var priv = add(ctx, "smtp_send_email",
                "Skill error:" + NL + "Traceback..." + NL + line, PRIVATE);

        assertTrue(ctx.isAllowedLeak(priv.n(), PrivateIndex.normalise(line).substring(0, 40)),
                "without this a credentialed skill's failure made its own repair prompt "
                        + "unsendable — and repair is the loop this project exists for");
        assertFalse(ctx.isAllowedLeak(priv.n(),
                PrivateIndex.normalise("Traceback... and the mailbox contents that followed")),
                "only the source, not the rest of the failure");
    }

    @Test
    @DisplayName("nothing is allowed by default")
    void nothingByDefault() {
        var ctx = task("hello");
        assertFalse(ctx.isAllowedLeak(1, "anything at all here that is long"));
        assertFalse(ctx.isAllowedLeak(1, ""));
    }

    @Test
    @DisplayName("an artifact is claimed by exactly one step, and never by a later one")
    void artifactsAreClaimedOnce() {
        var ctx = task("send it");
        add(ctx, "imap_fetch", "x", PUBLIC);           // $1, recorded by step 1

        assertTrue(ctx.claimArtifact(1), "step 1 recorded it, so step 1 reports it");
        assertFalse(ctx.claimArtifact(1),
                "step 2 was a not-found tool and recorded nothing; without this it inherited "
                        + "{{1}}'s handle, label and hash and the ops page named a tool that had "
                        + "never run");

        add(ctx, "smtp_send_email", "y", PRIVATE);     // $2
        assertTrue(ctx.claimArtifact(2));
        assertFalse(ctx.claimArtifact(2));
    }

    @Test
    @DisplayName("a delegation claims everything it reported, so no later step can re-report it")
    void delegationClaimsItsOwn() {
        var ctx = task("send it");
        add(ctx, "imap_fetch", "x", PRIVATE);
        add(ctx, "smtp_send_email", "y", PRIVATE);

        ctx.claimAllArtifacts();
        assertFalse(ctx.claimArtifact(1));
        assertFalse(ctx.claimArtifact(2));

        add(ctx, "later", "z", PUBLIC);
        assertTrue(ctx.claimArtifact(3), "but a step after it still reports its own");
    }

    // ── a task holding a file ──

    private static final List<String> CSV_WHY = List.of("uploaded file", "text/csv, 412 bytes");
    private static final List<String> PDF_WHY =
            List.of("uploaded file", "application/pdf, 84211 bytes, no text read (not text, over 100 KB, or not UTF-8)");

    @Test
    @DisplayName("on a file task every result is PRIVATE and unindexed, so its descriptor shows no keys")
    void fileTaskResultsArePrivateAndUnindexed() {
        var ctx = task("summarise this statement");
        ctx.addFile("f1", "date,amount\n2026-09-01,-1200\n", CSV_WHY);

        // No credentials, no reference: the skill reached the file through _attached_files, or
        // by opening the uploads directory itself. Nothing in the call says so.
        var d = ctx.decide(List.of(), List.of(), false);
        assertEquals(Label.PRIVATE, d.label(),
                "every skill run in this task is handed the file, so what it returns is the file's");
        assertFalse(d.indexed());
        assertEquals(List.of("given the file {{1}}"), d.why(), "by handle, never by name");

        // A statement parser's result: its key names and booleans are the statement.
        String parsed = "{\"ok\": true, \"iban_CZ6508000000192000145399\": \"x\", "
                + "\"closing_balance\": 41200, \"overdrawn\": false}";
        String shown = add(ctx, "statement_parser", parsed, d).describe();
        for (String leaked : List.of("iban", "CZ65", "closing_balance", "overdrawn", "ok=", "use:")) {
            assertFalse(shown.contains(leaked), leaked + " in the descriptor: " + shown);
        }
        assertTrue(shown.startsWith("{{2}} statement_parser ✓ — PRIVATE (given the file {{1}})"), shown);
    }

    @Test
    @DisplayName("on a file task, a result that references the file is unindexed too")
    void referenceToAFileIsUnindexed() {
        var ctx = task("summarise this statement");
        var file = ctx.addFile("f1", "date,amount\n2026-09-01,-1200\n", CSV_WHY);
        assertTrue(file.isPrivate() && file.indexed(), "the upload's own text is in the canary");

        var d = ctx.decide(List.of(), List.of(file), false);
        assertEquals(Label.PRIVATE, d.label());
        assertEquals(List.of("references {{1}}"), d.why());
        assertFalse(d.indexed(),
                "the file is indexed, so without the file rule this result would be too -- and "
                        + "its descriptor would list the parser's key names");
    }

    @Test
    @DisplayName("on a file task, a credentialed result gets the short descriptor too")
    void credentialsOnAFileTaskKeepTheirDescriptor() {
        var ctx = task("email this statement to my accountant");
        ctx.addFile("f1", "", PDF_WHY);

        var d = ctx.decide(List.of("SMTP_PASS"), List.of(), false);
        assertEquals(Label.PRIVATE, d.label());
        assertEquals(List.of("credentials (1)"), d.why());
        assertFalse(d.indexed(),
                "every skill is handed the file, a credentialed one too, so its key names can be "
                        + "the file's data");
    }

    @Test
    @DisplayName("a task without a file is labelled exactly as before")
    void noFileNoChange() {
        var ctx = task("fetch the menu");
        var plain = ctx.decide(List.of(), List.of(), false);
        assertEquals(Label.PUBLIC, plain.label());
        assertEquals(List.of(), plain.why());

        var tainted = ctx.decide(List.of(), List.of(), true);
        assertEquals(Label.PRIVATE, tainted.label());
        assertEquals(List.of("after private data in this delegation"), tainted.why());
        assertFalse(tainted.indexed());

        var creds = ctx.decide(List.of("IMAP_PASS"), List.of(), false);
        assertEquals(Label.PRIVATE, creds.label());
        assertTrue(creds.indexed());
    }

    @Test
    @DisplayName("the ids a skill is handed are the files that were registered, and nothing else")
    void handedListIsTheRegisteredList() {
        var ctx = task("compare these two");
        assertEquals(List.of(), ctx.attachmentIds());
        String csv = prose(400, 7);
        ctx.addFile("f1", csv, CSV_WHY);
        ctx.addFile("f2", "", PDF_WHY);

        assertEquals(List.of("f1", "f2"), ctx.attachmentIds(),
                "_attached_files and the file rule read one list, so they cannot drift apart");
        assertEquals(2, ctx.files().size());
        assertThrows(UnsupportedOperationException.class, () -> ctx.files().clear());
        assertNotNull(ctx.privateIndex().firstHitIn("…" + csv.substring(100, 140) + "…"),
                "a text upload's own bytes are in the canary");
        assertEquals(List.of("given the files {{1}}, {{2}}"), ctx.decide(List.of(), List.of(), false).why());
    }

    @Test
    @DisplayName("the egress context carries the task's identity, index, secrets and the predicate")
    void egressContext() {
        var ctx = task("send it");
        ctx.setSecretValues(Map.of("IMAP_PASS", "hunter2secret"));
        String secret = prose(200, 6);
        var priv = add(ctx, "imap_fetch", secret, PRIVATE);

        var e = ctx.egress("think");
        assertEquals("u1", e.userId());
        assertEquals("t1", e.taskId());
        assertEquals("think", e.purpose());
        assertSame(ctx.privateIndex(), e.index());
        assertEquals("hunter2secret", e.secretValues().get("IMAP_PASS"));
        assertFalse(e.allowed().test(priv.n(), PrivateIndex.normalise(secret).substring(0, 40)));
        assertTrue(e.allowed().test(priv.n(), PrivateIndex.normalise("send it")));
    }
}
