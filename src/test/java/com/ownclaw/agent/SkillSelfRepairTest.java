package com.ownclaw.agent;

import com.ownclaw.agent.tools.Tool;
import com.ownclaw.agent.tools.ToolExecutionContext;
import com.ownclaw.agent.tools.ToolParam;
import com.ownclaw.agent.tools.ToolResult;
import com.ownclaw.core.ResultDelivery;
import com.ownclaw.core.TaskQueue;
import com.ownclaw.llm.ToolSpec;
import com.ownclaw.observability.TaskTraceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static com.ownclaw.agent.LoopRig.call;
import static com.ownclaw.agent.LoopRig.respond;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Self-repair end to end, through the real loop, the real task queue and the migrated schema: an
 * unattended task that shows a skill did not do its whole job starts one repair of it, which runs
 * as a background task kept to that skill, is recorded with the code's hash before and after, and
 * is told to the owner in the chat of scheduled results -- and none starts where none may.
 */
class SkillSelfRepairTest {

    static final String MENU = "daily_menu_fetcher";
    static final String TASK = "Daily lunch menu\nUsing daily_menu_fetcher, collect today's menus and email them to me.";
    static final String DEGRADED = "{\"success\": true, \"menus\": {\"Cafe One\": \"Soup, schnitzel\", "
            + "\"Cafe Two\": \"WARNING: OCR failed -- the picture held no text (HTTP 455 Blocked, fell back to logo.png)\"}}";
    static final String GOOD = "{\"success\": true, \"menus\": {\"Cafe One\": \"Soup, schnitzel\", \"Cafe Two\": \"Goulash\"}}";
    static final String PROBLEM = "The menu of Cafe Two was not read: OCR failed on a fallback picture.";
    /** Degraded another way: what a change that did something but not enough gives. */
    static final String DEGRADED_OTHERWISE = "{\"success\": true, \"menus\": {\"Cafe One\": \"Soup, schnitzel\", "
            + "\"Cafe Two\": \"WARNING: OCR failed -- HTTP 403 with browser headers\"}}";

    /** A fake skill: records its calls, answers with {@code body}. */
    /** @param sideEffects what its SKILL.yaml says of its side effects: true, false, or null for nothing */
    static Tool skill(String name, Boolean sideEffects, List<String> credentials, boolean debug,
                      List<Map<String, Object>> calls, Function<Map<String, Object>, ToolResult> body) {
        return new Tool() {
            public String name() { return name; }
            public String description() { return "test skill " + name; }
            public Map<String, ToolParam> inputSchema() {
                return debug ? Map.of("debug", ToolParam.optional("boolean", "say what it does")) : Map.of();
            }
            public List<String> requiredCredentials() { return credentials; }
            public boolean hasSideEffects() { return Boolean.TRUE.equals(sideEffects); }
            public Boolean declaredSideEffects() { return sideEffects; }
            public boolean requiresNetwork() { return true; }
            public ToolResult execute(Map<String, Object> p, ToolExecutionContext c) {
                calls.add(Map.copyOf(p));
                return body.apply(p);
            }
        };
    }

    /** The rig, a self-repair wired into its loop, and the skills' code on "disk". */
    static final class Fix {
        final LoopRig rig;
        final SkillSelfRepair repair;
        final Map<String, String> code = new ConcurrentHashMap<>();
        /** What the menu skill answers; a repair's skill_create sets it to {@code fixedOutput}. */
        final AtomicReference<String> menuOutput = new AtomicReference<>(DEGRADED);
        /** Set, the menu skill fails outright. */
        final java.util.concurrent.atomic.AtomicBoolean menuFails = new java.util.concurrent.atomic.AtomicBoolean();
        final List<Map<String, Object>> menuCalls = new CopyOnWriteArrayList<>();
        final List<Map<String, Object>> mailCalls = new CopyOnWriteArrayList<>();
        final List<Map<String, Object>> created = new CopyOnWriteArrayList<>();
        volatile String fixedOutput = GOOD;

        Fix(Path tmp, Tool... more) throws Exception {
            this(tmp, null, more);
        }

        Fix(Path tmp, TaskQueue queue, Tool... more) throws Exception {
            this(tmp, queue, registry -> null, more);
        }

        Fix(Path tmp, TaskQueue queue, Function<com.ownclaw.agent.tools.ToolRegistry, CapabilityResolver> resolver,
            Tool... more) throws Exception {
            this(tmp, queue, resolver, new StopWithoutLocalModelTest.Down(), more);
        }

        /** @param local the local model: down, unless a test scripts one */
        Fix(Path tmp, TaskQueue queue, Function<com.ownclaw.agent.tools.ToolRegistry, CapabilityResolver> resolver,
            com.ownclaw.llm.LlmProvider local, Tool... more) throws Exception {
            var tools = new ArrayList<Tool>();
            tools.add(skill(MENU, false, List.of(), true, menuCalls, p -> menuFails.get()
                    ? ToolResult.failure("Traceback: ConnectionResetError") : ToolResult.success(menuOutput.get())));
            tools.add(skill("smtp_send_email", true, List.of(), false, mailCalls, p -> ToolResult.success("{\"ok\": true}")));
            tools.addAll(List.of(more));
            rig = new LoopRig(tmp, tools, 600, local,
                    (registry, config, router) -> new ThinkingEngine(registry, config, router), resolver);
            for (Tool t : tools) code.put(t.name(), "def run(params):\n    return {}  # " + t.name() + " v1\n");
            rig.skills.source = code::get;
            rig.skills.files = name -> code.containsKey(name)
                    ? "## Skill: " + name + "\n\n### skill.py\n```python\n" + code.get(name) + "```\n"
                    : "ERROR: '" + name + "' is not a skill or does not exist.";
            // A skill_create writes new code, and the menu skill then reads the second restaurant.
            rig.skills.create = p -> {
                created.add(Map.copyOf(p));
                code.put(String.valueOf(p.get("name")), String.valueOf(p.get("code")));
                if (MENU.equals(p.get("name"))) menuOutput.set(fixedOutput);
                return "Skill '" + p.get("name") + "' updated and registered.";
            };
            repair = new SkillSelfRepair(rig.jdbc, rig.config, rig.skills, rig.registry,
                    queue != null ? queue : rig.queue, new ResultDelivery(rig.chat, rig.emitter), rig.chat,
                    rig.events, ZoneId.of("UTC"));
            rig.loop.setSelfRepair(repair);
        }

        /** An unattended task, as the scheduler's queue runs one. */
        AgentResult unattended(String message) {
            return rig.loop.executeFull("u1", message, true);
        }

        /**
         * The scheduled task's steps: the menu skill, then an answer naming its problem. Scripted
         * before its repair's, which runs after it on the queue and reads the same script.
         */
        void scriptMenu(boolean reportIt) {
            rig.cloud.think.add(call(MENU, Map.of()));
            rig.cloud.think.add(reportIt ? problems("Here are today's menus; Cafe Two's could not be read.",
                    List.of(Map.of("tool", MENU, "what", PROBLEM))) : respond("Here are today's menus."));
        }

        /** The scheduled task's run, its steps scripted here. */
        AgentResult menuRun(boolean reportIt) {
            scriptMenu(reportIt);
            return unattended(TASK);
        }

        List<Map<String, Object>> repairs() {
            return rig.jdbc.queryForList("SELECT * FROM skill_repairs ORDER BY id");
        }

        Map<String, Object> repairRow(int i) {
            return repairs().get(i);
        }

        /** What the owner was told in the chat of scheduled results, oldest first. */
        List<String> told() {
            return rig.jdbc.queryForList("SELECT c.content FROM conversations c JOIN chat_sessions s "
                    + "ON s.id = c.session_id WHERE s.kind = 'scheduled' AND c.role = 'assistant' ORDER BY c.rowid",
                    String.class);
        }

