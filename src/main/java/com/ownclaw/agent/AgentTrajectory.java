package com.ownclaw.agent;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The trajectory of an agent execution — an ordered sequence of (action, observation) pairs.
 * This is the agent's "working memory" for the current task.
 */
public class AgentTrajectory {

    private static final ObjectMapper JSON = new ObjectMapper();

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
         * The step's action as the model wrote it: its reasoning, the tool, and the arguments as
         * written -- a reference as {{N}}, never the bytes it resolves to -- as JSON. One
         * rendering for both renderers: the Anthropic replay's assistant turn, and the history
         * every other provider reads, which named only the tool, so a model there could not tell
         * whom it had written to or which page it had fetched.
         */
        public String actionText() {
            Map<String, Object> map = new LinkedHashMap<>();
            String reasoning = action.reasoning();
            if (reasoning != null && !reasoning.isBlank()) map.put("reasoning", reasoning);
            map.put("tool", action.tool());
            if (action.params() != null && !action.params().isEmpty()) map.put("params", action.params());
            try {
                return JSON.writeValueAsString(map);
            } catch (Exception e) {
                return "{\"tool\": \"" + action.tool() + "\"}";
            }
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
     * The steps the model took, in order: every turn but the ones the loop recorded itself
     * ({@link Turn#byTheLoop}). What looks back for a run of failed, empty or repeated steps
     * reads these, so a reflection the loop injects between two failures neither adds to the run
     * nor ends it.
     */
    public List<Turn> modelSteps() {
        return turns.stream().filter(t -> !t.byTheLoop()).toList();
    }

    /**
     * Count how many of the model's steps at the tail ({@link #modelSteps}) failed in a row.
     */
    public int consecutiveFailures() {
        var steps = modelSteps();
        int count = 0;
        for (int i = steps.size() - 1; i >= 0; i--) {
            if (!steps.get(i).observation().success()) {
                count++;
            } else {
                break;
            }
        }
        return count;
    }

    /**
     * Count consecutive "hollow" results among the model's steps at the tail
     * ({@link #modelSteps}) — tool calls that technically succeeded but produced empty or
     * trivially short output, suggesting the tool is broken or returning nothing useful.
     */
    public int consecutiveHollowResults() {
        var steps = modelSteps();
        int count = 0;
        for (int i = steps.size() - 1; i >= 0; i--) {
            var obs = steps.get(i).observation();
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
     * The trajectory as the prompt of a provider without multi-turn replay reads it: every step
     * in order, each whole -- the action as the Anthropic replay shows it ({@link Turn#actionText}),
     * then the result as {@link Turn#observationText} renders it. A step the loop took itself
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
            sb.append(turn.actionText()).append('\n').append(turn.observationText()).append("\n\n");
        }
        return sb.toString();
    }
}
