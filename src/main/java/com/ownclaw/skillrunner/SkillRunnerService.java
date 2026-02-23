package com.ownclaw.skillrunner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.core.StepResult;
import com.ownclaw.core.TaskStep;
import com.ownclaw.observability.ChatStatusEmitter;
import com.ownclaw.observability.ChatStatusEmitter.StatusMessage;
import com.ownclaw.observability.EventLogService;
import com.ownclaw.sandbox.SandboxManager;
import com.ownclaw.sandbox.SandboxResult;
import com.ownclaw.skills.PythonEnvironmentService;
import com.ownclaw.skills.SkillLoader;
import com.ownclaw.skills.SkillManifest;
import com.ownclaw.skills.SkillModel;
import com.ownclaw.skills.SkillVersionManager;
import com.ownclaw.users.CredentialGrantService;
import com.ownclaw.users.CredentialVault;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Executes skill scripts in the sandbox and captures results.
 * Handles the I/O protocol: JSON params → stdin, JSON-lines ← stdout.
 */
@Service
public class SkillRunnerService {

    private static final Logger log = LoggerFactory.getLogger(SkillRunnerService.class);

    private final SandboxManager sandbox;
    private final SkillLoader skillLoader;
    private final SkillManifest skillManifest;
    private final PythonEnvironmentService pythonEnv;
    private final CredentialGrantService credentialGrants;
    private final CredentialVault credentialVault;
    private final SkillVersionManager versionManager;
    private final SkillOutputParser outputParser;
    private final EventLogService eventLog;
    private final ChatStatusEmitter statusEmitter;
    private final SkillInteractionHandler interactionHandler;
    private final ObjectMapper mapper;
    private final int defaultTimeout;

    /** Last failure context captured during execution, for self-healing diagnosis. */
    private volatile SkillFailureContext lastFailureContext;

    public SkillRunnerService(SandboxManager sandbox, SkillLoader skillLoader,
                              SkillManifest skillManifest,
                              PythonEnvironmentService pythonEnv,
                              CredentialGrantService credentialGrants,
                              CredentialVault credentialVault,
                              SkillVersionManager versionManager,
                              SkillOutputParser outputParser, EventLogService eventLog,
                              ChatStatusEmitter statusEmitter,
                              SkillInteractionHandler interactionHandler,
                              ObjectMapper mapper,
                              OwnClawConfig config) {
        this.sandbox = sandbox;
        this.skillLoader = skillLoader;
        this.skillManifest = skillManifest;
        this.pythonEnv = pythonEnv;
        this.credentialGrants = credentialGrants;
        this.credentialVault = credentialVault;
        this.versionManager = versionManager;
        this.outputParser = outputParser;
        this.eventLog = eventLog;
        this.statusEmitter = statusEmitter;
        this.interactionHandler = interactionHandler;
        this.mapper = mapper;
        this.defaultTimeout = config.getSandbox().getDefaultTimeout();
    }

