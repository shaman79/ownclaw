package com.ownclaw.interfaces.web;

import com.ownclaw.agent.AgentLoop;
import com.ownclaw.agent.AgentResult;
import com.ownclaw.agent.tools.DynamicSkillRegistry;
import com.ownclaw.core.TaskCancellationService;
import com.ownclaw.observability.OpsService;
import com.ownclaw.users.AuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ops API: diagnostics and a small set of safe actions, for an operator or an AI assistant
 * driving a test → inspect → fix loop.
 * <p>
 * Authentication is handled entirely by {@code OpsAuthFilter} with the {@code OWNCLAW_OPS_TOKEN}
 * shared secret; there is no user context here and no JWT. If the token is unset the filter
 * refuses every request, so this controller is unreachable by default.
 * <p>
 * Deliberately <b>not</b> offered, because they are what made the old debug API dangerous:
 * reading credential values, arbitrary shell, triggering a deploy, and forcing a restart.
 * Deploys belong to the deploy script and its rollback path.
 */
@RestController
@RequestMapping("/api/ops")
public class OpsController {

    private static final Logger log = LoggerFactory.getLogger(OpsController.class);

    private final OpsService ops;
    private final AgentLoop agentLoop;
    private final AuthService authService;
    private final DynamicSkillRegistry skillRegistry;
    private final TaskCancellationService cancellation;
    private final com.ownclaw.agent.SkillMaintenanceService skillMaintenance;

    public OpsController(OpsService ops, AgentLoop agentLoop, AuthService authService,
                         DynamicSkillRegistry skillRegistry, TaskCancellationService cancellation,
                         com.ownclaw.agent.SkillMaintenanceService skillMaintenance) {
        this.ops = ops;
        this.agentLoop = agentLoop;
        this.authService = authService;
        this.skillRegistry = skillRegistry;
        this.cancellation = cancellation;
        this.skillMaintenance = skillMaintenance;
    }

    // ── discovery ──

    /** Lists the API, so a fresh assistant can orient itself with one call. */
    @GetMapping({"", "/"})
    public ResponseEntity<?> index() {
        return ResponseEntity.ok(Map.of(
                "service", "ownclaw ops api",
                "auth", "X-Ops-Token: <OWNCLAW_OPS_TOKEN>  (or Authorization: Bearer <token>)",
                "readOnly", List.of(
                        "GET  /api/ops/ping",
                        "GET  /api/ops/health",
                        "GET  /api/ops/config",
                        "GET  /api/ops/logs?lines=200&grep=&level=",
                        "GET  /api/ops/db/tables",
                        "POST /api/ops/db/query            {\"sql\":\"SELECT ...\",\"limit\":200}",
                        "GET  /api/ops/users",
                        "GET  /api/ops/forensics/{userId}?limit=200",
                        "GET  /api/ops/skills[?name=x]",
                    "GET  /api/ops/skills/quarantine",
                        "GET  /api/ops/ollama",
                        "GET  /api/ops/tasks?limit=50",
                        "GET  /api/ops/tasks/{taskId}"),
                "actions", List.of(
                        "POST /api/ops/selftest",
                        "POST /api/ops/agent/run           {\"message\":\"...\",\"userId\":\"optional\",\"async\":true}",
                    "GET  /api/ops/agent/run/{runId}   (collect an async run)",
                        "POST /api/ops/agent/cancel/{userId}",
                        "POST /api/ops/skills/reload",
                    "POST /api/ops/skills/maintenance[?apply=true]  (dry run unless apply=true)"),
                "notProvided", List.of(
                        "credential values", "arbitrary shell", "deploy", "restart"),
                "notes", List.of(
                        "Secrets are redacted by config key and by SQL result column.",
                        "db/query accepts a single SELECT only.",
                        "Every call is logged, including the SQL text.")));
    }

    // ── read-only ──

    @GetMapping("/ping")
    public ResponseEntity<?> ping() {
        return ResponseEntity.ok(ops.ping());
    }

    @GetMapping("/health")
    public ResponseEntity<?> health() {
        return ResponseEntity.ok(ops.health());
    }

    @GetMapping("/config")
    public ResponseEntity<?> config() {
        return ResponseEntity.ok(ops.config());
    }

    @GetMapping("/logs")
    public ResponseEntity<?> logs(@RequestParam(defaultValue = "200") int lines,
                                  @RequestParam(required = false) String grep,
                                  @RequestParam(required = false) String level) {
        return ResponseEntity.ok(ops.logs(lines, grep, level));
    }

    @GetMapping("/db/tables")
    public ResponseEntity<?> tables() {
        return ResponseEntity.ok(ops.tables());
    }

