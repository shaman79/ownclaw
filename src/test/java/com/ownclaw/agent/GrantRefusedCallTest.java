package com.ownclaw.agent;

import com.ownclaw.agent.AgentResult.TerminationReason;
import com.ownclaw.agent.tools.DynamicSkill;
import com.ownclaw.agent.tools.Tool;
import com.ownclaw.conversation.ChatOptions;
import com.ownclaw.core.ResultDelivery;
import com.ownclaw.interfaces.CommandHandler;
import com.ownclaw.llm.LlmProvider;
import com.ownclaw.sandbox.SandboxResult;
import com.ownclaw.users.CredentialVault;
import com.ownclaw.users.GrantsKit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static com.ownclaw.agent.AssistantPartsTest.tool;
import static com.ownclaw.agent.LoopRig.call;
import static com.ownclaw.agent.LoopRig.respond;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A call of a skill its account has not granted a credential it declares, through the real loop:
 * whoever calls the skill asks first -- the cloud model's own call and a delegated one alike --
 * and the call is not run and leaves no result, so it is no failure of the skill for self-repair
 * to take up. The task's ending then says, by code and whatever the model wrote, which skill
 * waits for which credentials, and offers the command that grants them to click.
 * <p>
 * The skills are real ones, written by the real skill manager, over a sandbox that starts
 * nothing; the vault and the grants are the loop's own.
 */
class GrantRefusedCallTest {

    static final String PASS = "loop-canary-pass-34";
    /** What whoever called the skill is told: the skill, the credential, the command. */
    static final String NOT_RUN = "Not run: 'mailer' has not been allowed to read SMTP_PASS from the vault. Only the "
            + "user can allow it, by sending `/grant mailer SMTP_PASS` in the chat";
    /** How the line the ending gets for the mailer ends. */
    static final String ASKED = "mailer was not run: you have not allowed it to read SMTP_PASS from your vault. "
            + "To allow it, send:_ `/grant mailer SMTP_PASS`";
    static final String COMMAND = "/grant mailer SMTP_PASS";

    /** The rig, its one account's vault holding the mail password, and a real skill that declares it. */
    static final class Run {
        final LoopRig rig;
        final GrantsKit.Skills disk;
        final DynamicSkill mailer;

        Run(Path tmp, LlmProvider local, Tool... more) throws Exception {
            rig = local == null ? new LoopRig(tmp, List.of(more)) : new LoopRig(tmp, List.of(more), 600, local);
            rig.jdbc.update("INSERT INTO users (id, display_name) VALUES ('u1', 'Owner')");   // the vault's salt lives on it
            rig.vault.storeCredential("u1", "SMTP_PASS", PASS);
            disk = new GrantsKit.Skills(tmp.resolve("disk"), rig.registry, rig.vault);
            mailer = disk.create("mailer", List.of("SMTP_PASS"));
        }

        String session() {
            return rig.chat.createSession("u1", "Mail");
        }

        /** What the cloud was sent with its think call {@code n}, from 0. */
        String sent(int n) {
            return TaskEndToEndTest.sentTo(rig, n);
        }

        List<String> grants() {
            return GrantsKit.rows(rig.jdbc);
        }

        int usageRows() {
            return rig.jdbc.queryForObject("SELECT COUNT(*) FROM skill_usage WHERE tool_name = 'mailer'", Integer.class);
        }

        /** A self-repair wired into the loop, as the application wires it. */
        SkillSelfRepair selfRepair() {
            rig.skills.source = name -> "def run(params):\n    return {'output': 'ran'}\n";
            var repair = new SkillSelfRepair(rig.jdbc, rig.config, rig.skills, rig.registry, rig.queue,
                    new ResultDelivery(rig.chat, rig.emitter), rig.chat, rig.events, ZoneId.of("UTC"));
            repair.setCancellation(rig.cancellation);
            rig.loop.setSelfRepair(repair);
            return repair;
        }

        List<Map<String, Object>> repairs() {
            return rig.jdbc.queryForList("SELECT * FROM skill_repairs ORDER BY id");
        }
    }

    static Map<String, Object> mail() {
        return Map.of("to", "owner@example.org");
    }

    static LoopRig.Reply delegateCalls(String... tools) {
        return call(AgentAction.DELEGATE, Map.of("calls",
                java.util.Arrays.stream(tools).map(t -> Map.of("tool", t, "params", mail())).toList()));
    }

