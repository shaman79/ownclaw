package com.ownclaw.sandbox;

import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.skills.PythonEnvironmentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * pip and venv are the commands that run code from packages a model named, and what they write
 * is code a skill will import. These drive {@link PythonEnvironmentService} end to end against a
 * stand-in python that records every start: with the jail inactive -- production today -- and
 * with it active, through a stand-in bubblewrap that records every call.
 */
class PythonEnvPipWiringTest {

    @TempDir Path tmp;

    private JailTestKit.Mirror m;
    private Path launches;
    private Path venvFails;
    private Path python;

    @BeforeEach
    void setUp() throws IOException {
        assumeTrue(Files.isExecutable(Path.of("/bin/sh")) && Files.isDirectory(Path.of("/proc/self")),
                "the stand-ins are shell scripts that read /proc");
        m = JailTestKit.Mirror.create(tmp);
        launches = Files.createDirectories(tmp.resolve("launches"));
        venvFails = tmp.resolve("venv-fails");
        python = JailTestKit.fakePython(tmp.resolve("bin/python"), launches, venvFails);
    }

    private SkillJail inactiveJail() {
        var jail = new SkillJail("auto", tmp.resolve("no/such/bwrap").toString(), m.layout(), List.of(),
                System::currentTimeMillis);
        assertEquals(SkillJail.State.INACTIVE, jail.status().state());
        return jail;
    }

    private PythonEnvironmentService service(SkillJail jail) {
        var cfg = new OwnClawConfig();
        cfg.getSkills().setGeneratedPath(m.generated().toString());
        cfg.getDatabase().setPath(m.db().toString());
        cfg.getSandbox().setPythonPath(python.toString());
        var svc = new PythonEnvironmentService(cfg, jail);
        svc.init();
        return svc;
    }

    private Path venv() {
        return m.root().resolve("skills/_envs/probe_skill");
    }

    private Path target() {
        return m.root().resolve("skills/_envs/_targets/probe_skill");
    }

    private Path requirements() {
        return m.skillDir().resolve("requirements.txt");
    }

    private List<JailTestKit.Launched> launched() throws IOException {
        return JailTestKit.launched(launches);
    }

    private long pipInstalls() throws IOException {
        return launched().stream().filter(JailTestKit.Launched::pipInstall).count();
    }

    /** The environment every Python the service starts must get: the jail's allow-list. */
    private static Map<String, String> allowListed() {
        var pb = new ProcessBuilder();
        SkillJail.applyEnvironment(pb, Map.of());
        return Map.copyOf(pb.environment());
    }

    @Test
    @DisplayName("with the jail inactive, pip and venv still get only the allow-listed environment")
    void pipNeverInheritsTheServiceEnvironment() throws Exception {
        Map<String, String> allowed = allowListed();
        // In production this JVM holds every key in .env. Without something beyond the
        // allow-list here too, the equality below would prove nothing.
        assumeTrue(System.getenv().keySet().stream().anyMatch(k -> !allowed.containsKey(k)),
                "this JVM's environment holds nothing the allow-list would remove");
        Files.writeString(requirements(), "requests\n");

        var res = service(inactiveJail()).resolveExecution(m.skillDir(), "probe_skill");

        assertEquals(venv(), res.envDir(), "the resolution names the environment a run must read");
        var all = launched();
        assertTrue(all.stream().anyMatch(l -> l.argv().containsAll(List.of("-m", "venv", venv().toString()))),
                "venv was created: " + all);
        assertTrue(all.stream().anyMatch(l -> l.pipInstall() && l.argv().contains("-r")),
                "pip installed the requirements: " + all);
        for (var l : all) {
            assertEquals(allowed, l.env(), "started with more than the allow-list: " + l.argv());
            assertFalse(l.env().containsKey("ANTHROPIC_API_KEY") || l.env().containsKey("OWNCLAW_OPS_TOKEN"));
        }
    }

