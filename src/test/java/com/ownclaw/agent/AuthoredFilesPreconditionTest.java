package com.ownclaw.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownclaw.agent.tools.DynamicSkillRegistry;
import com.ownclaw.agent.tools.ToolExecutionContext;
import com.ownclaw.agent.tools.ToolRegistry;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.conversation.MigratedDatabase;
import com.ownclaw.interfaces.web.SkillController;
import com.ownclaw.sandbox.JailTestKit;
import com.ownclaw.sandbox.ProcessSandbox;
import com.ownclaw.sandbox.RealBubblewrap;
import com.ownclaw.sandbox.SkillJail;
import com.ownclaw.skills.PythonEnvironmentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * SKILL.yaml, skill.py and requirements.txt are what a skill was written as: the credentials it
 * may ask for, the code that runs, the packages pip installs. The JVM reads and writes them in a
 * directory the skill itself can write, so each must be a regular file -- a link planted there
 * (to .env, say) is refused, never read, written through or shown to the model -- and all three
 * always exist, so there is no missing name for the skill to fill in.
 */
class AuthoredFilesPreconditionTest {

    @TempDir Path root;

    private static final String SECRET = "OWNCLAW_OPS_TOKEN=canary-env-secret\n";
    private static final String PARAMS = "{\"name\": {\"type\": \"string\"}}";
    private static final String CODE = "def run(params):\n    return {'output': 'hello'}\n";

    private Path generated;
    private Path secret;
    private SkillManager manager;
    private DynamicSkillRegistry dynamic;
    private ToolRegistry tools;
    private PythonEnvironmentService env;

    private void setUp(SkillJail jail) throws Exception {
        assumeTrue(pythonAvailable(), "python3 is not available");
        generated = Files.createDirectories(root.resolve("skills/generated"));
        secret = Files.writeString(root.resolve(".env"), SECRET);
        var cfg = new OwnClawConfig();
        cfg.getSkills().setGeneratedPath(generated.toString());
        cfg.getDatabase().setPath(root.resolve("data/ownclaw.db").toString());
        cfg.getSandbox().setPythonPath("python3");
        env = new PythonEnvironmentService(cfg, jail);
        env.init();
        var sandbox = new ProcessSandbox(jail);
        tools = new ToolRegistry(List.of());
        dynamic = new DynamicSkillRegistry(cfg, tools, sandbox, null, env, null, null);
        manager = new SkillManager(dynamic, tools, cfg, sandbox, env, null);
    }

