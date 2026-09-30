package com.ownclaw.agent;

import com.ownclaw.agent.tools.ToolRegistry;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.llm.LlmMessage;
import com.ownclaw.llm.LlmProvider;
import com.ownclaw.llm.LlmRequestConfig;
import com.ownclaw.llm.LlmResponse;
import com.ownclaw.llm.Replies;
import com.ownclaw.llm.ToolCall;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The gate that decides whether the cloud may execute — each leg, and the valve.
 * <p>
 * The previous test never touched this decision: it called {@code ToolSchemas.build} directly,
 * so the gate itself, the valve and the prompt were all unexercised while the feature was
 * declared implemented. Every leg here flips the outcome on its own, because a gate whose legs
 * are never tested individually is a gate that quietly loses one.
 */
class StepModeGateTest {

    /** A cloud provider that does or does not take native tools. */
    private static LlmProvider provider(boolean tools) {
        return new LlmProvider() {
            public LlmResponse chat(List<LlmMessage> m, LlmRequestConfig c) {
                throw new UnsupportedOperationException("not called");
            }
            public boolean isAvailable() { return true; }
            public boolean supportsTools() { return tools; }
            public String name() { return "anthropic"; }
        };
    }

    private static ThinkingEngine engine(OwnClawConfig config) {
        ToolRegistry registry = new ToolRegistry(List.of());
        // llmRouter is null: every test pre-answers the local health question on the context,
        // which is the whole point of deciding it once per task rather than per step.
        return new ThinkingEngine(registry, config, null);
    }

    private static OwnClawConfig config(boolean nativeTools, boolean localFirst) {
        var c = new OwnClawConfig();
        c.getMentor().setNativeTools(nativeTools);
        c.getMentor().setLocalFirstUnattended(localFirst);
        return c;
    }

    private static AgentContext context(boolean unattended, boolean localReady) {
        var ctx = new AgentContext("u1", "t1", "Send the morning digest.");
        ctx.setUnattended(unattended);
        ctx.setLocalTierReady(localReady);
        return ctx;
    }

    /** The configuration in which the registry IS withheld. Each test breaks exactly one leg. */
    private static ThinkingEngine.StepMode mode(boolean nativeToolsCfg, boolean localFirstCfg,
                                                boolean providerTools, boolean unattended,
                                                boolean localReady) {
        return engine(config(nativeToolsCfg, localFirstCfg))
                .stepMode(context(unattended, localReady), provider(providerTools));
    }

    @Test
    @DisplayName("all four legs true: the registry is withheld")
    void theRestrictionApplies() {
        var m = mode(true, true, true, true, true);
        assertTrue(m.nativeTools());
        assertTrue(m.localFirst());
    }

    @Test
    @DisplayName("attended work is never restricted")
    void attendedIsExempt() {
        assertFalse(mode(true, true, true, false, true).localFirst(),
                "the user is waiting; a local step costs about a minute");
    }

    @Test
    @DisplayName("a local tier that cannot take the work leaves the registry alone")
    void localTierDownIsExempt() {
        assertFalse(mode(true, true, true, true, false).localFirst(),
                "a local tier that is not answering must never become a reason for scheduled "
                        + "work to stop");
    }

    @Test
    @DisplayName("the flag off changes nothing")
    void flagOffIsExempt() {
        assertFalse(mode(true, false, true, true, true).localFirst());
    }

    @Test
    @DisplayName("without native tools the restriction would not restrict anything")
    void textProtocolIsExempt() {
        var m = mode(true, true, false, true, true);
        assertFalse(m.nativeTools(), "the provider does not take a tools array");
        assertFalse(m.localFirst(),
                "on the text protocol the prose manifest IS the channel, so withholding tools "
                        + "from an array nobody reads restricts nothing");
    }

    @Test
    @DisplayName("native tools off globally also disables the restriction")
    void nativeToolsOffIsExempt() {
        assertFalse(mode(false, true, true, true, true).localFirst());
    }

    // ── the valve ──

    @Test
    @DisplayName("a failed delegation hands the registry back for the rest of the task")
    void failedDelegationOpensTheValve() {
        var ctx = context(true, true);
        ctx.trajectory().record(
                new AgentAction(AgentAction.DELEGATE, Map.of("goal", "send the digest"), ""),
                AgentObservation.failure(AgentAction.DELEGATE, "Delegation incomplete", 1000));

        var m = engine(config(true, true)).stepMode(ctx, provider(true));
        assertFalse(m.localFirst(),
                "the local tier has had its turn; the owner's morning email arriving matters "
                        + "more than the tokens it costs");
        assertTrue(m.nativeTools(), "which is not a reason to fall back to the text protocol");
    }

