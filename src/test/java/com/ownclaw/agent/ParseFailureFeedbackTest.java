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
 * What the model is shown of a step that produced nothing to run: every one, in its place, with
 * nothing invented for it.
 * <p>
 * The Anthropic renderer used to keep only the latest such step, reduce the others to a count, and
 * move the latest to the end of the history -- so a correction the model had already acted on was
 * the newest thing it read for the rest of the task. It replayed a sentence the parser invented
 * ("Empty response from reasoning engine.") as the model's own turn, and the OpenAI summary
 * collapsed older ones into "N thinking failures (JSON parse errors) — skipped".
 */
class ParseFailureFeedbackTest {

    private static final ThinkingEngine.StepMode TEXT = new ThinkingEngine.StepMode(false, false);

    private static ThinkingEngine engine() {
        var registry = new ToolRegistry(List.of());
        return new ThinkingEngine(registry, new OwnClawConfig(), null);
    }

    /** A step that produced nothing to run, as AgentLoop records it. */
    private static void unusable(AgentContext ctx, String wrote, String told) {
        ctx.trajectory().record(new AgentAction(ThinkingEngine.THINKING,
                        wrote == null ? Map.of() : Map.of("message", wrote), told),
                AgentObservation.failure(ThinkingEngine.THINKING, told, 0));
    }

    private static void step(AgentContext ctx, String tool, String output) {
        ctx.trajectory().record(new AgentAction(tool, Map.of("q", tool), "running " + tool),
                AgentObservation.success(tool, output, Map.of(), 5));
    }

    private static List<LlmMessage> anthropic(AgentContext ctx) {
        return engine().buildMessages(ctx, "anthropic", TEXT);
    }

    private static String joined(List<LlmMessage> msgs) {
        StringBuilder sb = new StringBuilder();
        for (LlmMessage m : msgs) sb.append('[').append(m.role()).append("] ").append(m.content()).append('\n');
        return sb.toString();
    }

    @Test
    @DisplayName("every step that produced nothing is shown in its place, and no assistant turn is invented for it")
    void everyOneInItsPlace() {
        var ctx = new AgentContext("u1", "t1", "Check the router and tell me what you find.");
        step(ctx, "router_status", "STATUS-ONE");
        unusable(ctx, "The router looks fine to me.", "TOLD-ONE: not an action. It was:\n\nThe router looks fine to me.");
        step(ctx, "router_logs", "STATUS-TWO");
        unusable(ctx, null, "TOLD-TWO: your previous reply was empty");
        unusable(ctx, null, "TOLD-THREE: the call to the model failed");

        List<LlmMessage> messages = anthropic(ctx);
        String all = joined(messages);

        // Only the two actions the model took are assistant turns.
        var assistants = messages.stream().filter(m -> m.role() == LlmMessage.Role.ASSISTANT)
                .map(LlmMessage::content).toList();
        assertEquals(2, assistants.size(), all);
        assertTrue(assistants.get(0).contains("router_status") && assistants.get(1).contains("router_logs"), all);
        assertTrue(assistants.stream().noneMatch(a -> a.contains("looks fine") || a.contains("TOLD")),
                "what the model was told, and the reply it is told about, are not its turns:\n" + all);

        // Each correction where it happened, every one of them -- none counted away, none moved.
        int one = all.indexOf("STATUS-ONE"), toldOne = all.indexOf("TOLD-ONE"),
                two = all.indexOf("STATUS-TWO"), toldTwo = all.indexOf("TOLD-TWO"),
                toldThree = all.indexOf("TOLD-THREE");
        assertTrue(one < toldOne && toldOne < all.indexOf("router_logs") && two < toldTwo && toldTwo < toldThree,
                "in the order it happened:\n" + all);
        assertFalse(all.contains("earlier parse failure"), all);
    }

    @Test
    @DisplayName("the roles alternate, the last turn is the user's, and it carries the step's context")
    void rolesAlternate() {
        var ctx = new AgentContext("u1", "t1", "hello");
        unusable(ctx, null, "empty");
        step(ctx, "a", "x");
        unusable(ctx, "prose", "not an action");
        unusable(ctx, null, "empty again");

        var messages = anthropic(ctx);
        assertEquals(LlmMessage.Role.SYSTEM, messages.get(0).role());
        for (int i = 2; i < messages.size(); i++) {
            assertNotEquals(messages.get(i - 1).role(), messages.get(i).role(),
                    "two " + messages.get(i).role() + " turns in a row at " + i + ":\n" + joined(messages));
        }
        String last = messages.get(messages.size() - 1).content();
        assertEquals(LlmMessage.Role.USER, messages.get(messages.size() - 1).role());
        assertTrue(last.contains("empty again") && last.contains("## Environment"), last);
    }

    @Test
    @DisplayName("before any action, the task, what went wrong and the context are one message, the task cached")
    void beforeAnyActionTheTaskIsTheFirstBlock() {
        var ctx = new AgentContext("u1", "t1", "what is the capital of France");
        unusable(ctx, null, "Your previous reply was empty");

        var messages = anthropic(ctx);
        assertEquals(2, messages.size(), "system and one user message: " + joined(messages));
        String user = messages.get(1).content();
        int cut = user.indexOf(ThinkingEngine.CACHE_BOUNDARY_MARKER);
        assertTrue(cut > 0, user);
        assertTrue(user.substring(0, cut).endsWith("what is the capital of France"),
                "the cached block is the task, the same bytes as on the first call");
        assertTrue(user.indexOf("Your previous reply was empty") > cut);
    }

    @Test
    @DisplayName("a reflection the loop injected is what the model was told, not a turn of its own")
    void aReflectionIsNotTheModelsTurn() {
        var ctx = new AgentContext("u1", "t1", "fetch it");
        ctx.trajectory().record(new AgentAction("fetch", Map.of(), "fetching"),
                AgentObservation.failure("fetch", "timeout", 5));
        ctx.trajectory().record(new AgentAction("_reflection", Map.of(), "System-injected reflection"),
                AgentObservation.failure("_reflection", "REFLECT: 2x failed.", 0));

        String all = joined(anthropic(ctx));
        assertFalse(all.contains("System-injected reflection") || all.contains("\"_reflection\""),
                "the model never chose a _reflection step:\n" + all);
        assertTrue(all.contains("REFLECT: 2x failed."), all);
        assertTrue(all.indexOf("timeout") < all.indexOf("REFLECT"), all);
    }

    @Test
    @DisplayName("the history summary shows every one of them in its place, not a count")
    void theSummaryShowsEveryOne() {
        var t = new AgentTrajectory();
        for (int i = 1; i <= 4; i++) {
            String told = "TOLD-" + i;
            t.record(new AgentAction(ThinkingEngine.THINKING, Map.of(), told),
                    AgentObservation.failure(ThinkingEngine.THINKING, told, 0));
        }
        t.record(new AgentAction("fetch", Map.of(), "now fetching"),
                AgentObservation.success("fetch", "PAGE", Map.of(), 5));

        String summary = t.toPromptSummary();
        for (int i = 1; i <= 4; i++) {
            assertTrue(summary.contains("[Step " + i + "] TOLD-" + i), summary);
        }
        assertFalse(summary.contains("skipped") || summary.contains("thinking failures"), summary);
        assertTrue(summary.contains("[Step 5] Tool: fetch"), summary);
        assertFalse(summary.contains("Tool: " + ThinkingEngine.THINKING),
                "a step the loop recorded is what the model was told, not a tool it ran: " + summary);
    }
}
