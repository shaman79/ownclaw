package com.ownclaw.agent;

import com.ownclaw.privacy.Label;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The reference grammar and the properties its three old copies kept breaking.
 * <p>
 * The corpus is split BY HAND into what a model plausibly meant as a reference and what it did
 * not. The previous version of this test let the parser under test decide which was which, so
 * any shape the grammar rejected was, by definition, never checked — and that is exactly where
 * the literal-text sends were.
 */
class ArtifactRefTest {

    /** A PRIVATE {{1}} with every awkward key a generated skill has produced. */
    private static final String PRIVATE_JSON = "{\"ok\":true,"
            + "\"body_text\":\"SECRET body text\","
            + "\"body-text\":\"SECRET hyphen\","
            + "\"Message body\":\"SECRET with a space\","
            + "\"polévka\":\"SECRET czech\","
            + "\"2fa_code\":\"SECRET digit-first\","
            + "\"2026-09-24\":\"SECRET date key\","
            + "\"24h_report\":\"SECRET 24h\","
            + "\"rendered_html_for_email_body_with_css\":\"SECRET long name\"}";

    private static final List<Artifact> NAMESPACE = List.of(
            new Artifact(1, "imap_fetch", Map.of(), Map.of(), PRIVATE_JSON, true, Label.PRIVATE,
                    List.of("credentials (1)")),
            new Artifact(2, "daily_menu_fetcher", Map.of(), Map.of(),
                    "ERROR: Traceback: AttributeError: 'NoneType' object has no attribute 'text'",
                    false, Label.PUBLIC, List.of()));

    /** Everything here was meant as a reference: each must be substituted or refused. */
    private static final List<String> ATTEMPTS = List.of(
            "{{1}}", "{{1.body_text}}", "{{1.body-text}}", "{{1.Message body}}", "{{1.polévka}}",
            "{{1.2fa_code}}", "{{1.2026-09-24}}", "{{1.24h_report}}", "{{1.rendered_html_for_e…}}",
            " {{1.body_text}} ", "{{ 1.body_text }}", "{{$1.body_text}}", "​{{1}}", "﻿{{1}}",
            " {{1}} ",
            // must be refused: dangling, missing field, failed result, not the whole value
            "{{3}}", "{{0}}", "{{1.no_such_field}}", "{{1.body_text (string, 16 chars)}}",
            "{{1.body}}", "{{2}}", "{{2.text}}", "{{1234567890}}",
            "\"{{1.body_text}}\"", "`{{1}}`", "'{{1.body_text}}'",
            "{{١}}", "{{１}}",
            // slips: nothing but references, braces and punctuation (round 7)
            "{{{1}}}", "{{1}}}", "{{1}}.", "{{1}}{{2}}", "{{1}} {{1}}", "{{1}}\n\n{{2}}",
            "{{1}}.body_text", "{{1:body_text}}", "**{{1}}**", "“{{1}}”",
            // the old form naming a field result 1 really has
            "$1.body_text", "$1.polévka");

    /** Nothing here was meant as a reference: each must pass through untouched. */
    private static final List<String> NOT_ATTEMPTS = List.of(
            "$50", "$5.50", "$1.99", "$1.234,56", "$5.00/kg", "$1", "$2", "the price is $50 today",
            "$5.50 Polévka\nHlavní chod", "petr@example.com", "Menu", "", "{name}", "{{name}}",
            "{{ user.email }}", "Hello {{name}}!", "#1", "@1", "{1}", "echo \"$1\" | wc -c",
            // code and templates that contain braces and a digit -- round 6 found every one of
            // these refused, with no way to send them
            "rf\"\\d{{4}}\"", "\\frac{{1}}{{2}}", "int a[2][2] = {{1,2},{3,4}};", "{{1,2},{3,4}}",
            "Hello {{1}}, your order {{2}} ships today", "{{ 0 if is_state('x','on') else 1 }}",
            "rename 's/(.*)\\.jpeg$/$1.jpg/'",
            // a reference INSIDE text is text (the known limit that buys the above)
            "Menu: {{1.body_text}}", "Dobré ráno!\n\n{{1.body_text}}",
            // the old $ form is text unless it names a real field of a real result
            "$1.jpg", "$1.no_such_key", "$12.polévka", "$20.Thanks");

