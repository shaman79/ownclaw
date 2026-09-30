package com.ownclaw.llm;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a cloud call actually costs.
 * <p>
 * Every call site recorded {@code 0.0}, so {@code token_usage.cost_usd} was a column of zeros,
 * the budget ceilings in {@code application.yaml} guarded nothing, and no claim about token
 * savings — the first thing this system was built to deliver — could be checked against
 * anything. This is the instrument; it has to exist before any routing decision can be judged.
 * <p>
 * Three separate input rates, because prompt caching does not price as one number:
 * <ul>
 *   <li><b>input</b> — uncached prompt tokens, the base rate.</li>
 *   <li><b>cache write</b> — tokens written into the cache, about 1.25x base. Writing is a
 *       premium, which is why caching a prompt that is never re-read is a net loss.</li>
 *   <li><b>cache read</b> — tokens served from the cache, about 0.1x base (less on some models,
 *       below). This is where the saving lives, and it was invisible because the provider
 *       discarded the field.</li>
 * </ul>
 * Rates are USD per million tokens. They are published prices that change, so this is a
 * best-effort estimate rather than a bill: treat the number as a strong relative signal for
 * comparing routes, not as accounting. An unknown model returns 0 and logs nothing — a silent
 * zero is what produced the current situation, so callers should check {@link #isKnown}.
 */
public final class ModelPricing {

    /** USD per million tokens. */
    public record Rates(double input, double output, double cacheWrite, double cacheRead) {
        /** The usual cache prices: writes (5-minute) at 1.25x the input rate, reads at 0.1x. */
        public static Rates of(double input, double output) {
            return new Rates(input, output, input * 1.25, input * 0.10);
        }
    }

    // Prefix-matched, longest wins, so a dated variant like claude-haiku-4-5-20251001 resolves
    // to its family without an entry per release, and claude-opus-5-5 is not priced as
    // claude-opus-5. The Claude rates are Anthropic's published first-party prices; the Claude
    // models not listed here fall back to the unknown-Claude entry.
    private static final Map<String, Rates> RATES = new LinkedHashMap<>();
    static {
        // Cache reads at 0.025x the input rate; Claude Mythos 5.1 has the same per-token price,
        // and whether it shares that cache-read rate was open at launch, so it keeps 0.1x.
        RATES.put("claude-fable-5-1",   new Rates(10.00, 50.00, 12.50, 0.25));
        RATES.put("claude-mythos-5-1",  Rates.of(10.00, 50.00));
        RATES.put("claude-fable-5",     Rates.of(10.00, 50.00));
        RATES.put("claude-mythos-5",    Rates.of(10.00, 50.00));
        RATES.put("claude-opus-5-5",    new Rates(4.00, 20.00, 5.00, 0.20));   // reads at 0.05x
        RATES.put("claude-opus-5",      Rates.of(5.00, 25.00));
        RATES.put("claude-opus-4-8",    Rates.of(5.00, 25.00));   // what serves a cyber fallback
        RATES.put("claude-opus-4-7",    Rates.of(5.00, 25.00));
        RATES.put("claude-opus-4-6",    Rates.of(5.00, 25.00));
        RATES.put("claude-sonnet-5",    Rates.of(2.00, 10.00));
        RATES.put("claude-sonnet-4",    Rates.of(3.00, 15.00));
        RATES.put("claude-haiku-4",     Rates.of(1.00, 5.00));
        RATES.put("claude-",            Rates.of(3.00, 15.00));   // unknown Claude: assume mid
        RATES.put("gpt-5",              Rates.of(1.25, 10.00));
        RATES.put("gpt-4o-mini",        Rates.of(0.15, 0.60));
        RATES.put("gpt-4o",             Rates.of(2.50, 10.00));
        RATES.put("o3",                 Rates.of(2.00, 8.00));
    }

    private ModelPricing() {}

    /** Whether we have a rate for this model, as opposed to silently charging nothing. */
    public static boolean isKnown(String model) {
        return lookup(model) != null;
    }

    private static Rates lookup(String model) {
        if (model == null || model.isBlank()) return null;
        String m = model.toLowerCase();
        String bestKey = null;
        for (String key : RATES.keySet()) {
            if (m.startsWith(key) && (bestKey == null || key.length() > bestKey.length())) {
                bestKey = key;
            }
        }
        return bestKey == null ? null : RATES.get(bestKey);
    }

    /**
     * Estimated USD for one call. A local (Ollama) model costs nothing and is not in the table,
     * which is correct: the point of the local tier is that its tokens are free.
     */
    public static double costUsd(String model, int promptTokens, int completionTokens,
                                 int cacheWriteTokens, int cacheReadTokens) {
        Rates r = lookup(model);
        if (r == null) return 0.0;
        return (promptTokens      * r.input()      / 1_000_000.0)
             + (completionTokens  * r.output()     / 1_000_000.0)
             + (cacheWriteTokens  * r.cacheWrite() / 1_000_000.0)
             + (cacheReadTokens   * r.cacheRead()  / 1_000_000.0);
    }

    /** Convenience for a response that carries its own counters. */
    public static double costUsd(String model, LlmResponse response) {
        if (response == null) return 0.0;
        return costUsd(model, response.promptTokens(), response.completionTokens(),
                response.cacheCreationTokens(), response.cacheReadTokens());
    }
}