    @Test
    @DisplayName("installPackages starts nothing for a name pip would read as a path, a URL or an option")
    void craftedPackageNamesStartNothing() throws Exception {
        var svc = service(inactiveJail());
        int before = launched().size();
        for (String crafted : List.of("../x", "/tmp/x", "x/y", "-e", "--index-url=https://example.invalid/simple",
                "-r", "a b", ".hidden", "..", "git+https://example.invalid/x",
                "https://example.invalid/pkg.tar.gz", "requests==2.0", "pkg.whl", "", "requests\n",
                "a".repeat(65))) {
            assertFalse(svc.installPackages(m.skillDir(), "probe_skill", List.of(crafted)), crafted);
        }
        assertFalse(svc.installPackages(m.skillDir(), "probe_skill", List.of("requests", "-e")),
                "one bad name refuses the whole call");
        assertEquals(before, launched().size(), "a Python was started for a refused name");
        assertFalse(Files.exists(requirements(), LinkOption.NOFOLLOW_LINKS),
                "a refused name was written to requirements.txt");
        assertTrue(svc.getLastProvisionError(m.skillDir(), "probe_skill").orElseThrow()
                .contains("not a plain package name"));

        for (String name : List.of("requests", "beautifulsoup4", "python-dateutil")) {
            assertTrue(svc.installPackages(m.skillDir(), "probe_skill", List.of(name)),
                    name + ": " + svc.getLastProvisionError(m.skillDir(), "probe_skill"));
            var last = launched().getLast();
            assertTrue(last.pipInstall(), last.argv().toString());
            assertEquals(name, last.argv().getLast());
        }
        assertTrue(Files.isRegularFile(requirements(), LinkOption.NOFOLLOW_LINKS));
        assertEquals(List.of("requests", "beautifulsoup4", "python-dateutil"), Files.readAllLines(requirements()));
    }

    @Test
    @DisplayName("a link planted at a venv's .req_hash is neither trusted nor written through")
    void aLinkAtTheVenvHashIsNotFollowed() throws Exception {
        Files.writeString(requirements(), "requests\n");
        service(inactiveJail()).resolveExecution(m.skillDir(), "probe_skill");
        Path hashFile = venv().resolve(".req_hash");
        assertTrue(Files.isRegularFile(hashFile, LinkOption.NOFOLLOW_LINKS));
        String genuine = Files.readString(hashFile);

        // A link to a file that holds the right hash must not pass for "already installed".
        // A fresh service each time, so nothing is cached in memory.
        Path forged = Files.writeString(tmp.resolve("forged-hash"), genuine);
        Files.delete(hashFile);
        Files.createSymbolicLink(hashFile, forged);
        long installs = pipInstalls();
        service(inactiveJail()).resolveExecution(m.skillDir(), "probe_skill");
        assertEquals(installs + 1, pipInstalls(), "a linked .req_hash was trusted");
        assertFalse(Files.isSymbolicLink(hashFile));
        assertEquals(genuine, Files.readString(hashFile));

        // A link to anything else must not be written through.
        Files.delete(hashFile);
        Files.createSymbolicLink(hashFile, m.env());
        service(inactiveJail()).resolveExecution(m.skillDir(), "probe_skill");
        assertEquals("OWNCLAW_OPS_TOKEN=canary-env-secret\n", Files.readString(m.env()),
                "the hash was written through the link into .env");
        assertFalse(Files.isSymbolicLink(hashFile));
        assertEquals(genuine, Files.readString(hashFile));
    }

