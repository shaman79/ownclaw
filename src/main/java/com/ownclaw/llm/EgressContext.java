package com.ownclaw.llm;

import com.ownclaw.privacy.PrivateIndex;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;
import java.util.function.IntFunction;

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
 * @param allowed      whether a stretch of hits (handle, normalised text: one window, or several
 *                     running on) is material the cloud was already given -- the task's text, a
 *                     PUBLIC artifact recorded before the private one, the rest that
 *                     AgentContext's {@code Excuses} lists -- and may go
 * @param toolOf       the tool that produced a result, by handle -- null for a handle it does
 *                     not know -- so a refusal names the result it found
 * @param handedOut    the replies the gateway handed out under this context as the model wrote
 *                     them ({@link HandedOut}): always this context's own, and empty when it is
 *                     made -- whatever a caller passes is not taken
 */
public record EgressContext(String userId, String taskId, String purpose, PrivateIndex index,
                            Map<String, String> secretValues,
                            BiPredicate<Integer, String> allowed,
                            IntFunction<String> toolOf,
                            HandedOut handedOut) {

    public EgressContext {
        index = index == null ? new PrivateIndex() : index;
        secretValues = secretValues == null ? Map.of() : Map.copyOf(secretValues);
        allowed = allowed == null ? (h, w) -> false : allowed;
        toolOf = toolOf == null ? n -> null : toolOf;
        purpose = purpose == null ? "unspecified" : purpose;
        // Never the caller's: only the gateway puts a text in it, so no caller can give it one with
        // texts it never handed out.
        handedOut = new HandedOut();
    }

    /** A context with a memory of its own, empty ({@link #handedOut}). */
    public EgressContext(String userId, String taskId, String purpose, PrivateIndex index,
                         Map<String, String> secretValues, BiPredicate<Integer, String> allowed,
                         IntFunction<String> toolOf) {
        this(userId, taskId, purpose, index, secretValues, allowed, toolOf, null);
    }

    /**
     * The texts the gateway handed out under one context as the model wrote them -- the replies to
     * requests that asked for that ({@link LlmRequestConfig#asWritten}): placeholders where the
     * model wrote them, no value of the user's put back -- each also without the whitespace it
     * ends with. An assistant turn whose text is one of them is the model's own words coming back
     * as they left it, and the gateway sends it so ({@code CloudGateway#chat}). Only the gateway
     * puts a text in or asks about one; held by the context, it ends with it.
     */
    public static final class HandedOut {
        private final Set<String> texts = ConcurrentHashMap.newKeySet();

        private HandedOut() {}

        /** {@code text} was handed out as written: it, and it without its trailing whitespace. */
        void add(String text) {
            if (text == null || text.isEmpty()) return;
            texts.add(text);
            texts.add(text.stripTrailing());
        }

        /** Whether {@code text} is a text handed out as written, or one without its trailing whitespace. */
        boolean holds(String text) {
            return text != null && !text.isEmpty() && texts.contains(text);
        }
    }

    /**
     * Without the vault values -- their names only. A record's own toString prints every
     * component, and so every decrypted secret, into whatever logs the context or a request
     * config holding it.
     */
    @Override
    public String toString() {
        return "EgressContext[userId=" + userId + ", taskId=" + taskId + ", purpose=" + purpose
                + ", secretValues=" + secretValues.keySet() + "]";
    }
}
