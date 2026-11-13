package com.ownclaw.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.ownclaw.agent.AgentResult.TerminationReason;
import com.ownclaw.agent.tools.Tool;
import com.ownclaw.agent.tools.ToolRegistry;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.conversation.ConversationService;
import com.ownclaw.core.ResultDelivery;
import com.ownclaw.core.TaskQueue;
import com.ownclaw.observability.EventLogService;
import com.ownclaw.privacy.Redactor;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiPredicate;

/**
 * Self-repair: when unattended work -- a scheduled run, /bg -- shows that a skill did not do its
 * whole job, a background task repairs the skill, checks the repair where it safely can, and the
 * owner is told what it did.
 * <p>
 * On 12 and 13 Nov 2026 the scheduled lunch menu ran daily_menu_fetcher; one restaurant's site
 * answered "HTTP 455 Blocked", the skill fell back to a wrong picture, read nothing from it, and
 * returned success with "OCR se nezdařilo" inside. The task completed, the email went out degraded
 * twice, and the cloud model even wrote in its answer that one menu could not be read -- and
 * nothing acted on it. What acts on it now is code:
 * <ul>
 *   <li>What a task shows ({@link #found}): the skills its final answer names in {@code problems}
 *       -- offered to unattended work's respond only ({@link SpecialActionSchemas#RESPOND_UNATTENDED})
 *       -- and, with no model's judgement, every skill whose last call in the task failed. Only a
 *       generated skill of the task's user that ran in the task; any other name is logged and
 *       left.</li>
 *   <li>What starts ({@link #take}): one repair per skill at a time -- a problem reported while one
 *       runs is added to its record -- under the task's user, at background priority, with a goal
 *       written here: what ran and when, the problem, and the evidence, each call of the skill in
 *       the task with its output when that is PUBLIC and its descriptor when it is not.</li>
 *   <li>What a repair may do ({@link #outOfScope}): change its one skill, and call no tool that has
 *       side effects -- refused in the loop's steps and in every delegated call alike.</li>
 *   <li>When none starts: a repair that ended without changing the skill's code, followed by the
 *       problem again, with the code still what it left, is no progress -- the owner is told, once,
 *       and it is left to him. A repair that changed the code and was followed by the problem again
 *       is progress: the next repair is given its report. No count of attempts ends anything.</li>
 *   <li>How it ended ({@link #finish}), by code: the code's hash before and after, whether the
 *       skill was run after its last change, and whether that run worked and the repair's own
 *       answer named no problem with it. The owner is told through the channels unattended
 *       results use ({@link ResultDelivery}): the chat of scheduled results, and Telegram.</li>
 * </ul>
 * Never for a chat, whose owner is there to ask for a fix; nor an ops run; nor a repair's own
 * problems, which are its verdict, not a reason for another. Switched off by
 * {@code ownclaw.self-repair.enabled}.
 */
@Service
public class SkillSelfRepair {

    private static final Logger log = LoggerFactory.getLogger(SkillSelfRepair.class);

    /** respond's parameter for what did not do its whole job ({@link SpecialActionSchemas#RESPOND_UNATTENDED}). */
    public static final String PROBLEMS = "problems";

    /** A repair's respond parameter for how its check went ({@link SpecialActionSchemas#RESPOND_REPAIR}). */
    public static final String CHECK = "check";

    /** What {@link #CHECK} may say. */
    static final List<String> CHECKS = List.of("passed", "failed", "not_run");

    /**
     * The repair a task is: its record, the skill, the task's id (given before it runs, so the
     * triggering task's ending can name it), whether the skill had side effects when the repair
     * began, and whether the work it repairs after was local only -- then the repair is too.
     */
    public record Job(long id, String skill, String taskId, boolean sideEffects, boolean localOnly) {}

    /** One entry of an answer's {@code problems}, as it was read: a tool's name and what it did not do. */
    public record Reported(String tool, String what) {}

    /** What a task showed of one skill: what went wrong, in sentences, and its calls in the task. */
    record Found(String skill, List<String> what, List<Artifact> calls) {}

    static final String RUNNING = "running", FIXED = "fixed", UNVERIFIED = "unverified", NOT_FIXED = "not_fixed",
            UNCHANGED = "unchanged", GAVE_UP = "gave_up", INTERRUPTED = "interrupted", NOT_STARTED = "not_started",
            STOPPED = "stopped", NOT_FINISHED = "not_finished";
    // interrupted, not_started, stopped and not_finished are no verdict on the skill: the repair never
    // ran, was cut off, was stopped, or did not finish -- an outage, the stall watchdog, the window, a
    // question nobody answers -- without changing anything. The no-progress rule reads none of them.

    /** The event rows of a triggering task and of a repair task: the task page reads them. */
    static final String EVENT = "self_repair";

