package com.ownclaw.agent;

import com.ownclaw.agent.tools.Tool;
import com.ownclaw.privacy.PrivateIndex;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static com.ownclaw.agent.AssistantPartsTest.tool;
import static com.ownclaw.agent.LoopRig.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * How a task ends, and what the next turn is given, through the real loop: the owner's 29 Sep
 * chat is the model -- a Stop that waited ten minutes for code generation to return, a stall
 * reported as "Task was cancelled.", a privacy block that ended on the gateway's own message with
 * a 175 KB audit dropped, and a next turn that knew nothing of what the last one had done.
 */
class TaskEndToEndTest {

    static final Tool NOOP = tool("noop", List.of(), p -> "nothing to report");

    static String session(LoopRig rig) {
        return rig.chat.createSession("u1", "Network");
    }

    @Test
    @DisplayName("Stop ends the model call in flight, and the ending says who stopped it")
    void aStopEndsTheCallInFlight(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of(NOOP));
        rig.cloud.think.add(streaming(5_000, respond("an answer that came too late")));
        var stop = new Thread(() -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                return;
            }
            rig.cancellation.requestAll("u1", "you pressed Stop");
        });
        long t0 = System.currentTimeMillis();
        stop.start();
        AgentResult r = rig.turn(session(rig), "audit the routers");
        stop.join();

        assertEquals(AgentResult.TerminationReason.CANCELLED, r.terminationReason(), r.response());
        assertTrue(System.currentTimeMillis() - t0 < 3_000, "the call ran to its end after Stop");
        assertTrue(r.response().startsWith("**Stopped:** you pressed Stop.\n\n**What it did** — 0 steps"), r.response());
        // Mutation: take the progress hook off the think call -> the reply arrives after 5 s and
        // is delivered as the answer.
    }

    @Test
    @DisplayName("a reply that keeps streaming in is progress: the stall watchdog leaves the task alone")
    void aStreamingReplyIsProgress(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of(NOOP), 1);
        rig.cloud.think.add(streaming(2_500, call("noop", Map.of())));
        rig.cloud.think.add(respond("done"));
        AgentResult r;
        try (var ticking = rig.watchdog()) {
            r = rig.turn(session(rig), "check the network");
        }
        assertEquals(AgentResult.TerminationReason.COMPLETED, r.terminationReason(), r.response());
        assertEquals("done", r.response());
        // Mutation: the hook does not mark progress -> STALLED after the 2.5 s call.
    }

    @Test
    @DisplayName("a call that goes silent is stopped by the watchdog: STALLED, with its facts and the steps")
    void aSilentCallIsStalled(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of(NOOP), 1);
        rig.cloud.think.add(call("noop", Map.of()));
        rig.cloud.think.add(silent(1_800, call("noop", Map.of())));
        AgentResult r;
        try (var ticking = rig.watchdog()) {
            r = rig.turn(session(rig), "check the network");
        }
        assertEquals(AgentResult.TerminationReason.STALLED, r.terminationReason(), r.response());
        assertTrue(r.response().startsWith("**Stopped:** no progress for "), r.response());
        assertTrue(r.response().contains("— no step finished and no model reply streamed in — and the limit is 1.0s."),
                r.response());
        assertTrue(r.response().contains("1. ✓ noop"), r.response());
        assertEquals(2, rig.cloud.calls("think").size(), "no step after the stop");
        // Mutation: the watchdog asks through the cancellation service with no mark -> CANCELLED.
    }

    static final String PASSWORD = "x7Qp-2Lm-9Rt-Wq4z";
    static final String AUDIT = String.join("\n",
            "# Network audit", "## 192.0.2.1 (main router)", "wireless.guest.ssid='guest-net'",
            "wireless.default_radio0.key='" + PASSWORD + "'", "guest isolation: OK",
            "## 192.0.2.2 (access point)", "network.lan.gateway='192.0.2.1'", "guest isolation: BROKEN");

    @Test
    @DisplayName("a privacy block names the result, hands the owner his results, and keeps them out of what later prompts read")
    void aPrivacyBlockHandsTheOwnerHisResults(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of(
                tool("openwrt_audit", List.of("OPENWRT_USER", "OPENWRT_PASS"), p -> AUDIT),
                // The case the canary exists for: a public skill that hands back the private text.
                tool("cat_report", List.of(), p -> AUDIT)));
        rig.jdbc.update("INSERT INTO users (id, display_name) VALUES ('u1', 'Owner')");   // the vault's salt lives on it
        rig.vault.storeCredential("u1", "OPENWRT_PASS", PASSWORD);
        rig.cloud.think.add(call("openwrt_audit", Map.of()));
        rig.cloud.think.add(call("cat_report", Map.of("path", "/srv/audit.md")));
        String session = session(rig);

        AgentResult r = rig.turn(session, "audit the routers");

        assertEquals(AgentResult.TerminationReason.PRIVACY_BLOCKED, r.terminationReason(), r.response());
        assertTrue(r.response().startsWith("**Stopped:** the next request to the cloud model held text of "
                + "result 1 (openwrt_audit), which is private (credentials (2)); it was found in a user "
                + "message, so nothing was sent."), r.response());
        assertTrue(r.response().contains("result 2 (cat_report): "), r.response());
        String audit = PrivateIndex.normalise(AUDIT.replace(PASSWORD, "«vault:OPENWRT_PASS»"));
        String said = PrivateIndex.normalise(r.response());
        for (int i = 0; i + PrivateIndex.WINDOW <= audit.length(); i++) {
            assertFalse(said.contains(audit.substring(i, i + PrivateIndex.WINDOW)),
                    "the text later prompts read carries the audit at " + i);
        }
        assertTrue(r.ownerText().contains(AUDIT.replace(PASSWORD, "«vault:OPENWRT_PASS»")),
                "the owner gets the audit he asked for: " + r.ownerText());
        assertFalse(r.ownerText().contains(PASSWORD), "never a vault value, whoever reads it");
        for (String text : List.of(r.response(), r.ownerText())) {
            assertFalse(ArtifactRef.TOKEN.matcher(text).find(), "a handle saved to the chat: " + text);
        }

        // The next turn reads the ending, and not the audit.
        rig.cloud.think.add(respond("ok"));
        rig.turn(session, "what happened?");
        String next = String.join("\n", userParts(rig.cloud.calls("think").get(2)));
        assertTrue(next.contains("**Stopped:** the next request to the cloud model held text of result 1"), next);
        String nextNormal = PrivateIndex.normalise(next);
        for (int i = 0; i + PrivateIndex.WINDOW <= audit.length(); i++) {
            assertFalse(nextNormal.contains(audit.substring(i, i + PrivateIndex.WINDOW)), "the next turn read the audit");
        }
    }

    @Test
    @DisplayName("an exception in the loop ends with the steps that ran, a task_completed row and an episode")
    void anExceptionKeepsTheRecord(@TempDir Path tmp) throws Exception {
        String links = "eth0 up\n" + "lo up, mtu 65536, qdisc noqueue, state UNKNOWN\n".repeat(20);
        var rig = new LoopRig(tmp, List.of(tool("shell_exec", List.of(), p -> links)));
        rig.cloud.think.add(call("shell_exec", Map.of()));
        rig.cloud.think.add(c -> { throw new IllegalStateException("boom in step 2"); });

        AgentResult r = rig.turn(session(rig), "check my network");

        assertEquals(AgentResult.TerminationReason.ERROR, r.terminationReason());
        assertTrue(r.response().startsWith("**Stopped:** an internal error: IllegalStateException: boom in step 2."),
                r.response());
        assertTrue(r.response().contains("1. ✓ shell_exec"), r.response());
        assertTrue(r.response().contains("result 1 (shell_exec): " + links.length() + " chars, public — in full below.")
                && r.response().endsWith("**result 1 (shell_exec):**\n\n" + links + "\n\n**Next:** Your next message "
                        + "starts a new task, which reads this message. Every step is on this task's page: task "
                        + r.taskId() + "."), r.response());
        assertEquals(1, rig.jdbc.queryForObject(
                "SELECT count(*) FROM events WHERE event_type = 'task_completed' AND task_id = ?", Integer.class, r.taskId()));
        String episode = rig.jdbc.queryForObject(
                "SELECT content FROM agent_memory WHERE memory_type = 'episode' AND task_id = ?", String.class, r.taskId());
        assertEquals("Task: check my network\nSteps: 1\nOutcome: ERROR\nResponse: " + r.response(), episode,
                "the episode is the whole task and the whole ending");
    }

    @Test
    @DisplayName("a long-running skill's task and result are recorded whole")
    void aLongRunningSkillIsRecordedWhole(@TempDir Path tmp) throws Exception {
        String output = "scanned host ".repeat(300);
        var rig = new LoopRig(tmp, List.of(new Tool() {
            public String name() { return "slow_scan"; }
            public String description() { return "a scan that reports progress"; }
            public Map<String, com.ownclaw.agent.tools.ToolParam> inputSchema() { return Map.of(); }
            public List<String> requiredCredentials() { return List.of(); }
            public com.ownclaw.agent.tools.ToolResult execute(Map<String, Object> p, com.ownclaw.agent.tools.ToolExecutionContext c) {
                c.progressCallback().onProgress("half way", 50);
                return com.ownclaw.agent.tools.ToolResult.success(output);
            }
        }));
        rig.jdbc.update("INSERT INTO users (id, display_name) VALUES ('u1', 'Owner')");
        rig.cloud.think.add(call("slow_scan", Map.of()));
        rig.cloud.think.add(respond("done"));
        String message = "scan the whole network " + "and every host on it ".repeat(20);
        AgentResult r = rig.turn(session(rig), message);
        var row = rig.jdbc.queryForMap("SELECT description, result_summary FROM long_running_tasks WHERE task_id = ?", r.taskId());
        assertEquals(message, row.get("description"));
        assertEquals(output, row.get("result_summary"));
    }

    @Test
    @DisplayName("a think reply the provider declined, or cut off by a limit, ends the task saying so -- its tokens counted")
    void aThinkWithNoAnswerEndsSayingWhy(@TempDir Path tmp) throws Exception {
        var declined = new com.ownclaw.llm.LlmResponse("", 2_000, 300, 0, 0, "refusal", List.of(), "cyber",
                "claude-opus-5", 128_000, 1_000_000);
        var rig = new LoopRig(tmp, List.of(), 600, new StopWithoutLocalModelTest.Down(),
                (registry, config, router) -> new ThinkingEngine(registry, new ToolSelector(registry), config, router) {
                    @Override
                    public ThinkResult decideNextActionFull(AgentContext context, com.ownclaw.llm.LlmProvider provider) {
                        throw new com.ownclaw.llm.ProviderRefused("anthropic", declined);
                    }
                });
        AgentResult r = rig.turn(session(rig), "scan my network for open ports");
        assertEquals(AgentResult.TerminationReason.ERROR, r.terminationReason());
        assertTrue(r.response().startsWith("**Stopped:** [anthropic] the model declined this request (stop reason: "
                + "refusal, category: cyber).\n\n**What it did** — 0 steps, 2,300 cloud tokens, "), r.response());
        assertEquals(2_300, rig.jdbc.queryForObject("SELECT tokens_used FROM token_usage WHERE user_id = 'u1'", Integer.class),
                "the declined reply was billed, so it is counted");

        // A chat longer than the model can read: the provider says so, and the task ends saying it.
        var tooLong = new LoopRig(tmp.resolve("long"), List.of(), 600, new StopWithoutLocalModelTest.Down(),
                (registry, config, router) -> new ThinkingEngine(registry, new ToolSelector(registry), config, router) {
                    @Override
                    public ThinkResult decideNextActionFull(AgentContext context, com.ownclaw.llm.LlmProvider provider) {
                        throw new com.ownclaw.llm.OutputTruncated("anthropic",
                                com.ownclaw.llm.OutputTruncated.Limit.CONTEXT_WINDOW, 1_000_000, null);
                    }
                });
        AgentResult cut = tooLong.turn(session(tooLong), "and now?");
        assertEquals(AgentResult.TerminationReason.ERROR, cut.terminationReason());
        assertTrue(cut.response().startsWith("**Stopped:** [anthropic] the conversation is longer than the model's "
                + "1,000,000-token context window.\n\n"), cut.response());
    }

    @Test
    @DisplayName("the next turn is given the record of a finished task under its answer, and no episode unasked")
    void aFinishedTasksRecordReachesTheNextTurn(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of(NOOP));
        new com.ownclaw.agent.memory.SqliteAgentMemory(rig.jdbc).storeEpisode("u1", "0ld0ld00",
                "Task: EPISODE-FROM-ANOTHER-CHAT network check", true, List.of("noop"));
        String session = session(rig);
        rig.cloud.think.add(call("noop", Map.of()));
        rig.cloud.think.add(respond("FIRST-ANSWER"));
        AgentResult first = rig.turn(session, "check the network");
        assertEquals("FIRST-ANSWER", first.response(), "a finished answer is delivered as the model wrote it");

        rig.cloud.think.add(respond("second"));
        rig.turn(session, "what did you check?");
        String next = String.join("\n", userParts(rig.cloud.calls("think").get(2)));
        assertTrue(next.contains("ASSISTANT: FIRST-ANSWER\n[OwnClaw's record of task " + first.taskId()
                + ", from its step log:\n1. ✓ noop"), next);
        assertTrue(next.contains("It answered after "), next);
        assertFalse(next.contains("EPISODE-FROM-ANOTHER-CHAT"), "past episodes are asked for, not put in: " + next);
    }

    @Test
    @DisplayName("an ending already carries the record, so the next turn is not given it twice")
    void anEndingIsNotRecordedTwice(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of(NOOP));
        rig.config.getTasks().setMaxPlanSteps(2);
        String session = session(rig);
        rig.cloud.think.add(call("noop", Map.of()));
        rig.cloud.think.add(call("noop", Map.of()));
        AgentResult first = rig.turn(session, "check the network");
        assertEquals(AgentResult.TerminationReason.MAX_STEPS, first.terminationReason());
        assertTrue(first.response().startsWith("**Stopped:** it used all 2 steps a task may take.\n\n"
                + "**What it did** — 2 steps, "), first.response());
        assertTrue(first.response().contains("\n1. ✓ noop") && first.response().contains("\n2. ✓ noop"),
                first.response());
        assertTrue(first.response().contains("**Next:** Reply **continue** to carry on"), first.response());

        rig.cloud.think.add(respond("second"));
        rig.turn(session, "continue");
        String next = String.join("\n", userParts(rig.cloud.calls("think").get(2)));
        assertTrue(next.contains("ASSISTANT: **Stopped:** it used all 2 steps"), next);
        assertFalse(next.contains("[OwnClaw's record of task"), next);
    }

    @Test
    @DisplayName("a missing credential: the model is told to ask the owner to type /cred set, never to ask for the value")
    void aMissingCredentialIsTypedByTheOwner(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of());
        rig.cloud.think.add(call(AgentAction.CREDENTIAL_MANAGE, Map.of("action", "check", "key", "OPENWRT_PASS")));
        rig.cloud.think.add(respond("ok"));
        rig.turn(session(rig), "audit the routers");
        String observed = String.join("\n", userParts(rig.cloud.calls("think").get(1)));
        assertTrue(observed.contains("Credential 'OPENWRT_PASS' NOT found. Ask the user to type this in the chat"),
                observed);
        assertTrue(observed.contains("/cred set OPENWRT_PASS <value>"), observed);
        assertFalse(observed.contains("Use ask_user to request it"), observed);
    }

    @Test
    @DisplayName("the library analysis streams with the task's hook, and a refused one is a failed step that says why")
    void theAnalysisIsTheTasks(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of());
        rig.cloud.think.add(call(AgentAction.SKILL_MANAGE, Map.of("action", "analyze")));
        rig.cloud.think.add(c -> {
            c.progress().onProgress();
            return new com.ownclaw.llm.LlmResponse("", 10, 10, 0, 0, "refusal", List.of(), "cyber",
                    "claude-opus-5", 128_000, 1_000_000);
        });
        rig.cloud.think.add(respond("ok"));
        rig.turn(session(rig), "tidy up my skills");

        var analysis = rig.cloud.calls.stream().filter(c -> "analyze".equals(c.purpose())).findFirst().orElseThrow();
        assertNotSame(com.ownclaw.llm.LlmProgress.NONE, analysis.config().progress(), "the call carries no hook");
        String observed = String.join("\n", userParts(rig.cloud.calls("think").get(1)));
        assertTrue(observed.contains("ERROR: the analysis of the skill library failed: [anthropic] the model "
                + "declined this request (stop reason: refusal, category: cyber)"), observed);
        assertEquals(0, rig.jdbc.queryForObject(
                "SELECT count(*) FROM events WHERE event_type = 'step' AND json_extract(details, '$.success') = 1",
                Integer.class), "a refused analysis is a failed step, not a successful one");
    }

    @Test
    @DisplayName("memory_manage recall gives every past task that matches, whole, most relevant first")
    void recallIsAskedFor(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of());
        var memory = new com.ownclaw.agent.memory.SqliteAgentMemory(rig.jdbc);
        String longAudit = "Response: " + "the audit found port 22 open. ".repeat(100);
        memory.storeEpisode("u1", "aaaa0001", "Task: router audit\n" + longAudit, true, List.of());
        memory.storeEpisode("u1", "aaaa0002", "Task: lunch menu", true, List.of());
        memory.storeEpisode("u1", "aaaa0003", "Task: audit of the NAS", false, List.of());
        rig.jdbc.update("UPDATE agent_memory SET created_at = '2026-09-29 08:13:32' WHERE task_id = 'aaaa0001'");
        rig.cloud.think.add(call(AgentAction.MEMORY_MANAGE, Map.of("action", "recall", "query", "router audit")));
        rig.cloud.think.add(respond("ok"));
        rig.turn(session(rig), "what did the last audit find?");
        String observed = String.join("\n", userParts(rig.cloud.calls("think").get(1)));
        assertTrue(observed.contains("2 past tasks match 'router audit', most relevant first:"), observed);
        assertTrue(observed.contains(longAudit), "whole: " + observed.length());
        assertTrue(observed.contains(", 2026-09-29T08:13:32Z ---\nTask: router audit"), "when, in UTC as stored: " + observed);
        assertTrue(observed.indexOf("Task: router audit") < observed.indexOf("Task: audit of the NAS"),
                "two words in common before one");
        assertFalse(observed.contains("lunch menu"), observed);
    }
}
