package com.ownclaw.agent;

import com.ownclaw.agent.tools.DynamicSkill;
import com.ownclaw.agent.tools.ToolRegistry;
import com.ownclaw.agent.tools.ToolResult;
import com.ownclaw.sandbox.SandboxResult;
import com.ownclaw.users.CredentialGrantService;
import com.ownclaw.users.GrantsKit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * A grant ends with what it was for. It was given to the code that was there: so it ends when
 * the skill is deleted; when a skill is made under a name no skill holds -- one deleted, retired
 * or set aside may have left its grants behind; and, for every other account, when another
 * account than the owner writes the skill again, which its author may do whenever it likes. An
 * update by the owner, or by maintenance, ends nothing, and bringing a retired skill back is no
 * new skill.
 * <p>
 * Through the real skill manager and registry over a sandbox that starts nothing, the real vault
 * and grants, the migrated database with foreign keys on, and the real AuthService to say who
 * the owner is.
 */
class GrantsEndWithTheSkillTest {

    static final String OWNERS = "end-canary-owner-72", KIDS = "end-canary-kid-72", ANNS = "end-canary-ann-72";
    static final List<String> PASS = List.of("SMTP_PASS");

    @TempDir static Path once;
    @TempDir Path tmp;

    /** The owner -- the oldest account that can log in -- and two accounts made later, each with a mail password. */
    private static Path filled;

    private JdbcTemplate jdbc;
    private GrantsKit.Vault vault;
    private CredentialGrantService grants;
    private GrantsKit.Skills skills;

    @BeforeAll
    static void fill() throws Exception {
        filled = once.resolve("filled.db");
        JdbcTemplate jdbc = GrantsKit.strict(filled);
        GrantsKit.account(jdbc, "owner", "2026-01-05 09:00:00");
        GrantsKit.account(jdbc, "kid", "2026-03-11 17:30:00");
        GrantsKit.account(jdbc, "ann", "2026-04-02 08:15:00");
        var vault = new GrantsKit.Vault(jdbc);
        vault.storeCredential("owner", "SMTP_PASS", OWNERS);
        vault.storeCredential("kid", "SMTP_PASS", KIDS);
        vault.storeCredential("ann", "SMTP_PASS", ANNS);
    }

    @BeforeEach
    void start() throws Exception {
        jdbc = GrantsKit.copy(filled, tmp.resolve("t.db"));
        vault = new GrantsKit.Vault(jdbc);
        grants = vault.grants();
        var accounts = GrantsKit.accounts(jdbc);
        assertTrue(accounts.isOwner("owner") && !accounts.isOwner("kid") && !accounts.isOwner("ann"), "the premise");
        skills = new GrantsKit.Skills(tmp, new ToolRegistry(List.of()), vault, accounts);
        vault.decrypted.clear();
    }

    /** skill_create of {@code name} for a task of {@code requester}'s, its code marked {@code version}. */
    private String write(String requester, String name, String version) {
        Map<String, Object> spec = GrantsKit.Skills.spec(name, PASS);
        spec.put("code", "def run(params):\n    return {'output': '" + version + "'}\n");
        return skills.manager.createSkill(spec, requester, () -> false, () -> {});
    }

    /** What a write answered, or that it threw. */
    private static String attempt(java.util.function.Supplier<String> write) {
        try {
            return write.get();
        } catch (RuntimeException thrown) {
            return "thrown: " + thrown;
        }
    }

    private String code(String name) throws Exception {
        return Files.readString(skills.generated.resolve(name).resolve("skill.py"));
    }

    private void everyoneGrants(String skill) {
        for (String account : List.of("owner", "kid", "ann")) grants.grant(account, skill, PASS);
    }

    /** The accounts that have granted {@code skill} its password, by name. */
    private List<String> grantedBy(String skill) {
        return jdbc.queryForList("SELECT user_id FROM credential_grants WHERE skill_name = ? ORDER BY user_id",
                String.class, skill);
    }

