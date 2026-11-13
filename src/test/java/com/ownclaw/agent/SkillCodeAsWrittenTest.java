package com.ownclaw.agent;

import com.ownclaw.llm.LlmMessage;
import com.ownclaw.privacy.Redactor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.ownclaw.agent.LoopRig.call;
import static com.ownclaw.agent.LoopRig.respond;
import static com.ownclaw.agent.SkillCodeInPartsTest.*;
import static com.ownclaw.agent.SkillCodegenTest.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A skill's code goes back to the cloud with none of the user's values in it: to be fixed, as the
 * model wrote it -- kept beside the skill, out of the skill's own reach -- or, where none was kept,
 * as skill.py with every value the user's table holds replaced wherever it stands; and as code
 * wherever else it or Python's words about it go -- a skill read, a load error, a run's traceback.
 */
class SkillCodeAsWrittenTest {

    /** skill.py as the restore made it: a value inside a longer word, and a short one. */
    static final String ON_DISK = "import json\nARCHIVE = '/tmp/ExampleHome5G_backup.tar'\nGUEST = 'AP7'\n\n"
            + "def run(params):\n    return {'output': ARCHIVE + GUEST, 'success': True}\n";

    static String everything(LoopRig.Call c) {
        var sb = new StringBuilder();
        for (LlmMessage m : c.messages()) sb.append(m.content()).append("\n---\n");
        return sb.toString();
    }

