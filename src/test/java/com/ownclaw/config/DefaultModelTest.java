package com.ownclaw.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Anthropic model OwnClaw calls when its environment names none. Production names its model
 * (OWNCLAW_ANTHROPIC_MODEL); the default is what a new install and every test that names none
 * get, and claude-sonnet-4-20250514 -- the default until now -- was retired on 2026-06-15.
 * <p>
 * And the provider a new install calls: Anthropic, with OpenAI the alternative.
 */
class DefaultModelTest {

    @Test
    @DisplayName("the default Anthropic model is claude-opus-5-5 -- application.yaml's and the configuration's own alike")
    void theDefaultIsServed() throws Exception {
        var sources = new org.springframework.boot.env.YamlPropertySourceLoader().load("application.yaml",
                new org.springframework.core.io.ClassPathResource("application.yaml"));
        var mentor = new org.springframework.boot.context.properties.bind.Binder(
                org.springframework.boot.context.properties.source.ConfigurationPropertySources.from(sources),
                new org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver(sources))
                .bind("ownclaw.mentor", OwnClawConfig.Mentor.class)
                .get();
        assertEquals("claude-opus-5-5", mentor.getAnthropicModel(), "application.yaml, with no OWNCLAW_ANTHROPIC_MODEL");
        assertEquals("claude-opus-5-5", new OwnClawConfig().getMentor().getAnthropicModel(), "the configuration's own");
        // Mutation: either default back to claude-sonnet-4-20250514 -> this names it.
    }

    @Test
    @DisplayName("the default cloud provider is Anthropic -- application.yaml's and the configuration's own alike -- and OWNCLAW_MENTOR_PROVIDER=openai chooses OpenAI")
    void theDefaultProviderIsAnthropic() throws Exception {
        assertEquals("anthropic", mentor(java.util.Map.of()).getProvider(), "application.yaml, with no OWNCLAW_MENTOR_PROVIDER");
        assertEquals("anthropic", new OwnClawConfig().getMentor().getProvider(), "the configuration's own");
        assertEquals("openai", mentor(java.util.Map.of("OWNCLAW_MENTOR_PROVIDER", "openai")).getProvider(),
                "the alternative, chosen in the environment");
        // Mutation: either default back to openai -> this names it.
    }

    @Test
    @DisplayName("a provider setting that names neither provider -- left blank, mistyped -- is the default, not OpenAI; openai in any case is OpenAI")
    void aProviderThatIsNeitherIsTheDefault() throws Exception {
        for (String neither : new String[] {"", "  ", "claude", "open ai", "Anthropic"}) {
            assertEquals("anthropic", mentor(java.util.Map.of("OWNCLAW_MENTOR_PROVIDER", neither)).getProvider(),
                    "OWNCLAW_MENTOR_PROVIDER='" + neither + "'");
        }
        for (String openai : new String[] {"openai", "OpenAI", " OPENAI "}) {
            assertEquals("openai", mentor(java.util.Map.of("OWNCLAW_MENTOR_PROVIDER", openai)).getProvider(),
                    "OWNCLAW_MENTOR_PROVIDER='" + openai + "'");
        }
        // A stored setting is applied through the same setter (SetupWizardService#applyOverrides).
        var stored = new OwnClawConfig().getMentor();
        stored.setProvider("openai");
        stored.setProvider(null);
        assertEquals("anthropic", stored.getProvider());
        // Mutation: the setter keeps what it is given -> "claude" stays, and the gateway, which
        // asks only whether the name is "anthropic", calls OpenAI.
    }

    /** application.yaml's {@code ownclaw.mentor}, bound as the application binds it in this environment. */
    private static OwnClawConfig.Mentor mentor(java.util.Map<String, Object> environment) throws Exception {
        var sources = new java.util.ArrayList<org.springframework.core.env.PropertySource<?>>();
        sources.add(new org.springframework.core.env.MapPropertySource("environment", environment));
        sources.addAll(new org.springframework.boot.env.YamlPropertySourceLoader().load("application.yaml",
                new org.springframework.core.io.ClassPathResource("application.yaml")));
        return new org.springframework.boot.context.properties.bind.Binder(
                org.springframework.boot.context.properties.source.ConfigurationPropertySources.from(sources),
                new org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver(sources))
                .bind("ownclaw.mentor", OwnClawConfig.Mentor.class)
                .get();
    }
}
