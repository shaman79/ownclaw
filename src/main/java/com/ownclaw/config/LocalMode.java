package com.ownclaw.config;

import org.springframework.stereotype.Component;

/**
 * The owner's local-only switch ({@link OwnClawConfig.Mentor#isLocalOnly}): read by the router on
 * every model call, set from the settings page and the /local command, and saved, so a restart
 * keeps it ({@link SetupWizardService#applyOverrides}).
 */
@Component
public class LocalMode {

    /** Its row in system_settings: "true" or "false". */
    static final String SETTING = "local_only";

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
}
