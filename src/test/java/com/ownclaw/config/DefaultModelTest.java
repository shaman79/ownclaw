package com.ownclaw.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Anthropic model OwnClaw calls when its environment names none. Production names its model
 * (OWNCLAW_ANTHROPIC_MODEL); the default is what a new install and every test that names none
 * get, and claude-sonnet-4-20250514 -- the default until now -- was retired on 2026-06-15.
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
}
