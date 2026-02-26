package com.ownclaw.agent.tools.impl;

import com.ownclaw.agent.SkillCuratorService;
import com.ownclaw.agent.tools.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Manages and curates the skill library — listing, analyzing, reading, and deleting skills.
 *
 * <p>Actions:
 * <ul>
 *   <li>{@code list} — Show all tools with usage statistics</li>
 *   <li>{@code analyze} — LLM-powered analysis with pruning/merging/refactoring recommendations</li>
 *   <li>{@code read} — Display a modifiable skill's source code and metadata</li>
 *   <li>{@code delete} — Permanently remove a modifiable skill</li>
 * </ul>
 *
 * <p>The analyze action uses the cloud LLM to identify redundancy, low usage,
 * merge candidates, and capability gaps — keeping the skill library lean.
 */
@Component
public class SkillCurateTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(SkillCurateTool.class);

    private final SkillCuratorService curatorService;
    private final DynamicSkillRegistry dynamicSkillRegistry;

    public SkillCurateTool(SkillCuratorService curatorService,
                           DynamicSkillRegistry dynamicSkillRegistry) {
        this.curatorService = curatorService;
        this.dynamicSkillRegistry = dynamicSkillRegistry;
    }

    @Override
    public String name() { return "skill_curate"; }

    @Override
    public String description() {
        return "Manage and curate the tool library. Actions: " +
                "'list' shows all tools with usage statistics; " +
                "'analyze' performs LLM-powered analysis with pruning/merging recommendations; " +
                "'read' shows a skill's source code and metadata; " +
                "'delete' permanently removes a modifiable skill.";
    }

    @Override
    public Map<String, ToolParam> inputSchema() {
        var schema = new LinkedHashMap<String, ToolParam>();
        schema.put("action", ToolParam.required("string",
                "Action to perform: list, analyze, read, or delete"));
        schema.put("name", ToolParam.optional("string",
                "Skill name — required for 'read' and 'delete' actions"));
        return schema;
    }

    @Override
    public boolean hasSideEffects() { return true; }

    @Override
    public ToolResult execute(Map<String, Object> params, ToolExecutionContext context) {
        String action = (String) params.get("action");
        if (action == null || action.isBlank()) {
            return ToolResult.failure(
                    "Missing 'action' parameter. Must be one of: list, analyze, read, delete.");
        }

        return switch (action.toLowerCase()) {
            case "list" -> handleList();
            case "analyze" -> handleAnalyze();
            case "read" -> handleRead(params);
            case "delete" -> handleDelete(params);
            default -> ToolResult.failure(
                    "Unknown action '" + action + "'. Must be one of: list, analyze, read, delete.");
        };
    }

    private ToolResult handleList() {
        String listing = curatorService.listSkillsWithStats();
        return ToolResult.success(listing);
    }

    private ToolResult handleAnalyze() {
        String analysis = curatorService.analyzeLibrary();
        return ToolResult.success(analysis);
    }

    private ToolResult handleRead(Map<String, Object> params) {
        String name = (String) params.get("name");
        if (name == null || name.isBlank()) {
            return ToolResult.failure("'name' parameter is required for the 'read' action.");
        }

        var skillOpt = dynamicSkillRegistry.getDynamic(name);
        if (skillOpt.isEmpty()) {
            return ToolResult.failure(
                    "'" + name + "' is not a modifiable skill or does not exist.");
        }

        try {
            DynamicSkill skill = skillOpt.get();
            Path codeFile = skill.skillDir().resolve("skill.py");
            Path yamlFile = skill.skillDir().resolve("SKILL.yaml");
            Path reqFile = skill.skillDir().resolve("requirements.txt");

            var sb = new StringBuilder();
            sb.append("## Skill: ").append(name).append("\n\n");

            if (Files.exists(yamlFile)) {
                sb.append("### SKILL.yaml\n```yaml\n");
                sb.append(Files.readString(yamlFile, StandardCharsets.UTF_8));
                sb.append("\n```\n\n");
            }

            if (Files.exists(codeFile)) {
                sb.append("### skill.py\n```python\n");
                sb.append(Files.readString(codeFile, StandardCharsets.UTF_8));
                sb.append("\n```\n\n");
            }

            if (Files.exists(reqFile)) {
                sb.append("### requirements.txt\n```\n");
                sb.append(Files.readString(reqFile, StandardCharsets.UTF_8));
                sb.append("\n```\n");
            }

            return ToolResult.success(sb.toString());

        } catch (IOException e) {
            return ToolResult.failure("Failed to read skill files: " + e.getMessage());
        }
    }

    private ToolResult handleDelete(Map<String, Object> params) {
        String name = (String) params.get("name");
        if (name == null || name.isBlank()) {
            return ToolResult.failure("'name' parameter is required for the 'delete' action.");
        }

        if (!dynamicSkillRegistry.isDynamic(name)) {
            return ToolResult.failure(
                    "'" + name + "' is not a modifiable skill and cannot be deleted.");
        }

        var skillOpt = dynamicSkillRegistry.getDynamic(name);
        if (skillOpt.isEmpty()) {
            return ToolResult.failure("Skill '" + name + "' not found.");
        }

        // Unregister from registry
        dynamicSkillRegistry.unregister(name);

        // Delete files from disk
        Path skillDir = skillOpt.get().skillDir();
        try {
            if (Files.exists(skillDir)) {
                try (Stream<Path> walk = Files.walk(skillDir)) {
                    walk.sorted(Comparator.reverseOrder())
                        .forEach(p -> {
                            try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                        });
                }
            }
        } catch (IOException e) {
            log.warn("Failed to fully delete skill directory {}: {}", skillDir, e.getMessage());
        }

        return ToolResult.success(
                "Skill '" + name + "' has been permanently deleted and unregistered.");
    }
}
