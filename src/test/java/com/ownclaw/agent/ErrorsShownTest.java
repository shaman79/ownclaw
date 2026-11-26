package com.ownclaw.agent;

import com.ownclaw.privacy.Label;
import com.ownclaw.privacy.PrivateIndex;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * In a task holding a private file every result is private, and a skill that failed there used to
 * show the cloud no error at all: it asked the local model which kind of error it was, three steps
 * of guessing (2026-11-25). The owner's decision of 2026-11-26: a failed tool's error may be shown.
 * Its error, never its output; never one that repeats a private result of the task; and only where
 * the file alone makes the result private. And what the cloud was shown, or what stands in a
 * quoted skill's own code, does not refuse that skill's repair.
 */
class ErrorsShownTest {

    private static final String ERROR = "tesseract is not installed or it's not in your PATH. See README file for more information.";

    /** A task holding a private text file. */
    private static AgentContext withFile(String text) {
        var ctx = new AgentContext("u1", "t1", "Read the price list.");
        ctx.addFile("f1", text, List.of("uploaded file", "text/csv, 120 bytes"));
        return ctx;
    }

    private static Artifact record(AgentContext ctx, String tool, String output, Artifact.Decision d) {
        return ctx.addArtifact(tool, Map.of(), Map.of(), output, false, d);
    }

    private static boolean excused(AgentContext ctx, int handle, String stretch) {
        return ctx.egress("test").allowed().test(handle, stretch);
    }

    @Test
    @DisplayName("a failed skill in a file task: still private, its error shown beside the descriptor, its output never")
    void theErrorIsShown() {
        var ctx = withFile("Položka;Cena\nObědové menu;155 Kč\n");
        String output = "{\"success\": false, \"error\": \"" + ERROR + "\", \"rows\": \"Obědové menu;155 Kč\"}";
        var d = ctx.decide(List.of(), List.of(), false, output, ERROR);
        assertEquals(Label.PRIVATE, d.label());
        assertEquals(ERROR, d.shownError());
        var a = record(ctx, "ocr_image_to_text", output, d);
        String described = a.describe();
        assertTrue(described.endsWith("\n[its error, shown; its output is not]\n" + ERROR), described);
        assertFalse(described.contains("155"), "nothing of its output: " + described);
        assertTrue(described.contains("given the file {{1}}"), described);
        // Mutation: return the file branch's decision without the error -> not shown.
    }

    @Test
    @DisplayName("an error that repeats the file, or a result the task read, is withheld, and the descriptor says so")
    void anErrorThatRepeatsPrivateTextIsWithheld() {
        String row = "Obědové menu s polévkou a dezertem;155 Kč;denně";
        var ctx = withFile("Položka;Cena;Kdy\n" + row + "\n");
        String error = "ValueError: could not convert string to float: '" + row + "'";
        var d = ctx.decide(List.of(), List.of(), false, "ERROR: " + error, error);
        assertNull(d.shownError(), "it quotes the file");
        assertTrue(d.why().contains("its error repeats {{1}}"), String.valueOf(d.why()));

        // A PDF's text, read by an earlier skill: private, unindexed -- and still never repeated.
        var pdf = new AgentContext("u1", "t1", "Read the contract.");
        pdf.addFile("f1", null, List.of("uploaded file", "application/pdf, 9000 bytes"));
        String clause = "The tenant pays 18 400 CZK monthly to account of the landlord";
        record(pdf, "pdf_extract_text", clause, pdf.decide(List.of(), List.of(), false, clause));
        var e = pdf.decide(List.of(), List.of(), false, "boom", "KeyError: '" + clause + "'");
        assertNull(e.shownError());
        assertTrue(e.why().contains("its error repeats {{2}}"), String.valueOf(e.why()));
    }

    @Test
    @DisplayName("no error is shown where more than the file makes a result private, nor of a success, nor without a file")
    void onlyTheFilesReason() {
        var tainted = withFile("a;b\n");
        assertNull(tainted.decide(List.of(), List.of(), true, "x", ERROR).shownError(),
                "written after the local model read private data: its error can carry it");
        var personal = withFile("a;b\n");
        assertNull(personal.decide(List.of("IMAP_PASS"), List.of(), false, "x", ERROR).shownError(),
                "a mail reader's error is the mail's");
        var used = withFile("a;b\n");
        var priv = record(used, "imap_fetch", "mail", new Artifact.Decision(Label.PRIVATE, List.of("personal source")));
        assertNull(used.decide(List.of(), List.of(priv), false, "x", ERROR).shownError(), "it pulled in a private result");
        var ok = withFile("a;b\n");
        assertNull(ok.decide(List.of(), List.of(), false, "fine", null).shownError(), "a success has none");
        var none = new AgentContext("u1", "t1", "Fix the skill.");
        var d = none.decide(List.of(), List.of(), false, "ERROR: " + ERROR, ERROR);
        assertEquals(Label.PUBLIC, d.label(), "without a file the result is shown whole, as before");
        assertNull(d.shownError());
    }