    private DynamicSkill skill(String name) {
        return skills.registry.getDynamic(name).orElseThrow();
    }

    /** A run of the skill for the account is not started, and its refusal offers the command. */
    private void assertRefused(String name, String account) {
        int before = skills.launches.runs().size();
        ToolResult refused = GrantsKit.run(skill(name), account);
        assertFalse(refused.success(), name + " ran for " + account);
        assertTrue(refused.output().contains("`/grant " + name + " SMTP_PASS`"), refused.output());
        assertEquals(before, skills.launches.runs().size(), name + " was started for " + account);
    }

    // ───────────────────────── written again ─────────────────────────

    @Test
    @DisplayName("another account than the owner writes its skill again: every other account's grant of it ends and its own stays -- the owner's next run is not started until the owner grants the new code")
    void writtenAgainByAnotherAccountThanTheOwner() throws Exception {
        assertFalse(write("kid", "kids_mailer", "v1").startsWith("ERROR"));
        everyoneGrants("kids_mailer");
        assertTrue(GrantsKit.run(skill("kids_mailer"), "owner").success(), "the premise: granted, it runs for the owner");
        vault.decrypted.clear();

        String said = write("kid", "kids_mailer", "v2");

        assertTrue(said.startsWith("Skill 'kids_mailer' updated"), said);
        assertTrue(code("kids_mailer").contains("'v2'"));
        assertEquals(List.of("kid"), grantedBy("kids_mailer"), "the owner's and ann's grants were for what it replaced");
        assertRefused("kids_mailer", "owner");
        assertRefused("kids_mailer", "ann");
        assertEquals(List.of(), vault.decrypted, "kid's new code was handed nothing of anyone else's");
        assertTrue(GrantsKit.run(skill("kids_mailer"), "kid").success(), "its author's own grant stays");
        assertEquals(Map.of("SMTP_PASS", KIDS), skills.launches.runs().getLast().credentials());

        grants.grant("owner", "kids_mailer", PASS);
        assertTrue(GrantsKit.run(skill("kids_mailer"), "owner").success(), "granted again, for the code that is there now");
        assertEquals(Map.of("SMTP_PASS", OWNERS), skills.launches.runs().getLast().credentials());
        // Mutations: a rewrite ends nothing, whoever makes it -> the owner's run hands kid's new code
        // the owner's password; it ends its author's grant too -> kid's own run is refused; the
        // grants ended are the author's and not the others' -> the same, and the owner's stays.
    }

    @Test
    @DisplayName("the owner writing a skill again, or maintenance with no account's task, ends nothing: every account's grant stays, another account's skill included, and it stays that account's")
    void writtenAgainByTheOwnerOrByNoAccount() throws Exception {
        write("kid", "kids_mailer", "v1");
        write("owner", "owners_mailer", "v1");
        everyoneGrants("kids_mailer");
        everyoneGrants("owners_mailer");

        assertTrue(write("owner", "kids_mailer", "repaired by the owner").startsWith("Skill 'kids_mailer' updated"));
        assertTrue(write("owner", "owners_mailer", "v2").startsWith("Skill 'owners_mailer' updated"));
        assertTrue(write(null, "kids_mailer", "tidied by maintenance").startsWith("Skill 'kids_mailer' updated"));

        assertEquals(List.of("ann", "kid", "owner"), grantedBy("kids_mailer"));
        assertEquals(List.of("ann", "kid", "owner"), grantedBy("owners_mailer"));
        assertTrue(code("kids_mailer").contains("tidied by maintenance"));
        String yaml = Files.readString(skills.generated.resolve("kids_mailer/SKILL.yaml"));
        assertTrue(yaml.contains("author: \"kid\"") || yaml.contains("author: kid"), "the owner repairing it does not take it over: " + yaml);
        assertTrue(GrantsKit.run(skill("kids_mailer"), "ann").success(), "a repair leaves the skill working for who granted it");
        assertEquals(Map.of("SMTP_PASS", ANNS), skills.launches.runs().getLast().credentials());
        // Mutation: the owner's rewrite ends the others' grants as another account's does -> only the owner's are left.
    }

