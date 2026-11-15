package com.ownclaw.agent.tools;

import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.users.AuthService;
import com.ownclaw.users.GrantsKit;
import com.ownclaw.users.UserRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The one-time grant, by who wrote the skill. A skill another account than the owner wrote is
 * that account's to write again whenever it likes, so the first start that checks grants hands
 * it that account's credentials and nobody else's: granted the owner's unasked, its author could
 * put any code under them. A skill with no author -- from before authors were kept -- and one
 * the owner wrote are granted to every account that holds the credential, as before.
 * <p>
 * The real registry over skills on disk, the real vault, the migrated database with foreign keys
 * on, and the real AuthService to say who the owner is: the oldest account that can log in.
 */
class GrantExistingByAuthorTest {

    static final String OWNERS = "author-canary-owner-45", KIDS = "author-canary-kid-45";

    @TempDir static Path once;
    @TempDir Path tmp;

    /**
     * The owner, a second account made later, and a profile that cannot log in (made for a
     * Telegram chat): each vault holds a mail password, the owner's an IMAP one too.
     */
    private static Path filled;

    private JdbcTemplate jdbc;
    private GrantsKit.Vault vault;

    @BeforeAll
    static void fill() throws Exception {
        filled = once.resolve("filled.db");
        JdbcTemplate jdbc = GrantsKit.strict(filled);
        GrantsKit.account(jdbc, "owner", "2026-01-05 09:00:00");
        GrantsKit.account(jdbc, "kid", "2026-03-11 17:30:00");
        GrantsKit.user(jdbc, "guest");
        var vault = new GrantsKit.Vault(jdbc);
        vault.storeCredential("owner", "SMTP_PASS", OWNERS);
        vault.storeCredential("owner", "IMAP_PASS", "author-canary-imap-45");
        vault.storeCredential("kid", "SMTP_PASS", KIDS);
        vault.storeCredential("guest", "SMTP_PASS", "author-canary-guest-45");
    }

    @BeforeEach
    void start() throws Exception {
        jdbc = GrantsKit.copy(filled, tmp.resolve("t.db"));
        vault = new GrantsKit.Vault(jdbc);
        vault.decrypted.clear();
    }

    /** The skills directory and a registry over it that asks {@code accounts} who the owner is. */
    private GrantsKit.Skills skills(AuthService accounts) {
        return new GrantsKit.Skills(tmp, new ToolRegistry(List.of()), vault, accounts);
    }

    private static DynamicSkillRegistry started(GrantsKit.Skills skills) {
        DynamicSkillRegistry registry = skills.registry();
        registry.init();
        return registry;
    }

    @Test
    @DisplayName("a skill another account than the owner wrote is granted to that account alone; one the owner wrote, and one with no author, to every account that holds the credential")
    void grantedToItsAuthorAlone() throws Exception {
        AuthService accounts = GrantsKit.accounts(jdbc);
        assertTrue(accounts.isOwner("owner") && !accounts.isOwner("kid") && !accounts.isOwner("guest"),
                "the premise: the oldest account that can log in is the owner");
        var skills = skills(accounts);
        skills.onDisk("kids_mailer", "kid", "[SMTP_PASS]");
        skills.onDisk("owners_mailer", "owner", "[SMTP_PASS]");
        skills.onDisk("old_mailer", null, "[SMTP_PASS]");

        DynamicSkillRegistry registry = started(skills);

        assertEquals(List.of(
                "guest old_mailer SMTP_PASS permanent",
                "guest owners_mailer SMTP_PASS permanent",
                "kid kids_mailer SMTP_PASS permanent",
                "kid old_mailer SMTP_PASS permanent",
                "kid owners_mailer SMTP_PASS permanent",
                "owner old_mailer SMTP_PASS permanent",
                "owner owners_mailer SMTP_PASS permanent"), GrantsKit.rows(jdbc),
                "kid's skill reads kid's password and nobody else's");
        assertTrue(GrantsKit.existing(jdbc).startsWith("done "), GrantsKit.existing(jdbc));

        // The owner's run of kid's skill hands kid's code nothing until the owner says so.
        DynamicSkill kids = registry.getDynamic("kids_mailer").orElseThrow();
        ToolResult refused = GrantsKit.run(kids, "owner");
        assertFalse(refused.success(), refused.output());
        assertTrue(refused.output().contains("`/grant kids_mailer SMTP_PASS`"), refused.output());
        assertEquals(List.of(), skills.launches.runs());
        assertEquals(List.of(), vault.decrypted);
        // Kid's own run goes on working, with kid's value.
        assertTrue(GrantsKit.run(kids, "kid").success());
        assertEquals(Map.of("SMTP_PASS", KIDS), skills.launches.runs().get(0).credentials());
        // And the owner's skill for every account that used it.
        assertTrue(GrantsKit.run(registry.getDynamic("owners_mailer").orElseThrow(), "kid").success());
        // Mutations: the one-time grant made for every holder whoever wrote the skill -> owner and
        // guest are granted kids_mailer; the registry not saying whose a skill is -> the same;
        // every skill that names an author granted to it alone -> owners_mailer is the owner's only.
    }

