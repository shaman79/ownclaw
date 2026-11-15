package com.ownclaw.agent.tools;

import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.sandbox.ContainerSandbox;
import com.ownclaw.sandbox.SandboxManager;
import com.ownclaw.sandbox.SandboxResult;
import com.ownclaw.users.CredentialGrantService;
import com.ownclaw.users.CredentialVault;
import com.ownclaw.users.GrantsKit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A run of a skill, where its credentials are decided: a real skill, written and registered by the
 * real skill manager, the real vault over the migrated database, and a sandbox that starts nothing
 * and keeps what each launch was handed. A skill its account has not granted every credential it
 * declares is not started at all; one it has is handed them with its input, and nothing else is.
 */
class SkillGrantRunTest {

    static final String PASS = "run-canary-pass-63", USER = "run-canary-user-63", BOBS = "run-canary-bobs-63",
            LINK = "run-canary-link-63";

    @TempDir static Path once;
    @TempDir Path tmp;

    /** Two accounts and what their vaults hold, made once: each test works on a copy. */
    private static Path filled;

    private JdbcTemplate jdbc;
    private GrantsKit.Vault vault;
    private CredentialGrantService grants;
    private GrantsKit.Skills skills;

    @BeforeAll
    static void fill() throws Exception {
        filled = once.resolve("filled.db");
        JdbcTemplate jdbc = GrantsKit.strict(filled);
        GrantsKit.user(jdbc, "ann");
        GrantsKit.user(jdbc, "bob");
        var vault = new GrantsKit.Vault(jdbc);
        vault.storeCredential("ann", "SMTP_PASS", PASS);
        vault.storeCredential("ann", "SMTP_USER", USER);
        vault.storeCredential("ann", CredentialVault.LINK_SECRET, LINK);
        vault.storeCredential("bob", "SMTP_PASS", BOBS);
    }

    @BeforeEach
    void start() throws Exception {
        jdbc = GrantsKit.copy(filled, tmp.resolve("t.db"));
        vault = new GrantsKit.Vault(jdbc);
        grants = vault.grants();
        skills = new GrantsKit.Skills(tmp, new ToolRegistry(List.of()), vault);
        vault.decrypted.clear();
    }

    /** Every value of a vault here, in anything a launch was handed. */
    private static boolean holdsAValue(GrantsKit.Launch launch) {
        String all = launch.stdin() + launch.env();
        return all.contains(PASS) || all.contains(USER) || all.contains(BOBS) || all.contains(LINK);
    }

    // ───────────────────────── refused: nothing is started (R1) ─────────────────────────

    @Test
    @DisplayName("a skill its account has not granted its credential is not run: no process is started, nothing is decrypted, and the refusal names the skill, the credential and the command")
    void refusedStartsNothing() {
        DynamicSkill mailer = skills.create("mailer", List.of("SMTP_PASS"));

        ToolResult refused = GrantsKit.run(mailer, "ann");

        assertFalse(refused.success(), refused.output());
        assertTrue(refused.output().startsWith("Not run: 'mailer' has not been allowed to read SMTP_PASS from the vault."),
                refused.output());
        assertTrue(refused.output().contains("`/grant mailer SMTP_PASS`"), "the exact command: " + refused.output());
        assertEquals(List.of(), skills.launches.runs(), "the skill's process was started");
        assertEquals(List.of(), vault.decrypted);
        assertEquals(List.of("SMTP_PASS"), mailer.ungranted("ann"), "what whoever calls it is told first");
        // Mutation: run() goes on past a handover that names an ungranted credential -> the skill is started.
    }

