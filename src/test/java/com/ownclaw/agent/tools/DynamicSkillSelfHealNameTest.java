package com.ownclaw.agent.tools;

import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.sandbox.JailTestKit;
import com.ownclaw.sandbox.SandboxManager;
import com.ownclaw.sandbox.SandboxResult;
import com.ownclaw.skills.PythonEnvironmentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The self-heal reads the name of a missing module out of the skill's own output and hands it to
 * pip. The skill decides what that output says, so a name pip would read as an option, a URL or
 * a path must never get as far as installPackages.
 */
class DynamicSkillSelfHealNameTest {

    @TempDir Path tmp;

    private static final String JSON_FAILURE = "{\"success\": false, \"output\": \"%s\"}";

    /**
     * Run a skill whose sandbox answers with {@code result}, and return every install the
     * self-heal asked for.
     */
    private List<List<String>> installsAfter(SandboxResult result) throws Exception {
        Path skillDir = Files.createDirectories(tmp.resolve("skills/pdf_text"));
        Files.writeString(skillDir.resolve("skill.py"), "def run(params):\n    return {}\n");
        var asked = new ArrayList<List<String>>();
        var pythonEnv = new PythonEnvironmentService(new OwnClawConfig(), JailTestKit.off()) {
            @Override public void installPackages(Path dir, String name, List<String> packages, java.util.function.BooleanSupplier stopped, Runnable waiting) throws java.io.IOException {
                asked.add(packages);
                throw new java.io.IOException("not installed");
            }
        };
        SandboxManager sandbox = new SandboxManager() {
            @Override
            public SandboxResult execute(String python, Path script, Path dir, String stdin, Map<String, String> env,
                                         int timeout, ProgressCallback progress, List<Path> readOnly) {
                return result;
            }

            @Override
            public Optional<String> unavailable() {
                return Optional.empty();
            }
        };
        var skill = new DynamicSkill("pdf_text", "reads a pdf", Map.of(), skillDir, false, false, 30,
                sandbox, pythonEnv, List.of(), null, List.of(), null, null, null);
        skill.execute(Map.of(), new ToolExecutionContext("u1", "t1", null, () -> false));
        return asked;
    }

    private static SandboxResult onStderr(String module) {
        return new SandboxResult(1, "", "Traceback (most recent call last):\n"
                + "ModuleNotFoundError: No module named '" + module + "'\n", 1, false);
    }

    private static SandboxResult inOutput(String module) {
        return new SandboxResult(0, String.format(JSON_FAILURE,
                "Skill error: ModuleNotFoundError: No module named '" + module + "'"), "", 1, false);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "-e git+https://example.invalid/x",
            "--index-url=https://example.invalid/simple",
            "-r",
            "https://example.invalid/pkg.tar.gz",
            "file:///etc/passwd",
            "../../opt/data",
            "/tmp/evil",
            "x/y",
            ".hidden",
            "..",
            "a b",
            "requests==2.0"})
    @DisplayName("a crafted module name never reaches installPackages")
    void craftedNamesAreRefused(String crafted) throws Exception {
        assertNull(DynamicSkill.extractMissingModule("ModuleNotFoundError: No module named '" + crafted + "'"));
        assertEquals(List.of(), installsAfter(onStderr(crafted)), "from stderr");
        assertEquals(List.of(), installsAfter(inOutput(crafted)), "from the skill's JSON output");
    }

    @Test
    @DisplayName("a real missing module still self-heals, by its top-level package")
    void realNamesStillHeal() throws Exception {
        assertEquals(List.of(List.of("pdfplumber")), installsAfter(onStderr("pdfplumber")));
        assertEquals(List.of(List.of("beautifulsoup4")), installsAfter(onStderr("bs4.element")));
        assertEquals(List.of(List.of("python-dateutil")), installsAfter(inOutput("dateutil.parser")));
        assertEquals(List.of(List.of("pyyaml")), installsAfter(new SandboxResult(1, "",
                "ImportError: No module named yaml", 1, false)), "the Python 2 wording");
    }
}
