package com.ownclaw.skills;

import com.ownclaw.config.OwnClawConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Resolves a skill name to its on-disk directory and script path.
 * Looks in core → generated → user directories (in that order).
 */
@Service
public class SkillLoader {

    private static final Logger log = LoggerFactory.getLogger(SkillLoader.class);

    private final OwnClawConfig.Skills skillsConfig;

    public SkillLoader(OwnClawConfig config) {
        this.skillsConfig = config.getSkills();
    }

    /**
     * Resolve a skill name to the path of its skill.py script.
     * Checks: core/{name}/v{latest}/skill.py → generated/{name}/current/skill.py
     *
     * @return path to skill.py if found
     */
    public Optional<Path> resolveScript(String skillName) {
        // Try generated skills first — these are evolved/patched versions that override core.
        // Self-healing and skill generation write to generated/, so they must take priority.
        Optional<Path> genPath = findInDirectory(Path.of(skillsConfig.getGeneratedPath()), skillName);
        if (genPath.isPresent()) return genPath;

        // Fall back to core skills (factory defaults)
        return findInDirectory(Path.of(skillsConfig.getCorePath()), skillName);
    }

    /**
     * Resolve the skill's working directory (the version dir containing skill.py).
     */
    public Optional<Path> resolveDirectory(String skillName) {
        return resolveScript(skillName).map(Path::getParent);
    }

    private Optional<Path> findInDirectory(Path baseDir, String skillName) {
        Path skillDir = baseDir.resolve(skillName);
        if (!Files.isDirectory(skillDir)) return Optional.empty();

        // Check for "current" symlink/directory first
        Path current = skillDir.resolve("current");
        if (Files.isDirectory(current)) {
            Path script = current.resolve("skill.py");
            if (Files.exists(script)) return Optional.of(script);
        }

        // Fall back to highest numbered version directory
        try (var stream = Files.list(skillDir)) {
            return stream
                    .filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().matches("v\\d+"))
                    .sorted((a, b) -> {
                        int va = Integer.parseInt(a.getFileName().toString().substring(1));
                        int vb = Integer.parseInt(b.getFileName().toString().substring(1));
                        return Integer.compare(vb, va); // highest first
                    })
                    .map(p -> p.resolve("skill.py"))
                    .filter(Files::exists)
                    .findFirst();
        } catch (Exception e) {
            log.warn("Error scanning skill directory {}: {}", skillDir, e.getMessage());
            return Optional.empty();
        }
    }
}