    /** One read-only SELECT. See {@code OpsService.query} for the guards. */
    @PostMapping("/db/query")
    public ResponseEntity<?> query(@RequestBody Map<String, Object> body) {
        Object sql = body.get("sql");
        Object limit = body.get("limit");
        Integer cap = limit instanceof Number n ? n.intValue() : null;
        Map<String, Object> result = ops.query(sql == null ? null : String.valueOf(sql), cap);
        return result.containsKey("error") && !result.containsKey("rows")
                ? ResponseEntity.badRequest().body(result)
                : ResponseEntity.ok(result);
    }

    @GetMapping("/users")
    public ResponseEntity<?> users() {
        return ResponseEntity.ok(ops.users());
    }

    /** Everything recorded about one account — built for "who is this and what did it do". */
    @GetMapping("/forensics/{userId}")
    public ResponseEntity<?> forensics(@PathVariable String userId,
                                       @RequestParam(defaultValue = "200") int limit) {
        Map<String, Object> result = ops.forensics(userId, limit);
        return result.containsKey("error")
                ? ResponseEntity.status(404).body(result)
                : ResponseEntity.ok(result);
    }

    /**
     * GET /api/ops/skills/consolidate — tool sequences that keep succeeding together.
     * Detection only; nothing is created.
     */
    /** GET /api/ops/skills/duplicates — registered skills that look like the same capability. */
    @GetMapping("/skills/duplicates")
    public ResponseEntity<?> duplicates(@RequestParam(defaultValue = "0.6") double threshold) {
        return ResponseEntity.ok(ops.duplicateSkills(Math.min(1.0, Math.max(0.3, threshold))));
    }

    @GetMapping("/skills/consolidate")
    public ResponseEntity<?> consolidate(@RequestParam(defaultValue = "2") int minLength,
                                         @RequestParam(defaultValue = "3") int minTasks) {
        return ResponseEntity.ok(ops.consolidationCandidates(
                Math.max(2, minLength), Math.max(2, minTasks)));
    }

    @GetMapping("/skills")
    public ResponseEntity<?> skills(@RequestParam(required = false) String name) {
        return ResponseEntity.ok(ops.skills(name));
    }

    @GetMapping("/ollama")
    public ResponseEntity<?> ollama() {
        return ResponseEntity.ok(ops.ollama());
    }

    @GetMapping("/tasks")
    public ResponseEntity<?> tasks(@RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(ops.tasks(limit));
    }

    @GetMapping("/tasks/{taskId}")
    public ResponseEntity<?> task(@PathVariable String taskId) {
        return ResponseEntity.ok(ops.task(taskId));
    }

    // ── actions ──

    /**
     * Skill maintenance. A dry run by default: {@code POST /api/ops/skills/maintenance} shows
     * what it would retire and why, and only {@code ?apply=true} moves anything.
     */
    @PostMapping("/skills/maintenance")
    public ResponseEntity<?> skillMaintenance(
            @RequestParam(required = false, defaultValue = "false") boolean apply) {
        var actions = skillMaintenance.run(!apply);
        var out = new LinkedHashMap<String, Object>();
        out.put("dryRun", !apply);
        out.put("count", actions.size());
        out.put("retirements", actions.stream().map(r -> Map.of(
                "skill", r.skill(), "rule", r.rule(),
                "reason", r.reason(), "performed", r.performed())).toList());
        out.put("note", apply
                ? "Retired skills were moved to the quarantine/ directory with a REASON file; "
                  + "move one back into generated/ and restart to undo."
                : "Nothing was changed. Re-send with ?apply=true to carry this out.");
        return ResponseEntity.ok(out);
    }

    /** What is sitting in quarantine, recoverable. */
    @GetMapping("/skills/quarantine")
    public ResponseEntity<?> quarantine() {
        var entries = skillRegistry.quarantined();
        return ResponseEntity.ok(Map.of(
                "count", entries.size(),
                "entries", entries,
                "restore", "POST /api/ops/skills/quarantine/restore  "
                        + "{\"entries\":[...]} or {\"all\":true}"));
    }

