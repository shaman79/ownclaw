package com.ownclaw.interfaces;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownclaw.agent.LlmRouter;
import com.ownclaw.config.LocalMode;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.config.SetupWizardService;
import com.ownclaw.config.ThinkingEffort;
import com.ownclaw.conversation.MigratedDatabase;
import com.ownclaw.interfaces.web.SettingsController;
import com.ownclaw.llm.LocalModelCheck;
import com.ownclaw.users.AuthService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** The owner's thinking effort: high until set, saved, kept by a restart, and on the settings page. */
class ThinkingEffortSettingTest {

    @Test
    @DisplayName("the level is saved and a restart keeps it; anything but low, medium or high is ignored")
    void savedAndKept(@TempDir Path tmp) throws Exception {
        var db = MigratedDatabase.at(tmp.resolve("t.db"));
        var config = new OwnClawConfig();
        var settings = new SetupWizardService(db, config, new ObjectMapper(), null, null, null);
        var effort = new ThinkingEffort(config, settings);
        assertEquals("high", effort.level(), "high until the owner sets it");

        effort.set("low");
        assertEquals("low", config.getMentor().getThinkingEffort());
        assertEquals(Optional.of("low"), settings.getSetting("thinking_effort"));

        effort.set("max");
        effort.set("");
        assertEquals("low", effort.level(), "a level the choice does not offer was taken");
        assertEquals(Optional.of("low"), settings.getSetting("thinking_effort"));

        var restarted = new OwnClawConfig();
        new SetupWizardService(db, restarted, new ObjectMapper(), null, null, null).applyOverrides();
        assertEquals("low", restarted.getMentor().getThinkingEffort(), "a restart forgot the level");
    }

    @Test
    @DisplayName("the settings API shows the level and sets it, as the page sends it")
    void theSettingsApi(@TempDir Path tmp) throws Exception {
        var db = MigratedDatabase.at(tmp.resolve("t.db"));
        var config = new OwnClawConfig();
        config.getExecutor().setUrl("");   // the local model's status is then read without a network
        var settings = new SetupWizardService(db, config, new ObjectMapper(), null, null, null) {
            @Override public DiagnosticResult runDiagnostics() { return null; }
        };
        var controller = new SettingsController(settings, config, new LlmRouter(null, null, config, null),
                new AuthService(null, null, config) {
                    @Override public boolean isOwner(String userId) { return true; }
                },
                new LocalModelCheck(config, new ObjectMapper()), new LocalMode(config, settings),
                new ThinkingEffort(config, settings));

        assertEquals("high", controller.getSettings("owner").getBody().get("thinking_effort"));
        var after = controller.updateSettings(Map.of("thinking_effort", " Medium "), "owner").getBody();
        assertEquals("medium", after.get("thinking_effort"));
        assertEquals(Optional.of("medium"), settings.getSetting("thinking_effort"));
    }

    @Test
    @DisplayName("the settings page shows the level, says what each one does, and saves a change")
    void onTheSettingsPage() throws Exception {
        String flat = LocalSwitchTest.resource("/static/index.html").replaceAll("\\s+", " ");
        for (String level : new String[] {"low", "medium", "high"}) {
            assertTrue(flat.contains("'<option value=\"" + level + "\"' + (data.thinking_effort === '" + level
                    + "' ? ' selected' : '')"), level + " is not a choice on the settings page");
        }
        assertTrue(flat.contains("low: 'Low: Claude thinks least -- fastest and cheapest. The local model also "
                + "answers without reasoning first, so its steps are much faster.'"), "what low does locally");
        assertTrue(flat.contains("if (effort !== originalData.thinking_effort) payload.thinking_effort = effort;"),
                "a change is not saved");
    }
}
