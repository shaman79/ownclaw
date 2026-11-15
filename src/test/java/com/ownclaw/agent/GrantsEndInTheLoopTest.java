package com.ownclaw.agent;

import com.ownclaw.agent.AgentResult.TerminationReason;
import com.ownclaw.conversation.ChatOptions;
import com.ownclaw.users.GrantsKit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.ownclaw.agent.LoopRig.call;
import static com.ownclaw.agent.LoopRig.respond;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Where a grant ends, through the real loop: a task's skill_create reaches the real skill manager
 * with the account the task runs for, and what that account may end is decided there. A task of
 * another account than the owner that writes its skill again ends the owner's grant of it, so the
 * owner's next call is refused and asked again; a self-repair, which runs as the owner, ends
 * nothing; and a skill_create that is refused or fails before a skill is made ends nothing.
 */
class GrantsEndInTheLoopTest {

    static final String OWNERS = "loopend-canary-owner-58", KIDS = "loopend-canary-kid-58";
    static final List<String> PASS = List.of("SMTP_PASS");

    /**
     * The rig, with u1 its owner -- the oldest account that can log in -- and a second account,
     * each holding a mail password; real skills on disk, and the real skill manager behind the
     * loop's own.
     */
    static final class Run {
        final LoopRig rig;
        final GrantsKit.Skills disk;
        final String session;

        Run(Path tmp) throws Exception {
            rig = new LoopRig(tmp, List.of());
            GrantsKit.account(rig.jdbc, "u1", "2026-01-05 09:00:00");
            GrantsKit.account(rig.jdbc, "kid", "2026-03-11 17:30:00");
            rig.vault.storeCredential("u1", "SMTP_PASS", OWNERS);
            rig.vault.storeCredential("kid", "SMTP_PASS", KIDS);
            var accounts = GrantsKit.accounts(rig.jdbc);
            assertTrue(accounts.isOwner("u1") && !accounts.isOwner("kid"), "the premise");
            disk = new GrantsKit.Skills(tmp.resolve("disk"), rig.registry, rig.vault, accounts);
            rig.skills.createFor = (params, requester) ->
                    disk.manager.createSkill(params, requester, () -> false, () -> {});
            session = rig.chat.createSession("u1", "Mail");
        }

        /** Kid's skill, which the owner and kid have each granted its password. */
        void kidsMailer() {
            assertFalse(disk.create("kid", "kids_mailer", PASS).startsWith("ERROR"));
            rig.vault.grants().grant("u1", "kids_mailer", PASS);
            rig.vault.grants().grant("kid", "kids_mailer", PASS);
        }

        /** What the cloud was sent with its latest think call. */
        String lastSent() {
            var thinks = rig.cloud.calls("think");
            return String.join("\n", LoopRig.userParts(thinks.get(thinks.size() - 1)));
        }

        String code(String name) throws Exception {
            return Files.readString(disk.generated.resolve(name).resolve("skill.py"));
        }

        List<String> grants() {
            return GrantsKit.rows(rig.jdbc);
        }
    }

    /** A skill_create as a model sends one: the code is written by the code generation. */
    static Map<String, Object> spec(String name, String credentials) {
        var p = new LinkedHashMap<String, Object>();
        p.put("name", name);
        p.put("description", "Send a mail with " + name + ".");
        p.put("parameters", "{}");
        if (credentials != null) p.put("credentials", credentials);
        return p;
    }

    static Map<String, Object> mail() {
        return Map.of("to", "owner@example.org");
    }

    private Run run;

    @AfterEach
    void stopQueue() {
        if (run != null) run.rig.queue.stop();
    }

