package com.ownclaw.agent.tools;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ownclaw.conversation.MigratedDatabase;
import com.ownclaw.users.CredentialVault;
import com.ownclaw.users.GrantsKit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * What works today goes on working, once: the first start of the release that checks grants
 * gives each skill there is the credentials it was being handed -- each one it declares, to
 * every account whose vault holds one of that name -- and no start after it gives anything.
 * The real registry over a skills directory on disk, the real vault, and the migrated database
 * with foreign keys on, as production opens it.
 */
class GrantExistingSkillsTest {

    static final String PASS = "start-canary-pass-28", USER = "start-canary-user-28", BOBS = "start-canary-bobs-28";

    @TempDir static Path once;
    @TempDir Path tmp;

    /**
     * Three accounts, as changeset 028 finds them: ann's vault holds the mail credentials, the
     * link secret and one no skill declares; bob's the password alone; cyd's nothing.
     */
    private static Path filled;

    private JdbcTemplate jdbc;
    private GrantsKit.Vault vault;
    private GrantsKit.Skills skills;

    @BeforeAll
    static void fill() throws Exception {
        filled = once.resolve("filled.db");
        JdbcTemplate jdbc = GrantsKit.strict(filled);
        for (String account : List.of("ann", "bob", "cyd")) GrantsKit.user(jdbc, account);
        var vault = new GrantsKit.Vault(jdbc);
        vault.storeCredential("ann", "SMTP_PASS", PASS);
        vault.storeCredential("ann", "SMTP_USER", USER);
        vault.storeCredential("ann", CredentialVault.LINK_SECRET, "start-canary-link-28");
        vault.storeCredential("ann", "BACKUP_KEY", "start-canary-backup-28");
        vault.storeCredential("bob", "SMTP_PASS", BOBS);
    }

    @BeforeEach
    void start() throws Exception {
        jdbc = GrantsKit.copy(filled, tmp.resolve("t.db"));
        vault = new GrantsKit.Vault(jdbc);
        skills = new GrantsKit.Skills(tmp, new ToolRegistry(List.of()), vault);
        vault.decrypted.clear();
    }

    /** A start: a new registry over the same directory, tools, vault and database. */
    private DynamicSkillRegistry restart() {
        DynamicSkillRegistry registry = skills.registry();
        registry.init();
        return registry;
    }

    private static boolean done(String value) {
        return value != null && value.matches("done \\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");
    }

    // ───────────────────────── the first start ─────────────────────────

    @Test
    @DisplayName("changeset 028 asks for it: a migrated database holds the row, pending, and has granted nothing by itself")
    void theChangesetAsks() {
        assertEquals("pending", GrantsKit.existing(jdbc));
        assertEquals(List.of(), GrantsKit.rows(jdbc));
        // Mutation: the changeset left out of the master changelog -> no row, and no start grants anything.
    }

    @Test
    @DisplayName("an upgrade keeps what /grant wrote before the check, and the row asks for the rest")
    void anUpgradeKeepsWhatWasGrantedByHand() throws Exception {
        Path db = tmp.resolve("upgraded.db");
        JdbcTemplate before = MigratedDatabase.before(db, "028-credential-grants-checked.sql");
        before.update("INSERT INTO users (id, display_name) VALUES ('ann', 'Ann')");
        before.update("INSERT INTO credential_grants (user_id, skill_name, credential, grant_type) "
                + "VALUES ('ann', 'old_mailer', 'SMTP_PASS', 'permanent')");
        assertNull(GrantsKit.existing(before), "the release before had no such row");

        JdbcTemplate after = GrantsKit.strict(db);

        assertEquals("pending", GrantsKit.existing(after));
        assertEquals(List.of("ann old_mailer SMTP_PASS permanent"), GrantsKit.rows(after));
    }