    @Test
    @DisplayName("granted, the skill is started once, with its credentials in its input and none in the environment it is started with")
    void grantedRunsWithItsCredentials() {
        DynamicSkill mailer = skills.create("mailer", List.of("SMTP_USER", "SMTP_PASS"));
        grants.grant("ann", "mailer", List.of("SMTP_USER", "SMTP_PASS"));

        ToolResult ran = GrantsKit.run(mailer, "ann");

        assertTrue(ran.success(), ran.output());
        assertEquals(1, skills.launches.runs().size());
        GrantsKit.Launch run = skills.launches.runs().get(0);
        assertEquals(Map.of("SMTP_USER", USER, "SMTP_PASS", PASS), run.credentials());
        assertFalse(run.env().toString().contains(PASS) || run.env().toString().contains(USER), run.env().toString());
        assertEquals(List.of(), mailer.ungranted("ann"));
        // Mutation: run() reads no credentials (skips the vault) -> the skill is started with none.
    }

    @Test
    @DisplayName("all or nothing: with one of two declared credentials not granted the skill is not started, and only the one that waits is asked for")
    void oneRefusedStartsNothing() {
        DynamicSkill mailer = skills.create("mailer", List.of("SMTP_USER", "SMTP_PASS"));
        grants.grant("ann", "mailer", List.of("SMTP_PASS"));

        ToolResult refused = GrantsKit.run(mailer, "ann");

        assertFalse(refused.success(), refused.output());
        assertTrue(refused.output().startsWith("Not run: 'mailer' has not been allowed to read SMTP_USER from the vault."),
                refused.output());
        assertTrue(refused.output().contains("`/grant mailer SMTP_USER`"), refused.output());
        assertFalse(refused.output().contains(PASS), refused.output());
        assertEquals(List.of(), skills.launches.runs(), "it ran on half of what it declares");
        assertEquals(List.of(), vault.decrypted, "not even the granted one was decrypted");
        // Mutation: the grants read as "any one granted is all granted" -> it runs with both.
    }

    @Test
    @DisplayName("a credential the skill was granted but the vault does not hold: not started, and told to store it")
    void grantedButNotStored() {
        DynamicSkill reader = skills.create("mail_reader", List.of("SMTP_PASS", "IMAP_PASS"));
        grants.grant("ann", "mail_reader", List.of("SMTP_PASS", "IMAP_PASS"));

        ToolResult failed = GrantsKit.run(reader, "ann");

        assertFalse(failed.success());
        assertTrue(failed.output().startsWith("Missing required credentials: IMAP_PASS."), failed.output());
        assertTrue(failed.output().contains("/cred set KEY value"), failed.output());
        assertEquals(List.of(), skills.launches.runs());
        // Mutation: run() goes on past a credential the vault does not hold -> the skill is started without it.
    }

    @Test
    @DisplayName("not granted and not stored: the one refusal says both -- what to grant and what to store")
    void notGrantedAndNotStored() {
        DynamicSkill reader = skills.create("mail_reader", List.of("SMTP_PASS", "IMAP_PASS"));

        ToolResult refused = GrantsKit.run(reader, "ann");

        assertTrue(refused.output().startsWith("Not run: 'mail_reader' has not been allowed to read SMTP_PASS, IMAP_PASS "
                + "from the vault."), refused.output());
        assertTrue(refused.output().contains("`/grant mail_reader SMTP_PASS IMAP_PASS`"), refused.output());
        assertTrue(refused.output().contains("Missing required credentials: IMAP_PASS."), refused.output());
        assertEquals(List.of(), skills.launches.runs());
    }

    // ───────────────────────── each account's own (R6) ─────────────────────────

    @Test
    @DisplayName("ann's grant runs the skill for ann with ann's value; a run of the same skill for bob is not started until bob grants it, and then is handed bob's")
    void eachAccountsOwn() {
        DynamicSkill mailer = skills.create("mailer", List.of("SMTP_PASS"));
        grants.grant("ann", "mailer", List.of("SMTP_PASS"));

        ToolResult forBob = GrantsKit.run(mailer, "bob");
        assertFalse(forBob.success(), forBob.output());
        assertTrue(forBob.output().startsWith("Not run: 'mailer' has not been allowed"), forBob.output());
        assertEquals(List.of(), skills.launches.runs(), "ann's grant started a run for bob");
        assertEquals(List.of(), vault.decrypted);

        assertTrue(GrantsKit.run(mailer, "ann").success());
        assertEquals(Map.of("SMTP_PASS", PASS), skills.launches.runs().get(0).credentials());

        grants.grant("bob", "mailer", List.of("SMTP_PASS"));
        assertTrue(GrantsKit.run(mailer, "bob").success());
        assertEquals(Map.of("SMTP_PASS", BOBS), skills.launches.runs().get(1).credentials(), "bob's own value, not ann's");
        assertEquals(List.of("ann:SMTP_PASS", "bob:SMTP_PASS"), vault.decrypted);
        // Mutation: the grants of a skill read whoever made them -> bob's run is started on ann's grant.
    }

