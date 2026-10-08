package com.ownclaw.conversation;

import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.config.ThinkingEffort;

import java.util.List;

/**
 * What a chat has chosen of the owner's two defaults on the settings page: time vs cost -- one of
 * {@link #COST_MODES} -- and the thinking effort, one of {@link ThinkingEffort#LEVELS}. Null is no
 * choice: the chat follows that default. The web chat sends its choice with each message, and it
 * is saved on the chat ({@link ConversationService#setChatOptions}), so it stays for the chat's
 * next messages, those sent from Telegram too.
 * <p>
 * A value that is none of those is no choice either: a message cannot make a chat run on
 * something the owner was never offered.
 */
public record ChatOptions(String costMode, String effort) {

    /** The stops of time vs cost: the cloud runs the skills, the local model runs them, local only. */
    public static final List<String> COST_MODES = List.of("fast", "cheaper", "free");

    /** No choice: the defaults, both. */
    public static final ChatOptions NONE = new ChatOptions(null, null);

    public ChatOptions {
        // List.of(...).contains(null) throws, so null is asked first.
        if (costMode != null && !COST_MODES.contains(costMode)) costMode = null;
        if (effort != null && !ThinkingEffort.LEVELS.contains(effort)) effort = null;
    }

    /**
     * The owner's defaults, as the stops he chose them at: local only is the last stop of time vs
     * cost, as on the settings page, whatever prefer-cost was left at.
     */
    public static ChatOptions defaultsOf(OwnClawConfig.Mentor mentor) {
        return new ChatOptions(mentor.isLocalOnly() ? "free" : mentor.isPreferCost() ? "cheaper" : "fast",
                mentor.getThinkingEffort());
    }

    /** This choice, and the given one where this has none. */
    public ChatOptions or(ChatOptions defaults) {
        return new ChatOptions(costMode != null ? costMode : defaults.costMode,
                effort != null ? effort : defaults.effort);
    }
}
