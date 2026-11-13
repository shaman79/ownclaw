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

    // ---- 2. a fence opened again after a whole line, the model going on with the next line

    static final String HEAD = "```python\nimport json\n\ndef run(params):\n    hosts = params.get('hosts') or ['gw']";
    static final String REST = "    report = []\n    for h in hosts:\n        report.append(h)\n"
            + "    return {'output': json.dumps(report), 'success': True}\n```\n";
    static final String CODE = "import json\n\ndef run(params):\n    hosts = params.get('hosts') or ['gw']\n" + REST.replace("\n```\n", "");

    @Test
    @DisplayName("```python opened again after a whole line, then the next line: the skill as written, not run() cut at the seam")
    void reopenedAfterAWholeLine(@TempDir Path tmp) throws Exception {
        // Cut in the line after the hosts line: the hosts line is whole, and kept.
        var rig = new LoopRig(tmp, List.of());
        AgentLoop.Codegen out = generate(rig, tmp, HEAD + "\n    rep", "```python\n" + REST);
        assertNull(out.error(), out.error());
        assertEquals(CODE, out.params().get("code"), "created: " + out.params().get("code"));
        // Cut right at the hosts line's end: that line was never seen ending, so it is dropped, and the
        // model -- shown the line above it -- writes it again after the fence.
        var again = new LoopRig(tmp.resolve("again"), List.of());
        AgentLoop.Codegen out2 = generate(again, tmp.resolve("again"), HEAD,
                "```python\n    hosts = params.get('hosts') or ['gw']\n" + REST);
        assertEquals(CODE, out2.params() == null ? out2.error() : out2.params().get("code"));
        assertEquals(2, again.cloud.calls("codegen").size());
    }

    @Test
    @DisplayName("```python opened again after a comment line: the next statement is not swallowed by the comment")
    void reopenedAfterAComment(@TempDir Path tmp) throws Exception {
        String head = "```python\nimport json\n\ndef run(params):\n    # One line per host.";
        String rest = REST.replace("for h in hosts", "for h in params.get('hosts', [])");
        String code = "import json\n\ndef run(params):\n    # One line per host.\n" + rest.replace("\n```\n", "");
        var rig = new LoopRig(tmp, List.of());
        AgentLoop.Codegen out = generate(rig, tmp, head + "\n    rep", "```python\n" + rest);
        assertNull(out.error(), out.error());
        assertEquals(code, out.params().get("code"));
        // A part with no whole line is kept whole, and that seam falls inside its line: there too,
        // what follows a fence opened again begins a line of its own.
        String midLine = ReplySeam.join("```python\nimport json\n\ndef run(params):\n    # One line per host.",
                "```python\n" + rest);
        assertEquals("```python\n" + code + "\n```\n", midLine);
    }

    @Test
    @DisplayName("a bare ``` or ```py opening a code block again: the skill as written, not run() cut at the seam")
    void reopenedBareOrPy(@TempDir Path tmp) throws Exception {
        var wrong = new java.util.ArrayList<String>();
        for (String fence : List.of("```\n", "```py\n", "```python3\n", "```Python\n")) {
            Path dir = tmp.resolve(fence.strip().replace("`", "b"));
            var rig = new LoopRig(dir, List.of());
            AgentLoop.Codegen out = generate(rig, dir, HEAD, fence + "    hosts = params.get('hosts') or ['gw']\n" + REST);
            Object code = out.params() == null ? out.error() : out.params().get("code");
            if (!CODE.equals(code)) wrong.add(fence.strip() + " -> " + rig.cloud.calls("codegen").size() + " calls, created: " + code);
        }
        assertEquals(List.of(), wrong);
    }

    // ---- 3. a reply whose code is broken after the seam: a repair, not a reading that cuts the code short

    @Test
    @DisplayName("a reply broken after the seam, its fence opened again: a repair is asked for, never the code before the seam")
    void brokenAfterTheSeamIsRepaired(@TempDir Path tmp) throws Exception {
        String head = "```python\nimport json\n\ndef run(params):\n    hosts = params.get('hosts') or ['gw']\n    report = [h for h in hosts]";
        String part2 = "```python\n    report = [h for h in hosts]\n    return {'output': json.dumps(report), 'success': True\n```\n";
        var rig = new LoopRig(tmp, List.of());
        AgentLoop.Codegen out = generate(rig, tmp, head, part2);
        assertEquals(3, rig.cloud.calls("codegen").size(), "no repair was asked for; created: "
                + (out.params() == null ? out.error() : out.params().get("code")));
        assertEquals("import json\n\ndef run(params):\n    return {'output': 'REPAIRED', 'success': True}",
                out.params().get("code"));
    }

    @Test
    @DisplayName("a reply whose code ends at the seam while a block of code follows it, as joined: a repair is asked for, told the code is in pieces")
    void codeInPiecesIsRepaired(@TempDir Path tmp) throws Exception {
        String head = "```python\nimport json\n\ndef run(params):\n    hosts = params.get('hosts') or ['gw']\n    rep";
        // The part closes the fence, and the rest of the code follows in a block of its own after a lead-in:
        // joined, the code ends at the seam; the lead-in dropped, it is whole.
        String rest = "    return {'output': json.dumps(hosts), 'success': True}\n```\n";
        var mended = new LoopRig(tmp.resolve("mended"), List.of());
        AgentLoop.Codegen whole = generate(mended, tmp.resolve("mended"), head, "```\n\nAnd the rest:\n\n```python\n" + rest);
        assertEquals("import json\n\ndef run(params):\n    hosts = params.get('hosts') or ['gw']\n" + rest.replace("\n```\n", ""),
                whole.params() == null ? whole.error() : whole.params().get("code"));
        // The rest broken: no reading mends it, and the code before the seam -- which compiles -- is not taken.
        var rig = new LoopRig(tmp, List.of());
        AgentLoop.Codegen out = generate(rig, tmp, head, "```\n\nAnd the rest:\n\n```python\n" + rest.replace("True}", "True"));
        assertEquals(3, rig.cloud.calls("codegen").size(), "created: " + (out.params() == null ? out.error() : out.params().get("code")));
        String turn = rig.cloud.calls("codegen").get(2).messages().getLast().content();
        assertTrue(turn.startsWith("Your reply's code is in pieces: line 4: the code ends where its ```python fence closes"), turn);
    }

    // ---- 5. a reading whose check could not run

    @Test
    @DisplayName("a reading whose syntax check could not run ends the code as the first check would, not with a repair")
    void aReadingsCheckThatCannotRun(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of());
        var checks = new java.util.concurrent.atomic.AtomicInteger();
        rig.skills.syntax = code -> checks.incrementAndGet() == 1
                ? "  File \"skill.py\", line 4\n    x = compute(1, 2\n             ^\nSyntaxError: '(' was never closed"
                : SkillManager.CHECK_FAILED + "it timed out";
        rig.cloud.codegen.add(cutOff("```python\nimport json\n\ndef run(params):\n    x = compute("));
        // The line it was shown written again: read first without it, and appended as written another reading.
        rig.cloud.codegen.add(ends("def run(params):\n    x = compute(1, 2)\n    return {'output': json.dumps(x), 'success': True}\n```\n"));
        rig.cloud.codegen.add(finished(module("")));
        AgentLoop.Codegen out = rig.loop.generateSkillCode(spec(), task());
        assertEquals(2, rig.cloud.calls("codegen").size(), "a whole repair was asked for after a check that could not run: "
                + out.error());
        assertTrue(out.error().contains("could not be checked (" + SkillManager.CHECK_FAILED + "it timed out)"), out.error());
    }
}