    @Test
    @DisplayName("the first start grants each skill there is each credential it declares, to every account whose vault holds one of that name -- and to no other; then the row says when it was done")
    void theFirstStartGrantsWhatWasBeingHandedOver() throws Exception {
        skills.onDisk("mailer", "[SMTP_USER, SMTP_PASS]");
        skills.onDisk("mail_digest", "[IMAP_PASS, SMTP_PASS]");
        skills.onDisk("clock", null);

        DynamicSkillRegistry registry = restart();

        assertEquals(List.of(
                "ann mail_digest SMTP_PASS permanent",
                "ann mailer SMTP_PASS permanent",
                "ann mailer SMTP_USER permanent",
                "bob mail_digest SMTP_PASS permanent",
                "bob mailer SMTP_PASS permanent"), GrantsKit.rows(jdbc),
                "per vault owner: cyd stored nothing, bob no SMTP_USER, nobody an IMAP_PASS; BACKUP_KEY no skill declares");
        assertTrue(done(GrantsKit.existing(jdbc)), GrantsKit.existing(jdbc));
        assertEquals(List.of(), vault.decrypted, "granting reads no value");

        // What worked goes on working: ann's mail is sent with ann's credentials.
        DynamicSkill mailer = registry.getDynamic("mailer").orElseThrow();
        assertTrue(GrantsKit.run(mailer, "ann").success());
        assertEquals(Map.of("SMTP_USER", USER, "SMTP_PASS", PASS), skills.launches.runs().get(0).credentials());
        // What did not work does not start to: bob's vault never held SMTP_USER, cyd's nothing.
        assertFalse(GrantsKit.run(mailer, "bob").success());
        assertFalse(GrantsKit.run(mailer, "cyd").success());
        assertEquals(1, skills.launches.runs().size());
        // Mutations: init() does not ask -> no grant, and ann's mailer is refused; granted to every
        // account whatever its vault holds -> cyd and IMAP_PASS are granted; the row left pending -> not done.
    }

    @Test
    @DisplayName("a credential named in SKILL.yaml as one string, in lower case or with blanks is granted under the vault's name, each name in it")
    void namesInSkillYamlAreTheVaultsNames() throws Exception {
        skills.onDisk("mailer", "\"[smtp_user, SMTP_PASS]\"");
        skills.onDisk("postman", "[ smtp_pass ]");

        restart();

        assertEquals(List.of(
                "ann mailer SMTP_PASS permanent",
                "ann mailer SMTP_USER permanent",
                "ann postman SMTP_PASS permanent",
                "bob mailer SMTP_PASS permanent",
                "bob postman SMTP_PASS permanent"), GrantsKit.rows(jdbc));
    }

    @Test
    @DisplayName("the link-signing secret is granted to no skill, though one declares it and a vault holds it")
    void theLinkSecretIsNotGranted() throws Exception {
        skills.onDisk("signer", "[" + CredentialVault.LINK_SECRET + ", SMTP_USER]");

        restart();

        assertEquals(List.of("ann signer SMTP_USER permanent"), GrantsKit.rows(jdbc));
        // Mutation: grantExisting without its forSkills test -> ann signer GUARDIAN_LINK_SECRET is granted.
    }

    @Test
    @DisplayName("a grant made by hand before the first start stays as it is, once")
    void aGrantMadeBeforeStays() throws Exception {
        skills.onDisk("mailer", "[SMTP_PASS]");
        jdbc.update("INSERT INTO credential_grants (user_id, skill_name, credential, grant_type, granted_at) "
                + "VALUES ('ann', 'mailer', 'SMTP_PASS', 'permanent', '2026-03-01 08:00:00')");
        jdbc.update("INSERT INTO credential_grants (user_id, skill_name, credential, grant_type) "
                + "VALUES ('cyd', 'gone_skill', 'WIFI_PSK', 'permanent')");

        restart();

        assertEquals(List.of("ann mailer SMTP_PASS permanent", "bob mailer SMTP_PASS permanent",
                "cyd gone_skill WIFI_PSK permanent"), GrantsKit.rows(jdbc));
        assertEquals("2026-03-01 08:00:00", jdbc.queryForObject("SELECT granted_at FROM credential_grants "
                + "WHERE user_id = 'ann' AND skill_name = 'mailer'", String.class));
    }

    // ───────────────────────── once ─────────────────────────

