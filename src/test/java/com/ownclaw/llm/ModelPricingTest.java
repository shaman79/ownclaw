package com.ownclaw.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The published rates, per million tokens, as Anthropic's model reference states them. One
 * million tokens of a kind costs exactly that kind's rate, so each rate is read back directly.
 */
class ModelPricingTest {

    private static final int M = 1_000_000;

    private static double input(String model) { return ModelPricing.costUsd(model, M, 0, 0, 0); }
    private static double output(String model) { return ModelPricing.costUsd(model, 0, M, 0, 0); }
    private static double cacheWrite(String model) { return ModelPricing.costUsd(model, 0, 0, M, 0); }
    private static double cacheRead(String model) { return ModelPricing.costUsd(model, 0, 0, 0, M); }

    @Test
    @DisplayName("claude-opus-5 is $5 in and $25 out, with the usual cache prices")
    void opus5() {
        assertEquals(5.00, input("claude-opus-5"), 1e-9);
        assertEquals(25.00, output("claude-opus-5"), 1e-9);
        assertEquals(6.25, cacheWrite("claude-opus-5"), 1e-9, "writes at 1.25x");
        assertEquals(0.50, cacheRead("claude-opus-5"), 1e-9, "reads at 0.1x");
    }

    @Test
    @DisplayName("the model a cyber refusal falls back to, and the other current Claude models")
    void currentModels() {
        assertEquals(5.00, input("claude-opus-4-8"), 1e-9);
        assertEquals(25.00, output("claude-opus-4-8"), 1e-9);
        assertEquals(5.00, input("claude-opus-4-7"), 1e-9);
        assertEquals(5.00, input("claude-opus-4-6"), 1e-9);
        assertEquals(2.00, input("claude-sonnet-5"), 1e-9);
        assertEquals(10.00, output("claude-sonnet-5"), 1e-9);
        assertEquals(3.00, input("claude-sonnet-4-6"), 1e-9);
        assertEquals(15.00, output("claude-sonnet-4-6"), 1e-9);
        assertEquals(1.00, input("claude-haiku-4-5-20251001"), 1e-9, "a dated id resolves to its family");
        assertEquals(5.00, output("claude-haiku-4-5"), 1e-9);
        assertEquals(10.00, input("claude-fable-5"), 1e-9);
        assertEquals(1.00, cacheRead("claude-fable-5"), 1e-9);
    }

    @Test
    @DisplayName("a longer model id is not priced as its prefix, and its own cache-read rate holds")
    void longestPrefixWins() {
        assertEquals(4.00, input("claude-opus-5-5"), 1e-9, "not claude-opus-5's $5");
        assertEquals(20.00, output("claude-opus-5-5"), 1e-9);
        assertEquals(0.20, cacheRead("claude-opus-5-5"), 1e-9, "0.05x");
        assertEquals(5.00, cacheWrite("claude-opus-5-5"), 1e-9);
        assertEquals(10.00, input("claude-fable-5-1"), 1e-9);
        assertEquals(50.00, output("claude-fable-5-1"), 1e-9);
        assertEquals(0.25, cacheRead("claude-fable-5-1"), 1e-9, "0.025x");
        assertEquals(12.50, cacheWrite("claude-fable-5-1"), 1e-9);
    }

    @Test
    @DisplayName("Claude Mythos 5.1 and Mythos 5 cost what Fable does, with cache reads kept at 0.1x")
    void mythos() {
        for (String model : new String[] {"claude-mythos-5-1", "claude-mythos-5"}) {
            assertEquals(10.00, input(model), 1e-9, model);
            assertEquals(50.00, output(model), 1e-9, model);
            assertEquals(12.50, cacheWrite(model), 1e-9, model);
            assertEquals(1.00, cacheRead(model), 1e-9,
                    model + ": whether Mythos 5.1 shares Fable 5.1's 0.025x was open at launch");
        }
    }

    @Test
    @DisplayName("a reply billed for two attempts prices each at the rates of the model that ran it")
    void eachAttemptAtItsOwnModel() {
        var r = new LlmResponse("x", java.util.List.of(), null, "end_turn", null, "claude-opus-4-8", null, null,
                java.util.List.of(new LlmResponse.Usage("claude-fable-5-1", M, M, 0, 0),
                        new LlmResponse.Usage("claude-opus-4-8", M, M, 0, 0)));
        assertEquals((10.00 + 50.00) + (5.00 + 25.00), ModelPricing.costUsd("claude-opus-4-8", r), 1e-9,
                "the declined Fable attempt at Fable's rates, the Opus 4.8 answer at its own");
        var unnamed = Replies.of("x", M, 0, 0, 0, "end_turn");
        assertEquals(5.00, ModelPricing.costUsd("claude-opus-5", unnamed), 1e-9,
                "an attempt that names no model is priced at the model given");
    }

    @Test
    @DisplayName("an unknown Claude keeps the mid-tier fallback; an unknown model costs nothing and says so")
    void unknown() {
        assertEquals(3.00, input("claude-opus-4-1"), 1e-9);
        assertTrue(ModelPricing.isKnown("claude-opus-4-1"));
        assertEquals(0.0, ModelPricing.costUsd("local-model:q4", 1000, 1000, 0, 0), 1e-12);
        assertFalse(ModelPricing.isKnown("local-model:q4"));
        assertFalse(ModelPricing.isKnown(null));
    }

    @Test
    @DisplayName("a reply is priced from its own counters, each at its own rate")
    void fromAResponse() {
        var r = Replies.of("x", 1000, 200, 3000, 50_000, "end_turn");
        // 1000 x $5 + 200 x $25 + 3000 x $6.25 + 50,000 x $0.50, per million
        assertEquals(0.00500 + 0.00500 + 0.01875 + 0.02500, ModelPricing.costUsd("claude-opus-5", r), 1e-12);
    }
}