    @Test
    @DisplayName("revoked, the skill is refused again: the run before it was started, the run after it is not")
    void revokedIsRefusedAgain() {
        DynamicSkill mailer = skills.create("mailer", List.of("SMTP_PASS"));
        grants.grant("ann", "mailer", List.of("SMTP_PASS"));
        assertTrue(GrantsKit.run(mailer, "ann").success());

        grants.revoke("ann", "mailer", List.of());
        ToolResult after = GrantsKit.run(mailer, "ann");

        assertFalse(after.success());
        assertTrue(after.output().startsWith("Not run: 'mailer' has not been allowed"), after.output());
        assertEquals(1, skills.launches.runs().size(), "only the run made while it was granted");
        assertEquals(List.of("ann:SMTP_PASS"), vault.decrypted, "read once, for that run");
        // Mutation: revoke deletes nothing -> the run after it is started too.
    }

    @Test
    @DisplayName("a run for no account is handed no credential, whatever anyone granted")
    void noAccountNoCredentials() {
        DynamicSkill mailer = skills.create("mailer", List.of("SMTP_PASS"));
        grants.grant("ann", "mailer", List.of("SMTP_PASS"));

        GrantsKit.run(mailer, null);

        for (GrantsKit.Launch launch : skills.launches.runs()) {
            assertEquals(Map.of(), launch.credentials());
            assertFalse(holdsAValue(launch), launch.toString());
        }
        assertEquals(List.of(), vault.decrypted);
        assertEquals(List.of(), mailer.ungranted(null), "nobody to ask for a grant");
    }

    // ───────────────────────── a skill's name and a credential's name (R7) ─────────────────────────

    @Test
    @DisplayName("a skill written again under its name keeps its grants; a credential it declares anew waits for a grant of its own, and until then the whole skill is refused")
    void rewrittenKeepsItsGrantsAndANewCredentialWaits() {
        skills.create("mailer", List.of("SMTP_PASS"));
        grants.grant("ann", "mailer", List.of("SMTP_PASS"));

        DynamicSkill rewritten = skills.create("mailer", List.of("SMTP_PASS"));
        assertTrue(GrantsKit.run(rewritten, "ann").success(), "a repair of the skill left it refused");
        assertEquals(Map.of("SMTP_PASS", PASS), skills.launches.runs().get(0).credentials());

        DynamicSkill wider = skills.create("mailer", List.of("SMTP_PASS", "SMTP_USER"));
        vault.decrypted.clear();
        ToolResult refused = GrantsKit.run(wider, "ann");
        assertFalse(refused.success());
        assertTrue(refused.output().startsWith("Not run: 'mailer' has not been allowed to read SMTP_USER from the vault."),
                refused.output());
        assertEquals(1, skills.launches.runs().size(), "the skill ran on a credential it was never granted, or on half");
        assertEquals(List.of(), vault.decrypted);

        grants.grant("ann", "mailer", List.of("SMTP_USER"));
        assertTrue(GrantsKit.run(wider, "ann").success());
        assertEquals(Map.of("SMTP_PASS", PASS, "SMTP_USER", USER), skills.launches.runs().get(1).credentials());
        // Mutation: a skill granted one credential is granted all it declares -> the wider skill runs with SMTP_USER.
    }

