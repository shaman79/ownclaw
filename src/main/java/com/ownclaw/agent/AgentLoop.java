package com.ownclaw.agent;

import com.ownclaw.agent.memory.AgentMemory;
import com.ownclaw.agent.tools.*;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.core.TaskCancellationService;
import com.ownclaw.llm.LlmProvider;
import com.ownclaw.observability.ChatStatusEmitter;
import com.ownclaw.observability.ChatStatusEmitter.StatusMessage;
import com.ownclaw.observability.DebugSessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.ownclaw.llm.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * The AgentLoop is the central execution engine.
 *
 * It implements the reactive Think → Critique → Act → Observe loop:
 *   1. ThinkingEngine decides the next action (LLM call)
 *   2. CriticAgent validates the action (fast, rule-based)
 *   3. Tool is executed (sandbox, HTTP, etc.)
 *   4. Observation is recorded in the trajectory
 *   5. Repeat until the agent responds, times out, or hits a limit
 *
 * The loop is the central execution engine that replaced the old plan-first approach.
 */
@Component
public class AgentLoop {

    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);

    private final ThinkingEngine thinkingEngine;
    private final CriticAgent criticAgent;
    private final ToolRegistry toolRegistry;
    private final ChatStatusEmitter statusEmitter;
    private final OwnClawConfig config;
    private final LlmRouter llmRouter;
    private final AgentMemory memory;
    private final SkillCuratorService curatorService;
    private final SkillManager skillManager;
    private final DebugSessionService debugService;
    private final TaskCancellationService cancellationService;

    public AgentLoop(
            ThinkingEngine thinkingEngine,
            CriticAgent criticAgent,
            ToolRegistry toolRegistry,
            ChatStatusEmitter statusEmitter,
            OwnClawConfig config,
            LlmRouter llmRouter,
            AgentMemory memory,
            SkillCuratorService curatorService,
            SkillManager skillManager,
            DebugSessionService debugService,
            TaskCancellationService cancellationService
    ) {
        this.thinkingEngine = thinkingEngine;
        this.criticAgent = criticAgent;
        this.toolRegistry = toolRegistry;
        this.statusEmitter = statusEmitter;
        this.config = config;
        this.llmRouter = llmRouter;
        this.memory = memory;
        this.curatorService = curatorService;
        this.skillManager = skillManager;
        this.debugService = debugService;
        this.cancellationService = cancellationService;
    }

    /**
     * Execute the agent loop for a user message.
     * This is the main entry point — replaces the old orchestrator.processMessage().
     *
     * @param userId  the user who submitted the task
     * @param message the user's message
     * @return the agent's final response string
     */
    public String execute(String userId, String message) {
        try {
            AgentResult result = executeFull(userId, message);
            return result.response();
        } catch (Exception e) {
            log.error("AgentLoop fatal error for user={}: {}", userId, e.getMessage(), e);
            statusEmitter.emit(userId, StatusMessage.Type.FAILED, "An unexpected error occurred.");
            return "I encountered an unexpected error while processing your request. Please try again.";
        }
    }

    /**
     * Execute the agent loop and return the full AgentResult (with trajectory).
     * Used by the debug API for full execution trace visibility.
     *
     * @param userId  the user who submitted the task
     * @param message the user's message
     * @return the full AgentResult including trajectory, steps, and timing
     */
    public AgentResult executeFull(String userId, String message) {
        String taskId = UUID.randomUUID().toString().substring(0, 8);
        AgentContext context = new AgentContext(userId, taskId, message);

        // Recall relevant past experiences to enrich context
        try {
            List<AgentMemory.MemoryEntry> relevantMemories = memory.recallEpisodes(userId, message, 3);
            if (!relevantMemories.isEmpty()) {
                String memoryContext = relevantMemories.stream()
                        .map(m -> (m.outcome() ? "[SUCCESS] " : "[FAILED] ") + m.content())
                        .collect(Collectors.joining("\n"));
                context.metadata().put("relevantMemories", memoryContext);
            }
            List<AgentMemory.MemoryEntry> facts = memory.getFacts(userId);
            if (!facts.isEmpty()) {
                String factContext = facts.stream()
                        .map(AgentMemory.MemoryEntry::content)
                        .collect(Collectors.joining("\n"));
                context.setUserPreferences(factContext);
            }
        } catch (Exception e) {
            log.debug("Failed to recall memories for user {}: {}", userId, e.getMessage());
        }

        // Clear any stale cancel flag from a previous task
        cancellationService.clear(userId);

        statusEmitter.emit(userId, StatusMessage.Type.STARTED, "Processing your request...");

        AgentResult result = runLoop(context);
        emitResult(userId, result);

        // Store this execution as an episodic memory
        storeEpisode(context, result);

        return result;
    }

    /**
     * Execute the agent loop with a pre-built context (for advanced use cases).
     */
    public AgentResult executeWithContext(AgentContext context) {
        try {
            return runLoop(context);
        } catch (Exception e) {
            log.error("AgentLoop error: {}", e.getMessage(), e);
            return AgentResult.error(
                    "An unexpected error occurred: " + e.getMessage(),
                    context.trajectory(),
                    context.elapsedMs()
            );
        }
    }

    /**
     * The core loop implementation.
     */
    private AgentResult runLoop(AgentContext context) {
        int maxSteps = config.getTasks().getMaxPlanSteps();
        long timeoutMs = config.getTasks().getDefaultTimeout() * 1000L;

        for (int step = 0; step < maxSteps; step++) {
            // Check cancellation — both local flag and service flag from WebSocket cancel button
            if (context.isCancelled() || cancellationService.isCancelled(context.userId())) {
                log.info("Task {} cancelled by user", context.taskId());
                return AgentResult.cancelled(
                        "Task was cancelled.",
                        context.trajectory(),
                        context.elapsedMs()
                );
            }

            // Check timeout
            if (context.elapsedMs() > timeoutMs) {
                log.warn("Task {} timed out after {}ms", context.taskId(), context.elapsedMs());
                return AgentResult.timeout(
                        "I ran out of time working on this task. Here's what I found so far:\n" +
                                summarizeProgress(context),
                        context.trajectory(),
                        context.elapsedMs()
                );
            }

            // === THINK ===
            LlmProvider provider = llmRouter.selectProvider(context);
            statusEmitter.emit(context.userId(), StatusMessage.Type.STEP,
                    "Thinking... (step " + (step + 1) + ")");

            boolean debug = debugService.isEnabled(context.userId());

            ThinkResult thinkResult = thinkingEngine.decideNextActionFull(context, provider);
            AgentAction action = thinkResult.action();

            // Emit debug info when debug mode is active
            if (debug) {
                emitDebugPrompt(context.userId(), thinkResult, step + 1);
            }

            log.info("Task {} step {}: tool={} reasoning={}",
                    context.taskId(), step + 1, action.tool(),
                    truncate(action.reasoning(), 100));

            // === RESPOND / ASK ===
            if (action.isResponse()) {
                return AgentResult.completed(
                        action.responseText(),
                        context.trajectory(),
                        context.elapsedMs()
                );
            }

            if (action.isAskUser()) {
                return AgentResult.completed(
                        action.responseText(),
                        context.trajectory(),
                        context.elapsedMs()
                );
            }

            // === SKILL MANAGEMENT (special actions — always available) ===
            if (action.isSkillCreate()) {
                statusEmitter.emit(context.userId(), StatusMessage.Type.STEP,
                        "Creating skill '" + action.params().getOrDefault("name", "?") + "'...");

                // Regenerate skill code using the cloud LLM for superior quality
                Map<String, Object> enhancedParams = enhanceSkillCodeWithCloud(action.params(), context);

                // Auto-infer pip requirements from import statements in the code
                enhancedParams = ensureRequirements(enhancedParams);

                long startMs = System.currentTimeMillis();
                String result = skillManager.createSkill(enhancedParams);
                long durationMs = System.currentTimeMillis() - startMs;
                boolean ok = !result.startsWith("ERROR");
                AgentObservation obs = ok
                        ? AgentObservation.success(action.tool(), result, Map.of(), durationMs)
                        : AgentObservation.failure(action.tool(), result, durationMs);
                context.trajectory().record(action, obs);
                if (debug) {
                    emitDebug(context.userId(),
                            "SKILL_CREATE [" + enhancedParams.getOrDefault("name", "?") + "] "
                                    + (ok ? "OK" : "FAIL") + " (" + durationMs + "ms)\n"
                                    + truncate(result, 2000));
                }
                continue;
            }

            if (action.isSkillManage()) {
                long startMs = System.currentTimeMillis();
                String result = executeSkillManage(action.params());
                long durationMs = System.currentTimeMillis() - startMs;
                boolean ok = !result.startsWith("ERROR");
                AgentObservation obs = ok
                        ? AgentObservation.success(action.tool(), result, Map.of(), durationMs)
                        : AgentObservation.failure(action.tool(), result, durationMs);
                context.trajectory().record(action, obs);
                if (debug) {
                    emitDebug(context.userId(),
                            "SKILL_MANAGE [" + action.params().getOrDefault("action", "?") + "] "
                                    + (ok ? "OK" : "FAIL") + " (" + durationMs + "ms)\n"
                                    + truncate(result, 2000));
                }
                continue;
            }

            // === CRITIQUE ===
            CriticAgent.Verdict verdict = criticAgent.evaluate(action, context);
            if (!verdict.allowed()) {
                log.warn("Task {} step {} blocked by critic: {}", context.taskId(), step + 1, verdict.blockReason());
                if (debug) {
                    emitDebug(context.userId(), "CRITIC BLOCKED: " + verdict.blockReason());
                }
                // Feed the block reason back as an observation so the ThinkingEngine can adjust
                AgentObservation blockObs = AgentObservation.failure(
                        action.tool(),
                        "BLOCKED: " + verdict.blockReason(),
                        0
                );
                context.trajectory().record(action, blockObs);
                continue;
            }

            if (verdict.hasWarnings()) {
                for (String warning : verdict.warnings()) {
                    log.info("Task {} critic warning: {}", context.taskId(), warning);
                }
            }

            // === ACT ===
            AgentObservation observation = executeTool(action, context);

            // === OBSERVE ===
            context.trajectory().record(action, observation);

            if (debug) {
                emitDebug(context.userId(),
                        "TOOL RESULT [" + action.tool() + "] "
                                + (observation.success() ? "OK" : "FAIL")
                                + " (" + observation.durationMs() + "ms)\n"
                                + truncate(observation.output(), 2000));
            }

            // Track tool usage for skill curation analytics
            curatorService.recordUsage(action.tool(), context.userId(), context.taskId(),
                    observation.success(), observation.durationMs());

            if (observation.success()) {
                statusEmitter.emit(context.userId(), StatusMessage.Type.PROGRESS,
                        action.tool() + " completed (" + observation.durationMs() + "ms)");
            } else {
                statusEmitter.emit(context.userId(), StatusMessage.Type.WARNING,
                        action.tool() + " failed: " + truncate(observation.output(), 100));
            }

            // Inject reflection after consecutive failures OR consecutive hollow results
            injectReflection(context, action);
        }

        // Exhausted max steps
        log.warn("Task {} hit max steps ({})", context.taskId(), maxSteps);
        return AgentResult.maxSteps(
                "I've reached the maximum number of steps for this task. Here's what I found:\n" +
                        summarizeProgress(context),
                context.trajectory(),
                context.elapsedMs()
        );
    }

    /**
     * Execute a tool and wrap the result in an AgentObservation.
     */
    private AgentObservation executeTool(AgentAction action, AgentContext context) {
        var toolOpt = toolRegistry.find(action.tool());
        if (toolOpt.isEmpty()) {
            String available = String.join(", ", toolRegistry.names());
            String hint = available.isEmpty()
                    ? "No tools are currently registered. Use skill_create to build the tool you need."
                    : "Available tools: " + available + ". Use skill_create to build a new tool if none of these fit.";
            return AgentObservation.failure(action.tool(),
                    "Tool '" + action.tool() + "' not found. " + hint, 0);
        }

        Tool tool = toolOpt.get();
        ToolExecutionContext execCtx = new ToolExecutionContext(
                context.userId(),
                context.taskId(),
                null, // workDir — can be extended later
                context::isCancelled
        );

        long startMs = System.currentTimeMillis();
        try {
            ToolResult result = tool.execute(action.params(), execCtx);
            long durationMs = System.currentTimeMillis() - startMs;

            if (result.success()) {
                return AgentObservation.success(
                        action.tool(),
                        result.output(),
                        result.structured(),
                        durationMs
                );
            } else {
                return AgentObservation.failure(
                        action.tool(),
                        result.output(),
                        result.structured(),
                        durationMs
                );
            }
        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startMs;
            log.error("Tool '{}' threw exception: {}", action.tool(), e.getMessage(), e);
            return AgentObservation.failure(
                    action.tool(),
                    "Tool execution error: " + e.getMessage(),
                    durationMs
            );
        }
    }

    /**
     * Dispatch a skill_manage action to the appropriate SkillManager method.
     */
    private String executeSkillManage(Map<String, Object> params) {
        String action = params.get("action") != null ? params.get("action").toString() : "";
        String name = params.get("name") != null ? params.get("name").toString() : null;

        return switch (action) {
            case "read" -> skillManager.readSkill(name);
            case "delete" -> skillManager.deleteSkill(name);
            case "list" -> skillManager.listSkills();
            case "analyze" -> skillManager.analyzeSkills();
            default -> "ERROR: Unknown action '" + action + "'. Use one of: read, delete, list, analyze";
        };
    }

    /**
     * Inject a reflection observation when the agent is struggling.
     * Triggers on consecutive failures OR consecutive hollow (empty-output) results.
     */
    private void injectReflection(AgentContext context, AgentAction lastAction) {
        int failures = context.trajectory().consecutiveFailures();
        int hollow = context.trajectory().consecutiveHollowResults();
        int trouble = Math.max(failures, hollow);
        if (trouble < 2) return;

        String reflectionHint;
        if (trouble == 2) {
            reflectionHint = "REFLECTION: The last " + trouble + " tool calls " +
                    (failures >= 2 ? "failed" : "returned empty/useless output") + ". " +
                    "Reconsider your approach. Inspect the tool's code with skill_manage(action='read') " +
                    "to find the bug, then fix it with skill_create. Or try a fundamentally different strategy.";
        } else {
            reflectionHint = "REFLECTION: " + trouble + " consecutive " +
                    (failures >= trouble ? "failures" : "empty results") + ". " +
                    "STOP repeating the same approach. Read the skill code, fix it, or try " +
                    "a completely different technique. If nothing works, respond with what you know.";
        }

        // Record reflection as a synthetic observation so the ThinkingEngine sees it
        AgentAction reflectionAction = new AgentAction("_reflection", Map.of(), "System-injected reflection");
        AgentObservation reflectionObs = AgentObservation.success("_reflection", reflectionHint, Map.of(), 0);
        context.trajectory().record(reflectionAction, reflectionObs);
    }

    /**
     * Summarize the progress made so far (for timeout/max-steps responses).
     */
    private String summarizeProgress(AgentContext context) {
        var trajectory = context.trajectory();
        if (trajectory.isEmpty()) return "No actions were taken.";

        var sb = new StringBuilder();
        int successCount = (int) trajectory.turns().stream()
                .filter(t -> t.observation().success())
                .count();
        sb.append("I took ").append(trajectory.size()).append(" actions (")
                .append(successCount).append(" successful).\n");

        // Include the last successful observation's output as the partial result
        for (int i = trajectory.turns().size() - 1; i >= 0; i--) {
            var turn = trajectory.turns().get(i);
            if (turn.observation().success() && turn.observation().output() != null
                    && !turn.observation().output().isBlank()) {
                sb.append("\nLast successful result:\n");
                String output = turn.observation().output();
                if (output.length() > 1000) {
                    output = output.substring(0, 1000) + "...[truncated]";
                }
                sb.append(output);
                break;
            }
        }

        return sb.toString();
    }

    /**
     * Store the completed task as an episodic memory for future recall.
     */
    private void storeEpisode(AgentContext context, AgentResult result) {
        try {
            String summary = "Task: " + truncate(context.originalMessage(), 200) +
                    "\nSteps: " + result.totalSteps() +
                    "\nOutcome: " + result.terminationReason() +
                    "\nResponse: " + truncate(result.response(), 500);

            // Extract tool names used as tags
            List<String> tags = context.trajectory().turns().stream()
                    .map(t -> t.action().tool())
                    .filter(t -> t != null && !t.equals(AgentAction.RESPOND))
                    .distinct()
                    .collect(Collectors.toList());

            memory.storeEpisode(
                    context.userId(),
                    context.taskId(),
                    summary,
                    result.success(),
                    tags
            );
        } catch (Exception e) {
            log.debug("Failed to store episode for task {}: {}", context.taskId(), e.getMessage());
        }
    }

    private void emitResult(String userId, AgentResult result) {
        if (result.success()) {
            statusEmitter.emit(userId, StatusMessage.Type.COMPLETED,
                    "Task completed in " + result.totalSteps() + " steps (" +
                            result.totalDurationMs() + "ms)");
        } else {
            statusEmitter.emit(userId, StatusMessage.Type.FAILED,
                    "Task ended: " + result.terminationReason());
        }
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    // ── Auto-infer pip requirements from Python imports ──

    /**
     * Standard library modules that do NOT need pip install.
     * This list covers Python 3.10+ stdlib modules commonly used in skill code.
     */
    private static final Set<String> PYTHON_STDLIB = Set.of(
            "abc", "argparse", "ast", "asyncio", "base64", "binascii",
            "builtins", "calendar", "cgi", "cmath", "codecs", "collections",
            "concurrent", "configparser", "contextlib", "copy", "csv",
            "ctypes", "dataclasses", "datetime", "decimal", "difflib",
            "dis", "email", "enum", "errno", "fnmatch", "fractions",
            "ftplib", "functools", "gc", "getpass", "gettext", "glob",
            "gzip", "hashlib", "heapq", "hmac", "html", "http",
            "imaplib", "importlib", "inspect", "io", "ipaddress",
            "itertools", "json", "keyword", "linecache", "locale",
            "logging", "lzma", "math", "mimetypes", "multiprocessing",
            "numbers", "operator", "os", "pathlib", "pdb", "pickle",
            "pkgutil", "platform", "pprint", "profile", "pstats",
            "queue", "random", "re", "readline", "reprlib", "resource",
            "runpy", "sched", "secrets", "select", "shelve", "shlex",
            "shutil", "signal", "site", "smtplib", "socket", "socketserver",
            "sqlite3", "ssl", "stat", "statistics", "string", "struct",
            "subprocess", "sys", "sysconfig", "syslog", "tarfile",
            "tempfile", "textwrap", "threading", "time", "timeit",
            "token", "tokenize", "tomllib", "trace", "traceback",
            "tracemalloc", "tty", "turtle", "types", "typing",
            "unicodedata", "unittest", "urllib", "uu", "uuid",
            "venv", "warnings", "weakref", "webbrowser", "xml",
            "xmlrpc", "zipfile", "zipimport", "zlib",
            // typing extensions
            "typing_extensions",
            // Common sub-modules users import from
            "os.path", "urllib.parse", "urllib.request", "collections.abc",
            "concurrent.futures", "email.mime", "html.parser",
            "http.client", "http.server", "xml.etree", "xml.dom"
    );

    /**
     * Map from Python import module name → pip package name.
     * Only needed when the module name differs from the pip package name.
     */
    private static final Map<String, String> MODULE_TO_PIP = Map.ofEntries(
            Map.entry("bs4", "beautifulsoup4"),
            Map.entry("PIL", "Pillow"),
            Map.entry("cv2", "opencv-python"),
            Map.entry("sklearn", "scikit-learn"),
            Map.entry("yaml", "PyYAML"),
            Map.entry("docx", "python-docx"),
            Map.entry("pptx", "python-pptx"),
            Map.entry("attr", "attrs"),
            Map.entry("dotenv", "python-dotenv"),
            Map.entry("gi", "PyGObject"),
            Map.entry("serial", "pyserial"),
            Map.entry("usb", "pyusb"),
            Map.entry("magic", "python-magic"),
            Map.entry("dateutil", "python-dateutil"),
            Map.entry("Bio", "biopython"),
            Map.entry("wx", "wxPython"),
            Map.entry("Crypto", "pycryptodome"),
            Map.entry("jose", "python-jose"),
            Map.entry("jwt", "PyJWT"),
            Map.entry("github", "PyGithub"),
            Map.entry("googleapiclient", "google-api-python-client"),
            Map.entry("fitz", "PyMuPDF"),
            Map.entry("chardet", "chardet"),
            Map.entry("lxml", "lxml"),
            Map.entry("openpyxl", "openpyxl"),
            Map.entry("tabulate", "tabulate"),
            Map.entry("tqdm", "tqdm"),
            Map.entry("numpy", "numpy"),
            Map.entry("pandas", "pandas"),
            Map.entry("matplotlib", "matplotlib"),
            Map.entry("scipy", "scipy"),
            Map.entry("flask", "flask"),
            Map.entry("fastapi", "fastapi"),
            Map.entry("uvicorn", "uvicorn"),
            Map.entry("pydantic", "pydantic"),
            Map.entry("httpx", "httpx"),
            Map.entry("aiohttp", "aiohttp"),
            Map.entry("selenium", "selenium"),
            Map.entry("playwright", "playwright"),
            Map.entry("pymongo", "pymongo"),
            Map.entry("redis", "redis"),
            Map.entry("celery", "celery"),
            Map.entry("boto3", "boto3"),
            Map.entry("paramiko", "paramiko"),
            Map.entry("cryptography", "cryptography"),
            Map.entry("jinja2", "Jinja2"),
            Map.entry("Jinja2", "Jinja2"),
            Map.entry("markupsafe", "MarkupSafe"),
            Map.entry("requests", "requests"),
            Map.entry("pdfplumber", "pdfplumber"),
            Map.entry("PyPDF2", "PyPDF2"),
            Map.entry("pypdf", "pypdf"),
            Map.entry("camelot", "camelot-py"),
            Map.entry("pytesseract", "pytesseract"),
            Map.entry("feedparser", "feedparser"),
            Map.entry("xmltodict", "xmltodict"),
            Map.entry("toml", "toml"),
            Map.entry("arrow", "arrow"),
            Map.entry("pendulum", "pendulum"),
            Map.entry("rich", "rich"),
            Map.entry("click", "click"),
            Map.entry("typer", "typer"),
            Map.entry("colorama", "colorama")
    );

    /**
     * Ensure the skill params include all pip requirements needed by the code.
     * Parses import statements and maps module names to pip packages.
     * Merges with any explicitly specified requirements.
     */
    private Map<String, Object> ensureRequirements(Map<String, Object> params) {
        String code = str(params, "code");
        if (code == null || code.isBlank()) return params;

        String existingReqs = str(params, "requirements");
        Set<String> existing = new LinkedHashSet<>();
        if (existingReqs != null && !existingReqs.isBlank()) {
            for (String line : existingReqs.split("\n")) {
                String trimmed = line.strip();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    // Extract bare package name (strip version specifiers)
                    String pkg = trimmed.split("[>=<\\[!~]")[0].strip().toLowerCase();
                    existing.add(pkg);
                }
            }
        }

        Set<String> inferred = inferRequirementsFromCode(code);

        // Remove packages already in existing requirements (case-insensitive)
        inferred.removeIf(pkg -> existing.contains(pkg.toLowerCase()));

        if (inferred.isEmpty()) return params;

        // Merge: existing requirements + inferred ones
        StringBuilder merged = new StringBuilder();
        if (existingReqs != null && !existingReqs.isBlank()) {
            merged.append(existingReqs.strip()).append('\n');
        }
        for (String pkg : inferred) {
            merged.append(pkg).append('\n');
        }

        log.info("Auto-inferred pip requirements for skill: {} (merged with existing: {})",
                inferred, existing);

        Map<String, Object> updated = new HashMap<>(params);
        updated.put("requirements", merged.toString().strip());
        return updated;
    }

    /**
     * Infer pip package requirements from Python import statements.
     * Returns a set of pip package names needed by the code.
     */
    private Set<String> inferRequirementsFromCode(String code) {
        Set<String> packages = new LinkedHashSet<>();

        // Match: import X, from X import Y, from X.Y import Z
        var importPattern = java.util.regex.Pattern.compile(
                "^\\s*(?:import|from)\\s+([a-zA-Z_][a-zA-Z0-9_.]*)",
                java.util.regex.Pattern.MULTILINE
        );

        var matcher = importPattern.matcher(code);
        while (matcher.find()) {
            String module = matcher.group(1);
            // Get the top-level module name
            String topLevel = module.contains(".") ? module.substring(0, module.indexOf('.')) : module;

            // Skip stdlib modules
            if (PYTHON_STDLIB.contains(topLevel) || PYTHON_STDLIB.contains(module)) {
                continue;
            }

            // Map to pip package name
            String pipPkg = MODULE_TO_PIP.getOrDefault(topLevel, topLevel);
            packages.add(pipPkg);
        }

        return packages;
    }

    // ── Cloud-escalated skill code generation ──

    /**
     * Enhance skill code by regenerating it with the cloud LLM.
     *
     * <p>The local model decides WHAT skill to create (name, description, parameter
     * intent) — that's fast routing.  The cloud model writes the actual Python
     * code — that's where quality matters most.
     *
     * <p>If the cloud provider is unavailable or the call fails, falls back to
     * the original (local-generated) code so skill creation never blocks.
     */
    private Map<String, Object> enhanceSkillCodeWithCloud(Map<String, Object> originalParams, AgentContext context) {
        LlmProvider cloud = llmRouter.cloud();
        if (!cloud.isAvailable()) {
            log.info("Cloud provider unavailable, using local-generated skill code");
            return originalParams;
        }

        String name = str(originalParams, "name");
        String description = str(originalParams, "description");
        String parameters = str(originalParams, "parameters");
        String requirements = str(originalParams, "requirements");
        String localCode = str(originalParams, "code");

        statusEmitter.emit(context.userId(), StatusMessage.Type.STEP,
                "Generating skill code with cloud LLM...");

        try {
            List<LlmMessage> messages = buildSkillCodePrompt(
                    name, description, parameters, requirements, localCode, context);

            LlmRequestConfig codeGenConfig = new LlmRequestConfig(
                    null,   // use provider default model
                    0.2,    // low temperature for precise code generation
                    8192,   // generous token budget for complete code
                    false   // no JSON mode — we want raw Python code
            );

            LlmResponse response = cloud.chat(messages, codeGenConfig);
            String cloudCode = extractPythonCode(response.content());

            if (cloudCode != null && !cloudCode.isBlank()) {
                log.info("Cloud LLM generated {} chars of skill code for '{}' ({} tokens)",
                        cloudCode.length(), name, response.totalTokens());

                // Build enhanced params with cloud-generated code
                Map<String, Object> enhanced = new HashMap<>(originalParams);
                enhanced.put("code", cloudCode);

                // Cloud may also suggest better requirements — extract if present
                String cloudRequirements = extractRequirements(response.content());
                if (cloudRequirements != null) {
                    enhanced.put("requirements", cloudRequirements);
                }

                return enhanced;
            } else {
                log.warn("Cloud LLM returned no extractable Python code, falling back to local");
                return originalParams;
            }
        } catch (Exception e) {
            log.warn("Cloud skill code generation failed for '{}': {}, falling back to local",
                    name, e.getMessage());
            return originalParams;
        }
    }

    /**
     * Build a specialized prompt for the cloud LLM to generate high-quality skill code.
     */
    private List<LlmMessage> buildSkillCodePrompt(
            String name, String description, String parameters,
            String requirements, String localDraft, AgentContext context) {

        List<LlmMessage> messages = new ArrayList<>();

        // System prompt: expert Python code generator
        var sys = new StringBuilder();
        sys.append("You are an expert Python developer generating production-quality code for a skill ");
        sys.append("in an autonomous agent system.\n\n");

        sys.append("## Skill Contract\n");
        sys.append("- The script MUST define `def run(params):` as the entry point.\n");
        sys.append("- `params` is a dict with the parameters defined in the skill spec.\n");
        sys.append("- The function MUST return a dict with an 'output' key containing the result string.\n");
        sys.append("- On failure, return `{'output': 'ERROR: <description>'}` — never raise unhandled exceptions.\n\n");

        sys.append("## Quality Standards\n");
        sys.append("- **Encoding**: Always handle character encoding properly. For HTTP responses, use ");
        sys.append("`response.encoding = response.apparent_encoding` or detect charset from headers/content. ");
        sys.append("Support UTF-8, Latin-1, Windows-1250, and other common encodings.\n");
        sys.append("- **Content types**: Detect and handle different content types (HTML, PDF, JSON, XML, ");
        sys.append("plain text, binary). Check Content-Type headers and file extensions.\n");
        sys.append("- **HTML processing**: Use BeautifulSoup to extract clean, readable text. Strip scripts, ");
        sys.append("styles, navigation boilerplate. Preserve document structure (headings, lists, tables).\n");
        sys.append("- **Link extraction**: For HTML, ALWAYS extract and include navigation links (hrefs) ");
        sys.append("at the end of the output under a '## Links' section. Format: `[link text](url)`. ");
        sys.append("Resolve relative URLs to absolute URLs using urllib.parse.urljoin. ");
        sys.append("This is CRITICAL — the agent navigates websites by following these links.\n");
        sys.append("- **Error handling**: Catch all exceptions. Report HTTP status codes, connection errors, ");
        sys.append("and timeouts clearly. Never let the skill crash.\n");
        sys.append("- **Large content**: If output might exceed 10KB, truncate intelligently — return the ");
        sys.append("most relevant portion with a note about truncation.\n");
        sys.append("- **Network**: Set reasonable timeouts (10-30s). Use proper User-Agent headers. ");
        sys.append("Follow redirects.\n");
        sys.append("- **Robustness**: Handle edge cases — empty responses, invalid URLs, missing data, ");
        sys.append("unexpected formats. The skill must work reliably across diverse inputs.\n\n");

        sys.append("## Output Format\n");
        sys.append("Return ONLY the Python code inside a ```python code fence. No explanations before or after.\n");
        sys.append("If you suggest pip requirements beyond what was specified, add them in a separate ");
        sys.append("```requirements fence after the code.\n");

        messages.add(LlmMessage.system(sys.toString()));

        // User prompt: the skill specification
        var user = new StringBuilder();
        user.append("Generate the Python code for this skill:\n\n");
        user.append("**Name**: ").append(name).append("\n");
        user.append("**Description**: ").append(description).append("\n");
        user.append("**Parameters**: ").append(parameters).append("\n");
        if (requirements != null && !requirements.isBlank()) {
            user.append("**Available pip packages**: ").append(requirements).append("\n");
        }

        // Include the task context so the cloud knows what the skill needs to accomplish
        user.append("\n**Context**: The agent is working on this task: \"");
        user.append(truncate(context.originalMessage(), 500));
        user.append("\"\n");

        // Include the local model's draft as a starting point
        if (localDraft != null && !localDraft.isBlank()) {
            user.append("\n**Draft code** (from a smaller model — improve and fix it):\n```python\n");
            user.append(localDraft);
            user.append("\n```\n");
        }

        messages.add(LlmMessage.user(user.toString()));

        return messages;
    }

    /**
     * Extract Python code from a cloud LLM response.
     * Handles ```python fences, plain ``` fences, and raw code.
     */
    private String extractPythonCode(String response) {
        if (response == null || response.isBlank()) return null;

        // Try to find ```python ... ``` fence
        int start = response.indexOf("```python");
        if (start >= 0) {
            start = response.indexOf('\n', start) + 1;
            int end = response.indexOf("```", start);
            if (end > start) {
                return response.substring(start, end).strip();
            }
        }

        // Try plain ``` fence
        start = response.indexOf("```");
        if (start >= 0) {
            start = response.indexOf('\n', start) + 1;
            int end = response.indexOf("```", start);
            if (end > start) {
                return response.substring(start, end).strip();
            }
        }

        // If the response looks like raw Python code (starts with import or def), use it directly
        String trimmed = response.strip();
        if (trimmed.startsWith("import ") || trimmed.startsWith("from ") || trimmed.startsWith("def ")) {
            return trimmed;
        }

        return null;
    }

    /**
     * Extract pip requirements from a cloud LLM response if it included a
     * ```requirements fence.
     */
    private String extractRequirements(String response) {
        if (response == null) return null;

        int start = response.indexOf("```requirements");
        if (start < 0) return null;

        start = response.indexOf('\n', start) + 1;
        int end = response.indexOf("```", start);
        if (end > start) {
            String reqs = response.substring(start, end).strip();
            return reqs.isBlank() ? null : reqs;
        }
        return null;
    }

    private String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v != null ? v.toString() : null;
    }

    // ── Debug helpers ──

    private void emitDebug(String userId, String text) {
        statusEmitter.emit(userId, StatusMessage.Type.DEBUG, text);
    }

    /**
     * Emit the full prompt and raw LLM response for a thinking step.
     */
    private void emitDebugPrompt(String userId, ThinkResult result, int step) {
        var sb = new StringBuilder();
        sb.append("### Step ").append(step).append(" — Thinking\n\n");

        sb.append("**Prompt messages** (").append(result.promptMessages().size()).append("):\n");
        for (var msg : result.promptMessages()) {
            sb.append("\n---\n**[").append(msg.role().name()).append("]**\n");
            String content = msg.content();
            if (content.length() > 4000) {
                content = content.substring(0, 4000) + "\n\n...[truncated, " + msg.content().length() + " chars total]";
            }
            sb.append(content).append('\n');
        }

        sb.append("\n---\n**Raw LLM output** (").append(result.totalTokens()).append(" tokens):\n```json\n");
        String raw = result.rawLlmOutput();
        if (raw != null && raw.length() > 2000) {
            raw = raw.substring(0, 2000) + "\n...[truncated]";
        }
        sb.append(raw != null ? raw : "(null)").append("\n```\n");

        sb.append("**Parsed action**: tool=`").append(result.action().tool())
                .append("` reasoning=").append(truncate(result.action().reasoning(), 300));

        emitDebug(userId, sb.toString());
    }
}