    private Run run;

    @AfterEach
    void stopQueue() {
        if (run != null) run.rig.queue.stop();
    }

    // ───────────────────────── the cloud model's own call ─────────────────────────

    @Test
    @DisplayName("attended, the cloud's own call: the skill is not started, the model is told why with the command, nothing is recorded of it, and the ending says so by code and offers the command to click")
    void theCloudsOwnCallAttended(@TempDir Path tmp) throws Exception {
        run = new Run(tmp, null);
        run.rig.cloud.think.add(call("mailer", mail()));
        run.rig.cloud.think.add(respond("I could not send it."));
        String session = run.session();

        AgentResult r = run.rig.turn(session, "mail me the report", ChatOptions.NONE);

        assertEquals(TerminationReason.COMPLETED, r.terminationReason(), r.response());
        assertEquals(List.of(), run.disk.launches.runs(), "the skill's process was started");
        assertTrue(run.sent(1).contains(NOT_RUN), "what the model was told: " + run.sent(1));
        assertEquals(0, run.usageRows(), "a refusal is no use of the skill, and no failure of it");
        // The model said nothing of a grant: the ending does, by code.
        assertTrue(r.response().startsWith("I could not send it."), r.response());
        assertTrue(r.response().endsWith(ASKED), r.response());
        assertEquals(List.of(COMMAND), r.choices());
        // The saved row the page draws again after a reload: the line, and the command as a button.
        var saved = run.rig.chat.getSessionMessages("u1", session, null, null).messages().getLast();
        assertTrue(String.valueOf(saved.get("content")).endsWith(ASKED), String.valueOf(saved.get("content")));
        assertEquals("[\"" + COMMAND + "\"]", String.valueOf(saved.get("choices")));
        // Mutations: the loop does not ask before the call -> the refusal is the skill's own, a result
        // of it, recorded; the ending without grantsAsked -> no line and no choice.
    }

    @Test
    @DisplayName("the command the ending offers, sent as the owner's message, grants: the next call of the skill runs with the credential")
    void theOfferedCommandGrants(@TempDir Path tmp) throws Exception {
        run = new Run(tmp, null);
        run.rig.cloud.think.add(call("mailer", mail()));
        run.rig.cloud.think.add(respond("It waits for you."));
        String session = run.session();
        AgentResult refused = run.rig.turn(session, "mail me the report", ChatOptions.NONE);
        var commands = new CommandHandler(run.rig.registry, null, null, null, run.rig.vault, run.rig.vault.grants(),
                null, null, null, null, null, null, null, null, null, null);

        // The button's text is the message: the chat hands it to the command handler as it does a typed one.
        assertTrue(CommandHandler.isCommand(refused.choices().get(0)));
        String said = commands.handle("u1", refused.choices().get(0)).orElseThrow();
        assertTrue(said.contains("'mailer' may now read: SMTP_PASS."), said);

        run.rig.cloud.think.add(call("mailer", mail()));
        run.rig.cloud.think.add(respond("Sent."));
        AgentResult sent = run.rig.turn(session, "now send it", ChatOptions.NONE);

        assertEquals(1, run.disk.launches.runs().size());
        assertEquals(Map.of("SMTP_PASS", PASS), run.disk.launches.runs().get(0).credentials());
        assertEquals("Sent.", sent.response(), "nothing waits: the ending says nothing of grants");
        assertEquals(List.of(), sent.choices());
        assertEquals(1, run.usageRows(), "the run is a use of the skill");
    }

    @Test
    @DisplayName("unattended, the cloud's own call: no result is recorded, so self-repair finds nothing to repair -- even where the model reports the skill as a problem -- and the ending still says what waits")
    void theCloudsOwnCallUnattended(@TempDir Path tmp) throws Exception {
        run = new Run(tmp, null);
        run.rig.cloud.think.add(call("mailer", mail()));
        run.rig.cloud.think.add(SkillSelfRepairTest.problems("The report could not be mailed.",
                List.of(Map.of("tool", "mailer", "what", "It was not allowed to read its password."))));
        var ctx = new AgentContext("u1", "0grant01", "Daily report\nUsing mailer, mail me the report.");
        ctx.setUnattended(true);

        AgentResult r = run.rig.loop.run(ctx);

        assertEquals(TerminationReason.COMPLETED, r.terminationReason(), r.response());
        assertEquals(List.of(), run.disk.launches.runs());
        assertEquals(List.of(), ctx.artifacts(), "a refused call left a result");
        assertEquals(List.of("mailer"), ctx.notAllowed());
        assertEquals(Map.of(), SkillSelfRepair.found(ctx, r, (name, user) -> true), "nothing ran: nothing to repair");
        assertTrue(r.response().endsWith(ASKED), r.response());
        assertEquals(List.of(COMMAND), r.choices());
        assertEquals(0, run.usageRows());
        // Mutation: the refusal recorded as a failed result of the skill -> found() names mailer.
    }

