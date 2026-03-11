package com.ownclaw.agent;

import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.llm.LlmProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * Routes LLM requests to the appropriate provider based on context.
 *
 * <p>Architecture: Cloud-as-orchestrator, local-as-executor.
 * <ul>
 *   <li>The main agent loop ALWAYS uses the cloud provider for reasoning/planning</li>
 *   <li>The local provider is used exclusively by {@link LocalExecutor} for
 *       delegated tool execution</li>
 *   <li>If cloud is unavailable, falls back to local in degraded mode</li>
 * </ul>
 * Skill code generation always uses cloud (handled separately in AgentLoop).
 *
 * <p>Cloud provider is selected based on {@code ownclaw.mentor.provider}:
 * openai (default) or anthropic. The active cloud provider is resolved at startup
 * and whenever the config changes (e.g. via /setup wizard).
 */
@Component
public class LlmRouter {

    private static final Logger log = LoggerFactory.getLogger(LlmRouter.class);

    private final LlmProvider localProvider;
    private final LlmProvider openAiProvider;
    private final LlmProvider anthropicProvider;
    private final OwnClawConfig config;

    /** The currently active cloud provider (resolved from config). */
    private volatile LlmProvider cloudProvider;

    public LlmRouter(
            @Qualifier("ollamaProvider") LlmProvider localProvider,
            @Qualifier("openAiProvider") LlmProvider openAiProvider,
            @Qualifier("anthropicProvider") LlmProvider anthropicProvider,
            OwnClawConfig config
    ) {
        this.localProvider = localProvider;
        this.openAiProvider = openAiProvider;
        this.anthropicProvider = anthropicProvider;
        this.config = config;
    }

    @PostConstruct
    void init() {
        resolveCloudProvider();
    }

    /**
     * Re-resolve the active cloud provider from config.
     * Called at startup and after /setup wizard changes the provider setting.
     */
    public void resolveCloudProvider() {
        String provider = config.getMentor().getProvider();
        if ("anthropic".equalsIgnoreCase(provider)) {
            this.cloudProvider = anthropicProvider;
            log.info("Cloud LLM provider: Anthropic (model: {})", config.getMentor().getAnthropicModel());
        } else {
            this.cloudProvider = openAiProvider;
            log.info("Cloud LLM provider: OpenAI (model: {})", config.getMentor().getModel());
        }
    }

    /**
     * Select the best provider for the current reasoning step.
     *
     * <p>Cloud-as-orchestrator: the main agent loop always uses cloud for reasoning.
     * Local is only used by {@link LocalExecutor} for delegated tool execution.
     * Falls back to local only when cloud is completely unavailable (degraded mode).
     */
    public LlmProvider selectProvider(AgentContext context) {
        // Cloud always orchestrates the main agent loop
        if (cloudProvider.isAvailable()) {
            int step = (context.trajectory() != null && !context.trajectory().isEmpty())
                    ? context.trajectory().size() + 1 : 1;
            log.debug("Step {}: using cloud provider (orchestrator)", step);
            return cloudProvider;
        }

        // Fallback: cloud unavailable — degraded mode with local
        if (localProvider.isAvailable()) {
            log.warn("Cloud unavailable — falling back to local provider (degraded mode)");
            return localProvider;
        }

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

    /**
     * Check if a provider is the local (Ollama) provider.
     * Used for token tracking and routing decisions.
     */
    public boolean isLocal(LlmProvider provider) {
        return provider == localProvider;
    }
}