    @Test
    @DisplayName("a skill made after the first start is granted by no restart and no reload: its account grants it, or nobody")
    void aSkillMadeLaterIsNeverGrantedByAStart() throws Exception {
        skills.onDisk("mailer", "[SMTP_PASS]");
        restart();
        String when = GrantsKit.existing(jdbc);
        List<String> first = GrantsKit.rows(jdbc);
        assertEquals(List.of("ann mailer SMTP_PASS permanent", "bob mailer SMTP_PASS permanent"), first);

        skills.create("postman", List.of("SMTP_PASS"));
        skills.registry.reload();
        DynamicSkillRegistry again = restart();
        restart();

        assertEquals(first, GrantsKit.rows(jdbc), "a restart granted a skill made after the first start");
        assertEquals(when, GrantsKit.existing(jdbc), "done once, and not written again");
        ToolResult refused = GrantsKit.run(again.getDynamic("postman").orElseThrow(), "ann");
        assertFalse(refused.success());
        assertTrue(refused.output().contains("`/grant postman SMTP_PASS`"), refused.output());
        assertEquals(List.of(), skills.launches.runs());
        // Mutation: grantExisting whatever the row says -> the restart grants postman.
    }

    @Test
    @DisplayName("a credential a skill declares after the first start is granted by no restart, and the skill is refused until its account grants that one")
    void aCredentialDeclaredLaterIsNeverGrantedByAStart() throws Exception {
        skills.onDisk("mailer", "[SMTP_PASS]");
        restart();

        skills.create("mailer", List.of("SMTP_PASS", "SMTP_USER"));
        DynamicSkillRegistry again = restart();

        assertEquals(List.of("ann mailer SMTP_PASS permanent", "bob mailer SMTP_PASS permanent"), GrantsKit.rows(jdbc));
        ToolResult refused = GrantsKit.run(again.getDynamic("mailer").orElseThrow(), "ann");
        assertTrue(refused.output().startsWith("Not run: 'mailer' has not been allowed to read SMTP_USER from the vault."),
                refused.output());
        assertEquals(List.of(), skills.launches.runs());
        assertEquals(List.of(), vault.decrypted);
    }

    @Test
    @DisplayName("a credential stored after the first start, for a skill that declared it all along, is granted by no restart either")
    void aCredentialStoredLaterIsNeverGrantedByAStart() throws Exception {
        skills.onDisk("mail_digest", "[SMTP_PASS, IMAP_PASS]");
        restart();
        assertEquals(List.of("ann mail_digest SMTP_PASS permanent", "bob mail_digest SMTP_PASS permanent"),
                GrantsKit.rows(jdbc));

        vault.storeCredential("ann", "IMAP_PASS", "stored-after-the-first-start");
        vault.storeCredential("cyd", "SMTP_PASS", "cyds-stored-after");
        vault.decrypted.clear();
        DynamicSkillRegistry again = restart();

        assertEquals(List.of("ann mail_digest SMTP_PASS permanent", "bob mail_digest SMTP_PASS permanent"),
                GrantsKit.rows(jdbc));
        DynamicSkill digest = again.getDynamic("mail_digest").orElseThrow();
        assertTrue(GrantsKit.run(digest, "ann").output().contains("`/grant mail_digest IMAP_PASS`"));
        assertTrue(GrantsKit.run(digest, "cyd").output().contains("`/grant mail_digest SMTP_PASS IMAP_PASS`"));
        assertEquals(List.of(), skills.launches.runs());
        assertEquals(List.of(), vault.decrypted);
    }

    @Test
    @DisplayName("with no skills directory at all the row is still marked done: the skills made afterwards are granted by no later start")
    void noSkillsDirectory() throws Exception {
        assertFalse(Files.exists(skills.generated));

        restart();

        assertTrue(done(GrantsKit.existing(jdbc)), GrantsKit.existing(jdbc));
        assertEquals(List.of(), GrantsKit.rows(jdbc));

        skills.onDisk("mailer", "[SMTP_PASS]");
        restart();
        assertEquals(List.of(), GrantsKit.rows(jdbc), "the first start had no skills: the second is not the first");
        // Mutation: init() returns before it asks when there is no directory -> the row stays pending,
        // and the second start grants mailer.
    }

    @Test
    @DisplayName("with no row nothing is granted, and no row is made")
    void noRow() throws Exception {
        jdbc.update("DELETE FROM system_settings WHERE key = 'credential_grants.existing'");
        skills.onDisk("mailer", "[SMTP_PASS]");

        restart();

        assertEquals(List.of(), GrantsKit.rows(jdbc));
        assertNull(GrantsKit.existing(jdbc));
    }

    // ───────────────────────── a start that died half way ─────────────────────────

