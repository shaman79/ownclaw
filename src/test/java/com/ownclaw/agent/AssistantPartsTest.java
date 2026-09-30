package com.ownclaw.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownclaw.agent.tools.Tool;
import com.ownclaw.agent.tools.ToolExecutionContext;
import com.ownclaw.agent.tools.ToolParam;
import com.ownclaw.agent.tools.ToolRegistry;
import com.ownclaw.agent.tools.ToolResult;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.conversation.MigratedDatabase;
import com.ownclaw.core.LongRunningTaskManager;
import com.ownclaw.core.TaskCancellationService;
import com.ownclaw.core.TokenBudgetTracker;
import com.ownclaw.llm.CloudGateway;
import com.ownclaw.llm.EgressLedger;
import com.ownclaw.llm.EgressRefused;
import com.ownclaw.llm.LlmMessage;
import com.ownclaw.llm.LlmProvider;
import com.ownclaw.llm.LlmRequestConfig;
import com.ownclaw.llm.LlmResponse;
import com.ownclaw.llm.ToolCall;
import com.ownclaw.observability.ChatStatusEmitter;
import com.ownclaw.observability.DebugSessionService;
import com.ownclaw.observability.EventLogService;
import com.ownclaw.privacy.PrivateIndex;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The model's own earlier turns are not a disclosure to it -- and nothing else is ever replayed
 * as one.
 * <p>
 * A task stopped on the skill spec the cloud had written at its first step: a later PRIVATE
 * result, a writer's preview printed as JSON, quoted the report headings the spec had dictated,
 * and the Anthropic renderer replays the cloud's actions as JSON too -- so the same escaped run
 * sat in both, and the canary refused the cloud's own words. The gateway no longer scans
 * assistant parts. That is sound only while an assistant part holds nothing but what the model
 * wrote, which is what these tests pin, through the real renderer, the real gateway, and once
 * through the real loop.
 */
class AssistantPartsTest {

    static final ObjectMapper JSON = new ObjectMapper();

    /** What the cloud wrote at its first step: several lines and quotes, as it typed them. */
    static final String SPEC = String.join("\n",
            "Audit every router on the LAN. 192.0.2.1 is the main one.",
            "Output: a Markdown report that begins exactly with:",
            "# Network audit",
            "## Summary",
            "Devices checked: <n>, unreachable: <m>",
            "then one section per device with \"guest isolation: OK\" or \"BROKEN\".");

    /** What the credentialed audit returns: the spec's headings, then the owner's data. */
    static final String REPORT = String.join("\n",
            "# Network audit", "## Summary", "Devices checked: 2, unreachable: 0",
            "## 192.0.2.1 (main router)", "wireless.guest.ssid='guest-net'",
            "wireless.default_radio0.key='x7Qp-2Lm-9Rt-Wq4z'", "guest isolation: OK",
            "## 192.0.2.2 (access point)", "network.lan.gateway='192.0.2.1'", "guest isolation: OK");