        /** The repair's record once its task has ended. */
        Map<String, Object> ended(long id) throws InterruptedException {
            long until = System.currentTimeMillis() + 30_000;
            while (System.currentTimeMillis() < until) {
                var row = rig.jdbc.queryForMap("SELECT * FROM skill_repairs WHERE id = ?", id);
                if (!"running".equals(row.get("outcome"))) {
                    // The owner is told just after the record is closed.
                    Thread.sleep(200);
                    return rig.jdbc.queryForMap("SELECT * FROM skill_repairs WHERE id = ?", id);
                }
                Thread.sleep(20);
            }
            throw new AssertionError("repair " + id + " did not end");
        }

        long lastId() {
            return ((Number) rig.jdbc.queryForObject("SELECT MAX(id) FROM skill_repairs", Number.class)).longValue();
        }
    }

    /** The model answers, with problems. */
    static LoopRig.Reply problems(String message, Object problems) {
        var args = new java.util.LinkedHashMap<String, Object>();
        args.put("message", message);
        args.put(SkillSelfRepair.PROBLEMS, problems);
        return call(AgentAction.RESPOND, args);
    }

    /** A repair of {@code skill}, which declared {@code sideEffects}, as its task is handed one. */
    static SkillSelfRepair.Job job(String skill, Boolean sideEffects) {
        return new SkillSelfRepair.Job(999, skill, "abcd1234",
                new SkillSelfRepair.Declared(sideEffects, true, List.of(), List.of(), null), false, List.of());
    }

    /** A repair's answer, with how its check went. */
    static LoopRig.Reply verdict(String message, String check) {
        var args = new java.util.LinkedHashMap<String, Object>();
        args.put("message", message);
        args.put(SkillSelfRepair.CHECK, check);
        return call(AgentAction.RESPOND, args);
    }

    /** A repair that reads the skill, fixes it, runs it again and says so. */
    static void scriptFixAndCheck(LoopRig rig, Map<String, Object> runArgs, LoopRig.Reply last) {
        rig.cloud.think.add(call(AgentAction.SKILL_MANAGE, Map.of("action", "read", "name", MENU)));
        rig.cloud.think.add(call(AgentAction.SKILL_CREATE, Map.of("name", MENU, "description",
                "Fetch the menus; when a site answers 455, retry as a browser.", "parameters", "{}")));
        rig.cloud.codegen.add(SkillCodegenTest.finished(SkillCodegenTest.module("# v2")));
        if (runArgs != null) rig.cloud.think.add(call(MENU, runArgs));
        if (last != null) rig.cloud.think.add(last);
    }

    private Fix fix;

    @AfterEach
    void stopQueue() {
        if (fix != null) fix.rig.queue.stop();
    }

    // ───────────────────────── starting one ─────────────────────────

    @Test
    @DisplayName("an unattended run whose skill returns success with a degraded output, and whose answer names it in problems, queues exactly one repair with the skill, the problem and the public output in its goal")
    void degradedOutputStartsOneRepair(@TempDir Path tmp) throws Exception {
        fix = new Fix(tmp);
        AgentResult r = fix.menuRun(true);

        assertTrue(r.success(), r.response());
        var rows = fix.repairs();
        assertEquals(1, rows.size(), rows.toString());
        var row = rows.get(0);
        assertEquals("running", row.get("outcome"));
        assertEquals(MENU, row.get("skill_name"));
        assertEquals(r.taskId(), row.get("trigger_task_id"));
        assertEquals(TASK, row.get("trigger_message"));
        assertEquals(PROBLEM, row.get("problem"));
        assertEquals(SkillSelfRepair.sha256(fix.code.get(MENU)), row.get("code_before"));
        assertEquals(1, fix.rig.queue.getQueueSize(), "the repair waits in the queue, once");
        String goal = (String) row.get("goal");
        assertTrue(goal.contains("Repair the skill '" + MENU + "'"), goal);
        // What ran is in the goal; what it produced is handed over as data, by handle, never pasted in.
        assertFalse(goal.contains(DEGRADED), goal);
        assertFalse(goal.contains(PROBLEM), goal);
        assertTrue(goal.contains("- {{1}}: what went wrong with '" + MENU + "'"), goal);
        assertTrue(goal.contains("- {{2}}: call 1 of 1 of '" + MENU + "' in that task, which reported success: its output."), goal);
        String evidence = (String) row.get("evidence");
        assertTrue(evidence.contains(PROBLEM), evidence);
        assertTrue(evidence.contains(DEGRADED.replace("\"", "\\\"")), "the public output, whole: " + evidence);
        assertTrue(goal.contains("> Daily lunch menu"), "what ran: " + goal);
        assertTrue(goal.contains(r.taskId()), goal);
        assertTrue(goal.contains("debug set to true"), "the skill has a debug option: " + goal);
        assertTrue(goal.contains("skill_create under the same name"), goal);
        // The run's own ending says a repair started, for which skill, as which task.
        assertTrue(r.response().contains("Self-repair started for " + MENU), r.response());
        assertTrue(r.response().contains("task " + row.get("repair_task_id")), r.response());
        // And its page.
        var trace = new TaskTraceService(fix.rig.events).trace("u1", r.taskId()).orElseThrow();
        @SuppressWarnings("unchecked")
        var shown = (List<Map<String, Object>>) trace.get("selfRepair");
        assertEquals(1, shown.size(), shown.toString());
        assertEquals("started", shown.get(0).get("state"));
        assertEquals(MENU, shown.get(0).get("skill"));
        assertEquals(row.get("repair_task_id"), shown.get(0).get("repairTaskId"));
    }

    @Test
    @DisplayName("a private result is in the goal by its descriptor only, and a problem sentence that repeats it is withheld")
    void privateResultIsDescribedOnly(@TempDir Path tmp) throws Exception {
        String body = "Dear owner, your statement for account 4000-1234 shows a balance of 48,213.07 and three "
                + "pending transfers to the landlord.";
        var reads = new CopyOnWriteArrayList<Map<String, Object>>();
        fix = new Fix(tmp, skill("mail_digest", false, List.of("IMAP_USER", "IMAP_PASS"), false, reads,
                p -> ToolResult.success("{\"ok\": true, \"text\": \"" + body + "\"}")));
        fix.rig.cloud.think.add(call("mail_digest", Map.of()));
        fix.rig.cloud.think.add(problems("Done.", List.of(Map.of("tool", "mail_digest",
                "what", "It said " + body.substring(0, 70) + " and stopped there."))));
        fix.unattended("Using mail_digest, summarise my mail.");

        var row = fix.repairRow(0);
        String goal = (String) row.get("goal");
        assertFalse(goal.contains("statement for account"), "the private text went into the goal: " + goal);
        assertFalse(goal.contains("48,213.07"), goal);
        assertTrue(goal.contains("its result is private and stays on this machine"), goal);
        assertTrue(goal.contains("PRIVATE (personal source)"), "its descriptor: " + goal);
        String evidence = (String) row.get("evidence");
        assertTrue(evidence.contains(SkillSelfRepair.WITHHELD), evidence);
        assertFalse(evidence.contains("statement for account"), evidence);
        assertEquals(SkillSelfRepair.WITHHELD, row.get("problem"));
    }

    @Test
    @DisplayName("a generated skill whose call failed and was never followed by one that worked is a problem by code: its error is the evidence")
    void aFailedCallIsAProblem(@TempDir Path tmp) throws Exception {
        var calls = new CopyOnWriteArrayList<Map<String, Object>>();
        fix = new Fix(tmp, skill("web_snapshot", false, List.of(), false, calls,
                p -> ToolResult.failure("HTTP 455 Blocked by the host's bot protection")));
        fix.rig.cloud.think.add(call("web_snapshot", Map.of("url", "https://example.com/menu")));
        fix.rig.cloud.think.add(respond("The page could not be fetched."));
        fix.unattended("Using web_snapshot, fetch https://example.com/menu");

        var row = fix.repairRow(0);
        assertEquals("web_snapshot", row.get("skill_name"));
        assertEquals(SkillSelfRepair.LAST_CALL_FAILED, row.get("problem"));
        String goal = (String) row.get("goal");
        assertTrue(goal.contains("- {{2}}: call 1 of 1 of 'web_snapshot' in that task, which FAILED: its error."), goal);
        String evidence = (String) row.get("evidence");
        assertTrue(evidence.contains("HTTP 455 Blocked by the host's bot protection"), evidence);
        assertTrue(evidence.contains("-- it FAILED; its error"), evidence);
    }

