package com.ownclaw.agent;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.ownclaw.agent.tools.DynamicSkill;
import com.ownclaw.agent.tools.DynamicSkillRegistry;
import com.ownclaw.agent.tools.ToolRegistry;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.sandbox.SandboxManager;
import com.ownclaw.sandbox.SandboxResult;
import com.ownclaw.sandbox.SkillJail;
import com.ownclaw.skills.PythonEnvironmentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Core service for creating, editing, reading, deleting, and listing skills.
 *
 * <p>This is the single gateway through which the agent modifies its own
 * tool inventory. It consolidates logic that was previously spread across
 * SkillCreatorTool and SkillCurateTool. Because all tools are now dynamic
 * Python skills, every tool in the registry can be managed through this service.
 *
 * <p>Skill management is exposed to the agent as special actions (like
 * {@code respond} and {@code ask_user}), not as tools — this guarantees the
 * agent can always create and improve tools regardless of what tools are
 * currently registered.
 */
@Service
public class SkillManager {

    private static final Logger log = LoggerFactory.getLogger(SkillManager.class);
    // Lenient mapper for parsing parameters JSON from LLM output.
    // Tolerates unquoted keys, single quotes, trailing commas — common LLM quirks.
    private static final ObjectMapper jsonMapper = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_UNQUOTED_FIELD_NAMES)
            .enable(JsonReadFeature.ALLOW_SINGLE_QUOTES)
            .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
            .build();
    private static final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    /**
     * Loads the skill exactly as the runner does -- the same loader, from source -- and confirms
     * {@code run} is callable.
     */
    private static final String LOAD_CHECK = DynamicSkill.SKILL_LOADER +
            "try:\n" +
            "    mod = _load_skill()\n" +
            "    if not callable(getattr(mod, 'run', None)):\n" +
            "        print('Module imported but has no callable run()', file=sys.stderr)\n" +
            "        sys.exit(1)\n" +
            "except Exception:\n" +
            "    traceback.print_exc()\n" +
            "    sys.exit(1)\n";

    private final DynamicSkillRegistry dynamicSkillRegistry;
    private final ToolRegistry toolRegistry;
    private final OwnClawConfig config;
    private final SandboxManager sandbox;
    private final PythonEnvironmentService pythonEnv;
    private final SkillCuratorService curatorService;

    public SkillManager(DynamicSkillRegistry dynamicSkillRegistry,
                        @Lazy ToolRegistry toolRegistry,
                        OwnClawConfig config,
                        SandboxManager sandbox,
                        PythonEnvironmentService pythonEnv,
                        SkillCuratorService curatorService) {
        this.dynamicSkillRegistry = dynamicSkillRegistry;
        this.toolRegistry = toolRegistry;
        this.config = config;
        this.sandbox = sandbox;
        this.pythonEnv = pythonEnv;
        this.curatorService = curatorService;
    }

    // ────────────────────── Create / Update ──────────────────────

    /**
     * Create (or update) a Python skill.
     *
     * @param params must contain: name, description, code, parameters (JSON string).
     *               Optional: requirements, requires_network, has_side_effects, timeout.
     * @return human-readable result message
     */
    public String createSkill(Map<String, Object> params) {
        String name = str(params, "name");
        String description = str(params, "description");
        String code = str(params, "code");
        // --- Validate ---

        if (name == null || !name.matches("[a-z][a-z0-9_]*")) {
            return "ERROR: Invalid skill name. Must start with a lowercase letter and " +
                    "contain only lowercase letters, digits, and underscores.";
        }

        // When every launch is being refused, stop before touching anything. The syntax and load
        // checks below could not run, their failure would read as the code's, and what follows a
        // failed check -- restoring the previous version, quarantining a new one -- would act on
        // an outage as though it were a verdict.
        var down = sandbox.unavailable();
        if (down.isPresent()) return "ERROR: " + down.get();

        // Reject variant names like web_fetch_v2, web_fetch_fixed, web_fetch_new, etc.
        // The LLM should overwrite the original skill with the SAME name instead.
        String baseName = detectBaseSkillName(name);
        if (baseName != null && toolRegistry.find(baseName).isPresent()) {
            return "ERROR: Skill '" + baseName + "' already exists. " +
                    "Do NOT create '" + name + "'. " +
                    "To fix or update a skill, use skill_create with the SAME name '" + baseName + "' — " +
                    "it will overwrite the existing skill in-place.";
        }

        if (description == null || description.isBlank()) return "ERROR: Description is required.";
        if (code == null || code.isBlank()) return "ERROR: Code is required.";

        // Adaptively normalize parameters: LLMs produce this field as:
        //   a) JSON string: "{\"url\": {\"type\": \"string\"}}"  (correct)
        //   b) Map object: {url={type=string}} (common from local LLM)
        //   c) List/Array: [{name: "url", type: "string"}] (rare but happens)
        //   d) null/missing (error)
        Map<String, Object> parametersDef;
        try {
            parametersDef = normalizeParameters(params.get("parameters"));
        } catch (Exception e) {
            return "ERROR: Invalid parameters JSON: " + e.getMessage();
        }
        if (parametersDef == null) {
            return "ERROR: parameters field is required.";
        }

        List<String> credentials;
        try {
            credentials = credentialNames(str(params, "credentials"));
        } catch (IllegalArgumentException refused) {
            return "ERROR: " + refused.getMessage();
        }

        String syntaxError = checkPythonSyntax(code);
        if (syntaxError != null) return "ERROR: Python syntax error:\n" + syntaxError;

        String requirements = str(params, "requirements");
        boolean requiresNetwork = Boolean.TRUE.equals(params.get("requires_network"));
        boolean hasSideEffects = Boolean.TRUE.equals(params.get("has_side_effects"));
        int timeout = toInt(params.get("timeout"), 30);
        String systemPackagesStr = str(params, "system_packages");
        String containerImage = str(params, "container_image");

        // --- Write to disk & register ---

        try {
            Path skillDir = Path.of(config.getSkills().getGeneratedPath()).resolve(name);
            Files.createDirectories(skillDir);

            // Snapshot EVERY authored file before touching any of them.
            //
            // Only skill.py used to be kept, so a failed update restored the code and left the
            // new SKILL.yaml and the new (or deleted) requirements.txt in place. The result was
            // a skill whose code was the working version while its declared parameters,
            // credentials and dependencies were the broken one's -- so the old code ran with a
            // parameter it did not accept, or without the credentials it needed, and the failure
            // looked like the restored version being broken rather than a half-applied update.
            Path codeFile = skillDir.resolve("skill.py");
            Path yamlFile = skillDir.resolve("SKILL.yaml");
            Path reqFile = skillDir.resolve("requirements.txt");
            // Read before anything is written: a file that is not a regular one -- a link a skill
            // left, pointing at .env -- throws here, and the skill is left untouched.
            String previousCode = SkillJail.readAuthored(codeFile);
            String previousYaml = SkillJail.readAuthored(yamlFile);
            String previousRequirements = SkillJail.readAuthored(reqFile);

            SkillJail.writeAuthored(yamlFile,
                    buildSkillYaml(name, description, parametersDef, requiresNetwork, hasSideEffects,
                            timeout, credentials, systemPackagesStr, containerImage));
            SkillJail.writeAuthored(codeFile, code);
            // A skill that declares nothing still gets requirements.txt -- empty, which resolves
            // to system Python exactly as a missing one did, so the old venv is not used. It is
            // bound read-only into the jail with the other two; a missing one would be a name the
            // skill could create itself, for the JVM to read and pip to install from.
            SkillJail.writeAuthored(reqFile, requirements != null && !requirements.isBlank()
                    ? requirements.strip() + "\n" : "");

            // Does it actually load? py_compile above proves the file parses, which is a much
            // weaker claim than it sounds: it never executes a single import, so a skill that
            // imports a package nobody installed passes and then fails on first real use, long
            // after the context that produced it is gone.
            String loadError = verifyLoads(skillDir, name);
            if (loadError != null) {
                if (previousCode != null) {
                    // Put the whole previous version back, not just its code.
                    SkillJail.writeAuthored(codeFile, previousCode);
                    if (previousYaml != null) {
                        SkillJail.writeAuthored(yamlFile, previousYaml);
                    }
                    SkillJail.writeAuthored(reqFile, previousRequirements != null ? previousRequirements : "");
                    log.warn("Skill '{}' failed to load; restored the previous version in full", name);
                    return "ERROR: the new code for '" + name + "' does not load, so the previous "
                            + "working version was kept. Fix and retry with the SAME name.\n" + loadError;
                }
                // A brand-new skill that will not import must not be left lying in generated/.
                // Nothing deleted it, and startup registers every directory it finds there -- so
                // the next deploy silently registered a skill that had already been rejected, and
                // the agent would pick it from the manifest and fail on first use, with the
                // context that produced it long gone. Quarantine keeps the code recoverable.
                log.warn("New skill '{}' failed to load: {}", name, loadError.replace('\n', ' '));
                dynamicSkillRegistry.quarantineUnregistered(skillDir,
                        "Rejected at creation: it does not import.\n" + loadError);
                return "ERROR: '" + name + "' was written but does not load, so it was not "
                        + "registered. Fix and retry with the SAME name.\n" + loadError;
            }

            DynamicSkill skill = dynamicSkillRegistry.loadSkill(skillDir);
            if (skill == null) return "ERROR: Failed to load — check SKILL.yaml format.";

            boolean isUpdate = toolRegistry.find(name).isPresent();
            dynamicSkillRegistry.unregister(name);
            dynamicSkillRegistry.register(skill);

            return "Skill '" + name + "' " + (isUpdate ? "updated" : "created") +
                    " and registered. It is now available as a tool.";
        } catch (IOException e) {
            log.error("Failed to create skill '{}': {}", name, e.getMessage());
            return "ERROR: Failed to write skill files: " + e.getMessage();
        }
    }

    // ────────────────────── Read ──────────────────────

    /**
     * Read just the Python source code of an existing skill.
     * Returns {@code null} if the skill does not exist or has no code file.
     * This is cheaper than {@link #readSkill} — no formatting, no YAML, no requirements.
     */
    public String readSkillCode(String name) {
        if (name == null || name.isBlank()) return null;
        var skillOpt = dynamicSkillRegistry.getDynamic(name);
        if (skillOpt.isEmpty()) return null;
        try {
            return SkillJail.readAuthored(skillOpt.get().skillDir().resolve("skill.py"));
        } catch (IOException e) {
            log.warn("Failed to read skill code for '{}': {}", name, e.getMessage());
            return null;
        }
    }

    /**
     * Read a skill's source code and metadata.
     */
    public String readSkill(String name) {
        if (name == null || name.isBlank()) return "ERROR: Skill name is required.";

        var skillOpt = dynamicSkillRegistry.getDynamic(name);
        if (skillOpt.isEmpty()) return "ERROR: '" + name + "' is not a skill or does not exist.";

        try {
            DynamicSkill skill = skillOpt.get();
            var sb = new StringBuilder();
            sb.append("## Skill: ").append(name).append("\n\n");

            // What is read here goes to the model, so a link at one of these names -- one a
            // skill left before it was isolated, pointing at .env -- is refused, not followed.
            String yaml = SkillJail.readAuthored(skill.skillDir().resolve("SKILL.yaml"));
            if (yaml != null) {
                sb.append("### SKILL.yaml\n```yaml\n");
                sb.append(yaml);
                sb.append("\n```\n\n");
            }

            String code = SkillJail.readAuthored(skill.skillDir().resolve("skill.py"));
            if (code != null) {
                sb.append("### skill.py\n```python\n");
                sb.append(code);
                sb.append("\n```\n\n");
            }

            String requirements = SkillJail.readAuthored(skill.skillDir().resolve("requirements.txt"));
            if (requirements != null) {
                sb.append("### requirements.txt\n```\n");
                sb.append(requirements);
                sb.append("\n```\n");
            }

            return sb.toString();
        } catch (IOException e) {
            return "ERROR: Failed to read skill files: " + e.getMessage();
        }
    }

    // ────────────────────── Delete ──────────────────────

    /**
     * Patch the credentials field of an existing skill's YAML and reload it.
     * Used to fix skills that were created before credential injection was standardized.
     *
     * @param name skill name
     * @param credentials comma-separated credential keys (e.g. "SMTP_HOST,SMTP_PORT,SMTP_USER,SMTP_PASS")
     * @return result message
     */
    public String patchCredentials(String name, String credentials) {
        if (name == null || name.isBlank()) return "ERROR: Skill name is required.";
        if (credentials == null || credentials.isBlank()) return "ERROR: Credentials list is required.";

        try {
            credentialNames(credentials);
        } catch (IllegalArgumentException refused) {
            return "ERROR: " + refused.getMessage();
        }
        var skillOpt = dynamicSkillRegistry.getDynamic(name);
        if (skillOpt.isEmpty()) return "ERROR: Skill '" + name + "' not found.";

        Path skillDir = skillOpt.get().skillDir();
        Path yamlFile = skillDir.resolve("SKILL.yaml");

        try {
            String yaml = SkillJail.readAuthored(yamlFile);
            if (yaml == null) return "ERROR: Skill '" + name + "' has no SKILL.yaml.";

            // Remove existing credentials line if present
            yaml = yaml.replaceAll("(?m)^credentials:.*\\n?", "");

            // Add credentials field before requires_network or at the end
            String credLine = "credentials: \"" + credentials.strip() + "\"\n";
            if (yaml.contains("requires_network:")) {
                yaml = yaml.replace("requires_network:", credLine + "requires_network:");
            } else {
                yaml = yaml.stripTrailing() + "\n" + credLine;
            }

            SkillJail.writeAuthored(yamlFile, yaml);

            // Reload the skill
            dynamicSkillRegistry.unregister(name);
            DynamicSkill reloaded = dynamicSkillRegistry.loadSkill(skillDir);
            if (reloaded == null) return "ERROR: Failed to reload skill after patching.";
            dynamicSkillRegistry.register(reloaded);

            return "Skill '" + name + "' credentials patched to [" + credentials + "] and reloaded.";
        } catch (IOException e) {
            log.error("Failed to patch credentials for skill '{}': {}", name, e.getMessage());
            return "ERROR: " + e.getMessage();
        }
    }

    /**
     * Delete a skill permanently: its directory and its environments. Refused while a run of it
     * is in flight.
     */
    public String deleteSkill(String name) {
        if (name == null || name.isBlank()) return "ERROR: Skill name is required.";
        if (!dynamicSkillRegistry.isDynamic(name)) {
            return "ERROR: '" + name + "' is not a modifiable skill.";
        }

        var skillOpt = dynamicSkillRegistry.getDynamic(name);
        if (skillOpt.isEmpty()) return "ERROR: Skill '" + name + "' not found.";
        DynamicSkill skill = skillOpt.get();

        // Never while a run of it is going on: the run can go on writing the directory and its
        // environment while they are removed, and leave part of them behind. (It cannot turn the
        // delete on anything outside them: that never follows a link below them -- see
        // SkillJail.deleteTree.) Checked again once it is unregistered, for a run that started
        // in between.
        if (skill.inFlight() > 0) return stillRunning(name);
        dynamicSkillRegistry.unregister(name);
        if (skill.inFlight() > 0) {
            dynamicSkillRegistry.register(skill);
            return stillRunning(name);
        }

        SkillJail.deleteTree(skill.skillDir());
        // The environment too. This deleted the code and kept the 7 GB of packages the code had
        // pulled in, for every skill ever deleted.
        pythonEnv.removeEnvironments(name);

        return "Skill '" + name + "' has been permanently deleted.";
    }

    private static String stillRunning(String name) {
        return "ERROR: Skill '" + name + "' is running right now; it was not deleted. Try again when it has finished.";
    }

    // ────────────────────── List / Stats ──────────────────────

    /**
     * List all tools with usage statistics.
     */
    public String listSkills() {
        return curatorService.listSkillsWithStats();
    }

    /**
     * LLM-powered library analysis.
     */
    public String analyzeSkills(com.ownclaw.llm.EgressContext egress) {
        return curatorService.analyzeLibrary(egress);
    }

    // ────────────────────── Helpers ──────────────────────

    /**
     * Import the skill in its own environment and confirm {@code run} is callable.
     * Returns an error message, or null when it loads.
     * <p>
     * The gate before this was {@code py_compile} plus an AST check for a function named
     * {@code run} — "it parses and has the right shape". That is a much weaker claim than it
     * sounds, because compiling never executes a single import. A skill importing {@code bs4}
     * when nothing declared it in requirements.txt passed, registered, and then failed on first
     * real use, by which time the reasoning that produced it was long gone and the failure had
     * to be diagnosed from scratch.
     * <p>
     * Importing runs everything at module level — the imports, the constants, the decorators —
     * which is where dead-on-arrival code actually dies. It resolves the skill's real venv
     * first, so this also verifies that the declared requirements genuinely cover the imports.
     * <p>
     * Deliberately only an import, not an invocation. Calling {@code run()} would need
     * parameters, and invented parameters produce failures that say nothing about the code —
     * a URL of "test" fails for reasons that are not the skill's fault, and a check that cries
     * wolf gets ignored or, worse, triggers repairs of code that was fine. This answers exactly
     * one question, mechanically: can this thing load at all? Whether it does the right thing
     * is a different question and needs real cases.
     */
    private String verifyLoads(Path skillDir, String name) {
        Path checkScript = null;
        try {
            var resolution = pythonEnv.resolveExecution(skillDir, name);
            // Under a fresh name each time: it used to be _load_check.py, and a link the skill
            // left at that name in its own directory turned this write into one over its target.
            checkScript = DynamicSkill.writeHelperScript(skillDir, "_load_check_", LOAD_CHECK);

            // The environment is read the way a run reads it, so the check imports what a run
            // would. A launch the jail refuses comes back as a failed result, never as an
            // exception, so it cannot reach the "registering unverified" catch below: a skill
            // whose check could not run isolated is not let in unverified.
            SandboxResult result = sandbox.execute(resolution.python(), checkScript, skillDir,
                    null, resolution.extraEnv(), 60, null, DynamicSkill.readOnlyFor(resolution, List.of()));
            if (result.isSuccess()) return null;
            if (result.timedOut()) {
                return "Importing the module timed out after 60s — module-level code should not "
                        + "do real work; put it inside run().";
            }
            String detail = result.stderr().isBlank() ? result.stdout() : result.stderr();
            return detail.isBlank() ? "Import failed with exit code " + result.exitCode() : detail;
        } catch (Exception e) {
            // A failure of the CHECK must not block a skill. Better to register something
            // unverified than to lose a capability because the sandbox hiccuped.
            log.warn("Load check for '{}' could not run ({}); registering unverified",
                    name, e.getMessage());
            return null;
        } finally {
            if (checkScript != null) {
                try { Files.deleteIfExists(checkScript); } catch (IOException ignored) {}
            }
        }
    }

    /**
     * Check Python code for syntax errors without writing to the skill directory.
     * @return error message if syntax error found, null if code is valid.
     */
    String checkPythonSyntax(String code) {
        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("ownclaw_syntax_");
            Path targetFile = tempDir.resolve("skill.py");
            Files.writeString(targetFile, code, StandardCharsets.UTF_8);

            Path checkScript = tempDir.resolve("_check.py");
            String targetPath = targetFile.toAbsolutePath().toString().replace("\\", "/");
            Files.writeString(checkScript,
                    "import py_compile, sys, ast\n" +
                    "try:\n" +
                    "    py_compile.compile('" + targetPath + "', doraise=True)\n" +
                    "except py_compile.PyCompileError as e:\n" +
                    "    print(str(e), file=sys.stderr)\n" +
                    "    sys.exit(1)\n" +
                    "with open('" + targetPath + "') as f:\n" +
                    "    tree = ast.parse(f.read())\n" +
                    "funcs = [n.name for n in ast.walk(tree) if isinstance(n, ast.FunctionDef)]\n" +
                    "if 'run' not in funcs:\n" +
                    "    print('Missing required function: def run(params)', file=sys.stderr)\n" +
                    "    sys.exit(1)\n",
                    StandardCharsets.UTF_8);

            String python = pythonEnv.getSystemPython();
            SandboxResult result = sandbox.execute(python, checkScript, tempDir, null, Map.of(), 10, null, List.of());

            if (!result.isSuccess()) {
                return result.stderr().isBlank() ? result.stdout() : result.stderr();
            }
            return null;
        } catch (Exception e) {
            return "Syntax check failed: " + e.getMessage();
        } finally {
            if (tempDir != null) SkillJail.deleteTree(tempDir);
        }
    }

    /**
     * The credential names in a comma-separated list, upper-cased -- the model may send the list
     * stringified, as "[IMAP_HOST, IMAP_PORT]".
     *
     * @throws IllegalArgumentException at the first that is not {@link SkillJail#settableName}
     */
    static List<String> credentialNames(String credentials) {
        if (credentials == null || credentials.isBlank()) return List.of();
        List<String> names = Arrays.stream(credentials.replaceAll("[\\[\\]]", "").split(","))
                .map(String::trim)
                .map(String::toUpperCase)
                .filter(s -> !s.isBlank())
                .toList();
        names.forEach(SkillJail::requireSettable);
        return names;
    }

    private String buildSkillYaml(String name, String description, Map<String, Object> parametersDef,
                                  boolean requiresNetwork, boolean hasSideEffects, int timeout,
                                  List<String> credentials, String systemPackages, String containerImage)
            throws IOException {
        Map<String, Object> yaml = new LinkedHashMap<>();
        yaml.put("name", name);
        yaml.put("description", description);
        yaml.put("version", 1);
        if (parametersDef != null && !parametersDef.isEmpty()) {
            yaml.put("parameters", parametersDef);
        }
        yaml.put("requires_network", requiresNetwork);
        yaml.put("has_side_effects", hasSideEffects);
        yaml.put("timeout", timeout);
        if (!credentials.isEmpty()) yaml.put("credentials", credentials);
        if (systemPackages != null && !systemPackages.isBlank()) {
            // Support comma-separated or space-separated
            String delimiter = systemPackages.contains(",") ? "," : "\\s+";
            List<String> pkgList = Arrays.stream(systemPackages.split(delimiter))
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .filter(s -> s.matches("[a-zA-Z0-9._+:-]+")) // sanitize
                    .toList();
            if (!pkgList.isEmpty()) {
                yaml.put("system_packages", pkgList);
            }
        }
        if (containerImage != null && !containerImage.isBlank()) {
            yaml.put("container_image", containerImage.trim());
        }
        return yamlMapper.writeValueAsString(yaml);
    }

    private String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v != null ? v.toString() : null;
    }

    /**
     * Normalize the parameters field from LLM output into a proper Map.
     * <p>Handles multiple formats that LLMs produce:
     * <ul>
     *   <li>String: parse as JSON (the intended format)</li>
     *   <li>Map: already deserialized by Jackson (common when local LLM produces nested object)</li>
     *   <li>List: convert [{name: "x", type: "string"}] → {"x": {type: "string"}}</li>
     *   <li>null: returns null</li>
     * </ul>
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> normalizeParameters(Object raw) throws Exception {
        if (raw == null) return null;

        // Already a Map (Jackson deserialized it from nested JSON object)
        if (raw instanceof Map<?, ?> map) {
            // Normalise the shorthand the model very often emits: {"url": "string"} instead of
            // {"url": {"type": "string"}}. Passed through unchanged, that reached the YAML writer
            // as a String where a parameter definition was expected, the entry was dropped, and
            // the skill registered with NO input schema. The agent was then told the skill takes
            // no arguments, called it with none, and the Python died on a missing key -- while
            // skill_create had reported success. Nothing in the loop could work out why, because
            // the manifest it reads and the code on disk disagreed.
            Map<String, Object> normalised = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String key = String.valueOf(e.getKey());
                Object def = e.getValue();
                if (def instanceof Map) {
                    normalised.put(key, def);
                } else if (def == null) {
                    normalised.put(key, new LinkedHashMap<>(Map.of("type", "string")));
                } else {
                    // "string" / "int" / a one-line description -- treat it as the type when it
                    // looks like one, otherwise as the description. Either way the parameter
                    // survives, which is the point.
                    String text = String.valueOf(def).trim();
                    var d = new LinkedHashMap<String, Object>();
                    if (text.matches("(?i)string|str|int|integer|number|float|bool|boolean|array|list|object")) {
                        d.put("type", text.toLowerCase(java.util.Locale.ROOT));
                    } else {
                        d.put("type", "string");
                        d.put("description", text);
                    }
                    normalised.put(key, d);
                }
            }
            return normalised;
        }

        // List/Array format: [{"name": "url", "type": "string", ...}, ...]
        // Convert to Map format: {"url": {"type": "string", ...}}
        if (raw instanceof List<?> list) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> entry) {
                    String paramName = entry.get("name") != null ? entry.get("name").toString() : null;
                    if (paramName != null) {
                        Map<String, Object> paramDef = new LinkedHashMap<>((Map<String, Object>) entry);
                        paramDef.remove("name");
                        result.put(paramName, paramDef);
                    }
                }
            }
            return result.isEmpty() ? null : result;
        }

        // String: parse as JSON with lenient mapper
        String jsonStr = raw.toString();
        if (jsonStr.isBlank()) return null;

        // Handle Java Map.toString() format: {type=string, description=...}
        // This happens when str() calls toString() on a Map object
        if (jsonStr.contains("=") && !jsonStr.contains(":") && !jsonStr.contains("\"")) {
            // Convert {key=value, ...} to {"key": "value", ...}
            jsonStr = jsonStr.replaceAll("(\\w+)=", "\"$1\":");
            // Wrap unquoted values
            jsonStr = jsonStr.replaceAll(":\\s*([^,{}\"\\[\\]]+)", ": \"$1\"");
        }

        return jsonMapper.readValue(jsonStr, new TypeReference<>() {});
    }

    /**
     * Detect if a skill name is a variant of an existing skill.
     * Returns the base name if a variant suffix is found (e.g. "web_fetch_v2" → "web_fetch"),
     * or null if the name looks original.
     */
    private String detectBaseSkillName(String name) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^(.+?)_(v\\d+|fixed|new|updated|alt|retry|patched|mod|revised|rewrite|reworked)$")
                .matcher(name);
        return m.matches() ? m.group(1) : null;
    }

    private int toInt(Object value, int defaultValue) {
        if (value == null) return defaultValue;
        if (value instanceof Number n) return n.intValue();
        try { return Integer.parseInt(value.toString()); } catch (Exception e) { return defaultValue; }
    }
}
