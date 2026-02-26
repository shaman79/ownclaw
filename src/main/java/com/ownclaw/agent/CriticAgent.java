package com.ownclaw.agent;

import com.ownclaw.agent.tools.Tool;
import com.ownclaw.agent.tools.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The CriticAgent performs fast, rule-based evaluation of proposed actions
 * BEFORE they are executed. It acts as a safety net and quality gate.
 *
 * Unlike the ThinkingEngine (which uses LLM reasoning), the critic uses
 * deterministic heuristics for speed. This keeps the agent loop fast while
 * catching obvious issues.
 *
 * The critic can:
 * - Block invalid tool references
 * - Detect infinite loops (repeated identical actions)
 * - Enforce safety limits (max consecutive failures, budget)
 * - Validate required parameters
 * - Flag high-risk actions for logging
 */
@Component
public class CriticAgent {

    private static final Logger log = LoggerFactory.getLogger(CriticAgent.class);

    /** Maximum times the same tool+params can be invoked consecutively. */
    private static final int MAX_IDENTICAL_CONSECUTIVE = 3;

    /** Maximum consecutive failures before the critic recommends stopping. */
    private static final int MAX_CONSECUTIVE_FAILURES = 5;

    private final ToolRegistry toolRegistry;

    public CriticAgent(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    /**
     * Evaluate a proposed action and return a verdict.
     *
     * @param action  the proposed action from the ThinkingEngine
     * @param context the current agent context
     * @return the critic's verdict
     */
    public Verdict evaluate(AgentAction action, AgentContext context) {
        List<String> warnings = new ArrayList<>();

        // 1. Special actions are always allowed
        if (action.isSpecialAction()) {
            return Verdict.allow(warnings);
        }

        // 2. Check tool exists
        String toolName = action.tool();
        var toolOpt = toolRegistry.find(toolName);
        if (toolOpt.isEmpty()) {
            return Verdict.block("Tool '" + toolName + "' does not exist. Available tools: " +
                    String.join(", ", toolRegistry.names()));
        }

        Tool tool = toolOpt.get();

        // 3. Validate required parameters
        var schema = tool.inputSchema();
        if (schema != null) {
            for (var entry : schema.entrySet()) {
                if (entry.getValue().required()) {
                    Object value = action.params().get(entry.getKey());
                    if (value == null || (value instanceof String s && s.isBlank())) {
                        return Verdict.block("Required parameter '" + entry.getKey() +
                                "' is missing for tool '" + toolName + "'");
                    }
                }
            }
        }

        // 4. Detect repeated identical actions (loop detection)
        AgentTrajectory trajectory = context.trajectory();
        int identicalCount = countIdenticalTrailingActions(trajectory, action);
        if (identicalCount >= MAX_IDENTICAL_CONSECUTIVE) {
            return Verdict.block("Action '" + toolName +
                    "' with the same parameters has been attempted " + identicalCount +
                    " times consecutively. Try a different approach.");
        }

        // 5. Check consecutive failure limit
        int failures = trajectory.consecutiveFailures();
        if (failures >= MAX_CONSECUTIVE_FAILURES) {
            return Verdict.block("There have been " + failures +
                    " consecutive failures. Consider responding with what you've learned so far.");
        }

        // 6. Flag side-effect tools
        if (tool.hasSideEffects()) {
            warnings.add("Tool '" + toolName + "' has side effects.");
        }

        // 7. Check excessive tool reuse
        long totalUses = trajectory.toolInvocationCount(toolName);
        if (totalUses > 10) {
            warnings.add("Tool '" + toolName + "' has been used " + totalUses + " times in this task.");
        }

        // 8. Check if task is taking too long (step count)
        if (trajectory.size() > 15) {
            warnings.add("Task has taken " + trajectory.size() + " steps. Consider wrapping up.");
        }

        return Verdict.allow(warnings);
    }

    /**
     * Count how many identical actions (same tool + same params) trail the trajectory.
     */
    private int countIdenticalTrailingActions(AgentTrajectory trajectory, AgentAction proposed) {
        int count = 0;
        var turns = trajectory.turns();
        for (int i = turns.size() - 1; i >= 0; i--) {
            AgentAction past = turns.get(i).action();
            if (past.tool().equals(proposed.tool()) && paramsEqual(past.params(), proposed.params())) {
                count++;
            } else {
                break;
            }
        }
        return count;
    }

    private boolean paramsEqual(Map<String, Object> a, Map<String, Object> b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.equals(b);
    }

    /**
     * Verdict from the critic.
     */
    public record Verdict(
            boolean allowed,
            String blockReason,
            List<String> warnings
    ) {
        public static Verdict allow(List<String> warnings) {
            return new Verdict(true, null, warnings);
        }

        public static Verdict block(String reason) {
            return new Verdict(false, reason, List.of());
        }

        public boolean hasWarnings() {
            return warnings != null && !warnings.isEmpty();
        }
    }
}
