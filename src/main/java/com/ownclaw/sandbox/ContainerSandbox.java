package com.ownclaw.sandbox;

import com.ownclaw.config.OwnClawConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Container-based sandbox for skills that need system packages (apt/yum).
 *
 * <p>When a skill declares {@code system_packages} (e.g. nmap, net-tools),
 * it cannot run in the bare ProcessSandbox because those packages may not be
 * installed on the host — and the agent typically lacks sudo.  This sandbox
 * builds a lightweight Docker/Podman container image with the required system
 * packages pre-installed, caches the image by content hash, and runs the skill
 * inside it.
 *
 * <p>The container gets:
 * <ul>
 *   <li>The skill directory bind-mounted read-only at {@code /skill}</li>
 *   <li>{@code --network=host} so the skill can access the local network</li>
 *   <li>Environment variables (credentials, PYTHONIOENCODING, etc.) via {@code -e}</li>
 *   <li>JSON input on stdin, JSON output on stdout</li>
 * </ul>
 *
 * <p>Image layers are cached by Docker/Podman so repeated executions are fast.
 * Only the first run of a new system_packages combination triggers a build.
 *
 * <p>Falls back gracefully: if no container runtime is available, {@code isAvailable()}
 * returns false and the caller should use ProcessSandbox instead (with a warning).
 */
@Component
public class ContainerSandbox {

    private static final Logger log = LoggerFactory.getLogger(ContainerSandbox.class);

    /** Prefix for all images built by this sandbox. Includes localhost/ for Podman compatibility. */
    private static final String IMAGE_PREFIX = "localhost/ownclaw-skill-";

    private final OwnClawConfig config;

    /** "docker" or "podman" — whichever is available (or null). */
    private volatile String containerRuntime;

    /** Tracks which image tags have been verified/built this session. */
    private final Set<String> builtImages = ConcurrentHashMap.newKeySet();

    public ContainerSandbox(OwnClawConfig config) {
        this.config = config;
    }

    @PostConstruct
    public void init() {
        detectRuntime();
    }

    /**
     * Auto-detect whether Docker or Podman is available.
     * Prefers Docker (more reliable for building — runs as daemon with root)
     * then falls back to Podman if Docker is not found.
     */
    private void detectRuntime() {
        String configured = config.getSandbox().getContainerRuntime();
        if (configured != null && !configured.isBlank() && !"auto".equalsIgnoreCase(configured)) {
            if (isRuntimeUsable(configured)) {
                containerRuntime = configured;
                log.info("Container sandbox: using configured runtime '{}'", containerRuntime);
                return;
            }
            log.warn("Configured container runtime '{}' is not available/usable", configured);
        }

        // Auto-detect: try docker first (daemon-based, reliable builds), then podman
        for (String candidate : List.of("docker", "podman")) {
            if (isRuntimeUsable(candidate)) {
                containerRuntime = candidate;
                log.info("Container sandbox: auto-detected runtime '{}'", containerRuntime);
                return;
            }
        }

        log.info("Container sandbox: no usable container runtime (docker/podman) found. "
                + "Skills with system_packages will fall back to direct execution.");
    }

    /** The secondary runtime (the other one), if available. Used as build fallback. */
    private volatile String fallbackRuntime;

    /**
     * Check whether a container runtime is installed, responding, and actually
     * capable of building images.  For Podman rootless this means verifying that
     * subuid/subgid mapping works (the most common failure mode).
     */
    private boolean isRuntimeUsable(String runtime) {
        if (!isRuntimeInstalled(runtime)) return false;

        // For podman, verify rootless setup actually works
        if ("podman".equals(runtime) && !isPodmanRootlessReady(runtime)) {
            log.warn("Podman is installed but rootless namespace mapping is broken "
                    + "(missing /etc/subuid or /etc/subgid entries?). Skipping podman.");
            // Keep it as a fallback in case Docker is primary but user fixes podman later
            fallbackRuntime = null;
            return false;
        }

        // Determine fallback runtime
        String other = "docker".equals(runtime) ? "podman" : "docker";
        if (isRuntimeInstalled(other)) {
            // Only set podman as fallback if it's actually usable
            if ("podman".equals(other) && !isPodmanRootlessReady(other)) {
                fallbackRuntime = null;
            } else {
                fallbackRuntime = other;
            }
        }

        return true;
    }

