package com.ownclaw.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static com.ownclaw.agent.SkillCodeInPartsTest.*;
import static com.ownclaw.agent.SkillCodegenTest.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The second review of the seam, each a scenario the seam read wrong: a cut after a line's first
 * character that the next one repeats, a fence opened again in other ways, code broken after the
 * seam, a reading whose check could not run. Parts are kept in whole lines (ReplySeam#wholeLines),
 * so each scripted model writes what it would after being shown them.
 */
class SeamReviewRound2Test {

    static AgentLoop.Codegen generate(LoopRig rig, Path tmp, String part1, String part2) throws Exception {
        realSyntaxCheck(rig, tmp);
        rig.cloud.codegen.add(cutOff(part1));
        rig.cloud.codegen.add(ends(part2));
        rig.cloud.codegen.add(ends("```python\nimport json\n\ndef run(params):\n    return {'output': 'REPAIRED', 'success': True}\n```\n"));
        return rig.loop.generateSkillCode(spec(), task());
    }

    // ---- 1. the exact continuation the request asks for, after a line's first character that the next repeats

    @Test
    @DisplayName("cut after '    s' of '    ssh = ...', the model going on from exactly what it was shown: the skill as written")
    void exactAfterADoubledFirstLetter(@TempDir Path tmp) throws Exception {
        String code = "import json, subprocess\n\nTIMEOUT = 30\n\ndef run(params):\n"
                + "    host = params.get('host', '192.0.2.1')\n"
                + "    ssh = ['ssh', '-o', 'BatchMode=yes', 'root@' + host]\n"
                + "    out = subprocess.run(ssh + ['uci show wireless'], capture_output=True, text=True, timeout=TIMEOUT)\n"
                + "    return {'output': json.dumps(out.stdout), 'success': out.returncode == 0}";
        String whole = "```python\n" + code + "\n```\n";
        int cut = whole.indexOf("    ssh = ") + "    s".length();
        var rig = new LoopRig(tmp, List.of());
        realSyntaxCheck(rig, tmp);
        rig.cloud.codegen.add(cutOff(whole.substring(0, cut)));
        rig.cloud.codegen.add(exactly(rig, whole));
        AgentLoop.Codegen out = rig.loop.generateSkillCode(spec(), task());
        assertNull(out.error(), out.error());
        assertEquals(code, out.params().get("code"), "the exact continuation lost a character and still compiled");
        assertEquals(2, rig.cloud.calls("codegen").size());
    }

    @Test
    @DisplayName("cut after the first character of __all__, ttl, ssid, **kwargs: joined as written, exactly or with the line written again")
    void exactAfterDoubledFirstCharacters() {
        var wrong = new java.util.ArrayList<String>();
        for (String line : List.of("__all__ = ['run']", "ttl = 3600", "    ssid = cfg.get('ssid')", "        **kwargs,",
                "    __slots__ = ('a',)", "    llm = None", "    ssh.connect(host)")) {
            String sofar = "```python\nimport json\n\ndef f(*a, **kw):\n    pass\n\n";
            int indent = line.length() - line.stripLeading().length();
            String whole = sofar + line + "\n";
            String kept = ReplySeam.wholeLines(sofar + line.substring(0, indent + 1));
            String shown = kept.stripTrailing();
            String exact = ReplySeam.join(kept, whole.substring(shown.length()));
            if (!exact.equals(whole)) wrong.add("exact [" + line + "] -> [" + exact.substring(shown.length()).strip() + "]");
            String again = ReplySeam.join(kept, line + "\n");
            if (!again.equals(shown + "\n" + line + "\n")) wrong.add("again [" + line + "] -> [" + again.substring(shown.length()).strip() + "]");
        }
        assertEquals(List.of(), wrong);
    }
}
