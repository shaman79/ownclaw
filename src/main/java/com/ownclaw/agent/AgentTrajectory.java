package com.ownclaw.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The trajectory of an agent execution — an ordered sequence of (action, observation) pairs.
 * This is the agent's "working memory" for the current task.
 */
public class AgentTrajectory {

    public record Turn(AgentAction action, AgentObservation observation) {
        /**
         * Whether the loop recorded this step itself rather than the model taking it: a reply
         * that produced nothing to run ({@code _thinking}) or a reflection the loop injected
         * ({@code _reflection}). The model did not write such a step, so no prompt shows it as
         * the model's -- only what the model was told. No tool the model can run starts with an
         * underscore (skill names begin with a letter; the special actions are words), so these
         * steps are the ones whose tool does.
         * <p>
         * One step the loop takes is not among them: the skill_create it runs at step 1 when
         * CapabilityResolver finds a missing capability. It ran as that tool and is recorded
         * under its name, so it is replayed like an action of the model's, with the resolver's
         * spec as its arguments and a sentence about the resolver as its reasoning -- the
         * resolver's own constants, never a result.
         */
        public boolean byTheLoop() {
            return action != null && action.tool() != null && action.tool().startsWith("_");
        }

        /**
         * The step's result as the think prompts show it: a line naming the tool, how it went and
         * how long it took, then the output whole on the lines after it -- for a PRIVATE result,
         * the description it was recorded as. One rendering for both renderers, so an output has
         * the same frame on either: a ')' and a line break before it, the break being whitespace
         * the canary does not count ({@code PrivateIndex.firstLeakIn}).
         */
        public String observationText() {
            var sb = new StringBuilder();
            sb.append('[').append(action.tool()).append("] ")
              .append(observation.success() ? "OK" : "FAILED")
              .append(" (").append(observation.durationMs()).append("ms)\n");
            String output = observation.output();
            if (output != null && !output.isBlank()) sb.append(output);
            return sb.toString();
        }
    }

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
     * The trajectory as the prompt of a provider without multi-turn replay reads it: every step
     * in order, each whole -- the tool, the model's reasoning, then the result as
     * {@link Turn#observationText} renders it. A step the loop took itself
     * ({@link Turn#byTheLoop}) is what the model was told about it.
     */
    public String toPromptSummary() {
        var sb = new StringBuilder();
        for (int i = 0; i < turns.size(); i++) {
            var turn = turns.get(i);
            sb.append("[Step ").append(i + 1).append("] ");
            if (turn.byTheLoop()) {
                String told = turn.observation().output();
                sb.append(told == null ? "" : told).append("\n\n");
                continue;
            }
            sb.append("Tool: ").append(turn.action().tool()).append('\n');
            String reasoning = turn.action().reasoning();
            if (reasoning != null && !reasoning.isBlank()) {
                sb.append("Reasoning: ").append(reasoning).append('\n');
            }
            sb.append(turn.observationText()).append("\n\n");
        }
        return sb.toString();
    }
}
