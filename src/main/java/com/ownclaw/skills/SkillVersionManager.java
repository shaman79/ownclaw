package com.ownclaw.skills;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ownclaw.config.OwnClawConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * Manages skill versioning: creating new versions, activating, rolling back,
 * and updating the manifest.json after changes.
 *
 * <p>Skill directory layout:
 * <pre>
 * skills/generated/{skill_name}/
 *   v1/
 *     skill.py
 *     requirements.txt  (optional)
 *   v2/
 *     ...
 * </pre>
 */
@Service
public class SkillVersionManager {

    private static final Logger log = LoggerFactory.getLogger(SkillVersionManager.class);

    private final OwnClawConfig config;
    private final SkillManifest skillManifest;
    private final ObjectMapper mapper;
    private final JdbcTemplate jdbc;

    public SkillVersionManager(OwnClawConfig config, SkillManifest skillManifest,
                               ObjectMapper mapper, JdbcTemplate jdbc) {
        this.config = config;
        this.skillManifest = skillManifest;
        this.mapper = mapper;
        this.jdbc = jdbc;
    }

    /**
     * Create a new generated skill version on disk.
     *
     * @param skillName   unique skill name (lowercase, underscore-separated)
     * @param skillPy     the Python script content
     * @param requirements optional requirements.txt content (null if none)
     * @return path to the created version directory
     */
    public Path createSkillVersion(String skillName, String skillPy, String requirements) throws IOException {
        Path genDir = resolveWritableGeneratedDir();

        Path skillDir = genDir.resolve(skillName);
        int nextVersion = getNextVersion(skillDir);
        Path versionDir = skillDir.resolve("v" + nextVersion);

        Files.createDirectories(versionDir);
        Files.writeString(versionDir.resolve("skill.py"), skillPy, StandardCharsets.UTF_8);

        if (requirements != null && !requirements.isBlank()) {
            Files.writeString(versionDir.resolve("requirements.txt"), requirements, StandardCharsets.UTF_8);
        }

        log.info("Created skill '{}' v{} at {}", skillName, nextVersion, versionDir);
        return versionDir;
    }

    /**
     * Register a generated skill in the manifest.json and the DB registry.
     *
     * @param skillName   skill name
     * @param summary     short description for manifest
     * @param keywords    matching keywords
     * @param params      parameter names
     * @param credentials required credentials (empty list if none)
     * @param version     version number
     * @param createdBy   user ID who triggered creation
     */
    public void registerSkill(String skillName, String summary, List<String> keywords,
                              List<String> params, List<String> credentials,
                              int version, boolean interactive, String createdBy) throws IOException {
        // Add to manifest.json (or to a runtime overlay if the base manifest is not writable)
        Path manifestPath = Path.of(config.getSkills().getManifestPath());
        Path runtimeManifest = runtimeSkillsDir().resolve("manifest.runtime.json");

        Path writeTarget = manifestPath;
        try {
            if (Files.exists(manifestPath)) {
                // Fast permission probe: attempt to open for write
                if (!Files.isWritable(manifestPath)) {
                    writeTarget = runtimeManifest;
                }
            } else {
                // Ensure parent is writable
                Path parent = manifestPath.toAbsolutePath().getParent();
                if (parent != null && (!Files.exists(parent) || !Files.isWritable(parent))) {
                    writeTarget = runtimeManifest;
                }
            }
        } catch (Exception ignored) {
            writeTarget = runtimeManifest;
        }

        if (!writeTarget.equals(manifestPath)) {
            Files.createDirectories(writeTarget.toAbsolutePath().getParent());
            log.warn("Base manifest is not writable; writing skill registration to runtime manifest: {}", writeTarget);
        }
        ObjectNode root;
        if (Files.exists(writeTarget)) {
            root = (ObjectNode) mapper.readTree(writeTarget.toFile());
        } else {
            root = mapper.createObjectNode();
            root.putArray("skills");
        }

        ArrayNode skillsArray = (ArrayNode) root.get("skills");

        // Remove existing entry for this skill (update case)
        for (int i = 0; i < skillsArray.size(); i++) {
            if (skillName.equals(skillsArray.get(i).path("name").asText())) {
                skillsArray.remove(i);
                break;
            }
        }

        // Add new entry
        ObjectNode entry = mapper.createObjectNode();
        entry.put("name", skillName);
        entry.put("version", version);
        entry.put("summary", summary);
        ArrayNode kw = entry.putArray("keywords");
        keywords.forEach(kw::add);
        entry.put("complexity", "medium");
        ArrayNode pa = entry.putArray("params");
        params.forEach(pa::add);
        ArrayNode cr = entry.putArray("credentials");
        credentials.forEach(cr::add);
        entry.put("reversible", false);
        entry.put("interactive", interactive);
        skillsArray.add(entry);

        mapper.writerWithDefaultPrettyPrinter().writeValue(writeTarget.toFile(), root);
        log.info("Updated manifest ({}) with skill '{}' v{}", writeTarget, skillName, version);

        // Reload the in-memory manifest
        skillManifest.reload();

        // Update or insert DB registry
        jdbc.update("""
                INSERT INTO generated_skills (skill_name, description, version, status, created_by)
                VALUES (?, ?, ?, 'probationary', ?)
                ON CONFLICT(skill_name) DO UPDATE SET
                    description = excluded.description,
                    version = excluded.version,
                    status = 'probationary',
                    updated_at = datetime('now')
                """, skillName, summary, version, createdBy);
    }

