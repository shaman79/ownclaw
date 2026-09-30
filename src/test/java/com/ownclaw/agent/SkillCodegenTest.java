package com.ownclaw.agent;

import com.ownclaw.core.TaskCancellationService;
import com.ownclaw.llm.LlmException;
import com.ownclaw.llm.LlmMessage;
import com.ownclaw.llm.LlmProvider;
import com.ownclaw.llm.LlmRequestConfig;
import com.ownclaw.llm.LlmResponse;
import com.ownclaw.llm.Replies;
import com.ownclaw.privacy.Label;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.ownclaw.agent.LoopRig.Reply;
import static org.junit.jupiter.api.Assertions.*;

/**
 * skill_create's code generation through the real gateway, with a scripted model where the
 * provider stands. 29 Sep: four replies per skill_create each cut off at the output limit and sent
 * back for repair with every earlier attempt resent twice, ten minutes with nothing marking
 * progress, and a cut-off reply reported to the owner as an outage.
 */
class SkillCodegenTest {

    static Map<String, Object> spec() {
        Map<String, Object> p = new HashMap<>();
        p.put("name", "openwrt_audit");
        p.put("description", "Audit every OpenWrt router.");
        p.put("parameters", "{\"hosts\": {\"type\": \"string\"}}");
        return p;
    }

    static String module(String tail) {
        return "import json\n\ndef run(params):\n    return {'output': json.dumps(1), 'success': True}\n" + tail;
    }

    static Reply finished(String code) {
        return c -> Replies.of("```python\n" + code + "\n```\n", 3_000, 4_000, 0, 0, "end_turn");
    }

    /** A reply stopped at the model's maximum output, as Anthropic reports one. */
    static Reply cutOff(String text) {
        return c -> new LlmResponse(text, List.of(), null, "max_tokens", null,
                "claude-opus-5", 128_000, 1_000_000,
                List.of(new LlmResponse.Usage("claude-opus-5", 3_000, 128_000, 0, 0)));
    }

    static AgentContext task() {
        return new AgentContext("u1", "a1b2c3d4", "audit the routers");
    }

