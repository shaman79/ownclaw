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
import java.util.List;
import java.util.Comparator;
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
        moveToQuarantine(skillDir,
                "Quarantined because it could not be loaded.\n"
                        + "Cause: " + cause.getClass().getSimpleName() + ": " + cause.getMessage() + "\n\n"
                        + "The code is intact. Fix SKILL.yaml and move the directory back into\n"
                        + "generated/ to restore the skill.\n");
    }

    /**
     * Retire a registered skill: unregister it and move its directory aside, reversibly.
     * <p>
     * The same mechanism as an unloadable skill, exposed for maintenance. It is deliberately a
     * move and not a delete. Generated skills are written by the agent at runtime, live outside
     * the repository, and are therefore in no backup git provides — so a wrong automated decision
     * has to stay undoable, and "undo" has to mean moving one directory back. A retirement that
     * cannot be reversed would make any automatic pruning rule too dangerous to enable.
     *
     * @param reason why, in a sentence, written into the directory for whoever finds it later
     * @return where it went, or empty if the skill was unknown or could not be moved
     */
    public Optional<Path> retire(String name, String reason) {
        DynamicSkill skill = dynamicSkills.get(name);
        if (skill == null) {
            log.warn("Cannot retire '{}': no such dynamic skill", name);
            return Optional.empty();
        }
        // Move FIRST, unregister only on success. Unregistering first left a half-state when the
        // move failed -- the skill gone from the manifest but still on disk, so it silently came
        // back at the next restart and nothing recorded that anything had been attempted. Doing
        // the fallible half first means a failure changes nothing at all: the skill stays
        // registered and usable, and the next pass will simply try again.
        Optional<Path> dest = moveToQuarantine(skill.skillDir(),
                "Retired automatically by skill maintenance.\n"
                        + "Reason: " + reason + "\n\n"
                        + "Nothing is necessarily wrong with this code; it was judged no longer\n"
                        + "earning its place. To bring it back, move this directory into\n"
                        + "generated/ (drop the timestamp suffix) and restart.\n");
        if (dest.isEmpty()) {
            log.error("Could not retire '{}': its files could not be moved, so it stays registered "
                    + "and usable. Nothing has changed.", name);
            return dest;
        }
        unregister(name);
        return dest;
    }

    /** The quarantine directory, a sibling of generated/. */
    private Path quarantineDir() {
        return Path.of(config.getSkills().getGeneratedPath()).toAbsolutePath()
                .resolveSibling("quarantine");
    }

    /**
     * Quarantined directories, newest first, as {@code <name>-<timestamp>}.
     * <p>
     * Exposed because quarantine was write-only: things went in and nothing could see them or
     * bring them back without a shell on the host. That was tolerable while the only way in was
     * a skill that would not load, and untenable once an automated pass could put a working
     * skill there — recovery has to be reachable by whoever discovers the mistake.
     */
    public List<String> quarantined() {
        Path dir = quarantineDir();
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .sorted(Comparator.reverseOrder())
                    .toList();
        } catch (IOException e) {
            log.warn("Could not list quarantine at {}: {}", dir, e.getMessage());
            return List.of();
        }
    }

    /**
     * Move a quarantined directory back and register it again.
     *
     * @param entry the directory name exactly as {@link #quarantined()} reports it
     * @return the skill's name once live again, or empty with the reason logged
     */
    public Optional<String> restoreFromQuarantine(String entry) {
        if (entry == null || entry.isBlank() || entry.contains("/") || entry.contains("..")) {
            log.warn("Refusing to restore a suspicious quarantine entry: {}", entry);
            return Optional.empty();
        }
        Path source = quarantineDir().resolve(entry);
        if (!Files.isDirectory(source)) {
            log.warn("No quarantined directory named {}", entry);
            return Optional.empty();
        }
        // "<name>-<ISO timestamp>" -- the timestamp starts at the last '-' followed by a digit
        // sequence that parses as a date, but the name itself may contain '-', so cut at the
        // first '-' that begins a 4-digit year.
        String name = entry.replaceFirst("-\\d{4}-\\d{2}-\\d{2}T.*$", "");
        Path dest = Path.of(config.getSkills().getGeneratedPath()).toAbsolutePath().resolve(name);
        if (Files.exists(dest)) {
            log.warn("Not restoring {}: a skill directory named '{}' already exists.", entry, name);
            return Optional.empty();
        }
        try {
            Files.move(source, dest);
            Files.deleteIfExists(dest.resolve("QUARANTINE-REASON.txt"));
            DynamicSkill skill = loadSkill(dest);
            if (skill == null) {
                log.error("Restored {} to {} but it would not load; it stays on disk.", entry, dest);
                return Optional.empty();
            }
            register(skill);
            log.warn("Restored quarantined skill '{}' from {}", name, entry);
            return Optional.of(skill.name());
        } catch (Exception e) {
            log.error("Could not restore {}: {}", entry, e.getMessage());
            return Optional.empty();
        }
    }

    /** Move a skill directory into the quarantine sibling, leaving a note saying why. */
    private Optional<Path> moveToQuarantine(Path skillDir, String reasonText) {
        try {
            Path quarantineDir = quarantineDir();
            Files.createDirectories(quarantineDir);
            String stamp = java.time.Instant.now().toString().replace(':', '-');
            Path dest = quarantineDir.resolve(skillDir.getFileName() + "-" + stamp);
            Files.move(skillDir, dest);
            Files.writeString(dest.resolve("QUARANTINE-REASON.txt"),
                    reasonText + "\nWhen: " + java.time.Instant.now() + "\n");
            log.warn("Quarantined skill '{}' to {} — its code is intact and recoverable",
                    skillDir.getFileName(), dest);
            return Optional.of(dest);
        } catch (Exception moveEx) {
            log.error("Could not quarantine {} ({}). Leaving it in place.", skillDir, moveEx.getMessage());
            return Optional.empty();
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
