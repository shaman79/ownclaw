package com.ownclaw.skills;

import com.ownclaw.config.OwnClawConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
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

/**
 * Manages Python virtual environments for skill execution.
 * Auto-creates venvs and installs requirements.txt on first use.
 * Caches a hash of requirements to skip re-install when unchanged.
 */
@Service
public class PythonEnvironmentService {

    private static final Logger log = LoggerFactory.getLogger(PythonEnvironmentService.class);
    private static final URI GET_PIP_URI = URI.create("https://bootstrap.pypa.io/get-pip.py");

    private volatile String systemPython;
    private volatile boolean systemPipAvailable;
    private volatile boolean systemEnsurePipAvailable;
    private volatile Path envsDir;
    private volatile Path targetsDir;
    private volatile Path bootstrapDir;

    /** Tracks which skill dirs have been provisioned this session (avoid redundant checks). */
    private final Map<String, String> provisionedHashes = new ConcurrentHashMap<>();

    /** Captures the last provisioning error per skill+dir (for user-facing diagnostics). */
    private final Map<String, String> lastProvisionErrors = new ConcurrentHashMap<>();

    private final OwnClawConfig config;

    /** An environment directory whose skill no longer exists. */
    public record OrphanedEnv(String skill, Path dir, long bytes) {}

