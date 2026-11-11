package com.ownclaw.agent;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A structured delegation plan created by the cloud LLM for execution by the local LLM.
 *
 * <p>The cloud orchestrator creates this plan with a goal and, when it knows them, the tool
 * calls, their order and quality checkpoints. The {@link LocalExecutor} then runs this plan
 * using the local LLM to execute tools and chain results, until the local model says it is done.
 *
 * @param goal        what the delegation should achieve (natural language); blank when the
 *                    {@code calls} are all there is to do
 * @param steps       ordered list of tool calls to execute
 * @param checkpoints quality criteria to verify before marking as done
 * @param tools       the tools the cloud says this delegation needs (see LocalExecutor.offered)
 * @param texts       the exact texts the cloud hands the delegation by name, in its order: a
 *                    script, a configuration file, a message body. {@code {{name}}} in a tool
 *                    argument is replaced with the text when the call runs
 *                    ({@link References#resolveInText}), so the local model never retypes it
 * @param calls       exact tool calls the cloud wrote, run by code in their order before the
 *                    local model is given anything ({@link LocalExecutor}), so nothing of them is
 *                    retyped. In their arguments {@code {{1}}} is the first call's result, as in
 *                    the local model's own calls, and {@code {{name}}} a text
 */
public record DelegationPlan(
        String goal,
        List<Step> steps,
        List<String> checkpoints,
        List<String> tools,
        Map<String, String> texts,
        List<Call> calls
) {
    public DelegationPlan {
        tools = tools == null ? List.of() : List.copyOf(tools);
        texts = texts == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(texts));
        calls = calls == null ? List.of() : List.copyOf(calls);
    }

    public DelegationPlan(String goal, List<Step> steps, List<String> checkpoints, List<String> tools,
                          Map<String, String> texts) {
        this(goal, steps, checkpoints, tools, texts, List.of());
    }

    public DelegationPlan(String goal, List<Step> steps, List<String> checkpoints, List<String> tools) {
        this(goal, steps, checkpoints, tools, Map.of(), List.of());
    }

    public DelegationPlan(String goal, List<Step> steps, List<String> checkpoints) {
        this(goal, steps, checkpoints, List.of(), Map.of(), List.of());
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

    /**
     * One exact call: the tool and its arguments as the cloud wrote them.
     *
     * @param tool   the tool's exact name
     * @param params its arguments as written: references and text handles are filled in when it runs
     */
    public record Call(String tool, Map<String, Object> params) {
        public Call {
            params = params == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(params));
        }
    }
}