    @Test
    @DisplayName("with self-repair wired in: a refused skill starts no repair, where the same skill failing for real starts one")
    void aRefusalStartsNoRepair(@TempDir Path tmp) throws Exception {
        run = new Run(tmp, null);
        run.selfRepair();
        run.rig.cloud.think.add(call("mailer", mail()));
        run.rig.cloud.think.add(respond("The report could not be mailed."));

        AgentResult refused = run.rig.loop.executeFull("u1", "Daily report\nUsing mailer, mail me the report.", true);

        assertEquals(List.of(), run.repairs(), "a repair cannot grant: it would change a skill nothing is wrong with");
        assertFalse(refused.response().contains("Self-repair"), refused.response());
        assertTrue(refused.response().endsWith(ASKED), refused.response());

        // The control: granted, and failing by itself, the skill's last call failed -- a repair starts.
        run.rig.vault.grants().grant("u1", "mailer", List.of("SMTP_PASS"));
        run.disk.launches.answer = launch -> new SandboxResult(1, "", "Traceback: ConnectionResetError", 1, false);
        run.rig.cloud.think.add(call("mailer", mail()));
        run.rig.cloud.think.add(respond("The report could not be mailed."));

        AgentResult failed = run.rig.loop.executeFull("u1", "Daily report\nUsing mailer, mail me the report.", true);

        assertEquals(1, run.repairs().size(), failed.response());
        assertEquals("mailer", run.repairs().get(0).get("skill_name"));
        assertTrue(failed.response().contains("Self-repair started for mailer"), failed.response());
        assertFalse(failed.response().contains("was not run"), "granted: nothing waits");
        // Mutation: the loop does not ask before the call -> the skill's own refusal is its failed
        // result, and a repair of it starts.
    }

    // ───────────────────────── a delegated call ─────────────────────────

    @Test
    @DisplayName("a delegated call the cloud wrote, attended: not started, the cloud is told why, no result, and the ending says so and offers the command")
    void aDelegatedCallAttended(@TempDir Path tmp) throws Exception {
        run = new Run(tmp, null);
        run.rig.cloud.think.add(delegateCalls("mailer"));
        run.rig.cloud.think.add(respond("It waits for you."));

        AgentResult r = run.rig.turn(run.session(), "mail me the report", ChatOptions.NONE);

        assertEquals(TerminationReason.COMPLETED, r.terminationReason(), r.response());
        assertEquals(List.of(), run.disk.launches.runs());
        assertTrue(run.sent(1).contains("call 1 (mailer) was not run. " + NOT_RUN), run.sent(1));
        assertTrue(r.response().endsWith(ASKED), r.response());
        assertEquals(List.of(COMMAND), r.choices());
        assertEquals(0, run.usageRows());
        // Mutation: the delegation does not ask before the call -> the refusal is the skill's own, recorded as its result.
    }

    @Test
    @DisplayName("a delegated call, unattended -- how scheduled work calls a skill: no result, nothing for self-repair, and the ending says what waits; the call before it ran, the one after it did not")
    void aDelegatedCallUnattended(@TempDir Path tmp) throws Exception {
        var pinged = new java.util.concurrent.CopyOnWriteArrayList<Map<String, Object>>();
        run = new Run(tmp, null, DelegationCallsLoopTest.recording("ping", pinged, "pong"));
        run.rig.cloud.think.add(delegateCalls("ping", "mailer", "ping"));
        run.rig.cloud.think.add(respond("The report could not be mailed."));
        var ctx = new AgentContext("u1", "0grant02", "Daily report\nUsing mailer, mail me the report.");
        ctx.setUnattended(true);

        AgentResult r = run.rig.loop.run(ctx);

        assertEquals(1, pinged.size(), "the call before the refused one ran, the one after it did not");
        assertEquals(List.of(), run.disk.launches.runs());
        assertEquals(List.of("ping"), ctx.artifacts().stream().map(Artifact::tool).toList(), "no result of mailer");
        assertEquals(Map.of(), SkillSelfRepair.found(ctx, r, (name, user) -> true));
        assertTrue(run.sent(1).contains("call 2 (mailer) was not run. " + NOT_RUN), run.sent(1));
        assertTrue(r.response().endsWith(ASKED), r.response());
        assertEquals(List.of(COMMAND), r.choices());
        // Mutation: the delegation does not note the skill it refused -> the ending says nothing of it.
    }

