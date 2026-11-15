package com.ownclaw.interfaces;

import com.ownclaw.agent.tools.DynamicSkill;
import com.ownclaw.agent.tools.Tool;
import com.ownclaw.agent.tools.ToolExecutionContext;
import com.ownclaw.agent.tools.ToolParam;
import com.ownclaw.agent.tools.ToolRegistry;
import com.ownclaw.agent.tools.ToolResult;
import com.ownclaw.users.CredentialVault;
import com.ownclaw.users.GrantsKit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The commands that make and take back a grant, through the real command handler: the real
 * registry with real skills in it, the real vault and grants over the migrated database with
 * foreign keys on. A grant is made by its account, in a command it sends -- {@code /grant
 * <skill> [KEY ...]} -- for the credentials the skill declares and no others;
 * {@code /revoke <skill> [KEY ...]} takes it back, for a skill that is gone too; {@code /grants}
 * says what each skill may read and which skills wait.
 */
class GrantCommandsTest {

    static final String PASS = "cmd-canary-pass-57", USER = "cmd-canary-user-57";

    @TempDir static Path once;
    @TempDir Path tmp;

    /** ann's vault holds the mail credentials and the link secret, bob's a password of his own. */
    private static Path filled;

    private JdbcTemplate jdbc;
    private GrantsKit.Vault vault;
    private GrantsKit.Skills skills;
    private CommandHandler commands;
    private DynamicSkill mailer;

    /** A registered tool that is no skill of the registry's: it declares nothing. */
    static final Tool PLAIN = new Tool() {
        public String name() { return "plain_tool"; }
        public String description() { return "a tool that reads no credential"; }
        public Map<String, ToolParam> inputSchema() { return Map.of(); }
        public ToolResult execute(Map<String, Object> p, ToolExecutionContext c) { return ToolResult.success("ok"); }
    };

    @BeforeAll
    static void fill() throws Exception {
        filled = once.resolve("filled.db");
        JdbcTemplate jdbc = GrantsKit.strict(filled);
        GrantsKit.user(jdbc, "ann");
        GrantsKit.user(jdbc, "bob");
        var vault = new GrantsKit.Vault(jdbc);
        vault.storeCredential("ann", "SMTP_PASS", PASS);
        vault.storeCredential("ann", "SMTP_USER", USER);
        vault.storeCredential("ann", CredentialVault.LINK_SECRET, "cmd-canary-link-57");
        vault.storeCredential("bob", "SMTP_PASS", "cmd-canary-bobs-57");
    }

    @BeforeEach
    void start() throws Exception {
        jdbc = GrantsKit.copy(filled, tmp.resolve("t.db"));
        vault = new GrantsKit.Vault(jdbc);
        skills = new GrantsKit.Skills(tmp, new ToolRegistry(new ArrayList<>(List.of(PLAIN))), vault);
        mailer = skills.create("mailer", List.of("SMTP_USER", "SMTP_PASS"));
        skills.create("mail_digest", List.of("IMAP_PASS"));
        skills.create("clock", null);
        commands = new CommandHandler(skills.tools, null, null, null, vault, vault.grants(), null, null, null, null,
                null, null, null, null, null, null);
        vault.decrypted.clear();
    }

    private String say(String account, String command) {
        return commands.handle(account, command).orElseThrow(() -> new AssertionError("no command: " + command));
    }

    private List<String> rows() {
        return GrantsKit.rows(jdbc);
    }

    // ───────────────────────── /grant ─────────────────────────

    @Test
    @DisplayName("/grant <skill> with no key grants every credential the skill declares, says which, and the skill then runs with them")
    void grantWithNoKeyGrantsAllItDeclares() {
        assertFalse(GrantsKit.run(mailer, "ann").success(), "refused before the command");

        String said = say("ann", "/grant mailer");

        assertTrue(said.contains("'mailer' may now read: SMTP_USER, SMTP_PASS."), said);
        assertFalse(said.contains("Still not allowed") || said.contains("Not in your vault"), said);
        assertEquals(List.of("ann mailer SMTP_PASS permanent", "ann mailer SMTP_USER permanent"), rows());
        assertTrue(GrantsKit.run(mailer, "ann").success());
        assertEquals(Map.of("SMTP_USER", USER, "SMTP_PASS", PASS), skills.launches.runs().get(0).credentials());
        // Mutation: /grant with no key grants nothing -> no row, and the skill stays refused.
    }

