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
     * <p>
     * Applies smart compression to reduce token usage on cloud LLM:
     * <ul>
     *   <li>Last 2 turns: full output (LLM needs recent context for next decision)</li>
     *   <li>Older turns: output truncated to {@code maxOlderOutputChars} chars</li>
     *   <li>Consecutive _thinking failures: collapsed into a single summary line</li>
     *   <li>Reasoning on older turns: truncated to 200 chars</li>
     * </ul>
     */
    public String toPromptSummary() {
        return toPromptSummary(500);
    }

    /**
     * Configurable version for testing/tuning the truncation threshold.
     */
    public String toPromptSummary(int maxOlderOutputChars) {
        if (turns.isEmpty()) return "";

        var sb = new StringBuilder();
        int fullDetailFrom = Math.max(0, turns.size() - 2); // last 2 turns get full output

        // Collapse consecutive _thinking failures into a count
        int thinkingFailStreak = 0;

        for (int i = 0; i < turns.size(); i++) {
            var turn = turns.get(i);
            boolean isThinkingFail = !turn.observation().success()
                    && "_thinking".equals(turn.observation().tool());

            // Collapse _thinking failures
            if (isThinkingFail && i < fullDetailFrom) {
                thinkingFailStreak++;
                continue;
            }

            // Flush any accumulated _thinking failures before this step
            if (thinkingFailStreak > 0) {
                sb.append("[Steps ").append(i - thinkingFailStreak + 1).append("-").append(i)
                        .append("] ").append(thinkingFailStreak)
                        .append(" thinking failures (JSON parse errors) — skipped\n\n");
                thinkingFailStreak = 0;
            }

            boolean isFull = i >= fullDetailFrom;

            sb.append("[Step ").append(i + 1).append("] ");
            sb.append("Tool: ").append(turn.action().tool());
            sb.append(" | Status: ").append(turn.observation().success() ? "OK" : "FAILED");
            sb.append(" | Duration: ").append(turn.observation().durationMs()).append("ms");

            String reasoning = turn.action().reasoning();
            if (reasoning != null && !reasoning.isBlank()) {
                if (isFull || reasoning.length() <= 200) {
                    sb.append("\nReasoning: ").append(reasoning);
                } else {
                    sb.append("\nReasoning: ").append(reasoning, 0, 200).append("...");
                }
            }

            String output = turn.observation().output();
            if (output != null && !output.isBlank()) {
                if (isFull) {
                    sb.append("\nOutput: ").append(output);
                } else if (output.length() <= maxOlderOutputChars) {
                    sb.append("\nOutput: ").append(output);
                } else {
                    sb.append("\nOutput (truncated): ").append(output, 0, maxOlderOutputChars)
                            .append("... [").append(output.length()).append(" chars total]");
                }
            }
            sb.append("\n\n");
        }

        // Flush trailing _thinking failures
        if (thinkingFailStreak > 0) {
            int from = turns.size() - thinkingFailStreak + 1;
            sb.append("[Steps ").append(from).append("-").append(turns.size())
                    .append("] ").append(thinkingFailStreak)
                    .append(" thinking failures (JSON parse errors)\n\n");
        }

        return sb.toString();
    }
}
