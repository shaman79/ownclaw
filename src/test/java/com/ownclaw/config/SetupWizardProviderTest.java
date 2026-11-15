package com.ownclaw.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownclaw.conversation.MigratedDatabase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The wizard's provider step. Anthropic is the default provider, so a new install is offered it
 * first and has it in use until another is chosen; OpenAI is the alternative.
 */
class SetupWizardProviderTest {

    /** The wizard on a new install: no key, no stored setting, and nothing looked for on the host. */
    private static SetupWizardService wizard(Path db, OwnClawConfig config) throws Exception {
        return new SetupWizardService(MigratedDatabase.at(db), config, new ObjectMapper(), null, null) {
            @Override public DiagnosticResult runDiagnostics() {
                return new DiagnosticResult(false, "", null, null, false, false, config.getMentor().getProvider());
            }
        };
    }

    @Test
    @DisplayName("a new install is offered Anthropic first and has it in use: skipping the step keeps it and asks for its key")
    void anthropicIsOfferedFirstAndInUse(@TempDir Path tmp) throws Exception {
        var config = new OwnClawConfig();
        var wizard = wizard(tmp.resolve("t.db"), config);

        String welcome = wizard.processStep(0, null).message();
        int anthropic = welcome.indexOf("1) `anthropic`"), openai = welcome.indexOf("2) `openai`");
        assertTrue(anthropic >= 0 && anthropic < openai, welcome);
        assertTrue(welcome.contains("Current: `anthropic`"), welcome);

        String kept = wizard.processStep(1, "-").message();
        assertTrue(kept.contains("Paste your **Anthropic** API key"), kept);
        assertEquals("anthropic", config.getMentor().getProvider());
        assertEquals(Optional.empty(), wizard.getSetting("cloud_provider"), "nothing was chosen, so nothing is stored");
    }

    @Test
    @DisplayName("the number shown beside a provider chooses it, as its name does: 1 is Anthropic, 2 is OpenAI")
    void theNumbersChooseWhatTheyAreShownBeside(@TempDir Path tmp) throws Exception {
        var config = new OwnClawConfig();
        var wizard = wizard(tmp.resolve("t.db"), config);

        assertTrue(wizard.processStep(1, "2").message().contains("Paste your **OpenAI** API key"));
        assertEquals("openai", config.getMentor().getProvider());
        assertEquals(Optional.of("openai"), wizard.getSetting("cloud_provider"));

        assertTrue(wizard.processStep(1, "1").message().contains("Paste your **Anthropic** API key"));
        assertEquals("anthropic", config.getMentor().getProvider());
        assertEquals(Optional.of("anthropic"), wizard.getSetting("cloud_provider"));

        assertTrue(wizard.processStep(1, "OpenAI").message().contains("Paste your **OpenAI** API key"));
        assertTrue(wizard.processStep(1, "claude").message().contains("Paste your **Anthropic** API key"));
        // Mutation: the numbers left as they were, 1 for OpenAI -> "2" asks for Anthropic's key.
    }

    /** What {@code applyOverrides} makes of an install: its keys, a stored choice, the environment's. */
    private static String providerOf(Path db, String openAiKey, String anthropicKey, String stored, String env) throws Exception {
        var config = new OwnClawConfig();
        if (openAiKey != null) config.getMentor().setApiKey(openAiKey);
        if (anthropicKey != null) config.getMentor().setAnthropicApiKey(anthropicKey);
        if (env != null) config.getMentor().setProvider(env);   // as application.yaml binds the variable
        var wizard = wizard(db, config);
        wizard.environment = name -> "OWNCLAW_MENTOR_PROVIDER".equals(name) ? env : null;
        if (stored != null) wizard.saveSetting("cloud_provider", stored);
        wizard.applyOverrides();
        return config.getMentor().getProvider();
    }

    @Test
    @DisplayName("an install that never chose a provider and has only an OpenAI key goes on with OpenAI: it ran on it while OpenAI was the default")
    void anOpenAiInstallThatNeverChoseKeepsOpenAi(@TempDir Path tmp) throws Exception {
        assertEquals("openai", providerOf(tmp.resolve("a.db"), "sk-test-openai", null, null, null));
        assertEquals("openai", providerOf(tmp.resolve("b.db"), "sk-test-openai", "  ", null, " "), "blank is not set");
        // Mutation: applyOverrides without providerWhereNoneWasChosen -> anthropic, with no key.
    }

