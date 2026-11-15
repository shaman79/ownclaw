package com.ownclaw.interfaces.web;

import com.ownclaw.agent.tools.ToolRegistry;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.users.AuthService;
import com.ownclaw.users.GrantsKit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A test run from the Skills page starts a skill's code beside the loop: nobody asks before it,
 * so what decides is the run itself. It is a run for the owner, with the owner's vault -- and
 * the owner's grants.
 */
class SkillTestRunGrantTest {

    static final String PASS = "page-canary-pass-19";

    @Test
    @DisplayName("a test run of a skill the owner has not granted its credential starts nothing and says why with the command; granted, it runs with the owner's value, which the page is not shown")
    void aTestRunIsTheOwnersRun(@TempDir Path tmp) throws Exception {
        var jdbc = GrantsKit.strict(tmp.resolve("t.db"));
        GrantsKit.user(jdbc, "owner");
        var vault = new GrantsKit.Vault(jdbc);
        vault.storeCredential("owner", "SMTP_PASS", PASS);
        vault.decrypted.clear();
        var skills = new GrantsKit.Skills(tmp, new ToolRegistry(List.of()), vault);
        skills.create("mailer", List.of("SMTP_PASS"));
        skills.launches.answer = launch -> new com.ownclaw.sandbox.SandboxResult(0,
                "{\"success\": true, \"output\": \"logged in with " + PASS + "\"}", "", 1, false);
        var auth = new AuthService(null, null, new OwnClawConfig()) {
            @Override public boolean isOwner(String userId) { return "owner".equals(userId); }
        };
        var page = new SkillController(skills.manager, skills.registry, null, auth);
        Map<String, Object> body = Map.of("params", Map.of("to", "owner@example.org"));

        Map<String, Object> refused = page.runSkill("mailer", body, "owner").getBody();

        assertEquals(false, refused.get("success"), String.valueOf(refused));
        String said = String.valueOf(refused.get("output"));
        assertTrue(said.startsWith("Not run: 'mailer' has not been allowed to read SMTP_PASS from the vault."), said);
        assertTrue(said.contains("`/grant mailer SMTP_PASS`"), said);
        assertEquals(List.of(), skills.launches.runs(), "the test run started the skill");
        assertEquals(List.of(), vault.decrypted);

        vault.grants().grant("owner", "mailer", List.of("SMTP_PASS"));
        Map<String, Object> ran = page.runSkill("mailer", body, "owner").getBody();

        assertEquals(true, ran.get("success"), String.valueOf(ran));
        assertEquals(1, skills.launches.runs().size());
        assertEquals(Map.of("SMTP_PASS", PASS), skills.launches.runs().get(0).credentials());
        assertFalse(String.valueOf(ran.get("output")).contains(PASS), "the page was shown the value: " + ran);

        assertEquals(403, page.runSkill("mailer", body, "kid").getStatusCode().value(), "another account runs nothing here");
        assertEquals(1, skills.launches.runs().size());
        // Mutation: run() goes on past an ungranted handover -> the refused test run starts the skill.
    }
}
