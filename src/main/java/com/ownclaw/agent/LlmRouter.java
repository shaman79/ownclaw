package com.ownclaw.agent;

import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.llm.LlmProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Routes LLM requests to the appropriate provider based on context.
 *
 * Strategy: cloud-first for user task reasoning.
 * - Use the cloud provider (e.g., OpenAI) by default for reliable
 *   understanding of complex user prompts and multi-step reasoning.
 * - Fall back to the local provider (e.g., Ollama) when:
 *   a) The cloud provider is unavailable
 * - Use local() directly for simple, high-volume operations
 *   (summarization, content analysis, etc.)
 */
@Component
public class LlmRouter {

    private static final Logger log = LoggerFactory.getLogger(LlmRouter.class);

    private final LlmProvider localProvider;
    private final LlmProvider cloudProvider;
    private final OwnClawConfig config;

    public LlmRouter(
            @Qualifier("ollamaProvider") LlmProvider localProvider,
            @Qualifier("openAiProvider") LlmProvider cloudProvider,
            OwnClawConfig config
    ) {
        this.localProvider = localProvider;
        this.cloudProvider = cloudProvider;
        this.config = config;
    }

    /**
     * Select the best provider for user task reasoning.
     * Cloud-first: the cloud/mentor LLM understands complex user prompts
     * far more reliably than the local model.
     */
    public LlmProvider selectProvider(AgentContext context) {
        // Prefer cloud for reliable understanding of user intent
        if (cloudProvider.isAvailable()) {
            log.debug("Using cloud provider for task reasoning");
            return cloudProvider;
        }

        // Fall back to local if cloud is unavailable
        if (localProvider.isAvailable()) {
            log.warn("Cloud provider unavailable, falling back to local");
            return localProvider;
        }

        // Last resort: return cloud and let it fail with a clear error
        log.error("No LLM providers available!");
        return cloudProvider;
    }

    /**
     * Get the local provider directly (for non-critical, high-volume operations).
     */
    public LlmProvider local() {
        return localProvider;
    }

    /**
     * Get the cloud provider directly (for critical operations requiring best reasoning).
     */
    public LlmProvider cloud() {
        return cloudProvider;
    }
}
