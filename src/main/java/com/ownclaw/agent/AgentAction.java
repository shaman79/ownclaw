package com.ownclaw.agent;

import java.util.Map;

/**
 * Represents a single action decided by the ThinkingEngine.
 *
 * @param tool      the tool to invoke (or "respond" to deliver a final answer)
 * @param params    tool parameters
 * @param reasoning the agent's internal rationale for this action
 */
public record AgentAction(
        String tool,
        Map<String, Object> params,
        String reasoning
) {
    /** Sentinel tool name indicating the agent wants to deliver a final response. */
    public static final String RESPOND = "respond";

    /** Sentinel tool name indicating the agent wants to ask the user a question. */
    public static final String ASK_USER = "ask_user";

    public boolean isResponse() {
        return RESPOND.equals(tool);
    }

    public boolean isAskUser() {
        return ASK_USER.equals(tool);
    }

    public String responseText() {
        if (!isResponse() && !isAskUser()) return "";
        Object msg = params.get("message");
        return msg != null ? msg.toString() : "";
    }
}