    /** A writer that previews what it wrote, as the result dict the skill runner prints as JSON. */
    static String writerResult(Map<String, Object> p) {
        String content = String.valueOf(p.get("content"));
        try {
            return "{\"ok\": true, \"path\": " + JSON.writeValueAsString(p.get("path"))
                    + ", \"bytes_written\": " + content.length() + ", \"preview\": "
                    + JSON.writeValueAsString(content) + ", \"success\": true}";
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static Tool tool(String name, List<String> credentials, Function<Map<String, Object>, String> body) {
        return new Tool() {
            public String name() { return name; }
            public String description() { return "test skill " + name; }
            public Map<String, ToolParam> inputSchema() { return Map.of(); }
            public List<String> requiredCredentials() { return credentials; }
            public ToolResult execute(Map<String, Object> p, ToolExecutionContext c) {
                return ToolResult.success(body.apply(p));
            }
        };
    }

    /** A cloud behind the real gateway: answers from a script and keeps every request. */
    static final class Scripted implements LlmProvider {
        final List<ToolCall> script;
        final List<List<LlmMessage>> requests = new ArrayList<>();
        Scripted(List<ToolCall> script) { this.script = script; }
        public LlmResponse chat(List<LlmMessage> m, LlmRequestConfig c) {
            requests.add(m);
            var call = script.get(Math.min(requests.size(), script.size()) - 1);
            return new LlmResponse("", 100, 10, 0, 0, "tool_use", List.of(call));
        }
        public boolean isAvailable() { return true; }
        public boolean supportsTools() { return true; }
        public String name() { return "anthropic"; }
        public String model() { return "claude-opus-5"; }
    }

    static CloudGateway gateway(LlmProvider cloud, List<EgressLedger.Row> rows) {
        var config = new OwnClawConfig();
        config.getMentor().setProvider("anthropic");
        return new CloudGateway(cloud, cloud, config, rows::add, null);
    }

    static ToolCall call(String name, Map<String, Object> args) {
        return new ToolCall("c-" + name, name, args);
    }

    // ── the renderer and the gateway ──

    /**
     * The run as the loop records it: the spec step, the private audit {{1}}, and the write whose
     * JSON preview {{2}} repeats the audit escaped -- including the headings the spec dictated.
     */
    static AgentContext theRun() {
        var ctx = new AgentContext("u1", "t-audit", "Audit the routers and save the report.");
        ctx.trajectory().record(new AgentAction("plan_report", Map.of("spec", SPEC), "planning the report"),
                AgentObservation.success("plan_report", "noted", Map.of(), 5));

        var audit = ctx.addArtifact("router_audit", Map.of(), Map.of(), REPORT, true,
                Artifact.labelFor(List.of("ROUTER_USER", "ROUTER_PASS"), List.of()));
        ctx.trajectory().record(new AgentAction("router_audit", Map.of(), "auditing"),
                Artifact.asObservation(audit, ToolResult.success(REPORT), 10));

        Map<String, Object> written = new LinkedHashMap<>();
        written.put("path", "/srv/reports/audit.md");
        written.put("content", "{{1}}");
        Map<String, Object> resolved = new LinkedHashMap<>(written);
        resolved.put("content", REPORT);
        String preview = writerResult(resolved);
        var write = ctx.addArtifact("write_text_file_verbatim", written, resolved, preview, true,
                Artifact.labelFor(List.of(), List.of(audit)));
        ctx.trajectory().record(new AgentAction("write_text_file_verbatim", written, "saving it"),
                Artifact.asObservation(write, ToolResult.success(preview), 10));
        assertTrue(audit.isPrivate() && write.isPrivate(), "both results are private");
        return ctx;
    }

    static List<LlmMessage> render(AgentContext ctx) {
        var registry = new ToolRegistry(List.of());
        var engine = new ThinkingEngine(registry, new ToolSelector(registry), new OwnClawConfig(), null);
        return engine.buildMessages(ctx, "anthropic", new ThinkingEngine.StepMode(true, false));
    }

    static LlmMessage firstAssistant(List<LlmMessage> messages) {
        return messages.stream().filter(m -> m.role() == LlmMessage.Role.ASSISTANT).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("the cloud's own spec, replayed as JSON, is sent although a private result repeats it escaped")
    void theReplayedSpecIsSent() {
        var ctx = theRun();
        var messages = render(ctx);

        // The premise, so this cannot pass by accident: the replay really does hold a run of the
        // private preview, and nothing but the assistant-part rule lets it through.
        String replay = firstAssistant(messages).content();
        PrivateIndex.Hit hit = ctx.privateIndex().firstHitIn(replay);
        assertNotNull(hit, "the replayed spec carries a run of the private preview");
        assertEquals(2, hit.handle(), "the preview, which holds the headings escaped as JSON");
        String window = PrivateIndex.normalise(replay).substring(hit.offset(), hit.offset() + hit.length());
        assertFalse(ctx.isAllowedLeak(hit.handle(), window),
                "the allowance compares the argument as typed, so it cannot excuse the escaped replay");

        var rows = new ArrayList<EgressLedger.Row>();
        var cloud = new Scripted(List.of(call("respond", Map.of("message", "done"))));
        assertDoesNotThrow(() -> gateway(cloud, rows).chat(messages,
                LlmRequestConfig.DEFAULT.withEgress(ctx.egress("think"))));
        assertEquals(EgressLedger.Decision.SENT, rows.get(0).decision());
        // Mutation: scan assistant parts again -> EgressRefused on {{2}} in part 2 (assistant).
    }

    @Test
    @DisplayName("the same bytes in a user part are still refused")
    void theSameBytesInAUserPartAreRefused() {
        var ctx = theRun();
        String replay = firstAssistant(render(ctx)).content();
        var asUser = List.of(LlmMessage.system("S"), LlmMessage.user(replay));

        var rows = new ArrayList<EgressLedger.Row>();
        var cloud = new Scripted(List.of(call("respond", Map.of("message", "done"))));
        var refused = assertThrows(EgressRefused.class, () -> gateway(cloud, rows).chat(asUser,
                LlmRequestConfig.DEFAULT.withEgress(ctx.egress("think"))));
        assertEquals(2, refused.handle());
        assertEquals(LlmMessage.Role.USER, asUser.get(refused.partIndex()).role());
        assertTrue(cloud.requests.isEmpty(), "nothing was sent");
        assertEquals(EgressLedger.Decision.REFUSED, rows.get(0).decision());
    }

    @Test
    @DisplayName("a vault value the cloud typed is still scrubbed from its replayed turn")
    void theVaultScrubCoversAssistantParts() {
        String secret = "Vault-Secret-9f3k2Qm";
        var ctx = theRun();
        ctx.setSecretValues(Map.of("ROUTER_PASS", secret));
        ctx.trajectory().record(new AgentAction("router_login", Map.of("user", "admin", "password", secret),
                        "logging in"), AgentObservation.success("router_login", "ok", Map.of(), 5));
        var messages = render(ctx);
        assertTrue(messages.stream().anyMatch(m -> m.role() == LlmMessage.Role.ASSISTANT
                && m.content().contains(secret)), "the premise: the replay carries the value");

        var rows = new ArrayList<EgressLedger.Row>();
        var cloud = new Scripted(List.of(call("respond", Map.of("message", "done"))));
        gateway(cloud, rows).chat(messages, LlmRequestConfig.DEFAULT.withEgress(ctx.egress("think")));

        var sent = cloud.requests.get(0);
        assertTrue(sent.stream().noneMatch(m -> m.content().contains(secret)), "the value never left");
        assertTrue(sent.stream().anyMatch(m -> m.role() == LlmMessage.Role.ASSISTANT
                && m.content().contains("«vault:ROUTER_PASS»")));
        assertTrue(rows.get(0).scrubs() >= 1);
    }

    @Test
    @DisplayName("a replayed turn holds only what the model wrote: a reference as written, never what it resolves to")
    void aReplayHoldsOnlyTheModelsWords() throws Exception {
        var ctx = theRun();
        var messages = render(ctx);
        StringBuilder own = new StringBuilder();
        for (var turn : ctx.trajectory().turns()) {
            own.append(PrivateIndex.normalise(JSON.writeValueAsString(turn.action().params()))).append('\n');
            own.append(PrivateIndex.normalise(turn.action().reasoning())).append('\n');
        }
        boolean referenceAsWritten = false;
        for (var m : messages) {
            if (m.role() != LlmMessage.Role.ASSISTANT) continue;
            String part = PrivateIndex.normalise(m.content());
            if (part.contains("\"content\":\"{{1}}\"")) referenceAsWritten = true;
            for (String secret : List.of(REPORT, writerResultOf(ctx))) {
                String s = PrivateIndex.normalise(secret);
                for (int i = 0; i + PrivateIndex.WINDOW <= s.length(); i++) {
                    String w = s.substring(i, i + PrivateIndex.WINDOW);
                    assertFalse(part.contains(w) && !own.toString().contains(w),
                            "an assistant part carries private text the model never wrote: [" + w + "]");
                }
            }
        }
        assertTrue(referenceAsWritten, "the write is replayed with {{1}} as the model wrote it");
    }

    private static String writerResultOf(AgentContext ctx) {
        return ctx.lastArtifact().orElseThrow().output();
    }

    // ── the whole loop ──

    static AgentLoop loop(JdbcTemplate jdbc, ToolRegistry registry, CloudGateway gateway, OwnClawConfig config) {
        var emitter = new ChatStatusEmitter();
        var events = new EventLogService(jdbc);
        var router = new LlmRouter(new StopWithoutLocalModelTest.Down(), gateway, config, null);
        var engine = new ThinkingEngine(registry, new ToolSelector(registry), config, router);
        return new AgentLoop(engine, new CriticAgent(registry), registry, emitter, config, router,
                null, new SkillCuratorService(jdbc, null, null, null), null,
                new DebugSessionService(), new TaskCancellationService(), null, null,
                new LongRunningTaskManager(jdbc, emitter, events, config), null,
                new TokenBudgetTracker(jdbc, config, emitter), events, null, null, null);
    }

    @Test
    @DisplayName("through the real loop: the task completes, every call is sent, and the loop replays what was written")
    void theRealLoopRecordsWhatTheModelWrote(@TempDir Path tmp) throws Exception {
        var jdbc = MigratedDatabase.at(tmp.resolve("t.db"));
        var config = new OwnClawConfig();
        config.getMentor().setProvider("anthropic");
        var registry = new ToolRegistry(List.of(
                tool("plan_report", List.of(), p -> "noted"),
                tool("router_audit", List.of("ROUTER_USER", "ROUTER_PASS"), p -> REPORT),
                tool("write_text_file_verbatim", List.of(), AssistantPartsTest::writerResult)));
        Map<String, Object> write = new LinkedHashMap<>();
        write.put("path", "/srv/reports/audit.md");
        write.put("content", "{{2}}");
        var cloud = new Scripted(List.of(
                call("plan_report", Map.of("spec", SPEC)),
                call("router_audit", Map.of()),
                call("write_text_file_verbatim", write),
                call("respond", Map.of("message", "{{2}}"))));
        var rows = new ArrayList<EgressLedger.Row>();

        var ctx = new AgentContext("u1", "t-loop", "Audit the routers and save the report.");
        AgentResult r = loop(jdbc, registry, gateway(cloud, rows), config).executeWithContext(ctx);

        assertEquals(AgentResult.TerminationReason.COMPLETED, r.terminationReason(),
                "the step after the writer was refused on the cloud's own spec: " + r.response());
        assertTrue(r.ownerText() != null && r.ownerText().contains("guest-net"), "the owner got the report");
        assertTrue(rows.stream().allMatch(x -> x.decision() == EgressLedger.Decision.SENT));

        // What the cloud was actually sent last: an assistant part carries the audit's text only
        // where the cloud itself wrote it, and carries the reference as written.
        var last = cloud.requests.get(cloud.requests.size() - 1);
        String own = PrivateIndex.normalise(JSON.writeValueAsString(SPEC));
        String audit = PrivateIndex.normalise(REPORT);
        boolean referenceAsWritten = false;
        for (var m : last) {
            if (m.role() != LlmMessage.Role.ASSISTANT) continue;
            String part = PrivateIndex.normalise(m.content());
            if (part.contains("{{2}}")) referenceAsWritten = true;
            for (int i = 0; i + PrivateIndex.WINDOW <= audit.length(); i++) {
                String w = audit.substring(i, i + PrivateIndex.WINDOW);
                assertFalse(part.contains(w) && !own.contains(w),
                        "an assistant part carries audit text the cloud never wrote: [" + w + "]");
            }
        }
        assertTrue(referenceAsWritten, "the writer's call is replayed with {{2}} as the cloud wrote it");
        // Mutation: record the resolved arguments instead of the written ones -> the window check.
    }
}
