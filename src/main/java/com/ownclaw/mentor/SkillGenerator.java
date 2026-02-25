package com.ownclaw.mentor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownclaw.llm.*;
import com.ownclaw.observability.EventLogService;
import com.ownclaw.skills.SkillValidator;
import com.ownclaw.skills.SkillVersionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Generates new Python skills using the Mentor (cloud LLM).
 * The pipeline:
 * 1. Mentor generates skill code based on a task description
 * 2. SkillValidator runs static checks and sandbox dry run
 * 3. If validation fails, send errors back to Mentor for retry (up to 2 attempts)
 * 4. SkillVersionManager writes the skill to disk and updates the manifest
 */
@Service
public class SkillGenerator {

    private static final Logger log = LoggerFactory.getLogger(SkillGenerator.class);
    private static final int MAX_GENERATION_RETRIES = 2;

    private static final String GENERATION_PROMPT = """
            You are a Python skill generator for an autonomous agent system.
            
            Generate a Python script that implements the requested capability.
            
            REQUIREMENTS:
            1. Script must be directly executable (with 'if __name__ == "__main__"' block)
            2. Read input as JSON from stdin: `params = json.loads(sys.stdin.read())`
            3. Emit output as JSON lines to stdout using this protocol:
               - Progress: {"type": "progress", "message": "..."}
               - Result:   {"type": "result", "status": "success"|"error", "output": {...}}
            4. Handle errors gracefully — catch exceptions and emit error results
            5. Use only standard library + common packages
            6. Do NOT use subprocess, os.system, or ctypes. For shell commands, use the
               shell_command skill in the plan instead. eval/exec are allowed.
            7. Keep the script under 500 lines
            
            OUTPUT FORMAT (respond with ONLY this JSON, no other text):
            {
              "name": "skill_name_lowercase_underscored",
              "summary": "One-line description of what this skill does",
              "keywords": ["keyword1", "keyword2", ...],
              "params": ["param1", "param2?"],
              "credentials": [],
              "requirements": "package1>=1.0\\npackage2",
              "script": "#!/usr/bin/env python3\\nimport json\\nimport sys\\n...",
              "test_params": {"param1": "test_value"}
            }
            
            NOTES:
            - "params" uses ? suffix for optional parameters
            - "requirements" is the content of requirements.txt (empty string if only stdlib)
            - "test_params" should be safe test values that won't cause side effects
            - "credentials" lists env var names the skill needs (e.g., ["API_KEY"])
            """;

    private final OpenAiProvider openAi;
    private final SkillValidator validator;
    private final SkillVersionManager versionManager;
    private final EventLogService eventLog;
    private final ObjectMapper mapper;

    public SkillGenerator(OpenAiProvider openAi, SkillValidator validator,
                          SkillVersionManager versionManager, EventLogService eventLog,
                          ObjectMapper mapper) {
        this.openAi = openAi;
        this.validator = validator;
        this.versionManager = versionManager;
        this.eventLog = eventLog;
        this.mapper = mapper;
    }

    /**
     * Generate a new skill based on a task description.
     *
     * @param taskDescription what the skill should do
     * @param userId          user who requested the skill
     * @param taskId          task ID for logging
     * @return generation result with skill name and status
     */
    public GenerationResult generate(String taskDescription, String userId, String taskId) {
                return generateInternal(null, taskDescription, userId, taskId);
        }

        /**
         * Generate a new skill with an exact expected name.
         * Useful when the Mentor references a skill name that doesn't exist yet.
         */
        public GenerationResult generateForName(String expectedSkillName, String taskDescription,
                                                                                        String userId, String taskId) {
                if (expectedSkillName == null || expectedSkillName.isBlank()) {
                        return generate(taskDescription, userId, taskId);
                }
                return generateInternal(expectedSkillName, taskDescription, userId, taskId);
        }