    @Test
    @DisplayName("PROPERTY: every reference attempt is substituted or refused — none goes out as text")
    void everyAttemptIsSubstitutedOrRefused() {
        for (String v : ATTEMPTS) {
            var r = assertDoesNotThrow(() -> References.resolve(Map.of("body", v), NAMESPACE),
                    "threw on: " + v);
            boolean substituted = r.ok() && !v.equals(r.params().get("body"));
            assertTrue(substituted || !r.ok(),
                    "passed through as literal text — it would be the body of an email, sent and "
                            + "recorded green: " + v);
        }
    }

    @Test
    @DisplayName("PROPERTY: nothing that is not a reference is touched or refused")
    void nonAttemptsPassThrough() {
        for (String v : NOT_ATTEMPTS) {
            var r = References.resolve(Map.of("body", v), NAMESPACE);
            assertTrue(r.ok(), "refused a value that is not a reference: " + v + " — " + r.reason());
            assertEquals(v, r.params().get("body"), "changed a value that is not a reference: " + v);
        }
    }

    @Test
    @DisplayName("PROPERTY: if private content moved, the label says PRIVATE")
    void movedContentIsLabelledPrivate() {
        for (String v : ATTEMPTS) {
            var r = References.resolve(Map.of("body", v), NAMESPACE);
            if (!r.ok() || v.equals(r.params().get("body"))) continue;
            assertEquals(Label.PRIVATE, Artifact.labelFor(List.of(), r.used()).label(),
                    "private bytes moved and the result was labelled PUBLIC: " + v);
        }
    }

    @Test
    @DisplayName("keys that start with a digit are fields, not prices — the marker makes it so")
    void digitLeadingKeysResolve() {
        // With "$1.2fa_code" these were read as prices and went out as literal text, while the
        // descriptor offered exactly these tokens. Nothing about "{{" is a price.
        for (String key : List.of("2fa_code", "2026-09-24", "24h_report")) {
            var r = References.resolve(Map.of("body", "{{1." + key + "}}"), NAMESPACE);
            assertTrue(r.ok(), key + ": " + r.reason());
            assertTrue(String.valueOf(r.params().get("body")).startsWith("SECRET"), key);
        }
    }

    @Test
    @DisplayName("a failed result is never passed on — its output is an error message")
    void failedResultsAreRefused() {
        // What the second delegation of the 23 September run forwarded as the morning email.
        var r = References.resolve(Map.of("body", "{{2}}"), NAMESPACE);
        assertFalse(r.ok());
        assertTrue(r.reason().contains("FAILED"), r.reason());
    }

    @Test
    @DisplayName("quotes or backticks around a reference are forgiven")
    void quotedReferencesResolve() {
        for (String v : List.of("\"{{1.body_text}}\"", "`{{1.body_text}}`", "'{{1.body_text}}'")) {
            var r = References.resolve(Map.of("body", v), NAMESPACE);
            assertTrue(r.ok(), v + ": " + r.reason());
            assertEquals("SECRET body text", r.params().get("body"), v);
        }
    }

    @Test
    @DisplayName("succeeded() reads the whole top level, booleans and the string \"false\"")
    void succeededReadsTheWholeEnvelope() {
        var sb = new StringBuilder("{");
        for (int i = 0; i < 15; i++) sb.append("\"k").append(i).append("\":1,");
        String late = sb.append("\"ok\":false}").toString();
        for (String out : List.of(late, "{\"ok\":\"false\"}", "{\"success\":false}", "{\"ok\":false}")) {
            assertFalse(new Artifact("t", Map.of(), out, true).succeeded(), out);
        }
        for (String out : List.of("{\"ok\":true}", "plain text", "[1,2]", "{\"status\":\"fine\"}")) {
            assertTrue(new Artifact("t", Map.of(), out, true).succeeded(), out);
        }
        assertFalse(new Artifact("t", Map.of(), "{\"ok\":true}", false).succeeded(), "the harness flag still counts");
    }

