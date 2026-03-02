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

    /** Sentinel tool name indicating the agent wants to create or update a skill. */
    public static final String SKILL_CREATE = "skill_create";

    /** Sentinel tool name indicating the agent wants to manage skills (read/delete/list/analyze). */
    public static final String SKILL_MANAGE = "skill_manage";

    /** Sentinel tool name indicating the agent wants to manage credentials (list/check/store). */
    public static final String CREDENTIAL_MANAGE = "credential_manage";

    public boolean isResponse() {
        return RESPOND.equals(tool);
    }

    public boolean isAskUser() {
        return ASK_USER.equals(tool);
    }

    public boolean isSkillCreate() {
        return SKILL_CREATE.equals(tool);
    }

    public boolean isSkillManage() {
        return SKILL_MANAGE.equals(tool);
    }

    public boolean isCredentialManage() {
        return CREDENTIAL_MANAGE.equals(tool);
    }

    /** Returns true if this action is a built-in special action (not a tool invocation). */
    public boolean isSpecialAction() {
        return isResponse() || isAskUser() || isSkillCreate() || isSkillManage() || isCredentialManage();
    }

    public String responseText() {
        if (!isResponse() && !isAskUser()) return "";
        Object msg = params.get("message");
        return msg != null ? msg.toString() : "";
    }
}
