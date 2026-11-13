package com.ownclaw.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.ownclaw.core.TaskCancellationService;
import com.ownclaw.llm.LlmException;
import com.ownclaw.llm.LlmMessage;
import com.ownclaw.llm.LlmResponse;
import com.ownclaw.llm.OutputTruncated;
import com.ownclaw.llm.Replies;
import com.ownclaw.llm.RequestBodies;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

import static com.ownclaw.agent.LoopRig.Call;
import static com.ownclaw.agent.LoopRig.Reply;
import static com.ownclaw.agent.SkillCodegenTest.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A skill's code longer than the model's maximum output, written in parts: each reply cut off at
 * the limit is kept without its trailing whitespace, sent back as the model's own turn exactly as
 * the model wrote it, and continued from there; the parts joined, the user's values are put back
 * once. The first reply and every repair alike, through the real loop and gateway -- its
 * placeholder table in the database -- with a scripted model where the provider stands. The owner,
 * 2026-11-11: if the skill is needed to finish the task, the skill must be finished.
 */
class SkillCodeInPartsTest {

    /** The first part, cut off in its last line. */
    static final String PART1 = "```python\nimport json\n\ndef run(params):\n    total = 0\n    for i in range(";
    /** What the model is shown of it: its whole lines, the line it was cut in dropped. */
    static final String PART1_SHOWN = "```python\nimport json\n\ndef run(params):\n    total = 0";
    /** What follows PART1_SHOWN as the model was shown it: the cut line again, whole, after the line break. */
    static final String PART2 = "\n    for i in range(3):\n        total += i\n    if total > 2:";
    /** PART2 cut off inside the next line's indentation. */
    static final String PART2_CUT = PART2 + "\n        ";
    /** What follows PART2 as the model was shown it: the line break and the indentation are its own. */
    static final String PART3 = "\n        return {'output': json.dumps(total), 'success': True}\n"
            + "    return {'output': '', 'success': False}\n```\n\n```requirements\nrequests\n```\n";
    static final String CODE = "import json\n\ndef run(params):\n    total = 0\n    for i in range(3):\n"
            + "        total += i\n    if total > 2:\n        return {'output': json.dumps(total), 'success': True}\n"
            + "    return {'output': '', 'success': False}";

    static final String STOPPED = "ERROR: the task was stopped while the code for 'openwrt_audit' was being "
            + "written, so nothing was created.";

    /** A part that ends by itself: 3,000 tokens read, 4,000 written. */
    static Reply ends(String text) {
        return c -> Replies.of(text, 3_000, 4_000, 0, 0, "end_turn");
    }

    /** The syntax check of the system's Python, through the sandbox, as the skill is checked for real. */
    static void realSyntaxCheck(LoopRig rig, Path tmp) throws Exception {
        SkillManager python = SyntaxCheckTest.manager(tmp.resolve("python"));
        rig.skills.syntax = python::checkPythonSyntax;
    }

    static List<String> efforts(LoopRig rig) {
        return rig.cloud.calls("codegen").stream().map(c -> c.config().effort()).toList();
    }

    /** What the model was shown of its reply so far: its own turns of the latest request, as received. */
    static String shown(LoopRig rig) {
        var sb = new StringBuilder();
        for (LlmMessage m : rig.cloud.calls("codegen").getLast().messages()) {
            if (m.role() == LlmMessage.Role.ASSISTANT) sb.append(m.content());
        }
        return sb.toString();
    }

    /** A model that goes on from the exact character of what it was shown: the rest of {@code whole}. */
    static Reply exactly(LoopRig rig, String whole) {
        return c -> {
            String seen = shown(rig);
            assertTrue(whole.startsWith(seen), "the model was shown what it did not write: " + seen);
            return ends(whole.substring(seen.length())).answer(c);
        };
    }

    @Test
    @DisplayName("a skill written in three parts is joined and created, its requirements from the last; each part goes back without its trailing whitespace, appended, the caller's turn last")
    void threeParts(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of());
        realSyntaxCheck(rig, tmp);
        rig.cloud.codegen.add(cutOff(PART1));
        rig.cloud.codegen.add(cutOff(PART2_CUT));   // cut inside the next line's indentation
        rig.cloud.codegen.add(ends(PART3));

        AgentLoop.Codegen out = rig.loop.generateSkillCode(spec(), task());

