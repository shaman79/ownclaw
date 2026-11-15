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
        return new SetupWizardService(MigratedDatabase.at(db), config, new ObjectMapper(), null, null, null) {
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
}
