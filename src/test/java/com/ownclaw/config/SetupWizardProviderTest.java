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
        // Mutation: applyOverrides without keepOpenAiWhereItWasTheDefault -> anthropic, with no key.
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
