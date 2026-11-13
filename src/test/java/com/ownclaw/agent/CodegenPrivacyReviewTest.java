package com.ownclaw.agent;

import com.ownclaw.llm.LlmMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static com.ownclaw.agent.LoopRig.call;
import static com.ownclaw.agent.LoopRig.respond;
import static com.ownclaw.agent.SkillCodeInPartsTest.*;
import static com.ownclaw.agent.SkillCodegenTest.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The second privacy review of the code generation: where text the user's values were put back into
 * went next, how they are put back into code, and what a repair is told.
 */
class CodegenPrivacyReviewTest {

    static String everything(LoopRig.Call c) {
        var sb = new StringBuilder();
        for (LlmMessage m : c.messages()) sb.append(m.content()).append("\n---\n");
        return sb.toString();
    }

    @Test
    @DisplayName("py_compile's one-line IndentationError names the block's line first -- the repair is told and shown Python's line, not that one")
    void theIndentationErrorsLine(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of());
        realSyntaxCheck(rig, tmp);
        rig.cloud.codegen.add(ends("```python\nimport json\n\ndef run(params):\n    if params:\n    return {'output': '', 'success': True}\n```\n"));
        rig.cloud.codegen.add(finished(module("")));
        assertNull(rig.loop.generateSkillCode(spec(), task()).error());
        String turn = rig.cloud.calls("codegen").get(1).messages().getLast().content();
        assertTrue(turn.startsWith("The code failed Python's syntax check at line 5"), turn);
        assertTrue(turn.contains("Line 5, as you wrote it:\n    return {'output': '', 'success': True}"), turn);
        assertEquals(5, AgentLoop.errorLine("  File \"skill.py\", line 5\n    x = (\n        ^\nSyntaxError: '(' was never closed (detected at line 9)"));
        assertEquals(5, AgentLoop.errorLine("Sorry: IndentationError: expected an indented block after 'if' statement on line 4 (skill.py, line 5)"));
        assertEquals(3, AgentLoop.errorLine("line 3: invalid syntax"));
        assertNull(AgentLoop.errorLine("Missing required function: def run(params)"));
        // Mutation: take the first "line N" -> the repair is told line 4, the if, which is fine.
    }

    /** The placeholders shown for ssid="..." in the last think call's user parts, in order. */
    static List<String> shownInThink(LoopRig rig) {
        var last = rig.cloud.calls("think").getLast();
        var m = Pattern.compile("ssid=\\\\?\"(<ssid_\\d+>)\\\\?\"").matcher(String.join("\n", LoopRig.userParts(last)));
        var out = new ArrayList<String>();
        while (m.find()) out.add(m.group(1));
        return out;
    }

    @Test
    @DisplayName("repairs that go round end the code with Python's line and last words only -- the next think call is sent no value, inside a longer word or short")
    void aGivenUpRepairSendsNoValue(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of());
        realSyntaxCheck(rig, tmp);
        String session = rig.chat.createSession("u1", "Backup");
        String[] ph = new String[2];
        rig.cloud.think.add(c -> {
            var shown = shownInThink(rig);
            assertEquals(2, shown.size(), "premise: both SSIDs shown as placeholders to the think call");
            ph[0] = shown.get(0);
            ph[1] = shown.get(1);
            return call(AgentAction.SKILL_CREATE, Map.of("name", "router_backup",
                    "description", "Backs up the router. Its network is ssid=\"" + ph[0] + "\", the guest one ssid=\""
                            + ph[1] + "\".",
                    "parameters", "{}")).answer(c);
        });
        // The model's code: a syntax error on a line holding a value inside a longer word and a short one.
        String[] code = new String[1];
        rig.cloud.codegen.add(c -> {
            code[0] = "```python\nimport json\n\nARCHIVE = '/tmp/" + ph[0] + "_backup.tar' + '" + ph[1] + "')\n\n"
                    + "def run(params):\n    return {'output': ARCHIVE, 'success': True}\n```\n";
            return ends(code[0]).answer(c);
        });
        // The repair brings back the same code: the repairs go round.
        rig.cloud.codegen.add(c -> ends(code[0]).answer(c));
        rig.cloud.think.add(respond("I could not create the skill."));

        rig.turn(session, "back up my router; its network is ssid=\"ExampleHome5G\", the guest one ssid=\"AP7\"");

        var codegen = rig.cloud.calls("codegen");
        assertEquals(2, codegen.size(), "premise: one attempt and one repair");
        for (var c : codegen) {
            String sent = everything(c);
            assertFalse(sent.contains("ExampleHome5G") || sent.contains("'AP7'"), "codegen sent a value: " + sent);
        }
        var think = rig.cloud.calls("think");
        assertTrue(think.size() >= 2, "premise: a think call after the failed skill_create");
        String next = everything(think.get(1));
        assertTrue(next.contains("Python syntax error: line 3: SyntaxError: unmatched ')'"), next);
        assertFalse(next.contains("ExampleHome5G_backup"), "a value inside a longer word reached the cloud in clear:\n" + next);
        assertFalse(next.contains("'AP7'"), "a short value reached the cloud in clear:\n" + next);
        assertFalse(next.contains("ARCHIVE = "), "the code Python quotes went with its error:\n" + next);
        // Mutation: give up with Python's whole error -> the line it quotes, and both values, in the next think call.
    }

    @Test
    @DisplayName("a value whose quote is the literal's own, put back by the tokenizer: escaped for the literal, the code compiles, the skill is made from the first reply")
    void aValueWithTheLiteralsQuote(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of());
        realSyntaxCheck(rig, tmp);
        var spec = spec();
        spec.put("name", "guest_backup");
        spec.put("description", "Backs up the guest network ssid=\"Example Guest's WiFi\".");
        rig.cloud.codegen.add(c -> {
            String shown = rig.cloud.calls("codegen").get(0).messages().get(1).content();
            var m = Pattern.compile("ssid=\"(<ssid_\\d+>)\"").matcher(shown);
            assertTrue(m.find(), shown);
            return ends("```python\nimport json\n\nARCHIVE = '/tmp/" + m.group(1) + "_backup.tar'\n\n"
                    + "def run(params):\n    return {'output': ARCHIVE, 'success': True}\n```\n").answer(c);
        });

        AgentLoop.Codegen out = rig.loop.generateSkillCode(spec, task());

        assertNull(out.error(), out.error());
        assertEquals(1, rig.cloud.calls("codegen").size(), "no repair of code that compiles as written");
        String code = String.valueOf(out.params().get("code"));
        assertTrue(code.contains("ARCHIVE = '/tmp/Example Guest\\'s WiFi_backup.tar'"), code);
        assertEquals("/tmp/Example Guest's WiFi_backup.tar", ran(tmp, code + "\nprint(ARCHIVE)\n"));
        assertTrue(out.asWritten().contains("ARCHIVE = '/tmp/<ssid_"), "the code as the model wrote it, beside it");
        // Mutation: put the values back as text -> the quote ends the string, and the code does not compile.
    }

    @Test
    @DisplayName("a scanned SSID holding both quotes and code, put back into a quoted literal of every kind, is text: the skill prints the name")
    void aHostileSsidPutBackIsText(@TempDir Path tmp) throws Exception {
        var r = new com.ownclaw.privacy.Redactor(null);
        // A neighbour's network as a WiFi scan prints it (32 bytes at most).
        String hostile = "a'+__import__(\"os\").getcwd()+'b";
        assertTrue(hostile.length() <= 32);
        String shown = r.filter("u1", "BSS 02:00:5e:10:00:01(on wlan0)\n\tfreq: 2412\n\tSSID: " + hostile + "\n",
                Map.of(), new com.ownclaw.privacy.Redactor.Tally());
        var m = Pattern.compile("(<ssid_\\d+>)").matcher(shown);
        assertTrue(m.find(), "premise: learned as an SSID: " + shown);
        SkillManager python = SyntaxCheckTest.manager(tmp.resolve("py"));
        var wrong = new ArrayList<String>();
        for (String literal : List.of("'%s'", "\"%s\"", "'''%s'''", "r'%s'", "f'%s'", "rf\"%s\"", "b'%s'")) {
            String written = "BLOCKED = " + literal.formatted(m.group(1)) + "\n";
            var restored = python.restoreValues(written, CodeValues.valuesIn(written, ph -> r.restore("u1", ph)));
            assertNull(restored.refused(), written);
            String printed = ran(tmp, restored.code() + "print(BLOCKED.decode() if isinstance(BLOCKED, bytes) else BLOCKED)\n");
            if (!hostile.equals(printed)) wrong.add(literal + " -> " + restored.code().strip() + " printed " + printed);
        }
        assertEquals(List.of(), wrong, "the network's name ran as code in the skill");
        // Mutation: Redactor.restore over the code -> 'a'+__import__("os").getcwd()+'b' runs, and prints the directory.
    }

    @Test
    @DisplayName("a placeholder outside any string or comment is not put back: the skill is not made, said plainly, with no value and no repair")
    void aPlaceholderOutsideALiteral(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of());
        realSyntaxCheck(rig, tmp);
        var spec = spec();
        spec.put("description", "Checks the network ssid=\"ExampleHome5G\".");
        rig.cloud.codegen.add(c -> {
            String shown = rig.cloud.calls("codegen").get(0).messages().get(1).content();
            var m = Pattern.compile("ssid=\"(<ssid_\\d+>)\"").matcher(shown);
            assertTrue(m.find(), shown);
            // Compiles as written -- 1 < ssid_1 > 2 -- and put back, the value would be code.
            return ends("```python\nimport json\n\nok = 1 " + m.group(1) + " 2\n\ndef run(params):\n"
                    + "    return {'output': '', 'success': True}\n```\n").answer(c);
        });
        rig.cloud.codegen.add(finished(module("")));
        AgentLoop.Codegen out = rig.loop.generateSkillCode(spec, task());
        assertEquals("ERROR: the code for 'openwrt_audit' names a value of yours at line 3 outside any string or "
                + "comment, where it would be code, not text, so nothing was created.", out.error());
        assertEquals(1, rig.cloud.calls("codegen").size());
    }

    @Test
    @DisplayName("code that compiles as written but not with the values put back is the restore's fault: said so, with its line only, never sent to a repair")
    void aRestoreThatBreaksTheCode(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of());
        realSyntaxCheck(rig, tmp);
        rig.skills.restore = (code, values) -> CodeValues.Restored.unchanged(code.replace("'ExampleHome5G'", "'Example'Home5G'"));
        rig.cloud.codegen.add(finished("import json\n\nNET = 'ExampleHome5G'\n\ndef run(params):\n    return {'output': NET, 'success': True}"));
        rig.cloud.codegen.add(finished(module("")));
        AgentLoop.Codegen out = rig.loop.generateSkillCode(spec(), task());
        assertEquals("ERROR: the code for 'openwrt_audit' compiles as the model wrote it but not with your values put "
                + "back (at line 3) -- a fault in putting them back, not in the code -- so nothing was created.", out.error());
        assertEquals(1, rig.cloud.calls("codegen").size(), "no repair: the model's code is not what is wrong");
        // Mutation: no second check -> a skill that does not compile is made.
    }

    @Test
    @DisplayName("a check that could not run ends the code with its last words only")
    void aCheckThatCouldNotRun(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of());
        rig.skills.syntax = code -> SkillManager.CHECK_FAILED + "exit 1: Traceback (most recent call last):\n"
                + "  File \"_check.py\", line 9, in <module>\n    tree = ast.parse(f.read())\nRecursionError: too deep";
        rig.cloud.codegen.add(finished(module("")));
        assertEquals("ERROR: the code for 'openwrt_audit' could not be checked (Syntax check failed: RecursionError: "
                + "too deep), so nothing was created.", rig.loop.generateSkillCode(spec(), task()).error());
    }

    @Test
    @DisplayName("the property, in the Python the skills run on: random values in every kind of literal and in comments -- the code compiles, each literal is exactly the text meant, the program's shape is the one written")
    void theRestoresProperty(@TempDir Path tmp) throws Exception {
        Path script = Files.writeString(tmp.resolve("restore_values.py"), CodeValues.SCRIPT);
        Path property;
        try (var in = getClass().getResourceAsStream("restore_property.py")) {
            property = Files.write(tmp.resolve("restore_property.py"), in.readAllBytes());
        }
        var p = new ProcessBuilder("python3", "-I", property.toString(), script.toString(), "11", "3000")
                .redirectErrorStream(true).start();
        String said = new String(p.getInputStream().readAllBytes()).strip();
        assertEquals(0, p.waitFor(), said);
        assertTrue(said.matches("(?s)\\S+ ok \\d+ bad 0 refused \\{\\}"), said);
        // Mutation: no escape of the literal's quote -> code that does not compile, or literals that differ.
    }

    /** What {@code code} prints, run by the system's Python in a directory of its own. */
    static String ran(Path tmp, String code) throws Exception {
        Path dir = Files.createTempDirectory(tmp, "run");
        var p = new ProcessBuilder("python3", "-I", "-c", code).directory(dir.toFile()).redirectErrorStream(true).start();
        String printed = new String(p.getInputStream().readAllBytes()).strip();
        p.waitFor();
        return printed;
    }
}
