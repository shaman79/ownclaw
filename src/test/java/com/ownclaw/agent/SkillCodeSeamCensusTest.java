package com.ownclaw.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every cut a realistic skill's reply can have, and what its two parts join to. The first part is
 * cut at each character in turn and kept without its trailing whitespace, as the model is shown it;
 * the second is what one of four models writes after it: the exact characters that follow what it
 * was shown (what the request to go on asks for); the cut line written again from its beginning;
 * the cut line written again after its indentation; and a restart from the nearest line above that
 * is not blank -- where a model goes back to for the context. Each pair is
 * read as the code generation reads it: joined, and when that fails the checks, the other readings
 * of the seam in order, the first whose code compiles kept ({@code AgentLoop#generateSkillCode}).
 * Python's own compiler decides, all the candidates of the census at once.
 * <p>
 * The first three must give the skill as written, byte for byte, at every cut. A restart from a
 * line above must give the skill as written -- read first, or by another reading -- or code that
 * fails every check, which a repair is then asked to mend; never other code that compiles. (A part
 * that begins with a line break and then writes the cut line again is the same bytes as an exact
 * continuation whose next line begins as the cut one, and is read as that: ReplySeamTest.)
 */
class SkillCodeSeamCensusTest {

    static final String CODE = """
            import json
            import os
            import re
            import subprocess
            import time

            ROUTERS = {
                "gw": "192.0.2.1",
                "ap1": "192.0.2.2",
            }
            BACKUP_DIR = "backups"


            def _ssh(host, cmd, timeout=30):
                \"""Run one command on a router over ssh; return (code, out).\"""
                try:
                    proc = subprocess.run(
                        ["ssh", "-o", "BatchMode=yes", f"root@{host}", cmd],
                        capture_output=True,
                        text=True,
                        timeout=timeout,
                    )
                except subprocess.TimeoutExpired:
                    return 124, ""
                return proc.returncode, proc.stdout


            def _parse_uci(text):
                values = {}
                for line in text.splitlines():
                    m = re.match(r"^([\\w.]+)='?(.*?)'?$", line)
                    if not m:
                        continue
                    values[m.group(1)] = m.group(2)
                return values


            def _prune(days):
                # Remove the backups older than the given number of days.
                removed = []
                cutoff = time.time() - days * 86400
                for name in sorted(os.listdir(BACKUP_DIR)):
                    path = os.path.join(BACKUP_DIR, name)
                    stale = os.path.getmtime(path) < cutoff
                    if stale:
                        os.remove(path)
                        removed.append(name)
                return removed


            def run(params):
                hosts = params.get('hosts') or list(ROUTERS)
                report = []
                failed = 0
                os.makedirs(BACKUP_DIR, exist_ok=True)
                for name in hosts:
                    host = ROUTERS.get(name, name)
                    code, out = _ssh(host, "uci show wireless")
                    if code != 0:
                        failed += 1
                        report.append(f"{name}: unreachable ({code})")
                        continue
                    cfg = _parse_uci(out)
                    for key, value in sorted(cfg.items()):
                        if key.endswith('.disabled') and value == '1':
                            report.append(f"{name}: {key} is off")
                        elif key.endswith('.encryption') and value in ('none', 'wep'):
                            report.append(f"{name}: {key} is weak ({value})")
                    report.append(f"{name}: {len(cfg)} settings read")
                    report.append("")
                    report.append("")
                removed = _prune(int(params.get('keep_days', 30)))
                return {
                    'output': json.dumps({'report': report, 'failed': failed, 'pruned': removed}),
                    'success': failed == 0,
                }
            """;

    static final String REPLY = "```python\n" + CODE + "```\n\n```requirements\nrequests\n```\n";

    static final List<String> MODELS = List.of("exact", "the cut line again", "the cut line again after its indentation",
            "a restart from a line above");

    /** One cut, one model: the two parts as the code generation is handed them. */
    record Case(int cut, String model, List<String> parts) {}

    /** The second part each model writes after {@code kept}, or null where it writes nothing of its own. */
    static String second(String model, String kept) {
        int lineStart = kept.lastIndexOf('\n') + 1;
        int indented = lineStart;
        while (indented < kept.length() && (kept.charAt(indented) == ' ' || kept.charAt(indented) == '\t')) indented++;
        return switch (model) {
            case "exact" -> REPLY.substring(kept.length());
            case "the cut line again" -> REPLY.substring(lineStart);
            case "the cut line again after its indentation" -> indented > lineStart ? REPLY.substring(indented) : null;
            default -> {
                // The nearest line above that is not blank: where a model goes back to for the context.
                int above = lineStart;
                do {
                    if (above == 0) yield null;
                    above = kept.lastIndexOf('\n', above - 2) + 1;
                } while (kept.substring(above, kept.indexOf('\n', above)).isBlank());
                yield REPLY.substring(above);
            }
        };
    }

    @Test
    @DisplayName("at every cut of a realistic skill: an exact continuation and the cut line written again join to the skill as written; a restart from a line above too, or fails the check")
    void everyCut(@TempDir Path tmp) throws Exception {
        String intended = AgentLoop.extractPythonCode(REPLY);
        String requirements = AgentLoop.extractRequirements(REPLY);
        assertEquals(CODE.strip(), intended, "the premise: the reply's code is the skill");

        var cases = new ArrayList<Case>();
        for (int cut = 1; cut < REPLY.length(); cut++) {
            String kept = REPLY.substring(0, cut).stripTrailing();
            if (kept.isEmpty()) continue;  // nothing written: asked for again, not joined
            for (String model : MODELS) {
                String part = second(model, kept);
                if (part != null) cases.add(new Case(cut, model, List.of(kept, part)));
            }
        }
        // Every reading of every case, in the order the code generation tries them; then one compile of all.
        var candidates = new LinkedHashMap<Case, List<String>>();
        var codes = new LinkedHashSet<String>();
        codes.add(intended);
        for (Case c : cases) {
            var texts = new ArrayList<String>();
            texts.add(ReplySeam.join(c.parts().get(0), c.parts().get(1)));
            for (ReplySeam.Reading r : ReplySeam.readings(c.parts())) texts.add(r.text());
            candidates.put(c, texts);
            for (String t : texts) {
                String code = AgentLoop.extractPythonCode(t);
                if (code != null && code.contains("def run(")) codes.add(code);
            }
        }
        Map<String, String> compiled = compileAll(codes, tmp.resolve("candidates"));
        String intendedTree = compiled.get(intended);
        assertNotNull(intendedTree, "the skill as written compiles");

        var tally = new TreeMap<String, Integer>();
        var wrong = new ArrayList<String>();
        for (var e : candidates.entrySet()) {
            Case c = e.getKey();
            String chosen = null;
            for (String t : e.getValue()) {
                String code = AgentLoop.extractPythonCode(t);
                if (code != null && code.contains("def run(") && compiled.get(code) != null) {
                    chosen = t;
                    break;
                }
            }
            String code = chosen == null ? null : AgentLoop.extractPythonCode(chosen);
            boolean asWritten = intended.equals(code) && requirements.equals(AgentLoop.extractRequirements(chosen));
            boolean firstReading = chosen != null && chosen.equals(e.getValue().getFirst());
            String outcome = chosen == null ? "fails every check: a repair is asked for"
                    : asWritten ? (firstReading ? "joined as written" : "mended by another reading")
                    : intendedTree.equals(compiled.get(code)) ? "compiles to the same program, a comment or a blank apart"
                    : "OTHER CODE THAT COMPILES";
            tally.merge(c.model() + ": " + outcome, 1, Integer::sum);
            boolean ok = c.model().equals("a restart from a line above")
                    ? !outcome.equals("OTHER CODE THAT COMPILES")
                    : asWritten;
            if (!ok && wrong.size() < 20) {
                int lineStart = REPLY.lastIndexOf('\n', c.cut() - 1) + 1;
                wrong.add(c.model() + " at " + c.cut() + " (line '" + REPLY.substring(lineStart, REPLY.indexOf('\n', c.cut()))
                        + "', after " + (c.cut() - lineStart) + " characters): " + outcome);
            }
        }
        var census = new StringBuilder("Seam census over " + (REPLY.length() - 1) + " cuts of a " + CODE.lines().count()
                + "-line skill:\n");
        tally.forEach((k, v) -> census.append(String.format("  %-90s %5d%n", k, v)));
        System.out.println(census);
        assertEquals(List.of(), wrong, census.toString());
        // Mutation: keep a cut part's trailing blanks -> the exact model misjoins every cut inside an indentation.
        // Mutation: the longest run from any line read first -> report.append("") twice loses one.
    }

    /**
     * Each code compiled by the system's Python, once: its syntax tree dumped where it compiles and
     * holds a {@code def run} at the top, null where it does not -- the check the code generation
     * runs (SkillManager#checkPythonSyntax), all at once.
     */
    static Map<String, String> compileAll(java.util.Collection<String> codes, Path dir) throws Exception {
        var list = new ArrayList<>(codes);
        Files.createDirectories(dir);
        for (int i = 0; i < list.size(); i++) {
            Files.writeString(dir.resolve(String.format("%07d.py", i)), list.get(i), StandardCharsets.UTF_8);
        }
        String script = String.join("\n",
                "import ast, hashlib, os, sys",
                "d = sys.argv[1]",
                "for f in sorted(os.listdir(d)):",
                "    try:",
                "        tree = ast.parse(open(os.path.join(d, f), encoding='utf-8').read(), 'skill.py')",
                "        runs = [n for n in tree.body if isinstance(n, ast.FunctionDef) and n.name == 'run']",
                "        print(f, hashlib.sha256(ast.dump(tree).encode()).hexdigest() if runs else '-')",
                "    except (SyntaxError, ValueError):",
                "        print(f, '-')",
                "");
        Process python = new ProcessBuilder("python3", "-I", "-W", "ignore", "-c", script, dir.toString())
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        String out = new String(python.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, python.waitFor(), out);
        var trees = new HashMap<String, String>();
        var result = java.util.regex.Pattern.compile("^(\\d{7})\\.py (\\S+)$");
        for (String line : out.split("\n")) {
            var m = result.matcher(line);
            if (!m.matches()) continue;
            trees.put(list.get(Integer.parseInt(m.group(1))), m.group(2).equals("-") ? null : m.group(2));
        }
        assertEquals(list.size(), trees.size(), out);
        return trees;
    }
}
