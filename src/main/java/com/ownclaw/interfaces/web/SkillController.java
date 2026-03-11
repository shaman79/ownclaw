package com.ownclaw.interfaces.web;

import com.ownclaw.agent.SkillCuratorService;
import com.ownclaw.agent.SkillManager;
import com.ownclaw.agent.tools.DynamicSkill;
import com.ownclaw.agent.tools.DynamicSkillRegistry;
import com.ownclaw.agent.tools.Tool;
import com.ownclaw.agent.tools.ToolExecutionContext;
import com.ownclaw.agent.tools.ToolParam;
import com.ownclaw.agent.tools.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * REST API for managing skills from the web UI.
 *
 * <p>Provides listing, viewing, editing, deleting, and test-running
 * of dynamic Python skills. All endpoints are auto-protected by
 * the JWT filter on /api/*.
 */
@RestController
@RequestMapping("/api/skills")
public class SkillController {

    private static final Logger log = LoggerFactory.getLogger(SkillController.class);

    private final SkillManager skillManager;
    private final DynamicSkillRegistry dynamicSkillRegistry;
    private final SkillCuratorService curatorService;

    public SkillController(SkillManager skillManager,
                           DynamicSkillRegistry dynamicSkillRegistry,
                           SkillCuratorService curatorService) {
        this.skillManager = skillManager;
        this.dynamicSkillRegistry = dynamicSkillRegistry;
        this.curatorService = curatorService;
    }

