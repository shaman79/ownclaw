package com.ownclaw.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Which module the self-heal installs. A run writes what it was given -- a web page, a mail, a
 * file -- so a "No module named '...'" in its output is the input's to choose, and it once had
 * pip install the package it named into the skill's environment and the skill run again on it,
 * with the same credentials. The heal now reads the runner's own word alone (an uncaught
 * ModuleNotFoundError, in the result's {@code _missing_module}), and installs only a module the
 * skill's source imports in its own import statements -- never one of its own directory, and
 * never a name pip would read as an option, a URL or a path.
 */
class DynamicSkillSelfHealNameTest {

    @TempDir Path tmp;

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Where a fake run's answer has the run's id, until {@link #answered} puts in the one the run was handed. */
    static final String THIS_RUN = "@this-run@";

    /** The line the runner prints for a run whose skill raised a ModuleNotFoundError for {@code module}. */
    static String runnerSaysMissing(String module, String runId) {
        var result = new LinkedHashMap<String, Object>();
        result.put("success", false);
        result.put("output", "Skill error: No module named '" + module + "'\nTraceback (most recent call last): ...");
        result.put(DynamicSkill.MISSING_MODULE, module);
        result.put(DynamicSkill.RUN, runId);
        try {
            return JSON.writeValueAsString(result);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * A run of the skill that failed as {@link #runnerSaysMissing} says, exited as the runner does;
     * a fake sandbox answers it through {@link #answered}, which puts in the run's own id.
     */
    static SandboxResult missing(String module) {
        return new SandboxResult(0, runnerSaysMissing(module, THIS_RUN), "", 1, false);
    }

    /** {@code result} as the run handed {@code stdin} gets it: the run's id from its input in place of {@link #THIS_RUN}. */
    static SandboxResult answered(SandboxResult result, String stdin) {
        if (result == null || result.stdout() == null || !result.stdout().contains(THIS_RUN)) return result;
        String runId;
        try {
            runId = JSON.readTree(stdin).path(DynamicSkill.RUN).asText("");
        } catch (Exception e) {
            runId = "";
        }
        return new SandboxResult(result.exitCode(), result.stdout().replace(THIS_RUN, runId), result.stderr(),
                result.durationMs(), result.timedOut());
    }

    /** What the last run returned, for the tests that read it. */
    private ToolResult last;

    /**
     * Run a skill of this code whose sandbox answers with {@code result}, and return every install
     * the self-heal asked for.
     */
    private List<List<String>> installsAfter(String code, SandboxResult result) throws Exception {
        Path skillDir = Files.createDirectories(tmp.resolve("skills/pdf_text"));
        Files.writeString(skillDir.resolve("skill.py"), code);
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
                return answered(result, stdin);
            }

            @Override
            public Optional<String> unavailable() {
                return Optional.empty();
            }
        };
        var skill = new DynamicSkill("pdf_text", "reads a pdf", Map.of(), skillDir, false, false, 30,
                sandbox, pythonEnv, List.of(), null, List.of(), null, null, null);
        last = skill.execute(Map.of(), new ToolExecutionContext("u1", "t1", null, () -> false));
        return asked;
    }

    private List<List<String>> installsAfter(SandboxResult result) throws Exception {
        return installsAfter("import pdfplumber\nimport bs4.element\nfrom dateutil import parser\nimport yaml\n"
                + "def run(params):\n    return {}\n", result);
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
    @DisplayName("a crafted module name never reaches installPackages, even when the runner reports it")
    void craftedNamesAreRefused(String crafted) throws Exception {
        assertEquals(List.of(), installsAfter("import " + crafted + "\ndef run(params):\n    return {}\n", missing(crafted)));
    }

    @Test
    @DisplayName("a module the skill imports, reported missing by the runner, is healed by its top-level package")
    void aModuleTheSkillImportsIsHealed() throws Exception {
        assertEquals(List.of(List.of("pdfplumber")), installsAfter(missing("pdfplumber")));
        assertEquals(List.of(List.of("beautifulsoup4")), installsAfter(missing("bs4.element")));
        assertEquals(List.of(List.of("python-dateutil")), installsAfter(missing("dateutil.parser")));
        assertEquals(List.of(List.of("pyyaml")), installsAfter(missing("yaml")));
        // Mutation: the reported name not cut to its top-level package -> pip is asked for bs4.element.
    }

    @Test
    @DisplayName("what a run printed is never a module to install: \"No module named\" on stderr, in the skill's own result, in its output -- a page the skill read can say so")
    void whatARunPrintedHealsNothing() throws Exception {
        String said = "ModuleNotFoundError: No module named 'pdfplumber'";
        assertEquals(List.of(), installsAfter(new SandboxResult(1, "", "Traceback (most recent call last):\n" + said + "\n", 1, false)),
                "on stderr, as a crash");
        assertEquals(List.of(), installsAfter(new SandboxResult(0,
                "{\"success\": false, \"output\": \"Skill error: " + said + "\"}", "", 1, false)), "in the skill's result");
        assertEquals(List.of(), installsAfter(new SandboxResult(0,
                "The page said: " + said + "\n{\"success\": false, \"output\": \"could not read it\"}", "", 1, false)),
                "printed before the result");
        assertEquals(List.of(), installsAfter(new SandboxResult(1, "", "ImportError: No module named yaml", 1, false)),
                "the Python 2 wording");
        // Mutation: the heal reading the output for "No module named", as it did -> each of these installs pdfplumber or pyyaml.
    }

    @Test
    @DisplayName("a module the skill's source does not import is not installed, though the runner reports it: one only another package imports, or only importlib names -- and the run is told why")
    void aModuleTheSkillDoesNotImportIsNotHealed() throws Exception {
        String code = "import importlib\nimport pdfplumber\ndef run(params):\n"
                + "    return importlib.import_module(params.get('name', 'evil_pkg'))\n";
        assertEquals(List.of(), installsAfter(code, missing("evil_pkg")));
        assertFalse(last.success());
        assertTrue(last.output().contains("'evil_pkg' is not installed automatically: skill.py does not import it itself"),
                last.output());
        assertEquals(List.of(), installsAfter("\"\"\"pdfplumber is used upstream.\"\"\"\ndef run(params):\n    return {}\n",
                missing("pdfplumber")), "named in a docstring, not imported");
        // Mutation: any name the runner reports installed -> evil_pkg is installed.
    }

    @Test
    @DisplayName("a module of the skill's own directory is never installed from an index under its name: nothing there is imported, and the run is told to put it into skill.py")
    void aModuleOfItsOwnDirectoryIsNotHealed() throws Exception {
        Path skillDir = Files.createDirectories(tmp.resolve("skills/pdf_text"));
        Files.writeString(skillDir.resolve("helper_mod.py"), "VALUE = 1\n");
        Files.createDirectories(skillDir.resolve("helper_pkg"));

        assertEquals(List.of(), installsAfter("import helper_mod\nimport helper_pkg\ndef run(params):\n    return {}\n",
                missing("helper_mod")));
        assertTrue(last.output().contains("'helper_mod' is a module in the skill's own directory"), last.output());
        assertEquals(List.of(), installsAfter("import helper_mod\nimport helper_pkg\ndef run(params):\n    return {}\n",
                missing("helper_pkg.sub")));
        // Mutation: the skill's own directory not looked at -> pip is asked for helper_mod, whatever an index holds under it.
    }

    @Test
    @DisplayName("a line that names a missing module without this run's id is not the runner's, and heals nothing: what a skill printed last, when it exited on its own -- a page it fetched can say so")
    void aLineWithoutThisRunsIdHealsNothing() throws Exception {
        assertEquals(List.of(), installsAfter(new SandboxResult(0, runnerSaysMissing("pdfplumber", "not-this-run"), "", 1, false)),
                "another run's id");
        assertEquals(List.of(), installsAfter(new SandboxResult(1, "Fetched: " + runnerSaysMissing("pdfplumber", null), "", 1, false)),
                "no id, after sys.exit(1)");
        assertEquals(List.of(), installsAfter(new SandboxResult(0,
                "{\"success\": false, \"output\": \"x\", \"" + DynamicSkill.MISSING_MODULE + "\": \"pdfplumber\"}", "", 1, false)),
                "no id at all");
        assertEquals(List.of(List.of("pdfplumber")), installsAfter(missing("pdfplumber")), "the premise: with it, it heals");
        // Mutation: the run's id not compared -> each of the first three installs pdfplumber.
    }

    @Test
    @DisplayName("the runner's key is no part of what the skill returned: the output reads as it did before the key was added")
    void theRunnersKeyIsNotInTheOutput() throws Exception {
        installsAfter(missing("pdfplumber"));
        assertFalse(last.output().contains(DynamicSkill.MISSING_MODULE), last.output());
        assertFalse(last.output().contains("\"" + DynamicSkill.RUN + "\""), last.output());
        assertTrue(last.output().startsWith("Skill error: No module named 'pdfplumber'"), last.output());
        // Mutation: parseOutput leaving the key in -> the whole result object is the output.
    }

    @Test
    @DisplayName("what a skill's source imports: every form of import statement, at a line's start or after a colon, by top-level name")
    void whatASourceImports() {
        String source = """
                import os, sys as system
                import google.cloud.storage
                from bs4.element import Tag
                from . import sibling
                from .rel import thing
                try: import ujson as json
                except ImportError: import json
                if True: from yaml import safe_load
                def run(params):
                    import pdfplumber  # lazily
                    x = 1; import requests
                    s = "this import is text"
                    return {}
                # import commented_out
                print('Error: import failed')  # TODO: import pandas later
                STEPS = \"""
                Step 1: import contacts from the CSV
                import nothing_here
                \"""
                ESCAPED = 'it\\'s: import quoted'
                """;
        assertEquals(Set.of("os", "sys", "google", "bs4", "ujson", "json", "yaml", "pdfplumber", "requests"),
                DynamicSkill.importedBy(source));
        // A quote inside an f-string's field (3.12) does not end the string; a backslash before CRLF continues it.
        assertEquals(Set.of("yaml"), DynamicSkill.importedBy("x = f\"{'\"'}\"; import yaml\n"));
        assertEquals(Set.of("yaml"), DynamicSkill.importedBy("x = rf'{d[\"k\"]:>{w}}'; import yaml\n"));
        assertEquals(Set.of(), DynamicSkill.importedBy("s = 'abc \\\r\nimport evilpkg'\r\n"));
        assertEquals(Set.of("os"), DynamicSkill.importedBy("s = f'{{import evil}}'\nimport os\n"));
        assertEquals(Set.of(), DynamicSkill.importedBy(null));
        assertEquals(Set.of(), DynamicSkill.importedBy("x = 'important'\nprint('imports')\n"));
    }
}