    /**
     * Rollback a skill to its previous version.
     * Removes the current (latest) version directory and updates the manifest.
     *
     * @return true if rollback succeeded, false if no previous version exists
     */
    public boolean rollback(String skillName) throws IOException {
        Path genDir = Path.of(config.getSkills().getGeneratedPath());
        Path skillDir = genDir.resolve(skillName);

        if (!Files.isDirectory(skillDir)) {
            // Also check core skills
            Path coreDir = Path.of(config.getSkills().getCorePath()).resolve(skillName);
            if (!Files.isDirectory(coreDir)) return false;
            skillDir = coreDir;
        }

        List<Integer> versions = listVersions(skillDir);
        if (versions.size() < 2) {
            log.warn("Cannot rollback '{}': only {} version(s)", skillName, versions.size());
            return false;
        }

        int latestVersion = versions.getLast();
        int previousVersion = versions.get(versions.size() - 2);

        // Remove latest version directory
        Path latestDir = skillDir.resolve("v" + latestVersion);
        deleteDirectory(latestDir);

        // Update manifest version
        updateManifestVersion(skillName, previousVersion);
        skillManifest.reload();

        // Update DB
        jdbc.update("UPDATE generated_skills SET version = ?, status = 'active', updated_at = datetime('now') WHERE skill_name = ?",
                previousVersion, skillName);

        log.info("Rolled back '{}' from v{} to v{}", skillName, latestVersion, previousVersion);
        return true;
    }

    /**
     * Record a skill execution outcome. If failure rate exceeds threshold,
        * update status for monitoring.
     */
    public void recordExecution(String skillName, boolean success) {
        if (success) {
            jdbc.update("""
                    UPDATE generated_skills SET executions = executions + 1, updated_at = datetime('now')
                    WHERE skill_name = ?
                    """, skillName);
        } else {
            jdbc.update("""
                    UPDATE generated_skills SET executions = executions + 1, failures = failures + 1, updated_at = datetime('now')
                    WHERE skill_name = ?
                    """, skillName);
        }

        // Check probationary status: first 3 executions
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT status, executions, failures, version FROM generated_skills WHERE skill_name = ?",
                skillName);
        if (rows.isEmpty()) return;

        Map<String, Object> skill = rows.getFirst();
        String status = (String) skill.get("status");
        int executions = ((Number) skill.get("executions")).intValue();
        int failures = ((Number) skill.get("failures")).intValue();