    @Test
    @DisplayName("a failed call followed by one of the same skill that worked is no problem")
    void aLaterSuccessIsNoProblem(@TempDir Path tmp) throws Exception {
        var calls = new CopyOnWriteArrayList<Map<String, Object>>();
        var first = new java.util.concurrent.atomic.AtomicBoolean(true);
        fix = new Fix(tmp, skill("web_snapshot", false, List.of(), false, calls,
                p -> first.getAndSet(false) ? ToolResult.failure("timed out") : ToolResult.success("<html>menu</html>")));
        fix.rig.cloud.think.add(call("web_snapshot", Map.of("url", "https://example.com/menu")));
        fix.rig.cloud.think.add(call("web_snapshot", Map.of("url", "https://example.com/menu", "retry", true)));
        fix.rig.cloud.think.add(respond("Fetched."));
        AgentResult r = fix.unattended("Using web_snapshot, fetch https://example.com/menu");

        assertEquals(2, calls.size());
        assertTrue(fix.repairs().isEmpty(), fix.repairs().toString());
        assertFalse(r.response().contains("Self-repair"), r.response());
    }

    @Test
    @DisplayName("a task the owner stopped starts no repair for a call that failed: the Stop can be what failed it")
    void aStoppedTaskStartsNone(@TempDir Path tmp) throws Exception {
        fix = new Fix(tmp, skill("web_snapshot", false, List.of(), false, new ArrayList<>(),
                p -> ToolResult.failure("interrupted")));
        fix.rig.cloud.think.add(call("web_snapshot", Map.of("url", "https://example.com/menu")));
        fix.rig.cloud.think.add(c -> {
            fix.rig.cancellation.requestAll("u1", "the owner pressed Stop");
            return call(AgentAction.SKILL_MANAGE, Map.of("action", "list")).answer(c);
        });
        AgentResult r = fix.unattended("Using web_snapshot, fetch https://example.com/menu");

        assertEquals(AgentResult.TerminationReason.CANCELLED, r.terminationReason(), r.response());
        assertTrue(fix.repairs().isEmpty(), fix.repairs().toString());
    }

    @Test
    @DisplayName("the repair of work that ran local only runs local only too: its evidence never reaches the cloud")
    void localOnlyWorkIsRepairedLocally(@TempDir Path tmp) throws Exception {
        var local = LocalModeTest.localModel();
        fix = new Fix(tmp, null, registry -> null, local);
        fix.rig.queue.start();
        local.think.add(call(MENU, Map.of()));
        local.think.add(problems("Cafe Two's menu could not be read.", List.of(Map.of("tool", MENU, "what", PROBLEM))));
        local.think.add(call(AgentAction.SKILL_MANAGE, Map.of("action", "read", "name", MENU)));
        local.think.add(respond("The site blocks it; nothing to change."));
        fix.rig.loop.executeFull("u1", TASK, true, null, List.of(), null, null,
                new com.ownclaw.conversation.ChatOptions("free", null));
        var row = fix.ended(fix.lastId());

        assertEquals("unchanged", row.get("outcome"));
        assertEquals(4, local.calls("think").size(), "the local model ran the task and its repair");
        assertTrue(fix.rig.cloud.calls.isEmpty(), "the cloud was sent: " + fix.rig.cloud.calls);
    }

    @Test
    @DisplayName("an attended chat starts no repair, whatever its answer says and whatever failed")
    void attendedChatStartsNone(@TempDir Path tmp) throws Exception {
        fix = new Fix(tmp, skill("web_snapshot", false, List.of(), false, new ArrayList<>(),
                p -> ToolResult.failure("HTTP 455 Blocked")));
        String session = fix.rig.chat.createSession("u1", "Lunch");
        fix.rig.cloud.think.add(call(MENU, Map.of()));
        fix.rig.cloud.think.add(call("web_snapshot", Map.of("url", "https://example.com")));
        fix.rig.cloud.think.add(problems("Cafe Two's menu could not be read.", List.of(Map.of("tool", MENU, "what", PROBLEM))));
        AgentResult r = fix.rig.turn(session, "What's for lunch today?");

        assertTrue(fix.repairs().isEmpty(), fix.repairs().toString());
        assertFalse(r.response().contains("Self-repair"), r.response());
    }

    @Test
    @DisplayName("an ops run, unattended or not, starts no repair")
    void opsRunStartsNone(@TempDir Path tmp) throws Exception {
        fix = new Fix(tmp);
        fix.rig.cloud.think.add(call(MENU, Map.of()));
        fix.rig.cloud.think.add(problems("Cafe Two's menu could not be read.", List.of(Map.of("tool", MENU, "what", PROBLEM))));
        fix.rig.loop.executeFull("u1", TASK, true, null, List.of(), TaskChat.Channel.OPS);

        assertTrue(fix.repairs().isEmpty(), fix.repairs().toString());
    }

    @Test
    @DisplayName("switched off, nothing: no record, no task, no line in the ending")
    void switchedOff(@TempDir Path tmp) throws Exception {
        fix = new Fix(tmp);
        fix.rig.config.getSelfRepair().setEnabled(false);
        AgentResult r = fix.menuRun(true);

        assertTrue(fix.repairs().isEmpty(), fix.repairs().toString());
        assertEquals(0, fix.rig.queue.getQueueSize());
        assertFalse(r.response().contains("Self-repair"), r.response());
    }

    @Test
    @DisplayName("a problem named of a tool that did not run, or that is no generated skill of the user, is ignored")
    void unknownToolsAreIgnored(@TempDir Path tmp) throws Exception {
        fix = new Fix(tmp);
        fix.rig.skills.changeable = (name, user) -> !MENU.equals(name);
        fix.rig.cloud.think.add(call(MENU, Map.of()));
        fix.rig.cloud.think.add(problems("Done.", List.of(
                Map.of("tool", MENU, "what", PROBLEM),                                   // ran, not changeable
                Map.of("tool", "smtp_send_email", "what", "It did not send."),          // did not run
                Map.of("tool", "no_such_skill", "what", "It does not exist."))));
        fix.unattended(TASK);

        assertTrue(fix.repairs().isEmpty(), fix.repairs().toString());
    }

    @Test
    @DisplayName("a second problem of a skill while its repair is under way is added to that repair: no second task")
    void oneRepairPerSkillAtATime(@TempDir Path tmp) throws Exception {
        fix = new Fix(tmp);
        fix.menuRun(true);
        AgentResult second = fix.menuRun(true);

        var rows = fix.repairs();
        assertEquals(1, rows.size(), rows.toString());
        assertEquals(1, fix.rig.queue.getQueueSize(), "one repair task, not two");
        assertTrue(String.valueOf(rows.get(0).get("added_problems")).contains("task " + second.taskId() + ": " + PROBLEM),
                String.valueOf(rows.get(0).get("added_problems")));
        assertTrue(second.response().contains(MENU + " is being repaired already"), second.response());
        assertTrue(second.response().contains("added to that repair's record; the repair under way does not see it"),
                second.response());
    }

    // ───────────────────────── a repair runs ─────────────────────────