    private boolean isRuntimeInstalled(String runtime) {
        try {
            Process p = new ProcessBuilder(runtime, "version")
                    .redirectErrorStream(true)
                    .start();
            drainStream(p.getInputStream());
            boolean finished = p.waitFor(5, TimeUnit.SECONDS);
            if (!finished) { p.destroyForcibly(); return false; }
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Verify that rootless Podman can actually perform user namespace mapping.
     * Without entries in /etc/subuid and /etc/subgid, podman build fails with
     * lchown errors (e.g. "lchown /etc/gshadow: invalid argument").
     */
    private boolean isPodmanRootlessReady(String podman) {
        try {
            // Quick smoke test: ask podman to unshare a trivial command.
            // This exercises the same user-namespace code path that build uses.
            Process p = new ProcessBuilder(podman, "unshare", "cat", "/proc/self/uid_map")
                    .redirectErrorStream(true)
                    .start();
            String output = new String(drainStream(p.getInputStream()), StandardCharsets.UTF_8);
            boolean finished = p.waitFor(10, TimeUnit.SECONDS);
            if (!finished) { p.destroyForcibly(); return false; }
            if (p.exitValue() != 0) {
                log.debug("Podman rootless check failed (exit {}): {}",
                        p.exitValue(), truncate(output, 500));
                return false;
            }
            return true;
        } catch (Exception e) {
            log.debug("Podman rootless check error: {}", e.getMessage());
            return false;
        }
    }

    /** Whether a container runtime is available. */
    public boolean isAvailable() {
        return containerRuntime != null;
    }

    /** The detected runtime name, or null. */
    public String runtime() {
        return containerRuntime;
    }

    // ────────────────────── Image Management ──────────────────────

    /**
     * Ordered fallback chain of Python base images.
     * If the preferred image (from SKILL.yaml or config) fails to build or validate,
     * we try the next one. This makes the system resilient to image unavailability.
     */
    private static final List<String> PYTHON_IMAGE_FALLBACKS = List.of(
            "docker.io/library/python:3.11-slim",
            "docker.io/library/python:3.12-slim",
            "docker.io/library/python:3-slim",
            "docker.io/library/python:3.11",
            "docker.io/library/python:3"
    );

    /**
     * Ensure a container image exists for the given system packages and pip requirements.
     * Builds the image if not already built. Uses content-based hashing for cache keys.
     * Tries the preferred image first, then config default, then fallback images.
     *
     * @param systemPackages   system packages to install (e.g. ["nmap", "net-tools"])
     * @param pipRequirements  pip requirements content (from requirements.txt), or null
     * @param skillDir         skill directory (for copying requirements.txt into build context)
     * @param preferredImage   preferred base image from SKILL.yaml (e.g. "python:3.11-slim"), or null
     * @return the image tag (e.g. "localhost/ownclaw-skill-a1b2c3d4")
     */
    public String ensureImage(List<String> systemPackages, String pipRequirements, Path skillDir,
                              String preferredImage)
            throws IOException, InterruptedException {
        return ensureImage(systemPackages, pipRequirements, skillDir, preferredImage, null);
    }

    /**
     * Ensure a container image exists, streaming build progress through the callback.
     */
    public String ensureImage(List<String> systemPackages, String pipRequirements, Path skillDir,
                              String preferredImage, SandboxManager.ProgressCallback progressCallback)
            throws IOException, InterruptedException {

        // Build ordered list of base images to try: preferred first, then config default, then fallbacks
        List<String> candidates = buildImageCandidateList(preferredImage);
        String firstCandidate = candidates.get(0);

        String imageTag = computeImageTag(firstCandidate, systemPackages, pipRequirements);

        if (builtImages.contains(imageTag)) {
            return imageTag;
        }

        // Check if the image already exists in the local registry
        if (imageExists(imageTag)) {
            builtImages.add(imageTag);
            log.debug("Container image '{}' already exists", imageTag);
            return imageTag;
        }

        // Try building with each candidate base image in order
        IOException lastError = null;
        int candidateIndex = 0;
        for (String baseImage : candidates) {
            candidateIndex++;
            String candidateTag = computeImageTag(baseImage, systemPackages, pipRequirements);
            // Maybe a fallback image was already built previously
            if (!candidateTag.equals(imageTag)
                    && (builtImages.contains(candidateTag) || imageExists(candidateTag))) {
                builtImages.add(candidateTag);
                log.info("Using previously built fallback image '{}'", candidateTag);
                return candidateTag;
            }
            try {
                log.info("Building image attempt {}/{} with base '{}'",
                        candidateIndex, candidates.size(), baseImage);
                if (progressCallback != null) {
                    progressCallback.onProgress(
                            "Building container image (" + candidateIndex + "/" + candidates.size()
                            + "): " + baseImage, null);
                }
                buildImage(candidateTag, baseImage, systemPackages, pipRequirements, skillDir, progressCallback);
                builtImages.add(candidateTag);
                return candidateTag;
            } catch (IOException e) {
                log.warn("Base image '{}' failed: {}. Trying next candidate...",
                        baseImage, truncate(e.getMessage(), 200));
                lastError = e;
            }
        }
        throw new IOException("All base image candidates failed. Last error: "
                + (lastError != null ? lastError.getMessage() : "unknown"));
    }

    /**
     * Build the ordered list of base images to try.
     * Preferred image goes first, then config default, then hardcoded fallbacks.
     * All entries are qualified and deduplicated.
     */
    private List<String> buildImageCandidateList(String preferredImage) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        // 1. Skill-specified preferred image (may be null if invalid)
        if (preferredImage != null && !preferredImage.isBlank()) {
            String qualified = qualifyImageName(preferredImage);
            if (qualified != null) seen.add(qualified);
        }
        // 2. Config default (may be null if invalid)
        String configDefault = qualifyImageName(config.getSandbox().getContainerBaseImage());
        if (configDefault != null) seen.add(configDefault);
        // 3. Hardcoded fallbacks (always valid)
        seen.addAll(PYTHON_IMAGE_FALLBACKS);
        return new ArrayList<>(seen);
    }

    private boolean imageExists(String imageTag) {
        try {
            Process p = new ProcessBuilder(containerRuntime, "image", "inspect", imageTag)
                    .redirectErrorStream(true)
                    .start();
            drainStream(p.getInputStream());
            boolean finished = p.waitFor(10, TimeUnit.SECONDS);
            if (!finished) { p.destroyForcibly(); return false; }
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Build a container image with the specified base image, system packages, and pip requirements.
     */
    private void buildImage(String imageTag, String baseImage, List<String> systemPackages,
                            String pipRequirements, Path skillDir,
                            SandboxManager.ProgressCallback progressCallback)
            throws IOException, InterruptedException {

        // Create a temporary build context directory
        Path buildCtx = Files.createTempDirectory("ownclaw-build-");
        try {
            // Write Dockerfile
            StringBuilder dockerfile = new StringBuilder();
            dockerfile.append("FROM ").append(baseImage).append("\n");
            dockerfile.append("ENV DEBIAN_FRONTEND=noninteractive\n");
            dockerfile.append("ENV PYTHONIOENCODING=utf-8\n");
            dockerfile.append("ENV PYTHONUTF8=1\n");
            // Ensure the Python shared library (libpython3.xx.so) is always findable.
            // Some system packages trigger ldconfig which can drop /usr/local/lib from cache.
            dockerfile.append("ENV LD_LIBRARY_PATH=/usr/local/lib\n");
            dockerfile.append("ENV PATH=/usr/local/bin:/usr/local/sbin:/usr/sbin:/usr/bin:/sbin:/bin\n");

            // Install system packages
            if (systemPackages != null && !systemPackages.isEmpty()) {
                // Sanitize package names (only allow alphanumeric, dash, dot, plus, colon)
                List<String> sanitized = systemPackages.stream()
                        .filter(pkg -> pkg.matches("[a-zA-Z0-9._+:-]+"))
                        .toList();
                if (!sanitized.isEmpty()) {
                    dockerfile.append("RUN apt-get update && apt-get install -y --no-install-recommends ");
                    dockerfile.append(String.join(" ", sanitized));
                    // ldconfig rebuilds the shared library cache — ensures /usr/local/lib
                    // (where libpython3.xx.so lives) stays indexed after new packages are added
                    dockerfile.append(" && ldconfig && rm -rf /var/lib/apt/lists/*\n");
                }
            }

            // Install pip requirements
            if (pipRequirements != null && !pipRequirements.isBlank()) {
                Files.writeString(buildCtx.resolve("requirements.txt"),
                        pipRequirements, StandardCharsets.UTF_8);
                dockerfile.append("COPY requirements.txt /tmp/requirements.txt\n");
                dockerfile.append("RUN pip install --no-cache-dir --disable-pip-version-check ");
                dockerfile.append("-r /tmp/requirements.txt && rm /tmp/requirements.txt\n");
            }

            dockerfile.append("WORKDIR /skill\n");

            Files.writeString(buildCtx.resolve("Dockerfile"),
                    dockerfile.toString(), StandardCharsets.UTF_8);

            log.info("Building container image '{}' with system packages: {}",
                    imageTag, systemPackages);

            // Try primary runtime first, fall back to secondary if build fails
            String buildRuntime = containerRuntime;
            String buildError = tryBuildImage(buildRuntime, imageTag, buildCtx, progressCallback);

            if (buildError != null && fallbackRuntime != null) {
                log.warn("Build failed with '{}': {}. Trying fallback runtime '{}'...",
                        buildRuntime, truncate(buildError, 200), fallbackRuntime);
                buildRuntime = fallbackRuntime;
                String fallbackError = tryBuildImage(buildRuntime, imageTag, buildCtx, progressCallback);
                if (fallbackError != null) {
                    throw new IOException("Container image build failed with both runtimes.\n"
                            + containerRuntime + ": " + truncate(buildError, 1000) + "\n"
                            + fallbackRuntime + ": " + truncate(fallbackError, 1000));
                }
                // Fallback succeeded — switch runtimes for future calls
                log.info("Fallback runtime '{}' succeeded. Switching primary runtime.", buildRuntime);
                containerRuntime = buildRuntime;
            } else if (buildError != null) {
                throw new IOException("Container image build failed (" + buildRuntime + "):\n"
                        + truncate(buildError, 2000));
            }

            log.info("Successfully built container image '{}' with {}", imageTag, containerRuntime);

            // Validate the built image: verify python3 is actually usable.
            // This catches cases where the base image was pulled from a wrong registry
            // (Podman doesn't default to Docker Hub) or layers are broken.
            if (!validateImage(imageTag)) {
                builtImages.remove(imageTag);
                throw new IOException("Container image '" + imageTag + "' built but python3 is not "
                        + "usable inside it. The base image may have been pulled from a wrong registry. "
                        + "Check that '" + baseImage + "' is reachable.");
            }

        } finally {
            // Clean up build context
            try (var walk = Files.walk(buildCtx)) {
                walk.sorted(Comparator.reverseOrder())
                        .forEach(path -> { try { Files.deleteIfExists(path); } catch (IOException ignored) {} });
            }
        }
    }

    /**
     * Attempt to build a container image with the given runtime.
     *
     * @return null on success, or the error output on failure
     */
    private String tryBuildImage(String runtime, String imageTag, Path buildCtx,
                                   SandboxManager.ProgressCallback progressCallback)
            throws InterruptedException {
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add(runtime);
            cmd.add("build");
            cmd.add("--tag");
            cmd.add(imageTag);

            // Podman-specific flags to improve compatibility
            if ("podman".equals(runtime)) {
                cmd.add("--format");
                cmd.add("docker");
            }

            // Don't use --quiet: stall detection relies on build output (layer
            // downloads, RUN steps) to know the build is still making progress.
            // With --quiet, a legitimately slow build produces zero output and
            // gets killed by the stall detector.
            cmd.add(buildCtx.toAbsolutePath().toString());

            Process p = new ProcessBuilder(cmd)
                    .redirectErrorStream(true)
                    .start();

            // Stall detection only — no hard wall-clock cap.
            // As long as the build produces output (layer downloads, package installs)
            // it's allowed to continue. The user sees real-time progress via WebSocket.
            AtomicLong lastBuildActivity = new AtomicLong(System.currentTimeMillis());

            // Read output line-by-line to stream build progress to the user.
            // Also feeds lastBuildActivity for stall detection.
            StringBuilder outputBuf = new StringBuilder();
            CompletableFuture<String> outputFuture = CompletableFuture.supplyAsync(() -> {
                try (var reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        lastBuildActivity.set(System.currentTimeMillis());
                        outputBuf.append(line).append('\n');
                        if (progressCallback != null) {
                            // Summarize the line for the UI — strip ANSI codes and truncate
                            String clean = line.replaceAll("\\x1B\\[[0-9;]*m", "").trim();
                            if (!clean.isEmpty()) {
                                progressCallback.onProgress(
                                        "\uD83D\uDCE6 " + truncate(clean, 120), null);
                            }
                        }
                    }
                } catch (IOException ignored) {}
                return outputBuf.toString();
            });

            int buildStallTimeout = config.getSandbox().getStallTimeout();
            boolean finished = waitForWithStallDetection(p, buildStallTimeout, lastBuildActivity);

            if (!finished) {
                long stallSec = (System.currentTimeMillis() - lastBuildActivity.get()) / 1000;
                p.destroyForcibly();
                try { p.waitFor(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                outputFuture.cancel(true);
                return "Build stalled (no output for " + stallSec + "s)";
            }

            String output = outputFuture.join();

            if (p.exitValue() != 0) {
                return "exit " + p.exitValue() + ": " + output;
            }

            return null; // success
        } catch (IOException e) {
            return "IO error: " + e.getMessage();
        }
    }

    // ────────────────────── Execution ──────────────────────

    /**
     * Execute a Python script inside a container.
     *
     * @param imageTag  the container image to use (from ensureImage)
     * @param python    python executable (usually "python3" inside the container)
     * @param scriptPath path to the runner script (relative to skillDir)
     * @param skillDir   skill directory — mounted read-write at /skill
     * @param stdinJson  JSON input for stdin
     * @param envVars    environment variables to inject
     * @param timeoutSec execution timeout
     * @return sandbox result
     */
    public SandboxResult execute(String imageTag, String python, Path scriptPath,
                                 Path skillDir, String stdinJson,
                                 Map<String, String> envVars, int timeoutSec) {
        long startTime = System.currentTimeMillis();

        try {
            List<String> cmd = buildRunCommand(imageTag, python, scriptPath, skillDir, envVars);

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(false);

            Process process = pb.start();

            // Write JSON input to stdin
            if (stdinJson != null) {
                try (OutputStream os = process.getOutputStream()) {
                    os.write(stdinJson.getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }
            } else {
                process.getOutputStream().close();
            }

            // Track output activity for stall detection.
            AtomicLong lastActivity = new AtomicLong(System.currentTimeMillis());

            // Drain stderr async
            CompletableFuture<byte[]> stderrFuture = CompletableFuture.supplyAsync(
                    () -> drainStreamWithActivity(process.getErrorStream(), lastActivity));

            // Drain stdout async
            CompletableFuture<byte[]> stdoutFuture = CompletableFuture.supplyAsync(
                    () -> drainStreamWithActivity(process.getInputStream(), lastActivity));

            boolean finished = waitForWithStallDetection(process, timeoutSec, lastActivity);
            long durationMs = System.currentTimeMillis() - startTime;

            if (!finished) {
                long stallSec = (System.currentTimeMillis() - lastActivity.get()) / 1000;
                process.destroyForcibly();
                try { process.waitFor(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                String stdout = new String(stdoutFuture.join(), StandardCharsets.UTF_8);
                String stderr = new String(stderrFuture.join(), StandardCharsets.UTF_8);
                log.warn("Container stalled (no output for {}s) — killed {} after {}ms total",
                        stallSec, scriptPath.getFileName(), durationMs);
                return new SandboxResult(-1, stdout, stderr, durationMs, true);
            }

            String stdout = new String(stdoutFuture.join(), StandardCharsets.UTF_8);
            String stderr = new String(stderrFuture.join(), StandardCharsets.UTF_8);

            int exitCode = process.exitValue();
            log.debug("Container execution completed [exit={}] {} in {}ms",
                    exitCode, scriptPath.getFileName(), durationMs);
            return new SandboxResult(exitCode, stdout, stderr, durationMs, false);

        } catch (IOException e) {
            long durationMs = System.currentTimeMillis() - startTime;
            String errMsg = "Container execution error: " + e.getMessage();
            log.error("Container execution failed for {}: {}", scriptPath.getFileName(), e.getMessage());
            return new SandboxResult(-1, "", errMsg, durationMs, false);
        }
    }

    /**
     * Execute with progress callback support.
     * Progress lines are intercepted and removed from stdout.
     */
    public SandboxResult execute(String imageTag, String python, Path scriptPath,
                                 Path skillDir, String stdinJson,
                                 Map<String, String> envVars, int timeoutSec,
                                 SandboxManager.ProgressCallback progressCallback) {
        return execute(imageTag, python, scriptPath, skillDir, stdinJson, envVars, timeoutSec,
                progressCallback, null);
    }

    /**
     * Execute with progress callback and extra volume mounts.
     */
    public SandboxResult execute(String imageTag, String python, Path scriptPath,
                                 Path skillDir, String stdinJson,
                                 Map<String, String> envVars, int timeoutSec,
                                 SandboxManager.ProgressCallback progressCallback,
                                 Map<String, String> extraVolumes) {
        if (progressCallback == null && extraVolumes == null) {
            return execute(imageTag, python, scriptPath, skillDir, stdinJson, envVars, timeoutSec);
        }

        long startTime = System.currentTimeMillis();

        try {
            List<String> cmd = buildRunCommand(imageTag, python, scriptPath, skillDir, envVars, extraVolumes);

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(false);

            Process process = pb.start();

            if (stdinJson != null) {
                try (OutputStream os = process.getOutputStream()) {
                    os.write(stdinJson.getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }
            } else {
                process.getOutputStream().close();
            }

            // Track output activity for stall detection.
            AtomicLong lastActivity = new AtomicLong(System.currentTimeMillis());

            CompletableFuture<byte[]> stderrFuture = CompletableFuture.supplyAsync(
                    () -> drainStreamWithActivity(process.getErrorStream(), lastActivity));

            // Read stdout line-by-line to intercept progress messages
            CompletableFuture<byte[]> stdoutFuture = CompletableFuture.supplyAsync(() -> {
                ByteArrayOutputStream buf = new ByteArrayOutputStream(8192);
                try (var reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        lastActivity.set(System.currentTimeMillis());
                        String trimmed = line.trim();
                        if (trimmed.startsWith("{") && trimmed.contains("\"progress\"")) {
                            try {
                                var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                                var node = mapper.readTree(trimmed);
                                if ("progress".equals(node.path("type").asText(""))) {
                                    String msg = node.path("message").asText("Working...");
                                    Integer pct = node.has("percent") && !node.get("percent").isNull()
                                            ? node.get("percent").asInt() : null;
                                    progressCallback.onProgress(msg, pct);
                                    continue;
                                }
                            } catch (Exception ignored) {}
                        }
                        buf.write(line.getBytes(StandardCharsets.UTF_8));
                        buf.write('\n');
                    }
                } catch (IOException ignored) {}
                return buf.toByteArray();
            });

            boolean finished = waitForWithStallDetection(process, timeoutSec, lastActivity);
            long durationMs = System.currentTimeMillis() - startTime;

            if (!finished) {
                long stallSec = (System.currentTimeMillis() - lastActivity.get()) / 1000;
                process.destroyForcibly();
                try { process.waitFor(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                String stdout = new String(stdoutFuture.join(), StandardCharsets.UTF_8);
                String stderr = new String(stderrFuture.join(), StandardCharsets.UTF_8);
                log.warn("Container stalled (no output for {}s) — killed {} after {}ms total",
                        stallSec, scriptPath.getFileName(), durationMs);
                return new SandboxResult(-1, stdout, stderr, durationMs, true);
            }

            String stdout = new String(stdoutFuture.join(), StandardCharsets.UTF_8);
            String stderr = new String(stderrFuture.join(), StandardCharsets.UTF_8);

            return new SandboxResult(process.exitValue(), stdout, stderr, durationMs, false);

        } catch (IOException e) {
            long durationMs = System.currentTimeMillis() - startTime;
            return new SandboxResult(-1, "", "Container error: " + e.getMessage(), durationMs, false);
        }
    }

    /**
     * Build the docker/podman run command.
     */
    private List<String> buildRunCommand(String imageTag, String python, Path scriptPath,
                                         Path skillDir, Map<String, String> envVars) {
        return buildRunCommand(imageTag, python, scriptPath, skillDir, envVars, null);
    }

    private List<String> buildRunCommand(String imageTag, String python, Path scriptPath,
                                         Path skillDir, Map<String, String> envVars,
                                         Map<String, String> extraVolumes) {
        List<String> cmd = new ArrayList<>();
        cmd.add(containerRuntime);
        cmd.add("run");
        cmd.add("--rm");            // auto-remove container after exit
        cmd.add("-i");              // interactive (for stdin)
        cmd.add("--network=host");  // access local network (for scanning, etc.)

        // Bind-mount the skill directory at /skill (read-write for temp files)
        cmd.add("-v");
        cmd.add(skillDir.toAbsolutePath() + ":/skill");

        // Extra bind-mounts (e.g. uploads directory for attached files)
        if (extraVolumes != null) {
            for (var vol : extraVolumes.entrySet()) {
                cmd.add("-v");
                cmd.add(vol.getKey() + ":" + vol.getValue() + ":ro");
            }
        }

        // Ensure Python shared library is always findable at runtime
        cmd.add("-e");
        cmd.add("LD_LIBRARY_PATH=/usr/local/lib");

        // Inject environment variables
        if (envVars != null) {
            for (var entry : envVars.entrySet()) {
                cmd.add("-e");
                cmd.add(entry.getKey() + "=" + entry.getValue());
            }
        }

        cmd.add(imageTag);

        // The command to run inside the container
        cmd.add(python);
        cmd.add("/skill/" + scriptPath.getFileName());

        return cmd;
    }

    // ────────────────────── Helpers ──────────────────────

    /** Regex for valid Docker image names: registry/org/name:tag or name:tag@digest */
    private static final java.util.regex.Pattern VALID_IMAGE_NAME =
            java.util.regex.Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9._/:@-]*$");

    /**
     * Qualify a Docker image name for Podman compatibility.
     * Podman doesn't default to Docker Hub — unqualified names like "python:3.11-slim"
     * may resolve to a wrong registry or fail entirely. Prefix with "docker.io/library/"
     * for official images to ensure correct resolution on both Docker and Podman.
     *
     * Also validates the image name to prevent Dockerfile injection via newlines or
     * other special characters (the value may originate from LLM output).
     */
    static String qualifyImageName(String imageName) {
        if (imageName == null || imageName.isBlank()) return null;
        String trimmed = imageName.trim();
        if (!VALID_IMAGE_NAME.matcher(trimmed).matches()) {
            log.warn("Rejecting invalid container image name: '{}'", truncate(trimmed, 100));
            return null;
        }
        // Already qualified (contains a slash indicating registry/org)
        if (trimmed.contains("/")) return trimmed;
        // Unqualified official image (e.g. "python:3.11-slim") → "docker.io/library/python:3.11-slim"
        return "docker.io/library/" + trimmed;
    }

    /**
     * Validate that a built container image actually has a working python3.
     * Catches broken images early (wrong registry pull, missing layers, etc.)
     */
    private boolean validateImage(String imageTag) {
        try {
            Process p = new ProcessBuilder(containerRuntime, "run", "--rm", imageTag,
                    "python3", "--version")
                    .redirectErrorStream(true)
                    .start();
            // Drain async so the 30s waitFor timeout is actually reachable.
            // Previous version drained synchronously (blocking until EOF) which
            // made the timeout dead code — a hanging container blocked forever.
            CompletableFuture<byte[]> outputFuture = CompletableFuture.supplyAsync(
                    () -> drainStream(p.getInputStream()));
            boolean finished = p.waitFor(30, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                outputFuture.cancel(true);
                log.warn("Image validation timed out for '{}'", imageTag);
                return false;
            }
            String output = new String(outputFuture.join(), StandardCharsets.UTF_8);
            if (p.exitValue() != 0) {
                log.warn("Image validation failed for '{}': exit={}, output={}",
                        imageTag, p.exitValue(), truncate(output, 500));
                return false;
            }
            log.debug("Image validation passed for '{}': {}", imageTag, output.strip());
            return true;
        } catch (Exception e) {
            log.warn("Image validation error for '{}': {}", imageTag, e.getMessage());
            return false;
        }
    }

    /**
     * Compute a deterministic image tag based on the content hash of
     * base image + system packages + pip requirements.
     */
    private String computeImageTag(String baseImage, List<String> systemPackages, String pipRequirements) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            // Include the base image in hash so different base images produce different tags.
            md.update(baseImage.getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            if (systemPackages != null) {
                List<String> sorted = new ArrayList<>(systemPackages);
                Collections.sort(sorted);
                for (String pkg : sorted) {
                    md.update(pkg.getBytes(StandardCharsets.UTF_8));
                    md.update((byte) 0);
                }
            }
            md.update((byte) 1);
            if (pipRequirements != null) {
                md.update(pipRequirements.getBytes(StandardCharsets.UTF_8));
            }
            String hash = HexFormat.of().formatHex(md.digest()).substring(0, 12);
            return IMAGE_PREFIX + hash;
        } catch (Exception e) {
            // Fallback — should never happen (SHA-256 always available)
            return IMAGE_PREFIX + "fallback-" + System.currentTimeMillis();
        }
    }

    private static byte[] drainStream(InputStream is) {
        try {
            ByteArrayOutputStream buf = new ByteArrayOutputStream(8192);
            byte[] chunk = new byte[8192];
            int n;
            while ((n = is.read(chunk)) != -1) {
                buf.write(chunk, 0, n);
            }
            return buf.toByteArray();
        } catch (IOException e) {
            return new byte[0];
        }
    }

    /**
     * Read an InputStream fully, updating lastActivity on each chunk.
     * This lets stall detection know the process is still producing output.
     */
    private static byte[] drainStreamWithActivity(InputStream is, AtomicLong lastActivity) {
        try {
            ByteArrayOutputStream buf = new ByteArrayOutputStream(8192);
            byte[] chunk = new byte[8192];
            int n;
            while ((n = is.read(chunk)) != -1) {
                lastActivity.set(System.currentTimeMillis());
                buf.write(chunk, 0, n);
            }
            return buf.toByteArray();
        } catch (IOException e) {
            return new byte[0];
        }
    }

    /**
     * Wait for a process to finish with stall detection instead of a hard wall-clock timeout.
     * The process runs indefinitely as long as it keeps producing output.
     * If no output for {@code stallTimeoutSec} seconds, the process is considered stalled.
     *
     * @return true if the process finished normally, false if it stalled
     */
    private static boolean waitForWithStallDetection(Process process, int stallTimeoutSec,
                                                     AtomicLong lastActivity) {
        long stallTimeoutMs = stallTimeoutSec * 1000L;
        while (process.isAlive()) {
            try {
                if (process.waitFor(5, TimeUnit.SECONDS)) {
                    return true;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            long sinceLastOutput = System.currentTimeMillis() - lastActivity.get();
            if (sinceLastOutput > stallTimeoutMs) {
                return false;
            }
        }
        return true;
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...[truncated]";
    }
}