    @Test
    @DisplayName("a task of another account than the owner writes its skill again: the owner's grant ends, the owner's next call is not started and is asked again, and the account's own calls go on")
    void anotherAccountsTaskWritesItsSkillAgain(@TempDir Path tmp) throws Exception {
        run = new Run(tmp);
        run.kidsMailer();
        run.rig.cloud.think.add(call("kids_mailer", mail()));
        run.rig.cloud.think.add(respond("Sent."));
        run.rig.turn(run.session, "mail me the report", ChatOptions.NONE);
        assertEquals(Map.of("SMTP_PASS", OWNERS), run.disk.launches.runs().get(0).credentials(), "the premise: granted, it runs");

        run.rig.cloud.think.add(call(AgentAction.SKILL_CREATE, spec("kids_mailer", "SMTP_PASS")));
        run.rig.cloud.codegen.add(SkillCodegenTest.finished(SkillCodegenTest.module("# kid's second version")));
        run.rig.cloud.think.add(respond("Rewritten."));
        AgentResult rewritten = run.rig.loop.executeFull("kid", "make kids_mailer copy me on every mail", false);

        assertEquals(TerminationReason.COMPLETED, rewritten.terminationReason(), rewritten.response());
        assertTrue(run.code("kids_mailer").contains("# kid's second version"), run.lastSent());
        assertEquals(List.of("kid kids_mailer SMTP_PASS permanent"), run.grants(), "the owner's grant was for what kid replaced");

        run.rig.cloud.think.add(call("kids_mailer", mail()));
        run.rig.cloud.think.add(respond("It waits for you."));
        AgentResult asked = run.rig.turn(run.session, "mail me the report again", ChatOptions.NONE);

        assertTrue(run.lastSent().contains("Not run: 'kids_mailer' has not been allowed to read SMTP_PASS from the vault."),
                run.lastSent());
        assertEquals(1, run.disk.launches.runs().size(), "kid's new code was started for the owner");
        assertTrue(asked.response().endsWith("To allow it, send:_ `/grant kids_mailer SMTP_PASS`"), asked.response());
        assertEquals(List.of("/grant kids_mailer SMTP_PASS"), asked.choices());

        run.rig.cloud.think.add(call("kids_mailer", mail()));
        run.rig.cloud.think.add(respond("Sent."));
        AgentResult kids = run.rig.loop.executeFull("kid", "mail me the report", false);
        assertEquals("Sent.", kids.response(), "kid's own grant stayed");
        assertEquals(Map.of("SMTP_PASS", KIDS), run.disk.launches.runs().getLast().credentials());
        // Mutation: a rewrite ends nothing, whoever makes it -> the owner's call starts kid's second
        // version with the owner's password.
    }

    @Test
    @DisplayName("a self-repair runs as the task's account, here the owner: its rewrite of another account's skill ends nobody's grant, so the skill works after the repair for everyone it worked for")
    void aSelfRepairEndsNothing(@TempDir Path tmp) throws Exception {
        run = new Run(tmp);
        run.kidsMailer();
        var job = new SkillSelfRepair.Job(999, "kids_mailer", "0repair1",
                new SkillSelfRepair.Declared(null, false, PASS, List.of(), null), false, List.of());
        run.rig.cloud.think.add(call(AgentAction.SKILL_CREATE, spec("kids_mailer", null)));
        run.rig.cloud.codegen.add(SkillCodegenTest.finished(SkillCodegenTest.module("# repaired: retries once on a reset")));
        run.rig.cloud.think.add(SkillSelfRepairTest.verdict("Changed. It holds a credential, so it was not run again.", "not_run"));

        run.rig.loop.executeRepair("u1", "Repair the skill 'kids_mailer'.", job);

        assertTrue(run.code("kids_mailer").contains("# repaired"), "the premise: the repair wrote it again: " + run.lastSent());
        assertEquals(List.of("kid kids_mailer SMTP_PASS permanent", "u1 kids_mailer SMTP_PASS permanent"), run.grants());
        assertEquals(PASS, run.disk.registry.getDynamic("kids_mailer").orElseThrow().requiredCredentials(),
                "and it declares what it declared");

        run.rig.cloud.think.add(call("kids_mailer", mail()));
        run.rig.cloud.think.add(respond("Sent."));
        AgentResult kids = run.rig.loop.executeFull("kid", "mail me the report", false);
        assertEquals("Sent.", kids.response());
        assertEquals(Map.of("SMTP_PASS", KIDS), run.disk.launches.runs().getLast().credentials());
        // Mutation: the owner's rewrite ends the others' grants as another account's does -> kid's call is refused.
    }

