package com.ownclaw.config;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * How much the models think before they act: the owner's thinking effort, low, medium or high
 * ({@link OwnClawConfig.Mentor#getThinkingEffort}). Set from the settings page; saved, so a
 * restart keeps it ({@link SetupWizardService#applyOverrides}).
 */
@Component
public class ThinkingEffort {

    /** Its row in system_settings: one of {@link #LEVELS}. */
    static final String SETTING = "thinking_effort";

    /** The levels the owner chooses from. */
    private static final List<String> LEVELS = List.of("low", "medium", "high");

    private final OwnClawConfig config;
    private final SetupWizardService settings;

    public ThinkingEffort(OwnClawConfig config, SetupWizardService settings) {
        this.config = config;
        this.settings = settings;
    }

    public String level() {
        return config.getMentor().getThinkingEffort();
    }

    /**
     * Saved first, so a level that is shown as set is one a restart keeps. Anything but one of
     * {@link #LEVELS} is ignored, as an unknown cloud provider is: those three are the levels
     * every model that takes an effort accepts.
     */
    public void set(String level) {
        if (!LEVELS.contains(level)) return;
        settings.saveSetting(SETTING, level);
        config.getMentor().setThinkingEffort(level);
    }
}