    @Test
    @DisplayName("a delegation that worked does not open the valve")
    void successfulDelegationKeepsTheRestriction() {
        var ctx = context(true, true);
        ctx.trajectory().record(
                new AgentAction(AgentAction.DELEGATE, Map.of("goal", "send the digest"), ""),
                AgentObservation.success(AgentAction.DELEGATE, "Digest sent.", Map.of(), 1000));

        assertTrue(engine(config(true, true)).stepMode(ctx, provider(true)).localFirst());
    }

    @Test
    @DisplayName("a failed skill call is not a failed delegation")
    void otherFailuresDoNotOpenTheValve() {
        var ctx = context(true, true);
        ctx.trajectory().record(
                new AgentAction("smtp_send_email", Map.of(), ""),
                AgentObservation.failure("smtp_send_email", "ERROR: auth", 10));

        assertTrue(engine(config(true, true)).stepMode(ctx, provider(true)).localFirst(),
                "the valve exists for a local tier that cannot manage the work, not for any "
                        + "failure anywhere in the task");
    }

    // ── a plan is not an answer, on EITHER channel ──

    /** A provider that answers with whatever the test hands it. */
    private static LlmProvider answering(boolean tools, LlmResponse canned) {
        return new LlmProvider() {
            public LlmResponse chat(List<LlmMessage> m, LlmRequestConfig c) { return canned; }
            public boolean isAvailable() { return true; }
            public boolean supportsTools() { return tools; }
            public String name() { return "anthropic"; }
        };
    }

    private static LlmResponse respondCall(String message) {
        return Replies.of("", 10, 5, 0, 0, "tool_use",
                List.of(new ToolCall("t1", AgentAction.RESPOND, Map.of("message", message))));
    }

    @Test
    @DisplayName("a native respond call before any work produces nothing to run, not an answer")
    void nativeRespondBeforeAnyWorkIsRefused() {
        // The channel the prompt actually teaches: under native tools it says "For respond, put
        // the whole answer in the message argument". So a model that cannot run
        // daily_news_digest says so by CALLING respond — which used to be returned as the final
        // answer, ending the scheduled run COMPLETED with no email and nothing run.
        var engine = engine(config(true, true));
        var ctx = context(true, true);

        var result = engine.decideNextActionFull(ctx,
                answering(true, respondCall("I'll fetch today's news digest first.")));

        assertEquals(ThinkingEngine.THINKING, result.action().tool(),
                "AgentLoop keys its retry off this step; as a respond the task ends green");
        assertTrue(result.action().reasoning().contains("I'll fetch today's news digest first."),
                "the model is shown what it answered: " + result.action().reasoning());
        assertTrue(result.action().reasoning().contains("Call 'delegate'"), result.action().reasoning());
    }

    @Test
    @DisplayName("prose before any work produces nothing to run either")
    void proseBeforeAnyWorkIsRefused() {
        var result = engine(config(true, true)).decideNextActionFull(context(true, true),
                answering(true, Replies.of("I'll fetch today's news digest first.", 10, 5)));

        assertEquals(ThinkingEngine.THINKING, result.action().tool());
        assertEquals("I'll fetch today's news digest first.", result.action().params().get("message"));
    }

    @Test
    @DisplayName("a respond envelope written as text before any work produces nothing to run either")
    void aTextEnvelopeBeforeAnyWorkIsRefused() {
        // The third channel: a local model orchestrating in the cloud's place ignores the tools
        // array and writes the old JSON envelope as text. It was parsed and returned as the answer
        // before the guard was asked, so the run ended COMPLETED with nothing run.
        var result = engine(config(true, true)).decideNextActionFull(context(true, true),
                answering(true, Replies.of("{\"tool\": \"respond\", \"params\": {\"message\": "
                        + "\"I'll fetch today's news digest first.\"}}", 10, 5)));

        assertEquals(ThinkingEngine.THINKING, result.action().tool(),
                "as a respond the task ends green having done nothing");
        assertEquals("I'll fetch today's news digest first.", result.action().params().get("message"));
        assertTrue(result.action().reasoning().contains("Call 'delegate'"), result.action().reasoning());
    }

