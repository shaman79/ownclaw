package com.ownclaw.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownclaw.agent.tools.ToolResult;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.llm.LlmMessage;
import com.ownclaw.llm.LlmProvider;
import com.ownclaw.llm.LlmRequestConfig;
import com.ownclaw.llm.LlmResponse;
import com.ownclaw.privacy.Redactor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.ownclaw.agent.DelegationBehaviourTest.*;
import static com.ownclaw.agent.LoopRig.respond;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Texts a delegation is handed by name: the cloud puts a script, a configuration file or a message
 * body in delegate's 'texts', the goal names it {{name}}, the local model writes {{name}} in its
 * tool call, and code puts the text there -- so the local model never retypes it. And the guard
 * that makes it the only way: a delegation whose plan holds a block of lines is refused before
 * anything runs, and the cloud is told to hand the block over by name.
 * <p>
 * On 2026-10-08 the local model retyped a 33-line watchdog install written into the goal: replayed,
 * one line came back broken in five calls of seven, and the production run looped for two and a
 * half hours.
 * The shape of that goal is the guard's case here, with documentation addresses.
 */
class DelegationTextsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The watchdog install, as the cloud wrote it on 2026-10-08: a heredoc, then the commands around it. */
    static final String SCRIPT = String.join("\n",
            "cat > /usr/sbin/wifi-vap-watchdog.sh <<'XEOF'",
            "#!/bin/sh",
            "# Restarts wifi when an AP VAP lost its channel (iwinfo reports \"Channel: 0\").",
            "LOG=/tmp/wifi-vap-watchdog.log",
            "DEAD=0",
            "for i in $(iwinfo 2>/dev/null | grep ESSID | cut -d' ' -f1); do",
            "  M=$(iwinfo $i info 2>/dev/null | grep -c \"Mode: Master\")",
            "  C=$(iwinfo $i info 2>/dev/null | grep -c \"Channel: 0 \")",
            "  if [ \"$M\" -ge 1 ] && [ \"$C\" -ge 1 ]; then",
            "    echo \"$(date) DEAD_VAP $i\" >> $LOG",
            "    DEAD=1",
            "  fi",
            "done",
            "if [ \"$DEAD\" = \"1\" ]; then",
            "  echo \"$(date) running wifi up\" >> $LOG",
            "  wifi up",
            "  sleep 45",
            "fi",
            "tail -n 200 $LOG > $LOG.tmp 2>/dev/null && mv $LOG.tmp $LOG",
            "XEOF",
            "chmod +x /usr/sbin/wifi-vap-watchdog.sh",
            "echo \"*/5 * * * * /usr/sbin/wifi-vap-watchdog.sh\" >> /etc/crontabs/root",
            "/etc/init.d/cron restart",
            "echo \"== PING\"; ping -c2 -W2 192.0.2.1 | tail -2");

    /** The lines between the heredoc's opening line and its terminator. */
    static final int HEREDOC_LINES = 18;

    static final String HEAD = "Install a small watchdog on two OpenWRT devices, 192.0.2.2 and 192.0.2.4. Use "
            + "the tool openwrt_run_to_file. Do this for BOTH hosts, one after the other (two separate calls).\n\n"
            + "For host 192.0.2.2 use file=\"/tmp/wd2.txt\"; for host 192.0.2.4 use file=\"/tmp/wd4.txt\". ";
    static final String TAIL = "\n\nAfter both calls, call shell_exec with: cat /tmp/wd2.txt; echo \"#### HOST4 ####\"; "
            + "cat /tmp/wd4.txt\n\nReturn the full verbatim combined output. Do not summarise.";

    /** The goal as it was sent: the script written into it. */
    static final String RETYPED = HEAD + "Use EXACTLY this script for both (identical text):\n\n" + SCRIPT + TAIL;

    /** The same goal with the script handed over by name. */
    static final String BY_NAME = HEAD + "The command for both is exactly {{watchdog_script}}." + TAIL;

    static final Map<String, String> TEXTS = Map.of("watchdog_script", SCRIPT);

    static DelegationPlan plan(String goal, Map<String, String> texts) {
        return new DelegationPlan(goal, List.of(), List.of(), List.of(), texts);
    }

    // ── parsing ──

    @Test
    @DisplayName("texts are read as an object of names to texts, in their order, or as that object written as JSON")
    void textsAreRead() throws Exception {
        var texts = new LinkedHashMap<String, Object>();
        texts.put("watchdog_script", SCRIPT);
        texts.put("note", "");
        var plan = LocalExecutor.parsePlan(Map.of("goal", "g", "texts", texts));
        assertEquals(List.of("watchdog_script", "note"), List.copyOf(plan.texts().keySet()));
        assertEquals(SCRIPT, plan.texts().get("watchdog_script"), "taken exactly as written");
        assertEquals(Map.of("s", SCRIPT), LocalExecutor.parsePlan(Map.of("goal", "g", "texts",
                JSON.writeValueAsString(Map.of("s", SCRIPT)))).texts(), "a JSON object sent as a string");
        assertEquals(Map.of(), LocalExecutor.parsePlan(Map.of("goal", "g")).texts());
    }

    @Test
    @DisplayName("a name that is not one, a text that is not a string, or texts that are not an object are refused, never guessed at")
    void badTextsAreRefused() {
        for (String name : List.of("Watchdog", "1", "2nd", "watchdog-script", "with space", "", "{{x}}")) {
            var e = assertThrows(IllegalArgumentException.class,
                    () -> LocalExecutor.parsePlan(Map.of("goal", "g", "texts", Map.of(name, "x"))), name);
            assertTrue(e.getMessage().contains("'" + name + "' cannot name a text"), e.getMessage());
        }
        for (Object value : List.of(42, Map.of("a", "b"), List.of("a"))) {
            var e = assertThrows(IllegalArgumentException.class,
                    () -> LocalExecutor.parsePlan(Map.of("goal", "g", "texts", Map.of("s", value))), value.toString());
            assertTrue(e.getMessage().contains("texts.s must be a string"), e.getMessage());
        }
        for (Object texts : List.of(List.of("a"), "not json", 7)) {
            assertThrows(IllegalArgumentException.class,
                    () -> LocalExecutor.parsePlan(Map.of("goal", "g", "texts", texts)), texts.toString());
        }
    }

    @Test
    @DisplayName("delegate's schema declares texts an object of strings")
    @SuppressWarnings("unchecked")
    void theSchemaSaysSo() {
        var delegate = SpecialActionSchemas.ALL.stream().filter(s -> AgentAction.DELEGATE.equals(s.name()))
                .findFirst().orElseThrow();
        var texts = ((Map<String, Map<String, Object>>) delegate.inputSchema().get("properties")).get("texts");
        assertEquals("object", texts.get("type"));
        assertEquals(Map.of("type", "string"), texts.get("additionalProperties"));
        assertTrue(delegate.description().contains("goes in 'texts' under a name"), delegate.description());
    }

    // ── insertion ──

    @Test
    @DisplayName("{{name}} is replaced with the text: as the whole value, inside a longer one, and in a list")
    void aTextIsInserted() {
        var written = new LinkedHashMap<String, Object>();
        written.put("command", "{{watchdog_script}}");
        written.put("longer", "set -e\n{{watchdog_script}}\necho \"done: {{ watchdog_script }}\"");
        written.put("list", List.of("{{watchdog_script}}", Map.of("k", "x {{watchdog_script}}")));
        written.put("host", "192.0.2.2");
        var r = References.resolveInText(written, List.of(), TEXTS);
        assertTrue(r.ok(), r.reason());
        assertEquals(SCRIPT, r.params().get("command"));
        assertEquals("set -e\n" + SCRIPT + "\necho \"done: " + SCRIPT + "\"", r.params().get("longer"));
        assertEquals(List.of(SCRIPT, Map.of("k", "x " + SCRIPT)), r.params().get("list"));
        assertEquals("192.0.2.2", r.params().get("host"));
        assertEquals(List.of(), r.used(), "a text is no result");
    }

    @Test
    @DisplayName("a name that is no text is left as text; the cloud's own calls have no texts and insert nothing")
    void otherNamesAreText() {
        var r = References.resolveInText(Map.of("body", "Hi {{customer_name}}: {{watchdog_script}}"), List.of(), TEXTS);
        assertEquals("Hi {{customer_name}}: " + SCRIPT, r.params().get("body"), "a template's own placeholder");
        assertEquals("{{watchdog_script}}",
                References.resolveInText(Map.of("command", "{{watchdog_script}}"), List.of()).params().get("command"));
    }

    @Test
    @DisplayName("a scripted local model's {{watchdog_script}} reaches the tool as the exact script, alone and inside a longer command")
    void theToolGetsTheExactText() {
        var openwrt = new FakeTool("openwrt_run_to_file", true, List.of(), p -> ToolResult.success("written"));
        var llm = new Scripted(
                call("openwrt_run_to_file", Map.of("host", "192.0.2.2", "command", "{{watchdog_script}}",
                        "file", "/tmp/wd2.txt")),
                call("openwrt_run_to_file", Map.of("host", "192.0.2.4",
                        "command", "logger start; {{watchdog_script}}\nlogger end", "file", "/tmp/wd4.txt")),
                done("installed on both"));

        var outcome = executor(llm, new Usage(), openwrt).execute(plan(BY_NAME, TEXTS), task(), UNCOUNTED);

        assertTrue(outcome.ok(), outcome.text());
        assertEquals(SCRIPT, openwrt.calls.get(0).get("command"));
        assertEquals("logger start; " + SCRIPT + "\nlogger end", openwrt.calls.get(1).get("command"));
        String system = llm.calls.get(0).get(0).content();
        assertTrue(system.contains(String.format(java.util.Locale.ROOT, "### {{watchdog_script}} (%,d characters "
                + "in 24 lines)\n%s\n\n", SCRIPT.length(), SCRIPT)), "shown whole, to read: " + system);
        assertTrue(system.contains("write its handle, e.g. {{watchdog_script}}, in the argument"), system);
        // Mutation: resolve the arguments without the plan's texts -> the tool gets
        // "{{watchdog_script}}"; drop the texts from the plan as given -> the same.
    }

    @Test
    @DisplayName("a text holding {{1}} or another text's {{name}} is inserted as it is: one pass, never read for references again")
    void aTextIsNeverResolvedAgain() {
        var fetch = new FakeTool("fetch", false, List.of(), p -> ToolResult.success("RESULT-ONE"));
        var send = new FakeTool("send", true, List.of(), p -> ToolResult.success("sent"));
        var texts = new LinkedHashMap<String, String>();
        texts.put("template", "Dear {{1}}, see {{other}} and {{nope}}.");
        texts.put("other", "OTHER");
        var llm = new Scripted(call("fetch", Map.of()),
                call("send", Map.of("body", "{{1}} / {{template}} / {{other}} / {{nope}}")),
                done("sent"));

        executor(llm, new Usage(), fetch, send).execute(plan("Fetch, then send {{template}}.", texts), task(), UNCOUNTED);

        assertEquals("RESULT-ONE / Dear {{1}}, see {{other}} and {{nope}}. / OTHER / {{nope}}",
                send.calls.get(0).get("body"));
        // Mutation: resolve the value a second time after the insertion -> "Dear RESULT-ONE, see
        // OTHER"; replace a name that is no text with "" -> "/  / " where {{nope}} was.
    }

    // ── the guard ──

    @Test
    @DisplayName("the goal of 2026-10-08 is refused before anything runs, with what to do instead")
    void theProductionGoalIsRefused() {
        var block = LiteralBlocks.first(plan(RETYPED, Map.of()));
        assertNotNull(block);
        assertEquals("a heredoc", block.kind());
        assertEquals("the goal", block.where());
        assertEquals(HEREDOC_LINES, block.lines());
        assertEquals("cat > /usr/sbin/wifi-vap-watchdog.sh <<'XEOF'", block.first());
        String told = LiteralBlocks.refusal(block);
        assertTrue(told.startsWith("Not started: the goal holds a heredoc of 18 lines"), told);
        assertTrue(told.contains("'texts'") && told.contains("{{script}}") && told.contains("Nothing was run."), told);
    }

    @Test
    @DisplayName("the same work with the script handed over by name is not refused")
    void theSameByNameRuns() {
        assertNull(LiteralBlocks.first(plan(BY_NAME, TEXTS)));
    }

    @Test
    @DisplayName("a fenced block or a heredoc of two lines or more is refused, wherever the plan holds it")
    void blocksAreRefused() {
        Map<String, String> blocks = new LinkedHashMap<>();
        blocks.put("```sh\nuci set wireless.radio0.channel=36\nuci commit wireless\n```", "a fenced code block");
        blocks.put("Write this:\n~~~\noption a b\noption c d\n~~~\nthen reload.", "a fenced code block");
        blocks.put("Unclosed:\n```\na\nb", "a fenced code block");
        blocks.put("cat > /etc/x <<-EOF\n\toption a b\n\toption c d\n\tEOF", "a heredoc");
        blocks.put("cat > /etc/x << \"END\"\na\nb\nEND", "a heredoc");
        blocks.put("cat > /etc/x <<\\EOF\na\nb\nEOF", "a heredoc");
        for (var e : blocks.entrySet()) {
            var block = LiteralBlocks.first(plan(e.getKey(), Map.of()));
            assertNotNull(block, e.getKey());
            assertEquals(e.getValue(), block.kind(), e.getKey());
            assertEquals(2, block.lines(), e.getKey());
        }
        var steps = List.of(new DelegationPlan.Step("read it", "shell_exec", Map.of("command", "echo ok")),
                new DelegationPlan.Step("write it", "shell_exec", Map.of("command", List.of("a\nb"))));
        var inParams = LiteralBlocks.first(new DelegationPlan("g", steps, List.of()));
        assertEquals(new LiteralBlocks.Block("a value", "step 2's 'command' parameter", 2, "a"), inParams);
        var inDescription = LiteralBlocks.first(new DelegationPlan("g",
                List.of(new DelegationPlan.Step("run\n```\na\nb\n```", "shell_exec", Map.of())), List.of()));
        assertEquals("step 1's description", inDescription.where());
        var inCheckpoint = LiteralBlocks.first(new DelegationPlan("g", List.of(), List.of("ok", "the file reads\n```\na\nb\n```")));
        assertEquals("checkpoint 2", inCheckpoint.where());
    }

    @Test
    @DisplayName("short inline commands stay allowed: one line, in a block or not, and what only looks like a heredoc")
    void inlineCommandsStay() {
        for (String goal : List.of(
                "Run `uci show wireless` on 192.0.2.1 and return what it prints.",
                "Run: cat /tmp/wd2.txt; echo \"#### HOST4 ####\"; cat /tmp/wd4.txt",
                "```\nuci commit wireless\n```",
                "cat > /tmp/x <<EOF\noption a b\nEOF\nthen reload it.",
                "Compute $((1 << 4)) and read it with grep x <<< \"$v\".\nThen stop.",
                "Run ```ls -la /tmp``` and say\nwhat is there.",
                "The shift x << y\nis not a heredoc when no line ends it.")) {
            assertNull(LiteralBlocks.first(plan(goal, Map.of())), goal);
        }
        var oneLine = List.of(new DelegationPlan.Step("send it", "smtp_send_email",
                Map.of("to", "owner@example.org", "body", "{{1}}")));
        assertNull(LiteralBlocks.first(new DelegationPlan("g", oneLine, List.of())));
        // Mutation: LINES = 3 -> the two-line blocks above are let through; no heredoc pattern
        // -> the goal of 2026-10-08 runs.
    }

    // ── end to end ──

    @Test
    @DisplayName("refused, the cloud is told what to do and delegates again with the text by name; the tool gets it exactly")
    void theCloudIsToldAndHandsItOver(@TempDir Path tmp) throws Exception {
        var openwrt = new FakeTool("openwrt_run_to_file", true, List.of(), p -> ToolResult.success("written"));
        var local = new ProgressMessagesTest.Native(
                ProgressMessagesTest.Native.turn("Instaluji watchdog.", "openwrt_run_to_file", Map.of(
                        "host", "192.0.2.2", "command", "{{watchdog_script}}", "file", "/tmp/wd2.txt")),
                ProgressMessagesTest.Native.turn("", "done", Map.of("summary", "Installed on 192.0.2.2.")));
        var rig = new LoopRig(tmp, List.of(openwrt), 600, local);
        rig.cloud.think.add(LoopRig.call(AgentAction.DELEGATE, Map.of("goal", RETYPED, "tools", "openwrt_run_to_file")));
        rig.cloud.think.add(LoopRig.call(AgentAction.DELEGATE, Map.of("goal", BY_NAME, "tools", "openwrt_run_to_file",
                "texts", TEXTS)));
        rig.cloud.think.add(respond("Installed."));

        AgentResult r = rig.turn(rig.chat.createSession("u1", "Watchdog"), "nainstaluj watchdog");

        assertEquals("Installed.", r.response());
        assertEquals(1, openwrt.calls.size(), "only the delegation that handed the script over ran");
        assertEquals(SCRIPT, openwrt.calls.get(0).get("command"));
        for (var asked : local.asked) {
            for (LlmMessage m : asked) assertFalse(m.content().contains("Use EXACTLY this script"), m.content());
        }
        String second = rig.cloud.calls("think").get(1).messages().stream().map(LlmMessage::content)
                .reduce("", String::concat);
        assertTrue(second.contains("Not started: the goal holds a heredoc of 18 lines"), second);

        var notes = ProgressMessagesTest.progress(rig).stream().map(row -> String.valueOf(row.get("content")))
                .filter(c -> c.startsWith("↩️")).toList();
        assertEquals(List.of("↩️ The delegation of step 1 was not started: the goal holds a heredoc of 18 lines, "
                + "which the local model would have had to retype. The cloud model is asked to hand it over by "
                + "name instead."), notes);

        var steps = (List<?>) new com.ownclaw.observability.TaskTraceService(rig.events).trace("u1", r.taskId())
                .orElseThrow().get("steps");
        var refused = (Map<?, ?>) steps.get(0);
        assertEquals(true, refused.get("notStarted"));
        assertEquals(false, refused.get("ok"));
        assertEquals(RETYPED, refused.get("goal"), "the page shows what was refused, whole");
        var ran = (Map<?, ?>) steps.get(1);
        assertEquals(false, ran.get("notStarted"));
        assertEquals(BY_NAME, ran.get("goal"));
        assertEquals(TEXTS, ran.get("texts"), "and the texts, whole, with the goal");

        // The local tier had no turn in the refused step, so it opens no valve.
        var ctx = new AgentContext("u1", "t2", "nainstaluj watchdog");
        ctx.setUnattended(true);
        ctx.setLocalTierReady(true);
        var turn = r.trajectory().turns().get(0);
        ctx.trajectory().record(turn.action(), turn.observation());
        var config = new OwnClawConfig();
        config.getMentor().setNativeTools(true);
        config.getMentor().setLocalFirstUnattended(true);
        assertTrue(new ThinkingEngine(new com.ownclaw.agent.tools.ToolRegistry(List.of()), config, null)
                .stepMode(ctx, cloud()).localFirst(), "a refused delegation is no failed one");
        // Mutation: count a delegation refused before it started as a failed one -> the valve
        // opens; no guard in the loop -> the local model is given the goal and the tool runs twice.
    }

    @Test
    @DisplayName("a text holding a removed secret is refused before anything runs: it would be written in the secret's place")
    void aRemovedSecretIsRefused(@TempDir Path tmp) throws Exception {
        var shell = new FakeTool("shell_exec", true, List.of(), p -> ToolResult.success("ok"));
        var local = new ProgressMessagesTest.Native();
        var rig = new LoopRig(tmp, List.of(shell), 600, local);
        rig.cloud.think.add(LoopRig.call(AgentAction.DELEGATE, Map.of("goal", "Run {{conf}} on 192.0.2.1.",
                "tools", "shell_exec", "texts", Map.of("conf", "uci set wireless.k=" + Redactor.SECRET_REMOVED))));
        rig.cloud.think.add(respond("Not done."));

        rig.turn(rig.chat.createSession("u1", "Wifi"), "set the key");

        assertTrue(local.asked.isEmpty(), "the local model was given nothing");
        String second = rig.cloud.calls("think").get(1).messages().stream().map(LlmMessage::content)
                .reduce("", String::concat);
        assertTrue(second.contains("Not started: a text holds a removed secret."), second);
    }

    /** A cloud model that takes native tools, for the step mode. */
    private static LlmProvider cloud() {
        return new LlmProvider() {
            public LlmResponse chat(List<LlmMessage> m, LlmRequestConfig c) {
                throw new UnsupportedOperationException("not called");
            }
            public boolean isAvailable() { return true; }
            public boolean supportsTools() { return true; }
            public String name() { return "anthropic"; }
        };
    }
}
