package com.ownclaw.agent;

import com.ownclaw.agent.tools.DynamicSkill;
import com.ownclaw.agent.tools.DynamicSkillRegistry;
import com.ownclaw.agent.tools.Tool;
import com.ownclaw.agent.tools.ToolRegistry;
import com.ownclaw.llm.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/**
 * Manages skill lifecycle analytics and provides LLM-powered curation.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Record tool invocation metrics (all tools, not just dynamic skills)</li>
 *   <li>Query aggregated usage statistics</li>
 *   <li>Perform LLM-powered library analysis — identifying redundant, unused,
 *       low-quality, or merge-candidate skills</li>
 * </ul>
 *
 * <p>The curator treats all tools equally for usage tracking. For modification
 * recommendations, it naturally focuses on modifiable (Python-based) skills,
 * since fixed tools cannot be altered at runtime.
 */
@Component
public class SkillCuratorService {

    private static final Logger log = LoggerFactory.getLogger(SkillCuratorService.class);

    private final JdbcTemplate jdbc;
    private final LlmRouter llmRouter;
    private final ToolRegistry toolRegistry;
    private final DynamicSkillRegistry dynamicSkillRegistry;

    public SkillCuratorService(JdbcTemplate jdbc, LlmRouter llmRouter,
                               @Lazy ToolRegistry toolRegistry,
                               DynamicSkillRegistry dynamicSkillRegistry) {
        this.jdbc = jdbc;
        this.llmRouter = llmRouter;
        this.toolRegistry = toolRegistry;
        this.dynamicSkillRegistry = dynamicSkillRegistry;
    }

    // ===== Usage Tracking =====

    /**
     * Record a single tool invocation. Called from AgentLoop after every tool execution.
     * Non-fatal — failures are logged at debug level and swallowed.
     */
    public void recordUsage(String toolName, String userId, String taskId,
                            boolean success, long durationMs) {
        try {
            jdbc.update(
                    "INSERT INTO skill_usage (tool_name, user_id, task_id, success, duration_ms) " +
                    "VALUES (?, ?, ?, ?, ?)",
                    toolName, userId, taskId, success ? 1 : 0, durationMs
            );
        } catch (Exception e) {
            log.debug("Failed to record tool usage for '{}': {}", toolName, e.getMessage());
        }
    }

    /**
     * Get aggregated usage statistics for all tools, or a specific tool.
     *
     * @param toolName filter by tool name, or null for all tools
     * @return list of rows with columns: tool_name, total_invocations, successes,
     *         avg_duration_ms, last_used
     */
    public List<Map<String, Object>> getUsageStats(String toolName) {
        if (toolName != null) {
            return jdbc.queryForList(
                    "SELECT tool_name, " +
                    "       COUNT(*) as total_invocations, " +
                    "       SUM(CASE WHEN success = 1 THEN 1 ELSE 0 END) as successes, " +
                    "       CAST(AVG(duration_ms) AS INTEGER) as avg_duration_ms, " +
                    "       MAX(created_at) as last_used " +
                    "FROM skill_usage " +
                    "WHERE tool_name = ? " +
                    "GROUP BY tool_name",
                    toolName
            );
        }

        return jdbc.queryForList(
                "SELECT tool_name, " +
                "       COUNT(*) as total_invocations, " +
                "       SUM(CASE WHEN success = 1 THEN 1 ELSE 0 END) as successes, " +
                "       CAST(AVG(duration_ms) AS INTEGER) as avg_duration_ms, " +
                "       MAX(created_at) as last_used " +
                "FROM skill_usage " +
                "GROUP BY tool_name " +
                "ORDER BY total_invocations DESC"
        );
    }

    // ===== Listing =====

