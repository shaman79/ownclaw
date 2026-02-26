package com.ownclaw.interfaces.web;

import com.ownclaw.agent.AgentLoop;
import com.ownclaw.agent.AgentResult;
import com.ownclaw.agent.AgentTrajectory;
import com.ownclaw.agent.SkillManager;
import com.ownclaw.agent.tools.ToolRegistry;
import com.ownclaw.observability.ChatStatusEmitter;
import com.ownclaw.observability.ChatStatusEmitter.StatusMessage;
import com.ownclaw.observability.DebugSessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * REST API for automated testing, debugging, and deployment.
 *
 * Designed to be called by an AI assistant for the test → monitor → fix → deploy loop:
 *   POST /api/debug/prompt    — inject a test prompt and get full execution trace
 *   GET  /api/debug/output/{taskId} — retrieve stored trace from a previous run
 *   POST /api/debug/deploy    — trigger git pull + build + restart on the server
 *   GET  /api/debug/status    — system health: registered skills, queue info
 *
 * All endpoints are JWT-protected (handled by JwtAuthFilter).
 */
@RestController
@RequestMapping("/api/debug")
public class DebugController {

    private static final Logger log = LoggerFactory.getLogger(DebugController.class);

    /** Max number of stored traces before oldest are evicted. */
    private static final int MAX_STORED_TRACES = 50;

    private final AgentLoop agentLoop;
    private final ToolRegistry toolRegistry;
    private final SkillManager skillManager;
    private final DebugSessionService debugService;
    private final ChatStatusEmitter statusEmitter;
    private final JdbcTemplate jdbc;

    /** Stored execution traces, keyed by taskId. */
    private final Map<String, Map<String, Object>> storedTraces = new ConcurrentHashMap<>();
    /** Ordered list of taskIds for eviction. */
    private final List<String> traceOrder = new CopyOnWriteArrayList<>();

    public DebugController(
            AgentLoop agentLoop,
            ToolRegistry toolRegistry,
            SkillManager skillManager,
            DebugSessionService debugService,
            ChatStatusEmitter statusEmitter,
            JdbcTemplate jdbc
    ) {
        this.agentLoop = agentLoop;
        this.toolRegistry = toolRegistry;
        this.skillManager = skillManager;
        this.debugService = debugService;
        this.statusEmitter = statusEmitter;
        this.jdbc = jdbc;
    }

    // ────────────────────────────────────────────────────────────────
    //  POST /api/debug/prompt — submit a test message, get full trace
    // ────────────────────────────────────────────────────────────────

