package com.ownclaw.agent;

import com.ownclaw.agent.tools.ToolRegistry;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.llm.LlmMessage;
import com.ownclaw.llm.LlmProvider;
import com.ownclaw.llm.LlmRequestConfig;
import com.ownclaw.llm.LlmResponse;
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
        return new ThinkingEngine(registry, new ToolSelector(registry), config, null);
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