    @Test
    @DisplayName("a native respond call after real work is a real answer")
    void nativeRespondAfterWorkIsAnAnswer() {
        var engine = engine(config(true, true));
        var ctx = context(true, true);
        ctx.trajectory().record(
                new AgentAction(AgentAction.DELEGATE, Map.of("goal", "send it"), ""),
                AgentObservation.success(AgentAction.DELEGATE, "Digest sent.", Map.of(), 1000));

        var result = engine.decideNextActionFull(ctx, answering(true, respondCall("Sent.")));

        assertEquals(AgentAction.RESPOND, result.action().tool(),
                "it did the work and is reporting it; refusing that would loop forever");
    }

    @Test
    @DisplayName("attended work can always answer directly")
    void attendedRespondIsNeverRefused() {
        var engine = engine(config(true, true));
        var ctx = context(false, true);

        var result = engine.decideNextActionFull(ctx, answering(true, respondCall("Paris.")));

        assertEquals(AgentAction.RESPOND, result.action().tool(),
                "the user asked a question; answering it is the whole job");
    }

    @Test
    @DisplayName("the offered set is cleared when the step does not offer tools")
    void offeredToolsIsClearedOnTheTextPath() {
        var ctx = context(true, true);

        // A native step first: the restriction is applied and recorded.
        engine(config(true, true)).decideNextActionFull(ctx,
                answering(true, respondCall("...")));
        assertNotNull(ctx.offeredTools(), "the restriction was in force on that step");

        // Then native tools go off — the documented kill switch — and the step offers nothing.
        engine(config(false, true)).decideNextActionFull(ctx,
                answering(true, Replies.of("{\"tool\":\"respond\",\"params\":{}}", 1, 1)));

        assertNull(ctx.offeredTools(),
                "toolsFor is the only writer, so without an explicit clear the last restriction "
                        + "outlives it and AgentLoop refuses every registry tool for the rest of "
                        + "the task — a kill switch that leaves the thing it killed running");
    }

    // ── the think call carries the task to the door ──

    @Test
    @DisplayName("every think call is made on behalf of the task, or the gateway would refuse it")
    void thinkCallCarriesTheEgressContext() {
        var seen = new java.util.ArrayList<LlmRequestConfig>();
        LlmProvider recording = new LlmProvider() {
            public LlmResponse chat(List<LlmMessage> m, LlmRequestConfig c) {
                seen.add(c); return respondCall("Paris.");
            }
            public boolean isAvailable() { return true; }
            public boolean supportsTools() { return true; }
            public String name() { return "anthropic"; }
        };
        var ctx = context(false, true);
        engine(config(true, false)).decideNextActionFull(ctx, recording);

        assertEquals(1, seen.size());
        var e = seen.get(0).egress();
        assertNotNull(e, "unclassified means denied: a think call without a context is refused "
                + "at the door, so the call site must attach one");
        assertEquals("think", e.purpose());
        assertEquals("t1", e.taskId());
        assertEquals("u1", e.userId());
        assertSame(ctx.privateIndex(), e.index(), "the task's own index, not an empty one");
    }

    @Test
    @DisplayName("a step is priced as the model that wrote the reply, which a declined request can change")
    void theServedModelIsPriced() {
        // Anthropic's server-side fallback answers a declined request with another model, in the
        // same call; the provider reports which one wrote the reply.
        LlmProvider fallback = new LlmProvider() {
            public LlmResponse chat(List<LlmMessage> m, LlmRequestConfig c) {
                return new LlmResponse("",
                        List.of(new ToolCall("t1", AgentAction.RESPOND, Map.of("message", "Paris."))),
                        null, "tool_use", null, "claude-opus-4-8", 128_000, 1_000_000,
                        List.of(new LlmResponse.Usage("claude-opus-4-8", 10, 5, 0, 0)));
            }
            public boolean isAvailable() { return true; }
            public boolean supportsTools() { return true; }
            public String name() { return "anthropic"; }
            public String model() { return "claude-opus-5"; }
        };
        var result = engine(config(true, false)).decideNextActionFull(context(false, true), fallback);
        assertEquals("claude-opus-4-8", result.reply().model(),
                "the reply the loop prices, which names the model that wrote it");
    }

    // ── the health answer is settled once ──

    @Test
    @DisplayName("the local health question is asked once per task, not once per step")
    void healthIsDecidedOnce() {
        var ctx = context(true, true);
        var engine = engine(config(true, true));
        // llmRouter is null, so a second probe would throw. Ten steps, no exception.
        for (int i = 0; i < 10; i++) {
            assertTrue(engine.stepMode(ctx, provider(true)).localFirst());
        }
        assertEquals(Boolean.TRUE, ctx.localTierReady());
    }
}
