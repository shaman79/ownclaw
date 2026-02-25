package com.ownclaw.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.core.TaskCancellationService.TaskCancelledException;
import com.ownclaw.conversation.ConversationService;
import com.ownclaw.executor.ExecutorService;
import com.ownclaw.executor.ExecutorService.ClassificationResult;
import com.ownclaw.executor.ExecutorService.CompletenessResult;
import com.ownclaw.executor.PreferencesManager;
import com.ownclaw.llm.LlmMessage;
import com.ownclaw.mentor.MentorService;
import com.ownclaw.mentor.SkillDiagnostician;
import com.ownclaw.mentor.SkillGenerator;
import com.ownclaw.mentor.SkillRepairer;
import com.ownclaw.mentor.TaskContext;
import com.ownclaw.observability.ChatStatusEmitter;
import com.ownclaw.observability.ChatStatusEmitter.StatusMessage;
import com.ownclaw.observability.EventLogService;
import com.ownclaw.skillrunner.SkillFailureContext;
import com.ownclaw.skillrunner.SkillInteractionHandler;
import com.ownclaw.skillrunner.SkillRunnerService;
import com.ownclaw.skills.SkillLoader;
import com.ownclaw.skills.SkillManifest;
import com.ownclaw.skills.SkillModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The main task flow coordinator.
 * Implements: Executor classifies → Mentor plans → SkillRunner executes → Mentor reviews.
 */
