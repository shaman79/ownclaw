package com.ownclaw.agent.tools;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownclaw.sandbox.ContainerSandbox;
import com.ownclaw.sandbox.SandboxManager;
import com.ownclaw.sandbox.SandboxResult;
import com.ownclaw.sandbox.SkillJail;
import com.ownclaw.skills.PythonEnvironmentService;
import com.ownclaw.users.CredentialVault;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A dynamically created Python-based tool, loaded from disk.
 * Treated identically to any other tool — the agent sees no distinction.
 *
 * <p>Each dynamic skill is stored as:
 * <pre>
 * skills/generated/{name}/
 *   SKILL.yaml        — metadata (name, description, parameters, etc.)
 *   skill.py           — Python implementation (stdin JSON → stdout JSON)
 *   requirements.txt   — optional pip dependencies
 * </pre>
 *
 * <p>Python contract: skill.py defines a {@code def run(params)} function that
 * receives the tool parameters as a dict and returns a dict. The execution is
 * bootstrapped by a runner harness that handles stdin reading, calling run(),
 * and printing the JSON result to stdout. This means skill authors only need to
 * write the {@code run()} function — no stdin/stdout boilerplate needed.
 *
 * <p>This class is NOT a Spring component — instances are created by
 * {@link DynamicSkillRegistry} and registered into {@link ToolRegistry} at runtime.
 */
public class DynamicSkill implements Tool {