        assertNull(out.error(), out.error());
        assertEquals(CODE, out.params().get("code"));
        assertEquals("requests", out.params().get("requirements"));
        List<Call> calls = rig.cloud.calls("codegen");
        assertEquals(3, calls.size());
        assertEquals(2, calls.get(0).messages().size(), "the specification, as before");
        assertEquals(LlmMessage.assistant(PART1_SHOWN), calls.get(1).messages().get(2),
                "the part as the model wrote it, in whole lines: the line it was cut in dropped");
        assertEquals(LlmMessage.user(AgentLoop.CONTINUE), calls.get(1).messages().get(3));
        assertEquals(LlmMessage.assistant(PART2), calls.get(2).messages().get(4),
                "without the blanks it was cut in: the model writes the line's whole indentation itself");
        for (int k = 1; k < calls.size(); k++) {
            List<LlmMessage> before = calls.get(k - 1).messages();
            List<LlmMessage> now = calls.get(k).messages();
            assertEquals(before, now.subList(0, before.size()), "call " + (k + 1) + " rewrote what call " + k + " sent");
            assertEquals(before.size() + 2, now.size(), "a continuation appends the part and the request to go on");
            assertEquals(LlmMessage.Role.USER, now.getLast().role(), "the model's own words last are a prefill, refused");
        }
        assertEquals(List.of("high", "low", "low"), efforts(rig),
                "the first part at the task's effort; the parts after it, whose reasoning is never sent back, at low");
        // Mutation: send the joined text as one turn, rebuilt each round -> call 3 does not begin with call 2.
        // Mutation: keep the part's trailing blanks -> the third turn ends in them, and the line lands at 16.
    }

    @Test
    @DisplayName("a cut inside a line's indentation, at every depth, continued by a model going on from exactly what it was shown: the skill as written, no repair")
    void anExactModelAtEveryDepth(@TempDir Path tmp) throws Exception {
        String intended = "import json, os\n\ndef run(params):\n    removed = []\n    for path in params.get('paths', []):\n"
                + "        stale = os.path.getmtime(path) < params['before']\n        if stale:\n"
                + "            removed.append(path)\n            os.remove(path)\n"
                + "    return {'output': json.dumps(removed), 'success': True}";
        String whole = "```python\n" + intended + "\n```\n";
        int line = whole.indexOf("            os.remove(");
        var wrong = new ArrayList<String>();
        for (int k = 0; k <= 12; k++) {
            var rig = new LoopRig(tmp.resolve("k" + k), List.of());
            realSyntaxCheck(rig, tmp.resolve("k" + k));
            rig.cloud.codegen.add(cutOff(whole.substring(0, line + k)));
            rig.cloud.codegen.add(exactly(rig, whole));
            AgentLoop.Codegen out = rig.loop.generateSkillCode(spec(), task());
            if (!intended.equals(out.params() == null ? null : out.params().get("code"))
                    || rig.cloud.calls("codegen").size() != 2) {
                wrong.add("cut after " + k + " blanks: " + (out.error() != null ? out.error() : out.params().get("code")));
            }
        }
        assertEquals(List.of(), wrong);
        // Mutation: keep the cut part's blanks -> after 4 or 8 of 12 the os.remove line moves out of `if stale:`.
    }

    @Test
    @DisplayName("a repair is the complete code again, as long again: cut off, it is continued too, from the repair's own request")
    void aRepairInParts(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of());
        rig.skills.syntax = code -> code.contains("def broken(:") ? "line 5: invalid syntax" : null;
        rig.cloud.codegen.add(finished(module("def broken(:\n    pass")));
        rig.cloud.codegen.add(cutOff("```python\nimport json\n\ndef run(params):\n    return {'output': json.dumps(1), "));
        rig.cloud.codegen.add(ends("\n    return {'output': json.dumps(1), 'success': True}\n```\n"));

        AgentLoop.Codegen out = rig.loop.generateSkillCode(spec(), task());

        assertNull(out.error(), out.error());
        assertEquals(module("").strip(), String.valueOf(out.params().get("code")));
        List<Call> calls = rig.cloud.calls("codegen");
        assertEquals(3, calls.size());
        List<LlmMessage> repair = calls.get(1).messages();
        assertEquals(4, repair.size(), "the specification, the attempt as the model's turn, the error");
        assertTrue(repair.get(2).content().contains("def broken(:"), "the premise: a repair of that attempt");
        assertEquals(repair, calls.get(2).messages().subList(0, 4), "the repair's own request, continued");
        assertEquals(LlmMessage.user(AgentLoop.CONTINUE), calls.get(2).messages().getLast());
        // Mutation: continue the first reply only -> the repair's cut-off part is the reply, and fails.
    }

    @Test
    @DisplayName("a repair sends the latest attempt only, and the gateway forgets what it handed out of the attempts before it")
    void earlierAttemptsForgotten(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of());
        rig.skills.syntax = code -> code.contains("def broken(:") ? "line 5: invalid syntax"
                : code.contains("x = = 1") ? "line 6: cannot assign" : null;
        rig.cloud.codegen.add(cutOff("```python\nimport json\n\ndef run(params):\n    return {'output': '', 'success': True}\n\ndef bro"));
        rig.cloud.codegen.add(ends("\ndef broken(:\n    pass\n```\n"));
        rig.cloud.codegen.add(finished(module("x = = 1")));
        rig.cloud.codegen.add(finished(module("")));

        AgentLoop.Codegen out = rig.loop.generateSkillCode(spec(), task());

        assertNull(out.error(), out.error());
        assertEquals(4, rig.cloud.calls("codegen").size());
        var handedOut = rig.cloud.calls("codegen").getLast().config().egress().handedOut();
        assertEquals(2, handedOut.size(), "the second repair's own reply and the attempt it mended; the first attempt's two parts forgotten");
        // Mutation: forget nothing -> 4 texts held, every attempt's parts for the whole of the code generation.
    }

    @Test
    @DisplayName("a continuation that begins the cut line again is joined once, and the joined file passes Python's own check")
    void aLineBegunAgain(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of());
        realSyntaxCheck(rig, tmp);
        rig.cloud.codegen.add(cutOff("```python\nimport json\n\ndef compute(a, b):\n    return a + b\n\n"
                + "def run(params):\n    x = compute("));
        rig.cloud.codegen.add(ends("```python\n    x = compute(1, 2)\n    return {'output': json.dumps(x), 'success': True}\n```\n"));

        AgentLoop.Codegen out = rig.loop.generateSkillCode(spec(), task());

        assertNull(out.error(), out.error());
        String code = String.valueOf(out.params().get("code"));
        assertTrue(code.endsWith("\ndef run(params):\n    x = compute(1, 2)\n    return {'output': json.dumps(x), 'success': True}"), code);
        assertEquals(2, rig.cloud.calls("codegen").size(), "no repair was needed");
    }

    @Test
    @DisplayName("a continuation that adds nothing -- the cut line again, or no text at all -- ends the code at low effort, saying so")
    void noProgress(@TempDir Path tmp) throws Exception {
        String part = "```python\nimport json\n\ndef run(params):\n    x = compute(";
        // Its last whole line written again and cut off in the next, no text at all, or only blanks.
        for (String nothing : List.of("def run(params):\n    x = comp", "", "\n\n    ")) {
            var rig = new LoopRig(tmp.resolve("n" + nothing.length()), List.of());
            rig.cloud.codegen.add(cutOff(part));
            rig.cloud.codegen.add(cutOff(nothing));
            rig.cloud.codegen.add(ends("1)\n    return {'output': '', 'success': True}\n```\n"));
            var ctx = task();

            AgentLoop.Codegen out = rig.loop.generateSkillCode(spec(), ctx);

            assertEquals(2, rig.cloud.calls("codegen").size(), "asked again after a part that added nothing");
            assertEquals(List.of("high", "low"), efforts(rig));
            assertNull(out.params());
            assertEquals("ERROR: continuing the code for 'openwrt_audit' got no further: part 2, asked at low effort, "
                    + "was cut off again ([anthropic] the reply reached the model's maximum output of 128,000 tokens "
                    + "and was cut off) and added nothing to the " + ReplySeam.wholeLines(part).length()
                    + " characters written before it, "
                    + "so nothing was created. A smaller skill fits: one job per skill, or split this one into "
                    + "several.", out.error());
            assertEquals(2 * 131_000, ctx.cloudTokens(), "both parts were billed, and counted");
        }
        // Mutation: no progress check -> part 3 is asked for, and the joined code created.
    }

    @Test
    @DisplayName("a first part that spent its whole output reasoning is asked again at low effort; at low effort a second ends it")
    void allReasoning(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of());
        rig.cloud.codegen.add(cutOff(""));
        rig.cloud.codegen.add(cutOff(""));
        rig.cloud.codegen.add(finished(module("")));
        var ctx = task();

        AgentLoop.Codegen out = rig.loop.generateSkillCode(spec(), ctx);

        assertEquals(List.of("high", "low"), efforts(rig), "asked again once, at low effort");
        List<Call> calls = rig.cloud.calls("codegen");
        assertEquals(calls.get(0).messages(), calls.get(1).messages(), "the same request: there was nothing to go on from");
        assertEquals("ERROR: the model reasoned through its whole maximum output without writing any of the code for "
                + "'openwrt_audit' ([anthropic] the reply reached the model's maximum output of 128,000 tokens and was "
                + "cut off), even at low effort, so nothing was created. A smaller skill fits: one job per skill, or "
                + "split this one into several.", out.error());
        assertEquals(2 * 131_000, ctx.cloudTokens());

        var owners = new LoopRig(tmp.resolve("low"), List.of());
        owners.cloud.codegen.add(cutOff("   \n\n"));
        owners.cloud.codegen.add(finished(module("")));
        var atLow = task();
        atLow.setOptions(new TaskOptions(false, true, "low"));
        assertTrue(owners.loop.generateSkillCode(spec(), atLow).error().contains("even at low effort"),
                "blanks are nothing written; at the owner's low effort there is no less to ask for");
        assertEquals(1, owners.cloud.calls("codegen").size());
        // Mutation: ask again at the same effort -> efforts [high, high].
        // Mutation: no stop at low effort -> the script's third reply is asked for, and the skill created.
    }

    @Test
    @DisplayName("asked again at low effort, the code is written, and every part after is asked at low effort too")
    void lowEffortWrites(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of());
        realSyntaxCheck(rig, tmp);
        rig.cloud.codegen.add(cutOff(""));
        rig.cloud.codegen.add(cutOff(PART1));
        rig.cloud.codegen.add(cutOff(PART2_CUT));
        rig.cloud.codegen.add(ends(PART3));

        AgentLoop.Codegen out = rig.loop.generateSkillCode(spec(), task());

        assertNull(out.error(), out.error());
        assertEquals(CODE, out.params().get("code"));
        assertEquals(List.of("high", "low", "low", "low"), efforts(rig));
        List<Call> calls = rig.cloud.calls("codegen");
        assertEquals(calls.get(0).messages(), calls.get(1).messages(), "part 1 asked again, as it was");
        assertEquals(LlmMessage.assistant(PART2), calls.get(3).messages().get(4), "no turn of the part that wrote nothing");
    }

    @Test
    @DisplayName("a provider that is sent no effort is not asked again -- it would be the same request -- and the error says what happened")
    void aProviderWithoutEffort(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of());
        rig.cloud.effort = false;
        rig.cloud.codegen.add(cutOff(""));
        rig.cloud.codegen.add(finished(module("")));

        AgentLoop.Codegen out = rig.loop.generateSkillCode(spec(), task());

        assertEquals(1, rig.cloud.calls("codegen").size(), "asked again byte for byte");
        assertEquals("ERROR: the model reasoned through its whole maximum output without writing any of the code for "
                + "'openwrt_audit' ([anthropic] the reply reached the model's maximum output of 128,000 tokens and was "
                + "cut off); asking again would be the same request -- this model is sent no thinking effort -- so "
                + "nothing was created. A smaller skill fits: one job per skill, or split this one into several.",
                out.error());
        assertFalse(out.error().contains("low effort"));

        var parts = new LoopRig(tmp.resolve("parts"), List.of());
        parts.cloud.effort = false;
        parts.cloud.codegen.add(cutOff(PART1));
        parts.cloud.codegen.add(cutOff("3):"));
        parts.cloud.codegen.add(cutOff(""));
        AgentLoop.Codegen stuck = parts.loop.generateSkillCode(spec(), task());
        assertEquals(List.of("high", "high", "high"), efforts(parts), "no effort to lower");
        assertTrue(stuck.error().startsWith("ERROR: continuing the code for 'openwrt_audit' got no further: part 3 was "
                + "cut off again"), stuck.error());
    }

    @Test
    @DisplayName("Stop ends the code between two parts and during one; every part's call is closed")
    void stopDuringParts(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of());
        var ctx = task();
        var started = new CopyOnWriteArrayList<String>();
        var ended = new CopyOnWriteArrayList<String>();
        ctx.setCallWatch(call -> {
            started.add(call.purpose());
            return () -> ended.add(call.purpose());
        });
        rig.cloud.codegen.add(cutOff(PART1));
        rig.cloud.codegen.add(c -> {
            c.progress().onProgress();
            ctx.stall("test: stopped during part 2");
            c.progress().onProgress();
            throw new AssertionError("the hook of a stopped task let part 2 go on");
        });

        AgentLoop.Codegen out = rig.loop.generateSkillCode(spec(), ctx);

        assertEquals(STOPPED, out.error());
        assertEquals(List.of("the code of skill 'openwrt_audit'", "continuing the code of skill 'openwrt_audit' (part 2)"),
                started, "each part a live call of its own");
        assertEquals(started, ended, "and each closed");

        var between = new LoopRig(tmp.resolve("between"), List.of());
        var stopped = task();
        stopped.setChat(new TaskChat(stopped, between.chat.createSession("u1", "Routers"), TaskChat.Channel.WEB,
                between.chat, between.emitter, null, null, null));
        var cancellation = new TaskCancellationService();
        stopped.setExternalCancel(() -> cancellation.isCancelled("u1", stopped.taskId(), stopped.startTimeMs()));
        between.cloud.codegen.add(c -> {
            cancellation.requestAll("u1", "you pressed Stop");
            return cutOff(PART1).answer(c);
        });
        between.cloud.codegen.add(ends(PART2 + PART3));
        assertEquals(STOPPED, between.loop.generateSkillCode(spec(), stopped).error());
        assertEquals(1, between.cloud.calls("codegen").size(), "a part was asked for after Stop");
        assertEquals(List.of(), LiveCallTaskTest.rows(between), "the chat told of a part that was never asked for");

        // Stop, then the connection breaks off: the part is not asked of the cloud once more.
        var retry = new LoopRig(tmp.resolve("retry"), List.of());
        var stoppedThen = task();
        var cancelled = new TaskCancellationService();
        stoppedThen.setExternalCancel(() -> cancelled.isCancelled("u1", stoppedThen.taskId(), stoppedThen.startTimeMs()));
        retry.cloud.codegen.add(cutOff(PART1));
        retry.cloud.codegen.add(c -> {
            cancelled.requestAll("u1", "you pressed Stop");
            return LocalModeTest.noInternet().answer(c);
        });
        retry.cloud.codegen.add(ends(PART2 + PART3));
        assertEquals(STOPPED, retry.loop.generateSkillCode(spec(), stoppedThen).error());
        assertEquals(2, retry.cloud.calls("codegen").size(), "a part was asked again after Stop");
        // Mutation: no stop check before a part -> part 2 is asked for after Stop, and asked again.
    }

    @Test
    @DisplayName("every part's billed tokens count once, and the owner's chat is told of each continuation")
    void eachPartCountedAndSaid(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of());
        String session = rig.chat.createSession("u1", "Routers");
        var ctx = task();
        ctx.setChat(new TaskChat(ctx, session, TaskChat.Channel.WEB, rig.chat, rig.emitter, null, null, null));
        rig.cloud.codegen.add(cutOff(""));
        rig.cloud.codegen.add(cutOff(PART1));
        rig.cloud.codegen.add(cutOff(PART2_CUT));
        rig.cloud.codegen.add(ends(PART3));

        assertNull(rig.loop.generateSkillCode(spec(), ctx).error());

        assertEquals(3 * 131_000 + 7_000, ctx.cloudTokens(), "each part once, the one that wrote nothing too");
        assertEquals(3 * 131_000 + 7_000, rig.jdbc.queryForObject(
                "SELECT SUM(tokens_used) FROM token_usage WHERE user_id = 'u1'", Integer.class));
        assertEquals(List.of(
                "⏩ The code of 'openwrt_audit' reached the model's maximum output of 128,000 tokens before any of it "
                        + "was written: the model spent all of it reasoning. Asking again at low effort, so it reasons "
                        + "less first.",
                "⏩ The code of 'openwrt_audit' reached the model's maximum output of 128,000 tokens -- continuing "
                        + "where it stopped (part 2).",
                "⏩ The code of 'openwrt_audit' reached the model's maximum output of 128,000 tokens -- continuing "
                        + "where it stopped (part 3)."), LiveCallTaskTest.rows(rig));
        // Mutation: count a cut-off part again -> 524,000 + 7,000.
    }

    @Test
    @DisplayName("a continuation refused, not sent or failed ends as the first call would; once the parts fill the window, the code outgrew it")
    void aContinuationThatFails(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of());
        rig.cloud.codegen.add(cutOff(PART1));
        rig.cloud.codegen.add(c -> {
            throw new com.ownclaw.llm.ProviderRefused("anthropic", new LlmResponse("", List.of(), null, "refusal",
                    "cyber", "claude-opus-5", 128_000, 1_000_000,
                    List.of(new LlmResponse.Usage("claude-opus-5", 2_000, 300, 0, 0))));
        });
        var declined = task();
        assertTrue(rig.loop.generateSkillCode(spec(), declined).error().startsWith(
                "ERROR: the model declined to write the code for 'openwrt_audit' ([anthropic] the model declined"));
        assertEquals(131_000 + 2_300, declined.cloudTokens());

        rig.cloud.codegen.add(cutOff(PART1));
        rig.cloud.codegen.add(c -> { throw new com.ownclaw.llm.EgressRefused("anthropic", 2, "openwrt_audit", 1, "user", 10); });
        assertTrue(rig.loop.generateSkillCode(spec(), task()).error().startsWith(
                "ERROR: the request for the code of 'openwrt_audit' was not sent ("));

        rig.cloud.codegen.add(cutOff(PART1));
        rig.cloud.codegen.add(c -> { throw new LlmException("anthropic", "HTTP 400: bad request", 400, null); });
        assertEquals("ERROR: the request for the code of 'openwrt_audit' failed ([anthropic] HTTP 400: bad request), "
                + "so nothing was created.", rig.loop.generateSkillCode(spec(), task()).error());

        // Once the parts fill the window, the provider refuses the request as too long for it.
        rig.cloud.codegen.add(cutOff(PART1));
        rig.cloud.codegen.add(c -> {
            throw new OutputTruncated("anthropic", OutputTruncated.Limit.CONTEXT_WINDOW, 1_000_000, null);
        });
        assertEquals("ERROR: the code for 'openwrt_audit' outgrew the model's context window: written in 1 part so "
                + "far, its " + ReplySeam.wholeLines(PART1).length() + " characters and the request no longer fit ([anthropic] the "
                + "conversation is longer than the model's 1,000,000-token context window), so nothing was created. "
                + "A smaller skill fits: one job per skill, or split this one into several.",
                rig.loop.generateSkillCode(spec(), task()).error());

        rig.cloud.codegen.add(cutOff(PART1));
        rig.cloud.codegen.add(cutOff(PART2_CUT));
        rig.cloud.codegen.add(c -> {
            throw new OutputTruncated("anthropic", OutputTruncated.Limit.CONTEXT_WINDOW, 1_000_000,
                    Replies.of("        total += i\n", 870_000, 130_000, 0, 0, "model_context_window_exceeded"));
        });
        assertEquals("ERROR: the code for 'openwrt_audit' outgrew the model's context window: written in 2 parts so "
                + "far, its " + ReplySeam.join(ReplySeam.wholeLines(PART1), ReplySeam.wholeLines(PART2_CUT)).length()
                + " characters and the request no longer fit ([anthropic] a "
                + "request of 870,000 tokens and a reply of 130,000 tokens filled the model's 1,000,000-token context "
                + "window), so nothing was created. A smaller skill fits: one job per skill, or split this one into "
                + "several.", rig.loop.generateSkillCode(spec(), task()).error());

        rig.cloud.codegen.add(c -> {
            throw new OutputTruncated("anthropic", OutputTruncated.Limit.CONTEXT_WINDOW, 1_000_000, null);
        });
        assertTrue(rig.loop.generateSkillCode(spec(), task()).error().startsWith("ERROR: the request for the code of "
                + "'openwrt_audit' did not fit ("), "the specification itself too long is said as before");

        // A repair carries the code it mends: its first part refused by the window is the code outgrowing it.
        rig.skills.syntax = code -> code.contains("def broken(:") ? "line 5: invalid syntax" : null;
        String broken = module("def broken(:\n    pass");
        rig.cloud.codegen.add(finished(broken));
        rig.cloud.codegen.add(c -> {
            throw new OutputTruncated("anthropic", OutputTruncated.Limit.CONTEXT_WINDOW, 1_000_000, null);
        });
        assertEquals("ERROR: the code for 'openwrt_audit' outgrew the model's context window: its repair, with the "
                + ("```python\n" + broken + "\n```\n").length() + " characters of the code it mends, no "
                + "longer fit ([anthropic] the conversation is longer than the model's 1,000,000-token context window), "
                + "so nothing was created. A smaller skill fits: one job per skill, or split this one into several.",
                rig.loop.generateSkillCode(spec(), task()).error());
        // Mutation: no carried code for a repair -> "the request for the code of 'openwrt_audit' did not fit".
        assertTrue(rig.cloud.codegen.isEmpty());
    }

    @Test
    @DisplayName("a continuation whose connection broke off is asked again as it was; a cloud given up on hands the code to the local model, from the start")
    void aContinuationWithoutTheCloud(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of(), 600, LocalModeTest.localModel());
        rig.cloud.codegen.add(cutOff(PART1));
        rig.cloud.codegen.add(LocalModeTest.noInternet());
        rig.cloud.codegen.add(ends(PART2 + PART3));
        AgentLoop.Codegen out = rig.loop.generateSkillCode(spec(), task());
        assertNull(out.error(), out.error());
        assertEquals(CODE, out.params().get("code"));
        List<Call> calls = rig.cloud.calls("codegen");
        assertEquals(3, calls.size());
        assertEquals(calls.get(1).messages(), calls.get(2).messages(), "the same continuation, asked once more");

        // A part cut off at the maximum output is the cloud answering: a connection that fails after it
        // is the first in a row again, asked once more -- not the second, which hands the task over.
        var again = new LoopRig(tmp.resolve("again"), List.of(), 600, LocalModeTest.localModel());
        again.cloud.codegen.add(LocalModeTest.noInternet());
        again.cloud.codegen.add(cutOff(PART1));
        again.cloud.codegen.add(LocalModeTest.noInternet());
        again.cloud.codegen.add(ends(PART2 + PART3));
        var onCloud = task();
        assertEquals(CODE, again.loop.generateSkillCode(spec(), onCloud).params().get("code"));
        assertFalse(onCloud.onLocal(), "handed to the local model after one failure following a reply");
        assertEquals(4, again.cloud.calls("codegen").size());

        var local = LocalModeTest.localModel();
        var away = new LoopRig(tmp.resolve("away"), List.of(), 600, local);
        away.cloud.codegen.add(cutOff(PART1));
        away.cloud.codegen.add(LocalModeTest.noInternet());
        away.cloud.codegen.add(LocalModeTest.noInternet());
        local.codegen.add(finished(module("")));
        var ctx = task();
        AgentLoop.Codegen written = away.loop.generateSkillCode(spec(), ctx);
        assertNull(written.error(), written.error());
        assertEquals(module("").strip(), String.valueOf(written.params().get("code")));
        assertTrue(ctx.onLocal());
        assertEquals(2, local.calls("codegen").getFirst().messages().size(), "the local model's own prompt, from the start");
        assertTrue(local.calls("codegen").getFirst().messages().getFirst().content().contains("You are a local model"));
    }

    // ── fences ──

    @Test
    @DisplayName("cut right after the ```python line and the fence opened again: the code keeps its line, and its imports")
    void cutRightAfterTheOpener(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of());
        realSyntaxCheck(rig, tmp);
        rig.cloud.codegen.add(cutOff("Here is the skill:\n```python\n"));
        rig.cloud.codegen.add(ends("```python\nimport json\n\ndef run(params):\n"
                + "    return {'output': json.dumps(1), 'success': True}\n```\n"));

        AgentLoop.Codegen out = rig.loop.generateSkillCode(spec(), task());

        assertNull(out.error(), out.error());
        assertEquals(module("").strip(), out.params().get("code"), "imports and all");
        // Mutation: no line break kept -> "```pythonimport json", the code begins at def run, json undefined.
    }

    @Test
    @DisplayName("a bare ``` beginning a continuation is the model closing the fence: the note after it stays out of the code")
    void aClosingFenceAtTheSeam(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of());
        realSyntaxCheck(rig, tmp);
        rig.cloud.codegen.add(cutOff("```python\nimport json\n\ndef run(params):\n    return {'output': json.dumps(1), 'success': True}\n"));
        rig.cloud.codegen.add(ends("\n```\n\nThe skill returns 1 as JSON.\n\n```requirements\nrequests\n```\n"));

        AgentLoop.Codegen out = rig.loop.generateSkillCode(spec(), task());

        assertNull(out.error(), out.error());
        assertEquals(module("").strip(), out.params().get("code"));
        assertEquals("requests", out.params().get("requirements"));
        assertEquals(2, rig.cloud.calls("codegen").size(), "no repair");
        // Mutation: drop a bare ``` as a fence opened again -> the note is in the code; the reading
        // "appended as written" mends it here, and ReplySeamTest#aBareFenceIsKept fails.
    }

    @Test
    @DisplayName("a lead-in before the fence opened again fails the check as joined; the seam read without it passes, and no repair is asked for")
    void aLeadInBeforeTheFence(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of());
        realSyntaxCheck(rig, tmp);
        rig.cloud.codegen.add(cutOff("```python\nimport json\n\ndef run(params):\n    x = compute("));
        rig.cloud.codegen.add(ends("Continuing from where it stopped:\n\n```python\n    x = compute(1, 2)\n"
                + "    return {'output': json.dumps(x), 'success': True}\n```\n"));

        AgentLoop.Codegen out = rig.loop.generateSkillCode(spec(), task());

        assertNull(out.error(), out.error());
        assertEquals("import json\n\ndef run(params):\n    x = compute(1, 2)\n    return {'output': json.dumps(x), "
                + "'success': True}", out.params().get("code"));
        assertEquals(2, rig.cloud.calls("codegen").size(), "mended here, not by a repair of the whole code");
        // Mutation: no other readings -> a repair is asked for, and the script has none.
    }

    @Test
    @DisplayName("a restart from a line higher up fails the check as joined, and the seam read so passes")
    void aRestartFromHigherUp(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of());
        realSyntaxCheck(rig, tmp);
        rig.cloud.codegen.add(cutOff("```python\nimport json\n\ndef run(params):\n    total = 0\n    x = (total +"));
        rig.cloud.codegen.add(ends("    total = 0\n    x = (total + 1)\n    return {'output': json.dumps(x), 'success': True}\n```\n"));

        AgentLoop.Codegen out = rig.loop.generateSkillCode(spec(), task());

        assertNull(out.error(), out.error());
        assertEquals("import json\n\ndef run(params):\n    total = 0\n    x = (total + 1)\n"
                + "    return {'output': json.dumps(x), 'success': True}", out.params().get("code"));
        assertEquals(2, rig.cloud.calls("codegen").size());
    }

    // ── the user's values ──

    static Map<String, Object> backupSpec() {
        var spec = spec();
        spec.put("description", "Backs up the router. Its network is ssid=\"ExampleHome5G\", the guest one ssid=\"AP7\".");
        return spec;
    }

    /** The placeholders the model was shown for the spec's SSIDs, in order. */
    static List<String> ssidPlaceholders(LoopRig rig) {
        String shown = rig.cloud.calls("codegen").get(0).messages().get(1).content();
        var m = Pattern.compile("ssid=\"(<ssid_\\d+>)\"").matcher(shown);
        var out = new ArrayList<String>();
        while (m.find()) out.add(m.group(1));
        return out;
    }

    static List<String> learned(LoopRig rig) {
        return rig.jdbc.queryForList("SELECT value FROM privacy_placeholders WHERE user_id = 'u1' ORDER BY kind, n",
                String.class);
    }

    @Test
    @DisplayName("a part goes back as the model wrote it: a value inside a path, or one known only by its context, never leaves; the code holds the values")
    void valuesNeverGoBackOut(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of());
        String[] ph = new String[2];
        rig.cloud.codegen.add(c -> {
            var shown = ssidPlaceholders(rig);
            assertEquals(2, shown.size(), "the premise: both values were replaced in the specification");
            ph[0] = shown.get(0);
            ph[1] = shown.get(1);
            return cutOff("```python\nimport json\n\nHOME = '" + ph[0] + "'\nGUEST = '" + ph[1] + "'\n"
                    + "ARCHIVE = '/tmp/" + ph[0] + "_backup.tar'\n\ndef run(params):\n    x = (").answer(c);
        });
        // Shown its whole lines, the model writes the line it was cut in again.
        rig.cloud.codegen.add(ends("\n    x = (1)\n    return {'output': json.dumps([HOME, GUEST, ARCHIVE, x]), 'success': True}\n```\n"));

        AgentLoop.Codegen out = rig.loop.generateSkillCode(backupSpec(), task());

        assertNull(out.error(), out.error());
        String turn = rig.cloud.calls("codegen").get(1).messages().get(2).content();
        assertTrue(turn.contains("HOME = '" + ph[0] + "'") && turn.contains("/tmp/" + ph[0] + "_backup.tar"), turn);
        assertFalse(turn.contains("ExampleHome5G") || turn.contains("AP7"), "sent to the cloud: " + turn);
        String code = String.valueOf(out.params().get("code"));
        assertTrue(code.contains("HOME = 'ExampleHome5G'\nGUEST = 'AP7'\nARCHIVE = '/tmp/ExampleHome5G_backup.tar'"), code);
        assertEquals(List.of("ExampleHome5G", "AP7"), learned(rig), "learned from the specification, and only from it");
        // Mutation: hand the parts back restored, as before -> the turn holds the path's value and 'AP7'.
    }

    @Test
    @DisplayName("a repair sends nothing the values were put back into: the attempt as written, the error by its line's number")
    void aRepairSendsNoValue(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of());
        rig.skills.syntax = code -> code.contains("def broken(:") ? "  File \"skill.py\", line 6\n    def broken(:\n"
                + "              ^\nSyntaxError: invalid syntax" : null;
        String[] ph = new String[2];
        rig.cloud.codegen.add(c -> {
            var shown = ssidPlaceholders(rig);
            ph[0] = shown.get(0);
            ph[1] = shown.get(1);
            return ends("```python\nimport json\nGUEST = '" + ph[1] + "'\nARCHIVE = '/tmp/" + ph[0] + "_backup.tar'\n"
                    + "def run(params):\n    return {'output': '', 'success': True}\ndef broken(:\n    pass\n```\n").answer(c);
        });
        rig.cloud.codegen.add(c -> ends("```python\nimport json\nGUEST = '" + ph[1] + "'\n\ndef run(params):\n"
                + "    return {'output': GUEST, 'success': True}\n```\n").answer(c));

        AgentLoop.Codegen out = rig.loop.generateSkillCode(backupSpec(), task());

        assertNull(out.error(), out.error());
        assertTrue(String.valueOf(out.params().get("code")).contains("GUEST = 'AP7'"));
        List<LlmMessage> repair = rig.cloud.calls("codegen").get(1).messages();
        for (LlmMessage m : repair) {
            assertFalse(m.content().contains("ExampleHome5G") || m.content().contains("'AP7'"),
                    "the repair sent a value: " + m.content());
        }
        assertEquals("The code failed Python's syntax check at line 6: SyntaxError: invalid syntax.\nLine 6, as you "
                + "wrote it:\ndef broken(:\n\nFix the error. Return the COMPLETE corrected code in a ```python fence, and "
                + "the ```requirements fence as asked.", repair.getLast().content());
        assertEquals(List.of("ExampleHome5G", "AP7"), learned(rig));
    }

    @Test
    @DisplayName("the model's own literals are not learned as the user's, and the request stays append-only")
    void nothingLearnedFromTheModelsCode(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of());
        rig.cloud.codegen.add(cutOff("```python\nimport json, subprocess\n\nDEFAULTS = {'ssid': 'OpenWrt', 'hostname': 'gateway'}\n"
                + "UCI = \"uci set wireless.@wifi-iface[0].ssid='OpenWrt'\"\n\ndef run(params):\n    x = ("));
        rig.cloud.codegen.add(cutOff("1 +"));
        rig.cloud.codegen.add(ends(" 2)\n    return {'output': json.dumps([DEFAULTS, UCI, x]), 'success': True}\n```\n"));

        AgentLoop.Codegen out = rig.loop.generateSkillCode(spec(), task());

        assertNull(out.error(), out.error());
        assertEquals(List.of(), learned(rig), "a default SSID and hostname of the model's are no identifiers of the user's");
        List<Call> calls = rig.cloud.calls("codegen");
        assertEquals(calls.get(1).messages(), calls.get(2).messages().subList(0, 4), "round 3 begins with round 2");
        assertTrue(calls.get(1).messages().get(2).content().contains("'ssid': 'OpenWrt'"), "sent as the model wrote it");

        // A repair's quoted line would teach the filter too: such a line is told by its number only.
        var repaired = new LoopRig(tmp.resolve("repair"), List.of());
        repaired.skills.syntax = code -> code.contains("broken") ? "  File \"skill.py\", line 3\n    DEFAULTS = "
                + "{'ssid': 'OpenWrt'} broken\n    ^\nSyntaxError: invalid syntax" : null;
        repaired.cloud.codegen.add(ends("```python\nimport json\n\nDEFAULTS = {'ssid': 'OpenWrt'} broken\n\ndef run(params):\n"
                + "    return {'output': '', 'success': True}\n```\n"));
        repaired.cloud.codegen.add(finished(module("")));
        assertNull(repaired.loop.generateSkillCode(spec(), task()).error());
        assertEquals("The code failed Python's syntax check at line 3: SyntaxError: invalid syntax.\n\nFix the error. "
                + "Return the COMPLETE corrected code in a ```python fence, and the ```requirements fence as asked.",
                repaired.cloud.calls("codegen").get(1).messages().getLast().content());
        assertEquals(List.of(), learned(repaired));
    }

    @Test
    @DisplayName("a placeholder or its quote split across a seam is put back once, whole, and stays code")
    void aPlaceholderSplitByTheCut(@TempDir Path tmp) throws Exception {
        var spec = spec();
        spec.put("description", "Says whether the network ssid=\"Example Guest's WiFi\" is up.");
        // The cut inside the placeholder, between it and its closing quote, between its opening quote and it.
        List<int[]> cuts = List.of(new int[] {4}, new int[] {-1}, new int[] {0});
        for (int[] cut : cuts) {
            var rig = new LoopRig(tmp.resolve("c" + cut[0]), List.of());
            realSyntaxCheck(rig, tmp.resolve("c" + cut[0]));
            String[] whole = new String[1];
            rig.cloud.codegen.add(c -> {
                String shown = rig.cloud.calls("codegen").get(0).messages().get(1).content();
                var m = Pattern.compile("ssid=\"(<ssid_\\d+>)\"").matcher(shown);
                assertTrue(m.find(), shown);
                whole[0] = "```python\nNETWORK = '" + m.group(1) + "'\n" + module("") + "```\n";
                int at = whole[0].indexOf(m.group(1)) + (cut[0] >= 0 ? cut[0] : m.group(1).length());
                return cutOff(whole[0].substring(0, at)).answer(c);
            });
            rig.cloud.codegen.add(c -> exactly(rig, whole[0]).answer(c));

            AgentLoop.Codegen out = rig.loop.generateSkillCode(spec, task());

            assertNull(out.error(), out.error());
            assertTrue(String.valueOf(out.params().get("code")).startsWith("NETWORK = \"Example Guest's WiFi\"\n"),
                    String.valueOf(out.params().get("code")));
            assertEquals(2, rig.cloud.calls("codegen").size(), "no repair");
        }
        // Mutation: no restore of the joined text -> "<ssid_1>" in the code, which the check refuses.
    }

    @Test
    @DisplayName("the values are put back exactly once: a placeholder's shape the user wrote as text comes back as that text")
    void restoredOnce(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of());
        var spec = spec();
        spec.put("description", "The router is ssid=\"ExampleHome5G\". Log lines hold the marker <ssid_1>; count them.");
        rig.cloud.codegen.add(c -> {
            String shown = rig.cloud.calls("codegen").get(0).messages().get(1).content();
            var m = Pattern.compile("marker (<text_\\d+>)").matcher(shown);
            assertTrue(m.find(), "the premise: the literal is shown as one of its own: " + shown);
            return cutOff("```python\nimport json\n\nMARK = '" + m.group(1) + "'\n").answer(c);
        });
        rig.cloud.codegen.add(ends("\ndef run(params):\n    return {'output': json.dumps(MARK), 'success': True}\n```\n"));

        AgentLoop.Codegen out = rig.loop.generateSkillCode(spec, task());

        assertNull(out.error(), out.error());
        assertTrue(String.valueOf(out.params().get("code")).contains("MARK = '<ssid_1>'\n"),
                "put back twice, the literal turns into the SSID: " + out.params().get("code"));
        // Mutation: put the values back in each part and again in the join -> MARK = 'ExampleHome5G'.
    }

    @Test
    @DisplayName("through the real gateway and provider: each round sends the last round's messages as they were, byte for byte, the newest part marked, the caller's turn last")
    void theRequestsOnTheWire(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of());
        rig.cloud.codegen.add(c -> {
            String ph = ssidPlaceholders(rig).getFirst();
            return cutOff("```python\nimport json\n\nHOME = '" + ph + "'\nDEFAULTS = {'ssid': 'OpenWrt'}\n\n"
                    + "def run(params):\n    x = (").answer(c);
        });
        rig.cloud.codegen.add(cutOff("\n    x = (1 +\n        "));
        rig.cloud.codegen.add(ends("\n        2)\n    return {'output': json.dumps([HOME, x]), 'success': True}\n```\n"));

        assertNull(rig.loop.generateSkillCode(backupSpec(), task()).error());

        List<Call> calls = rig.cloud.calls("codegen");
        var bodies = new ArrayList<JsonNode>();
        for (Call c : calls) bodies.add(RequestBodies.anthropic(c.messages(), c.config(), "claude-opus-5-5"));
        for (int k = 1; k < bodies.size(); k++) {
            JsonNode before = bodies.get(k - 1).path("messages");
            JsonNode now = bodies.get(k).path("messages");
            assertEquals(bodies.get(k - 1).path("system"), bodies.get(k).path("system"));
            for (int i = 0; i < before.size(); i++) {
                assertEquals(before.get(i).path("role"), now.get(i).path("role"));
                assertEquals(text(before.get(i).path("content")), text(now.get(i).path("content")),
                        "round " + (k + 1) + " message " + i + " is not the bytes round " + k + " sent");
            }
            int newestPart = now.size() - 2;
            assertEquals("assistant", now.get(newestPart).path("role").asText());
            assertEquals("{\"type\":\"ephemeral\"}", now.get(newestPart).path("content").get(0).path("cache_control").toString(),
                    "the sliding mark on the part just written");
            assertEquals("user", now.get(now.size() - 1).path("role").asText(), "no prefill");
            assertFalse(text(now.get(newestPart).path("content")).matches("(?s).*\\s"), "no trailing whitespace in the model's turn");
            assertEquals("low", bodies.get(k).path("output_config").path("effort").asText(), "a continuation at low effort");
        }
        assertEquals("high", bodies.get(0).path("output_config").path("effort").asText());
    }

    private static String text(JsonNode content) {
        return content.isArray() ? content.get(0).path("text").asText() : content.asText();
    }
}