    @Test
    @DisplayName("the repair runs as an unattended task, fixes the skill, runs it again and is told to the owner as fixed and checked")
    void fixedAndChecked(@TempDir Path tmp) throws Exception {
        fix = new Fix(tmp);
        fix.rig.queue.start();
        fix.scriptMenu(true);
        scriptFixAndCheck(fix.rig, Map.of("debug", true),
                verdict("It was blocked with HTTP 455 and fell back to a logo. It now asks as a browser; run "
                        + "again, both menus are read.", "passed"));
        fix.unattended(TASK);
        var row = fix.ended(fix.lastId());

        assertEquals("fixed", row.get("outcome"), String.valueOf(row.get("report")));
        assertNotEquals(row.get("code_before"), row.get("code_after"));
        assertEquals(SkillSelfRepair.sha256(fix.code.get(MENU)), row.get("code_after"));
        assertTrue(String.valueOf(row.get("report")).contains("asks as a browser"), String.valueOf(row.get("report")));
        assertNotNull(row.get("ended_at"));
        // It ran the skill again with debug on; it called nothing else.
        assertEquals(Map.of("debug", true), fix.menuCalls.get(fix.menuCalls.size() - 1));
        assertTrue(fix.mailCalls.isEmpty());
        // The repair's own task was unattended and is on record like any other.
        String repairTask = (String) row.get("repair_task_id");
        var trace = new TaskTraceService(fix.rig.events).trace("u1", repairTask).orElseThrow();
        assertNotNull(trace.get("outcome"), "the repair task's ending is recorded: " + trace);
        // The owner is told.
        var told = fix.told();
        String note = told.get(told.size() - 1);
        assertTrue(note.contains("🔧 Self-repair of " + MENU), note);
        assertTrue(note.contains("After 'Daily lunch menu' at "), note);
        assertTrue(note.contains("Fixed and checked"), note);
        assertTrue(note.contains("asks as a browser"), note);
        assertTrue(note.contains("/bg " + TASK), "how to run the task now: " + note);
        // The repair's own success is no problem, and its run starts nothing.
        assertEquals(1, fix.repairs().size());
    }

    @Test
    @DisplayName("checked through a delegation's exact call, as a cloud that is not given the skill runs it: fixed")
    void checkedThroughADelegation(@TempDir Path tmp) throws Exception {
        fix = new Fix(tmp);
        fix.rig.queue.start();
        fix.scriptMenu(true);
        scriptFixAndCheck(fix.rig, null, null);
        fix.rig.cloud.think.add(call(AgentAction.DELEGATE, Map.of("calls",
                List.of(Map.of("tool", MENU, "params", Map.of("debug", true))))));
        fix.rig.cloud.think.add(verdict("Blocked by 455; now asks as a browser; run again through a delegation, "
                + "both menus are read.", "passed"));
        fix.unattended(TASK);
        var row = fix.ended(fix.lastId());

        assertEquals("fixed", row.get("outcome"), String.valueOf(row.get("report")));
        assertEquals(Map.of("debug", true), fix.menuCalls.get(fix.menuCalls.size() - 1));
    }

    @Test
    @DisplayName("a run of the changed skill that worked is not 'fixed' unless the repair says its check passed: a degraded output can work")
    void aWorkingRunIsNotTheCheck(@TempDir Path tmp) throws Exception {
        fix = new Fix(tmp);
        fix.rig.queue.start();
        fix.scriptMenu(true);
        scriptFixAndCheck(fix.rig, Map.of("debug", true), respond("Changed the headers and ran it."));
        fix.unattended(TASK);
        var row = fix.ended(fix.lastId());
        assertEquals("unverified", row.get("outcome"));
        String note = fix.told().get(fix.told().size() - 1);
        assertTrue(note.contains("Changed, but not checked"), note);
    }

    @Test
    @DisplayName("changed, and run again it still shows the problem: not fixed, and the owner told so")
    void changedButStillFailing(@TempDir Path tmp) throws Exception {
        fix = new Fix(tmp);
        fix.fixedOutput = DEGRADED;
        fix.rig.queue.start();
        fix.scriptMenu(true);
        scriptFixAndCheck(fix.rig, Map.of("debug", true), verdict("I changed the headers, but it is still blocked.",
                "failed"));
        fix.unattended(TASK);
        var row = fix.ended(fix.lastId());

        assertEquals("not_fixed", row.get("outcome"));
        String note = fix.told().get(fix.told().size() - 1);
        assertTrue(note.contains("Changed, but run again it still shows the problem"), note);
        // The repair's own problems start no repair.
        assertEquals(1, fix.repairs().size(), fix.repairs().toString());
        assertEquals(0, fix.rig.queue.getQueueSize());
    }

    @Test
    @DisplayName("a repair's own failed call starts no repair either")
    void aRepairsOwnFailureStartsNone(@TempDir Path tmp) throws Exception {
        fix = new Fix(tmp);
        fix.rig.queue.start();
        // The repair runs the skill, which now fails outright, and ends without a change.
        fix.rig.cloud.think.add(call(MENU, Map.of()));
        fix.rig.cloud.think.add(problems("Cafe Two's could not be read.", List.of(Map.of("tool", MENU, "what", PROBLEM))));
        fix.rig.cloud.think.add(c -> {
            fix.menuFails.set(true);
            return call(MENU, Map.of("debug", true)).answer(c);
        });
        fix.rig.cloud.think.add(respond("It fails before I can see why."));
        fix.unattended(TASK);
        var row = fix.ended(fix.lastId());

        assertEquals("unchanged", row.get("outcome"));
        assertEquals(2, fix.menuCalls.size(), "the repair did run it: " + fix.menuCalls);
        assertEquals(1, fix.repairs().size(), fix.repairs().toString());
        assertEquals(0, fix.rig.queue.getQueueSize());
    }

    @Test
    @DisplayName("no progress: a repair that changed nothing, then the problem again -- no repair starts, the owner is told once, with its report")
    void noProgressIsLeftToTheOwner(@TempDir Path tmp) throws Exception {
        fix = new Fix(tmp);
        fix.rig.queue.start();
        fix.scriptMenu(true);
        fix.rig.cloud.think.add(call(AgentAction.SKILL_MANAGE, Map.of("action", "read", "name", MENU)));
        fix.rig.cloud.think.add(respond("The site blocks every client it does not know; nothing in the skill can "
                + "change that."));
        fix.unattended(TASK);
        var first = fix.ended(fix.lastId());
        assertEquals("unchanged", first.get("outcome"));
        assertEquals(first.get("code_before"), first.get("code_after"));
        String unchangedNote = fix.told().get(fix.told().size() - 1);
        assertTrue(unchangedNote.contains("Not fixed: the repair did not change the skill"), unchangedNote);

        // The next day, the problem again.
        int toldBefore = fix.told().size();
        AgentResult again = fix.menuRun(true);
        var rows = fix.repairs();
        assertEquals(2, rows.size(), rows.toString());
        var gaveUp = rows.get(1);
        assertEquals("gave_up", gaveUp.get("outcome"));
        assertNull(gaveUp.get("repair_task_id"), "no repair task");
        assertEquals(first.get("id"), gaveUp.get("previous_id"));
        assertEquals(0, fix.rig.queue.getQueueSize());
        assertTrue(again.response().contains("Self-repair is not tried again for " + MENU), again.response());
        var told = fix.told();
        assertEquals(toldBefore + 1, told.size(), told.toString());
        String note = told.get(told.size() - 1);
        assertTrue(note.contains("Self-repair could not fix " + MENU), note);
        assertTrue(note.contains("nothing in the skill can change that"), "the last repair's report: " + note);
        assertTrue(note.contains("left to you"), note);

        // And again: nothing starts, and he is not told a second time.
        fix.menuRun(true);
        assertEquals(2, fix.repairs().size());
        assertEquals(toldBefore + 1, fix.told().size(), fix.told().toString());
        assertTrue(String.valueOf(fix.repairRow(1).get("added_problems")).contains(PROBLEM));

        // A run with no problem clears it: the next problem starts a repair again.
        fix.menuOutput.set(GOOD);
        fix.menuRun(false);
        fix.menuOutput.set(DEGRADED);
        fix.scriptMenu(true);
        fix.rig.cloud.think.add(call(AgentAction.SKILL_MANAGE, Map.of("action", "read", "name", MENU)));
        fix.rig.cloud.think.add(respond("Blocked again; nothing to change."));
        fix.unattended(TASK);
        var third = fix.ended(fix.lastId());
        assertEquals(3, fix.repairs().size());
        assertNotNull(third.get("repair_task_id"));
    }

