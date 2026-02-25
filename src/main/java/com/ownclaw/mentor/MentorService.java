package com.ownclaw.mentor;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownclaw.core.TaskPlan;
import com.ownclaw.core.TaskStep;
import com.ownclaw.core.TokenBudgetTracker;
import com.ownclaw.llm.*;
import com.ownclaw.observability.EventLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Mentor service — cloud LLM that plans, reviews, and teaches.
 * All calls go to OpenAI (Phase 1). Multi-provider support in Phase 3.
 *
 * <p>Prompts are kept lean: a small base system prompt carries only universal
 * instructions; domain-specific guidance (web scraping, email, file ops, etc.)
 * is injected dynamically via {@link PromptStrategies} based on the current
 * {@link TaskContext}.</p>
 */
@Service
public class MentorService {

    private static final Logger log = LoggerFactory.getLogger(MentorService.class);

    // ─── Prompt building blocks ──────────────────────────────────────

    /** Lean base — role, platform, constraints. No domain-specific strategies. */
    private static final String BASE_PROMPT_TEMPLATE = """
            You are a task planning assistant for an autonomous agent system.
            
            ROLE: Analyze tasks and create execution plans by COMPOSING the available skills.
            Break complex tasks into sequences of skill invocations that together accomplish the goal.
            
            SYSTEM CONTEXT:
            - Platform: %s
            - Shell: %s
            - Current date/time: %s
            
            PLATFORM AWARENESS (CRITICAL):
            - The shell_command skill runs in the system's native shell shown above.
            - On Windows: use PowerShell commands
            - On Linux/macOS: use bash commands
            - ALWAYS use the correct shell syntax for the platform.
            - Do NOT overcomplicate simple tasks.
            
            CONSTRAINTS:
            - Output ONLY valid JSON matching the requested schema.
            - PREFER skills listed in the provided manifest, but you MAY reference
              skill names that don't exist yet — the system can auto-generate them.
            - COMPOSE multiple skills to accomplish tasks. Chain skill outputs as inputs
              to subsequent steps. Do NOT look for a single skill that matches the whole task.
            - Only respond with {"action": "create_skill", ...} if none of the available skills
              can contribute to the task in any combination.
            - Flag irreversible actions (email, API calls, file deletion).
            - Keep all text responses under 500 tokens.
            - NEVER use sudo or apt-get/yum/brew in shell_command steps. The service runs as
              a non-privileged user with 'no new privileges' enforced. System packages cannot
              be installed at runtime. If a CLI tool is missing, create a skill that uses a
              Python library or HTTP API instead.
            
            TRIVIAL KNOWLEDGE:
            - If the answer is ALREADY in SYSTEM CONTEXT above (e.g. current date/time),
              return: {"direct_answer": "<answer>"} — do NOT plan a skill execution.
            - This avoids unnecessary shell commands for information you already have.
            """;

    /** Appended only when the Mentor is asked to build an execution plan. */
    private static final String PLAN_SECTION = """
            
            PLAN SCHEMA:
            {
              "steps": [{"id":int, "skill":str,
                         "description":"one-line of what this step accomplishes",
                         "params":{}, "depends_on":[int], "condition":str_or_null,
                         "on_fail":"report|skip|retry", "reversible":bool}]
            }

                                                ALTERNATIVE (only if no available skills can help even in combination):
                                                {
                                                        "action": "create_skill",
                                                        "name": "skill_name_lowercase_underscored",
                                                        "task": "What the new skill should do (1-3 sentences)",
                                                        "params": ["param1", "param2?"],
                                                        "notes": "Optional extra guidance"
                                                }
            
            ON_FAIL STRATEGY:
            - Use "skip" for INDEPENDENT steps that don't block others.
            - Use "retry" for steps that are important AND that might have a code bug
              or transient failure — self-healing can diagnose and fix most issues.
            - Use "report" only for CRITICAL steps where failure means the entire
              task is impossible to continue.
            - DEFAULT to "skip" for parallel independent fetches (multiple URLs,
              multiple queries). NEVER use "report" for independent data-fetching steps.
            - DEFAULT to "retry" for most skills — the system can self-heal failures.
            
            CONDITION GRAMMAR (for "condition" field):
              Variables: $N.success (bool), $N.output (str), $N.exit_code (int)
              Operators: && || ! == != .contains("x") .isEmpty()
              Example: "$1.success && !$2.output.isEmpty()"
            
            FAILED STEP HINTS:
            - Failure entries may include a "Hint for next attempt:" line from the diagnoser.
            - ALWAYS read and act on these hints when planning follow-up steps.
            - If a hint says a new skill was auto-generated, USE that skill by name.
            - If a hint says to probe a base URL first, add a step that does exactly that.
            """;

