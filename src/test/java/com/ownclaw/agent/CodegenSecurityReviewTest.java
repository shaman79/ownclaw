package com.ownclaw.agent;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The third review of the code generation, adversarial: values put back into code that the skill's
 * runner -- reading the file's bytes as they declare -- would read as other code.
 */
class CodegenSecurityReviewTest {

    /** Runs code as DynamicSkill's runner loads skill.py: compile() of the file's bytes, which honours a coding cookie. */
    static String runAsTheRunnerDoes(Path dir, String code) throws Exception {
        Path f = dir.resolve("skill.py");
        Files.writeString(f, code, StandardCharsets.UTF_8);
        var pb = new ProcessBuilder("python3", "-I", "-c",
                "import sys\nsrc=open(sys.argv[1],'rb').read()\nns={}\nexec(compile(src,sys.argv[1],'exec',dont_inherit=True),ns)\n"
                        + "print(repr(ns.get('X')))", f.toString());
        pb.directory(dir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        p.waitFor();
        return out;
    }

    @Test
    @DisplayName("F1: a value put back into a comment on the first two lines would be the encoding declaration -- refused; on a later line, it is a comment's text and the code runs as written")
    void aValueInTheEncodingDeclaration(@TempDir Path tmp) throws Exception {
        SkillManager python = SyntaxCheckTest.manager(tmp.resolve("python"));
        String value = "coding:unicode_escape'+str(7*6)+'\"";
        for (String header : List.of("# Monitors the network <ssid_1>\n", "#!/usr/bin/env python3\n# Monitors <ssid_1>\n")) {
            String asWritten = header + "import json\n\nX = '<ssid_1>'\n\ndef run(params):\n    return {'output': X, 'success': True}\n";
            assertNull(python.checkPythonSyntax(asWritten), "premise: compiles as written");
            CodeValues.Restored r = python.restoreValues(asWritten, Map.of("<ssid_1>", value));
            assertEquals("header", r.refused(), "put back: " + r.code());
            assertTrue(r.why().contains("encoding declaration"), r.why());
        }
        // A later comment: the value is its text -- every character but printable ASCII escaped -- and
        // the string is the value.
        String later = "import json\n\n# Monitors the network <ssid_1>\nX = '<ssid_1>'\n# Seen: <ssid_1>\n";
        for (String v : List.of(value, "coding:unicode_escape\nX=1")) {
            CodeValues.Restored r = python.restoreValues(later, Map.of("<ssid_1>", v));
            assertNotNull(r.code(), r.refused() + " " + r.failed());
            assertEquals(pythonRepr(v), runAsTheRunnerDoes(Files.createDirectories(tmp.resolve("run" + v.length())), r.code()),
                    "the value as text, never code:\n" + r.code());
        }
        // Mutation: allow a placeholder in a comment on line 1 -> refused all the same, by the check
        // of what was put back (its encoding changed): see theResultIsChecked.
    }

    /** {@code s} as Python's repr prints it, for the strings the tests use. */
    static String pythonRepr(String s) {
        String q = s.contains("'") && !s.contains("\"") ? "\"" : "'";
        return q + s.replace("\\", "\\\\").replace(q, "\\" + q).replace("\n", "\\n") + q;
    }

    @Test
    @DisplayName("F1: whatever was put back is checked as the runner reads it -- another encoding declared, or any other program, is refused")
    void theResultIsChecked(@TempDir Path tmp) throws Exception {
        Path script = Files.writeString(tmp.resolve("restore_values.py"), CodeValues.SCRIPT);
        // The check alone, handed what a faulty restore could have made.
        String harness = String.join("\n",
                "import importlib.util, sys",
                "spec = importlib.util.spec_from_file_location('rv', sys.argv[1]); rv = importlib.util.module_from_spec(spec)",
                "spec.loader.exec_module(rv)",
                "code = \"# a header\\n# <ssid_1>\\nX = '<ssid_1>'\\n\"",
                "v = {'<ssid_1>': 'coding:latin-1'}",
                "for out in [\"# a header\\n# coding:latin-1\\nX = 'coding:latin-1'\\n\",",
                "            \"# a header\\n# x\\nX = 'coding:latin-1'; import os\\n\",",
                "            \"# a header\\n# x\\nX = 'coding:latin-2'\\n\",",
                "            \"# a header\\n# x\\nX = 'coding:latin-1'\\n\"]:",
                "    try:",
                "        rv.checked(code, v, out); print('accepted')",
                "    except rv.Refused as r:",
                "        print(r.why)",
                "");
        var p = new ProcessBuilder("python3", "-I", "-c", harness, script.toString()).redirectErrorStream(true).start();
        String said = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        assertEquals(0, p.waitFor(), said);
        assertEquals(List.of("changed", "changed", "changed", "accepted"), List.of(said.split("\n")),
                "another encoding, another statement, another string refused; the program meant accepted");
        // Mutation: no check of the encoding -> the first is accepted.
    }

    @Test
    @DisplayName("F4: an octal escape before the placeholder cannot swallow the value's first digits")
    void anOctalEscapeBeforeTheValue(@TempDir Path tmp) throws Exception {
        SkillManager python = SyntaxCheckTest.manager(tmp.resolve("python"));
        CodeValues.Restored r = python.restoreValues("X = '\\0<host_1>'\n", Map.of("<host_1>", "12ab"));
        assertEquals("'\\x0012ab'", runAsTheRunnerDoes(Files.createDirectories(tmp.resolve("run")), r.code()),
                "NUL then the value; restored: " + r.code());
        CodeValues.Restored b = python.restoreValues("X = b'\\1<host_1>'\n", Map.of("<host_1>", "7x"));
        assertEquals("b'\\x017x'", runAsTheRunnerDoes(Files.createDirectories(tmp.resolve("bytes")), b.code()), b.code());
        CodeValues.Restored escaped = python.restoreValues("X = '\\\\0<host_1>'\n", Map.of("<host_1>", "12"));
        assertEquals("'\\\\012'", runAsTheRunnerDoes(Files.createDirectories(tmp.resolve("escaped")), escaped.code()),
                "an escaped backslash ends no escape: " + escaped.code());
        // Mutation: no guard -> '\012ab', a line break and "ab".
    }

    @Test
    @DisplayName("F6: on a Python older than 3.12 a value in an f-string is refused: a quote in a format spec or a \\N{...} fooled reading its fields")
    void oldPythonsFStrings(@TempDir Path tmp) throws Exception {
        var olds = new ArrayList<Path>();
        for (String name : List.of("python3.11", "python3.10", "python3.9")) {
            for (String dir : (System.getenv("PATH") + ":" + System.getProperty("user.home") + "/.local/bin").split(":")) {
                Path p = Path.of(dir, name);
                if (Files.isExecutable(p) && !olds.contains(p)) olds.add(p);
            }
        }
        try (var uv = Files.exists(Path.of(System.getProperty("user.home"), ".local/share/uv/python"))
                ? Files.list(Path.of(System.getProperty("user.home"), ".local/share/uv/python")) : java.util.stream.Stream.<Path>empty()) {
            uv.filter(d -> d.getFileName().toString().matches("cpython-3\\.(9|10|11)\\..*"))
                    .forEach(d -> {
                        String v = d.getFileName().toString().replaceAll("cpython-(3\\.\\d+).*", "$1");
                        Path p = d.resolve("bin/python" + v);
                        if (Files.isExecutable(p)) olds.add(p);
                    });
        }
        Assumptions.assumeFalse(olds.isEmpty(), "no Python older than 3.12 here");
        Path script = Files.writeString(tmp.resolve("restore_values.py"), CodeValues.SCRIPT);
        String hostile = "'+__import__('os').getcwd()+'";
        for (Path python : olds) {
            for (String code : List.of("x = 'q'\nX = f\"{x:'<5}{'}<ssid_1>'}\"\n", "X = f\"\\\\N{'<ssid_1>'}\"\n",
                    "X = f'plain <ssid_1>'\n")) {
                var p = new ProcessBuilder(python.toString(), "-I", script.toString()).redirectErrorStream(true).start();
                p.getOutputStream().write(CodeValues.input(code, Map.of("<ssid_1>", hostile)).getBytes(StandardCharsets.UTF_8));
                p.getOutputStream().close();
                String said = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                p.waitFor();
                assertTrue(said.contains("\"refused\": \"fstring\""), python + " " + code + " -> " + said);
            }
        }
        // Mutation: read an old f-string's fields by hand again -> the format spec's quote puts the value in code.
    }

    @Test
    @DisplayName("F7: putting values back is linear in the code's length -- 50,000 lines in well under the check's time")
    void aLongSkill(@TempDir Path tmp) throws Exception {
        SkillManager python = SyntaxCheckTest.manager(tmp.resolve("python"));
        var code = new StringBuilder();
        for (int i = 0; i < 50_000; i++) code.append("V").append(i).append(" = 'line ").append(i).append("'\n");
        code.append("X = '<ssid_1>'\n");
        long start = System.nanoTime();
        CodeValues.Restored r = python.restoreValues(code.toString(), Map.of("<ssid_1>", "ExampleHome5G"));
        long seconds = (System.nanoTime() - start) / 1_000_000_000L;
        assertNotNull(r.code(), r.refused() + " " + r.failed());
        assertTrue(r.code().endsWith("X = 'ExampleHome5G'\n"));
        assertTrue(seconds < 30, seconds + " s");
        // Mutation: sum the lines before each token again -> minutes, past the 60 s the check is given.
    }
}