    @Test
    @DisplayName("a call the local model makes in a delegation: not started, the local model is told why, and the ending says what waits")
    void theLocalModelsCall(@TempDir Path tmp) throws Exception {
        var local = new DelegationFitTest.Local();
        local.says(DelegationBehaviourTest.call("mailer", mail()));
        local.says(DelegationBehaviourTest.done("The mailer waits for the owner to allow it."));
        run = new Run(tmp, local);
        run.rig.cloud.think.add(call(AgentAction.DELEGATE, Map.of("goal", "Mail the report to the owner with mailer.",
                "tools", "mailer")));
        run.rig.cloud.think.add(respond("It waits for you."));

        AgentResult r = run.rig.turn(run.session(), "mail me the report");

        assertEquals(TerminationReason.COMPLETED, r.terminationReason(), r.response());
        assertEquals(2, local.asked.size());
        String told = local.asked.get(1).get(local.asked.get(1).size() - 1).content();
        assertTrue(told.contains(NOT_RUN), "what the local model was told: " + told);
        assertEquals(List.of(), run.disk.launches.runs());
        assertEquals(0, run.usageRows());
        assertTrue(r.response().endsWith(ASKED), r.response());
        assertEquals(List.of(COMMAND), r.choices());
    }

    // ───────────────────────── the ending ─────────────────────────

    @Test
    @DisplayName("a task that ends without an answer says it too: three delegations refused in a row end it, and its ending names the skill and offers the command")
    void anEndingThatIsNoAnswer(@TempDir Path tmp) throws Exception {
        run = new Run(tmp, null);
        for (int i = 0; i < 6; i++) run.rig.cloud.think.add(delegateCalls("mailer"));

        AgentResult r = run.rig.turn(run.session(), "mail me the report", ChatOptions.NONE);

        assertEquals(TerminationReason.FAILURE_LIMIT, r.terminationReason(), r.response());
        assertTrue(r.response().endsWith(ASKED), r.response());
        assertEquals(List.of(COMMAND), r.choices(), "named once, however often it was refused");
        assertEquals(List.of(), run.disk.launches.runs());
    }

    @Test
    @DisplayName("a skill granted meanwhile is not named: the ending asks for nothing that is no longer needed")
    void grantedMeanwhile(@TempDir Path tmp) throws Exception {
        run = new Run(tmp, null);
        run.rig.cloud.think.add(call("mailer", mail()));
        run.rig.cloud.think.add(config -> {
            // The owner sends /grant in another chat while the task goes on.
            run.rig.vault.grants().grant("u1", "mailer", List.of("SMTP_PASS"));
            return respond("It was refused; try again.").answer(config);
        });

        AgentResult r = run.rig.turn(run.session(), "mail me the report", ChatOptions.NONE);

        assertEquals("It was refused; try again.", r.response());
        assertEquals(List.of(), r.choices());
        // Mutation: the ending names every skill that was refused, as it stood then -> the line and the choice.
    }

