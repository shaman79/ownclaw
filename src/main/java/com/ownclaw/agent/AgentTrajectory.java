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
        return toPromptSummary(300);
    }

    /**
     * How much of the prompt may be spent on tool output kept in full.
     * <p>
     * Roughly 15k tokens. Big enough to hold several pages at once, small enough that it cannot
     * be the thing that overflows a context window on its own.
     */
    static final int FULL_OUTPUT_BUDGET_CHARS = 60_000;

    /**
     * The oldest turn that still gets its output in full, deciding by BUDGET rather than count.
     * <p>
     * This used to be {@code turns.size() - 2}: the last two turns in full, everything older
     * crushed to 150 characters of head and 150 of tail. That makes a whole class of task
     * impossible rather than merely lossy -- "read these three pages and compare them" cannot
     * work, because by the time the third arrives the first is a 300-character stub, and the
     * model is left comparing summaries it was never given. It was also wasteful in the other
     * direction: two turns of a 200 KB page each are re-sent in full on every step.
     * <p>
     * Walking newest-first and spending a budget fixes both ends. Three pages of 10k fit; twenty
     * do not, and the oldest are the ones that get stubbed -- which is the right order to lose
     * them in, because the newest output is what the current step is reasoning about.
     * <p>
     * At least one turn is always kept in full, however large: a model that cannot see the
     * result of the step it just took cannot take the next one.
     */
    static int firstTurnKeptInFull(List<Turn> turns, int budgetChars) {
        int spent = 0;
        int first = turns.size() - 1;
        for (int i = turns.size() - 1; i >= 0; i--) {
            var obs = turns.get(i).observation();
            int cost = obs == null || obs.output() == null ? 0 : obs.output().length();
            // The newest turn is kept whatever it costs; after that, stop at the budget.
            if (i < turns.size() - 1 && spent + cost > budgetChars) break;
            spent += cost;
            first = i;
        }
        return first;
    }

    /**
     * Configurable version for testing/tuning the truncation threshold.
     */
    public String toPromptSummary(int maxOlderOutputChars) {
        if (turns.isEmpty()) return "";

        var sb = new StringBuilder();
        int fullDetailFrom = firstTurnKeptInFull(turns, FULL_OUTPUT_BUDGET_CHARS);

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
                    // Smart truncation: keep head + tail
                    int half = maxOlderOutputChars / 2;
                    sb.append("\nOutput: ").append(output, 0, half)
                            .append("\n...[" ).append(output.length()).append(" chars, middle omitted]...\n")
                            .append(output, output.length() - half, output.length());
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
