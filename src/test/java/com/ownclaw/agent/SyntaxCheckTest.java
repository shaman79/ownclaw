package com.ownclaw.agent;

import com.ownclaw.agent.tools.DynamicSkillRegistry;
import com.ownclaw.agent.tools.ToolRegistry;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.sandbox.JailTestKit;
import com.ownclaw.sandbox.ProcessSandbox;
import com.ownclaw.skills.PythonEnvironmentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The real syntax check of a skill's code, through the sandbox and the system's Python: what the
 * repairs compare (AgentLoop#errorKey) is the same for the same code wherever it is checked.
 */
class SyntaxCheckTest {

    static SkillManager manager(Path root) throws Exception {
        Path generated = Files.createDirectories(root.resolve("generated"));
        OwnClawConfig cfg = new OwnClawConfig();
        cfg.getSkills().setGeneratedPath(generated.toString());
        cfg.getSandbox().setPythonPath("python3");
        var jail = JailTestKit.off();
        var pyEnv = new PythonEnvironmentService(cfg, jail);
        pyEnv.init();
        var sandbox = new ProcessSandbox(jail);
        var registry = new ToolRegistry(List.of());
        return new SkillManager(new DynamicSkillRegistry(cfg, registry, sandbox, null, pyEnv, null, null),
                registry, cfg, sandbox, pyEnv, null, null);
    }

    static final String BROKEN = "import json\n\ndef run(params):\n    return {'output': '', 'success': True}\n\ndef broken(:\n    pass\n";

    @Test
    @DisplayName("the same code gets the same words, naming skill.py, wherever it was checked; a shifted line is the same error")
    void theSameCodeTheSameWords(@TempDir Path tmp) throws Exception {
        SkillManager skills = manager(tmp);
        String first = skills.checkPythonSyntax(BROKEN);
        String again = skills.checkPythonSyntax(BROKEN);
        assertNotNull(first);
        assertEquals(first, again, "checked twice, each time in a directory of its own");
        assertTrue(first.contains("\"skill.py\""), first);
        assertFalse(first.contains("ownclaw_syntax_"), "no temporary directory in it: " + first);
        assertFalse(first.startsWith(SkillManager.CHECK_FAILED), first);

        String shifted = skills.checkPythonSyntax("# a comment the repair added\n" + BROKEN);
        assertNotEquals(first, shifted, "the line's number moved");
        assertEquals(AgentLoop.errorKey(first, BROKEN), AgentLoop.errorKey(shifted, "# a comment the repair added\n" + BROKEN),
                "and it is the same error");

        String otherCode = BROKEN.replace("def broken(:", "x = = 1");
        String other = skills.checkPythonSyntax(otherCode);
        assertNotEquals(AgentLoop.errorKey(first, BROKEN), AgentLoop.errorKey(other, otherCode),
                "a different broken line is progress");

        assertEquals("Missing required function: def run(params)",
                skills.checkPythonSyntax("import json\n").strip());
        assertNull(skills.checkPythonSyntax("import json\n\ndef run(params):\n    return {'output': '', 'success': True}\n"));
        // Mutation: drop dfile='skill.py' -> the two checks of the same code differ.
    }

    @Test
    @DisplayName("numbers inside Python's own message do not make a moved error new; indentation errors are told apart by their line")
    void theMessagesOwnNumbers(@TempDir Path tmp) throws Exception {
        SkillManager skills = manager(tmp);
        String head = "import json\n\ndef run(params):\n    return {'output': '', 'success': True}\n\n";
        for (String broken : java.util.List.of("x = \"abc\n", "DOC = \"\"\"abc\n\ndef later():\n    pass\n",
                "x = [1,\n2)\n")) {
            String code = head + broken;
            String moved = head + "# the repair added a line\n" + broken;
            String a = skills.checkPythonSyntax(code);
            String b = skills.checkPythonSyntax(moved);
            assertNotNull(a, broken);
            assertEquals(AgentLoop.errorKey(a, code), AgentLoop.errorKey(b, moved), "moved down a line: " + a + " / " + b);
        }
        String indented = head + "def f():\nreturn 1\n";
        String elsewhere = head + "def g():\n    pass\n\ndef f2():\nx = 2\n";
        String i1 = skills.checkPythonSyntax(indented);
        String i2 = skills.checkPythonSyntax(elsewhere);
        assertTrue(i1.contains("IndentationError"), i1);
        assertNotEquals(AgentLoop.errorKey(i1, indented), AgentLoop.errorKey(i2, elsewhere),
                "two indentation errors in different places are two errors: " + i1 + " / " + i2);
        String i1moved = head + "# moved\n" + "def f():\nreturn 1\n";
        assertEquals(AgentLoop.errorKey(i1, indented), AgentLoop.errorKey(skills.checkPythonSyntax(i1moved), i1moved));
        assertTrue(skills.pythonVersion().orElseThrow().startsWith("Python 3."), "the version the code runs on is known");
        // Mutation: keep the message's own line numbers -> the unterminated string, moved, is a new error.
    }
}
