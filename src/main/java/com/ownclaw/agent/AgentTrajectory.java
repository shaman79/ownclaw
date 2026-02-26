package com.ownclaw.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The trajectory of an agent execution — an ordered sequence of (action, observation) pairs.
 * This is the agent's "working memory" for the current task.
 */
public class AgentTrajectory {

    public record Turn(AgentAction action, AgentObservation observation) {}

    private final List<Turn> turns = new ArrayList<>();

    public void record(AgentAction action, AgentObservation observation) {
        turns.add(new Turn(action, observation));
    }

    public List<Turn> turns() {
        return Collections.unmodifiableList(turns);
    }

    public int size() {
        return turns.size();
    }

    public boolean isEmpty() {
        return turns.isEmpty();
    }

    public Turn lastTurn() {
        if (turns.isEmpty()) return null;
        return turns.get(turns.size() - 1);
    }

    /**
     * Count how many consecutive failures have occurred at the tail of the trajectory.
     */
    public int consecutiveFailures() {
        int count = 0;
        for (int i = turns.size() - 1; i >= 0; i--) {
            if (!turns.get(i).observation().success()) {
                count++;
            } else {
                break;
            }
        }
        return count;
    }

    /**
     * Count consecutive "hollow" results at the tail — tool calls that technically
     * succeeded but produced empty or trivially short output, suggesting the tool
     * is broken or returning nothing useful.
     */
    public int consecutiveHollowResults() {
        int count = 0;
        for (int i = turns.size() - 1; i >= 0; i--) {
            var obs = turns.get(i).observation();
            String out = obs.output();
            if (obs.success() && (out == null || out.isBlank() || out.length() < 10)) {
                count++;
            } else {
                break;
            }
        }
        return count;
    }

    /**
     * Count how many times a specific tool has been invoked.
     */
    public long toolInvocationCount(String toolName) {
        return turns.stream()
                .filter(t -> t.action().tool().equals(toolName))
                .count();
    }

    /**
     * Total tokens consumed (estimated from output lengths).
     */
    public int estimatedObservationTokens() {
        return turns.stream()
                .mapToInt(t -> t.observation().output() != null ? t.observation().output().length() / 4 : 0)
                .sum();
    }

    /**
     * Build a textual representation of the trajectory for LLM context.
     * No truncation — local models have no token cost, so the agent sees everything.
     */
    public String toPromptSummary() {
        if (turns.isEmpty()) return "";

        var sb = new StringBuilder();
        for (int i = 0; i < turns.size(); i++) {
            var turn = turns.get(i);
            sb.append("[Step ").append(i + 1).append("] ");
            sb.append("Tool: ").append(turn.action().tool());
            sb.append(" | Status: ").append(turn.observation().success() ? "OK" : "FAILED");
            sb.append(" | Duration: ").append(turn.observation().durationMs()).append("ms");

            String reasoning = turn.action().reasoning();
            if (reasoning != null && !reasoning.isBlank()) {
                sb.append("\nReasoning: ").append(reasoning);
            }

            String output = turn.observation().output();
            if (output != null && !output.isBlank()) {
                sb.append("\nOutput: ").append(output);
            }
            sb.append("\n\n");
        }

        return sb.toString();
    }
}