    @Test
    @DisplayName("a skill whose credentials changed between the refusal and the ending is asked for what it declares now; one that declares none, or is gone, is not named")
    void declaredAnewBeforeTheEnding(@TempDir Path tmp) throws Exception {
        run = new Run(tmp, null);
        run.rig.vault.storeCredential("u1", "SMTP_USER", "loop-canary-user-34");
        run.disk.create("postman", List.of("SMTP_PASS"));
        run.disk.create("courier", List.of("SMTP_PASS"));
        run.rig.cloud.think.add(call("mailer", mail()));
        run.rig.cloud.think.add(call("postman", mail()));
        run.rig.cloud.think.add(call("courier", mail()));
        run.rig.cloud.think.add(config -> {
            run.disk.create("mailer", List.of("SMTP_USER", "SMTP_PASS"));   // rewritten: it declares one more
            run.disk.create("postman", null);                               // rewritten: it declares none
            run.disk.manager.deleteSkill("courier");                        // gone
            return respond("None of the three could send.").answer(config);
        });

        AgentResult r = run.rig.turn(run.session(), "mail me the report", ChatOptions.NONE);

        assertEquals(List.of("/grant mailer SMTP_USER SMTP_PASS"), r.choices(), r.response());
        assertTrue(r.response().endsWith("mailer was not run: you have not allowed it to read SMTP_USER, SMTP_PASS from "
                + "your vault. To allow it, send:_ `/grant mailer SMTP_USER SMTP_PASS`"), r.response());
        assertFalse(r.response().contains("postman was not run") || r.response().contains("courier was not run"),
                r.response());
        assertEquals(List.of(), run.disk.launches.runs());
        // Mutation: as grantedMeanwhile's -> postman and courier are asked a grant of nothing.
    }

    @Test
    @DisplayName("two skills that wait are two lines and two commands, in the order they were first refused; one refused twice is named once")
    void twoSkillsWait(@TempDir Path tmp) throws Exception {
        run = new Run(tmp, null);
        run.disk.create("mail_digest", List.of("IMAP_USER", "IMAP_PASS"));
        run.rig.cloud.think.add(call("mail_digest", Map.of()));
        run.rig.cloud.think.add(call("mailer", mail()));
        run.rig.cloud.think.add(call("mail_digest", Map.of("folder", "INBOX")));
        run.rig.cloud.think.add(respond("Neither could run."));

        AgentResult r = run.rig.turn(run.session(), "digest my mail and send it", ChatOptions.NONE);

        assertEquals(List.of("/grant mail_digest IMAP_USER IMAP_PASS", COMMAND), r.choices());
        String said = r.response();
        int digest = said.indexOf("mail_digest was not run: you have not allowed it to read IMAP_USER, IMAP_PASS");
        int mailer = said.indexOf("mailer was not run: you have not allowed it to read SMTP_PASS");
        assertTrue(digest > 0 && mailer > digest, said);
        assertEquals(said.lastIndexOf("mail_digest was not run"), digest, "once: " + said);
        assertTrue(said.contains("`/grant mail_digest IMAP_USER IMAP_PASS`") && said.endsWith(ASKED), said);
    }

    // ───────────────────────── a credential that is not in the vault either ─────────────────────────

    /** How the model is told what the vault does not hold. */
    static final String STORE_TOO = " Not in the vault yet either: IMAP_PASS -- they store each by typing /cred set KEY "
            + "value; do not ask them to paste the value to you.";
    /** How the ending's line for mail_digest ends when its IMAP password is not stored. */
    static final String ASKED_AND_MISSING = "mail_digest was not run: you have not allowed it to read SMTP_PASS, IMAP_PASS "
            + "from your vault. To allow it, send:_ `/grant mail_digest SMTP_PASS IMAP_PASS`\n"
            + "_Not in your vault yet: IMAP_PASS. Store each with_ `/cred set <KEY> <VALUE>`";

    @Test
    @DisplayName("a refused skill whose credential is not in the vault either is told both at once: the model reads which to have stored, the ending's line names it under the command, and only the grant is a button")
    void whatIsNotInTheVaultIsSaidAtOnce(@TempDir Path tmp) throws Exception {
        run = new Run(tmp, null);
        run.disk.create("mail_digest", List.of("SMTP_PASS", "IMAP_PASS"));   // the vault holds the first alone
        run.rig.cloud.think.add(call("mail_digest", Map.of()));
        run.rig.cloud.think.add(call("mailer", mail()));
        run.rig.cloud.think.add(respond("Neither could run."));

        AgentResult r = run.rig.turn(run.session(), "digest my mail and send it", ChatOptions.NONE);

        String digest = run.sent(1);
        assertTrue(digest.contains("Not run: 'mail_digest' has not been allowed to read SMTP_PASS, IMAP_PASS from the vault."), digest);
        assertTrue(digest.contains("say that it waits for them." + STORE_TOO), "the one the vault does not hold, and not the one it does: " + digest);
        String mailer = run.sent(2).substring(run.sent(2).indexOf("Not run: 'mailer'"));
        assertFalse(mailer.contains("Not in the vault yet"), "everything mailer declares is stored: " + mailer);

        assertTrue(r.response().contains(ASKED_AND_MISSING + "\n\n"), r.response());
        assertTrue(r.response().endsWith(ASKED), "mailer's line is as it was: " + r.response());
        assertEquals(List.of("/grant mail_digest SMTP_PASS IMAP_PASS", COMMAND), r.choices(),
                "storing a credential is typed, never offered to click");
        // Mutations: the refusal not told what the vault holds -> the model reads nothing of IMAP_PASS;
        // the ending's line not told -> it ends at the command.
    }