    @Test
    @DisplayName("asked twice while pending -- a start that died before it wrote the row -- every grant is there once and none is lost")
    void twiceWhilePending() throws Exception {
        skills.onDisk("mailer", "[SMTP_USER, SMTP_PASS]");
        skills.onDisk("postman", "[SMTP_PASS]");
        restart();
        List<String> first = GrantsKit.rows(jdbc);
        assertEquals(5, first.size(), first.toString());
        // The first start died before the row was written; what it had granted is there.
        jdbc.update("UPDATE system_settings SET value = 'pending' WHERE key = 'credential_grants.existing'");
        jdbc.update("DELETE FROM credential_grants WHERE skill_name = 'postman'");

        restart();

        assertEquals(first, GrantsKit.rows(jdbc), "each once, the ones the dead start did not reach too");
        assertEquals(5, jdbc.queryForObject("SELECT COUNT(*) FROM credential_grants", Integer.class));
        assertTrue(done(GrantsKit.existing(jdbc)));
        // Mutation: a plain INSERT -> the second start fails on the grants the first made.
    }

    @Test
    @DisplayName("a start that fails half way leaves the row pending, and is not swallowed: the next start grants the rest")
    void aFailedStartIsFinishedByTheNext() throws Exception {
        skills.onDisk("mailer", "[SMTP_USER, SMTP_PASS]");
        // The database refuses the second grant, as a full disk does. Until the reviews of the
        // release the failure here was a vault row of an account that is gone, refused on the
        // grant's foreign key: that row fails no start any more (aVaultRowOfAnAccountThatIsGone).
        jdbc.execute("CREATE TRIGGER the_disk_is_full BEFORE INSERT ON credential_grants "
                + "WHEN NEW.credential = 'SMTP_PASS' BEGIN SELECT RAISE(ABORT, 'database or disk is full'); END");

        assertThrows(DataAccessException.class, this::restart, "a start that could not grant must not go on as if it had");

        assertEquals("pending", GrantsKit.existing(jdbc), "written last: what was not finished is asked again");
        assertEquals(List.of("ann mailer SMTP_USER permanent"), GrantsKit.rows(jdbc), "the half it reached");

        jdbc.execute("DROP TRIGGER the_disk_is_full");
        restart();

        assertEquals(List.of("ann mailer SMTP_PASS permanent", "ann mailer SMTP_USER permanent",
                "bob mailer SMTP_PASS permanent"), GrantsKit.rows(jdbc));
        assertTrue(done(GrantsKit.existing(jdbc)));
        // Mutations: the row written before the grants -> the failed start is recorded as done,
        // and the next grants nothing; the failure caught at the start -> nothing is thrown.
    }

    @Test
    @DisplayName("a vault row whose account is gone fails no start: it is passed over, every account there is gets its grants, and the row says done")
    void aVaultRowOfAnAccountThatIsGone() throws Exception {
        skills.onDisk("mailer", "[SMTP_USER, SMTP_PASS]");
        // Written with foreign keys off, as a database from before they were on can hold one. A
        // grant for it is refused by the database -- and OR IGNORE does not pass over that -- so
        // it used to fail the statement, the start, and every start after it.
        MigratedDatabase.at(tmp.resolve("t.db")).update("INSERT INTO credential_vault (user_id, credential_key, "
                + "encrypted_value, iv) VALUES ('ghost', 'SMTP_PASS', 'x', 'y')");

        DynamicSkillRegistry registry = restart();

        assertEquals(List.of("ann mailer SMTP_PASS permanent", "ann mailer SMTP_USER permanent",
                "bob mailer SMTP_PASS permanent"), GrantsKit.rows(jdbc), "everyone there is, and nobody who is not");
        assertTrue(done(GrantsKit.existing(jdbc)), GrantsKit.existing(jdbc));
        assertTrue(GrantsKit.run(registry.getDynamic("mailer").orElseThrow(), "ann").success());
        // Mutation: granted to the account of every vault row, as before -> the database refuses
        // the ghost's grant on its foreign key, and the start fails.
    }

    // ───────────────────────── a directory that cannot be read ─────────────────────────

