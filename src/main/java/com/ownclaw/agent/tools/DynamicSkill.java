package com.ownclaw.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
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

    /**
     * The key of the runner's result that names a module the skill's code failed to import: what
     * the runner sets from an uncaught {@code ModuleNotFoundError}, and takes out of a result the
     * skill returned itself. The self-heal reads this and nothing else ({@link #toHeal}).
     */
    static final String MISSING_MODULE = "_missing_module";

    /**
     * The key of this run's id: handed to the runner on its stdin, taken out of the parameters
     * before skill.py is loaded, and set beside {@link #MISSING_MODULE} by the runner alone. The
     * result line is the last line of stdout, and a skill that exits on its own -- sys.exit,
     * os._exit, a crash -- leaves no line of the runner's: the last line is then whatever it
     * printed last, a fetched page's text among it. A line without this run's id names no module
     * to install, whatever it says.
     */
    static final String RUN = "_run";

    /**
     * The key the runner alone sets, to this run's id, on the result it makes of an exception the
     * skill raised: its "Skill error: ..." and traceback are then the runner's own account, not
     * text a skill returned under that prefix -- which can be anything it read.
     */
    static final String RAISED = "_raised";

    /** An import statement of skill.py: at a line's start, or after a colon or a semicolon ("try: import ujson"). */
    private static final Pattern IMPORT = Pattern.compile("(?:^|[:;])\\s*import\\s+([^#;]+)");
    private static final Pattern FROM_IMPORT = Pattern.compile("(?:^|[:;])\\s*from\\s+([A-Za-z_]\\w*)[\\w.]*\\s+import\\b");

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
    /** Whether {@link #parameters} is all its SKILL.yaml declares: false when part of that could not be read. */
    private final boolean declaredInFull;
    private final Path skillDir;
    private final boolean requiresNetwork;
    private final boolean hasSideEffects;
    /** What SKILL.yaml says of its side effects: true, false, or null when it says nothing. */
    private final Boolean declaredSideEffects;
    private final int timeoutSec;
    private final SandboxManager sandbox;
    private final PythonEnvironmentService pythonEnv;
    private final List<String> requiredCredentials;
    private final CredentialVault credentialVault;
    private final List<String> systemPackages;
    private final String containerImage;
    private final ContainerSandbox containerSandbox;
    private final com.ownclaw.conversation.FileStorageService fileStorage;
    /** What gives a run on the host the system packages the skill declares; null where nothing does. */
    private volatile com.ownclaw.sandbox.HostPackages hostPackages;

    /**
     * How many invocations of this skill are running right now.
     * <p>
     * Read before retiring a skill. Retirement moves the skill's directory, and a running
     * invocation is reading {@code skill.py}, its virtualenv and its own {@code _runner_} harness
     * out of that directory the whole time it executes -- some of them for minutes. Moving it
     * mid-run does not fail cleanly: the interpreter is already started, so what the user gets is
     * a Python error about a path that existed a moment ago.
     */
    private final java.util.concurrent.atomic.AtomicInteger inFlight =
            new java.util.concurrent.atomic.AtomicInteger();

    /** Invocations currently executing. Zero means the directory is safe to move. */
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
        this(name, description, parameters, skillDir, requiresNetwork, (Boolean) hasSideEffects, timeoutSec, sandbox,
                pythonEnv, requiredCredentials, credentialVault, systemPackages, containerImage, containerSandbox,
                fileStorage);
    }

    /** @param declaredSideEffects what SKILL.yaml says of its side effects: true, false, or null for nothing */
    public DynamicSkill(String name, String description, Map<String, ToolParam> parameters,
                        Path skillDir, boolean requiresNetwork, Boolean declaredSideEffects,
                        int timeoutSec, SandboxManager sandbox, PythonEnvironmentService pythonEnv,
                        List<String> requiredCredentials, CredentialVault credentialVault,
                        List<String> systemPackages, String containerImage,
                        ContainerSandbox containerSandbox,
                        com.ownclaw.conversation.FileStorageService fileStorage) {
        this(name, description, parameters, true, skillDir, requiresNetwork, declaredSideEffects, timeoutSec,
                sandbox, pythonEnv, requiredCredentials, credentialVault, systemPackages, containerImage,
                containerSandbox, fileStorage);
    }

    /**
     * @param declaredInFull      whether {@code parameters} is all its SKILL.yaml declares
     *                            ({@link DynamicSkillRegistry.Declaration#whole})
     * @param declaredSideEffects what SKILL.yaml says of its side effects: true, false, or null for nothing
     */
    public DynamicSkill(String name, String description, Map<String, ToolParam> parameters, boolean declaredInFull,
                        Path skillDir, boolean requiresNetwork, Boolean declaredSideEffects,
                        int timeoutSec, SandboxManager sandbox, PythonEnvironmentService pythonEnv,
                        List<String> requiredCredentials, CredentialVault credentialVault,
                        List<String> systemPackages, String containerImage,
                        ContainerSandbox containerSandbox,
                        com.ownclaw.conversation.FileStorageService fileStorage) {
        this.declaredSideEffects = declaredSideEffects;
        boolean hasSideEffects = Boolean.TRUE.equals(declaredSideEffects);
        this.name = name;
        this.description = description;
        this.parameters = parameters;
        this.declaredInFull = declaredInFull;
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
    @Override public boolean declaredInFull() { return declaredInFull; }
    @Override public boolean requiresNetwork() { return requiresNetwork; }
    @Override public boolean hasSideEffects() { return hasSideEffects; }
    @Override public Boolean declaredSideEffects() { return declaredSideEffects; }
    @Override public int estimatedMaxDurationSeconds() { return timeoutSec; }
    @Override public List<String> requiredCredentials() { return requiredCredentials; }

    /**
     * Whether skill.py reads {@code name} from the parameters it is handed, read from the file
     * when asked: the code may have changed since the skill was loaded.
     */
    @Override
    public boolean readsParameter(String name) {
        try {
            String code = SkillJail.readAuthored(skillDir.resolve("skill.py"));
            return code != null && readsParameter(code, name);
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Whether a skill's source reads {@code name} from the parameters it is handed, by that name:
     * {@code params.get('name'}, {@code params.pop('name'}, {@code params.setdefault('name'},
     * {@code params['name']} where that is not what is assigned to, or {@code 'name' in params}
     * -- in either quote, {@code params} being that word or whatever {@code def run(...)} calls
     * its argument: a skill that begins {@code params = args or {}} reads through both.
     * <p>
     * A call the cloud wrote is refused for a name its skill does not declare, because a skill
     * ignores such a name without a word: a mail's text passed as 'body', which the mailer does
     * not read, is a mail sent without it. A name the code reads is not ignored. Production has
     * two that their skills read and do not declare, an alias and an optional timeout, and the
     * cloud had no way to pass either (the review of e16a1ff, 2026-11-18).
     * <p>
     * A write is no read: a skill that builds a request's query and sets {@code params['limit']}
     * itself ignores the 'limit' it is handed, and counted as reading it the call's own 'limit'
     * was let through and lost (the second review). An assignment that also reads -- {@code +=}
     * -- is a read.
     * <p>
     * A plain pattern over the text, not a reading of the Python: it also finds the name in a
     * comment or a string, and misses one read through another variable. Either way the worst
     * is what held before the check -- a name let through that the skill ignores, or one refused
     * that it would have read.
     */
    static boolean readsParameter(String code, String name) {
        var run = Pattern.compile("(?m)^def\\s+run\\s*\\(\\s*([A-Za-z_][A-Za-z0-9_]*)").matcher(code);
        String handed = "\\b(?:params|" + Pattern.quote(run.find() ? run.group(1) : "params") + ")";
        String quoted = "(?:'" + Pattern.quote(name) + "'|\"" + Pattern.quote(name) + "\")";
        return Pattern.compile(handed + "\\s*\\.\\s*(?:get|pop|setdefault)\\s*\\(\\s*" + quoted
                + "|" + handed + "\\s*\\[\\s*" + quoted + "\\s*\\](?!\\s*=(?!=))"
                + "|" + quoted + "\\s+(?:not\\s+)?in\\s+" + handed + "\\b").matcher(code).find();
    }

    @Override
    public List<String> ungranted(String userId) {
        if (requiredCredentials.isEmpty() || credentialVault == null || userId == null) return List.of();
        // Not a credential that is never handed to a skill: nobody can grant it, so nobody is asked
        // to -- a run of a skill that declares one fails by itself, saying to take it out (run).
        return credentialVault.grants().ungranted(userId, name,
                requiredCredentials.stream().filter(CredentialVault::forSkills).toList());
    }

    /** The directory containing this skill's files. */
    public Path skillDir() { return skillDir; }

    /** System packages required by this skill (e.g. nmap, net-tools). */
    public List<String> systemPackages() { return systemPackages; }

    /**
     * What gives this skill's runs on the host -- with no docker or podman to build an image in --
     * the system packages it declares, without root ({@link com.ownclaw.sandbox.HostPackages}).
     */
    public void setHostPackages(com.ownclaw.sandbox.HostPackages hostPackages) { this.hostPackages = hostPackages; }

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
     * check, so the check loads skill.py as a run does -- the same code, {@code report_progress}
     * in place. A run has what the check has not: its input and its credentials.
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
     * The module is in {@code sys.modules} as {@code skill} before it runs, as an import would
     * put it there. A function it defines is pickled by that name, so a skill can hand one to a
     * multiprocessing worker under any start method: one started by spawn or forkserver imports
     * skill.py by the name, through the finder the loader puts first on {@code sys.meta_path},
     * which answers for {@code skill} alone.
     * <p>
     * Nothing else in the skill's directory is ever imported. Python puts a script's directory
     * first on {@code sys.path}, and the runner is written into the skill's directory, which is
     * the skill's own and writable by every run of it. A module one run left there -- a json.py,
     * a requests.py -- was imported by the next run, with that run's credentials, ahead of the
     * standard library and the skill's packages: code nobody wrote with skill_create, and nobody
     * granted. The loader takes the directory off {@code sys.path} before it imports anything
     * that could be found there; {@code sys} and {@code os} are in before its first line runs. So
     * what runs is skill.py and the packages its environment holds -- read-only to a run -- and a
     * file a run leaves is data to the next, never its code, unless skill.py itself runs it.
     */
    public static final String SKILL_LOADER = String.join("\n",
        "import sys, os",
        "_skill_dir = os.path.dirname(os.path.abspath(__file__))",
        "# Nothing in the skill's directory is imported but skill.py: it is off sys.path before",
        "# anything that could be found there is imported. sys and os are in already.",
        "_real_skill_dir = os.path.realpath(_skill_dir)",
        "sys.path[:] = [p for p in sys.path if os.path.realpath(p or os.curdir) != _real_skill_dir]",
        "import json, builtins, traceback, inspect, importlib.util, importlib.machinery",
        "",
        "# --- Progress reporting API for long-running skills ---",
        "# Skills call report_progress('Scanning host 12/255', percent=5)",
        "# The message is emitted as a JSON line on stdout, before the result line;",
        "# the sandbox takes it out of stdout and hands it to the caller if it listens.",
        "_real_stdout = sys.__stdout__",
        "# What the skill prints reaches stdout a line at a time, as it prints it.",
        "try:",
        "    _real_stdout.reconfigure(line_buffering=True)",
        "except Exception:",
        "    pass",
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
        "builtins.report_progress = report_progress",
        "",
        "class _SkillSource(importlib.machinery.SourceFileLoader):",
        "    \"\"\"skill.py, compiled from its source each time: never a __pycache__ entry.\"\"\"",
        "    def exec_module(self, mod):",
        "        with open(self.path, 'rb') as f:",
        "            source = f.read()",
        "        mod.report_progress = report_progress",
        "        exec(compile(source, self.path, 'exec', dont_inherit=True), mod.__dict__)",
        "",
        "class _SkillOnly:",
        "    \"\"\"`import skill` finds skill.py, and nothing else is looked for in its directory.\"\"\"",
        "    @staticmethod",
        "    def find_spec(name, path=None, target=None):",
        "        if name != 'skill':",
        "            return None",
        "        path = os.path.join(_skill_dir, 'skill.py')",
        "        return importlib.util.spec_from_file_location('skill', path, loader=_SkillSource('skill', path))",
        "",
        "sys.meta_path.insert(0, _SkillOnly)",
        "",
        "def _load_skill():",
        "    spec = _SkillOnly.find_spec('skill')",
        "    mod = importlib.util.module_from_spec(spec)",
        "    sys.modules['skill'] = mod",
        "    spec.loader.exec_module(mod)",
        "    return mod",
        ""
    );

    /**
     * The key a skill's credentials travel under in the JSON on the runner's stdin. The runner
     * takes them out of the parameters and puts them into its own environment before skill.py is
     * loaded, so the skill reads them from {@code os.environ}, as it always has, its subprocesses
     * inherit them, and they are never in the params it is given. They are never on the command
     * line or in the environment of a process the service starts, either: handed to the container
     * runtime as {@code -e NAME=VALUE}, every value -- a password, a whole SSH private key -- was
     * in the host's process list, readable by every account on the machine, for as long as the
     * container ran.
     */
    static final String CREDENTIALS = "_credentials";

    /**
     * The runner harness that bootstraps skill execution.
     *
     * <p>We do NOT run skill.py directly — instead we run this thin wrapper that:
     * <ol>
     *   <li>Reads JSON parameters from stdin, and moves the {@link #CREDENTIALS} among them into
     *       its own environment</li>
     *   <li>Imports skill.py and calls its {@code run(params)} function</li>
     *   <li>Prints the returned dict as JSON to stdout, as the last line</li>
     *   <li>Catches and reports any exception as a structured failure</li>
     * </ol>
     *
     * <p>This decouples the LLM's authoring convention ({@code def run(params): return ...})
     * from the process-level stdin/stdout contract.
     *
     * <p>What the skill prints goes to stdout as it prints it, a line at a time, before the
     * result line ({@link #parseOutput}): on every path -- a result, an exception, sys.exit, a
     * hang the sandbox ends -- and counted as output by the sandbox's stall check, which ends a
     * run only after no output for its timeout. The prints were once held back and added to the
     * result's {@code output} on success alone: a skill that printed and then raised, or called
     * sys.exit, lost them, and one that printed and returned a dict or a number as its output had
     * that output broken by the addition -- a failure, for a send that had gone.
     *
     * <p>Skills can call {@code report_progress(message, percent=None)} ({@link #SKILL_LOADER}) to
     * emit structured progress updates for long-running tasks.  These are intercepted
     * by the sandbox and forwarded to the user's chat in real time when the caller gave a
     * progress callback; never part of the stdout the result is read from either way.
     */
    private static final String RUNNER_HARNESS = SKILL_LOADER + String.join("\n",
        "",
        "def _result(result):",
        "    \"\"\"The result: the last line, on a line of its own whatever was printed before it.\"\"\"",
        "    _real_stdout.write('\\n' + json.dumps(result, default=str, ensure_ascii=False) + '\\n')",
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
        "# Only when run, not when imported: a multiprocessing worker started by spawn or",
        "# forkserver -- the default from Python 3.14 -- imports this script again, as __mp_main__.",
        "if __name__ == '__main__':",
        "    _run_id = None",
        "    try:",
        "        params = json.loads(sys.stdin.read()) if not sys.stdin.isatty() else {}",
        "        os.environ.update(params.pop('" + CREDENTIALS + "', None) or {})",
        "        _run_id = params.pop('" + RUN + "', None)",
        "        mod = _load_skill()",
        "        if not hasattr(mod, 'run'):",
        "            _result({'success': False, 'output': 'skill.py does not define a run(params) function'})",
        "            sys.exit(0)",
        "        result = _dispatch_run(mod.run, params)",
        "        if not isinstance(result, dict):",
        "            result = {'output': str(result) if result is not None else ''}",
        "        if 'success' not in result:",
        "            result['success'] = True",
        "        result.pop('" + MISSING_MODULE + "', None)",
        "        result.pop('" + RUN + "', None)",
        "        result.pop('" + RAISED + "', None)",
        "        _result(result)",
        "    except Exception as e:",
        "        failed = {'success': False, 'output': f'Skill error: {e}\\n{traceback.format_exc()}', '" + RAISED + "': _run_id}",
        "        if isinstance(e, ModuleNotFoundError) and isinstance(e.name, str):",
        "            failed['" + MISSING_MODULE + "'] = e.name",
        "            failed['" + RUN + "'] = _run_id",
        "        _result(failed)",
        ""
    );

    @Override
    public ToolResult execute(Map<String, Object> params, ToolExecutionContext context) {
        // Read-held for the run: a skill_create, delete or retirement of it waits for the run.
        // One under way is waited for in turn, said to the task as its progress.
        var running = SkillLocks.of(name).readLock();
        var progress = context == null ? null : context.progressCallback();
        if (!SkillLocks.take(running, stoppedOf(context), () -> {
            if (progress != null) progress.onProgress("Waiting: skill '" + name + "' is being changed by another task", null);
        })) {
            return said("Stopped while skill '" + name + "' was being changed: it did not run.");
        }
        try {
            return run(params, context);
        } finally {
            running.unlock();
        }
    }

    /** Whether the task running this has been stopped: what ends its waits for other tasks. */
    private static java.util.function.BooleanSupplier stoppedOf(ToolExecutionContext context) {
        return context == null || context.cancelled() == null ? () -> false : context.cancelled();
    }

    /** Said to the task while another task sets up this skill's environment: its progress meanwhile. */
    private Runnable settingUp(ToolExecutionContext context) {
        var progress = context == null ? null : context.progressCallback();
        return () -> {
            if (progress != null) {
                progress.onProgress("Waiting: another task is setting up the environment of skill '" + name + "'", null);
            }
        };
    }

    private ToolResult run(Map<String, Object> params, ToolExecutionContext context) {
        Path scriptPath = skillDir.resolve("skill.py");
        if (!Files.exists(scriptPath)) {
            return ToolResult.failure("Skill script not found: " + scriptPath, "Skill script not found: skill.py");
        }
        // Here, before the container is chosen: a run in docker or podman never passes the jail,
        // so while it refuses every launch it would be the one way around it.
        Optional<String> refused = sandbox == null ? Optional.empty() : sandbox.unavailable();
        if (refused.isPresent()) return said(refused.get());

        Path runnerScript = null;
        inFlight.incrementAndGet();
        try {
            // Resolve Python (creates venv + installs requirements if needed)
            var resolution = pythonEnv.resolveExecution(skillDir, name, stoppedOf(context), settingUp(context));

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

            // The skill's credentials, from the encrypted vault, go in with its input (see
            // CREDENTIALS) -- always set, so a parameter of that name from the caller never
            // reaches the runner's environment.
            Map<String, String> creds = Map.of();
            for (String key : requiredCredentials) {
                if (!CredentialVault.forSkills(key)) {
                    log.warn("Skill '{}' declares {}, which is never handed to a skill; not run", name, key);
                    return ToolResult.failure("The credential " + key + " is never handed to a skill: it "
                            + "signs the links that open the owner's chat as verified, and a skill that could "
                            + "read it could sign any message. Remove it from this skill's credentials.");
                }
            }
            if (!requiredCredentials.isEmpty() && credentialVault != null && context.userId() != null) {
                log.info("Skill '{}': requesting credentials {} for user='{}'",
                        name, requiredCredentials, context.userId());
                // Only what its user granted this skill, and all of it or nothing: whoever calls
                // a skill asks first (Tool#ungranted), and this is where it is decided.
                CredentialVault.Handover fromVault = credentialVault.forSkill(
                        context.userId(), name, requiredCredentials);
                String missing = fromVault.missing().isEmpty() ? null
                        : "Missing required credentials: " + String.join(", ", fromVault.missing())
                        + ". Ask the user to store each one by typing: /cred set KEY value"
                        + " — do not ask them to paste the value to you.";
                if (!fromVault.ungranted().isEmpty()) {
                    log.warn("Skill '{}' not run: user '{}' has not granted it {}", name, context.userId(),
                            fromVault.ungranted());
                    return ToolResult.failure("Not run: "
                            + com.ownclaw.users.CredentialGrantService.notAllowed(name, fromVault.ungranted())
                            + (missing == null ? "" : " " + missing));
                }
                if (missing != null) {
                    log.warn("Skill '{}' missing credentials: {}", name, fromVault.missing());
                    return said(missing);
                }
                Map<String, String> found = fromVault.values();
                log.info("Skill '{}': vault returned {} credentials, keys={}",
                        name, found.size(), found.keySet());
                creds = found;
                log.info("Skill '{}': all {} credentials handed over with its input", name, creds.size());
            } else {
                log.info("Skill '{}': credential injection SKIPPED (requiredCredentials={}, "
                        + "credentialVault={}, userId={})",
                        name, requiredCredentials,
                        credentialVault != null ? "present" : "NULL",
                        context.userId() != null ? context.userId() : "NULL");
            }
            effectiveParams.put(CREDENTIALS, creds);
            // This run's id: the runner names a missing module beside it, and only such a line is read (toHeal).
            String runId = nonce();
            effectiveParams.put(RUN, runId);
            String inputJson = mapper.writeValueAsString(effectiveParams);

            // The skill's environment: its venv on PATH, or its target on PYTHONPATH. The UTF-8
            // encoding every host run gets is set where every launch's environment is made
            // (SkillJail.applyEnvironment); a container's comes from its image (ENV lines).
            Map<String, String> envVars = new HashMap<>(resolution.extraEnv());

            // Run the skill: choose container or direct process execution.
            // Skills with system_packages run in a Docker/Podman container so packages
            // can be installed without sudo. Falls back to direct execution if no
            // container runtime is available.
            SandboxResult result;
            boolean usedContainer = false;
            // The declared system packages of a run on the host, and what it reads: the files it
            // was handed, and those packages.
            com.ownclaw.sandbox.HostPackages.Provided packages = null;
            var readable = new ArrayList<Path>(handed);
            String containerImageTag = null;
            // The files this task was given, each on its own, read-only -- for the first run and
            // for a self-healed retry alike, or the retry cannot see the file it was fixed to read.
            Map<String, String> extraVolumes = attachedFiles.isEmpty() ? null : containerMounts(attachedFiles);
            if (!systemPackages.isEmpty() && containerSandbox != null && containerSandbox.isAvailable()) {
                // Container execution: build image with system packages + pip deps, run inside
                String pipReqs = readRequirements();
                containerImageTag = containerSandbox.ensureImage(systemPackages, pipReqs, skillDir, containerImage,
                        context.progressCallback(), stoppedOf(context));
                usedContainer = true;
                result = containerSandbox.execute(
                        containerImageTag, "python3", runnerScript, skillDir,
                        inputJson, timeoutSec,
                        context.progressCallback(), extraVolumes);
            } else {
                // No container runtime: what the host lacks of the declared packages, unpacked
                // without root into a directory of the service's, which the run reads -- or, when
                // they cannot be had, the run as before, and its result says why.
                if (!systemPackages.isEmpty()) {
                    if (hostPackages == null) {
                        log.warn("Skill '{}' needs system packages {} but nothing here can give them -- running "
                                + "without them", name, systemPackages);
                    } else {
                        var progress = context.progressCallback();
                        packages = hostPackages.provide(systemPackages,
                                line -> { if (progress != null) progress.onProgress(line, null); }, stoppedOf(context));
                        if (packages.failure() != null) {
                            log.warn("Skill '{}' runs without its system packages {}: {}", name, systemPackages,
                                    packages.failure());
                        }
                        // Stopped while its packages were being fetched: it does not run.
                        if (stoppedOf(context).getAsBoolean()) {
                            return ToolResult.failure("Stopped while the system packages of skill '" + name
                                    + "' were being fetched: it did not run.");
                        }
                        packages.applyTo(envVars);
                        readable.addAll(packages.binds());
                    }
                }
                // Direct process execution (original path)
                result = sandbox.execute(
                        resolution.python(), runnerScript, skillDir,
                        inputJson, envVars, timeoutSec,
                        context.progressCallback(), readOnlyFor(resolution, readable), context.userId());
            }

            ToolResult toolResult = withoutFileNames(interpret(result, runId), attachedFiles);

            // Self-heal: ModuleNotFoundError → install missing package → retry once. What the
            // retry returns is the answer, failure included: the package is in by then, and the
            // error it hits next is the one that matters. A package that cannot be installed
            // fails with pip's own account of why, beside the error that asked for it. Which
            // module is the runner's to say, and only one skill.py imports is installed (toHeal).
            String missingModule = null;
            if (!toolResult.success() && !result.timedOut()) {
                Heal heal = toHeal(result.stdout(), runId);
                missingModule = heal.module();
                if (heal.notHealed() != null) toolResult = failedWith(toolResult, "not installed", heal.notHealed());
            }
            if (missingModule != null) {
                String pkg = MODULE_TO_PACKAGE.getOrDefault(missingModule, missingModule);
                log.warn("SELF-HEAL: skill '{}' missing module '{}' → installing pip package '{}'",
                        name, missingModule, pkg);
                try {
                    pythonEnv.installPackages(skillDir, name, List.of(pkg), stoppedOf(context), settingUp(context));
                    SandboxResult retry = retrySelfHeal(stoppedOf(context), usedContainer, containerImageTag,
                            runnerScript, skillDir, inputJson, envVars, timeoutSec, extraVolumes, readable,
                            packages, context.userId());
                    if (retry != null) {
                        toolResult = withoutFileNames(interpret(retry, runId), attachedFiles);
                        log.info("Self-heal retry of skill '{}': {}", name,
                                toolResult.success() ? "succeeded" : "failed");
                    }
                } catch (IOException notInstalled) {
                    toolResult = failedWith(toolResult, "installing " + pkg + " failed",
                            notInstalled.getMessage());
                }
            }
            // Why the skill's own requirements are not installed, when they are not: the run
            // went on without them, and its error names only the module it then missed -- a
            // wrong package name, a wheel that needs a system library and an index out of reach
            // all read "No module named" there, and only pip says which.
            // Why its system packages are not there, when they are not: its error then names
            // only the program or the library it missed.
            if (packages != null) toolResult = failedWith(toolResult, "system packages not installed", packages.failure());
            return failedWith(toolResult, "requirements not installed", resolution.installError());
        } catch (Exception e) {
            String message = String.valueOf(e.getMessage());
            log.error("Dynamic skill '{}' execution failed: {}", name, firstLineOf(message));
            return ToolResult.failure("Execution error: " + message);
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
     * handed over: the record names no regular file directly in the uploads directory, or one
     * with another name (see {@code FileStorageService.getFilePath}). A bad attachment is
     * dropped, not thrown, so one bad row never fails the task.
     * <p>
     * The real path, because it is bound into the jail at that path and so has to be what the
     * skill is told. A regular file directly in uploads is not a link, so its real path is the
     * same file, directly in the real uploads directory.
     */
    private Path handedFile(String fileId) {
        Path stored = fileStorage.getFilePath(fileId);
        if (stored == null) {
            log.warn("Skill '{}': attachment {} names no regular file of its own in the uploads directory; not handing it over",
                    name, fileId);
            return null;
        }
        try {
            return stored.toRealPath();
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
    public static List<Path> readOnlyFor(PythonEnvironmentService.PythonResolution resolution, List<Path> handed) {
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
        Path script = dir.resolve(prefix + nonce() + ".py");
        SkillJail.writeFresh(script, content);
        return script;
    }

    /** 128 random bits, as hex: a name nobody can guess, or a run's id. */
    static String nonce() {
        byte[] nonce = new byte[16];
        NONCES.nextBytes(nonce);
        return HexFormat.of().formatHex(nonce);
    }

    /**
     * Retry a self-healed skill execution using the same execution path as the original run.
     * Container-based skills retry inside the container; direct skills retry via the process
     * sandbox, with the same handed files as the first run and the environment as it is now.
     */
    private SandboxResult retrySelfHeal(java.util.function.BooleanSupplier stopped, boolean usedContainer,
                                         String containerImageTag,
                                         Path runnerScript, Path skillDir, String inputJson,
                                         Map<String, String> envVars, int timeoutSec,
                                         Map<String, String> extraVolumes, List<Path> handed,
                                         com.ownclaw.sandbox.HostPackages.Provided packages, String tmpOwner) {
        try {
            // Asked again: the jail may have started refusing since the first run, and a run in
            // docker or podman never passes it.
            Optional<String> refused = sandbox == null ? Optional.empty() : sandbox.unavailable();
            if (refused.isPresent()) {
                log.warn("Self-heal retry of skill '{}' not run: {}", name, refused.get());
                return null;
            }
            if (usedContainer && containerSandbox != null && containerSandbox.isAvailable()) {
                // Rebuild image to pick up newly installed pip packages
                String pipReqs = readRequirements();
                String healedImageTag = containerSandbox.ensureImage(systemPackages, pipReqs, skillDir, containerImage,
                        null, stopped);
                return containerSandbox.execute(
                        healedImageTag, "python3", runnerScript, skillDir,
                        inputJson, timeoutSec, null, extraVolumes);
            } else {
                // Direct process execution
                // The heal may have just created the environment, so it is read again rather
                // than taken from the first run; the handed files are the first run's own, and
                // the credentials come with the input, as on the first run.
                var healedResolution = pythonEnv.resolveExecution(skillDir, name, stopped, () -> {});
                Map<String, String> healedEnv = new HashMap<>(healedResolution.extraEnv());
                healedEnv.putAll(envVars.entrySet().stream()
                        .filter(e -> !healedEnv.containsKey(e.getKey()))
                        .collect(java.util.stream.Collectors.toMap(
                                Map.Entry::getKey, Map.Entry::getValue)));
                // The environment read again sets its own PATH: the packages are found after it again.
                if (packages != null) {
                    for (String k : List.of("PATH", "LD_LIBRARY_PATH")) {
                        if (!healedResolution.extraEnv().containsKey(k)) healedEnv.remove(k);
                    }
                    packages.applyTo(healedEnv);
                }
                return sandbox.execute(
                        healedResolution.python(), runnerScript, skillDir,
                        inputJson, healedEnv, timeoutSec, null, readOnlyFor(healedResolution, handed),
                        tmpOwner);
            }
        } catch (Exception e) {
            log.error("Self-heal retry failed for skill '{}': {}", name,
                    firstLineOf(String.valueOf(e.getMessage())));
            return null;
        }
    }

    /**
     * An exception's message for the log: its first line and its size. A failed image build's
     * message carries every base image's whole build log -- the skill's result is where that
     * goes, not the log the ops API serves.
     */
    private static String firstLineOf(String message) {
        String first = message.lines().findFirst().orElse("");
        return first + " (" + message.length() + " chars)";
    }

    /**
     * What a run of the skill returned: a stall with what it wrote before going quiet, a crash
     * with its stderr and whatever reached stdout, or the harness's result ({@link #parseOutput}).
     */
    private ToolResult interpret(SandboxResult result, String runId) {
        if (result.timedOut()) {
            // What it wrote before it went quiet is the only evidence of where it hung.
            String stalled = "Skill '" + name + "' stalled (no output for " + timeoutSec + "s).";
            return ToolResult.failure(stalled + printed("stdout", result.stdout())
                    + printed("stderr", result.stderr()), withoutPrinted(stalled + printed("stderr", result.stderr()), result.stdout()));
        }
        if (result.isSuccess()) return parseOutput(result.stdout(), result.stderr(), runId);
        // A crash can leave stdout as well as stderr: a subprocess writes to it directly.
        String crashed = result.stderr().isBlank() ? "Exit code: " + result.exitCode() : result.stderr();
        return ToolResult.failure(crashed + printed("stdout", result.stdout()), withoutPrinted(crashed, result.stdout()));
    }

    /**
     * What a skill whose result asks for input fails with. No answer can reach a skill while it
     * runs -- its input is read to the end before skill.py is loaded -- so the model is told what
     * to do instead.
     */
    static final String NEED_INPUT_UNSUPPORTED = "need_input is not supported: a skill cannot ask the user "
            + "while it runs; fail and say what you need";

    /** Whether a skill's result is a need_input request: its "type" says so. */
    private static boolean asksForInput(ObjectNode result) {
        JsonNode type = result.get("type");
        return type != null && type.isTextual() && "need_input".equals(type.asText());
    }

    /**
     * Parse the Python script's stdout into a ToolResult.
     * <p>
     * The runner harness prints the skill's result last, as one line of JSON:
     * {@code {"success": true, "output": "text"}} and whatever else the skill returned. All of
     * that is the output: {@code output} alone when nothing else it returned holds anything -- as
     * text, or as JSON when it is not a string -- and otherwise the whole object as printed, so
     * that no key is dropped and an {@code "ok": false} stays where Artifact.succeeded reads it.
     * When the skill returned nothing, what was printed before that line is the output.
     * Nothing else the process wrote is dropped either ({@link #withStream}): stdout printed before
     * that line -- by the skill's own print() or by a subprocess -- and stderr, whether the skill succeeded or not: a failure is exactly when its
     * warnings are the evidence. Output whose last line is not a JSON object is shown whole, as a
     * failure. A result whose "type" is need_input fails, saying it is not supported
     * ({@link #NEED_INPUT_UNSUPPORTED}).
     *
     * @param stdout the script's standard output
     * @param stderr the script's standard error
     */
    private ToolResult parseOutput(String stdout, String stderr, String runId) {
        if (stdout == null || stdout.isBlank()) {
            String detail = (stderr != null && !stderr.isBlank())
                    ? "stderr: " + stderr.strip()
                    : "The skill's run() function may not be returning/printing output.";
            return said("Tool produced no output. " + detail
                    + " Use skill_create with the SAME name '" + name + "' to fix it.");
        }

        // Read from the end: the harness's line is the last one. An earlier line can be a JSON
        // object too -- a progress report, what curl printed -- and reading from the top took it
        // for the result and lost the result.
        String text = stdout.strip();
        int cut = text.lastIndexOf('\n');
        String line = text.substring(cut + 1).strip();
        String before = cut < 0 ? "" : text.substring(0, cut).strip();
        ObjectNode parsed = ToolResult.jsonObject(line);
        if (parsed == null) {
            // Non-JSON output from the runner harness means something went wrong.
            // Treat as failure so the agent gets a signal to investigate.
            String detail = (stderr != null && !stderr.isBlank())
                    ? " stderr: " + stderr.strip() : "";
            String fix = " Use skill_create with the SAME name '" + name + "' to fix.";
            return ToolResult.failure(
                    "Tool produced non-JSON output (possible runner error): "
                    + text + detail + fix,
                    "Tool produced non-JSON output (possible runner error), not shown: it is what the skill printed."
                            + detail + fix);
        }

        if (asksForInput(parsed)) return said(NEED_INPUT_UNSUPPORTED);
        // The runner's own word to the self-heal (toHeal), no part of what the skill returned.
        parsed.remove(MISSING_MODULE);
        // Whether the runner made this result of an exception the skill raised: its own key, this run's id.
        boolean raised = runId != null && parsed.has(RAISED) && runId.equals(parsed.get(RAISED).asText(""));
        parsed.remove(RAISED);
        parsed.remove(RUN);
        boolean success = BooleanNode.TRUE.equals(parsed.get("success"));
        JsonNode returned = parsed.get("output");
        // Everything the skill returned. Taking "output" alone dropped every other key -- a
        // message id, a data list, and an "ok": false beside it, so a failed send read as a
        // success. A key that holds nothing (a data dict left empty) does not make the object
        // the output.
        boolean onlyOutput = parsed.properties().stream()
                .allMatch(e -> "output".equals(e.getKey()) || "success".equals(e.getKey())
                        || holdsNothing(e.getValue()));
        // A skill that returned nothing and printed: what it printed is its output, as printed.
        // That is how a skill that print()s its result instead of returning it answers -- JSON
        // printed so stays JSON for the descriptor, the field references and an "ok": false.
        boolean printedIt = onlyOutput && (returned == null || holdsNothing(returned))
                && !before.isEmpty();
        String output = printedIt ? before
                : !onlyOutput || returned == null ? line
                : returned.isTextual() ? returned.asText() : returned.toString();

        // Safety net: if output starts with ERROR: but success was True (LLM code bug), flip to failure
        String said = printedIt ? before
                : returned != null && returned.isTextual() ? returned.asText() : null;
        if (success && said != null && said.startsWith("ERROR:")) {
            log.warn("Skill output starts with 'ERROR:' but success=true — treating as failure");
            success = false;
        }

        if (!printedIt) output = withStream(output, "skill stdout", before);

        // Treat empty output content as failure even if success=true
        if (success && (output.isBlank() || "null".equals(output))) {
            String hint = (stderr != null && !stderr.isBlank())
                    ? " stderr: " + stderr.strip()
                    : "";
            return said(
                    "Tool returned success but with empty output — this usually means the skill " +
                    "code has a bug (e.g. missing return, wrong variable, unhandled error)." + hint
                    + " Use skill_create with the SAME name '" + name + "' to fix it.");
        }

        output = withStream(output, "stderr", stderr);
        if (success) return ToolResult.success(output);
        // Its error as it said it -- never what it printed (before), never the data beside it: the
        // first line of the "error" it returned, or of its own "ERROR: ...", or the runner's
        // account of an exception it raised ("Skill error: ..." and the traceback); and its stderr.
        JsonNode error = parsed.get("error");
        String saidBack = returned != null && returned.isTextual() ? returned.asText() : null;
        String own = raised && saidBack != null ? saidBack
                : error != null && error.isTextual() ? firstLine(error.asText())
                : saidBack != null && saidBack.startsWith("ERROR:") ? firstLine(saidBack) : null;
        String streamed = stderr == null || stderr.isBlank() ? "" : printed("stderr", stderr);
        // What it printed before its result line -- never the runner's own line.
        return ToolResult.failure(output, withoutPrinted((own == null ? "" : own.strip()) + streamed, before));
    }

    private static String firstLine(String text) {
        String t = text.strip();
        int nl = t.indexOf('\n');
        return nl < 0 ? t : t.substring(0, nl).strip();
    }

    /**
     * {@code error} with every line of what the process printed to stdout -- eight characters or
     * more, the longest first -- taken out where it stands in it: the data a skill reads is what it
     * prints, and a message built from it repeats it. An error the skill also printed -- print(e),
     * then return it -- is taken out with it: it cannot be told from data that is also the error.
     */
    static String withoutPrinted(String error, String stdout) {
        if (error == null || error.isBlank() || stdout == null || stdout.isBlank()) return error;
        var lines = new ArrayList<String>();
        for (String line : stdout.split("\\R")) {
            String l = line.strip();
            if (l.length() >= 8) lines.add(l);
        }
        // The longest first: a shorter line inside a longer one would leave the rest of it.
        lines.sort((a, b) -> b.length() - a.length());
        String out = error;
        for (String l : lines) out = out.replace(l, "[printed output]");
        return out;
    }

    /** A failure in the service's own words: all of it is the error, and none of it the skill's data. */
    private static ToolResult said(String message) {
        return ToolResult.failure(message, message);
    }

    /** A value with nothing in it: null, "", {} or []. */
    private static boolean holdsNothing(JsonNode value) {
        return value.isNull() || value.isTextual() && value.asText().isEmpty()
                || value.isContainerNode() && value.isEmpty();
    }

    /**
     * {@code output} with {@code text} beside it under {@code label} -- what the process also wrote
     * to a stream, or why packages it needed are not installed; as it was when the text is blank.
     * An output that is a JSON object ({@link ToolResult#jsonObject}) gets it as one more key,
     * before the closing brace of the skill's own text and never one the skill returned itself:
     * text after the object hid an {@code "ok": false} from Artifact.succeeded and every field
     * from the descriptor. Any other output gets it as a labelled block after the text.
     */
    private static String withStream(String output, String label, String text) {
        if (text == null || text.isBlank()) return output;
        ObjectNode object = ToolResult.jsonObject(output);
        if (object == null) return output + printed(label, text);
        String key = label;
        while (object.has(key)) key = "_" + key;
        String t = output.strip();
        int close = t.lastIndexOf('}');
        return t.substring(0, close) + (object.isEmpty() ? "" : ", ") + TextNode.valueOf(key) + ": "
                + TextNode.valueOf(text.strip()) + "}";
    }

    /**
     * A failed result with {@code text} beside it, as {@link #withStream} puts it; a success, or a
     * blank text, as it is.
     */
    private static ToolResult failedWith(ToolResult result, String label, String text) {
        return result.success() || text == null || text.isBlank() ? result
                : ToolResult.failure(withStream(result.output(), label, text),
                        (result.error() == null ? "" : result.error()) + printed(label, text));
    }

    /** {@code s} with every character past ASCII written as JSON writes it, \\uXXXX in lower case. */
    private static String jsonEscaped(String s) {
        var sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c < 128) sb.append(c);
            else sb.append(String.format("\\u%04x", (int) c));
        }
        return sb.toString();
    }

    /**
     * {@code result} with every name of the files it was handed taken out of its error, each
     * where it stands -- its path, its name on disk, the name it was uploaded under -- for
     * "[an attached file]": a traceback names the file it failed on, and a statement's name
     * carries its account number. The output is not touched: it is private wherever a file is.
     */
    static ToolResult withoutFileNames(ToolResult result, List<Map<String, String>> attached) {
        if (result.error() == null || attached.isEmpty()) return result;
        String error = result.error();
        var forms = new ArrayList<String>();
        for (var f : attached) {
            String path = f.get("path");
            if (path != null && !path.isBlank()) {
                forms.add(path);
                Path file = Path.of(path).getFileName();
                if (file != null) forms.add(file.toString());
            }
            if (f.get("container_path") != null) forms.add(f.get("container_path"));
            String uploaded = f.get("name");
            if (uploaded != null && uploaded.strip().length() >= 3) {
                forms.add(uploaded.strip());
                // Its stem: code reads Path(p).stem as often as the name -- when it is no everyday
                // word ("image" of image.png would take "cannot identify image file" apart).
                int dot = uploaded.strip().lastIndexOf('.');
                String stem = dot >= 3 ? uploaded.strip().substring(0, dot) : "";
                if (stem.length() >= 6 && stem.chars().anyMatch(c -> Character.isDigit(c) || c == ' ' || c == '_' || c == '-' || c > 127)) {
                    forms.add(stem);
                }
            }
        }
        var names = new ArrayList<String>();
        for (String n : forms) {
            names.add(n);
            // As JSON writes it (\u00fd), and as a file:// URL does (%20, %C3%BD).
            names.add(jsonEscaped(n));
            names.add(java.net.URLEncoder.encode(n, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20")
                    .replace("%2F", "/"));
        }
        names.sort((a, b) -> b.length() - a.length());   // the longest first: a path holds its name
        for (String n : new java.util.LinkedHashSet<>(names)) {
            if (n.length() >= 3) error = error.replace(n, "[an attached file]");
        }
        return new ToolResult(result.success(), result.output(), error);
    }

    /** {@code text} labelled as what the process wrote to {@code stream}; nothing when blank. */
    private static String printed(String stream, String text) {
        return text == null || text.isBlank() ? "" : "\n[" + stream + ": " + text.strip() + "]";
    }

    /**
     * What the self-heal does with a failed run: the module to install, by its top-level name, or
     * none -- with, when it says something the run's caller can act on, why it was not installed.
     */
    record Heal(String module, String notHealed) {
        static final Heal NONE = new Heal(null, null);
    }

    /**
     * Which module a failed run is healed with.
     * <p>
     * Read from the runner's result alone ({@link #MISSING_MODULE}, from an uncaught
     * {@code ModuleNotFoundError}), and only for a module skill.py itself imports. The heal read
     * any "No module named '...'" in what the run wrote, and a run writes what it was given: a
     * web page, a mail, a file that said so had pip install the package it named into the
     * skill's environment and the skill run again on it, with the same credentials -- a package
     * that can run code at every start of the skill's Python, whoever runs it next. Now the
     * name is one the skill's own code names, as its requirements would: what the owner reads
     * before granting it. A module only another package imports, or only importlib names, is not
     * healed: the run fails with its error, and a skill_create with requirements fixes it.
     * <p>
     * Nor is a module of the skill's own directory installed: nothing there is imported but
     * skill.py ({@link #SKILL_LOADER}), and pip would install whatever an index holds under that
     * name instead.
     */
    Heal toHeal(String stdout, String runId) {
        String reported = reportedMissingModule(stdout, runId);
        if (reported == null) return Heal.NONE;
        int dot = reported.indexOf('.');
        String module = (dot > 0 ? reported.substring(0, dot) : reported).strip();
        if (!PythonEnvironmentService.isPlainName(module)) {
            log.warn("Skill '{}': the module reported missing is no plain name; not healed", name);
            return Heal.NONE;
        }
        if (Files.exists(skillDir.resolve(module + ".py"), java.nio.file.LinkOption.NOFOLLOW_LINKS)
                || Files.isDirectory(skillDir.resolve(module), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            log.warn("Skill '{}': module '{}' is in the skill's own directory, where nothing is imported from; not healed",
                    name, module);
            return new Heal(null, "'" + module + "' is a module in the skill's own directory, and nothing there is "
                    + "imported but skill.py. Put what it holds into skill.py (skill_create with the SAME name).");
        }
        String code;
        try {
            code = SkillJail.readAuthored(skillDir.resolve("skill.py"));
        } catch (IOException e) {
            log.warn("Skill '{}': skill.py could not be read to check the missing module against it: {}", name,
                    e.getMessage());
            return Heal.NONE;
        }
        if (!importedBy(code).contains(module)) {
            log.warn("Skill '{}': module '{}' was missing, and skill.py does not import it; not healed", name, module);
            return new Heal(null, "'" + module + "' is not installed automatically: skill.py does not import it "
                    + "itself. If a package it uses needs it, add it to the skill's requirements (skill_create with "
                    + "the SAME name).");
        }
        return new Heal(module, null);
    }

    /**
     * Python source with every comment and every string literal blanked -- each character a
     * space, line breaks kept -- so what is left is code. Strings of any quote, single or
     * triple, with any prefix: a backslash escapes the next character in every one of them, as
     * far as where a string ends goes, and a backslash before a line break continues the line,
     * CRLF included. An f- or t-string's replacement fields are read as the code they are, so a
     * quote inside one ({@code f"{'"'}"}, Python 3.12) does not end the string.
     */
    static String codeOnly(String source) {
        var out = new StringBuilder(source.length());
        int n = source.length();
        int i = 0;
        while (i < n) {
            char c = source.charAt(i);
            int end;
            if (c == '#') {
                end = i;
                while (end < n && source.charAt(end) != '\n' && source.charAt(end) != '\r') end++;
            } else if (c == '\'' || c == '"') {
                end = stringEnd(source, i, hasFields(source, i));
            } else {
                out.append(c);
                i++;
                continue;
            }
            for (int k = i; k < end; k++) {
                char d = source.charAt(k);
                out.append(d == '\n' || d == '\r' ? d : ' ');
            }
            i = end;
        }
        return out.toString();
    }

    /** Whether the string whose opening quote is at {@code quote} is an f- or t-string, by the prefix right before it. */
    private static boolean hasFields(String s, int quote) {
        int p = quote;
        while (p > 0 && quote - p < 2 && Character.isLetter(s.charAt(p - 1))) p--;
        if (p > 0 && (Character.isLetterOrDigit(s.charAt(p - 1)) || s.charAt(p - 1) == '_')) return false;
        String prefix = s.substring(p, quote).toLowerCase(java.util.Locale.ROOT);
        return prefix.chars().allMatch(ch -> "rbuft".indexOf(ch) >= 0) && (prefix.contains("f") || prefix.contains("t"));
    }

    /** Just past the closing quote of the string whose opening quote is at {@code start}; a one-line string not closed ends at its line's end. */
    private static int stringEnd(String s, int start, boolean fields) {
        int n = s.length();
        char q = s.charAt(start);
        boolean triple = start + 2 < n && s.charAt(start + 1) == q && s.charAt(start + 2) == q;
        int i = start + (triple ? 3 : 1);
        while (i < n) {
            char d = s.charAt(i);
            if (d == '\\') {
                i += i + 2 < n && s.charAt(i + 1) == '\r' && s.charAt(i + 2) == '\n' ? 3 : 2;
                continue;
            }
            if (fields && d == '{') {
                if (i + 1 < n && s.charAt(i + 1) == '{') {
                    i += 2;
                } else {
                    i = fieldEnd(s, i + 1);
                }
                continue;
            }
            if (d == q && (!triple || (i + 2 < n && s.charAt(i + 1) == q && s.charAt(i + 2) == q))) {
                return i + (triple ? 3 : 1);
            }
            if (!triple && (d == '\n' || d == '\r')) return i;
            i++;
        }
        return n;
    }

    /**
     * Just past the closing brace of an f-string's replacement field whose expression starts at
     * {@code from}. A colon outside every bracket begins its format spec, whose text is literal --
     * a quote there is a fill character, {@code f"{name:'^30}"} -- with fields nested in it.
     */
    private static int fieldEnd(String s, int from) {
        int n = s.length();
        int depth = 0;
        int i = from;
        while (i < n) {
            char c = s.charAt(i);
            if (c == '\'' || c == '"') {
                i = stringEnd(s, i, hasFields(s, i));
                continue;
            }
            if (depth == 0 && c == ':' && !(i + 1 < n && s.charAt(i + 1) == '=')) {
                for (i++; i < n; i++) {
                    char d = s.charAt(i);
                    if (d == '}') return i + 1;
                    if (d == '{') i = fieldEnd(s, i + 1) - 1;
                }
                return n;
            }
            if (c == '{' || c == '[' || c == '(') {
                depth++;
            } else if (c == '}' || c == ']' || c == ')') {
                if (depth == 0) return i + 1;
                depth--;
            }
            i++;
        }
        return n;
    }


    /**
     * The {@link #MISSING_MODULE} of the runner's result line -- the last line of stdout -- or
     * null: also when the line does not carry this run's id ({@link #RUN}), and so is not the
     * runner's.
     */
    static String reportedMissingModule(String stdout, String runId) {
        if (stdout == null || stdout.isBlank() || runId == null) return null;
        String text = stdout.strip();
        ObjectNode result = ToolResult.jsonObject(text.substring(text.lastIndexOf('\n') + 1).strip());
        if (result == null) return null;
        JsonNode run = result.get(RUN);
        if (run == null || !run.isTextual() || !runId.equals(run.asText())) return null;
        JsonNode module = result.get(MISSING_MODULE);
        return module != null && module.isTextual() && !module.asText().isBlank() ? module.asText() : null;
    }

    /**
     * The top-level names of the modules a skill's source imports in its import statements:
     * {@code import a.b as c, d} gives a and d, {@code from e.f import g} gives e. A relative
     * import gives nothing, and neither does {@code __import__} or {@code importlib}: what is
     * healed is what the code names as an import does. Read from the code alone: a comment
     * ("# TODO: import pandas") or a string ("Step 1: import contacts") names nothing.
     */
    static java.util.Set<String> importedBy(String source) {
        var out = new java.util.LinkedHashSet<String>();
        if (source == null) return out;
        for (String line : codeOnly(source).split("\\R")) {
            Matcher from = FROM_IMPORT.matcher(line);
            while (from.find()) out.add(from.group(1));
            Matcher plain = IMPORT.matcher(line);
            while (plain.find()) {
                for (String part : plain.group(1).split(",")) {
                    String[] words = part.strip().split("\\s+");
                    String module = words[0];
                    int dot = module.indexOf('.');
                    if (dot > 0) module = module.substring(0, dot);
                    if (!module.isEmpty()) out.add(module);
                }
            }
        }
        return out;
    }
}