    @Test
    @DisplayName("a credential stored while the task went on is not named in the ending: its line reads the vault as it is then")
    void storedMeanwhileIsNotNamed(@TempDir Path tmp) throws Exception {
        run = new Run(tmp, null);
        run.disk.create("mail_digest", List.of("SMTP_PASS", "IMAP_PASS"));
        run.rig.cloud.think.add(call("mail_digest", Map.of()));
        run.rig.cloud.think.add(config -> {
            // The owner sends /cred set in another chat while the task goes on.
            run.rig.vault.storeCredential("u1", "IMAP_PASS", "loop-canary-imap-34");
            return respond("It waits for you.").answer(config);
        });

        AgentResult r = run.rig.turn(run.session(), "digest my mail", ChatOptions.NONE);

        assertTrue(run.sent(1).contains(STORE_TOO), "the premise: at the call, it was not stored: " + run.sent(1));
        assertTrue(r.response().endsWith("To allow it, send:_ `/grant mail_digest SMTP_PASS IMAP_PASS`"), r.response());
        assertFalse(r.response().contains("Not in your vault yet"), r.response());
        // Mutation: the ending told what the vault held when the task began -> IMAP_PASS is named as missing.
    }

    @Test
    @DisplayName("a delegated call is told the same: the cloud for a call it wrote, the local model for one it made")
    void aDelegatedCallIsToldWhatIsMissing(@TempDir Path tmp) throws Exception {
        var local = new DelegationFitTest.Local();
        local.says(DelegationBehaviourTest.call("mail_digest", Map.of()));
        local.says(DelegationBehaviourTest.done("The digest waits for the owner."));
        run = new Run(tmp, local);
        run.disk.create("mail_digest", List.of("SMTP_PASS", "IMAP_PASS"));
        run.rig.cloud.think.add(delegateCalls("mail_digest"));
        run.rig.cloud.think.add(call(AgentAction.DELEGATE, Map.of("goal", "Digest the owner's mail with mail_digest.",
                "tools", "mail_digest")));
        run.rig.cloud.think.add(respond("It waits for you."));

        AgentResult r = run.rig.turn(run.session(), "digest my mail");

        assertTrue(run.sent(1).contains("call 1 (mail_digest) was not run. Not run: 'mail_digest' has not been allowed"), run.sent(1));
        assertTrue(run.sent(1).contains(STORE_TOO), run.sent(1));
        String told = local.asked.get(1).get(local.asked.get(1).size() - 1).content();
        assertTrue(told.contains("Not run: 'mail_digest' has not been allowed") && told.contains(STORE_TOO),
                "what the local model was told: " + told);
        assertTrue(r.response().endsWith(ASKED_AND_MISSING), r.response());
        assertEquals(List.of(), run.disk.launches.runs());
        // Mutation: the delegation not told what the vault holds -> neither model reads anything of IMAP_PASS.
    }

    @Test
    @DisplayName("the owner's own text of the answer gets the line too, when there is one")
    void theOwnersTextSaysItToo(@TempDir Path tmp) throws Exception {
        String unread = "3 unread: the landlord about the heating, the bank, and a parcel notice";
        run = new Run(tmp, null, tool("imap_peek", List.of("IMAP_USER"), p -> unread));
        run.rig.config.getPrivacy().setPersonalSources(List.of("IMAP_"));   // the mailbox stands for a private result
        run.rig.cloud.think.add(call("imap_peek", Map.of()));
        run.rig.cloud.think.add(call("mailer", mail()));
        run.rig.cloud.think.add(respond("{{1}}"));

        AgentResult r = run.rig.turn(run.session(), "what is unread? and mail it to me", ChatOptions.NONE);

        assertNotNull(r.ownerText(), r.response());
        assertTrue(r.ownerText().contains(unread), r.ownerText());
        assertFalse(r.response().contains(unread), "the premise: the answer the cloud's side keeps is not the owner's");
        assertTrue(r.ownerText().endsWith(ASKED), "what the owner reads: " + r.ownerText());
        assertTrue(r.response().endsWith(ASKED), r.response());
        // Mutation: the line added to the response alone -> the owner, who is shown his own text, never reads it.
    }