    @Test
    @DisplayName("what another account's skill is granted is what that account's own vault holds: a credential only the owner stored is granted to nobody")
    void theAuthorsOwnVaultDecides() throws Exception {
        var skills = skills(GrantsKit.accounts(jdbc));
        skills.onDisk("kids_digest", "kid", "[IMAP_PASS, SMTP_PASS]");

        started(skills);

        assertEquals(List.of("kid kids_digest SMTP_PASS permanent"), GrantsKit.rows(jdbc),
                "the owner's IMAP_PASS is not granted to kid's skill for anyone");
    }

    @Test
    @DisplayName("an author that is no account any more, or one that can no longer log in, is not the owner: its skill is granted to it alone, when it is there and holds the credential")
    void anAuthorThatIsGoneOrDisabled() throws Exception {
        jdbc.update("UPDATE users SET password_hash = NULL WHERE id = 'kid'");   // disabled: the row and its vault stay
        var skills = skills(GrantsKit.accounts(jdbc));
        skills.onDisk("kids_mailer", "kid", "[SMTP_PASS]");
        skills.onDisk("ghosts_mailer", "ghost", "[SMTP_PASS]");

        started(skills);

        assertEquals(List.of("kid kids_mailer SMTP_PASS permanent"), GrantsKit.rows(jdbc),
                "nobody else's password is granted to a skill whose author nobody can ask");
        assertTrue(GrantsKit.existing(jdbc).startsWith("done "));
    }

    @Test
    @DisplayName("the owner is whoever AuthService says: with another account named owner in the configuration, that account's skills are everyone's and the oldest account's are its own")
    void theOwnerIsAsked() throws Exception {
        var config = new OwnClawConfig();
        config.getAuth().setOwner("Account kid");
        AuthService accounts = new AuthService(jdbc, new UserRepository(jdbc), config);
        assertTrue(accounts.isOwner("kid") && !accounts.isOwner("owner"), "the premise");
        var skills = skills(accounts);
        skills.onDisk("kids_mailer", "kid", "[SMTP_PASS]");
        skills.onDisk("owners_mailer", "owner", "[SMTP_PASS]");

        started(skills);

        assertEquals(List.of(
                "guest kids_mailer SMTP_PASS permanent",
                "kid kids_mailer SMTP_PASS permanent",
                "owner kids_mailer SMTP_PASS permanent",
                "owner owners_mailer SMTP_PASS permanent"), GrantsKit.rows(jdbc));
    }

    @Test
    @DisplayName("with no accounts to ask, as in a test without them, nobody is told apart: every holder is granted, whoever wrote the skill")
    void withNoAccountsNobodyIsToldApart() throws Exception {
        var skills = skills(null);
        skills.onDisk("kids_mailer", "kid", "[SMTP_PASS]");

        started(skills);

        assertEquals(List.of(
                "guest kids_mailer SMTP_PASS permanent",
                "kid kids_mailer SMTP_PASS permanent",
                "owner kids_mailer SMTP_PASS permanent"), GrantsKit.rows(jdbc));
    }
}
