package com.ownclaw.sandbox;

import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.conversation.FileStorageService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Puts every Python process OwnClaw starts for a skill inside an unprivileged bubblewrap jail.
 * <p>
 * A skill is code the model wrote, and until now it ran as the service user with the service's
 * view of the disk: the database (the vault's master key, the JWT secret and every stored
 * credential), {@code .env}, every user's uploads and every other skill were one {@code open()}
 * away, and the network was there to send them anywhere. Stripping the environment (the
 * allow-list below) took the secrets out of the process; this takes them off the disk the
 * process can see. The host stays visible read-only, so a skill that inspects the machine still
 * works; a tmpfs is laid over each outermost directory the service keeps its state in, and only
 * the skill's own directory (writable), its authored files, and what the caller hands it
 * (read-only) are put back. A fresh PID namespace removes {@code /proc/<jvm>/environ} and
 * {@code /proc/<jvm>/root}, the routes around any hidden mount. The network is deliberately
 * shared: the property that matters -- a skill cannot read the database and post it anywhere --
 * comes from hiding the database, and skills that talk to the LAN, mail servers or Ollama keep
 * working.
 * <p>
 * Three modes, from the root-owned {@code .env} only ({@code OWNCLAW_SANDBOX_ISOLATION}), so no
 * skill can switch it off:
 * <ul>
 *   <li>{@code auto} (default) -- jail every launch when bubblewrap is installed and passes a
 *       self-check; otherwise run exactly as before, with one warning. It never refuses a skill
 *       because bubblewrap is missing or blocked, so the first deploy to a host without it
 *       changes nothing.</li>
 *   <li>{@code required} -- if the jail is not working, refuse the launch. Fail closed.</li>
 *   <li>{@code off} -- development and Windows.</li>
 * </ul>
 */
@Component
public class SkillJail {

    private static final Logger log = LoggerFactory.getLogger(SkillJail.class);

    /** How often a jail that is not READY looks again, and the floor on a forced recheck. */
    static final long RECHECK_MS = 60_000;

    private static final int SELF_CHECK_SECONDS = 10;

    /** The files a skill was written as. Read-only inside the jail, always. */
    public static final List<String> AUTHORED = List.of("SKILL.yaml", "skill.py", "requirements.txt");

