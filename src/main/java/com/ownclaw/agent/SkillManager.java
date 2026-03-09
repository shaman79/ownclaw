package com.ownclaw.agent;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.ownclaw.agent.tools.DynamicSkill;
import com.ownclaw.agent.tools.DynamicSkillRegistry;
import com.ownclaw.agent.tools.ToolRegistry;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.sandbox.SandboxManager;
import com.ownclaw.sandbox.SandboxResult;
import com.ownclaw.skills.PythonEnvironmentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * Core service for creating, editing, reading, deleting, and listing skills.
 *
 * <p>This is the single gateway through which the agent modifies its own
 * tool inventory. It consolidates logic that was previously spread across
 * SkillCreatorTool and SkillCurateTool. Because all tools are now dynamic
 * Python skills, every tool in the registry can be managed through this service.
 *
 * <p>Skill management is exposed to the agent as special actions (like
 * {@code respond} and {@code ask_user}), not as tools — this guarantees the
 * agent can always create and improve tools regardless of what tools are
 * currently registered.
 */
@Service
public class SkillManager {

    private static final Logger log = LoggerFactory.getLogger(SkillManager.class);
    // Lenient mapper for parsing parameters JSON from LLM output.
    // Tolerates unquoted keys, single quotes, trailing commas — common LLM quirks.
    private static final ObjectMapper jsonMapper = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_UNQUOTED_FIELD_NAMES)
            .enable(JsonReadFeature.ALLOW_SINGLE_QUOTES)
            .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
            .build();
    private static final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    private final DynamicSkillRegistry dynamicSkillRegistry;
    private final ToolRegistry toolRegistry;
    private final OwnClawConfig config;
    private final SandboxManager sandbox;
    private final PythonEnvironmentService pythonEnv;
    private final SkillCuratorService curatorService;

    public SkillManager(DynamicSkillRegistry dynamicSkillRegistry,
                        @Lazy ToolRegistry toolRegistry,
                        OwnClawConfig config,
                        SandboxManager sandbox,
                        PythonEnvironmentService pythonEnv,
                        SkillCuratorService curatorService) {
        this.dynamicSkillRegistry = dynamicSkillRegistry;
        this.toolRegistry = toolRegistry;
        this.config = config;
        this.sandbox = sandbox;
        this.pythonEnv = pythonEnv;
        this.curatorService = curatorService;
    }

    // ────────────────────── Create / Update ──────────────────────

    /**
     * Create (or update) a Python skill.
     *
     * @param params must contain: name, description, code, parameters (JSON string).
     *               Optional: requirements, requires_network, has_side_effects, timeout.
     * @return human-readable result message
     */
    public String createSkill(Map<String, Object> params) {
        String name = str(params, "name");
        String description = str(params, "description");
        String code = str(params, "code");
        // --- Validate ---

        if (name == null || !name.matches("[a-z][a-z0-9_]*")) {
            return "ERROR: Invalid skill name. Must start with a lowercase letter and " +
                    "contain only lowercase letters, digits, and underscores.";
        }
        if (description == null || description.isBlank()) return "ERROR: Description is required.";
        if (code == null || code.isBlank()) return "ERROR: Code is required.";

        // Adaptively normalize parameters: LLMs produce this field as:
        //   a) JSON string: "{\"url\": {\"type\": \"string\"}}"  (correct)
        //   b) Map object: {url={type=string}} (common from local LLM)
        //   c) List/Array: [{name: "url", type: "string"}] (rare but happens)
        //   d) null/missing (error)
        Map<String, Object> parametersDef;
        try {
            parametersDef = normalizeParameters(params.get("parameters"));
        } catch (Exception e) {
            return "ERROR: Invalid parameters JSON: " + e.getMessage();
        }
        if (parametersDef == null) {
            return "ERROR: parameters field is required.";
        }

        String syntaxError = checkPythonSyntax(code);
        if (syntaxError != null) return "ERROR: Python syntax error:\n" + syntaxError;

        String requirements = str(params, "requirements");
        boolean requiresNetwork = Boolean.TRUE.equals(params.get("requires_network"));
        boolean hasSideEffects = Boolean.TRUE.equals(params.get("has_side_effects"));
        int timeout = toInt(params.get("timeout"), 30);
        String credentials = str(params, "credentials");
        String systemPackagesStr = str(params, "system_packages");

        // --- Write to disk & register ---

        try {
            Path skillDir = Path.of(config.getSkills().getGeneratedPath()).resolve(name);
            Files.createDirectories(skillDir);

            Files.writeString(skillDir.resolve("SKILL.yaml"),
                    buildSkillYaml(name, description, parametersDef, requiresNetwork, hasSideEffects,
                            timeout, credentials, systemPackagesStr),
                    StandardCharsets.UTF_8);
            Files.writeString(skillDir.resolve("skill.py"), code, StandardCharsets.UTF_8);
            if (requirements != null && !requirements.isBlank()) {
                Files.writeString(skillDir.resolve("requirements.txt"),
                        requirements.strip() + "\n", StandardCharsets.UTF_8);
            } else {
                // Remove stale requirements.txt so the old venv isn't used
                Files.deleteIfExists(skillDir.resolve("requirements.txt"));
            }

            DynamicSkill skill = dynamicSkillRegistry.loadSkill(skillDir);
            if (skill == null) return "ERROR: Failed to load — check SKILL.yaml format.";

            boolean isUpdate = toolRegistry.find(name).isPresent();
            dynamicSkillRegistry.unregister(name);
            dynamicSkillRegistry.register(skill);

            return "Skill '" + name + "' " + (isUpdate ? "updated" : "created") +
                    " and registered. It is now available as a tool.";
        } catch (IOException e) {
            log.error("Failed to create skill '{}': {}", name, e.getMessage());
            return "ERROR: Failed to write skill files: " + e.getMessage();
        }
    }

    // ────────────────────── Read ──────────────────────

    /**
     * Read a skill's source code and metadata.
     */
    public String readSkill(String name) {
        if (name == null || name.isBlank()) return "ERROR: Skill name is required.";

        var skillOpt = dynamicSkillRegistry.getDynamic(name);
        if (skillOpt.isEmpty()) return "ERROR: '" + name + "' is not a skill or does not exist.";

        try {
            DynamicSkill skill = skillOpt.get();
            var sb = new StringBuilder();
            sb.append("## Skill: ").append(name).append("\n\n");

            Path yamlFile = skill.skillDir().resolve("SKILL.yaml");
            if (Files.exists(yamlFile)) {
                sb.append("### SKILL.yaml\n```yaml\n");
                sb.append(Files.readString(yamlFile, StandardCharsets.UTF_8));
                sb.append("\n```\n\n");
            }

            Path codeFile = skill.skillDir().resolve("skill.py");
            if (Files.exists(codeFile)) {
                sb.append("### skill.py\n```python\n");
                sb.append(Files.readString(codeFile, StandardCharsets.UTF_8));
                sb.append("\n```\n\n");
            }

            Path reqFile = skill.skillDir().resolve("requirements.txt");
            if (Files.exists(reqFile)) {
                sb.append("### requirements.txt\n```\n");
                sb.append(Files.readString(reqFile, StandardCharsets.UTF_8));
                sb.append("\n```\n");
            }

            return sb.toString();
        } catch (IOException e) {
            return "ERROR: Failed to read skill files: " + e.getMessage();
        }
    }

    // ────────────────────── Delete ──────────────────────

    /**
     * Patch the credentials field of an existing skill's YAML and reload it.
     * Used to fix skills that were created before credential injection was standardized.
     *
     * @param name skill name
     * @param credentials comma-separated credential keys (e.g. "SMTP_HOST,SMTP_PORT,SMTP_USER,SMTP_PASS")
     * @return result message
     */
    public String patchCredentials(String name, String credentials) {
        if (name == null || name.isBlank()) return "ERROR: Skill name is required.";
        if (credentials == null || credentials.isBlank()) return "ERROR: Credentials list is required.";

        var skillOpt = dynamicSkillRegistry.getDynamic(name);
        if (skillOpt.isEmpty()) return "ERROR: Skill '" + name + "' not found.";

        Path skillDir = skillOpt.get().skillDir();
        Path yamlFile = skillDir.resolve("SKILL.yaml");

        try {
            String yaml = Files.readString(yamlFile, StandardCharsets.UTF_8);

            // Remove existing credentials line if present
            yaml = yaml.replaceAll("(?m)^credentials:.*\\n?", "");

            // Add credentials field before requires_network or at the end
            String credLine = "credentials: \"" + credentials.strip() + "\"\n";
            if (yaml.contains("requires_network:")) {
                yaml = yaml.replace("requires_network:", credLine + "requires_network:");
            } else {
                yaml = yaml.stripTrailing() + "\n" + credLine;
            }

            Files.writeString(yamlFile, yaml, StandardCharsets.UTF_8);

            // Reload the skill
            dynamicSkillRegistry.unregister(name);
            DynamicSkill reloaded = dynamicSkillRegistry.loadSkill(skillDir);
            if (reloaded == null) return "ERROR: Failed to reload skill after patching.";
            dynamicSkillRegistry.register(reloaded);

            return "Skill '" + name + "' credentials patched to [" + credentials + "] and reloaded.";
        } catch (IOException e) {
            log.error("Failed to patch credentials for skill '{}': {}", name, e.getMessage());
            return "ERROR: " + e.getMessage();
        }
    }

    /**
     * Delete a skill permanently.
     */
    public String deleteSkill(String name) {
        if (name == null || name.isBlank()) return "ERROR: Skill name is required.";
        if (!dynamicSkillRegistry.isDynamic(name)) {
            return "ERROR: '" + name + "' is not a modifiable skill.";
        }

        var skillOpt = dynamicSkillRegistry.getDynamic(name);
        if (skillOpt.isEmpty()) return "ERROR: Skill '" + name + "' not found.";

        Path skillDir = skillOpt.get().skillDir();
        dynamicSkillRegistry.unregister(name);

        try {
            if (Files.exists(skillDir)) {
                try (Stream<Path> walk = Files.walk(skillDir)) {
                    walk.sorted(Comparator.reverseOrder())
                        .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
                }
            }
        } catch (IOException e) {
            log.warn("Failed to fully delete skill directory {}: {}", skillDir, e.getMessage());
        }

        return "Skill '" + name + "' has been permanently deleted.";
    }

    // ────────────────────── List / Stats ──────────────────────

    /**
     * List all tools with usage statistics.
     */
    public String listSkills() {
        return curatorService.listSkillsWithStats();
    }

    /**
     * LLM-powered library analysis.
     */
    public String analyzeSkills() {
        return curatorService.analyzeLibrary();
    }

    // ────────────────────── Helpers ──────────────────────

    private String checkPythonSyntax(String code) {
        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("ownclaw_syntax_");
            Path targetFile = tempDir.resolve("skill.py");
            Files.writeString(targetFile, code, StandardCharsets.UTF_8);

            Path checkScript = tempDir.resolve("_check.py");
            String targetPath = targetFile.toAbsolutePath().toString().replace("\\", "/");
            Files.writeString(checkScript,
                    "import py_compile, sys\n" +
                    "try:\n" +
                    "    py_compile.compile('" + targetPath + "', doraise=True)\n" +
                    "except py_compile.PyCompileError as e:\n" +
                    "    print(str(e), file=sys.stderr)\n" +
                    "    sys.exit(1)\n",
                    StandardCharsets.UTF_8);

            String python = pythonEnv.getSystemPython();
            SandboxResult result = sandbox.execute(python, checkScript, tempDir, null, Map.of(), 10);

            if (!result.isSuccess()) {
                return result.stderr().isBlank() ? result.stdout() : result.stderr();
            }
            return null;
        } catch (Exception e) {
            return "Syntax check failed: " + e.getMessage();
        } finally {
            cleanupTempDir(tempDir);
        }
    }

    private String buildSkillYaml(String name, String description, Map<String, Object> parametersDef,
                                  boolean requiresNetwork, boolean hasSideEffects, int timeout,
                                  String credentials, String systemPackages)
            throws IOException {
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
        if (credentials != null && !credentials.isBlank()) {
            List<String> credList = Arrays.stream(credentials.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .toList();
            if (!credList.isEmpty()) {
                yaml.put("credentials", credList);
            }
        }
        if (systemPackages != null && !systemPackages.isBlank()) {
            // Support comma-separated or space-separated
            String delimiter = systemPackages.contains(",") ? "," : "\\s+";
            List<String> pkgList = Arrays.stream(systemPackages.split(delimiter))
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .filter(s -> s.matches("[a-zA-Z0-9._+:-]+")) // sanitize
                    .toList();
            if (!pkgList.isEmpty()) {
                yaml.put("system_packages", pkgList);
            }
        }
        return yamlMapper.writeValueAsString(yaml);
    }

    private void cleanupTempDir(Path dir) {
        if (dir == null) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder())
                .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}
    }

    private String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v != null ? v.toString() : null;
    }

    /**
     * Normalize the parameters field from LLM output into a proper Map.
     * <p>Handles multiple formats that LLMs produce:
     * <ul>
     *   <li>String: parse as JSON (the intended format)</li>
     *   <li>Map: already deserialized by Jackson (common when local LLM produces nested object)</li>
     *   <li>List: convert [{name: "x", type: "string"}] → {"x": {type: "string"}}</li>
     *   <li>null: returns null</li>
     * </ul>
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> normalizeParameters(Object raw) throws Exception {
        if (raw == null) return null;

        // Already a Map (Jackson deserialized it from nested JSON object)
        if (raw instanceof Map) {
            return (Map<String, Object>) raw;
        }

        // List/Array format: [{"name": "url", "type": "string", ...}, ...]
        // Convert to Map format: {"url": {"type": "string", ...}}
        if (raw instanceof List<?> list) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> entry) {
                    String paramName = entry.get("name") != null ? entry.get("name").toString() : null;
                    if (paramName != null) {
                        Map<String, Object> paramDef = new LinkedHashMap<>((Map<String, Object>) entry);
                        paramDef.remove("name");
                        result.put(paramName, paramDef);
                    }
                }
            }
            return result.isEmpty() ? null : result;
        }

        // String: parse as JSON with lenient mapper
        String jsonStr = raw.toString();
        if (jsonStr.isBlank()) return null;

        // Handle Java Map.toString() format: {type=string, description=...}
        // This happens when str() calls toString() on a Map object
        if (jsonStr.contains("=") && !jsonStr.contains(":") && !jsonStr.contains("\"")) {
            // Convert {key=value, ...} to {"key": "value", ...}
            jsonStr = jsonStr.replaceAll("(\\w+)=", "\"$1\":");
            // Wrap unquoted values
            jsonStr = jsonStr.replaceAll(":\\s*([^,{}\"\\[\\]]+)", ": \"$1\"");
        }

        return jsonMapper.readValue(jsonStr, new TypeReference<>() {});
    }

    private int toInt(Object value, int defaultValue) {
        if (value == null) return defaultValue;
        if (value instanceof Number n) return n.intValue();
        try { return Integer.parseInt(value.toString()); } catch (Exception e) { return defaultValue; }
    }
}
