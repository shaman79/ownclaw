package com.ownclaw.mentor;

import com.ownclaw.observability.EventLogService;
import com.ownclaw.skills.SkillLoader;
import com.ownclaw.skills.SkillValidator;
import com.ownclaw.skills.SkillVersionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.file.Path;

/**
 * Repairs skill scripts based on a diagnosis from the SkillDiagnostician.
 * Creates a new version of the skill with the fix, validates it,
 * and activates it or rolls back on validation failure.
 *
 * <p>Part of the self-healing loop: Fail → Diagnose → Repair → Validate → Retry
 */
@Service
public class SkillRepairer {

    private static final Logger log = LoggerFactory.getLogger(SkillRepairer.class);

    private final SkillVersionManager versionManager;
    private final SkillValidator validator;
    private final SkillLoader skillLoader;
    private final EventLogService eventLog;
    private final JdbcTemplate jdbc;

    public SkillRepairer(SkillVersionManager versionManager, SkillValidator validator,
                         SkillLoader skillLoader, EventLogService eventLog,
                         JdbcTemplate jdbc) {
        this.versionManager = versionManager;
        this.validator = validator;
        this.skillLoader = skillLoader;
        this.eventLog = eventLog;
        this.jdbc = jdbc;
    }

    /**
     * Apply a diagnosed fix to a skill.
     *
     * <ol>
     *   <li>Create a new version directory with the fixed script</li>
     *   <li>Validate the new version (static checks + optional dry run)</li>
     *   <li>If validation passes, activate the new version</li>
     *   <li>If validation fails, roll back and report</li>
     *   <li>Log the repair attempt to skill_repair_log</li>
     * </ol>
     *
     * @param skillName skill to repair
     * @param diagnosis the diagnosis containing the fix
     * @param userId    for logging
     * @param taskId    for logging
     * @return repair result
     */
    public RepairResult repair(String skillName, SkillDiagnostician.Diagnosis diagnosis,
                               String userId, String taskId) {

        if (!diagnosis.hasCodeFix()) {
            log.info("Diagnosis for '{}' has no code fix (category={})", skillName, diagnosis.category());
            logRepairAttempt(skillName, diagnosis, false, "No code fix in diagnosis", userId, taskId);
            return new RepairResult(false, skillName, 0,
                    "No code fix available — " + diagnosis.rootCause(), null);
        }

        int previousVersion = versionManager.getCurrentVersion(skillName);
        log.info("Attempting to repair skill '{}' (current v{}) based on diagnosis: {}",
                skillName, previousVersion, diagnosis.category());

        try {
            // Step 1: Create new version with fixed script.
            // Strip markdown code fences — LLMs sometimes wrap the repaired script in ```python ... ```
            // which causes SyntaxError: invalid syntax on line 1.
            String fixedScript = SkillGenerator.stripMarkdownFences(diagnosis.fixedScript());
            String requirements = diagnosis.requirements();
            Path versionDir = versionManager.createSkillVersion(
                    skillName, fixedScript, requirements);

            int newVersion = versionManager.getCurrentVersion(skillName);

            eventLog.info(userId, taskId, "skill.repair_attempt",
                    "Repairing " + skillName + " v" + previousVersion + " → v" + newVersion
                            + " | cause: " + diagnosis.rootCause());

            // Step 2: Validate the new version including a sandbox dry-run.
            // Pass empty params "{}" — this verifies imports resolve and the script
            // doesn't crash on startup, catching dependency regressions before runtime.
            SkillValidator.ValidationResult validation = validator.validate(
                    versionDir, skillName, "{}");

            if (!validation.passed()) {
                // Validation failed — roll back
                log.warn("Repaired skill '{}' v{} failed validation: {}",
                        skillName, newVersion, validation.summary());

                try {
                    versionManager.rollback(skillName);
                } catch (Exception e) {
                    log.warn("Rollback after failed repair validation: {}", e.getMessage());
                }

                logRepairAttempt(skillName, diagnosis, false,
                        "Validation failed: " + validation.summary(), userId, taskId);

                eventLog.warn(userId, taskId, "skill.repair_validation_failed",
                        skillName + " v" + newVersion + " fix failed validation: " + validation.summary());

                return new RepairResult(false, skillName, newVersion,
                        "Fix failed validation: " + validation.summary(), validation.warnings());
            }

            // Step 3: Validation passed — skill is ready
            log.info("Skill '{}' v{} repair passed validation", skillName, newVersion);

            logRepairAttempt(skillName, diagnosis, true,
                    "Repair applied and validated", userId, taskId);

            eventLog.info(userId, taskId, "skill.repaired",
                    skillName + " repaired: v" + previousVersion + " → v" + newVersion
                            + " | " + diagnosis.rootCause());

            return new RepairResult(true, skillName, newVersion,
                    "Skill repaired successfully", validation.warnings());

        } catch (Exception e) {
            log.error("Repair failed for '{}': {}", skillName, e.getMessage(), e);

            logRepairAttempt(skillName, diagnosis, false,
                    "Exception: " + e.getMessage(), userId, taskId);

            eventLog.error(userId, taskId, "skill.repair_failed",
                    "Failed to repair " + skillName + ": " + e.getMessage());

            return new RepairResult(false, skillName, 0,
                    "Repair failed: " + e.getMessage(), null);
        }
    }

    private void logRepairAttempt(String skillName, SkillDiagnostician.Diagnosis diagnosis,
                                   boolean success, String outcome,
                                   String userId, String taskId) {
        try {
            jdbc.update("""
                    INSERT INTO skill_repair_log
                        (skill_name, task_id, user_id, category, root_cause, fixable,
                         confidence, success, outcome, lesson)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    skillName, taskId, userId,
                    diagnosis.category(), diagnosis.rootCause(), diagnosis.fixable(),
                    diagnosis.confidence(), success, outcome,
                    diagnosis.lesson());
        } catch (Exception e) {
            log.debug("Failed to log repair attempt: {}", e.getMessage());
        }
    }

    /**
     * Result of a repair attempt.
     */
    public record RepairResult(
            boolean success,
            String skillName,
            int newVersion,
            String summary,
            java.util.List<String> warnings
    ) {}
}