    /** What a task found by code: the last call of the skill failed. */
    static final String LAST_CALL_FAILED = "Its last call in the task failed, and no later call of it succeeded.";

    /** In place of a sentence of the task's model that repeats a private result. */
    static final String WITHHELD = "(what the task's model wrote about it repeats a private result, so it is "
            + "not given here)";

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter AT = DateTimeFormatter.ofPattern("HH:mm");

    private final JdbcTemplate jdbc;
    private final OwnClawConfig config;
    private final SkillManager skills;
    private final ToolRegistry tools;
    private final TaskQueue queue;
    private final ResultDelivery delivery;
    private final ConversationService conversations;
    private final EventLogService events;
    /** The zone "at 06:30" is said in: the scheduler's own. */
    private final ZoneId zone;
    /** Held to decide and record a repair, so two tasks ending at once cannot start two of one skill. */
    private final Object lock = new Object();

    @org.springframework.beans.factory.annotation.Autowired
    public SkillSelfRepair(JdbcTemplate jdbc, OwnClawConfig config, SkillManager skills, ToolRegistry tools,
                           @Lazy TaskQueue queue, ResultDelivery delivery, ConversationService conversations,
                           EventLogService events) {
        this(jdbc, config, skills, tools, queue, delivery, conversations, events, ZoneId.systemDefault());
    }

    SkillSelfRepair(JdbcTemplate jdbc, OwnClawConfig config, SkillManager skills, ToolRegistry tools,
                    TaskQueue queue, ResultDelivery delivery, ConversationService conversations,
                    EventLogService events, ZoneId zone) {
        this.jdbc = jdbc;
        this.config = config;
        this.skills = skills;
        this.tools = tools;
        this.queue = queue;
        this.delivery = delivery;
        this.conversations = conversations;
        this.events = events;
        this.zone = zone;
    }

    /**
     * Said at startup, like the other switches; and a repair that was under way when OwnClaw
     * stopped is recorded as cut off -- it did not end, so it neither runs on nor stops the next.
     */
    @PostConstruct
    public void start() {
        if (enabled()) {
            log.info("Self-repair on: a skill that unattended work shows did not do its whole job is repaired "
                    + "by a background task, and the owner is told.");
        } else {
            log.info("Self-repair off (ownclaw.self-repair.enabled): problems of unattended work start no repair.");
        }
        int cut = jdbc.update("UPDATE skill_repairs SET outcome = ?, ended_at = datetime('now') WHERE outcome = ?",
                INTERRUPTED, RUNNING);
        if (cut > 0) log.warn("Self-repair: {} repair(s) under way when OwnClaw stopped recorded as interrupted.", cut);
    }

    private boolean enabled() {
        return config.getSelfRepair().isEnabled();
    }

    // ───────────────────────── a task ended ─────────────────────────

    /**
     * A task has ended ({@link AgentLoop}): a repair is recorded and the owner told; unattended
     * work that showed problems starts the repairs, and its ending says so -- the result returned
     * is the one emitted, delivered and remembered. Nothing here may break a task's ending: what
     * fails is logged, and the ending is returned as it was.
     */
    public AgentResult taskEnded(AgentContext ctx, AgentResult ended) {
        try {
            // A repair is recorded however it ended, the switch aside: its record must not stay open.
            if (ctx.repair() != null) {
                finish(ctx, ended);
                return ended;
            }
            if (!enabled() || !ctx.isUnattended() || ctx.isOps()) return ended;
            Map<String, Found> found = found(ctx, ended, this::isOwnSkill);
            if (ended.terminationReason() == TerminationReason.COMPLETED) cleared(ctx, found);
            if (found.isEmpty()) return ended;
            var lines = new ArrayList<String>();
            for (Found f : found.values()) lines.add(take(ctx, f));
            String said = "\n\n" + String.join("\n\n", lines);
            return new AgentResult(ended.success(), ended.response() + said, ended.trajectory(), ended.totalSteps(),
                    ended.totalDurationMs(), ended.terminationReason(), ended.taskId(),
                    ended.ownerText() == null ? null : ended.ownerText() + said, ended.choices(), ended.localOnly());
        } catch (RuntimeException e) {
            log.error("Self-repair after task {} failed: {}", ctx.taskId(), e.getMessage(), e);
            return ended;
        }
    }

    /** A generated skill a task of this user may change: what self-repair repairs. */
    private boolean isOwnSkill(String name, String userId) {
        return tools.find(name).isPresent() && skills.isChangeableSkill(name, userId);
    }

