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
        recordUsage(toolName, userId, taskId, success, durationMs, null, null);
    }

    /**
     * Record an invocation, keeping enough to reproduce it when it failed.
     * <p>
     * The counters alone said a skill failed; they could not say what it was asked to do or what
     * went wrong, so nothing could act on the failure. A failed invocation with its parameters
     * and its error IS a test case — which matters because the alternative, synthesising inputs
     * to test a skill, produces failures the skill is not responsible for. Real calls that
     * really broke are the only inputs that are certainly worth passing.
     * <p>
     * Parameters are redacted and truncated on the way in: a skill parameter can carry a token
     * or a password, and this database already holds the owner's conversation history.
     */
    public void recordUsage(String toolName, String userId, String taskId,
                            boolean success, long durationMs,
                            Map<String, Object> params, String error) {
        recordUsage(toolName, userId, taskId, success, durationMs, params, error, null);
    }

    /**
     * @param label whether the invocation's error text may reach a cloud model; the row keeps
     *              the error either way, because it is the owner's diagnostic
     */
    public void recordUsage(String toolName, String userId, String taskId,
                            boolean success, long durationMs,
                            Map<String, Object> params, String error,
                            com.ownclaw.privacy.Label label) {
        try {
            jdbc.update(
                    "INSERT INTO skill_usage (tool_name, user_id, task_id, success, duration_ms, "
                    + "params_json, error, label) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    toolName, userId, taskId, success ? 1 : 0, durationMs,
                    redactParams(params), truncate(error, 2000),
                    label == null ? null : label.name()
            );
        } catch (Exception e) {
            log.debug("Failed to record tool usage for '{}': {}", toolName, e.getMessage());
        }
    }


    private String redactParams(Map<String, Object> params) {
        if (params == null || params.isEmpty()) return null;
        var out = new LinkedHashMap<String, Object>();
        for (var e : params.entrySet()) {
            String k = e.getKey();
            if (k != null && com.ownclaw.users.CredentialVault.isSecretKey(k)) {
                out.put(k, "[REDACTED]");
            } else {
                Object v = e.getValue();
                out.put(k, v instanceof String s ? truncate(s, 500) : v);
            }
        }
        try {
            return truncate(new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(out), 4000);
        } catch (Exception ex) {
            return null;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /**
     * The failed invocations of a skill, most recent first — the cases a repair must fix, and
     * the cases a repaired version must then survive.
     */
    /**
     * Tool sequences that keep succeeding together, as candidates for one skill.
     * <p>
     * This is the smallest useful form of the thing the owner asked for: a library that
     * consolidates instead of accumulating. Every task now writes one {@code step} row per
     * action, so the same run of tools happening over and over across separate tasks is
     * visible as data rather than as something a person has to notice.
     * <p>
     * Deliberately only <em>detection</em>. It proposes; it does not write a skill. Automatic
     * consolidation would need the composed skill to be generated, validated and shown to
     * behave identically to the sequence it replaces, and an automated rewrite that is merely
     * probably equivalent is how a library loses behaviour nobody knew it depended on. Naming
     * the candidates is most of the value and risks nothing.
     * <p>
     * Deterministic: contiguous runs, exact tool names, a count of distinct tasks. No model
     * and no similarity scoring — the evidence is either there or it is not.
     *
     * @param minLength   shortest run worth reporting (2 is the useful floor)
     * @param minTasks    how many separate tasks must show it before it counts as a pattern
     */
    public List<Map<String, Object>> consolidationCandidates(int minLength, int minTasks) {
        List<Map<String, Object>> rows;
        try {
            rows = jdbc.queryForList(
                    "SELECT task_id, json_extract(details,'$.tool') AS tool, "
                    + "       json_extract(details,'$.success') AS ok "
                    + "FROM events WHERE event_type = 'step' AND task_id IS NOT NULL "
                    + "ORDER BY task_id, id");
        } catch (Exception e) {
            log.debug("Could not read step history: {}", e.getMessage());
            return List.of();
        }

        // Successful runs of tools within each task, in order.
        Map<String, List<String>> perTask = new LinkedHashMap<>();
        for (var r : rows) {
            Object ok = r.get("ok");
            boolean succeeded = ok != null && !"0".equals(String.valueOf(ok))
                    && !"false".equalsIgnoreCase(String.valueOf(ok));
            String tool = r.get("tool") == null ? null : String.valueOf(r.get("tool"));
            if (tool == null || tool.isBlank()) continue;
            List<String> seq = perTask.computeIfAbsent(String.valueOf(r.get("task_id")),
                    k -> new ArrayList<>());
            // A failed step breaks the run: a sequence is only worth collapsing if it worked.
            seq.add(succeeded ? tool : " ");
        }

        // Count every contiguous sub-run, remembering which tasks it appeared in.
        Map<String, Set<String>> tasksBySeq = new LinkedHashMap<>();
        for (var e : perTask.entrySet()) {
            List<String> seq = e.getValue();
            for (int i = 0; i < seq.size(); i++) {
                if (" ".equals(seq.get(i))) continue;
                for (int len = minLength; i + len <= seq.size(); len++) {
                    List<String> run = seq.subList(i, i + len);
                    if (run.contains(" ")) break;
                    tasksBySeq.computeIfAbsent(String.join(" -> ", run), k -> new LinkedHashSet<>())
                            .add(e.getKey());
                }
            }
        }

        var out = new ArrayList<Map<String, Object>>();
        for (var e : tasksBySeq.entrySet()) {
            if (e.getValue().size() < minTasks) continue;
            var m = new LinkedHashMap<String, Object>();
            m.put("sequence", e.getKey());
            m.put("tasks", e.getValue().size());
            m.put("length", e.getKey().split(" -> ").length);
            out.add(m);
        }
        // Longest first, then most frequent: a longer run collapses more work.
        out.sort((a, b) -> {
            int byLen = ((Integer) b.get("length")) - ((Integer) a.get("length"));
            return byLen != 0 ? byLen : ((Integer) b.get("tasks")) - ((Integer) a.get("tasks"));
        });
        return out;
    }

    public List<Map<String, Object>> recentFailures(String toolName, int limit) {
        try {
            return jdbc.queryForList(
                    // PUBLIC rows only: these go into the code-generation prompt, which goes to
                    // the cloud. A private skill's traceback carries what it fetched. Rows from
                    // before the label existed are NULL and are treated as not public.
                    "SELECT params_json, error, created_at FROM skill_usage "
                    + "WHERE tool_name = ? AND success = 0 AND params_json IS NOT NULL "
                    + "AND label = 'PUBLIC' "
                    + "ORDER BY created_at DESC LIMIT ?",
                    toolName, limit);
        } catch (Exception e) {
            log.debug("Could not read failures for '{}': {}", toolName, e.getMessage());
            return List.of();
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
    public String analyzeLibrary(com.ownclaw.llm.EgressContext egress) {
        var sb = new StringBuilder();

        sb.append("Analyze this tool inventory. Recommend improvements to keep the library lean.\n\n");

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

        sb.append("\n## Instructions\n");
        sb.append("Respond with JSON: {summary, redundant[{name,overlaps_with,recommendation}], ");
        sb.append("unused[{name,recommendation,reason}], low_quality[{name,issues,suggestion}], ");
        sb.append("merge_candidates[{skills[],into,reason}], capability_gaps[{description,suggested_name}]}\n");
        sb.append("Only non-empty arrays. Concise. Only modify/delete [modifiable] tools.\n");

        // Use cloud provider for best analytical reasoning
        try {
            LlmProvider provider = llmRouter.cloud().isAvailable()
                    ? llmRouter.cloud()
                    : llmRouter.local();

            // Every skill's full source goes out in this call. It carries the asking task's
            // context like the other two sites, so it is scrubbed, checked and ledgered -- and
            // the ledger row will show its size, which is the first time anyone sees it.
            LlmRequestConfig requestConfig = new LlmRequestConfig(null, null, true, null)
                    .withEgress(egress);
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