    private static final Logger log = LoggerFactory.getLogger(DynamicSkill.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final SecureRandom NONCES = new SecureRandom();

    /** Detect ModuleNotFoundError / ImportError in Python output. */
    private static final Pattern MODULE_NOT_FOUND = Pattern.compile(
            "ModuleNotFoundError: No module named ['\"]([^'\"]+)['\"]" +
            "|ImportError: No module named ([A-Za-z0-9_.]+)");

    /** Module name → pip package name (only when they differ). */
    private static final Map<String, String> MODULE_TO_PACKAGE = Map.ofEntries(
            Map.entry("bs4", "beautifulsoup4"),
            Map.entry("yaml", "pyyaml"),
            Map.entry("PIL", "pillow"),
            Map.entry("cv2", "opencv-python"),
            Map.entry("sklearn", "scikit-learn"),
            Map.entry("lxml", "lxml"),
            Map.entry("fitz", "PyMuPDF"),
            Map.entry("docx", "python-docx"),
            Map.entry("pptx", "python-pptx"),
            Map.entry("dotenv", "python-dotenv"),
            Map.entry("dateutil", "python-dateutil"),
            Map.entry("attr", "attrs"),
            Map.entry("jwt", "PyJWT")
    );

    private final String name;
    private final String description;
    private final Map<String, ToolParam> parameters;
    private final Path skillDir;
    private final boolean requiresNetwork;
    private final boolean hasSideEffects;
    private final int timeoutSec;
    private final SandboxManager sandbox;
    private final PythonEnvironmentService pythonEnv;
    private final List<String> requiredCredentials;
    private final CredentialVault credentialVault;
    private final List<String> systemPackages;
    private final String containerImage;
    private final ContainerSandbox containerSandbox;
    private final com.ownclaw.conversation.FileStorageService fileStorage;

    /**
     * How many invocations of this skill are running right now.
     * <p>
     * Read before retiring or deleting a skill. Retirement moves the skill's directory, and a
     * running invocation is reading {@code skill.py}, its virtualenv and its own {@code _runner_}
     * harness out of that directory the whole time it executes -- some of them for minutes.
     * Moving it mid-run does not fail cleanly: the interpreter is already started, so what the
     * user gets is a Python error about a path that existed a moment ago.
     */
    private final java.util.concurrent.atomic.AtomicInteger inFlight =
            new java.util.concurrent.atomic.AtomicInteger();

    /** Invocations currently executing. Zero means the directory is safe to move or delete. */
    public int inFlight() {
        return inFlight.get();
    }

    public DynamicSkill(String name, String description, Map<String, ToolParam> parameters,
                        Path skillDir, boolean requiresNetwork, boolean hasSideEffects,
                        int timeoutSec, SandboxManager sandbox, PythonEnvironmentService pythonEnv,
                        List<String> requiredCredentials, CredentialVault credentialVault,
                        List<String> systemPackages, String containerImage,
                        ContainerSandbox containerSandbox,
                        com.ownclaw.conversation.FileStorageService fileStorage) {
        this.name = name;
        this.description = description;
        this.parameters = parameters;
        this.skillDir = skillDir;
        this.requiresNetwork = requiresNetwork;
        this.hasSideEffects = hasSideEffects;
        this.timeoutSec = timeoutSec;
        this.sandbox = sandbox;
        this.pythonEnv = pythonEnv;
        this.requiredCredentials = requiredCredentials != null ? requiredCredentials : List.of();
        this.credentialVault = credentialVault;
        this.systemPackages = systemPackages != null ? systemPackages : List.of();
        this.containerImage = containerImage;
        this.containerSandbox = containerSandbox;
        this.fileStorage = fileStorage;
    }

    @Override public String name() { return name; }
    @Override public String description() { return description; }
    @Override public Map<String, ToolParam> inputSchema() { return parameters; }
    @Override public boolean requiresNetwork() { return requiresNetwork; }
    @Override public boolean hasSideEffects() { return hasSideEffects; }
    @Override public int estimatedMaxDurationSeconds() { return timeoutSec; }
    @Override public List<String> requiredCredentials() { return requiredCredentials; }

    /** The directory containing this skill's files. */
    public Path skillDir() { return skillDir; }

    /** System packages required by this skill (e.g. nmap, net-tools). */
    public List<String> systemPackages() { return systemPackages; }

    /** Preferred container base image (e.g. "python:3.11-slim"), or null for system default. */
    public String containerImage() { return containerImage; }

    /**
     * Read the requirements.txt file content, or null if it is absent or not a regular file.
     */
    private String readRequirements() {
        try {
            return SkillJail.readAuthored(skillDir.resolve("requirements.txt"));
        } catch (IOException e) {
            log.warn("Failed to read requirements.txt for skill '{}': {}", name, e.getMessage());
            return null;
        }
    }

    /**
     * The opening of every script that loads a skill: the runner below, and SkillManager's load
     * check, so what the check proves is what runs.
     * <p>
     * The module is made as importlib makes it -- {@code __spec__}, {@code __loader__} and the
     * rest are there, as they were when the runner imported it -- but skill.py is compiled from
     * its source every time rather than run by the spec's loader. That loader runs a
     * {@code __pycache__/skill.*.pyc} whose header says it is current: the same modification
     * time, to the whole second, and the same size as skill.py. Loading that way, the load check
     * would leave the pyc of the update it imports, so an update that failed the check and had
     * the previous version written back within the same second, at the same size, would go on
     * running the code just rejected.
     * <p>
     * This does not pin what runs against the skill itself. Its directory is writable and first
     * on sys.path, so a module it leaves there -- a json.py, say -- is imported before skill.py
     * is, as it always was; the jail bounds what such a module can do.
     */
    public static final String SKILL_LOADER = String.join("\n",
        "import sys, os, io, json, builtins, traceback, inspect, importlib.util",
        "_skill_dir = os.path.dirname(os.path.abspath(__file__))",
        "",
        "def _load_skill(inject=None):",
        "    path = os.path.join(_skill_dir, 'skill.py')",
        "    with open(path, 'rb') as f:",
        "        source = f.read()",
        "    mod = importlib.util.module_from_spec(importlib.util.spec_from_file_location('skill', path))",
        "    if inject:",
        "        mod.__dict__.update(inject)",
        "    exec(compile(source, path, 'exec', dont_inherit=True), mod.__dict__)",
        "    return mod",
        ""
    );

    /**
     * The runner harness that bootstraps skill execution.
     *
     * <p>We do NOT run skill.py directly — instead we run this thin wrapper that:
     * <ol>
     *   <li>Reads JSON parameters from stdin</li>
     *   <li>Imports skill.py and calls its {@code run(params)} function</li>
     *   <li>Prints the returned dict as JSON to stdout</li>
     *   <li>Catches and reports any exception as a structured failure</li>
     * </ol>
     *
     * <p>This decouples the LLM's authoring convention ({@code def run(params): return ...})
     * from the process-level stdin/stdout contract.
     *
     * <p>Skills can call {@code report_progress(message, percent=None)} to emit
     * structured progress updates for long-running tasks.  These are intercepted
     * by the sandbox and forwarded to the user's chat in real time.
     */
    private static final String RUNNER_HARNESS = SKILL_LOADER + String.join("\n",
        "",
        "# --- Progress reporting API for long-running skills ---",
        "# Skills call report_progress('Scanning host 12/255', percent=5)",
        "# The message is emitted as a JSON line on the real stdout and",
        "# intercepted by the sandbox — it never reaches the final output.",
        "_real_stdout = sys.__stdout__",
        "",
        "def report_progress(message, percent=None):",
        "    \"\"\"Report progress for a long-running task.",
        "    ",
        "    Args:",
        "        message: Human-readable progress message (e.g. 'Scanning host 12/255').",
        "        percent: Optional completion percentage (0-100).",
        "    \"\"\"",
        "    progress = {'type': 'progress', 'message': str(message)}",
        "    if percent is not None:",
        "        progress['percent'] = int(percent)",
        "    _real_stdout.write(json.dumps(progress) + '\\n')",
        "    _real_stdout.flush()",
        "",
        "def _dispatch_run(run_fn, params):",
        "    \"\"\"Smart parameter dispatch: supports both def run(params) and def run(key=val) styles.\"\"\"",
        "    sig = inspect.signature(run_fn)",
        "    positional = [p for p in sig.parameters.values()",
        "                  if p.kind in (p.POSITIONAL_ONLY, p.POSITIONAL_OR_KEYWORD)]",
        "    has_var_keyword = any(p.kind == p.VAR_KEYWORD for p in sig.parameters.values())",
        "    names = [p.name for p in positional]",
        "    required = [p.name for p in positional",
        "                if p.default is inspect.Parameter.empty]",
        "    # Single param explicitly named for the whole dict -> pass it whole",
        "    if len(positional) == 1 and names[0] in ('params', 'data', 'args', 'kwargs', 'input', 'payload'):",
        "        return run_fn(params)",
        "    # Parameters named after the keys the caller supplied -> call by keyword.",
        "    # This MUST precede the single-positional rule below. A skill written as",
        "    # def run(query) has one positional with no default, so that rule fired and",
        "    # handed it the whole dict: query became {'query': 'road bike'} rather than",
        "    # 'road bike'. Nothing raised -- the skill searched for the str() of a dict, or",
        "    # built a URL full of braces -- so it returned confident nonsense.",
        "    if required and all(n in params for n in required):",
        "        if has_var_keyword:",
        "            return run_fn(**params)",
        "        return run_fn(**{k: v for k, v in params.items() if k in names})",
        "    # Single param with no default and no matching key -> unnamed-dict convention",
        "    if len(positional) == 1 and positional[0].default is inspect.Parameter.empty:",
        "        return run_fn(params)",
        "    # Has **kwargs or multiple named params → unpack as keyword arguments",
        "    if has_var_keyword or len(positional) != 1:",
        "        try:",
        "            return run_fn(**params)",
        "        except TypeError:",
        "            return run_fn(params)",
        "    # Fallback: try dict first, then kwargs",
        "    try:",
        "        return run_fn(params)",
        "    except TypeError:",
        "        return run_fn(**params)",
        "",
        "try:",
        "    params = json.loads(sys.stdin.read()) if not sys.stdin.isatty() else {}",
        "    builtins.report_progress = report_progress",
        "    _capture = io.StringIO()",
        "    sys.stdout = _capture",
        "    # Inject report_progress into the skill module so it can be called directly",
        "    mod = _load_skill({'report_progress': report_progress})",
        "    if not hasattr(mod, 'run'):",
        "        sys.stdout = _real_stdout",
        "        print(json.dumps({'success': False, 'output': 'skill.py does not define a run(params) function'}))",
        "        sys.exit(0)",
        "    result = _dispatch_run(mod.run, params)",
        "    sys.stdout = _real_stdout",
        "    captured = _capture.getvalue()",
        "    if not isinstance(result, dict):",
        "        result = {'output': str(result) if result is not None else ''}",
        "    if 'success' not in result:",
        "        result['success'] = True",
        "    if captured and captured.strip():",
        "        result.setdefault('output', '')",
        "        if result['output']:",
        "            result['output'] += '\\n[skill stdout: ' + captured.strip() + ']'",
        "        else:",
        "            result['output'] = captured.strip()",
        "    print(json.dumps(result, default=str, ensure_ascii=False))",
        "except Exception as e:",
        "    sys.stdout = sys.__stdout__",
        "    print(json.dumps({'success': False, 'output': f'Skill error: {e}\\n{traceback.format_exc()}'}, ensure_ascii=False))",
        ""
    );

    @Override
    public ToolResult execute(Map<String, Object> params, ToolExecutionContext context) {
        Path scriptPath = skillDir.resolve("skill.py");
        if (!Files.exists(scriptPath)) {
            return ToolResult.failure("Skill script not found: " + scriptPath);
        }
        // Here, before the container is chosen: a run in docker or podman never passes the jail,
        // so while it refuses every launch it would be the one way around it.
        Optional<String> refused = sandbox == null ? Optional.empty() : sandbox.unavailable();
        if (refused.isPresent()) return ToolResult.failure(refused.get());

        Path runnerScript = null;
        inFlight.incrementAndGet();
        try {
            // Resolve Python (creates venv + installs requirements if needed)
            var resolution = pythonEnv.resolveExecution(skillDir, name);

            // Write the runner harness under a name of its own, so concurrent runs of the same
            // skill do not collide and nothing left in the directory can be written through.
            runnerScript = writeHelperScript(skillDir, "_runner_", RUNNER_HARNESS);

            // Serialize input parameters as JSON for stdin.
            // If the current message has file attachments, inject their paths so the skill can
            // access them. Each path is the very one bound read-only into the jail: the skill is
            // told exactly what it can open, and can open exactly what it is told.
            Map<String, Object> effectiveParams = params != null ? new HashMap<>(params) : new HashMap<>();
            var attachedFiles = new ArrayList<Map<String, String>>();
            var handed = new ArrayList<Path>();
            if (!context.attachmentIds().isEmpty() && fileStorage != null) {
                for (String fileId : context.attachmentIds()) {
                    var info = fileStorage.getFileInfo(fileId);
                    if (info == null) {
                        log.warn("Skill '{}': attachment {} has no record; not handing it over", name, fileId);
                        continue;
                    }
                    Path file = handedFile(fileId);
                    if (file == null) continue;
                    attachedFiles.add(Map.of(
                            "id", fileId,
                            "name", Objects.toString(info.get("original_name"), ""),
                            "content_type", Objects.toString(info.get("content_type"), ""),
                            "path", file.toString(),
                            "container_path", "/uploads/" + file.getFileName()
                    ));
                    handed.add(file);
                }
                if (!attachedFiles.isEmpty()) {
                    effectiveParams.put("_attached_files", attachedFiles);
                }
            }
            String inputJson = mapper.writeValueAsString(effectiveParams);

            Map<String, String> envVars = new HashMap<>(resolution.extraEnv());
            // Force UTF-8 for Python's stdin/stdout/stderr — prevents mojibake when
            // skills produce non-ASCII output (Czech, CJK, accented chars, etc.)
            envVars.put("PYTHONIOENCODING", "utf-8");
            envVars.put("PYTHONUTF8", "1");

            // Inject credentials from the encrypted vault as environment variables
            if (!requiredCredentials.isEmpty() && credentialVault != null && context.userId() != null) {
                log.info("Skill '{}': requesting credentials {} for user='{}'",
                        name, requiredCredentials, context.userId());
                Map<String, String> creds = credentialVault.getCredentials(
                        context.userId(), requiredCredentials);
                log.info("Skill '{}': vault returned {} credentials, keys={}",
                        name, creds.size(), creds.keySet());
                envVars.putAll(creds);
                if (creds.size() < requiredCredentials.size()) {
                    List<String> missing = requiredCredentials.stream()
                            .filter(k -> !creds.containsKey(k))
                            .toList();
                    log.warn("Skill '{}' missing credentials: {}", name, missing);
                    return ToolResult.failure(
                            "Missing required credentials: " + String.join(", ", missing)
                            + ". Ask the user to store each one by typing: /cred set KEY value"
                            + " — do not ask them to paste the value to you.");
                }
                log.info("Skill '{}': all {} credentials injected as env vars", name, creds.size());
            } else {
                log.info("Skill '{}': credential injection SKIPPED (requiredCredentials={}, "
                        + "credentialVault={}, userId={})",
                        name, requiredCredentials,
                        credentialVault != null ? "present" : "NULL",
                        context.userId() != null ? context.userId() : "NULL");
            }

            // Run the skill: choose container or direct process execution.
            // Skills with system_packages run in a Docker/Podman container so packages
            // can be installed without sudo. Falls back to direct execution if no
            // container runtime is available.
            SandboxResult result;
            boolean usedContainer = false;
            String containerImageTag = null;
            // The files this task was given, each on its own, read-only -- for the first run and
            // for a self-healed retry alike, or the retry cannot see the file it was fixed to read.
            Map<String, String> extraVolumes = attachedFiles.isEmpty() ? null : containerMounts(attachedFiles);
            if (!systemPackages.isEmpty() && containerSandbox != null && containerSandbox.isAvailable()) {
                // Container execution: build image with system packages + pip deps, run inside
                String pipReqs = readRequirements();
                containerImageTag = containerSandbox.ensureImage(systemPackages, pipReqs, skillDir, containerImage,
                        context.progressCallback());
                usedContainer = true;
                result = containerSandbox.execute(
                        containerImageTag, "python3", runnerScript, skillDir,
                        inputJson, envVars, timeoutSec,
                        context.progressCallback(), extraVolumes);
            } else {
                if (!systemPackages.isEmpty()) {
                    log.warn("Skill '{}' needs system packages {} but no container runtime available — "
                            + "running directly (may fail if packages not installed on host)",
                            name, systemPackages);
                }
                // Direct process execution (original path)
                result = sandbox.execute(
                        resolution.python(), runnerScript, skillDir,
                        inputJson, envVars, timeoutSec,
                        context.progressCallback(), readOnlyFor(resolution, handed));
            }

            if (result.timedOut()) {
                return ToolResult.failure("Skill '" + name + "' stalled (no output for " + timeoutSec + "s).");
            }

            if (result.isSuccess()) {
                ToolResult toolResult = parseOutput(result.stdout(), result.stderr());

                // Self-heal: ModuleNotFoundError → install missing package → retry once
                if (!toolResult.success()) {
                    String missingModule = extractMissingModule(toolResult.output());
                    if (missingModule != null) {
                        String pkg = MODULE_TO_PACKAGE.getOrDefault(missingModule, missingModule);
                        log.warn("SELF-HEAL: skill '{}' missing module '{}' → installing pip package '{}'",
                                name, pkg, missingModule);

                        boolean installed = pythonEnv.installPackages(skillDir, name, List.of(pkg));
                        if (installed) {
                            SandboxResult retry = retrySelfHeal(usedContainer, containerImageTag,
                                    runnerScript, skillDir, inputJson, envVars, timeoutSec, extraVolumes,
                                    handed);
                            if (retry != null && !retry.timedOut() && retry.isSuccess()) {
                                log.info("Self-heal succeeded for skill '{}'", name);
                                return parseOutput(retry.stdout(), retry.stderr());
                            }
                        }
                    }
                }

                return toolResult;
            } else {
                String error = result.stderr().isBlank()
                        ? "Exit code: " + result.exitCode()
                        : result.stderr();

                // Self-heal stderr-based ModuleNotFoundError too
                String missingModule = extractMissingModule(error);
                if (missingModule != null) {
                    String pkg = MODULE_TO_PACKAGE.getOrDefault(missingModule, missingModule);
                    log.info("Self-healing skill '{}' (stderr): installing '{}' for module '{}'",
                            name, pkg, missingModule);
                    if (pythonEnv.installPackages(skillDir, name, List.of(pkg))) {
                        SandboxResult retry = retrySelfHeal(usedContainer, containerImageTag,
                                runnerScript, skillDir, inputJson, envVars, timeoutSec, extraVolumes,
                                handed);
                        if (retry != null && !retry.timedOut() && retry.isSuccess()) {
                            log.info("Self-heal (stderr) succeeded for skill '{}'", name);
                            return parseOutput(retry.stdout(), retry.stderr());
                        }
                    }
                }

                return ToolResult.failure(error);
            }
        } catch (Exception e) {
            log.error("Dynamic skill '{}' execution failed: {}", name, e.getMessage());
            return ToolResult.failure("Execution error: " + e.getMessage());
        } finally {
            inFlight.decrementAndGet();
            // Clean up the temp runner script
            if (runnerScript != null) {
                try { Files.deleteIfExists(runnerScript); } catch (IOException ignored) {}
            }
        }
    }

    /**
     * One mount per file the skill was handed, at the {@code container_path} it was told.
     * <p>
     * The whole uploads directory used to be mounted, and it holds every file every user has
     * sent: a skill handed one statement could read all of them. Now the container sees exactly
     * the files in {@code _attached_files}, the same list the task's label is decided by.
     */
    static Map<String, String> containerMounts(List<Map<String, String>> attached) {
        var mounts = new java.util.LinkedHashMap<String, String>();
        for (var f : attached) mounts.put(f.get("path"), f.get("container_path"));
        return mounts;
    }

    /**
     * The handed file's real path, or null -- logged by id, never by name -- when it cannot be
     * handed over: the record names no plain file in the uploads directory, the file is gone,
     * it is a link, or its real path is anything but a regular file directly inside the uploads
     * directory. A bad attachment is dropped, not thrown, so one bad row never fails the task.
     * <p>
     * The real path, because it is bound into the jail at that path and so has to be what the
     * skill is told; and a regular file directly inside uploads, because a link or a crafted
     * row pointing at the database or at another user's upload would otherwise be bound in
     * read-only as though it were the file this task was given.
     */
    private Path handedFile(String fileId) {
        Path stored = fileStorage.getFilePath(fileId);
        if (stored == null) {
            log.warn("Skill '{}': attachment {} names no regular file in the uploads directory; not handing it over",
                    name, fileId);
            return null;
        }
        try {
            if (!Files.isRegularFile(stored, LinkOption.NOFOLLOW_LINKS)) {
                log.warn("Skill '{}': attachment {} is missing, a link or not a regular file; not handing it over",
                        name, fileId);
                return null;
            }
            Path uploads = fileStorage.getUploadsDir().toRealPath();
            Path real = stored.toRealPath();
            if (!uploads.equals(real.getParent()) || !Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)) {
                log.warn("Skill '{}': attachment {} is not a regular file directly in the uploads directory; "
                        + "not handing it over", name, fileId);
                return null;
            }
            return real;
        } catch (IOException | RuntimeException e) {
            log.warn("Skill '{}': attachment {} could not be resolved ({}); not handing it over",
                    name, fileId, e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * What an isolated run may read besides the read-only host: the environment its imports
     * come from, and the files this task was handed. Nothing else of the service's.
     */
    static List<Path> readOnlyFor(PythonEnvironmentService.PythonResolution resolution, List<Path> handed) {
        var out = new ArrayList<Path>();
        if (resolution.envDir() != null) out.add(resolution.envDir());
        out.addAll(handed);
        return out;
    }

    /**
     * Write one of the JVM's helper scripts into a skill's directory, under a name nobody can
     * guess and never through anything already there.
     * <p>
     * The directory is the skill's own and writable by it. A link it left at a predictable name
     * -- the load check was always {@code _load_check.py} -- made the JVM's next write land
     * wherever the link pointed. The name now carries 128 random bits, and the write removes
     * whatever is at the name and then creates the file new.
     */
    public static Path writeHelperScript(Path dir, String prefix, String content) throws IOException {
        byte[] nonce = new byte[16];
        NONCES.nextBytes(nonce);
        Path script = dir.resolve(prefix + HexFormat.of().formatHex(nonce) + ".py");
        SkillJail.writeFresh(script, content);
        return script;
    }

    /**
     * Retry a self-healed skill execution using the same execution path as the original run.
     * Container-based skills retry inside the container; direct skills retry via the process
     * sandbox, with the same handed files as the first run and the environment as it is now.
     */
    private SandboxResult retrySelfHeal(boolean usedContainer, String containerImageTag,
                                         Path runnerScript, Path skillDir, String inputJson,
                                         Map<String, String> envVars, int timeoutSec,
                                         Map<String, String> extraVolumes, List<Path> handed) {
        try {
            if (usedContainer && containerSandbox != null && containerSandbox.isAvailable()) {
                // Rebuild image to pick up newly installed pip packages
                String pipReqs = readRequirements();
                String healedImageTag = containerSandbox.ensureImage(systemPackages, pipReqs, skillDir, containerImage, null);
                return containerSandbox.execute(
                        healedImageTag, "python3", runnerScript, skillDir,
                        inputJson, envVars, timeoutSec, null, extraVolumes);
            } else {
                // Direct process execution
                var healedResolution = pythonEnv.resolveExecution(skillDir, name);
                Map<String, String> healedEnv = new HashMap<>(healedResolution.extraEnv());
                healedEnv.put("PYTHONIOENCODING", "utf-8");
                healedEnv.put("PYTHONUTF8", "1");
                healedEnv.putAll(envVars.entrySet().stream()
                        .filter(e -> !healedEnv.containsKey(e.getKey()))
                        .collect(java.util.stream.Collectors.toMap(
                                Map.Entry::getKey, Map.Entry::getValue)));
                // The heal may have just created the environment, so it is read again rather
                // than taken from the first run; the handed files are the first run's own.
                return sandbox.execute(
                        healedResolution.python(), runnerScript, skillDir,
                        inputJson, healedEnv, timeoutSec, null, readOnlyFor(healedResolution, handed));
            }
        } catch (Exception e) {
            log.error("Self-heal retry failed for skill '{}': {}", name, e.getMessage());
            return null;
        }
    }

    /**
     * Parse the Python script's stdout into a ToolResult.
     * Expected JSON: {"success": true, "output": "text", "data": {}}
     * Falls back to treating stdout as plain text if not valid JSON.
     *
     * @param stdout the script's standard output
     * @param stderr the script's standard error (included in failure messages for diagnostics)
     */
    private ToolResult parseOutput(String stdout, String stderr) {
        if (stdout == null || stdout.isBlank()) {
            String detail = (stderr != null && !stderr.isBlank())
                    ? "stderr: " + stderr.strip()
                    : "The skill's run() function may not be returning/printing output.";
            return ToolResult.failure("Tool produced no output. " + detail
                    + " Use skill_create with the SAME name '" + name + "' to fix it.");
        }

        try {
            Map<String, Object> parsed = mapper.readValue(stdout.strip(), new TypeReference<>() {});
            boolean success = Boolean.TRUE.equals(parsed.get("success"));
            String output = parsed.containsKey("output") ? String.valueOf(parsed.get("output")) : stdout;

            // Safety net: if output starts with ERROR: but success was True (LLM code bug), flip to failure
            if (success && output != null && output.startsWith("ERROR:")) {
                log.warn("Skill output starts with 'ERROR:' but success=true — treating as failure");
                success = false;
            }

            // Treat empty output content as failure even if success=true
            if (success && (output == null || output.isBlank() || "null".equals(output))) {
                String hint = (stderr != null && !stderr.isBlank())
                        ? " stderr: " + stderr.strip()
                        : "";
                return ToolResult.failure(
                        "Tool returned success but with empty output — this usually means the skill " +
                        "code has a bug (e.g. missing return, wrong variable, unhandled error)." + hint
                        + " Use skill_create with the SAME name '" + name + "' to fix it.");
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> data = parsed.containsKey("data") && parsed.get("data") instanceof Map
                    ? (Map<String, Object>) parsed.get("data")
                    : Map.of();

            // Append stderr as a warning if present on a successful result
            if (success && stderr != null && !stderr.isBlank()) {
                output = output + "\n[stderr warning: " + stderr.strip() + "]";
            }

            return success ? ToolResult.success(output, data) : ToolResult.failure(output, data);
        } catch (Exception e) {
            // Full stdout isn't valid JSON — try parsing the last non-empty line
            // (skills may print debug info on earlier lines, with the JSON result last)
            String lastLine = lastNonEmptyLine(stdout);
            if (lastLine != null && lastLine.startsWith("{")) {
                try {
                    Map<String, Object> fallback = mapper.readValue(lastLine, new TypeReference<>() {});
                    boolean ok = Boolean.TRUE.equals(fallback.get("success"));
                    String out = fallback.containsKey("output") ? String.valueOf(fallback.get("output")) : lastLine;
                    return ok ? ToolResult.success(out) : ToolResult.failure(out);
                } catch (Exception ignored) {}
            }
            // Non-JSON output from the runner harness means something went wrong.
            // Treat as failure so the agent gets a signal to investigate.
            String detail = (stderr != null && !stderr.isBlank())
                    ? " stderr: " + stderr.strip() : "";
            return ToolResult.failure(
                    "Tool produced non-JSON output (possible runner error): "
                    + truncateStr(stdout.strip(), 500) + detail
                    + " Use skill_create with the SAME name '" + name + "' to fix.");
        }
    }

    /** Return the last non-empty line in a multi-line string, or null. */
    private static String lastNonEmptyLine(String text) {
        if (text == null) return null;
        String[] lines = text.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].strip();
            if (!line.isEmpty()) return line;
        }
        return null;
    }

    /** Truncate a string with ellipsis. */
    private static String truncateStr(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    /**
     * Extract the missing module name from a Python error message, or null.
     * <p>
     * The message is the skill's own output, so the skill decides what it says: a crafted
     * "No module named '-e git+https://...'" or "'../../some/dir'" would otherwise reach pip as
     * an option, a URL or a path to build. Only a plain name survives -- the same test
     * installPackages applies before pip starts.
     */
    static String extractMissingModule(String outputText) {
        if (outputText == null || outputText.isBlank()) return null;
        Matcher m = MODULE_NOT_FOUND.matcher(outputText);
        if (!m.find()) return null;
        String mod = m.group(1) != null ? m.group(1) : m.group(2);
        if (mod == null || mod.isBlank()) return null;
        // Top-level package only (e.g. "bs4.element" → "bs4")
        int dot = mod.indexOf('.');
        if (dot > 0) mod = mod.substring(0, dot);
        mod = mod.strip();
        return PythonEnvironmentService.isPlainName(mod) ? mod : null;
    }
}