    /**
     * GET /api/skills — list all dynamic skills with metadata and usage stats.
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> listSkills() {
        var skills = new ArrayList<Map<String, Object>>();

        // Gather usage stats indexed by tool name
        var statsList = curatorService.getUsageStats(null);
        Map<String, Map<String, Object>> statsMap = new HashMap<>();
        for (var row : statsList) {
            statsMap.put((String) row.get("tool_name"), row);
        }

        for (DynamicSkill skill : sorted(dynamicSkillRegistry.allDynamic())) {
            var item = new LinkedHashMap<String, Object>();
            item.put("name", skill.name());
            item.put("description", skill.description());
            item.put("requiresNetwork", skill.requiresNetwork());
            item.put("hasSideEffects", skill.hasSideEffects());
            item.put("timeout", skill.estimatedMaxDurationSeconds());
            item.put("requiredCredentials", skill.requiredCredentials());
            item.put("systemPackages", skill.systemPackages());

            // Parameter names for quick view
            var paramNames = new ArrayList<String>();
            if (skill.inputSchema() != null) {
                paramNames.addAll(skill.inputSchema().keySet().stream().sorted().toList());
            }
            item.put("parameters", paramNames);

            // Usage stats
            var toolStats = statsMap.get(skill.name());
            if (toolStats != null) {
                long total = ((Number) toolStats.get("total_invocations")).longValue();
                long successes = ((Number) toolStats.get("successes")).longValue();
                item.put("totalInvocations", total);
                item.put("successes", successes);
                item.put("successRate", total > 0 ? Math.round((double) successes / total * 100) : 0);
                item.put("avgDurationMs", toolStats.get("avg_duration_ms"));
                item.put("lastUsed", toolStats.get("last_used"));
            } else {
                item.put("totalInvocations", 0);
                item.put("successes", 0);
                item.put("successRate", 0);
                item.put("avgDurationMs", null);
                item.put("lastUsed", null);
            }

            skills.add(item);
        }

        return ResponseEntity.ok(Map.of("skills", skills, "total", skills.size()));
    }

    /**
     * GET /api/skills/{name} — get full skill detail including source code.
     */
    @GetMapping("/{name}")
    public ResponseEntity<Map<String, Object>> getSkill(@PathVariable String name) {
        var skillOpt = dynamicSkillRegistry.getDynamic(name);
        if (skillOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        DynamicSkill skill = skillOpt.get();
        var result = new LinkedHashMap<String, Object>();
        result.put("name", skill.name());
        result.put("description", skill.description());
        result.put("requiresNetwork", skill.requiresNetwork());
        result.put("hasSideEffects", skill.hasSideEffects());
        result.put("timeout", skill.estimatedMaxDurationSeconds());
        result.put("requiredCredentials", skill.requiredCredentials());
        result.put("systemPackages", skill.systemPackages());

        // Full parameter definitions
        var params = new LinkedHashMap<String, Object>();
        if (skill.inputSchema() != null) {
            for (var entry : skill.inputSchema().entrySet()) {
                var tp = entry.getValue();
                var paramDef = new LinkedHashMap<String, Object>();
                paramDef.put("type", tp.type());
                paramDef.put("description", tp.description());
                paramDef.put("required", tp.required());
                params.put(entry.getKey(), paramDef);
            }
        }
        result.put("parameters", params);

        // Source files
        try {
            Path skillDir = skill.skillDir();

            Path yamlFile = skillDir.resolve("SKILL.yaml");
            if (Files.exists(yamlFile)) {
                result.put("yaml", Files.readString(yamlFile, StandardCharsets.UTF_8));
            }

            Path codeFile = skillDir.resolve("skill.py");
            if (Files.exists(codeFile)) {
                result.put("code", Files.readString(codeFile, StandardCharsets.UTF_8));
            }

            Path reqFile = skillDir.resolve("requirements.txt");
            if (Files.exists(reqFile)) {
                result.put("requirements", Files.readString(reqFile, StandardCharsets.UTF_8).strip());
            } else {
                result.put("requirements", "");
            }
        } catch (Exception e) {
            log.error("Failed to read skill files for '{}': {}", name, e.getMessage());
            result.put("error", "Failed to read source files: " + e.getMessage());
        }

        // Usage stats
        var stats = curatorService.getUsageStats(name);
        if (!stats.isEmpty()) {
            var s = stats.get(0);
            result.put("totalInvocations", s.get("total_invocations"));
            result.put("successes", s.get("successes"));
            result.put("avgDurationMs", s.get("avg_duration_ms"));
            result.put("lastUsed", s.get("last_used"));
        }

        return ResponseEntity.ok(result);
    }

    /**
     * PUT /api/skills/{name} — update a skill's code and/or metadata.
     */
    @PutMapping("/{name}")
    public ResponseEntity<Map<String, Object>> updateSkill(
            @PathVariable String name,
            @RequestBody Map<String, Object> body) {

        if (!dynamicSkillRegistry.isDynamic(name)) {
            return ResponseEntity.notFound().build();
        }

        // Build params map for SkillManager.createSkill (which handles create + update)
        var params = new LinkedHashMap<String, Object>();
        params.put("name", name);
        params.put("description", body.getOrDefault("description", ""));
        params.put("code", body.getOrDefault("code", ""));
        params.put("parameters", body.getOrDefault("parameters", Map.of()));

        if (body.containsKey("requirements")) {
            params.put("requirements", body.get("requirements"));
        }
        if (body.containsKey("requiresNetwork")) {
            params.put("requires_network", body.get("requiresNetwork"));
        }
        if (body.containsKey("hasSideEffects")) {
            params.put("has_side_effects", body.get("hasSideEffects"));
        }
        if (body.containsKey("timeout")) {
            params.put("timeout", body.get("timeout"));
        }
        if (body.containsKey("credentials")) {
            params.put("credentials", body.get("credentials"));
        }
        if (body.containsKey("systemPackages")) {
            params.put("system_packages", body.get("systemPackages"));
        }

        String result = skillManager.createSkill(params);

        if (result.startsWith("ERROR:")) {
            return ResponseEntity.badRequest().body(Map.of("error", result));
        }

        return ResponseEntity.ok(Map.of("message", result));
    }

    /**
     * DELETE /api/skills/{name} — permanently delete a skill.
     */
    @DeleteMapping("/{name}")
    public ResponseEntity<Map<String, Object>> deleteSkill(@PathVariable String name) {
        String result = skillManager.deleteSkill(name);

        if (result.startsWith("ERROR:")) {
            return ResponseEntity.badRequest().body(Map.of("error", result));
        }

        return ResponseEntity.ok(Map.of("message", result));
    }

    /**
     * POST /api/skills/{name}/run — test-run a skill with provided parameters.
     */
    @PostMapping("/{name}/run")
    public ResponseEntity<Map<String, Object>> runSkill(
            @PathVariable String name,
            @RequestBody Map<String, Object> body) {

        var skillOpt = dynamicSkillRegistry.getDynamic(name);
        if (skillOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        DynamicSkill skill = skillOpt.get();

        @SuppressWarnings("unchecked")
        Map<String, Object> params = body.containsKey("params")
                ? (Map<String, Object>) body.get("params")
                : Map.of();

        // Create a minimal execution context for test runs
        var context = new ToolExecutionContext("web-ui", "test-run", null, () -> false);

        try {
            long start = System.currentTimeMillis();
            ToolResult result = skill.execute(params, context);
            long duration = System.currentTimeMillis() - start;

            return ResponseEntity.ok(Map.of(
                    "output", result.output(),
                    "durationMs", duration,
                    "success", result.success()
            ));
        } catch (Exception e) {
            log.error("Test-run of skill '{}' failed: {}", name, e.getMessage());
            return ResponseEntity.ok(Map.of(
                    "output", e.getMessage() != null ? e.getMessage() : "Unknown error",
                    "durationMs", 0,
                    "success", false
            ));
        }
    }

    /**
     * POST /api/skills/reload — reload all dynamic skills from disk.
     */
    @PostMapping("/reload")
    public ResponseEntity<Map<String, Object>> reloadSkills() {
        dynamicSkillRegistry.reload();
        int count = dynamicSkillRegistry.allDynamic().size();
        return ResponseEntity.ok(Map.of(
                "message", "Reloaded " + count + " skills",
                "count", count
        ));
    }

    private List<DynamicSkill> sorted(Collection<DynamicSkill> skills) {
        var list = new ArrayList<>(skills);
        list.sort(Comparator.comparing(DynamicSkill::name));
        return list;
    }
}