    private String create(String code, String requirements) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("name", "greet_user");
        p.put("description", "Says hello.");
        p.put("parameters", PARAMS);
        p.put("code", code);
        if (requirements != null) p.put("requirements", requirements);
        return manager.createSkill(p);
    }

    private Path skillDir() {
        return generated.resolve("greet_user");
    }

    @ParameterizedTest
    @ValueSource(strings = {"SKILL.yaml", "skill.py", "requirements.txt"})
    @DisplayName("an authored file that is a link is refused: its target is neither read, written nor shown")
    void aLinkedAuthoredFileIsRefused(String file) throws Exception {
        setUp(JailTestKit.off());
        assertFalse(create(CODE, null).startsWith("ERROR"));
        Path planted = skillDir().resolve(file);
        Files.delete(planted);
        Files.createSymbolicLink(planted, secret);

        String result = create("def run(params):\n    return {'output': 'updated'}\n", null);

        assertTrue(result.startsWith("ERROR") && result.contains("tampered"), result);
        assertFalse(result.contains("canary-env-secret"), result);
        assertEquals(SECRET, Files.readString(secret), "written through the link");
        assertTrue(Files.isSymbolicLink(planted), "the link was replaced rather than refused");

        String shown = manager.readSkill("greet_user");
        assertFalse(shown.contains("canary-env-secret"), "the link's target reached the model: " + shown);
        String code = manager.readSkillCode("greet_user");
        assertTrue(code == null || !code.contains("canary-env-secret"));
        String patched = manager.patchCredentials("greet_user", "SMTP_USER");
        assertFalse(patched.contains("canary-env-secret"), patched);
        assertEquals(SECRET, Files.readString(secret), "patchCredentials wrote through the link");

        // The skills page reads the same files for the browser, by the same rule.
        var jdbc = MigratedDatabase.at(root.resolve("page.db"));
        var page = new SkillController(manager, dynamic, new SkillCuratorService(jdbc, null, tools, dynamic), null)
                .getSkill("greet_user").getBody();
        assertNotNull(page);
        assertFalse(String.valueOf(page).contains("canary-env-secret"), "the link's target reached the page: " + page);
        assertTrue(String.valueOf(page.get("error")).contains("tampered"), String.valueOf(page));
    }

    @ParameterizedTest
    @ValueSource(strings = {"SKILL.yaml", "skill.py", "requirements.txt"})
    @DisplayName("an authored file that is a hard link to .env is refused: never read, shown or written")
    void aHardLinkedAuthoredFileIsRefused(String file) throws Exception {
        setUp(JailTestKit.off());
        assertFalse(create(CODE, null).startsWith("ERROR"));
        // Made while nothing was jailed, it is a regular file to every other check.
        Path planted = skillDir().resolve(file);
        Files.delete(planted);
        Files.createLink(planted, secret);
        assertTrue(Files.isRegularFile(planted, LinkOption.NOFOLLOW_LINKS));

        String result = create("def run(params):\n    return {'output': 'updated'}\n", null);
        assertTrue(result.startsWith("ERROR") && result.contains(file + " has another name (a hard link)"), result);
        assertFalse(result.contains("canary-env-secret"), result);

        String shown = manager.readSkill("greet_user");
        assertFalse(shown.contains("canary-env-secret"), "the hard link's other name reached the model: " + shown);
        String code = manager.readSkillCode("greet_user");
        assertTrue(code == null || !code.contains("canary-env-secret"));

        var refused = assertThrows(java.io.IOException.class, () -> SkillJail.writeAuthored(planted, "overwritten\n"));
        assertTrue(refused.getMessage().contains("hard link"), refused.getMessage());
        assertEquals(SECRET, Files.readString(secret), "written through the hard link");
    }

    @ParameterizedTest
    @ValueSource(strings = {"SKILL.yaml", "skill.py", "requirements.txt"})
    @DisplayName("a link a skill leaves at an authored file while it is checked is not written through by the restore")
    void theRestoreNeverWritesThroughALink(String file) throws Exception {
        setUp(JailTestKit.off());
        assertFalse(create(CODE, null).startsWith("ERROR"));
        // Unjailed -- as every skill runs until bubblewrap is installed -- the load check runs the
        // module's top level in the skill's own directory. This one swaps the file for a link and
        // then fails to import, so the previous version is written back over that very name.
        String swaps = "import os\n"
                + "os.remove('" + file + "')\n"
                + "os.symlink(" + pyString(secret) + ", '" + file + "')\n"
                + "raise RuntimeError('does not load')\n"
                + CODE;

        String result = create(swaps, null);

        assertTrue(result.startsWith("ERROR"), result);
        assertEquals(SECRET, Files.readString(secret), "the restore wrote through the link");
    }

    private static String pyString(Path p) {
        return "'" + p.toString().replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    @Test
    @DisplayName("a skill with no requirements gets an empty regular requirements.txt, and runs on system Python")
    void noRequirementsMeansAnEmptyFile() throws Exception {
        setUp(JailTestKit.off());
        Path req = generated.resolve("greet_user/requirements.txt");

        assertFalse(create(CODE, null).startsWith("ERROR"));
        assertTrue(Files.isRegularFile(req, LinkOption.NOFOLLOW_LINKS), "no requirements.txt was written");
        assertEquals(0, Files.size(req));
        var resolution = env.resolveExecution(skillDir(), "greet_user");
        assertEquals("python3", resolution.python());
        assertNull(resolution.envDir());
        assertEquals(Map.of(), resolution.extraEnv());

        // An update that drops its requirements empties the file rather than deleting it.
        Files.writeString(req, "requests\n");
        assertFalse(create(CODE, "").startsWith("ERROR"));
        assertTrue(Files.isRegularFile(req, LinkOption.NOFOLLOW_LINKS));
        assertEquals(0, Files.size(req));

        // So does restoring a version that had none, after a broken update.
        assertTrue(create("import missing_package_xyz\n" + CODE, null).startsWith("ERROR"));
        assertTrue(Files.isRegularFile(req, LinkOption.NOFOLLOW_LINKS), "the restore deleted requirements.txt");
        assertEquals(0, Files.size(req));

        // And one that had no file at all -- a skill written before every skill got one.
        Files.delete(req);
        assertTrue(create("import missing_package_xyz\n" + CODE, null).startsWith("ERROR"));
        assertTrue(Files.isRegularFile(req, LinkOption.NOFOLLOW_LINKS),
                "restoring a version without requirements.txt left the name free");
        assertEquals(0, Files.size(req));
    }

    @Test
    @DisplayName("jailed for real: a skill can write its own directory but none of its authored files")
    void aJailedSkillCannotRewriteItsAuthoredFiles() throws Exception {
        RealBubblewrap.assumeWorks();
        Path db = Files.writeString(Files.createDirectories(root.resolve("data")).resolve("ownclaw.db"), "canary-db");
        SkillJail jail = RealBubblewrap.requiredJail(
                new SkillJail.Layout(root, db, root.resolve("skills/generated"), null));
        setUp(jail);
        String code = """
                import json, os
                def run(params):
                    def appendable(name):
                        try:
                            with open(name, 'a') as f:
                                f.write('# written by the skill\\n')
                            return True
                        except Exception:
                            return False
                    def replaceable(name):
                        try:
                            os.rename(name, name + '.old')
                            return True
                        except Exception:
                            return False
                    r = {n: appendable(n) or replaceable(n) for n in ['SKILL.yaml', 'skill.py', 'requirements.txt']}
                    r['own_dir'] = appendable('notes.txt')
                    return {'output': json.dumps(r)}
                """;
        assertFalse(create(code, null).startsWith("ERROR"));
        var before = new LinkedHashMap<String, String>();
        for (String f : SkillJail.AUTHORED) before.put(f, Files.readString(skillDir().resolve(f)));

        var run = tools.find("greet_user").orElseThrow()
                .execute(Map.of(), new ToolExecutionContext("u1", "t1", null, () -> false));

        assertTrue(run.success(), run.output());
        JsonNode seen = new ObjectMapper().readTree(run.output());
        for (String f : SkillJail.AUTHORED) {
            assertFalse(seen.get(f).asBoolean(), f + " could be changed from inside the jail");
            assertEquals(before.get(f), Files.readString(skillDir().resolve(f)), f);
        }
        assertTrue(seen.get("own_dir").asBoolean());
        assertTrue(Files.exists(skillDir().resolve("notes.txt")), "a write to its own directory must reach the host");
        assertEquals(0, jail.status().directRuns());
    }

    @Test
    @DisplayName("jailed for real: updating a skill while it runs leaves that run's authored files read-only")
    void anUpdateDuringARunKeepsItsAuthoredFilesReadOnly() throws Exception {
        RealBubblewrap.assumeWorks();
        Path db = Files.writeString(Files.createDirectories(root.resolve("data")).resolve("ownclaw.db"), "canary-db");
        SkillJail jail = RealBubblewrap.requiredJail(
                new SkillJail.Layout(root, db, root.resolve("skills/generated"), null));
        setUp(jail);
        // Says it is running, waits to be told the update is done, then tries every way in.
        String code = """
                import json, os, time
                def run(params):
                    open('running', 'w').close()
                    for _ in range(300):
                        if os.path.exists('updated'):
                            break
                        time.sleep(0.1)
                    def changeable(name):
                        try:
                            with open(name, 'a') as f:
                                f.write('# written by the skill\\n')
                            return True
                        except Exception:
                            pass
                        try:
                            os.rename(name, name + '.old')
                            return True
                        except Exception:
                            return False
                    r = {n: changeable(n) for n in ['SKILL.yaml', 'skill.py', 'requirements.txt']}
                    r['sees_update'] = 'updated version' in open('SKILL.yaml').read()
                    return {'output': json.dumps(r)}
                """;
        String created = create(code, null);
        assertFalse(created.startsWith("ERROR"), created);
        var running = java.util.concurrent.CompletableFuture.supplyAsync(() -> tools.find("greet_user").orElseThrow()
                .execute(Map.of(), new ToolExecutionContext("u1", "t1", null, () -> false)));
        long deadline = System.currentTimeMillis() + 20_000;
        while (!Files.exists(skillDir().resolve("running")) && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertTrue(Files.exists(skillDir().resolve("running")), "the skill never started");

        // The update SkillManager makes while that run is still going: every authored file rewritten.
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("name", "greet_user");
        p.put("description", "Says hello, updated version.");
        p.put("parameters", PARAMS);
        p.put("code", code);
        String updated = manager.createSkill(p);
        assertFalse(updated.startsWith("ERROR"), updated);
        var after = new LinkedHashMap<String, String>();
        for (String f : SkillJail.AUTHORED) after.put(f, Files.readString(skillDir().resolve(f)));
        Files.createFile(skillDir().resolve("updated"));

        var run = running.get(60, java.util.concurrent.TimeUnit.SECONDS);
        assertTrue(run.success(), run.output());
        JsonNode seen = new ObjectMapper().readTree(run.output());
        for (String f : SkillJail.AUTHORED) {
            assertFalse(seen.get(f).asBoolean(), f + " became writable to the running skill after the update: " + seen);
            assertEquals(after.get(f), Files.readString(skillDir().resolve(f)), f);
        }
        assertTrue(seen.get("sees_update").asBoolean(), "the update was not written in place");
    }

    private static boolean pythonAvailable() {
        try {
            return new ProcessBuilder("python3", "--version").start().waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    @DisplayName("quarantining a skill that left a link at the note's name writes the note, never through the link")
    void theQuarantineNoteIsNotWrittenThroughALink() throws Exception {
        setUp(JailTestKit.off());
        Path dir = Files.createDirectories(generated.resolve("bad_skill"));
        Files.writeString(dir.resolve("skill.py"), "raise SystemExit\n");
        Files.createSymbolicLink(dir.resolve("QUARANTINE-REASON.txt"), secret);

        var moved = dynamic.quarantineUnregistered(dir, "does not import");

        assertTrue(moved.isPresent());
        assertEquals(SECRET, Files.readString(secret), "the link's target is untouched");
        Path note = moved.get().resolve("QUARANTINE-REASON.txt");
        assertTrue(Files.isRegularFile(note, java.nio.file.LinkOption.NOFOLLOW_LINKS));
        assertTrue(Files.readString(note).contains("does not import"));
        // Mutation: the note written as before -> .env overwritten with the reason.
    }

    @Test
    @DisplayName("a SKILL.yaml that is a link is refused at load, not parsed through it")
    void aLinkedYamlIsNotLoaded() throws Exception {
        setUp(JailTestKit.off());
        Path dir = Files.createDirectories(generated.resolve("linked_skill"));
        Files.writeString(dir.resolve("skill.py"), CODE);
        Path elsewhere = Files.writeString(root.resolve("elsewhere.yaml"),
                "name: linked_skill\ndescription: read from wherever the link pointed\n");
        Files.createSymbolicLink(dir.resolve("SKILL.yaml"), elsewhere);

        var e = assertThrows(java.io.IOException.class, () -> dynamic.loadSkill(dir));
        assertTrue(e.getMessage().contains("not a regular file"), e.getMessage());
        // Mutation: the YAML read through the link -> the skill loads with that description.
    }
}