    @Test
    @DisplayName("a reply cut off at the output limit is refused after one call, says which limit, and is not an outage")
    void aCutOffReplyIsRefusedNotRepaired(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of());
        for (int i = 0; i < 4; i++) rig.cloud.codegen.add(cutOff("```python\n" + module("    an =")));
        var ctx = task();
        AgentLoop.Codegen out = rig.loop.generateSkillCodeWithCloud(spec(), ctx);
        assertEquals(1, rig.cloud.calls("codegen").size(), "a cut-off file was sent back for repair");
        assertNull(out.params());
        assertTrue(out.error().startsWith("ERROR: the code for 'openwrt_audit' did not fit in one reply "
                + "([anthropic] the reply reached the model's maximum output of 128,000 tokens and was cut off)."),
                out.error());
        assertFalse(out.error().toLowerCase().contains("unavailable"), out.error());
        assertEquals(131_000, ctx.cloudTokens(), "the cut-off reply was billed, and counted");
    }

    @Test
    @DisplayName("a repair sends the specification and the latest attempt only, so it does not grow")
    void aRepairSendsOnlyTheLatestAttempt(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of());
        rig.skills.syntax = code -> code.contains("def broken(:") ? "line 5: invalid syntax" : null;
        for (int i = 0; i < 4; i++) rig.cloud.codegen.add(finished(module("def broken(:\n    pass  # " + i)));
        AgentLoop.Codegen out = rig.loop.generateSkillCodeWithCloud(spec(), task());

        var calls = rig.cloud.calls("codegen");
        assertEquals(4, calls.size(), "the first reply and three repairs");
        assertEquals("ERROR: Python syntax error:\nline 5: invalid syntax", out.error());
        String theSpec = calls.get(0).messages().get(1).content();
        for (int k = 1; k < 4; k++) {
            List<LlmMessage> sent = calls.get(k).messages();
            assertEquals(2, sent.size(), "repair " + k + " carried earlier attempts");
            assertEquals(LlmMessage.Role.USER, sent.get(1).role());
            assertTrue(sent.get(1).content().startsWith(theSpec), "repair " + k + " lost the specification");
            assertTrue(sent.get(1).content().contains("# " + (k - 1)) && !sent.get(1).content().contains("# " + (k - 2)),
                    "repair " + k + " must carry the latest attempt and no earlier one");
        }
    }

    @Test
    @DisplayName("a repaired syntax error gives the repaired code")
    void aRepairThatWorksIsUsed(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of());
        rig.skills.syntax = code -> code.contains("def broken(:") ? "line 5: invalid syntax" : null;
        rig.cloud.codegen.add(finished(module("def broken(:\n    pass")));
        rig.cloud.codegen.add(finished(module("")));
        AgentLoop.Codegen out = rig.loop.generateSkillCodeWithCloud(spec(), task());
        assertNull(out.error());
        assertFalse(String.valueOf(out.params().get("code")).contains("def broken(:"));
        assertEquals(2, rig.cloud.calls("codegen").size());
    }

    @Test
    @DisplayName("each code call carries the task's hook: an event is progress, and a stopped task's call ends")
    void everyCallCarriesTheTasksHook(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of());
        var ctx = task();
        var idle = new ArrayList<Long>();
        rig.cloud.codegen.add(c -> {
            Thread.sleep(60);
            idle.add(ctx.msSinceLastProgress());
            c.progress().onProgress();
            idle.add(ctx.msSinceLastProgress());
            ctx.stall("test: stopped during the call");
            c.progress().onProgress();
            throw new AssertionError("the hook of a stopped task let the reply go on");
        });
        AgentLoop.Codegen out = rig.loop.generateSkillCodeWithCloud(spec(), ctx);
        assertTrue(idle.get(0) >= 50 && idle.get(1) < 50, "an event of the reply is progress: " + idle);
        assertEquals("ERROR: the task was stopped while the code for 'openwrt_audit' was being written, "
                + "so nothing was created.", out.error());
        assertEquals(1, rig.cloud.calls("codegen").size());
    }

    @Test
    @DisplayName("a reply that comes back is progress, whatever it holds")
    void aReplyIsProgress(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of());
        rig.cloud.codegen.add(c -> {
            Thread.sleep(120);
            return Replies.of("no code here", 10, 10, 0, 0, "end_turn");
        });
        var ctx = task();
        rig.loop.generateSkillCodeWithCloud(spec(), ctx);
        assertTrue(ctx.msSinceLastProgress() < 100, "the reply did not count as progress: "
                + ctx.msSinceLastProgress() + " ms since the last mark");
    }

    @Test
    @DisplayName("a stop asked for during one call is honoured before the next is sent")
    void aStopEndsItBeforeTheNextCall(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of());
        rig.skills.syntax = code -> "line 5: invalid syntax";
        var ctx = task();
        var cancellation = new TaskCancellationService();
        ctx.setExternalCancel(() -> cancellation.isCancelled("u1", ctx.taskId(), ctx.startTimeMs()));
        rig.cloud.codegen.add(c -> {
            cancellation.requestAll("u1", "you pressed Stop");
            return finished(module("def broken(:")).answer(c);
        });
        AgentLoop.Codegen out = rig.loop.generateSkillCodeWithCloud(spec(), ctx);
        assertEquals(1, rig.cloud.calls("codegen").size(), "a repair was sent after Stop");
        assertTrue(out.error().contains("was stopped"), out.error());
    }

    @Test
    @DisplayName("each failure says what happened; 'unavailable' only when no model is")
    void failuresSayWhy(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of());
        rig.cloud.codegen.add(c -> { throw new LlmException("anthropic", "HTTP 500: server error", 500, null); });
        String failed = rig.loop.generateSkillCodeWithCloud(spec(), task()).error();
        assertEquals("ERROR: the request for the code of 'openwrt_audit' failed ([anthropic] HTTP 500: "
                + "server error), so nothing was created.", failed);

        rig.cloud.codegen.add(c -> Replies.of("I would write a class for this.", 10, 10, 0, 0, "end_turn"));
        assertEquals("ERROR: the reply for 'openwrt_audit' held no Python code, so nothing was created.",
                rig.loop.generateSkillCodeWithCloud(spec(), task()).error());

        rig.cloud.codegen.add(c -> Replies.of("```python\nimport json\nprint(json.dumps(1))\n```", 10, 10, 0, 0, "end_turn"));
        assertEquals("ERROR: the reply for 'openwrt_audit' held Python code but no def run(params), which every "
                + "skill needs, so nothing was created.", rig.loop.generateSkillCodeWithCloud(spec(), task()).error());

        rig.cloud.codegen.add(c -> new LlmResponse("", List.of(), null, "refusal", "cyber",
                "claude-opus-5", 128_000, 1_000_000,
                List.of(new LlmResponse.Usage("claude-opus-5", 2_000, 300, 0, 0))));
        var declined = task();
        assertTrue(rig.loop.generateSkillCodeWithCloud(spec(), declined).error().startsWith(
                "ERROR: the model declined to write the code for 'openwrt_audit' ([anthropic] the model "
                        + "declined this request (stop reason: refusal, category: cyber))"));
        assertEquals(2_300, declined.cloudTokens(), "a declined reply was billed, so it is counted");

        rig.cloud.codegen.add(c -> { throw new com.ownclaw.llm.EgressRefused("anthropic", 2, "openwrt_audit", 1, "user", 10); });
        String refused = rig.loop.generateSkillCodeWithCloud(spec(), task()).error();
        assertTrue(refused.startsWith("ERROR: the request for the code of 'openwrt_audit' was not sent ("), refused);

        rig.cloud.codegen.add(c -> {
            throw new com.ownclaw.llm.OutputTruncated("anthropic", com.ownclaw.llm.OutputTruncated.Limit.CONTEXT_WINDOW,
                    1_000_000, null);
        });
        String tooLong = rig.loop.generateSkillCodeWithCloud(spec(), task()).error();
        assertTrue(tooLong.startsWith("ERROR: the request for the code of 'openwrt_audit' did not fit ([anthropic] "
                + "the conversation is longer than the model's 1,000,000-token context window"), tooLong);
        assertFalse(tooLong.contains("one reply"), "a request too long is not a reply cut off: " + tooLong);

        rig.cloud.available = false;
        assertTrue(rig.loop.generateSkillCodeWithCloud(spec(), task()).error().contains(
                "neither the cloud model nor the local model is available"));
    }

    @Test
    @DisplayName("every call's billed tokens -- cache included -- count once, against the tier that did the work")
    void oneAccountingPath(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of());
        rig.skills.syntax = code -> code.contains("def broken(:") ? "line 5: invalid syntax" : null;
        rig.cloud.codegen.add(c -> Replies.of("```python\n" + module("def broken(:") + "\n```",
                100, 50, 1_000, 5_000, "end_turn"));
        rig.cloud.codegen.add(c -> Replies.of("```python\n" + module("") + "\n```",
                200, 60, 0, 6_000, "end_turn"));
        var ctx = task();
        rig.loop.generateSkillCodeWithCloud(spec(), ctx);
        assertEquals(6_150 + 6_260, ctx.cloudTokens(), "the repair was counted without its cache reads");
        assertEquals(6_150 + 6_260, rig.jdbc.queryForObject(
                "SELECT SUM(tokens_used) FROM token_usage WHERE user_id = 'u1'", Integer.class));

        // The cloud down: the local model writes the code, and its tokens are not the cloud's.
        LlmProvider local = new LlmProvider() {
            public LlmResponse chat(List<LlmMessage> m, LlmRequestConfig c) {
                return Replies.of("```python\n" + module("") + "\n```", 700, 300);
            }
            public boolean isAvailable() { return true; }
            public String name() { return "ollama"; }
        };
        var offline = new LoopRig(tmp.resolve("offline"), List.of(), 600, local);
        offline.cloud.available = false;
        var ctx2 = task();
        assertNull(offline.loop.generateSkillCodeWithCloud(spec(), ctx2).error());
        assertEquals(1_000, ctx2.localTokens());
        assertEquals(0, ctx2.cloudTokens());
        assertEquals(0, offline.jdbc.queryForObject("SELECT count(*) FROM token_usage", Integer.class),
                "local tokens reached the cloud budget");
    }

    @Test
    @DisplayName("a reply is priced as the model that wrote it, which a fallback can make another than the one asked")
    void pricedAsTheModelThatWroteIt(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of());
        rig.cloud.codegen.add(c -> new LlmResponse("```python\n" + module("") + "\n```", List.of(), null,
                "end_turn", null, "claude-sonnet-5", 64_000, 1_000_000,
                List.of(new LlmResponse.Usage("claude-sonnet-5", 1_000_000, 0, 0, 0))));
        rig.loop.generateSkillCodeWithCloud(spec(), task());
        assertEquals(2.0, rig.jdbc.queryForObject("SELECT cost_usd FROM token_usage WHERE user_id = 'u1'", Double.class),
                1e-9, "a million input tokens at the $2 of the model that answered, not the $5 of the one asked");
    }

    @Test
    @DisplayName("a reply with no fence keeps every import above def run")
    void everyImportIsKept(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of());
        rig.cloud.codegen.add(c -> Replies.of("Here is the skill:\n\nimport json\nimport subprocess\n\n"
                + "def run(params):\n    return {'output': json.dumps(subprocess.run(['true']).returncode), 'success': True}\n",
                10, 10, 0, 0, "end_turn"));
        String code = String.valueOf(rig.loop.generateSkillCodeWithCloud(spec(), task()).params().get("code"));
        assertTrue(code.startsWith("import json\nimport subprocess\n"), code);
    }

    @Test
    @DisplayName("the code request carries the whole task, the whole error and every recorded failure")
    void theRequestIsWhole(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of());
        rig.skills.source = name -> "def run(params):\n    raise KeyError('uid')\n";
        String traceback = "Traceback (most recent call last):\n" + "  File \"skill.py\", line 2\n".repeat(300)
                + "KeyError: 'uid'";
        var curator = new SkillCuratorService(rig.jdbc, null, null, null);
        String longParam = "p".repeat(1_000);
        for (int i = 0; i < 5; i++) {
            curator.recordUsage("openwrt_audit", "u1", "t" + i, false, 5, Map.of("host", longParam + i),
                    "E" + i + " " + "e".repeat(3_000), Label.PUBLIC);
        }
        String message = "audit the routers " + "and report every open port ".repeat(80);
        var ctx = new AgentContext("u1", "a1b2c3d4", message);
        ctx.trajectory().record(new AgentAction("openwrt_audit", Map.of(), ""),
                AgentObservation.failure("openwrt_audit", traceback, 5));
        rig.cloud.codegen.add(finished(module("")));
        rig.loop.generateSkillCodeWithCloud(spec(), ctx);

        String sent = rig.cloud.calls("codegen").get(0).messages().get(1).content();
        assertTrue(sent.contains(traceback), "the error was cut");
        assertTrue(sent.contains("\"" + message + "\""), "the task was cut");
        for (int i = 0; i < 5; i++) {
            assertTrue(sent.contains("E" + i + " " + "e".repeat(3_000)), "failure " + i + " missing or cut");
            assertTrue(sent.contains(longParam + i), "the parameters of failure " + i + " were cut");
        }
    }
}