    // ───────────────────────── deleted, retired, a name that is free ─────────────────────────

    @Test
    @DisplayName("deleting a skill ends every account's grant of it, its author's too: made again under the name, it is refused for everyone, the one who made it included")
    void deleted() {
        write("kid", "kids_mailer", "v1");
        write("owner", "owners_mailer", "v1");
        everyoneGrants("kids_mailer");
        everyoneGrants("owners_mailer");

        assertEquals("Skill 'kids_mailer' has been permanently deleted.",
                skills.manager.deleteSkill("kids_mailer", "kid", () -> false, () -> {}));

        assertEquals(List.of(), grantedBy("kids_mailer"));
        assertEquals(List.of("ann", "kid", "owner"), grantedBy("owners_mailer"), "no other skill's");

        assertFalse(write("kid", "kids_mailer", "another skill").startsWith("ERROR"));
        for (String account : List.of("kid", "owner", "ann")) assertRefused("kids_mailer", account);
        // Mutation: a delete that leaves the grants behind -> they are there after it.
    }

    @Test
    @DisplayName("a skill made under a name no skill holds starts with no grant, whatever was left under that name: by a skill that was retired, by one set aside at a start, by a row from before grants were checked")
    void aNameThatIsFreeStartsWithNoGrant() throws Exception {
        // Retired by maintenance: set aside with its grants, for when it is brought back.
        write("owner", "mailer", "the owner's");
        everyoneGrants("mailer");
        assertTrue(skills.registry.retire("mailer", "not used for a year").isPresent());
        assertEquals(List.of("ann", "kid", "owner"), grantedBy("mailer"), "retiring a skill ends no grant");
        // Set aside at a start, for a SKILL.yaml that does not read: its grants stay as well.
        Path broken = skills.onDisk("digest", "[SMTP_PASS]");
        Files.writeString(broken.resolve("SKILL.yaml"), "name: digest\ndescription: [unclosed\n");
        everyoneGrants("digest");
        skills.registry.reload();
        assertTrue(skills.registry.getDynamic("digest").isEmpty(), "the premise: it did not load");
        // A row /grant wrote before grants were checked, for a skill removed by hand since.
        grants.grant("owner", "courier", PASS);

        for (String name : List.of("mailer", "digest", "courier")) {
            assertTrue(write("kid", name, "kid's own").startsWith("Skill '" + name + "' created"), name);
            assertEquals(List.of(), grantedBy(name), "kid's new '" + name + "' kept what another skill was granted");
            assertRefused(name, "owner");
        }
        assertEquals(List.of(), vault.decrypted);
        // Mutation: a new skill keeps the grants left under its name -> kid's code runs with the owner's password.
    }

    @Test
    @DisplayName("bringing a retired skill back is no new skill: it comes back with its grants, and runs on them")
    void broughtBackWithItsGrants() {
        write("owner", "mailer", "the owner's");
        everyoneGrants("mailer");
        Path aside = skills.registry.retire("mailer", "not used for a year").orElseThrow();

        assertEquals("mailer", skills.registry.restoreFromQuarantine(aside.getFileName().toString()).orElseThrow());

        assertEquals(List.of("ann", "kid", "owner"), grantedBy("mailer"));
        assertTrue(GrantsKit.run(skill("mailer"), "ann").success());
        assertEquals(Map.of("SMTP_PASS", ANNS), skills.launches.runs().getLast().credentials());
        // Mutations: retiring a skill ends its grants; bringing one back ends them -> nobody's are left.
    }

    // ───────────────────────── what ends nothing ─────────────────────────