    @Test
    @DisplayName("it is worked out at each start from the keys there are and never stored: a start that does not see the Anthropic key does not leave the install on OpenAI, and the environment's choice stands after it")
    void whatIsWorkedOutIsNotStored(@TempDir Path tmp) throws Exception {
        Path db = tmp.resolve("a.db");
        // A start that sees only the OpenAI key: an .env that could not be read, a key being replaced.
        assertEquals("openai", providerOf(db, "sk-test-openai", null, null, null));
        assertTrue(wizard(db, new OwnClawConfig()).getSetting("cloud_provider").isEmpty(), "stored, it is a choice nobody made");

        // The next start sees both keys again: the default.
        assertEquals("anthropic", providerOf(db, "sk-test-openai", "sk-ant-test", null, null));
        // And one with the environment's choice has that, whatever a start before it worked out.
        assertEquals("anthropic", providerOf(db, "sk-test-openai", null, null, "anthropic"));
        assertEquals("openai", providerOf(db, "sk-test-openai", "sk-ant-test", null, "openai"));
        assertTrue(wizard(db, new OwnClawConfig()).getSetting("cloud_provider").isEmpty());
        // Mutation: what is worked out stored as the provider chosen -> the second start is on
        // OpenAI with both keys, and the third over the environment's choice.
    }

    @Test
    @DisplayName("a key saved in the settings counts at once, and in whatever order: the only key, OpenAI's, on an install that never chose -> OpenAI from then; with Anthropic's saved too -> Anthropic; a choice stands")
    void aKeySavedInTheSettingsCountsAtOnce(@TempDir Path tmp) throws Exception {
        var openAiFirst = new Settings(tmp.resolve("a.db"));
        openAiFirst.save(java.util.Map.of("openai_api_key", "sk-test-openai"));
        assertEquals("openai", openAiFirst.config.getMentor().getProvider(), "not from the next start");
        openAiFirst.save(java.util.Map.of("anthropic_api_key", "sk-ant-test"));
        assertEquals("anthropic", openAiFirst.config.getMentor().getProvider(), "both keys: the default, as the other order gives");
        assertTrue(openAiFirst.wizard.getSetting("cloud_provider").isEmpty(), "nothing was chosen");

        var anthropicFirst = new Settings(tmp.resolve("b.db"));
        anthropicFirst.save(java.util.Map.of("anthropic_api_key", "sk-ant-test"));
        anthropicFirst.save(java.util.Map.of("openai_api_key", "sk-test-openai"));
        assertEquals("anthropic", anthropicFirst.config.getMentor().getProvider());

        var chose = new Settings(tmp.resolve("c.db"));
        chose.save(java.util.Map.of("cloud_provider", "openai"));
        chose.save(java.util.Map.of("anthropic_api_key", "sk-ant-test"));
        assertEquals("openai", chose.config.getMentor().getProvider(), "a choice stands, whatever the keys");
        var choseAnthropic = new Settings(tmp.resolve("d.db"));
        choseAnthropic.save(java.util.Map.of("cloud_provider", "anthropic"));
        choseAnthropic.save(java.util.Map.of("openai_api_key", "sk-test-openai"));
        assertEquals("anthropic", choseAnthropic.config.getMentor().getProvider());
        // Mutations: the settings not asking -> the first stays on anthropic, with no key, until a
        // restart; the rule only ever moving to OpenAI -> the first stays on OpenAI with both keys
        // until a restart, and the order the keys were saved in decides.
    }

    /** The settings page's controller over a fresh install, as its owner saves into it. */
    private static final class Settings {
        final OwnClawConfig config = new OwnClawConfig();
        final SetupWizardService wizard;
        final com.ownclaw.interfaces.web.SettingsController controller;

        Settings(Path db) throws Exception {
            wizard = wizard(db, config);
            wizard.environment = name -> null;
            controller = new com.ownclaw.interfaces.web.SettingsController(wizard, config,
                    new com.ownclaw.users.AuthService(null, null, config) {
                        @Override public boolean isOwner(String userId) { return true; }
                    },
                    new com.ownclaw.llm.LocalModelCheck(config, new ObjectMapper()), new LocalMode(config, wizard),
                    new ThinkingEffort(config, wizard));
        }

        void save(java.util.Map<String, String> updates) {
            assertEquals(200, controller.updateSettings(updates, "owner").getStatusCode().value());
        }
    }

    @Test
    @DisplayName("a provider that was chosen is left alone, and so is an install with both keys or none: there the default decides")
    void aChoiceOrTheDefaultStands(@TempDir Path tmp) throws Exception {
        assertEquals("anthropic", providerOf(tmp.resolve("a.db"), "sk-test-openai", null, "anthropic", null), "chosen in the settings");
        assertEquals("anthropic", providerOf(tmp.resolve("b.db"), "sk-test-openai", null, null, "anthropic"), "chosen in the environment");
        assertEquals("anthropic", providerOf(tmp.resolve("c.db"), "sk-test-openai", "sk-ant-test", null, null), "both keys: the default");
        assertEquals("anthropic", providerOf(tmp.resolve("d.db"), null, null, null, null), "no key: the default, and the local model");
        assertEquals("openai", providerOf(tmp.resolve("e.db"), null, "sk-ant-test", "openai", null), "chosen, whatever the keys");
        // Mutation: the rule asked whatever was chosen -> the first two become openai.
    }
}
