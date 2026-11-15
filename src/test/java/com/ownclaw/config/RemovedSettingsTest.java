package com.ownclaw.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Settings that nothing acted on were removed, and the token budgets with them. An install set
 * up before can still name one -- production's environment file is not ours to read -- and has
 * to start as it did: a name the configuration has no field for is ignored, not refused.
 */
class RemovedSettingsTest {

    /** Every removed setting, under {@code ownclaw}. */
    private static final List<String> REMOVED = List.of(
            "executor.provider", "confidence.mentor-threshold", "confidence.cache-skip-threshold",
            "feedback.max-rounds", "observability.chat-status-messages", "observability.status-verbosity",
            "webui.enabled", "queue.ollama-concurrency", "queue.cloud-concurrency", "queue.sandbox-concurrency",
            "tasks.default-timeout", "tasks.step-timeout", "tasks.long-running-threshold",
            "budgets.daily-cloud-tokens", "budgets.per-task-cloud-tokens", "budgets.warning-threshold");

    /** OwnClawConfig bound as the application binds it: by Spring Boot, from the environment. */
    @EnableConfigurationProperties
    @Import(OwnClawConfig.class)
    static class Bound { }

    @Test
    @DisplayName("an environment that still names a removed setting starts as before: the name is ignored, and the settings beside it are read")
    void aRemovedSettingStillNamedIsIgnored() {
        // As the service's environment file names them (OWNCLAW_BUDGETS_DAILY_CLOUD_TOKENS), and
        // as a property file or a command line would (ownclaw.budgets.daily-cloud-tokens).
        var variables = new HashMap<String, Object>();
        var properties = new HashMap<String, Object>();
        for (String name : REMOVED) {
            variables.put(("OWNCLAW_" + name).toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_'), "1");
            properties.put("ownclaw." + name, "1");
        }
        variables.put("OWNCLAW_TASKS_STALL_TIMEOUT", "77");
        properties.put("ownclaw.queue.max-queued-tasks", "7");

        try (var context = new AnnotationConfigApplicationContext()) {
            var sources = context.getEnvironment().getPropertySources();
            sources.addFirst(new SystemEnvironmentPropertySource("removed-systemEnvironment", variables));
            sources.addFirst(new MapPropertySource("removed-properties", properties));
            context.register(Bound.class);
            assertDoesNotThrow(context::refresh, "the start");

            OwnClawConfig config = context.getBean(OwnClawConfig.class);
            assertEquals(77, config.getTasks().getStallTimeout(), "a variable beside the removed ones is read");
            assertEquals(7, config.getQueue().getMaxQueuedTasks(), "and a property beside them");
        }
        // Mutation: ignoreUnknownFields = false on OwnClawConfig -> the context does not start:
        // the removed names are properties nothing binds.
    }

    @Test
    @DisplayName("the removed settings are gone from the configuration, so the test above names what it has no field for")
    void theRemovedSettingsHaveNoField() {
        Map<String, Object> shown = new com.fasterxml.jackson.databind.ObjectMapper()
                .convertValue(new OwnClawConfig(), new com.fasterxml.jackson.core.type.TypeReference<>() { });
        for (String name : REMOVED) {
            Object at = shown;
            for (String part : name.split("\\.")) {
                at = at instanceof Map<?, ?> group ? group.get(camel(part)) : null;
            }
            assertNull(at, "ownclaw." + name + " is still a setting");
        }
        assertNotNull(((Map<?, ?>) shown.get("queue")).get("maxQueuedTasks"), "read the same way, a setting that stays is found");
    }

    /** {@code max-queued-tasks} as the configuration's property is named: {@code maxQueuedTasks}. */
    private static String camel(String kebab) {
        var out = new StringBuilder();
        for (String word : kebab.split("-")) {
            out.append(out.isEmpty() ? word : word.substring(0, 1).toUpperCase(Locale.ROOT) + word.substring(1));
        }
        return out.toString();
    }
}
