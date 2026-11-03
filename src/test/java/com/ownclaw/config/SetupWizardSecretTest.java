package com.ownclaw.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownclaw.conversation.MigratedDatabase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** The wizard's replies are saved into the chat, so they repeat nothing it did not accept. */
class SetupWizardSecretTest {

    @Test
    @DisplayName("a key pasted at the provider step is neither kept as the provider nor repeated")
    void keyPastedEarlyIsNotRepeated(@TempDir Path tmp) throws Exception {
        var config = new OwnClawConfig();
        var wizard = new SetupWizardService(MigratedDatabase.at(tmp.resolve("t.db")), config,
                new ObjectMapper(), null, null, null);
        String key = "sk-ant-api03-PastedEarly";
        var reply = wizard.processStep(1, key);
        assertFalse(reply.message().toLowerCase().contains(key.toLowerCase()), reply.message());
        assertTrue(reply.message().contains("not one of the providers"), "and says it was not used");
        assertEquals(Optional.empty(), wizard.getSetting("cloud_provider"));
        assertEquals("openai", config.getMentor().getProvider(), "the provider in use is unchanged");

        assertTrue(wizard.processStep(1, "2").message().contains("`anthropic`"));
        assertEquals(Optional.of("anthropic"), wizard.getSetting("cloud_provider"));
    }
}