    /**
     * What a task showed, skill by skill, in the order its calls ran: each skill its delivered
     * answer named in {@code problems} -- only one that ran in the task, and only a generated skill
     * of its user ({@code own}); any other name is logged and left -- and, by code, each such skill
     * whose last call failed, unless the task was stopped, which can be what failed it. A sentence
     * that repeats a private result of the task ({@link AgentContext#firstRunOf}) is not carried
     * into a repair's goal, which goes to the cloud.
     */
    static Map<String, Found> found(AgentContext ctx, AgentResult ended, BiPredicate<String, String> own) {
        List<Artifact> results = new ArrayList<>(ctx.artifacts());
        var callsOf = new LinkedHashMap<String, List<Artifact>>();
        for (Artifact a : results) callsOf.computeIfAbsent(a.tool(), n -> new ArrayList<>()).add(a);

        var found = new LinkedHashMap<String, Found>();
        for (Reported r : parseProblems(ctx.reportedProblems())) {
            if (!callsOf.containsKey(r.tool())) {
                log.warn("Task {}: a problem reported of '{}' was ignored -- it did not run in the task.",
                        ctx.taskId(), r.tool());
                continue;
            }
            if (!own.test(r.tool(), ctx.userId())) {
                log.warn("Task {}: a problem reported of '{}' was ignored -- it is no generated skill of user {}.",
                        ctx.taskId(), r.tool(), ctx.userId());
                continue;
            }
            // Stored, shown and sent to the cloud in the repair's goal: no private result's run, and
            // no vault value, as nothing stored for display holds one.
            String what = ctx.firstRunOf(results, r.what()) == null
                    ? Redactor.scrubVault(r.what(), ctx.secretValues()).text() : WITHHELD;
            found.computeIfAbsent(r.tool(), n -> new Found(n, new ArrayList<>(), callsOf.get(n))).what().add(what);
        }
        if (ended.terminationReason() != TerminationReason.CANCELLED) {
            for (var e : callsOf.entrySet()) {
                if (e.getValue().getLast().succeeded() || !own.test(e.getKey(), ctx.userId())) continue;
                found.computeIfAbsent(e.getKey(), n -> new Found(n, new ArrayList<>(), e.getValue()))
                        .what().add(LAST_CALL_FAILED);
            }
        }
        // In the order the skills first ran.
        var ordered = new LinkedHashMap<String, Found>();
        for (String name : callsOf.keySet()) {
            if (found.containsKey(name)) ordered.put(name, found.get(name));
        }
        return ordered;
    }

    /**
     * {@code problems} as the model wrote it, read strictly: a list of objects, each with a
     * {@code tool} that is a skill's name and a {@code what} with words in it; a JSON text of one,
     * as the text protocol writes it; a single object as a list of one. Anything else -- a sentence,
     * a number, an entry with no tool or no words -- is not a problem anyone can repair: logged, and
     * left out. Nothing is guessed.
     */
    static List<Reported> parseProblems(Object raw) {
        if (raw == null) return List.of();
        Object value = raw;
        if (value instanceof String text) {
            if (text.isBlank()) return List.of();
            try {
                value = TextCalls.MAPPER.readValue(text.strip(), Object.class);
            } catch (JsonProcessingException notJson) {
                log.warn("problems was not read: text that is not JSON ({} chars).", text.length());
                return List.of();
            }
        }
        if (value instanceof Map<?, ?> one) value = List.of(one);
        if (!(value instanceof List<?> items)) {
            log.warn("problems was not read: a {} where a list was asked for.", value.getClass().getSimpleName());
            return List.of();
        }
        var out = new ArrayList<Reported>();
        for (Object item : items) {
            if (item instanceof Map<?, ?> entry
                    && entry.get("tool") instanceof String tool && SkillManager.isSkillName(tool)
                    && entry.get("what") instanceof String what && !what.isBlank()) {
                out.add(new Reported(tool, what.strip()));
            } else {
                log.warn("An entry of problems was not read: it is no {tool: a skill's name, what: a sentence}.");
            }
        }
        return out;
    }

    /**
     * A task that completed used each of these skills with no problem: its last call worked and it
     * was not reported. A repair of it that changed nothing, and the owner told it was left to him,
     * no longer stand in the way of the next repair -- the problem they were about went away.
     */
    private void cleared(AgentContext ctx, Map<String, Found> found) {
        var last = new LinkedHashMap<String, Artifact>();
        for (Artifact a : ctx.artifacts()) last.put(a.tool(), a);
        for (var e : last.entrySet()) {
            if (found.containsKey(e.getKey()) || !e.getValue().succeeded() || !isOwnSkill(e.getKey(), ctx.userId())) {
                continue;
            }
            // This account's repairs only: another's record is never touched by this one's runs.
            jdbc.update("UPDATE skill_repairs SET cleared_at = datetime('now') WHERE skill_name = ? AND user_id = ? "
                    + "AND cleared_at IS NULL AND outcome IN (?, ?)", e.getKey(), ctx.userId(), UNCHANGED, GAVE_UP);
        }
    }

