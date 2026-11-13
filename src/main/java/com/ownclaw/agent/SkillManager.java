package com.ownclaw.agent;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.ownclaw.agent.tools.DynamicSkill;
import com.ownclaw.agent.tools.DynamicSkillRegistry;
import com.ownclaw.agent.tools.ToolRegistry;
import com.ownclaw.agent.tools.ToolSchemas;
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
     * Loads the skill with the runner's own loader ({@link DynamicSkill#SKILL_LOADER}) -- from
     * source, {@code report_progress} in place -- and confirms {@code run} is callable. The
     * skill's credentials are absent here: a run is given them, the check is not, so a skill that
     * needs one while it loads, rather than in {@code run}, fails the check.
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
    /**
     * Who is the owner: a skill's code is global, so who may change it is decided by its author
     * and the owner ({@link #changeRefusal}). Null in a test that makes its own manager.
     */
    private final com.ownclaw.users.AuthService authService;

    public SkillManager(DynamicSkillRegistry dynamicSkillRegistry,
                        @Lazy ToolRegistry toolRegistry,
                        OwnClawConfig config,
                        SandboxManager sandbox,
                        PythonEnvironmentService pythonEnv,
                        SkillCuratorService curatorService,
                        @Lazy com.ownclaw.users.AuthService authService) {
        this.dynamicSkillRegistry = dynamicSkillRegistry;
        this.toolRegistry = toolRegistry;
        this.config = config;
        this.sandbox = sandbox;
        this.pythonEnv = pythonEnv;
        this.curatorService = curatorService;
        this.authService = authService;
    }

    // ────────────────────── Create / Update ──────────────────────

    /** A lowercase letter, then lowercase letters, digits and underscores. */
    private static final java.util.regex.Pattern SKILL_NAME_SHAPE =
            java.util.regex.Pattern.compile("[a-z][a-z0-9_]*");

    /** {@link #isSkillName}, in words: what a refusal tells whoever gave the name. */
    public static final String SKILL_NAME_RULE = "a skill's name starts with a lowercase letter, "
            + "has only lowercase letters, digits and underscores, and is at most 64 characters "
            + "long: it is the tool's name, and the model providers accept no longer one";

    /**
     * What a skill may be called: the one rule, for creating one, for recording which one ran,
     * and for naming one to read through the ops API.
     * <p>
     * The name is a directory, a Python-friendly identifier, and the tool's name on every
     * request -- where the model providers accept at most 64 characters
     * ({@link ToolSchemas#isApiSafeName}). A longer name used to be accepted here and then left
     * out of every tools array, so the skill existed and nothing could call it.
     */
    public static boolean isSkillName(String name) {
        return name != null && SKILL_NAME_SHAPE.matcher(name).matches()
                && ToolSchemas.isApiSafeName(name);
    }

    /**
     * Why no skill may be made under this name, as the ERROR line a skill_create step records, or
     * null when one may: the one rule for a skill's name ({@link #isSkillName}), and no variant of
     * a skill that exists -- web_fetch_v2, web_fetch_fixed -- which is updated under its own name
     * instead. {@link #createSkill} asks it, and so does the agent loop before a model writes the
     * skill's code: the name does not depend on the code, and asked only after it, a refused
     * name cost a whole code generation every time.
     */
    public String nameRefusal(String name) {
        if (!isSkillName(name)) {
            return "ERROR: Invalid skill name: " + SKILL_NAME_RULE + ".";
        }

        // When every launch is being refused, stop before touching anything. The syntax and load
        // checks below could not run, their failure would read as the code's, and what follows a
        // failed check -- restoring the previous version, quarantining a new one -- would act on
        // an outage as though it were a verdict.
        var down = sandbox == null ? java.util.Optional.<String>empty() : sandbox.unavailable();
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
        return null;
    }

    /**
     * Create (or update) a Python skill.
     *
     * @param params must contain: name, description, code, parameters (JSON string).
     *               Optional: requirements, requires_network, has_side_effects, timeout.
     * @return human-readable result message
     */
    public String createSkill(Map<String, Object> params) {
        return createSkill(params, null, () -> false, () -> {});
    }

    /**
     * {@link #createSkill(Map)} for a task of {@code requester}'s: refused when the skill exists
     * and is another account's ({@link #changeRefusal}); while the skill's runs under way in
     * other tasks end, it is said to wait ({@code waiting}), which the stall watchdog counts as
     * progress, and a Stop of it ({@code stopped}) ends the wait with nothing written.
     *
     * @param requester the account the task runs for; null for the owner's own pages and
     *                  maintenance, which are not an account's task
     */
    public String createSkill(Map<String, Object> params, String requester,
                              java.util.function.BooleanSupplier stopped, Runnable waiting) {
        return createSkill(params, null, requester, stopped, waiting);
    }

    /**
     * {@link #createSkill(Map, String, java.util.function.BooleanSupplier, Runnable)} of code a model
     * wrote with placeholders, and the user's values put back: {@code asWritten} is that code as the
     * model wrote it, kept beside the skill ({@link #readAsWritten}) -- null for code written
     * otherwise, which keeps none.
     */
    public String createSkill(Map<String, Object> params, String asWritten, String requester,
                              java.util.function.BooleanSupplier stopped, Runnable waiting) {
        String name = str(params, "name");
        if (name == null) return createSkillHeld(params, requester, asWritten);
        // Held to write while its files are written, checked and registered: another task's
        // skill_create of the same name waits, and so does every run of it.
        var changing = com.ownclaw.agent.tools.SkillLocks.of(name).writeLock();
        if (!com.ownclaw.agent.tools.SkillLocks.take(changing, stopped, waiting)) {
            return "ERROR: Stopped while a run of skill '" + name + "' was under way: nothing was written.";
        }
        try {
            return createSkillHeld(params, requester, asWritten);
        } finally {
            changing.unlock();
        }
    }

    /**
     * Why {@code requester} may not change or delete the skill whose files are in {@code skillDir},
     * or null when it may. A skill's code is global -- every account's tasks are offered it -- and
     * it runs with the vault values and the files of whoever runs it, so a skill the owner runs
     * must not be one another account replaced: overwritten, account B's code would read the
     * owner's credentials and attached files the next time he ran it. The account that first
     * wrote a skill is its author, kept in its SKILL.yaml; it and the owner may change it, nobody
     * else. A skill written before authors were kept has none, and is the owner's. A new name is
     * anyone's to take. Checked with the skill's write lock held, so the author read is the one
     * the write replaces.
     */
    private String changeRefusal(String name, Path skillDir, String requester, String change) {
        if (requester == null) return null;                         // the owner's pages, maintenance
        Path yamlFile = skillDir.resolve("SKILL.yaml");
        if (!Files.exists(yamlFile)) return null;                   // a new skill: anyone's to write
        if (authService != null && authService.isOwner(requester)) return null;
        if (requester.equals(authorOf(yamlFile))) return null;      // its author
        return "ERROR: the skill '" + name + "' belongs to another account, so it cannot be " + change
                + " -- only its author or the owner can. Create a skill of your own under another name.";
    }

    /**
     * The account that first wrote this skill, from its SKILL.yaml; null when none is kept. Read
     * as every authored file is ({@link SkillJail#readAuthored}): a link at the name is refused,
     * not followed to a file that names another author.
     */
    private static String authorOf(Path yamlFile) {
        try {
            String text = SkillJail.readAuthored(yamlFile);
            if (text == null) return null;
            Map<String, Object> yaml = yamlMapper.readValue(text, new TypeReference<>() {});
            Object author = yaml.get("author");
            return author == null || author.toString().isBlank() ? null : author.toString();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private String createSkillHeld(Map<String, Object> params, String requester, String asWritten) {
        String name = str(params, "name");
        String description = str(params, "description");
        String code = str(params, "code");
        // --- Validate ---

        String refused = nameRefusal(name);
        if (refused != null) return refused;

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
            credentials = DynamicSkillRegistry.credentialNames(params.get("credentials"));
        } catch (IllegalArgumentException badName) {
            return "ERROR: " + badName.getMessage();
        }

        String syntaxError = checkPythonSyntax(code);
        if (syntaxError != null) return "ERROR: Python syntax error:\n" + syntaxError;

        String requirements = str(params, "requirements");
        String notPlain = requirementsRefusal(requirements);
        if (notPlain != null) return "ERROR: the requirements for '" + name + "' were not taken: " + notPlain;
        boolean requiresNetwork = Boolean.TRUE.equals(params.get("requires_network"));
        boolean hasSideEffects = Boolean.TRUE.equals(params.get("has_side_effects"));
        int timeout = toInt(params.get("timeout"), 30);
        String systemPackagesStr = str(params, "system_packages");
        String containerImage = str(params, "container_image");

        // --- Write to disk & register ---

        try {
            Path skillDir = Path.of(config.getSkills().getGeneratedPath()).resolve(name);
            String notYours = changeRefusal(name, skillDir, requester, "changed");
            if (notYours != null) return notYours;
            // The author is kept across updates: the owner repairing another account's skill
            // does not take it over. A new skill is its requester's.
            String existingAuthor = authorOf(skillDir.resolve("SKILL.yaml"));
            String author = existingAuthor != null ? existingAuthor : requester;
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
                    buildSkillYaml(name, author, description, parametersDef, requiresNetwork, hasSideEffects,
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
                // Not its words: Python's traceback quotes the code, the user's values in it.
                log.warn("New skill '{}' failed to load ({} lines of Python's error, returned to the task)", name,
                        loadError.lines().count());
                dynamicSkillRegistry.quarantineUnregistered(skillDir,
                        "Rejected at creation: it does not import.\n" + loadError);
                return "ERROR: '" + name + "' was written but does not load, so it was not "
                        + "registered. Fix and retry with the SAME name.\n" + loadError;
            }

            DynamicSkill skill = dynamicSkillRegistry.loadSkill(skillDir);
            if (skill == null) return "ERROR: Failed to load — check SKILL.yaml format.";

            boolean isUpdate = toolRegistry.find(name).isPresent();
            // Registered over the old one, not after unregistering it: in between, another task's
            // look-up of the skill found no such tool.
            dynamicSkillRegistry.register(skill);
            keepAsWritten(name, asWritten, code, requester);

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

    /** One requirement as pip reads a plain one: a package name, its extras, and its version specifiers. */
    private static final java.util.regex.Pattern PLAIN_REQUIREMENT = java.util.regex.Pattern.compile(
            "[A-Za-z0-9](?:[A-Za-z0-9._-]*[A-Za-z0-9])?"
            + "(?:\\[\\s*[A-Za-z0-9._-]+(?:\\s*,\\s*[A-Za-z0-9._-]+)*\\s*\\])?"
            + "(?:\\s*(?:===|==|!=|<=|>=|~=|<|>)\\s*[A-Za-z0-9.*+!_-]+"
            + "(?:\\s*,\\s*(?:===|==|!=|<=|>=|~=|<|>)\\s*[A-Za-z0-9.*+!_-]+)*)?");

    /**
     * Why {@code requirements} -- what requirements.txt would hold, for {@code pip install -r} --
     * is not taken, or null when every line is a plain requirement or a comment. Whatever wrote it:
     * the code's reply, or the planner's skill_create, whose arguments come back with the user's
     * values put back as text -- and a value with a line break, or a line of the model's own, could
     * add a pip option (an index URL, -e, -r, --trusted-host), a direct reference (name @ URL), an
     * environment marker or a line continuation. Said by its line's number, never its text.
     */
    static String requirementsRefusal(String requirements) {
        if (requirements == null || requirements.isBlank()) return null;
        String[] lines = requirements.split("\r\n|\r|\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            // pip's comment: a # at the line's start or after a blank.
            int hash = -1;
            for (int j = 0; j < line.length() && hash < 0; j++) {
                if (line.charAt(j) == '#' && (j == 0 || Character.isWhitespace(line.charAt(j - 1)))) hash = j;
            }
            String requirement = (hash >= 0 ? line.substring(0, hash) : line).strip();
            if (requirement.isEmpty()) continue;
            if (!PLAIN_REQUIREMENT.matcher(requirement).matches()) {
                return "line " + (i + 1) + " is not a plain requirement -- a package name, its extras and its "
                        + "version; pip options, URLs, direct references, environment markers and continued lines "
                        + "are not taken -- so nothing was written.";
            }
        }
        return null;
    }

    /**
     * Where the code of skill {@code name} is kept as the model wrote it: beside the skills' own
     * directories, never in one -- a skill's directory is its working directory, writable by the
     * skill, and a copy there could be rewritten by the skill's own run, for the next fix of it to
     * send with whatever it put in. Under the skills' root, which the jail hides from every run.
     */
    private Path asWrittenFile(String name) {
        return Path.of(config.getSkills().getGeneratedPath()).toAbsolutePath().normalize().getParent()
                .resolve("_as_written").resolve(name + ".py");
    }

    /** The first line of a kept copy: the digest of the skill.py it was kept with, and whose placeholders it holds. */
    private static final String KEPT_WITH = "# skill.py sha256 ";

    /** The first line of the copy kept with {@code code}, {@code userId}'s placeholders in it. */
    private static String keptWith(String code, String userId) {
        return KEPT_WITH + sha256(code) + " for " + sha256(userId == null ? "" : userId);
    }

    /**
     * Keeps {@code asWritten} -- skill {@code name}'s code as the model wrote it, {@code userId}'s
     * placeholders in it -- with the digest of {@code code}, the skill.py it was made into; or, null,
     * forgets any copy kept: code written otherwise has none.
     */
    private void keepAsWritten(String name, String asWritten, String code, String userId) {
        try {
            Path file = asWrittenFile(name);
            if (asWritten == null) {
                Files.deleteIfExists(file);
                return;
            }
            Files.createDirectories(file.getParent());
            Files.writeString(file, keptWith(code, userId) + "\n" + asWritten, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            log.warn("The code of '{}' as written could not be kept: {}", name, e.getClass().getSimpleName());
        }
    }

    /**
     * Skill {@code name}'s code exactly as the model wrote it -- its placeholders where the user's
     * values are in skill.py -- when one was kept with the skill.py the skill has now, for
     * {@code userId}: the placeholders are one account's, and read in another's table they are that
     * account's values -- the owner fixing another account's skill put his own network's name into
     * it. Otherwise null: a skill made before copies were kept, changed since by hand or by a page,
     * or made by another account.
     */
    public String readAsWritten(String name, String userId) {
        String code = readSkillCode(name);
        if (code == null) return null;
        try {
            Path file = asWrittenFile(name);
            if (!Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return null;
            String kept = Files.readString(file, StandardCharsets.UTF_8);
            int newline = kept.indexOf('\n');
            if (newline < 0 || !kept.substring(0, newline).equals(keptWith(code, userId))) return null;
            return kept.substring(newline + 1);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static String sha256(String text) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
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
     * Delete a skill permanently: its directory and its environments. Waits for the runs of it
     * under way to end (the write lock), and no run starts while it goes.
     */
    public String deleteSkill(String name) {
        return deleteSkill(name, null, () -> false, () -> {});
    }

    /**
     * {@link #deleteSkill(String)} for a task of {@code requester}'s: refused for another account's
     * skill as a change is ({@link #changeRefusal}), and waiting as
     * {@link #createSkill(Map, String, java.util.function.BooleanSupplier, Runnable)} does.
     */
    public String deleteSkill(String name, String requester, java.util.function.BooleanSupplier stopped,
                              Runnable waiting) {
        if (name == null || name.isBlank()) return "ERROR: Skill name is required.";
        // Held to write: the runs of it under way end first, and none starts while it goes.
        var removing = com.ownclaw.agent.tools.SkillLocks.of(name).writeLock();
        if (!com.ownclaw.agent.tools.SkillLocks.take(removing, stopped, waiting)) {
            return "ERROR: Stopped while a run of skill '" + name + "' was under way: it was not deleted.";
        }
        try {
            return deleteSkillHeld(name, requester);
        } finally {
            removing.unlock();
        }
    }

    private String deleteSkillHeld(String name, String requester) {
        if (!dynamicSkillRegistry.isDynamic(name)) {
            return "ERROR: '" + name + "' is not a modifiable skill.";
        }

        var skillOpt = dynamicSkillRegistry.getDynamic(name);
        if (skillOpt.isEmpty()) return "ERROR: Skill '" + name + "' not found.";
        String notYours = changeRefusal(name, skillOpt.get().skillDir(), requester, "deleted");
        if (notYours != null) return notYours;
        DynamicSkill skill = skillOpt.get();

        // No run of it is going on: the write lock this holds waited for them, and a run takes
        // the read lock to start. (Nor could one turn the delete on anything outside the
        // directory: deleting never follows a link below it -- see SkillJail.deleteTree.)
        dynamicSkillRegistry.unregister(name);

        boolean dirGone = SkillJail.deleteTree(skill.skillDir());
        keepAsWritten(name, null, null, null);
        // The environment too. This deleted the code and kept the 7 GB of packages the code had
        // pulled in, for every skill ever deleted.
        boolean envsGone = pythonEnv.removeEnvironments(name);
        if (!dirGone || !envsGone) {
            // Say all that is left: removing only what is named, the owner would leave a stale
            // environment for a later skill of this name.
            var left = new ArrayList<String>();
            if (!dirGone) left.add(skill.skillDir().toString());
            if (!envsGone) left.add("its environment");
            return "ERROR: Skill '" + name + "' is unregistered, but " + String.join(" and ", left)
                    + " could not be fully removed (the log says why). Remove what is left on the host "
                    + "before creating a skill of this name again.";
        }
        return "Skill '" + name + "' has been permanently deleted.";
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
    public String analyzeSkills(com.ownclaw.llm.LlmProvider provider, com.ownclaw.llm.EgressContext egress,
                                com.ownclaw.llm.LlmProgress progress,
                                java.util.function.BiConsumer<com.ownclaw.llm.LlmProvider, com.ownclaw.llm.LlmResponse> billed) {
        return curatorService.analyzeLibrary(provider, egress, progress, billed);
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
            if (detail.isBlank()) detail = "Import failed with exit code " + result.exitCode();
            // The import error names the module it missed; why the requirements that declare it
            // are not installed is pip's to say, and the next attempt needs it as much.
            return resolution.installError() == null ? detail
                    : detail.strip() + "\n[requirements not installed: " + resolution.installError().strip() + "]";
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
    /** The system Python's version, once known: "Python 3.12.3". */
    private volatile String pythonVersion;

    /**
     * The version of the Python skills run on, "Python 3.12.3", asked of it once; empty when it
     * cannot be asked, and asked again then.
     */
    java.util.Optional<String> pythonVersion() {
        String known = pythonVersion;
        if (known != null) return java.util.Optional.of(known);
        if (pythonEnv == null) return java.util.Optional.empty();
        java.util.Optional<String> asked = pythonEnv.getPythonVersion();
        asked.ifPresent(v -> pythonVersion = v);
        return asked;
    }

    /**
     * What a failed syntax check returns when the check itself could not run -- no Python, a
     * sandbox that refused, a timeout: nothing about the code, which no repair can fix.
     */
    static final String CHECK_FAILED = "Syntax check failed: ";

    /** The exit status of the check's script for a finding about the code: its own failures exit otherwise. */
    private static final int FOUND = 3;

    /**
     * The syntax check of a skill's code: null when it compiles and has {@code def run(params)};
     * otherwise what is wrong, as Python says it, with the file named skill.py wherever it was
     * checked -- the same code always gets the same words, which the repairs compare
     * (AgentLoop#errorKey). Checked under its temporary path, every error named a directory made
     * afresh for it, and no two were ever the same. A check that could not run says so, starting
     * {@link #CHECK_FAILED}.
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
                    "    py_compile.compile('" + targetPath + "', dfile='skill.py', doraise=True)\n" +
                    "except py_compile.PyCompileError as e:\n" +
                    "    print(str(e), file=sys.stderr)\n" +
                    "    sys.exit(" + FOUND + ")\n" +
                    "with open('" + targetPath + "', encoding='utf-8') as f:\n" +
                    "    tree = ast.parse(f.read())\n" +
                    "funcs = [n.name for n in ast.walk(tree) if isinstance(n, ast.FunctionDef)]\n" +
                    "if 'run' not in funcs:\n" +
                    "    print('Missing required function: def run(params)', file=sys.stderr)\n" +
                    "    sys.exit(" + FOUND + ")\n",
                    StandardCharsets.UTF_8);

            String python = pythonEnv.getSystemPython();
            SandboxResult result = sandbox.execute(python, checkScript, tempDir, null, Map.of(), 10, null, List.of());

            if (result.isSuccess()) return null;
            String said = result.stderr().isBlank() ? result.stdout() : result.stderr();
            if (result.exitCode() == FOUND && !result.timedOut()) return said;
            return CHECK_FAILED + (result.timedOut() ? "it timed out" : "exit " + result.exitCode() + ": " + said.strip());
        } catch (Exception e) {
            return CHECK_FAILED + e.getMessage();
        } finally {
            if (tempDir != null) SkillJail.deleteTree(tempDir);
        }
    }

    /**
     * {@code code} -- as the model wrote it, compiling -- with {@code values} put back where its
     * placeholders stand, by Python's own tokenizer ({@link CodeValues}): run as the syntax check
     * runs, in the sandbox on the system's Python, the values on its standard input and never on a
     * command line or in a file. Nothing to put back, nothing is run. What it could not do is said
     * by why, never with what Python wrote: that could quote the values.
     */
    CodeValues.Restored restoreValues(String code, Map<String, String> values) {
        if (values.isEmpty()) return CodeValues.Restored.unchanged(code);
        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("ownclaw_restore_");
            Path script = tempDir.resolve("restore_values.py");
            Files.writeString(script, CodeValues.SCRIPT, StandardCharsets.UTF_8);
            SandboxResult result = sandbox.execute(pythonEnv.getSystemPython(), script, tempDir,
                    CodeValues.input(code, values), Map.of(), 60, null, List.of());
            if (!result.isSuccess()) {
                return CodeValues.Restored.couldNotRun(result.timedOut() ? "it timed out" : "exit " + result.exitCode());
            }
            return CodeValues.Restored.of(result.stdout());
        } catch (Exception e) {
            return CodeValues.Restored.couldNotRun(e.getClass().getSimpleName());
        } finally {
            if (tempDir != null) SkillJail.deleteTree(tempDir);
        }
    }

    private String buildSkillYaml(String name, String author, String description, Map<String, Object> parametersDef,
                                  boolean requiresNetwork, boolean hasSideEffects, int timeout,
                                  List<String> credentials, String systemPackages, String containerImage)
            throws IOException {
        Map<String, Object> yaml = new LinkedHashMap<>();
        yaml.put("name", name);
        // Who may change it besides the owner (changeRefusal); none for one the owner's pages write.
        if (author != null) yaml.put("author", author);
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
