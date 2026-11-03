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
 * Every result the cloud reasons over reaches it whole, on every step, on both renderers.
 * <p>
 * It used to be cut three ways. A result over 12,000 characters was sent as its first and last
 * 6,000 -- the 29 September audit, 175 KB, reached the cloud as 12 KB, and the cloud reasoned
 * over a result with a hole in it. Past a 60,000-character budget, counted newest first, every
 * older result became 150 characters of head and 150 of tail, and older reasoning 200. And the
 * OpenAI summary did the same with its own numbers. The only bound left is the model's context
 * window, which the provider reports when a prompt exceeds it.
 */
class ObservationRetentionTest {

    private static final ThinkingEngine.StepMode NATIVE = new ThinkingEngine.StepMode(true, false, false);

    private static ThinkingEngine engine() {
        var registry = new ToolRegistry(List.of());
        return new ThinkingEngine(registry, new OwnClawConfig(), null);
    }

    /** A result of about {@code chars} characters that no other result shares a run with. */
    private static String page(String name, int chars) {
        var sb = new StringBuilder(name).append("-BEGIN ");
        for (int i = 0; sb.length() < chars; i++) sb.append(name).append(" line ").append(i).append('\n');
        return sb.append(name).append("-END").toString();
    }

    private static AgentContext taskWith(String... outputs) {
        var ctx = new AgentContext("u1", "t1", "Read these and compare them.");
        for (int i = 0; i < outputs.length; i++) {
            ctx.trajectory().record(new AgentAction("fetch_page", Map.of("n", i), "reading page " + i),
                    AgentObservation.success("fetch_page", outputs[i], Map.of(), 10));
        }
        return ctx;
    }

    private static String anthropic(AgentContext ctx) {
        return engine().buildMessages(ctx, "anthropic", NATIVE).stream()
                .map(LlmMessage::content).reduce("", (a, b) -> a + "\n" + b);
    }

    private static String openai(AgentContext ctx) {
        return engine().buildMessages(ctx, "openai", NATIVE).stream()
                .map(LlmMessage::content).reduce("", (a, b) -> a + "\n" + b);
    }

    @Test
    @DisplayName("a 175 KB result reaches the cloud whole, on both renderers")
    void aLargeResultIsWhole() {
        String audit = page("AUDIT", 175_000);
        var ctx = taskWith(audit);

        for (String prompt : List.of(anthropic(ctx), openai(ctx))) {
            assertTrue(prompt.contains(audit), "the whole result, not its head and tail");
            assertFalse(prompt.contains("middle omitted"));
        }
    }

    @Test
    @DisplayName("older results stay whole however many steps follow and however large they are")
    void olderResultsStayWhole() {
        String first = page("FIRST", 50_000), second = page("SECOND", 50_000),
                third = page("THIRD", 50_000), last = page("LAST", 500);
        var ctx = taskWith(first, second, third, last);

        for (String prompt : List.of(anthropic(ctx), openai(ctx))) {
            for (String p : List.of(first, second, third, last)) {
                assertTrue(prompt.contains(p), "an older result became a stub");
            }
        }
    }

    @Test
    @DisplayName("the model's own older reasoning is replayed whole")
    void olderReasoningIsWhole() {
        String reasoning = "Comparing the two pages first, because ".repeat(20);   // ~800 chars
        var ctx = new AgentContext("u1", "t1", "Compare them.");
        ctx.trajectory().record(new AgentAction("fetch_page", Map.of("n", 1), reasoning),
                AgentObservation.success("fetch_page", page("A", 70_000), Map.of(), 10));
        ctx.trajectory().record(new AgentAction("fetch_page", Map.of("n", 2), "second"),
                AgentObservation.success("fetch_page", page("B", 70_000), Map.of(), 10));

        assertTrue(openai(ctx).contains("{\"reasoning\":\"" + reasoning.strip()));
        String replay = engine().buildMessages(ctx, "anthropic", NATIVE).stream()
                .filter(m -> m.role() == LlmMessage.Role.ASSISTANT).findFirst().orElseThrow().content();
        assertTrue(replay.contains(reasoning.strip()), "the replayed turn carries it whole: " + replay);
    }
}
