package com.ownclaw.agent;

import com.ownclaw.agent.tools.ToolResult;
import com.ownclaw.privacy.Label;
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
        var d = Artifact.labelFor(List.of("IMAP_PASS"), false, Map.of(), List.of());
        assertEquals(Label.PRIVATE, d.label());
        assertEquals(List.of("credentials (1)"), d.why(),
                "the COUNT, not the names: the skill harness words a vault miss as 'Missing "
                        + "required credentials: SMTP_PASS, SMTP_USER', so naming them here put "
                        + "a run of the OUTPUT into the descriptor and the canary refused the "
                        + "call that carried it");
    }

    @Test
    @DisplayName("an unattended attachment is covered by reference, not by a separate rule")
    void attachmentsAreCoveredByTheReferenceClause() {
        // There was a taskHasAttachments flag here. It could not fire on any path: files arrive
        // only through attended chat, and an attended attachment artifact is deliberately PUBLIC
        // so "summarise this" still works. What covers the unattended case is this — the
        // attachment artifact is PRIVATE where it is recorded, and anything referencing it is
        // PRIVATE because it is derived from it.
        var attachment = new Artifact(1, "attachment:statement.csv", Map.of(), Map.of(),
                "acct,balance\nCZ4720100123,41200", true, Label.PRIVATE, List.of("attachment"));
        var d = Artifact.labelFor(List.of(), false, Map.of("text", "$1"), List.of(attachment));
        assertEquals(Label.PRIVATE, d.label());
        assertEquals(List.of("references $1"), d.why());
    }

    @Test
    @DisplayName("after a private step, everything the same delegation produces is PRIVATE")
    void taintMakesItPrivate() {
        // The local model has read private content; whatever it writes now — including a
        // public tool's arguments — may carry it.
        var d = Artifact.labelFor(List.of(), true, Map.of(), List.of());
        assertEquals(Label.PRIVATE, d.label());
    }

    @Test
    @DisplayName("a $N reference to a PRIVATE artifact makes the result PRIVATE")
    void referenceToPrivateMakesItPrivate() {
        var store = List.of(privateResult("imap_fetch", "{}", true));
        var d = Artifact.labelFor(List.of(), false, Map.of("body", "$2.body_text"), store);
        assertEquals(Label.PRIVATE, d.label(), "derived from private is private");
        assertTrue(d.why().contains("references $2"));

        var pub = List.of(new Artifact(2, "x", Map.of(), Map.of(), "{}", true, Label.PUBLIC, List.of()));
        assertEquals(Label.PUBLIC,
                Artifact.labelFor(List.of(), false, Map.of("body", "$2"), pub).label(),
                "a reference to a PUBLIC artifact is not a reason");
    }

    @Test
    @DisplayName("a field name the resolver accepts is a reference to the label as well")
    void theLabelSeesEveryFieldNameTheResolverDoes() {
        // resolveRef accepts ANY non-blank field name. While this pattern required word
        // characters, a key with a hyphen or an accent — or the descriptor's own truncated
        // "rendered_html_for_ema…" — moved a PRIVATE artifact's text into the arguments and the
        // result was then labelled PUBLIC, so it went to the cloud as content.
        var store = List.of(privateResult("imap_fetch", "{}", true));   // $2, PRIVATE
        for (String ref : List.of("$2.body-text", "$2.polévka", "$2.Content-Type",
                                  "$2.rendered_html_for_ema…", "$2.menu.body", "$2")) {
            assertEquals(Label.PRIVATE,
                    Artifact.labelFor(List.of(), false, Map.of("body", ref), store).label(),
                    "moving it makes the result derived from it: " + ref);
        }
        assertEquals(Label.PUBLIC,
                Artifact.labelFor(List.of(), false, Map.of("body", "$5.50 Polévka"), store).label(),
                "but a price is not a reference, whatever it looks like");
    }

    @Test
    @DisplayName("with none of the facts, a result is PUBLIC — exactly as today")
    void nothingMakesItPublic() {
        var d = Artifact.labelFor(List.of(), false, Map.of("url", "https://x"), List.of());
        assertEquals(Label.PUBLIC, d.label());
        assertTrue(d.why().isEmpty());
    }

    // ── describe: everything except the content ──

    @Test
    @DisplayName("the descriptor names the handle, the tool, the label, the shape — and no content")
    void descriptorCarriesShapeNotContent() {
        String d = privateResult("smtp_send_email", PRIVATE_JSON, false).describe();

        assertTrue(d.startsWith("$2 smtp_send_email ✗ — PRIVATE (credentials (1))"), d);
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
    @DisplayName("the field list is bounded")
    void fieldListIsBounded() {
        var sb = new StringBuilder("{");
        for (int i = 0; i < 40; i++) sb.append("\"field_number_").append(i).append("\": 1,");
        sb.setLength(sb.length() - 1);
        sb.append("}");
        String d = privateResult("t", sb.toString(), true).describe();
        assertTrue(d.contains("field_number_0"));
        assertTrue(d.length() < 900,
                "bounded by characters, which is what 'a descriptor that has become the "
                        + "content' actually meant: " + d.length() + " chars");
        assertTrue(d.contains("more"), "and it says how many it did not name: " + d);
    }

    // ── asObservation: the substitution point ──

    @Test
    @DisplayName("a PUBLIC result enters the trajectory byte for byte, structured data included")
    void publicIsUnchanged() {
        var r = ToolResult.success("the digest text", Map.of("count", 8));
        var a = new Artifact(1, "daily_news_digest", Map.of(), Map.of(), r.output(), true,
                Label.PUBLIC, List.of());

        var obs = Artifact.asObservation(a, r, 12);
        assertEquals("the digest text", obs.output(), "exactly as today");
        assertEquals(Map.of("count", 8), obs.structured());
        assertTrue(obs.success());
    }

    @Test
    @DisplayName("a PRIVATE result enters the trajectory as its descriptor, with no structured data")
    void privateIsSubstituted() {
        var r = ToolResult.success(PRIVATE_JSON, Map.of("body_text", "confidential"));
        var a = privateResult("smtp_send_email", PRIVATE_JSON, false);

        var obs = Artifact.asObservation(a, r, 12);
        assertEquals(a.describe(), obs.output());
        assertFalse(obs.success(), "the outcome is not hidden with the content");
        assertFalse(obs.output().contains("confidential"));

        // The skill's own structured map is the same content in another shape and never
        // travels. What replaces it is metadata about the artifact -- the shape the delegation
        // already reports -- so a privately-executed direct call is still countable by the
        // withheld line, which otherwise printed nothing and read as "nothing was withheld".
        assertFalse(obs.structured().containsKey("body_text"),
                "the skill's own keys are content and must not survive");
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
        assertDoesNotThrow(() -> Artifact.labelFor(List.of(), false, withNull, List.of()));
    }

    @Test
    @DisplayName("field names cannot carry a 32-character run of the output")
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
    @DisplayName("jsonFieldNames: the real keys of a JSON object, none for anything else")
    void jsonFieldNames() {
        assertEquals(List.of("ok", "body_text"),
                Artifact.jsonFieldNames("{\"ok\": true, \"body_text\": \"text\"}"));
        assertTrue(Artifact.jsonFieldNames("not json").isEmpty());
        assertTrue(Artifact.jsonFieldNames("[1,2]").isEmpty());
        assertTrue(Artifact.jsonFieldNames(null).isEmpty());
    }

    @Test
    @DisplayName("a name the model is told to reference is never truncated")
    void referenceNamesAreNeverTruncated() {
        // The descriptor abbreviates a long key and marks the cut with an ellipsis. Deriving
        // the reference list from it handed the model "$1.rendered_html_for_ema…", which
        // resolves to nothing and is not recognised as a reference either, so it reached the
        // tool as the literal argument.
        String key = "rendered_html_for_email_body_with_inline_css";
        assertTrue(key.length() > Artifact.MAX_FIELD_NAME, "the case only exists above the cut");
        String json = "{\"" + key + "\": \"x\"}";

        assertEquals(List.of(key), Artifact.jsonFieldNames(json),
                "this list is what the model types back; it has to be the real key");
        assertTrue(new Artifact(1, "t", Map.of(), Map.of(), json, true, Label.PRIVATE, List.of())
                        .describe().contains("…"),
                "while the descriptor still bounds what it prints — they differ on purpose");
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

        // Complete tokens, not a template. "pass $1.<field>" beside a list that annotates its
        // names made the cloud compose "$1.body_text (string, 48 chars)", which resolves to
        // nothing — and the literal went out as the body of the email.
        assertTrue(d.contains("use: $1, $1.body_text"), d);
        assertFalse(d.contains("<field>"), "nothing left for the cloud to compose: " + d);
        assertTrue(d.contains("substituted here"), d);
        assertFalse(d.contains("česneková"), "still never the content");
    }

    @Test
    @DisplayName("the reference list is capped, like the descriptor it sits beside")
    void referenceNamesAreCapped() {
        var sb = new StringBuilder("{");
        for (int i = 0; i < 40; i++) sb.append(i > 0 ? "," : "").append("\"f").append(i).append("\":1");
        List<String> names = Artifact.jsonFieldNames(sb.append("}").toString());

        assertEquals(40, names.size(),
                "bounded, but by its own cap. Borrowing the descriptor's MAX_FIELDS hid the "
                        + "thirteenth key from the local model too, and on an imap envelope "
                        + "body_text sits past the twelfth — so the one field the task needed "
                        + "could be named by nobody");
        var wide = new StringBuilder("{");
        for (int i = 0; i < Artifact.MAX_REFERENCE_NAMES + 20; i++) {
            wide.append(i > 0 ? "," : "").append("\"g").append(i).append("\":1");
        }
        assertEquals(Artifact.MAX_REFERENCE_NAMES,
                Artifact.jsonFieldNames(wide.append("}").toString()).size(),
                "and it is still a cap");
    }

    @Test
    @DisplayName("the body of an imap envelope is offered even when it is the twentieth key")
    void theContentIsOfferedFirst() {
        // The reference list is budgeted, so its ORDER decides what gets named. In key order the
        // budget went on the envelope and body_text — past the twelfth key — was never offered;
        // the cloud had nothing to forward and wrote the email from the description instead.
        var sb = new StringBuilder("{");
        String[] envelope = {"from", "to", "cc", "subject", "date", "message_id", "uid", "flags",
                "folder", "size", "seen", "snippet", "has_attachments", "in_reply_to",
                "references", "priority", "thread_id", "labels", "charset"};
        for (String k : envelope) sb.append('"').append(k).append("\":\"x\",");
        sb.append("\"body_text\":\"").append("Polévka dne: česneková. ".repeat(120)).append("\"}");
        String d = privateResult("imap_fetch", sb.toString(), true).describe();

        assertTrue(d.contains("$2.body_text"),
                "the biggest text in the result is what a task forwards: " + d);
        assertTrue(d.indexOf("$2.body_text") < d.indexOf("$2.from"),
                "and it is offered before the envelope, not after it runs the budget out");
        assertFalse(d.contains("česneková"), "still never the content");
    }

    @Test
    @DisplayName("a truncated field name can never be long enough to be a canary window")
    void fieldNamesStayUnderTheCanaryWindow() {
        assertTrue(Artifact.MAX_FIELD_NAME < com.ownclaw.privacy.PrivateIndex.WINDOW,
                "the descriptor prints the field names of a PRIVATE result; if one could reach "
                        + com.ownclaw.privacy.PrivateIndex.WINDOW + " characters it would itself "
                        + "be a window of the private text — the descriptor would leak, and then "
                        + "refuse the call carrying it");
    }
}