    @Test
    @DisplayName("an account that may not change a skill ends none of its grants by trying: its rewrite and its delete are refused before anything is touched, for the owner's skill and for one with no author")
    void aRefusedChangeEndsNothing() throws Exception {
        write("owner", "owners_mailer", "the owner's");
        write(null, "old_mailer", "from before authors were kept");
        write("ann", "anns_mailer", "ann's");
        for (String name : List.of("owners_mailer", "old_mailer", "anns_mailer")) everyoneGrants(name);

        for (String name : List.of("owners_mailer", "old_mailer", "anns_mailer")) {
            String rewrite = write("kid", name, "kid's code under someone else's grant");
            assertTrue(rewrite.startsWith("ERROR: the skill '" + name + "' belongs to another account"), rewrite);
            String delete = skills.manager.deleteSkill(name, "kid", () -> false, () -> {});
            assertTrue(delete.startsWith("ERROR: the skill '" + name + "' belongs to another account"), delete);

            assertEquals(List.of("ann", "kid", "owner"), grantedBy(name), "a refused change ended grants of " + name);
            assertFalse(code(name).contains("kid's code"), code(name));
            assertTrue(GrantsKit.run(skill(name), "owner").success(), "and it still runs for the owner, as it was");
        }
        // Nor does deleting a name no skill holds end what was left under it.
        grants.grant("owner", "courier", PASS);
        assertTrue(skills.manager.deleteSkill("courier", "kid", () -> false, () -> {}).startsWith("ERROR: 'courier' is not a modifiable skill"));
        assertEquals(List.of("owner"), grantedBy("courier"));
        // Mutation: the test of whose skill it is skipped for a rewrite -> kid's code is written, and the
        // owner's and ann's grants end with kid's own left.
    }

    @Test
    @DisplayName("a creation that fails ends nothing: a name refused, code that does not compile or does not load, a credential that is no name, requirements that are none -- the skill stays as it was, granted as it was")
    void aCreationThatFailsEndsNothing() throws Exception {
        write("kid", "kids_mailer", "v1");
        everyoneGrants("kids_mailer");
        grants.grant("owner", "kids_mailer_v2", PASS);   // left under a name the gate of variants refuses
        grants.grant("owner", "courier", PASS);          // left under a free name
        var ok = skills.launches.answer;

        // A variant's name: refused before anything else.
        assertTrue(write("kid", "kids_mailer_v2", "a variant").startsWith("ERROR: Skill 'kids_mailer' already exists"));
        // Code that does not compile.
        skills.launches.answer = launch -> launch.script().getFileName().toString().equals("_check.py")
                ? new SandboxResult(3, "", "SyntaxError: invalid syntax", 1, false) : ok.apply(launch);
        assertTrue(write("kid", "kids_mailer", "broken").startsWith("ERROR: Python syntax error"));
        skills.launches.answer = ok;
        // A credential that is no name, and requirements that are none.
        Map<String, Object> badName = GrantsKit.Skills.spec("kids_mailer", "smtp pass!");
        assertTrue(skills.manager.createSkill(badName, "kid", () -> false, () -> {}).startsWith("ERROR: "));
        Map<String, Object> badRequirements = GrantsKit.Skills.spec("kids_mailer", PASS);
        badRequirements.put("requirements", "-r /etc/passwd");
        assertTrue(skills.manager.createSkill(badRequirements, "kid", () -> false, () -> {}).startsWith("ERROR: the requirements"));
        // Code that compiles and does not load: the version before it is put back, whole.
        skills.launches.answer = launch -> launch.script().getFileName().toString().startsWith("_load_check_")
                ? new SandboxResult(1, "", "ModuleNotFoundError: No module named 'nowhere'", 1, false) : ok.apply(launch);
        assertTrue(write("kid", "kids_mailer", "does not load").startsWith("ERROR: the new code for 'kids_mailer' does not load"));
        // A new skill that does not load is set aside, and the name stays free.
        assertTrue(write("kid", "courier", "does not load either").startsWith("ERROR: 'courier' was written but does not load"));
        skills.launches.answer = ok;

        assertEquals(List.of("ann", "kid", "owner"), grantedBy("kids_mailer"), "a failed rewrite ended grants");
        assertTrue(code("kids_mailer").contains("'v1'"), code("kids_mailer"));
        assertTrue(GrantsKit.run(skill("kids_mailer"), "owner").success(), "the version that was granted still runs");
        assertEquals(Map.of("SMTP_PASS", OWNERS), skills.launches.runs().getLast().credentials());
        assertEquals(List.of("owner"), grantedBy("kids_mailer_v2"), "nothing was made under it");
        assertEquals(List.of("owner"), grantedBy("courier"), "nothing was registered under it");

        // And once a skill is made under the free name, what was left there ends.
        assertTrue(write("kid", "courier", "loads").startsWith("Skill 'courier' created"));
        assertEquals(List.of(), grantedBy("courier"));
        // Mutation: the grants ended before the new code is checked to load -> a rewrite that does not
        // load, and is undone, has ended the owner's and ann's grants all the same.
    }