    // ───────────────────────── starting a repair ─────────────────────────

    /**
     * What a problem of one skill becomes, decided and recorded under one lock: added to the repair
     * under way; or -- the last repair changed nothing, nothing has changed the code since, and no
     * run since went well -- no repair, and the owner told, once; or a repair started. Returns the
     * line the triggering task's ending gets.
     */
    private String take(AgentContext ctx, Found f) {
        String skill = f.skill();
        String problem = String.join(" ", f.what());
        String tellOwner = null;
        String line;
        synchronized (lock) {
            String code = codeHash(skill);
            // One repair of a skill at a time, whoever's: a skill's code is global. Another account's
            // repair under way is not this account's record, and nothing of it is said or added here.
            Map<String, Object> running = row("WHERE skill_name = ? AND outcome = ? ORDER BY id DESC LIMIT 1",
                    skill, RUNNING);
            if (running != null && !ctx.userId().equals(running.get("user_id"))) {
                log.info("Task {}: '{}' is being repaired already, for another account; nothing starts.",
                        ctx.taskId(), skill);
                event(ctx.userId(), ctx.taskId(), "busy", skill, problem, null, ctx.taskId());
                return "_🔧 " + skill + " is being repaired already, for another account: no repair starts for "
                        + "this problem._";
            }
            if (running != null) {
                addProblem(id(running), ctx, problem);
                log.info("Task {}: '{}' is being repaired already (task {}); its problem was added to that repair's "
                        + "record.", ctx.taskId(), skill, running.get("repair_task_id"));
                event(ctx.userId(), ctx.taskId(), "added", skill, problem, str(running, "repair_task_id"), ctx.taskId());
                return "_🔧 " + skill + " is being repaired already (task " + running.get("repair_task_id")
                        + "): this problem was added to that repair's record; the repair under way does not see "
                        + "it._";
            }
            // What this account's own repairs of the skill did: never another account's report.
            Map<String, Object> last = row("WHERE skill_name = ? AND user_id = ? AND outcome NOT IN (?, ?, ?, ?, ?) "
                    + "ORDER BY id DESC LIMIT 1", skill, ctx.userId(), RUNNING, INTERRUPTED, NOT_STARTED, STOPPED,
                    NOT_FINISHED);
            boolean noProgress = last != null && last.get("cleared_at") == null
                    && Objects.equals(code, last.get("code_after"))
                    && (UNCHANGED.equals(last.get("outcome")) || GAVE_UP.equals(last.get("outcome")));
            if (noProgress && GAVE_UP.equals(last.get("outcome"))) {
                addProblem(id(last), ctx, problem);
                log.info("Task {}: '{}' showed its problem again; self-repair left it to the owner before, so nothing "
                        + "starts.", ctx.taskId(), skill);
                event(ctx.userId(), ctx.taskId(), "left", skill, problem, null, ctx.taskId());
                return "_🔧 Self-repair could not fix " + skill + " before, and you were told: it is left to you._";
            }
            if (noProgress) {
                jdbc.update("""
                        INSERT INTO skill_repairs (user_id, skill_name, trigger_task_id, trigger_message, trigger_at,
                            problem, side_effects, code_before, code_after, outcome, report, previous_id, ended_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, datetime('now'))
                        """, ctx.userId(), skill, ctx.taskId(), ctx.originalMessage(), startedAt(ctx), problem,
                        sideEffects(skill) ? 1 : 0, code, code, GAVE_UP, last.get("report"), id(last));
                log.warn("Task {}: '{}' showed its problem again after a repair that changed nothing (task {}): no "
                        + "repair starts, the owner is told.", ctx.taskId(), skill, last.get("repair_task_id"));
                event(ctx.userId(), ctx.taskId(), "left", skill, problem, str(last, "repair_task_id"), ctx.taskId());
                tellOwner = "Self-repair could not fix " + skill + ". After " + trigger(ctx.originalMessage(),
                        startedAt(ctx)) + " the problem is back: " + problem + "\n\nThe last repair (task "
                        + last.get("repair_task_id") + ") changed nothing, so self-repair does not try again: it is "
                        + "left to you. That repair's report:\n\n" + last.get("report");
                line = "_🔧 Self-repair is not tried again for " + skill + ": its last repair (task "
                        + last.get("repair_task_id") + ") changed nothing. You were told; it is left to you._";
            } else {
                Map<String, Object> previous = last != null && !Objects.equals(last.get("code_before"), last.get("code_after"))
                        && List.of(FIXED, UNVERIFIED, NOT_FIXED).contains(String.valueOf(last.get("outcome")))
                        ? last : null;
                line = start(ctx, f, problem, code, previous);
            }
        }
        if (tellOwner != null) {
            tell(ctx.userId(), ctx.taskId(), "🔧 Self-repair of " + skill + " left to you", tellOwner, null);
        }
        return line;
    }

