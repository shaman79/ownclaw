package com.ownclaw.interfaces.web;

import com.ownclaw.agent.AgentLoop;
import com.ownclaw.agent.AgentResult;
import com.ownclaw.agent.TaskChat;
import com.ownclaw.agent.tools.DynamicSkillRegistry;
import com.ownclaw.conversation.ConversationService;
import com.ownclaw.core.TaskCancellationService;
import com.ownclaw.core.TaskQueue;
import com.ownclaw.core.UserMessage;
import com.ownclaw.observability.OpsService;
import com.ownclaw.users.AuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

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
    private final com.ownclaw.config.OwnClawConfig config;
    private final ConversationService conversations;
    /** Where a chat turn goes: to the task running in its chat, or run as one ({@link #runTask}). */
    private final TaskQueue taskQueue;
    /** Milliseconds now: what async runs are timed and aged by. */
    private final LongSupplier clock;

    @Autowired
    public OpsController(OpsService ops, AgentLoop agentLoop, AuthService authService,
                         DynamicSkillRegistry skillRegistry, TaskCancellationService cancellation,
                         com.ownclaw.agent.SkillMaintenanceService skillMaintenance,
                         com.ownclaw.config.OwnClawConfig config,
                         ConversationService conversations, TaskQueue taskQueue) {
        this(ops, agentLoop, authService, skillRegistry, cancellation, skillMaintenance, config,
                conversations, taskQueue, System::currentTimeMillis);
    }

    /** With the clock async runs are timed and aged by, so a test can move it. */
    OpsController(OpsService ops, AgentLoop agentLoop, AuthService authService,
                  DynamicSkillRegistry skillRegistry, TaskCancellationService cancellation,
                  com.ownclaw.agent.SkillMaintenanceService skillMaintenance,
                  com.ownclaw.config.OwnClawConfig config,
                  ConversationService conversations, TaskQueue taskQueue, LongSupplier clock) {
        this.ops = ops;
        this.agentLoop = agentLoop;
        this.authService = authService;
        this.skillRegistry = skillRegistry;
        this.cancellation = cancellation;
        this.skillMaintenance = skillMaintenance;
        this.config = config;
        this.conversations = conversations;
        this.taskQueue = taskQueue;
        this.clock = clock;
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
                        "GET  /api/ops/logs?lines=200&grep=&level=&cursor=",
                        "GET  /api/ops/db/tables",
                        "POST /api/ops/db/query            {\"sql\":\"SELECT ...\",\"offset\":0,\"limit\":500}",
                        "GET  /api/ops/users",
                        "GET  /api/ops/forensics/{userId}?offset=0&limit=200",
                        "GET  /api/ops/skills[?name=x]",
                    "GET  /api/ops/skills/quarantine",
                        "GET  /api/ops/ollama",
                        "GET  /api/ops/tasks?offset=0&limit=50",
                        "GET  /api/ops/tasks/{taskId}"),
                "actions", List.of(
                        "POST /api/ops/selftest",
                        "POST /api/ops/agent/run           {\"message\":\"...\",\"userId\":\"optional\",\"sessionId\":\"optional: new or a chat id\",\"async\":true,\"unattended\":true}",
                    "GET  /api/ops/agent/run/{runId}   (collect an async run: kept until collected, or a day)",
                        "POST /api/ops/agent/cancel/{userId}",
                        "POST /api/ops/skills/reload",
                    "POST /api/ops/config/native-tools?enabled=true|false",
                    "POST /api/ops/config/local-first?enabled=true|false",
                    "POST /api/ops/config/local-only?enabled=true|false",
                    "POST /api/ops/config/privacy-canary?mode=enforce|observe",
                    "GET  /api/ops/egress?offset=0&limit=50[&decision=SENT|REFUSED|OBSERVED_LEAK|ERROR]",
                    "POST /api/ops/skills/maintenance[?apply=true]  (dry run unless apply=true)",
                    "POST /api/ops/skills/envs/prune[?apply=true]   (orphaned Python envs; dry run unless apply=true)"),
                "notProvided", List.of(
                        "credential values", "arbitrary shell", "deploy", "restart"),
                "notes", List.of(
                        "Secrets are redacted by config key. db/query refuses SQL that names a "
                                + "table holding secrets or private text, and says which; /users "
                                + "lists the accounts, /config the stored settings by name, "
                                + "/forensics one account's records.",
                        "db/query accepts a single SELECT only, and returns every column: a label "
                                + "that repeats is keyed label#2, label#3, ... from its second "
                                + "column on (SELECT 1 AS a, 2 AS a gives a and a#2).",
                        "Listings are paged, never cut: a response with more rows gives nextOffset "
                                + "(and names the sections in more), /logs gives the cursor next; "
                                + "ask again from there. Any limit may be asked for. An offset "
                                + "counts rows, so rows added or deleted between two pages shift it.",
                        "agent/run with sessionId is a chat turn, saved to that chat the way the web "
                                + "chat saves one (\"new\" starts a chat titled Ops check, which does "
                                + "not become the owner's open chat); the response names the chat. "
                                + "While another such turn runs in that chat, the turn is handed to "
                                + "it instead (202, steered: true): the task reads it before its next "
                                + "step, or, ending first, runs it as a task of its own whose answer "
                                + "is saved in the chat. A task asked from the web chat or Telegram "
                                + "takes no turn: one sent in its chat runs beside it, and what is "
                                + "sent there after it is queued.",
                        "Every call is logged, including the SQL text.")));
    }

    /**
     * An IllegalArgumentException from a handler here is answered 400 with its message: it is
     * how a paging value out of range, a number that is not a whole number and a /logs cursor
     * that no longer points anywhere are refused.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> refused(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
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
                                  @RequestParam(required = false) String level,
                                  @RequestParam(required = false) String cursor) {
        return ResponseEntity.ok(ops.logs(lines, grep, level, cursor));
    }

    @GetMapping("/db/tables")
    public ResponseEntity<?> tables() {
        return ResponseEntity.ok(ops.tables());
    }

    /** Rows a db/query page holds when the body names no limit. */
    private static final int QUERY_PAGE = 500;

    /** One read-only SELECT, a page of its rows. See {@code OpsService.query} for the guards. */
    @PostMapping("/db/query")
    public ResponseEntity<?> query(@RequestBody Map<String, Object> body) {
        Object sql = body.get("sql");
        Object offset = body.get("offset") == null ? 0 : body.get("offset");
        Object limit = body.get("limit") == null ? QUERY_PAGE : body.get("limit");
        // JSON numbers arrive as Integer, or Long past the int range: a limit is an int because
        // a page is one list.
        if (!(offset instanceof Integer || offset instanceof Long) || !(limit instanceof Integer)) {
            throw new IllegalArgumentException("offset and limit must be whole numbers (limit up to "
                    + Integer.MAX_VALUE + "), not " + offset + " and " + limit);
        }
        Map<String, Object> result = ops.query(sql == null ? null : String.valueOf(sql),
                new OpsService.Page(((Number) offset).longValue(), (Integer) limit));
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
                                       @RequestParam(defaultValue = "0") long offset,
                                       @RequestParam(defaultValue = "200") int limit) {
        Map<String, Object> result = ops.forensics(userId, new OpsService.Page(offset, limit));
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
    public ResponseEntity<?> tasks(@RequestParam(defaultValue = "0") long offset,
                                   @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(ops.tasks(new OpsService.Page(offset, limit)));
    }

    @GetMapping("/tasks/{taskId}")
    public ResponseEntity<?> task(@PathVariable String taskId) {
        return ResponseEntity.ok(ops.task(taskId));
    }

    // ── actions ──

    /**
     * Python environments whose skill no longer exists. A dry run by default: it lists them with
     * sizes; only {@code ?apply=true} removes anything. On 2026-09-23 this was 45.7 GB of a
     * 99 GB disk.
     */
    @PostMapping("/skills/envs/prune")
    public ResponseEntity<?> pruneSkillEnvironments(
            @RequestParam(required = false, defaultValue = "false") boolean apply) {
        var orphans = skillMaintenance.pruneOrphanedEnvironments(!apply);
        long total = orphans.stream().mapToLong(o -> o.bytes()).sum();
        long freed = orphans.stream().filter(o -> o.removed()).mapToLong(o -> o.bytes()).sum();
        long stillThere = orphans.stream().filter(o -> !o.removed()).count();
        var out = new LinkedHashMap<String, Object>();
        out.put("dryRun", !apply);
        out.put("count", orphans.size());
        out.put("totalMB", total / (1024 * 1024));
        if (apply) {
            out.put("removed", orphans.size() - stillThere);
            out.put("freedMB", freed / (1024 * 1024));
        }
        out.put("orphans", orphans.stream().map(o -> Map.of(
                "skill", o.skill(), "dir", o.dir().toString(),
                "mb", o.bytes() / (1024 * 1024), "removed", o.removed())).toList());
        out.put("note", orphans.isEmpty()
                ? "Nothing to remove — no orphaned environment was listed. (If no skill is loaded "
                  + "at all, the registry cannot say what is live and nothing is listed; see the log.)"
                : !apply
                    ? "Nothing removed. Repeat with ?apply=true to reclaim the space."
                    : stillThere == 0
                        ? "Removed. A quarantined skill that is restored provisions its "
                          + "environment again on its next run."
                        : stillThere + " could not be removed and are still on disk (a file the "
                          + "service user cannot delete); see the log.");
        return ResponseEntity.ok(out);
    }

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

    /**
     * Turn native tool calling on or off without a restart.
     * <p>
     * Exists so the flag can be verified in production the moment it ships, rather than sitting
     * off until someone remembers it. A flag that is never turned on is code that rots -- this
     * codebase already has several examples -- and an env var that needs a redeploy makes both
     * the enabling and the rollback slow enough to postpone. This is the kill switch too:
     * flipping it back takes effect on the next step of the next task.
     */
    @PostMapping("/config/native-tools")
    public ResponseEntity<?> nativeTools(@RequestParam boolean enabled) {
        boolean before = config.getMentor().isNativeTools();
        config.getMentor().setNativeTools(enabled);
        log.warn("Native tool calling {} at runtime (was {})", enabled ? "ENABLED" : "DISABLED", before);
        return ResponseEntity.ok(Map.of(
                "nativeTools", enabled,
                "previous", before,
                "note", "Applies from the next reasoning step. Not persisted: a restart returns "
                        + "to ownclaw.mentor.native-tools in configuration."));
    }

    /**
     * The local-only switch, for this run: on, no cloud model is called and every task runs on
     * the local model -- the way to try that path on a live system and turn it off again. Not
     * saved: a restart returns to the owner's setting (the settings page, /local).
     */
    @PostMapping("/config/local-only")
    public ResponseEntity<?> localOnly(@RequestParam boolean enabled) {
        boolean before = config.getMentor().isLocalOnly();
        config.getMentor().setLocalOnly(enabled);
        log.warn("Local only {} at runtime (was {})", enabled ? "ON" : "OFF", before);
        return ResponseEntity.ok(Map.of(
                "localOnly", enabled,
                "previous", before,
                "note", "Applies from the next model call. Not saved: a restart returns to the "
                        + "owner's setting."));
    }

    /** What the canary does on a hit. Not persisted; OWNCLAW_PRIVACY_CANARY on restart. */
    @PostMapping("/config/privacy-canary")
    public ResponseEntity<?> privacyCanary(@RequestParam String mode) {
        var before = config.getPrivacy().getCanary();
        com.ownclaw.llm.CloudGateway.Mode next;
        try {
            next = com.ownclaw.llm.CloudGateway.Mode.valueOf(mode.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "mode must be enforce or observe"));
        }
        config.getPrivacy().setCanary(next);
        log.warn("Privacy canary {} at runtime (was {})", next, before);
        return ResponseEntity.ok(Map.of(
                "canary", next.name(), "previous", before == null ? "ENFORCE" : before.name(),
                "note", "OBSERVE sends a hit and records OBSERVED_LEAK instead of refusing. Using "
                        + "it is a bug report with the handle and part index attached, not a "
                        + "configuration. Not persisted: a restart returns to OWNCLAW_PRIVACY_CANARY."));
    }

    /** What left this JVM for a cloud model: sizes, hashes, decisions. Never content. */
    @GetMapping("/egress")
    public ResponseEntity<?> egress(@RequestParam(defaultValue = "0") long offset,
                                    @RequestParam(required = false, defaultValue = "50") int limit,
                                    @RequestParam(required = false) String decision) {
        return ResponseEntity.ok(ops.egress(new OpsService.Page(offset, limit), decision));
    }

    /** Withhold the registry from the cloud on unattended work, so it must delegate. */
    @PostMapping("/config/local-first")
    public ResponseEntity<?> localFirst(@RequestParam boolean enabled) {
        boolean before = config.getMentor().isLocalFirstUnattended();
        config.getMentor().setLocalFirstUnattended(enabled);
        log.warn("Local-first unattended execution {} at runtime (was {})",
                enabled ? "ENABLED" : "DISABLED", before);
        return ResponseEntity.ok(Map.of(
                "localFirstUnattended", enabled,
                "previous", before,
                "note", "Applies to the next unattended task. Attended chat is never affected. "
                        + "Falls back to the full tool set whenever the local model is "
                        + "unreachable or does not advertise tool use."));
    }

    @PostMapping("/selftest")
    public ResponseEntity<?> selftest() {
        return ResponseEntity.ok(ops.selfTest());
    }

    /**
     * Run one agent task and return the outcome -- in the response, or, with {@code async}, as a
     * handle to collect it by.
     * <p>
     * This is the loop that makes autonomous development possible: send a prompt, read the
     * result, then read {@code /logs?grep=Task+<id>} for the step trail. It runs outside the task
     * queue's lanes -- on the calling thread, or on a thread of its own when async -- so it does
     * not wait behind other work, and for the same reason nothing serializes it: avoid running
     * several at once against one Ollama instance.
     * <p>
     * Defaults to the owner's account so context, memory and credentials match normal use.
     * <p>
     * With {@code sessionId} the run is a chat turn: the message is saved to that chat as the
     * user row, the task runs attended with that row as the message it answers -- what a
     * web-chat task is handed, so AgentLoop loads its conversation the way it does for one --
     * and the answer is saved after it, as the web chat saves one. Its progress messages are
     * saved in that chat as the task goes, and sent nowhere ({@code TaskChat.Channel.OPS}).
     * While it runs, another turn sent in that chat goes to it, as a message sent in the web
     * chat goes to a task asked there ({@link TaskQueue#steer}), and starts no run. A task asked
     * from the web chat or Telegram takes no turn ({@link com.ownclaw.core.Inbox#offer}): one sent
     * in its chat runs beside it.
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

        log.info("Ops agent run as user={}: {} chars", userId, message.length());

        // Long work -- a delegation to the local model runs minutes -- outlives the reverse
        // proxy in front of this service, which closes the connection after about two minutes and
        // leaves the caller with an empty body while the run continues invisibly on the server.
        // Asking for it asynchronously returns a handle immediately and the result is collected
        // by polling, so the answer survives the proxy.
        // Scheduled work behaves differently from chat -- it delegates, it nudges, it is allowed
        // to take minutes -- and none of that is reachable from here without saying so. Without
        // this flag the only way to observe unattended behaviour is to wait for a cron slot,
        // which makes verifying a change a next-morning affair.
        boolean unattended = Boolean.TRUE.equals(body.get("unattended"));

        Object session = body.get("sessionId");
        String sessionId = session == null || String.valueOf(session).isBlank()
                ? null : String.valueOf(session).trim();
        if (sessionId != null) {
            if (unattended) {
                return ResponseEntity.badRequest().body(Map.of("error", "sessionId makes the run a "
                        + "chat turn, and a chat turn is attended: drop unattended, or drop sessionId."));
            }
            // A chat belongs to a user, so there is none to start for one that does not exist:
            // said here, before anything is saved, rather than as the database's refusal.
            if (NEW_CHAT.equals(sessionId) && !authService.userExists(userId)) {
                return ResponseEntity.badRequest().body(Map.of("error", "There is no user " + userId
                        + ", so no chat can be started for one."));
            }
            if (!NEW_CHAT.equals(sessionId) && conversations.listSessions(userId, true).stream()
                    .noneMatch(s -> sessionId.equals(s.get("id")))) {
                return ResponseEntity.badRequest().body(Map.of("error", "User " + userId
                        + " has no chat " + sessionId + ". Pass \"new\" to start one."));
            }
        }
        UserMessage turn = sessionId == null ? null : startTurn(userId, sessionId, message);
        if (turn != null && taskQueue.steer(turn)) {
            var steered = new LinkedHashMap<String, Object>();
            steered.put("steered", true);
            steered.put("sessionId", turn.sessionId());
            steered.put("messageId", turn.messageId());
            steered.put("note", "A chat turn of this API is running in this chat: the message was handed to "
                    + "it, and it reads it before its next step. If the task ends first, the message runs as "
                    + "a task of its own, and its answer is saved in the chat.");
            return ResponseEntity.accepted().body(steered);
        }

        if (Boolean.TRUE.equals(body.get("async"))) {
            return ResponseEntity.accepted().body(startAsyncRun(userId, message, unattended, turn));
        }

        long t0 = clock.getAsLong();
        try {
            return ResponseEntity.ok(describeRun(userId, runTask(userId, message, unattended, turn), turn, t0));
        } catch (Exception e) {
            log.error("Ops agent run failed: {}", e.getMessage(), e);
            var failed = new LinkedHashMap<String, Object>();
            failed.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            failed.put("durationMs", clock.getAsLong() - t0);
            if (turn != null) failed.put("sessionId", turn.sessionId());
            return ResponseEntity.internalServerError().body(failed);
        }
    }

    /** The {@code sessionId} that starts a fresh chat for the turn. */
    private static final String NEW_CHAT = "new";

    /**
     * The user's half of a chat turn, saved as the web chat saves it: the chat titled from the
     * message if it is still "New Chat", then the user row -- before the task runs, so the task
     * finds it as the message it answers. Its answer is saved in the chat as the web chat saves
     * one: the answer of the task it runs, or -- handed to a running task that ended before it
     * read it -- of the task it then runs on the queue.
     */
    private UserMessage startTurn(String userId, String sessionId, String message) {
        // A fresh chat is never made the open one: the web page files what the owner types next
        // in the open chat, and an ops check must not move his conversation.
        String chat = NEW_CHAT.equals(sessionId)
                ? conversations.createSessionWithoutOpening(userId, "Ops check") : sessionId;
        conversations.autoTitleIfNeeded(userId, chat, message);
        return new UserMessage(userId, chat, conversations.saveMessage(userId, chat, "user", message, List.of()),
                message, List.of(), TaskChat.Channel.OPS, result -> conversations.saveAnswer(userId, chat, result));
    }

    /**
     * Run the task. A chat turn runs attended on the queue's chat path ({@link TaskQueue#runChat}),
     * with its user row as the current message, and its answer is saved as the web chat saves
     * one -- also when the task throws: then "Internal error" is its answer, and the exception
     * still reaches the caller.
     */
    private AgentResult runTask(String userId, String message, boolean unattended, UserMessage turn) {
        return turn != null ? taskQueue.runChat(turn)
                : agentLoop.executeFull(userId, message, unattended, null, List.of(), TaskChat.Channel.OPS);
    }

    /** Everything the caller is told about a finished run. Shared by the sync and async paths. */
    private Map<String, Object> describeRun(String userId, AgentResult result, UserMessage chat, long t0) {
        {
            String taskId = result.taskId();

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
                step.put("output", output);
                steps.add(step);
            }

            var out = new LinkedHashMap<String, Object>();
            out.put("taskId", taskId);
            out.put("userId", userId);
            if (chat != null) out.put("sessionId", chat.sessionId());
            out.put("success", result.success());
            out.put("terminationReason", String.valueOf(result.terminationReason()));
            out.put("totalSteps", result.totalSteps());
            out.put("agentDurationMs", result.totalDurationMs());
            out.put("response", result.response());
            out.put("steps", steps);
            out.put("durationMs", clock.getAsLong() - t0);
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

    /** A run started with {"async": true}, kept until it is collected. */
    private static final class AsyncRun {
        final String userId;
        final String sessionId;   // the chat of a chat turn, else null
        final long startedAt;
        volatile Map<String, Object> result;
        volatile String error;
        /** When the run ended, 0 while it runs. Written after result or error, so it publishes them. */
        volatile long finishedAt;
        AsyncRun(String userId, String sessionId, long startedAt) {
            this.userId = userId;
            this.sessionId = sessionId;
            this.startedAt = startedAt;
        }
    }

    /** How long a finished run nobody collects is kept. */
    private static final Duration UNCOLLECTED_KEPT = Duration.ofDays(1);

    /**
     * Async runs, each until its result is collected. One nobody collects is dropped a day after
     * it ended, and a running one never is, so the map holds what is running and what is waiting
     * to be read -- and no run is lost because others were started after it. Looked up through
     * {@link #asyncRuns()}, which drops the expired ones first.
     */
    private final Map<String, AsyncRun> runs = new ConcurrentHashMap<>();

    /** The async runs, less those that ended more than {@link #UNCOLLECTED_KEPT} ago. */
    private Map<String, AsyncRun> asyncRuns() {
        long now = clock.getAsLong();
        runs.values().removeIf(run -> run.finishedAt != 0 && now - run.finishedAt > UNCOLLECTED_KEPT.toMillis());
        return runs;
    }

    /** One thread: ops runs are for diagnosis, and serialising them keeps them out of each other's way. */
    private final java.util.concurrent.ExecutorService asyncExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "ops-agent-run");
                t.setDaemon(true);
                return t;
            });

    private Map<String, Object> startAsyncRun(String userId, String message, boolean unattended,
                                              UserMessage turn) {
        String runId = java.util.UUID.randomUUID().toString().substring(0, 8);
        AsyncRun run = new AsyncRun(userId, turn == null ? null : turn.sessionId(), clock.getAsLong());
        asyncRuns().put(runId, run);
        asyncExecutor.submit(() -> {
            long t0 = clock.getAsLong();
            try {
                run.result = describeRun(userId, runTask(userId, message, unattended, turn), turn, t0);
            } catch (Throwable e) {
                // Throwable: a run that died of an Error would otherwise read as running forever.
                log.error("Async ops agent run {} failed: {}", runId, e.getMessage(), e);
                run.error = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
            run.finishedAt = clock.getAsLong();
        });
        var out = new LinkedHashMap<String, Object>();
        out.put("runId", runId);
        out.put("status", "running");
        if (run.sessionId != null) out.put("sessionId", run.sessionId);
        out.put("poll", "GET /api/ops/agent/run/" + runId);
        out.put("note", "The run continues on the server regardless of this connection. Poll until "
                + "status is 'done'; a local delegation can take several minutes. The result is kept "
                + "until it is collected, or for a day after the run ends if it never is.");
        return out;
    }

    /** Collect an async run. A finished run is handed over once and then forgotten. */
    @GetMapping("/agent/run/{runId}")
    public ResponseEntity<?> asyncRunResult(@PathVariable String runId) {
        AsyncRun run = asyncRuns().get(runId);
        if (run == null) {
            return ResponseEntity.status(404).body(Map.of(
                    "error", "No such run. A finished run is kept until it is collected once, or for "
                            + "a day after it ended if it never is, and no run survives a restart."));
        }
        var out = new LinkedHashMap<String, Object>();
        out.put("runId", runId);
        if (run.finishedAt == 0) {
            out.put("status", "running");
            out.put("userId", run.userId);
            if (run.sessionId != null) out.put("sessionId", run.sessionId);
            out.put("elapsedMs", clock.getAsLong() - run.startedAt);
            out.put("hint", "GET /api/ops/logs?grep=Delegation shows local execution as it happens.");
            return ResponseEntity.ok(out);
        }
        runs.remove(runId);
        if (run.error != null) {
            out.put("status", "failed");
            out.put("error", run.error);
            if (run.sessionId != null) out.put("sessionId", run.sessionId);
            out.put("elapsedMs", clock.getAsLong() - run.startedAt);
            return ResponseEntity.ok(out);
        }
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
            cancellation.request(userId, taskId, "a stop request from the ops API");
        } else {
            cancellation.requestAll(userId, "a stop request from the ops API");
        }
        return ResponseEntity.ok(Map.of("cancelRequested", true, "userId", userId,
                "scope", taskId != null && !taskId.isBlank() ? taskId : "all tasks for this user",
                "caveat", "Cancellation is observed inside a step as well as between them: a "
                        + "model call the task is waiting on -- think, code-writing, analysis or a "
                        + "delegation's -- ends at once, whether or not its reply has started, and "
                        + "a delegation stops before its next step. A running skill is not "
                        + "interrupted: the task stops when it returns."));
    }

    /**
     * Re-read the generated skills directory from disk.
     * <p>
     * reload(), not init(). init() only scans and adds, so a skill removed from the directory --
     * exactly what the quarantine note tells you to do to undo a restore -- stayed registered
     * and invocable with no files behind it, until someone restarted the service. Reload
     * unregisters everything first, so the registry ends up matching what is actually on disk.
     */
    @PostMapping("/skills/reload")
    public ResponseEntity<?> reloadSkills() {
        int before = skillRegistry.allDynamic().size();
        skillRegistry.reload();
        int after = skillRegistry.allDynamic().size();
        return ResponseEntity.ok(Map.of(
                "reloaded", true,
                "dynamicSkills", after,
                "changed", after - before,
                "note", "The registry now matches the generated/ directory, including removals."));
    }
}
