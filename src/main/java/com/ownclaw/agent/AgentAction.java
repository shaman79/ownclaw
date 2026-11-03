package com.ownclaw.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.ownclaw.interfaces.CommandHandler;

import java.util.LinkedHashSet;
import java.util.List;
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

    /** Sentinel tool name indicating the agent wants to manage persistent memory (store/list/delete facts). */
    public static final String MEMORY_MANAGE = "memory_manage";

    /** Sentinel tool name indicating the agent wants to manage scheduled tasks (schedule/list/cancel/pause/resume). */
    public static final String SCHEDULE_MANAGE = "schedule_manage";

    /** Sentinel tool name indicating the agent wants to delegate a plan to the local LLM executor. */
    public static final String DELEGATE = "delegate";

    /** The local model running a task itself searches the skills ({@link ToolFinder}). */
    public static final String FIND_TOOLS = "find_tools";

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

    public boolean isMemoryManage() {
        return MEMORY_MANAGE.equals(tool);
    }

    public boolean isScheduleManage() {
        return SCHEDULE_MANAGE.equals(tool);
    }

    public boolean isDelegate() {
        return DELEGATE.equals(tool);
    }

    public boolean isFindTools() {
        return FIND_TOOLS.equals(tool);
    }

    /** Returns true if this action is a built-in special action (not a tool invocation). */
    public boolean isSpecialAction() {
        return isResponse() || isAskUser() || isSkillCreate() || isSkillManage()
                || isCredentialManage() || isMemoryManage() || isScheduleManage()
                || isDelegate() || isFindTools();
    }

    public String responseText() {
        if (!isResponse() && !isAskUser()) return "";
        Object msg = params.get("message");
        return msg != null ? msg.toString() : "";
    }

    /**
     * The answers an {@code ask_user} offers the owner to pick from, as the model wrote them: each
     * once, in order, blanks dropped. A native call gives a list; the text protocol a JSON array,
     * or one answer per line. Anything else offers none -- the owner types the answer.
     * <p>
     * A picked answer is sent as the owner's message, and read as one typed: one the chats would read
     * as a slash command ({@link CommandHandler#isCommand}) is not offered, for it would run as
     * one -- or be refused as an unknown command -- rather than answer; nor one holding a secret
     * command ({@link CommandHandler#misplacedSecret}), which the chats refuse and keep nothing of.
     */
    public List<String> choices() {
        if (!isAskUser()) return List.of();
        List<?> items = switch (params.get("choices")) {
            case List<?> list -> list;
            case String text -> listIn(text.strip());
            case null, default -> List.of();
        };
        var choices = new LinkedHashSet<String>();
        for (Object item : items) {
            if (item instanceof String || item instanceof Number || item instanceof Boolean) {
                String choice = item.toString().strip();
                if (!choice.isEmpty() && !CommandHandler.isCommand(choice) && !CommandHandler.misplacedSecret(choice)) {
                    choices.add(choice);
                }
            }
        }
        return List.copyOf(choices);
    }

    private static List<?> listIn(String text) {
        if (text.startsWith("[")) {
            try {
                return TextCalls.MAPPER.readValue(text, List.class);
            } catch (JsonProcessingException notJson) {
                // one per line, below
            }
        }
        return List.of(text.split("\\R"));
    }
}
