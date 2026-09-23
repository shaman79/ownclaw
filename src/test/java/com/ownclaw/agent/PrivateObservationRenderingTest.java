package com.ownclaw.agent;

import com.ownclaw.agent.tools.Tool;
import com.ownclaw.agent.tools.ToolExecutionContext;
import com.ownclaw.agent.tools.ToolParam;
import com.ownclaw.agent.tools.ToolRegistry;
import com.ownclaw.agent.tools.ToolResult;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.llm.CloudGateway;
import com.ownclaw.llm.EgressLedger;
import com.ownclaw.llm.EgressRefused;
import com.ownclaw.llm.LlmMessage;
import com.ownclaw.llm.LlmProvider;
import com.ownclaw.llm.LlmRequestConfig;
import com.ownclaw.llm.LlmResponse;
import com.ownclaw.privacy.Label;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The end-to-end proof, with two independent assertions so neither mechanism can silently carry
 * the other.
 * <p>
 * A 5,000-character PRIVATE result is recorded on a task and enters the trajectory the way a
 * real step's would. Both prompt renderers run over it. Then every message passes through the
 * real gateway with the task's own index. The direct assertion is that no 32-character window
 * of the private text is in any message; the gateway's is that nothing is refused. If
 * asObservation ever let the bytes through, the gateway would refuse; if the index ever stopped
 * indexing, the direct assertion would fail. The control: the same bytes as PUBLIC are present
 * in both renderings — the label decides, not the renderer.
 */
class PrivateObservationRenderingTest {

    private static String prose(int chars, long seed) {
        var r = new Random(seed);
        var sb = new StringBuilder();
        String[] w = {"faktura", "Novák", "platba", "quarterly", "Bratčice", "ledger", "IBAN CZ65", "due"};
        while (sb.length() < chars) sb.append(w[r.nextInt(w.length)]).append(r.nextInt(99_999)).append(' ');
        return sb.substring(0, chars);
    }

    private static Tool skill(String name, List<String> credentials) {
        return new Tool() {
            public String name() { return name; }
            public String description() { return "test skill"; }
            public Map<String, ToolParam> inputSchema() { return Map.of(); }
            public List<String> requiredCredentials() { return credentials; }
            public ToolResult execute(Map<String, Object> p, ToolExecutionContext c) {
                return ToolResult.failure("not run");
            }
        };
    }

    private static ThinkingEngine engine(ToolRegistry registry) {
        return new ThinkingEngine(registry, new ToolSelector(registry), new OwnClawConfig(), null);
    }

    /** A task with one result recorded exactly as executeTool records it. */
    private static AgentContext taskWith(String tool, List<String> credentials, String output) {
        var ctx = new AgentContext("u1", "t1", "Summarise my mailbox and email me the result.");
        ctx.setUnattended(true);
        var decision = Artifact.labelFor(credentials, false, false, Map.of(), ctx.artifacts());
        var a = ctx.addArtifact(tool, Map.of(), Map.of(), output, true, decision);
        var obs = Artifact.asObservation(a, ToolResult.success(output, Map.of("k", "v")), 10);
        ctx.trajectory().record(new AgentAction(tool, Map.of(), "fetching"), obs);
        return ctx;
    }

    private static List<String> allText(ThinkingEngine engine, AgentContext ctx) {
        var out = new ArrayList<String>();
        for (String provider : List.of("anthropic", "openai")) {
            for (LlmMessage m : engine.buildMessages(ctx, provider, new ThinkingEngine.StepMode(true, false))) {
                out.add(m.content());
            }
        }
        out.add(ctx.trajectory().toPromptSummary());
        return out;
    }

