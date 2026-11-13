package com.ownclaw.llm;

import com.ownclaw.privacy.PrivateIndex;
import com.ownclaw.privacy.Redactor;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
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
 * @param skillCode    texts the task holds that are skill code, or what a skill printed -- the code
 *                     being fixed, a skill read's code, a load error, a run's output -- each to what it
 *                     is ({@link Redactor.Declared}): the gateway filters every part whole, as any,
 *                     and inside each such text also replaces every value the user's table holds
 *                     wherever it stands ({@code Redactor#filter(String, String, Map, Redactor.Tally,
 *                     Map)}). Only the task declares them ({@code AgentContext#declareSkillCode})
 */
public record EgressContext(String userId, String taskId, String purpose, PrivateIndex index,
                            Map<String, String> secretValues,
                            BiPredicate<Integer, String> allowed,
                            IntFunction<String> toolOf,
                            HandedOut handedOut,
                            Map<String, Redactor.Declared> skillCode) {

    public EgressContext {
        index = index == null ? new PrivateIndex() : index;
        secretValues = secretValues == null ? Map.of() : Map.copyOf(secretValues);
        allowed = allowed == null ? (h, w) -> false : allowed;
        toolOf = toolOf == null ? n -> null : toolOf;
        purpose = purpose == null ? "unspecified" : purpose;
        // Never the caller's: only the gateway puts a text in it, so no caller can give it one with
        // texts it never handed out.
        handedOut = new HandedOut();
        skillCode = skillCode == null ? Map.of() : Map.copyOf(skillCode);
    }

    /** A context with a memory of its own, empty ({@link #handedOut}), and no skill code. */
    public EgressContext(String userId, String taskId, String purpose, PrivateIndex index,
                         Map<String, String> secretValues, BiPredicate<Integer, String> allowed,
                         IntFunction<String> toolOf) {
        this(userId, taskId, purpose, index, secretValues, allowed, toolOf, null, null);
    }

    /** As the canonical constructor, with no skill code: a {@code handedOut} passed is not taken. */
    public EgressContext(String userId, String taskId, String purpose, PrivateIndex index,
                         Map<String, String> secretValues, BiPredicate<Integer, String> allowed,
                         IntFunction<String> toolOf, HandedOut handedOut) {
        this(userId, taskId, purpose, index, secretValues, allowed, toolOf, handedOut, null);
    }

    /**
     * The texts the gateway handed out under one context as the model wrote them -- the replies to
     * requests that asked for that ({@link LlmRequestConfig#asWritten}): placeholders where the
     * model wrote them, no value of the user's put back. An assistant turn that is one of them, or
     * the beginning of one up to the end of a line -- what follows it there is blanks and a line
     * break, or only whitespace -- is the model's own words coming back as they left it, and the
     * gateway sends it so ({@code CloudGateway#chat}). A part of a skill's code cut off at the
     * maximum output goes back in whole lines, the line it was cut in dropped (the code's
     * ReplySeam): that is a beginning of the reply, and nothing in it is not the provider's own.
     * Only the gateway puts a text in or asks about one; held by the context, it ends with it.
     */
    public static final class HandedOut {
        private final List<String> texts = new CopyOnWriteArrayList<>();

        private HandedOut() {}

        /** {@code text} was handed out as written. */
        void add(String text) {
            if (text == null || text.isEmpty()) return;
            texts.add(text);
        }

        /**
         * Whether {@code turn} is a text handed out as written, or its beginning up to the end of a
         * line of it ({@link #echoes}).
         */
        boolean holds(String turn) {
            if (turn == null || turn.isEmpty()) return false;
            for (String text : texts) {
                if (echoes(text, turn)) return true;
            }
            return false;
        }

        /**
         * Forgets every text handed out that none of {@code turns} echoes: a skill's code asked for
         * again from its latest attempt, the attempts before it no longer go back. It can only take
         * texts out, so no caller can make a turn an echo with it.
         */
        public void keepOnlyEchoedBy(java.util.Collection<String> turns) {
            texts.removeIf(text -> turns.stream().noneMatch(turn -> turn != null && !turn.isEmpty() && echoes(text, turn)));
        }

        /** How many texts are held: what a long skill's repairs keep in memory. */
        public int size() {
            return texts.size();
        }

        /**
         * Whether {@code turn} is {@code text}, or a beginning of it that ends where a line of it
         * ends: what follows in {@code text} is whitespace through a line break, or nothing but
         * whitespace.
         */
        static boolean echoes(String text, String turn) {
            if (!text.startsWith(turn)) return false;
            for (int i = turn.length(); i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '\n') return true;
                if (!Character.isWhitespace(c)) return false;
            }
            return true;
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
                + ", secretValues=" + secretValues.keySet() + ", skillCode=" + skillCode.size() + " texts]";
    }
}