    @Test
    @DisplayName("a question's own answers stay and the command is offered after them; an answer the model wrote as the command is not offered at all")
    void aQuestionKeepsItsAnswers(@TempDir Path tmp) throws Exception {
        run = new Run(tmp, null);
        run.rig.cloud.think.add(call("mailer", mail()));
        run.rig.cloud.think.add(call(AgentAction.ASK_USER, Map.of("message", "The mailer may not read its password. What now?",
                "choices", List.of("Skip the mail", "/grant mailer SMTP_PASS SMTP_USER", "/revoke mailer", "Try again later"))));

        AgentResult r = run.rig.turn(run.session(), "mail me the report", ChatOptions.NONE);

        assertTrue(r.awaitingUser(), r.response());
        assertEquals(List.of("Skip the mail", "Try again later", COMMAND), r.choices(),
                "the model's answers, none of them a command, then the one command code wrote");
        assertTrue(r.response().endsWith(ASKED), r.response());
        assertEquals(List.of(), run.grants(), "asking grants nothing");
        // Mutation: choices() without its isCommand test -> the model's /grant and /revoke are buttons too.
    }

    // ───────────────────────── only its user grants (R2), and only in a command ─────────────────────────

    private static List<String> choicesOf(Object choices) {
        var params = new java.util.HashMap<String, Object>();
        params.put("message", "Allow it?");
        params.put("choices", choices);
        return new AgentAction(AgentAction.ASK_USER, params, "").choices();
    }

    @Test
    @DisplayName("a model's own choices never hold /grant or /revoke, however it writes them: a picked choice is sent as the owner's message, and would grant")
    void noChoiceOfTheModelsIsAGrant() {
        // A no-break space and a zero-width one before the slash: blanks the page's trim leaves, and the chats' grammar strips.
        String nbsp = String.valueOf((char) 0x00A0), zeroWidth = String.valueOf((char) 0x200B);
        List<String> commands = List.of("/grant mailer SMTP_PASS", "/grant mailer", " /grant mailer SMTP_PASS", "/GRANT mailer",
                "/Grant mailer smtp_pass", "\t/grant mailer", "/revoke mailer", "/revoke mailer SMTP_PASS", "/grants",
                "/grant", "/grant\tmailer", "\n/grant mailer\n", "/ grant mailer", nbsp + "/grant mailer SMTP_PASS",
                zeroWidth + "/grant mailer SMTP_PASS", zeroWidth + nbsp + " /revoke mailer");
        assertEquals(List.of(), choicesOf(commands), "a native call's list");
        assertEquals(List.of(), choicesOf(String.join("\n", commands)), "the text protocol's one per line");
        assertEquals(List.of(), choicesOf("[\"/grant mailer SMTP_PASS\", \"/revoke mailer\", \" /grant mailer\"]"),
                "the text protocol's JSON array");
        assertEquals(List.of("Allow it", "No"), choicesOf(List.of("Allow it", "/grant mailer SMTP_PASS", "No")));
        for (String command : commands) {
            assertTrue(CommandHandler.isCommand(command), "the chats read '" + command + "' as a command");
        }
        // Mutation: choices() without its isCommand test -> "/grant mailer SMTP_PASS" is a button the model drew.
    }

