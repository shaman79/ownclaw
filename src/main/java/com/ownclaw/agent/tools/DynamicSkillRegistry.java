package com.ownclaw.agent.tools;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.sandbox.SandboxManager;
import com.ownclaw.skills.PythonEnvironmentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Discovers, loads, and manages dynamic Python-based skills from disk.
 *
 * <p>On startup, scans the generated skills directory ({@code ownclaw.skills.generated-path})
 * and registers each valid skill as a {@link Tool} in the {@link ToolRegistry}.
 * Supports hot-registration and unregistration at runtime when the agent creates
 * or deletes skills.
 *
 * <p>Dynamic skills are stored as subdirectories of the generated path, each containing
 * a {@code SKILL.yaml} and {@code skill.py} file.
 */
@Component
public class DynamicSkillRegistry {

    private static final Logger log = LoggerFactory.getLogger(DynamicSkillRegistry.class);
    private static final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    private final OwnClawConfig config;
    private final ToolRegistry toolRegistry;
    private final SandboxManager sandbox;
    private final PythonEnvironmentService pythonEnv;

    private final Map<String, DynamicSkill> dynamicSkills = new ConcurrentHashMap<>();

    public DynamicSkillRegistry(OwnClawConfig config, @Lazy ToolRegistry toolRegistry,
                                SandboxManager sandbox, PythonEnvironmentService pythonEnv) {
        this.config = config;
        this.toolRegistry = toolRegistry;
        this.sandbox = sandbox;
        this.pythonEnv = pythonEnv;
    }

    @PostConstruct
    public void init() {
        Path generatedDir = Path.of(config.getSkills().getGeneratedPath());
        if (!Files.isDirectory(generatedDir)) {
            log.info("No generated skills directory at {} — no dynamic skills loaded", generatedDir);
            return;
        }

        try (Stream<Path> dirs = Files.list(generatedDir)) {
            dirs.filter(Files::isDirectory)
                .forEach(this::loadAndRegister);
        } catch (IOException e) {
            log.error("Failed to scan generated skills directory: {}", e.getMessage());
        }

        log.info("Loaded {} dynamic skill(s)", dynamicSkills.size());
    }

    private void loadAndRegister(Path skillDir) {
        try {
            DynamicSkill skill = loadSkill(skillDir);
            if (skill != null) {
                register(skill);
            }
        } catch (Exception e) {
            log.warn("Failed to load dynamic skill from {}: {}", skillDir, e.getMessage());
        }
    }

    /**
     * Load a dynamic skill from a directory containing SKILL.yaml and skill.py.
     *
     * @param skillDir the directory to load from
     * @return the loaded DynamicSkill, or null if the directory is incomplete
     */
    public DynamicSkill loadSkill(Path skillDir) throws IOException {
        Path yamlFile = skillDir.resolve("SKILL.yaml");
        Path scriptFile = skillDir.resolve("skill.py");

        if (!Files.exists(yamlFile) || !Files.exists(scriptFile)) {
            log.warn("Incomplete skill in {}: missing SKILL.yaml or skill.py", skillDir);
            return null;
        }

        Map<String, Object> yaml = yamlMapper.readValue(yamlFile.toFile(), new TypeReference<>() {});

        String name = getString(yaml, "name");
        String description = getString(yaml, "description");
        if (name == null || description == null) {
            log.warn("Skill in {} missing required 'name' or 'description'", skillDir);
            return null;
        }

        Map<String, ToolParam> parameters = parseParameters(yaml);
        boolean requiresNetwork = Boolean.TRUE.equals(yaml.get("requires_network"));
        boolean hasSideEffects = Boolean.TRUE.equals(yaml.get("has_side_effects"));
        int timeout = yaml.containsKey("timeout") ? toInt(yaml.get("timeout"), 30) : 30;

        return new DynamicSkill(name, description, parameters, skillDir,
                requiresNetwork, hasSideEffects, timeout, sandbox, pythonEnv);
    }

    @SuppressWarnings("unchecked")
    private Map<String, ToolParam> parseParameters(Map<String, Object> yaml) {
        Object paramsObj = yaml.get("parameters");
        if (!(paramsObj instanceof Map)) return Map.of();

        Map<String, Object> paramsMap = (Map<String, Object>) paramsObj;
        Map<String, ToolParam> result = new LinkedHashMap<>();

        for (var entry : paramsMap.entrySet()) {
            if (entry.getValue() instanceof Map paramDef) {
                String type = paramDef.containsKey("type") ? String.valueOf(paramDef.get("type")) : "string";
                String desc = paramDef.containsKey("description") ? String.valueOf(paramDef.get("description")) : "";
                boolean required = Boolean.TRUE.equals(paramDef.get("required"));
                result.put(entry.getKey(), new ToolParam(type, desc, required));
            }
        }

        return result;
    }

    /** Register a dynamic skill in both the internal map and the ToolRegistry. */
    public void register(DynamicSkill skill) {
        dynamicSkills.put(skill.name(), skill);
        toolRegistry.register(skill);
        log.info("Registered dynamic skill: {}", skill.name());
    }

    /** Unregister a dynamic skill by name. Returns true if it existed. */
    public boolean unregister(String name) {
        DynamicSkill removed = dynamicSkills.remove(name);
        if (removed != null) {
            toolRegistry.unregister(name);
            log.info("Unregistered dynamic skill: {}", name);
            return true;
        }
        return false;
    }

    /** All currently loaded dynamic skills. */
    public Collection<DynamicSkill> allDynamic() {
        return Collections.unmodifiableCollection(dynamicSkills.values());
    }

    /** Get a specific dynamic skill by name. */
    public Optional<DynamicSkill> getDynamic(String name) {
        return Optional.ofNullable(dynamicSkills.get(name));
    }

    /** Check if a tool name corresponds to a dynamic (modifiable) skill. */
    public boolean isDynamic(String name) {
        return dynamicSkills.containsKey(name);
    }

    /** Unregister all dynamic skills and re-scan the generated directory. */
    public void reload() {
        for (String name : new ArrayList<>(dynamicSkills.keySet())) {
            unregister(name);
        }
        init();
    }

    private String getString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value != null ? value.toString() : null;
    }

    private int toInt(Object value, int defaultValue) {
        if (value instanceof Number n) return n.intValue();
        try { return Integer.parseInt(value.toString()); } catch (Exception e) { return defaultValue; }
    }
}
