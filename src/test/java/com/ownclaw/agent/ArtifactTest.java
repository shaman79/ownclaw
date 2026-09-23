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
                List.of("credentials: SMTP_PASS"));
    }

    // ── labelFor: every clause on its own ──

    @Test
    @DisplayName("a skill that declared credentials produces a PRIVATE result")
    void credentialsMakeItPrivate() {
        var d = Artifact.labelFor(List.of("IMAP_PASS"), false, false, Map.of(), List.of());
        assertEquals(Label.PRIVATE, d.label());
        assertEquals(List.of("credentials: IMAP_PASS"), d.why(),
                "the descriptor and the ledger say which fact decided it");
    }

    @Test
    @DisplayName("a task given files produces PRIVATE results")
    void attachmentsMakeItPrivate() {
        var d = Artifact.labelFor(List.of(), true, false, Map.of(), List.of());
        assertEquals(Label.PRIVATE, d.label());
        assertTrue(d.why().contains("attachment"));
    }

    @Test
    @DisplayName("after a private step, everything the same delegation produces is PRIVATE")
    void taintMakesItPrivate() {
        // The local model has read private content; whatever it writes now — including a
        // public tool's arguments — may carry it.
        var d = Artifact.labelFor(List.of(), false, true, Map.of(), List.of());
        assertEquals(Label.PRIVATE, d.label());
    }

    @Test
    @DisplayName("a $N reference to a PRIVATE artifact makes the result PRIVATE")
    void referenceToPrivateMakesItPrivate() {
        var store = List.of(privateResult("imap_fetch", "{}", true));
        var d = Artifact.labelFor(List.of(), false, false, Map.of("body", "$2.body_text"), store);
        assertEquals(Label.PRIVATE, d.label(), "derived from private is private");
        assertTrue(d.why().contains("references $2"));

        var pub = List.of(new Artifact(2, "x", Map.of(), Map.of(), "{}", true, Label.PUBLIC, List.of()));
        assertEquals(Label.PUBLIC,
                Artifact.labelFor(List.of(), false, false, Map.of("body", "$2"), pub).label(),
                "a reference to a PUBLIC artifact is not a reason");
    }

    @Test
    @DisplayName("with none of the facts, a result is PUBLIC — exactly as today")
    void nothingMakesItPublic() {
        var d = Artifact.labelFor(List.of(), false, false, Map.of("url", "https://x"), List.of());
        assertEquals(Label.PUBLIC, d.label());
        assertTrue(d.why().isEmpty());
    }

    // ── describe: everything except the content ──

    @Test
    @DisplayName("the descriptor names the handle, the tool, the label, the shape — and no content")
    void descriptorCarriesShapeNotContent() {
        String d = privateResult("smtp_send_email", PRIVATE_JSON, false).describe();

        assertTrue(d.startsWith("$2 smtp_send_email ✗ — PRIVATE (credentials: SMTP_PASS)"), d);
        assertTrue(d.contains("json"), d);
        assertTrue(d.contains("ok=false"),
                "a success envelope around a failure is the normal shape of a skill result; a "
                        + "descriptor that hid ok=false would have the cloud report a send that "
                        + "never happened");
        assertTrue(d.contains("count=3"), "numbers are shown with their values");
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
        assertFalse(d.contains("field_number_" + (Artifact.MAX_FIELDS + 1)),
                "forty field names is a descriptor that has become the content");
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
        assertTrue(obs.structured().isEmpty(),
                "the structured map is the same content in another shape");
        assertFalse(obs.success(), "the outcome is not hidden with the content");
        assertFalse(obs.output().contains("confidential"));
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
    @DisplayName("jsonFields is the one parser: names of a JSON object, none for anything else")
    void jsonFields() {
        assertEquals(List.of("ok", "body_text (string, 4 chars)"),
                Artifact.jsonFields("{\"ok\": true, \"body_text\": \"text\"}"));
        assertTrue(Artifact.jsonFields("not json").isEmpty());
        assertTrue(Artifact.jsonFields("[1,2]").isEmpty());
        assertTrue(Artifact.jsonFields(null).isEmpty());
    }
}
