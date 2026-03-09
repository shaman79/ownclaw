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
 * Strategy — smart task routing with circuit breaker:
 * <ul>
 *   <li>Step 1 (empty trajectory): Cloud — full user intent analysis</li>
 *   <li>Step 2+ (has trajectory): Local — cheaper follow-up reasoning</li>
 *   <li>Circuit breaker: 2+ consecutive failures → cloud for rest of task</li>
 *   <li>Thinking failures: 2+ JSON parse failures → cloud permanently (local can't cope)</li>
 *   <li>BLOCKED tool: tool doesn't exist → cloud (will need skill_create)</li>
 *   <li>Last step failed: cloud for recovery reasoning</li>
 * </ul>
 * Skill code generation always uses cloud (handled separately in AgentLoop).
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
     * Select the best provider for the current reasoning step.
     *
     * <p>Implements a smart routing strategy with circuit breaker to prevent
     * cascading failures when the local model struggles with complex JSON output.
     */
    public LlmProvider selectProvider(AgentContext context) {
        var trajectory = context.trajectory();
        boolean hasTrajectory = trajectory != null && !trajectory.isEmpty();

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

        int step = trajectory.size() + 1;

        // --- CIRCUIT BREAKER: consecutive failures ---
        // If 2+ consecutive failures, the local model is clearly struggling.
        // Switch to cloud permanently for this task.
        if (trajectory.consecutiveFailures() >= 2) {
            if (cloudProvider.isAvailable()) {
                log.info("Step {}: CIRCUIT BREAKER — {} consecutive failures, switching to cloud",
                        step, trajectory.consecutiveFailures());
                return cloudProvider;
            }
        }

        // --- THINKING FAILURES: local model can't produce valid JSON ---
        // Count _thinking failures (JSON parse errors from any LLM, but almost always local).
        // If 2+ have occurred in this task, the local model has proven it can't handle
        // this task's complexity. Stay on cloud.
        long thinkingFailures = trajectory.turns().stream()
                .filter(t -> !t.observation().success()
                        && "_thinking".equals(t.observation().tool()))
                .count();
        if (thinkingFailures >= 2) {
            if (cloudProvider.isAvailable()) {
                log.info("Step {}: {} thinking (JSON) failures in task, staying on cloud",
                        step, thinkingFailures);
                return cloudProvider;
            }
        }

        // --- BLOCKED TOOL: tool doesn't exist → next step will need skill_create ---
        var lastTurn = trajectory.lastTurn();
        if (lastTurn != null && lastTurn.observation() != null
                && !lastTurn.observation().success()
                && lastTurn.observation().output() != null
                && lastTurn.observation().output().contains("does not exist")) {
            if (cloudProvider.isAvailable()) {
                log.info("Step {}: blocked tool (doesn't exist) — using cloud for skill creation",
                        step);
                return cloudProvider;
            }
        }

        // --- LAST STEP FAILED: cloud for recovery ---
        if (lastTurn != null && lastTurn.observation() != null
                && !lastTurn.observation().success()) {
            if (cloudProvider.isAvailable()) {
                log.info("Step {}: last step failed — escalating to cloud for recovery", step);
                return cloudProvider;
            }
        }

        // Normal step 2+: use local
        if (localProvider.isAvailable()) {
            log.debug("Step {}: using local provider for follow-up reasoning", step);
            return localProvider;
        }

        // Local unavailable, fall back to cloud
        if (cloudProvider.isAvailable()) {
            log.debug("Step {}: local unavailable, using cloud", step);
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
