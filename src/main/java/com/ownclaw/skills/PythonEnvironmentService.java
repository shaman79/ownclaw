package com.ownclaw.skills;

import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.sandbox.ProcessSandbox;
import com.ownclaw.sandbox.SkillJail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.stream.Stream;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Manages Python virtual environments for skill execution.
 * Auto-creates venvs and installs requirements.txt on first use.
 * Caches a hash of requirements to skip re-install when unchanged.
 * <p>
 * Every Python it starts -- venv, pip, the version probes -- goes through {@link SkillJail},
 * like a skill's own run: see {@link #run}.
 */
@Service
public class PythonEnvironmentService {

    private static final Logger log = LoggerFactory.getLogger(PythonEnvironmentService.class);
    private static final URI GET_PIP_URI = URI.create("https://bootstrap.pypa.io/get-pip.py");

    private volatile String systemPython;
    private volatile Path envsDir;
    private volatile Path targetsDir;
    private volatile Path bootstrapDir;

    /** Tracks which skill dirs have been provisioned this session (avoid redundant checks). */
    private final Map<String, String> provisionedHashes = new ConcurrentHashMap<>();

    /** Captures the last provisioning error per skill+dir (for user-facing diagnostics). */
    private final Map<String, String> lastProvisionErrors = new ConcurrentHashMap<>();

    private final OwnClawConfig config;
    private final SkillJail jail;

