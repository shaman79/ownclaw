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

import com.ownclaw.llm.LlmMessage;
import com.ownclaw.privacy.Redactor;

import static com.ownclaw.agent.SkillCodeInPartsTest.*;
import static com.ownclaw.agent.SkillCodegenTest.*;
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

    static String everything(LoopRig.Call c) {
        var sb = new StringBuilder();
        for (LlmMessage m : c.messages()) sb.append(m.content()).append("\n---\n");
        return sb.toString();
    }

    @Test
    @DisplayName("F2: a run's output in a fix's request is text, filtered as on main -- an address and a phone number the table does not know are replaced, and learned")
    void aRunsOutputIsText(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of());
        var spec = spec();
        spec.put("name", "mail_report");
        rig.skills.source = name -> "import json\n\ndef run(params):\n    return {'output': '', 'success': False}\n";
        AgentContext ctx = task();
        String error = "SMTP 550 mailbox postmaster0@example.com unavailable (relay 192.0.2.25, phone +420 601 234 567)";
        ctx.trajectory().record(new AgentAction("mail_report", Map.of(), null), AgentObservation.failure("mail_report", error, 5));
        rig.cloud.codegen.add(finished(module("")));
        assertNull(rig.loop.generateSkillCode(spec, ctx).error());
        String sent = everything(rig.cloud.calls("codegen").get(0));
        assertTrue(sent.contains("SMTP 550 mailbox"), "premise: the error is in the fix's request: " + sent);
        assertFalse(sent.contains("postmaster0@example.com"), "address in clear: " + sent);
        assertFalse(sent.contains("601 234 567"), "phone in clear: " + sent);
        String onMain = new Redactor(null).filter("u1", error, Map.of(), new Redactor.Tally());
        assertFalse(onMain.contains("postmaster0@example.com") || onMain.contains("601 234 567"), "as on main: " + onMain);
        // Mutation: declare a run's output CODE -> what the table does not know goes out, not learned.
    }

    @Test
    @DisplayName("F3: a run whose whole output is \"0\" splits nothing: the request is filtered whole -- an address and an IBAN in the specification replaced")
    void aShortOutputSplitsNothing(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of());
        var spec = spec();
        spec.put("name", "router_check");
        spec.put("description", "Checks the router and mails ops0@example.com; invoices to CZ65 0800 0000 1920 0014 5399.");
        rig.skills.source = name -> "import json\n\ndef run(params):\n    return {'output': 0, 'success': False}\n";
        AgentContext ctx = task();
        ctx.trajectory().record(new AgentAction("router_check", Map.of(), null), AgentObservation.failure("router_check", "0", 5));
        rig.cloud.codegen.add(finished(module("")));
        assertNull(rig.loop.generateSkillCode(spec, ctx).error());
        String sent = everything(rig.cloud.calls("codegen").get(0));
        assertFalse(sent.contains("ops0@example.com"), "address in clear: " + sent);
        assertFalse(sent.contains("0800 0000 1920"), "IBAN in clear: " + sent);
        // Mutation: filter each declared stretch apart -> the address and the IBAN, cut at every "0", go out.
    }

    @Test
    @DisplayName("F9: a fix on the local model is shown skill.py, not the copy with placeholders: nothing on that path would put them back")
    void aLocalFixIsShownTheCode(@TempDir Path tmp) throws Exception {
        var local = LocalModeTest.localModel();
        var rig = new LoopRig(tmp, List.of(), 600, local);
        rig.cloud.codegen.add(LocalModeTest.noInternet());
        rig.cloud.codegen.add(LocalModeTest.noInternet());
        String onDisk = "import json\nNET = 'ExampleHome5G'\n\ndef run(params):\n    return {'output': NET, 'success': True}\n";
        rig.skills.source = name -> onDisk;
        rig.skills.asWritten = name -> onDisk.replace("ExampleHome5G", "<ssid_1>");
        // The local model keeps the line it was shown.
        local.codegen.add(c -> {
            String shown = LocalModeTest.all(local.calls("codegen").getLast());
            String line = shown.contains("NET = 'ExampleHome5G'") ? "NET = 'ExampleHome5G'" : "NET = '<ssid_1>'";
            return com.ownclaw.llm.Replies.of("```python\nimport json\n" + line
                    + "\n\ndef run(params):\n    return {'output': NET + '!', 'success': True}\n```\n", 100, 100, 0, 0, "end_turn");
        });
        var spec = spec();
        spec.put("name", "net_report");
        AgentLoop.Codegen out = rig.loop.generateSkillCode(spec, task());
        assertNull(out.error(), out.error());
        assertTrue(String.valueOf(out.params().get("code")).contains("NET = 'ExampleHome5G'"),
                "skill.py written with a placeholder in it: " + out.params().get("code"));
        // Mutation: show the local model the copy -> skill.py holds NET = '<ssid_1>'.
    }

    @Test
    @DisplayName("F5: the copy is one account's placeholders: a fix by another account -- the owner -- is shown skill.py, never the copy read in its own table")
    void theCopyIsTheAuthors(@TempDir Path tmp) throws Exception {
        SkillManager skills = SyntaxCheckTest.manager(tmp);
        String code = "import json\n\nNET = 'ExampleHome5G'\n\ndef run(params):\n    return {'output': NET, 'success': True}\n";
        var params = new java.util.HashMap<String, Object>(Map.of("name", "net_report", "description", "Reports the network.",
                "parameters", "{}", "code", code));
        assertTrue(skills.createSkill(params, code.replace("ExampleHome5G", "<ssid_1>"), "author", () -> false, () -> { })
                .startsWith("Skill 'net_report' created"));
        assertNotNull(skills.readAsWritten("net_report", "author"));
        assertNull(skills.readAsWritten("net_report", "owner"), "the author's <ssid_1> is the owner's own network in his table");
        // Mutation: keep no account with the copy -> the owner's fix is shown the author's placeholders.
    }

    @Test
    @DisplayName("pre-existing (a): a value goes back into the requirements only as one plain token -- not a line, an option or a reference")
    void requirementsTakePlainTokensOnly(@TempDir Path tmp) throws Exception {
        for (String value : List.of("-e git+https://example.com/x.git", "requests\n--index-url https://example.com/simple",
                "x@https://example.com/x.whl", "a b", "pkg;os_name=='nt'")) {
            var r = new Redactor(null);
            var rig = new LoopRig(tmp.resolve(String.valueOf(Math.abs(value.hashCode()))), List.of());
            var spec = spec();
            spec.put("description", "Uses the package ssid=\"" + value.replace("\n", "\\n") + "\".");
            rig.cloud.codegen.add(c -> {
                String shown = rig.cloud.calls("codegen").get(0).messages().get(1).content();
                var m = java.util.regex.Pattern.compile("(<ssid_\\d+>)").matcher(shown);
                assertTrue(m.find(), "premise: learned: " + shown);
                return ends("```python\nimport json\n\ndef run(params):\n    return {'output': '', 'success': True}\n```\n\n"
                        + "```requirements\n" + m.group(1) + "\n```\n").answer(c);
            });
            AgentLoop.Codegen out = rig.loop.generateSkillCode(spec, task());
            assertNotNull(out.error(), value + " -> " + (out.params() == null ? null : out.params().get("requirements")));
            assertTrue(out.error().contains("not a plain package name or version"), out.error());
        }
        var rig = new LoopRig(tmp.resolve("plain"), List.of());
        var spec = spec();
        spec.put("description", "Uses the host ssid=\"pypi-mirror.example\".");
        rig.cloud.codegen.add(c -> {
            String shown = rig.cloud.calls("codegen").get(0).messages().get(1).content();
            var m = java.util.regex.Pattern.compile("(<ssid_\\d+>)").matcher(shown);
            assertTrue(m.find(), shown);
            return ends("```python\nimport json\n\ndef run(params):\n    return {'output': '', 'success': True}\n```\n\n"
                    + "```requirements\n" + m.group(1) + "\n```\n").answer(c);
        });
        AgentLoop.Codegen plain = rig.loop.generateSkillCode(spec, task());
        assertNull(plain.error(), plain.error());
        assertEquals("pypi-mirror.example", plain.params().get("requirements"));
        // Mutation: put the values back into the requirements as text -> a line of pip options is added.
    }

    @Test
    @DisplayName("F8: every form the restore writes a value in -- either quote, f-string text, bytes, a comment, after an octal escape -- is found by the code pass, whatever the Unicode")
    void theCodePassFindsEveryForm(@TempDir Path tmp) throws Exception {
        SkillManager python = SyntaxCheckTest.manager(tmp.resolve("python"));
        var r = new Redactor(null);
        var wrong = new ArrayList<String>();
        for (String value : List.of("Kavárna Wi-Fi", "Example Guest's WiFi", "a\"b'c\\d", "tab\tand\nline", "{ExampleNet}",
                "🫨 Net", "007-net", "NUL\u0000x")) {
            String user = "u" + Math.abs(value.hashCode());
            r.filter(user, "ssid=\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\t", "\\t")
                    .replace("\u0000", "\\u0000") + "\"", Map.of(), new Redactor.Tally());
            String ph = r.filterCode(user, "X = '" + value.replace("\\", "\\\\").replace("'", "\\'") + "'", Map.of(), new Redactor.Tally(), false);
            var m = java.util.regex.Pattern.compile("<ssid_\\d+>").matcher(ph);
            if (!m.find()) {
                wrong.add("not learned: " + value + " -> " + ph);
                continue;
            }
            String p = m.group();
            String asWritten = "import json\n\n# Seen: " + p + "\nA = '" + p + "'\nB = \"" + p + "\"\nC = b'" + p + "'\n"
                    + "D = f'{1}" + p + "'\nE = " + "'''" + p + "'''" + "\nF = r'" + p + "'\nG = '\\0" + p + "'\n";
            CodeValues.Restored restored = python.restoreValues(asWritten, Map.of(p, value));
            if (restored.code() == null) {
                wrong.add(value + ": not restored: " + restored.refused() + " " + restored.failed());
                continue;
            }
            String sent = r.filterCode(user, restored.code(), Map.of(), new Redactor.Tally(), false);
            for (String form : Redactor.restoredForms(value)) {
                if (form.length() >= 3 && sent.contains(form)) wrong.add(value + ": [" + form + "] in clear in " + sent);
            }
            for (String line : List.of("# Seen: " + p + "\n", "D = f'{1}" + p + "'\n", "E = " + "\'\'\'" + p + "\'\'\'\n",
                    "G = '\\0" + p + "'\n")) {
                if (!sent.contains(line)) wrong.add(value + ": [" + line.strip() + "] not in " + sent);
            }
            if (!sent.contains(" = b'" + p + "'") && !sent.contains(" = b\"" + p + "\"")) wrong.add(value + ": bytes: " + sent);
        }
        assertEquals(List.of(), wrong);
        // Mutation: look for the value as written only -> its bytes form, and its escaped forms, go out.
    }

    @Test
    @DisplayName("round 4: requirements.txt holds plain requirements only, whatever named them -- the reply, or the planner's call with its values put back as text")
    void requirementsArePlainWhateverTheSource(@TempDir Path tmp) throws Exception {
        for (String ok : List.of("requests", "requests>=2.31\nparamiko==3.4.0  # ssh\n\n# none else", "requests[socks] >= 2, < 3",
                "numpy==1.26.*", "Pillow~=10.0")) {
            assertNull(SkillManager.requirementsRefusal(ok), ok);
        }
        for (String bad : List.of("requests\n--index-url https://pypi-mirror.example/simple", "-e git+https://example.com/x.git",
                "-r other.txt", "x @ https://example.com/x.whl", "x@https://example.com/x.whl",
                "pkg; os_name == 'nt'", "pkg \\\n--trusted-host example.com", "https://example.com/x.whl", "pkg --hash=sha256:00")) {
            String why = SkillManager.requirementsRefusal(bad);
            assertNotNull(why, bad);
            assertFalse(why.contains("example.com"), "said by its line, never its text: " + why);
        }
        // The planner's call names them, the reply none: the code generation refuses them.
        var rig = new LoopRig(tmp.resolve("planner"), List.of());
        var spec = spec();
        spec.put("requirements", "requests\n--index-url https://pypi-mirror.example/simple");
        rig.cloud.codegen.add(ends("```python\nimport json\n\ndef run(params):\n    return {'output': '', 'success': True}\n```\n"));
        AgentLoop.Codegen out = rig.loop.generateSkillCode(spec, task());
        assertNotNull(out.error(), String.valueOf(out.params()));
        assertTrue(out.error().contains("line 2 is not a plain requirement"), out.error());
        // And where requirements.txt is written, whoever calls: nothing is written.
        SkillManager skills = SyntaxCheckTest.manager(tmp.resolve("skills"));
        var params = new java.util.HashMap<String, Object>(Map.of("name", "pip_probe", "description", "Probes pip.",
                "parameters", "{}", "code", "import json\n\ndef run(params):\n    return {'output': '', 'success': True}\n",
                "requirements", "requests\n--index-url https://pypi-mirror.example/simple"));
        String made = skills.createSkill(params);
        assertTrue(made.startsWith("ERROR: the requirements for 'pip_probe' were not taken: line 2"), made);
        assertFalse(Files.exists(tmp.resolve("skills/generated/pip_probe")), "nothing written");
        // Mutation: check only the reply's requirements fence -> the planner's index URL reaches pip.
    }
}
