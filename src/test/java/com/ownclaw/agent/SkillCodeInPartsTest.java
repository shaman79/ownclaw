package com.ownclaw.agent;

import com.ownclaw.core.TaskCancellationService;
import com.ownclaw.llm.LlmException;
import com.ownclaw.llm.LlmMessage;
import com.ownclaw.llm.LlmResponse;
import com.ownclaw.llm.OutputTruncated;
import com.ownclaw.llm.Replies;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.ownclaw.agent.LoopRig.Call;
import static com.ownclaw.agent.LoopRig.Reply;
import static com.ownclaw.agent.SkillCodegenTest.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A skill's code longer than the model's maximum output, written in parts: each reply cut off at
 * the limit is continued from where it stopped and joined to the ones before, until one ends by
 * itself -- the first reply and every repair alike -- through the real loop and gateway, with a
 * scripted model where the provider stands. The owner, 2026-11-11: if the skill is needed to
 * finish the task, the skill must be finished; a cut-off reply used to be thrown away.
 */
class SkillCodeInPartsTest {

    static final String PART1 = "```python\nimport json\n\ndef run(params):\n    total = 0\n    for i in range(";
    static final String PART2 = "3):\n        total += i\n    if total > 2:\n";
    static final String PART3 = "        return {'output': json.dumps(total), 'success': True}\n"
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

    @Test
    @DisplayName("a skill written in three parts is joined and created, its requirements from the last; each continuation appends and ends with the caller's turn")
    void threeParts(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of());
        realSyntaxCheck(rig, tmp);
        rig.cloud.codegen.add(cutOff(PART1));
        rig.cloud.codegen.add(cutOff(PART2));
        rig.cloud.codegen.add(ends(PART3));

        AgentLoop.Codegen out = rig.loop.generateSkillCode(spec(), task());