    private static CloudGateway gateway(List<EgressLedger.Row> rows) {
        LlmProvider fake = new LlmProvider() {
            public LlmResponse chat(List<LlmMessage> m, LlmRequestConfig c) { return new LlmResponse("ok", 1, 1); }
            public boolean isAvailable() { return true; }
            public boolean supportsTools() { return true; }
            public String name() { return "anthropic"; }
        };
        var config = new OwnClawConfig();
        config.getMentor().setProvider("anthropic");
        return new CloudGateway(fake, fake, config, rows::add, null);
    }

    @Test
    @DisplayName("a PRIVATE result reaches neither renderer, and the gateway confirms it")
    void privateBytesReachNoPrompt() {
        String secret = prose(5_000, 11);
        var registry = new ToolRegistry(List.of(skill("imap_fetch", List.of("IMAP_PASS"))));
        var ctx = taskWith("imap_fetch", List.of("IMAP_PASS"), secret);
        var engine = engine(registry);

        List<String> texts = allText(engine, ctx);

        // Direct: not one 32-char window of the private text, in any rendering.
        for (String t : texts) {
            for (int i = 0; i + 32 <= secret.length(); i += 61) {
                assertFalse(t.contains(secret.substring(i, i + 32)),
                        "a window of the private text at " + i + " is in a prompt");
            }
        }
        assertTrue(texts.stream().anyMatch(t -> t.contains("$1 imap_fetch")),
                "and the descriptor IS there, so the cloud knows the result exists");
        assertTrue(texts.stream().anyMatch(t -> t.contains("PRIVATE (credentials: IMAP_PASS)")));

        // Independent: the real door, with the task's own index, sends every message.
        var rows = new ArrayList<EgressLedger.Row>();
        var gw = gateway(rows);
        for (String provider : List.of("anthropic", "openai")) {
            var messages = engine.buildMessages(ctx, provider, new ThinkingEngine.StepMode(true, false));
            assertDoesNotThrow(() -> gw.chat(messages, LlmRequestConfig.DEFAULT.withEgress(ctx.egress("think"))),
                    provider + ": the gateway found private bytes the renderer test did not");
        }
        assertTrue(rows.stream().allMatch(r -> r.decision() == EgressLedger.Decision.SENT));
        // Mutations: asObservation returns r.output() for PRIVATE -> the gateway refuses;
        // addArtifact skips indexing -> the direct assertion fails instead.
    }

    @Test
    @DisplayName("control: the same bytes as PUBLIC are in both renderings — the label decides")
    void publicBytesArePresent() {
        String text = prose(5_000, 12);
        var registry = new ToolRegistry(List.of(skill("daily_news_digest", List.of())));
        var ctx = taskWith("daily_news_digest", List.of(), text);

        List<String> texts = allText(engine(registry), ctx);
        assertTrue(texts.stream().anyMatch(t -> t.contains(text.substring(100, 400))),
                "public content goes to the cloud exactly as today");
        var rows = new ArrayList<EgressLedger.Row>();
        var gw = gateway(rows);
        var messages = engine(registry).buildMessages(ctx, "anthropic", new ThinkingEngine.StepMode(true, false));
        assertDoesNotThrow(() -> gw.chat(messages, LlmRequestConfig.DEFAULT.withEgress(ctx.egress("think"))));
    }

    @Test
    @DisplayName("if a renderer ever leaked the bytes, the door would refuse the call")
    void theDoorIsIndependentOfTheRenderer() {
        String secret = prose(5_000, 13);
        var ctx = taskWith("imap_fetch", List.of("IMAP_PASS"), secret);
        var gw = gateway(new ArrayList<>());
        // A message that a wrong renderer might build: the private bytes, verbatim.
        var leaking = List.of(LlmMessage.system("S"), LlmMessage.user("Mailbox: " + secret.substring(500, 900)));

        var ex = assertThrows(EgressRefused.class,
                () -> gw.chat(leaking, LlmRequestConfig.DEFAULT.withEgress(ctx.egress("think"))));
        assertEquals(1, ex.handle(), "$1 is the artifact whose bytes were found");
    }
}