    /**
     * What a package name handed to pip may look like: letters, digits, '_' and '-', not
     * starting with '-', at most 64 characters.
     * <p>
     * The self-heal takes the name from a skill's own error output, and pip reads far more than
     * names from its command line: a path builds that directory (running its setup code), a URL
     * fetches and builds whatever is there, and a leading '-' is an option ({@code -e},
     * {@code --index-url}). None of those can pass this, and every name the self-heal produces
     * for a real missing module does. The self-heal checks the name it read with the same
     * pattern pip's caller checks here, so the two cannot drift apart.
     */
    private static final Pattern PLAIN_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,63}");

    /** True when {@code name} is one pip can only take as a package from the index. */
    public static boolean isPlainName(String name) {
        return name != null && PLAIN_NAME.matcher(name).matches();
    }

    /**
     * An environment directory whose skill no longer exists.
     *
     * @param removed whether it is gone now — false on a dry run, and false when deletion
     *                failed, because "Removed" over a directory still on disk is the kind of
     *                claim this whole day has been about
     */
    public record OrphanedEnv(String skill, Path dir, long bytes, boolean removed) {}

    /**
     * Is there a GPU on this host? Either the device node or the driver's /proc entry; the second
     * exists on hosts where udev has not created the node yet. If neither is present there is
     * nothing a CUDA wheel could ever use.
     */
    public static boolean gpuPresent() {
        return Files.exists(Path.of("/dev/nvidia0"))
                || Files.exists(Path.of("/proc/driver/nvidia/version"));
    }

    /**
     * Extra pip arguments for a host without a GPU.
     * <p>
     * pip resolves {@code torch} to the CUDA build by default on Linux — about 7 GB with its
     * {@code nvidia-*} dependencies, against ~200 MB for the CPU build. On 2026-09-23 the
     * production host was at 96% disk, and 59 GB of it was per-skill environments, five of which
     * held {@code libtorch_cuda.so} on a four-core VM with no GPU, installed so a skill could OCR a
     * lunch menu. With the CPU index offered, pip picks the {@code +cpu} wheel of the same
     * version (a local version label sorts above the bare one) and the CUDA libraries are never
     * pulled in at all. Every other package still comes from PyPI as before.
     */
    public static List<String> indexArgs(boolean gpu) {
        return gpu ? List.of() : List.of("--extra-index-url", "https://download.pytorch.org/whl/cpu");
    }

    // On every install, not only when the requirements name torch. A previous round scoped it to
    // requirements that mention torch, to spare unrelated installs the extra index; then the real
    // files were read: daily_menu_fetcher says "easyocr", ocr_image_to_text says "pytesseract",
    // and both environments hold torch anyway, pulled in as a dependency. The scoping would have
    // covered none of the installs that filled the disk. So the index is offered whenever there
    // is no GPU, which is one mechanism and covers dependencies. Its costs, stated: pip queries
    // the extra index for every project in a closure (one small request each against a CDN), and
    // if that CDN is unreachable while PyPI is up, provisioning of any skill fails on pip's
    // retries until it is back — the agent retries the skill later. And pip has no index
    // priority, so a package the CPU index mirrors at a HIGHER version than PyPI would come from
    // it; as of today every mirrored version is at or below PyPI's.

    // ── the pip commands, as functions, so the wiring can be tested and not only the flag ──

    static List<String> packagesInstallArgs(String python, List<String> packages, boolean gpu) {
        var args = new ArrayList<>(List.of(python, "-m", "pip", "install",
                "--disable-pip-version-check", "-q"));
        args.addAll(indexArgs(gpu));
        args.addAll(packages);
        return args;
    }

    static List<String> requirementsInstallArgs(String python, Path reqFile, boolean gpu) {
        var args = new ArrayList<>(List.of(python, "-m", "pip", "install",
                "--disable-pip-version-check", "-q", "-r", reqFile.toAbsolutePath().toString()));
        args.addAll(indexArgs(gpu));
        return args;
    }

    static List<String> targetInstallArgs(String python, Path reqFile, Path targetDir, boolean gpu) {
        var args = requirementsInstallArgs(python, reqFile, gpu);
        args.add("--target");
        args.add(targetDir.toAbsolutePath().toString());
        return args;
    }

    /** A path is a direct child of the root and nothing else — no {@code ..}, no absolute name. */
    static boolean isDirectChild(Path root, Path dir) {
        Path r = root.toAbsolutePath().normalize();
        Path d = dir.toAbsolutePath().normalize();
        return d.getParent() != null && d.getParent().equals(r) && !d.equals(r);
    }

    /** Without a jail: for tests that build the service by hand. */
    public PythonEnvironmentService(OwnClawConfig config) {
        this(config, SkillJail.off());
    }

    @Autowired
    public PythonEnvironmentService(OwnClawConfig config, SkillJail jail) {
        this.config = config;
        this.jail = jail;
        this.systemPython = config.getSandbox().getPythonPath();
        // Defer env directory resolution to init() so we can pick a writable location.
    }

    @PostConstruct
    public void init() {
        resolveWritableEnvDirs();
        // Every check below starts a Python through the jail. While it refuses them all, each
        // would fail, and the failures would read as "no Python here" -- a false diagnosis.
        var down = jail.unavailable();
        if (down.isPresent()) {
            log.warn("Python checks skipped, the skill jail refuses every launch: {}. They run at the next start.",
                    down.get());
            return;
        }

        // Auto-detect a working Python if the configured one doesn't work
        if (!isPythonAvailable(systemPython)) {
            log.warn("Configured python-path '{}' is not available, searching for alternatives...", systemPython);
            Optional<String> detected = detectPython();
            if (detected.isPresent()) {
                systemPython = detected.get();
                log.info("Auto-detected Python: {}", systemPython);
            } else {
                log.error("No Python interpreter found! Skill execution will fail. "
                        + "Install Python 3 and configure ownclaw.sandbox.python-path in application.yaml");
            }
        } else {
            getPythonVersion().ifPresent(v -> log.info("Python ready: {} -> {}", systemPython, v));
        }
    }

    private void resolveWritableEnvDirs() {
        Path preferred = Path.of(config.getSkills().getGeneratedPath()).getParent().resolve("_envs");
        Path fallback = runtimeSkillsDir().resolve("_envs");

        Path chosen = tryEnsureWritableDir(preferred) ? preferred : fallback;
        if (!chosen.equals(preferred)) {
            log.warn("Using writable fallback for python envs: {} (preferred not writable: {})", chosen, preferred);
        }

        this.envsDir = chosen;
        this.targetsDir = chosen.resolve("_targets");
        this.bootstrapDir = chosen.resolve("_bootstrap");

        try {
            Files.createDirectories(envsDir);
            Files.createDirectories(targetsDir);
            Files.createDirectories(bootstrapDir);
        } catch (IOException e) {
            log.warn("Failed to create python env directories under {}: {}", chosen, e.getMessage());
        }
    }

    private boolean tryEnsureWritableDir(Path dir) {
        try {
            Files.createDirectories(dir);
            Path probe = dir.resolve(".write_test");
            Files.writeString(probe, "ok", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.deleteIfExists(probe);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private Path runtimeSkillsDir() {
        try {
            Path db = Path.of(config.getDatabase().getPath()).toAbsolutePath().normalize();
            Path dataDir = db.getParent() != null ? db.getParent() : Path.of("./data");
            return dataDir.resolve("skills");
        } catch (Exception e) {
            return Path.of("./data/skills");
        }
    }

    /** Quick check if a Python command is executable. */
    private boolean isPythonAvailable(String pythonCmd) {
        try {
            return run(5, List.of(pythonCmd, "--version"), null, List.of()).exitCode == 0;
        } catch (Exception ignored) {}
        return false;
    }

    /** @return the current resolved Python command */
    public String getSystemPython() {
        return systemPython;
    }

    /**
     * Resolved Python command plus optional environment variables needed for imports.
     *
     * <p>Normally this returns a per-skill venv python. On minimal Linux installs
     * (missing python3-venv / ensurepip), it falls back to installing into a
     * per-skill target directory and returns system Python with PYTHONPATH set.</p>
     *
     * @param envDir the venv or target directory the skill imports from -- what an isolated run
     *               must be given to read; null when it runs on system Python alone
     */
    public record PythonResolution(String python, Map<String, String> extraEnv, Path envDir) {}

    // ── environments that outlived their skill ──

    /**
     * Delete the orphans, or only list them.
     *
     * @param dryRun when true nothing is touched and the same list comes back — how the ops
     *               endpoint shows its working before anything is removed
     */
    public List<OrphanedEnv> pruneOrphans(Set<String> liveSkills, boolean dryRun) {
        return prune(envsDir, targetsDir, liveSkills, dryRun);
    }

    /**
     * The prune, as a function of its directories, so the guards can be tested on a temp tree.
     * <p>
     * It refuses to delete anything when {@code live} is empty. An empty registry is not "no
     * skills exist", it is "the registry cannot be trusted right now" — init found no directory,
     * a scan threw, or a reload is mid-way — and in that state every environment on the host
     * looks orphaned. The retirement half of maintenance has exactly this guard; the first
     * version of this half did not, and one call would have removed the live environments
     * along with the dead ones.
     */
    static List<OrphanedEnv> prune(Path envsDir, Path targetsDir, Set<String> live,
                                   boolean dryRun) {
        // The guard comes before the dry-run return, so both modes say the same thing. A dry
        // run is the owner's gate for the deletion; if it listed every live environment as
        // removable and the apply then refused, the gate would have lied first.
        if (live.isEmpty()) {
            log.warn("No skill is loaded, so the registry cannot be trusted to say what is live: "
                    + "nothing is listed and nothing would be removed.");
            return List.of();
        }
        List<OrphanedEnv> orphans = findOrphans(envsDir, targetsDir, live);
        if (dryRun || orphans.isEmpty()) return orphans;
        var out = new ArrayList<OrphanedEnv>();
        long freed = 0;
        for (OrphanedEnv o : orphans) {
            // No traversal check here: findOrphans lists real children of the root, so a path
            // that is not one cannot arrive. removeEnvironments takes a NAME from outside and
            // is where that check lives. No cache eviction either: resolvePython checks that
            // the interpreter exists before it trusts a cache hit, so a pruned environment
            // simply misses on the next call.
            SkillJail.deleteTree(o.dir());
            boolean removed = !Files.exists(o.dir());
            if (removed) freed += o.bytes();
            else log.warn("Environment {} is still on disk after deletion — a file in it "
                    + "could not be removed; the space counted as freed excludes it.", o.dir());
            out.add(new OrphanedEnv(o.skill(), o.dir(), o.bytes(), removed));
        }
        log.warn("Pruned {} of {} orphaned skill environment(s), {} MB: {}",
                out.stream().filter(OrphanedEnv::removed).count(), orphans.size(),
                freed / (1024 * 1024),
                out.stream().filter(OrphanedEnv::removed).map(OrphanedEnv::skill).toList());
        return out;
    }

    /** Remove a skill's environments now, because the skill itself is being removed. */
    public void removeEnvironments(String skillName) {
        if (envsDir == null) return;
        removeEnvironments(envsDir, targetsDir, skillName, this::forgetProvisioning);
    }

    /**
     * Remove {@code <root>/<name>} under both roots — and only that.
     * <p>
     * The name comes from SKILL.yaml, which the boot scan takes verbatim; {@code ".."} resolved
     * under {@code _envs} and handed to a recursive delete removed the parent directory in a
     * probe. Static, as a function of its roots, so that probe is now a test.
     */
    static void removeEnvironments(Path envsDir, Path targetsDir, String skillName,
                                   java.util.function.Consumer<String> forget) {
        if (skillName == null || skillName.isBlank()) return;
        for (Path root : List.of(envsDir, targetsDir)) {
            if (root == null) continue;
            Path dir = root.resolve(skillName);
            if (!isDirectChild(root, dir)) {
                log.warn("Refusing to remove environment for '{}': not a plain name", skillName);
                continue;
            }
            if (Files.isDirectory(dir)) SkillJail.deleteTree(dir);
        }
        forget.accept(skillName);
    }

    private void forgetProvisioning(String skillName) {
        provisionedHashes.keySet().removeIf(k -> k.startsWith(skillName + ":"));
        lastProvisionErrors.keySet().removeIf(k -> k.startsWith(skillName + ":"));
    }

    /**
     * The orphan rule, as a function of the directories, so it can be tested on a temp tree.
     * {@code _bootstrap} and {@code _targets} under the env root are the service's own and are
     * never candidates.
     */
    static List<OrphanedEnv> findOrphans(Path envsDir, Path targetsDir, Set<String> live) {
        var out = new ArrayList<OrphanedEnv>();
        for (Path root : List.of(envsDir, targetsDir)) {
            if (root == null || !Files.isDirectory(root)) continue;
            try (Stream<Path> children = Files.list(root)) {
                children.filter(Files::isDirectory).forEach(dir -> {
                    String name = dir.getFileName().toString();
                    if (root.equals(envsDir) && (name.equals("_bootstrap") || name.equals("_targets"))) {
                        return;
                    }
                    if (!live.contains(name)) out.add(new OrphanedEnv(name, dir, sizeOf(dir), false));
                });
            } catch (IOException e) {
                log.warn("Could not list {}: {}", root, e.getMessage());
            }
        }
        out.sort(Comparator.comparingLong(OrphanedEnv::bytes).reversed());
        return out;
    }

    /**
     * The bytes of the regular files in the tree. A link is neither followed nor counted, and a
     * directory that cannot be read -- a jailed pip can leave one mode 0300 -- counts as empty
     * instead of failing the whole listing.
     */
    static long sizeOf(Path dir) {
        long[] bytes = {0};
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path f, BasicFileAttributes a) {
                    if (a.isRegularFile()) bytes[0] += a.size();
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path f, IOException e) {
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException e) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            // what was counted stands
        }
        return bytes[0];
    }

    /**
     * Resolve the best Python executable for running a skill, provisioning dependencies if needed.
     */
    public PythonResolution resolveExecution(Path skillDir, String skillName) {
        Path reqFile = skillDir.resolve("requirements.txt");
        try {
            String declared = SkillJail.readAuthored(reqFile);
            if (declared == null || declared.isBlank()) {
                return new PythonResolution(systemPython, Map.of(), null);
            }
            String reqContent = declared.strip();

            // Try venv path first.
            String python = resolvePython(skillDir, skillName);
            if (python != null && !python.isBlank() && !python.equals(systemPython)) {
                // Expose the venv bin/ directory on PATH so that subprocesses launched
                // by the skill (e.g. "python3 -c ..." via shell_command) also resolve
                // to this venv's Python and can find its installed packages.
                String venvBin = Path.of(python).getParent().toAbsolutePath().toString();
                // PREPEND. This used to be Map.of("PATH", venvBin), which replaces PATH with a
                // single directory containing python and pip and nothing else -- so a skill with
                // a virtualenv could not reach ls, curl, nmap, tesseract, ip or any other system
                // binary. That contradicts what the skill-authoring prompt promises ("system
                // packages on PATH") and what the capability guidance instructs skills to do
                // (read the ARP table, shell out to a scanner), and it only bites skills that
                // declare requirements.txt, which is why it looked like flaky dependencies.
                String inherited = System.getenv("PATH");
                String path = inherited == null || inherited.isBlank()
                        ? venvBin
                        : venvBin + java.io.File.pathSeparator + inherited;
                return new PythonResolution(python, Map.of("PATH", path), ownDir(envsDir, skillName));
            }

            // Venv unavailable or provisioning failed; try a target install + PYTHONPATH.
            Path target = ensureTargetDependencies(skillDir, skillName, reqContent);
            if (target != null) {
                return new PythonResolution(systemPython, Map.of(
                        "PYTHONPATH", target.toAbsolutePath().toString()
                ), target.toAbsolutePath());
            }
        } catch (Exception e) {
            String msg = (e.getMessage() == null || e.getMessage().isBlank()) ? e.getClass().getSimpleName() : e.getMessage();
            log.warn("Failed to resolve execution environment for skill '{}': {}", skillName, msg);
        }

        return new PythonResolution(systemPython, Map.of(), null);
    }

    /**
     * Resolve the Python executable for a skill.
     * If the skill has a requirements.txt, ensures a venv exists and deps are installed.
     *
     * @param skillDir the versioned skill directory (e.g. skills/core/shell_command/v1)
     * @param skillName skill name for the venv directory name
     * @return path to the Python executable (venv python or system python)
     */
    public String resolvePython(Path skillDir, String skillName) {
        Path reqFile = skillDir.resolve("requirements.txt");

        try {
            String declared = SkillJail.readAuthored(reqFile);
            if (declared == null || declared.isBlank()) {
                // No dependencies — use system Python directly
                return systemPython;
            }
            String reqContent = declared.strip();

            String reqHash = hash(reqContent);
            String cacheKey = skillName + ":" + skillDir;
            Path venvDir = ownDir(envsDir, skillName);

            // Already provisioned this session with same hash?
            // ...and the interpreter still exists: the cache outlived a prune once and handed a
            // restored skill the path of a deleted python until the next restart.
            if (reqHash.equals(provisionedHashes.get(cacheKey))
                    && Files.exists(venvDir.resolve(pythonRelative()))) {
                lastProvisionErrors.remove(cacheKey);
                return venvPython(skillName);
            }

            Path hashFile = venvDir.resolve(".req_hash");

            // Check on-disk hash — skip install if unchanged
            if (reqHash.equals(storedHash(hashFile)) && Files.exists(venvDir.resolve(pythonRelative()))) {
                provisionedHashes.put(cacheKey, reqHash);
                lastProvisionErrors.remove(cacheKey);
                return venvPython(skillName);
            }

            // Create or update venv
            log.info("Provisioning Python venv for skill '{}' ...", skillName);
            createVenv(venvDir, skillDir);
            installRequirements(venvDir, reqFile, skillDir);
            SkillJail.writeFresh(hashFile, reqHash);
            provisionedHashes.put(cacheKey, reqHash);
            lastProvisionErrors.remove(cacheKey);

            log.info("Venv ready for skill '{}'", skillName);
            return venvPython(skillName);

        } catch (Exception e) {
            String msg = (e.getMessage() == null || e.getMessage().isBlank()) ? e.getClass().getSimpleName() : e.getMessage();
            lastProvisionErrors.put(skillName + ":" + skillDir, msg);
            log.warn("Failed to provision venv for skill '{}': {}. Falling back to system Python.",
                    skillName, msg);
            return systemPython;
        }
    }

    public Optional<String> getLastProvisionError(Path skillDir, String skillName) {
        return Optional.ofNullable(lastProvisionErrors.get(skillName + ":" + skillDir));
    }

    /**
     * Install one or more packages into a per-skill venv, creating the venv if needed.
     * Also persists the packages into requirements.txt for future seamless provisioning.
     *
     * This is used for deterministic self-healing (e.g. ModuleNotFoundError).
     */
    public boolean installPackages(Path skillDir, String skillName, List<String> packages) {
        if (packages == null || packages.isEmpty()) return false;
        for (String pkg : packages) {
            if (!isPlainName(pkg)) {
                // Refused before anything starts or is written: see PLAIN_NAME for what pip
                // would have done with it.
                String msg = "Refusing to install '" + pkg + "': not a plain package name";
                lastProvisionErrors.put(skillName + ":" + skillDir, msg);
                log.warn("Skill '{}': {}", skillName, msg);
                return false;
            }
        }
        try {
            Path venvDir = ownDir(envsDir, skillName);
            try {
                createVenv(venvDir, skillDir);
                ensurePipAvailable(venvDir, skillDir);
            } catch (Exception venvErr) {
                // Minimal Python installs often cannot create venvs.
                // Fall back to target installs + PYTHONPATH.
                persistRequirements(skillDir, packages);
                String declared = SkillJail.readAuthored(skillDir.resolve("requirements.txt"));
                String reqContent = declared == null ? "" : declared.strip();
                Path target = ensureTargetDependencies(skillDir, skillName, reqContent);
                if (target == null) {
                    throw new IOException("No target dependency directory produced");
                }
                lastProvisionErrors.remove(skillName + ":" + skillDir);
                return true;
            }

            String venvPython = venvDir.resolve(pythonRelative()).toAbsolutePath().toString();

            ProcessResult install = run(600, packagesInstallArgs(venvPython, packages, gpuPresent()),
                    venvDir, List.of(skillDir));
            if (install.exitCode != 0) {
                throw new IOException("pip install failed: " + install.output);
            }

            // Persist/update requirements.txt so future runs provision automatically.
            persistRequirements(skillDir, packages);

            // Refresh hash cache so resolvePython() uses venv without re-install.
            String declared = SkillJail.readAuthored(skillDir.resolve("requirements.txt"));
            if (declared != null && !declared.isBlank()) {
                String reqHash = hash(declared.strip());
                Files.createDirectories(venvDir);
                SkillJail.writeFresh(venvDir.resolve(".req_hash"), reqHash);
                provisionedHashes.put(skillName + ":" + skillDir, reqHash);
            }

            lastProvisionErrors.remove(skillName + ":" + skillDir);
            return true;
        } catch (Exception e) {
            String msg = (e.getMessage() == null || e.getMessage().isBlank()) ? e.getClass().getSimpleName() : e.getMessage();
            lastProvisionErrors.put(skillName + ":" + skillDir, msg);
            log.warn("Failed to install packages for skill '{}': {}", skillName, msg);
            return false;
        }
    }

    /**
     * Ensure dependencies are installed into a per-skill target directory (no venv required).
     *
     * @return the target directory if ready; null if installation failed
     */
    private Path ensureTargetDependencies(Path skillDir, String skillName, String reqContent)
            throws IOException, InterruptedException {
        if (reqContent == null || reqContent.isBlank()) return null;

        String reqHash = hash(reqContent);
        Path targetDir = ownDir(targetsDir, skillName);
        Path hashFile = targetDir.resolve(".req_hash");

        if (reqHash.equals(storedHash(hashFile))) {
            return targetDir;
        }

        Files.createDirectories(targetDir);

        ensurePipForPython(systemPython, /*isVenv=*/false, skillName, targetDir, skillDir);

        Path reqFile = skillDir.resolve("requirements.txt");
        ProcessResult install = run(900, targetInstallArgs(systemPython, reqFile, targetDir, gpuPresent()),
                targetDir, List.of(skillDir));

        if (install.exitCode != 0) {
            throw new IOException("pip --target install failed: " + install.output);
        }

        SkillJail.writeFresh(hashFile, reqHash);
        return targetDir;
    }

    private void ensurePipAvailable(Path venvDir, Path skillDir) throws IOException, InterruptedException {
        String venvPython = venvDir.resolve(pythonRelative()).toAbsolutePath().toString();
        ensurePipForPython(venvPython, /*isVenv=*/true, venvDir.getFileName().toString(), venvDir, skillDir);
    }

    /**
     * @param envDir   the directory being provisioned, the only one the commands may write
     * @param skillDir the skill's directory, readable to them
     */
    private void ensurePipForPython(String pythonExe, boolean isVenv, String contextName,
                                    Path envDir, Path skillDir)
            throws IOException, InterruptedException {
        List<Path> readOnly = List.of(skillDir);
        ProcessResult pipCheck = run(30, List.of(pythonExe, "-m", "pip", "--version"), envDir, readOnly);
        if (pipCheck.exitCode == 0) {
            return;
        }

        // 1) Try ensurepip if available
        ProcessResult ensure = run(120, List.of(pythonExe, "-m", "ensurepip", "--upgrade"), envDir, readOnly);
        if (ensure.exitCode == 0) {
            ProcessResult pipAfter = run(30, List.of(pythonExe, "-m", "pip", "--version"), envDir, readOnly);
            if (pipAfter.exitCode == 0) return;
        }

        // 2) Fall back to get-pip.py bootstrap
        log.warn("Bootstrapping pip via get-pip.py for {} (ensurepip unavailable)", contextName);
        bootstrapPipWithGetPip(pythonExe, isVenv, envDir, skillDir);

        ProcessResult pipAfter = run(30, List.of(pythonExe, "-m", "pip", "--version"), envDir, readOnly);
        if (pipAfter.exitCode != 0) {
            throw new IOException("pip bootstrapping failed: " + pipAfter.output);
        }
    }

    private void bootstrapPipWithGetPip(String pythonExe, boolean isVenv, Path envDir, Path skillDir)
            throws IOException, InterruptedException {
        Path getPip = downloadGetPipIfNeeded();

        var args = new java.util.ArrayList<String>();
        args.add(pythonExe);
        args.add(getPip.toAbsolutePath().toString());

        // For system Python, avoid requiring root by installing into user site.
        if (!isVenv) {
            args.add("--user");
        }

        // get-pip.py lives under _bootstrap, which an isolated command does not otherwise see.
        ProcessResult res = run(600, args, envDir, List.of(skillDir, getPip));
        if (res.exitCode != 0) {
            throw new IOException("get-pip.py failed: " + res.output);
        }
    }

    private Path downloadGetPipIfNeeded() throws IOException {
        if (bootstrapDir == null) {
            // Defensive: init() should have set this.
            bootstrapDir = runtimeSkillsDir().resolve("_envs/_bootstrap");
            Files.createDirectories(bootstrapDir);
        }

        Path dest = bootstrapDir.resolve("get-pip.py");
        if (Files.exists(dest) && Files.size(dest) > 0) {
            return dest;
        }

        Files.createDirectories(dest.getParent());
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(java.time.Duration.ofSeconds(10))
                .build();
        HttpRequest req = HttpRequest.newBuilder(GET_PIP_URI)
                .timeout(java.time.Duration.ofSeconds(30))
                .GET()
                .build();
        try {
            HttpResponse<byte[]> resp = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new IOException("Failed to download get-pip.py: HTTP " + resp.statusCode());
            }
            Files.write(dest, resp.body(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return dest;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted downloading get-pip.py", e);
        }
    }

    private void persistRequirements(Path skillDir, List<String> packages) throws IOException {
        Path reqFile = skillDir.resolve("requirements.txt");
        Files.createDirectories(skillDir);

        // A link here is refused, not followed: what is read is written back, into a file the
        // skill can read, so following a link to .env would hand the skill its contents.
        String current = SkillJail.readAuthored(reqFile);
        java.util.Set<String> existing = new java.util.LinkedHashSet<>();
        if (current != null) {
            for (String line : current.split("\\R")) {
                String trimmed = line.strip();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                existing.add(trimmed);
            }
        }

        boolean changed = false;
        for (String pkg : packages) {
            String trimmed = pkg == null ? "" : pkg.strip();
            if (trimmed.isEmpty()) continue;
            if (existing.add(trimmed)) changed = true;
        }

        if (current == null || changed) {
            String content = String.join("\n", existing) + "\n";
            // In place, not replaced: see SkillJail.writeAuthored -- a running copy of this
            // skill holds requirements.txt as a read-only mount.
            SkillJail.writeAuthored(reqFile, content);
        }
    }

    private void createVenv(Path venvDir, Path skillDir) throws IOException, InterruptedException {
        if (Files.exists(venvDir.resolve(pythonRelative()))) {
            return; // venv already exists
        }
        // Made here, not by venv: an isolated command can write only a directory that exists
        // on the host, bound at its own path. venv fills an empty directory as it would a new one.
        Files.createDirectories(venvDir);

        ProcessResult created = run(120, List.of(systemPython, "-m", "venv", venvDir.toAbsolutePath().toString()),
                venvDir, List.of(skillDir));
        if (created.exitCode != 0) {
            throw new IOException("Failed to create venv: " + created.output);
        }
    }

    private void installRequirements(Path venvDir, Path reqFile, Path skillDir)
            throws IOException, InterruptedException {
        String venvPython = venvDir.resolve(pythonRelative()).toAbsolutePath().toString();

        // Ensure pip is available in this venv (some Python installs omit it)
        ensurePipAvailable(venvDir, skillDir);

        // Install requirements using `python -m pip` (more portable than pip.exe path)
        ProcessResult install = run(300, requirementsInstallArgs(venvPython, reqFile, gpuPresent()),
                venvDir, List.of(skillDir));
        if (install.exitCode != 0) {
            throw new IOException("pip install failed: " + install.output);
        }
    }

    private String venvPython(String skillName) {
        return envsDir.resolve(skillName).resolve(pythonRelative()).toAbsolutePath().toString();
    }

    /**
     * Start one of this service's Python commands and wait for it.
     * <p>
     * Every Python the service starts comes through here, and so through the jail, as a skill's
     * own run does: pip downloads packages a model named and runs their build code, and what it
     * writes is code the skill will import. When the jail is active the command sees the host
     * read-only, writes only {@code envDir}, reads {@code readOnly}, and sees none of the
     * service's state -- a link the skill left in its directory resolves inside the jail, where
     * its target is hidden. When the jail is inactive or off the command runs unjailed.
     * <p>
     * Either way the environment is the jail's allow-list. pip and venv used to inherit the
     * service's whole environment -- every API key and token .env holds -- while running that
     * same build code; that part does not wait for bubblewrap to be installed.
     *
     * @param envDir   the only directory the command may write: the venv or target directory
     *                 being provisioned, which must already exist. Null for a probe that builds
     *                 nothing; it gets an empty directory of its own for the run.
     * @param readOnly host paths the command may read besides the read-only host
     * @throws IOException when the jail refuses the launch. Thrown, not returned as a failed
     *                     command: a refusal is not "pip is missing", and the fallbacks behind a
     *                     failed pip -- ensurepip, then downloading get-pip.py -- must not run
     *                     on it.
     */
    private ProcessResult run(int timeoutSeconds, List<String> command, Path envDir, List<Path> readOnly)
            throws IOException, InterruptedException {
        Path scratch = null;
        Path workDir = envDir;
        if (workDir == null) {
            scratch = Files.createTempDirectory("ownclaw-python-");
            workDir = scratch;
        }
        try (SkillJail.Launch launch = jail.prepare(command, workDir, readOnly)) {
            if (launch.refusal() != null) {
                throw new IOException(launch.refusal());
            }
            ProcessBuilder pb = new ProcessBuilder(launch.argv());
            pb.redirectErrorStream(true);
            SkillJail.applyEnvironment(pb, Map.of());
            Process p = pb.start();
            // Read beside the wait, not after it: a command whose output fills the pipe blocks
            // until it is read, and a hung build hook that holds the output open -- or a process
            // it left running -- never gives the read an end. So a command out of time is killed
            // first, and its output is waited for only briefly. Its output is bounded as a
            // skill's is (see ProcessSandbox.limited): past the limit the command is killed.
            var out = new ByteArrayOutputStream();
            var overLimit = new AtomicBoolean(false);
            Thread drain = Thread.ofVirtual().start(() -> {
                try (var in = ProcessSandbox.limited(p.getInputStream(), p, overLimit)) {
                    in.transferTo(out);
                } catch (IOException ignored) {
                    // what was read before is kept
                }
            });
            boolean ok = p.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!ok) {
                p.destroyForcibly();
                p.waitFor(5, TimeUnit.SECONDS);
            }
            drain.join(Duration.ofSeconds(5));
            String output = out.toString(StandardCharsets.UTF_8);
            if (overLimit.get()) {
                return new ProcessResult(-1, "(killed: output past the " + (ProcessSandbox.OUTPUT_LIMIT >> 20)
                        + " MB limit)\n" + output);
            }
            if (!ok) return new ProcessResult(-1, output + "\n(timeout)");
            return new ProcessResult(p.exitValue(), output);
        } finally {
            if (scratch != null) SkillJail.deleteTree(scratch);
        }
    }

    private record ProcessResult(int exitCode, String output) {}

    /**
     * A skill's own directory under an environment root: the one directory its pip may write.
     * The name comes from SKILL.yaml, which the boot scan takes verbatim, so it must be a plain
     * name -- "../generated/other" would hand pip another skill's code, read-write -- and not
     * one of the service's own directories under the root.
     */
    private static Path ownDir(Path root, String skillName) throws IOException {
        Path dir = root.resolve(skillName);
        if (!isDirectChild(root, dir) || skillName.equals("_targets") || skillName.equals("_bootstrap")) {
            throw new IOException("'" + skillName + "' is not a plain skill name; not provisioning it");
        }
        return dir.toAbsolutePath().normalize();
    }

    /**
     * The hash an earlier provisioning left, or null when there is none to trust. Its directory
     * is one a jailed pip writes, so only a regular file counts: a link is not followed, and
     * the fresh write that follows replaces it.
     */
    private static String storedHash(Path hashFile) throws IOException {
        if (!Files.isRegularFile(hashFile, LinkOption.NOFOLLOW_LINKS)) return null;
        return readNoFollow(hashFile).strip();
    }

    private static String readNoFollow(Path file) throws IOException {
        try (var in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Relative path to the python executable inside a venv. */
    private String pythonRelative() {
        return isWindows() ? "Scripts/python.exe" : "bin/python";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static String hash(String content) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h).substring(0, 16); // 16 hex chars is enough
        } catch (Exception e) {
            return String.valueOf(content.hashCode());
        }
    }

    /** Check if system Python is available. */
    public Optional<String> detectPython() {
        for (String candidate : new String[]{systemPython, "python3", "python", "py"}) {
            try {
                ProcessResult r = run(5, List.of(candidate, "--version"), null, List.of());
                if (r.exitCode == 0) {
                    log.debug("Found Python: {} -> {}", candidate, r.output.strip());
                    return Optional.of(candidate);
                }
            } catch (Exception ignored) {
                // try next candidate
            }
        }
        return Optional.empty();
    }

    /** Get the version string of the configured Python. */
    public Optional<String> getPythonVersion() {
        try {
            ProcessResult r = run(5, List.of(systemPython, "--version"), null, List.of());
            if (r.exitCode == 0) {
                return Optional.of(r.output.strip());
            }
        } catch (Exception ignored) {}
        return Optional.empty();
    }
}
