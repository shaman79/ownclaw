package com.ownclaw.agent;

import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.conversation.ChatOptions;

/**
 * How one task runs, decided once, when it starts ({@link AgentLoop}): what its message chose next
 * to the message box, else what its chat chose, else the owner's defaults on the settings page.
 * Every reader reads them here, not in the configuration, so a chat's choice is its own tasks'
 * alone, and a default changed while a task runs changes the tasks that start after it.
 *
 * @param localOnly  no cloud model is called: the local model runs the task ({@link LlmRouter#selectProvider})
 * @param preferCost on a chat, the cloud plans and the local model runs the skills
 *                   ({@code ThinkingEngine#stepMode})
 * @param effort     the thinking effort every think, code and delegation call carries
 */
public record TaskOptions(boolean localOnly, boolean preferCost, String effort) {

    /** What was chosen, and the owner's defaults where nothing was. */
    static TaskOptions of(ChatOptions chosen, OwnClawConfig.Mentor defaults) {
        ChatOptions options = chosen.or(ChatOptions.defaultsOf(defaults));
        return new TaskOptions("free".equals(options.costMode()), "cheaper".equals(options.costMode()),
                options.effort());
    }
}