    /**
     * Write one of a skill's {@link #AUTHORED} files from the JVM: in place, and never through a
     * link -- the open fails instead.
     * <p>
     * In place, not deleted and created anew as the JVM's other files in these directories
     * are: a jailed run of the same skill may be going on, holding this file as a read-only
     * mount. Deleting the file on the host takes that mount away and leaves the name free inside
     * the jail, for the running skill to create its own -- a SKILL.yaml declaring the
     * credentials it wants, a requirements.txt of its choosing -- before the JVM's create gets
     * there. Written in place, the mount stays and the running copy sees the new content.
     */
    public static void writeAuthored(Path file, String content) throws IOException {
        Files.writeString(file, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
    }

    /** The name the self-check's shell runs under; lets a test double recognise the probe. */
    static final String PROBE_NAME = "ownclaw-jail-probe";

    // Directories that must stay visible for anything to run. A hidden root that is one of them,
    // or contains one, would blank the interpreter, the resolver (/etc/resolv.conf points into
    // /run) or the jail's own /proc, /dev and /tmp.
    private static final List<Path> SYSTEM = List.of(
            Path.of("/usr"), Path.of("/etc"), Path.of("/bin"), Path.of("/sbin"), Path.of("/lib"),
            Path.of("/lib64"), Path.of("/proc"), Path.of("/dev"), Path.of("/tmp"), Path.of("/sys"),
            Path.of("/run"));

    // The probe runs as `sh -c PROBE ownclaw-jail-probe <paths that must be hidden>`. It only
    // prints what it saw; the JVM decides, and checks the host side of every write itself.
    private static final String PROBE = String.join("\n",
            "for p in \"$@\"; do",
            "  if [ -e \"$p\" ] || [ -h \"$p\" ]; then echo \"visible=$p\"; fi",
            "done",
            "echo \"pidns=$(readlink /proc/self/ns/pid)\"",
            "echo \"netns=$(readlink /proc/self/ns/net)\"",
            "echo ok > rw.txt",
            "echo ok > /tmp/ownclaw-jail-probe.txt",
            "if (echo x >> ro.txt) 2>/dev/null; then echo ro=writable; fi",
            "if head -c 1 /etc/passwd > /dev/null 2>&1; then echo etc=readable; fi",
            "echo probe=done");

    public enum Mode { AUTO, REQUIRED, OFF }

    /**
     * READY: every launch is jailed. INACTIVE: launches run directly, as before the jail existed
     * (auto only). REFUSING: every launch is refused. OFF: switched off by configuration.
     */
    public enum State { READY, INACTIVE, REFUSING, OFF }

    /**
     * Where the service keeps what a skill must not see, all absolute and normalized.
     *
     * @param cwd       the service's working directory; in production it holds .env, the repo,
     *                  backups and the JDK, and is the one root that covers all the others
     * @param database  the SQLite file; its directory also holds the uploads
     * @param generated the generated-skills directory; its parent holds the environments
     * @param logDir    where the log file is written
     */
    public record Layout(Path cwd, Path database, Path generated, Path logDir) {
        public Layout {
            cwd = abs(cwd);
            database = abs(database);
            generated = abs(generated);
            logDir = logDir == null ? null : abs(logDir);
        }

        Path dataDir() { return database.getParent(); }
        Path uploads() { return FileStorageService.uploadsDirFor(database); }
        Path skillsRoot() { return generated.getParent(); }
        Path envsRoot() { return skillsRoot().resolve("_envs"); }
        Path dotEnv() { return cwd.resolve(".env"); }

        /** What the self-check proves invisible from inside the jail. */
        List<Path> mustBeHidden() {
            var out = new ArrayList<>(List.of(database, dotEnv(), uploads(), generated, envsRoot()));
            if (logDir != null) out.add(logDir);
            return out;
        }

        /** What neither the owner's expose list nor a caller's bind may ever reach. */
        List<Path> protectedPaths() {
            var out = new ArrayList<>(List.of(dataDir(), uploads(), database, dotEnv(), skillsRoot(),
                    generated, envsRoot(), cwd.resolve("backups"), cwd.resolve("repo")));
            if (logDir != null) out.add(logDir);
            return out;
        }
    }

    /** A read-only bind of a host path at a (possibly different) path inside the jail. */
    public record Bind(Path source, Path dest) {}

    /**
     * What to start, and how. Exactly one of these holds: {@code refusal} is set and nothing may
     * be started; or {@code argv} is the command to start -- wrapped in bubblewrap when
     * {@code jailed}, unchanged otherwise. Close it when the process is gone: that removes the
     * run's private /tmp.
     */
    public record Launch(List<String> argv, boolean jailed, String refusal, Path tmpDir)
            implements AutoCloseable {

        static Launch direct(List<String> command) {
            return new Launch(List.copyOf(command), false, null, null);
        }

        static Launch refused(String reason) {
            return new Launch(List.of(), false, reason, null);
        }

        @Override
        public void close() {
            if (tmpDir != null) deleteTree(tmpDir);
        }
    }

    /** A snapshot for health and tests. */
    public record Status(Mode mode, State state, String reason, String bwrap, String version,
                         List<Path> hiddenRoots, List<Bind> exposed, Map<String, Boolean> selfCheck,
                         long checkedAtMillis, long isolatedRuns, long directRuns, long refusedRuns) {}

    private record Signature(boolean present, long size, long modified, Object key) {
        static final Signature ABSENT = new Signature(false, -1, -1, null);
    }

    private record Evaluation(State state, String reason, Signature signature, long at,
                              String version, List<Path> roots, List<Bind> expose,
                              Map<String, Boolean> checks) {}

    private record SelfCheck(boolean completed, Map<String, Boolean> checks, String reason) {}

    private final Mode mode;
    private final String configError;
    private final Path bwrap;
    private final Layout layout;
    private final List<String> exposeEntries;
    private final LongSupplier clock;

    private final AtomicLong isolatedRuns = new AtomicLong();
    private final AtomicLong directRuns = new AtomicLong();
    private final AtomicLong refusedRuns = new AtomicLong();
    // Counted from the first moment the jail was READY in this process, not from startup: the
    // jail picks up a bubblewrap installed while the service runs, and the direct launches from
    // before that would otherwise read as launches that escaped a working jail. A later fall
    // back to INACTIVE and its direct launches do count -- that is what this number is for.
    private final AtomicLong directSinceReady = new AtomicLong();
    private final long startedAt;
    private volatile long readySince;

    private Evaluation evaluation;
    private boolean recheckRequested;

    @Autowired
    public SkillJail(OwnClawConfig config) {
        this(config.getSandbox().getIsolation(), config.getSandbox().getBwrapPath(),
                layoutOf(config), config.getSandbox().getExpose(), System::currentTimeMillis);
    }

    /**
     * @param isolation auto, required or off; anything else refuses every launch, because a
     *                  mistyped "required" must not quietly become "auto"
     * @param bwrap     absolute path of the bubblewrap binary; never looked up on PATH
     */
    public SkillJail(String isolation, String bwrap, Layout layout, List<String> expose,
                     LongSupplier clock) {
        String error = null;
        Mode m;
        String value = isolation == null ? "" : isolation.trim().toLowerCase(Locale.ROOT);
        switch (value) {
            case "", "auto" -> m = Mode.AUTO;
            case "required" -> m = Mode.REQUIRED;
            case "off" -> m = Mode.OFF;
            default -> {
                m = Mode.REQUIRED;
                error = "unknown ownclaw.sandbox.isolation '" + isolation
                        + "' (expected auto, required or off)";
            }
        }
        Path binary = null;
        if (bwrap != null && !bwrap.isBlank() && Path.of(bwrap).isAbsolute()) {
            binary = Path.of(bwrap).normalize();
        } else if (error == null && m != Mode.OFF) {
            error = "ownclaw.sandbox.bwrap-path must be an absolute path, got '" + bwrap + "'";
        }
        this.mode = m;
        this.configError = error;
        this.bwrap = binary;
        this.layout = layout;
        this.exposeEntries = expose == null ? List.of() : List.copyOf(expose);
        this.clock = clock;
        this.startedAt = clock.getAsLong();
    }

    /** A jail that is switched off, for code and tests that construct a sandbox by hand. */
    public static SkillJail off() {
        return new SkillJail("off", null, null, List.of(), System::currentTimeMillis);
    }

    /** Evaluate once at startup, so the log says what the jail will do before the first skill. */
    @PostConstruct
    public void init() {
        status();
    }

    public Status status() {
        Evaluation e = current();
        return new Status(mode, e.state(), e.reason(), bwrap == null ? null : bwrap.toString(),
                e.version(), e.roots(), e.expose(), e.checks(), e.at(),
                isolatedRuns.get(), directRuns.get(), refusedRuns.get());
    }

    /** The reason every launch is being refused right now, if it is. */
    public Optional<String> unavailable() {
        Evaluation e = current();
        return e.state() == State.REFUSING ? Optional.of(refusal(e)) : Optional.empty();
    }

    /**
     * Decide how to start {@code command} for a skill.
     *
     * @param command  the interpreter and its arguments, exactly as they would run unjailed
     * @param workDir  the only host directory the process may write (besides its private /tmp)
     * @param readOnly host paths the process may read, each at its own path -- the skill's
     *                 environment and the files this task was handed; nothing else
     */
    public Launch prepare(List<String> command, Path workDir, List<Path> readOnly) {
        Evaluation e = current();
        switch (e.state()) {
            case OFF, INACTIVE -> {
                directRuns.incrementAndGet();
                if (readySince != 0) directSinceReady.incrementAndGet();
                return Launch.direct(command);
            }
            case REFUSING -> {
                refusedRuns.incrementAndGet();
                String why = refusal(e);
                log.warn("Skill launch refused: {}", why);
                return Launch.refused(why);
            }
            case READY -> { }
        }
        Path tmp = null;
        try {
            // Recomputed per launch: a data directory created or re-linked after startup must
            // not slip out from under the tmpfs because the check that saw it ran earlier.
            List<Path> roots = hiddenRoots(layout);
            Path dir = abs(workDir);
            List<Path> ro = new ArrayList<>(authoredFiles(dir));
            String linked = linkedOutside(dir);
            if (linked != null) throw new IOException(linked);
            for (Path p : readOnly == null ? List.<Path>of() : readOnly) {
                if (p == null) continue;
                Path n = abs(p);
                if (!Files.exists(n)) {
                    log.warn("Not binding {} into the jail: it does not exist", n);
                    continue;
                }
                ro.add(n);
            }
            String leak = reachesProtected(dir, ro, roots);
            if (leak != null) throw new IOException(leak);
            tmp = Files.createTempDirectory("ownclaw-run-");
            List<String> argv = bwrapArgs(bwrap.toString(), roots, dir, ro, e.expose(), tmp, command);
            isolatedRuns.incrementAndGet();
            return new Launch(argv, true, null, tmp);
        } catch (IOException | RuntimeException ex) {
            // Never a quiet fallback to an unjailed run: this one launch fails, with the reason.
            if (tmp != null) deleteTree(tmp);
            refusedRuns.incrementAndGet();
            String why = "could not isolate this launch: " + ex.getMessage();
            log.warn("Skill launch refused: {}", why);
            return Launch.refused(why);
        }
    }

    /**
     * Tell the jail how a jailed run ended. bubblewrap reports its own failures on stderr as
     * {@code bwrap: ...} before the skill ever starts; one of those means the host may have
     * changed under a jail that passed its check, so look again -- at most once a minute, since
     * a skill can print that prefix too.
     */
    public void afterRun(Launch launch, String stderr) {
        if (launch != null && launch.jailed() && stderr != null && stderr.startsWith("bwrap:")) {
            synchronized (this) {
                recheckRequested = true;
            }
        }
    }

    /**
     * Run the evaluation now, self-check included, whatever the once-a-minute schedule says.
     * For the operator's self-test: a pass there must describe the host as it is, not as it
     * was when the jail last looked.
     */
    public Status recheck() {
        current(true);
        return status();
    }

    // ────────────────────────────── evaluation ──────────────────────────────

    private Evaluation current() {
        return current(false);
    }

    private synchronized Evaluation current(boolean force) {
        if (mode == Mode.OFF && evaluation != null) return evaluation;
        long now = clock.getAsLong();
        Signature sig = bwrap == null ? Signature.ABSENT : signature(bwrap);
        Evaluation e = evaluation;
        boolean due = force || e == null
                || !sig.equals(e.signature())
                || ((e.state() != State.READY || recheckRequested) && now - e.at() >= RECHECK_MS);
        if (due) {
            Evaluation next;
            try {
                next = evaluate(sig, now);
            } catch (RuntimeException ex) {
                // Never let a surprise escape prepare(): a caller that catches exceptions and
                // carries on (the load check registers "unverified") would turn it into a run.
                next = unavailable("the jail could not be evaluated: " + ex, sig, now, null, List.of());
            }
            recheckRequested = false;
            if (e == null || e.state() != next.state() || !Objects.equals(e.reason(), next.reason())) {
                logState(next);
            }
            evaluation = next;
            if (next.state() == State.READY && readySince == 0) readySince = next.at();
        }
        return evaluation;
    }

    private Evaluation evaluate(Signature sig, long now) {
        if (mode == Mode.OFF) {
            return new Evaluation(State.OFF, "isolation is off (ownclaw.sandbox.isolation=off)",
                    sig, now, null, List.of(), List.of(), Map.of());
        }
        if (configError != null) return unavailable(configError, sig, now, null, List.of());
        if (!sig.present()) {
            return unavailable("bubblewrap is not installed (" + bwrap + " not found); "
                    + "install it with: sudo apt install bubblewrap", sig, now, null, List.of());
        }
        String version = version();
        List<Path> roots;
        try {
            roots = hiddenRoots(layout);
        } catch (IllegalStateException ex) {
            return unavailable(ex.getMessage(), sig, now, version, List.of());
        }
        List<Bind> expose = filterExpose(exposeEntries, layout);
        SelfCheck check = selfCheck(roots, expose);
        if (!check.completed()) return unavailable(check.reason(), sig, now, version, roots);
        if (check.checks().containsValue(false)) {
            // bubblewrap ran and the jail does not hold. That is not "not installed yet", it is a
            // jail that lies, so even auto refuses rather than pretend.
            return new Evaluation(State.REFUSING, check.reason(), sig, now, version, roots, expose,
                    check.checks());
        }
        return new Evaluation(State.READY, null, sig, now, version, roots, expose, check.checks());
    }

    private Evaluation unavailable(String reason, Signature sig, long now, String version,
                                   List<Path> roots) {
        State s = mode == Mode.AUTO ? State.INACTIVE : State.REFUSING;
        return new Evaluation(s, reason, sig, now, version, roots, List.of(), Map.of());
    }

    private String refusal(Evaluation e) {
        return mode == Mode.REQUIRED
                ? "isolation required but unavailable: " + e.reason()
                : "skill isolation is installed but failed its self-check: " + e.reason();
    }

    private void logState(Evaluation e) {
        switch (e.state()) {
            case READY -> log.info("Skill isolation ON ({}): hidden {}, exposed read-only {}",
                    e.version(), e.roots(), e.expose());
            case INACTIVE -> log.warn("Skill isolation INACTIVE, skills run unisolated as before: {}",
                    e.reason());
            case REFUSING -> log.error("Skill isolation REFUSING every skill launch: {}", e.reason());
            case OFF -> log.info("Skill isolation is off (ownclaw.sandbox.isolation=off)");
        }
    }

    private static Signature signature(Path binary) {
        try {
            BasicFileAttributes a = Files.readAttributes(binary, BasicFileAttributes.class);
            if (!a.isRegularFile() || !Files.isExecutable(binary)) return Signature.ABSENT;
            return new Signature(true, a.size(), a.lastModifiedTime().toMillis(), a.fileKey());
        } catch (IOException | RuntimeException e) {
            return Signature.ABSENT;
        }
    }

    private String version() {
        try {
            ProcessBuilder pb = new ProcessBuilder(bwrap.toString(), "--version").redirectErrorStream(true);
            applyEnvironment(pb, Map.of());
            Process p = pb.start();
            p.getOutputStream().close();
            if (!p.waitFor(5, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            return new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /**
     * Run a fixed probe through exactly the argv a skill would get, against the real paths, and
     * check from the host side that the jail holds: the state is invisible, the PID namespace is
     * new, the network is the host's, the working directory is writable and reaches the host, a
     * read-only bind is read-only, /etc is readable, and /tmp is the run's own.
     */
    private SelfCheck selfCheck(List<Path> roots, List<Bind> expose) {
        Path probeDir = null;
        Path work = null;
        try {
            probeDir = createProbeDir();
            Path ro = probeDir.resolve("ro.txt");
            Files.writeString(ro, "ro", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            // The run's /tmp and the probe's captured output live side by side in one JVM temp
            // directory; only the tmp half is bound in.
            work = Files.createTempDirectory("ownclaw-jail-check-");
            Path tmp = Files.createDirectory(work.resolve("tmp"));
            Path out = work.resolve("out");
            Path err = work.resolve("err");

            List<String> command = new ArrayList<>(List.of("/bin/sh", "-c", PROBE, PROBE_NAME));
            for (Path p : layout.mustBeHidden()) {
                // By real path too: a .env or database linked outside every hidden root would be
                // hidden where it is configured and readable where it lives.
                command.add(p.toString());
                Path r = real(p);
                if (!r.equals(p)) command.add(r.toString());
            }
            List<String> argv = bwrapArgs(bwrap.toString(), roots, probeDir, List.of(ro), expose, tmp,
                    command);

            ProcessBuilder pb = new ProcessBuilder(argv).directory(probeDir.toFile());
            applyEnvironment(pb, Map.of());
            pb.redirectOutput(out.toFile());
            pb.redirectError(err.toFile());
            Process p = pb.start();
            p.getOutputStream().close();
            boolean finished = p.waitFor(SELF_CHECK_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                p.waitFor(2, TimeUnit.SECONDS);
            }
            String stdout = Files.readString(out, StandardCharsets.UTF_8);
            String stderr = Files.readString(err, StandardCharsets.UTF_8).strip();
            if (!finished) {
                return new SelfCheck(false, Map.of(), "bubblewrap self-check did not finish in "
                        + SELF_CHECK_SECONDS + " s" + (stderr.isEmpty() ? "" : ": " + stderr));
            }
            List<String> lines = stdout.lines().toList();
            if (!lines.contains("probe=done")) {
                // bubblewrap could not build the jail at all -- typically user namespaces are
                // blocked. Its own words are the most useful diagnosis there is, so keep them.
                return new SelfCheck(false, Map.of(), "bubblewrap could not start a jail (exit "
                        + p.exitValue() + "): " + (stderr.isEmpty() ? "no output" : stderr));
            }

            List<String> visible = lines.stream().filter(l -> l.startsWith("visible="))
                    .map(l -> l.substring("visible=".length())).toList();
            String pidNs = value(lines, "pidns=");
            String netNs = value(lines, "netns=");
            String jvmPid = namespace("pid");
            String jvmNet = namespace("net");

            var checks = new LinkedHashMap<String, Boolean>();
            checks.put("hidden", visible.isEmpty());
            checks.put("pidNamespace", pidNs != null && !pidNs.isBlank() && jvmPid != null
                    && !pidNs.equals(jvmPid));
            checks.put("sharedNetwork", netNs != null && netNs.equals(jvmNet));
            checks.put("workDirWritable", Files.isRegularFile(probeDir.resolve("rw.txt"),
                    LinkOption.NOFOLLOW_LINKS));
            checks.put("readOnlyBinds", !lines.contains("ro=writable")
                    && "ro".equals(Files.readString(ro, StandardCharsets.UTF_8)));
            checks.put("etcReadable", lines.contains("etc=readable"));
            checks.put("privateTmp", Files.isRegularFile(tmp.resolve("ownclaw-jail-probe.txt"),
                    LinkOption.NOFOLLOW_LINKS));

            var failed = checks.entrySet().stream().filter(c -> !c.getValue())
                    .map(Map.Entry::getKey).toList();
            String reason = null;
            if (!failed.isEmpty()) {
                reason = "self-check failed: " + String.join(", ", failed)
                        + (visible.isEmpty() ? "" : "; visible inside the jail: " + String.join(", ", visible))
                        + (stderr.isEmpty() ? "" : "; bwrap said: " + stderr);
            }
            return new SelfCheck(true, java.util.Collections.unmodifiableMap(checks), reason);
        } catch (IOException e) {
            return new SelfCheck(false, Map.of(), "bubblewrap self-check could not run: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new SelfCheck(false, Map.of(), "bubblewrap self-check was interrupted");
        } finally {
            if (probeDir != null) deleteTree(probeDir);
            if (work != null) deleteTree(work);
        }
    }

    // Inside the skills directory when it can be written, so the probe's own directory is
    // re-exposed from under a hidden root exactly as a skill's is; the JVM temp directory
    // otherwise. The dot keeps it out of every scan of generated/ and _envs/.
    private Path createProbeDir() throws IOException {
        Path skills = layout.skillsRoot();
        if (Files.isDirectory(skills) && Files.isWritable(skills)) {
            try {
                return Files.createTempDirectory(skills, ".jail-probe-");
            } catch (IOException ignored) {
                // fall through
            }
        }
        return Files.createTempDirectory("ownclaw-jail-probe-");
    }

    private static String value(List<String> lines, String prefix) {
        return lines.stream().filter(l -> l.startsWith(prefix))
                .map(l -> l.substring(prefix.length())).findFirst().orElse(null);
    }

    private static String namespace(String kind) {
        try {
            return Files.readSymbolicLink(Path.of("/proc/self/ns/" + kind)).toString();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    // ────────────────────────────── the mount model ──────────────────────────────

    /**
     * The whole bubblewrap command line. Pure: the order is the mechanism, so it is built in
     * one place and tested as data.
     * <ol>
     *   <li>new namespaces for everything but the network; die with the JVM; own session;</li>
     *   <li>the host, read-only;</li>
     *   <li>the host's /dev (serial devices keep working; permissions still apply), a private
     *       /dev/shm, a /proc for the new PID namespace, and the run's own /tmp;</li>
     *   <li>a tmpfs over every hidden root;</li>
     *   <li>then, on top of those, the working directory read-write, and the read-only binds;</li>
     *   <li>then each hidden root's tmpfs made read-only -- after the binds, so the working
     *       directory's own mount stays writable;</li>
     *   <li>{@code --} and the command, which is never parsed as a bubblewrap option.</li>
     * </ol>
     * Nothing from the environment appears here: credentials reach the skill through
     * bubblewrap's own process environment, never through its argv.
     */
    static List<String> bwrapArgs(String bwrap, List<Path> hiddenRoots, Path workDir,
                                  List<Path> readOnly, List<Bind> expose, Path tmp,
                                  List<String> command) {
        var a = new ArrayList<String>();
        a.add(bwrap);
        a.addAll(List.of("--unshare-all", "--share-net", "--die-with-parent", "--new-session"));
        a.addAll(List.of("--ro-bind", "/", "/"));
        a.addAll(List.of("--dev-bind", "/dev", "/dev"));
        a.addAll(List.of("--tmpfs", "/dev/shm"));
        a.addAll(List.of("--proc", "/proc"));
        a.addAll(List.of("--bind", tmp.toString(), "/tmp"));
        for (Path root : hiddenRoots) a.addAll(List.of("--tmpfs", root.toString()));
        a.addAll(List.of("--bind", workDir.toString(), workDir.toString()));
        for (Path p : readOnly) a.addAll(List.of("--ro-bind", p.toString(), p.toString()));
        // -try: an entry the owner listed that is absent right now is skipped, not fatal. A
        // missing file must not take the jail down with it.
        for (Bind b : expose) a.addAll(List.of("--ro-bind-try", b.source().toString(), b.dest().toString()));
        for (Path root : hiddenRoots) a.addAll(List.of("--remount-ro", root.toString()));
        a.addAll(List.of("--chdir", workDir.toString()));
        a.add("--");
        a.addAll(command);
        return a;
    }

    static List<Path> hiddenRoots(Layout l) {
        return hiddenRoots(l.cwd(), l.dataDir(), l.skillsRoot(), l.envsRoot(), l.logDir());
    }

    /**
     * The outermost directories to lay a tmpfs over: each candidate and, when it exists, its
     * real path -- a data directory symlinked elsewhere is hidden at both ends. Candidates that
     * do not exist hide nothing and are left out (bubblewrap cannot create a mount point on the
     * read-only host). Refuses a root that is '/' or holds a system directory, because hiding it
     * would blank the interpreter or the jail's own /proc, /dev or /tmp.
     *
     * @throws IllegalStateException when a root would hide the system
     */
    static List<Path> hiddenRoots(Path cwd, Path dbParent, Path generatedParent, Path envsRoot,
                                  Path logDir) {
        var all = new LinkedHashSet<Path>();
        for (Path c : new Path[] {cwd, dbParent, generatedParent, envsRoot, logDir}) {
            if (c == null) continue;
            Path n = abs(c);
            if (!Files.exists(n)) continue;
            all.add(n);
            all.add(real(n));
        }
        for (Path r : all) {
            if (r.getParent() == null) {
                throw new IllegalStateException("refusing to hide " + r
                        + ": it is the filesystem root. Run OwnClaw from its own directory.");
            }
            for (Path s : SYSTEM) {
                if (s.startsWith(r)) {
                    throw new IllegalStateException("refusing to hide " + r + ": it contains " + s
                            + ". Keep OwnClaw's data, skills and logs out of system directories.");
                }
            }
        }
        var outer = new ArrayList<Path>();
        for (Path r : all) {
            boolean covered = all.stream().anyMatch(o -> !o.equals(r) && r.startsWith(o));
            if (!covered) outer.add(r);
        }
        outer.sort(null);
        return List.copyOf(outer);
    }

    /**
     * The owner's read-only exceptions (for example a HOME dotfile a skill needs), minus any
     * that would reach the service's state: an entry equal to, inside, or containing a protected
     * path is dropped -- by its written path and by its real path -- as is anything under the
     * host's /proc, and /tmp or the JVM's temp directory as a whole, which would lay the JVM's
     * temp files over the run's own /tmp. Entries absent today are kept and bound with
     * {@code -try}.
     */
    static List<Bind> filterExpose(List<String> entries, Layout layout) {
        var guarded = new ArrayList<Path>();
        for (Path p : layout.protectedPaths()) {
            guarded.add(p);
            guarded.add(real(p));
        }
        guarded.add(Path.of("/proc"));
        Path jvmTmp = abs(Path.of(System.getProperty("java.io.tmpdir")));
        List<Path> tmps = List.of(Path.of("/tmp"), jvmTmp, real(jvmTmp));

        var out = new ArrayList<Bind>();
        for (String entry : entries == null ? List.<String>of() : entries) {
            if (entry == null || entry.isBlank()) continue;
            Path written = Path.of(entry.trim());
            if (!written.isAbsolute()) {
                log.warn("Ignoring sandbox.expose entry '{}': not an absolute path", entry);
                continue;
            }
            Path p = written.normalize();
            Path r = real(p);
            boolean overlaps = guarded.stream().anyMatch(g ->
                    g.startsWith(p) || p.startsWith(g) || g.startsWith(r) || r.startsWith(g))
                    || tmps.stream().anyMatch(t -> t.startsWith(p) || t.startsWith(r));
            if (overlaps) {
                log.warn("Ignoring sandbox.expose entry '{}': it overlaps what skills must not see", entry);
                continue;
            }
            out.add(new Bind(r, p));
        }
        return List.copyOf(out);
    }

    /**
     * The authored files of a skill directory (a direct child of generated/), all three, as
     * regular files. A missing requirements.txt is created empty: a skill that declares nothing
     * must still find it read-only, or it could write one for the JVM to install from. Anything
     * that is not a regular file -- a link planted before the jail existed -- refuses the launch.
     */
    private List<Path> authoredFiles(Path workDir) throws IOException {
        if (!layout.generated().equals(workDir.getParent())) return List.of();
        var out = new ArrayList<Path>();
        for (String name : AUTHORED) {
            Path f = workDir.resolve(name);
            if (name.equals("requirements.txt") && Files.notExists(f, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    Files.write(f, new byte[0], StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                } catch (FileAlreadyExistsException raced) {
                    // another launch made it first; checked below like any other
                }
            }
            if (!Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException(name + " in " + workDir
                        + " is not a regular file (missing, a link or a directory): tampered skill");
            }
            out.add(f);
        }
        return out;
    }

    /**
     * A regular file in the working directory that also has a name outside it. A hard link made
     * while nothing was jailed -- to the database, say -- would keep that file readable, and
     * current, through the writable bind. Nothing inside a jail can make one: a link cannot cross
     * the jail's mounts. Links whose every name is inside the directory are the skill's own
     * business. Returns why, or null.
     */
    static String linkedOutside(Path workDir) throws IOException {
        var namesInside = new HashMap<Object, Integer>();
        var links = new HashMap<Object, Integer>();
        var named = new HashMap<Object, Path>();
        Files.walkFileTree(workDir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException {
                if (!a.isRegularFile()) return FileVisitResult.CONTINUE;
                int n = (Integer) Files.getAttribute(f, "unix:nlink", LinkOption.NOFOLLOW_LINKS);
                if (n > 1) {
                    Object key = a.fileKey() != null ? a.fileKey() : f;
                    namesInside.merge(key, 1, Integer::sum);
                    links.put(key, n);
                    named.putIfAbsent(key, f);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        for (var e : links.entrySet()) {
            if (e.getValue() > namesInside.get(e.getKey())) {
                return named.get(e.getKey()) + " is a hard link to a file outside " + workDir
                        + ": tampered skill";
            }
        }
        return null;
    }

    /**
     * A bind that is, or contains, a hidden root or a protected path would put it back on top of
     * the tmpfs. Checked by written and by real path. Returns why, or null.
     */
    private String reachesProtected(Path workDir, List<Path> readOnly, List<Path> roots) {
        var guarded = new ArrayList<Path>();
        for (Path p : roots) guarded.add(p);
        for (Path p : layout.protectedPaths()) {
            guarded.add(p);
            guarded.add(real(p));
        }
        var binds = new ArrayList<Path>();
        binds.add(workDir);
        binds.addAll(readOnly);
        for (Path b : binds) {
            Path r = real(b);
            for (Path g : guarded) {
                if (g.startsWith(b) || g.startsWith(r)) {
                    return "binding " + b + " would expose " + g;
                }
            }
        }
        return null;
    }

    // ────────────────────────────── operator view ──────────────────────────────

    /** The settings that decide whether an unprivileged process may create user namespaces. */
    private static final List<String> USERNS_SYSCTLS = List.of(
            "kernel.apparmor_restrict_unprivileged_userns",
            "kernel.unprivileged_userns_clone",
            "user.max_user_namespaces");

    /**
     * The AppArmor profile that lets bubblewrap create user namespaces on an Ubuntu that
     * restricts them (the apparmor package ships it from 25.04), relative to the host root.
     */
    static final String BWRAP_PROFILE = "etc/apparmor.d/bwrap-userns-restrict";

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z").withZone(ZoneId.systemDefault());

    /**
     * What the owner needs to decide on the jail and to verify it, for the ops health check:
     * what it is doing and why, what it hides, which bubblewrap is here, what the self-check
     * saw, how many launches went which way, and the facts about this host that decide what to
     * install. Names and settings only, never the content of a file.
     */
    public Map<String, Object> health() {
        return health(Path.of("/"), home());
    }

    /** {@link #health()}, reading the host's files under {@code hostRoot} and HOME at {@code home}. */
    Map<String, Object> health(Path hostRoot, Path home) {
        Status s = status();
        Map<String, String> os = osRelease(hostRoot);
        Map<String, String> sysctls = sysctls(hostRoot);
        boolean profile = Files.isRegularFile(hostRoot.resolve(BWRAP_PROFILE));
        boolean present = bwrap != null && signature(bwrap).present();

        var m = new LinkedHashMap<String, Object>();
        m.put("mode", s.mode().name().toLowerCase(Locale.ROOT));
        m.put("state", s.state().name());
        m.put("isolated", s.state() == State.READY);
        m.put("canRunSkills", s.state() != State.REFUSING);
        // Verbatim, bubblewrap's stderr included: its own words are the diagnosis.
        m.put("reason", s.reason());
        // Worked out now, as prepare() does for every launch, so the owner can read what the
        // jail will hide before bubblewrap is even installed.
        List<String> roots = List.of();
        if (layout != null) {
            try {
                roots = hiddenRoots(layout).stream().map(Path::toString).toList();
            } catch (RuntimeException ex) {
                m.put("hiddenRootsError", ex.getMessage());
            }
        }
        m.put("hiddenRoots", roots);
        // The owner's expose entries that survived the filter; a jail that has not passed its
        // check yet exposes nothing, so this stays empty until then.
        m.put("exposed", s.exposed().stream()
                .map(b -> b.source().equals(b.dest()) ? b.dest().toString() : b.dest() + " <- " + b.source())
                .toList());

        var binary = new LinkedHashMap<String, Object>();
        binary.put("path", s.bwrap());
        binary.put("present", present);
        binary.put("version", s.version());
        m.put("bwrap", binary);

        var check = new LinkedHashMap<String, Object>();
        check.put("checkedAt", iso(s.checkedAtMillis()));
        // Empty when the probe never ran to the end; the reason then says why.
        check.put("results", s.selfCheck());
        m.put("selfCheck", check);

        var runs = new LinkedHashMap<String, Object>();
        runs.put("since", iso(startedAt));
        runs.put("isolated", s.isolatedRuns());
        runs.put("direct", s.directRuns());
        runs.put("refused", s.refusedRuns());
        long ready = readySince;
        runs.put("readySince", ready == 0 ? null : iso(ready));
        runs.put("directSinceReady", directSinceReady.get());
        m.put("runs", runs);

        var host = new LinkedHashMap<String, Object>();
        host.put("os", os.getOrDefault("PRETTY_NAME", "unknown"));
        host.put("kernel", System.getProperty("os.version"));
        host.put("sysctls", sysctls);
        host.put("bwrapProfilePresent", profile);
        m.put("host", host);

        m.put("homeDotDirs", homeDotDirs(home));
        m.put("installHint", installHint(s, present, os, sysctls, profile));
        return m;
    }

    /**
     * One line for /status, in the web chat and in Telegram. When ON it counts the launches
     * that ran unisolated since the jail was first ready: zero is the mechanical proof that
     * nothing has run outside it since.
     */
    public String statusLine() {
        Status s = status();
        return switch (s.state()) {
            case READY -> {
                long n = directSinceReady.get();
                yield "Skill isolation: ON (" + (s.version() == null ? "bubblewrap" : s.version()) + ") — "
                        + n + (n == 1 ? " unisolated launch" : " unisolated launches") + " since "
                        + WHEN.format(Instant.ofEpochMilli(readySince));
            }
            case INACTIVE -> "Skill isolation: INACTIVE, skills run unisolated — " + brief(s.reason());
            case REFUSING -> "Skill isolation: REFUSING every skill launch — " + brief(s.reason());
            case OFF -> "Skill isolation: OFF (ownclaw.sandbox.isolation=off)";
        };
    }

    /**
     * The one next step for this host, or "none". The user-namespace settings come before the
     * install command because on a host that blocks them installing bubblewrap is not enough,
     * and saying only "install it" would send the owner round the loop once for nothing.
     */
    private String installHint(Status s, boolean present, Map<String, String> os,
                               Map<String, String> sysctls, boolean profile) {
        if (mode == Mode.OFF) {
            return "none: isolation is switched off. To turn it on, remove "
                    + "OWNCLAW_SANDBOX_ISOLATION=off from .env and restart.";
        }
        if (configError != null) return "fix the setting in .env and restart: " + configError;
        if (s.state() == State.READY) {
            return mode == Mode.REQUIRED
                    ? "none: isolation is on, and required."
                    : "none: isolation is on. To refuse skills whenever it is not (fail closed), add "
                      + "OWNCLAW_SANDBOX_ISOLATION=required to .env and restart.";
        }
        String install = present ? null : installCommand(os);
        // A probe that ran to the end proves the namespaces were created, whatever the settings
        // suggest; then what is wrong is the jail, not the host.
        String blocked = s.selfCheck().isEmpty() ? usernsBlocked(sysctls, profile) : null;
        if (blocked != null) {
            return install == null ? blocked : install + " -- but that alone is not enough here: " + blocked;
        }
        if (install != null) return install;
        return "bubblewrap is installed but the jail is not working here; the reason says why, "
                + "in bubblewrap's own words where it had any.";
    }

    private String installCommand(Map<String, String> os) {
        var ids = List.of((os.getOrDefault("ID", "") + " " + os.getOrDefault("ID_LIKE", ""))
                .toLowerCase(Locale.ROOT).trim().split("\\s+"));
        if (ids.contains("debian") || ids.contains("ubuntu")) return "sudo apt install bubblewrap";
        if (ids.contains("fedora") || ids.contains("rhel")) return "sudo dnf install bubblewrap";
        return "install this distribution's bubblewrap package, so that " + bwrap + " exists";
    }

    private static String usernsBlocked(Map<String, String> sysctls, boolean profile) {
        if ("0".equals(sysctls.get("kernel.unprivileged_userns_clone"))
                || "0".equals(sysctls.get("user.max_user_namespaces"))) {
            return "unprivileged user namespaces are switched off on this host "
                    + "(kernel.unprivileged_userns_clone or user.max_user_namespaces is 0), and "
                    + "bubblewrap needs them. Turning them on is a host-wide change for the owner "
                    + "to decide; nothing is written automatically.";
        }
        if ("1".equals(sysctls.get("kernel.apparmor_restrict_unprivileged_userns")) && !profile) {
            return "kernel.apparmor_restrict_unprivileged_userns=1 and there is no /" + BWRAP_PROFILE
                    + ", so AppArmor stops bubblewrap creating user namespaces. Allowing it takes an "
                    + "AppArmor profile for bwrap or a sysctl change, and either weakens a host-wide "
                    + "restriction: the owner's decision; nothing is written automatically.";
        }
        return null;
    }

    /** NAME=value pairs from os-release under {@code root}; empty when there is none. */
    static Map<String, String> osRelease(Path root) {
        var out = new LinkedHashMap<String, String>();
        for (String name : List.of("etc/os-release", "usr/lib/os-release")) {
            Path f = root.resolve(name);
            if (!Files.isRegularFile(f)) continue;
            try {
                for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                    int eq = line.indexOf('=');
                    if (eq <= 0 || line.startsWith("#")) continue;
                    String v = line.substring(eq + 1).strip();
                    if (v.length() >= 2 && (v.charAt(0) == '"' || v.charAt(0) == '\'')
                            && v.charAt(v.length() - 1) == v.charAt(0)) {
                        v = v.substring(1, v.length() - 1);
                    }
                    out.put(line.substring(0, eq).strip(), v);
                }
            } catch (IOException | RuntimeException e) {
                // what was read so far is still worth showing
            }
            break;
        }
        return out;
    }

    /** The user-namespace sysctls under {@code root}/proc/sys: the value, "absent" or "unreadable". */
    static Map<String, String> sysctls(Path root) {
        var out = new LinkedHashMap<String, String>();
        for (String key : USERNS_SYSCTLS) {
            Path f = root.resolve("proc/sys/" + key.replace('.', '/'));
            String v;
            try {
                v = Files.readString(f, StandardCharsets.UTF_8).strip();
            } catch (java.nio.file.NoSuchFileException e) {
                v = "absent";
            } catch (IOException | RuntimeException e) {
                v = "unreadable";
            }
            out.put(key, v);
        }
        return out;
    }

    /**
     * The dot entries directly in HOME, by name only, a directory with a trailing '/'. In
     * production HOME is the service directory, which the jail hides, so this is what a jailed
     * skill loses -- ~/.ssh, ~/.config, ~/.netrc -- and what decides whether the owner needs an
     * expose entry. Never a file's content, and nothing below the top level.
     */
    static List<String> homeDotDirs(Path home) {
        if (home == null || !Files.isDirectory(home)) return List.of();
        try (var entries = Files.list(home)) {
            return entries.filter(p -> p.getFileName().toString().startsWith("."))
                    .map(p -> p.getFileName() + (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS) ? "/" : ""))
                    .sorted()
                    .limit(100)
                    .toList();
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }

    // The HOME a skill is given -- it is on the allow-list -- which is the service's own.
    private static Path home() {
        String h = System.getenv("HOME");
        if (h == null || h.isBlank()) h = System.getProperty("user.home");
        return h == null || h.isBlank() ? null : Path.of(h);
    }

    /** A reason on one line and of a length a chat message can carry; health has it whole. */
    private static String brief(String reason) {
        if (reason == null) return "no reason recorded";
        String one = reason.strip().replaceAll("\\s+", " ");
        return one.length() <= 240 ? one : one.substring(0, 239) + "…";
    }

    private static String iso(long millis) {
        return Instant.ofEpochMilli(millis).toString();
    }

    // ────────────────────────────── environment ──────────────────────────────

    /**
     * Variables a skill legitimately needs. Everything else is removed before the process
     * starts, so generated code no longer inherits the service's own secrets.
     * <p>
     * The systemd unit loads {@code /opt/ownclaw/.env}, so the JVM environment holds the
     * cloud API keys, the GitHub token, the Telegram token and the ops token. Until now
     * {@code pb.environment()} handed all of them to every skill, which meant one buggy or
     * prompt-injected skill could read them and post them anywhere.
     */
    static final Set<String> ENV_ALLOWLIST = Set.of(
            "PATH",                 // find python and system binaries
            "HOME",                 // pip, venv and several libraries expect it
            "LANG", "LC_ALL", "LC_CTYPE", "PYTHONIOENCODING",   // UTF-8 in and out
            "TZ",
            "TMPDIR", "TMP", "TEMP",
            "VIRTUAL_ENV",          // set by PythonEnvironmentService for venv skills
            "SSL_CERT_FILE", "SSL_CERT_DIR", "REQUESTS_CA_BUNDLE", "CURL_CA_BUNDLE",
            "SYSTEMROOT", "WINDIR", "COMSPEC", "PATHEXT",       // Windows needs these to run at all
            "TEMPDIR", "USERPROFILE");

    /**
     * Replaces the inherited environment with the allow-listed subset plus the variables the
     * caller explicitly asked for (the skill's declared credentials). Applied to whatever is
     * started -- bubblewrap passes its own environment to the skill, so this is the skill's.
     */
    public static void applyEnvironment(ProcessBuilder pb, Map<String, String> envVars) {
        Map<String, String> env = pb.environment();
        Map<String, String> keep = new LinkedHashMap<>();
        for (String name : ENV_ALLOWLIST) {
            String value = env.get(name);
            if (value != null) {
                keep.put(name, value);
            }
        }
        env.clear();
        env.putAll(keep);
        // Default to UTF-8 rather than the platform encoding: skills handle Czech text and
        // mojibake has been a recurring source of bad output.
        env.putIfAbsent("PYTHONIOENCODING", "utf-8");
        if (envVars != null) {
            env.putAll(envVars);
        }
    }

    // ────────────────────────────── helpers ──────────────────────────────

    static Layout layoutOf(OwnClawConfig config) {
        // The log file is found the way OpsService finds it: Spring's LOG_FILE, then the env.
        String logFile = System.getProperty("LOG_FILE");
        if (logFile == null || logFile.isBlank()) logFile = System.getenv("OWNCLAW_LOG_FILE");
        if (logFile == null || logFile.isBlank()) logFile = "./logs/ownclaw.log";
        return new Layout(Path.of(""), Path.of(config.getDatabase().getPath()),
                Path.of(config.getSkills().getGeneratedPath()), abs(Path.of(logFile)).getParent());
    }

    private static Path abs(Path p) {
        return p.toAbsolutePath().normalize();
    }

    private static Path real(Path p) {
        try {
            return p.toRealPath();
        } catch (IOException | RuntimeException e) {
            return p;
        }
    }

    /**
     * Remove a directory a jailed run could write into. Never follows a link (a skill can
     * leave one pointing anywhere), and re-opens directories the skill made unreadable so
     * their contents can go too.
     */
    static void deleteTree(Path root) {
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    try {
                        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
                    } catch (IOException | RuntimeException ignored) {
                        // best effort
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    try { Files.deleteIfExists(file); } catch (IOException ignored) { }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.deleteIfExists(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException | RuntimeException e) {
            log.warn("Could not fully remove {}: {}", root, e.getMessage());
        }
    }
}
