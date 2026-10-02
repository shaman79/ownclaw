package com.ownclaw.agent;

import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.llm.CloudGateway;
import com.ownclaw.llm.LlmProvider;
import com.ownclaw.llm.LocalModelCheck;
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
 *   <li>The main agent loop uses the cloud provider for reasoning/planning</li>
 *   <li>The local provider runs {@link LocalExecutor}'s delegated tool execution</li>
 *   <li>The local model runs the whole task instead -- best effort -- when the owner's
 *       local-only switch is on, when no cloud is configured, or when the task's cloud
 *       model could not be reached ({@link AgentContext#onLocal})</li>
 * </ul>
 * Every model call of a task chooses through {@link #selectProvider}: each step, the skill code
 * it writes, the analysis of the skill library.
 *
 * <p>Cloud provider is selected based on {@code ownclaw.mentor.provider}:
 * openai (default) or anthropic. The active cloud provider is resolved at startup
 * and whenever the config changes (e.g. via /setup wizard).
 */
@Component
public class LlmRouter {

    private static final Logger log = LoggerFactory.getLogger(LlmRouter.class);

    private final LlmProvider localProvider;
    /**
     * The one door to a cloud model. Not a provider chosen here: the gateway reads the
     * configured provider on every call, so the setup wizard and the settings page changing it
     * take effect without this class holding a stale reference.
     */
    private final CloudGateway cloudProvider;
    private final OwnClawConfig config;
    private final LocalModelCheck localModelCheck;

    public LlmRouter(
            @Qualifier("ollamaProvider") LlmProvider localProvider,
            CloudGateway cloud,
            OwnClawConfig config,
            LocalModelCheck localModelCheck
    ) {
        this.localProvider = localProvider;
        this.cloudProvider = cloud;
        this.config = config;
        this.localModelCheck = localModelCheck;
    }

    @PostConstruct
    void init() {
        resolveCloudProvider();
    }

    /**
     * Say which cloud provider is configured. It used to swap a field; the gateway now reads the
     * configuration on every call, so the callers that still invoke this after the wizard or the
     * settings page change the provider get the log line and nothing else needs to happen.
     */
    public void resolveCloudProvider() {
        String provider = config.getMentor().getProvider();
        if ("anthropic".equalsIgnoreCase(provider)) {
            log.info("Cloud LLM provider: Anthropic (model: {})", config.getMentor().getAnthropicModel());
        } else {
            log.info("Cloud LLM provider: OpenAI (model: {})", config.getMentor().getModel());
        }
    }

    /**
     * The model for a call of this task: the local model when the owner's switch is on or the
     * task has gone local, even when it is not reachable either -- the call then fails saying so;
     * otherwise the cloud, or the local model when no cloud is configured.
     */
    public LlmProvider selectProvider(AgentContext context) {
        if (localOnly() || context.onLocal()) return localProvider;
        if (cloudProvider.isAvailable()) return cloudProvider;

        // No cloud configured: the local model, in degraded mode
        if (localProvider.isAvailable()) {
            log.warn("No cloud model configured — using the local provider (degraded mode)");
            return localProvider;
        }

        log.error("No LLM providers available!");
        return cloudProvider;
    }

    /** The owner's local-only switch ({@link com.ownclaw.config.LocalMode}). */
    public boolean localOnly() {
        return config.getMentor().isLocalOnly();
    }

    /**
     * Whether a task whose cloud model cannot be reached can go on on the local model: the
     * server answers. Whether it can drive the task is found out by trying.
     */
    public boolean canGoLocal() {
        return localProvider.isAvailable();
    }

    /**
     * Whether the local tier is genuinely usable right now — configured model installed and
     * drivable through /api/chat, not merely a server that answers /api/tags.
     * <p>
     * Reachability was never the question: through the months the local tier was broken, Ollama
     * answered, the model was installed, and every reply was unrelated because the server could
     * not render a chat template for that architecture.
     */
    public LocalModelCheck.LocalStatus localStatus() {
        return localModelCheck.status();
    }

    /** The local provider directly (for non-critical, high-volume operations). */
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
