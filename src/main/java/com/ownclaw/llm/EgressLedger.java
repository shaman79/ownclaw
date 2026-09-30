package com.ownclaw.llm;

import java.util.List;

/**
 * One row per cloud call, sent or not: what left, how big, what it cost — never what it said.
 * <p>
 * This is what makes privacy verifiable rather than asserted. The row holds sizes, kinds and
 * hash prefixes of every part, the labels checked, the tokens and the price; it holds no
 * content, so it cannot become the second plaintext copy the roadmap warned about. A caller
 * can prove what was sent by size and hash and can never read it back.
 */
@FunctionalInterface
public interface EgressLedger {

    enum Decision { SENT, REFUSED, OBSERVED_LEAK, ERROR }

    /** One part of the outbound body: a message, a tool description, a tool schema. */
    record Part(int index, String kind, int chars, String sha256_16) {}

    /**
     * {@code model} is the model that wrote the reply when there was one -- a declined request
     * can be answered by a fallback model -- and the configured model otherwise.
     * {@code stopReason} is why the provider's reply ended -- "end_turn", "max_tokens",
     * "refusal (cyber)" -- or null when there was no reply. A provider's refusal is recorded
     * there, not in {@code refusalRef}, which is the gateway's own: why it did not send, what its
     * check observed, or how the call failed.
     */
    record Row(String userId, String taskId, String purpose, String provider, String model,
               Decision decision, List<Part> parts, long bytesOut, int toolCount,
               int promptTokens, int completionTokens, int cacheWriteTokens, int cacheReadTokens,
               double costUsd, int scrubs, String refusalRef, String stopReason) {}

    void record(Row row);
}
