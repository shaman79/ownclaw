package com.ownclaw.config;

import org.springframework.stereotype.Component;

/**
 * How much of the work the local model does: the owner's time-vs-cost slider -- the default, which
 * a chat can override next to its message box. Its stops are the cloud running the skills
 * (fastest), the cloud planning and the local model running them
 * ({@link OwnClawConfig.Mentor#isPreferCost}), and local only ({@link OwnClawConfig.Mentor#isLocalOnly}).
 * A task reads it when it starts ({@code TaskOptions}). Set from the settings page and, local
 * only, the /local command; saved, so a restart keeps it ({@link SetupWizardService#applyOverrides}).
 */
@Component
public class LocalMode {

    /** Their rows in system_settings: "true" or "false". */
    static final String SETTING = "local_only";
    static final String PREFER_COST = "prefer_cost";

    private final OwnClawConfig config;
    private final SetupWizardService settings;

    public LocalMode(OwnClawConfig config, SetupWizardService settings) {
        this.config = config;
        this.settings = settings;
    }

    public boolean on() {
        return config.getMentor().isLocalOnly();
    }

    /** Saved first, so a switch that is shown as set is one a restart keeps. */
    public void set(boolean on) {
        settings.saveSetting(SETTING, Boolean.toString(on));
        config.getMentor().setLocalOnly(on);
    }

    public boolean preferCost() {
        return config.getMentor().isPreferCost();
    }

    /** Saved first, as {@link #set} is. */
    public void setPreferCost(boolean on) {
        settings.saveSetting(PREFER_COST, Boolean.toString(on));
        config.getMentor().setPreferCost(on);
    }
}
