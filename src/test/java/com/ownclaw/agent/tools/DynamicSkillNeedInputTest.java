package com.ownclaw.agent.tools;

import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.sandbox.JailTestKit;
import com.ownclaw.sandbox.ProcessSandbox;
import com.ownclaw.skills.PythonEnvironmentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A skill cannot ask the user anything while it runs: a result whose "type" is need_input fails,
 * saying so, whichever way it came back. A result that merely mentions need_input is a result.
 */
class DynamicSkillNeedInputTest {

    @TempDir Path tmp;

    @BeforeEach
    void python() {
        assumeTrue(Files.isExecutable(Path.of("/usr/bin/python3")), "python3 is not available");
    }

    /** Runs {@code code} as a skill, through the real runner, unjailed, with no progress callback. */
    private ToolResult run(String code) throws Exception {
        Path skillDir = Files.createDirectories(tmp.resolve("skills/generated/asker"));
        Files.writeString(skillDir.resolve("skill.py"), code);
        var env = new PythonEnvironmentService(new OwnClawConfig(), JailTestKit.off()) {
            @Override
            public PythonResolution resolveExecution(Path dir, String name) {
                return new PythonResolution("python3", Map.of(), null);
            }
        };
        var skill = new DynamicSkill("asker", "asks", Map.of(), skillDir, false, false, 30,
                new ProcessSandbox(JailTestKit.off()), env, List.of(), null, List.of(), null, null, null);
        return skill.execute(Map.of(), new ToolExecutionContext("u1", "t1", null, () -> false));
    }

    @Test
    @DisplayName("a skill that returns a need_input request fails, saying need_input is not supported")
    void aNeedInputResultFails() throws Exception {
        var result = run("def run(params):\n    return {'type': 'need_input', 'question': 'Which folder?'}\n");
        assertFalse(result.success(), result.output());
        assertEquals(DynamicSkill.NEED_INPUT_UNSUPPORTED, result.output());
    }

    @Test
    @DisplayName("so does one printed after other output, where the result is its last line")
    void aNeedInputResultAfterOtherOutputFails() throws Exception {
        // A process the skill starts writes past the runner's capture, before its result.
        var result = run("import subprocess\ndef run(params):\n    subprocess.run(['echo', 'looking'])\n"
                + "    return {'type': 'need_input', 'question': 'Which folder?'}\n");
        assertFalse(result.success(), result.output());
        assertEquals(DynamicSkill.NEED_INPUT_UNSUPPORTED, result.output());
    }

    @Test
    @DisplayName("a result with a key or a value named need_input is an ordinary result")
    void aResultMentioningNeedInputSucceeds() throws Exception {
        var result = run("def run(params):\n"
                + "    return {'output': 'form parsed', 'data': {'need_input': False, 'mode': 'need_input'}}\n");
        assertTrue(result.success(), result.output());
        assertEquals("form parsed", result.output());
    }
}
