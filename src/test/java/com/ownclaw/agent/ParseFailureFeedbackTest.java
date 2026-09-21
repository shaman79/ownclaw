package com.ownclaw.agent;

import com.ownclaw.agent.tools.ToolRegistry;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.llm.LlmMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Whether the model is told that its last answer could not be parsed.
 * <p>
 * It was not, on the provider this deployment actually runs. When Claude replies in prose instead
 * of the action JSON, the loop records the raw text and the required format as a {@code _thinking}
 * failure and retries — and {@code buildAnthropicMessages} discarded that turn as "noise without
 * useful info". No other part of the Anthropic prompt reads the trajectory, so the retry was a
 * byte-identical prompt, drew the identical reply, and the run aborted at three with
 * "3 consecutive reasoning failures". Four of those sit in this deployment's chat history, for
 * questions the model had answered correctly each time.
 */
class ParseFailureFeedbackTest {

    private static final String FEEDBACK =
            "PARSE ERROR. Your output:\nThe capital of France is Paris.\n\n"
                    + "Required format: {\"tool\": \"name\", \"params\": {...}, \"reasoning\": \"...\"}";

    private static ThinkingEngine engine() {
        ToolRegistry registry = new ToolRegistry(List.of());
        // llmRouter is only touched by local tool pre-selection, which is off by default.
        return new ThinkingEngine(registry, new ToolSelector(registry), new OwnClawConfig(), null);
    }

    /** A context whose trajectory holds one parse failure, exactly as AgentLoop records it. */
    private static AgentContext contextWithParseFailure(String rawModelText) {
        AgentContext ctx = new AgentContext("u1", "t1", "What is the capital of France?");
        AgentAction fallback = new AgentAction(AgentAction.RESPOND,
                Map.of("message", rawModelText),
                "LLM did not produce structured output; delivering raw response");
        ctx.trajectory().record(fallback, AgentObservation.failure("_thinking", FEEDBACK, 0));
        return ctx;
    }

    private static String joined(List<LlmMessage> msgs) {
        StringBuilder sb = new StringBuilder();
        for (LlmMessage m : msgs) sb.append('[').append(m.role()).append("] ").append(m.content()).append('\n');
        return sb.toString();
    }

    @Test
    @DisplayName("the correction actually reaches the model")
    void feedbackIsSent() {
        var messages = new ArrayList<LlmMessage>();
        engine().buildAnthropicMessages(messages, contextWithParseFailure("The capital of France is Paris."));
        String all = joined(messages);

        assertTrue(all.contains("PARSE ERROR"),
                "without this the retry is byte-identical to the prompt that just failed:\n" + all);
        assertTrue(all.contains("Required format"), "the model must be told what shape to produce");
    }

    @Test
    @DisplayName("the model is shown its real prose, not a fabricated valid action")
    void noFabricatedActionIsReplayed() {
        var messages = new ArrayList<LlmMessage>();
        engine().buildAnthropicMessages(messages, contextWithParseFailure("The capital of France is Paris."));
        String all = joined(messages);

        // The parser invents {"tool":"respond","params":{"message":"<prose>"}} to carry the text.
        // Replaying that as the model's own output, then calling it unparseable, would teach the
        // opposite of the lesson: that prose does become a valid respond action.
        assertFalse(all.contains("\"tool\":\"respond\"") || all.contains("\"tool\": \"respond\""),
                "the fabricated fallback action must never be replayed as the assistant turn:\n" + all);
        assertTrue(all.contains("The capital of France is Paris."),
                "the model should see the actual text it produced");
    }

    @Test
    @DisplayName("roles still alternate, as the Messages API requires")
    void rolesAlternate() {
        var messages = new ArrayList<LlmMessage>();
        engine().buildAnthropicMessages(messages, contextWithParseFailure("prose"));
        for (int i = 1; i < messages.size(); i++) {
            assertNotEquals(messages.get(i - 1).role(), messages.get(i).role(),
                    "two consecutive " + messages.get(i).role() + " messages at index " + i
                            + " — the Anthropic Messages API rejects that:\n" + joined(messages));
        }
        assertEquals(LlmMessage.Role.USER, messages.get(messages.size() - 1).role(),
                "the last message must be the user turn carrying the correction");
    }

    @Test
    @DisplayName("repeated failures are counted, so the model can see it is looping")
    void olderFailuresAreCounted() {
        AgentContext ctx = contextWithParseFailure("prose one");
        ctx.trajectory().record(
                new AgentAction(AgentAction.RESPOND, Map.of("message", "prose two"),
                        "Failed to parse structured output"),
                AgentObservation.failure("_thinking", FEEDBACK, 0));

        var messages = new ArrayList<LlmMessage>();
        engine().buildAnthropicMessages(messages, ctx);
        String all = joined(messages);

        assertTrue(all.contains("earlier parse failure"),
                "a model repeating itself should be told it is repeating itself:\n" + all);
        assertTrue(all.contains("prose two"), "the most recent attempt is the one to correct");
    }

    @Test
    @DisplayName("a clean first step is unchanged")
    void step0IsUntouched() {
        var messages = new ArrayList<LlmMessage>();
        AgentContext ctx = new AgentContext("u1", "t1", "hello");
        engine().buildAnthropicMessages(messages, ctx);

        assertEquals(1, messages.size(), "step 0 is a single user message");
        assertFalse(joined(messages).contains("PARSE ERROR"));
    }

    @Test
    @DisplayName("a successful turn followed by a parse failure keeps both")
    void mixedTrajectory() {
        AgentContext ctx = new AgentContext("u1", "t1", "do the thing");
        ctx.trajectory().record(
                new AgentAction("shell_exec", Map.of("cmd", "hostname"), "check the host"),
                AgentObservation.success("shell_exec", "prod-box", Map.of(), 12));
        ctx.trajectory().record(
                new AgentAction(AgentAction.RESPOND, Map.of("message", "some prose"),
                        "Fallback response"),
                AgentObservation.failure("_thinking", FEEDBACK, 0));

        var messages = new ArrayList<LlmMessage>();
        engine().buildAnthropicMessages(messages, ctx);
        String all = joined(messages);

        assertTrue(all.contains("prod-box"), "the real tool result must survive");
        assertTrue(all.contains("PARSE ERROR"), "and so must the correction");
    }
}