    @Test
    @DisplayName("a link planted at a --target install's .req_hash is not written through")
    void aLinkAtTheTargetHashIsNotFollowed() throws Exception {
        Files.writeString(venvFails, "");
        Files.writeString(requirements(), "requests\n");
        Path hashFile = Files.createDirectories(target()).resolve(".req_hash");
        Files.createSymbolicLink(hashFile, m.db());

        var res = service(inactiveJail()).resolveExecution(m.skillDir(), "probe_skill");

        assertEquals(target(), res.envDir());
        assertEquals(target().toString(), res.extraEnv().get("PYTHONPATH"));
        assertTrue(launched().stream().anyMatch(l -> l.pipInstall() && l.argv().contains("--target")));
        assertEquals("canary-db-secret", Files.readString(m.db()), "the hash was written into the database");
        assertTrue(Files.isRegularFile(hashFile, LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    @DisplayName("a requirements.txt that is a link is refused: never read, handed to pip or written back")
    void aLinkAtRequirementsIsRefused() throws Exception {
        Files.createSymbolicLink(requirements(), m.env());
        var svc = service(inactiveJail());
        int before = launched().size();

        var res = svc.resolveExecution(m.skillDir(), "probe_skill");
        assertEquals(python.toString(), res.python());
        assertNull(res.envDir());

        assertFalse(svc.installPackages(m.skillDir(), "probe_skill", List.of("requests")));
        assertEquals("OWNCLAW_OPS_TOKEN=canary-env-secret\n", Files.readString(m.env()),
                "requirements.txt was written through the link into .env");
        assertTrue(Files.isSymbolicLink(requirements()));
        assertTrue(launched().subList(before, launched().size()).stream().noneMatch(l -> l.argv().contains("-r")),
                "pip was pointed at the link");
    }

    @Test
    @DisplayName("with the jail active, every Python the service starts runs inside it, writing only its own environment")
    void provisioningRunsInsideTheJail() throws Exception {
        Path calls = Files.createDirectories(tmp.resolve("calls"));
        Path bwrap = JailTestKit.recordingEach(tmp.resolve("bin/bwrap"), calls);
        var jail = new SkillJail("auto", bwrap.toString(), m.layout(), List.of(), System::currentTimeMillis);
        assertEquals(SkillJail.State.READY, jail.status().state(), jail.status().reason());
        Files.writeString(requirements(), "requests\n");
        var scratchBefore = scratchDirs();

        var res = service(jail).resolveExecution(m.skillDir(), "probe_skill");

        assertEquals(venv(), res.envDir());
        assertTrue(Files.isRegularFile(venv().resolve(".req_hash"), LinkOption.NOFOLLOW_LINKS));

        // Every Python started came through bubblewrap, with the allow-listed environment.
        var all = launched();
        assertTrue(all.stream().anyMatch(JailTestKit.Launched::pipInstall), all.toString());
        Map<String, String> allowed = allowListed();
        for (var l : all) {
            var env = new java.util.HashMap<>(l.env());
            assertEquals("1", env.remove("OWNCLAW_TEST_VIA_BWRAP"), "started beside the jail: " + l.argv());
            // The stand-in's own, from its `cd` into the working directory.
            env.remove("PWD");
            env.remove("OLDPWD");
            assertEquals(allowed, env, "started with more than the allow-list: " + l.argv());
        }

        // What bubblewrap was asked for: the environment being built is the one writable
        // directory, the skill's directory is readable, and nothing else is bound.
        var jailed = JailTestKit.calls(calls);
        assertEquals(all.size(), jailed.size(), "one bubblewrap call per Python started: "
                + jailed.stream().map(JailTestKit::command).toList());
        int provisioning = 0;
        for (List<String> argv : jailed) {
            var ops = JailTestKit.ops(argv);
            List<String> cmd = JailTestKit.command(argv);
            List<List<String>> rw = ops.stream().filter(o -> o.flag().equals("--bind")
                    && !o.args().get(1).equals("/tmp")).map(JailTestKit.Op::args).toList();
            List<List<String>> ro = ops.stream().filter(o -> o.flag().equals("--ro-bind")
                    && !o.args().get(0).equals("/")).map(JailTestKit.Op::args).toList();
            String chdir = ops.stream().filter(o -> o.flag().equals("--chdir")).findFirst().orElseThrow().args().get(0);
            assertEquals(1, rw.size(), "one writable directory: " + argv);
            assertEquals(chdir, rw.get(0).get(0));
            if (Path.of(chdir).getFileName().toString().startsWith("ownclaw-python-")) {
                // A startup probe of the system python: an empty directory of its own and
                // nothing of any skill's.
                assertEquals(python.toString(), cmd.get(0), argv.toString());
                assertEquals("--version", cmd.getLast(), argv.toString());
                assertTrue(ro.isEmpty(), argv.toString());
                continue;
            }
            provisioning++;
            assertEquals(List.of(venv().toString(), venv().toString()), rw.get(0), argv.toString());
            assertEquals(List.of(List.of(m.skillDir().toString(), m.skillDir().toString())), ro, argv.toString());
        }
        assertTrue(provisioning >= 3, "venv, pip --version and pip install: " + jailed);
        assertEquals(scratchBefore, scratchDirs(), "a probe's own directory must be removed");
    }

    private static List<Path> scratchDirs() throws IOException {
        try (Stream<Path> s = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return s.filter(p -> p.getFileName().toString().startsWith("ownclaw-python-")).sorted().toList();
        }
    }

    @Test
    @DisplayName("a jail that refuses starts no Python for provisioning, and says why")
    void aRefusingJailStartsNothing() throws Exception {
        var jail = new SkillJail("required", tmp.resolve("no/such/bwrap").toString(), m.layout(), List.of(),
                System::currentTimeMillis);
        assertEquals(SkillJail.State.REFUSING, jail.status().state());
        Files.writeString(requirements(), "requests\n");
        var svc = service(jail);

        var res = svc.resolveExecution(m.skillDir(), "probe_skill");

        assertTrue(launched().isEmpty(), "a Python was started: " + launched());
        assertNull(res.envDir());
        assertFalse(Files.exists(venv().resolve(".req_hash")));
        assertTrue(svc.getLastProvisionError(m.skillDir(), "probe_skill").orElseThrow()
                .contains("isolation required but unavailable"));
        try (Stream<Path> s = Files.list(m.root().resolve("skills/_envs/_bootstrap"))) {
            assertEquals(0, s.count(), "a refusal fell through to downloading get-pip.py");
        }
    }
}
