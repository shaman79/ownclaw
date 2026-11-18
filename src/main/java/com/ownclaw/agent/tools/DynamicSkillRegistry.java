package com.ownclaw.agent.tools;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.conversation.FileStorageService;
import com.ownclaw.sandbox.ContainerSandbox;
import com.ownclaw.sandbox.SandboxManager;
import com.ownclaw.sandbox.SkillJail;
import com.ownclaw.skills.PythonEnvironmentService;
import com.ownclaw.users.CredentialVault;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.util.List;
import java.util.Comparator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Discovers, loads, and manages dynamic Python-based skills from disk.
 *
 * <p>On startup, scans the generated skills directory ({@code ownclaw.skills.generated-path})
 * and registers each valid skill as a {@link Tool} in the {@link ToolRegistry}.
 * Supports hot-registration and unregistration at runtime when the agent creates
 * or deletes skills.
 *
 * <p>Dynamic skills are stored as subdirectories of the generated path, each containing
 * a {@code SKILL.yaml} and {@code skill.py} file.
 */
@Component
public class DynamicSkillRegistry {

    private static final Logger log = LoggerFactory.getLogger(DynamicSkillRegistry.class);
    private static final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    private final OwnClawConfig config;
    private final ToolRegistry toolRegistry;
    private final SandboxManager sandbox;
    private final ContainerSandbox containerSandbox;
    private final PythonEnvironmentService pythonEnv;
    private final CredentialVault credentialVault;
    private final FileStorageService fileStorage;

    private final Map<String, DynamicSkill> dynamicSkills = new ConcurrentHashMap<>();

    public DynamicSkillRegistry(OwnClawConfig config, @Lazy ToolRegistry toolRegistry,
                                SandboxManager sandbox, ContainerSandbox containerSandbox,
                                PythonEnvironmentService pythonEnv,
                                CredentialVault credentialVault,
                                FileStorageService fileStorage) {
        this.config = config;
        this.toolRegistry = toolRegistry;
        this.sandbox = sandbox;
        this.containerSandbox = containerSandbox;
        this.pythonEnv = pythonEnv;
        this.credentialVault = credentialVault;
        this.fileStorage = fileStorage;
    }

    @PostConstruct
    public void init() {
        Path generatedDir = Path.of(config.getSkills().getGeneratedPath());
        if (!Files.isDirectory(generatedDir)) {
            // Known to be absent, or not known at all: isDirectory says no to both. Only an
            // install with no skills may use the one-time grant up for none -- behind a parent
            // that cannot be searched, or a path that is a file, the skills may be waiting.
            // (Not told apart: a directory that is absent because its volume is. There the
            // database is as a rule absent with it, and this is another install's first start.)
            if (Files.notExists(generatedDir)) {
                log.info("No generated skills directory at {} — no dynamic skills loaded", generatedDir);
                keepGranted();
            } else {
                log.error("The skills directory {} cannot be reached (or is no directory), so no skill is loaded "
                        + "and the credentials its skills were being handed are not granted at this start: the "
                        + "next start that reads it grants them.", generatedDir);
            }
            return;
        }
        boolean read = scan(generatedDir, new java.util.HashSet<>());
        log.info("Loaded {} dynamic skill(s)", dynamicSkills.size());
        // Not after a directory that could not be read: the grant is made once, for the skills
        // found, and made for none it would leave every skill that holds a credential refused
        // from the start that does read them. It stays asked for, and the next start makes it.
        if (read) {
            keepGranted();
        } else {
            log.error("The skills directory {} could not be read, so the credentials its skills were being "
                    + "handed are not granted at this start: the next start that reads it grants them.", generatedDir);
        }
    }

    /**
     * The skills there are at the first start that checks grants keep the credentials they were
     * being handed (CredentialGrantService#grantExisting): asked once, here, before any task can
     * run one -- with no skill there is nothing to keep, and it is still asked, so that it is
     * never asked again for a skill made later.
     */
    private void keepGranted() {
        if (credentialVault == null) return;
        var declared = new LinkedHashMap<String, List<String>>();
        // A skill another account than the owner wrote is that account's to write again, so it
        // keeps that account's credentials and nobody else's. One with no author, from before
        // authors were kept, is the owner's.
        var onlyFor = new LinkedHashMap<String, String>();
        for (DynamicSkill skill : dynamicSkills.values()) {
            declared.put(skill.name(), skill.requiredCredentials());
            String author = authorOf(skill.skillDir());
            if (author != null && authService != null && !authService.isOwner(author)) onlyFor.put(skill.name(), author);
        }
        credentialVault.grants().grantExisting(declared, onlyFor);
    }

    /** Who is the owner: asked once, for the one-time grant ({@link #keepGranted}). Not there in a test without accounts. */
    private com.ownclaw.users.AuthService authService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setAuthService(@Lazy com.ownclaw.users.AuthService authService) {
        this.authService = authService;
    }