    @Test
    @DisplayName("a skill is not written again, or made, over grants that could not be ended: the version that was granted is put back whole and is what runs, a new one is set aside, and the task is told")
    void whatCannotEndTheGrantsIsNotMade() throws Exception {
        write("kid", "kids_mailer", "v1");
        everyoneGrants("kids_mailer");
        grants.grant("owner", "courier", PASS);   // left under a free name
        // The database refuses to end them, as a full disk does.
        jdbc.execute("CREATE TRIGGER the_disk_is_full BEFORE DELETE ON credential_grants "
                + "BEGIN SELECT RAISE(ABORT, 'database or disk is full'); END");

        String rewrite = attempt(() -> write("kid", "kids_mailer", "v2"));
        String made = attempt(() -> write("kid", "courier", "kid's own"));

        jdbc.execute("DROP TRIGGER the_disk_is_full");
        // Kid's new code is nowhere a grant that outlived it could reach.
        assertEquals(List.of("ann", "kid", "owner"), grantedBy("kids_mailer"));
        assertTrue(code("kids_mailer").contains("'v1'"), "kid's new code stayed, under the owner's grant: " + code("kids_mailer"));
        assertTrue(GrantsKit.run(skill("kids_mailer"), "owner").success(), "the version that was granted runs on");
        assertEquals(List.of("owner"), grantedBy("courier"));
        assertTrue(skills.registry.getDynamic("courier").isEmpty(), "kid's new skill was registered under what was left there");
        assertFalse(Files.exists(skills.generated.resolve("courier")), "and it is not left for the next start to register");
        assertTrue(rewrite.startsWith("ERROR: 'kids_mailer' was not changed"), rewrite);
        assertTrue(made.startsWith("ERROR: 'courier' was not registered"), made);

        // With the database well again, both are made, and the grants end as they should.
        assertTrue(write("kid", "kids_mailer", "v2").startsWith("Skill 'kids_mailer' updated"));
        assertTrue(write("kid", "courier", "kid's own").startsWith("Skill 'courier' created"));
        assertEquals(List.of("kid"), grantedBy("kids_mailer"));
        assertEquals(List.of(), grantedBy("courier"));
        // Mutation: the skill registered first and its grants ended after, as before -> the failure
        // leaves kid's v2 on disk and registered, and the owner's next run hands it the owner's password.
    }

