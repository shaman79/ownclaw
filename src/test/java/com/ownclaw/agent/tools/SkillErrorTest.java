package com.ownclaw.agent.tools;

import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.sandbox.JailTestKit;
import com.ownclaw.sandbox.SandboxManager;
import com.ownclaw.sandbox.SandboxResult;
import com.ownclaw.skills.PythonEnvironmentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A failed skill's error, as the cloud may be shown it where its output is private because of a
 * file the task holds (ToolResult#error): what the skill said went wrong and what it wrote to
 * stderr -- never what it printed as output, the data it read -- and no name of a file it was
 * handed.
 */
class SkillErrorTest {

    private static final String DATA = "Obědové menu;155 Kč;Jana Nováková";

    private static ToolResult run(Path tmp, SandboxResult answer) throws Exception {
        SandboxManager host = new SandboxManager() {
            @Override
            public SandboxResult execute(String python, Path script, Path dir, String stdin, Map<String, String> env,
                                         int timeout, ProgressCallback progress, List<Path> readOnly) {
                return answer;
            }

            @Override
            public Optional<String> unavailable() {
                return Optional.empty();
            }
        };
        Path dir = Files.createDirectories(tmp.resolve("skills/price_list"));
        Files.writeString(dir.resolve("skill.py"), "def run(params):\n    return {}\n");
        var skill = new DynamicSkill("price_list", "reads a price list", Map.of(), dir, false, false, 30, host,
                new PythonEnvironmentService(new OwnClawConfig(), JailTestKit.off()), List.of(), null, List.of(), null,
                null, null);
        return skill.execute(Map.of(), new ToolExecutionContext("u1", "t1", null, () -> false, null, List.of()));
    }

    @Test
    @DisplayName("a crash: its stderr is the error; what it printed to stdout is in the output only")
    void aCrash(@TempDir Path tmp) throws Exception {
        String trace = "Traceback (most recent call last):\n  File \"skill.py\", line 9, in run\nValueError: no price column";
        var r = run(tmp, new SandboxResult(1, DATA, trace, 1, false));
        assertFalse(r.success());
        assertTrue(r.output().contains(DATA), "the output keeps everything");
        assertEquals(trace, r.error());
        var silent = run(tmp, new SandboxResult(3, DATA, "", 1, false));
        assertEquals("Exit code: 3", silent.error());
    }

    @Test
    @DisplayName("a failure the skill returned: its own error and its stderr, never the data beside them")
    void aReturnedFailure(@TempDir Path tmp) throws Exception {
        String line = "{\"success\": false, \"error\": \"tesseract is not installed\", \"rows\": \"" + DATA + "\"}";
        var r = run(tmp, new SandboxResult(0, "reading " + DATA + "\n" + line, "WARNING: no tessdata", 1, false));
        assertFalse(r.success());
        assertEquals("tesseract is not installed\n[stderr: WARNING: no tessdata]", r.error());
        assertFalse(r.error().contains("155"));
        var said = run(tmp, new SandboxResult(0, "{\"success\": true, \"output\": \"ERROR: no price column\"}", "", 1, false));
        assertFalse(said.success());
        assertEquals("ERROR: no price column", said.error(), "its own ERROR: line");
        var bare = run(tmp, new SandboxResult(0, "{\"success\": false, \"output\": \"" + DATA + "\"}", "", 1, false));
        assertNull(bare.error(), "nothing said of its own: no error to show, and never its output");
    }

    @Test
    @DisplayName("only the first line of the skill's own words; what it printed never, not even after ERROR:")
    void firstLineAndNothingPrinted(@TempDir Path tmp) throws Exception {
        var printed = run(tmp, new SandboxResult(0, "ERROR: could not parse the list\n" + DATA + "\n{\"output\": \"\", \"success\": true}",
                "", 1, false));
        assertFalse(printed.success());
        assertNull(printed.error(), "it returned nothing of its own: what it printed is no error to show");
        var lines = run(tmp, new SandboxResult(0, "{\"success\": true, \"output\": \"ERROR: bad row\\n" + DATA + "\"}", "", 1, false));
        assertEquals("ERROR: bad row", lines.error());
        var field = run(tmp, new SandboxResult(0, "{\"success\": false, \"error\": \"cannot parse\\n" + DATA + "\"}", "", 1, false));
        assertEquals("cannot parse", field.error());
        // A skill that returns text under the runner's prefix is no runner's account: its first line is not even taken.
        var posing = run(tmp, new SandboxResult(0, "{\"success\": false, \"output\": \"Skill error: could not parse\\n" + DATA + "\"}", "", 1, false));
        assertNull(posing.error(), String.valueOf(posing.error()));
        var echoed = run(tmp, new SandboxResult(1, "reading " + DATA, "ValueError: bad line 'reading " + DATA + "'", 1, false));
        assertFalse(echoed.error().contains("155"), echoed.error());
        assertTrue(echoed.error().contains("[printed output]"), echoed.error());
        var dict = run(tmp, new SandboxResult(1, "{'name': 'Jana Nováková', 'rc': '8501011234'}",
                "Invalid record: {'name': 'Jana Nováková', 'rc': '8501011234'}", 1, false));
        assertFalse(dict.error().contains("8501011234"), "a printed dict is printed output too: " + dict.error());
        var nested = run(tmp, new SandboxResult(1, "Jana Nováková\nJana Nováková;8501011234;18 400 Kč;Praha",
                "bad line 'Jana Nováková;8501011234;18 400 Kč;Praha'", 1, false));
        assertFalse(nested.error().contains("8501011234"), "the longest line first: " + nested.error());
    }

    @Test
    @DisplayName("output that is no JSON, a stall: the service's words, never what was printed")
    void theServicesWords(@TempDir Path tmp) throws Exception {
        var notJson = run(tmp, new SandboxResult(0, DATA, "", 1, false));
        assertFalse(notJson.success());
        assertTrue(notJson.output().contains(DATA));
        assertFalse(notJson.error().contains("155"), notJson.error());
        assertTrue(notJson.error().startsWith("Tool produced non-JSON output"), notJson.error());
        var stalled = run(tmp, new SandboxResult(-1, DATA, "still waiting", 1, true));
        assertTrue(stalled.error().startsWith("Skill 'price_list' stalled"), stalled.error());
        assertTrue(stalled.error().contains("[stderr: still waiting]"), stalled.error());
        assertFalse(stalled.error().contains("155"), stalled.error());
        // Mutation: make the error of a crash its whole output -> the data is shown with it.
    }

    @Test
    @DisplayName("every name of a handed file is taken out of the error: its path, its name on disk, the name it came with")
    void noFileName() {
        var attached = List.of(Map.of("id", "f1", "name", "Vypis uctu 123456789.pdf",
                "path", "/opt/ownclaw/data/uploads/0b1c_Vypis uctu 123456789.pdf",
                "container_path", "/uploads/0b1c_Vypis uctu 123456789.pdf", "content_type", "application/pdf"));
        var failed = ToolResult.failure("whole", "FileNotFoundError: '/opt/ownclaw/data/uploads/0b1c_Vypis uctu 123456789.pdf'\n"
                + "while reading 0b1c_Vypis uctu 123456789.pdf (Vypis uctu 123456789.pdf) in /uploads/0b1c_Vypis uctu 123456789.pdf");
        var clean = DynamicSkill.withoutFileNames(failed, attached);
        assertFalse(clean.error().contains("123456789"), clean.error());
        assertEquals("FileNotFoundError: '[an attached file]'\nwhile reading [an attached file] ([an attached file]) in [an attached file]",
                clean.error());
        assertEquals("whole", clean.output(), "the output is not touched: it is private wherever a file is");
        var forms = ToolResult.failure("whole", "No table in Vypis uctu 123456789; "
                + "json \"Vypis uctu 123456789.pdf\"; file:///opt/ownclaw/data/uploads/0b1c_Vypis%20uctu%20123456789.pdf");
        String cleaned = DynamicSkill.withoutFileNames(forms, attached).error();
        assertFalse(cleaned.contains("123456789"), cleaned);
        var czech = List.of(Map.of("id", "f2", "name", "Výpis účtu.pdf", "path", "/u/0c_Výpis účtu.pdf",
                "container_path", "/uploads/0c_Výpis účtu.pdf", "content_type", "application/pdf"));
        String escaped = DynamicSkill.withoutFileNames(ToolResult.failure("w", "open \"V\\u00fdpis \\u00fa\\u010dtu.pdf\" failed"), czech).error();
        assertFalse(escaped.contains("\\u00fd"), escaped);
    }
}
