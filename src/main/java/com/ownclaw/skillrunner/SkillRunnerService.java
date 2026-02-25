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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private final NativeSkillRegistry nativeSkillRegistry;
    private final SkillOutputParser outputParser;
    private final EventLogService eventLog;
    private final ChatStatusEmitter statusEmitter;
    private final SkillInteractionHandler interactionHandler;
    private final ObjectMapper mapper;
    private final int defaultTimeout;

    /** Last failure context captured during execution, for self-healing diagnosis. */
    private volatile SkillFailureContext lastFailureContext;

        private static final Pattern MODULE_NOT_FOUND = Pattern.compile(
            "ModuleNotFoundError: No module named ['\"]([^'\"]+)['\"]|ImportError: No module named ([A-Za-z0-9_.]+)");


        private static final Map<String, String> MODULE_TO_PACKAGE = Map.ofEntries(
            Map.entry("bs4", "beautifulsoup4"),
            Map.entry("yaml", "pyyaml"),
            Map.entry("PIL", "pillow"),
            Map.entry("cv2", "opencv-python"),
            Map.entry("sklearn", "scikit-learn"),
            Map.entry("lxml", "lxml")
        );

    public SkillRunnerService(SandboxManager sandbox, SkillLoader skillLoader,
                              SkillManifest skillManifest,
                              PythonEnvironmentService pythonEnv,
                              CredentialGrantService credentialGrants,
                              CredentialVault credentialVault,
                              SkillVersionManager versionManager,
                              NativeSkillRegistry nativeSkillRegistry,
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
        this.nativeSkillRegistry = nativeSkillRegistry;
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

        // Native skills (Java-implemented) take precedence.
        var nativeSkill = nativeSkillRegistry.find(step.skill());
        if (nativeSkill.isPresent()) {
            long start = System.currentTimeMillis();
            try {
            StepResult res = nativeSkill.get().execute(step, userId, taskId, resolvedParams);
            long duration = System.currentTimeMillis() - start;
            String sev = res.success() ? "info" : "warn";
            eventLog.log(userId, taskId, "skill.executed", sev,
                step.skill() + " -> " + (res.success() ? "success" : "failed"), null, 0);
            return new StepResult(step.id(), res.success(), res.output(), res.exitCode(),
                duration, step.skill(), resolvedParams);
            } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            String msg = e.getMessage() != null ? e.getMessage() : "Native skill failed";
            eventLog.warn(userId, taskId, "skill.executed",
                step.skill() + " -> failed | " + msg);
            return StepResult.failureWithContext(step.id(), msg, -1, duration, step.skill(), resolvedParams);
            }
        }

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

        // Resolve Python executable + any env needed for dependency fallback.
        PythonEnvironmentService.PythonResolution py = pythonEnv.resolveExecution(workDir, step.skill());
        String python = py.python();

        // Merge extra env from Python resolution (venv PATH injection, PYTHONPATH fallback, etc.).
        // PATH and PYTHONPATH are prepended rather than replaced so system entries are preserved.
        if (py.extraEnv() != null && !py.extraEnv().isEmpty()) {
            for (var entry : py.extraEnv().entrySet()) {
                if ("PYTHONPATH".equals(entry.getKey()) || "PATH".equals(entry.getKey())) {
                    String prepend = entry.getValue();
                    String existing = envVars.getOrDefault(entry.getKey(), System.getenv(entry.getKey()));
                    if (existing != null && !existing.isBlank()) {
                        envVars.put(entry.getKey(), prepend + java.io.File.pathSeparator + existing);
                    } else {
                        envVars.put(entry.getKey(), prepend);
                    }
                } else {
                    envVars.put(entry.getKey(), entry.getValue());
                }
            }
        }

        // If venv provisioning failed, we fall back to system Python.
        // Avoid warning the user here: missing deps are often recoverable via deterministic self-heal.
        try {
            if (Files.exists(workDir.resolve("requirements.txt"))
                    && python != null
                    && python.equals(pythonEnv.getSystemPython())) {
            pythonEnv.getLastProvisionError(workDir, step.skill()).ifPresent(err ->
                log.warn("Python deps provisioning failed for skill '{}': {} (using system Python)",
                    step.skill(), err)
            );
            }
        } catch (Exception ignored) {
            // non-fatal
        }

        // Pre-flight: verify Python is available
        if (python == null || python.isBlank()) {
            String errMsg = "No Python interpreter available. Install Python 3 and run /setup.";
            eventLog.error(userId, taskId, "skill.no_python", errMsg);
            return StepResult.failure(step.id(), errMsg, -1, 0);
        }

        // Execute in sandbox — enable interactive mode when the skill supports need_input.
        boolean isInteractive = shouldRunInteractive(skillMeta, script);
        SandboxResult sandboxResult = runInSandbox(isInteractive, python, script, workDir, stdinJson, envVars,
                userId, taskId, step.skill());

        // Parse output
        List<SkillOutputParser.SkillOutput> outputs = outputParser.parse(sandboxResult.stdout());

        // Forward progress and need_input messages to user
        for (var output : outputs) {
            if (output.type() == SkillOutputParser.SkillOutput.Type.PROGRESS) {
                statusEmitter.emit(userId, StatusMessage.Type.PROGRESS, output.content());
            } else if (output.type() == SkillOutputParser.SkillOutput.Type.NEED_INPUT) {
                // If we ran in interactive mode, need_input was already handled live.
                // In non-interactive mode, we can only surface it as a post-execution hint.
                if (!isInteractive) {
                    log.info("Skill {} emitted need_input (post-execution): {}", step.skill(), output.content());
                    statusEmitter.emit(userId, StatusMessage.Type.NEED_INPUT, output.content());
                }
            }
        }

        // Extract result
        SkillOutputParser.SkillOutput resultOutput = outputParser.extractResult(outputs);
        String outputText;
        boolean success;

        if (sandboxResult.timedOut()) {
            // If the skill emitted a need_input prompt before timing out, the root cause is
            // the skill waiting for user input that never arrived in this automated context.
            // Surface a specific, actionable message so the Mentor doesn't blindly retry.
            String partialOut = sandboxResult.stdout();
            boolean waitedForInput = partialOut != null && partialOut.contains("\"type\":\"need_input\"");
            if (waitedForInput) {
                outputText = "Skill timed out waiting for user input (need_input was emitted but "
                        + "no response arrived in " + defaultTimeout + "s). "
                        + "Hint for next attempt: Don't call ask_user in non-interactive runs. "
                        + "Provide the missing task details as normal tool/skill parameters "
                        + "(or route through an interactive UI that will send a "
                        + "{\"type\":\"user_input\",\"value\":...} message back), "
                        + "otherwise the request will always time out.";
            } else {
                outputText = "Skill timed out after " + defaultTimeout + "s";
            }
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

        // Deterministic self-heal: missing Python module -> pip install -> retry once.
        if (!success) {
            String missingModule = extractMissingModule(outputText);
            if (missingModule != null) {
                String pkg = mapModuleToPackage(missingModule);
                statusEmitter.emit(userId, StatusMessage.Type.PROGRESS,
                        "Self-healing " + step.skill() + "...");

                boolean installed = pythonEnv.installPackages(workDir, step.skill(), List.of(pkg));
                if (installed) {
                    PythonEnvironmentService.PythonResolution healed = pythonEnv.resolveExecution(workDir, step.skill());
                    String healedPython = healed.python();

                    Map<String, String> retryEnv = new HashMap<>(envVars);
                    if (healed.extraEnv() != null && !healed.extraEnv().isEmpty()) {
                        for (var entry : healed.extraEnv().entrySet()) {
                            if ("PYTHONPATH".equals(entry.getKey())) {
                                String prepend = entry.getValue();
                                String existing = retryEnv.getOrDefault("PYTHONPATH", System.getenv("PYTHONPATH"));
                                if (existing != null && !existing.isBlank()) {
                                    retryEnv.put("PYTHONPATH", prepend + java.io.File.pathSeparator + existing);
                                } else {
                                    retryEnv.put("PYTHONPATH", prepend);
                                }
                            } else {
                                retryEnv.put(entry.getKey(), entry.getValue());
                            }
                        }
                    }
                    SandboxResult retryResult = runInSandbox(isInteractive, healedPython, script, workDir,
                            stdinJson, retryEnv, userId, taskId, step.skill());
                    List<SkillOutputParser.SkillOutput> retryOutputs = outputParser.parse(retryResult.stdout());
                    for (var o : retryOutputs) {
                        if (o.type() == SkillOutputParser.SkillOutput.Type.PROGRESS) {
                            statusEmitter.emit(userId, StatusMessage.Type.PROGRESS, o.content());
                        } else if (o.type() == SkillOutputParser.SkillOutput.Type.NEED_INPUT) {
                            if (!isInteractive) {
                                statusEmitter.emit(userId, StatusMessage.Type.NEED_INPUT, o.content());
                            }
                        }
                    }

                    SkillOutputParser.SkillOutput retryResultOutput = outputParser.extractResult(retryOutputs);
                    if (retryResult.timedOut()) {
                        outputText = "Skill timed out after " + defaultTimeout + "s";
                        success = false;
                        sandboxResult = retryResult;
                        outputs = retryOutputs;
                        resultOutput = retryResultOutput;
                    } else if (!retryResult.isSuccess()) {
                        outputText = retryResult.stderr().isBlank()
                                ? "Exit code " + retryResult.exitCode()
                                : retryResult.stderr();
                        success = false;
                        sandboxResult = retryResult;
                        outputs = retryOutputs;
                        resultOutput = retryResultOutput;
                    } else if (retryResultOutput != null) {
                        outputText = retryResultOutput.content();
                        success = retryResultOutput.isSuccess();
                        sandboxResult = retryResult;
                        outputs = retryOutputs;
                        resultOutput = retryResultOutput;
                    } else {
                        outputText = retryResult.stdout();
                        success = retryResult.exitCode() == 0;
                        sandboxResult = retryResult;
                        outputs = retryOutputs;
                        resultOutput = retryResultOutput;
                    }
                }
            }
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

    private SandboxResult runInSandbox(boolean isInteractive, String python, Path script, Path workDir,
                                       String stdinJson, Map<String, String> envVars,
                                       String userId, String taskId, String skillName) {
        if (isInteractive) {
            log.info("Executing interactive skill: {}", skillName);
            return sandbox.executeInteractive(python, script, workDir, stdinJson, envVars,
                    defaultTimeout, prompt -> {
                        try {
                            return interactionHandler.requestInput(userId, taskId, prompt);
                        } catch (Exception e) {
                            log.warn("Interactive input failed for skill {}: {}", skillName, e.getMessage());
                            return "";
                        }
                    });
        }
        return sandbox.execute(python, script, workDir, stdinJson, envVars, defaultTimeout);
    }

    private boolean shouldRunInteractive(Optional<SkillModel> skillMeta, Path scriptPath) {
        if (skillMeta.isPresent() && skillMeta.get().interactive()) {
            return true;
        }

        // Heuristic fallback: if the script contains the need_input protocol string,
        // run in interactive mode even if the manifest flag is stale.
        // This keeps interactive skills working even when older manifests had interactive=false.
        try {
            String src = Files.readString(scriptPath, StandardCharsets.UTF_8);
            return src.contains("need_input");
        } catch (Exception ignored) {
            return false;
        }
    }

    private String extractMissingModule(String outputText) {
        if (outputText == null || outputText.isBlank()) return null;
        Matcher m = MODULE_NOT_FOUND.matcher(outputText);
        if (!m.find()) return null;

        String mod = m.group(1) != null ? m.group(1) : m.group(2);
        if (mod == null || mod.isBlank()) return null;
        // top-level package only
        int dot = mod.indexOf('.');
        if (dot > 0) mod = mod.substring(0, dot);
        return mod.strip();
    }

    private String mapModuleToPackage(String moduleName) {
        if (moduleName == null || moduleName.isBlank()) return moduleName;
        String direct = MODULE_TO_PACKAGE.get(moduleName);
        if (direct != null) return direct;
        // Common case: module == package
        return moduleName;
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