    /** Record a repair, mark both tasks, and queue it. Held under the lock. */
    private String start(AgentContext ctx, Found f, String problem, String code, Map<String, Object> previous) {
        String skill = f.skill();
        Tool tool = tools.find(skill).orElse(null);
        boolean sideEffects = tool != null && tool.hasSideEffects();
        boolean debug = tool != null && tool.inputSchema() != null && tool.inputSchema().containsKey("debug");
        String taskId = UUID.randomUUID().toString().substring(0, 8);
        String goal = goal(skill, ctx.originalMessage(), Instant.ofEpochMilli(ctx.startTimeMs()), zone, ctx.taskId(),
                f.what(), f.calls(), sideEffects, debug, previous);
        // Stored and sent: no vault value goes either way, as nothing stored for display does.
        goal = Redactor.scrubVault(goal, ctx.secretValues()).text();
        long id = insertReturningId("""
                INSERT INTO skill_repairs (user_id, skill_name, trigger_task_id, trigger_message, trigger_at,
                    repair_task_id, problem, goal, side_effects, code_before, outcome, previous_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, ctx.userId(), skill, ctx.taskId(), ctx.originalMessage(), startedAt(ctx), taskId, problem, goal,
                sideEffects ? 1 : 0, code, RUNNING, previous == null ? null : id(previous));
        boolean localOnly = ctx.options() != null && ctx.options().localOnly();
        var job = new Job(id, skill, taskId, sideEffects, localOnly);
        log.info("Task {}: '{}' did not do its whole job -- self-repair {} started as task {}.", ctx.taskId(), skill,
                id, taskId);
        event(ctx.userId(), ctx.taskId(), "started", skill, problem, taskId, ctx.taskId());
        event(ctx.userId(), taskId, "repairing", skill, problem, taskId, ctx.taskId());
        String userId = ctx.userId();
        queue.submitRepair(userId, goal, job).whenComplete((result, failure) -> settle(job, userId, result, failure));
        Map<String, Object> now = row("WHERE id = ?", id);
        if (now != null && NOT_STARTED.equals(now.get("outcome"))) {
            return "_🔧 Self-repair of " + skill + " could not start: " + now.get("report") + "_";
        }
        return "_🔧 Self-repair started for " + skill + ": " + problem + " It runs in the background as task "
                + taskId + "._";
    }

    /**
     * The goal of a repair, written by code: what ran and when, what went wrong, the evidence --
     * each call of the skill in the task, its output whole when it is PUBLIC and its descriptor
     * when it is PRIVATE, which is all the cloud was ever shown of it -- the previous repair's
     * report when it changed the code and the problem came back, and what to do, which turns on
     * whether the skill has side effects.
     */
    static String goal(String skill, String message, Instant at, ZoneId zone, String triggerTaskId, List<String> what,
                       List<Artifact> calls, boolean sideEffects, boolean debug, Map<String, Object> previous) {
        var sb = new StringBuilder();
        sb.append("Repair the skill '").append(skill).append("'. This is a self-repair: OwnClaw started it by itself, ")
          .append("nobody is waiting for it, and it changes that one skill and nothing else.\n\n");
        sb.append("What ran: the unattended task ").append(triggerTaskId).append(", started ")
          .append(WHEN.withZone(zone).format(at)).append(". Its request:\n");
        for (String l : (message == null ? "" : message).split("\\R", -1)) sb.append("> ").append(l).append('\n');
        sb.append("\nWhat went wrong with '").append(skill).append("':\n");
        for (String w : what) sb.append("- ").append(w).append('\n');
        sb.append("\nIts calls in that task, oldest first:\n");
        for (int i = 0; i < calls.size(); i++) {
            Artifact a = calls.get(i);
            sb.append("\nCall ").append(i + 1).append(" of ").append(calls.size()).append(": ");
            if (a.isPrivate()) {
                // What the cloud was shown of it then, and nothing more: without its handle, which
                // is that task's and means nothing in this one.
                String d = a.describe();
                if (d.startsWith(a.handle())) d = d.substring(a.handle().length()).strip();
                sb.append("its result is private and stays on this machine; what may be said of it: ").append(d)
                  .append('\n');
                continue;
            }
            sb.append(a.succeeded() ? "it reported success" : "it FAILED").append(", called with ")
              .append(json(a.written())).append(". Its ").append(a.succeeded() ? "output" : "error")
              .append(", whole:\n").append(a.output()).append('\n');
        }
        if (previous != null) {
            sb.append("\nThe previous self-repair of this skill (task ").append(previous.get("repair_task_id"))
              .append(") changed its code, and the problem came back. Its report:\n")
              .append(previous.get("report")).append('\n');
        }
        sb.append("\nWhat to do:\n");
        sb.append("1. Find the cause. Read the skill: skill_manage with action 'read' and name '").append(skill)
          .append("'.");
        if (!sideEffects && debug) {
            sb.append(" It has a 'debug' parameter: run it with debug set to true to see what it does.");
        }
        sb.append(" Probe the source it reads if that helps, with a tool that changes nothing.\n");
        sb.append("2. Fix it with skill_create under the same name, '").append(skill).append("', as a targeted ")
          .append("change: describe what it does and what to change, and keep its parameters, credentials, ")
          .append("requirements and system packages unless one of them is the cause.\n");
        if (sideEffects) {
            sb.append("3. Do not run it: its SKILL.yaml says has_side_effects: true, so a run would change ")
              .append("something. Check the new code by reading it instead.\n");
        } else {
            sb.append("3. Run '").append(skill).append("' again, with the arguments of the call above")
              .append(debug ? " and debug set to true" : "").append(", and check that the problem is gone. If ")
              .append("you are not given the skill to call yourself, run it through delegate, as an exact call ")
              .append("in 'calls'.\n");
        }
        sb.append("4. Call no tool that has side effects -- sending mail, writing to other systems: in this task ")
          .append("such calls are refused.\n");
        sb.append("5. End with respond: say in a few sentences what was wrong, what you changed, and whether the ")
          .append("check passed, and set 'check' to passed, failed or not_run.\n");
        return sb.toString();
    }

    // ───────────────────────── a repair ended ─────────────────────────

    /**
     * A repair task has ended: its outcome by code, its record closed, the owner told. Changed or
     * not is the code's hash; checked is a run of the skill after its last change, in a repair that
     * completed, whose last run worked and whose answer named no problem with it -- a run that
     * "worked" with a degraded output is exactly what started this, so the repair's own judgement
     * counts. A skill with side effects is never run, so its change is never checked.
     */
    private void finish(AgentContext ctx, AgentResult ended) {
        Job job = ctx.repair();
        Map<String, Object> row;
        String outcome;
        boolean sideEffects;
        synchronized (lock) {
            row = row("WHERE id = ?", job.id());
            if (row == null) return;
            String after = codeHash(job.skill());
            boolean changed = !Objects.equals(row.get("code_before"), after);
            sideEffects = job.sideEffects() || sideEffects(job.skill());
            Boolean check = changed && !sideEffects ? checked(ctx, ended, job.skill()) : null;
            // "Changed nothing" is a verdict only of a repair that finished: one stopped, cut short by
            // an outage, the watchdog or the window, or left waiting for an answer never really tried.
            TerminationReason how = ended.terminationReason();
            outcome = changed ? (check == null ? UNVERIFIED : check ? FIXED : NOT_FIXED)
                    : how == TerminationReason.COMPLETED ? UNCHANGED
                    : how == TerminationReason.CANCELLED ? STOPPED : NOT_FINISHED;
            jdbc.update("UPDATE skill_repairs SET code_after = ?, outcome = ?, report = ?, ended_at = datetime('now') "
                    + "WHERE id = ?", after, outcome, ended.response(), job.id());
            event(ctx.userId(), ctx.taskId(), outcome, job.skill(), str(row, "problem"), ctx.taskId(),
                    str(row, "trigger_task_id"));
        }
        log.info("Self-repair {} of '{}' (task {}) ended: {}.", job.id(), job.skill(), ctx.taskId(), outcome);
        String said = said(outcome, sideEffects, ended);
        String head = "After " + trigger(str(row, "trigger_message"), str(row, "trigger_at")) + " it did not do its "
                + "whole job: " + row.get("problem") + "\n\n" + said + "\n\n";
        String rerun = FIXED.equals(outcome) || UNVERIFIED.equals(outcome)
                ? "\n\nIts next run uses the changed skill. To run that task now, send:\n/bg "
                        + str(row, "trigger_message")
                : "";
        tell(ctx.userId(), ctx.taskId(), "🔧 Self-repair of " + job.skill(), head + ended.response() + rerun,
                ended.ownerText() == null ? null : head + ended.ownerText() + rerun);
    }

    /** What an outcome is, for the owner. */
    private static String said(String outcome, boolean sideEffects, AgentResult ended) {
        boolean completed = ended.terminationReason() == TerminationReason.COMPLETED;
        String which = completed ? "What the repair said:" : "How the repair ended:";
        return switch (outcome) {
            case FIXED -> "Fixed and checked: it was run again, and the problem is gone. " + which;
            case UNVERIFIED -> (sideEffects
                    ? "Changed, but not run again to check it: it has side effects."
                    : completed ? "Changed, but the repair did not run it again to check the change."
                    : "Changed, but the repair did not finish, so the change was not checked.") + " " + which;
            case NOT_FIXED -> "Changed, but run again it still shows the problem. When the problem shows again, "
                    + "self-repair tries again with this report. " + which;
            case STOPPED -> "Stopped before it changed the skill. The next time the problem shows, a repair "
                    + "starts again. " + which;
            case NOT_FINISHED -> "The repair did not finish, and changed nothing. The next time the problem shows, "
                    + "a repair starts again. " + which;
            default -> "Not fixed: the repair did not change the skill. If the problem comes back, self-repair "
                    + "does not try again: it is left to you. " + which;
        };
    }

    /**
     * Whether the skill's change was checked and passed, in a repair that completed and ran the skill
     * after its last change: false when that run failed or the repair said the check failed; true
     * only when the run worked AND the repair said it passed -- a run that "worked" with a degraded
     * output is what started this, so its judgement is needed; null when nothing judged a run of
     * the changed skill.
     */
    static Boolean checked(AgentContext ctx, AgentResult ended, String skill) {
        if (ended.terminationReason() != TerminationReason.COMPLETED) return null;
        int from = ctx.changedAfter();
        if (from < 0) return null;
        List<Artifact> results = new ArrayList<>(ctx.artifacts());
        List<Artifact> runs = results.subList(Math.min(from, results.size()), results.size()).stream()
                .filter(a -> skill.equals(a.tool())).toList();
        if (runs.isEmpty()) return null;
        String check = ctx.reportedCheck() instanceof String c ? c.strip() : null;
        if (!runs.getLast().succeeded() || "failed".equals(check)) return false;
        return "passed".equals(check) ? Boolean.TRUE : null;
    }

    /**
     * The repair's task has run, or will not: one that ended was closed by its ending
     * ({@link #finish}); one still open never ran -- the queue was full, it was dropped by a Stop
     * while it waited, it failed before its loop began -- and is recorded so, and the owner told.
     */
    private void settle(Job job, String userId, AgentResult result, Throwable failure) {
        String why;
        synchronized (lock) {
            Map<String, Object> row = row("WHERE id = ?", job.id());
            if (row == null || !RUNNING.equals(row.get("outcome"))) return;
            why = failure != null ? String.valueOf(failure.getMessage())
                    : result == null ? "it gave no result" : result.response();
            jdbc.update("UPDATE skill_repairs SET outcome = ?, report = ?, ended_at = datetime('now') WHERE id = ?",
                    NOT_STARTED, why, job.id());
        }
        log.warn("Self-repair {} of '{}' did not start: {}", job.id(), job.skill(), why);
        tell(userId, job.taskId(), "🔧 Self-repair of " + job.skill() + " did not start",
                why + "\n\nThe next time the problem shows, a repair is started again.", null);
    }

    // ───────────────────────── a repair's scope ─────────────────────────

    /**
     * Why a repair task may not take this action, or null when it may: the one rule, for the
     * loop's steps and for every call of a delegation, the model's and the cloud's exact ones
     * alike. A repair reads, runs what changes nothing, and changes its one skill: skill_create
     * only under its name; skill_manage, memory_manage and schedule_manage only to look; and no
     * tool with side effects -- nor the skill under repair when it had them as the repair began,
     * whatever its new SKILL.yaml says.
     *
     * @param registered the registry's tool of that name, or null for a special action or a name
     *                   no tool has (the loop says so itself)
     */
    static String outOfScope(Job job, String name, Map<String, Object> params, Tool registered) {
        if (job == null || name == null) return null;
        String action = params == null || params.get("action") == null ? "" : String.valueOf(params.get("action"));
        String repairs = "this task repairs the skill '" + job.skill() + "' and ";
        switch (name) {
            case AgentAction.RESPOND, AgentAction.ASK_USER, AgentAction.DELEGATE, AgentAction.FIND_TOOLS,
                 AgentAction.READ_RESULT, AgentAction.CREDENTIAL_MANAGE -> {
                return null;
            }
            case AgentAction.SKILL_CREATE -> {
                Object skill = params == null ? null : params.get("name");
                return skill != null && job.skill().equals(String.valueOf(skill)) ? null
                        : repairs + "changes no other skill: skill_create only under the name '" + job.skill() + "'.";
            }
            case AgentAction.SKILL_MANAGE -> {
                return List.of("read", "list", "analyze").contains(action) ? null
                        : repairs + "deletes nothing: skill_manage only to read, list or analyze.";
            }
            case AgentAction.MEMORY_MANAGE -> {
                return List.of("list", "recall").contains(action) ? null
                        : repairs + "keeps or forgets no fact: memory_manage only to list or recall.";
            }
            case AgentAction.SCHEDULE_MANAGE -> {
                return "list".equals(action) ? null : repairs + "changes nothing scheduled: schedule_manage only to list.";
            }
            default -> { }
        }
        if (registered == null) return null;
        boolean changes = registered.hasSideEffects() || registered.name().equals(job.skill()) && job.sideEffects();
        return changes ? "'" + name + "' has side effects, and " + repairs + "calls no tool that changes anything -- "
                + "sending mail, writing to other systems." : null;
    }

    /**
     * A repair's skill_create of its skill, with what the skill declares and the model left out kept
     * -- its credentials, its network, its timeout -- and its side effects never taken away: the
     * refusal of a side-effecting run ({@link #outOfScope}) is decided by that flag.
     */
    static Map<String, Object> keepDeclarations(Job job, Map<String, Object> params, Tool current) {
        var out = new LinkedHashMap<String, Object>(params == null ? Map.of() : params);
        if (current == null || !job.skill().equals(String.valueOf(out.get("name")))) return out;
        if (job.sideEffects() || current.hasSideEffects()) out.put("has_side_effects", true);
        if (!out.containsKey("requires_network")) out.put("requires_network", current.requiresNetwork());
        if (!out.containsKey("has_side_effects")) out.put("has_side_effects", current.hasSideEffects());
        if (!out.containsKey("credentials") && !current.requiredCredentials().isEmpty()) {
            out.put("credentials", String.join(",", current.requiredCredentials()));
        }
        if (!out.containsKey("timeout")) out.put("timeout", current.estimatedMaxDurationSeconds());
        return out;
    }

    // ───────────────────────── helpers ─────────────────────────

    /** The owner is told, as unattended results are: the chat of scheduled results, and Telegram. */
    private void tell(String userId, String taskId, String header, String text, String ownerText) {
        try {
            delivery.deliver(userId, () -> conversations.scheduledSession(userId), header, text, taskId, ownerText);
        } catch (RuntimeException e) {
            log.error("Self-repair could not tell user {}: {}", userId, e.getMessage());
        }
    }

    /** A row on a task's record, for its page: what self-repair did about it. */
    private void event(String userId, String taskId, String state, String skill, String problem, String repairTaskId,
                       String triggerTaskId) {
        try {
            var d = new LinkedHashMap<String, Object>();
            d.put("state", state);
            d.put("skill", skill);
            d.put("problem", problem);
            d.put("repairTaskId", repairTaskId);
            d.put("triggerTaskId", triggerTaskId);
            events.log(userId, taskId, EVENT, "info", "self-repair " + state + ": " + skill,
                    TextCalls.MAPPER.writeValueAsString(d), 0);
        } catch (Exception e) {
            log.warn("Self-repair could not record '{}' on task {}: {}", state, taskId, e.getMessage());
        }
    }

    private void addProblem(long id, AgentContext ctx, String problem) {
        jdbc.update("UPDATE skill_repairs SET added_problems = COALESCE(added_problems || char(10), '') || ? "
                + "WHERE id = ?", "task " + ctx.taskId() + ": " + problem, id);
    }

    private boolean sideEffects(String skill) {
        return tools.find(skill).map(Tool::hasSideEffects).orElse(false);
    }

    /** The sha256 of the skill's code, or null when it has none. */
    private String codeHash(String skill) {
        return sha256(skills.readSkillCode(skill));
    }

    static String sha256(String text) {
        if (text == null) return null;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String startedAt(AgentContext ctx) {
        return Instant.ofEpochMilli(ctx.startTimeMs()).toString();
    }

    /** "'Denní polední menu' at 06:30": the task's first line, and when it started. */
    private String trigger(String message, String at) {
        String name = "";
        for (String l : (message == null ? "" : message).split("\\R")) {
            if (!l.isBlank()) {
                name = l.strip();
                break;
            }
        }
        String time = "";
        try {
            if (at != null) time = " at " + AT.withZone(zone).format(Instant.parse(at));
        } catch (RuntimeException unreadable) {
            // said without a time
        }
        return "'" + name + "'" + time;
    }

    private Map<String, Object> row(String where, Object... args) {
        var rows = jdbc.queryForList("SELECT * FROM skill_repairs " + where, args);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static long id(Map<String, Object> row) {
        return ((Number) row.get("id")).longValue();
    }

    private static String str(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v == null ? null : v.toString();
    }

    private static String json(Map<String, Object> args) {
        try {
            return TextCalls.MAPPER.writeValueAsString(args);
        } catch (JsonProcessingException e) {
            return String.valueOf(args);
        }
    }

    /** Insert a row and return its id, from the same statement (ScheduledTaskService#insertReturningId). */
    private long insertReturningId(String sql, Object... args) {
        var keys = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(sql, java.sql.Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            return ps;
        }, keys);
        Number key = keys.getKey();
        if (key == null) throw new IllegalStateException("Insert returned no generated key");
        return key.longValue();
    }
}
