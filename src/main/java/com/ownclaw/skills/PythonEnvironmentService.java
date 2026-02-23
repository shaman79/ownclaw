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

    /** Tracks which skill dirs have been provisioned this session (avoid redundant checks). */
    private final Map<String, String> provisionedHashes = new ConcurrentHashMap<>();

    public PythonEnvironmentService(OwnClawConfig config) {
        this.systemPython = config.getSandbox().getPythonPath();
        this.envsDir = Path.of(config.getSkills().getCorePath()).getParent().resolve("_envs");
    }

    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(envsDir);
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
                return venvPython(skillName);
            }

            Path venvDir = envsDir.resolve(skillName);
            Path hashFile = venvDir.resolve(".req_hash");

            // Check on-disk hash — skip install if unchanged
            if (Files.exists(hashFile)) {
                String storedHash = Files.readString(hashFile, StandardCharsets.UTF_8).strip();
                if (reqHash.equals(storedHash) && Files.exists(venvDir.resolve(pythonRelative()))) {
                    provisionedHashes.put(cacheKey, reqHash);
                    return venvPython(skillName);
                }
            }

            // Create or update venv
            log.info("Provisioning Python venv for skill '{}' ...", skillName);
            createVenv(venvDir);
            installRequirements(venvDir, reqFile);
            Files.writeString(hashFile, reqHash, StandardCharsets.UTF_8);
            provisionedHashes.put(cacheKey, reqHash);

            log.info("Venv ready for skill '{}'", skillName);
            return venvPython(skillName);

        } catch (Exception e) {
            log.warn("Failed to provision venv for skill '{}': {}. Falling back to system Python.",
                    skillName, e.getMessage());
            return systemPython;
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
        String pip = venvPipPath(venvDir);

        ProcessBuilder pb = new ProcessBuilder(pip, "install", "-q", "-r", reqFile.toAbsolutePath().toString());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        boolean ok = p.waitFor(300, TimeUnit.SECONDS);
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        if (!ok) {
            p.destroyForcibly();
            throw new IOException("pip install timed out");
        }
        if (p.exitValue() != 0) {
            throw new IOException("pip install failed: " + output);
        }
    }

    private String venvPython(String skillName) {
        return envsDir.resolve(skillName).resolve(pythonRelative()).toAbsolutePath().toString();
    }

    private String venvPipPath(Path venvDir) {
        return venvDir.resolve(isWindows() ? "Scripts/pip.exe" : "bin/pip").toAbsolutePath().toString();
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
