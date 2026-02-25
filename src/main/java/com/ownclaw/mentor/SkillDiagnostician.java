package com.ownclaw.mentor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownclaw.core.TokenBudgetTracker;
import com.ownclaw.llm.*;
import com.ownclaw.observability.EventLogService;
import com.ownclaw.skillrunner.SkillFailureContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Diagnoses skill failures by analyzing the full execution context with the Mentor LLM.
 * Returns a structured diagnosis: root cause, whether the skill code needs fixing,
 * and if so, the proposed fix.
 *
 * <p>Part of the self-healing loop: Fail → Diagnose → Repair → Validate → Retry
 */
@Service
public class SkillDiagnostician {

    private static final Logger log = LoggerFactory.getLogger(SkillDiagnostician.class);

    private static final String DIAGNOSIS_PROMPT = """
            You are an expert Python debugger for an autonomous agent system.
            
            A skill script failed during execution. Analyze the failure report and determine:
            1. ROOT CAUSE: What exactly went wrong
            2. CATEGORY: One of: code_bug | missing_dependency | bad_params | network_error
                         | timeout | permission_denied | external_service_error | data_format
            3. FIXABLE: Can this be fixed by modifying the skill's Python code? (true/false)
            4. FIX: If fixable, provide the COMPLETE corrected Python script.
               If only params need adjustment, provide corrected params instead.
            
            IMPORTANT — FIXABILITY RULES (apply strictly, in order):
            1. missing_dependency (system binary not found, e.g. tesseract, ffmpeg, curl):
               → fixable=false ALWAYS. The SkillRepairer modifies Python code only; it cannot install
                 system packages. lesson: suggest a Python library or HTTP API alternative.
            2. permission_denied (sudo blocked, setuid, 'no new privileges' container flag):
               → fixable=false ALWAYS. No code change can grant OS-level privileges.
                 lesson: suggest removing sudo / finding an API alternative.
            3. Code bugs or missing Python error handling (SSL errors, encoding issues, redirect
               loops, auth challenges) → fixable=true. Fix inline: SSL → ssl._create_unverified_context().
            4. Truly external failures (dead server, non-existent domain, permanent auth wall) → fixable=false.
            5. Wrong param format → category="bad_params", fixable=false, explain correct params.
            - Keep fixes minimal. Preserve I/O protocol (stdin JSON, stdout JSON lines).
            
            For the "lesson" field, write a CONCRETE NEXT-STEP SUGGESTION, not a post-mortem.
            Write what the NEXT attempt should try differently.
            
            Respond with ONLY this JSON:
            {
              "root_cause": "concise explanation of what went wrong",
              "category": "code_bug|missing_dependency|bad_params|network_error|timeout|permission_denied|external_service_error|data_format",
              "fixable": true|false,
              "confidence": 0.0-1.0,
              "fixed_script": "complete corrected Python script (null if not fixable)",
              "fixed_params": {"key": "value"} or null,
              "requirements": "additional pip requirements if needed (null if none)",
              "lesson": "what should be remembered to avoid this failure in the future"
            }
            """;

    private final OpenAiProvider openAi;
    private final TokenBudgetTracker budgetTracker;
    private final EventLogService eventLog;
    private final ObjectMapper mapper;

    public SkillDiagnostician(OpenAiProvider openAi, TokenBudgetTracker budgetTracker,
                              EventLogService eventLog, ObjectMapper mapper) {
        this.openAi = openAi;
        this.budgetTracker = budgetTracker;
        this.eventLog = eventLog;
        this.mapper = mapper;
    }

    /**
     * Diagnose a skill failure by sending the full context to the Mentor.
     *
     * @param failureContext rich failure context from the skill execution
     * @param userId         for logging and budget tracking
     * @param taskId         for logging
     * @return diagnosis with root cause and optional fix
     */
    public Diagnosis diagnose(SkillFailureContext failureContext, String userId, String taskId) {
        log.info("Diagnosing failure of skill '{}' v{}", failureContext.skillName(), failureContext.version());

        try {
            String diagnosticReport = failureContext.toDiagnosticReport();

            List<LlmMessage> messages = List.of(
                    LlmMessage.system(DIAGNOSIS_PROMPT),
                    LlmMessage.user(diagnosticReport)
            );

            LlmResponse response = openAi.chat(messages, LlmRequestConfig.withJsonMode(4096));

            budgetTracker.recordUsage(userId, "openai", response.totalTokens(), 0.0);

            eventLog.log(userId, taskId, "skill.diagnosis", "info",
                    "Diagnosing " + failureContext.skillName() + " failure ("
                            + response.totalTokens() + " tokens)",
                    null, response.totalTokens());

            return parseDiagnosis(response.content(), failureContext.skillName());

        } catch (Exception e) {
            log.error("Diagnosis failed for skill '{}': {}", failureContext.skillName(), e.getMessage());
            eventLog.warn(userId, taskId, "skill.diagnosis_failed",
                    "Failed to diagnose " + failureContext.skillName() + ": " + e.getMessage());

            return new Diagnosis(
                    "Diagnosis failed: " + e.getMessage(),
                    "unknown",
                    false,
                    0.0,
                    null,
                    null,
                    null,
                    "Diagnosis LLM call failed"
            );
        }
    }

    private Diagnosis parseDiagnosis(String raw, String skillName) {
        try {
            String json = LlmOutputUtils.stripCodeFences(raw);

            JsonNode root = mapper.readTree(json);

            String rootCause = root.path("root_cause").asText("unknown");
            String category = root.path("category").asText("unknown");
            boolean fixable = root.path("fixable").asBoolean(false);
            double confidence = root.path("confidence").asDouble(0.5);
            String fixedScript = root.has("fixed_script") && !root.path("fixed_script").isNull()
                    ? root.path("fixed_script").asText() : null;
            String fixedParams = root.has("fixed_params") && !root.path("fixed_params").isNull()
                    ? root.get("fixed_params").toString() : null;
            String requirements = root.has("requirements") && !root.path("requirements").isNull()
                    ? root.path("requirements").asText() : null;
            String lesson = root.path("lesson").asText("");

            log.info("Diagnosis for '{}': category={}, fixable={}, confidence={}, cause={}",
                    skillName, category, fixable, confidence, rootCause);

            return new Diagnosis(rootCause, category, fixable, confidence,
                    fixedScript, fixedParams, requirements, lesson);

        } catch (Exception e) {
            log.warn("Failed to parse diagnosis JSON for '{}': {}", skillName, e.getMessage());
            return new Diagnosis(
                    "Failed to parse diagnosis: " + e.getMessage(),
                    "unknown", false, 0.0,
                    null, null, null, "Diagnosis JSON was unparseable"
            );
        }
    }

    /**
     * Structured diagnosis result.
     */
    public record Diagnosis(
            String rootCause,
            String category,       // code_bug | missing_dependency | bad_params | network_error | timeout | ...
            boolean fixable,
            double confidence,
            String fixedScript,    // complete corrected Python source (null if not fixable)
            String fixedParams,    // corrected params JSON (null if not applicable)
            String requirements,   // additional pip requirements (null if none)
            String lesson          // what to remember for the future
    ) {
        public boolean hasCodeFix() {
            return fixable && fixedScript != null && !fixedScript.isBlank();
        }

        public boolean hasParamFix() {
            return fixedParams != null && !fixedParams.isBlank();
        }
    }
}
