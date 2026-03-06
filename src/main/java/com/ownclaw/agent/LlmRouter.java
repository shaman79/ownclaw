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
 * Strategy: cloud-first for user intent analysis, local for follow-up steps.
 * - Step 1 (empty trajectory): Cloud — needs best reasoning for user intent
 * - Step 2+ (has trajectory): Local — simpler follow-up after tool results
 *   EXCEPT: if last step failed, escalate back to cloud for recovery reasoning
 * - Skill code generation always uses cloud (handled separately in AgentLoop)
 * - local_llm tool provides explicit delegation for subtask offloading
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
     *
     * <p>Step 1 uses cloud for reliable user intent understanding.
     * Steps 2+ use local (cheaper, faster) unless the last step failed,
     * in which case cloud is used for stronger recovery reasoning.
     */
    public LlmProvider selectProvider(AgentContext context) {
        boolean hasTrajectory = context.trajectory() != null && !context.trajectory().isEmpty();

        // Step 1: always cloud for intent analysis
        if (!hasTrajectory) {
            if (cloudProvider.isAvailable()) {
                log.debug("Step 1: using cloud provider for user intent analysis");
                return cloudProvider;
            }
            if (localProvider.isAvailable()) {
                log.warn("Step 1: cloud unavailable, falling back to local");
                return localProvider;
            }
            log.error("No LLM providers available!");
            return cloudProvider;
        }

        // Step 2+: prefer local, but escalate to cloud on failure recovery
        var lastTurn = context.trajectory().lastTurn();
        boolean lastFailed = lastTurn != null && lastTurn.observation() != null
                && !lastTurn.observation().success();

        if (lastFailed) {
            if (cloudProvider.isAvailable()) {
                log.info("Step {}: last step failed — escalating to cloud for recovery",
                        context.trajectory().size() + 1);
                return cloudProvider;
            }
        }

        // Normal step 2+: use local
        if (localProvider.isAvailable()) {
            log.debug("Step {}: using local provider for follow-up reasoning",
                    context.trajectory().size() + 1);
            return localProvider;
        }

        // Local unavailable, fall back to cloud
        if (cloudProvider.isAvailable()) {
            log.debug("Step {}: local unavailable, using cloud", context.trajectory().size() + 1);
            return cloudProvider;
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
}