    @Test
    @DisplayName("what the cloud was shown of an error is no leak when the local model's answer quotes it later")
    void aShownErrorExcusesItsQuote() {
        var ctx = withFile("Položka;Cena\nObědové menu;155 Kč\n");
        var failed = record(ctx, "ocr_image_to_text", "ERROR: " + ERROR, ctx.decide(List.of(), List.of(), false,
                "ERROR: " + ERROR, ERROR));
        // The answer quotes the failure, which is private and indexed on a file task.
        var answer = record(ctx, "local_answer", "The skill failed: " + ERROR,
                new Artifact.Decision(Label.PRIVATE, List.of(Artifact.quotes(failed.n())), true));
        String stretch = PrivateIndex.normalise(ERROR).substring(0, 40);
        assertTrue(excused(ctx, answer.n(), stretch), "the cloud was shown it, before the answer was written");

        // One the cloud was not shown -- it repeated the file -- excuses nothing.
        var other = withFile("tesseract is not installed or it's not in your PATH. See README\n");
        var withheld = record(other, "ocr_image_to_text", "ERROR: " + ERROR,
                other.decide(List.of(), List.of(), false, "ERROR: " + ERROR, ERROR));
        assertNull(withheld.shownError());
        var quoting = record(other, "local_answer", "The skill failed: " + ERROR,
                new Artifact.Decision(Label.PRIVATE, List.of(Artifact.quotes(withheld.n())), true));
        assertFalse(excused(other, quoting.n(), stretch));
        // Mutation: excuse shown errors after the hit too -> a later failure's error excuses an earlier answer.
    }

    @Test
    @DisplayName("a local answer quoting a skill's error is no leak of the skill's own code: its repair request carries that code")
    void theQuotedSkillsSourceExcusesIt() {
        var ctx = new AgentContext("u1", "t1", "Read my mail.");
        ctx.setSkillSource(tool -> "ocr_image_to_text".equals(tool)
                ? "def run(params):\n    raise RuntimeError(\"" + ERROR + "\")\n" : null);
        // A mail reader's failure: private for what it is, so no error is shown.
        var failed = record(ctx, "ocr_image_to_text", "ERROR: " + ERROR,
                new Artifact.Decision(Label.PRIVATE, List.of("personal source")));
        var answer = record(ctx, "local_answer", "It failed with: " + ERROR,
                new Artifact.Decision(Label.PRIVATE, List.of(Artifact.quotes(failed.n())), true));
        String inCode = PrivateIndex.normalise(ERROR).substring(0, 40);
        assertTrue(excused(ctx, answer.n(), inCode), "the literal of skill.py the answer quotes");
        assertFalse(excused(ctx, answer.n(), PrivateIndex.normalise("It failed with: tesseract is not installed")),
                "the answer's own words around it are not the code's");

        // An answer that quotes a result of another skill, whose code has no such line: no excuse.
        var other = new AgentContext("u1", "t1", "Read my mail.");
        other.setSkillSource(tool -> "ocr_image_to_text".equals(tool)
                ? "def run(params):\n    raise RuntimeError(\"" + ERROR + "\")\n" : null);
        var mail = record(other, "imap_fetch", "ERROR: " + ERROR,
                new Artifact.Decision(Label.PRIVATE, List.of("personal source")));
        var quotesMail = record(other, "local_answer", "It failed with: " + ERROR,
                new Artifact.Decision(Label.PRIVATE, List.of(Artifact.quotes(mail.n())), true));
        assertFalse(excused(other, quotesMail.n(), inCode), "the code quoted is not the quoted result's skill's");
        assertEquals(List.of(mail.n()), quotesMail.quoted());
        // Mutation: drop the quoted loop in Excuses -> the first is refused, as on 2026-11-25.
    }

    @Test
    @DisplayName("the descriptor that shows the error is no leak of the local answer that quotes it after a colon: the gate scans the real text")
    void theDescriptorPassesTheGate() {
        var ctx = withFile("Položka;Cena\nObědové menu;155 Kč\n");
        var failed = record(ctx, "ocr_image_to_text", "ERROR: " + ERROR,
                ctx.decide(List.of(), List.of(), false, "ERROR: " + ERROR, ERROR));
        record(ctx, "local_answer", "The OCR skill failed with this error: " + ERROR + " So I could not read the price list.",
                new Artifact.Decision(Label.PRIVATE, List.of(Artifact.quotes(failed.n())), true));
        assertNull(ctx.firstLeakIn(failed.describe()), failed.describe());
        assertNull(ctx.firstLeakIn("Step 2:\n" + failed.describe() + "\nStep 3: the local model answered."));
        // Mutation: put the error inline after a colon, and excuse only the stretch as found -> a hit
        // on ": tesseract is not installed or ", and every later request is refused.
    }

    @Test
    @DisplayName("the same failure twice: its error is shown the second time too")
    void theSameErrorTwice() {
        var ctx = withFile("Položka;Cena\nObědové menu;155 Kč\n");
        record(ctx, "ocr_image_to_text", "ERROR: " + ERROR, ctx.decide(List.of(), List.of(), false, "ERROR: " + ERROR, ERROR));
        var again = ctx.decide(List.of(), List.of(), false, "ERROR: " + ERROR, ERROR);
        assertEquals(ERROR, again.shownError(), String.valueOf(again.why()));
        // Mutation: check shown errors before the hit only -> "its error repeats {{2}}".
    }

    @Test
    @DisplayName("the handles a why says are quoted are read back exactly as recordAnswer writes them")
    void quotesRoundTrip() {
        assertEquals("quotes {{7}}", Artifact.quotes(7));
        var a = new Artifact(3, "local_answer", Map.of(), Map.of(), "x", true, Label.PRIVATE,
                List.of("references {{2}}", Artifact.quotes(1), "quotes {{x}}", "quotes {{12}} and more"), true);
        assertEquals(List.of(1), a.quoted());
    }
}