    @Test
    @DisplayName("progress: a repair that changed the code, then the problem again -- a new repair, given the last one's report")
    void progressStartsAnotherWithTheReport(@TempDir Path tmp) throws Exception {
        fix = new Fix(tmp);
        fix.fixedOutput = DEGRADED_OTHERWISE;   // the change did something: the output is not the same
        fix.rig.queue.start();
        // Changed, not run again: unverified.
        fix.scriptMenu(true);
        scriptFixAndCheck(fix.rig, null, respond("I added browser headers; I did not run it again."));
        fix.unattended(TASK);
        var first = fix.ended(fix.lastId());
        assertEquals("unverified", first.get("outcome"));
        String note = fix.told().get(fix.told().size() - 1);
        assertTrue(note.contains("Changed, but not checked: the repair did not run it again after its change and say the check passed."), note);

        fix.menuRun(true);
        var rows = fix.repairs();
        assertEquals(2, rows.size(), rows.toString());
        var second = rows.get(1);
        assertEquals(first.get("id"), second.get("previous_id"));
        assertNotEquals(first.get("repair_task_id"), second.get("repair_task_id"));
        String goal = (String) second.get("goal");
        assertTrue(goal.contains(": the report of the previous self-repair of this skill (task "
                + first.get("repair_task_id") + ")"), goal);
        assertTrue(String.valueOf(second.get("evidence")).contains("I added browser headers; I did not run it again."),
                String.valueOf(second.get("evidence")));
    }

    // ───────────────────────── what a repair may do ─────────────────────────

    @Test
    @DisplayName("inside a repair, tools with side effects are refused -- the cloud's own call and a delegated call alike -- and so is a skill_create of another skill")
    void sideEffectsRefused(@TempDir Path tmp) throws Exception {
        fix = new Fix(tmp);
        fix.rig.queue.start();
        fix.scriptMenu(true);
        fix.rig.cloud.think.add(call("smtp_send_email", Map.of("to", "owner@example.com", "body", "test")));
        fix.rig.cloud.think.add(call(AgentAction.DELEGATE, Map.of("calls",
                List.of(Map.of("tool", "smtp_send_email", "params", Map.of("to", "owner@example.com"))))));
        // Two steps that ran nothing, then one that did: three in a row would end the task.
        fix.rig.cloud.think.add(call(AgentAction.SKILL_MANAGE, Map.of("action", "read", "name", MENU)));
        fix.rig.cloud.think.add(call(AgentAction.SKILL_CREATE, Map.of("name", "menu_helper", "description", "x",
                "parameters", "{}")));
        fix.rig.cloud.think.add(call(AgentAction.SKILL_MANAGE, Map.of("action", "delete", "name", "smtp_send_email")));
        fix.rig.cloud.think.add(respond("Nothing changed."));
        fix.unattended(TASK);
        var row = fix.ended(fix.lastId());
        assertEquals("Nothing changed.", row.get("report"), "it ended by its answer");

        assertTrue(fix.mailCalls.isEmpty(), "a side-effecting tool ran in a repair: " + fix.mailCalls);
        assertTrue(fix.created.isEmpty(), "another skill was created: " + fix.created);
        assertEquals(0, fix.rig.cloud.calls("codegen").size());
        String sent = String.join("\n", LoopRig.userParts(fix.rig.cloud.calls("think")
                .get(fix.rig.cloud.calls("think").size() - 1)));
        assertTrue(sent.contains("runs no other tool"), sent);
        assertTrue(sent.contains("changes no other skill: skill_create only under the name '" + MENU + "'"), sent);
        assertTrue(sent.contains("deletes nothing"), sent);
    }

    @Test
    @DisplayName("a skill with side effects is never run in its own repair, keeps the flag through its skill_create, and its change is told as not checked")
    void sideEffectSkillIsNeverRun(@TempDir Path tmp) throws Exception {
        var sent = new CopyOnWriteArrayList<Map<String, Object>>();
        fix = new Fix(tmp, skill("menu_mailer", true, List.of("SMTP_PASS"), false, sent,
                p -> ToolResult.success("{\"ok\": true, \"warning\": \"one menu missing\"}")));
        fix.rig.queue.start();
        fix.rig.cloud.think.add(call("menu_mailer", Map.of()));
        fix.rig.cloud.think.add(problems("Sent, one menu missing.", List.of(Map.of("tool", "menu_mailer",
                "what", "It sent the mail without the second menu."))));
        // The repair: tries to run it, then to declare it free of side effects, then fixes it.
        fix.rig.cloud.think.add(call("menu_mailer", Map.of()));
        fix.rig.cloud.think.add(call(AgentAction.SKILL_CREATE, Map.of("name", "menu_mailer",
                "description", "Mail the menus; wait for both.", "parameters", "{}", "has_side_effects", false)));
        fix.rig.cloud.think.add(call(AgentAction.SKILL_MANAGE, Map.of("action", "read", "name", "menu_mailer")));
        fix.rig.cloud.think.add(call(AgentAction.SKILL_CREATE, Map.of("name", "menu_mailer",
                "description", "Mail the menus; wait for both.", "parameters", "{}")));
        fix.rig.cloud.codegen.add(SkillCodegenTest.finished(SkillCodegenTest.module("# v2")));
        fix.rig.cloud.think.add(respond("It mailed before the second menu arrived; now it waits for both."));
        fix.unattended("Using menu_mailer, mail me the menus.");
        var row = fix.ended(fix.lastId());

        assertEquals(1, sent.size(), "run only by the scheduled task, never by its repair: " + sent);
        assertEquals(1, fix.created.size(), "the claim of no side effects was refused: " + fix.created);
        assertEquals(Boolean.TRUE, fix.created.get(0).get("has_side_effects"), fix.created.get(0).toString());
        assertEquals("SMTP_PASS", fix.created.get(0).get("credentials"), "kept: " + fix.created.get(0));
        assertEquals("unverified", row.get("outcome"));
        assertEquals("true", row.get("side_effects"));
        assertTrue(String.valueOf(row.get("goal")).contains("3. Do not run it: it holds credentials (SMTP_PASS), and a "
                + "repair runs only a skill that holds none -- a repair runs only a skill that holds no credential and "
                + "whose SKILL.yaml says has_side_effects: false."),
                String.valueOf(row.get("goal")));
        String note = fix.told().get(fix.told().size() - 1);
        assertTrue(note.contains("Changed, but not run again to check it: it holds credentials (SMTP_PASS)"), note);
    }

    @Test
    @DisplayName("a repair the queue refuses is recorded as not started, and the owner told")
    void notStarted(@TempDir Path tmp) throws Exception {
        var config = new com.ownclaw.config.OwnClawConfig();
        config.getQueue().setMaxQueuedTasks(0);
        // A queue with no room: the repair is refused at once.
        fix = new Fix(tmp, new TaskQueue(null, null, new com.ownclaw.observability.ChatStatusEmitter(), config, null) {
            @Override
            public java.util.concurrent.CompletableFuture<AgentResult> submitRepair(String userId, String goal,
                                                                                     SkillSelfRepair.Job job) {
                return java.util.concurrent.CompletableFuture.completedFuture(
                        AgentResult.error("System busy — please try again later.", new AgentTrajectory(), 0));
            }
        });
        AgentResult r = fix.menuRun(true);

        var row = fix.repairRow(0);
        assertEquals("not_started", row.get("outcome"));
        assertTrue(r.response().contains("Self-repair of " + MENU + " could not start"), r.response());
        String note = fix.told().get(fix.told().size() - 1);
        assertTrue(note.contains("did not start"), note);
        assertTrue(note.contains("System busy"), note);
        // Not a repair that changed nothing: the next problem starts one.
        fix.rig.loop.setSelfRepair(new SkillSelfRepair(fix.rig.jdbc, fix.rig.config, fix.rig.skills, fix.rig.registry,
                fix.rig.queue, new ResultDelivery(fix.rig.chat, fix.rig.emitter), fix.rig.chat, fix.rig.events,
                ZoneId.of("UTC")));
        fix.menuRun(true);
        assertEquals("running", fix.repairRow(1).get("outcome"));
    }