@Service
public class TaskOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(TaskOrchestrator.class);
    private static final Pattern REF_PATTERN = Pattern.compile("\\$(\\d+)\\.(output|exit_code|success)");
    private static final int MAX_SELF_HEAL_ATTEMPTS = 2;

    private final ExecutorService executor;
    private final MentorService mentor;
    private final SkillRunnerService skillRunner;
    private final SkillManifest skillManifest;
    private final SkillLoader skillLoader;
    private final ConditionEvaluator conditionEvaluator;
    private final ConversationService conversation;
    private final EventLogService eventLog;
    private final ChatStatusEmitter statusEmitter;
    private final PlanCacheService planCache;
    private final TokenBudgetTracker budgetTracker;
    private final SkillGenerator skillGenerator;
    private final SkillDiagnostician skillDiagnostician;
    private final SkillRepairer skillRepairer;
    private final PreferencesManager preferencesManager;
    private final ObjectMapper mapper;
    private final JdbcTemplate jdbc;
    private final OwnClawConfig config;
    private final SkillInteractionHandler interactionHandler;
    private final TaskCancellationService cancellationService;

    public TaskOrchestrator(ExecutorService executor, MentorService mentor,
                            SkillRunnerService skillRunner, SkillManifest skillManifest,
                            SkillLoader skillLoader,
                            ConditionEvaluator conditionEvaluator, ConversationService conversation,
                            EventLogService eventLog,
                            ChatStatusEmitter statusEmitter, PlanCacheService planCache,
                            TokenBudgetTracker budgetTracker, SkillGenerator skillGenerator,
                            SkillDiagnostician skillDiagnostician,
                            SkillRepairer skillRepairer,
                            PreferencesManager preferencesManager,
                            ObjectMapper mapper, JdbcTemplate jdbc, OwnClawConfig config,
                            SkillInteractionHandler interactionHandler,
                            TaskCancellationService cancellationService) {
        this.executor = executor;
        this.mentor = mentor;
        this.skillRunner = skillRunner;
        this.skillManifest = skillManifest;
        this.skillLoader = skillLoader;
        this.conditionEvaluator = conditionEvaluator;
        this.conversation = conversation;
        this.eventLog = eventLog;
        this.statusEmitter = statusEmitter;
        this.planCache = planCache;
        this.budgetTracker = budgetTracker;
        this.skillGenerator = skillGenerator;
        this.skillDiagnostician = skillDiagnostician;
        this.skillRepairer = skillRepairer;
        this.preferencesManager = preferencesManager;
        this.mapper = mapper;
        this.jdbc = jdbc;
        this.config = config;
        this.interactionHandler = interactionHandler;
        this.cancellationService = cancellationService;
    }

    /**
     * Process a user message through the full pipeline.
     *
     * @param userId      user ID
     * @param userMessage raw user input
     * @return final response text to send back to the user
     */
    public String processMessage(String userId, String userMessage) {
        // Clear any stale cancel flag from a previous task before starting fresh.
        cancellationService.clear(userId);

        String taskId = UUID.randomUUID().toString().substring(0, 8);
        String sessionId = conversation.getCurrentSession(userId);
        eventLog.info(userId, taskId, "task.received", "Task: " + truncate(userMessage, 100));

        // Save user message to conversation history
        conversation.saveMessage(userId, sessionId, "user", userMessage);

        // Load recent history (excluding the message we just saved — it's at index 0 in DESC order).
        // Used to give the classifier and Mentor context for follow-up messages.
        List<Map<String, Object>> recentForContext = conversation.getRecentMessages(userId, sessionId, 7);
        // recentForContext[0] is the message just saved; skip it and reverse the rest to chronological
        List<LlmMessage> classifyHistory = new ArrayList<>();
        StringBuilder contextSb = new StringBuilder();
        for (int i = recentForContext.size() - 1; i >= 1; i--) {
            Map<String, Object> row = recentForContext.get(i);
            String role = (String) row.get("role");
            String content = (String) row.get("content");
            classifyHistory.add("assistant".equals(role)
                    ? LlmMessage.assistant(content)
                    : LlmMessage.user(content));
            contextSb.append(role).append(": ").append(truncate(content, 400)).append("\n");
        }
        String conversationContext = contextSb.length() > 0 ? contextSb.toString().strip() : null;

        try {
            // Step 1: Executor classifies (with history so follow-up messages resolve correctly)
            statusEmitter.emit(userId, StatusMessage.Type.STARTED, "Analyzing task...");
            ClassificationResult classification = executor.classify(userMessage, classifyHistory);

            eventLog.info(userId, taskId, "task.classified",
                    "Intent: " + classification.intent()
                            + " | Confidence: " + String.format("%.2f", classification.confidence())
                            + " | Conversational: " + classification.conversational()
                            + " | Mentor: " + classification.needsMentor());

            log.info("Classification for [{}]: intent={}, confidence={}, conversational={}, matches={}",
                    truncate(userMessage, 50), classification.intent(),
                    classification.confidence(), classification.conversational(), classification.matchesJson());

            // If conversational — no skills needed, just respond
            if (classification.conversational()) {
                return handleConversational(userId, taskId, userMessage);
            }

            // Step 2: Determine flow based on confidence
            double confidence = classification.confidence();
            double mentorThreshold = config.getConfidence().getMentorThreshold();

            if (!classification.needsMentor() && confidence > config.getConfidence().getCacheSkipThreshold()) {
                // High confidence + no mentor needed → try plan cache
            }

            // Step 2b: Check plan cache before calling Mentor
            Optional<TaskPlan> cachedPlan = config.getPlanCache().isEnabled()
                    ? planCache.lookup(userMessage) : Optional.empty();

            TaskPlan plan;
            if (cachedPlan.isPresent()) {
                plan = cachedPlan.get();
                eventLog.info(userId, taskId, "task.cache_hit",
                        "Using cached plan: " + plan.size() + " steps");
                statusEmitter.emit(userId, StatusMessage.Type.STARTED, "Using cached plan...");
            } else {
                // Step 3: Compress and send to Mentor
                statusEmitter.emit(userId, StatusMessage.Type.MENTOR, "Planning with Mentor...");

                // Budget check: ensure we have tokens before calling cloud LLM
                if (!budgetTracker.hasBudget(userId)) {
                    String msg = "Daily cloud token budget exhausted. Try again tomorrow or ask a simpler question.";
                    statusEmitter.emit(userId, StatusMessage.Type.FAILED, "Token budget exhausted");
                    conversation.saveMessage(userId, sessionId, "assistant", msg);
                    return msg;
                }

                String compressedPayload;
                if (confidence < mentorThreshold) {
                    // Low confidence: send raw message but still include conversation context
                    compressedPayload = conversationContext != null
                            ? "[Recent conversation]\n" + conversationContext + "\n[Current task]\n" + userMessage
                            : userMessage;
                    eventLog.warn(userId, taskId, "task.low_confidence",
                            "Confidence " + String.format("%.2f", confidence) + " < threshold, sending raw to Mentor");
                } else {
                    compressedPayload = executor.compressForMentor(userMessage, conversationContext, classification);
                }

                // Build dynamic task context from classification so the Mentor
                // receives only strategies relevant to this specific task
                TaskContext taskCtx = TaskContext.fromMatchesJson(classification.matchesJson());

                plan = mentor.plan(compressedPayload, skillManifest.toPromptSnippet(),
                        taskCtx, userId, taskId);
                eventLog.info(userId, taskId, "task.planned", "Plan: " + plan.size() + " steps");
            }

            // Fast path: Mentor answered from knowledge (no skills needed)
            if (plan.isDirectAnswer()) {
                String answer = plan.directAnswer();
                eventLog.info(userId, taskId, "task.direct_answer",
                        "Mentor answered directly: " + truncate(answer, 100));
                statusEmitter.emit(userId, StatusMessage.Type.COMPLETED, "Done");
                conversation.saveMessage(userId, sessionId, "assistant", answer);
                return answer;
            }

            // Guard: if the Mentor returned 0 steps, try generating a new skill
            if (plan.steps().isEmpty()) {
                statusEmitter.emit(userId, StatusMessage.Type.MENTOR,
                        "No matching skills — attempting to generate a new one...");
                eventLog.info(userId, taskId, "task.skill_gen_attempt",
                        "Mentor returned 0 steps, triggering skill generation");

                try {
                    SkillGenerator.GenerationResult genResult;
                    if (plan.isCreateSkillRequest()) {
                    var req = plan.createSkillRequest();
                    String name = req != null ? req.name() : null;
                    String task = req != null && req.taskDescription() != null && !req.taskDescription().isBlank()
                        ? req.taskDescription()
                        : userMessage;
                    genResult = skillGenerator.generateForName(name, task, userId, taskId);
                    } else {
                    genResult = skillGenerator.generate(userMessage, userId, taskId);
                    }

                    if (genResult.success()) {
                        statusEmitter.emit(userId, StatusMessage.Type.COMPLETED,
                                "New skill '" + genResult.skillName() + "' created! Re-planning...");

                        // Re-plan now that a new skill is available
                        String compressedRetry = executor.compressForMentor(userMessage, null, classification);
                        // Refresh context — the new skill may change which strategies are relevant
                        TaskContext retryCtx = TaskContext.fromMatchesJson(classification.matchesJson());
                        plan = mentor.plan(compressedRetry, skillManifest.toPromptSnippet(),
                                retryCtx, userId, taskId);

                        if (plan.steps().isEmpty()) {
                            String msg = "A new skill was created ('" + genResult.skillName()
                                    + "') but planning still produced no steps. "
                                    + "Try rephrasing your request.";
                            conversation.saveMessage(userId, sessionId, "assistant", msg);
                            return msg;
                        }
                    } else {
                        String msg = "I wasn't able to create a plan for this task. "
                                + "The available skills may not cover what's needed, "
                                + "or the request may need to be rephrased.\n"
                                + "Skill generation also failed: " + genResult.summary();
                        eventLog.warn(userId, taskId, "task.empty_plan",
                                "Mentor returned 0 steps and skill generation failed");
                        statusEmitter.emit(userId, StatusMessage.Type.FAILED, "No plan generated");
                        conversation.saveMessage(userId, sessionId, "assistant", msg);
                        return msg;
                    }
                } catch (Exception e) {
                    log.warn("Skill generation failed: {}", e.getMessage());
                    String msg = "I wasn't able to create a plan for this task. "
                            + "The available skills may not cover what's needed, "
                            + "or the request may need to be rephrased.";
                    eventLog.warn(userId, taskId, "task.empty_plan",
                            "Mentor returned 0 steps");
                    statusEmitter.emit(userId, StatusMessage.Type.FAILED, "No plan generated");
                    conversation.saveMessage(userId, sessionId, "assistant", msg);
                    return msg;
                }
            }

            // Persist task state for recovery
            persistTaskState(taskId, userId, "executing", plan);

            // Step 4: Execute the plan (DAG-aware)
            statusEmitter.emit(userId, StatusMessage.Type.STARTED,
                    "Executing plan (" + plan.size() + " step" + (plan.size() > 1 ? "s" : "") + ")...");
            Map<Integer, StepResult> stepResults = executePlan(plan, userId, taskId);

            // Step 5: Evaluate results
            persistTaskState(taskId, userId, "completed", plan);
            boolean allSuccess = stepResults.values().stream()
                    .filter(r -> !r.isSkipped())
                    .allMatch(StepResult::success);

            // Cache the plan if it succeeded, has steps, and wasn't already cached
            if (config.getPlanCache().isEnabled() && cachedPlan.isEmpty()
                    && allSuccess && !plan.steps().isEmpty()) {
                planCache.store(userMessage, plan);
            }

            // Iterative re-planning — check if results actually answer the question
            boolean hasAnyRealResults = stepResults.values().stream().anyMatch(r -> !r.isSkipped());
            if (hasAnyRealResults) {
                int maxRounds = config.getFeedback().getMaxRounds();
                int nextStepId = stepResults.keySet().stream().mapToInt(Integer::intValue).max().orElse(0) + 1;

                // Carry the task context through follow-up rounds; we'll
                // enrich it with failure info as rounds progress
                TaskContext followUpCtx = TaskContext.fromMatchesJson(classification.matchesJson());

                for (int round = 1; round <= maxRounds; round++) {
                    // Check for cancellation before each follow-up round.
                    if (cancellationService.isCancelled(userId)) {
                        throw new TaskCancelledException(userId);
                    }

                    // Include both successes AND failure summaries so the eval
                    // knows what already failed and doesn't request impossible follow-ups
                    String rawSoFar = buildResponseForEvaluation(stepResults);

                    statusEmitter.emit(userId, StatusMessage.Type.STARTED,
                            "Evaluating completeness (round " + round + "/" + maxRounds + ")...");

                    CompletenessResult eval = executor.evaluateCompleteness(userMessage, rawSoFar);
                    eventLog.info(userId, taskId, "task.completeness_eval",
                            "Round " + round + ": complete=" + eval.complete()
                                    + " | " + truncate(eval.analysis(), 200));

                    if (eval.complete() || eval.followUp() == null || eval.followUp().isBlank()) {
                        log.info("Results complete after round {} evaluation", round);
                        break;
                    }

                    // Not complete — ask Mentor for a follow-up plan
                    log.info("Results incomplete (round {}): {}", round, eval.analysis());
                    statusEmitter.emit(userId, StatusMessage.Type.MENTOR,
                            "Planning follow-up (round " + round + ")...");

                    try {
                        // Pass failure context so the Mentor knows what broke and doesn't retry the same thing
                        String failureSummary = buildFailureSummary(stepResults);
                        String followUpContext = eval.followUp()
                                + (failureSummary.isEmpty() ? "" : "\n\nPREVIOUS FAILURES (do NOT retry these):\n" + failureSummary);

                        // Pass the full evaluation context (successes + failures) so the
                        // Mentor sees what failed and why when replanning from scratch.
                        TaskPlan followUp = mentor.followUpPlan(
                                userMessage, buildResponseForEvaluation(stepResults), followUpContext,
                                skillManifest.toPromptSnippet(), nextStepId, followUpCtx,
                                userId, taskId);

                        if (followUp.steps().isEmpty()) {
                            log.info("Mentor returned empty follow-up plan, stopping");
                            break;
                        }

                        eventLog.info(userId, taskId, "task.follow_up",
                                "Follow-up plan: " + followUp.size() + " steps (round " + round + ")");

                        statusEmitter.emit(userId, StatusMessage.Type.STARTED,
                                "Executing follow-up (" + followUp.size() + " step"
                                        + (followUp.size() > 1 ? "s" : "") + ")...");

                        Map<Integer, StepResult> followUpResults = executePlan(followUp, userId, taskId);
                        stepResults.putAll(followUpResults);

                        // Update next step ID offset for potential further rounds
                        nextStepId = stepResults.keySet().stream()
                                .mapToInt(Integer::intValue).max().orElse(nextStepId) + 1;

                        // Note follow-up failures but DON'T break — let the next round's
                        // completeness eval see the combined results and decide what to do.
                        // The eval is now aware of failures and won't blindly retry.
                        boolean followUpSuccess = followUpResults.values().stream()
                                .allMatch(StepResult::success);
                        if (!followUpSuccess) {
                            long failCount = followUpResults.values().stream()
                                    .filter(r -> !r.success()).count();
                            log.info("Follow-up round {} had {} failure(s), continuing evaluation",
                                    round, failCount);
                        }
                    } catch (TaskCancelledException e) {
                        throw e; // propagate cancellation past the follow-up catch
                    } catch (Exception e) {
                        log.warn("Follow-up planning failed in round {}: {}", round, e.getMessage());
                        eventLog.warn(userId, taskId, "task.follow_up_failed",
                                "Round " + round + ": " + e.getMessage());
                        break;
                    }
                }

                // Re-check allSuccess after follow-up rounds
                allSuccess = stepResults.values().stream()
                        .filter(r -> !r.isSkipped())
                        .allMatch(StepResult::success);
            }

            String response;
            boolean hasAnySuccessForSummary = stepResults.values().stream().anyMatch(StepResult::success);
            boolean hasRealFailures = stepResults.values().stream()
                    .anyMatch(r -> !r.success() && !r.isSkipped());
            if (!allSuccess && !hasAnySuccessForSummary && hasRealFailures) {
                // Total failure — no successful steps at all
                response = buildErrorResponse(stepResults);
                statusEmitter.emit(userId, StatusMessage.Type.FAILED, "Task failed");
            } else if (hasRealFailures && hasAnySuccessForSummary) {
                // Partial success — some steps succeeded, some failed.
                // Summarize what we got + explain what failed, via the LLM.
                String rawOutput = buildPartialResponse(stepResults);
                statusEmitter.emit(userId, StatusMessage.Type.STARTED, "Summarizing partial results...");
                try {
                    response = executor.summarize(userMessage, rawOutput);
                } catch (Exception e) {
                    log.warn("Summarization of partial results failed: {}", e.getMessage());
                    response = null;
                }
                // Guard against empty response from LLM — never dump raw output to chat
                if (response == null || response.isBlank()) {
                    log.warn("Summarizer returned empty response for partial results, building safe fallback");
                    response = buildSafeFallbackResponse(userMessage, stepResults);
                }
                statusEmitter.emit(userId, StatusMessage.Type.COMPLETED, "Done (some steps failed)");
            } else {
                // Step 7: Summarize successful output into a user-friendly response
                String rawOutput = buildFinalResponse(stepResults);
                statusEmitter.emit(userId, StatusMessage.Type.STARTED, "Summarizing results...");
                try {
                    response = executor.summarize(userMessage, rawOutput);
                } catch (Exception e) {
                    log.warn("Summarization failed: {}", e.getMessage());
                    response = null;
                }
                // Guard against empty response from LLM — never dump raw output to chat
                if (response == null || response.isBlank()) {
                    log.warn("Summarizer returned empty response, building safe fallback");
                    response = buildSafeFallbackResponse(userMessage, stepResults);
                }
                statusEmitter.emit(userId, StatusMessage.Type.COMPLETED, "Done");
            }

            eventLog.info(userId, taskId, "task.completed", "Task complete");
            conversation.saveMessage(userId, sessionId, "assistant", response);

            // Phase 2: Learn from this interaction (async-safe, non-blocking)
            try {
                preferencesManager.learnFromTask(userId, userMessage, response, taskId);
                preferencesManager.distillIfNeeded(userId);
            } catch (Exception e) {
                log.debug("Preference learning failed (non-critical): {}", e.getMessage());
            }

            return response;

        } catch (TaskCancelledException e) {
            log.info("Task {} cancelled by user {}", taskId, userId);
            eventLog.info(userId, taskId, "task.cancelled", "Task cancelled by user request");
            statusEmitter.emit(userId, StatusMessage.Type.FAILED, "Task cancelled");
            persistTaskState(taskId, userId, "cancelled", null);
            String cancelMsg = "⏹ Task cancelled.";
            conversation.saveMessage(userId, sessionId, "assistant", cancelMsg);
            return cancelMsg;

        } catch (Exception e) {
            log.error("Task {} failed: {}", taskId, e.getMessage(), e);
            eventLog.error(userId, taskId, "task.failed", e.getMessage());
            statusEmitter.emit(userId, StatusMessage.Type.FAILED, "Task failed: " + e.getMessage());
            persistTaskState(taskId, userId, "failed", null);
            String errMsg = "Task failed: " + e.getMessage();
            conversation.saveMessage(userId, sessionId, "assistant", errMsg);
            return errMsg;
        }
    }

    /**
     * Execute a plan respecting DAG dependencies and conditions.
     */
    private Map<Integer, StepResult> executePlan(TaskPlan plan, String userId, String taskId) {
        Map<Integer, StepResult> results = new LinkedHashMap<>();
        Set<Integer> completed = new HashSet<>();

        // Simple topological execution: iterate until all steps done or blocked
        int maxIterations = plan.size() * 2; // safety limit
        for (int iter = 0; iter < maxIterations && completed.size() < plan.size(); iter++) {
            boolean progress = false;

            for (TaskStep step : plan.steps()) {
                if (completed.contains(step.id())) continue;

                // Bail out immediately if the user requested cancellation.
                if (cancellationService.isCancelled(userId)) {
                    throw new TaskCancelledException(userId);
                }

                // Check dependencies satisfied
                if (!completed.containsAll(step.dependsOn())) continue;

                // Check condition
                if (step.condition() != null && !conditionEvaluator.evaluate(step.condition(), results)) {
                    results.put(step.id(), StepResult.skipped(step.id()));
                    completed.add(step.id());
                    progress = true;
                    continue;
                }

                // Resolve parameter references ($N.output)
                Map<String, Object> resolvedParams = resolveParams(step.params(), results);

                statusEmitter.emit(userId, StatusMessage.Type.STEP,
                        "Step " + step.id() + "/" + plan.size() + ": " + step.skill());

                // Auto-generate skills that are missing on disk (or not in manifest).
                // This prevents a dead-end where Mentor references a manifest-listed skill
                // but the corresponding skill files were not deployed to the runtime.
                ensureSkillAvailableOnDisk(step, resolvedParams, userId, taskId);

                // Execute
                StepResult result = skillRunner.executeStep(step, userId, taskId, resolvedParams, Map.of());
                results.put(step.id(), result);
                completed.add(step.id());
                progress = true;

                // Handle failure
                if (!result.success()) {
                    // Promote REPORT → RETRY: always attempt self-healing before aborting.
                    // Skills are skills — any failure is worth diagnosing and retrying.
                    TaskStep.OnFail effectiveOnFail = step.onFail();
                    if (effectiveOnFail == TaskStep.OnFail.REPORT) {
                        log.info("Promoting on_fail=REPORT → RETRY for skill '{}' — attempting self-heal first",
                                step.skill());
                        effectiveOnFail = TaskStep.OnFail.RETRY;
                    }

                    switch (effectiveOnFail) {
                        case REPORT -> {
                            eventLog.warn(userId, taskId, "step.failed",
                                    "Step " + step.id() + " (" + step.skill() + ") failed: " + truncate(result.output(), 200));
                            updateTaskStateStep(taskId, step.id(), results);
                            return results; // stop plan execution
                        }
                        case SKIP -> {
                            // Even when Mentor says "skip", first try a single self-heal attempt.
                            // This avoids silently skipping steps due to fixable failures.
                            StepResult healed = attemptSelfHeal(step, userId, taskId, resolvedParams, result, 1);
                            if (healed.success()) {
                                results.put(step.id(), healed);
                            } else {
                                results.put(step.id(), StepResult.skipped(step.id()));
                                eventLog.info(userId, taskId, "step.skipped",
                                        "Step " + step.id() + " failed, skipping (on_fail=skip)");
                            }
                        }
                        case RETRY -> {
                            // Self-healing retry: diagnose → repair → retry
                            StepResult healedResult = attemptSelfHeal(
                                    step, userId, taskId, resolvedParams, result, MAX_SELF_HEAL_ATTEMPTS);
                            results.put(step.id(), healedResult);
                            if (!healedResult.success()) {
                                if (healedResult.terminal()) {
                                    // Definitive plan-level failure (e.g. bad_params): abort immediately.
                                    // The follow-up planning loop will explain the root cause to the user.
                                    updateTaskStateStep(taskId, step.id(), results);
                                    return results;
                                }
                                eventLog.warn(userId, taskId, "step.retry_failed",
                                        "Step " + step.id() + " (" + step.skill()
                                                + ") failed after self-heal — continuing with remaining steps");
                                // Don't abort — continue with remaining independent steps.
                                // Dependent steps will be skipped via dependency check.
                            }
                        }
                    }
                }

                // Update persisted state
                updateTaskStateStep(taskId, step.id(), results);
            }

            if (!progress) {
                log.warn("DAG execution stalled — unresolvable dependencies in task {}", taskId);
                break;
            }
        }

        return results;
    }

    /**
     * Attempt to self-heal a failed skill step.
     *
     * <p>The self-healing loop:
     * <ol>
     *   <li>Capture the failure context (source code, stderr, params, etc.)</li>
     *   <li>Ask the Mentor to diagnose the failure</li>
     *   <li>If fixable, apply the repair (new skill version)</li>
     *   <li>Validate the repair and retry the step</li>
     *   <li>If fix fails or isn't possible, fall back to a plain retry</li>
     * </ol>
     *
     * @param step           the failed step
     * @param userId         user ID
     * @param taskId         task ID
     * @param resolvedParams the params that were used
     * @param failedResult   the original failure result
    * @param maxAttempts    number of self-heal attempts to try (LLM-driven)
     * @return the result of the retry (may be success or failure)
     */
    private StepResult attemptSelfHeal(TaskStep step, String userId, String taskId,
                                Map<String, Object> resolvedParams,
                                StepResult failedResult,
                                int maxAttempts) {

        SkillFailureContext failureContext = skillRunner.getLastFailureContext();

        if (failureContext == null) {
            log.info("No failure context available for step {} — falling back to simple retry", step.id());
            return skillRunner.executeStep(step, userId, taskId, resolvedParams, Map.of());
        }

        // Check budget before spending tokens on diagnosis
        if (!budgetTracker.hasBudget(userId)) {
            log.info("Token budget exhausted — falling back to simple retry for step {}", step.id());
            return skillRunner.executeStep(step, userId, taskId, resolvedParams, Map.of());
        }

        boolean regenerated = false;

        // Determine if the currently selected skill implementation is generated.
        // Generated skills override core skills and can be regenerated safely.
        boolean isGeneratedSkill = skillLoader.resolveScript(step.skill())
            .map(p -> p.normalize().toAbsolutePath().startsWith(
                java.nio.file.Path.of(config.getSkills().getGeneratedPath()).normalize().toAbsolutePath()))
            .orElse(false);

        // Fast path: "command not found" — the shell_command skill ran a missing binary.
        // The Python wrapper always exits 0 and embeds the real exit code in its JSON stdout,
        // so failureContext.exitCode() is 0 and the exitCode==127 check never fires.
        // Detect via the embedded JSON content or stderr string instead.
        // Self-healing can only modify Python — it cannot install system binaries.
        // Skip ALL repair/regenerate attempts; go straight to creating a replacement skill.
        boolean commandNotFound = failureContext.exitCode() == 127
                || (failureContext.stdout() != null
                        && (failureContext.stdout().contains("command not found")
                                || failureContext.stdout().contains("\"exit_code\":127")
                                || failureContext.stdout().contains("\"exit_code\": 127")))
                || (failureContext.stderr() != null
                        && failureContext.stderr().contains("command not found"));
        if (commandNotFound && !isGeneratedSkill) {
            String cmd = String.valueOf(resolvedParams.getOrDefault("command", "")).strip();
            String binary = cmd.isEmpty() ? "unknown" : cmd.split("\\s+")[0];
            eventLog.info(userId, taskId, "skill.command_not_found",
                    "exit 127: '" + binary + "' is not installed — asking user for alternative");
            var syntheticDiagnosis = new SkillDiagnostician.Diagnosis(
                    "'" + binary + "' is not installed on this host (exit code 127)",
                    "missing_dependency", false, 1.0, null, null, null, null);
            return buildDefinitiveFailure(step, syntheticDiagnosis, failedResult, userId, taskId, resolvedParams);
        }

        // Minimal user-facing status line: emit once per self-heal invocation.
        statusEmitter.emit(userId, StatusMessage.Type.MENTOR,
            "Self-healing " + step.skill() + "...");

        int boundedAttempts = Math.max(1, Math.min(maxAttempts, MAX_SELF_HEAL_ATTEMPTS));
        for (int attempt = 1; attempt <= boundedAttempts; attempt++) {
            log.info("Self-heal attempt {}/{} for skill '{}' step {}",
                attempt, boundedAttempts, step.skill(), step.id());

            // Re-check budget on every iteration — diagnosis calls can be 1000-4000 tokens each.
            if (!budgetTracker.hasBudget(userId)) {
                log.info("Token budget exhausted mid-loop — stopping self-heal for step {}", step.id());
                return buildDefinitiveFailure(step,
                        new SkillDiagnostician.Diagnosis("Token budget exhausted",
                                "external_service_error", false, 1.0, null, null, null, null),
                        failedResult, userId, taskId, resolvedParams);
            }

            // Step 1: Diagnose
            SkillDiagnostician.Diagnosis diagnosis = skillDiagnostician.diagnose(
                    failureContext, userId, taskId);

            // Hard override: missing_dependency and permission_denied can NEVER be fixed by
            // modifying Python code. The SkillRepairer cannot install system packages or grant
            // OS-level privileges. Override fixable=false regardless of what the LLM returned.
            boolean isCodeFixable = diagnosis.fixable()
                    && !"missing_dependency".equals(diagnosis.category())
                    && !"permission_denied".equals(diagnosis.category());
            if (diagnosis.fixable() && !isCodeFixable) {
                log.info("Overriding fixable=true for category='{}' — code change cannot help here",
                        diagnosis.category());
            }

            log.info("Diagnosis for '{}': category={}, fixable={}, confidence={}, cause={}",
                    step.skill(), diagnosis.category(), isCodeFixable,
                    diagnosis.confidence(), diagnosis.rootCause());

            // Step 2: Attempt repair if fixable
            if (isCodeFixable && diagnosis.hasCodeFix() && diagnosis.confidence() >= 0.4) {
                SkillRepairer.RepairResult repairResult = skillRepairer.repair(
                        step.skill(), diagnosis, userId, taskId);

                if (repairResult.success()) {
                    // Reload skill manifest to pick up the new version
                    skillManifest.reload();

                    // Retry with the repaired skill
                    StepResult retryResult = skillRunner.executeStep(
                            step, userId, taskId, resolvedParams, Map.of());

                    if (retryResult.success()) {
                        eventLog.info(userId, taskId, "skill.self_healed",
                                step.skill() + " self-healed: " + diagnosis.rootCause()
                                        + " → v" + repairResult.newVersion());
                        return retryResult;
                    }

                    // Retry failed even after repair — capture new context for next attempt
                    failureContext = skillRunner.getLastFailureContext();
                    if (failureContext == null) {
                        // Runner didn't record a new context (unusual) — use what we already know.
                        log.warn("No failure context after repair retry for '{}' — ending self-heal", step.skill());
                        return buildDefinitiveFailure(step, diagnosis, failedResult, userId, taskId, resolvedParams);
                    }

                    log.warn("Repaired skill '{}' v{} still fails", step.skill(), repairResult.newVersion());
                    // If repairs keep failing and this is a generated skill, try regeneration once.
                    // Not for missing_dependency / permission_denied — regenerating skill code won't install the binary or grant privileges.
                    if (isGeneratedSkill && !regenerated && attempt == boundedAttempts
                            && !isTerminalCategory(diagnosis.category())) {
                        StepResult regen = attemptRegenerateSkill(step, userId, taskId, resolvedParams, failureContext);
                        if (regen.success()) return regen;
                        regenerated = true;
                    }
                    continue;
                }

                log.warn("Repair failed for '{}': {}", step.skill(), repairResult.summary());
            }

            // If not fixable (or low confidence) and this is a generated skill, try regeneration once.
            // Not for missing_dependency / permission_denied — can't fix by regenerating the Python wrapper.
            if (isGeneratedSkill && !regenerated && !isTerminalCategory(diagnosis.category())) {
                StepResult regen = attemptRegenerateSkill(step, userId, taskId, resolvedParams, failureContext);
                regenerated = true;
                if (regen.success()) return regen;

                // If regeneration didn't help, update context for any remaining attempts.
                SkillFailureContext newContext = skillRunner.getLastFailureContext();
                if (newContext != null) {
                    failureContext = newContext;
                }
            }

            // Not fixable via code (bad_params, network, external) or low confidence
            // For param issues, log the diagnosis so the Mentor can adjust the plan
            if (diagnosis.hasParamFix()) {
                eventLog.info(userId, taskId, "skill.param_diagnosis",
                        step.skill() + " needs different params: " + diagnosis.rootCause());
            }

            // Diagnosis is definitive — retrying the same command won't help.
            // For missing_dependency, ask the user for an alternative before giving up.
            return buildDefinitiveFailure(step, diagnosis, failedResult, userId, taskId, resolvedParams);
        }

        // Loop exhausted all repair attempts without a definitive verdict —
        // one plain retry is worthwhile in case the error was transient.
        log.info("Self-heal exhausted for step {} — executing simple retry", step.id());
        return skillRunner.executeStep(step, userId, taskId, resolvedParams, Map.of());
    }

    /**
     * Build a StepResult for diagnostically definitive failures (fixable=false).
     * <p>For {@code missing_dependency} failures, prompts the user for an alternative
     * approach so the follow-up planning loop has concrete user intent to work with.
     */
    private StepResult buildDefinitiveFailure(TaskStep step, SkillDiagnostician.Diagnosis diagnosis,
                                              StepResult originalFailure, String userId, String taskId,
                                              Map<String, Object> resolvedParams) {
        String cause = diagnosis.rootCause() != null ? diagnosis.rootCause() : originalFailure.output();

        if ("missing_dependency".equals(diagnosis.category())) {
            String binary = extractCommandName(step, cause);

            // First, try to auto-generate a purpose-built Python skill for the same capability.
            // Regenerating the failing skill (e.g. shell_command) won't help — the binary is still
            // missing. Instead, create a new skill that uses Python libraries or APIs.
            if (budgetTracker.hasBudget(userId)) {
                String goalDesc = step.description() != null && !step.description().isBlank()
                        ? step.description()
                        : "perform the task previously attempted via: " + binary
                                + " (params: " + describeParams(step) + ")";
                String altHint = diagnosis.lesson() != null && !diagnosis.lesson().isBlank()
                        ? diagnosis.lesson()
                        : "Use an HTTP API or pure-Python library that has NO system binary dependency. "
                                + "For OCR tasks, prefer ocr.space (free, no key needed for basic use: "
                                + "POST image to https://api.ocr.space/parse/image), Google Vision, or "
                                + "Tesseract-free libs. Do NOT use pytesseract, which still requires "
                                + "the tesseract binary.";

                // Include the original step's input param names so the generated skill knows
                // what to accept from stdin.
                String paramKeys = resolvedParams.entrySet().stream()
                        .filter(e -> !e.getKey().startsWith("__"))
                        .map(e -> e.getKey() + " (e.g. \""
                                + String.valueOf(e.getValue()).substring(0, Math.min(40, String.valueOf(e.getValue()).length())) + "\")")
                        .collect(java.util.stream.Collectors.joining(", "));
                String paramNote = paramKeys.isBlank() ? "" :
                        "\nThe skill will be called with JSON stdin containing these params: " + paramKeys
                                + "\nAccept them via: params = json.loads(sys.stdin.read())";

                String genPrompt = """
                        Create a Python skill to: %s
                        The previous approach called the '%s' binary (via shell_command) which is NOT installed.
                        %s
                        IMPORTANT CONSTRAINTS:
                        - Do NOT use subprocess, os.system, or any shell command.
                        - Do NOT use Python libraries that are just wrappers around missing binaries
                          (e.g. pytesseract still requires tesseract to be installed, so avoid it).
                        - USE HTTP APIs or pure-Python implementations that have no system binary dependency.
                        - Do NOT scrape websites by parsing HTML — use official structured JSON/REST APIs.
                        - Make the skill REUSABLE: accept generic input params (e.g. "query", "location")
                          rather than hard-coding specific values into the script.
                        - ALL third-party imports MUST be inside try/except ImportError.
                        Name the skill after the CAPABILITY it provides (e.g., 'image_ocr', 'web_search').%s
                        """.formatted(goalDesc, binary, altHint, paramNote);

                try {
                    eventLog.info(userId, taskId, "skill.auto_generate_alternative",
                            "'" + binary + "' not installed — auto-generating a capability skill");
                    // Derive a meaningful name for the new skill so the LLM cannot accidentally
                    // name it 'shell_command' (which would version under the broken skill).
                    String capabilityName = deriveCapabilitySkillName(step, binary);
                    SkillGenerator.GenerationResult gen =
                            skillGenerator.generateForName(capabilityName, genPrompt, userId, taskId);
                    if (gen.success()) {
                        skillManifest.reload();
                        eventLog.info(userId, taskId, "skill.alternative_created",
                                "Auto-generated '" + gen.skillName() + "' as alternative for missing '" + binary + "'");
                        // Immediately retry the current step with the newly generated skill.
                        // This avoids a full follow-up planning round just to use the new skill.
                        TaskStep altStep = new TaskStep(step.id(), gen.skillName(), step.description(),
                                step.params(), step.dependsOn(), step.condition(),
                                step.onFail(), step.reversible());
                        StepResult altResult = skillRunner.executeStep(altStep, userId, taskId, resolvedParams, Map.of());
                        if (altResult.success()) {
                            eventLog.info(userId, taskId, "skill.alternative_succeeded",
                                    "Auto-generated '" + gen.skillName() + "' succeeded on first run");
                        } else {
                            eventLog.warn(userId, taskId, "skill.alternative_failed",
                                    "Auto-generated '" + gen.skillName() + "' also failed — follow-up planner will retry");
                            return StepResult.failure(step.id(),
                                    cause + "\nAuto-generated alternative skill: '" + gen.skillName()
                                            + "' was created but also failed: " + altResult.output()
                                            + "\nHint: Try to fix skill '" + gen.skillName() + "'.",
                                    originalFailure.exitCode(), originalFailure.durationMs());
                        }
                        return altResult;
                    }
                } catch (Exception e) {
                    log.warn("Auto-generation of alternative for '{}' failed: {}", binary, e.getMessage());
                }
            }

            // Auto-generation unavailable or failed — ask the user for an alternative.
            eventLog.info(userId, taskId, "skill.missing_dependency",
                    "'" + binary + "' not available — asking user for alternative");

            // Build a prompt that tells the user what was attempted and gives concrete options.
            String goal = step.description() != null && !step.description().isBlank()
                    ? step.description()
                    : "run `" + binary + "`";
            String prompt = "**The system could not complete the step: _" + goal + "_**\n\n"
                    + "The `" + binary + "` program is not installed on this server, "
                    + "and the attempt to auto-create a Python-based replacement skill failed.\n\n"
                    + "**What would you like to do?**\n"
                    + "• **A** — Tell me an API or web service I can use instead "
                    + "(e.g. `https://api.ocr.space/parse/image` for OCR)\n"
                    + "• **B** — Describe a completely different approach to accomplish: _" + goal + "_\n"
                    + "• **C** — Skip this step and continue with the rest of the task\n"
                    + "• **cancel** — Stop the task entirely\n\n"
                    + "Type A/B/C or paste a URL / description:";
            try {
                String userInput = interactionHandler.requestInput(userId, taskId, prompt);
                if (userInput != null && !userInput.isBlank()
                        && !"cancel".equalsIgnoreCase(userInput.strip())) {
                    String stripped = userInput.strip();
                    if ("c".equalsIgnoreCase(stripped)) {
                        // User chose to skip — return a non-fatal failure so the plan continues
                        return StepResult.failure(step.id(),
                                "missing_dependency: '" + binary + "' not installed. User chose to skip this step.",
                                originalFailure.exitCode(), originalFailure.durationMs());
                    }
                    // Embed the user's answer so the follow-up planning loop can use it.
                    return StepResult.failure(step.id(),
                            "missing_dependency: '" + binary + "' is not installed.\n"
                                    + "Goal: " + goal + "\n"
                                    + "User provided guidance: " + stripped,
                            originalFailure.exitCode(), originalFailure.durationMs());
                }
            } catch (Exception e) {
                log.debug("User did not respond to missing_dependency prompt ({})", e.getMessage());
            }
        }

        // Append the diagnosis lesson so the Mentor sees an actionable hint in PREVIOUS FAILURES.
        // This is the primary channel by which diagnostic insights inform follow-up planning.
        String fullOutput = cause;
        if (diagnosis.lesson() != null && !diagnosis.lesson().isBlank()) {
            fullOutput = cause + "\nHint for next attempt: " + diagnosis.lesson();
        }

        // bad_params and data_format are plan-level failures — the Mentor planned wrong inputs
        // (e.g. sent an HTML URL to pdf_parser).  Continuing with independent downstream steps
        // would produce a garbled partial result without any clear explanation.  Mark as
        // terminal so executePlan aborts the plan immediately and falls through to the
        // follow-up planning loop, which will surface the diagnosis to the user.
        boolean isTerminalFailure = "bad_params".equals(diagnosis.category())
                || "data_format".equals(diagnosis.category());
        if (isTerminalFailure) {
            eventLog.warn(userId, taskId, "step.bad_params",
                    "Step " + step.id() + " (" + step.skill() + ") aborted plan: " + truncate(fullOutput, 200));
            return StepResult.definitiveFailure(step.id(), fullOutput, originalFailure.exitCode(), originalFailure.durationMs());
        }
        return StepResult.failure(step.id(), fullOutput, originalFailure.exitCode(), originalFailure.durationMs());
    }

    /**
     * Derive a snake_case skill name that describes the capability rather than the implementation.
     * Prefers the step description; falls back to a name constructed from the missing binary.
     * Ensures the result is never the same as the failing skill name (e.g. 'shell_command').
     */
    private String deriveCapabilitySkillName(TaskStep step, String binary) {
        // Strip filler/stop words and extract up to 3 meaningful content words.
        // Examples:
        //   "perform a web search to find the weather forecast" → "web_search_weather"
        //   "extract text from image using OCR"                → "image_ocr"
        //   "run tesseract on a PNG file"                      → "tesseract_png"
        if (step.description() != null && !step.description().isBlank()) {
            java.util.Set<String> filler = new java.util.HashSet<>(java.util.Arrays.asList(
                    "perform", "execute", "run", "do", "a", "an", "the", "to", "for",
                    "with", "using", "by", "via", "in", "of", "and", "or", "from", "that",
                    "which", "find", "get", "make", "create", "generate", "retrieve", "fetch",
                    "some", "any", "all", "try", "attempt", "this", "task", "step", "result",
                    "on", "at", "as", "its", "it", "is", "be", "been", "being", "tomorrow",
                    "today", "yesterday", "current", "next", "file", "text", "data"));
            String[] words = step.description().toLowerCase().split("[^a-z0-9]+");
            List<String> meaningful = new ArrayList<>();
            for (String w : words) {
                if (!w.isBlank() && !filler.contains(w)) {
                    meaningful.add(w);
                    if (meaningful.size() >= 3) break;
                }
            }
            if (!meaningful.isEmpty()) {
                String base = String.join("_", meaningful);
                if (!base.equals(step.skill())) return base;
            }
        }

        // Fall back to binary name + _python
        String base = binary.toLowerCase().replaceAll("[^a-z0-9]+", "_") + "_python";
        if (base.equals(step.skill())) base = base + "_capability";
        return base;
    }

    /**
     * Categories where no amount of Python code modification can fix the failure.
     * Regenerating the skill is pointless for these — route straight to buildDefinitiveFailure.
     */
    private boolean isTerminalCategory(String category) {
        return "missing_dependency".equals(category) || "permission_denied".equals(category);
    }

    /** Summarise a step's params for inclusion in generation prompts (3 entries max, truncated). */
    private String describeParams(TaskStep step) {
        if (step.params() == null || step.params().isEmpty()) return "(none)";
        return step.params().entrySet().stream()
                .limit(3)
                .map(e -> e.getKey() + "=" + String.valueOf(e.getValue())
                        .substring(0, Math.min(60, String.valueOf(e.getValue()).length())))
                .collect(java.util.stream.Collectors.joining(", "));
    }

    /** Extract the relevant command/binary name from step params or the diagnosis root cause. */
    private String extractCommandName(TaskStep step, String cause) {
        Object cmd = step.params() != null ? step.params().get("command") : null;
        if (cmd instanceof String s && !s.isBlank()) {
            return s.strip().split("\\s+")[0];
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("'([^']+)'")
                .matcher(cause != null ? cause : "");
        if (m.find()) return m.group(1);
        return step.skill();
    }

    private StepResult attemptRegenerateSkill(TaskStep step, String userId, String taskId,
                                             Map<String, Object> resolvedParams,
                                             SkillFailureContext failureContext) {
        if (failureContext == null) {
            return StepResult.failure(step.id(), "No failure context", -1, 0);
        }
        if (!budgetTracker.hasBudget(userId)) {
            return StepResult.failure(step.id(), "No token budget for regeneration", -1, 0);
        }

        // Regenerate a fresh version of the skill (same name) into skills/generated.
        String prompt = "Regenerate the existing OwnClaw skill as a new version. "
                + "The JSON field 'name' MUST be exactly '" + step.skill() + "'. "
                + "Preserve the stdin JSON → stdout JSON-lines protocol and keep changes minimal but robust. "
                                + "If dependencies are missing, include a correct requirements.txt content.\n\n"
                + "Failure report:\n" + failureContext.toDiagnosticReport();

        try {
            SkillGenerator.GenerationResult gen = skillGenerator.generateForName(step.skill(), prompt, userId, taskId);
            if (!gen.success()) {
                log.warn("Regeneration failed for skill '{}': {}", step.skill(), gen.summary());
                return StepResult.failure(step.id(), "Regeneration failed: " + gen.summary(), -1, 0);
            }

            skillManifest.reload();

            // Retry with regenerated skill
            return skillRunner.executeStep(step, userId, taskId, resolvedParams, Map.of());

        } catch (Exception e) {
            log.warn("Regeneration exception for skill '{}': {}", step.skill(), e.getMessage());
            return StepResult.failure(step.id(), "Regeneration exception: " + e.getMessage(), -1, 0);
        }
    }

    private void ensureSkillAvailableOnDisk(TaskStep step, Map<String, Object> resolvedParams,
                                            String userId, String taskId) {
        // If the skill script exists on disk, we're good.
        if (skillLoader.resolveScript(step.skill()).isPresent()) {
            return;
        }

        // If we can't afford cloud tokens, don't attempt generation.
        if (!budgetTracker.hasBudget(userId)) {
            statusEmitter.emit(userId, StatusMessage.Type.WARNING,
                    "Skill '" + step.skill() + "' is missing on disk and token budget is exhausted; cannot auto-generate.");
            eventLog.warn(userId, taskId, "skill.missing_no_budget",
                    "Skill missing on disk: " + step.skill());
            return;
        }

        // Build a generation prompt using manifest metadata when available.
        Optional<SkillModel> meta = skillManifest.findByName(step.skill());
        StringBuilder gen = new StringBuilder();
        gen.append("Create a Python skill for OwnClaw. ");
        gen.append("The JSON field 'name' MUST be exactly '").append(step.skill()).append("'. ");

        if (meta.isPresent()) {
            gen.append("Skill summary: ").append(meta.get().summary()).append(". ");
            gen.append("Parameters: ").append(String.join(", ", meta.get().params())).append(". ");
            if (meta.get().keywords() != null && !meta.get().keywords().isEmpty()) {
                gen.append("Keywords: ").append(String.join(", ", meta.get().keywords())).append(". ");
            }
        } else {
            gen.append("Parameters: ").append(String.join(", ", resolvedParams.keySet())).append(". ");
        }

        gen.append("Example input params: ").append(truncate(resolvedParams.toString(), 300));

        statusEmitter.emit(userId, StatusMessage.Type.MENTOR,
            "Self-healing " + step.skill() + "...");
        eventLog.info(userId, taskId, "skill.auto_gen",
                "Skill missing on disk, triggering generation: " + step.skill());

        try {
            SkillGenerator.GenerationResult genResult = skillGenerator.generateForName(step.skill(), gen.toString(), userId, taskId);
            if (genResult.success() && step.skill().equals(genResult.skillName())) {
                // SkillGenerator already registers + reloads manifest; re-resolve script for safety.
                skillManifest.reload();
                if (skillLoader.resolveScript(step.skill()).isPresent()) {
                    eventLog.info(userId, taskId, "skill.auto_gen_success",
                            "Auto-generated missing skill '" + genResult.skillName() + "' v" + genResult.version());
                } else {
                    eventLog.warn(userId, taskId, "skill.auto_gen_incomplete",
                            "Generated skill but could not resolve it on disk: " + step.skill());
                }
            } else {
                eventLog.warn(userId, taskId, "skill.auto_gen_failed",
                        "Failed to auto-generate missing skill '" + step.skill() + "': " + genResult.summary());
                statusEmitter.emit(userId, StatusMessage.Type.WARNING,
                        "Failed to auto-generate missing skill '" + step.skill() + "': " + genResult.summary());
            }
        } catch (Exception e) {
            log.warn("Auto-generation of missing skill '{}' failed: {}", step.skill(), e.getMessage());
            eventLog.warn(userId, taskId, "skill.auto_gen_exception",
                    "Auto-generation exception for '" + step.skill() + "': " + e.getMessage());
        }
    }

    /**
     * Resolve $N.output, $N.exit_code, $N.success references in step params.
     */
    private Map<String, Object> resolveParams(Map<String, Object> params, Map<Integer, StepResult> results) {
        Map<String, Object> resolved = new LinkedHashMap<>();
        for (var entry : params.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String s) {
                resolved.put(entry.getKey(), resolveReferences(s, results));
            } else {
                resolved.put(entry.getKey(), value);
            }
        }
        return resolved;
    }

    private String resolveReferences(String template, Map<Integer, StepResult> results) {
        Matcher m = REF_PATTERN.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            int stepId = Integer.parseInt(m.group(1));
            String field = m.group(2);
            StepResult r = results.get(stepId);
            String replacement = "";
            if (r != null) {
                replacement = switch (field) {
                    case "output" -> r.output() != null ? r.output() : "";
                    case "exit_code" -> String.valueOf(r.exitCode());
                    case "success" -> String.valueOf(r.success());
                    default -> "";
                };
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String handleConversational(String userId, String taskId, String userMessage) {
        eventLog.info(userId, taskId, "task.conversational", "Conversational — calling Executor LLM");
        statusEmitter.emit(userId, StatusMessage.Type.STARTED, "Thinking...");

        // Load recent conversation history for context (already includes the user message we just saved)
        String sessionId = conversation.getCurrentSession(userId);
        List<Map<String, Object>> recentRows = conversation.getRecentMessages(userId, sessionId, 10);

        // Convert DB rows to LlmMessage list (recentRows is DESC order, reverse to chronological)
        // Exclude the last user message since executor.converse() adds it
        List<LlmMessage> history = new ArrayList<>();
        for (int i = recentRows.size() - 1; i >= 0; i--) {
            Map<String, Object> row = recentRows.get(i);
            String role = (String) row.get("role");
            String content = (String) row.get("content");
            // Skip the current message — it's the one we just saved, and converse() will add it
            if (i == 0 && "user".equals(role) && userMessage.equals(content)) continue;
            history.add(new LlmMessage(
                    "assistant".equals(role) ? LlmMessage.Role.ASSISTANT : LlmMessage.Role.USER,
                    content));
        }

        // Call Executor LLM for real conversational response
        String response = executor.converse(userMessage, history);

        // Save assistant response to conversation
        conversation.saveMessage(userId, sessionId, "assistant", response);

        return response;
    }

    private String buildFinalResponse(Map<Integer, StepResult> results) {
        // Collect ALL successful step outputs — needed for completeness evaluation
        // and for the summarizer to see all gathered information across rounds
        var sb = new StringBuilder();
        int count = 0;
        StepResult last = null;
        for (StepResult r : results.values()) {
            if (!r.success()) continue;
            last = r;
            count++;
        }
        // Single successful step — return its output directly (avoid wrapper noise)
        // Cap to prevent enormous payloads (e.g. full HTML pages) from flooding downstream.
        if (count == 1 && last != null) return capOutput(last.output());
        // Multiple successful steps — concatenate with headers
        for (StepResult r : results.values()) {
            if (!r.success()) continue;
            if (sb.length() > 0) sb.append("\n\n---\n\n");
            sb.append("[Step ").append(r.stepId()).append(" — ").append(r.label()).append("]\n");
            sb.append(capOutput(r.output()));
        }
        return sb.length() > 0 ? sb.toString() : "No results produced.";
    }

    /**
     * Build response for completeness evaluation.
     * Includes BOTH successful outputs AND failure summaries so the evaluator
     * understands what already failed and can make realistic follow-up recommendations.
     */
    private String buildResponseForEvaluation(Map<Integer, StepResult> results) {
        var sb = new StringBuilder();
        // Successful outputs
        for (StepResult r : results.values()) {
            if (!r.success()) continue;
            if (sb.length() > 0) sb.append("\n\n---\n\n");
            sb.append("[Step ").append(r.stepId()).append(" — ").append(r.label()).append(" — SUCCESS]\n");
            sb.append(r.output());
        }
        // Brief failure summaries (not full output — just enough for the eval to know)
        String failureSummary = buildFailureSummary(results);
        if (!failureSummary.isEmpty()) {
            if (sb.length() > 0) sb.append("\n\n---\n\n");
            sb.append("FAILED STEPS (these cannot be retried with the same parameters):\n");
            sb.append(failureSummary);
        }
        return sb.length() > 0 ? sb.toString() : "No results produced.";
    }

    /**
     * Build a concise summary of all failures.
     * Used by the completeness eval and follow-up planner to avoid retrying the same broken operations.
     */
    private String buildFailureSummary(Map<Integer, StepResult> results) {
        var sb = new StringBuilder();
        for (StepResult r : results.values()) {
            if (r.success()) continue;
            if (r.isSkipped()) continue;
            sb.append("- ").append(r.label()).append(": ").append(truncate(r.output(), 300)).append('\n');
        }
        return sb.toString();
    }

    /**
     * Build a response that includes both successful outputs and failure context.
     * This IS passed through the LLM summarizer so it can explain what worked and what didn't.
     */
    private String buildPartialResponse(Map<Integer, StepResult> results) {
        var sb = new StringBuilder();
        // Successful outputs first
        for (StepResult r : results.values()) {
            if (!r.success()) continue;
            if (sb.length() > 0) sb.append("\n\n---\n\n");
            sb.append("[Step ").append(r.stepId()).append(" — ").append(r.label()).append(" — SUCCESS]\n");
            sb.append(capOutput(r.output()));
        }
        // Then failures with context
        for (StepResult r : results.values()) {
            if (r.success()) continue;
            if (r.isSkipped()) continue;
            if (sb.length() > 0) sb.append("\n\n---\n\n");
            sb.append("[Step ").append(r.stepId()).append(" — ").append(r.label()).append(" — FAILED]\n");
            sb.append(truncate(r.output(), 500));
        }
        return sb.length() > 0 ? sb.toString() : "No results produced.";
    }

    /**
     * Build a clean, structured error message from failed steps.
     * This is returned directly to the user — never passed through the LLM.
     */
    private String buildErrorResponse(Map<Integer, StepResult> results) {
        var sb = new StringBuilder();
        sb.append("**Task failed**\n\n");
        for (StepResult r : results.values()) {
            if (r.success()) continue;
            String label = r.label();
            String output = r.output() != null ? r.output().strip() : "Unknown error";
            // Clean up verbose internal errors into user-friendly messages
            if (output.contains("Python interpreter not found")) {
                sb.append("Python is not installed or not found. Please install Python 3 and run `/setup`.\n");
            } else if (output.contains("Skill not found")) {
                sb.append(output).append("\n");
            } else if (output.contains("Credential access not granted")) {
                sb.append(output).append("\n");
            } else if (output.contains("timed out")) {
                sb.append(label).append(" timed out. The operation took too long to complete.\n");
            } else {
                sb.append(label).append(" failed: ");
                sb.append(truncate(output, 300)).append("\n");
            }
        }
        return sb.toString().strip();
    }

    private void persistTaskState(String taskId, String userId, String status, TaskPlan plan) {
        try {
            String planJson = plan != null ? mapper.writeValueAsString(plan) : null;
            jdbc.update("""
                INSERT INTO task_state (task_id, user_id, status, plan, current_step, step_results)
                VALUES (?, ?, ?, ?, 0, '{}')
                ON CONFLICT(task_id) DO UPDATE SET status = ?, plan = ?, updated_at = datetime('now')
                """, taskId, userId, status, planJson, status, planJson);
        } catch (Exception e) {
            log.warn("Failed to persist task state: {}", e.getMessage());
        }
    }

    private void updateTaskStateStep(String taskId, int stepId, Map<Integer, StepResult> results) {
        try {
            String resultsJson = mapper.writeValueAsString(results);
            jdbc.update("""
                UPDATE task_state SET current_step = ?, step_results = ?, updated_at = datetime('now')
                WHERE task_id = ?
                """, stepId, resultsJson, taskId);
        } catch (Exception e) {
            log.warn("Failed to update task state: {}", e.getMessage());
        }
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }

    /**
     * Cap step output to a maximum size suitable for LLM processing.
     * Prevents a single step (e.g. http_request returning a full HTML page)
     * from flooding downstream summarization with 100KB+ of noise.
     */
    private static final int MAX_STEP_OUTPUT = 12_000;

    private static String capOutput(String output) {
        if (output == null) return "";
        if (output.length() <= MAX_STEP_OUTPUT) return output;
        return output.substring(0, MAX_STEP_OUTPUT) + "\n... [output truncated at " + MAX_STEP_OUTPUT + " chars]";
    }

    /**
     * Build a structured, safe fallback response when summarization fails.
     * NEVER returns raw step output — instead explains what happened at a high level.
     */
    private String buildSafeFallbackResponse(String userMessage, Map<Integer, StepResult> results) {
        var sb = new StringBuilder();
        sb.append("I completed the task but couldn't summarize the results properly.\n\n");

        long successCount = results.values().stream().filter(StepResult::success).count();
        long failCount = results.values().stream().filter(r -> !r.success()).count();

        if (successCount > 0) {
            sb.append("**Completed steps:** ").append(successCount).append("\n");
            for (StepResult r : results.values()) {
                if (!r.success()) continue;
                sb.append("- ").append(r.label()).append(" — SUCCESS");
                // Show a brief snippet of the output, not the whole thing
                String snippet = truncate(r.output(), 200);
                if (!snippet.isBlank() && !snippet.contains("<html") && !snippet.contains("<body")) {
                    sb.append(": ").append(snippet);
                }
                sb.append("\n");
            }
        }
        if (failCount > 0) {
            sb.append("\n**Failed steps:** ").append(failCount).append("\n");
            for (StepResult r : results.values()) {
                if (r.success()) continue;
                if ("skipped".equals(r.output())) continue;
                sb.append("- ").append(r.label()).append(": ").append(truncate(r.output(), 200)).append("\n");
            }
        }
        sb.append("\nPlease try rephrasing your question or ask me to try again.");
        return sb.toString();
    }
}