    @Test
    @DisplayName("an ok:false envelope is a failed result: never forwarded, never offered")
    void anOkFalseResultIsAFailure() {
        var smtpError = new Artifact(1, "smtp_send_email", Map.of(), Map.of(),
                "{\"ok\": false, \"error\": \"SMTP connection error: timed out\"}", true,
                Label.PRIVATE, List.of("credentials (1)"));
        assertFalse(smtpError.succeeded(), "the harness said success; the skill said it failed");
        assertFalse(References.resolve(Map.of("body", "{{1}}"), List.of(smtpError)).ok(),
                "an error envelope is not something to send on");
        assertFalse(smtpError.describe().contains("use:"),
                "and the descriptor does not offer a token the resolver would refuse");
        assertTrue(smtpError.describe().contains("✗"), smtpError.describe());
    }

    @Test
    @DisplayName("a reference inside a list or an object is refused, not left as text")
    void nestedReferencesAreRefused() {
        var r = References.resolve(Map.of("messages", List.of("{{1.body_text}}"),
                "meta", Map.of("caption", "{{1}}")), NAMESPACE);
        assertFalse(r.ok(), "a reviewer's probe sent {messages=[$1.body_text]} and recorded it green");
    }

    @Test
    @DisplayName("resolved content that happens to contain a reference is not examined again")
    void resolvedContentIsNotRefused() {
        // The refusal used to run on the SUBSTITUTED values, so a menu whose text began like a
        // reference was refused as a dangling one after a perfectly correct forward.
        var ns = List.of(new Artifact(1, "menu", Map.of(), Map.of(),
                "{\"body\":\"{{2}} Specials today: goulash\"}", true, Label.PUBLIC, List.of()));
        var r = References.resolve(Map.of("body", "{{1.body}}"), ns);
        assertTrue(r.ok(), r.reason());
        assertEquals("{{2}} Specials today: goulash", r.params().get("body"));
    }

    @Test
    @DisplayName("a name the descriptor did not cut is never matched by prefix")
    void prefixMatchingIsOnlyForCutNames() {
        var ns = List.of(new Artifact(1, "render", Map.of(), Map.of(),
                "{\"ok\":true,\"body_html\":\"<html>PRIVATE</html>\"}", true, Label.PRIVATE, List.of()));
        for (String v : List.of("{{1.body}}", "{{1.b}}", "{{1.o}}")) {
            assertFalse(References.resolve(Map.of("body", v), ns).ok(),
                    "\"$1.o\" once matched ok, and the email's whole body was \"true\": " + v);
        }
        assertEquals("<html>PRIVATE</html>",
                References.resolve(Map.of("body", "{{1.body_h…}}"), ns).params().get("body"));
    }

    @Test
    @DisplayName("a reference wrapped in invisible spacing still resolves — it is not merely refused")
    void invisibleSpacingIsNotAnError() {
        // Refusing would be safe, and wrong: the model wrote the right reference, a copy-paste
        // or a tokenizer added a zero-width space, and the refusal it gets back says "a reference
        // has to be the whole value" about a value that visibly is. String.strip leaves these.
        for (String v : List.of("\u200B{{1.body_text}}", "\uFEFF{{1.body_text}}",
                "\u00A0{{1.body_text}}\u00A0", "{{1.body_text}}\u2060")) {
            var r = References.resolve(Map.of("body", v), NAMESPACE);
            assertTrue(r.ok(), "refused: " + r.reason());
            assertEquals("SECRET body text", r.params().get("body"));
        }
    }

    @Test
    @DisplayName("the grammar itself")
    void grammar() {
        assertEquals(new ArtifactRef(1, null), ArtifactRef.parse("{{1}}"));
        assertEquals(new ArtifactRef(12, "Message body"), ArtifactRef.parse(" {{ 12 . Message body }} "));
        assertEquals(new ArtifactRef(3, "x"), ArtifactRef.parse("{{$3.x}}"));
        assertNull(ArtifactRef.parse("{{0}}"));
        assertNull(ArtifactRef.parse("{{1234567890}}"), "more than nine digits would overflow");
        assertNull(ArtifactRef.parse("{{١}}"), "ASCII digits only");
        assertNull(ArtifactRef.parse("{{1.}}"));
        assertNull(ArtifactRef.parse("$1"), "the old syntax is not a reference any more");
        assertEquals("{{4.body_text}}", new ArtifactRef(4, "body_text").toString());
        assertEquals("{{4}}", ArtifactRef.handle(4));
    }
}
