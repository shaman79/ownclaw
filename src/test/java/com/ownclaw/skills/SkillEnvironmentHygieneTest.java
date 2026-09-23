package com.ownclaw.skills;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
 * <p>
 * The review of the first fix then found three ways the fix itself could delete something live,
 * and that its mutation claim covered the flag but not the commands. Those are the tests below
 * the first block.
 */
class SkillEnvironmentHygieneTest {

    private static Path dirWithBytes(Path parent, String name, int bytes) throws Exception {
        Path d = Files.createDirectories(parent.resolve(name));
        Files.write(d.resolve("payload.bin"), new byte[bytes]);
        return d;
    }

    // ── the orphan rule ──

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

        assertTrue(PythonEnvironmentService.findOrphans(envs, targets, Set.of()).isEmpty(),
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

    // ── the prune must not be able to delete something live ──

    @Test
    @DisplayName("an empty live set means the registry cannot be trusted: nothing is removed")
    void emptyRegistryRemovesNothing(@TempDir Path tmp) throws Exception {
        Path envs = Files.createDirectories(tmp.resolve("_envs"));
        Path targets = Files.createDirectories(envs.resolve("_targets"));
        Path live = dirWithBytes(envs, "daily_menu_fetcher", 5_000);
        Path dead = dirWithBytes(envs, "daily_lunch_preview", 7_000);

        var result = PythonEnvironmentService.prune(envs, targets, Set.of(), false, n -> { });

        assertTrue(Files.exists(live) && Files.exists(dead),
                "init found no directory, a scan threw, or a reload is mid-way: in that state "
                        + "every environment on the host looks orphaned and one call would have "
                        + "deleted the live ones along with the dead");
        assertTrue(result.stream().noneMatch(o -> o.removed()));
    }

    @Test
    @DisplayName("with a trusted live set, only the orphan goes and the result says so")
    void pruneRemovesOnlyOrphans(@TempDir Path tmp) throws Exception {
        Path envs = Files.createDirectories(tmp.resolve("_envs"));
        Path targets = Files.createDirectories(envs.resolve("_targets"));
        Path live = dirWithBytes(envs, "daily_menu_fetcher", 5_000);
        Path dead = dirWithBytes(envs, "daily_lunch_preview", 7_000);
        var forgotten = new ArrayList<String>();

        var result = PythonEnvironmentService.prune(envs, targets,
                Set.of("daily_menu_fetcher"), false, forgotten::add);

        assertTrue(Files.exists(live));
        assertFalse(Files.exists(dead));
        assertEquals(1, result.size());
        assertTrue(result.get(0).removed(), "and it reports what actually happened");
        assertEquals(List.of("daily_lunch_preview"), forgotten,
                "the provisioning cache must forget it, or a restored skill is handed the path "
                        + "of an interpreter that no longer exists until the next restart");
    }

    @Test
    @DisplayName("a dry run touches nothing")
    void dryRunTouchesNothing(@TempDir Path tmp) throws Exception {
        Path envs = Files.createDirectories(tmp.resolve("_envs"));
        Path dead = dirWithBytes(envs, "daily_lunch_preview", 7_000);

        var result = PythonEnvironmentService.prune(envs, envs.resolve("_targets"),
                Set.of("something_else"), true, n -> fail("nothing should be forgotten"));

        assertTrue(Files.exists(dead));
        assertEquals(1, result.size());
        assertFalse(result.get(0).removed());
    }

    @Test
    @DisplayName("removing a skill's environment cannot reach outside the roots")
    void removeEnvironmentsRefusesTraversal(@TempDir Path tmp) throws Exception {
        Path skills = Files.createDirectories(tmp.resolve("skills"));
        Path envs = Files.createDirectories(skills.resolve("_envs"));
        Path targets = Files.createDirectories(envs.resolve("_targets"));
        Path sibling = dirWithBytes(skills, "generated", 10);   // what ".." would reach
        Path mine = dirWithBytes(envs, "daily_menu_fetcher", 10);
        var forgotten = new ArrayList<String>();

        PythonEnvironmentService.removeEnvironments(envs, targets, "..", forgotten::add);
        assertTrue(Files.exists(sibling) && Files.exists(skills),
                "the reviewer's probe: \"..\" resolved under _envs and recursively deleted");
        PythonEnvironmentService.removeEnvironments(envs, targets, "../generated", forgotten::add);
        assertTrue(Files.exists(sibling));

        PythonEnvironmentService.removeEnvironments(envs, targets, "daily_menu_fetcher",
                forgotten::add);
        assertFalse(Files.exists(mine), "a plain name is removed as intended");
        assertTrue(forgotten.contains("daily_menu_fetcher"));
    }

    @Test
    @DisplayName("only a direct child of the root may be removed")
    void traversalNamesAreRefused(@TempDir Path tmp) throws Exception {
        Path envs = Files.createDirectories(tmp.resolve("_envs"));
        // A name comes from SKILL.yaml, which the boot scan takes verbatim. ".." resolved under
        // _envs and handed to a recursive delete removed the parent directory in a probe.
        assertFalse(PythonEnvironmentService.isDirectChild(envs, envs.resolve("..")));
        assertFalse(PythonEnvironmentService.isDirectChild(envs, envs.resolve("../data")));
        assertFalse(PythonEnvironmentService.isDirectChild(envs, Path.of("/")));
        assertFalse(PythonEnvironmentService.isDirectChild(envs, envs.resolve("a/b")));
        assertFalse(PythonEnvironmentService.isDirectChild(envs, envs), "the root itself");
        assertTrue(PythonEnvironmentService.isDirectChild(envs, envs.resolve("daily_menu_fetcher")));
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

    // ── CPU torch: the flag AND the commands that carry it ──

    @Test
    @DisplayName("without a GPU, a torch requirement gets the CPU index")
    void cpuIndexForTorchWithoutGpu() {
        assertEquals(List.of("--extra-index-url", "https://download.pytorch.org/whl/cpu"),
                PythonEnvironmentService.indexArgs(false, "easyocr\ntorch>=2.0\npillow"),
                "the +cpu wheel of the same version sorts above the bare one, so pip takes it "
                        + "and never pulls the nvidia-* libraries at all");
        assertEquals(List.of("--extra-index-url", "https://download.pytorch.org/whl/cpu"),
                PythonEnvironmentService.indexArgs(false, "torchvision==0.19"));
    }

    @Test
    @DisplayName("a requirement without torch is installed exactly as before")
    void noIndexWithoutTorch() {
        assertTrue(PythonEnvironmentService.indexArgs(false, "requests\nbeautifulsoup4").isEmpty(),
                "pip has no index priority: an extra index is queried for every package of "
                        + "every skill, and an outage there costs retries on all of them");
        assertTrue(PythonEnvironmentService.indexArgs(false, "").isEmpty());
        assertTrue(PythonEnvironmentService.indexArgs(false, null).isEmpty());
    }

    @Test
    @DisplayName("with a GPU, pip is left to its defaults")
    void defaultsWithGpu() {
        assertTrue(PythonEnvironmentService.indexArgs(true, "torch").isEmpty(),
                "a host that can use CUDA should get CUDA");
    }

    @Test
    @DisplayName("every pip command carries the index when it applies — not only the flag")
    void theCommandsCarryTheIndex(@TempDir Path tmp) {
        // The first fix's mutation claim covered indexArgs() and not one of the commands using
        // it; dropping the wiring from any path left every test green.
        Path req = tmp.resolve("requirements.txt");
        String idx = "--extra-index-url";

        assertTrue(PythonEnvironmentService.packagesInstallArgs("py", List.of("torch"), false)
                .contains(idx), "installPackages");
        assertTrue(PythonEnvironmentService.requirementsInstallArgs("py", req, "torch", false)
                .contains(idx), "installRequirements");
        var target = PythonEnvironmentService.targetInstallArgs("py", req, "torch",
                tmp.resolve("t"), false);
        assertTrue(target.contains(idx), "ensureTargetDependencies");
        assertTrue(target.contains("--target"), "and it is still a --target install");

        assertFalse(PythonEnvironmentService.packagesInstallArgs("py", List.of("requests"), false)
                .contains(idx));
        assertFalse(PythonEnvironmentService.requirementsInstallArgs("py", req, "torch", true)
                .contains(idx));
    }
}