    @Test
    @DisplayName("/grant <skill> KEY grants the ones named and says what is still not allowed: the skill stays refused until it has them all")
    void grantNamedKeys() {
        String said = say("ann", "/grant mailer SMTP_PASS");

        assertTrue(said.contains("'mailer' may now read: SMTP_PASS."), said);
        assertTrue(said.contains("Still not allowed: SMTP_USER."), said);
        assertEquals(List.of("ann mailer SMTP_PASS permanent"), rows());
        assertFalse(GrantsKit.run(mailer, "ann").success());
        assertEquals(List.of(), skills.launches.runs());

        assertFalse(say("ann", "/grant mailer SMTP_USER").contains("Still not allowed"));
        assertTrue(GrantsKit.run(mailer, "ann").success());
    }

    @Test
    @DisplayName("only what the skill declares can be granted: one name it does not declare, and nothing of the command is granted")
    void onlyWhatTheSkillDeclares() {
        for (String command : List.of("/grant mailer WIFI_PSK", "/grant mailer SMTP_PASS WIFI_PSK",
                "/grant mailer WIFI_PSK SMTP_PASS", "/grant mailer IMAP_PASS", "/grant mailer SMTP_%", "/grant mailer %",
                "/grant mailer SMTP_PASS_2", "/grant mailer SMTP", "/grant mailer *", "/grant mailer SMTP_PASS,SMTP_USER",
                "/grant mailer [SMTP_PASS, SMTP_USER]", "/grant mailer SMTP_PASS' OR '1'='1",
                "/grant mailer SMTP_PASS\n/grant mail_digest")) {
            String said = say("ann", command);
            assertTrue(said.startsWith("Not granted: 'mailer' does not declare "), command + " -> " + said);
            assertTrue(said.endsWith("It declares SMTP_USER, SMTP_PASS."), command + " -> " + said);
            assertEquals(List.of(), rows(), command);
        }
        // Mutations: /grant without the test of what the skill declares -> WIFI_PSK is granted;
        // granting the declared ones of the command beside the refusal -> SMTP_PASS is granted.
    }

    @Test
    @DisplayName("a name there is no skill of, a skill that declares nothing and a tool that is no skill are granted nothing")
    void nothingToGrant() {
        assertEquals("Skill not found: nosuch", say("ann", "/grant nosuch SMTP_PASS"));
        assertEquals("'clock' declares no credential: there is nothing to grant it.", say("ann", "/grant clock"));
        assertEquals("'clock' declares no credential: there is nothing to grant it.", say("ann", "/grant clock SMTP_PASS"));
        assertEquals("'plain_tool' declares no credential: there is nothing to grant it.", say("ann", "/grant plain_tool SMTP_PASS"));
        assertEquals(List.of(), rows());
    }

    @Test
    @DisplayName("a skill is named exactly: another case finds it and grants it under its own name; a name it begins, a pattern or SQL names no skill and grants nothing")
    void theSkillIsNamedExactly() {
        for (String name : List.of("mail", "maile", "mailer2", "mail%", "%", "mailer%", "maile_", "mail_", "mailer'--",
                "mailer';DROP", "'mailer'", "mailer,mail_digest", "*")) {
            String said = say("ann", "/grant " + name + " SMTP_PASS");
            assertEquals("Skill not found: " + name, said);
        }
        assertEquals(List.of(), rows());
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM credential_vault WHERE user_id = 'ann'", Integer.class),
                "the tables are as they were");