    @Test
    @DisplayName("a skill_create that makes no skill ends nothing: blocked by the duplicate gate, refused for its name, its code not written, or another account's skill -- the grants there were are all there")
    void aSkillCreateThatMakesNothingEndsNothing(@TempDir Path tmp) throws Exception {
        run = new Run(tmp);
        run.kidsMailer();
        assertFalse(run.disk.create("u1", "imap_move_to_trash_by_sender", PASS).startsWith("ERROR"));
        run.rig.vault.grants().grant("u1", "imap_move_to_trash_by_sender", PASS);
        // Left under names no skill holds: what a skill made there would end.
        for (String free : List.of("imap_move_to_bin_by_sender_gmail", "kids_mailer_v2", "courier")) {
            run.rig.vault.grants().grant("u1", free, PASS);
        }
        List<String> before = run.grants();

        // The duplicate gate: a name that nearly repeats an existing skill's. No code is asked for.
        run.rig.cloud.think.add(call(AgentAction.SKILL_CREATE, spec("imap_move_to_bin_by_sender_gmail", "SMTP_PASS")));
        run.rig.cloud.think.add(respond("The existing skill does it."));
        run.rig.loop.executeFull("kid", "make me a skill that bins a sender's mail", false);
        assertTrue(run.lastSent().contains("BLOCKED: 'imap_move_to_bin_by_sender_gmail' looks like a duplicate"), run.lastSent());
        // A variant's name: refused in the skill manager's own words.
        run.rig.cloud.think.add(call(AgentAction.SKILL_CREATE, spec("kids_mailer_v2", "SMTP_PASS")));
        run.rig.cloud.think.add(respond("It is to be changed under its own name."));
        run.rig.loop.executeFull("kid", "make a second mailer", false);
        assertTrue(run.lastSent().contains("Skill 'kids_mailer' already exists"), run.lastSent());
        assertTrue(run.rig.cloud.calls("codegen").isEmpty(), "code was asked for a skill that was refused");
        // Code that cannot be checked: nothing is created.
        run.rig.skills.syntax = code -> SkillManager.CHECK_FAILED + "exit 127: python3: not found";
        run.rig.cloud.think.add(call(AgentAction.SKILL_CREATE, spec("courier", "SMTP_PASS")));
        run.rig.cloud.codegen.add(SkillCodegenTest.finished(SkillCodegenTest.module("")));
        run.rig.cloud.think.add(respond("Its code could not be checked."));
        run.rig.loop.executeFull("kid", "make me a courier skill", false);
        assertTrue(run.lastSent().contains("could not be checked"), run.lastSent());

        assertEquals(before, run.grants(), "a skill_create that made nothing ended grants");
        for (String free : List.of("imap_move_to_bin_by_sender_gmail", "kids_mailer_v2", "courier")) {
            assertTrue(run.rig.registry.find(free).isEmpty(), free + " was made");
        }

        // Another account's skill: the owner's, which kid may not change.
        run.rig.skills.syntax = code -> null;
        run.rig.cloud.think.add(call(AgentAction.SKILL_CREATE, spec("imap_move_to_trash_by_sender", "SMTP_PASS")));
        run.rig.cloud.codegen.add(SkillCodegenTest.finished(SkillCodegenTest.module("# kid's code under the owner's grant")));
        run.rig.cloud.think.add(respond("It is not mine to change."));
        run.rig.loop.executeFull("kid", "change the trash skill", false);

        assertTrue(run.lastSent().contains("belongs to another account"), run.lastSent());
        assertFalse(run.code("imap_move_to_trash_by_sender").contains("kid's code"));
        assertEquals(before, run.grants());
        // Mutation: the test of whose skill it is skipped for a rewrite -> kid's code is written over
        // the owner's skill, and the owner's grant of it ends.
    }
}