        private GenerationResult generateInternal(String expectedSkillName, String taskDescription,
                                                                                         String userId, String taskId) {
        String lastError = null;

                String expectedSanitized = expectedSkillName != null && !expectedSkillName.isBlank()
                                ? expectedSkillName.toLowerCase().replaceAll("[^a-z0-9_]", "_")
                                : null;

        for (int attempt = 0; attempt <= MAX_GENERATION_RETRIES; attempt++) {
            try {
                String prompt;
                if (attempt == 0) {
                                        if (expectedSanitized != null) {
                                                prompt = "Generate a skill with the exact name '" + expectedSanitized + "'.\n"
                                                                + "Task:\n" + taskDescription;
                                        } else {
                                                prompt = "Generate a skill for this task:\n" + taskDescription;
                                        }
                } else {
                                        prompt = (expectedSanitized != null
                                                        ? "Generate a skill with the exact name '" + expectedSanitized + "'.\nTask:\n" + taskDescription
                                                        : "Generate a skill for this task:\n" + taskDescription)
                            + "\n\nPREVIOUS ATTEMPT FAILED VALIDATION:\n" + lastError
                            + "\n\nFix the issues and regenerate.";
                }

                List<LlmMessage> messages = List.of(
                        LlmMessage.system(GENERATION_PROMPT),
                        LlmMessage.user(prompt)
                );

                LlmResponse response = openAi.chat(messages, LlmRequestConfig.withJsonMode(4096));

                eventLog.log(userId, taskId, "skill.generation", "info",
                        "Mentor generating skill (attempt " + (attempt + 1) + ", "
                                + response.totalTokens() + " tokens)",
                        null, response.totalTokens());

                // Parse the generation response
                JsonNode genJson = parseGenerationResponse(response.content());
                if (genJson == null) {
                    lastError = "Failed to parse Mentor's response as valid JSON";
                    continue;
                }

                String skillName = genJson.path("name").asText("");
                String summary = genJson.path("summary").asText("");
                String script = genJson.path("script").asText("");
                String requirements = genJson.path("requirements").asText("");
                boolean interactive = genJson.path("interactive").asBoolean(false);
                String testParamsJson = genJson.has("test_params")
                        ? genJson.get("test_params").toString() : null;

                List<String> keywords = new ArrayList<>();
                genJson.path("keywords").forEach(n -> keywords.add(n.asText()));
                List<String> params = new ArrayList<>();
                genJson.path("params").forEach(n -> params.add(n.asText()));
                List<String> credentials = new ArrayList<>();
                genJson.path("credentials").forEach(n -> credentials.add(n.asText()));

                if (skillName.isBlank() || script.isBlank()) {
                    lastError = "Generated skill missing name or script content";
                    continue;
                }

                                // If Mentor didn't explicitly mark interactive, infer from script usage of need_input.
                                if (!interactive && script.contains("need_input")) {
                                        interactive = true;
                                }

                // Sanitize skill name
                skillName = skillName.toLowerCase().replaceAll("[^a-z0-9_]", "_");

                                // Enforce expected skill name if provided
                                if (expectedSanitized != null && !expectedSanitized.equals(skillName)) {
                                        lastError = "Generated skill name mismatch: expected '" + expectedSanitized
                                                        + "' but got '" + skillName + "'";
                                        continue;
                                }

                // Write to disk
                Path versionDir = versionManager.createSkillVersion(skillName, script,
                        requirements.isBlank() ? null : requirements);

                // Validate
                SkillValidator.ValidationResult validation = validator.validate(
                        versionDir, skillName, testParamsJson);

                if (!validation.passed()) {
                    lastError = validation.summary();
                    log.warn("Skill '{}' failed validation (attempt {}): {}",
                            skillName, attempt + 1, lastError);
                    eventLog.warn(userId, taskId, "skill.validation_failed",
                            "Skill '" + skillName + "' attempt " + (attempt + 1) + ": " + lastError);

                    // Don't delete — Mentor may succeed on next attempt with a new version
                    continue;
                }

                // Register in manifest and DB
                int version = versionManager.getCurrentVersion(skillName);
                if (version == 0) version = 1;
                versionManager.registerSkill(skillName, summary, keywords, params,
                        credentials, version, interactive, userId);

                eventLog.info(userId, taskId, "skill.created",
                        "New skill created: '" + skillName + "' v" + version);

                log.info("Successfully generated skill '{}' v{}", skillName, version);

                return new GenerationResult(true, skillName, version, summary,
                        validation.warnings());

            } catch (Exception e) {
                lastError = e.getMessage();
                log.warn("Skill generation attempt {} failed: {}", attempt + 1, e.getMessage());
            }
        }

        eventLog.error(userId, taskId, "skill.generation_failed",
                "Failed to generate skill after " + (MAX_GENERATION_RETRIES + 1) + " attempts: " + lastError);

        return new GenerationResult(false, null, 0,
                "Failed to generate skill: " + lastError, List.of());
    }

    private JsonNode parseGenerationResponse(String raw) {
        try {
            String json = LlmOutputUtils.stripCodeFences(raw);
            return mapper.readTree(json);
        } catch (Exception e) {
            log.warn("Failed to parse skill generation JSON: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Result of skill generation attempt.
     */
    public record GenerationResult(
            boolean success,
            String skillName,
            int version,
            String summary,
            List<String> warnings
    ) {}
}
