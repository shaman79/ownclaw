package com.ownclaw.agent;

import com.ownclaw.llm.LlmMessage;
import com.ownclaw.llm.LlmResponse;

import java.util.List;

/**
 * The full result of a single thinking step — carries both the parsed action
 * and the raw inputs/outputs for debug visibility.
 *
 * @param reply      the reply the step was billed for, or null when none came (the call failed).
 *                   The loop counts and prices it like every other model call made for the task:
 *                   every billed attempt, cache included, each at the rates of the model that ran
 *                   it.
 * @param callFailed how the call failed, when it did -- the provider's message -- or null when a
 *                   reply came: what the task's ending says, where the step records what the
 *                   model is told about it
 * @param inProse    whether the action is an answer the model gave without calling respond: text
 *                   and no tool call, which the engine reads as a respond holding that text. Such
 *                   an answer carries none of respond's other parameters -- unattended work's
 *                   {@code problems} -- so the loop asks once for what it could not say
 *                   ({@link SkillSelfRepair#owed})
 */
public record ThinkResult(
        AgentAction action,
        List<LlmMessage> promptMessages,
        String rawLlmOutput,
        LlmResponse reply,
        String callFailed,
        boolean inProse
) {
    /** A step whose call brought a reply: a call the model made, or nothing to run. */
    public ThinkResult(AgentAction action, List<LlmMessage> promptMessages, String rawLlmOutput,
                       LlmResponse reply) {
        this(action, promptMessages, rawLlmOutput, reply, null, false);
    }

    /** A step whose call failed. */
    public ThinkResult(AgentAction action, List<LlmMessage> promptMessages, String rawLlmOutput,
                       LlmResponse reply, String callFailed) {
        this(action, promptMessages, rawLlmOutput, reply, callFailed, false);
    }

    /** Input and output tokens as the provider reported them; 0 when no reply came. */
    public int totalTokens() {
        return reply == null ? 0 : reply.totalTokens();
    }
}