    /** OwnClaw started again: a new queue, and the self-repair a new process makes, wired into the loop. */
    static SkillSelfRepair restarted(Fix fix, TaskQueue queue) {
        var repair = new SkillSelfRepair(fix.rig.jdbc, fix.rig.config, fix.rig.skills, fix.rig.registry, queue,
                new ResultDelivery(fix.rig.chat, fix.rig.emitter), fix.rig.chat, fix.rig.events, ZoneId.of("UTC"));
        fix.rig.loop.setSelfRepair(repair);
        repair.start();
        return repair;
    }

    @Test
    @DisplayName("F10: a repair under way when OwnClaw stops starts again when it is up: its record goes on, both pages say so, and the owner is told")
    void resumedAfterARestart(@TempDir Path tmp) throws Exception {
        fix = new Fix(tmp);
        AgentResult trigger = fix.menuRun(true);   // queued; the process "stops" before it runs
        String cutOff = (String) fix.repairRow(0).get("repair_task_id");
        int toldBefore = fix.told().size();

        var queue = new TaskQueue(fix.rig.loop, fix.rig.events, fix.rig.emitter, fix.rig.config, fix.rig.cancellation);
        try {
            var repair = restarted(fix, queue);
            fix.rig.cloud.think.add(call(AgentAction.SKILL_MANAGE, Map.of("action", "read", "name", MENU)));
            fix.rig.cloud.think.add(respond("The site blocks every client; nothing to change."));
            queue.start();
            repair.resume();
            var row = fix.ended(fix.repairRow(0).get("id") instanceof Number n ? n.longValue() : -1);

            assertEquals("unchanged", row.get("outcome"), "the record went on to its end");
            String again = (String) row.get("repair_task_id");
            assertNotEquals(cutOff, again);
            assertEquals(1, fix.repairs().size());
            var told = fix.told();
            assertTrue(told.get(toldBefore).contains("OwnClaw restarted while it ran (task " + cutOff
                    + "), so it starts again as task " + again), told.toString());
            assertTrue(told.get(told.size() - 1).contains("Not fixed: the repair did not change the skill"), told.toString());
            assertTrue(states(fix, cutOff).contains("interrupted"), states(fix, cutOff).toString());
            assertTrue(states(fix, trigger.taskId()).contains("resumed"), states(fix, trigger.taskId()).toString());
            assertTrue(states(fix, again).containsAll(List.of("repairing", "unchanged")), states(fix, again).toString());
            // It was handed the same evidence again.
            var first = fix.rig.cloud.calls("think").get(fix.rig.cloud.calls("think").size() - 2);
            assertTrue(String.join("\n", LoopRig.userParts(first)).contains(PROBLEM));
        } finally {
            queue.stop();
        }
    }

    @Test
    @DisplayName("F10: a repair cut off again with no change of its own since it was started again is not started a third time; the next problem starts a new one")
    void cutOffTwiceIsNoProgress(@TempDir Path tmp) throws Exception {
        fix = new Fix(tmp);
        fix.menuRun(true);
        var queue = new TaskQueue(fix.rig.loop, fix.rig.events, fix.rig.emitter, fix.rig.config, fix.rig.cancellation);
        restarted(fix, queue).resume();                 // started again, and "stops" before it runs
        assertEquals("running", fix.repairRow(0).get("outcome"));
        var queue2 = new TaskQueue(fix.rig.loop, fix.rig.events, fix.rig.emitter, fix.rig.config, fix.rig.cancellation);
        restarted(fix, queue2).resume();
        assertEquals("interrupted", fix.repairRow(0).get("outcome"));
        assertEquals(0, queue2.getQueueSize());
        String note = fix.told().get(fix.told().size() - 1);
        assertTrue(note.contains("again, and it had changed nothing since it was started again"), note);
        // Interrupted is no verdict: the next problem starts a repair.
        fix.rig.loop.setSelfRepair(fix.repair);
        fix.menuRun(true);
        assertEquals("running", fix.repairRow(1).get("outcome"));
    }

    @Test
    @DisplayName("switched off since a repair began, it is not started again after a restart")
    void notResumedWhenSwitchedOff(@TempDir Path tmp) throws Exception {
        fix = new Fix(tmp);
        fix.menuRun(true);
        fix.rig.config.getSelfRepair().setEnabled(false);
        var queue = new TaskQueue(fix.rig.loop, fix.rig.events, fix.rig.emitter, fix.rig.config, fix.rig.cancellation);
        restarted(fix, queue).resume();
        assertEquals("interrupted", fix.repairRow(0).get("outcome"));
        assertEquals(0, queue.getQueueSize());
    }

    @Test
    @DisplayName("R2-5 (P3): a database error while starting repairs again never fails OwnClaw's start; the repair stays to be started next time")
    void aBusyDatabaseDoesNotFailTheStart(@TempDir Path tmp) throws Exception {
        fix = new Fix(tmp);
        fix.menuRun(true);
        var busy = new org.springframework.jdbc.core.JdbcTemplate(fix.rig.jdbc.getDataSource()) {
            @Override
            public List<Map<String, Object>> queryForList(String sql, Object... args) {
                if (sql.contains("skill_repairs")) {
                    throw new org.springframework.dao.CannotAcquireLockException("[SQLITE_BUSY] database is locked");
                }
                return super.queryForList(sql, args);
            }
        };
        var queue = new TaskQueue(fix.rig.loop, fix.rig.events, fix.rig.emitter, fix.rig.config, fix.rig.cancellation);
        var repair = new SkillSelfRepair(busy, fix.rig.config, fix.rig.skills, fix.rig.registry, queue,
                new ResultDelivery(fix.rig.chat, fix.rig.emitter), fix.rig.chat, fix.rig.events, ZoneId.of("UTC"));
        assertDoesNotThrow(repair::resume);
        assertEquals("running", fix.repairRow(0).get("outcome"), "left for the next start");
    }

