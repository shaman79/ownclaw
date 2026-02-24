package com.ownclaw.skills;

import com.ownclaw.config.OwnClawConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    private volatile String systemPython;
    private final Path envsDir;

    /** Dependency fallback location (when venv/ensurepip is unavailable). */
    private final Path targetsDir;

    /** Tracks which skill dirs have been provisioned this session (avoid redundant checks). */
    private final Map<String, String> provisionedHashes = new ConcurrentHashMap<>();

    /** Captures the last provisioning error per skill+dir (for user-facing diagnostics). */
    private final Map<String, String> lastProvisionErrors = new ConcurrentHashMap<>();

    public PythonEnvironmentService(OwnClawConfig config) {
        this.systemPython = config.getSandbox().getPythonPath();
        this.envsDir = Path.of(config.getSkills().getCorePath()).getParent().resolve("_envs");
        this.targetsDir = envsDir.resolve("_targets");
    }

    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(envsDir);
            Files.createDirectories(targetsDir);
        } catch (IOException e) {
            log.warn("Failed to create _envs directory: {}", e.getMessage());
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
                return new PythonResolution(python, Map.of());
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
                ensureTargetDependencies(skillDir, skillName, reqContent);
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
            args.addAll(packages);

            ProcessResult install = run(300, new ProcessBuilder(args));
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

        // Ensure pip is available on system Python
        ProcessResult pipCheck = run(60, new ProcessBuilder(systemPython, "-m", "pip", "--version"));
        if (pipCheck.exitCode != 0) {
            ProcessResult ensure = run(120, new ProcessBuilder(systemPython, "-m", "ensurepip", "--upgrade"));
            if (ensure.exitCode != 0) {
                throw new IOException("pip unavailable and ensurepip failed: " + ensure.output);
            }
        }

        Path reqFile = skillDir.resolve("requirements.txt");
        ProcessResult install = run(600, new ProcessBuilder(
                systemPython, "-m", "pip", "install",
                "--disable-pip-version-check",
                "-q", "-r", reqFile.toAbsolutePath().toString(),
                "--target", targetDir.toAbsolutePath().toString()
        ));

        if (install.exitCode != 0) {
            throw new IOException("pip --target install failed: " + install.output);
        }

        Files.writeString(hashFile, reqHash, StandardCharsets.UTF_8);
        return targetDir;
    }

    private void ensurePipAvailable(Path venvDir) throws IOException, InterruptedException {
        String venvPython = venvDir.resolve(pythonRelative()).toAbsolutePath().toString();
        ProcessResult pipCheck = run(120, new ProcessBuilder(venvPython, "-m", "pip", "--version"));
        if (pipCheck.exitCode != 0) {
            ProcessResult ensure = run(180, new ProcessBuilder(venvPython, "-m", "ensurepip", "--upgrade"));
            if (ensure.exitCode != 0) {
                throw new IOException("pip unavailable and ensurepip failed: " + ensure.output);
            }
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
        ProcessResult install = run(300, new ProcessBuilder(
                venvPython, "-m", "pip", "install",
                "--disable-pip-version-check",
                "-q", "-r", reqFile.toAbsolutePath().toString()));
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
