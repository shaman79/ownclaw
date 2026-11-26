package com.ownclaw.agent.tools;

import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.sandbox.ContainerSandbox;
import com.ownclaw.sandbox.HostPackagesKit;
import com.ownclaw.sandbox.JailTestKit;
import com.ownclaw.sandbox.SandboxManager;
import com.ownclaw.sandbox.SandboxResult;
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
 * A self-healed retry of a skill that declares system packages, on the host: the retry reads the
 * packages' directory and finds them as the first run did -- its environment is made again, and
 * its PATH with it.
 */
class HostPackagesRetryTest {

    @Test
    @DisplayName("the retry after a missing module is installed still reads the packages and finds them")
    void theRetryHasThePackages(@TempDir Path tmp) throws Exception {
        var envs = new ArrayList<Map<String, String>>();
        var reads = new ArrayList<List<Path>>();
        SandboxManager host = new SandboxManager() {
            @Override
            public SandboxResult execute(String python, Path script, Path dir, String stdin, Map<String, String> env,
                                         int timeout, ProgressCallback progress, List<Path> readOnly) {
                envs.add(Map.copyOf(env));
                reads.add(List.copyOf(readOnly));
                return envs.size() == 1
                        ? DynamicSkillSelfHealNameTest.answered(DynamicSkillSelfHealNameTest.missing("pytesseract"), stdin)
                        : new SandboxResult(0, "{\"success\": true, \"output\": \"read it\"}", "", 1, false);
            }

            @Override
            public Optional<String> unavailable() {
                return Optional.empty();
            }
        };
        var installs = new PythonEnvironmentService(new OwnClawConfig(), JailTestKit.off()) {
            @Override public void installPackages(Path skillDir, String skillName, List<String> packages,
                                                  java.util.function.BooleanSupplier stopped, Runnable waiting) {
            }
        };
        var noRuntime = new ContainerSandbox(new OwnClawConfig()) {
            @Override public boolean isAvailable() { return false; }
        };
        Path dir = Files.createDirectories(tmp.resolve("skills/ocr_image_to_text"));
        Files.writeString(dir.resolve("skill.py"), "import pytesseract\ndef run(params):\n    return {}\n");
        var skill = new DynamicSkill("ocr_image_to_text", "reads text", Map.of(), dir, false, false, 30, host, installs,
                List.of(), null, List.of("tesseract-ocr", "tesseract-ocr-eng"), null, noRuntime, null);
        var packages = HostPackagesKit.faked(tmp.resolve("data/system-packages"));
        skill.setHostPackages(packages);

        var result = skill.execute(Map.of(), new ToolExecutionContext("u1", "t1", null, () -> false, null, List.of()));

        assertTrue(result.success(), result.output());
        assertEquals(2, envs.size(), "the first run and the retry");
        for (int i = 0; i < 2; i++) {
            String path = envs.get(i).get("PATH");
            assertTrue(path.endsWith("/root/usr/bin") && path.contains("system-packages"), i + ": " + path);
            assertNotNull(envs.get(i).get("TESSDATA_PREFIX"), String.valueOf(i));
            assertNotNull(envs.get(i).get("LD_LIBRARY_PATH"), String.valueOf(i));
            assertTrue(reads.get(i).stream().anyMatch(p -> p.endsWith("root") && p.toString().contains("system-packages")),
                    i + ": " + reads.get(i));
        }
        assertEquals(1, envs.get(1).get("PATH").split("system-packages", -1).length - 1, "added once, not twice");
        // Mutations: drop packages.applyTo(healedEnv) -> the retry's PATH lacks the packages; pass
        // handed instead of readable to the retry -> the retry cannot read them.
    }
}
