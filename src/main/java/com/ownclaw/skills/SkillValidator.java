package com.ownclaw.skills;

import com.ownclaw.sandbox.SandboxManager;
import com.ownclaw.sandbox.SandboxResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Validates skill scripts before activation.
 * Two phases:
 * 1. Static analysis — structural checks without execution
 * 2. Sandbox dry run — execute with test params in restricted sandbox
 */
@Service
public class SkillValidator {

    private static final Logger log = LoggerFactory.getLogger(SkillValidator.class);

    private static final long MAX_SCRIPT_SIZE = 50 * 1024;  // 50KB
    private static final int MAX_REQUIREMENTS = 10;
    private static final int DRY_RUN_TIMEOUT_SEC = 15;

    /** Patterns that indicate dangerous or unsandboxed operations. */
    private static final Pattern BANNED_IMPORTS = Pattern.compile(
            "(?m)^\\s*(import\\s+ctypes|from\\s+ctypes|import\\s+subprocess|"
                    + "os\\.system\\s*\\(|os\\.popen\\s*\\(|exec\\s*\\(|eval\\s*\\()");

    /** Must have a main entry point. */
    private static final Pattern MAIN_GUARD = Pattern.compile(
            "if\\s+__name__\\s*==\\s*['\"]__main__['\"]");

    /** Must produce at least one JSON result output. */
    private static final Pattern RESULT_OUTPUT = Pattern.compile(
            "\"type\"\\s*:\\s*\"result\"");

    private final SandboxManager sandbox;
    private final PythonEnvironmentService pythonEnv;

    public SkillValidator(SandboxManager sandbox, PythonEnvironmentService pythonEnv) {
        this.sandbox = sandbox;
        this.pythonEnv = pythonEnv;
    }

    /**
     * Validate a skill. Runs static checks and optionally a sandbox dry run.
     *
     * @param versionDir  path to the skill version directory containing skill.py
     * @param skillName   skill name (for env resolution)
     * @param testParams  JSON string of test parameters for dry run (null to skip dry run)
     * @return validation result
     */
    public ValidationResult validate(Path versionDir, String skillName, String testParams) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        // Phase 1: Static checks
        Path scriptPath = versionDir.resolve("skill.py");

        if (!Files.exists(scriptPath)) {
            errors.add("skill.py not found in " + versionDir);
            return new ValidationResult(false, errors, warnings);
        }

        String scriptContent;
        try {
            long size = Files.size(scriptPath);
            if (size > MAX_SCRIPT_SIZE) {
                errors.add("skill.py exceeds maximum size: " + size + " bytes (max: " + MAX_SCRIPT_SIZE + ")");
                return new ValidationResult(false, errors, warnings);
            }
            scriptContent = Files.readString(scriptPath, StandardCharsets.UTF_8);
        } catch (IOException e) {
            errors.add("Failed to read skill.py: " + e.getMessage());
            return new ValidationResult(false, errors, warnings);
        }

        // Check main guard
        if (!MAIN_GUARD.matcher(scriptContent).find()) {
            errors.add("Missing __main__ guard: skill.py must have 'if __name__ == \"__main__\"' block");
        }

        // Check result output pattern
        if (!RESULT_OUTPUT.matcher(scriptContent).find()) {
            warnings.add("No '\"type\": \"result\"' pattern found — skill may not produce protocol-compliant output");
        }

        // Check for banned patterns
        var bannedMatcher = BANNED_IMPORTS.matcher(scriptContent);
        while (bannedMatcher.find()) {
            String match = bannedMatcher.group().strip();
            warnings.add("Potentially unsafe pattern detected: " + match);
        }

        // Check requirements.txt
        Path reqPath = versionDir.resolve("requirements.txt");
        if (Files.exists(reqPath)) {
            try {
                List<String> requirements = Files.readAllLines(reqPath, StandardCharsets.UTF_8)
                        .stream().filter(l -> !l.isBlank() && !l.startsWith("#")).toList();

                if (requirements.size() > MAX_REQUIREMENTS) {
                    errors.add("Too many requirements: " + requirements.size() + " (max: " + MAX_REQUIREMENTS + ")");
                }

                for (String req : requirements) {
                    if (req.contains("git+") || req.contains("http://") || req.contains("https://")) {
                        errors.add("URL/git dependency not allowed: " + req);
                    }
                }
            } catch (IOException e) {
                warnings.add("Could not read requirements.txt: " + e.getMessage());
            }
        }

        // If static checks failed, don't bother with dry run
        if (!errors.isEmpty()) {
            return new ValidationResult(false, errors, warnings);
        }

        // Phase 2: Sandbox dry run (if test params provided)
        if (testParams != null) {
            String python = pythonEnv.resolvePython(versionDir, skillName);
            if (python == null || python.isBlank()) {
                warnings.add("Skipping dry run: no Python interpreter available");
            } else {
                try {
                    SandboxResult result = sandbox.execute(python, scriptPath, versionDir,
                            testParams, Map.of(), DRY_RUN_TIMEOUT_SEC);

                    if (result.timedOut()) {
                        errors.add("Dry run timed out after " + DRY_RUN_TIMEOUT_SEC + "s");
                    } else if (result.exitCode() != 0) {
                        String stderr = result.stderr().length() > 500
                                ? result.stderr().substring(0, 500) + "..." : result.stderr();
                        errors.add("Dry run failed (exit code " + result.exitCode() + "): " + stderr);
                    } else {
                        // Check stdout for valid result
                        if (!result.stdout().contains("\"type\"")) {
                            warnings.add("Dry run produced no JSON output");
                        }
                        if (result.stderr().contains("Traceback")) {
                            warnings.add("Dry run stderr contains traceback (possible partial failure)");
                        }
                    }
                } catch (Exception e) {
                    warnings.add("Dry run error: " + e.getMessage());
                }
            }
        }

        boolean passed = errors.isEmpty();
        if (passed) {
            log.info("Skill '{}' passed validation (warnings: {})", skillName, warnings.size());
        } else {
            log.warn("Skill '{}' failed validation: {}", skillName, errors);
        }

        return new ValidationResult(passed, errors, warnings);
    }

    /**
     * Result of skill validation.
     */
    public record ValidationResult(boolean passed, List<String> errors, List<String> warnings) {
        public String summary() {
            if (passed && warnings.isEmpty()) return "Passed";
            if (passed) return "Passed with " + warnings.size() + " warning(s)";
            return "Failed: " + String.join("; ", errors);
        }
    }
}
