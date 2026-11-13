package com.ownclaw.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every cut a skill's reply can have, continued by many models, and what the code generation makes
 * of the parts -- read as it reads them ({@code AgentLoop#generateSkillCode}): each cut-off part kept
 * in whole lines ({@link ReplySeam#wholeLines}); the parts joined, and when that fails the checks,
 * the other readings of the seams in order; no reading whose code ends at a seam while more code
 * follows ({@link ReplySeam#dropsCode}); the first whose code passes production's check kept --
 * Python's {@code compile}, as py_compile runs it, and a {@code def run} anywhere in it
 * ({@code SkillManager#checkPythonSyntax}). Each model is shown what the code generation shows it:
 * the whole lines of the part cut off; and writes its part after that.
 * <p>
 * Every outcome is one of: joined as written; mended -- as written, by another reading; repaired
 * -- no reading passes, and a repair is asked for; the same program, a comment, a blank or a line's
 * layout apart; or silently different: other code, other text in a string, other requirements. The
 * exact model, and every one that writes what it was shown again, is silently different at no cut
 * (the census asserts it); and no reading of a reply whose code fails the check ever passes. Where
 * a model does what no reading can tell from another -- the next line without its line break when
 * it equals the line before it, a blank line in a string written as the line break, a prose lead-in
 * inside a string, a line written again deeper indented -- the census counts it, and prints it.
 * One case of a restating model is that: a part that begins with a line break and the line shown,
 * from the blank line above it, cut in the next line's indentation -- the same bytes as the exact
 * continuation of two equal lines in a row; the exact one is read first.
 * <p>
 * The skills: a realistic 76-line one, every cut; it with CRLF line ends, every cut; and -- each
 * sampled -- a 340-line one with blank lines holding blanks, a docstring, triple-quoted SQL, a
 * backslash string, f-strings, a nested function named run and two equal lines in a row; the same
 * indented with tabs; one of long unicode lines; and the long one with a syntax error near its end,
 * whose every passing reading would be code the model did not write. And three parts: two cuts of
 * the long one.
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


    // ---------------------------------------------------------------- production's check, by a worker

    /**
     * The check of SkillManager#checkPythonSyntax -- compile as py_compile compiles, then a function
     * named run anywhere -- for one code after another, by a Python that stays up: the hash of the
     * code's syntax tree, and of it with every string's whitespace folded, or "-".
     */
    static final String WORKER = String.join("\n",
            "import sys, ast, hashlib",
            "inp = sys.stdin.buffer",
            "while True:",
            "    h = inp.readline()",
            "    if not h: break",
            "    src = inp.read(int(h))",
            "    try:",
            "        compile(src, 'skill.py', 'exec', dont_inherit=True)",
            "        tree = ast.parse(src.decode('utf-8'))",
            "        if 'run' in [x.name for x in ast.walk(tree) if isinstance(x, ast.FunctionDef)]:",
            "            res = hashlib.sha256(ast.dump(tree).encode()).hexdigest()",
            "            for x in ast.walk(tree):",
            "                if isinstance(x, ast.Constant) and isinstance(x.value, str):",
            "                    x.value = ' '.join(x.value.split())",
            "            res += '/' + hashlib.sha256(ast.dump(tree).encode()).hexdigest()",
            "        else:",
            "            res = '-'",
            "    except Exception:",
            "        res = '-'",
            "    sys.stdout.write(res + '\\n'); sys.stdout.flush()",
            "");

    static final class Worker implements AutoCloseable {
        final Process p;
        final OutputStream in;
        final BufferedReader out;

        Worker() throws IOException {
            p = new ProcessBuilder("python3", "-I", "-W", "ignore", "-c", WORKER)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            in = new BufferedOutputStream(p.getOutputStream(), 1 << 16);
            out = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
        }

        String check(String code) throws IOException {
            byte[] b = code.getBytes(StandardCharsets.UTF_8);
            in.write((b.length + "\n").getBytes(StandardCharsets.US_ASCII));
            in.write(b);
            in.flush();
            String r = out.readLine();
            if (r == null) throw new IOException("the Python worker ended");
            return r;
        }

        @Override public void close() {
            p.destroy();
        }
    }

    final Map<String, String> checked = new ConcurrentHashMap<>();
    final List<Worker> workers = java.util.Collections.synchronizedList(new ArrayList<>());
    final ThreadLocal<Worker> worker = ThreadLocal.withInitial(() -> {
        try {
            var w = new Worker();
            workers.add(w);
            return w;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    });

    /** The tree hashes of code that passes the check, or null. */
    String tree(String code) {
        String r = checked.computeIfAbsent(code, c -> {
            try {
                return worker.get().check(c);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        return r.equals("-") ? null : r;
    }

    /** A reading's code, when it passes as the code generation checks it; otherwise null. */
    String passing(String text) {
        String code = AgentLoop.extractPythonCode(text);
        if (code == null || !code.contains("def run(")) return null;
        return tree(code) == null ? null : code;
    }

    // ---------------------------------------------------------------- the models

    /** The part a model writes after being shown {@code shown}, a beginning of {@code reply}; null where it does not apply. */
    interface Model {
        String part(String reply, String shown, int codeStart, int codeEnd);
    }

    static int lineStart(String s) {
        return s.lastIndexOf('\n') + 1;
    }

    static int indentEnd(String s, int from) {
        int i = from;
        while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\t')) i++;
        return i;
    }

    /** Whether what was shown ends where a line of the reply ends. */
    static boolean complete(String reply, String shown) {
        int i = shown.length();
        while (i < reply.length() && (reply.charAt(i) == ' ' || reply.charAt(i) == '\t' || reply.charAt(i) == '\r')) i++;
        return i >= reply.length() || reply.charAt(i) == '\n';
    }

    static int nextLineStart(String reply, String shown) {
        int nl = reply.indexOf('\n', shown.length());
        return nl < 0 ? reply.length() : nl + 1;
    }

    /** Where the k-th line above the last one that is not blank begins, or -1. */
    static int aboveNonBlank(String shown, int k) {
        int above = lineStart(shown);
        for (int j = 0; j < k; j++) {
            do {
                if (above == 0) return -1;
                above = shown.lastIndexOf('\n', above - 2) + 1;
            } while (shown.substring(above, shown.indexOf('\n', above)).isBlank());
        }
        return above;
    }

    static boolean inCode(String shown, int codeStart, int codeEnd) {
        return shown.length() > codeStart && shown.length() <= codeEnd && ReplySeam.inPythonFence(shown);
    }

    /** After a fence opened again: the cut line again, at a seam inside one; the next line, after a whole one. */
    static String natural(String reply, String shown) {
        return complete(reply, shown) ? reply.substring(nextLineStart(reply, shown)) : reply.substring(lineStart(shown));
    }

    static final LinkedHashMap<String, Model> MODELS = new LinkedHashMap<>();
    /** The models that write what was shown again: like the exact one, never silently different. */
    static final List<String> STRICT = List.of("exact", "the cut line again", "the cut line again after its indentation",
            "from 1 line above", "from 3 lines above", "the cut line again, 4 blanks less", "```python reopened, exact",
            "```python reopened, then the cut line again", "inside a line: a line break, then the cut line again");

    /**
     * The restating model whose part can be the same bytes as an exact continuation (the class's
     * last paragraph): read as the exact one, at most this often in a census.
     */
    static final String FROM_THE_BLANK_LINE = "from the line directly above, blank or not";

    static {
        MODELS.put("exact", (r, k, cs, ce) -> r.substring(k.length()));
        MODELS.put("the cut line again", (r, k, cs, ce) -> r.substring(lineStart(k)));
        MODELS.put("the cut line again after its indentation", (r, k, cs, ce) -> {
            int ls = lineStart(k), ie = indentEnd(k, ls);
            return ie > ls ? r.substring(ie) : null;
        });
        MODELS.put("from 1 line above", (r, k, cs, ce) -> {
            int a = aboveNonBlank(k, 1);
            return a < 0 ? null : r.substring(a);
        });
        MODELS.put("from 3 lines above", (r, k, cs, ce) -> {
            int a = aboveNonBlank(k, 3);
            return a < 0 ? null : r.substring(a);
        });
        MODELS.put("from the line directly above, blank or not", (r, k, cs, ce) -> {
            int ls = lineStart(k);
            return ls == 0 ? null : r.substring(k.lastIndexOf('\n', ls - 2) + 1);
        });
        MODELS.put("the next line, without its line break", (r, k, cs, ce) ->
                complete(r, k) && nextLineStart(r, k) < r.length() ? r.substring(nextLineStart(r, k)) : null);
        MODELS.put("inside a line: a line break, then the cut line again", (r, k, cs, ce) ->
                complete(r, k) ? null : "\n" + r.substring(lineStart(k)));
        MODELS.put("the cut line again, 4 blanks less", (r, k, cs, ce) -> {
            int ls = lineStart(k), ie = indentEnd(k, ls);
            if (ie - ls < 4 || !k.substring(ls, ie).endsWith("    ")) return null;
            return k.substring(ls, ie - 4) + r.substring(ie);
        });
        MODELS.put("the cut line again, 4 blanks more", (r, k, cs, ce) -> {
            int ls = lineStart(k), ie = indentEnd(k, ls);
            return ie == ls ? null : k.substring(ls, ie) + "    " + r.substring(ie);
        });
        MODELS.put("```python reopened, exact", (r, k, cs, ce) ->
                inCode(k, cs, ce) ? "```python\n" + r.substring(k.length()) : null);
        MODELS.put("```python reopened, then the cut line or the next line", (r, k, cs, ce) ->
                inCode(k, cs, ce) ? "```python\n" + natural(r, k) : null);
        MODELS.put("```python reopened, then the cut line again", (r, k, cs, ce) ->
                inCode(k, cs, ce) ? "```python\n" + r.substring(lineStart(k)) : null);
        for (String fence : List.of("```", "```py", "```python3", "```Python")) {
            MODELS.put((fence.equals("```") ? "bare ```" : fence) + " reopened, then the cut line or the next line",
                    (r, k, cs, ce) -> inCode(k, cs, ce) ? fence + "\n" + natural(r, k) : null);
        }
        MODELS.put("a lead-in, ```python, then the cut line or the next line", (r, k, cs, ce) ->
                inCode(k, cs, ce) ? "Continuing from where it stopped:\n\n```python\n" + natural(r, k) : null);
        MODELS.put("a lead-in without a fence, then the cut line again", (r, k, cs, ce) ->
                inCode(k, cs, ce) ? "Here is the rest of the code:\n\n" + r.substring(lineStart(k)) : null);
    }

    // ---------------------------------------------------------------- reading the parts

    /** What the code generation makes of a skill's reply. */
    record Intended(String reply, String code, String tree, String requirements, boolean broken) {
        int codeStart() {
            return reply.indexOf('\n') + 1;
        }

        int codeEnd() {
            return reply.indexOf("```", codeStart());
        }
    }

    Intended intended(String reply, boolean broken) {
        String code = AgentLoop.extractPythonCode(reply);
        String tree = tree(code);
        assertEquals(broken, tree == null, "the premise: the skill " + (broken ? "fails" : "passes") + " the check");
        return new Intended(reply, code, tree, AgentLoop.extractRequirements(reply), broken);
    }

    /** The outcome of reading {@code parts} as the code generation does, and how it was read. */
    String read(List<String> parts, Intended i) {
        ReplySeam.Reading chosen = null;
        String code = null;
        var first = ReplySeam.first(parts);
        if (!first.dropsCode()) code = passing(first.text());
        if (code != null) {
            chosen = first;
        } else {
            for (ReplySeam.Reading r : ReplySeam.readings(parts)) {
                if (r.dropsCode()) continue;
                code = passing(r.text());
                if (code != null) {
                    chosen = r;
                    break;
                }
            }
        }
        if (chosen == null) return "repaired";
        if (i.broken()) return "SILENT: a reply that fails the check passed";
        boolean sameCode = i.code().equals(code);
        String requirements = AgentLoop.extractRequirements(chosen.text());
        if (sameCode && Objects.equals(i.requirements(), requirements)) {
            return chosen == first ? "joined as written" : "mended (" + chosen.how() + ")";
        }
        if (sameCode) return "SILENT: other requirements";
        String got = tree(code);
        if (i.tree().equals(got)) return "the same program, a comment, a blank or a line's layout apart";
        if (i.tree().split("/")[1].equals(got.split("/")[1])) return "SILENT: other whitespace in a string";
        return "SILENT: OTHER CODE THAT COMPILES";
    }

    /** The parts of one cut and one model: the first in whole lines, the model's after what it was shown. */
    static List<String> twoParts(Intended i, int cut, Model model) {
        String written = i.reply().substring(0, cut);
        String kept = ReplySeam.wholeLines(written);
        if (kept.isEmpty()) return null;
        String part = model.part(i.reply(), kept.stripTrailing(), i.codeStart(), i.codeEnd());
        return part == null ? null : List.of(written, part);
    }

    /** Every cut in {@code cuts}, every model: the outcome by model, and examples of what is silently different. */
    Map<String, Map<String, Integer>> census(String name, Intended i, List<Integer> cuts, StringBuilder report) throws Exception {
        var tally = new ConcurrentSkipListMap<String, ConcurrentSkipListMap<String, AtomicInteger>>();
        var examples = new ConcurrentSkipListMap<String, String>();
        var pool = Executors.newFixedThreadPool(8);
        try {
            var futures = new ArrayList<Future<?>>();
            for (int t = 0; t < 8; t++) {
                final int from = t;
                futures.add(pool.submit(() -> {
                    for (int c = from; c < cuts.size(); c += 8) {
                        int cut = cuts.get(c);
                        for (var m : MODELS.entrySet()) {
                            List<String> parts = twoParts(i, cut, m.getValue());
                            if (parts == null) continue;
                            String outcome = read(parts, i);
                            tally.computeIfAbsent(m.getKey(), x -> new ConcurrentSkipListMap<>())
                                    .computeIfAbsent(outcome, x -> new AtomicInteger()).incrementAndGet();
                            if (outcome.startsWith("SILENT")) {
                                int ls = i.reply().lastIndexOf('\n', cut - 1) + 1;
                                int le = i.reply().indexOf('\n', cut);
                                examples.putIfAbsent(m.getKey() + " | " + outcome, "cut " + cut + " in ["
                                        + i.reply().substring(ls, le < 0 ? i.reply().length() : le).replace("\r", "\\r")
                                        + "] after " + (cut - ls) + " characters");
                            }
                        }
                    }
                    return null;
                }));
            }
            for (var f : futures) f.get();
        } finally {
            pool.shutdownNow();
        }
        return summarized(name + ": " + cuts.size() + " cuts of a " + i.reply().length() + "-character reply", tally,
                examples, report);
    }

    static Map<String, Map<String, Integer>> summarized(String title, Map<String, ? extends Map<String, AtomicInteger>> tally,
                                                        Map<String, String> examples, StringBuilder report) {
        var out = new TreeMap<String, Map<String, Integer>>();
        report.append("=== ").append(title).append('\n');
        for (var m : tally.entrySet()) {
            var counts = new TreeMap<String, Integer>();
            m.getValue().forEach((k, v) -> counts.put(k, v.get()));
            out.put(m.getKey(), counts);
            report.append(String.format("  %-62s %s%n", m.getKey(), counts));
        }
        examples.forEach((k, v) -> report.append("  -- ").append(k).append(": ").append(v).append('\n'));
        return out;
    }

    /**
     * What may not be silently different and is: the exact and restating models at any cut, the one
     * restating model whose part can be an exact continuation's bytes more than once, and -- of a
     * reply that fails the check -- any model.
     */
    static List<String> silent(Map<String, Map<String, Integer>> census, boolean broken) {
        var out = new ArrayList<String>();
        census.forEach((model, counts) -> counts.forEach((outcome, n) -> {
            if (!outcome.startsWith("SILENT")) return;
            if (broken || STRICT.contains(model) || model.equals(FROM_THE_BLANK_LINE) && n > 1) {
                out.add(model + ": " + outcome + " x" + n);
            }
        }));
        return out;
    }

    static String reply(String code, String nl) {
        return "```python" + nl + code + (code.endsWith(nl) ? "" : nl) + "```" + nl + nl + "```requirements" + nl
                + "requests" + nl + "```" + nl;
    }

    static String skill(String file) throws IOException {
        try (InputStream in = SkillCodeSeamCensusTest.class.getResourceAsStream("census/" + file)) {
            return new String(Objects.requireNonNull(in, file).readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static List<Integer> every(String reply) {
        var cuts = new ArrayList<Integer>();
        for (int c = 1; c < reply.length(); c++) cuts.add(c);
        return cuts;
    }

    /** Every cut in the first and last lines of the code and around its fences, and {@code n} more at random. */
    static List<Integer> sampled(String reply, int n, long seed) {
        var cuts = new java.util.TreeSet<Integer>();
        var rnd = new Random(seed);
        int firstLines = reply.indexOf('\n', reply.indexOf('\n', reply.indexOf('\n') + 1) + 1);
        for (int c = 1; c <= firstLines; c++) cuts.add(c);
        int end = reply.lastIndexOf("```", reply.indexOf("```requirements") - 1);
        for (int c = Math.max(1, end - 120); c < reply.length(); c++) cuts.add(c);
        while (cuts.size() < n + firstLines + 120) cuts.add(1 + rnd.nextInt(reply.length() - 1));
        return new ArrayList<>(cuts);
    }

    void closeWorkers() {
        workers.forEach(Worker::close);
    }

    // ---------------------------------------------------------------- the census

    @Test
    @DisplayName("every cut of a realistic skill, LF and CRLF, every model: the exact and restating ones never silently different, the exact one always joined as written")
    void everyCutOfTheSkill() throws Exception {
        var report = new StringBuilder();
        try {
            var lf = intended(reply(CODE, "\n"), false);
            var crlf = intended(reply(CODE.replace("\n", "\r\n"), "\r\n"), false);
            var a = census("census.py", lf, every(lf.reply()), report);
            var b = census("census.py, CRLF", crlf, every(crlf.reply()), report);
            System.out.println(report);
            var wrong = new ArrayList<>(silent(a, false));
            wrong.addAll(silent(b, false));
            assertEquals(List.of(), wrong, report.toString());
            for (var census : List.of(a, b)) {
                for (String model : List.of("exact")) {
                    assertEquals(java.util.Set.of("joined as written"), census.get(model).keySet(), model + "\n" + report);
                }
            }
        } finally {
            closeWorkers();
        }
        // Mutation: keep a cut part as it fell, mid-line -> the exact model loses a character after "    s".
    }

    @Test
    @DisplayName("sampled cuts of a 340-line skill, the same with tabs, long unicode lines: the exact and restating models never silently different; the long one broken near its end: no reading passes")
    void sampledCutsOfLongSkills() throws Exception {
        var report = new StringBuilder();
        try {
            var wrong = new ArrayList<String>();
            String big = skill("big.py");
            for (var e : Map.of("big.py", big, "big_tabs.py", skill("big_tabs.py"), "unicode_long.py",
                    skill("unicode_long.py")).entrySet()) {
                var i = intended(reply(e.getValue(), "\n"), false);
                wrong.addAll(silent(census(e.getKey(), i, sampled(i.reply(), 500, e.getKey().length()), report), false));
            }
            String broken = big.replace("'success': not stats[\"failed\"]}", "'success': not stats[\"failed\"]");
            var i = intended(reply(broken, "\n"), true);
            wrong.addAll(silent(census("big.py broken near its end", i, sampled(i.reply(), 500, 7), report), true));
            System.out.println(report);
            assertEquals(List.of(), wrong, report.toString());
        } finally {
            closeWorkers();
        }
    }

    @Test
    @DisplayName("three parts: two cuts of the long skill, the same model at both seams -- the exact and restating ones never silently different")
    void threeParts() throws Exception {
        var report = new StringBuilder();
        try {
            var i = intended(reply(skill("big.py"), "\n"), false);
            String reply = i.reply();
            var rnd = new Random(7);
            var tally = new TreeMap<String, Map<String, AtomicInteger>>();
            var examples = new TreeMap<String, String>();
            for (int n = 0; n < 400; n++) {
                int c1 = 1 + rnd.nextInt(reply.length() - 2), c2 = 1 + rnd.nextInt(reply.length() - 2);
                if (c1 == c2) continue;
                int lo = Math.min(c1, c2), hi = Math.max(c1, c2);
                for (String name : List.of("exact", "the cut line again", "from 1 line above",
                        "```python reopened, then the cut line or the next line", "the next line, without its line break")) {
                    Model m = MODELS.get(name);
                    String written1 = reply.substring(0, lo);
                    String kept1 = ReplySeam.wholeLines(written1);
                    if (kept1.isEmpty()) continue;
                    String p2 = m.part(reply, kept1.stripTrailing(), i.codeStart(), i.codeEnd());
                    if (p2 == null) continue;
                    // The model's second part, as written, cut where the reply reaches hi.
                    String joined = ReplySeam.join(kept1, p2, ReplySeam.tailOf(written1));
                    int keep = p2.length() - (joined.length() - hi);
                    if (keep <= 0) continue;
                    String written2 = p2.substring(0, keep);
                    String kept2 = ReplySeam.wholeLines(written2);
                    if (kept2.isEmpty()) continue;
                    String sofar = ReplySeam.join(kept1, kept2, ReplySeam.tailOf(written1)).stripTrailing();
                    String outcome;
                    if (!reply.startsWith(sofar)) {
                        // Joined otherwise than the reply at the second seam: what a model writes next is
                        // not known; the two-part census reads such seams.
                        outcome = "the second seam joined otherwise: not followed";
                    } else {
                        String p3 = m.part(reply, sofar, i.codeStart(), i.codeEnd());
                        if (p3 == null) continue;
                        outcome = read(List.of(written1, written2, p3), i);
                    }
                    tally.computeIfAbsent(name, x -> new TreeMap<>()).computeIfAbsent(outcome, x -> new AtomicInteger())
                            .incrementAndGet();
                    if (outcome.startsWith("SILENT")) examples.putIfAbsent(name + " | " + outcome, "cuts " + lo + ", " + hi);
                }
            }
            var census = summarized("big.py in three parts: 400 pairs of cuts", tally, examples, report);
            System.out.println(report);
            assertEquals(List.of(), silent(census, false), report.toString());
        } finally {
            closeWorkers();
        }
    }
}
