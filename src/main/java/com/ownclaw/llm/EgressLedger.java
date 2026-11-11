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
     * check observed, or how the call failed. {@code secretsRemoved} and
     * {@code identifiersReplaced} count what the privacy filter took out of the request and
     * what it wrote as placeholders. {@code images} are the images that left with it -- none on a
     * request that was refused -- and {@code withheld} the ones it placed that the gateway kept back.
     */
    record Row(String userId, String taskId, String purpose, String provider, String model,
               Decision decision, List<Part> parts, long bytesOut, int toolCount,
               int promptTokens, int completionTokens, int cacheWriteTokens, int cacheReadTokens,
               double costUsd, int secretsRemoved, int identifiersReplaced, String refusalRef,
               String stopReason, List<Image> images, List<Withheld> withheld) {

        public Row {
            images = images == null ? List.of() : List.copyOf(images);
            withheld = withheld == null ? List.of() : List.copyOf(withheld);
        }

        /** A request that carried no image. */
        public Row(String userId, String taskId, String purpose, String provider, String model,
                   Decision decision, List<Part> parts, long bytesOut, int toolCount,
                   int promptTokens, int completionTokens, int cacheWriteTokens, int cacheReadTokens,
                   double costUsd, int secretsRemoved, int identifiersReplaced, String refusalRef,
                   String stopReason) {
            this(userId, taskId, purpose, provider, model, decision, parts, bytesOut, toolCount, promptTokens,
                    completionTokens, cacheWriteTokens, cacheReadTokens, costUsd, secretsRemoved,
                    identifiersReplaced, refusalRef, stopReason, List.of(), List.of());
        }
    }

    /**
     * An image that left with a request -- an image the owner pasted, which the privacy filter does
     * not read: the file it is, the copy's type and pixel size, its bytes before base64 and a hash
     * prefix of them. Never the bytes: they stay in the file, where the hash can be checked against.
     */
    record Image(String fileId, String mediaType, int width, int height, long bytes, String sha256_16) {}

    /**
     * An image a request placed that the gateway did not send ({@link CloudImages}): which file, and
     * why -- not this user's, never marked shown to the cloud, its copy unreadable or changed since.
     */
    record Withheld(String fileId, String why) {}

    void record(Row row);
}