    /** The account a skill's SKILL.yaml names as its author, or null: none named, or a file that does not read. */
    private static String authorOf(Path skillDir) {
        try {
            Map<String, Object> yaml = yamlMapper.readValue(
                    SkillJail.readAuthored(skillDir.resolve("SKILL.yaml")), new TypeReference<>() {});
            Object author = yaml.get("author");
            return author == null || author.toString().isBlank() ? null : author.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The grants of a skill's name end ({@link com.ownclaw.users.CredentialGrantService#end}): for
     * whoever makes, rewrites or deletes skills, which has this registry and not the vault.
     */
    public void endGrants(String skill, String keep) {
        if (credentialVault != null) credentialVault.grants().end(skill, keep);
    }

    /**
     * Load and register each skill in the directory; one a task writes or runs now is left as it is, named in {@code busy}.
     *
     * @return whether the directory could be read
     */
    private boolean scan(Path generatedDir, java.util.Set<String> busy) {
        try (Stream<Path> dirs = Files.list(generatedDir)) {
            dirs.filter(Files::isDirectory)
                .forEach(dir -> loadAndRegister(dir, busy));
            return true;
        } catch (IOException | java.io.UncheckedIOException e) {
            log.error("Failed to scan generated skills directory: {}", e.getMessage());
            return false;
        }
    }

    private void loadAndRegister(Path skillDir, java.util.Set<String> busy) {
        // Held to write while it is read, registered or quarantined: a skill_create writing it now,
        // or a run of it, is not read half written or moved from under it -- it is left as it is
        // registered, for the next reload.
        String name = skillDir.getFileName().toString();
        var reading = SkillLocks.of(name).writeLock();
        if (!reading.tryLock()) {
            busy.add(name);
            log.info("Not reloading skill '{}': a task is writing or running it.", name);
            return;
        }
        try {
            loadAndRegisterHeld(skillDir);
        } finally {
            reading.unlock();
        }
    }

    private void loadAndRegisterHeld(Path skillDir) {
        try {
            DynamicSkill skill = loadSkill(skillDir);
            if (skill != null) {
                register(skill);
            }
        } catch (Exception e) {
            log.warn("Failed to load dynamic skill from {}: {}", skillDir, e.getMessage());
            quarantine(skillDir, e);
        }
    }

    /**
     * Move a skill that would not load out of the way, instead of deleting it.
     * <p>
     * This used to recursively delete the directory, with the justification that a broken skill
     * "blocks startup repeatedly". It does not: the exception is caught per directory, so a skill
     * that fails to load costs one log line and nothing else. What the deletion did do was destroy
     * working Python, permanently and silently. {@code loadSkill} throws on any YAML syntax error
     * — {@code yamlMapper.readValue} is not lenient — and on a malformed parameter block, so one
     * bad edit to SKILL.yaml by the model that writes these files was enough to lose the skill's
     * code. Generated skills live outside the repository and are not in any backup that git
     * provides, so there was nothing to restore from. A transient IO error during a deploy could
     * do the same to a skill that was perfectly fine.
     * <p>
     * Quarantine achieves the stated goal — the directory stops being rescanned, so the warning
     * does not repeat — while keeping the code recoverable. The destination is a sibling of the
     * generated directory rather than a child, because {@link #loadAll} lists every subdirectory
     * of {@code generated/} and would otherwise try to load the quarantined copy again.
     * <p>
     * If the move itself fails, the directory is deliberately left alone. A skill that logs a
     * warning on every startup is a far better outcome than one that is gone.
     */
    private void quarantine(Path skillDir, Exception cause) {
        moveToQuarantine(skillDir,
                "Quarantined because it could not be loaded.\n"
                        + "Cause: " + cause.getClass().getSimpleName() + ": " + cause.getMessage() + "\n\n"
                        + "The code is intact. Fix SKILL.yaml and move the directory back into\n"
                        + "generated/ to restore the skill.\n");
    }

    /**
     * Retire a registered skill: unregister it and move its directory aside, reversibly.
     * <p>
     * The same mechanism as an unloadable skill, exposed for maintenance. It is deliberately a
     * move and not a delete. Generated skills are written by the agent at runtime, live outside
     * the repository, and are therefore in no backup git provides — so a wrong automated decision
     * has to stay undoable, and "undo" has to mean moving one directory back. A retirement that
     * cannot be reversed would make any automatic pruning rule too dangerous to enable.
     *
     * @param reason why, in a sentence, written into the directory for whoever finds it later
     * @return where it went, or empty if the skill was unknown or could not be moved
     */
    public Optional<Path> retire(String name, String reason) {
        if (dynamicSkills.get(name) == null) {
            log.warn("Cannot retire '{}': no such dynamic skill", name);
            return Optional.empty();
        }
        // Never move a directory out from under a running invocation. The skill's interpreter is
        // already started and reads skill.py, its virtualenv and its own _runner_ harness from
        // that directory for the whole run -- which for a slow skill is minutes. The failure
        // would not be clean: the user gets a Python error about a path that existed a moment
        // ago. Retirement is never urgent, so the right answer is to skip and try next time.
        // Held to write while it moves, taken only if nothing runs it -- any instance of it, the
        // one registered before a skill_create among them, which its own count did not see.
        var moving = SkillLocks.of(name).writeLock();
        if (!moving.tryLock()) {
            log.info("Not retiring '{}': it is running. It will be reconsidered on the next pass.", name);
            return Optional.empty();
        }
        try {
            // Looked up again under the lock: a skill_create that finished meanwhile registered
            // another, whose directory is the one that would be moved.
            DynamicSkill skill = dynamicSkills.get(name);
            if (skill == null) return Optional.empty();
            return retireHeld(name, skill, reason);
        } finally {
            moving.unlock();
        }
    }

    private Optional<Path> retireHeld(String name, DynamicSkill skill, String reason) {
        int running = skill.inFlight();
        if (running > 0) {
            log.info("Not retiring '{}': {} invocation(s) still running. It will be reconsidered "
                    + "on the next pass.", name, running);
            return Optional.empty();
        }
        // Move FIRST, unregister only on success. Unregistering first left a half-state when the
        // move failed -- the skill gone from the manifest but still on disk, so it silently came
        // back at the next restart and nothing recorded that anything had been attempted. Doing
        // the fallible half first means a failure changes nothing at all: the skill stays
        // registered and usable, and the next pass will simply try again.
        Optional<Path> dest = moveToQuarantine(skill.skillDir(),
                "Retired automatically by skill maintenance.\n"
                        + "Reason: " + reason + "\n\n"
                        + "Nothing is necessarily wrong with this code; it was judged no longer\n"
                        + "earning its place. To bring it back, move this directory into\n"
                        + "generated/ (drop the timestamp suffix) and restart.\n");
        if (dest.isEmpty()) {
            log.error("Could not retire '{}': its files could not be moved, so it stays registered "
                    + "and usable. Nothing has changed.", name);
            return dest;
        }
        unregister(name);
        return dest;
    }

    /** The quarantine directory, a sibling of generated/. */
    private Path quarantineDir() {
        return Path.of(config.getSkills().getGeneratedPath()).toAbsolutePath()
                .resolveSibling("quarantine");
    }

    /**
     * Quarantined directories, newest first, as {@code <name>-<timestamp>}.
     * <p>
     * Exposed because quarantine was write-only: things went in and nothing could see them or
     * bring them back without a shell on the host. That was tolerable while the only way in was
     * a skill that would not load, and untenable once an automated pass could put a working
     * skill there — recovery has to be reachable by whoever discovers the mistake.
     */
    public List<String> quarantined() {
        Path dir = quarantineDir();
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .sorted(Comparator.reverseOrder())
                    .toList();
        } catch (IOException e) {
            log.warn("Could not list quarantine at {}: {}", dir, e.getMessage());
            return List.of();
        }
    }

    /**
     * Move a quarantined directory back and register it again. One the owner's tasks wrote, or
     * with no author, keeps the grants of its name; one another account wrote keeps that
     * account's alone, and every other account grants it again before it reads a credential.
     *
     * @param entry the directory name exactly as {@link #quarantined()} reports it
     * @return the skill's name once live again, or empty with the reason logged
     */
    public Optional<String> restoreFromQuarantine(String entry) {
        if (entry == null || entry.isBlank() || entry.contains("/") || entry.contains("..")) {
            log.warn("Refusing to restore a suspicious quarantine entry: {}", entry);
            return Optional.empty();
        }
        Path source = quarantineDir().resolve(entry);
        if (!Files.isDirectory(source)) {
            log.warn("No quarantined directory named {}", entry);
            return Optional.empty();
        }
        // "<name>-<ISO timestamp>" -- the timestamp starts at the last '-' followed by a digit
        // sequence that parses as a date, but the name itself may contain '-', so cut at the
        // first '-' that begins a 4-digit year.
        String name = entry.replaceFirst("-\\d{4}-\\d{2}-\\d{2}T.*$", "");
        Path dest = Path.of(config.getSkills().getGeneratedPath()).toAbsolutePath().resolve(name);
        if (Files.exists(dest)) {
            log.warn("Not restoring {}: a skill directory named '{}' already exists.", entry, name);
            return Optional.empty();
        }
        try {
            // Code another account than the owner wrote comes back to that account's grant
            // alone, as when that account writes a skill again: which directory of the name
            // this is cannot be told from here, and one of its tasks' that never loaded would
            // be registered under what the owner granted the skill maintenance retired. The
            // owner's own, or one with no author, comes back as it left -- so a retirement
            // that was a mistake is undone by this alone. The author is the one its
            // SKILL.yaml names, which the skill manager wrote and a jailed skill cannot
            // change. Ended before anything moves, so a database that refuses leaves it there.
            String author = authorOf(source);
            if (author != null && authService != null && !authService.isOwner(author)) endGrants(name, author);
            Files.move(source, dest);
            DynamicSkill skill = loadSkill(dest);
            if (skill == null) {
                Files.deleteIfExists(dest.resolve("QUARANTINE-REASON.txt"));
                log.error("Restored {} to {} but it would not load; it stays on disk.", entry, dest);
                return Optional.empty();
            }
            if (!skill.name().equals(name)) {
                // Its SKILL.yaml names another skill than its directory: it would be registered
                // as that one, under that one's grants, which nothing here ended.
                Files.move(dest, source);
                log.error("Not restoring {}: its SKILL.yaml names the skill '{}', not '{}'. It is back in quarantine.",
                        entry, skill.name(), name);
                return Optional.empty();
            }
            Files.deleteIfExists(dest.resolve("QUARANTINE-REASON.txt"));
            register(skill);
            log.warn("Restored quarantined skill '{}' from {}", name, entry);
            return Optional.of(skill.name());
        } catch (Exception e) {
            log.error("Could not restore {}: {}", entry, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Quarantine a directory that was never registered — a skill rejected at creation.
     * <p>
     * It cannot go through {@link #retire}, which looks the skill up in the registry and finds
     * nothing. Left alone, the directory stays in {@code generated/} and {@link #init} registers
     * it on the next restart, so a skill that was explicitly rejected for not importing comes
     * back silently and fails on first use.
     */
    public Optional<Path> quarantineUnregistered(Path skillDir, String reason) {
        return moveToQuarantine(skillDir,
                "Quarantined at creation.\n"
                        + "Reason: " + reason + "\n\n"
                        + "This skill was written but never registered. Fix the code and move the\n"
                        + "directory back into generated/ if it is worth keeping.\n");
    }

    /** Move a skill directory into the quarantine sibling, leaving a note saying why. */
    private Optional<Path> moveToQuarantine(Path skillDir, String reasonText) {
        try {
            Path quarantineDir = quarantineDir();
            Files.createDirectories(quarantineDir);
            String stamp = java.time.Instant.now().toString().replace(':', '-');
            Path dest = quarantineDir.resolve(skillDir.getFileName() + "-" + stamp);
            Files.move(skillDir, dest);
            log.warn("Quarantined skill '{}' to {} — its code is intact and recoverable",
                    skillDir.getFileName(), dest);
            // The directory was the skill's own, and whatever it left at this name -- a link to
            // the database, say -- must not be written through.
            Path note = dest.resolve("QUARANTINE-REASON.txt");
            try {
                SkillJail.writeFresh(note, reasonText + "\nWhen: " + java.time.Instant.now() + "\n");
            } catch (IOException noteEx) {
                log.warn("Quarantined {} without its note: {}", dest, noteEx.getMessage());
            }
            return Optional.of(dest);
        } catch (Exception moveEx) {
            log.error("Could not quarantine {} ({}). Leaving it in place.", skillDir, moveEx.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Load a dynamic skill from a directory containing SKILL.yaml and skill.py.
     *
     * @param skillDir the directory to load from
     * @return the loaded DynamicSkill, or null if the directory is incomplete
     */
    public DynamicSkill loadSkill(Path skillDir) throws IOException {
        Path yamlFile = skillDir.resolve("SKILL.yaml");
        Path scriptFile = skillDir.resolve("skill.py");

        if (!Files.exists(yamlFile) || !Files.exists(scriptFile)) {
            log.warn("Incomplete skill in {}: missing SKILL.yaml or skill.py", skillDir);
            return null;
        }

        // Through the one reader of authored files: a link left at this name is refused, not
        // followed into whatever it points at.
        Map<String, Object> yaml = yamlMapper.readValue(
                SkillJail.readAuthored(yamlFile), new TypeReference<>() {});

        String name = getString(yaml, "name");
        String description = getString(yaml, "description");
        if (name == null || description == null) {
            log.warn("Skill in {} missing required 'name' or 'description'", skillDir);
            return null;
        }

        Declaration declared = readParameters(yaml.get("parameters"));
        if (!declared.whole()) {
            // Its name alone: once per load, for whoever reads the log to put the declaration right.
            log.warn("Skill '{}': the declaration of its parameters in SKILL.yaml could not be read in full, so "
                    + "a call of it is not held to the names that were read, and a model listed them is told "
                    + "so. A declaration is a map of each parameter's name to its type, its description and "
                    + "whether it is required.", name);
        }
        Map<String, ToolParam> parameters = toolParams(declared);
        boolean requiresNetwork = Boolean.TRUE.equals(yaml.get("requires_network"));
        // Three states: a SKILL.yaml that says nothing is not one that says false (Tool#declaredSideEffects).
        Boolean hasSideEffects = yaml.get("has_side_effects") instanceof Boolean b ? b : null;
        int timeout = yaml.containsKey("timeout") ? toInt(yaml.get("timeout"), 30) : 30;
        List<String> credentials = parseCredentials(yaml);
        List<String> systemPackages = parseStringList(yaml, "system_packages");
        String containerImage = getString(yaml, "container_image");

        return new DynamicSkill(name, description, parameters, declared.whole(), skillDir,
                requiresNetwork, hasSideEffects, timeout, sandbox, pythonEnv,
                credentials, credentialVault, systemPackages, containerImage, containerSandbox,
                fileStorage);
    }

    /**
     * The credentials field from SKILL.yaml; a skill that declares a name
     * {@link #credentialNames} refuses does not load.
     */
    private List<String> parseCredentials(Map<String, Object> yaml) throws IOException {
        try {
            return credentialNames(yaml.get("credentials"));
        } catch (IllegalArgumentException refused) {
            throw new IOException("SKILL.yaml: " + refused.getMessage());
        }
    }

    /**
     * The credential names a skill declares, upper-cased: the one parser for them, for SKILL.yaml
     * and for skill_create alike. A list, nested or not, or a string, each element a
     * comma-separated list of names -- the model may send one stringified, as
     * "[IMAP_HOST, IMAP_PORT]", so brackets are dropped.
     *
     * @throws IllegalArgumentException at the first name that is not a
     *                                  {@link CredentialVault#validName}
     */
    public static List<String> credentialNames(Object declared) {
        var names = new ArrayList<String>();
        collectNames(declared, names);
        for (String name : names) {
            if (!CredentialVault.validName(name)) throw new IllegalArgumentException(CredentialVault.NAME_RULE);
        }
        return List.copyOf(names);
    }

    private static void collectNames(Object declared, List<String> out) {
        if (declared == null) return;
        if (declared instanceof List<?> list) {
            for (Object item : list) collectNames(item, out);
            return;
        }
        for (String part : declared.toString().replaceAll("[\\[\\]]", "").split(",")) {
            String name = part.trim().toUpperCase();
            if (!name.isBlank()) out.add(name);
        }
    }

    /**
     * Parse a generic string list field from SKILL.yaml.
     * Supports YAML list, comma-separated string, or space-separated string.
     */
    private List<String> parseStringList(Map<String, Object> yaml, String key) {
        Object obj = yaml.get(key);
        if (obj == null) return List.of();
        if (obj instanceof List<?> list) {
            return list.stream()
                    .map(Object::toString)
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .toList();
        }
        if (obj instanceof String str && !str.isBlank()) {
            // Support both comma-separated and space-separated
            String delimiter = str.contains(",") ? "," : "\\s+";
            return Arrays.stream(str.split(delimiter))
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .toList();
        }
        return List.of();
    }

    /**
     * What a skill's SKILL.yaml holds under {@code parameters}, as it stands there -- read in full
     * or not -- or null when it holds none or cannot be read: for a save that does not change
     * them ({@code SkillController}).
     */
    public Object declaredAsWritten(DynamicSkill skill) {
        try {
            String text = SkillJail.readAuthored(skill.skillDir().resolve("SKILL.yaml"));
            if (text == null) return null;
            Map<String, Object> yaml = yamlMapper.readValue(text, new TypeReference<>() {});
            return yaml == null ? null : yaml.get("parameters");
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    /**
     * A skill's parameters as its declaration was read ({@link #readParameters}).
     *
     * @param byName each parameter's definition by its name, in the declaration's order: its
     *               {@code type}, its {@code description} and whether it is {@code required},
     *               where the declaration says them
     * @param whole  whether all of the declaration was read. Where part of it was not -- a shape
     *               nobody foresaw -- the names read are not known to be all the skill takes: a
     *               call of it is not refused for a name outside them (LocalExecutor#misfit), and
     *               a model that is listed them is told so (ToolFinder#parameters)
     */
    public record Declaration(Map<String, Map<String, Object>> byName, boolean whole) {}

    /**
     * The parameters a skill declares: the one reader of a declaration, for what skill_create is
     * handed -- SkillManager writes SKILL.yaml from this reading -- and for a SKILL.yaml as it is
     * loaded, so the two cannot read one declaration two ways.
     * <p>
     * A declaration is a map of each name to its definition: its type, its description, whether
     * it is required. No entry of it is dropped. A definition written as a scalar is a parameter
     * too -- its text the type when it names one, its description otherwise -- and one left empty
     * is a string. Only definitions that were maps used to be read, and skill_create wrote a
     * declaration handed as a JSON string as it came: {"url": "string", "timeout": {"type":
     * "integer"}} made a skill that declared timeout alone, and a delegated call naming url,
     * which its code reads, was refused as naming a parameter the skill does not have -- with a
     * list of its parameters that left url out, so the cloud could not correct the call; one in
     * shorthand throughout declared nothing, and the cloud was told "(no parameters)" (the review
     * of e16a1ff, 2026-11-18).
     * <p>
     * The same written as a JSON Schema is read as its properties ({@link #schemaLike}):
     * read as a map of names, that was one parameter called "properties" and no other, which is
     * how imap_move_to_bin_by_sender_gmail was offered to the model, its own five nowhere. A list
     * of definitions that each give their name is read too.
     * <p>
     * What is none of these is not guessed at, and not passed off as read: a declaration that is
     * no map and no list, an entry of a list that names no parameter, a schema whose type is not
     * an object's, a keyword of a schema this does not know -- one that may hold more names, or
     * say that others are taken -- and a map under "properties" that is neither shown to be a
     * schema's nor reads as the definition of a parameter of that name. The rest is read, and the
     * declaration is said not to have been read in full ({@link Declaration#whole}).
     *
     * <p>
     * And what was read counts as all of the declaration only where the declaration is a shape
     * nothing else can be taken for ({@link #unmistakable}), read as that shape. The reading above
     * tells a schema from a map of names by what stands in it, and its second review (18 Nov
     * 2026) found each such telling wrong for some declaration, in full: {"type": "object",
     * "additionalProperties": true} read as the parameters type and additionalProperties, so a
     * skill that takes any name was refused the names it was called with; a parameter called
     * properties, whose definition held maps, read as a schema, and the parameters beside it were
     * gone. A declaration in doubt costs one rule -- a call of the skill is held to no names, and
     * a model is told its parameters are not known -- where a wrong reading passed off as whole
     * refuses a call that was right, with a list the call cannot be corrected by.
     *
     * @param declared what SKILL.yaml holds under {@code parameters}, or skill_create was handed
     */
    public static Declaration readParameters(Object declared) {
        Declaration read = readAsItCan(declared);
        return read.whole() && (aWrapper(read.byName().keySet()) || !unmistakable(declared, read.byName().keySet()))
                ? new Declaration(read.byName(), false) : read;
    }

    /** A schema's own words: at the top of a declaration, each is a parameter's name only beside a definition. */
    private static final Set<String> SCHEMA_WORDS = Set.of("type", "properties", "required", "additionalProperties",
            "patternProperties", "anyOf", "oneOf", "allOf", "not", "items", "title", "description", "definitions",
            "enum", "default", "const", "format");

    /**
     * Whether a declaration is one of the shapes nothing else can be taken for, and {@code names}
     * -- what the reading made of it -- are that shape's names, in its order:
     * <ul>
     *   <li>nothing at all: a skill that declares no parameters;</li>
     *   <li>a map of names: every key a name ({@link #nameOf}) and every value that parameter's
     *       definition -- a map, a scalar in shorthand, or nothing. A key that is a word of a
     *       schema's ({@link #SCHEMA_WORDS}) is a parameter only when its definition is a map
     *       that says a type or a description in plain words: {type: {type: string}} is a
     *       parameter called type, and {type: object, ...} is no map of names. One key may be
     *       {@code required} with the list of the map's own names that are: no parameter;</li>
     *   <li>a JSON Schema: {@code properties}, such a map of names, and beside it nothing but
     *       {@code type} -- the word object -- {@code required}, a list of names that are all
     *       properties, {@code additionalProperties: false}, and words about the schema (a title,
     *       a description, a {@code $schema}). Its type may be left out where something else
     *       shows it a schema: its properties are all maps, or its required names are theirs;</li>
     *   <li>a list whose every item is a map that gives its name and says a type or a
     *       description.</li>
     * </ul>
     * Anything else may be what the reading took it for, or something else.
     */
    static boolean unmistakable(Object declared, Collection<String> names) {
        if (declared == null) return names.isEmpty();
        if (declared instanceof List<?> list) {
            var listed = new ArrayList<String>();
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> named) || nameOf(named.get("name")) == null || !saysWhatItIs(named)) return false;
                listed.add(nameOf(named.get("name")));
            }
            return listed.equals(List.copyOf(names));
        }
        if (!(declared instanceof Map<?, ?> map)) return false;
        // A map of names first, as the reading takes it first: parameters that are called
        // properties and description, each defined, are no schema.
        if (namesOf(map, true, null)) return namesOf(map, true, names);
        Object type = map.get("type");
        boolean object = type instanceof String word && word.strip().equalsIgnoreCase("object");
        if (map.get("properties") instanceof Map<?, ?> properties && (object || !map.containsKey("type"))) {
            List<?> required = map.get("required") instanceof List<?> listed ? listed : null;
            // With no type, only its required names show it a schema. That its properties are
            // all maps does not: {properties: {email: {...}, firstname: {...}}} is as well one
            // parameter called properties, defined by its fields (the third review).
            boolean shown = object || !properties.isEmpty() && required != null && !required.isEmpty();
            for (var e : map.entrySet()) {
                String key = String.valueOf(e.getKey());
                Object value = e.getValue();
                boolean itsOwn = switch (key) {
                    case "type", "properties" -> true;
                    case "required" -> required != null
                            && required.stream().allMatch(n -> n instanceof String name && properties.containsKey(name));
                    case "additionalProperties" -> Boolean.FALSE.equals(value);
                    case "title", "description" -> value instanceof String;
                    default -> key.startsWith("$") && (value instanceof String || value instanceof Number || value instanceof Boolean);
                };
                if (!itsOwn) return false;
            }
            return shown && namesOf(properties, false, names);
        }
        return false;
    }

    /**
     * Whether {@code byName} is a map of names ({@link #unmistakable}) whose names are {@code names}.
     *
     * @param mayListRequired whether a {@code required} key may be the list of its required names
     */
    private static boolean namesOf(Map<?, ?> byName, boolean mayListRequired, Collection<String> names) {
        var keys = new ArrayList<String>();
        for (var e : byName.entrySet()) {
            if (!(e.getKey() instanceof String key) || !key.equals(nameOf(key))) return false;
            Object def = e.getValue();
            if (mayListRequired && key.equals("required") && def instanceof List<?> required
                    && required.stream().allMatch(n -> n instanceof String name && !name.equals("required")
                            && byName.containsKey(name))) {
                continue;
            }
            boolean defined = def instanceof Map<?, ?> definition && (!SCHEMA_WORDS.contains(key) || saysWhatItIs(definition));
            boolean shorthand = !SCHEMA_WORDS.contains(key)
                    && (def == null || def instanceof String || def instanceof Number || def instanceof Boolean);
            if (!defined && !shorthand) return false;
            keys.add(key);
        }
        return names == null || keys.equals(List.copyOf(names));
    }

    /**
     * The properties of a declaration that reads as a JSON Schema though it is none beyond doubt
     * ({@link #unmistakable}), or null when it is read by its keys: every key of it is a word of
     * a schema's, and it has a map of properties -- or none, and says its type is an object's,
     * which is a schema of a skill that names no parameter. A key that is no word of a schema's
     * is a parameter, and then so are the keys beside it: {database_id: ..., properties: {Name:
     * ..., Status: ...}} is two parameters, the second defined by its fields -- read as a schema
     * because every value under properties was a map, it lost database_id and was held to Name
     * (the second review, 18 Nov 2026).
     */
    private static Map<?, ?> schemaLike(Map<?, ?> declared) {
        boolean itsWords = declared.keySet().stream()
                .allMatch(key -> key instanceof String word && (SCHEMA_WORDS.contains(word) || word.startsWith("$")));
        if (!itsWords) return null;
        if (declared.get("properties") instanceof Map<?, ?> properties) return properties;
        return declared.get("type") instanceof String type && type.strip().equalsIgnoreCase("object") ? Map.of() : null;
    }

    /** The names a whole declaration is found wrapped in, where a model writes one level too many. */
    private static final Set<String> WRAPPERS = Set.of("properties", "parameters", "params", "input_schema",
            "inputSchema", "schema", "args", "arguments");

    /**
     * Whether what was read is one parameter alone -- words about a schema, {@code $schema}, aside
     * -- under a name a declaration is wrapped in. However it was written, such a reading is in
     * doubt: {parameters: {url: {...}}} is a parameter called parameters, or the declaration of
     * url one level down; {properties: {title: "string", description: "string"}} is a parameter
     * called properties whose description is the word "string", or a schema of two in shorthand.
     * Read in full as the first, a call naming url, or title, was refused (the third review of
     * the reader, 18 Nov 2026). And written by skill_create as the one shape it writes, a
     * declaration of one such parameter is that same map, whatever shape said it first.
     */
    private static boolean aWrapper(Collection<String> names) {
        var own = names.stream().filter(name -> !name.startsWith("$")).toList();
        return own.size() == 1 && WRAPPERS.contains(own.getFirst());
    }

    /** Whether a map reads as one parameter's definition: it says a type or a description, in plain words. */
    private static boolean saysWhatItIs(Map<?, ?> definition) {
        return definition.get("type") instanceof String || definition.get("description") instanceof String;
    }

    /** The reading of a declaration, as far as it can be read: see {@link #readParameters}. */
    private static Declaration readAsItCan(Object declared) {
        var byName = new LinkedHashMap<String, Map<String, Object>>();
        if (declared == null) return new Declaration(byName, true);
        if (declared instanceof Map<?, ?> map) {
            // A map of names where it is one beyond doubt (unmistakable); else a schema where it
            // reads as one (schemaLike); else its keys, each read as a parameter.
            Map<?, ?> properties = namesOf(map, true, null) ? null : schemaLike(map);
            if (properties == null) {
                // A map of names that lists its required ones the schema's way -- none, when the
                // list is empty: no parameter.
                List<String> required = map.get("required") instanceof List<?> ? requiredNames(map.get("required")) : null;
                boolean ofItsNames = required != null
                        && required.stream().allMatch(n -> !n.equals("required") && map.containsKey(n));
                map.forEach((name, def) -> {
                    if (!ofItsNames || !"required".equals(name)) byName.put(String.valueOf(name), definition(def));
                });
                if (ofItsNames) required.forEach(n -> byName.get(n).put("required", true));
                // Read by its keys. Whether that is all of it is not said here: only a map of
                // names beyond doubt counts as read in full (readParameters).
                return new Declaration(byName, true);
            }
            boolean whole = true;
            properties.forEach((name, def) -> byName.put(String.valueOf(name), definition(def)));
            for (var e : map.entrySet()) {
                String keyword = String.valueOf(e.getKey());
                Object value = e.getValue();
                switch (keyword) {
                    case "properties" -> { }
                    // An object's. Any other type is a schema of something this does not know.
                    case "type" -> whole &= value == null || isObject(value);
                    case "required" -> {
                        List<String> names = requiredNames(value);
                        if (names == null) {
                            whole = false;
                        } else {
                            // A name it requires and does not define is a parameter all the same.
                            names.forEach(n -> byName.computeIfAbsent(n, x -> new LinkedHashMap<>()).put("required", true));
                        }
                    }
                    // No other name is taken: what this reading holds anyway.
                    case "additionalProperties" -> whole &= Boolean.FALSE.equals(value);
                    // Words about the schema, or -- $schema, $id -- for whoever reads one: none a parameter.
                    default -> whole &= (keyword.equals("title") || keyword.equals("description")
                            || keyword.startsWith("$")) && !(value instanceof Map<?, ?>) && !(value instanceof Collection<?>);
                }
            }
            return new Declaration(byName, whole);
        }
        if (declared instanceof List<?> list) {
            boolean whole = true;
            for (Object item : list) {
                if (item instanceof Map<?, ?> named && nameOf(named.get("name")) != null) {
                    Map<String, Object> def = definition(named);
                    def.remove("name");
                    byName.put(nameOf(named.get("name")), def);
                } else if (nameOf(item) != null) {
                    byName.put(nameOf(item), definition(null));
                } else {
                    whole = false;
                }
            }
            return new Declaration(byName, whole);
        }
        return new Declaration(byName, false);
    }

    /** What a model writes for a type where a definition is asked for: {"url": "string"}. */
    private static final java.util.regex.Pattern TYPE_NAME = java.util.regex.Pattern.compile(
            "(?i)string|str|int|integer|number|float|bool|boolean|array|list|object");

    /**
     * One parameter's definition as it is read: a map as it stands; nothing at all as a string;
     * anything else by its text -- the type when it names one, the description otherwise. Either
     * way the parameter is there, which is the point: dropped, the skill registered without it,
     * the model was told the skill takes no such argument, and the Python died on a missing key
     * while skill_create had reported success.
     */
    private static Map<String, Object> definition(Object def) {
        var out = new LinkedHashMap<String, Object>();
        if (def instanceof Map<?, ?> map) {
            map.forEach((key, value) -> out.put(String.valueOf(key), value));
        } else if (def == null) {
            out.put("type", "string");
        } else {
            String text = String.valueOf(def).trim();
            if (TYPE_NAME.matcher(text).matches()) {
                out.put("type", text.toLowerCase(Locale.ROOT));
            } else {
                out.put("type", "string");
                out.put("description", text);
            }
        }
        return out;
    }

    /**
     * Whether a schema's {@code type} says "object": in any case -- or as {type: object}, which is
     * what skill_create's reading of a schema handed as an object once made of the word, taking
     * it for a parameter's type in shorthand.
     */
    private static boolean isObject(Object type) {
        if (type instanceof Map<?, ?> rewritten && rewritten.size() == 1) type = rewritten.get("type");
        return type instanceof String object && object.strip().equalsIgnoreCase("object");
    }

    /**
     * The names a schema's {@code required} holds, or null when it is no list of names: a list
     * of them -- or the definition skill_create's reading once made of one, whose description
     * is the list's text, "[url, depth]".
     */
    private static List<String> requiredNames(Object required) {
        if (required instanceof Map<?, ?> rewritten && rewritten.get("description") instanceof String text
                && text.strip().startsWith("[") && text.strip().endsWith("]")) {
            String inside = text.strip();
            required = Arrays.stream(inside.substring(1, inside.length() - 1).split(","))
                    .map(String::strip).filter(n -> !n.isEmpty()).toList();
        }
        if (!(required instanceof List<?> list)) return null;
        var names = new ArrayList<String>();
        for (Object item : list) {
            if (nameOf(item) == null) return null;
            names.add(nameOf(item));
        }
        return names;
    }

    /** {@code item} as a parameter's name -- text with no blank in it -- or null when it is none. */
    private static String nameOf(Object item) {
        return item instanceof String name && !name.isBlank() && !name.strip().matches(".*\\s.*")
                ? name.strip() : null;
    }

    /** The declared parameters as a tool's schema holds them. */
    private static Map<String, ToolParam> toolParams(Declaration declared) {
        Map<String, ToolParam> result = new LinkedHashMap<>();
        declared.byName().forEach((name, def) -> {
            // A description written as null is none given, not the word "null" -- which is what a
            // model was listed, "(string, optional): null". A type written so is a missing one's.
            Object type = def.get("type");
            Object description = def.get("description");
            result.put(name, new ToolParam(type == null ? "string" : String.valueOf(type),
                    description == null ? "" : String.valueOf(description),
                    Boolean.TRUE.equals(def.get("required"))));
        });
        return result;
    }

    /** Register a dynamic skill in both the internal map and the ToolRegistry. */
    public void register(DynamicSkill skill) {
        dynamicSkills.put(skill.name(), skill);
        toolRegistry.register(skill);
        log.info("Registered dynamic skill: {}", skill.name());
    }

    /** Unregister a dynamic skill by name. Returns true if it existed. */
    public boolean unregister(String name) {
        DynamicSkill removed = dynamicSkills.remove(name);
        if (removed != null) {
            toolRegistry.unregister(name);
            log.info("Unregistered dynamic skill: {}", name);
            return true;
        }
        return false;
    }

    /** All currently loaded dynamic skills. */
    public Collection<DynamicSkill> allDynamic() {
        return Collections.unmodifiableCollection(dynamicSkills.values());
    }

    /** Get a specific dynamic skill by name. */
    public Optional<DynamicSkill> getDynamic(String name) {
        return Optional.ofNullable(dynamicSkills.get(name));
    }

    /** Check if a tool name corresponds to a dynamic (modifiable) skill. */
    public boolean isDynamic(String name) {
        return dynamicSkills.containsKey(name);
    }

    /**
     * Re-scan the generated directory: each skill found is registered over the one loaded before,
     * and one no longer there is unregistered after. Unregistering them all first left every
     * other task, running beside the reload, finding no such tool.
     */
    public void reload() {
        var before = new java.util.HashMap<>(dynamicSkills);
        var busy = new java.util.HashSet<String>();
        Path generatedDir = Path.of(config.getSkills().getGeneratedPath());
        if (Files.isDirectory(generatedDir)) scan(generatedDir, busy);
        for (var e : before.entrySet()) {
            if (!busy.contains(e.getKey()) && dynamicSkills.get(e.getKey()) == e.getValue()) unregister(e.getKey());
        }
        log.info("Reloaded {} dynamic skill(s)", dynamicSkills.size());
    }

    private String getString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value != null ? value.toString() : null;
    }

    private int toInt(Object value, int defaultValue) {
        if (value instanceof Number n) return n.intValue();
        try { return Integer.parseInt(value.toString()); } catch (Exception e) { return defaultValue; }
    }
}