        assertNull(out.error(), out.error());
        assertEquals(CODE, out.params().get("code"));
        assertEquals("requests", out.params().get("requirements"));
        List<Call> calls = rig.cloud.calls("codegen");
        assertEquals(3, calls.size());
        assertEquals(2, calls.get(0).messages().size(), "the specification, as before");
        List<LlmMessage> second = calls.get(1).messages();
        assertEquals(calls.get(0).messages(), second.subList(0, 2));
        assertEquals(LlmMessage.assistant(PART1), second.get(2), "the part as the model wrote it, its visible text alone");
        assertEquals(LlmMessage.user(AgentLoop.CONTINUE), second.get(3));
        for (int k = 1; k < calls.size(); k++) {
            List<LlmMessage> before = calls.get(k - 1).messages();
            List<LlmMessage> now = calls.get(k).messages();
            assertEquals(before, now.subList(0, before.size()), "call " + (k + 1) + " rewrote what call " + k + " sent");
            assertEquals(before.size() + 2, now.size(), "a continuation appends the part and the request to go on");
            assertEquals(LlmMessage.Role.USER, now.getLast().role(), "the model's own words last are a prefill, refused");
        }
        assertEquals(List.of("high", "high", "high"), efforts(rig), "at the task's effort throughout");
        // Mutation: send the joined text as one turn, rebuilt each round -> call 3 does not begin with call 2.
    }

    @Test
    @DisplayName("a repair is the complete code again, as long again: cut off, it is continued too")
    void aRepairInParts(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of());
        rig.skills.syntax = code -> code.contains("def broken(:") ? "line 5: invalid syntax" : null;
        rig.cloud.codegen.add(finished(module("def broken(:\n    pass")));
        rig.cloud.codegen.add(cutOff("```python\nimport json\n\ndef run(params):\n    return {'output': json.dumps(1), "));
        rig.cloud.codegen.add(ends("'success': True}\n```\n"));

        AgentLoop.Codegen out = rig.loop.generateSkillCode(spec(), task());

        assertNull(out.error(), out.error());
        assertEquals(module("").strip(), String.valueOf(out.params().get("code")));
        List<Call> calls = rig.cloud.calls("codegen");
        assertEquals(3, calls.size());
        assertTrue(calls.get(1).messages().get(1).content().contains("failed the syntax check"), "the premise: a repair");
        assertEquals(calls.get(1).messages(), calls.get(2).messages().subList(0, 2), "the repair's own request, continued");
        assertEquals(LlmMessage.user(AgentLoop.CONTINUE), calls.get(2).messages().getLast());
        // Mutation: continue the first reply only -> the repair's cut-off part is the reply, and fails.
    }

    @Test
    @DisplayName("a continuation that begins the line again is joined once, and the joined file passes Python's own check")
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
    @DisplayName("a continuation that adds nothing ends the code, saying so: the next would be asked the same")
    void noProgress(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of());
        String part = "```python\nimport json\n\ndef run(params):\n    x = compute(";
        rig.cloud.codegen.add(cutOff(part));
        rig.cloud.codegen.add(cutOff("x = compute("));
        rig.cloud.codegen.add(ends("1)\n    return {'output': '', 'success': True}\n```\n"));
        var ctx = task();

        AgentLoop.Codegen out = rig.loop.generateSkillCode(spec(), ctx);

        assertEquals(2, rig.cloud.calls("codegen").size(), "asked again after a part that added nothing");
        assertNull(out.params());
        assertEquals("ERROR: continuing the code for 'openwrt_audit' got no further: part 2 was cut off again "
                + "([anthropic] the reply reached the model's maximum output of 128,000 tokens and was cut off) and "
                + "added nothing to the " + part.length() + " characters written before it, so nothing was created. "
                + "A smaller skill fits: one job per skill, or split this one into several.", out.error());
        assertEquals(2 * 131_000, ctx.cloudTokens(), "both parts were billed, and counted");
        // Mutation: no progress check -> part 3 is asked for, and the joined code created.
    }

    @Test
    @DisplayName("a reply that spent its whole output reasoning is asked again at low effort, and at low effort a second ends it")
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
                + "'openwrt_audit', even at low effort ([anthropic] the reply reached the model's maximum output of "
                + "128,000 tokens and was cut off), so nothing was created. A smaller skill fits: one job per skill, "
                + "or split this one into several.", out.error());
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
    @DisplayName("asked again at low effort, the code is written, and the parts after it are asked at low effort too")
    void lowEffortWrites(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of());
        realSyntaxCheck(rig, tmp);
        rig.cloud.codegen.add(cutOff(PART1));
        rig.cloud.codegen.add(cutOff(""));
        rig.cloud.codegen.add(cutOff(PART2));
        rig.cloud.codegen.add(ends(PART3));

        AgentLoop.Codegen out = rig.loop.generateSkillCode(spec(), task());

        assertNull(out.error(), out.error());
        assertEquals(CODE, out.params().get("code"));
        assertEquals(List.of("high", "high", "low", "low"), efforts(rig));
        List<Call> calls = rig.cloud.calls("codegen");
        assertEquals(calls.get(1).messages(), calls.get(2).messages(), "part 2 asked again, as it was");
        assertEquals(LlmMessage.assistant(PART2), calls.get(3).messages().get(4), "no turn of the part that wrote nothing");
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
        rig.cloud.codegen.add(cutOff(PART1));
        rig.cloud.codegen.add(cutOff(""));
        rig.cloud.codegen.add(cutOff(PART2));
        rig.cloud.codegen.add(ends(PART3));

        assertNull(rig.loop.generateSkillCode(spec(), ctx).error());

        assertEquals(3 * 131_000 + 7_000, ctx.cloudTokens(), "each part once, the one that wrote nothing too");
        assertEquals(3 * 131_000 + 7_000, rig.jdbc.queryForObject(
                "SELECT SUM(tokens_used) FROM token_usage WHERE user_id = 'u1'", Integer.class));
        assertEquals(List.of(
                "⏩ The code of 'openwrt_audit' reached the model's maximum output of 128,000 tokens -- continuing "
                        + "where it stopped (part 2).",
                "⏩ Part 2 of the code of 'openwrt_audit' reached the model's maximum output of 128,000 tokens before "
                        + "any of it was written: the model spent all of it reasoning. Asking again at low effort, so it "
                        + "reasons less first.",
                "⏩ The code of 'openwrt_audit' reached the model's maximum output of 128,000 tokens -- continuing "
                        + "where it stopped (part 3)."), LiveCallTaskTest.rows(rig));
        // Mutation: count a cut-off part again with the joined reply -> 524,000 + 7,000.
    }

    @Test
    @DisplayName("a continuation refused, not sent, failed or too long for the window ends as the first call would; the tokens billed count")
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
        assertEquals("ERROR: the request for the code of 'openwrt_audit' did not fit ([anthropic] the conversation is "
                + "longer than the model's 1,000,000-token context window), so nothing was created.",
                rig.loop.generateSkillCode(spec(), task()).error());

        rig.cloud.codegen.add(cutOff(PART1));
        rig.cloud.codegen.add(c -> {
            throw new OutputTruncated("anthropic", OutputTruncated.Limit.CONTEXT_WINDOW, 1_000_000,
                    Replies.of("        total += i\n", 870_000, 130_000, 0, 0, "model_context_window_exceeded"));
        });
        assertEquals("ERROR: writing the code for 'openwrt_audit' did not end: [anthropic] a request of 870,000 tokens "
                + "and a reply of 130,000 tokens filled the model's 1,000,000-token context window, so nothing was "
                + "created.", rig.loop.generateSkillCode(spec(), task()).error());
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

    @Test
    @DisplayName("a placeholder the cut fell in is put back once its halves are joined, and stays code")
    void aPlaceholderSplitByTheCut(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of());
        rig.skills.syntax = code -> code.contains("'Fake Jana's WiFi'") || code.contains("<ssid_")
                ? "SyntaxError: the placeholder is not the value" : null;
        var spec = spec();
        spec.put("description", "Says whether the network ssid=\"Fake Jana's WiFi\" is up.");
        String[] placeholder = new String[1];
        rig.cloud.codegen.add(c -> {
            String shown = rig.cloud.calls("codegen").get(0).messages().get(1).content();
            var m = java.util.regex.Pattern.compile("ssid=\"(<ssid_\\d+>)\"").matcher(shown);
            assertTrue(m.find(), shown);
            placeholder[0] = m.group(1);
            return cutOff("```python\nNETWORK = '" + placeholder[0].substring(0, 4)).answer(c);
        });
        rig.cloud.codegen.add(c -> ends(placeholder[0].substring(4) + "'\n" + module("") + "```\n").answer(c));

        AgentLoop.Codegen out = rig.loop.generateSkillCode(spec, task());

        assertNull(out.error(), out.error());
        assertTrue(String.valueOf(out.params().get("code")).startsWith("NETWORK = \"Fake Jana's WiFi\"\n"),
                String.valueOf(out.params().get("code")));
        assertEquals(2, rig.cloud.calls("codegen").size());
        // Mutation: no restoreJoined -> the code holds "<ssid_1>", which the check refuses, and repairs follow.
    }
}
