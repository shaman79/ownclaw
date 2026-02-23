package com.ownclaw.skills;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownclaw.config.OwnClawConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Loads and queries the skill manifest (manifest.json).
 * The manifest is a lightweight index of all available skills.
 */
@Service
public class SkillManifest {

    private static final Logger log = LoggerFactory.getLogger(SkillManifest.class);

    private final OwnClawConfig config;
    private final ObjectMapper mapper;
    private volatile List<SkillModel> skills = Collections.emptyList();

    public SkillManifest(OwnClawConfig config, ObjectMapper mapper) {
        this.config = config;
        this.mapper = mapper;
    }

    @PostConstruct
    public void load() {
        Path manifestPath = Path.of(config.getSkills().getManifestPath());
        if (!Files.exists(manifestPath)) {
            log.warn("Skill manifest not found: {}. Starting with empty manifest.", manifestPath);
            return;
        }
        try {
            JsonNode root = mapper.readTree(manifestPath.toFile());
            JsonNode skillsNode = root.path("skills");
            skills = mapper.convertValue(skillsNode, new TypeReference<>() {});
            log.info("Loaded {} skills from manifest", skills.size());
        } catch (IOException e) {
            log.error("Failed to load skill manifest: {}", e.getMessage());
        }
    }

    /** Reload manifest from disk (e.g. after a skill is added/updated). */
    public void reload() {
        load();
    }

    /** All skills in the manifest. */
    public List<SkillModel> allSkills() {
        return skills;
    }

    /** Find a skill by name. */
    public Optional<SkillModel> findByName(String name) {
        return skills.stream().filter(s -> s.name().equals(name)).findFirst();
    }

    /**
     * Get the manifest as a compact JSON string suitable for including in LLM prompts.
     * Only includes name, summary, params, keywords — enough for matching.
     */
    public String toPromptSnippet() {
        if (skills.isEmpty()) return "No skills available.";
        var sb = new StringBuilder();
        for (SkillModel s : skills) {
            sb.append("- ").append(s.name()).append(": ").append(s.summary());
            sb.append(" [params: ").append(String.join(", ", s.params())).append("]");
            sb.append(" [keywords: ").append(String.join(", ", s.keywords())).append("]");
            sb.append('\n');
        }
        return sb.toString();
    }
}
