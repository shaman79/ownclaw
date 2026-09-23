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
        assertEquals("$1", add(ctx, "a", "x", PUBLIC).handle());
        assertEquals("$2", add(ctx, "b", "y", PRIVATE).handle());
        assertEquals("$3", add(ctx, "c", "z", PUBLIC).handle());
        assertEquals(List.of(1, 2, 3), ctx.artifacts().stream().map(Artifact::n).toList(),
                "$3 has to mean one thing to the local ledger, the cloud descriptor and the "
                        + "events row, and a later delegation must be able to name an earlier "
                        + "delegation's result");
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
    @DisplayName("nothing is allowed by default")
    void nothingByDefault() {
        var ctx = task("hello");
        assertFalse(ctx.isAllowedLeak(1, "anything at all here that is long"));
        assertFalse(ctx.isAllowedLeak(1, ""));
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
