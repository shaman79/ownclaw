package com.ownclaw.users;

import com.ownclaw.conversation.MigratedDatabase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Which skill may read which credential of which account, at the one place a skill's credentials
 * are read ({@link CredentialVault#forSkill}) and in the rows behind it
 * ({@link CredentialGrantService}): the real vault and the real service over the migrated
 * database, opened as production opens it -- foreign keys on.
 * <p>
 * A skill is handed a value only when the account it runs for granted that skill that credential;
 * one it was not granted is not decrypted; with one refused, none is handed over. A grant is an
 * account's own, for a skill's name and a credential's name, exactly.
 */
class CredentialGrantsTest {

    static final String PASS = "grant-canary-pass-41", USER = "grant-canary-user-41", HOST = "mail.example.org";

    @TempDir static Path once;
    @TempDir Path tmp;

    /** Two accounts and what their vaults hold, made once: each test works on a copy. */
    private static Path filled;

    private JdbcTemplate jdbc;
    private GrantsKit.Vault vault;
    private CredentialGrantService grants;

    @BeforeAll
    static void fill() throws Exception {
        filled = once.resolve("filled.db");
        JdbcTemplate jdbc = GrantsKit.strict(filled);
        GrantsKit.user(jdbc, "ann");
        GrantsKit.user(jdbc, "bob");
        var vault = new GrantsKit.Vault(jdbc);
        vault.storeCredential("ann", "SMTP_PASS", PASS);
        vault.storeCredential("ann", "SMTP_USER", USER);
        vault.storeCredential("ann", "SMTP_HOST", HOST);
        vault.storeCredential("bob", "SMTP_PASS", "bobs-own-pass-77");
    }

    @BeforeEach
    void start() throws Exception {
        jdbc = GrantsKit.copy(filled, tmp.resolve("t.db"));
        vault = new GrantsKit.Vault(jdbc);
        grants = vault.grants();
        vault.decrypted.clear();
    }

    // ───────────────────────── the one place a skill's credentials are read (R1) ─────────────────────────

    @Test
    @DisplayName("a skill is handed the credentials it declares when its account granted it each of them")
    void grantedIsHandedOver() {
        grants.grant("ann", "mailer", List.of("SMTP_PASS", "SMTP_USER"));

        var handed = vault.forSkill("ann", "mailer", List.of("SMTP_PASS", "SMTP_USER"));

        assertEquals(Map.of("SMTP_PASS", PASS, "SMTP_USER", USER), handed.values());
        assertEquals(List.of(), handed.ungranted());
        assertEquals(List.of(), handed.missing());
        // Mutation: forSkill returns no values at all -> nothing is handed over.
    }

    @Test
    @DisplayName("a credential the account did not grant the skill is not handed over, and not even decrypted")
    void notGrantedIsNotDecrypted() {
        var handed = vault.forSkill("ann", "mailer", List.of("SMTP_PASS"));

        assertEquals(Map.of(), handed.values());
        assertEquals(List.of("SMTP_PASS"), handed.ungranted());
        assertEquals(List.of(), handed.missing(), "the vault holds it: only the grant is missing");
        assertEquals(List.of(), vault.decrypted, "nothing was read from the vault's values");
        // Mutations: forSkill reads the values before it asks for the grants -> SMTP_PASS was decrypted;
        // forSkill without the grants check -> the value is handed over.
    }

    @Test
    @DisplayName("all or nothing: with one declared credential not granted, no value is handed over -- not the granted ones either -- and none is decrypted")
    void oneRefusedIsAllRefused() {
        grants.grant("ann", "mailer", List.of("SMTP_PASS", "SMTP_HOST"));

        var handed = vault.forSkill("ann", "mailer", List.of("SMTP_PASS", "SMTP_USER", "SMTP_HOST"));

        assertEquals(Map.of(), handed.values(), "a skill does not run on half of what it declares");
        assertEquals(List.of("SMTP_USER"), handed.ungranted(), "only the one that waits for a grant is named");
        assertEquals(List.of(), vault.decrypted);
        // Mutation: forSkill hands over the granted ones beside the refusal -> SMTP_PASS and SMTP_HOST are in values.
    }

    @Test
    @DisplayName("granted but not stored: the name is said to be missing, and the ones the vault does hold are not handed over without it")
    void grantedButNotStored() {
        grants.grant("ann", "mailer", List.of("SMTP_PASS", "IMAP_PASS"));

        var handed = vault.forSkill("ann", "mailer", List.of("SMTP_PASS", "IMAP_PASS"));

        assertEquals(Map.of(), handed.values());
        assertEquals(List.of(), handed.ungranted());
        assertEquals(List.of("IMAP_PASS"), handed.missing());
        // Mutation: hand over what was found whatever is missing -> SMTP_PASS is in values.
    }

    @Test
    @DisplayName("refused, the handover still says which names the vault does not hold, so its account is told both at once -- without decrypting any")
    void refusedSaysWhatIsMissingToo() {
        grants.grant("ann", "mailer", List.of("SMTP_PASS"));

        var handed = vault.forSkill("ann", "mailer", List.of("SMTP_PASS", "IMAP_PASS", "IMAP_USER"));

        assertEquals(Map.of(), handed.values());
        assertEquals(List.of("IMAP_PASS", "IMAP_USER"), handed.ungranted());
        assertEquals(List.of("IMAP_PASS", "IMAP_USER"), handed.missing());
        assertEquals(List.of(), vault.decrypted);
    }

    @Test
    @DisplayName("a run for no account is handed nothing: there is no vault to read and no one who granted")
    void noAccountNoValues() {
        grants.grant("ann", "mailer", List.of("SMTP_PASS"));

        var handed = vault.forSkill(null, "mailer", List.of("SMTP_PASS"));

        assertEquals(Map.of(), handed.values());
        assertEquals(List.of("SMTP_PASS"), handed.missing());
        assertEquals(List.of(), grants.ungranted(null, "mailer", List.of("SMTP_PASS")), "nobody to ask for a grant");
    }

    // ───────────────────────── an account's own (R6) ─────────────────────────

    @Test
    @DisplayName("a grant is its account's: ann's grant never lets a run for bob read bob's credential, and ann's run is handed ann's value")
    void aGrantIsItsAccountsOwn() {
        grants.grant("ann", "mailer", List.of("SMTP_PASS"));

        var forBob = vault.forSkill("bob", "mailer", List.of("SMTP_PASS"));
        assertEquals(Map.of(), forBob.values(), "bob granted nothing");
        assertEquals(List.of("SMTP_PASS"), forBob.ungranted());
        assertEquals(List.of(), vault.decrypted, "bob's value was not decrypted");

        assertEquals(Map.of("SMTP_PASS", PASS), vault.forSkill("ann", "mailer", List.of("SMTP_PASS")).values());
        assertEquals(List.of("ann:SMTP_PASS"), vault.decrypted, "ann's, for ann, and no other");
        assertEquals(Map.of("mailer", List.of("SMTP_PASS")), grants.all("ann"));
        assertEquals(Map.of(), grants.all("bob"));
        // Mutation: read the grants of a skill whoever made them (no user_id in granted()) -> bob's run is handed bob's password.
    }

    @Test
    @DisplayName("revoking is its account's too: bob taking his grant back leaves ann's")
    void revokingIsItsAccountsOwn() {
        grants.grant("ann", "mailer", List.of("SMTP_PASS"));
        grants.grant("bob", "mailer", List.of("SMTP_PASS"));

        assertEquals(List.of("SMTP_PASS"), grants.revoke("bob", "mailer", List.of()));

        assertEquals(List.of("ann mailer SMTP_PASS permanent"), GrantsKit.rows(jdbc));
        assertEquals(Map.of("SMTP_PASS", PASS), vault.forSkill("ann", "mailer", List.of("SMTP_PASS")).values());
        assertEquals(List.of("SMTP_PASS"), vault.forSkill("bob", "mailer", List.of("SMTP_PASS")).ungranted());
        // Mutation: revoke deletes the skill's grants of every account -> ann's is gone.
    }

    // ───────────────────────── a skill's name and a credential's name (R7) ─────────────────────────

    @Test
    @DisplayName("a grant is for the skill's exact name: not another case, not a name it begins, not one that reads as a pattern")
    void aGrantIsForTheExactSkillName() {
        grants.grant("ann", "mailer", List.of("SMTP_PASS"));

        for (String other : List.of("Mailer", "MAILER", "mail", "mailer2", "mailer ", " mailer", "mail%", "%", "maile_",
                "mailer' OR '1'='1", "mailer'--", "")) {
            var handed = vault.forSkill("ann", other, List.of("SMTP_PASS"));
            assertEquals(Map.of(), handed.values(), "'" + other + "' read what 'mailer' was granted");
            assertEquals(List.of("SMTP_PASS"), handed.ungranted(), other);
        }
        assertEquals(List.of(), vault.decrypted);
        assertEquals(List.of("SMTP_PASS"), grants.granted("ann", "mailer"));
        // Mutation: match the skill's name with LIKE, or lower-cased -> 'mail%' or 'MAILER' reads the password.
    }

    @Test
    @DisplayName("a grant is for the credentials named, and no others: one the skill declares anew waits for a grant of its own")
    void aGrantIsForTheCredentialsNamed() {
        grants.grant("ann", "mailer", List.of("SMTP_PASS"));

        assertEquals(List.of("SMTP_USER"), grants.ungranted("ann", "mailer", List.of("SMTP_PASS", "SMTP_USER")));
        assertEquals(List.of("SMTP_PASS_2", "SMTP", "SMTP_PAS", "SMTP_%", "%"),
                grants.ungranted("ann", "mailer", List.of("SMTP_PASS_2", "SMTP", "SMTP_PAS", "SMTP_%", "%")),
                "no name that begins, extends or patterns the granted one is granted with it");
        grants.grant("ann", "mailer", List.of("SMTP_USER"));
        assertEquals(List.of(), grants.ungranted("ann", "mailer", List.of("SMTP_PASS", "SMTP_USER")));
        assertEquals(List.of("SMTP_PASS", "SMTP_USER"), grants.granted("ann", "mailer"));
    }

    // ───────────────────────── names as the vault stores them ─────────────────────────

    @Test
    @DisplayName("a credential is one however its name is written: another case, blanks around it, twice -- granted once, asked once, in the order declared")
    void namesAreReadAsTheVaultStoresThem() {
        assertEquals(List.of("SMTP_USER", "SMTP_PASS"),
                grants.ungranted("ann", "mailer", List.of(" smtp_user ", "SMTP_PASS", "Smtp_User", "smtp_pass\t", "", "  ")),
                "each once, as the vault names it, blanks dropped");

        grants.grant("ann", "mailer", List.of(" smtp_pass ", "SMTP_PASS", "Smtp_Pass"));
        assertEquals(List.of("ann mailer SMTP_PASS permanent"), GrantsKit.rows(jdbc), "one row, under the vault's name");

        var handed = vault.forSkill("ann", "mailer", List.of("smtp_pass", " SMTP_PASS", "SMTP_PASS"));
        assertEquals(List.of(), handed.ungranted());
        assertEquals(List.of(), handed.missing());
        assertEquals(java.util.Set.of(PASS), java.util.Set.copyOf(handed.values().values()), "the one value, whatever the spelling");
        // Mutation: compare names as written (key() returns its argument) -> ' smtp_pass ' is never granted.
    }

    @Test
    @DisplayName("a list written as one string is one name no vault holds: nothing is granted by it and nothing handed over for it")
    void aListInOneStringIsNoGrantOfItsParts() {
        grants.grant("ann", "mailer", List.of("[SMTP_PASS, SMTP_USER]"));

        assertEquals(List.of("SMTP_PASS", "SMTP_USER"),
                grants.ungranted("ann", "mailer", List.of("SMTP_PASS", "SMTP_USER")), "its parts were not granted");
        var handed = vault.forSkill("ann", "mailer", List.of("[SMTP_PASS, SMTP_USER]"));
        assertEquals(Map.of(), handed.values());
        assertEquals(List.of("[SMTP_PASS, SMTP_USER]"), handed.missing());
        assertEquals(List.of(), vault.decrypted);
    }

    // ───────────────────────── granting and taking back (R2) ─────────────────────────

    @Test
    @DisplayName("granting twice is one grant; revoking the named ones leaves the rest, and says which had been granted")
    void grantTwiceRevokeNamed() {
        grants.grant("ann", "mailer", List.of("SMTP_PASS", "SMTP_USER", "SMTP_HOST"));
        grants.grant("ann", "mailer", List.of("SMTP_PASS"));
        assertEquals(3, GrantsKit.rows(jdbc).size(), GrantsKit.rows(jdbc).toString());

        assertEquals(List.of("SMTP_PASS"), grants.revoke("ann", "mailer", List.of("smtp_pass", "IMAP_PASS")),
                "the one that had been granted, by its name; the other was never granted");

        assertEquals(List.of("SMTP_HOST", "SMTP_USER"), grants.granted("ann", "mailer"));
        assertEquals(List.of("SMTP_PASS"), vault.forSkill("ann", "mailer", List.of("SMTP_PASS", "SMTP_USER")).ungranted());
        assertEquals(List.of(), grants.revoke("ann", "mailer", List.of("SMTP_PASS")), "taken back already");
        // Mutation: revoke ignores the names and takes everything back -> SMTP_HOST and SMTP_USER are gone.
    }

    @Test
    @DisplayName("revoking with no name takes back everything the skill was granted by that account, and nothing of another skill")
    void revokeAll() {
        grants.grant("ann", "mailer", List.of("SMTP_PASS", "SMTP_USER"));
        grants.grant("ann", "mail_digest", List.of("SMTP_PASS"));

        assertEquals(List.of("SMTP_PASS", "SMTP_USER"), grants.revoke("ann", "mailer", null));

        assertEquals(List.of("ann mail_digest SMTP_PASS permanent"), GrantsKit.rows(jdbc));
        assertEquals(List.of("SMTP_PASS", "SMTP_USER"), grants.ungranted("ann", "mailer", List.of("SMTP_PASS", "SMTP_USER")));
        assertEquals(List.of(), grants.revoke("ann", "%", List.of()), "a pattern names no skill");
        assertEquals(1, GrantsKit.rows(jdbc).size());
    }

    @Test
    @DisplayName("a row of the first schema that was never a grant -- declined, one-time -- lets a skill read nothing; granting over it makes it one, and revoking removes it")
    void aRowThatIsNoGrant() {
        for (String type : List.of("declined", "one-time")) {
            jdbc.update("DELETE FROM credential_grants");
            jdbc.update("INSERT INTO credential_grants (user_id, skill_name, credential, grant_type) "
                    + "VALUES ('ann', 'mailer', 'SMTP_PASS', ?)", type);

            var handed = vault.forSkill("ann", "mailer", List.of("SMTP_PASS"));
            assertEquals(Map.of(), handed.values(), "a '" + type + "' row was read as a grant");
            assertEquals(List.of("SMTP_PASS"), handed.ungranted(), type);
            assertEquals(Map.of(), grants.all("ann"), type);

            grants.grant("ann", "mailer", List.of("SMTP_PASS"));
            assertEquals(List.of("ann mailer SMTP_PASS permanent"), GrantsKit.rows(jdbc), "granted over the '" + type + "' row");
            assertEquals(Map.of("SMTP_PASS", PASS), vault.forSkill("ann", "mailer", List.of("SMTP_PASS")).values());
        }
        jdbc.update("UPDATE credential_grants SET grant_type = 'declined'");
        assertEquals(List.of(), grants.revoke("ann", "mailer", List.of()), "nothing had been granted");
        assertEquals(List.of(), GrantsKit.rows(jdbc), "and what was left of the row is gone");
        // Mutation: granted() reads every row whatever its grant_type -> a 'declined' row hands the password over.
    }

    // ───────────────────────── what a refusal says ─────────────────────────

    @Test
    @DisplayName("a refusal names what the vault does not hold either, when what it holds is known -- compared as the vault names it; not known, it says what it always said")
    void aRefusalNamesWhatIsNotStored() {
        List<String> keys = List.of("SMTP_PASS", "IMAP_PASS", "IMAP_USER");
        String model = "'mailer' has not been allowed to read SMTP_PASS, IMAP_PASS, IMAP_USER from the vault. Only the user "
                + "can allow it, by sending `/grant mailer SMTP_PASS IMAP_PASS IMAP_USER` in the chat: the task's answer shows "
                + "them that command. Nothing you can call allows it. Go on with what does not need this skill, and say "
                + "that it waits for them.";
        String line = "_\uD83D\uDD11 mailer was not run: you have not allowed it to read SMTP_PASS, IMAP_PASS, IMAP_USER from "
                + "your vault. To allow it, send:_ `/grant mailer SMTP_PASS IMAP_PASS IMAP_USER`";

        // What the vault holds is not known: exactly the text of before.
        assertEquals(model, CredentialGrantService.notAllowed("mailer", keys));
        assertEquals(model, CredentialGrantService.notAllowed("mailer", keys, null));
        assertEquals(line, CredentialGrantService.asked("mailer", keys));
        assertEquals(line, CredentialGrantService.asked("mailer", keys, null));
        // It holds them all, however the names are written: nothing is added.
        assertEquals(model, CredentialGrantService.notAllowed("mailer", keys, List.of("imap_user", " IMAP_PASS ", "SMTP_PASS", "OTHER")));
        assertEquals(line, CredentialGrantService.asked("mailer", keys, List.of("imap_user", " IMAP_PASS ", "SMTP_PASS", "OTHER")));

        // It holds one: the other two are named, in the order declared, with how to store them.
        assertEquals(model + " Not in the vault yet either: IMAP_PASS, IMAP_USER -- they store each by typing /cred set KEY "
                + "value; do not ask them to paste the value to you.",
                CredentialGrantService.notAllowed("mailer", keys, List.of("smtp_pass")));
        assertEquals(line + "\n_Not in your vault yet: IMAP_PASS, IMAP_USER. Store each with_ `/cred set <KEY> <VALUE>`",
                CredentialGrantService.asked("mailer", keys, List.of("SMTP_PASS")));
        // It holds none.
        assertTrue(CredentialGrantService.asked("mailer", keys, List.of())
                .endsWith("\n_Not in your vault yet: SMTP_PASS, IMAP_PASS, IMAP_USER. Store each with_ `/cred set <KEY> <VALUE>`"));
        // Mutations: the names compared as written -> ' IMAP_PASS ' and 'imap_user' are said to be missing;
        // what is not known taken for nothing held -> the text of before names all three as missing.
    }

    @Test
    @DisplayName("the link-signing secret is no credential of a skill, however its name is written")
    void theLinkSecretIsNeverASkills() {
        for (String name : List.of(CredentialVault.LINK_SECRET, "guardian_link_secret", " Guardian_Link_Secret ",
                "GUARDIAN_LINK_SECRET\t")) {
            assertFalse(CredentialVault.forSkills(name), "'" + name + "'");
        }
        for (String name : List.of("GUARDIAN_LINK", "GUARDIAN_LINK_SECRET_2", "SMTP_PASS")) {
            assertTrue(CredentialVault.forSkills(name), name);
        }
    }

    @Test
    @DisplayName("the vault hands no skill the link-signing secret, not even with a row that says it was granted: nothing is decrypted, and nothing else is handed over with it")
    void aRowForTheLinkSecretHandsNothingOver() {
        vault.storeCredential("ann", CredentialVault.LINK_SECRET, "the-signing-secret-0001");
        // A row of before grants were checked, when /grant took any name.
        grants.grant("ann", "mailer", List.of(CredentialVault.LINK_SECRET, "SMTP_PASS"));
        vault.decrypted.clear();

        var handed = vault.forSkill("ann", "mailer", List.of("SMTP_PASS", " guardian_link_secret "));

        assertEquals(Map.of(), handed.values(), "not the granted password either: all or nothing");
        assertEquals(List.of(CredentialVault.LINK_SECRET), handed.ungranted());
        assertEquals(List.of(), vault.decrypted, "nothing was read from the vault's values");
        // Mutation: forSkill without its own forSkills check -> the secret is decrypted and handed over.
    }

    // ───────────────────────── foreign keys on, as in production (R8) ─────────────────────────

    @Test
    @DisplayName("production opens its database with foreign keys on, and so does this class: a grant for an account that does not exist is refused by the database, and one for a real account is written")
    void foreignKeysAreOn() throws Exception {
        String url = Files.readAllLines(Path.of("src/main/resources/application.yaml")).stream()
                .filter(line -> line.strip().startsWith("url:")).findFirst().orElseThrow();
        assertTrue(url.contains("jdbc:sqlite:") && url.contains("foreign_keys=true"), "production's JDBC URL: " + url);
        assertEquals(1, jdbc.queryForObject("PRAGMA foreign_keys", Integer.class));

        assertThrows(DataAccessException.class, () -> grants.grant("nobody", "mailer", List.of("SMTP_PASS")),
                "credential_grants.user_id references users(id)");
        assertEquals(List.of(), GrantsKit.rows(jdbc));

        grants.grant("ann", "mailer", List.of("SMTP_PASS"));
        assertEquals(List.of("ann mailer SMTP_PASS permanent"), GrantsKit.rows(jdbc));
        assertEquals(List.of("SMTP_PASS"), grants.revoke("ann", "mailer", List.of("SMTP_PASS")));
        assertEquals(List.of(), GrantsKit.rows(jdbc));

        // The control: through a connection opened without the pragma, as the tests' usual helper
        // opens one, the same grant for nobody is written and nothing says so.
        new CredentialGrantService(MigratedDatabase.at(tmp.resolve("t.db"))).grant("nobody", "mailer", List.of("SMTP_PASS"));
        assertEquals(List.of("nobody mailer SMTP_PASS permanent"), GrantsKit.rows(jdbc));
    }
}