    @Test
    @DisplayName("a rewrite that fails while its files are written leaves the code that was granted: the code is written last, so a failure before it puts no new code under the grants of the old")
    void aWriteThatFailsLeavesTheCodeThatWasGranted() throws Exception {
        write("kid", "kids_mailer", "v1");
        everyoneGrants("kids_mailer");
        // The requirements cannot be written, as on a disk that fails: here, a file made read-only.
        Path requirements = skills.generated.resolve("kids_mailer/requirements.txt");
        var writable = Files.getPosixFilePermissions(requirements);
        Files.setPosixFilePermissions(requirements, PosixFilePermissions.fromString("r--r--r--"));
        String said;
        try {
            assumeFalse(Files.isWritable(requirements), "run as root, every file can be written");
            said = write("kid", "kids_mailer", "v2");
        } finally {
            Files.setPosixFilePermissions(requirements, writable);
        }

        assertTrue(said.startsWith("ERROR: Failed to write skill files"), said);
        assertTrue(code("kids_mailer").contains("'v1'"), "kid's new code is on disk, under the owner's grant: " + code("kids_mailer"));
        assertEquals(List.of("ann", "kid", "owner"), grantedBy("kids_mailer"), "nothing was made, so nothing ended");
        assertTrue(GrantsKit.run(skill("kids_mailer"), "owner").success(), "the version that was granted runs on");
        // Mutation: the code written before the requirements, as before -> the failed rewrite leaves
        // kid's v2 as the skill's code, registered and granted as v1 was.
    }

    // ───────────────────────── no run between the new code and the ended grants ─────────────────────────

    @Test
    @DisplayName("a run cannot slip between the new code and the ended grants: asked while the skill is being written again, it waits for the write, and after it is not started")
    void aRunWaitsForTheRewriteAndIsRefusedAfterIt() throws Exception {
        write("kid", "kids_mailer", "v1");
        everyoneGrants("kids_mailer");
        // What a task of the owner's found and asked before the rewrite: the skill, granted.
        DynamicSkill found = skill("kids_mailer");
        assertEquals(List.of(), found.ungranted("owner"));
        vault.decrypted.clear();

        var ok = skills.launches.answer;
        var checking = new CountDownLatch(1);
        var goOn = new CountDownLatch(1);
        skills.launches.answer = launch -> {
            if (launch.script().getFileName().toString().startsWith("_load_check_")) {
                checking.countDown();
                try {
                    goOn.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return ok.apply(launch);
        };
        var tasks = Executors.newFixedThreadPool(2);
        try {
            Future<String> rewrite = tasks.submit(() -> write("kid", "kids_mailer", "v2"));
            assertTrue(checking.await(30, TimeUnit.SECONDS), "the rewrite is at its load check");
            assertTrue(code("kids_mailer").contains("'v2'"), "the premise: the new code is on disk, the grants not yet ended");
            assertEquals(List.of("ann", "kid", "owner"), grantedBy("kids_mailer"));

            Future<ToolResult> run = tasks.submit(() -> GrantsKit.run(found, "owner"));
            assertThrows(TimeoutException.class, () -> run.get(1_500, TimeUnit.MILLISECONDS),
                    "the run went on while the skill was being written");
            assertEquals(List.of(), skills.launches.runs(), "kid's new code was started under the owner's grant");

            goOn.countDown();
            assertTrue(rewrite.get(30, TimeUnit.SECONDS).startsWith("Skill 'kids_mailer' updated"));
            ToolResult refused = run.get(30, TimeUnit.SECONDS);
            assertFalse(refused.success(), refused.output());
            assertTrue(refused.output().startsWith("Not run: 'kids_mailer' has not been allowed"), refused.output());
        } finally {
            goOn.countDown();
            tasks.shutdownNow();
        }
        assertEquals(List.of(), skills.launches.runs(), "the run that waited was started after all");
        assertEquals(List.of(), vault.decrypted, "the owner's password was read for kid's new code");
        assertEquals(List.of("kid"), grantedBy("kids_mailer"));
        // Mutation: a run that does not take the skill's lock -> it starts kid's new code, already on
        // disk, with the owner's password, while the rewrite is still at its load check.
    }
}
