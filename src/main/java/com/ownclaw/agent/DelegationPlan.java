package com.ownclaw.agent;

import java.util.List;
import java.util.Map;

/**
 * A structured delegation plan created by the cloud LLM for execution by the local LLM.
 *
 * <p>The cloud orchestrator creates this plan with a goal and, when it knows them, the tool
 * calls, their order and quality checkpoints. The {@link LocalExecutor} then runs this plan
 * using the local LLM to execute tools and chain results, until the local model says it is done.
 *
 * @param goal        what the delegation should achieve (natural language)
 * @param steps       ordered list of tool calls to execute
 * @param checkpoints quality criteria to verify before marking as done
 * @param tools       the tools the cloud says this delegation needs (see LocalExecutor.offered)
 */
public record DelegationPlan(
        String goal,
        List<Step> steps,
        List<String> checkpoints,
        List<String> tools
) {
    public DelegationPlan {
        tools = tools == null ? List.of() : List.copyOf(tools);
    }

    public DelegationPlan(String goal, List<Step> steps, List<String> checkpoints) {
        this(goal, steps, checkpoints, List.of());
    }

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
