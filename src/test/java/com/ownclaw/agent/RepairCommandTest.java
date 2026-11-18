package com.ownclaw.agent;

import com.ownclaw.agent.SkillSelfRepairTest.Fix;
import com.ownclaw.core.ResultDelivery;
import com.ownclaw.core.ScheduledTaskService;
import com.ownclaw.interfaces.CommandHandler;
import com.ownclaw.observability.ChatStatusEmitter.StatusMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static com.ownclaw.agent.LoopRig.call;
import static com.ownclaw.agent.LoopRig.respond;
import static com.ownclaw.agent.SkillSelfRepairTest.DEGRADED;
import static com.ownclaw.agent.SkillSelfRepairTest.MENU;
import static com.ownclaw.agent.SkillSelfRepairTest.PROBLEM;
import static com.ownclaw.agent.SkillSelfRepairTest.TASK;
import static com.ownclaw.agent.SkillSelfRepairTest.scriptFixAndCheck;
import static com.ownclaw.agent.SkillSelfRepairTest.verdict;
import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code /repair <skill>}, through the real loop, self-repair, command handler and delivery. After
 * a repair that ended without a fix it had checked, a skill's next problems start none and are
 * left to the owner until a run of it goes well. From 14 Nov 2026 that left a skill whose repair
 * had in fact fixed it to him through two more problems, and nothing he could send said "try
 * again". The command does what a clean run does, for his own records; every text that leaves a
 * skill to him names it, and offers it to click.
 */
class RepairCommandTest {

    static final String COMMAND = "/repair " + MENU;

    /** How the rule ends since the command: the way out that is the owner's to take. */
    static final String OR_SEND = "until a run of the skill goes well, or you send `" + COMMAND + "`: then self-repair "
            + "tries again the next time it shows a problem.";

    static final String CLEARED = "✅ " + MENU + " is no longer left to you: the next time unattended work -- a "
            + "scheduled run, /bg -- shows a problem with it, self-repair starts a repair again. Nothing starts now.";

    static final String NOTHING_LEFT = "Nothing of " + MENU + " is left to you: the next time unattended work shows a "
            + "problem with it, self-repair takes it up as usual. Nothing was changed.";

    private Fix fix;

    @AfterEach
    void stopQueue() {
        if (fix != null) fix.rig.queue.stop();
    }

    /** The command handler the chats hand a typed or clicked text to, with self-repair wired in as Spring wires it. */
    private CommandHandler commands() {
        var commands = new CommandHandler(fix.rig.registry, fix.rig.chat, null, null, fix.rig.vault,
                fix.rig.vault.grants(), fix.rig.queue, null, null, null, null, fix.rig.cancellation,
                new ResultDelivery(fix.rig.chat, fix.rig.emitter), null, null, null);
        commands.setSelfRepair(fix.repair);
        return commands;
    }

    /** Every result delivered to u1 from now on (ResultDelivery), as it comes. */
    private BlockingQueue<StatusMessage> delivered() {
        var results = new LinkedBlockingQueue<StatusMessage>();
        fix.rig.emitter.subscribe("u1", results, m -> {
            if (m.type() == StatusMessage.Type.RESULT) results.add(m);
        });
        return results;
    }

