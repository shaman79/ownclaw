package com.ownclaw.llm;

import com.ownclaw.privacy.PrivateIndex;

import java.util.Map;
import java.util.function.BiPredicate;

/**
 * What a cloud call carries with it so the gateway can decide, scrub, check and record.
 * <p>
 * A call without one is refused, not scanned against nothing: "unclassified means denied" is the
 * property that stops a fourth call site next month from bypassing the door. The context is
 * built by the task ({@code AgentContext.egress}) and never by a call site by hand.
 *
 * @param userId       whose task, for the ledger and the vault
 * @param taskId       which task, for the ledger; null for the one call that has no task
 * @param purpose      think | codegen | analyze — one word, for the ledger row
 * @param index        the task's canary index: every PRIVATE artifact's windows
 * @param secretValues decrypted vault values that must be scrubbed from the body, by key
 * @param allowed      whether a hit (handle, normalised window) is material the cloud was
 *                     already given — the task text, or a PUBLIC artifact recorded before the
 *                     private one — and may go
 */
public record EgressContext(String userId, String taskId, String purpose, PrivateIndex index,
                            Map<String, String> secretValues,
                            BiPredicate<Integer, String> allowed) {

    public EgressContext {
        index = index == null ? new PrivateIndex() : index;
        secretValues = secretValues == null ? Map.of() : Map.copyOf(secretValues);
        allowed = allowed == null ? (h, w) -> false : allowed;
        purpose = purpose == null ? "unspecified" : purpose;
    }

    /** For a call that belongs to no task — nothing is private to it, nothing is allowed. */
    public static EgressContext noTask(String purpose, String userId) {
        return new EgressContext(userId, null, purpose, new PrivateIndex(), Map.of(), (h, w) -> false);
    }
}