    @Test
    @DisplayName("a skill deleted and made again under its name keeps its grants; another skill declaring the same credential has none")
    void deletedAndMadeAgain() {
        skills.create("mailer", List.of("SMTP_PASS"));
        grants.grant("ann", "mailer", List.of("SMTP_PASS"));
        assertEquals("Skill 'mailer' has been permanently deleted.", skills.manager.deleteSkill("mailer"));
        assertEquals(List.of("ann mailer SMTP_PASS permanent"), GrantsKit.rows(jdbc), "deleting a skill takes no grant back");

        DynamicSkill again = skills.create("mailer", List.of("SMTP_PASS"));
        DynamicSkill other = skills.create("postman", List.of("SMTP_PASS"));

        assertTrue(GrantsKit.run(again, "ann").success());
        assertFalse(GrantsKit.run(other, "ann").success(), "a grant is for the skill's name");
        assertEquals(1, skills.launches.runs().size());
    }

    // ───────────────────────── names (the adversarial pass) ─────────────────────────

    @Test
    @DisplayName("a credential declared as one string, in another case, with blanks or twice is the names in it: each needs its grant, and granted, each is handed over once under the vault's name")
    void declaredNamesAreReadOnce() {
        DynamicSkill mailer = skills.create("mailer", "[smtp_pass, SMTP_USER , smtp_pass]");
        assertEquals(List.of("SMTP_PASS", "SMTP_USER", "SMTP_PASS"), mailer.requiredCredentials());
        assertEquals(List.of("SMTP_PASS", "SMTP_USER"), mailer.ungranted("ann"), "each once");

        assertFalse(GrantsKit.run(mailer, "ann").success());
        grants.grant("ann", "mailer", List.of("SMTP_PASS"));
        assertFalse(GrantsKit.run(mailer, "ann").success(), "one of the names in the string is still not granted");
        assertEquals(List.of(), skills.launches.runs());

        grants.grant("ann", "mailer", List.of("smtp_user"));
        assertTrue(GrantsKit.run(mailer, "ann").success());
        assertEquals(Map.of("SMTP_PASS", PASS, "SMTP_USER", USER), skills.launches.runs().get(0).credentials());
        // Mutation: names compared as written -> granting smtp_user grants nothing, and the skill is never started.
    }

    @Test
    @DisplayName("the link-signing secret is handed to no skill: one that declares it is not started and nothing is decrypted, even where a row grants it -- as the /grant of before this check, which took any name, could write one")
    void theLinkSecretIsNeverHandedOver() {
        DynamicSkill signer = skills.create("signer", List.of("SMTP_PASS", CredentialVault.LINK_SECRET));
        grants.grant("ann", "signer", List.of("SMTP_PASS"));
        jdbc.update("INSERT INTO credential_grants (user_id, skill_name, credential, grant_type) "
                + "VALUES ('ann', 'signer', ?, 'permanent')", CredentialVault.LINK_SECRET);

        ToolResult refused = GrantsKit.run(signer, "ann");

        assertFalse(refused.success());
        assertTrue(refused.output().contains(CredentialVault.LINK_SECRET + " is never handed to a skill"), refused.output());
        assertEquals(List.of(), skills.launches.runs());
        assertEquals(List.of(), vault.decrypted);
        // Mutation: run() without its own refusal of the link secret -> with the row, it is handed over.
    }

    // ───────────────────────── every way a skill's code is started ─────────────────────────

    @Test
    @DisplayName("the checks of a skill_create start the skill's code with no input and no credential, granted or not")
    void theChecksOfACreateAreHandedNothing() {
        grants.grant("ann", "mailer", List.of("SMTP_PASS"));
        skills.create("mailer", List.of("SMTP_PASS"));
        skills.create("mailer", List.of("SMTP_PASS"));

        List<GrantsKit.Launch> checks = skills.launches.all.stream().filter(launch -> !launch.isRun()).toList();
        assertTrue(checks.stream().anyMatch(c -> c.script().getFileName().toString().startsWith("_load_check_")),
                "the load check imports skill.py: " + checks);
        for (GrantsKit.Launch check : checks) {
            assertNull(check.stdin(), "a check was handed input: " + check.script().getFileName());
            assertFalse(holdsAValue(check), check.toString());
        }
        assertEquals(List.of(), vault.decrypted);
        // Mutation: the load check is handed an input -> its stdin is no longer null.
    }

