package com.ownclaw.agent;

import java.util.List;
import java.util.Map;

/**
 * A structured delegation plan created by the cloud LLM for execution by the local LLM.
 *
 * <p>The cloud orchestrator creates this plan with specific tool calls, their order,
 * quality checkpoints, and a step budget. The {@link LocalExecutor} then runs this plan
 * using the local LLM to execute tools and chain results.
 *
 * @param goal        what the delegation should achieve (natural language)
 * @param steps       ordered list of tool calls to execute
 * @param checkpoints quality criteria to verify before marking as done
 * @param maxSteps    maximum number of executor steps (tool calls + retries)
 */
public record DelegationPlan(
        String goal,
        List<Step> steps,
        List<String> checkpoints,
        int maxSteps
) {
    /**
     * A single step in the delegation plan.
     *
     * @param description  human-readable description of what this step does
     * @param tool         the tool to invoke
     * @param params       the parameters for the tool call
     */
    public record Step(
            String description,
            String tool,
            Map<String, Object> params
    ) {}
}
