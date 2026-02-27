package com.ownclaw.agent.tools;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownclaw.sandbox.SandboxManager;
import com.ownclaw.sandbox.SandboxResult;
import com.ownclaw.skills.PythonEnvironmentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    public DynamicSkill(String name, String description, Map<String, ToolParam> parameters,
                        Path skillDir, boolean requiresNetwork, boolean hasSideEffects,
                        int timeoutSec, SandboxManager sandbox, PythonEnvironmentService pythonEnv) {
        this.name = name;
        this.description = description;
        this.parameters = parameters;
        this.skillDir = skillDir;
        this.requiresNetwork = requiresNetwork;
        this.hasSideEffects = hasSideEffects;
        this.timeoutSec = timeoutSec;
        this.sandbox = sandbox;
        this.pythonEnv = pythonEnv;
    }

    @Override public String name() { return name; }
    @Override public String description() { return description; }
    @Override public Map<String, ToolParam> inputSchema() { return parameters; }
    @Override public boolean requiresNetwork() { return requiresNetwork; }
    @Override public boolean hasSideEffects() { return hasSideEffects; }
    @Override public int estimatedMaxDurationSeconds() { return timeoutSec; }

    /** The directory containing this skill's files. */
    public Path skillDir() { return skillDir; }

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
     */
    private static final String RUNNER_HARNESS = String.join("\n",
        "import sys, json, os, io, importlib.util, traceback",
        "try:",
        "    params = json.loads(sys.stdin.read()) if not sys.stdin.isatty() else {}",
        "    skill_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'skill.py')",
        "    spec = importlib.util.spec_from_file_location('skill', skill_path)",
        "    mod = importlib.util.module_from_spec(spec)",
        "    _real_stdout = sys.stdout",
        "    _capture = io.StringIO()",
        "    sys.stdout = _capture",
        "    spec.loader.exec_module(mod)",
        "    if not hasattr(mod, 'run'):",
        "        sys.stdout = _real_stdout",
        "        print(json.dumps({'success': False, 'output': 'skill.py does not define a run(params) function'}))",
        "        sys.exit(0)",
        "    result = mod.run(params)",
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

        Path runnerScript = null;
        try {
            // Resolve Python (creates venv + installs requirements if needed)
            var resolution = pythonEnv.resolveExecution(skillDir, name);

            // Write the runner harness with a unique name to avoid conflicts
            // when the same skill is executed concurrently by different users
            String runnerId = Long.toHexString(Thread.currentThread().getId())
                    + "_" + Long.toHexString(System.nanoTime());
            runnerScript = skillDir.resolve("_runner_" + runnerId + ".py");
            Files.writeString(runnerScript, RUNNER_HARNESS, java.nio.charset.StandardCharsets.UTF_8);

            // Serialize input parameters as JSON for stdin
            String inputJson = mapper.writeValueAsString(params != null ? params : Map.of());

            Map<String, String> envVars = new HashMap<>(resolution.extraEnv());
            // Force UTF-8 for Python's stdin/stdout/stderr — prevents mojibake when
            // skills produce non-ASCII output (Czech, CJK, accented chars, etc.)
            envVars.put("PYTHONIOENCODING", "utf-8");
            envVars.put("PYTHONUTF8", "1");

            // Run: python _runner.py skill.py   (runner reads stdin, imports skill, calls run())
            SandboxResult result = sandbox.execute(
                    resolution.python(), runnerScript, skillDir,
                    inputJson, envVars, timeoutSec
            );

            if (result.timedOut()) {
                return ToolResult.failure("Skill '" + name + "' timed out after " + timeoutSec + "s.");
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
                            // Re-resolve and retry
                            var healedResolution = pythonEnv.resolveExecution(skillDir, name);
                            Map<String, String> healedEnv = new HashMap<>(healedResolution.extraEnv());
                            SandboxResult retry = sandbox.execute(
                                    healedResolution.python(), runnerScript, skillDir,
                                    inputJson, healedEnv, timeoutSec);
                            if (!retry.timedOut() && retry.isSuccess()) {
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
                        var healedResolution = pythonEnv.resolveExecution(skillDir, name);
                        Map<String, String> healedEnv = new HashMap<>(healedResolution.extraEnv());
                        SandboxResult retry = sandbox.execute(
                                healedResolution.python(), runnerScript, skillDir,
                                inputJson, healedEnv, timeoutSec);
                        if (!retry.timedOut() && retry.isSuccess()) {
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
            // Clean up the temp runner script
            if (runnerScript != null) {
                try { Files.deleteIfExists(runnerScript); } catch (IOException ignored) {}
            }
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
                    + " Use skill_manage(action='read') to inspect the code and skill_create to fix it.");
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
                        + " Use skill_manage(action='read') to inspect and skill_create to fix it.");
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
                    + " Use skill_manage(action='read') to inspect and skill_create to fix.");
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

    /** Extract the missing module name from a Python error message, or null. */
    private static String extractMissingModule(String outputText) {
        if (outputText == null || outputText.isBlank()) return null;
        Matcher m = MODULE_NOT_FOUND.matcher(outputText);
        if (!m.find()) return null;
        String mod = m.group(1) != null ? m.group(1) : m.group(2);
        if (mod == null || mod.isBlank()) return null;
        // Top-level package only (e.g. "bs4.element" → "bs4")
        int dot = mod.indexOf('.');
        if (dot > 0) mod = mod.substring(0, dot);
        return mod.strip();
    }
}
