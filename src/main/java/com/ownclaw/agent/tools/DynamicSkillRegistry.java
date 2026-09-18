package com.ownclaw.agent.tools;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.conversation.FileStorageService;
import com.ownclaw.sandbox.ContainerSandbox;
import com.ownclaw.sandbox.SandboxManager;
import com.ownclaw.skills.PythonEnvironmentService;
import com.ownclaw.users.CredentialVault;
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
    private final ContainerSandbox containerSandbox;
    private final PythonEnvironmentService pythonEnv;
    private final CredentialVault credentialVault;
    private final FileStorageService fileStorage;

    private final Map<String, DynamicSkill> dynamicSkills = new ConcurrentHashMap<>();

    public DynamicSkillRegistry(OwnClawConfig config, @Lazy ToolRegistry toolRegistry,
                                SandboxManager sandbox, ContainerSandbox containerSandbox,
                                PythonEnvironmentService pythonEnv,
                                CredentialVault credentialVault,
                                FileStorageService fileStorage) {
        this.config = config;
        this.toolRegistry = toolRegistry;
        this.sandbox = sandbox;
        this.containerSandbox = containerSandbox;
        this.pythonEnv = pythonEnv;
        this.credentialVault = credentialVault;
        this.fileStorage = fileStorage;
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
            quarantine(skillDir, e);
        }
    }

    /**
     * Move a skill that would not load out of the way, instead of deleting it.
     * <p>
     * This used to recursively delete the directory, with the justification that a broken skill
     * "blocks startup repeatedly". It does not: the exception is caught per directory, so a skill
     * that fails to load costs one log line and nothing else. What the deletion did do was destroy
     * working Python, permanently and silently. {@code loadSkill} throws on any YAML syntax error
     * — {@code yamlMapper.readValue} is not lenient — and on a malformed parameter block, so one
     * bad edit to SKILL.yaml by the model that writes these files was enough to lose the skill's
     * code. Generated skills live outside the repository and are not in any backup that git
     * provides, so there was nothing to restore from. A transient IO error during a deploy could
     * do the same to a skill that was perfectly fine.
     * <p>
     * Quarantine achieves the stated goal — the directory stops being rescanned, so the warning
     * does not repeat — while keeping the code recoverable. The destination is a sibling of the
     * generated directory rather than a child, because {@link #loadAll} lists every subdirectory
     * of {@code generated/} and would otherwise try to load the quarantined copy again.
     * <p>
     * If the move itself fails, the directory is deliberately left alone. A skill that logs a
     * warning on every startup is a far better outcome than one that is gone.
     */
    private void quarantine(Path skillDir, Exception cause) {
        try {
            Path quarantineDir = skillDir.getParent().resolveSibling("quarantine");
            Files.createDirectories(quarantineDir);
            String stamp = java.time.Instant.now().toString().replace(':', '-');
            Path dest = quarantineDir.resolve(skillDir.getFileName() + "-" + stamp);
            Files.move(skillDir, dest);
            Files.writeString(dest.resolve("QUARANTINE-REASON.txt"),
                    "Quarantined because it could not be loaded.\n"
                            + "When:  " + java.time.Instant.now() + "\n"
                            + "Cause: " + cause.getClass().getSimpleName() + ": " + cause.getMessage() + "\n\n"
                            + "The code is intact. Fix SKILL.yaml and move the directory back into\n"
                            + "generated/ to restore the skill.\n");
            log.warn("Quarantined unloadable skill '{}' to {} — its code is intact and recoverable",
                    skillDir.getFileName(), dest);
        } catch (Exception moveEx) {
            log.error("Could not quarantine broken skill {} ({}). Leaving it in place — it will be "
                            + "skipped on every startup until SKILL.yaml is fixed.",
                    skillDir, moveEx.getMessage());
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
        List<String> credentials = parseCredentials(yaml);
        List<String> systemPackages = parseStringList(yaml, "system_packages");
        String containerImage = getString(yaml, "container_image");

        return new DynamicSkill(name, description, parameters, skillDir,
                requiresNetwork, hasSideEffects, timeout, sandbox, pythonEnv,
                credentials, credentialVault, systemPackages, containerImage, containerSandbox,
                fileStorage);
    }

    /**
     * Parse the credentials field from SKILL.yaml.
     * Supports both a YAML list and a comma-separated string.
     */
    @SuppressWarnings("unchecked")
    private List<String> parseCredentials(Map<String, Object> yaml) {
        Object credObj = yaml.get("credentials");
        if (credObj == null) return List.of();
        if (credObj instanceof List<?> list) {
            // Flatten nested lists and strip brackets from stringified list elements
            List<String> result = new ArrayList<>();
            flattenCredentialList(list, result);
            return result;
        }
        if (credObj instanceof String str && !str.isBlank()) {
            // Strip brackets in case of stringified list format: "[KEY1, KEY2]"
            str = str.replaceAll("[\\[\\]]", "");
            return Arrays.stream(str.split(","))
                    .map(String::trim)
                    .map(String::toUpperCase)
                    .filter(s -> !s.isBlank())
                    .toList();
        }
        return List.of();
    }

    /** Recursively flatten nested lists of credential keys into a flat list of clean strings. */
    private void flattenCredentialList(List<?> list, List<String> result) {
        for (Object item : list) {
            if (item instanceof List<?> nested) {
                flattenCredentialList(nested, result);
            } else if (item != null) {
                String key = item.toString().replaceAll("[\\[\\]]", "").trim().toUpperCase();
                if (!key.isBlank()) {
                    result.add(key);
                }
            }
        }
    }

    /**
     * Parse a generic string list field from SKILL.yaml.
     * Supports YAML list, comma-separated string, or space-separated string.
     */
    private List<String> parseStringList(Map<String, Object> yaml, String key) {
        Object obj = yaml.get(key);
        if (obj == null) return List.of();
        if (obj instanceof List<?> list) {
            return list.stream()
                    .map(Object::toString)
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .toList();
        }
        if (obj instanceof String str && !str.isBlank()) {
            // Support both comma-separated and space-separated
            String delimiter = str.contains(",") ? "," : "\\s+";
            return Arrays.stream(str.split(delimiter))
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .toList();
        }
        return List.of();
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