    /**
     * Build a human-readable listing of all tools with their usage statistics.
     * Every tool is displayed equally — modifiable tools are only tagged so the
     * agent knows what can be changed, not to imply priority.
     */
    public String listSkillsWithStats() {
        var stats = getUsageStats(null);
        Map<String, Map<String, Object>> statsMap = new HashMap<>();
        for (var row : stats) {
            statsMap.put((String) row.get("tool_name"), row);
        }

        var sb = new StringBuilder();
        sb.append("## Tool Inventory\n\n");

        // Sort by name for consistent output
        var allTools = new ArrayList<>(toolRegistry.all());
        allTools.sort(Comparator.comparing(Tool::name));

        for (Tool tool : allTools) {
            sb.append("**").append(tool.name()).append("**");
            if (dynamicSkillRegistry.isDynamic(tool.name())) {
                sb.append(" [modifiable]");
            }
            sb.append(": ").append(tool.description()).append("\n");

            var toolStats = statsMap.get(tool.name());
            if (toolStats != null) {
                long total = ((Number) toolStats.get("total_invocations")).longValue();
                long successes = ((Number) toolStats.get("successes")).longValue();
                String rate = total > 0 ? String.format("%.0f%%", (double) successes / total * 100) : "N/A";
                sb.append("  Invocations: ").append(total);
                sb.append(" | Success rate: ").append(rate);
                sb.append(" | Avg duration: ").append(toolStats.get("avg_duration_ms")).append("ms");
                sb.append(" | Last used: ").append(toolStats.get("last_used"));
                sb.append("\n");
            } else {
                sb.append("  Never invoked\n");
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    // ===== LLM-Powered Analysis =====

    /**
     * Perform an LLM-powered analysis of the entire skill library.
     * Returns structured recommendations for pruning, merging, refactoring,
     * and capability gap identification.
     */
    public String analyzeLibrary() {
        var sb = new StringBuilder();

        sb.append("You are a tool library curator. Analyze the following inventory ");
        sb.append("and recommend improvements to keep the library lean and effective.\n\n");

        // All tools
        sb.append("## All Tools\n");
        var allTools = new ArrayList<>(toolRegistry.all());
        allTools.sort(Comparator.comparing(Tool::name));
        for (Tool tool : allTools) {
            sb.append("- ").append(tool.name());
            if (dynamicSkillRegistry.isDynamic(tool.name())) {
                sb.append(" [modifiable]");
            }
            sb.append(": ").append(tool.description()).append("\n");
        }

        // Dynamic skill source code (for deeper analysis)
        var dynamicSkills = dynamicSkillRegistry.allDynamic();
        if (!dynamicSkills.isEmpty()) {
            sb.append("\n## Modifiable Skill Source Code\n");
            for (DynamicSkill skill : dynamicSkills) {
                sb.append("\n### ").append(skill.name()).append("\n```python\n");
                try {
                    String code = Files.readString(skill.skillDir().resolve("skill.py"), StandardCharsets.UTF_8);
                    if (code.length() > 2000) code = code.substring(0, 2000) + "\n...[truncated]";
                    sb.append(code);
                } catch (Exception e) {
                    sb.append("(could not read source)");
                }
                sb.append("\n```\n");
            }
        }

        // Usage statistics
        sb.append("\n## Usage Statistics\n");
        var stats = getUsageStats(null);
        if (stats.isEmpty()) {
            sb.append("No usage data recorded yet.\n");
        } else {
            sb.append("| Tool | Invocations | Success Rate | Avg Duration | Last Used |\n");
            sb.append("|------|-------------|-------------|-------------|----------|\n");
            for (var row : stats) {
                long total = ((Number) row.get("total_invocations")).longValue();
                long successes = ((Number) row.get("successes")).longValue();
                String rate = total > 0 ? String.format("%.0f%%", (double) successes / total * 100) : "N/A";
                sb.append("| ").append(row.get("tool_name"));
                sb.append(" | ").append(total);
                sb.append(" | ").append(rate);
                sb.append(" | ").append(row.get("avg_duration_ms")).append("ms");
                sb.append(" | ").append(row.get("last_used"));
                sb.append(" |\n");
            }
        }

        // Instructions for analysis
        sb.append("\n## Instructions\n");
        sb.append("Analyze this library and provide recommendations as JSON:\n");
        sb.append("{\n");
        sb.append("  \"summary\": \"brief overall assessment of library health\",\n");
        sb.append("  \"redundant\": [{\"name\": \"...\", \"overlaps_with\": \"...\", ");
        sb.append("\"recommendation\": \"delete or merge\"}],\n");
        sb.append("  \"unused\": [{\"name\": \"...\", \"recommendation\": \"delete or keep\", ");
        sb.append("\"reason\": \"...\"}],\n");
        sb.append("  \"low_quality\": [{\"name\": \"...\", \"issues\": \"...\", ");
        sb.append("\"suggestion\": \"...\"}],\n");
        sb.append("  \"merge_candidates\": [{\"skills\": [\"a\", \"b\"], ");
        sb.append("\"into\": \"proposed_name\", \"reason\": \"...\"}],\n");
        sb.append("  \"capability_gaps\": [{\"description\": \"...\", ");
        sb.append("\"suggested_name\": \"...\"}]\n");
        sb.append("}\n");
        sb.append("Only include non-empty arrays. Be concise and actionable.\n");
        sb.append("Only recommend modifications/deletions for [modifiable] tools.\n");

        // Use cloud provider for best analytical reasoning
        try {
            LlmProvider provider = llmRouter.cloud().isAvailable()
                    ? llmRouter.cloud()
                    : llmRouter.local();

            LlmRequestConfig requestConfig = new LlmRequestConfig(null, null, 4096, true);
            LlmResponse response = provider.chat(
                    List.of(LlmMessage.user(sb.toString())),
                    requestConfig
            );
            return response.content();

        } catch (LlmException e) {
            log.error("Skill library analysis failed: {}", e.getMessage());
            return "{\"error\": \"Analysis failed: " + e.getMessage() + "\"}";
        }
    }
}
