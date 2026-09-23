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

    record Row(String userId, String taskId, String purpose, String provider, String model,
               Decision decision, List<Part> parts, long bytesOut, int toolCount,
               int promptTokens, int completionTokens, int cacheWriteTokens, int cacheReadTokens,
               double costUsd, int scrubs, int privateArtifacts, String refusalRef) {}

    void record(Row row);

    /** For tests and for wiring nothing: keeps the rows in memory. */
    static EgressLedger none() {
        return row -> { };
    }
}
