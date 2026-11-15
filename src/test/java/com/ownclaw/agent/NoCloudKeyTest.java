package com.ownclaw.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.llm.CloudGateway;
import com.ownclaw.llm.CloudImages;
import com.ownclaw.llm.EgressContext;
import com.ownclaw.llm.LlmException;
import com.ownclaw.llm.LlmMessage;
import com.ownclaw.llm.LlmProvider;
import com.ownclaw.llm.LlmRequestConfig;
import com.ownclaw.llm.LlmResponse;
import com.ownclaw.llm.Replies;
import com.ownclaw.privacy.Redactor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A new install with no API key at all. Its provider is Anthropic, the default, and it has to do
 * what an install on OpenAI with no key does: start, find no cloud model, and run its tasks on the
 * local one. The real providers are behind the door here, as Spring puts them there.
 */
class NoCloudKeyTest {

    /** A local model that answers. */
    private static final LlmProvider LOCAL = new LlmProvider() {
        public LlmResponse chat(List<LlmMessage> m, LlmRequestConfig c) { return Replies.of("hello", 1, 1); }
        public boolean isAvailable() { return true; }
        public String name() { return "ollama"; }
    };

    @ParameterizedTest(name = "provider: {0}")
    @NullSource   // left at its default
    @ValueSource(strings = {"anthropic", "openai"})
    @DisplayName("with no API key the cloud model is not there and a task's calls go to the local one, on the default provider as on either named")
    void withNoKeyTheLocalModelRunsTheTask(String provider) {
        var config = new OwnClawConfig();
        if (provider != null) config.getMentor().setProvider(provider);
        var gateway = new CloudGateway(config, new ObjectMapper(), row -> { }, new Redactor(null), CloudImages.NONE);
        var router = new LlmRouter(LOCAL, gateway, null);

        assertFalse(gateway.isAvailable(), "no key, no cloud model");
        assertSame(LOCAL, router.selectProvider(new AgentContext("u1", "t1", "hello")));

        // Asked all the same, the provider refuses before it sends anything, and says why -- once:
        // a missing key is not worth another try, and is not an outage to wait out.
        String expected = "openai".equals(provider) ? "openai" : "anthropic";
        assertEquals(expected, gateway.name());
        var asked = new LlmRequestConfig(null, null, false)
                .withEgress(new EgressContext("u1", "t1", "think", null, null, null, null));
        var refused = assertThrows(LlmException.class, () -> gateway.chat(List.of(LlmMessage.user("hello")), asked));
        assertEquals("[" + expected + "] API key not configured", refused.getMessage());
        assertFalse(refused.isRetryable());
        assertNull(gateway.contextWindow(asked.progress()), "and no lookup of the model's limits is sent either");
        // Mutation: Anthropic counted as available without a key -> the task is sent to the
        // cloud, and its first call fails where OpenAI's install went on locally.
    }
}