    @Test
    @DisplayName("words around the command are an answer, not a command: offered, picked and sent, they grant nothing")
    void aChoiceThatOnlyHoldsTheCommandGrantsNothing(@TempDir Path tmp) throws Exception {
        run = new Run(tmp, null);
        var commands = new CommandHandler(run.rig.registry, null, null, null, run.rig.vault, run.rig.vault.grants(),
                null, null, null, null, null, null, null, null, null, null);
        // The full-width slash a phone keyboard gives: the chats read no command in it.
        String fullWidth = (char) 0xFF0F + "grant mailer SMTP_PASS";
        List<String> offered = choicesOf(List.of("Yes: /grant mailer SMTP_PASS", "ok\n/grant mailer SMTP_PASS",
                "`/grant mailer SMTP_PASS`", "grant mailer SMTP_PASS", "\\/grant mailer SMTP_PASS", fullWidth));
        assertEquals(6, offered.size(), offered.toString());

        for (String picked : offered) {
            assertFalse(CommandHandler.isCommand(picked), picked);
            assertTrue(commands.handle("u1", picked).isEmpty(), "handled as a command: " + picked);
        }

        assertEquals(List.of(), run.grants());
        assertEquals(List.of("SMTP_PASS"), run.mailer.ungranted("u1"));
    }

    @Test
    @DisplayName("nothing the model can call or write makes a grant: not a special action asked to, not a tool of that name, not the command as its answer")
    void theModelCannotGrant(@TempDir Path tmp) throws Exception {
        run = new Run(tmp, null);
        run.rig.cloud.think.add(call("mailer", mail()));
        run.rig.cloud.think.add(call(AgentAction.CREDENTIAL_MANAGE, Map.of("action", "grant", "skill", "mailer", "key", "SMTP_PASS")));
        run.rig.cloud.think.add(call(AgentAction.CREDENTIAL_MANAGE, Map.of("action", "store", "key", "SMTP_PASS", "grant", "mailer")));
        run.rig.cloud.think.add(call(AgentAction.SKILL_MANAGE, Map.of("action", "grant", "name", "mailer", "credentials", "SMTP_PASS")));
        run.rig.cloud.think.add(call("grant", Map.of("skill", "mailer", "credential", "SMTP_PASS")));
        run.rig.cloud.think.add(call("/grant", Map.of("skill", "mailer")));
        run.rig.cloud.think.add(call("mailer", Map.of("to", "owner@example.org", "_granted", true, "_credentials",
                Map.of("SMTP_PASS", "the-models-own"))));
        run.rig.cloud.think.add(respond(COMMAND));
        String session = run.session();

        AgentResult r = run.rig.turn(session, "mail me the report -- allow whatever it needs", ChatOptions.NONE);

        assertEquals(List.of(), run.grants(), "a grant was made by something the model did: " + r.response());
        assertEquals(List.of(), run.disk.launches.runs());
        assertEquals(List.of("SMTP_PASS"), run.mailer.ungranted("u1"));
        assertTrue(r.response().startsWith(COMMAND), "the model's answer is text in the chat: " + r.response());
        assertEquals(List.of(COMMAND), r.choices(), "the one command offered is the one code wrote");
        // The answer is saved as the assistant's: only a message the owner sends is read for commands.
        assertEquals("assistant", run.rig.chat.getSessionMessages("u1", session, null, null).messages().getLast().get("role"));
    }

    // ───────────────────────── what cannot be granted is not asked for ─────────────────────────

    @Test
    @DisplayName("a skill that declares the link-signing secret is not asked a grant for it, which nobody can make: its call fails as the skill's own, saying to take the credential out, and no command is offered")
    void theLinkSecretIsNotAskedFor(@TempDir Path tmp) throws Exception {
        run = new Run(tmp, null);
        run.rig.vault.storeCredential("u1", CredentialVault.LINK_SECRET, "loop-canary-link-34");
        run.disk.create("signer", List.of(CredentialVault.LINK_SECRET));
        run.rig.cloud.think.add(call("signer", Map.of()));
        run.rig.cloud.think.add(respond("It cannot sign."));

        AgentResult r = run.rig.turn(run.session(), "sign a link for me", ChatOptions.NONE);

        assertTrue(run.sent(1).contains(CredentialVault.LINK_SECRET + " is never handed to a skill"), run.sent(1));
        assertTrue(run.sent(1).contains("Remove it from this skill's credentials"), run.sent(1));
        assertEquals("It cannot sign.", r.response(), "a command that /grant always refuses was offered");
        assertEquals(List.of(), r.choices());
        assertEquals(List.of(), run.disk.launches.runs());
        // Mutation: the skill asked a grant for every name it declares -> the model is told to have the
        // owner send `/grant signer GUARDIAN_LINK_SECRET`, and the ending offers it.
    }
}
