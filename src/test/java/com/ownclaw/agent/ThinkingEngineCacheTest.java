package com.ownclaw.agent;

import com.ownclaw.agent.tools.ToolRegistry;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.llm.LlmMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The conversation a task sends only grows: what the cloud cached on one step is the same bytes
 * on the next, so the cache can hold it.
 */
class ThinkingEngineCacheTest {

    @Test
    @DisplayName("step 0 marks where the task ends; on step 1 the task is the whole first message, unchanged")
    void theTaskIsStableAcrossSteps() {
        var registry = new ToolRegistry(List.of());
        var engine = new ThinkingEngine(registry, new OwnClawConfig(), null);
        var ctx = new AgentContext("u1", "t1", "summarise the article we discussed");
        ctx.setConversationSummary("### Recent conversation\nUSER: here is the article\nASSISTANT: noted");
        var mode = new ThinkingEngine.StepMode(true, false);

        String first0 = engine.buildMessages(ctx, "anthropic", mode).get(1).content();
        int cut = first0.indexOf(ThinkingEngine.CACHE_BOUNDARY_MARKER);
        assertTrue(cut > 0, "the boundary is there: " + first0);
        assertTrue(first0.substring(cut).contains("## Environment"), "the step's context comes after it");

        ctx.trajectory().record(new AgentAction("delegate", Map.of("goal", "read it"), ""),
                AgentObservation.success("delegate", "done", Map.of(), 5));
        List<LlmMessage> step1 = engine.buildMessages(ctx, "anthropic", mode);
        assertEquals(first0.substring(0, cut), step1.get(1).content(),
                "the cached prefix of step 0 is exactly step 1's first message");
        assertFalse(step1.get(1).content().contains("<!-- CACHE_BOUNDARY -->"));
    }

    @Test
    @DisplayName("the engine's step-0 message, as Anthropic's provider sends it: two blocks, the task cached, today's bytes")
    void theProviderSplitsWhatTheEngineMarks() throws Exception {
        var registry = new ToolRegistry(List.of());
        var engine = new ThinkingEngine(registry, new OwnClawConfig(), null);
        var ctx = new AgentContext("u1", "t1", "what is the weather in Brno");
        var messages = engine.buildMessages(ctx, "anthropic", new ThinkingEngine.StepMode(true, false));
        // The provider is package-private to com.ownclaw.llm; this is the one test that needs both.
        Class<?> anthropic = Class.forName("com.ownclaw.llm.AnthropicProvider");
        var ctor = anthropic.getDeclaredConstructor(OwnClawConfig.class,
                com.fasterxml.jackson.databind.ObjectMapper.class);
        ctor.setAccessible(true);
        var provider = ctor.newInstance(new OwnClawConfig(), new com.fasterxml.jackson.databind.ObjectMapper());
        var requestBody = anthropic.getDeclaredMethod("requestBody",
                List.class, com.ownclaw.llm.LlmRequestConfig.class, String.class, int.class);
        requestBody.setAccessible(true);
        var body = (com.fasterxml.jackson.databind.JsonNode) requestBody.invoke(provider, messages,
                new com.ownclaw.llm.LlmRequestConfig(null, null, false), "claude-opus-5", 128_000);
        var first = body.path("messages").get(0).path("content");
        assertTrue(first.isArray(), "split into two blocks: " + first);
        String joined = first.get(0).path("text").asText() + first.get(1).path("text").asText();
        assertEquals(messages.get(1).content().replace(ThinkingEngine.CACHE_BOUNDARY_MARKER, ""), joined,
                "the model reads today's bytes: the task, a blank line, then the context");
        assertTrue(joined.contains("what is the weather in Brno\n\n---\n"));
    }

    /** A message as the provider sends it: the marker is where it splits the text, not text. */
    private static String sent(LlmMessage m) {
        return m.content().replace(ThinkingEngine.CACHE_BOUNDARY_MARKER, "");
    }

    /** The newest message without what changes on every step (the date, the vault, the tools). */
    private static String withoutTheStepsContext(LlmMessage m) {
        String s = sent(m);
        return s.substring(0, s.lastIndexOf("\n\n---\n## Environment"));
    }

    @Test
    @DisplayName("through every kind of step, every message but the newest is the same bytes on the next step")
    void theConversationOnlyGrows() {
        var registry = new ToolRegistry(List.of());
        var engine = new ThinkingEngine(registry, new OwnClawConfig(), null);
        var mode = new ThinkingEngine.StepMode(true, false);
        var ctx = new AgentContext("u1", "t1", "audit the routers and write the report");
        var t = ctx.trajectory();
        String told = "Your previous reply was empty (stop reason: end_turn): no text and no tool "
                + "call, so nothing was run.\n\nContinue from where the task stands.";
        // Every kind of step the loop records, in an order that crosses each boundary: a reply
        // that could not be used before any action, a result far larger than any old ceiling,
        // another unusable reply, a reflection, and a reply quoted back to the model.
        List<Runnable> steps = List.of(
                () -> t.record(new AgentAction(ThinkingEngine.THINKING, Map.of(), told),
                        AgentObservation.failure(ThinkingEngine.THINKING, told, 0)),
                () -> t.record(new AgentAction("router_audit", Map.of(), "auditing"),
                        AgentObservation.success("router_audit", "config line\n".repeat(20_000), Map.of(), 900)),
                () -> t.record(new AgentAction(ThinkingEngine.THINKING, Map.of(), told),
                        AgentObservation.failure(ThinkingEngine.THINKING, told, 0)),
                () -> t.record(new AgentAction("write_report", Map.of("path", "/srv/r.md"), "saving"),
                        AgentObservation.failure("write_report", "disk full", 20)),
                () -> t.record(new AgentAction("_reflection", Map.of(), "System-injected reflection"),
                        AgentObservation.failure("_reflection", "REFLECT: try another path", 0)),
                () -> t.record(new AgentAction(ThinkingEngine.THINKING, Map.of("message", "I will retry."),
                                "not an action. It was:\n\nI will retry."),
                        AgentObservation.failure(ThinkingEngine.THINKING, "not an action. It was:\n\nI will retry.", 0)),
                () -> t.record(new AgentAction("write_report", Map.of("path", "/tmp/r.md"), "elsewhere"),
                        AgentObservation.success("write_report", "written", Map.of(), 20)));

        List<LlmMessage> before = engine.buildMessages(ctx, "anthropic", mode);
        for (int k = 0; k < steps.size(); k++) {
            steps.get(k).run();
            List<LlmMessage> after = engine.buildMessages(ctx, "anthropic", mode);
            int newest = before.size() - 1;
            assertTrue(after.size() >= before.size(), "step " + k + " took messages away");
            for (int i = 0; i < newest; i++) {
                assertEquals(sent(before.get(i)), sent(after.get(i)),
                        "step " + k + " rewrote message " + i + ", which the cache already holds");
            }
            assertTrue(sent(after.get(newest)).startsWith(withoutTheStepsContext(before.get(newest))),
                    "step " + k + " rewrote the newest message instead of adding to it");
            before = after;
        }
    }
}
