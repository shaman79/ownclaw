package com.ownclaw.agent;

import com.ownclaw.privacy.Label;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The reference grammar, and the two properties the three places that used to parse it kept
 * breaking.
 * <p>
 * The resolver, the label and the refusal each had their own answer to "is this a reference",
 * and every gap between them was a defect: private bytes substituted under a PUBLIC label, or a
 * reference-shaped value passed through as literal text. The properties are tested over a
 * corpus rather than case by case, because the last three rounds of fixes each closed the shapes
 * someone had thought of and left the ones nobody had.
 */
class ArtifactRefTest {

    /** A PRIVATE $1 carrying every key the corpus refers to. */
    private static final String PRIVATE_JSON = "{\"ok\":true,"
            + "\"body_text\":\"SECRET body text\","
            + "\"body-text\":\"SECRET hyphen\","
            + "\"Message body\":\"SECRET with a space\","
            + "\"polévka\":\"SECRET czech\","
            + "\"Content-Type\":\"SECRET header\","
            + "\"rendered_html_for_email_body_with_css\":\"SECRET long name\"}";

    private static final List<Artifact> STORE = List.of(new Artifact(1, "imap_fetch", Map.of(),
            Map.of(), PRIVATE_JSON, true, Label.PRIVATE, List.of("credentials (1)")));

    /** Everything a model might type as a whole argument value. */
    private static final List<String> CORPUS = List.of(
            // references that should resolve
            "$1", "$1.body_text", "$1.body-text", "$1.Message body", "$1.polévka",
            "$1.Content-Type", "$1.rendered_html_for_email…", " $1.body_text ",
            // references that should not resolve, and so must be refused
            "$1.no_such_field", "$1.body_text (string, 48 chars)", "$1.o", "$1.body",
            "$1.Message", "$2.body_text",
            // not references: prices, and handles written in ways that are not ours
            "$5.50", "$1.234,56", "$5.00/kg", "$1.99!", "$50", "$0", "$", "$.body",
            "$1.", "$+1", "$١", "$１", "$99999999999999999999", "$2147483648",
            // prose
            "the price is $50 today", "$5.50 Polévka\nHlavní chod", "Menu", "");

    @Test
    @DisplayName("PROPERTY: if private content moved, the result is labelled PRIVATE")
    void movedContentIsAlwaysPrivate() {
        for (String v : CORPUS) {
            var written = Map.<String, Object>of("body", v);
            var resolved = assertDoesNotThrow(() -> LocalExecutor.substituteRefs(written, STORE),
                    "the resolver threw on: " + v);
            boolean moved = !v.equals(resolved.get("body"));
            var label = assertDoesNotThrow(() -> Artifact.labelFor(List.of(), false, written, STORE),
                    "the label threw on " + v + " — and on the attended path it runs after the "
                            + "tool, so the email went and the task died").label();
            if (moved) {
                assertEquals(Label.PRIVATE, label, "private bytes moved into the arguments and "
                        + "the result was labelled PUBLIC, so it goes to the cloud as content: " + v);
            }
        }
    }

    @Test
    @DisplayName("PROPERTY: a reference is either substituted or refused — never sent as text")
    void aReferenceIsNeverPassedThroughLiterally() {
        for (String v : CORPUS) {
            var resolved = LocalExecutor.substituteRefs(Map.of("body", v), STORE);
            boolean moved = !v.equals(resolved.get("body"));
            String refused = assertDoesNotThrow(
                    () -> LocalExecutor.unresolvedRef(resolved, STORE.size()),
                    "the refusal threw on: " + v);
            ArtifactRef ref = ArtifactRef.parse(v);
            if (ref == null) {
                assertNull(refused, "not a reference, so not refused: " + v);
                continue;
            }
            // A bare handle out of range is a dollar amount by design; everything else that
            // parses as a reference must go one way or the other.
            if (!ref.hasField() && ref.handle() > STORE.size()) continue;
            assertTrue(moved || refused != null,
                    "reference-shaped, neither substituted nor refused — it goes out as the "
                            + "literal body of an email and the run is recorded green: " + v);
        }
    }

    @Test
    @DisplayName("the shapes that leaked, each pinned by name")
    void theShapesThatLeaked() {
        // Substituted by the resolver, labelled PUBLIC by a narrower pattern.
        assertEquals(new ArtifactRef(1, "Message body"), ArtifactRef.parse("$1.Message body"));
        assertEquals(new ArtifactRef(1, "polévka"), ArtifactRef.parse("$1.polévka"));
        // parseInt accepts these; the label's \d did not. Not references at all now.
        assertNull(ArtifactRef.parse("$+1"));
        assertNull(ArtifactRef.parse("$١"), "Arabic-Indic one");
        assertNull(ArtifactRef.parse("$１"), "fullwidth one");
        // Overflowed parseInt and threw out of the guard, after the tool had run.
        assertNull(assertDoesNotThrow(() -> ArtifactRef.parse("$99999999999999999999")));
        assertNull(ArtifactRef.parse("$2147483648"));
        // A field that starts with a digit is a price.
        for (String price : List.of("$5.50", "$1.234,56", "$5.00/kg", "$1.99!", "$3.50.")) {
            assertNull(ArtifactRef.parse(price), price);
        }
        // What the cloud copies out of an annotated field list is still a reference — so it is
        // refused, visibly, instead of being sent.
        assertEquals(new ArtifactRef(1, "body_text (string, 48 chars)"),
                ArtifactRef.parse("$1.body_text (string, 48 chars)"));
    }

    @Test
    @DisplayName("a name the descriptor did not cut is never matched by prefix")
    void prefixMatchingIsOnlyForCutNames() {
        // On every miss, prefix matching turned a visible refusal into a silent wrong answer:
        // "$1.body" took body_html, "$1.o" took ok, and the email's whole body was "true".
        var done = List.of(new Artifact(1, "render", Map.of(), Map.of(),
                "{\"ok\":true,\"body_html\":\"<html>PRIVATE</html>\"}", true, Label.PRIVATE, List.of()));
        for (String v : List.of("$1.body", "$1.b", "$1.o")) {
            assertEquals(v, LocalExecutor.substituteRefs(Map.of("body", v), done).get("body"),
                    "must stay unresolved, and so be refused: " + v);
            assertEquals("body", LocalExecutor.unresolvedRef(Map.of("body", v), 1), v);
        }
        assertEquals("<html>PRIVATE</html>", LocalExecutor.substituteRefs(
                Map.of("body", "$1.body_h…"), done).get("body"), "but a cut name still resolves");
    }
}