    @Test
    @DisplayName("fixing a skill kept no copy of: skill.py with every known value replaced wherever it stands -- inside a longer word, and short -- nothing learned; put back, it is skill.py")
    void aFixWithoutACopy(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of());
        var spec = backupSpec();
        spec.put("name", "router_backup");
        rig.skills.source = name -> ON_DISK;
        rig.cloud.codegen.add(finished(module("")));
        assertNull(rig.loop.generateSkillCode(spec, task()).error());
        String sent = everything(rig.cloud.calls("codegen").get(0));
        assertFalse(sent.contains("ExampleHome5G_backup") || sent.contains("'AP7'"), "sent in clear: " + sent);
        assertTrue(sent.contains("ARCHIVE = '/tmp/<ssid_1>_backup.tar'\nGUEST = '<ssid_2>'"), sent);
        var redactor = new Redactor(rig.jdbc);
        String shown = sent.substring(sent.indexOf("import json\nARCHIVE"), sent.indexOf("\n```\n", sent.indexOf("import json\nARCHIVE")) + 1);
        assertEquals(ON_DISK.strip(), redactor.restore("u1", shown).strip(), "put back, it is the code on disk");
        assertFalse(redactor.count("u1", "The backups go to /tmp as always.", Map.of()).any(), "nothing of the code learned");
        // Mutation: send skill.py through the filter of text -> "ExampleHome5G_backup" and 'AP7' in clear.
    }

    @Test
    @DisplayName("fixing a skill kept with its code as the model wrote it: that is sent, its placeholders as they are; one the table does not know is text")
    void aFixFromTheCopy(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of());
        realSyntaxCheck(rig, tmp);
        var spec = backupSpec();
        spec.put("name", "router_backup");
        rig.skills.source = name -> ON_DISK;
        String copy = "import json\nARCHIVE = '/tmp/<ssid_1>_backup.tar'\nGUEST = '<ssid_2>'\nOTHER = '<ssid_9>'\n\n"
                + "def run(params):\n    return {'output': ARCHIVE + GUEST, 'success': True}\n";
        rig.skills.asWritten = name -> copy;
        rig.cloud.codegen.add(finished("import json\nARCHIVE = '/tmp/<ssid_1>_backup.tar'\nGUEST = '<ssid_2>'\n\n"
                + "def run(params):\n    return {'output': ARCHIVE + GUEST + '!', 'success': True}"));

        AgentLoop.Codegen out = rig.loop.generateSkillCode(spec, task());

        String sent = everything(rig.cloud.calls("codegen").get(0));
        assertTrue(sent.contains("ARCHIVE = '/tmp/<ssid_1>_backup.tar'\nGUEST = '<ssid_2>'\nOTHER = '<text_"), sent);
        assertFalse(sent.contains("ExampleHome5G") || sent.contains("AP7"), sent);
        assertNull(out.error(), out.error());
        assertTrue(String.valueOf(out.params().get("code")).contains("ARCHIVE = '/tmp/ExampleHome5G_backup.tar'\nGUEST = 'AP7'"),
                "the model's fix, its values put back");
        assertTrue(out.asWritten().contains("GUEST = '<ssid_2>'"), "and kept as it wrote it");
        // Mutation: keep no placeholder in the copy -> <text_n> where the model wrote <ssid_1>, and the fix loses the value.
    }

    @Test
    @DisplayName("the copy lies outside every skill's own directory, is read only with the skill.py it was kept with, and goes with the skill")
    void theCopyIsKept(@TempDir Path tmp) throws Exception {
        SkillManager skills = SyntaxCheckTest.manager(tmp);
        String code = "import json\n\nNET = 'ExampleHome5G'\n\ndef run(params):\n    return {'output': NET, 'success': True}\n";
        String asWritten = code.replace("ExampleHome5G", "<ssid_1>");
        var params = new HashMap<String, Object>(Map.of("name", "net_report", "description", "Reports the network.",
                "parameters", "{}", "code", code));
        String made = skills.createSkill(params, asWritten, null, () -> false, () -> { });
        assertTrue(made.startsWith("Skill 'net_report' created"), made);
        assertEquals(asWritten, skills.readAsWritten("net_report"));
        Path skillDir = tmp.resolve("generated").resolve("net_report");
        try (var files = Files.walk(skillDir)) {
            assertTrue(files.noneMatch(f -> f.toString().contains("<ssid_1>") || readQuietly(f).contains("<ssid_1>")),
                    "nothing of it in the skill's own directory, which its runs can write");
        }
        Path kept = tmp.resolve("_as_written").resolve("net_report.py");
        assertTrue(Files.isRegularFile(kept), "beside the skills, under the root the jail hides");

        // skill.py changed otherwise -- by hand, by a page: the copy is not its code any more.
        Files.writeString(skillDir.resolve("skill.py"), code.replace("NET", "NETWORK"));
        assertNull(skills.readAsWritten("net_report"));
        // Made again without a copy: none is kept.
        assertTrue(skills.createSkill(params, null, null, () -> false, () -> { }).startsWith("Skill 'net_report' updated"));
        assertFalse(Files.exists(kept));
        assertNull(skills.readAsWritten("net_report"));
        skills.createSkill(params, asWritten, null, () -> false, () -> { });
        assertTrue(Files.exists(kept));
        skills.deleteSkill("net_report");
        assertFalse(Files.exists(kept), "deleted with the skill");
        // Mutation: no digest check -> the copy of the old code is sent for a skill.py it is not.
    }

    private static String readQuietly(Path f) {
        try {
            return Files.isRegularFile(f) ? Files.readString(f) : "";
        } catch (Exception e) {
            return "";
        }
    }

    @Test
    @DisplayName("a skill read goes to the next think call as code: no value inside a longer word or short in clear")
    void aSkillRead(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of());
        String session = rig.chat.createSession("u1", "Backup");
        rig.skills.files = name -> "## Skill: router_backup\n\n### skill.py\n```python\n" + ON_DISK + "\n```\n";
        rig.cloud.think.add(call(AgentAction.SKILL_MANAGE, Map.of("action", "read", "name", "router_backup")));
        rig.cloud.think.add(respond("It backs up the router."));

        rig.turn(session, "what does my backup skill do? my network is ssid=\"ExampleHome5G\", the guest one ssid=\"AP7\"");

        String next = everything(rig.cloud.calls("think").get(1));
        assertTrue(next.contains("/tmp/<ssid_1>_backup.tar"), next);
        assertFalse(next.contains("ExampleHome5G_backup"), next);
        assertFalse(next.contains("'AP7'"), next);
        // Mutation: declare no skill code for a read -> "ExampleHome5G_backup" in clear in the next think call.
    }

    @Test
    @DisplayName("a load error -- Python's traceback of the code, its values in it -- goes to the next think call as code")
    void aLoadError(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of());
        String session = rig.chat.createSession("u1", "Backup");
        rig.cloud.think.add(call(AgentAction.SKILL_CREATE, Map.of("name", "router_backup",
                "description", "Backs up the router.", "parameters", "{}")));
        rig.cloud.codegen.add(finished(module("")));
        rig.skills.create = p -> "ERROR: 'router_backup' was written but does not load, so it was not registered. Fix and "
                + "retry with the SAME name.\nTraceback (most recent call last):\n  File \"skill.py\", line 2, in <module>\n"
                + "    ARCHIVE = open('/tmp/ExampleHome5G_backup.tar')\nFileNotFoundError: no such file: 'AP7'";
        rig.cloud.think.add(respond("It does not load."));

        rig.turn(session, "back up my router; my network is ssid=\"ExampleHome5G\", the guest one ssid=\"AP7\"");

        String next = everything(rig.cloud.calls("think").get(1));
        assertTrue(next.contains("does not load"), next);
        assertFalse(next.contains("ExampleHome5G_backup"), next);
        assertFalse(next.contains("'AP7'"), next);
        // Mutation: declare no skill code for a failed create -> the traceback's values in clear.
    }

    @Test
    @DisplayName("the code pass: every known value wherever it stands -- as written and as Python escapes it -- a short one as a word; secrets removed; nothing learned; a placeholder kept only in the code as written, and only one the table knows")
    void theCodePass() {
        var r = new Redactor(null);
        var tally = new Redactor.Tally();
        r.filter("u1", "my networks: ssid=\"ExampleHome5G\", ssid=\"AP7\", ssid=\"Example Guest's WiFi\", ssid=\"ap\"",
                Map.of(), tally);
        String code = "A = '/tmp/ExampleHome5G_backup'\nB = 'AP7'\nC = 'MAP7X'\nD = 'Example Guest\\'s WiFi'\n"
                + "E = map(str, [])\nF = 'ap'\nG = '<ssid_1>'\nH = '<ssid_77>'\npassword = 'hunter2hunter2'\n"
                + "I = 'example.com'\nJ = 'admin@example.com'\n";
        String out = r.filterCode("u1", code, Map.of(), new Redactor.Tally(), false);
        assertTrue(out.contains("A = '/tmp/<ssid_1>_backup'"), out);
        assertTrue(out.contains("B = '<ssid_2>'"), out);
        assertTrue(out.contains("C = 'M<ssid_2>X'"), "inside a longer word: " + out);
        assertTrue(out.contains("D = '<ssid_3>'"), "as Python escapes it: " + out);
        assertTrue(out.contains("E = map(str, [])"), "a short one only as a word: " + out);
        assertTrue(out.contains("F = '<ssid_4>'"), out);
        assertTrue(out.contains("G = '<text_"), "a placeholder's shape is text: " + out);
        assertTrue(out.contains("H = '<text_"), out);
        assertFalse(out.contains("hunter2hunter2"), out);
        assertEquals(r.restore("u1", out.replace("D = '<ssid_3>'", "D = 'Example Guest\\'s WiFi'")),
                code.replace("hunter2hunter2", "«secret removed»").replace("password = 'hunter2hunter2'",
                        "password = '«secret removed»'"), "put back, the code as it was");
        assertTrue(out.contains("J = 'admin@example.com'"), "an address the table does not know is the code's own: " + out);
        assertEquals("write to <email_1>", r.filter("u1", "write to admin@example.com", Map.of(), new Redactor.Tally()),
                "and was not learned from it: the first address the table learns");
        String asWritten = r.filterCode("u1", "G = '<ssid_1>'\nH = '<ssid_77>'\n", Map.of(), new Redactor.Tally(), true);
        assertTrue(asWritten.startsWith("G = '<ssid_1>'\nH = '<text_"), "the code as written keeps the placeholders the table knows: " + asWritten);
        // Mutation: replace known values only as words -> "/tmp/ExampleHome5G_backup" in clear.
    }
}
