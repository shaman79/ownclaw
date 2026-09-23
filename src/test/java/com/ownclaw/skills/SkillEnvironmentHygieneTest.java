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

        var applied = PythonEnvironmentService.prune(envs, targets, Set.of(), false);
        assertTrue(Files.exists(live) && Files.exists(dead),
                "init found no directory, a scan threw, or a reload is mid-way: in that state "
                        + "every environment on the host looks orphaned and one call would have "
                        + "deleted the live ones along with the dead");
        assertTrue(applied.isEmpty());

        var dry = PythonEnvironmentService.prune(envs, targets, Set.of(), true);
        assertTrue(dry.isEmpty(),
                "the dry run is the owner's gate; if it listed the live environments as removable "
                        + "and the apply then refused, the gate would have lied first");
    }

    @Test
    @DisplayName("with a trusted live set, only the orphan goes and the result says so")
    void pruneRemovesOnlyOrphans(@TempDir Path tmp) throws Exception {
        Path envs = Files.createDirectories(tmp.resolve("_envs"));
        Path targets = Files.createDirectories(envs.resolve("_targets"));
        Path live = dirWithBytes(envs, "daily_menu_fetcher", 5_000);
        Path dead = dirWithBytes(envs, "daily_lunch_preview", 7_000);

        var result = PythonEnvironmentService.prune(envs, targets,
                Set.of("daily_menu_fetcher"), false);

        assertTrue(Files.exists(live));
        assertFalse(Files.exists(dead));
        assertEquals(1, result.size());
        assertTrue(result.get(0).removed(), "and it reports what actually happened");
    }

    @Test
    @DisplayName("a dry run touches nothing")
    void dryRunTouchesNothing(@TempDir Path tmp) throws Exception {
        Path envs = Files.createDirectories(tmp.resolve("_envs"));
        Path dead = dirWithBytes(envs, "daily_lunch_preview", 7_000);

        var result = PythonEnvironmentService.prune(envs, envs.resolve("_targets"),
                Set.of("something_else"), true);

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
    @DisplayName("deleting a tree removes all of it")
    void deleteTreeRemovesTheTree(@TempDir Path tmp) throws Exception {
        Path d = dirWithBytes(tmp, "gone", 4_096);
        Files.createDirectories(d.resolve("nested"));
        Files.write(d.resolve("nested/more.bin"), new byte[1_024]);

        PythonEnvironmentService.deleteTree(d);
        assertFalse(Files.exists(d));
    }

    // ── CPU torch: the flag, the three host commands, and the container line ──

    @Test
    @DisplayName("without a GPU the CPU index is offered on every install")
    void cpuIndexWithoutGpu() {
        assertEquals(List.of("--extra-index-url", "https://download.pytorch.org/whl/cpu"),
                PythonEnvironmentService.indexArgs(false),
                "on every install, not only when the requirements name torch: the real files "
                        + "say easyocr and pytesseract and both environments hold torch anyway, "
                        + "pulled in as a dependency — a name test would have covered none of the "
                        + "installs that filled the disk");
    }

    @Test
    @DisplayName("with a GPU, pip is left to its defaults")
    void defaultsWithGpu() {
        assertTrue(PythonEnvironmentService.indexArgs(true).isEmpty(),
                "a host that can use CUDA should get CUDA");
    }

    @Test
    @DisplayName("the three host pip commands carry the index — the builders are asserted, the "
            + "three one-line call sites are covered by reading")
    void theCommandsCarryTheIndex(@TempDir Path tmp) {
        Path req = tmp.resolve("requirements.txt");
        String idx = "--extra-index-url";

        assertTrue(PythonEnvironmentService.packagesInstallArgs("py", List.of("easyocr"), false)
                .contains(idx), "installPackages (the self-heal path that installed easyocr)");
        assertTrue(PythonEnvironmentService.requirementsInstallArgs("py", req, false)
                .contains(idx), "installRequirements");
        var target = PythonEnvironmentService.targetInstallArgs("py", req, tmp.resolve("t"), false);
        assertTrue(target.contains(idx), "ensureTargetDependencies");
        assertTrue(target.contains("--target"), "and it is still a --target install");

        assertFalse(PythonEnvironmentService.requirementsInstallArgs("py", req, true).contains(idx));
    }

    @Test
    @DisplayName("the container's pip line carries the index: a container never has a GPU")
    void theContainerLineCarriesTheIndex() {
        String line = com.ownclaw.sandbox.ContainerSandbox.pipInstallLine();
        assertTrue(line.startsWith("RUN pip install "));
        assertTrue(line.contains("--extra-index-url https://download.pytorch.org/whl/cpu"),
                "the run command never passes --gpus, so this is decided by the container, not "
                        + "the host");
        assertTrue(line.endsWith("-r /tmp/requirements.txt && rm /tmp/requirements.txt\n"));
    }
}
