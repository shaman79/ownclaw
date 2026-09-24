package com.ownclaw.agent;

import com.ownclaw.agent.tools.ToolRegistry;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.llm.LlmMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The first message of a task is the same bytes on every step, so the cloud's cache can hold it. */
class ThinkingEngineCacheTest {

    @Test
    @DisplayName("step 0 marks where the task ends; on step 1 the task is the whole first message, unchanged")
    void theTaskIsStableAcrossSteps() {
        var registry = new ToolRegistry(List.of());
        var engine = new ThinkingEngine(registry, new ToolSelector(registry), new OwnClawConfig(), null);
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
        var engine = new ThinkingEngine(registry, new ToolSelector(registry), new OwnClawConfig(), null);
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
                com.ownclaw.llm.LlmRequestConfig.DEFAULT, "claude-opus-5", 1024);
        var first = body.path("messages").get(0).path("content");
        assertTrue(first.isArray(), "split into two blocks: " + first);
        String joined = first.get(0).path("text").asText() + first.get(1).path("text").asText();
        assertEquals(messages.get(1).content().replace(ThinkingEngine.CACHE_BOUNDARY_MARKER, ""), joined,
                "the model reads today's bytes: the task, a blank line, then the context");
        assertTrue(joined.contains("what is the weather in Brno\n\n---\n"));
    }
}