        if ("probationary".equals(status) && executions >= 3) {
            if (failures >= 2) {
                // Do not automatically quarantine/rollback/disable. Just mark for regeneration.
                jdbc.update("UPDATE generated_skills SET status = 'needs_regeneration' WHERE skill_name = ?", skillName);
                log.warn("Skill '{}' marked needs_regeneration (failed {}/{})", skillName, failures, executions);
            } else {
                // Passed probation
                jdbc.update("UPDATE generated_skills SET status = 'active' WHERE skill_name = ?", skillName);
                log.info("Skill '{}' passed probation ({}/{} succeeded)", skillName, executions - failures, executions);
            }
        }
    }

    /**
     * Prune old versions for a skill, keeping only the N most recent.
     */
    public void pruneOldVersions(String skillName) throws IOException {
        int keep = config.getSkills().getMaxVersionsKept();
        Path genDir = resolveWritableGeneratedDir();
        Path skillDir = genDir.resolve(skillName);

        List<Integer> versions = listVersions(skillDir);
        if (versions.size() <= keep) return;

        List<Integer> toRemove = versions.subList(0, versions.size() - keep);
        for (int v : toRemove) {
            deleteDirectory(skillDir.resolve("v" + v));
            log.info("Pruned old version v{} of skill '{}'", v, skillName);
        }
    }

    /** Get the next version number for a skill. */
    private int getNextVersion(Path skillDir) {
        if (!Files.isDirectory(skillDir)) return 1;
        List<Integer> versions = listVersions(skillDir);
        return versions.isEmpty() ? 1 : versions.getLast() + 1;
    }

    /** Current (latest) version number for a skill. */
    public int getCurrentVersion(String skillName) {
        Path genDir = resolveGeneratedDirForRead();
        Path skillDir = genDir.resolve(skillName);
        if (!Files.isDirectory(skillDir)) {
            skillDir = Path.of(config.getSkills().getCorePath()).resolve(skillName);
        }
        List<Integer> versions = listVersions(skillDir);
        return versions.isEmpty() ? 0 : versions.getLast();
    }

    /** List all version numbers for a skill directory, sorted ascending. */
    private List<Integer> listVersions(Path skillDir) {
        if (!Files.isDirectory(skillDir)) return List.of();
        try (Stream<Path> stream = Files.list(skillDir)) {
            return stream
                    .filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .filter(n -> n.matches("v\\d+"))
                    .map(n -> Integer.parseInt(n.substring(1)))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private void updateManifestVersion(String skillName, int version) throws IOException {
        Path base = Path.of(config.getSkills().getManifestPath());
        Path runtime = runtimeSkillsDir().resolve("manifest.runtime.json");

        boolean updated = updateManifestVersionInFile(base, skillName, version);
        if (!updated) {
            updateManifestVersionInFile(runtime, skillName, version);
        }
    }

    private boolean updateManifestVersionInFile(Path manifestPath, String skillName, int version) throws IOException {
        if (manifestPath == null || !Files.exists(manifestPath)) return false;
        if (!Files.isWritable(manifestPath)) return false;

        ObjectNode root = (ObjectNode) mapper.readTree(manifestPath.toFile());
        ArrayNode skills = (ArrayNode) root.get("skills");
        if (skills == null) return false;
        boolean found = false;
        for (int i = 0; i < skills.size(); i++) {
            if (skillName.equals(skills.get(i).path("name").asText())) {
                ((ObjectNode) skills.get(i)).put("version", version);
                found = true;
                break;
            }
        }
        if (!found) return false;
        mapper.writerWithDefaultPrettyPrinter().writeValue(manifestPath.toFile(), root);
        return true;
    }

    private Path runtimeSkillsDir() {
        try {
            Path db = Path.of(config.getDatabase().getPath()).toAbsolutePath().normalize();
            Path dataDir = db.getParent() != null ? db.getParent() : Path.of("./data");
            return dataDir.resolve("skills");
        } catch (Exception e) {
            return Path.of("./data/skills");
        }
    }

    private Path resolveWritableGeneratedDir() throws IOException {
        Path preferred = Path.of(config.getSkills().getGeneratedPath());
        if (tryEnsureWritableDir(preferred)) {
            return preferred;
        }
        Path fallback = runtimeSkillsDir().resolve("generated");
        Files.createDirectories(fallback);
        return fallback;
    }

    private Path resolveGeneratedDirForRead() {
        Path preferred = Path.of(config.getSkills().getGeneratedPath());
        if (Files.isDirectory(preferred)) return preferred;
        Path fallback = runtimeSkillsDir().resolve("generated");
        return Files.isDirectory(fallback) ? fallback : preferred;
    }

    private boolean tryEnsureWritableDir(Path dir) {
        try {
            Files.createDirectories(dir);
            Path probe = dir.resolve(".write_test");
            Files.writeString(probe, "ok", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.deleteIfExists(probe);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void deleteDirectory(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    log.warn("Failed to delete {}: {}", p, e.getMessage());
                }
            });
        }
    }
}