    /** The next result delivered whose text begins with {@code header}: others before it are passed over. */
    private static StatusMessage await(BlockingQueue<StatusMessage> results, String header) throws InterruptedException {
        long until = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < until) {
            StatusMessage m = results.poll(1, TimeUnit.SECONDS);
            if (m != null && m.text().startsWith(header)) return m;
        }
        throw new AssertionError("nothing was delivered that begins with " + header);
    }

    /** The last row of the chat of scheduled results, as the page draws it again after a reload. */
    private Map<String, Object> lastScheduledRow() {
        return fix.rig.chat.getSessionMessages("u1", fix.rig.chat.scheduledSession("u1"), null, null).messages().getLast();
    }

    /** A scheduled run that shows the problem, and a repair of it that reads the skill and changes nothing. */
    private Map<String, Object> aRepairThatChangesNothing() throws InterruptedException {
        fix.scriptMenu(true);
        fix.rig.cloud.think.add(call(AgentAction.SKILL_MANAGE, Map.of("action", "read", "name", MENU)));
        fix.rig.cloud.think.add(respond("The site blocks every client it does not know; nothing in the skill can "
                + "change that."));
        fix.unattended(TASK);
        return fix.ended(fix.lastId());
    }

    private List<Object> clearedAt() {
        return fix.rig.jdbc.queryForList("SELECT cleared_at FROM skill_repairs ORDER BY id").stream()
                .map(r -> r.get("cleared_at")).toList();
    }

    @Test
    @DisplayName("/repair clears a skill left to the owner: it answers that the next problem starts a repair again, starts nothing itself, and the next problem does start one")
    void repairClearsWhatWasLeft(@TempDir Path tmp) throws Exception {
        fix = new Fix(tmp);
        fix.rig.queue.start();
        assertEquals("unchanged", aRepairThatChangesNothing().get("outcome"));
        AgentResult left = fix.menuRun(true);
        assertEquals("gave_up", fix.repairRow(1).get("outcome"), "the premise: the problem again is left to him");
        assertEquals(List.of(COMMAND), left.choices());

        // The button's text is the message: the chat hands it to the command handler as it does a typed one.
        assertTrue(CommandHandler.isCommand(left.choices().get(0)));
        String said = commands().handle("u1", left.choices().get(0)).orElseThrow();

        assertEquals(CLEARED, said);
        assertEquals(2, fix.repairs().size(), "the command started a repair itself: " + fix.repairs());
        assertEquals(0, fix.rig.queue.getQueueSize());
        assertTrue(clearedAt().stream().noneMatch(java.util.Objects::isNull), "what a clean run does: " + clearedAt());

        // The next time the problem shows, a repair starts again.
        fix.scriptMenu(true);
        fix.rig.cloud.think.add(call(AgentAction.SKILL_MANAGE, Map.of("action", "read", "name", MENU)));
        fix.rig.cloud.think.add(respond("Blocked again; nothing to change."));
        AgentResult next = fix.unattended(TASK);
        assertTrue(next.response().contains("Self-repair started for " + MENU), next.response());
        assertEquals(List.of(), next.choices(), "nothing is left to him, so nothing is offered");
        var third = fix.ended(fix.lastId());
        assertEquals(3, fix.repairs().size());
        assertNotNull(third.get("repair_task_id"));
        // Sent again now that nothing waits: nothing changes.
        fix.menuOutput.set(SkillSelfRepairTest.GOOD);
        fix.menuRun(false);                                   // a clean run clears the third
        assertEquals(NOTHING_LEFT, commands().handle("u1", COMMAND).orElseThrow());
        // Mutation: tryAgain answering without clearing the records -> the next problem is "left to
        // you" again, and no repair starts.
    }

    @Test
    @DisplayName("/repair with nothing left to the sender says so and changes nothing: before any repair, while one is under way, sent a second time, and by another account than the one the skill is left to")
    void nothingLeftChangesNothing(@TempDir Path tmp) throws Exception {
        fix = new Fix(tmp);
        var commands = commands();
        assertEquals(NOTHING_LEFT, commands.handle("u1", COMMAND).orElseThrow(), "no repair of it on record");

        // A repair of it is under way: its first call has not come back yet.
        var goOn = new java.util.concurrent.CountDownLatch(1);
        fix.rig.queue.start();
        fix.scriptMenu(true);
        fix.rig.cloud.think.add(c -> {
            goOn.await(30, TimeUnit.SECONDS);
            return call(AgentAction.SKILL_MANAGE, Map.of("action", "read", "name", MENU)).answer(c);
        });
        fix.rig.cloud.think.add(respond("Nothing in the skill can change that."));
        fix.unattended(TASK);
        String task = (String) fix.repairRow(0).get("repair_task_id");
        assertEquals("Nothing of " + MENU + " is left to you -- a repair of it is under way (task " + task + "): the "
                + "next time unattended work shows a problem with it, self-repair takes it up as usual. Nothing was "
                + "changed.", commands.handle("u1", COMMAND).orElseThrow());
        assertEquals("running", fix.repairRow(0).get("outcome"));

        // It ends having changed nothing: left to u1 from here -- and to nobody else.
        goOn.countDown();
        assertEquals("unchanged", fix.ended(fix.lastId()).get("outcome"));
        assertEquals(NOTHING_LEFT, commands.handle("u2", COMMAND).orElseThrow(), "another account's record is not the sender's");
        assertEquals(java.util.Collections.singletonList(null), clearedAt(), "u2's command cleared u1's record");
        AgentResult again = fix.menuRun(true);
        assertTrue(again.response().contains("Self-repair is not tried again for " + MENU), again.response());

        // Typed, in capitals: the same skill. u1's own command clears it.
        assertEquals(CLEARED, commands.handle("u1", "/repair " + MENU.toUpperCase(java.util.Locale.ROOT)).orElseThrow());
        assertEquals(NOTHING_LEFT, commands.handle("u1", COMMAND).orElseThrow(), "sent twice");
        // Mutation: tryAgain clearing whatever the last repair's verdict (its test of leftAfter
        // dropped) -> every answer here is the one that says it was cleared.
    }

    @Test
    @DisplayName("/repair after a repair that fixed and checked the skill changes nothing: a later problem starts a repair anyway")
    void afterAFixNothingIsLeft(@TempDir Path tmp) throws Exception {
        fix = new Fix(tmp);
        fix.rig.queue.start();
        fix.scriptMenu(true);
        scriptFixAndCheck(fix.rig, Map.of("debug", true), verdict("It asks as a browser now; checked.", "passed"));
        var results = delivered();
        fix.unattended(TASK);
        assertEquals("fixed", fix.ended(fix.lastId()).get("outcome"));

        assertEquals(NOTHING_LEFT, commands().handle("u1", COMMAND).orElseThrow());
        assertNull(fix.repairRow(0).get("cleared_at"));
        // And the report of a fix leaves nothing to him: it names no command, and offers none.
        StatusMessage report = await(results, "**🔧 Self-repair of " + MENU + "**");
        assertTrue(report.text().contains("Fixed and checked"), report.text());
        assertFalse(report.text().contains("/repair"), report.text());
        assertEquals(List.of(), ResultDelivery.choicesOf(report));
        // Mutations: as nothingLeftChangesNothing's -> the answer says it was cleared, and the record
        // is; a repair's report offering the command however the repair ended -> a fix's offers it.
    }

    @Test
    @DisplayName("/repair names one skill that exists: a bare command and one with more words are answered with the usage, a name no skill has is not found, and with self-repair off nothing is changed")
    void usageAndRefusals(@TempDir Path tmp) throws Exception {
        fix = new Fix(tmp);
        var commands = commands();
        String usage = "Usage: `/repair <skill>` — for a skill self-repair left to you: the next time unattended work "
                + "shows a problem with it, a repair starts again.";
        assertEquals(usage, commands.handle("u1", "/repair").orElseThrow());
        assertEquals(usage, commands.handle("u1", "/repair " + MENU + " now").orElseThrow());
        assertEquals("Skill not found: no_such_skill", commands.handle("u1", "/repair no_such_skill").orElseThrow());
        assertTrue(commands.handle("u1", "/help").orElseThrow().contains("- `/repair <skill>` — Have self-repair try "
                + "again for a skill it left to you"), commands.handle("u1", "/help").orElseThrow());

        // Left to him, and switched off since: nothing starts a repair, so nothing is cleared.
        fix.rig.jdbc.update("INSERT INTO skill_repairs (user_id, skill_name, problem, side_effects, outcome, completed, "
                + "report, repair_task_id) VALUES ('u1', ?, ?, 'false', 'unchanged', 1, 'Nothing to change.', 'r0000001')",
                MENU, PROBLEM);
        fix.rig.config.getSelfRepair().setEnabled(false);
        assertEquals("Self-repair is switched off (ownclaw.self-repair.enabled), so no repair starts by itself. "
                + "Nothing was changed.", commands.handle("u1", COMMAND).orElseThrow());
        assertEquals(java.util.Collections.singletonList(null), clearedAt());
        // A handler with no self-repair behind it -- a test's -- says so.
        var bare = new CommandHandler(fix.rig.registry, null, null, null, fix.rig.vault, fix.rig.vault.grants(),
                null, null, null, null, null, null, null, null, null, null);
        assertEquals("Self-repair is not available here.", bare.handle("u1", COMMAND).orElseThrow());
        // Mutation: the command handing self-repair whatever name was typed (its lookup of the skill
        // dropped) -> "Nothing of no_such_skill is left to you ...".
    }

    @Test
    @DisplayName("a repair's own report that leaves a later problem to the owner names the command and is delivered with it to click; the saved row offers it again after a reload")
    void theRepairsReportOffersTheCommand(@TempDir Path tmp) throws Exception {
        fix = new Fix(tmp);
        fix.rig.queue.start();
        var results = delivered();
        assertEquals("unchanged", aRepairThatChangesNothing().get("outcome"));

        StatusMessage report = await(results, "**🔧 Self-repair of " + MENU + "**");
        assertTrue(report.text().contains("Not fixed: the repair did not change the skill. Only a fix self-repair has "
                + "checked earns another try: if the problem shows again, it is left to you -- you can have it fixed "
                + "in a chat -- " + OR_SEND + " What the repair said:"), report.text());
        assertEquals(List.of(COMMAND), ResultDelivery.choicesOf(report), "what the page and Telegram are sent: " + report.data());
        var saved = lastScheduledRow();
        assertEquals("[\"" + COMMAND + "\"]", String.valueOf(saved.get("choices")), "drawn again after a reload");
        // Clicked there, before the problem shows again: the next problem starts a repair.
        assertEquals(CLEARED, commands().handle("u1", ResultDelivery.choicesOf(report).get(0),
                fix.rig.chat.scheduledSession("u1")).orElseThrow());
        fix.scriptMenu(true);
        fix.rig.cloud.think.add(respond("Blocked again; nothing to change."));
        assertTrue(fix.unattended(TASK).response().contains("Self-repair started for " + MENU));
        fix.ended(fix.lastId());
        // Mutation: the report delivered through the overload that offers nothing -> neither the
        // event nor the row has the command.
    }

    @Test
    @DisplayName("a run whose problem is left to the owner says so with the command, offers it among its result's choices, and the note he is told once offers it too; the next such run offers it again and tells him nothing more")
    void theEndingsThatLeaveItToTheOwnerOfferTheCommand(@TempDir Path tmp) throws Exception {
        fix = new Fix(tmp);
        fix.rig.queue.start();
        var first = aRepairThatChangesNothing();
        var results = delivered();
        int toldBefore = fix.told().size();

        // The problem again: no repair starts, the owner is told, and the run's ending names the command.
        AgentResult again = fix.menuRun(true);
        assertTrue(again.response().endsWith("_🔧 Self-repair is not tried again for " + MENU + ": its last repair (task "
                + first.get("repair_task_id") + ") changed nothing. You were told; it is left to you. To have it tried "
                + "again the next time it shows a problem, send:_ `" + COMMAND + "`"), again.response());
        assertEquals(List.of(COMMAND), again.choices());
        StatusMessage note = await(results, "**🔧 Self-repair of " + MENU + " left to you**");
        assertTrue(note.text().contains("only a fix self-repair has checked earns another try: it is left to you -- you "
                + "can have it fixed in a chat -- " + OR_SEND + " That repair's report:"), note.text());
        assertEquals(List.of(COMMAND), ResultDelivery.choicesOf(note));
        assertEquals("[\"" + COMMAND + "\"]", String.valueOf(lastScheduledRow().get("choices")));

        // And again: he is not told a second time, and the run's ending still offers the way out.
        AgentResult third = fix.menuRun(true);
        assertEquals(toldBefore + 1, fix.told().size(), fix.told().toString());
        assertTrue(third.response().endsWith("_🔧 Self-repair could not fix " + MENU + " before, and you were told: it is "
                + "left to you. To have it tried again the next time it shows a problem, send:_ `" + COMMAND + "`"),
                third.response());
        assertEquals(List.of(COMMAND), third.choices());
        // Mutations: take() naming the command in its lines and offering none (the list it is handed
        // left as it was) -> the endings' choices are empty; the note delivered through the overload
        // that offers nothing -> it offers none.
    }

    @Test
    @DisplayName("a scheduled run whose problem is left to the owner is delivered with the command as a button -- in its event and in the saved row -- and the command, sent from that chat, clears it")
    void aScheduledResultOffersTheCommand(@TempDir Path tmp) throws Exception {
        fix = new Fix(tmp);
        // Left to him already: his last repair of the skill changed nothing.
        fix.rig.jdbc.update("INSERT INTO skill_repairs (user_id, skill_name, problem, side_effects, outcome, completed, "
                + "report, repair_task_id) VALUES ('u1', ?, ?, 'false', 'unchanged', 1, 'Nothing to change.', 'r0000001')",
                MENU, PROBLEM);
        fix.scriptMenu(true);
        var results = delivered();
        var scheduler = new ScheduledTaskService(fix.rig.jdbc, fix.rig.queue, fix.rig.emitter, fix.rig.events,
                fix.rig.chat, new ResultDelivery(fix.rig.chat, fix.rig.emitter));
        fix.rig.queue.start();
        scheduler.scheduleDeferred("u1", TASK, Instant.now().minusSeconds(60));

        scheduler.pollDueTasks();
        StatusMessage result = await(results, "**Scheduled task: ");

        String pinned = fix.rig.chat.scheduledSession("u1");
        assertEquals(pinned, result.data().get("sessionId"));
        assertTrue(result.text().endsWith("it is left to you. To have it tried again the next time it shows a problem, "
                + "send:_ `" + COMMAND + "`"), result.text());
        assertEquals(List.of(COMMAND), ResultDelivery.choicesOf(result), "what the page and Telegram are sent: " + result.data());
        assertEquals("[\"" + COMMAND + "\"]", String.valueOf(lastScheduledRow().get("choices")), "drawn again after a reload");

        assertEquals(CLEARED, commands().handle("u1", ResultDelivery.choicesOf(result).get(0), pinned).orElseThrow());
        assertTrue(clearedAt().stream().noneMatch(java.util.Objects::isNull), clearedAt().toString());
        // Mutation: self-repair's lines added to a run's ending with the choices it had -> the
        // delivered result offers nothing, and the command is text to type.
    }

    @Test
    @DisplayName("a model can never offer the command: a choice that is one is dropped from a question's answers, so only what code wrote is a button")
    void aModelCannotOfferIt() {
        var asked = new AgentAction(AgentAction.ASK_USER, Map.of("message", "Shall I?",
                "choices", List.of(COMMAND, " " + COMMAND, "Yes", "No")), "");
        assertEquals(List.of("Yes", "No"), asked.choices());
        // Mutation: choices() without its isCommand test -> the model's /repair is a button too.
    }

    @Test
    @DisplayName("left to the owner means what it meant: /repair changes no verdict, and after it a repair that again ends without a checked fix leaves the next problem to him again")
    void leftAgainAfterAnotherRepairWithoutAFix(@TempDir Path tmp) throws Exception {
        fix = new Fix(tmp);
        fix.fixedOutput = DEGRADED;
        fix.rig.queue.start();
        aRepairThatChangesNothing();
        assertEquals(CLEARED, commands().handle("u1", COMMAND).orElseThrow());
        assertEquals("unchanged", fix.repairRow(0).get("outcome"), "the verdict stays as it was");

        assertEquals("unchanged", aRepairThatChangesNothing().get("outcome"), "a repair started again, and changed nothing");
        AgentResult left = fix.menuRun(true);
        assertTrue(left.response().contains("Self-repair is not tried again for " + MENU), left.response());
        assertEquals(List.of(COMMAND), left.choices());
        // Mutation: clearing written as calling the repair fixed (clear() setting the outcome too) ->
        // the record's verdict is rewritten.
    }
}
