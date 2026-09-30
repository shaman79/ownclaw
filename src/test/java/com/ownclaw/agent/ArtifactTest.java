package com.ownclaw.agent;

import com.ownclaw.agent.tools.ToolResult;
import com.ownclaw.privacy.Label;
import com.ownclaw.privacy.PrivateIndex;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The label decides; the renderer does not.
 * <p>
 * {@link Artifact#asObservation} is the one place a result's bytes are either admitted to the
 * trajectory or replaced by a descriptor. Everything downstream — both prompt renderers, the
 * progress summary, the episode, the events rows — reads that observation, so if this is right,
 * nothing else has to know about privacy. If it is wrong, nothing else can save it.
 */
class ArtifactTest {

    private static final String PRIVATE_JSON =
            "{\"ok\": false, \"error\": \"SMTP AUTH failed for petr@example.com\", "
                    + "\"to\": \"petr@example.com\", \"count\": 3, \"messages\": [1, 2, 3], "
                    + "\"body_text\": \"" + "confidential line ".repeat(200) + "\"}";

    private static Artifact privateResult(String tool, String output, boolean ok) {
        return new Artifact(2, tool, Map.of(), Map.of(), output, ok, Label.PRIVATE,
                List.of("credentials (1)"));
    }

    // ── labelFor: every clause on its own ──

    @Test
    @DisplayName("a skill that declared credentials produces a PRIVATE result")
    void credentialsMakeItPrivate() {
        var d = Artifact.labelFor(List.of("IMAP_PASS"), List.of());
        assertEquals(Label.PRIVATE, d.label());
        assertEquals(List.of("credentials (1)"), d.why(),
                "the COUNT, not the names: the skill harness words a vault miss as 'Missing "
                        + "required credentials: SMTP_PASS, SMTP_USER', so naming them here put "
                        + "a run of the OUTPUT into the descriptor and the canary refused the "
                        + "call that carried it");
    }

    @Test
    @DisplayName("a call that references a file is PRIVATE by the reference clause, and says which")
    void referenceToAFileMakesItPrivate() {
        // labelFor weighs the call's own facts only. That a task holds a file at all is weighed
        // in AgentContext.decide, because every skill run in such a task is handed it whether or
        // not it references it; this is the call's own fact, and it names the handle it pulled.
        var attachment = new Artifact(1, "attachment", Map.of(), Map.of(),
                "acct,balance\nCZ4720100123,41200", true, Label.PRIVATE, List.of("uploaded file"));
        var used = References.resolve(Map.of("text", "{{1}}"), List.of(attachment)).used();
        var d = Artifact.labelFor(List.of(), used);
        assertEquals(Label.PRIVATE, d.label());
        assertEquals(List.of("references {{1}}"), d.why());
    }

    @Test
    @DisplayName("a public tool's result stays PUBLIC after a private step")
    void aPrivateStepDoesNotMarkWhatPublicToolsReturn() {
        // It used to: once a delegation touched anything private, every later result of it was
        // PRIVATE. A reviewer's probe followed the consequence — the restaurant page fetched
        // after the email went out was marked private, the cloud's own later fetch of the same
        // public page then tripped the canary, and a run whose email had been sent reported
        // "did not finish". What the model WRITES after reading private content is withheld
        // elsewhere (LocalExecutor.completed, verbatimFailures); the label has two facts only.
        var d = Artifact.labelFor(List.of(), List.of());
        assertEquals(Label.PUBLIC, d.label());
    }

    @Test
    @DisplayName("pulling in a PRIVATE result makes the result PRIVATE; a PUBLIC one does not")
    void referenceToPrivateMakesItPrivate() {
        var priv = privateResult("imap_fetch", "{\"body_text\":\"x\"}", true);   // handle {{2}}
        var used = References.resolve(Map.of("body", "{{1.body_text}}"), List.of(priv)).used();
        var d = Artifact.labelFor(List.of(), used);
        assertEquals(Label.PRIVATE, d.label(), "derived from private is private");
        assertEquals(List.of("references {{2}}"), d.why(),
                "named by its TASK handle, which is what the cloud reads in the descriptor");

        var pub = new Artifact(2, "x", Map.of(), Map.of(), "{}", true, Label.PUBLIC, List.of());
        assertEquals(Label.PUBLIC, Artifact.labelFor(List.of(), List.of(pub)).label(),
                "a PUBLIC result is not a reason");
    }

    @Test
    @DisplayName("with none of the facts, a result is PUBLIC — exactly as today")
    void nothingMakesItPublic() {
        var d = Artifact.labelFor(List.of(), List.of());
        assertEquals(Label.PUBLIC, d.label());
        assertTrue(d.why().isEmpty());
    }

    // ── describe: everything except the content ──

    @Test
    @DisplayName("the descriptor names the handle, the tool, the label, the shape — and no content")
    void descriptorCarriesShapeNotContent() {
        String d = privateResult("smtp_send_email", PRIVATE_JSON, false).describe();

        assertTrue(d.startsWith("{{2}} smtp_send_email ✗ — PRIVATE (credentials (1))"), d);
        assertTrue(d.contains("json"), d);
        assertTrue(d.contains("ok=false"),
                "a success envelope around a failure is the normal shape of a skill result; a "
                        + "descriptor that hid ok=false would have the cloud report a send that "
                        + "never happened");
        assertTrue(d.contains("count (number)"),
                "a number can BE the secret — a balance, a count of unread mail — so it gets "
                        + "its kind and nothing more; only booleans carry their value");
        assertFalse(d.contains("count=3"));
        assertTrue(d.contains("error (string, "), "a string is where the data is: kind and size only");
        assertTrue(d.contains("messages (array, 3)"), d);
        assertFalse(d.contains("petr@example.com"), "not the error text");
        assertFalse(d.contains("confidential"), "not the body");
        for (int i = 0; i + 32 <= PRIVATE_JSON.length(); i += 97) {
            assertFalse(d.contains(PRIVATE_JSON.substring(i, i + 32)),
                    "no 32-char window of the output at " + i);
        }
    }

    @Test
    @DisplayName("a private failure that is not JSON says the text is withheld and where it is")
    void privateTextFailure() {
        String d = privateResult("imap_fetch", "Traceback (most recent call last): ...", false)
                .describe();
        assertTrue(d.contains("✗"));
        assertTrue(d.contains("text withheld; skill_usage row via ops"),
                "the owner reads the traceback there; the cloud does not read it at all");
        assertFalse(d.contains("Traceback"));
    }

    @Test
    @DisplayName("every field is listed and offered, however many there are")
    void everyFieldIsListed() {
        // Twelve listed and 240 characters of references once left an imap envelope's body_text,
        // past the twelfth key, with no reference at all -- so the cloud wrote the email itself.
        var sb = new StringBuilder("{");
        for (int i = 0; i < 70; i++) sb.append("\"field_number_").append(i).append("\": 1,");
        sb.setLength(sb.length() - 1);
        sb.append("}");
        String d = privateResult("t", sb.toString(), true).describe();
        for (int i = 0; i < 70; i++) {
            assertTrue(d.contains("field_number_" + i + " (number)"), "listed: " + i);
            assertTrue(d.contains("{{2.field_number_" + i + "}}"), "offered: " + i);
        }
        assertFalse(d.contains(" more"), "nothing is left for a '+N more': " + d);
    }

    // ── asObservation: the substitution point ──

    @Test
    @DisplayName("a PUBLIC result enters the trajectory byte for byte")
    void publicIsUnchanged() {
        var r = ToolResult.success("the digest text");
        var a = new Artifact(1, "daily_news_digest", Map.of(), Map.of(), r.output(), true,
                Label.PUBLIC, List.of());

        var obs = Artifact.asObservation(a, r, 12);
        assertEquals("the digest text", obs.output(), "exactly as today");
        assertTrue(obs.success());
    }

    @Test
    @DisplayName("a PRIVATE result enters the trajectory as its descriptor, with metadata for its bytes")
    void privateIsSubstituted() {
        var r = ToolResult.success(PRIVATE_JSON);
        var a = privateResult("smtp_send_email", PRIVATE_JSON, false);

        var obs = Artifact.asObservation(a, r, 12);
        assertEquals(a.describe(), obs.output());
        assertFalse(obs.success(), "the outcome is not hidden with the content");
        assertFalse(obs.output().contains("confidential"));

        // What travels in place of the bytes is metadata about the artifact -- the shape the
        // delegation already reports -- so a privately-executed direct call is still countable
        // by the withheld line, which otherwise printed nothing and read as "nothing was
        // withheld". None of the content is in it.
        assertFalse(String.valueOf(obs.structured()).contains("confidential"));
        assertEquals(List.of(Map.of("n", a.n(), "tool", "smtp_send_email",
                        "label", "PRIVATE", "chars", PRIVATE_JSON.length())),
                obs.structured().get("artifacts"));
    }

    @Test
    @DisplayName("a null argument value is ordinary, not a crash")
    void nullArgumentValues() {
        // Every provider keeps a JSON null as a null map entry, and a local model routinely
        // emits "cc": null for an unset optional. Map.copyOf rejects those, so the record threw
        // AFTER the tool had run: the email went out and the task died with "Internal error".
        var withNull = new java.util.LinkedHashMap<String, Object>();
        withNull.put("to", "petr@example.com");
        withNull.put("cc", null);

        var a = assertDoesNotThrow(() -> new Artifact(2, "smtp_send_email", withNull, withNull,
                "{\"ok\":true}", true, Label.PRIVATE, List.of("credentials (1)")));
        assertTrue(a.written().containsKey("cc"));
        assertNull(a.written().get("cc"));
        assertDoesNotThrow(a::describe);
        assertDoesNotThrow(() -> References.resolve(withNull, List.of()));
    }

    @Test
    @DisplayName("field names cannot carry a 32-character run of the output: a long one is a position")
    void fieldNamesAreTooShortToLeak() {
        String key = "a_very_long_field_name_that_would_otherwise_be_a_window";
        String json = "{\"" + key + "\": \"x\"}";
        String d = privateResult("t", json, true).describe();
        for (int i = 0; i + 32 <= json.length(); i++) {
            assertFalse(d.contains(json.substring(i, i + 32)),
                    "a field name at 40 characters was itself a window of the output");
        }
    }

    @Test
    @DisplayName("the legacy four-argument shape is PUBLIC with no handle")
    void legacyConstructor() {
        var a = new Artifact("x", Map.of("k", "v"), "out", true);
        assertEquals(Label.PUBLIC, a.label());
        assertEquals(0, a.n());
        assertEquals(a.written(), a.resolved());
    }

    @Test
    @DisplayName("fieldRefs: a reference to every key of a JSON object, none for anything else")
    void fieldRefs() {
        assertEquals(List.of(new ArtifactRef(3, "ok"), new ArtifactRef(3, "body_text")),
                Artifact.fieldRefs(3, "{\"ok\": true, \"body_text\": \"text\"}"));
        assertTrue(Artifact.fieldRefs(3, "not json").isEmpty());
        assertTrue(Artifact.fieldRefs(3, "[1,2]").isEmpty());
        assertTrue(Artifact.fieldRefs(3, null).isEmpty());
    }

    @Test
    @DisplayName("a long field name is offered by its position, and that reference resolves")
    void longNamesAreOfferedByPosition() {
        // A shortened name used to be offered -- "{{1.rendered_html_for_ema…}}" -- and resolved
        // by its visible beginning, which a second key sharing it turned into a guess.
        String key = "rendered_html_for_email_body_with_inline_css";
        String json = "{\"ok\": true, \"" + key + "\": \"<p>Polévka</p>\"}";
        var a = new Artifact(1, "t", Map.of(), Map.of(), json, true, Label.PRIVATE, List.of());

        assertTrue(a.describe().contains("#2 (string, 14 chars)"), a.describe());
        assertTrue(a.describe().contains("use: {{1}}, {{1.ok}}, {{1.#2}}"), a.describe());
        assertFalse(a.describe().contains("rendered_html"), "not the name, not a piece of it");
        assertEquals("<p>Polévka</p>",
                References.resolve(Map.of("body", "{{1.#2}}"), List.of(a)).params().get("body"));
        assertTrue(References.available(List.of(a)).contains("fields: ok, #2"),
                "the local model is told the same references: " + References.available(List.of(a)));
    }

    @Test
    @DisplayName("the descriptor tells the cloud how to use what it is not allowed to read")
    void theDescriptorSaysHowToReferenceIt() {
        // Without this line the descriptor is a dead end. When the local tier is down, the cloud
        // calls the credentialed skill itself, is shown "4,210 chars · fields: body_text" and is
        // told no way to put those characters into an email — so it writes the email from the
        // description and the owner gets a confident message with no menu in it.
        var a = new Artifact(1, "daily_menu_fetcher", Map.of(), Map.of(),
                "{\"body_text\": \"Polévka: česneková\"}", true, Label.PRIVATE,
                List.of("credentials (2)"));
        String d = a.describe();

        // Complete tokens, not a template. "pass <handle>.<field>" beside a list that annotates
        // its names made the cloud compose "body_text (string, 48 chars)" into it, which resolves to
        // nothing — and the literal went out as the body of the email.
        assertTrue(d.contains("use: {{1}}, {{1.body_text}}"), d);
        assertFalse(d.contains("<field>"), "nothing left for the cloud to compose: " + d);
        assertTrue(d.contains("substituted here"), d);
        assertFalse(d.contains("česneková"), "still never the content");
    }

    @Test
    @DisplayName("the body of an imap envelope is offered even when it is the twentieth key")
    void theContentIsOffered() {
        // When the reference list had a budget, the envelope spent it and body_text -- past the
        // twelfth key -- was never offered; the cloud had nothing to forward and wrote the email
        // from the description instead.
        var sb = new StringBuilder("{");
        String[] envelope = {"from", "to", "cc", "subject", "date", "message_id", "uid", "flags",
                "folder", "size", "seen", "snippet", "has_attachments", "in_reply_to",
                "references", "priority", "thread_id", "labels", "charset"};
        for (String k : envelope) sb.append('"').append(k).append("\":\"x\",");
        sb.append("\"body_text\":\"").append("Polévka dne: česneková. ".repeat(120)).append("\"}");
        String d = privateResult("imap_fetch", sb.toString(), true).describe();

        assertTrue(d.contains("{{2.body_text}}"), "the text a task forwards: " + d);
        assertTrue(d.contains("{{2.from}}") && d.contains("{{2.charset}}"), "and every other key: " + d);
        assertFalse(d.contains("česneková"), "still never the content");
    }

    @Test
    @DisplayName("a name shorter than a canary window is shown whole; a window's length is not")
    void namesAreShownBelowTheCanaryWindow() {
        // The descriptor prints the field names of a PRIVATE result; a name of a whole window
        // could itself be a window of the private text -- the descriptor would leak, and then
        // refuse the call carrying it.
        int window = com.ownclaw.privacy.PrivateIndex.WINDOW;
        String shortest = "k".repeat(window - 1), longest = "l".repeat(window);
        String json = "{\"" + shortest + "\": 1, \"" + longest + "\": 2}";
        String d = privateResult("t", json, true).describe();
        assertTrue(d.contains("{{2." + shortest + "}}"), d);
        assertTrue(d.contains("{{2.#2}}"), d);
        assertFalse(d.contains(longest), d);
    }

    @Test
    @DisplayName("a name is measured as the canary measures it: one that normalises to a window is a position")
    void namesAreMeasuredAfterNormalising() {
        // 31 characters as written; the dotted capital I lowercases to two, so the canary sees 32
        // -- a whole window of the private result, which the descriptor printed, and the gateway
        // then refused every cloud call that carried the descriptor.
        String key = "İnvoice_reference_for_2026_0930";
        assertEquals(31, key.length());
        assertEquals(PrivateIndex.WINDOW, PrivateIndex.normalise(key).length());
        var ctx = new AgentContext("u1", "t1", "which invoice was it?");
        Artifact a = ctx.addArtifact("invoice_lookup", Map.of(), Map.of(),
                "{\"ok\": true, \"" + key + "\": \"value 4711\"}", true,
                new Artifact.Decision(Label.PRIVATE, List.of("credentials (1)")));

        String d = a.describe();
        assertNull(ctx.privateIndex().firstHitIn(d), "the canary finds its own window in: " + d);
        assertTrue(d.contains("use: {{1}}, {{1.ok}}, {{1.#2}}"), d);

        // A ligature unfolds the same way: sixteen of them are a window, fifteen are not.
        assertEquals("#1", ArtifactRef.toField(1, "ﬁ".repeat(16), 1).field());
        assertEquals("ﬁ".repeat(15), ArtifactRef.toField(1, "ﬁ".repeat(15), 1).field());
    }

    @Test
    @DisplayName("one reading of a JSON output: two objects in a row are text to every reader")
    void oneReadingOfAJsonOutput() {
        // A record per host, as a scanner prints them. Read leniently, its first record was the
        // result: that record's ok:false failed the whole call and its fields were offered, while
        // a skill's output was kept whole by a strict reading -- the two disagreed on what an
        // envelope is.
        String records = "{\"ok\": false, \"host\": \"192.0.2.1\"}\n{\"ok\": true, \"host\": \"192.0.2.2\"}";
        var a = new Artifact(1, "net_scan", Map.of(), Map.of(), records, true, Label.PRIVATE,
                List.of("credentials (1)"));

        assertTrue(a.succeeded(), "one record's ok is not the call's");
        assertTrue(a.describe().contains(" · text · "), a.describe());
        assertFalse(References.resolve(Map.of("v", "{{1.host}}"), List.of(a)).ok(),
                "no field of one record resolves");
        assertTrue(References.resolve(Map.of("v", "{{1}}"), List.of(a)).ok());
    }

    @Test
    @DisplayName("booleans are shown in key order")
    void booleansKeepTheirOrder() {
        String json = "{\"ok\": true, \"sent\": false, \"queued\": true, \"retried\": false, "
                + "\"archived\": true, \"flagged\": false}";
        String d = privateResult("smtp_send_email", json, true).describe();
        assertTrue(d.contains(" · ok=true · sent=false · queued=true · retried=false · archived=true"
                + " · flagged=false · "), "the order the skill wrote them in, every time: " + d);
    }

    @Test
    @DisplayName("a name the grammar would read differently is offered by its position too")
    void unreadableNamesAreOfferedByPosition() {
        // Each of these, written after "{{2.", would resolve to some other field or to none.
        String json = "{\" padded \": 1, \"#1\": 2, \"a{{b\": 3, \"\": 4, \"plain\": 5}";
        var refs = Artifact.fieldRefs(2, json);
        assertEquals(List.of("#1", "#2", "#3", "#4", "plain"),
                refs.stream().map(ArtifactRef::field).toList());
        var a = new Artifact(2, "t", Map.of(), Map.of(), json, true, Label.PUBLIC, List.of());
        for (int k = 1; k <= 4; k++) {
            assertEquals(String.valueOf(k), References.resolve(Map.of("v", "{{1.#" + k + "}}"),
                    List.of(a)).params().get("v"), "position " + k);
        }
    }
}
