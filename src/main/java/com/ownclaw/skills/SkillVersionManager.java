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
        Path genDir = Path.of(config.getSkills().getGeneratedPath());
        Files.createDirectories(genDir);

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
                              int version, String createdBy) throws IOException {
        // Add to manifest.json
        Path manifestPath = Path.of(config.getSkills().getManifestPath());
        ObjectNode root;
        if (Files.exists(manifestPath)) {
            root = (ObjectNode) mapper.readTree(manifestPath.toFile());
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
        entry.put("interactive", false);
        skillsArray.add(entry);

        mapper.writerWithDefaultPrettyPrinter().writeValue(manifestPath.toFile(), root);
        log.info("Updated manifest with skill '{}' v{}", skillName, version);

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
     * auto-disable or rollback.
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

        if ("probationary".equals(status)) {
            if (executions >= 3) {
                if (failures >= 2) {
                    // Too many failures — try rollback, or disable
                    log.warn("Skill '{}' failed {}/{} during probation, attempting rollback", skillName, failures, executions);
                    try {
                        if (!rollback(skillName)) {
                            // No previous version — disable
                            jdbc.update("UPDATE generated_skills SET status = 'disabled' WHERE skill_name = ?", skillName);
                            log.warn("Skill '{}' disabled (no previous version to rollback)", skillName);
                        }
                    } catch (IOException e) {
                        log.error("Rollback failed for '{}': {}", skillName, e.getMessage());
                        jdbc.update("UPDATE generated_skills SET status = 'failed' WHERE skill_name = ?", skillName);
                    }
                } else {
                    // Passed probation
                    jdbc.update("UPDATE generated_skills SET status = 'active' WHERE skill_name = ?", skillName);
                    log.info("Skill '{}' passed probation ({}/{} succeeded)", skillName, executions - failures, executions);
                }
            }
        }
    }

    /**
     * Prune old versions for a skill, keeping only the N most recent.
     */
    public void pruneOldVersions(String skillName) throws IOException {
        int keep = config.getSkills().getMaxVersionsKept();
        Path genDir = Path.of(config.getSkills().getGeneratedPath());
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
        Path genDir = Path.of(config.getSkills().getGeneratedPath());
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
        Path manifestPath = Path.of(config.getSkills().getManifestPath());
        if (!Files.exists(manifestPath)) return;

        ObjectNode root = (ObjectNode) mapper.readTree(manifestPath.toFile());
        ArrayNode skills = (ArrayNode) root.get("skills");
        for (int i = 0; i < skills.size(); i++) {
            if (skillName.equals(skills.get(i).path("name").asText())) {
                ((ObjectNode) skills.get(i)).put("version", version);
                break;
            }
        }
        mapper.writerWithDefaultPrettyPrinter().writeValue(manifestPath.toFile(), root);
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