        String said = say("ann", "/GRANT Mailer smtp_pass SMTP_USER smtp_pass");
        assertTrue(said.contains("'mailer' may now read: SMTP_PASS, SMTP_USER."), said);
        assertEquals(List.of("ann mailer SMTP_PASS permanent", "ann mailer SMTP_USER permanent"), rows(),
                "under the skill's own name and the vault's names, each once");
        assertTrue(GrantsKit.run(mailer, "ann").success());
        // Mutations: the grant stored under the name as typed -> 'Mailer' is granted, and mailer stays
        // refused; the look-up in lower case dropped -> 'Mailer' is no skill.
    }

    @Test
    @DisplayName("the link-signing secret cannot be granted: named, it is refused; with no key, everything else the skill declares is granted and it is not")
    void theLinkSecretCannotBeGranted() {
        DynamicSkill signer = skills.create("signer", List.of("SMTP_PASS", CredentialVault.LINK_SECRET));

        for (String name : List.of(CredentialVault.LINK_SECRET, "guardian_link_secret", "Guardian_Link_Secret")) {
            assertEquals("Not granted: that credential is never handed to a skill.", say("ann", "/grant signer " + name));
        }
        assertEquals(List.of(), rows());

        String said = say("ann", "/grant signer");
        assertTrue(said.contains("'signer' may now read: SMTP_PASS."), said);
        assertEquals(List.of("ann signer SMTP_PASS permanent"), rows());
        assertTrue(say("ann", "/grant signer SMTP_PASS " + CredentialVault.LINK_SECRET).contains("may now read: SMTP_PASS."));
        assertEquals(List.of("ann signer SMTP_PASS permanent"), rows());
        assertFalse(GrantsKit.run(signer, "ann").success(), "and a skill that declares it is still not run");
        assertEquals(List.of(), skills.launches.runs());
        assertEquals(List.of(), vault.decrypted);
        assertFalse(say("ann", "/grants").contains("`/grant signer"), "/grants offers a line /grant refuses: " + say("ann", "/grants"));
        // Mutation: /grant without its forSkills test -> GUARDIAN_LINK_SECRET is granted.
    }

    @Test
    @DisplayName("granting a credential the vault does not hold yet says so, and where to store it")
    void grantedButNotInTheVaultYet() {
        String said = say("ann", "/grant mail_digest");

        assertTrue(said.contains("'mail_digest' may now read: IMAP_PASS."), said);
        assertTrue(said.contains("Not in your vault yet: IMAP_PASS"), said);
        assertTrue(said.contains("`/cred set <KEY> <VALUE>`"), said);
        assertEquals(List.of("ann mail_digest IMAP_PASS permanent"), rows());
    }

    @Test
    @DisplayName("a grant is a command sent on its own: inside another command, or after words, it grants nothing")
    void onlyOnItsOwn() {
        assertEquals(CommandHandler.COMMAND_AS_TASK, say("ann", "/bg /grant mailer SMTP_PASS"));
        for (String text : List.of("/queue /grant mailer SMTP_PASS", "please /grant mailer SMTP_PASS", "grant mailer SMTP_PASS",
                "/grantmailer SMTP_PASS", "/grants mailer", "/granted mailer SMTP_PASS", "//grant mailer SMTP_PASS")) {
            assertTrue(commands.handle("ann", text).isEmpty(), "read as a command of this handler: " + text);
        }
        assertEquals(List.of(), rows());
    }

    // ───────────────────────── an account's own (R6), foreign keys on (R8) ─────────────────────────

    @Test
    @DisplayName("a grant is made for the account that sent the command, and no other: bob's run of the skill ann granted is not started")
    void grantedForTheAccountThatSentIt() {
        say("ann", "/grant mailer");

        assertEquals(List.of("ann mailer SMTP_PASS permanent", "ann mailer SMTP_USER permanent"), rows());
        assertFalse(GrantsKit.run(mailer, "bob").success());
        assertEquals(List.of(), skills.launches.runs());
        assertEquals(List.of("SMTP_USER", "SMTP_PASS"), mailer.ungranted("bob"));

        say("bob", "/grant mailer");
        say("bob", "/revoke mailer");
        assertEquals(List.of("ann mailer SMTP_PASS permanent", "ann mailer SMTP_USER permanent"), rows(),
                "bob took back his own, not ann's");
        // Mutation: the grants of a skill read whoever made them -> bob's run is started on ann's grant.
    }

    @Test
    @DisplayName("with foreign keys on, as in production: a command from an account that is no row of users is refused by the database and grants nothing")
    void foreignKeysAreOn() {
        assertEquals(1, jdbc.queryForObject("PRAGMA foreign_keys", Integer.class));

        assertThrows(DataAccessException.class, () -> commands.handle("ghost", "/grant mailer"));

        assertEquals(List.of(), rows());
        assertTrue(say("ann", "/grant mailer").contains("may now read"));
        assertEquals(2, rows().size());
    }

    // ───────────────────────── /revoke ─────────────────────────

    @Test
    @DisplayName("/revoke <skill> KEY takes back the ones named, /revoke <skill> all of them, and each says what had been granted; the skill is refused again")
    void revoke() {
        say("ann", "/grant mailer");
        assertTrue(GrantsKit.run(mailer, "ann").success());

        assertEquals("'mailer' had been granted none of those.", say("ann", "/revoke mailer IMAP_PASS"));
        String one = say("ann", "/revoke mailer smtp_user");
        assertTrue(one.contains("'mailer' may no longer read: SMTP_USER."), one);
        assertEquals(List.of("ann mailer SMTP_PASS permanent"), rows());
        assertFalse(GrantsKit.run(mailer, "ann").success(), "all or nothing again");

        say("ann", "/grant mailer");
        String all = say("ann", "/REVOKE Mailer");
        assertTrue(all.contains("'mailer' may no longer read: SMTP_PASS, SMTP_USER."), all);
        assertEquals(List.of(), rows());
        assertEquals("'mailer' had been granted nothing.", say("ann", "/revoke mailer"));
        assertEquals(1, skills.launches.runs().size(), "only the run made while it was granted");
        // Mutation: /revoke takes everything back whatever is named -> SMTP_PASS is gone with SMTP_USER.
    }

    @Test
    @DisplayName("/revoke works for a skill that is no longer registered -- retired, set aside -- and left its grants: they are still listed, and taken back by its name, typed in any case")
    void revokeForASkillThatIsGone() {
        // The rule changed with the reviews of the release: a deleted skill's grants end with it
        // (aDeletedSkillLeavesNothingToRevoke), so what is left to take back is what a skill left
        // that was retired or set aside -- kept, for when it is brought back.
        say("ann", "/grant mailer");
        assertTrue(skills.registry.retire("mailer", "not used for a year").isPresent());
        assertEquals("Skill not found: mailer", say("ann", "/grant mailer"));
        assertTrue(say("ann", "/grants").contains("mailer: SMTP_PASS, SMTP_USER"), say("ann", "/grants"));

        // No registered skill to find the name by: it is read in lower case, as every skill's name is.
        String said = say("ann", "/revoke Mailer SMTP_PASS");
        assertTrue(said.contains("'mailer' may no longer read: SMTP_PASS."), said);
        assertTrue(say("ann", "/REVOKE MAILER").contains("'mailer' may no longer read: SMTP_USER."));

        assertEquals(List.of(), rows());
        assertFalse(say("ann", "/grants").contains("mailer:"), say("ann", "/grants"));

        // A name from before names were lower case kept its capitals: it is found as it is listed,
        // and typed in any other case.
        vault.grants().grant("ann", "OldMailer", List.of("SMTP_PASS", "SMTP_USER"));
        assertTrue(say("ann", "/grants").contains("OldMailer: SMTP_PASS, SMTP_USER"), say("ann", "/grants"));
        assertTrue(say("ann", "/revoke OldMailer SMTP_PASS").contains("'OldMailer' may no longer read: SMTP_PASS."));
        assertTrue(say("ann", "/revoke OLDMAILER").contains("'OldMailer' may no longer read: SMTP_USER."));
        assertEquals(List.of(), rows());
        assertEquals("'nosuchskill' had been granted nothing.", say("ann", "/revoke NoSuchSkill"));
        // Mutations: /revoke of a name no registered skill has does nothing -> the grants a retired
        // skill left stay; the name of a skill that is gone taken as typed alone -> 'Mailer' had been
        // granted nothing; taken in lower case alone -> 'oldmailer' had been granted nothing.
    }

    @Test
    @DisplayName("a deleted skill leaves nothing to revoke: its grants ended with it, for every account, and /grants no longer lists it")
    void aDeletedSkillLeavesNothingToRevoke() {
        say("ann", "/grant mailer");
        say("bob", "/grant mailer");
        say("ann", "/grant mail_digest");

        skills.manager.deleteSkill("mailer");

        assertEquals(List.of("ann mail_digest IMAP_PASS permanent"), rows(), "the deleted skill's grants, and no other's");
        assertFalse(say("ann", "/grants").contains("mailer"), say("ann", "/grants"));
        assertEquals("'mailer' had been granted nothing.", say("ann", "/revoke mailer"));
        assertEquals("'mailer' had been granted nothing.", say("bob", "/revoke mailer"));
        // Mutation: a delete that leaves the grants behind -> they are listed, and a skill made under the name next would read them.
    }

    @Test
    @DisplayName("/revoke names a skill exactly: a pattern, a name it begins or SQL takes nothing back")
    void revokeNamesTheSkillExactly() {
        say("ann", "/grant mailer");
        say("ann", "/grant mail_digest");
        List<String> before = rows();

        for (String name : List.of("%", "mail%", "mail", "maile_", "mailer'--", "*", "mail_digest%")) {
            assertEquals("'" + name + "' had been granted nothing.", say("ann", "/revoke " + name));
            assertEquals(before, rows(), "/revoke " + name);
        }
        for (String command : List.of("/revoke mailer' OR '1'='1", "/revoke x' OR skill_name LIKE '%", "/revoke mailer % SMTP_% *")) {
            assertTrue(say("ann", command).endsWith("had been granted none of those."), command);
            assertEquals(before, rows(), command);
        }
    }

    // ───────────────────────── /grants ─────────────────────────

    @Test
    @DisplayName("/grants lists what each skill may read of the account's vault and, as the command to send, each skill that waits for a grant")
    void grantsLists() {
        String none = say("ann", "/grants");
        assertTrue(none.startsWith("No skill has been granted a credential of yours."), none);
        assertTrue(none.contains("`/grant mail_digest IMAP_PASS`"), none);
        assertTrue(none.contains("`/grant mailer SMTP_USER SMTP_PASS`"), none);
        assertFalse(none.contains("clock") || none.contains("plain_tool"), "a tool that declares nothing waits for nothing: " + none);

        say("ann", "/grant mailer SMTP_PASS");
        say("ann", "/grant mail_digest");
        String some = say("ann", "/grants");

        assertTrue(some.contains("mail_digest: IMAP_PASS"), some);
        assertTrue(some.contains("mailer: SMTP_PASS"), some);
        assertTrue(some.contains("`/grant mailer SMTP_USER`"), "what mailer still waits for: " + some);
        assertFalse(some.contains("`/grant mail_digest"), some);

        String bobs = say("bob", "/grants");
        assertTrue(bobs.startsWith("No skill has been granted a credential of yours."), "ann's grants shown to bob: " + bobs);
        assertTrue(bobs.contains("`/grant mailer SMTP_USER SMTP_PASS`"), bobs);
        // Mutation: the grants listed whoever made them -> bob is shown ann's.
    }

    @Test
    @DisplayName("each line /grants offers is a command that grants: sent as it stands, the skill waits no longer")
    void theOfferedLineGrants() {
        String offered = say("ann", "/grants");
        var line = java.util.regex.Pattern.compile("`(/grant mailer [^`]+)`").matcher(offered);
        assertTrue(line.find(), offered);

        String said = say("ann", line.group(1));

        assertTrue(said.contains("'mailer' may now read: SMTP_USER, SMTP_PASS."), said);
        assertFalse(say("ann", "/grants").contains("`/grant mailer"), say("ann", "/grants"));
        assertEquals(List.of(), mailer.ungranted("ann"));
        // Mutation: /grants without the skills that wait -> no line to send.
    }
}
