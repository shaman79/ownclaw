package com.ownclaw.agent;

import com.ownclaw.agent.tools.Tool;
import com.ownclaw.agent.tools.ToolParam;
import com.ownclaw.agent.tools.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Selects the most relevant subset of tools to inject into the LLM prompt.
 *
 * When the tool count is small, all tools are included. As the registry grows
 * to dozens or hundreds, this selector keeps prompts lean by:
 *   1. Always including tools already used in the current trajectory
 *   2. Scoring every remaining tool by textual relevance to the user's query
 *   3. Including the top-N most relevant tools
 *   4. Returning the names of omitted tools so the LLM knows they exist
 *
 * This avoids bloating the system prompt while still giving the LLM awareness
 * of the full tool landscape.
 */
@Component
public class ToolSelector {

    private static final Logger log = LoggerFactory.getLogger(ToolSelector.class);

    /**
     * When the total tool count is at or below this threshold, all tools are
     * included verbatim — no filtering needed.
     */
    private static final int FULL_INCLUDE_THRESHOLD = 5;

    /**
     * Maximum number of tools to include with full descriptions when filtering
     * is active.
     */
    private static final int MAX_DETAILED_TOOLS = 20;

    private final ToolRegistry toolRegistry;

    public ToolSelector(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    /**
     * Result of a tool selection: the detailed tools to include in the prompt,
     * plus a list of names of tools that were omitted (available but not shown
     * in detail).
     */
    public record Selection(List<Tool> detailed, List<String> otherNames) {}

    /**
     * Select tools relevant to the given query and trajectory.
     *
     * @param query        the user's original message / task description
     * @param trajectory   the current execution trajectory (tools already used are always kept)
     * @return a Selection of detailed tools and names of omitted tools
     */
    public Selection select(String query, AgentTrajectory trajectory) {
        Collection<Tool> allTools = toolRegistry.all();

        // Small registries — include everything, no filtering
        if (allTools.size() <= FULL_INCLUDE_THRESHOLD) {
            return new Selection(new ArrayList<>(allTools), List.of());
        }

        // Collect tools already used in this trajectory — always include them
        Set<String> trajectoryToolNames = new HashSet<>();
        if (trajectory != null) {
            for (var turn : trajectory.turns()) {
                if (turn.action() != null && turn.action().tool() != null) {
                    trajectoryToolNames.add(turn.action().tool());
                }
            }
        }

        // Tokenize the query for relevance scoring
        Set<String> queryTokens = tokenize(query);

        // Score each tool
        List<ScoredTool> scored = new ArrayList<>();
        for (Tool tool : allTools) {
            boolean inTrajectory = trajectoryToolNames.contains(tool.name());
            double score = inTrajectory ? Double.MAX_VALUE : scoreTool(tool, queryTokens);
            scored.add(new ScoredTool(tool, score, inTrajectory));
        }

        // Sort: trajectory tools first, then by descending score
        scored.sort((a, b) -> {
            if (a.inTrajectory && !b.inTrajectory) return -1;
            if (!a.inTrajectory && b.inTrajectory) return 1;
            return Double.compare(b.score, a.score);
        });

        // Take the top N
        List<Tool> detailed = new ArrayList<>();
        List<String> otherNames = new ArrayList<>();

        for (int i = 0; i < scored.size(); i++) {
            ScoredTool st = scored.get(i);
            if (i < MAX_DETAILED_TOOLS || st.inTrajectory) {
                detailed.add(st.tool);
            } else {
                otherNames.add(st.tool.name());
            }
        }

        if (!otherNames.isEmpty()) {
            log.debug("ToolSelector: {} tools detailed, {} omitted for query: {}",
                    detailed.size(), otherNames.size(), truncate(query, 80));
        }

        return new Selection(detailed, otherNames);
    }

    /**
     * Score a tool's relevance to the query tokens.
     * Higher score = more relevant.
     */
    private double scoreTool(Tool tool, Set<String> queryTokens) {
        if (queryTokens.isEmpty()) return 0;

        double score = 0;

        // Tool name tokens (highest weight — exact concept match)
        Set<String> nameTokens = tokenize(tool.name().replace('_', ' '));
        score += 3.0 * overlapRatio(queryTokens, nameTokens);

        // Description tokens
        Set<String> descTokens = tokenize(tool.description());
        score += 2.0 * overlapRatio(queryTokens, descTokens);

        // Parameter description tokens (lower weight but still useful)
        Map<String, ToolParam> schema = tool.inputSchema();
        if (schema != null) {
            Set<String> paramTokens = new HashSet<>();
            for (var entry : schema.entrySet()) {
                paramTokens.addAll(tokenize(entry.getKey().replace('_', ' ')));
                paramTokens.addAll(tokenize(entry.getValue().description()));
            }
            score += 1.0 * overlapRatio(queryTokens, paramTokens);
        }

        // Bonus for substring matches in the tool name
        // (e.g., query "search" matches tool "web_search")
        String nameLower = tool.name().toLowerCase();
        for (String qt : queryTokens) {
            if (nameLower.contains(qt)) score += 1.5;
        }

        return score;
    }

    /**
     * Compute the ratio of query tokens that appear in the target tokens.
     * Returns 0..1.
     */
    private double overlapRatio(Set<String> queryTokens, Set<String> targetTokens) {
        if (queryTokens.isEmpty() || targetTokens.isEmpty()) return 0;
        long matches = queryTokens.stream().filter(targetTokens::contains).count();
        return (double) matches / queryTokens.size();
    }

    /**
     * Tokenize a string into lowercase word tokens, filtering out noise words.
     */
    private Set<String> tokenize(String text) {
        if (text == null || text.isBlank()) return Set.of();
        return Arrays.stream(text.toLowerCase().split("[^a-z0-9]+"))
                .filter(t -> t.length() > 2)       // skip very short tokens
                .filter(t -> !STOP_WORDS.contains(t))
                .collect(Collectors.toSet());
    }

    /** Common stop words that add noise to relevance scoring. */
    private static final Set<String> STOP_WORDS = Set.of(
            "the", "and", "for", "with", "this", "that", "from", "into",
            "can", "will", "are", "was", "has", "have", "not", "but",
            "use", "used", "using", "its", "also", "when", "which",
            "any", "all", "each", "than", "then", "more", "most"
    );

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    private record ScoredTool(Tool tool, double score, boolean inTrajectory) {}
}
