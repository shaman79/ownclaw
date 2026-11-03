package com.ownclaw.llm;

import java.util.List;

/**
 * Replies as a fake provider in a test returns them: one attempt, by a model the reply does not
 * name. The providers build theirs in one place (StreamedReply); tests build theirs here, so main
 * code carries no constructor only a test uses.
 */
public final class Replies {

    private Replies() {}

    /** Text and its counters, with no stop reason. */
    public static LlmResponse of(String content, int promptTokens, int completionTokens) {
        return of(content, promptTokens, completionTokens, 0, 0, null);
    }

    /** A reply that ended for {@code stopReason}, with all four counters. */
    public static LlmResponse of(String content, int promptTokens, int completionTokens,
                                 int cacheWriteTokens, int cacheReadTokens, String stopReason) {
        return of(content, promptTokens, completionTokens, cacheWriteTokens, cacheReadTokens,
                stopReason, List.of());
    }

    /** ...that also asks for these tool calls. */
    public static LlmResponse of(String content, int promptTokens, int completionTokens,
                                 int cacheWriteTokens, int cacheReadTokens, String stopReason,
                                 List<ToolCall> toolCalls) {
        return new LlmResponse(content, toolCalls, null, stopReason, null, null, null, null,
                List.of(new LlmResponse.Usage(null, promptTokens, completionTokens,
                        cacheWriteTokens, cacheReadTokens)));
    }
}