    /**
     * Execute a test prompt synchronously and return the full execution trace.
     *
     * Request body: {"message": "your test prompt here"}
     *
     * Response: {
     *   "taskId": "abc12345",
     *   "success": true,
     *   "response": "...",
     *   "terminationReason": "COMPLETED",
     *   "totalSteps": 3,
     *   "durationMs": 4500,
     *   "trajectory": [ { step, tool, reasoning, params, success, output, durationMs }, ... ],
     *   "statusMessages": [ "⚙️ Processing your request...", ... ]
     * }
     */
    @PostMapping("/prompt")
    public ResponseEntity<?> submitPrompt(
            @RequestBody Map<String, String> body,
            @RequestAttribute("userId") String userId
    ) {
        String message = body.get("message");
        if (message == null || message.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Message is required"));
        }

        log.info("Debug API prompt from user={}: {}", userId, truncate(message, 200));

        // Ensure debug mode is on for this user so we get detailed logging
        boolean wasDebugEnabled = debugService.isEnabled(userId);
        if (!wasDebugEnabled) {
            debugService.toggle(userId);
        }

        // Capture all status/debug messages emitted during execution
        List<String> capturedMessages = new CopyOnWriteArrayList<>();
        statusEmitter.subscribe(userId, msg -> capturedMessages.add(
                "[" + msg.type().name() + "] " + msg.text()
        ));

        try {
            AgentResult result = agentLoop.executeFull(userId, message);

            Map<String, Object> trace = buildTrace(result, message, capturedMessages);
            String taskId = (String) trace.get("taskId");
            storeTrace(taskId, trace);

            return ResponseEntity.ok(trace);
        } catch (Exception e) {
            log.error("Debug prompt execution failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Execution failed: " + e.getMessage()
            ));
        } finally {
            // Restore previous debug state
            if (!wasDebugEnabled && debugService.isEnabled(userId)) {
                debugService.toggle(userId);
            }
            // Note: we leave the emitter subscription — the WebSocket handler will re-subscribe
            // when the user next connects, overwriting our listener (which is fine).
        }
    }

    // ────────────────────────────────────────────────────────────────
    //  GET /api/debug/output/{taskId} — retrieve a stored trace
    // ────────────────────────────────────────────────────────────────

    @GetMapping("/output/{taskId}")
    public ResponseEntity<?> getOutput(@PathVariable String taskId) {
        var trace = storedTraces.get(taskId);
        if (trace == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(trace);
    }

    // ────────────────────────────────────────────────────────────────
    //  GET /api/debug/traces — list all stored trace IDs
    // ────────────────────────────────────────────────────────────────

    @GetMapping("/traces")
    public ResponseEntity<?> listTraces() {
        List<Map<String, Object>> summaries = new ArrayList<>();
        for (String taskId : traceOrder) {
            var trace = storedTraces.get(taskId);
            if (trace != null) {
                summaries.add(Map.of(
                        "taskId", taskId,
                        "message", truncate(String.valueOf(trace.get("message")), 100),
                        "success", trace.getOrDefault("success", false),
                        "totalSteps", trace.getOrDefault("totalSteps", 0),
                        "durationMs", trace.getOrDefault("durationMs", 0L),
                        "terminationReason", String.valueOf(trace.getOrDefault("terminationReason", "?"))
                ));
            }
        }
        return ResponseEntity.ok(summaries);
    }

    // ────────────────────────────────────────────────────────────────
    //  POST /api/debug/deploy — trigger git pull + build + restart
    // ────────────────────────────────────────────────────────────────

    /**
     * Trigger deployment by running deploy.sh --update on the server.
     * Returns the script output and exit code.
     *
     * Response: { "success": true, "exitCode": 0, "output": "...", "durationMs": 30000 }
     */
    @PostMapping("/deploy")
    public ResponseEntity<?> deploy() {
        log.info("Debug API deploy triggered");

        // Locate deploy.sh relative to the running JAR or repo
        String deployScript = findDeployScript();
        if (deployScript == null) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "deploy.sh not found. Expected at /opt/ownclaw/repo/deploy/deploy.sh"
            ));
        }

        long startMs = System.currentTimeMillis();
        try {
            ProcessBuilder pb = new ProcessBuilder("bash", deployScript, "--update");
            pb.redirectErrorStream(true);
            pb.environment().put("TERM", "dumb");

            Process process = pb.start();
            String output;
            try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                output = reader.lines().collect(Collectors.joining("\n"));
            }

            // Wait up to 5 minutes for deploy
            boolean finished = process.waitFor(300, java.util.concurrent.TimeUnit.SECONDS);
            long durationMs = System.currentTimeMillis() - startMs;

            if (!finished) {
                process.destroyForcibly();
                return ResponseEntity.ok(Map.of(
                        "success", false,
                        "exitCode", -1,
                        "output", output + "\n[TIMEOUT: deploy did not finish within 5 minutes]",
                        "durationMs", durationMs
                ));
            }

            int exitCode = process.exitValue();
            return ResponseEntity.ok(Map.of(
                    "success", exitCode == 0,
                    "exitCode", exitCode,
                    "output", output,
                    "durationMs", durationMs
            ));
        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startMs;
            log.error("Deploy failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Deploy execution failed: " + e.getMessage(),
                    "durationMs", durationMs
            ));
        }
    }

    // ────────────────────────────────────────────────────────────────
    //  POST /api/debug/skill — create or update a skill directly
    // ────────────────────────────────────────────────────────────────

    /**
     * Create or update a Python skill directly, bypassing the LLM.
     * This allows an external AI assistant to inject properly-written skill code.
     *
     * Request body: {
     *   "name": "web_fetch",
     *   "description": "Fetches a web page...",
     *   "code": "import requests\n...",
     *   "parameters": "{\"url\":{\"type\":\"string\",\"description\":\"URL\",\"required\":true}}",
     *   "requirements": "requests\nbeautifulsoup4",   // optional
     *   "requires_network": true,                      // optional
     *   "has_side_effects": false,                     // optional
     *   "timeout": 30                                  // optional
     * }
     */
    @PostMapping("/skill")
    public ResponseEntity<?> createSkill(@RequestBody Map<String, Object> body) {
        String name = body.get("name") != null ? body.get("name").toString() : null;
        if (name == null || name.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Skill name is required"));
        }

        log.info("Debug API skill create/update: {}", name);

        try {
            String result = skillManager.createSkill(body);
            boolean success = !result.startsWith("ERROR");
            return ResponseEntity.ok(Map.of(
                    "success", success,
                    "name", name,
                    "result", result
            ));
        } catch (Exception e) {
            log.error("Skill creation failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Skill creation failed: " + e.getMessage()
            ));
        }
    }

    // ────────────────────────────────────────────────────────────────
    //  GET /api/debug/status — system health and registered skills
    // ────────────────────────────────────────────────────────────────

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        var skills = toolRegistry.all().stream()
                .map(tool -> Map.of(
                        "name", tool.name(),
                        "description", tool.description()
                ))
                .collect(Collectors.toList());

        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "skillCount", skills.size(),
                "skills", skills
        ));
    }

    // ────────────────────────────────────────────────────────────────
    //  DELETE /api/debug/memory — clear agent memory (episodic/semantic)
    // ────────────────────────────────────────────────────────────────

    /**
     * Clear agent memory entries. Useful for removing poisoned memories from failed tests.
     *
     * Query params:
     *   type=episode (default) | fact | all
     *   userId (optional, defaults to authenticated user)
     */
    @DeleteMapping("/memory")
    public ResponseEntity<?> clearMemory(
            @RequestParam(defaultValue = "episode") String type,
            @RequestAttribute("userId") String userId
    ) {
        log.info("Debug API clearing memory: type={} userId={}", type, userId);

        try {
            int deleted;
            if ("all".equals(type)) {
                deleted = jdbc.update("DELETE FROM agent_memory WHERE user_id = ?", userId);
            } else if ("fact".equals(type)) {
                deleted = jdbc.update("DELETE FROM agent_memory WHERE user_id = ? AND memory_type = 'fact'", userId);
            } else {
                deleted = jdbc.update("DELETE FROM agent_memory WHERE user_id = ? AND memory_type = 'episode'", userId);
            }
            return ResponseEntity.ok(Map.of("deleted", deleted, "type", type));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    // ────────────────────────────────────────────────────────────────
    //  Helpers
    // ────────────────────────────────────────────────────────────────

    private Map<String, Object> buildTrace(AgentResult result, String message, List<String> statusMessages) {
        // Build trajectory as a list of step maps
        List<Map<String, Object>> steps = new ArrayList<>();
        var turns = result.trajectory().turns();
        for (int i = 0; i < turns.size(); i++) {
            var turn = turns.get(i);
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("step", i + 1);
            step.put("tool", turn.action().tool());
            step.put("reasoning", turn.action().reasoning());
            step.put("params", turn.action().params());
            step.put("success", turn.observation().success());
            step.put("output", turn.observation().output());
            step.put("durationMs", turn.observation().durationMs());
            steps.add(step);
        }

        // Derive taskId from result trajectory size and time for uniqueness
        String taskId = UUID.randomUUID().toString().substring(0, 8);

        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("taskId", taskId);
        trace.put("message", message);
        trace.put("success", result.success());
        trace.put("response", result.response());
        trace.put("terminationReason", result.terminationReason().name());
        trace.put("totalSteps", result.totalSteps());
        trace.put("durationMs", result.totalDurationMs());
        trace.put("trajectory", steps);
        trace.put("statusMessages", new ArrayList<>(statusMessages));

        return trace;
    }

    private void storeTrace(String taskId, Map<String, Object> trace) {
        storedTraces.put(taskId, trace);
        traceOrder.add(taskId);

        // Evict oldest if over limit
        while (traceOrder.size() > MAX_STORED_TRACES) {
            String oldest = traceOrder.removeFirst();
            storedTraces.remove(oldest);
        }
    }

    private String findDeployScript() {
        // Try standard server location first
        String serverPath = "/opt/ownclaw/repo/deploy/deploy.sh";
        if (Path.of(serverPath).toFile().exists()) {
            return serverPath;
        }

        // Try relative to working directory (dev mode)
        String devPath = "deploy/deploy.sh";
        if (Path.of(devPath).toFile().exists()) {
            return devPath;
        }

        return null;
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
