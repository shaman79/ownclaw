package com.ownclaw.agent.tools.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.ownclaw.agent.tools.*;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.sandbox.SandboxManager;
import com.ownclaw.sandbox.SandboxResult;
import com.ownclaw.skills.PythonEnvironmentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
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
 * Creates new reusable Python skills that become permanent tools.
 *
 * <p>When the agent identifies a repeatable pattern (data transformation,
 * API integration, specialised computation, etc.), it can encapsulate that
 * pattern as a named skill. The skill is immediately registered and available
 * for all future tasks — treated identically to every other tool.
 *
 * <p>The Python contract: skill.py reads a JSON object from stdin and prints
 * a JSON result to stdout: {@code {"success": true/false, "output": "text", "data": {}}}
 */
@Component
public class SkillCreatorTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(SkillCreatorTool.class);
    private static final ObjectMapper jsonMapper = new ObjectMapper();
    private static final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    private final DynamicSkillRegistry dynamicSkillRegistry;
    private final ToolRegistry toolRegistry;
    private final OwnClawConfig config;
    private final SandboxManager sandbox;
    private final PythonEnvironmentService pythonEnv;

    public SkillCreatorTool(DynamicSkillRegistry dynamicSkillRegistry, @Lazy ToolRegistry toolRegistry,
                            OwnClawConfig config, SandboxManager sandbox,
                            PythonEnvironmentService pythonEnv) {
        this.dynamicSkillRegistry = dynamicSkillRegistry;
        this.toolRegistry = toolRegistry;
        this.config = config;
        this.sandbox = sandbox;
        this.pythonEnv = pythonEnv;
    }

    @Override
    public String name() { return "skill_create"; }

    @Override
    public String description() {
        return "Create a new reusable Python skill that becomes a permanent tool available in all future tasks. " +
                "The skill receives input as JSON on stdin and must print a JSON result to stdout " +
                "with the shape: {\"success\": true/false, \"output\": \"text\", \"data\": {}}. " +
                "Use this when you find yourself repeating a pattern that could be encapsulated as a reusable capability.";
    }

    @Override
    public Map<String, ToolParam> inputSchema() {
        var schema = new LinkedHashMap<String, ToolParam>();
        schema.put("name", ToolParam.required("string",
                "Unique skill name (lowercase, underscores only, e.g. 'csv_analyzer')"));
        schema.put("description", ToolParam.required("string",
                "Clear description of what this skill does — shown to the agent for tool selection"));
        schema.put("code", ToolParam.required("string",
                "Complete Python script. Must read JSON from stdin and print JSON to stdout."));
        schema.put("parameters", ToolParam.required("string",
                "JSON defining input parameters, e.g. " +
                "{\"url\": {\"type\": \"string\", \"description\": \"Target URL\", \"required\": true}}"));
        schema.put("requirements", ToolParam.optional("string",
                "Pip packages needed, one per line (e.g. 'requests\\nbeautifulsoup4')"));
        schema.put("requires_network", ToolParam.optional("boolean",
                "Whether this skill needs network access (default: false)"));
        schema.put("has_side_effects", ToolParam.optional("boolean",
                "Whether this skill modifies external state (default: false)"));
        schema.put("timeout", ToolParam.optional("integer",
                "Execution timeout in seconds (default: 30)"));
        return schema;
    }

    @Override public boolean hasSideEffects() { return true; }

    @Override
    public ToolResult execute(Map<String, Object> params, ToolExecutionContext context) {
        String name = (String) params.get("name");
        String description = (String) params.get("description");
        String code = (String) params.get("code");
        String parametersJson = (String) params.get("parameters");

        // --- Validate inputs ---

        if (name == null || !name.matches("[a-z][a-z0-9_]*")) {
            return ToolResult.failure(
                    "Invalid skill name. Must start with a lowercase letter and contain only " +
                    "lowercase letters, digits, and underscores.");
        }

        if (description == null || description.isBlank()) {
            return ToolResult.failure("Description is required.");
        }
        if (code == null || code.isBlank()) {
            return ToolResult.failure("Code is required.");
        }

        // Reject if name conflicts with a non-dynamic (fixed) tool
        if (toolRegistry.find(name).isPresent() && !dynamicSkillRegistry.isDynamic(name)) {
            return ToolResult.failure(
                    "Name '" + name + "' conflicts with an existing tool that cannot be overwritten.");
        }

        // Validate parameters JSON
        Map<String, Object> parametersDef;
        try {
            parametersDef = jsonMapper.readValue(parametersJson, new TypeReference<>() {});
        } catch (Exception e) {
            return ToolResult.failure("Invalid parameters JSON: " + e.getMessage());
        }

        // Syntax-check the Python code
        String syntaxError = checkSyntax(code);
        if (syntaxError != null) {
            return ToolResult.failure("Python syntax error:\n" + syntaxError);
        }

        // --- Extract optional fields ---

        String requirements = params.containsKey("requirements") ? (String) params.get("requirements") : null;
        boolean requiresNetwork = Boolean.TRUE.equals(params.get("requires_network"));
        boolean hasSideEffects = Boolean.TRUE.equals(params.get("has_side_effects"));
        int timeout = params.containsKey("timeout") ? toInt(params.get("timeout"), 30) : 30;

        // --- Write files to disk ---

        try {
            Path skillDir = Path.of(config.getSkills().getGeneratedPath()).resolve(name);
            Files.createDirectories(skillDir);

            // SKILL.yaml
            String yamlContent = buildSkillYaml(name, description, parametersDef,
                    requiresNetwork, hasSideEffects, timeout);
            Files.writeString(skillDir.resolve("SKILL.yaml"), yamlContent, StandardCharsets.UTF_8);

            // skill.py
            Files.writeString(skillDir.resolve("skill.py"), code, StandardCharsets.UTF_8);

            // requirements.txt (optional)
            if (requirements != null && !requirements.isBlank()) {
                Files.writeString(skillDir.resolve("requirements.txt"),
                        requirements.strip() + "\n", StandardCharsets.UTF_8);
            }

            // --- Load and register ---

            DynamicSkill skill = dynamicSkillRegistry.loadSkill(skillDir);
            if (skill == null) {
                return ToolResult.failure("Failed to load the created skill — check SKILL.yaml format.");
            }

            // If updating an existing skill, unregister first
            dynamicSkillRegistry.unregister(name);
            dynamicSkillRegistry.register(skill);

            boolean isUpdate = toolRegistry.find(name).isPresent();
            String verb = isUpdate ? "updated" : "created";
            return ToolResult.success(
                    "Skill '" + name + "' " + verb + " and registered. " +
                    "It is now available as a tool for all future tasks.");

        } catch (IOException e) {
            log.error("Failed to create skill '{}': {}", name, e.getMessage());
            return ToolResult.failure("Failed to write skill files: " + e.getMessage());
        }
    }

    /**
     * Run py_compile on the code to catch syntax errors without executing it.
     *
     * @return error message if syntax is invalid, null if OK
     */
    private String checkSyntax(String code) {
        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("ownclaw_syntax_");
            Path targetFile = tempDir.resolve("skill.py");
            Files.writeString(targetFile, code, StandardCharsets.UTF_8);

            // Build a small check script that uses py_compile
            Path checkScript = tempDir.resolve("_check.py");
            String targetPath = targetFile.toAbsolutePath().toString().replace("\\", "/");
            Files.writeString(checkScript,
                    "import py_compile, sys\n" +
                    "try:\n" +
                    "    py_compile.compile('" + targetPath + "', doraise=True)\n" +
                    "except py_compile.PyCompileError as e:\n" +
                    "    print(str(e), file=sys.stderr)\n" +
                    "    sys.exit(1)\n",
                    StandardCharsets.UTF_8
            );

            String python = pythonEnv.getSystemPython();
            SandboxResult result = sandbox.execute(python, checkScript, tempDir, null, Map.of(), 10);

            if (!result.isSuccess()) {
                return result.stderr().isBlank() ? result.stdout() : result.stderr();
            }
            return null; // No syntax errors

        } catch (Exception e) {
            return "Syntax check failed: " + e.getMessage();
        } finally {
            cleanupTempDir(tempDir);
        }
    }

    /**
     * Build the SKILL.yaml content using Jackson YAML serialization for proper escaping.
     */
    @SuppressWarnings("unchecked")
    private String buildSkillYaml(String name, String description, Map<String, Object> parametersDef,
                                  boolean requiresNetwork, boolean hasSideEffects, int timeout) throws IOException {
        Map<String, Object> yaml = new LinkedHashMap<>();
        yaml.put("name", name);
        yaml.put("description", description);
        yaml.put("version", 1);
        if (parametersDef != null && !parametersDef.isEmpty()) {
            yaml.put("parameters", parametersDef);
        }
        yaml.put("requires_network", requiresNetwork);
        yaml.put("has_side_effects", hasSideEffects);
        yaml.put("timeout", timeout);
        return yamlMapper.writeValueAsString(yaml);
    }

    private void cleanupTempDir(Path dir) {
        if (dir == null) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder())
                .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}
    }

    private int toInt(Object value, int defaultValue) {
        if (value instanceof Number n) return n.intValue();
        try { return Integer.parseInt(value.toString()); } catch (Exception e) { return defaultValue; }
    }
}
