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
 * Strategy: local-first with cloud escalation.
 * - Start with the local provider (e.g., Ollama) for speed and cost
 * - Escalate to the cloud provider (e.g., OpenAI) when:
 *   a) The local provider is unavailable
 *   b) The task has had multiple consecutive failures (the local model may be struggling)
 *   c) The trajectory shows low progress (many steps with no successful tool calls)
 */
@Component
public class LlmRouter {

    private static final Logger log = LoggerFactory.getLogger(LlmRouter.class);

    /** Number of consecutive failures before escalating to cloud. */
    private static final int ESCALATION_FAILURE_THRESHOLD = 3;

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
     * Select the best provider for the current reasoning step.
     */
    public LlmProvider selectProvider(AgentContext context) {
        // If local provider is down, use cloud
        if (!localProvider.isAvailable()) {
            log.debug("Local provider unavailable, using cloud");
            return cloudProvider;
        }

        // If there have been several consecutive failures, try cloud for better reasoning
        int failures = context.trajectory().consecutiveFailures();
        if (failures >= ESCALATION_FAILURE_THRESHOLD && cloudProvider.isAvailable()) {
            log.info("Escalating to cloud provider after {} consecutive failures", failures);
            return cloudProvider;
        }

        // Default: use local
        return localProvider;
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