    @Test
    @DisplayName("P7: a repair cut off by OwnClaw stopping keeps its record running, untold, and is started again at the next start")
    void stoppingOwnClawLeavesTheRepairToResume(@TempDir Path tmp) throws Exception {
        fix = new Fix(tmp);
        fix.rig.queue.start();
        fix.scriptMenu(true);
        // OwnClaw starts stopping while the repair's first call is under way; the call fails with it.
        fix.rig.cloud.think.add(c -> {
            fix.repair.closing();
            throw new com.ownclaw.llm.LlmException("anthropic", "Connection failed: api.example.com", 0,
                    new java.net.UnknownHostException("api.example.com"));
        });
        fix.rig.cloud.think.add(c -> {
            throw new com.ownclaw.llm.LlmException("anthropic", "Connection failed: api.example.com", 0,
                    new java.net.UnknownHostException("api.example.com"));
        });
        int toldBefore = fix.told().size();
        fix.unattended(TASK);
        String repairTask = (String) fix.repairRow(0).get("repair_task_id");
        long until = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < until && states(fix, repairTask).size() < 1) Thread.sleep(50);
        // Its task has ended: its record is still running.
        while (System.currentTimeMillis() < until && fix.rig.jdbc.queryForList(
                "SELECT 1 FROM events WHERE event_type = 'task_completed' AND task_id = ?", repairTask).isEmpty()) {
            Thread.sleep(50);
        }
        assertEquals("running", fix.repairRow(0).get("outcome"));
        assertEquals(toldBefore, fix.told().size(), "told of an ending that is no outcome: " + fix.told());
        // The next start goes on with it.
        var queue = new TaskQueue(fix.rig.loop, fix.rig.events, fix.rig.emitter, fix.rig.config, fix.rig.cancellation);
        try {
            restarted(fix, queue).resume();
            assertNotEquals(repairTask, fix.repairRow(0).get("repair_task_id"));
            assertEquals(1, queue.getQueueSize());
        } finally {
            queue.stop();
        }
    }

    static List<String> states(Fix fix, String taskId) {
        return fix.rig.jdbc.queryForList("SELECT json_extract(details, '$.state') FROM events WHERE event_type = "
                + "'self_repair' AND task_id = ? ORDER BY id", String.class, taskId);
    }

    @Test
    @DisplayName("a repair's goal never sets off the capability resolver's skill_create at its first step, as an ordinary task's request does")
    void noDeterministicSkillCreateInARepair(@TempDir Path tmp) throws Exception {
        fix = new Fix(tmp, null, registry -> new CapabilityResolver(registry, null));
        String asks = "Scan the network for devices and list each host with its open ports.";
        assertNotNull(new CapabilityResolver(fix.rig.registry, null).resolve(asks), "the request sets it off");
        // An ordinary unattended task: a skill is made at step 1, by code.
        fix.rig.config.getSelfRepair().setEnabled(false);
        fix.rig.cloud.codegen.add(SkillCodegenTest.finished(SkillCodegenTest.module("")));
        fix.rig.cloud.think.add(respond("Made a scanner."));
        fix.unattended(asks);
        assertEquals(1, fix.created.size(), fix.created.toString());

        // The same words in a repair's goal: nothing is made that the repair did not ask for.
        fix.rig.config.getSelfRepair().setEnabled(true);
        fix.rig.cloud.think.add(call(AgentAction.SKILL_MANAGE, Map.of("action", "read", "name", MENU)));
        fix.rig.cloud.think.add(respond("Nothing to change."));
        fix.rig.loop.executeRepair("u1", "Repair the skill '" + MENU + "'. " + asks,
                job(MENU, false));
        assertEquals(1, fix.created.size(), "a repair made a skill by itself: " + fix.created);
        assertEquals(1, fix.rig.cloud.calls("codegen").size());
    }

    // ───────────────────────── the report ─────────────────────────

    @Test
    @DisplayName("problems is offered on an unattended task's respond, and not on a chat's")
    void problemsOfferedOnlyUnattended(@TempDir Path tmp) throws Exception {
        fix = new Fix(tmp);
        fix.rig.cloud.think.add(respond("Nothing to do."));
        fix.unattended("Say hello.");                                               // 0: unattended
        fix.rig.cloud.think.add(respond("Hello."));
        fix.rig.turn(fix.rig.chat.createSession("u1", "Hi"), "Say hello.");        // 1: a chat
        fix.rig.cloud.think.add(respond("Hello."));
        fix.rig.loop.executeFull("u1", "Say hello.", true, null, List.of(), TaskChat.Channel.OPS);   // 2: ops
        fix.rig.cloud.think.add(respond("Nothing to change."));
        fix.rig.loop.executeRepair("u1", "Repair the skill '" + MENU + "'.",
                job(MENU, false));     // 3: a repair
        fix.rig.config.getSelfRepair().setEnabled(false);
        fix.rig.cloud.think.add(respond("Nothing to do."));
        fix.unattended("Say hello.");                                               // 4: switched off

        var thinks = fix.rig.cloud.calls("think");
        assertEquals(5, thinks.size());
        assertTrue(propertiesOfRespond(thinks.get(0)).containsKey(SkillSelfRepair.PROBLEMS),
                "unattended: " + propertiesOfRespond(thinks.get(0)));
        for (int i : new int[] {1, 2, 3, 4}) {
            assertFalse(propertiesOfRespond(thinks.get(i)).containsKey(SkillSelfRepair.PROBLEMS),
                    i + ": " + propertiesOfRespond(thinks.get(i)));
        }
        assertFalse(String.valueOf(propertiesOfRespond(thinks.get(4))).contains("is repaired after this task"),
                "switched off, nothing promises a repair");
        // A repair is asked how its check went instead.
        @SuppressWarnings("unchecked")
        var check = (Map<String, Object>) propertiesOfRespond(thinks.get(3)).get(SkillSelfRepair.CHECK);
        assertEquals(SkillSelfRepair.CHECKS, check.get("enum"), String.valueOf(check));
        assertFalse(propertiesOfRespond(thinks.get(0)).containsKey(SkillSelfRepair.CHECK));
        @SuppressWarnings("unchecked")
        var items = (Map<String, Object>) ((Map<String, Object>) propertiesOfRespond(thinks.get(0))
                .get(SkillSelfRepair.PROBLEMS)).get("items");
        assertEquals(List.of("tool", "what"), items.get("required"));
    }

    @Test
    @DisplayName("on the text protocol, unattended work is told of problems in its step's prompt, and a chat is not")
    void problemsOnTheTextProtocol(@TempDir Path tmp) throws Exception {
        fix = new Fix(tmp);
        fix.rig.config.getMentor().setNativeTools(false);
        LoopRig.Reply text = c -> com.ownclaw.llm.Replies.of(
                "{\"tool\": \"respond\", \"params\": {\"message\": \"Hello.\"}}", 100, 10);
        fix.rig.cloud.think.add(text);
        fix.unattended("Say hello.");
        fix.rig.cloud.think.add(text);
        fix.rig.turn(fix.rig.chat.createSession("u1", "Hi"), "Say hello.");

        var thinks = fix.rig.cloud.calls("think");
        assertEquals(2, thinks.size());
        assertTrue(String.join("\n", LoopRig.userParts(thinks.get(0))).contains("- Problems: when you respond, add \""
                + SkillSelfRepair.PROBLEMS + "\""), String.join("\n", LoopRig.userParts(thinks.get(0))));
        assertFalse(String.join("\n", LoopRig.userParts(thinks.get(1))).contains("- Problems:"));
        // A repair is told to say how its check went, and nothing of problems.
        fix.rig.cloud.think.add(text);
        fix.rig.loop.executeRepair("u1", "Repair the skill '" + MENU + "'.",
                job(MENU, false));
        String repair = String.join("\n", LoopRig.userParts(fix.rig.cloud.calls("think").get(2)));
        assertTrue(repair.contains("- Check: when you respond, add \"" + SkillSelfRepair.CHECK + "\""), repair);
        assertFalse(repair.contains("- Problems:"), repair);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> propertiesOfRespond(LoopRig.Call call) {
        ToolSpec respond = call.config().tools().stream().filter(t -> AgentAction.RESPOND.equals(t.name()))
                .findFirst().orElseThrow();
        return (Map<String, Object>) respond.inputSchema().get("properties");
    }

    @Test
    @DisplayName("problems is read strictly: entries that are no {tool, what} are left out, and nothing is guessed")
    void problemsParsedStrictly() {
        assertEquals(List.of(), SkillSelfRepair.parseProblems(null));
        assertEquals(List.of(), SkillSelfRepair.parseProblems(""));
        assertEquals(List.of(), SkillSelfRepair.parseProblems("the menu skill failed"));
        assertEquals(List.of(), SkillSelfRepair.parseProblems(42));
        assertEquals(List.of(), SkillSelfRepair.parseProblems(true));
        assertEquals(List.of(), SkillSelfRepair.parseProblems(List.of("daily_menu_fetcher")));
        assertEquals(List.of(), SkillSelfRepair.parseProblems(List.of(Map.of("tool", MENU))));
        assertEquals(List.of(), SkillSelfRepair.parseProblems(List.of(Map.of("tool", MENU, "what", "  "))));
        assertEquals(List.of(), SkillSelfRepair.parseProblems(List.of(Map.of("tool", "Daily Menu", "what", "x"))));
        assertEquals(List.of(), SkillSelfRepair.parseProblems(List.of(Map.of("tool", 7, "what", "x"))));
        assertEquals(List.of(), SkillSelfRepair.parseProblems(List.of(Map.of("tool", MENU, "what", List.of("x")))));
        var one = new SkillSelfRepair.Reported(MENU, PROBLEM);
        assertEquals(List.of(one), SkillSelfRepair.parseProblems(List.of(Map.of("tool", MENU, "what", " " + PROBLEM + " "))));
        assertEquals(List.of(one), SkillSelfRepair.parseProblems(Map.of("tool", MENU, "what", PROBLEM)));
        assertEquals(List.of(one), SkillSelfRepair.parseProblems(
                "[{\"tool\": \"" + MENU + "\", \"what\": \"" + PROBLEM + "\"}]"));
        // A bad entry beside a good one: the good one is read.
        assertEquals(List.of(one), SkillSelfRepair.parseProblems(List.of("x", Map.of("tool", MENU, "what", PROBLEM))));
    }

    @Test
    @DisplayName("a malformed problems on an unattended answer starts nothing and does not break the task")
    void malformedProblemsEndWell(@TempDir Path tmp) throws Exception {
        fix = new Fix(tmp);
        fix.rig.cloud.think.add(call(MENU, Map.of()));
        fix.rig.cloud.think.add(problems("Here are the menus.", "Cafe Two's menu failed"));
        AgentResult r = fix.unattended(TASK);

        assertTrue(r.success(), r.response());
        assertEquals("Here are the menus.", r.response());
        assertTrue(fix.repairs().isEmpty());
    }

    // ───────────────────────── the scope rule, alone ─────────────────────────

    @Test
    @DisplayName("the scope rule: a repair reads, runs what changes nothing, and changes its one skill")
    void scopeRule() {
        var job = job(MENU, false);
        var calls = new ArrayList<Map<String, Object>>();
        Tool reads = skill("web_snapshot", false, List.of(), false, calls, p -> ToolResult.success(""));
        Tool sends = skill("smtp_send_email", true, List.of(), false, calls, p -> ToolResult.success(""));
        Tool menu = skill(MENU, false, List.of(), false, calls, p -> ToolResult.success(""));

        assertNull(SkillSelfRepair.outOfScope(null, "smtp_send_email", Map.of(), sends), "not a repair");
        // Its own skill, declared free of side effects as the repair began: run.
        assertNull(SkillSelfRepair.outOfScope(job, MENU, Map.of(), menu));
        // No other tool, whatever it declares.
        assertNotNull(SkillSelfRepair.outOfScope(job, "web_snapshot", Map.of(), reads));
        assertNotNull(SkillSelfRepair.outOfScope(job, "smtp_send_email", Map.of(), sends));
        // Its own skill, declared to have side effects, or declaring nothing: not run, whatever it says now.
        assertNotNull(SkillSelfRepair.outOfScope(job(MENU, true), MENU, Map.of(), menu));
        assertNotNull(SkillSelfRepair.outOfScope(job(MENU, null), MENU, Map.of(), menu));
        assertNull(SkillSelfRepair.outOfScope(job, AgentAction.SKILL_CREATE, Map.of("name", MENU), null));
        assertNotNull(SkillSelfRepair.outOfScope(job, AgentAction.SKILL_CREATE, Map.of("name", "other"), null));
        assertNotNull(SkillSelfRepair.outOfScope(job, AgentAction.SKILL_CREATE, Map.of(), null));
        assertNull(SkillSelfRepair.outOfScope(job, AgentAction.SKILL_MANAGE, Map.of("action", "read", "name", MENU), null));
        assertNotNull(SkillSelfRepair.outOfScope(job, AgentAction.SKILL_MANAGE, Map.of("action", "delete"), null));
        assertNull(SkillSelfRepair.outOfScope(job, AgentAction.MEMORY_MANAGE, Map.of("action", "recall"), null));
        assertNotNull(SkillSelfRepair.outOfScope(job, AgentAction.MEMORY_MANAGE, Map.of("action", "store"), null));
        assertNull(SkillSelfRepair.outOfScope(job, AgentAction.SCHEDULE_MANAGE, Map.of("action", "list"), null));
        assertNotNull(SkillSelfRepair.outOfScope(job, AgentAction.SCHEDULE_MANAGE, Map.of("action", "cancel"), null));
        assertNull(SkillSelfRepair.outOfScope(job, AgentAction.DELEGATE, Map.of("goal", "x"), null));
        assertNull(SkillSelfRepair.outOfScope(job, AgentAction.RESPOND, Map.of("message", "x"), null));
    }

    @Test
    @DisplayName("a repair's skill_create grants its skill nothing it did not have: no credential, network, system package, container image, or claim of no side effects")
    void grantsNothing() {
        var had = new SkillSelfRepair.Declared(null, false, List.of("MENU_API_KEY"), List.of("tesseract-ocr"), null);
        var job = new SkillSelfRepair.Job(1, MENU, "t1", had, false, List.of());
        java.util.function.Function<Map<String, Object>, String> create = extra -> {
            var p = new java.util.HashMap<String, Object>(Map.of("name", MENU, "description", "x", "parameters", "{}"));
            p.putAll(extra);
            return SkillSelfRepair.outOfScope(job, AgentAction.SKILL_CREATE, p, null);
        };
        assertNull(create.apply(Map.of()), "left out, all stays as it was");
        assertNull(create.apply(Map.of("credentials", "MENU_API_KEY")));
        assertNull(create.apply(Map.of("credentials", "")), "dropping one is no grant");
        assertNull(create.apply(Map.of("requires_network", false)));
        assertNull(create.apply(Map.of("system_packages", "tesseract-ocr")));
        assertNull(create.apply(Map.of("has_side_effects", true)), "claiming side effects is safe");
        assertNull(create.apply(Map.of("requirements", "requests\nPillow")), "its Python libraries are a repair's to change");
        assertNotNull(create.apply(Map.of("credentials", "MENU_API_KEY,SMTP_PASS")));
        assertTrue(create.apply(Map.of("credentials", "SMTP_PASS")).contains("the credential SMTP_PASS"));
        assertNotNull(create.apply(Map.of("requires_network", true)));
        assertNotNull(create.apply(Map.of("requires_network", "true")));
        assertNotNull(create.apply(Map.of("system_packages", "tesseract-ocr curl")));
        assertNotNull(create.apply(Map.of("system_packages", "")), "taking them away changes where it runs");
        assertNotNull(create.apply(Map.of("container_image", "python:3.12")));
        assertNotNull(create.apply(Map.of("has_side_effects", false)), "undeclared is not free of side effects");
        assertNotNull(create.apply(Map.of("has_side_effects", "false")));
        // Declared false: saying so again is no grant.
        var free = new SkillSelfRepair.Job(1, MENU, "t1",
                new SkillSelfRepair.Declared(false, true, List.of(), List.of(), "python:3.12"), false, List.of());
        var same = new java.util.HashMap<String, Object>(Map.of("name", MENU, "has_side_effects", false,
                "requires_network", true, "container_image", "python:3.12"));
        assertNull(SkillSelfRepair.outOfScope(free, AgentAction.SKILL_CREATE, same, null));
        // What it left out is kept as it was declared.
        var kept = SkillSelfRepair.keepDeclarations(job, Map.of("name", MENU), null);
        assertEquals("MENU_API_KEY", kept.get("credentials"));
        assertEquals(false, kept.get("requires_network"));
        assertEquals("tesseract-ocr", kept.get("system_packages"));
        assertFalse(kept.containsKey("has_side_effects"), "undeclared stays undeclared: " + kept);
    }
}
