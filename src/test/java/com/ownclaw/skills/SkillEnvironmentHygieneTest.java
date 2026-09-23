package com.ownclaw.skills;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Why the production host was at 96% disk.
 * <p>
 * 59 GB of a 99 GB disk was per-skill Python environments. Two defects, both here: deleting a
 * skill never removed its environment (32 orphans, 45.7 GB), and pip was left to resolve
 * {@code torch} on its own, which on Linux means the CUDA build — 7.4 GB each, five times over,
 * on a four-core VM with no GPU, so that a skill could OCR a lunch menu.
 */
class SkillEnvironmentHygieneTest {

    private static Path dirWithBytes(Path parent, String name, int bytes) throws Exception {
        Path d = Files.createDirectories(parent.resolve(name));
        Files.write(d.resolve("payload.bin"), new byte[bytes]);
        return d;
    }

    @Test
    @DisplayName("an environment whose skill no longer exists is an orphan")
    void orphansAreEnvsWithoutASkill(@TempDir Path tmp) throws Exception {
        Path envs = Files.createDirectories(tmp.resolve("_envs"));
        Path targets = Files.createDirectories(envs.resolve("_targets"));
        dirWithBytes(envs, "daily_menu_fetcher", 10);          // live
        dirWithBytes(envs, "daily_lunch_preview", 7_000);      // deleted in March
        dirWithBytes(targets, "daily_lunch_preview", 7_000);   // its --target twin
        dirWithBytes(targets, "daily_menu_fetcher", 10);       // live

        var orphans = PythonEnvironmentService.findOrphans(envs, targets,
                Set.of("daily_menu_fetcher", "daily_news_digest"));

        assertEquals(2, orphans.size(), "one env and one target, both for the dead skill");
        assertTrue(orphans.stream().allMatch(o -> o.skill().equals("daily_lunch_preview")));
        assertEquals(14_000, orphans.stream().mapToLong(o -> o.bytes()).sum(),
                "the size is what tells the owner whether it is worth pressing the button");
    }

    @Test
    @DisplayName("the service's own directories are never orphans")
    void bootstrapAndTargetsAreNotCandidates(@TempDir Path tmp) throws Exception {
        Path envs = Files.createDirectories(tmp.resolve("_envs"));
        Path targets = Files.createDirectories(envs.resolve("_targets"));
        dirWithBytes(envs, "_bootstrap", 100);

        var orphans = PythonEnvironmentService.findOrphans(envs, targets, Set.of());

        assertTrue(orphans.isEmpty(),
                "_bootstrap holds get-pip and _targets is a root, not a skill; deleting either "
                        + "would break every provisioning that follows");
    }

    @Test
    @DisplayName("a live skill's environment is left alone")
    void liveEnvsAreKept(@TempDir Path tmp) throws Exception {
        Path envs = Files.createDirectories(tmp.resolve("_envs"));
        dirWithBytes(envs, "ocr_image_to_text", 7_000);

        assertTrue(PythonEnvironmentService.findOrphans(envs, envs.resolve("_targets"),
                Set.of("ocr_image_to_text")).isEmpty());
    }

    @Test
    @DisplayName("deleting a tree reports what it freed")
    void deleteTreeReturnsBytes(@TempDir Path tmp) throws Exception {
        Path d = dirWithBytes(tmp, "gone", 4_096);
        Files.createDirectories(d.resolve("nested"));
        Files.write(d.resolve("nested/more.bin"), new byte[1_024]);

        assertEquals(5_120, PythonEnvironmentService.deleteTree(d));
        assertFalse(Files.exists(d));
    }

    @Test
    @DisplayName("without a GPU, pip is pointed at the CPU torch index")
    void cpuIndexWithoutGpu() {
        List<String> args = PythonEnvironmentService.indexArgs(false);
        assertEquals(List.of("--extra-index-url", "https://download.pytorch.org/whl/cpu"), args,
                "the +cpu wheel of the same version sorts above the bare one, so pip takes it "
                        + "and never pulls the nvidia-* libraries at all");
    }

    @Test
    @DisplayName("with a GPU, pip is left to its defaults")
    void defaultsWithGpu() {
        assertTrue(PythonEnvironmentService.indexArgs(true).isEmpty(),
                "a host that can use CUDA should get CUDA");
    }
}
