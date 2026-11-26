package com.ownclaw.sandbox;

import com.ownclaw.agent.tools.DynamicSkill;
import com.ownclaw.agent.tools.ToolExecutionContext;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.skills.PythonEnvironmentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A skill that declares system packages, run on the host with no container runtime: its run reads
 * the packages' directory and finds their programs, libraries and data -- and when they cannot be
 * had it runs as before, and a failure says why.
 */
class SkillRunsWithHostPackagesTest {

    /** The host sandbox: what each run was given, and a result as the test says. */
    static final class Recording implements SandboxManager {
        final List<Map<String, String>> envs = new ArrayList<>();
        final List<List<Path>> reads = new ArrayList<>();
        volatile String stdout = "{\"success\": true, \"output\": \"read it\"}";

        @Override
        public SandboxResult execute(String python, Path script, Path dir, String stdin, Map<String, String> env,
                                     int timeout, ProgressCallback progress, List<Path> readOnly) {
            envs.add(Map.copyOf(env));
            reads.add(List.copyOf(readOnly));
            return new SandboxResult(stdout.contains("true") ? 0 : 1, stdout, "", 1, false);
        }

        @Override
        public Optional<String> unavailable() {
            return Optional.empty();
        }
    }

    /** No docker, no podman: production's case. */
    static final class NoRuntime extends ContainerSandbox {
        NoRuntime() { super(new OwnClawConfig()); }
        @Override public boolean isAvailable() { return false; }
    }

    private static DynamicSkill skill(Path tmp, Recording host, HostPackages packages) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("skills/ocr_image_to_text"));
        Files.writeString(dir.resolve("skill.py"), "def run(params):\n    return {}\n");
        var skill = new DynamicSkill("ocr_image_to_text", "reads text", Map.of(), dir, false, false, 30, host,
                new PythonEnvironmentService(new OwnClawConfig(), JailTestKit.off()), List.of(), null,
                List.of("tesseract-ocr", "tesseract-ocr-eng"), null, new NoRuntime(), null);
        skill.setHostPackages(packages);
        return skill;
    }

    @Test
    @DisplayName("the run reads the packages' directory, finds their programs after its own PATH, and their libraries and data")
    void theRunFindsThem(@TempDir Path tmp) throws Exception {
        var host = new Recording();
        var packages = new HostPackages(tmp.resolve("data/system-packages"), new HostPackagesTest.FakeApt());
        var said = new ArrayList<String>();
        var context = new ToolExecutionContext("u1", "t1", null, () -> false, (line, data) -> said.add(line), List.of());

        var result = skill(tmp, host, packages).execute(Map.of(), context);

        assertTrue(result.success(), result.output());
        Path root = packages.store().resolve(HostPackages.key(List.of("tesseract-ocr", "tesseract-ocr-eng"))).resolve("root");
        assertTrue(host.reads.getFirst().contains(root), "read-only: " + host.reads.getFirst());
        var env = host.envs.getFirst();
        assertTrue(env.get("PATH").endsWith(":" + root.resolve("usr/bin")), env.get("PATH"));
        assertTrue(env.get("LD_LIBRARY_PATH").startsWith(root.resolve("usr/lib/x86_64-linux-gnu").toString()), env.get("LD_LIBRARY_PATH"));
        assertEquals(root.resolve("usr/share/tesseract-ocr/5/tessdata").toString(), env.get("TESSDATA_PREFIX"));
        assertTrue(said.stream().anyMatch(l -> l.startsWith("Installing system packages tesseract-ocr, tesseract-ocr-eng")), said.toString());
        // Mutation: drop readable.addAll(packages.binds()) -> the run cannot read the directory.
    }

    @Test
    @DisplayName("packages that cannot be had: the skill runs as before, and its failure says why")
    void withoutThemTheFailureSaysWhy(@TempDir Path tmp) throws Exception {
        var host = new Recording();
        host.stdout = "{\"success\": false, \"error\": \"tesseract is not installed or it's not in your PATH\"}";
        var apt = new HostPackagesTest.FakeApt();
        apt.simulateExit = 100;
        var packages = new HostPackages(tmp.resolve("data/system-packages"), apt);
        var context = new ToolExecutionContext("u1", "t1", null, () -> false, null, List.of());

        var result = skill(tmp, host, packages).execute(Map.of(), context);

        assertFalse(result.success());
        assertTrue(result.output().contains("system packages not installed"), result.output());
        assertTrue(result.output().contains("Unable to locate package"), result.output());
        assertEquals(1, host.envs.size(), "it ran");
        assertFalse(host.envs.getFirst().containsKey("TESSDATA_PREFIX"));
    }
}
