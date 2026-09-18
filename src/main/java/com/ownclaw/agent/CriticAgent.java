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

    /**
     * Jaccard threshold above which a proposed skill name is treated as a duplicate of an
     * existing tool. Calibrated against the real production library: at 0.6,
     * {@code imap_move_to_bin_by_sender_gmail} vs {@code imap_move_to_trash_by_sender} blocks
     * (0.63) while {@code imap_list_mailboxes} vs {@code imap_unread_summarizer} does not
     * (0.20) — those are genuinely different capabilities that happen to share a prefix word.
     */
    private static final double NAME_OVERLAP_BLOCK = 0.6;

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

        // 1. Response/ask are always allowed — they end or pause the task, they cannot loop.
        //
        // skill_create used to be in this list. It should not be: it is the one action that
        // permanently changes the system's capability surface, and exempting it from every check
        // while throttling skill_manage (below) created a pronounced bias toward writing a new
        // skill rather than looking at what already exists. In production that produced 31
        // generated skills including eight for IMAP and a web_search_bikes, and the block text on
        // skill_manage literally instructed the model to stop looking and start creating.
        if (action.isResponse() || action.isAskUser()) {
            return Verdict.allow(warnings);
        }

        // 1b. skill_manage gets loop detection — prevent endless list/analyze cycles
        if (action.isSkillManage()) {
            AgentTrajectory trajectory = context.trajectory();
            int identicalCount = countIdenticalTrailingActions(trajectory, action);
            if (identicalCount >= 2) {
                // Loop detection is right; the old advice was not. Repeating an identical
                // inventory call is pointless, but "so create a new skill instead" is the
                // instruction that produced eight IMAP skills. Point at the result already in
                // the trajectory instead.
                return Verdict.block("You have called skill_manage with the same parameters " +
                        identicalCount + " times in a row and the inventory has not changed. " +
                        "The result of the earlier call is already in your context — use it. " +
                        "If an existing tool fits, call it. Only create a new skill if you have " +
                        "checked the inventory and nothing covers this capability.");
            }
            // Also detect any excessive skill_manage calls (different params but same tool)
            long totalManage = trajectory.toolInvocationCount("skill_manage");
            if (totalManage >= 4) {
                return Verdict.block("You have called skill_manage " + totalManage +
                        " times. Stop inspecting and act on what you already know: call an " +
                        "existing tool, combine several, or — if nothing covers this capability " +
                        "— create one new general skill that takes the specifics as parameters.");
            }
            return Verdict.allow(warnings);
        }

        // 1c. skill_create is dispatched directly by AgentLoop (:564) and is NOT a registered
        // Tool, so it must not fall through to the registry and schema checks below — those
        // would block it outright. It gets the checks that actually apply to it.
        if (action.isSkillCreate()) {
            return evaluateSkillCreate(action, context, warnings);
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

        // 9. Wasted-effort detection — tiered response.
        //    Moderate waste: warn + redirect to different strategy (agent can still act).
        //    Extreme waste: hard block — force wrap-up.
        int blockedOrFailed = 0;
        for (var turn : trajectory.turns()) {
            if (!turn.observation().success()) blockedOrFailed++;
        }
        int totalSteps = trajectory.size();
        if (totalSteps >= 12 && blockedOrFailed * 4 > totalSteps * 3) {
            // 75%+ failures at 12+ steps — nothing is working, force wrap-up
            return Verdict.block(blockedOrFailed + " of " + totalSteps
                    + " steps have failed. Respond now with what you've accomplished "
                    + "and what went wrong.");
        }
        if (totalSteps >= 8 && blockedOrFailed * 2 > totalSteps) {
            // 50%+ failures at 8+ steps — strong redirect, not a block
            warnings.add("STRATEGY WARNING: " + blockedOrFailed + " of " + totalSteps
                    + " steps have failed. Your current approach is not working. "
                    + "CHANGE STRATEGY: search the internet for solutions, try a completely "
                    + "different technique, or simplify the approach. Do NOT repeat what already failed.");
        }

        return Verdict.allow(warnings);
    }

    /**
     * The compose-before-create gate.
     * <p>
     * Creating a skill permanently changes the capability surface, and until now it was the one
     * action exempt from every check. The production library is what that produced: 31 generated
     * skills, among them eight for IMAP — three near-identical move-to-trash-by-sender variants —
     * two left-over {@code _debug} artifacts, four overlapping network scanners, and a
     * {@code web_search_bikes}.
     * <p>
     * Both checks are deterministic string comparisons against the live registry. No model and no
     * embeddings: the library is tens of items rather than thousands, and a gate that itself
     * needed a 60-133 s local call would not be affordable on an attended turn.
     * <ul>
     *   <li><b>Prefix sibling</b> — the proposed name begins with an existing tool's name followed
     *       by {@code _}. That is the signature of a narrowing ({@code web_search_bikes} over a
     *       general web search) or of a failed repair escaping under a new name
     *       ({@code imap_move_to_trash_by_sender_imaplib}, {@code imap_list_mailboxes_debug}).</li>
     *   <li><b>Token overlap</b> — Jaccard similarity over underscore-separated tokens, catching
     *       siblings that share no prefix, such as {@code imap_move_to_bin_by_sender_gmail}
     *       against {@code imap_move_to_trash_by_sender}.</li>
     * </ul>
     * A block is never a dead end. The message names the specific tool believed to cover the case,
     * and {@code force: true} overrides it — "capable of anything I ask" means a genuinely new
     * capability must stay reachable, and a gate with no escape hatch is a worse failure than the
     * bloat it prevents. A forced creation is recorded as a warning rather than passing silently.
     */
    private Verdict evaluateSkillCreate(AgentAction action, AgentContext context,
                                        List<String> warnings) {
        Map<String, Object> params = action.params();
        Object rawName = params == null ? null : params.get("name");
        String proposed = rawName == null ? "" : rawName.toString().trim().toLowerCase();

        Object force = params == null ? null : params.get("force");
        boolean forced = force != null && Boolean.parseBoolean(force.toString());

        if (!proposed.isEmpty() && !forced) {
            // First pass: is this a GENERALISATION of something that already exists? If an
            // existing tool's name extends the proposed one — web_search_bikes when web_search
            // is proposed — then the proposal is the broader capability, and creating it is the
            // consolidation we want. Checking this first matters: the narrow sibling would
            // otherwise block its own replacement by token overlap (web_search vs
            // web_search_bikes scores 0.67), leaving the library permanently stuck with the
            // specific version and no way to create the general one.
            List<String> superseded = new ArrayList<>();
            for (String existing : toolRegistry.names()) {
                if (existing.toLowerCase().startsWith(proposed + "_")) superseded.add(existing);
            }
            if (!superseded.isEmpty()) {
                warnings.add("'" + proposed + "' generalises " + String.join(", ", superseded)
                        + " — retire the narrower skill(s) once this works");
                log.info("skill_create '{}' generalises existing narrow skill(s): {}",
                        proposed, superseded);
                return Verdict.allow(warnings);
            }

            for (String existing : toolRegistry.names()) {
                String e = existing.toLowerCase();
                // Re-creating the same name is a repair/overwrite, which is the behaviour we
                // actually want instead of a sibling. Never block it here.
                if (proposed.equals(e)) continue;
                if (proposed.startsWith(e + "_")) {
                    return Verdict.block("'" + proposed + "' is a narrower version of the existing "
                            + "tool '" + existing + "'. Call '" + existing + "' and pass the "
                            + "specifics as parameters instead. If '" + existing + "' genuinely "
                            + "cannot do this, either extend it under its own name, or retry with "
                            + "force=true and state what is missing.");
                }
                if (tokenOverlap(proposed, e) >= NAME_OVERLAP_BLOCK) {
                    return Verdict.block("'" + proposed + "' looks like a duplicate of the existing "
                            + "tool '" + existing + "'. Use '" + existing + "', or extend it to "
                            + "cover this case under its own name. If it is genuinely a different "
                            + "capability, retry with force=true and say how it differs.");
                }
            }
        }
        if (forced) {
            warnings.add("skill_create forced past the duplicate check for '" + proposed + "'");
            log.warn("skill_create for '{}' forced past the duplicate check", proposed);
        }

        // Repeating an identical creation is a loop whatever it is called.
        int identical = countIdenticalTrailingActions(context.trajectory(), action);
        if (identical >= MAX_IDENTICAL_CONSECUTIVE) {
            return Verdict.block("You have tried to create '" + proposed + "' with identical "
                    + "parameters " + identical + " times. Change the approach or ask the user.");
        }
        return Verdict.allow(warnings);
    }

    /** Jaccard similarity over underscore-separated name tokens. */
    static double tokenOverlap(String a, String b) {
        java.util.Set<String> ta = new java.util.HashSet<>(java.util.Arrays.asList(a.split("_")));
        java.util.Set<String> tb = new java.util.HashSet<>(java.util.Arrays.asList(b.split("_")));
        ta.remove(""); tb.remove("");
        if (ta.isEmpty() || tb.isEmpty()) return 0;
        java.util.Set<String> union = new java.util.HashSet<>(ta); union.addAll(tb);
        java.util.Set<String> inter = new java.util.HashSet<>(ta); inter.retainAll(tb);
        return (double) inter.size() / union.size();
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