    // ─── System-prompt constructors ──────────────────────────────────

    /** Build the platform-aware base and append the given section. */
    private static String buildSystemPrompt(String section) {
        String os = System.getProperty("os.name", "Unknown");
        String shell;
        if (os.toLowerCase().contains("win")) {
            shell = "PowerShell (use PowerShell cmdlets, NOT bash/Linux commands)";
        } else {
            shell = "Bash (use standard Unix commands)";
        }
        String dateTime = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss (EEEE)"));
        return BASE_PROMPT_TEMPLATE.formatted(os, shell, dateTime) + section;
    }

    private final OpenAiProvider openAi;
    private final EventLogService eventLog;
    private final TokenBudgetTracker budgetTracker;
    private final ObjectMapper mapper;

    public MentorService(OpenAiProvider openAi, EventLogService eventLog,
                         TokenBudgetTracker budgetTracker, ObjectMapper mapper) {
        this.openAi = openAi;
        this.eventLog = eventLog;
        this.budgetTracker = budgetTracker;
        this.mapper = mapper;
    }

    /**
     * Ask Mentor to create an execution plan for a compressed task payload.
     *
     * @param compressedPayload    compressed task from Executor
     * @param skillManifestSnippet available skills summary
     * @param ctx                  task context — drives dynamic strategy injection
     * @param userId               for logging
     * @param taskId               for logging
     * @return parsed TaskPlan
     */
    public TaskPlan plan(String compressedPayload, String skillManifestSnippet,
                         TaskContext ctx, String userId, String taskId) {

        String strategies = PromptStrategies.asUserMessageBlock(ctx);

        String userMsg = "TASK:\n" + compressedPayload
                + "\n\nAVAILABLE SKILLS:\n" + skillManifestSnippet
                + strategies
                + "\n\nCreate an execution plan using the PLAN SCHEMA.";

        List<LlmMessage> messages = List.of(
                LlmMessage.system(buildSystemPrompt(PLAN_SECTION)),
                LlmMessage.user(userMsg)
        );

        LlmResponse response = openAi.chat(messages, LlmRequestConfig.withJsonMode(2048));

        budgetTracker.recordUsage(userId, "openai", response.totalTokens(), 0.0);

        eventLog.log(userId, taskId, "mentor.plan", "info",
                "Mentor planning (" + response.totalTokens() + " tokens)"
                        + (strategies.isEmpty() ? "" : " [+strategies]"),
                null, response.totalTokens());

        return parsePlan(response.content());
    }