    /**
     * Is there a GPU on this host? {@code /dev/nvidia0} is what the NVIDIA driver creates; if it
     * is absent there is nothing a CUDA wheel could ever use.
     */
    static boolean gpuPresent() {
        return Files.exists(Path.of("/dev/nvidia0"));
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
    static List<String> indexArgs(boolean gpu) {
        return gpu ? List.of() : List.of("--extra-index-url", "https://download.pytorch.org/whl/cpu");
    }

    public PythonEnvironmentService(OwnClawConfig config) {
        this.config = config;
        this.systemPython = config.getSandbox().getPythonPath();
        // Defer env directory resolution to init() so we can pick a writable location.
    }

    @PostConstruct
    public void init() {
        resolveWritableEnvDirs();

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

        probeSystemPackageInstallCapabilities();
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

    private void probeSystemPackageInstallCapabilities() {
        try {
            // Check pip on system Python
            ProcessResult pip = run(10, new ProcessBuilder(systemPython, "-m", "pip", "--version"));
            systemPipAvailable = pip.exitCode == 0;

            // Check ensurepip module existence without modifying environment
            if (!systemPipAvailable) {
                ProcessResult ensure = run(10, new ProcessBuilder(systemPython, "-c", "import ensurepip"));
                systemEnsurePipAvailable = ensure.exitCode == 0;
            } else {
                systemEnsurePipAvailable = true;
            }

            if (!systemPipAvailable && !systemEnsurePipAvailable) {
                log.warn("Python dependency installation is unavailable (no pip, no ensurepip). "
                        + "Skills that require third-party packages cannot self-heal; prefer stdlib-only skills.");
            }
        } catch (Exception e) {
            // Be conservative: if probing fails, assume we can't install.
            systemPipAvailable = false;
            systemEnsurePipAvailable = false;
        }
    }

    /**
     * True if we can (in principle) install Python packages using system Python.
     * This covers either a working pip module, or an available ensurepip module.
     */
    public boolean canInstallPackagesOnSystemPython() {
        return systemPipAvailable || systemEnsurePipAvailable;
    }

    /**
     * True if this skill can install packages via its venv (if present) or via system Python.
     */
    public boolean canInstallPackagesForSkill(String skillName) {
        try {
            Path venvDir = envsDir.resolve(skillName);
            Path venvPython = venvDir.resolve(pythonRelative());
            if (Files.exists(venvPython)) {
                ProcessResult pip = run(10, new ProcessBuilder(
                        venvPython.toAbsolutePath().toString(), "-m", "pip", "--version"));
                if (pip.exitCode == 0) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            // fall through
        }
        return canInstallPackagesOnSystemPython();
    }

    /** Quick check if a Python command is executable. */
    private boolean isPythonAvailable(String pythonCmd) {
        try {
            Process p = new ProcessBuilder(pythonCmd, "--version")
                    .redirectErrorStream(true).start();
            boolean ok = p.waitFor(5, TimeUnit.SECONDS);
            if (ok && p.exitValue() == 0) {
                p.getInputStream().readAllBytes(); // drain
                return true;
            }
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
     */
    public record PythonResolution(String python, Map<String, String> extraEnv) {}

    // ── environments that outlived their skill ──

    /**
     * Environment directories whose skill is not among {@code liveSkills}.
     * <p>
     * Deleting, retiring or renaming a skill removed or moved its directory and left its
     * environment behind — nothing ever cleaned {@code _envs/<name>} or {@code _targets/<name>}.
     * By 2026-09-23 there were 32 such directories on the production host, 45.7 GB, three of
     * them 7.4 GB CUDA installs for lunch-menu fetchers deleted in March. An environment is a
     * rebuildable cache keyed on the requirements hash, so one whose skill no longer exists is
     * garbage by definition — and one whose skill is merely quarantined is rebuilt on restore.
     */
    public List<OrphanedEnv> orphanedEnvironments(Set<String> liveSkills) {
        return findOrphans(envsDir, targetsDir, liveSkills);
    }

    /**
     * Delete the orphans, or only list them.
     *
     * @param dryRun when true nothing is touched and the same list comes back — how the ops
     *               endpoint shows its working before anything is removed
     */
    public List<OrphanedEnv> pruneOrphans(Set<String> liveSkills, boolean dryRun) {
        List<OrphanedEnv> orphans = orphanedEnvironments(liveSkills);
        if (dryRun || orphans.isEmpty()) return orphans;
        long freed = 0;
        for (OrphanedEnv o : orphans) {
            try {
                freed += deleteTree(o.dir());
            } catch (IOException e) {
                log.warn("Could not remove orphaned environment {}: {}", o.dir(), e.getMessage());
            }
            // resolvePython trusts this cache before it looks at the disk, so without this a
            // skill restored in the same session is handed the path of an interpreter that no
            // longer exists, until the next restart.
            forgetProvisioning(o.skill());
        }
        log.warn("Pruned {} orphaned skill environment(s), {} MB", orphans.size(),
                freed / (1024 * 1024));
        return orphans;
    }

    /** Remove a skill's environments now, because the skill itself is being removed. */
    public void removeEnvironments(String skillName) {
        if (skillName == null || skillName.isBlank() || envsDir == null) return;
        for (Path dir : List.of(envsDir.resolve(skillName), targetsDir.resolve(skillName))) {
            try {
                if (Files.isDirectory(dir)) deleteTree(dir);
            } catch (IOException e) {
                log.warn("Could not remove environment {}: {}", dir, e.getMessage());
            }
        }
        forgetProvisioning(skillName);
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
                    if (!live.contains(name)) out.add(new OrphanedEnv(name, dir, sizeOf(dir)));
                });
            } catch (IOException e) {
                log.warn("Could not list {}: {}", root, e.getMessage());
            }
        }
        out.sort(Comparator.comparingLong(OrphanedEnv::bytes).reversed());
        return out;
    }

    static long sizeOf(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile).mapToLong(f -> {
                try { return Files.size(f); } catch (IOException e) { return 0L; }
            }).sum();
        } catch (IOException e) {
            return 0L;
        }
    }

    /** Delete a directory tree; returns the bytes it held. */
    static long deleteTree(Path dir) throws IOException {
        long bytes = sizeOf(dir);
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) { }
            });
        }
        return bytes;
    }

    /**
     * Resolve the best Python executable for running a skill, provisioning dependencies if needed.
     */
    public PythonResolution resolveExecution(Path skillDir, String skillName) {
        Path reqFile = skillDir.resolve("requirements.txt");
        if (!Files.exists(reqFile)) {
            return new PythonResolution(systemPython, Map.of());
        }

        try {
            String reqContent = Files.readString(reqFile, StandardCharsets.UTF_8).strip();
            if (reqContent.isEmpty()) {
                return new PythonResolution(systemPython, Map.of());
            }

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
                return new PythonResolution(python, Map.of("PATH", path));
            }

            // Venv unavailable or provisioning failed; try a target install + PYTHONPATH.
            Path target = ensureTargetDependencies(skillDir, skillName, reqContent);
            if (target != null) {
                return new PythonResolution(systemPython, Map.of(
                        "PYTHONPATH", target.toAbsolutePath().toString()
                ));
            }
        } catch (Exception e) {
            String msg = (e.getMessage() == null || e.getMessage().isBlank()) ? e.getClass().getSimpleName() : e.getMessage();
            log.warn("Failed to resolve execution environment for skill '{}': {}", skillName, msg);
        }

        return new PythonResolution(systemPython, Map.of());
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

        if (!Files.exists(reqFile)) {
            // No dependencies — use system Python directly
            return systemPython;
        }

        try {
            String reqContent = Files.readString(reqFile, StandardCharsets.UTF_8).strip();
            if (reqContent.isEmpty()) {
                return systemPython;
            }

            String reqHash = hash(reqContent);
            String cacheKey = skillName + ":" + skillDir;

            // Already provisioned this session with same hash?
            if (reqHash.equals(provisionedHashes.get(cacheKey))) {
                lastProvisionErrors.remove(cacheKey);
                return venvPython(skillName);
            }

            Path venvDir = envsDir.resolve(skillName);
            Path hashFile = venvDir.resolve(".req_hash");

            // Check on-disk hash — skip install if unchanged
            if (Files.exists(hashFile)) {
                String storedHash = Files.readString(hashFile, StandardCharsets.UTF_8).strip();
                if (reqHash.equals(storedHash) && Files.exists(venvDir.resolve(pythonRelative()))) {
                    provisionedHashes.put(cacheKey, reqHash);
                    lastProvisionErrors.remove(cacheKey);
                    return venvPython(skillName);
                }
            }

            // Create or update venv
            log.info("Provisioning Python venv for skill '{}' ...", skillName);
            createVenv(venvDir);
            installRequirements(venvDir, reqFile);
            Files.writeString(hashFile, reqHash, StandardCharsets.UTF_8);
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
        try {
            Path venvDir = envsDir.resolve(skillName);
            try {
                createVenv(venvDir);
                ensurePipAvailable(venvDir);
            } catch (Exception venvErr) {
                // Minimal Python installs often cannot create venvs.
                // Fall back to target installs + PYTHONPATH.
                persistRequirements(skillDir, packages);
                String reqContent = Files.exists(skillDir.resolve("requirements.txt"))
                        ? Files.readString(skillDir.resolve("requirements.txt"), StandardCharsets.UTF_8).strip()
                        : "";
                Path target = ensureTargetDependencies(skillDir, skillName, reqContent);
                if (target == null) {
                    throw new IOException("No target dependency directory produced");
                }
                lastProvisionErrors.remove(skillName + ":" + skillDir);
                return true;
            }

            String venvPython = venvDir.resolve(pythonRelative()).toAbsolutePath().toString();

            var args = new java.util.ArrayList<String>();
            args.add(venvPython);
            args.add("-m");
            args.add("pip");
            args.add("install");
            args.add("--disable-pip-version-check");
            args.add("-q");
            args.addAll(indexArgs(gpuPresent()));
            args.addAll(packages);

            ProcessResult install = run(600, new ProcessBuilder(args));
            if (install.exitCode != 0) {
                throw new IOException("pip install failed: " + install.output);
            }

            // Persist/update requirements.txt so future runs provision automatically.
            persistRequirements(skillDir, packages);

            // Refresh hash cache so resolvePython() uses venv without re-install.
            Path reqFile = skillDir.resolve("requirements.txt");
            if (Files.exists(reqFile)) {
                String reqContent = Files.readString(reqFile, StandardCharsets.UTF_8).strip();
                if (!reqContent.isBlank()) {
                    String reqHash = hash(reqContent);
                    Files.createDirectories(venvDir);
                    Files.writeString(venvDir.resolve(".req_hash"), reqHash, StandardCharsets.UTF_8);
                    provisionedHashes.put(skillName + ":" + skillDir, reqHash);
                }
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
        Path targetDir = targetsDir.resolve(skillName);
        Path hashFile = targetDir.resolve(".req_hash");

        if (Files.exists(hashFile)) {
            String stored = Files.readString(hashFile, StandardCharsets.UTF_8).strip();
            if (reqHash.equals(stored)) {
                return targetDir;
            }
        }

        Files.createDirectories(targetDir);

        ensurePipForPython(systemPython, /*isVenv=*/false, skillName);

        Path reqFile = skillDir.resolve("requirements.txt");
        var targetArgs = new ArrayList<>(List.of(
                systemPython, "-m", "pip", "install",
                "--disable-pip-version-check",
                "-q", "-r", reqFile.toAbsolutePath().toString(),
                "--target", targetDir.toAbsolutePath().toString()));
        targetArgs.addAll(indexArgs(gpuPresent()));
        ProcessResult install = run(900, new ProcessBuilder(targetArgs));

        if (install.exitCode != 0) {
            throw new IOException("pip --target install failed: " + install.output);
        }

        Files.writeString(hashFile, reqHash, StandardCharsets.UTF_8);
        return targetDir;
    }

    private void ensurePipAvailable(Path venvDir) throws IOException, InterruptedException {
        String venvPython = venvDir.resolve(pythonRelative()).toAbsolutePath().toString();
        ensurePipForPython(venvPython, /*isVenv=*/true, venvDir.getFileName().toString());
    }

    private void ensurePipForPython(String pythonExe, boolean isVenv, String contextName)
            throws IOException, InterruptedException {
        ProcessResult pipCheck = run(30, new ProcessBuilder(pythonExe, "-m", "pip", "--version"));
        if (pipCheck.exitCode == 0) {
            return;
        }

        // 1) Try ensurepip if available
        ProcessResult ensure = run(120, new ProcessBuilder(pythonExe, "-m", "ensurepip", "--upgrade"));
        if (ensure.exitCode == 0) {
            ProcessResult pipAfter = run(30, new ProcessBuilder(pythonExe, "-m", "pip", "--version"));
            if (pipAfter.exitCode == 0) return;
        }

        // 2) Fall back to get-pip.py bootstrap
        log.warn("Bootstrapping pip via get-pip.py for {} (ensurepip unavailable)", contextName);
        bootstrapPipWithGetPip(pythonExe, isVenv);

        ProcessResult pipAfter = run(30, new ProcessBuilder(pythonExe, "-m", "pip", "--version"));
        if (pipAfter.exitCode != 0) {
            throw new IOException("pip bootstrapping failed: " + pipAfter.output);
        }
    }

    private void bootstrapPipWithGetPip(String pythonExe, boolean isVenv)
            throws IOException, InterruptedException {
        Path getPip = downloadGetPipIfNeeded();

        var args = new java.util.ArrayList<String>();
        args.add(pythonExe);
        args.add(getPip.toAbsolutePath().toString());

        // For system Python, avoid requiring root by installing into user site.
        if (!isVenv) {
            args.add("--user");
        }

        ProcessResult res = run(600, new ProcessBuilder(args));
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

        java.util.Set<String> existing = new java.util.LinkedHashSet<>();
        if (Files.exists(reqFile)) {
            for (String line : Files.readString(reqFile, StandardCharsets.UTF_8).split("\\R")) {
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

        if (!Files.exists(reqFile) || changed) {
            String content = String.join("\n", existing) + "\n";
            Files.writeString(reqFile, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        }
    }

    private void createVenv(Path venvDir) throws IOException, InterruptedException {
        if (Files.exists(venvDir.resolve(pythonRelative()))) {
            return; // venv already exists
        }
        Files.createDirectories(venvDir.getParent());

        ProcessBuilder pb = new ProcessBuilder(systemPython, "-m", "venv", venvDir.toAbsolutePath().toString());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        boolean ok = p.waitFor(120, TimeUnit.SECONDS);
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        if (!ok || p.exitValue() != 0) {
            throw new IOException("Failed to create venv: " + output);
        }
    }

    private void installRequirements(Path venvDir, Path reqFile) throws IOException, InterruptedException {
        String venvPython = venvDir.resolve(pythonRelative()).toAbsolutePath().toString();

        // Ensure pip is available in this venv (some Python installs omit it)
        ensurePipAvailable(venvDir);

        // Install requirements using `python -m pip` (more portable than pip.exe path)
        var reqArgs = new ArrayList<>(List.of(
                venvPython, "-m", "pip", "install",
                "--disable-pip-version-check",
                "-q", "-r", reqFile.toAbsolutePath().toString()));
        reqArgs.addAll(indexArgs(gpuPresent()));
        ProcessResult install = run(300, new ProcessBuilder(reqArgs));
        if (install.exitCode != 0) {
            throw new IOException("pip install failed: " + install.output);
        }
    }

    private String venvPython(String skillName) {
        return envsDir.resolve(skillName).resolve(pythonRelative()).toAbsolutePath().toString();
    }

    private String venvPipPath(Path venvDir) {
        return venvDir.resolve(isWindows() ? "Scripts/pip.exe" : "bin/pip").toAbsolutePath().toString();
    }

    private static ProcessResult run(int timeoutSeconds, ProcessBuilder pb) throws IOException, InterruptedException {
        pb.redirectErrorStream(true);
        Process p = pb.start();
        boolean ok = p.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        if (!ok) {
            p.destroyForcibly();
            return new ProcessResult(-1, output + "\n(timeout)");
        }
        return new ProcessResult(p.exitValue(), output);
    }

    private record ProcessResult(int exitCode, String output) {}

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
                Process p = new ProcessBuilder(candidate, "--version")
                        .redirectErrorStream(true).start();
                boolean ok = p.waitFor(5, TimeUnit.SECONDS);
                if (ok && p.exitValue() == 0) {
                    String version = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
                    log.debug("Found Python: {} -> {}", candidate, version);
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
            Process p = new ProcessBuilder(systemPython, "--version")
                    .redirectErrorStream(true).start();
            if (p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0) {
                return Optional.of(new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip());
            }
        } catch (Exception ignored) {}
        return Optional.empty();
    }
}
