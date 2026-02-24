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
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        try {
            List<SkillModel> base = loadManifestIfExists(Path.of(config.getSkills().getManifestPath()));
            List<SkillModel> runtime = loadManifestIfExists(runtimeManifestPath());

            // Merge by name: runtime overrides base
            Map<String, SkillModel> merged = new LinkedHashMap<>();
            for (SkillModel s : base) merged.put(s.name(), s);
            for (SkillModel s : runtime) merged.put(s.name(), s);

            skills = new ArrayList<>(merged.values());

            if (skills.isEmpty()) {
                log.warn("No skills loaded from manifests (base={}, runtime={})",
                        config.getSkills().getManifestPath(), runtimeManifestPath());
            } else {
                log.info("Loaded {} skills (base={}, runtime={})",
                        skills.size(), base.size(), runtime.size());
            }
        } catch (IOException e) {
            log.error("Failed to load skill manifest: {}", e.getMessage());
        }
    }

    private List<SkillModel> loadManifestIfExists(Path manifestPath) throws IOException {
        if (manifestPath == null || !Files.exists(manifestPath)) {
            return List.of();
        }
        JsonNode root = mapper.readTree(manifestPath.toFile());
        JsonNode skillsNode = root.path("skills");
        if (skillsNode == null || skillsNode.isMissingNode()) {
            return List.of();
        }
        return mapper.convertValue(skillsNode, new TypeReference<>() {});
    }

    private Path runtimeManifestPath() {
        return runtimeSkillsDir().resolve("manifest.runtime.json");
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