    @Test
    @DisplayName("a self-healed retry is the run again: handed exactly what the run was handed, the vault read once -- and a refused skill has neither an install nor a retry")
    void aSelfHealedRetry() {
        DynamicSkill mailer = skills.create("mailer", List.of("SMTP_PASS"));
        skills.launches.answer = launch -> skills.launches.runs().size() == 1
                ? new SandboxResult(1, "", "ModuleNotFoundError: No module named 'requests'\n", 1, false)
                : new SandboxResult(0, "{\"success\": true, \"output\": \"sent\"}", "", 1, false);

        assertFalse(GrantsKit.run(mailer, "ann").success());
        assertEquals(List.of(), skills.launches.runs(), "refused: nothing to heal");
        assertEquals(List.of(), skills.installed);

        grants.grant("ann", "mailer", List.of("SMTP_PASS"));
        ToolResult healed = GrantsKit.run(mailer, "ann");

        assertTrue(healed.success(), healed.output());
        assertEquals(List.of(List.of("requests")), skills.installed);
        List<GrantsKit.Launch> runs = skills.launches.runs();
        assertEquals(2, runs.size());
        assertEquals(Map.of("SMTP_PASS", PASS), runs.get(0).credentials());
        assertEquals(runs.get(0).stdin(), runs.get(1).stdin(), "the retry's input is the run's");
        assertEquals(List.of("ann:SMTP_PASS"), vault.decrypted, "one read of the vault, by forSkill, for both");
        // Mutation: the retry is started with another input than the run's -> the two differ.
    }

    /** A container runtime that builds and starts nothing: it keeps each image asked for and each run's input. */
    static final class Containers extends ContainerSandbox {
        final List<String> images = new ArrayList<>();
        final List<String> stdin = new ArrayList<>();

        Containers() {
            super(new OwnClawConfig());
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public String ensureImage(List<String> systemPackages, String pipRequirements, Path skillDir, String preferredImage,
                                  SandboxManager.ProgressCallback progress, BooleanSupplier stopped) {
            images.add(String.join(",", systemPackages));
            return "localhost/test-image";
        }

        @Override
        public SandboxResult execute(String imageTag, String python, Path script, Path skillDir, String stdinJson,
                                     int timeoutSec, SandboxManager.ProgressCallback progress, Map<String, String> volumes) {
            stdin.add(stdinJson);
            return new SandboxResult(0, "{\"success\": true, \"output\": \"scanned\"}", "", 1, false);
        }
    }

    @Test
    @DisplayName("a skill that runs in a container is refused the same way: no image is built and no container started until its credential is granted")
    void aContainerRun() throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("skills/generated/port_scan"));
        Files.writeString(dir.resolve("skill.py"), "def run(params):\n    return {'output': 'scanned'}\n");
        var containers = new Containers();
        var scan = new DynamicSkill("port_scan", "scans ports", Map.of(), dir, true, false, 30, skills.launches,
                skills.python, List.of("SMTP_PASS"), vault, List.of("nmap"), null, containers, null);

        ToolResult refused = GrantsKit.run(scan, "ann");
        assertFalse(refused.success(), refused.output());
        assertTrue(refused.output().startsWith("Not run: 'port_scan' has not been allowed"), refused.output());
        assertEquals(List.of(), containers.images, "an image was built for a run that was refused");
        assertEquals(List.of(), containers.stdin);
        assertEquals(List.of(), skills.launches.all);

        grants.grant("ann", "port_scan", List.of("SMTP_PASS"));
        assertTrue(GrantsKit.run(scan, "ann").success());
        assertEquals(1, containers.stdin.size());
        assertTrue(containers.stdin.get(0).contains("\"SMTP_PASS\":\"" + PASS + "\""), containers.stdin.get(0));
        assertEquals(List.of(), skills.launches.all, "it ran in the container, not on the host");
        // Mutation: as refusedStartsNothing's -> the image is built and the container started.
    }
}
