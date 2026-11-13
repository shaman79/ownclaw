package com.ownclaw.agent;

import com.ownclaw.llm.LlmMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

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
}