    /**
     * Execute a single plan step by running its skill in the sandbox.
     *
     * @param step           the plan step to execute
     * @param userId         current user ID
     * @param taskId         current task ID
     * @param resolvedParams parameters with $N.output references already resolved
     * @param credentials    approved credentials to inject as env vars
     * @return step execution result
     */
    public StepResult executeStep(TaskStep step, String userId, String taskId,
                                  Map<String, Object> resolvedParams,
                                  Map<String, String> credentials) {

        statusEmitter.emit(userId, StatusMessage.Type.STEP,
                "Running skill: " + step.skill());

        // Clear previous failure context
        this.lastFailureContext = null;

        // Resolve skill script path
        Optional<Path> scriptOpt = skillLoader.resolveScript(step.skill());
        if (scriptOpt.isEmpty()) {
            eventLog.error(userId, taskId, "skill.not_found",
                    "Skill not found: " + step.skill());
            return StepResult.failure(step.id(), "Skill not found: " + step.skill(), -1, 0);
        }

        Path script = scriptOpt.get();
        Path workDir = script.getParent();

        // Check credential grants for this skill
        Optional<SkillModel> skillMeta = skillManifest.findByName(step.skill());
        if (skillMeta.isPresent() && !skillMeta.get().credentials().isEmpty()) {
            List<String> required = skillMeta.get().credentials();
            if (!credentialGrants.allGranted(userId, step.skill(), required)) {
                // Credentials not granted — emit a request to the user and fail the step
                statusEmitter.emit(userId, StatusMessage.Type.CREDENTIAL,
                        "\uD83D\uDD11 Skill '" + step.skill() + "' requires credentials: "
                                + String.join(", ", required)
                                + ". Use /grant " + step.skill() + " to approve.");
                eventLog.warn(userId, taskId, "credential.denied",
                        "Skill " + step.skill() + " requires unapproved credentials: " + required);
                return StepResult.failure(step.id(),
                        "Credential access not granted for: " + String.join(", ", required), -1, 0);
            }
            eventLog.info(userId, taskId, "credential.granted",
                    "Credentials approved for skill " + step.skill());
        }

        // Build stdin JSON
        String stdinJson;
        try {
            stdinJson = mapper.writeValueAsString(resolvedParams);
        } catch (Exception e) {
            return StepResult.failure(step.id(), "Failed to serialize params: " + e.getMessage(), -1, 0);
        }

        // Build env vars — merge caller-provided + vault-stored credentials
        Map<String, String> envVars = new HashMap<>();
        if (credentials != null) {
            envVars.putAll(credentials);
        }
        // Load actual credential values from the encrypted vault
        if (skillMeta.isPresent() && !skillMeta.get().credentials().isEmpty()) {
            Map<String, String> vaultCreds = credentialVault.getCredentials(
                    userId, skillMeta.get().credentials());
            envVars.putAll(vaultCreds);
        }

        // Resolve Python executable (venv if skill has requirements.txt)
        String python = pythonEnv.resolvePython(workDir, step.skill());

        // Pre-flight: verify Python is available
        if (python == null || python.isBlank()) {
            String errMsg = "No Python interpreter available. Install Python 3 and run /setup.";
            eventLog.error(userId, taskId, "skill.no_python", errMsg);
            return StepResult.failure(step.id(), errMsg, -1, 0);
        }

        // Execute in sandbox — use interactive mode if skill declares interactive: true
        boolean isInteractive = skillMeta.isPresent() && skillMeta.get().interactive();
        SandboxResult sandboxResult;
        if (isInteractive) {
            log.info("Executing interactive skill: {}", step.skill());
            sandboxResult = sandbox.executeInteractive(python, script, workDir, stdinJson, envVars,
                    defaultTimeout, prompt -> {
                        try {
                            return interactionHandler.requestInput(userId, taskId, prompt);
                        } catch (Exception e) {
                            log.warn("Interactive input failed for skill {}: {}", step.skill(), e.getMessage());
                            return "";
                        }
                    });
        } else {
            sandboxResult = sandbox.execute(python, script, workDir, stdinJson, envVars, defaultTimeout);
        }

        // Parse output
        List<SkillOutputParser.SkillOutput> outputs = outputParser.parse(sandboxResult.stdout());

        // Forward progress and need_input messages to user
        for (var output : outputs) {
            if (output.type() == SkillOutputParser.SkillOutput.Type.PROGRESS) {
                statusEmitter.emit(userId, StatusMessage.Type.PROGRESS, output.content());
            } else if (output.type() == SkillOutputParser.SkillOutput.Type.NEED_INPUT) {
                // Skill requested user input — emit prompt and wait.
                // Note: in the current one-shot sandbox model, the process has already exited
                // so we can't feed input back. We log the request for awareness.
                // Full interactive mode (keeping process alive) is possible via executeInteractive
                // when the sandbox supports it. For now, the skill should handle missing input gracefully.
                log.info("Skill {} emitted need_input (post-execution): {}", step.skill(), output.content());
                statusEmitter.emit(userId, StatusMessage.Type.NEED_INPUT, output.content());
            }
        }

        // Extract result
        SkillOutputParser.SkillOutput resultOutput = outputParser.extractResult(outputs);
        String outputText;
        boolean success;

        if (sandboxResult.timedOut()) {
            outputText = "Skill timed out after " + defaultTimeout + "s";
            success = false;
        } else if (!sandboxResult.isSuccess()) {
            outputText = sandboxResult.stderr().isBlank()
                    ? "Exit code " + sandboxResult.exitCode()
                    : sandboxResult.stderr();
            success = false;
        } else if (resultOutput != null) {
            outputText = resultOutput.content();
            success = resultOutput.isSuccess();
        } else {
            // No structured result — use raw stdout
            outputText = sandboxResult.stdout();
            success = sandboxResult.exitCode() == 0;
        }

        // Log the execution with error details for debugging
        String severity = success ? "info" : "warn";
        String summary = step.skill() + " -> " + (success ? "success" : "failed");
        if (!success) {
            String errorDetail = outputText != null && outputText.length() > 300
                    ? outputText.substring(0, 300) + "..." : outputText;
            summary += " | " + (errorDetail != null ? errorDetail : "no output");
            log.warn("Skill {} failed (exit={}, {}ms): {}", step.skill(),
                    sandboxResult.exitCode(), sandboxResult.durationMs(), errorDetail);
        }
        eventLog.log(userId, taskId, "skill.executed", severity, summary, null, 0);

        // Phase 2: Record execution for probation tracking
        try {
            versionManager.recordExecution(step.skill(), success);
        } catch (Exception e) {
            log.debug("Version tracking non-critical: {}", e.getMessage());
        }

        // Capture failure context for self-healing diagnosis
        if (!success) {
            try {
                String source = Files.readString(script, StandardCharsets.UTF_8);
                int version = versionManager.getCurrentVersion(step.skill());
                this.lastFailureContext = new SkillFailureContext(
                        step.skill(), version > 0 ? version : 1, source,
                        resolvedParams,
                        sandboxResult.stdout(), sandboxResult.stderr(),
                        sandboxResult.exitCode(), sandboxResult.durationMs(),
                        sandboxResult.timedOut(),
                        resultOutput != null ? resultOutput.content() : null,
                        outputText);
            } catch (Exception e) {
                log.debug("Failed to capture failure context: {}", e.getMessage());
            }
        }

        return success
                ? StepResult.successWithContext(step.id(), outputText, sandboxResult.durationMs(),
                        step.skill(), resolvedParams)
                : StepResult.failureWithContext(step.id(), outputText, sandboxResult.exitCode(),
                        sandboxResult.durationMs(), step.skill(), resolvedParams);
    }

    /**
     * Get the failure context from the last failed skill execution.
     * Returns null if the last execution succeeded or if capture failed.
     * Used by the self-healing loop in TaskOrchestrator.
     */
    public SkillFailureContext getLastFailureContext() {
        return lastFailureContext;
    }
}