    @Test
    @DisplayName("a skills directory that is there and cannot be read leaves the one-time grant asked for: nothing is granted for no skill, and the next start that reads it grants -- once")
    void aDirectoryThatCannotBeReadLeavesItAsked() throws Exception {
        skills.onDisk("mailer", "[SMTP_PASS]");
        var readable = Files.getPosixFilePermissions(skills.generated);
        var logged = new ListAppender<ILoggingEvent>();
        logged.start();
        var registryLog = (Logger) LoggerFactory.getLogger(DynamicSkillRegistry.class);
        registryLog.addAppender(logged);
        Files.setPosixFilePermissions(skills.generated, PosixFilePermissions.fromString("---------"));
        try {
            assumeFalse(Files.isReadable(skills.generated), "run as root, every directory can be listed");
            assertTrue(Files.isDirectory(skills.generated), "the premise: it is there, as a missing one is not");

            DynamicSkillRegistry blind = restart();

            assertEquals("pending", GrantsKit.existing(jdbc), "marked done for no skill, the grant would be used up");
            assertEquals(List.of(), GrantsKit.rows(jdbc));
            assertTrue(blind.allDynamic().isEmpty(), "nothing was read");
            assertTrue(logged.list.stream().anyMatch(event -> event.getLevel() == Level.ERROR
                            && event.getFormattedMessage().contains("could not be read")
                            && event.getFormattedMessage().contains("not granted at this start")),
                    "an error says why every skill that holds a credential is refused until the next start: " + logged.list);
        } finally {
            Files.setPosixFilePermissions(skills.generated, readable);
            registryLog.detachAppender(logged);
        }

        restart();

        assertEquals(List.of("ann mailer SMTP_PASS permanent", "bob mailer SMTP_PASS permanent"), GrantsKit.rows(jdbc),
                "the start that reads the skills grants what they were being handed");
        assertTrue(done(GrantsKit.existing(jdbc)), GrantsKit.existing(jdbc));

        // And once: what is made after that start is granted by none.
        skills.create("postman", List.of("SMTP_PASS"));
        restart();
        assertEquals(2, GrantsKit.rows(jdbc).size(), GrantsKit.rows(jdbc).toString());
        // Mutations: asked whatever the reading of the directory came to, as before -> the blind
        // start marks it done, and the one that reads mailer grants nothing; the error not logged
        // -> nothing says why.
    }

    @Test
    @DisplayName("a skills directory that cannot be reached -- its parent cannot be searched -- is not taken for none: the one-time grant stays asked for, and the next start that reaches it grants")
    void aDirectoryThatCannotBeReachedLeavesItAsked() throws Exception {
        skills.onDisk("mailer", "[SMTP_PASS]");
        Path parent = skills.generated.getParent();
        var open = Files.getPosixFilePermissions(parent);
        Files.setPosixFilePermissions(parent, PosixFilePermissions.fromString("---------"));
        try {
            assumeFalse(Files.isExecutable(parent), "run as root, every directory can be searched");
            assertFalse(Files.isDirectory(skills.generated), "the premise: to this start it is no directory, as a missing one is not");

            DynamicSkillRegistry blind = restart();

            assertEquals("pending", GrantsKit.existing(jdbc), "marked done for no skill, the grant would be used up");
            assertEquals(List.of(), GrantsKit.rows(jdbc));
            assertTrue(blind.allDynamic().isEmpty(), "nothing was read");
        } finally {
            Files.setPosixFilePermissions(parent, open);
        }

        restart();

        assertEquals(List.of("ann mailer SMTP_PASS permanent", "bob mailer SMTP_PASS permanent"), GrantsKit.rows(jdbc),
                "the start that reaches the skills grants what they were being handed");
        assertTrue(done(GrantsKit.existing(jdbc)), GrantsKit.existing(jdbc));
        // Mutation: whatever is no directory taken for no skills, as before -> the blind start marks
        // it done, and the one that reaches mailer grants nothing.
    }

    @Test
    @DisplayName("a skill that could not be loaded at the first start was not registered, and is granted nothing when it is brought back")
    void aSkillThatDidNotLoadIsNotGranted() throws Exception {
        skills.onDisk("mailer", "[SMTP_PASS]");
        Path broken = skills.onDisk("postman", "[SMTP_PASS]");
        Files.writeString(broken.resolve("SKILL.yaml"), "name: postman\ndescription: [unclosed\n");

        DynamicSkillRegistry registry = restart();

        assertEquals(List.of("ann mailer SMTP_PASS permanent", "bob mailer SMTP_PASS permanent"), GrantsKit.rows(jdbc),
                "one skill that does not load does not stop the others being granted");
        assertTrue(done(GrantsKit.existing(jdbc)));
        assertEquals(1, registry.quarantined().size(), registry.quarantined().toString());
    }
}
