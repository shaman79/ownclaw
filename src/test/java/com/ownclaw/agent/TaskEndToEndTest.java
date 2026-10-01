package com.ownclaw.agent;

import com.ownclaw.agent.tools.Tool;
import com.ownclaw.llm.LlmMessage;
import com.ownclaw.privacy.PrivateIndex;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static com.ownclaw.agent.AssistantPartsTest.tool;
import static com.ownclaw.agent.LoopRig.*;
import static com.ownclaw.observability.ChatStatusEmitter.StatusMessage.Type;
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

    /** A skill that fails, saying {@code output}. */
    static Tool failing(String name, String output) {
        return new Tool() {
            public String name() { return name; }
            public String description() { return "test skill " + name; }
            public Map<String, com.ownclaw.agent.tools.ToolParam> inputSchema() { return Map.of(); }
            public List<String> requiredCredentials() { return List.of(); }
            public com.ownclaw.agent.tools.ToolResult execute(Map<String, Object> p, com.ownclaw.agent.tools.ToolExecutionContext c) {
                return com.ownclaw.agent.tools.ToolResult.failure(output);
            }
        };
    }

    /** Every user message of the {@code thinkCall}-th think call, as one text. */
    static String sentTo(LoopRig rig, int thinkCall) {
        return String.join("\n", userParts(rig.cloud.calls("think").get(thinkCall)));
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

    /** A skill that takes {@code ms} and says nothing while it runs. */
    static Tool hanging(String name, long ms) {
        return tool(name, List.of(), p -> {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            return "done at last";
        });
    }

    @Test
    @DisplayName("a step that shows no progress -- a skill that hangs, reporting nothing -- is stopped by the watchdog: STALLED, with its facts and the steps")
    void aStepWithNoProgressIsStalled(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of(NOOP, hanging("router_audit", 1_800)), 1);
        rig.cloud.think.add(call("noop", Map.of()));
        rig.cloud.think.add(call("router_audit", Map.of()));
        AgentResult r;
        try (var ticking = rig.watchdog()) {
            r = rig.turn(session(rig), "check the network");
        }
        assertEquals(AgentResult.TerminationReason.STALLED, r.terminationReason(), r.response());
        assertTrue(r.response().startsWith("**Stopped:** no progress for "), r.response());
        assertTrue(r.response().contains("— no step finished, no model call was under way and no skill "
                + "reported progress — and the limit is 1.0s."), r.response());
        assertTrue(r.response().contains("1. ✓ noop"), r.response());
        assertEquals(2, rig.cloud.calls("think").size(), "no step after the stop");
        // Mutation: the watchdog asks through the cancellation service with no mark -> CANCELLED.
    }

    @Test
    @DisplayName("a skill that keeps reporting its progress is alive: the watchdog leaves the cloud's call of it alone, as it leaves a delegation's")
    void aSkillReportingProgressIsAlive(@TempDir Path tmp) throws Exception {
        // A LAN scan of ten minutes, reporting every few seconds: scaled to 2.5 s against a 1 s limit.
        Tool scan = new Tool() {
            public String name() { return "net_scan"; }
            public String description() { return "scans the LAN"; }
            public Map<String, com.ownclaw.agent.tools.ToolParam> inputSchema() { return Map.of(); }
            public com.ownclaw.agent.tools.ToolResult execute(Map<String, Object> p,
                                                             com.ownclaw.agent.tools.ToolExecutionContext c) {
                for (int i = 1; i <= 25; i++) {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        throw new IllegalStateException(e);
                    }
                    c.progressCallback().onProgress("Scanned " + i + " of 25 hosts", i * 4);
                }
                return com.ownclaw.agent.tools.ToolResult.success("3 hosts up");
            }
        };
        var rig = new LoopRig(tmp, List.of(scan), 1);
        rig.cloud.think.add(call("net_scan", Map.of()));
        rig.cloud.think.add(respond("Three hosts are up."));
        AgentResult r;
        try (var ticking = rig.watchdog()) {
            r = rig.turn(session(rig), "scan the LAN");
        }
        assertEquals(AgentResult.TerminationReason.COMPLETED, r.terminationReason(), r.response());
        assertEquals("Three hosts are up.", r.response());
        // Mutation: the cloud path's progress callback does not mark progress -> STALLED after
        // the scan returns, "no progress for 1.0s", though it reported every 100 ms.
    }

    @Test
    @DisplayName("two ops chat turns in a new chat: the second reads the first, with its record, and nothing of the owner's open chat")
    void opsChatTurnsAreAChat(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of(NOOP));
        String owners = rig.chat.createSession("u1", "The owner's chat");
        rig.chat.saveMessage("u1", owners, "user", "OWNER-CHAT question");
        rig.chat.saveMessage("u1", owners, "assistant", "OWNER-CHAT answer");
        rig.jdbc.update("INSERT INTO users (id, display_name) VALUES ('u1', 'Owner')");
        var ops = new com.ownclaw.interfaces.web.OpsController(null, rig.loop,
                new com.ownclaw.users.AuthService(rig.jdbc, new com.ownclaw.users.UserRepository(rig.jdbc), rig.config),
                null, rig.cancellation, null, rig.config, rig.chat, rig.queue);

        rig.cloud.think.add(call("noop", Map.of()));
        rig.cloud.think.add(respond("Noted: seven."));
        @SuppressWarnings("unchecked")
        var first = (Map<String, Object>) ops.runAgent(
                Map.of("message", "remember 7", "userId", "u1", "sessionId", "new")).getBody();
        String chat = (String) first.get("sessionId");
        assertEquals(owners, rig.chat.getCurrentSession("u1"), "the owner's open chat did not move");

        rig.cloud.think.add(respond("It was seven."));
        ops.runAgent(Map.of("message", "which number?", "userId", "u1", "sessionId", chat));

        String read = String.join("\n", userParts(rig.cloud.calls("think").get(2)));
        assertTrue(read.contains("USER: remember 7\nASSISTANT: Noted: seven.\n[OwnClaw's record of task "
                + first.get("taskId") + ", from its step log:\n1. ✓ noop"), read);
        assertFalse(read.contains("OWNER-CHAT"), "the owner's open chat is not the ops chat: " + read);
    }

    @Test
    @DisplayName("Stop ends a model call that has sent nothing yet -- a local model still loading -- at once")
    void stopEndsASilentCall(@TempDir Path tmp) throws Exception {
        var ollama = new com.ownclaw.llm.SilentOllama();
        var rig = new LoopRig(tmp, List.of(), 600, ollama.provider());
        rig.cloud.available = false;         // the local model does the thinking, and is loading
        var stop = new Thread(() -> {
            try {
                ollama.awaitCall();
            } catch (InterruptedException e) {
                return;
            }
            rig.cancellation.requestAll("u1", "you pressed Stop");
        });
        long t0 = System.currentTimeMillis();
        stop.start();
        AgentResult r = rig.turn(session(rig), "check the network");
        stop.join();

        assertEquals(AgentResult.TerminationReason.CANCELLED, r.terminationReason(), r.response());
        assertTrue(System.currentTimeMillis() - t0 < 5_000, "the silence was waited out after Stop");
        assertTrue(r.response().startsWith("**Stopped:** you pressed Stop.\n\n"), r.response());
        // Mutation: the Stop request ends no call -> the silent server fails the test after 30 s.
    }

    @Test
    @DisplayName("a local model that sends nothing past the stall limit while it reads a long prompt, then answers, finishes the task: a call under way is not silence")
    void aLocalModelReadingIsNotAStall(@TempDir Path tmp) throws Exception {
        // Ollama sends nothing, not even its headers, until it has read the whole prompt, and the
        // production host reads about 100 tokens a second: a delegated result of 60,000 tokens
        // is ten minutes of silence, the stall limit. Scaled: a 1 s limit, 2.5 s of reading.
        var ollama = new com.ownclaw.llm.SilentOllama(2_500, com.ownclaw.llm.SilentOllama.says(
                "{\"done\": true, \"summary\": \"Both routers answer.\"}"));
        var rig = new LoopRig(tmp, List.of(NOOP), 1, ollama.provider());
        rig.cloud.think.add(call(AgentAction.DELEGATE, Map.of("goal", "audit both routers")));
        rig.cloud.think.add(respond("Both routers answer."));
        AgentResult r;
        try (var ticking = rig.watchdog()) {
            r = rig.turn(session(rig), "audit the routers");
        }
        assertEquals(AgentResult.TerminationReason.COMPLETED, r.terminationReason(), r.response());
        assertEquals("Both routers answer.", r.response());
        assertEquals(1, ollama.chats(), "the local call that read for 2.5 s was the one that answered");
        assertTrue(userParts(rig.cloud.calls("think").get(1)).stream().anyMatch(u -> u.contains("Both routers answer.")),
                "and its answer reached the cloud");
        // Mutation: count the time a call is under way as silence -> the watchdog ends the local
        // call at 1 s and the task ends STALLED, the cloud never asked again.
    }

    /** The think call goes to the real Anthropic provider, as scripted, carrying the task's hook. */
    static Reply anthropic(com.ownclaw.llm.ScriptedAnthropic api) {
        return c -> api.provider().chat(List.of(LlmMessage.user("audit the routers")), c);
    }

    @Test
    @DisplayName("Stop ends a model call that is waiting to try again after an overload, at once")
    void aStopEndsTheWaitBeforeARetry(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of(NOOP));
        var api = com.ownclaw.llm.ScriptedAnthropic.overloaded();
        rig.cloud.think.add(anthropic(api));
        var stop = new Thread(() -> {
            try {
                Thread.sleep(1_000);          // the 529 is in: the provider waits, at least 24 s
            } catch (InterruptedException e) {
                return;
            }
            rig.cancellation.requestAll("u1", "you pressed Stop");
        });
        long t0 = System.currentTimeMillis();
        stop.start();
        AgentResult r = assertTimeoutPreemptively(Duration.ofSeconds(20), () -> rig.turn(session(rig), "audit the routers"),
                "the Stop was heard only when the wait was over");
        stop.join();

        assertEquals(AgentResult.TerminationReason.CANCELLED, r.terminationReason(), r.response());
        assertTrue(System.currentTimeMillis() - t0 < 5_000, "the wait ran on after Stop");
        assertTrue(r.response().startsWith("**Stopped:** you pressed Stop.\n\n"), r.response());
        assertEquals(1, api.requests(), "and nothing was sent again");
        // Mutation: a wait that hands the hook no cancel -> the Stop is heard at the next
        // attempt, 24 s or more later, and the preemptive timeout fails the test.
    }

    @Test
    @DisplayName("a reply stopped part-way is counted: the tokens its stream reported reach the ending, the budget and the ledger")
    void aStoppedReplyIsCounted(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of(NOOP));
        var api = com.ownclaw.llm.ScriptedAnthropic.answering(120_000, 80_000, 900, "The routers ", "are ", "fine.");
        // The owner presses Stop as the reply's third event arrives, message_start already in.
        rig.cloud.think.add(c -> anthropic(api).answer(c.withProgress(stopOn(3, c.progress(),
                () -> rig.cancellation.requestAll("u1", "you pressed Stop")))));
        AgentResult r = rig.turn(session(rig), "audit the routers");

        assertEquals(AgentResult.TerminationReason.CANCELLED, r.terminationReason(), r.response());
        long billed = 120_000 + 80_000 + 1;      // input, cache reads, and message_start's output count
        assertTrue(r.response().contains("200,001 cloud tokens"), r.response());
        assertEquals(billed, rig.jdbc.queryForObject(
                "SELECT COALESCE(SUM(tokens_used), 0) FROM token_usage WHERE user_id = 'u1'", Long.class),
                "the day's budget");
        String completed = rig.jdbc.queryForObject(
                "SELECT details FROM events WHERE event_type = 'task_completed'", String.class);
        assertTrue(completed.contains("\"cloudTokens\":" + billed), completed);
        String egress = rig.jdbc.queryForObject("SELECT details FROM events WHERE event_type = 'egress'", String.class);
        assertTrue(egress.contains("\"decision\":\"ERROR\"") && egress.contains("\"promptTokens\":120000")
                && egress.contains("\"cacheReadTokens\":80000"), egress);
        assertFalse(egress.contains("\"costUsd\":0.0,"), "priced: " + egress);
        // Mutation: the loop does not count what the hook is told -> 0 cloud tokens, and an
        // empty budget, though the ledger has them.
    }

    /** The task's hook, with the owner pressing Stop as the {@code n}-th event of the reply arrives. */
    static com.ownclaw.llm.LlmProgress stopOn(int n, com.ownclaw.llm.LlmProgress hook, Runnable stop) {
        int[] seen = {0};
        return new com.ownclaw.llm.LlmProgress() {
            @Override
            public void onProgress() {
                if (++seen[0] == n) stop.run();
                hook.onProgress();
            }

            @Override
            public void calling(Runnable cancel) {
                hook.calling(cancel);
            }

            @Override
            public void billed(com.ownclaw.llm.LlmResponse.Usage usage) {
                hook.billed(usage);
            }
        };
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
                // A public skill that hands back the private text: labelled private for it, and
                // the task goes on (AgentContext.decide).
                tool("cat_report", List.of(), p -> AUDIT)));
        rig.jdbc.update("INSERT INTO users (id, display_name) VALUES ('u1', 'Owner')");   // the vault's salt lives on it
        rig.config.getPrivacy().setPersonalSources(List.of("OPENWRT_"));   // the audit stands for a private result
        rig.vault.storeCredential("u1", "OPENWRT_PASS", PASSWORD);
        // What still reaches the door: a special action's answer, which is no result the task
        // labels -- here another skill's files, whose baseline holds the same configuration lines.
        rig.skills.files = name -> "## Skill: " + name + "\n\n### skill.py\n```python\nBASELINE = '''"
                + AUDIT.substring(AUDIT.indexOf("## 192.0.2.2")) + "'''\n```\n";
        rig.cloud.think.add(call("openwrt_audit", Map.of()));
        rig.cloud.think.add(call("cat_report", Map.of("path", "/srv/audit.md")));
        rig.cloud.think.add(call(AgentAction.SKILL_MANAGE, Map.of("action", "read", "name", "ap_baseline")));
        String session = session(rig);

        AgentResult r = rig.turn(session, "audit the routers");

        assertEquals(AgentResult.TerminationReason.PRIVACY_BLOCKED, r.terminationReason(), r.response());
        assertEquals(3, rig.cloud.calls("think").size(), "the fourth request was refused, not sent");
        assertTrue(r.response().startsWith("**Stopped:** the next request to the cloud model held text of "
                + "result 1 (openwrt_audit), which is private (personal source); it was found in a user "
                + "message, so nothing was sent."), r.response());
        assertTrue(r.response().contains("result 2 (cat_report): "), r.response());
        assertTrue(r.response().contains("result 2 (cat_report): 226 chars, private (repeats result 1)"),
                "the public skill's copy is the owner's alone: " + r.response());
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
        String next = String.join("\n", userParts(rig.cloud.calls("think").get(3)));
        assertTrue(next.contains("**Stopped:** the next request to the cloud model held text of result 1"), next);
        String nextNormal = PrivateIndex.normalise(next);
        for (int i = 0; i + PrivateIndex.WINDOW <= audit.length(); i++) {
            assertFalse(nextNormal.contains(audit.substring(i, i + PrivateIndex.WINDOW)), "the next turn read the audit");
        }
    }

    @Test
    @DisplayName("a failed call's recorded error is whole but for vault values: ops serves it, and ops is read from the cloud")
    void aRecordedErrorHoldsNoVaultValue(@TempDir Path tmp) throws Exception {
        String failure = "Skill error: Command 'sshpass -p " + PASSWORD + " ssh root@192.0.2.1 uci show' "
                + "returned non-zero exit status 5.";
        var rig = new LoopRig(tmp, List.of(new Tool() {
            public String name() { return "openwrt_audit"; }
            public String description() { return "test skill openwrt_audit"; }
            public Map<String, com.ownclaw.agent.tools.ToolParam> inputSchema() { return Map.of(); }
            public List<String> requiredCredentials() { return List.of("OPENWRT_USER", "OPENWRT_PASS"); }
            public com.ownclaw.agent.tools.ToolResult execute(Map<String, Object> p, com.ownclaw.agent.tools.ToolExecutionContext c) {
                return com.ownclaw.agent.tools.ToolResult.failure(failure);
            }
        }));
        rig.jdbc.update("INSERT INTO users (id, display_name) VALUES ('u1', 'Owner')");   // the vault's salt lives on it
        rig.vault.storeCredential("u1", "OPENWRT_PASS", PASSWORD);
        rig.cloud.think.add(call("openwrt_audit", Map.of()));
        rig.cloud.think.add(respond("The audit failed."));

        rig.turn(session(rig), "audit the routers");

        assertEquals(failure.replace(PASSWORD, "«vault:OPENWRT_PASS»"), rig.jdbc.queryForObject(
                "SELECT error FROM skill_usage WHERE tool_name = 'openwrt_audit'", String.class));
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
        var declined = new com.ownclaw.llm.LlmResponse("", List.of(), null, "refusal", "cyber",
                "claude-opus-5", 128_000, 1_000_000,
                List.of(new com.ownclaw.llm.LlmResponse.Usage("claude-opus-5", 2_000, 300, 0, 0)));
        var rig = new LoopRig(tmp, List.of(), 600, new StopWithoutLocalModelTest.Down(),
                (registry, config, router) -> new ThinkingEngine(registry, config, router) {
                    @Override
                    public ThinkResult decideNextActionFull(AgentContext context, com.ownclaw.llm.LlmProvider provider) {
                        throw new com.ownclaw.llm.ProviderRefused("anthropic", declined);
                    }
                });
        AgentResult r = rig.turn(session(rig), "scan my network for open ports");
        assertEquals(AgentResult.TerminationReason.ERROR, r.terminationReason());
        assertTrue(r.response().startsWith("**Stopped:** the cloud model's provider (anthropic) declined to answer "
                + "this request: its safety check placed it in the category \"cyber\".\n\n**What it did** — 0 steps, "
                + "2,300 cloud tokens, "), r.response());
        assertEquals(2_300, rig.jdbc.queryForObject("SELECT tokens_used FROM token_usage WHERE user_id = 'u1'", Integer.class),
                "the declined reply was billed, so it is counted");

        // A chat longer than the model can read: the provider says so, and the task ends saying it.
        var tooLong = new LoopRig(tmp.resolve("long"), List.of(), 600, new StopWithoutLocalModelTest.Down(),
                (registry, config, router) -> new ThinkingEngine(registry, config, router) {
                    @Override
                    public ThinkResult decideNextActionFull(AgentContext context, com.ownclaw.llm.LlmProvider provider) {
                        throw new com.ownclaw.llm.OutputTruncated("anthropic",
                                com.ownclaw.llm.OutputTruncated.Limit.CONTEXT_WINDOW, 1_000_000, null);
                    }
                });
        AgentResult cut = tooLong.turn(session(tooLong), "and now?");
        assertEquals(AgentResult.TerminationReason.CONTEXT_WINDOW, cut.terminationReason());
        assertTrue(cut.response().startsWith("**Stopped:** [anthropic] the conversation is longer than the model's "
                + "1,000,000-token context window.\n\n"), cut.response());
    }

    @Test
    @DisplayName("an ending on the context window says to carry on in a new chat: every message in this one is read with all of it")
    void aChatTooLongForTheModelSaysToStartANewOne(@TempDir Path tmp) throws Exception {
        // Two public results that fit one at a time and not together: the model refuses the
        // third call as the provider does a prompt over its window.
        String log = "203.0.113.7 GET /index.html 200\n".repeat(1_800);
        var rig = new LoopRig(tmp, List.of(tool("read_access_log", List.of(), p -> log)));
        rig.cloud.think.add(call("read_access_log", Map.of("day", "1")));
        rig.cloud.think.add(call("read_access_log", Map.of("day", "2")));
        rig.cloud.think.add(c -> {
            throw new com.ownclaw.llm.OutputTruncated("anthropic",
                    com.ownclaw.llm.OutputTruncated.Limit.CONTEXT_WINDOW, 1_000_000, null);
        });
        AgentResult r = rig.turn(session(rig), "which IP hits my site most?");

        assertEquals(AgentResult.TerminationReason.CONTEXT_WINDOW, r.terminationReason(), r.response());
        assertTrue(r.response().contains("**Next:** A message sent in this chat is read with the whole chat, "
                + "this ending included, so it is likely to be too long as well: start a new chat (/new) to "
                + "carry on, and say there what it needs from this one."), r.response());
        assertFalse(r.response().contains("Your next message starts a new task, which reads this message"),
                "the owner was sent back into the chat that could no longer be read: " + r.response());
        // Mutation: noAnswer ends every refused think as ERROR -> the generic Next line.
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
        String session = session(rig);
        rig.cloud.think.add(call("noop", Map.of()));
        for (int i = 0; i < 3; i++) rig.cloud.think.add(c -> com.ownclaw.llm.Replies.of("", 300, 0, 0, 0, "end_turn"));
        AgentResult first = rig.turn(session, "check the network");
        assertEquals(AgentResult.TerminationReason.FAILURE_LIMIT, first.terminationReason());
        assertTrue(first.response().startsWith("**Stopped:** The model produced nothing that could be run or "
                + "delivered 3 times in a row.\n\n**What it did** — 4 steps, "), first.response());
        assertTrue(first.response().contains("\n1. ✓ noop"), first.response());
        assertTrue(first.response().contains("**Next:** Your next message starts a new task"), first.response());

        rig.cloud.think.add(respond("second"));
        rig.turn(session, "carry on");
        String next = String.join("\n", userParts(rig.cloud.calls("think").get(4)));
        assertTrue(next.contains("ASSISTANT: **Stopped:** The model produced nothing"), next);
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

    /** A reply the provider stopped as {@code category}: billed, and no answer. */
    static Reply declined(String category) {
        return c -> new com.ownclaw.llm.LlmResponse("", List.of(), null, "refusal", category,
                "claude-opus-5", 128_000, 1_000_000,
                List.of(new com.ownclaw.llm.LlmResponse.Usage("claude-opus-5", 2_000, 300, 0, 0)));
    }

    static int cloudTokens(LoopRig rig) {
        return rig.jdbc.queryForObject("SELECT sum(tokens_used) FROM token_usage WHERE user_id = 'u1'", Integer.class);
    }

    @Test
    @DisplayName("a step declined as reasoning extraction is asked again, with no words asked for beside the call")
    void aStepDeclinedAsReasoningIsAskedAgainQuietly(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of(NOOP));
        rig.jdbc.update("INSERT INTO users (id, display_name) VALUES ('u1', 'Owner')");
        String beside = "Checking the network first.";
        rig.cloud.think.add(c -> com.ownclaw.llm.Replies.of(beside, 1_000, 100, 0, 0, "tool_use",
                List.of(new com.ownclaw.llm.ToolCall("c-noop", "noop", Map.of()))));
        rig.cloud.think.add(declined("reasoning_extraction"));
        rig.cloud.think.add(call("noop", Map.of()));
        rig.cloud.think.add(respond("all quiet"));
        AgentResult r = rig.turn(session(rig), "check the network");

        assertEquals(AgentResult.TerminationReason.COMPLETED, r.terminationReason(), r.response());
        var thinks = rig.cloud.calls("think");
        assertEquals(4, thinks.size());
        String invited = "reads what you write beside each call";
        List<LlmMessage> asked = thinks.get(1).messages();
        assertTrue(asked.get(0).content().contains(ThinkingEngine.NARRATION), asked.get(0).content());
        assertTrue(asked.get(asked.size() - 1).content().contains(invited), "the premise: the per-step block invites them");
        assertTrue(asked.stream().anyMatch(m -> m.content().contains(beside)), "the premise: step 1's words are replayed");
        for (var again : thinks.subList(2, 4)) {
            var quiet = again.messages();
            assertFalse(quiet.get(0).content().contains(ThinkingEngine.NARRATION), quiet.get(0).content());
            assertTrue(quiet.get(0).content().contains("Call exactly one tool per step, and write nothing beside it."),
                    quiet.get(0).content());
            String last = quiet.get(quiet.size() - 1).content();
            assertTrue(last.contains("THE USER IS WAITING") && !last.contains(invited), last);
            assertTrue(quiet.stream().noneMatch(m -> m.content().contains(beside)),
                    "the words beside an earlier call are not shown again: " + quiet);
        }
        assertEquals(2, r.trajectory().steps().size(), "the declined step is not one of them");
        assertEquals(3 * 1_100 + 2_300, cloudTokens(rig), "the declined reply was billed, so it is counted");
        List<String> rows = rig.jdbc.queryForList("SELECT content FROM conversations WHERE role = 'progress'", String.class);
        assertTrue(rows.stream().anyMatch(c -> c.startsWith("⚠️ The cloud model's provider stopped step 2 as "
                + "reasoning extraction")), String.valueOf(rows));
        assertTrue(rows.stream().anyMatch(c -> c.startsWith("☁️ Step 2 · noop")), "the step asked again keeps its "
                + "number: " + rows);
    }

    @Test
    @DisplayName("declined as reasoning extraction again when asked quietly, the task ends saying so in words")
    void declinedTwiceEndsInWords(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of());
        rig.jdbc.update("INSERT INTO users (id, display_name) VALUES ('u1', 'Owner')");
        rig.cloud.think.add(declined("reasoning_extraction"));
        rig.cloud.think.add(declined("reasoning_extraction"));
        AgentResult r = rig.turn(session(rig), "check the network");

        assertEquals(AgentResult.TerminationReason.ERROR, r.terminationReason());
        var thinks = rig.cloud.calls("think");
        assertEquals(2, thinks.size(), "asked again once, not until it answers");
        assertFalse(thinks.get(1).messages().get(0).content().contains(ThinkingEngine.NARRATION), "asked quietly");
        assertTrue(r.response().startsWith("**Stopped:** the cloud model's provider (anthropic) stopped the step as "
                + "reasoning extraction -- a safety check against giving away the model's hidden reasoning -- and "
                + "did so again after the model was asked for no progress updates beside its calls.\n\n"
                + "**What it did** — 0 steps, 4,600 cloud tokens, "), r.response());
        assertTrue(r.response().contains("**Next:** Your next message starts a new task"), r.response());
    }

    @Test
    @DisplayName("the library analysis streams with the task's hook, and a refused one is a failed step that says why")
    void theAnalysisIsTheTasks(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of());
        rig.cloud.think.add(call(AgentAction.SKILL_MANAGE, Map.of("action", "analyze")));
        rig.cloud.think.add(c -> {
            c.progress().onProgress();
            return new com.ownclaw.llm.LlmResponse("", List.of(), null, "refusal", "cyber",
                    "claude-opus-5", 128_000, 1_000_000,
                    List.of(new com.ownclaw.llm.LlmResponse.Usage("claude-opus-5", 10, 10, 0, 0)));
        });
        rig.cloud.think.add(respond("ok"));
        rig.turn(session(rig), "tidy up my skills");

        var analysis = rig.cloud.calls.stream().filter(c -> "analyze".equals(c.purpose())).findFirst().orElseThrow();
        assertNotSame(com.ownclaw.llm.LlmProgress.NONE, analysis.config().progress(), "the call carries no hook");
        String observed = String.join("\n", userParts(rig.cloud.calls("think").get(1)));
        assertTrue(observed.contains("ERROR: the analysis of the skill library failed: [anthropic] the model "
                + "declined this request (stop reason: refusal (cyber))"), observed);
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

    @Test
    @DisplayName("a finished answer that places a result holding a vault value keeps the value out of every row and screen")
    void aFinishedAnswerIsScrubbed(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of(
                tool("openwrt_audit", List.of("OPENWRT_USER", "OPENWRT_PASS"), p -> AUDIT),
                tool("lan_inventory", List.of(), p -> "main router, admin password " + PASSWORD)));
        rig.config.getPrivacy().setPersonalSources(List.of("OPENWRT_"));   // the audit stands for a private result
        rig.jdbc.update("INSERT INTO users (id, display_name) VALUES ('u1', 'Owner')");
        rig.vault.storeCredential("u1", "OPENWRT_PASS", PASSWORD);
        String session = session(rig);

        rig.cloud.think.add(call("openwrt_audit", Map.of()));
        rig.cloud.think.add(respond("{{1}}"));
        AgentResult audit = rig.turn(session, "audit the routers");
        assertEquals(AgentResult.TerminationReason.COMPLETED, audit.terminationReason(), audit.response());
        assertEquals(AgentLoop.PRIVATE_RESULT_HEADER + AUDIT.replace(PASSWORD, "«vault:OPENWRT_PASS»"), audit.ownerText());

        rig.cloud.think.add(call("lan_inventory", Map.of()));
        rig.cloud.think.add(respond("{{1}}"));
        AgentResult inventory = rig.turn(session, "and the inventory?");
        assertEquals("main router, admin password «vault:OPENWRT_PASS»", inventory.response());

        for (var row : rig.jdbc.queryForList("SELECT content, private_content FROM conversations")) {
            assertFalse(String.valueOf(row.get("content")).contains(PASSWORD), "saved for later prompts: " + row);
            assertFalse(String.valueOf(row.get("private_content")).contains(PASSWORD), "saved for the owner's screen: " + row);
        }
        // Mutation: TaskEnding returns a finished answer untouched -> the password in both rows.
    }

    @Test
    @DisplayName("an exception whose message quotes a private result ends without that message")
    void anExceptionQuotingAPrivateResultIsWithheld(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of(tool("openwrt_audit", List.of("OPENWRT_PASS"), p -> AUDIT)));
        rig.config.getPrivacy().setPersonalSources(List.of("OPENWRT_"));   // the audit stands for a private result
        rig.cloud.think.add(call("openwrt_audit", Map.of()));
        rig.cloud.think.add(c -> { throw new IllegalStateException("could not read the reply near: " + AUDIT.substring(0, 160)); });

        AgentResult r = rig.turn(session(rig), "audit the routers");
        assertEquals(AgentResult.TerminationReason.ERROR, r.terminationReason());
        assertTrue(r.response().startsWith("**Stopped:** an internal error: IllegalStateException (its message quotes "
                + "a private result; it is in the log).\n\n"), r.response());
        String audit = PrivateIndex.normalise(AUDIT);
        String said = PrivateIndex.normalise(r.response());
        for (int i = 0; i + PrivateIndex.WINDOW <= audit.length(); i++) {
            assertFalse(said.contains(audit.substring(i, i + PrivateIndex.WINDOW)), "the ending quotes the audit at " + i);
        }
        // Mutation: never withhold the message -> the audit's first lines are in the chat.
    }

    @Test
    @DisplayName("a Stop during a step ends the task as stopped, with no invitation to carry on")
    void aStopDuringAStepIsAStop(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of(tool("slow_scan", List.of(), p -> {
            try {
                Thread.sleep(800);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "scanned";
        })));
        rig.cloud.think.add(call("slow_scan", Map.of()));
        var stop = new Thread(() -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                return;
            }
            rig.cancellation.requestAll("u1", "you pressed Stop");
        });
        stop.start();
        AgentResult r = rig.turn(session(rig), "scan the network");
        stop.join();

        assertEquals(AgentResult.TerminationReason.CANCELLED, r.terminationReason(), r.response());
        assertTrue(r.response().startsWith("**Stopped:** you pressed Stop.\n\n"), r.response());
        assertFalse(r.response().contains("continue"), "no invitation to carry on after Stop: " + r.response());
        assertEquals(1, rig.cloud.calls("think").size(), "the step after the Stop was never asked for");
        // Mutation: check the stop only inside a model call -> the script has no second reply.
    }

    @Test
    @DisplayName("a skill_create step is timed from its first code call: the writing is most of what it takes")
    void aSkillCreateStepIncludesItsCode(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of());
        rig.cloud.think.add(call(AgentAction.SKILL_CREATE, Map.of("name", "openwrt_audit",
                "description", "Audit every OpenWrt router.", "parameters", "{}")));
        rig.cloud.codegen.add(silent(400, SkillCodegenTest.finished(SkillCodegenTest.module(""))));
        rig.cloud.think.add(respond("done"));
        AgentResult r = rig.turn(session(rig), "build an audit skill");
        long ms = rig.jdbc.queryForObject("SELECT json_extract(details, '$.durationMs') FROM events "
                + "WHERE event_type = 'step' AND task_id = ?", Long.class, r.taskId());
        assertTrue(ms >= 400, "the step took " + ms + " ms, and its code call alone 400");
        // Mutation: start the clock after the code is written -> a few ms.
    }

    @Test
    @DisplayName("recall writes the handles of past tasks as words: here they would name this task's results")
    void recalledHandlesAreWords(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of());
        new com.ownclaw.agent.memory.SqliteAgentMemory(rig.jdbc).storeEpisode("u1", "aaaa0001",
                "Task: router audit\nResponse: see {{2}} and {{3.body_text}}", true, List.of());
        rig.cloud.think.add(call(AgentAction.MEMORY_MANAGE, Map.of("action", "recall", "query", "router")));
        rig.cloud.think.add(respond("ok"));
        rig.turn(session(rig), "what did the audit find?");
        String observed = sentTo(rig, 1);
        assertTrue(observed.contains("Response: see result 2 and result 3.body_text"), observed);
        // Mutation: hand the episodes over as stored -> "see {{2}}", this task's second result.
    }

    @Test
    @DisplayName("recall names the words it did not look for, and says so when there is none to look for")
    void recallSaysWhatItLookedFor(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of());
        new com.ownclaw.agent.memory.SqliteAgentMemory(rig.jdbc).storeEpisode("u1", "aaaa0001",
                "Task: reboot the AP in the hall", true, List.of());
        rig.cloud.think.add(call(AgentAction.MEMORY_MANAGE, Map.of("action", "recall", "query", "the AP")));
        rig.cloud.think.add(call(AgentAction.MEMORY_MANAGE, Map.of("action", "recall", "query", "the and")));
        rig.cloud.think.add(respond("ok"));
        rig.turn(session(rig), "when did I last reboot the AP?");
        String observed = sentTo(rig, 2);
        assertTrue(observed.contains("1 past task matches 'the AP' (not looked for: the — words and letters nearly "
                + "every task has), most relevant first:"), observed);
        assertTrue(observed.contains("Task: reboot the AP in the hall"), observed);
        assertTrue(observed.contains("ERROR: 'the and' has no word to look for (not looked for: the, and"), observed);
    }

    @Test
    @DisplayName("a past task recalled beside a private traceback is sent: the cloud had it before, and every traceback opens alike")
    void aRecalledTracebackIsNotALeak(@TempDir Path tmp) throws Exception {
        // A credentialed skill fails; its traceback is private and indexed, and its first line is
        // a 32-character window of every Python traceback -- as is last week's public one, in the
        // record of a task that stopped on it, which the cloud recalls before or after the failure.
        Tool imap = new Tool() {
            public String name() { return "imap_fetch"; }
            public String description() { return "test skill imap_fetch"; }
            public Map<String, com.ownclaw.agent.tools.ToolParam> inputSchema() { return Map.of(); }
            public List<String> requiredCredentials() { return List.of("IMAP_PASS"); }
            public com.ownclaw.agent.tools.ToolResult execute(Map<String, Object> p, com.ownclaw.agent.tools.ToolExecutionContext c) {
                return com.ownclaw.agent.tools.ToolResult.failure("Skill error: login refused\n"
                        + "Traceback (most recent call last):\n  File \"/skills/imap_fetch/skill.py\", line 22, in run\n"
                        + "imaplib.IMAP4.error: [AUTHENTICATIONFAILED] Invalid credentials for petr@example.org");
            }
        };
        String lastWeek = "Task: fetch the lunch menu\nSteps: 1\nOutcome: MAX_STEPS\nResponse: **Stopped:** the "
                + "task used all 1 steps.\n\n**What it did** — 1 step\n1. ✗ web_fetch · 7ms — Skill error: timed out\n"
                + "Traceback (most recent call last):\n  File \"/skills/web_fetch/skill.py\", line 14, in run\n"
                + "TimeoutError: timed out";
        for (boolean recallFirst : List.of(false, true)) {
            var rig = new LoopRig(tmp.resolve(recallFirst ? "recall-first" : "fail-first"), List.of(imap));
            new com.ownclaw.agent.memory.SqliteAgentMemory(rig.jdbc).storeEpisode("u1", "0ld7a5k1", lastWeek,
                    false, List.of("web_fetch"));
            Reply recall = call(AgentAction.MEMORY_MANAGE, Map.of("action", "recall", "query", "fetch"));
            Reply fetch = call("imap_fetch", Map.of());
            rig.cloud.think.add(recallFirst ? recall : fetch);
            rig.cloud.think.add(recallFirst ? fetch : recall);
            rig.cloud.think.add(respond("The mail fetch failed: the server refused the login."));

            AgentResult r = rig.turn(session(rig), "fetch my new mail");

            String order = recallFirst ? "recalled first: " : "failed first: ";
            assertEquals(AgentResult.TerminationReason.COMPLETED, r.terminationReason(), order + r.response());
            assertEquals(3, rig.cloud.calls("think").size(), order + "every request was sent");
            assertTrue(sentTo(rig, 2).contains("TimeoutError: timed out"), order + "the past task, whole");
            assertFalse(sentTo(rig, 2).contains("AUTHENTICATIONFAILED"), order + "the private traceback stays here");
        }
    }

    @Test
    @DisplayName("a task stopped before it began -- a file, and no local model -- leaves no episode")
    void anEarlyStopIsNotRemembered(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of());
        String pdf = rig.files.store("u1", "statement.pdf", "application/pdf",
                new java.io.ByteArrayInputStream("%PDF-1.7 binary".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        AgentResult r = rig.loop.executeFull("u1", "summarise this statement", false, null, List.of(pdf), null);
        assertEquals(AgentResult.TerminationReason.ERROR, r.terminationReason());
        assertEquals(1, rig.jdbc.queryForObject(
                "SELECT count(*) FROM events WHERE event_type = 'task_completed' AND task_id = ?", Integer.class, r.taskId()));
        assertEquals(0, rig.jdbc.queryForObject("SELECT count(*) FROM agent_memory WHERE memory_type = 'episode'",
                Integer.class), "nothing was done, so there is nothing to remember");
    }

    @Test
    @DisplayName("an empty vault: the model is told the owner types /cred set, not to ask for values")
    void anEmptyVaultPointsAtCredSet(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of());
        rig.cloud.think.add(call(AgentAction.CREDENTIAL_MANAGE, Map.of("action", "list")));
        rig.cloud.think.add(respond("ok"));
        rig.turn(session(rig), "which credentials do I have?");
        String observed = sentTo(rig, 1);
        assertTrue(observed.contains("No credentials stored. Ask the user to type this in the chat"), observed);
        assertTrue(observed.contains("/cred set KEY <value>"), observed);
    }

    @Test
    @DisplayName("the task's message is recorded whole: its task_completed row and its episode")
    void theMessageIsRecordedWhole(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of());
        String message = "check every host on the network " + "and each open port with its service ".repeat(12);
        rig.cloud.think.add(respond("done"));
        AgentResult r = rig.turn(session(rig), message);
        assertEquals(message, rig.jdbc.queryForObject(
                "SELECT summary FROM events WHERE event_type = 'task_completed' AND task_id = ?", String.class, r.taskId()));
        assertEquals("Task: " + message + "\nSteps: 0\nOutcome: COMPLETED\nResponse: done", rig.jdbc.queryForObject(
                "SELECT content FROM agent_memory WHERE memory_type = 'episode' AND task_id = ?", String.class, r.taskId()));
    }

    @Test
    @DisplayName("what the activity panel and debug mode are sent is whole: a failure, a result, the prompt; "
            + "a failure's status line, which Telegram is sent too, says where it is instead")
    void theActivityIsWhole(@TempDir Path tmp) throws Exception {
        String failure = "Traceback (most recent call last): " + "frame ".repeat(100);
        String big = "row ".repeat(15_000);
        var rig = new LoopRig(tmp, List.of(failing("probe", failure), tool("dump", List.of(), p -> big)));
        rig.debug.toggle("u1");
        var seen = rig.statuses();
        String message = "inspect the hosts " + "and every service on them ".repeat(40);
        rig.cloud.think.add(call("probe", Map.of()));
        rig.cloud.think.add(call("dump", Map.of()));
        rig.cloud.think.add(respond("done"));
        var r = rig.turn(session(rig), message);

        assertTrue(seen.stream().anyMatch(m -> m.data() != null && "observe".equals(m.data().get("category"))
                && "probe".equals(m.data().get("tool")) && failure.equals(m.data().get("output"))),
                "the failure in the observe detail");
        var warnings = seen.stream().filter(m -> m.type() == Type.WARNING).map(m -> m.text()).toList();
        assertEquals(1, warnings.size(), String.valueOf(warnings));
        assertTrue(warnings.getFirst().matches("probe ✗ \\S+, " + failure.length() + " chars — task " + r.taskId()),
                "the failure's status line: " + warnings.getFirst());
        assertTrue(seen.stream().anyMatch(m -> m.data() != null && "observe".equals(m.data().get("category"))
                && big.equals(m.data().get("output"))), "the result in the observe detail");
        assertTrue(seen.stream().anyMatch(m -> m.data() != null && "think".equals(m.data().get("category"))
                && String.valueOf(m.data().get("prompt")).contains(message)), "the prompt in the think detail");
        assertTrue(seen.stream().anyMatch(m -> m.type() == Type.DEBUG && m.text().startsWith("TOOL RESULT [dump] OK (")
                && m.text().endsWith("ms)\n" + big)), "the result in debug mode");
        // Mutations: the failure's text back in its status line; the 1,000 and 800-character
        // detail cuts, or the 50,000-character debug cut.
    }

    /** A mail skill that keeps what it was asked to send. */
    static Tool mailer(List<Map<String, Object>> sent) {
        return tool("smtp_send_email", List.of(), p -> {
            sent.add(p);
            return "Sent";
        });
    }

    /** The model writes {@code text} and calls no tool. */
    static Reply says(String text) {
        return c -> com.ownclaw.llm.Replies.of(text, 1_000, 100, 0, 0, "end_turn");
    }

    @Test
    @DisplayName("an answer that quotes an example call is the answer: the example is not run")
    void anAnswerQuotingACallIsTheAnswer(@TempDir Path tmp) throws Exception {
        var sent = new java.util.ArrayList<Map<String, Object>>();
        var rig = new LoopRig(tmp, List.of(mailer(sent)));
        String answer = "You can send a test mail yourself. The call looks like this:\n\n```json\n"
                + "{\"tool\": \"smtp_send_email\", \"params\": {\"to\": \"someone@example.org\", \"body\": \"test\"}}\n"
                + "```\n\nIt goes out from the account in your vault.";
        rig.cloud.think.add(says(answer));
        AgentResult r = rig.turn(session(rig), "how would I send a test mail with you?");

        assertEquals(AgentResult.TerminationReason.COMPLETED, r.terminationReason(), r.response());
        assertEquals(answer, r.response(), "the answer the owner asked for, whole");
        assertTrue(sent.isEmpty(), "the example was sent: " + sent);
        assertEquals(1, rig.cloud.calls("think").size());
        // Mutation: read the first object with a "tool" key anywhere in the text -> the example
        // email is sent and the answer is never delivered.
    }

    @Test
    @DisplayName("a reply that is an action envelope and nothing else is still the action, fenced or not")
    void aWholeEnvelopeIsStillAnAction(@TempDir Path tmp) throws Exception {
        // What a local model orchestrating in the cloud's place writes when it ignores the tools.
        var sent = new java.util.ArrayList<Map<String, Object>>();
        var rig = new LoopRig(tmp, List.of(mailer(sent)));
        rig.cloud.think.add(says("```json\n{\"tool\": \"smtp_send_email\", \"params\": "
                + "{\"to\": \"owner@example.org\", \"body\": \"hi\"}}\n```"));
        rig.cloud.think.add(says("{\"tool\": \"respond\", \"params\": {\"message\": \"Sent.\"}}"));
        AgentResult r = rig.turn(session(rig), "mail me hi");

        assertEquals(AgentResult.TerminationReason.COMPLETED, r.terminationReason(), r.response());
        assertEquals("Sent.", r.response());
        assertEquals(List.of(Map.of("to", "owner@example.org", "body", "hi")), sent);
    }

    @Test
    @DisplayName("an empty answer or question is not delivered: the model is told, and asked again")
    void anEmptyAnswerIsNotDelivered(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of(NOOP));
        // The answer written beside the call, and the call's message left empty.
        rig.cloud.think.add(c -> com.ownclaw.llm.Replies.of(
                "Both routers answer; guest isolation is broken on the access point.", 1_000, 100, 0, 0,
                "tool_use", List.of(new com.ownclaw.llm.ToolCall("c1", AgentAction.RESPOND, Map.of("message", "")))));
        rig.cloud.think.add(call(AgentAction.ASK_USER, Map.of("message", "  ")));
        rig.cloud.think.add(respond("Guest isolation is broken on the access point."));
        AgentResult r = rig.turn(session(rig), "audit the routers");

        assertEquals(AgentResult.TerminationReason.COMPLETED, r.terminationReason(), r.response());
        assertEquals("Guest isolation is broken on the access point.", r.response());
        assertEquals(3, rig.cloud.calls("think").size());
        for (int call : new int[] {1, 2}) {
            assertTrue(sentTo(rig, call).contains("Not delivered: the message is empty."), sentTo(rig, call));
        }
        // Mutation: refuse only a placed handle that is empty -> the empty respond is delivered:
        // an empty bubble, and on Telegram an error.
    }

    @Test
    @DisplayName("a skill name that cannot be one is refused before any code is written for it")
    void aRefusedNameCostsNoCode(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of(NOOP));
        Map<String, Object> spec = Map.of("description", "audits the routers", "parameters", "{}");
        var tooLong = new java.util.HashMap<>(spec);
        tooLong.put("name", "fetch_all_openwrt_routers_on_the_lan_and_audit_firmware_and_wifi_x");
        var variant = new java.util.HashMap<>(spec);
        variant.put("name", "noop_v2");
        rig.cloud.think.add(call(AgentAction.SKILL_CREATE, tooLong));
        rig.cloud.think.add(call(AgentAction.SKILL_CREATE, variant));
        rig.cloud.think.add(respond("done"));
        rig.cloud.codegen.add(SkillCodegenTest.finished(SkillCodegenTest.module("")));
        rig.cloud.codegen.add(SkillCodegenTest.finished(SkillCodegenTest.module("")));
        rig.turn(session(rig), "build me a router audit");

        assertEquals(0, rig.cloud.calls("codegen").size(), "code was written for a name that was then refused");
        String told = sentTo(rig, 2);
        assertTrue(told.contains("ERROR: Invalid skill name: " + SkillManager.SKILL_NAME_RULE), told);
        assertTrue(told.contains("ERROR: Skill 'noop' already exists. Do NOT create 'noop_v2'."), told);
        // Mutation: check the name only in createSkill, after the code -> two code calls billed.
    }
}