    private TaskPlan parsePlan(String json) {
        try {
            json = LlmOutputUtils.stripCodeFences(json);
            JsonNode root = mapper.readTree(json);

            // Handle direct_answer — Mentor determined it already knows the answer
            if (root.has("direct_answer")) {
                String answer = root.path("direct_answer").asText();
                log.info("Mentor returned direct_answer (no skills needed)");
                return TaskPlan.directAnswer(answer);
            }

            // Handle non-plan responses (e.g., {"action": "create_skill", ...})
            if (root.has("action") && !root.has("steps")) {
                                String action = root.path("action").asText("");
                                log.warn("Mentor returned action instead of plan: {}", action);
                                if ("create_skill".equalsIgnoreCase(action)) {
                                        String name = root.path("name").asText("");
                                        if (name.isBlank()) name = root.path("skill_name").asText("");
                                        String task = root.path("task").asText("");
                                        if (task.isBlank()) task = root.path("description").asText("");
                                        if (task.isBlank()) task = root.path("prompt").asText("");
                                        // Keep behavior resilient: if name missing, TaskOrchestrator will fall back to generic generation.
                                        return TaskPlan.createSkill(name, task);
                                }

                                return new TaskPlan(List.of());
            }

            List<TaskStep> steps = new ArrayList<>();
            for (JsonNode stepNode : root.path("steps")) {
                Map<String, Object> params = mapper.convertValue(
                        stepNode.path("params"), new TypeReference<>() {});
                List<Integer> deps = new ArrayList<>();
                for (JsonNode d : stepNode.path("depends_on")) deps.add(d.asInt());

                steps.add(new TaskStep(
                        stepNode.path("id").asInt(),
                        stepNode.path("skill").asText(),
                        stepNode.path("description").asText(null),
                        params != null ? params : Map.of(),
                        deps,
                        stepNode.has("condition") && !stepNode.path("condition").isNull()
                                ? stepNode.path("condition").asText() : null,
                        TaskStep.OnFail.fromString(stepNode.path("on_fail").asText("report")),
                        stepNode.path("reversible").asBoolean(false)
                ));
            }

            if (steps.isEmpty()) {
                log.warn("Mentor returned plan with 0 steps. Raw JSON: {}",
                        json.length() > 500 ? json.substring(0, 500) + "..." : json);
            }

            return new TaskPlan(steps);
        } catch (Exception e) {
            log.error("Failed to parse Mentor plan: {}", e.getMessage());
            throw new LlmException("openai", "Invalid plan JSON: " + e.getMessage());
        }
    }

    /**
     * Ask Mentor to create a follow-up plan based on previous execution results
     * and a completeness analysis that identified missing information.
     *
     * @param originalTask         the original user request
     * @param previousResults      results from the initial plan execution
     * @param completenessAnalysis what's missing and where to look
     * @param skillManifestSnippet available skills
     * @param stepIdOffset         first step ID for the follow-up plan
     * @param ctx                  task context — drives dynamic strategy injection
     * @param userId               for logging
     * @param taskId               for logging
     * @return a follow-up TaskPlan with additional steps
     */
    public TaskPlan followUpPlan(String originalTask, String previousResults,
                                  String completenessAnalysis, String skillManifestSnippet,
                                  int stepIdOffset, TaskContext ctx,
                                  String userId, String taskId) {

        String strategies = PromptStrategies.asUserMessageBlock(
                ctx != null ? ctx.asFollowUp() : TaskContext.EMPTY.asFollowUp());

        String userMsg = """
                ORIGINAL TASK:
                %s
                
                PREVIOUS EXECUTION RESULTS (summary):
                %s
                
                COMPLETENESS ANALYSIS:
                The previous results do NOT fully answer the user's question.
                %s
                
                AVAILABLE SKILLS:
                %s
                %s
                Create a FOLLOW-UP execution plan for the missing information.
                Use step IDs starting from %d. Fetch only what's specifically missing.
                Use the PLAN SCHEMA.
                """.formatted(
                originalTask,
                previousResults.length() > 3000
                        ? previousResults.substring(0, 3000) + "\n... [truncated]"
                        : previousResults,
                completenessAnalysis,
                skillManifestSnippet,
                strategies,
                stepIdOffset
        );

        List<LlmMessage> messages = List.of(
                LlmMessage.system(buildSystemPrompt(PLAN_SECTION)),
                LlmMessage.user(userMsg)
        );

        LlmResponse response = openAi.chat(messages, LlmRequestConfig.withJsonMode(1024));

        budgetTracker.recordUsage(userId, "openai", response.totalTokens(), 0.0);

        eventLog.log(userId, taskId, "mentor.follow_up_plan", "info",
                "Mentor follow-up planning (" + response.totalTokens() + " tokens)",
                null, response.totalTokens());

        return parsePlan(response.content());
    }
}