    /**
     * Move quarantined skills back and register them again. Names come from
     * {@code GET /api/ops/skills/quarantine}; {@code {"all": true}} restores everything.
     */
    @PostMapping("/skills/quarantine/restore")
    public ResponseEntity<?> restoreQuarantined(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> in = body == null ? Map.of() : body;
        List<String> wanted;
        if (Boolean.TRUE.equals(in.get("all"))) {
            wanted = skillRegistry.quarantined();
        } else if (in.get("entries") instanceof List<?> list) {
            wanted = list.stream().map(String::valueOf).toList();
        } else {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Pass {\"entries\":[\"name-timestamp\", ...]} or {\"all\":true}."));
        }
        var restored = new java.util.ArrayList<String>();
        var failed = new java.util.ArrayList<String>();
        for (String entry : wanted) {
            skillRegistry.restoreFromQuarantine(entry)
                    .ifPresentOrElse(restored::add, () -> failed.add(entry));
        }
        return ResponseEntity.ok(Map.of(
                "restored", restored, "restoredCount", restored.size(),
                "failed", failed,
                "note", failed.isEmpty() ? "All requested skills are live again."
                        : "Failures are logged; a skill whose name already exists is skipped."));
    }

    @PostMapping("/selftest")
    public ResponseEntity<?> selftest() {
        return ResponseEntity.ok(ops.selfTest());
    }

    /**
     * Run one agent task synchronously and return the outcome.
     * <p>
     * This is the loop that makes autonomous development possible: send a prompt, read the
     * result, then read {@code /logs?grep=Task+<id>} for the step trail. It runs on the
     * calling thread rather than the task queue, so it does not wait behind other work — and
     * for the same reason it bypasses the queue's serialisation, so avoid running several at
     * once against one Ollama instance.
     * <p>
     * Defaults to the owner's account so context, memory and credentials match normal use.
     */
    @PostMapping("/agent/run")
    public ResponseEntity<?> runAgent(@RequestBody Map<String, Object> body) {
        Object raw = body.get("message");
        String message = raw == null ? "" : String.valueOf(raw).trim();
        if (message.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "message is required"));
        }

        Object requested = body.get("userId");
        String userId = requested != null && !String.valueOf(requested).isBlank()
                ? String.valueOf(requested).trim()
                : authService.ownerId().orElse(null);
        if (userId == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "No owner account exists yet and no userId was given"));
        }

        log.info("Ops agent run as user={}: {}", userId,
                message.length() > 200 ? message.substring(0, 200) + "..." : message);

        // Long work -- a delegation to the local model runs minutes -- outlives the reverse
        // proxy in front of this service, which closes the connection after about two minutes and
        // leaves the caller with an empty body while the run continues invisibly on the server.
        // Asking for it asynchronously returns a handle immediately and the result is collected
        // by polling, so the answer survives the proxy.
        if (Boolean.TRUE.equals(body.get("async"))) {
            return ResponseEntity.accepted().body(startAsyncRun(userId, message));
        }

        long t0 = System.currentTimeMillis();
        try {
            AgentResult result = agentLoop.executeFull(userId, message);
            return ResponseEntity.ok(describeRun(userId, result, t0));
        } catch (Exception e) {
            log.error("Ops agent run failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", e.getClass().getSimpleName() + ": " + e.getMessage(),
                    "durationMs", System.currentTimeMillis() - t0));
        }
    }

    /** Everything the caller is told about a finished run. Shared by the sync and async paths. */
    private Map<String, Object> describeRun(String userId, AgentResult result, long t0) {
        {
            String taskId = ops.latestTaskId(userId);

            var steps = new java.util.ArrayList<Map<String, Object>>();
            var turns = result.trajectory().turns();
            for (int i = 0; i < turns.size(); i++) {
                var turn = turns.get(i);
                var step = new LinkedHashMap<String, Object>();
                step.put("step", i + 1);
                step.put("tool", turn.action().tool());
                step.put("reasoning", turn.action().reasoning());
                step.put("params", turn.action().params());
                step.put("success", turn.observation().success());
                step.put("durationMs", turn.observation().durationMs());
                String output = turn.observation().output();
                step.put("outputLength", output == null ? 0 : output.length());
                step.put("output", output == null || output.length() <= 2000
                        ? output : output.substring(0, 2000) + "…[truncated]");
                steps.add(step);
            }

            var out = new LinkedHashMap<String, Object>();
            out.put("taskId", taskId);
            out.put("userId", userId);
            out.put("success", result.success());
            out.put("terminationReason", String.valueOf(result.terminationReason()));
            out.put("totalSteps", result.totalSteps());
            out.put("agentDurationMs", result.totalDurationMs());
            out.put("response", result.response());
            out.put("steps", steps);
            out.put("durationMs", System.currentTimeMillis() - t0);
            out.put("traceHint", taskId == null ? "no task id recorded"
                    : "GET /api/ops/tasks/" + taskId + " and /api/ops/logs?grep=Task+" + taskId);
            // The caveat this used to carry -- success() being true for every non-cancelled
            // ending, including the step cap and reasoning aborts -- no longer applies: each of
            // those endings now sets success=false and carries its own terminationReason.
            out.put("awaitingUser", result.awaitingUser());
            out.put("outcomeNote", result.awaitingUser()
                    ? "The agent asked the user a question and stopped for the answer. Not a "
                      + "failure and not delivered work; the response field holds the question."
                    : "success=false means the task did not finish: terminationReason says which "
                      + "ending it was.");
            return out;
        }
    }

    // ── asynchronous runs ────────────────────────────────────────────────────

    /** A run started with {"async": true}, kept until it is collected or evicted. */
    private static final class AsyncRun {
        final String userId;
        final long startedAt = System.currentTimeMillis();
        volatile Map<String, Object> result;   // null while still running
        volatile String error;
        AsyncRun(String userId) { this.userId = userId; }
    }

    /**
     * The most recent async runs, oldest evicted first.
     * <p>
     * Bounded because this is a diagnostic surface, not a job store: an unbounded map here would
     * hold every trajectory ever run in memory. Sixteen is enough to collect what you started.
     */
    private final Map<String, AsyncRun> asyncRuns = java.util.Collections.synchronizedMap(
            new java.util.LinkedHashMap<>(32, 0.75f, false) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, AsyncRun> e) {
                    return size() > 16;
                }
            });

    /** One thread: ops runs are for diagnosis, and serialising them keeps them out of each other's way. */
    private final java.util.concurrent.ExecutorService asyncExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "ops-agent-run");
                t.setDaemon(true);
                return t;
            });

    private Map<String, Object> startAsyncRun(String userId, String message) {
        String runId = java.util.UUID.randomUUID().toString().substring(0, 8);
        AsyncRun run = new AsyncRun(userId);
        asyncRuns.put(runId, run);
        asyncExecutor.submit(() -> {
            long t0 = System.currentTimeMillis();
            try {
                run.result = describeRun(userId, agentLoop.executeFull(userId, message), t0);
            } catch (Exception e) {
                log.error("Async ops agent run {} failed: {}", runId, e.getMessage(), e);
                run.error = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
        });
        return Map.of(
                "runId", runId,
                "status", "running",
                "poll", "GET /api/ops/agent/run/" + runId,
                "note", "The run continues on the server regardless of this connection. Poll "
                        + "until status is 'done'; a local delegation can take several minutes.");
    }

    /** Collect an async run. */
    @GetMapping("/agent/run/{runId}")
    public ResponseEntity<?> asyncRunResult(@PathVariable String runId) {
        AsyncRun run = asyncRuns.get(runId);
        if (run == null) {
            return ResponseEntity.status(404).body(Map.of(
                    "error", "No such run. Only the 16 most recent are kept, and they do not "
                            + "survive a restart."));
        }
        if (run.error != null) {
            return ResponseEntity.ok(Map.of("runId", runId, "status", "failed",
                    "error", run.error,
                    "elapsedMs", System.currentTimeMillis() - run.startedAt));
        }
        if (run.result == null) {
            return ResponseEntity.ok(Map.of("runId", runId, "status", "running",
                    "userId", run.userId,
                    "elapsedMs", System.currentTimeMillis() - run.startedAt,
                    "hint", "GET /api/ops/logs?grep=Delegation shows local execution as it happens."));
        }
        var out = new LinkedHashMap<String, Object>();
        out.put("runId", runId);
        out.put("status", "done");
        out.putAll(run.result);
        return ResponseEntity.ok(out);
    }

    /**
     * Request cancellation. With no taskId this stops everything that user is running;
     * with one, only that task.
     */
    @PostMapping("/agent/cancel/{userId}")
    public ResponseEntity<?> cancel(@PathVariable String userId,
                                    @RequestParam(required = false) String taskId) {
        if (taskId != null && !taskId.isBlank()) {
            cancellation.request(userId, taskId);
        } else {
            cancellation.requestAll(userId);
        }
        return ResponseEntity.ok(Map.of("cancelRequested", true, "userId", userId,
                "scope", taskId != null && !taskId.isBlank() ? taskId : "all tasks for this user",
                "caveat", "Cancellation is now observed inside a step as well as between them — "
                        + "a running tool or local call polls it — but a request already in "
                        + "flight to a provider still has to return before it is noticed."));
    }

    /** Re-read the generated skills directory from disk. */
    @PostMapping("/skills/reload")
    public ResponseEntity<?> reloadSkills() {
        skillRegistry.init();
        return ResponseEntity.ok(Map.of(
                "reloaded", true,
                "dynamicSkills", skillRegistry.allDynamic().size()));
    }
}
