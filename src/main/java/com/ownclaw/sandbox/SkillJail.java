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
import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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
 * Puts every Python process OwnClaw starts on the host for skills inside an unprivileged
 * bubblewrap jail: a skill's run, its syntax and load checks, and the venv, pip and version
 * probes that build its environment. (The /setup wizard's {@code python --version} is the one
 * Python started outside it.) A skill with system_packages runs in docker or podman instead when
 * one is available ({@link ContainerSandbox}) -- refused while the jail refuses.
 * <p>
 * A skill is code the model wrote, and until now it ran as the service user with the service's
 * view of the disk: the database (the vault's master key, the JWT secret and every stored
 * credential), {@code .env}, every user's uploads and every other skill were one {@code open()}
 * away, and the network was there to send them anywhere. Stripping the environment (the
 * allow-list below) took the secrets out of the process; this takes them off the disk the
 * process can see. The host's files stay visible read-only, so a skill that reads the machine's
 * state from them -- CPU, memory, disks, network interfaces, uptime -- still works; its processes
 * and daemons do not (below). A tmpfs is laid over each outermost directory the service keeps
 * its state in, and only the skill's own directory (writable), its authored files, and what the
 * caller hands it (read-only) are put back. The jail cannot drop the service user's
 * supplementary groups, so what the groups deploy.sh adds open is hidden too: a tmpfs goes over
 * journald's store ({@link #JOURNAL}; systemd-journal), which holds the service's own console
 * output, and another over all of {@code /run} ({@link #RUN}; docker's socket), with only the
 * resolver's directory put back ({@link #resolver}). Under /run too are the system bus and each user's
 * runtime directory, where the service user's systemd user manager and session bus would start
 * a process outside the jail when asked. A fresh PID namespace removes
 * {@code /proc/<jvm>/environ} and {@code /proc/<jvm>/root}. The network is deliberately shared:
 * the property that matters -- a skill cannot read the database and post it anywhere -- comes
 * from hiding the database, and skills that talk to the LAN, mail servers or Ollama keep
 * working.
 * <p>
 * Accepted as consequences of that design:
 * <ul>
 *   <li>{@code --share-net} shares the abstract Unix-socket namespace too, so whatever on the
 *       host listens on an abstract socket is reachable;</li>
 *   <li>a fresh PID namespace, kept because it hides {@code /proc/<jvm>/environ}: a skill sees
 *       only its own processes, so ps, pgrep and psutil list the jail's few, with no error;</li>
 *   <li>/dev is the host's but for a private /dev/shm and its own /dev/mqueue, and all of /run
 *       but the resolver is gone: {@code /dev/log}, which on a systemd host links into /run, no
 *       longer reaches the system log (logger exits 0 while nothing is logged); nothing reaches
 *       the mDNS daemon (a {@code .local} name it would answer does not resolve), and there is
 *       no {@code /run/lock} or utmp;</li>
 *   <li>/dev/pts is the host's too: a skill can open a terminal its user owns -- none in
 *       production, where the service user has no terminal, but on a dev host running as the
 *       owner, the owner's own. {@code --new-session} keeps it from typing into one
 *       (TIOCSTI);</li>
 *   <li>the user namespace maps only the service user's own uid and gid, so its supplementary
 *       groups show as 65534 (nogroup) inside: a skill that looks at its group list concludes
 *       it lacks a group, while access is still decided by the real groups;</li>
 *   <li>journald's stores are hidden, so journalctl exits 0 with "-- No entries --";</li>
 *   <li>with /run gone, so are the system bus and systemd's own socket: systemctl, timedatectl,
 *       hostnamectl, loginctl, busctl, resolvectl, nmcli and the docker CLI fail;</li>
 *   <li>CUPS is still reached over loopback, where it listens there;</li>
 *   <li>loopback reaches the service's own HTTP port: a skill can call its API as any client
 *       on the LAN can.</li>
 * </ul>
 * <p>
 * Each launch walks what it binds, file by file ({@link #linkedOutside}), and when the run ends
 * scans the process table for what is left of its jail. Measured on a desktop host with a warm
 * disk cache: 5-10 µs a file -- 40-75 ms for a Python package tree of 7,000-8,000 files -- and
 * about 30 ms for a table of 430 processes.
 * <p>
 * Three modes, from the root-owned {@code .env} only ({@code OWNCLAW_SANDBOX_ISOLATION}), so no
 * skill can switch it off:
 * <ul>
 *   <li>{@code auto} (default) -- jail every launch when bubblewrap is installed and passes its
 *       self-check. While bubblewrap is absent or cannot start a jail, skills run unjailed, with
 *       one warning -- unless the jail was ready earlier in this process: then that refuses
 *       every launch, as a self-check that runs but fails always does.</li>
 *   <li>{@code required} -- if the jail is not working, refuse the launch. Fail closed.</li>
 *   <li>{@code off} -- development and Windows.</li>
 * </ul>
 * While the jail is ready, a single launch it cannot isolate -- a tampered skill directory, a
 * hard link out of a bind, a bind that reaches protected state, a directory it cannot list or
 * enter -- is refused, never run unjailed.
 */
@Component
public class SkillJail {

    private static final Logger log = LoggerFactory.getLogger(SkillJail.class);

    /** How often a jail that is not READY looks again. */
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
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) refuseHardLink(file);
        Files.writeString(file, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
    }

    /**
     * One of a skill's {@link #AUTHORED} files, or null when it is absent: the one way the JVM
     * reads them. The directory is the skill's own and writable by it, so anything but a regular
     * file is refused rather than read -- a link is not followed (one left pointing at .env would
     * put it in front of the model or the owner's browser), and a FIFO would hang the read -- and
     * the open itself refuses a link that appears after the check. So is a regular file with a
     * second name (see {@link #refuseHardLink}).
     */
    public static String readAuthored(Path file) throws IOException {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return null;
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(file.getFileName() + " is not a regular file (a link or something else): "
                    + "tampered skill, not reading it");
        }
        refuseHardLink(file);
        try (var in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * An authored file that is a hard link -- made while nothing was jailed, to .env, say -- is a
     * regular file to every other check, and reading it would hand .env to the model, writing it
     * would overwrite .env. Nothing inside a jail can make one (see {@link #linkedOutside}).
     * Never refused where the file system keeps no link count the JVM can read ({@link #nlink}).
     */
    private static void refuseHardLink(Path file) throws IOException {
        if (nlink(file) > 1) {
            throw new IOException(file.getFileName() + " has another name (a hard link): tampered skill");
        }
    }

    /**
     * How many names {@code file} has, read without following a link: 1 where the file system
     * keeps no count the JVM can read (Windows), as if every file had only its own.
     */
    public static int nlink(Path file) throws IOException {
        if (!file.getFileSystem().supportedFileAttributeViews().contains("unix")) return 1;
        return (Integer) Files.getAttribute(file, "unix:nlink", LinkOption.NOFOLLOW_LINKS);
    }

    /**
     * Write a file the service owns into a directory a skill can write -- its own, or an
     * environment its pip writes. Whatever is at the name goes first -- a link is removed, not
     * followed -- and the file is then created new, which fails rather than follows if something
     * reappears in between. A planted link cannot turn the write into one over .env or the
     * database. Not for the {@link #AUTHORED} files: see {@link #writeAuthored}.
     */
    public static void writeFresh(Path file, String content) throws IOException {
        Files.deleteIfExists(file);
        Files.writeString(file, content, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
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
     * READY: every launch is jailed. INACTIVE: launches run unjailed (auto only, and only until
     * the jail is first ready). REFUSING: every launch is refused. OFF: switched off by
     * configuration.
     */
    public enum State { READY, INACTIVE, REFUSING, OFF }

    /**
     * Where the service keeps what a skill must not see, all absolute and normalized.
     *
     * @param cwd       the service's working directory; in production it holds .env, the repo,
     *                  backups and the JDK, and is the one root that covers all the service's
     *                  others -- a backups/ or repo/ linked elsewhere is hidden at its real path
     *                  too
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
        Path backups() { return cwd.resolve("backups"); }
        Path repo() { return cwd.resolve("repo"); }

        /** What the self-check proves invisible from inside the jail. */
        List<Path> mustBeHidden() {
            var out = new ArrayList<>(List.of(database, dotEnv(), uploads(), generated, envsRoot()));
            if (logDir != null) out.add(logDir);
            out.addAll(RUN_PROBES);
            // A tmpfs leaves the directory it covers in place -- journald's stores, and a backups/
            // or repo/ linked elsewhere, are roots of their own: what was in them, as the host has
            // it now, is what must be gone.
            for (Path store : JOURNALS) out.addAll(entries(store));
            out.addAll(entries(backups()));
            out.addAll(entries(repo()));
            return out;
        }

        /**
         * What neither the owner's expose list nor a caller's bind may contain, by written and
         * by real path: the service's state, journald's stores, and what the self-check proves
         * gone from /run.
         */
        List<Path> guarded() {
            var paths = new ArrayList<>(List.of(dataDir(), uploads(), database, dotEnv(), skillsRoot(),
                    generated, envsRoot(), backups(), repo()));
            if (logDir != null) paths.add(logDir);
            paths.addAll(JOURNALS);
            paths.addAll(RUN_PROBES);
            var out = new ArrayList<Path>();
            for (Path p : paths) {
                out.add(p);
                out.add(real(p));
            }
            return out;
        }
    }

    /** A read-only bind of a host path at a (possibly different) path inside the jail. */
    public record Bind(Path source, Path dest) {}

    /**
     * What to start, and how. Exactly one of these holds: {@code refusal} is set and nothing may
     * be started; or {@code argv} is the command to start -- wrapped in bubblewrap when
     * {@code jailed}, unchanged otherwise. Close it when the process is gone: that ends what is
     * left of its jail and removes the run's private /tmp.
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
            if (tmpDir == null) return;
            if (jailEnded(tmpDir.toString())) {
                deleteTree(tmpDir);
            } else {
                log.warn("Not removing {}: a process of its jail is still running", tmpDir);
            }
        }
    }

    /**
     * Whether every process of the jail whose /tmp is {@code runTmp} is gone, killing what is left
     * and waiting up to 5 s. bubblewrap returns as soon as the command it started exits, while
     * anything the command left running goes on. Its pid 1 in the jail -- a fork of it, so it
     * carries the same argv, this run's /tmp included -- is killed once bubblewrap is gone
     * (--die-with-parent), and the kernel ends every process in the jail's PID namespace before
     * that pid 1 is gone itself: nothing of the run outlives it. But it, and what the command
     * left running, do outlive bubblewrap's return -- usually by a millisecond or so, and a
     * process left running can still write {@code runTmp} in that time. A write racing
     * {@link #deleteTree} can leave part of the tree, or block the delete on a FIFO; so the
     * delete waits for that pid 1 instead. What is killed here is what carries that fresh
     * temporary path in its argv: this run's bubblewrap and its pid 1, or a process that put it
     * there itself, which only makes this wait longer.
     */
    private static boolean jailEnded(String runTmp) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (true) {
            List<ProcessHandle> left = ProcessHandle.allProcesses()
                    .filter(p -> p.info().arguments().map(a -> List.of(a).contains(runTmp)).orElse(false))
                    .toList();
            if (left.isEmpty()) return true;
            if (System.nanoTime() > deadline) return false;
            left.forEach(ProcessHandle::destroyForcibly);
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    /** A snapshot for health and tests. */
    public record Status(Mode mode, State state, String reason, String bwrap, String version,
                         List<Bind> exposed, Map<String, Boolean> selfCheck,
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
    private final long startedAt;
    // The first moment the jail was READY in this process; from then on auto never runs a
    // launch unjailed again (see notStarting()).
    private volatile long readySince;

    private Evaluation evaluation;

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

    /** Evaluate once at startup, so the log says what the jail will do before the first skill. */
    @PostConstruct
    public void init() {
        status();
    }

    public Status status() {
        Evaluation e = current();
        return new Status(mode, e.state(), e.reason(), bwrap == null ? null : bwrap.toString(),
                e.version(), e.expose(), e.checks(), e.at(),
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
            List<Path> ro = checkBinds(dir, readOnly, layout);
            tmp = Files.createTempDirectory("ownclaw-run-");
            List<String> argv = bwrapArgs(bwrap.toString(), roots, resolver(RESOLV_CONF), dir, ro,
                    e.expose(), tmp, command);
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
                || (e.state() != State.READY && now - e.at() >= RECHECK_MS);
        // Evaluated under the lock: while a recheck runs (up to ~17 s -- bwrap --version 5 s, the
        // probe 10 s, its kill 2 s) every launch waits for it.
        if (due) {
            Evaluation next;
            try {
                next = evaluate(sig, now);
            } catch (RuntimeException ex) {
                // Never let a surprise escape prepare(): a caller that catches exceptions and
                // carries on (the load check registers "unverified") would turn it into a run.
                next = notStarting("the jail could not be evaluated: " + ex, sig, now, null, List.of());
            }
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
        if (configError != null) return notStarting(configError, sig, now, null, List.of());
        if (!sig.present()) {
            return notStarting("bubblewrap is not installed (" + bwrap + " not found); "
                    + "install it with: sudo apt install bubblewrap, then sudo systemctl restart ownclaw",
                    sig, now, null, List.of());
        }
        String version = version();
        List<Path> roots;
        try {
            roots = hiddenRoots(layout);
        } catch (IllegalStateException ex) {
            return notStarting(ex.getMessage(), sig, now, version, List.of());
        }
        List<Bind> expose = filterExpose(exposeEntries, layout);
        SelfCheck check = selfCheck(roots, expose);
        if (!check.completed()) return notStarting(check.reason(), sig, now, version, roots);
        if (check.checks().containsValue(false)) {
            // bubblewrap ran and an item failed. Every item is required, the functional ones (a
            // writable working directory, a readable /etc, the shared network) as much as the
            // hiding ones: that is not "not installed yet" but a jail that does not work as it
            // must, so even auto refuses rather than run skills in it or around it.
            return new Evaluation(State.REFUSING, check.reason(), sig, now, version, roots, expose,
                    check.checks());
        }
        return new Evaluation(State.READY, null, sig, now, version, roots, expose, check.checks());
    }

    /**
     * No jail could be started. Under auto that runs skills unjailed only until the jail has been
     * READY in this process: after that, a jail that stops starting -- bubblewrap removed, a
     * probe that times out or cannot start one -- refuses, rather than quietly running every
     * later launch outside it. Taking bubblewrap away on purpose then takes a restart or
     * {@code off}.
     */
    private Evaluation notStarting(String reason, Signature sig, long now, String version,
                                   List<Path> roots) {
        State s = mode == Mode.AUTO && readySince == 0 ? State.INACTIVE : State.REFUSING;
        return new Evaluation(s, reason, sig, now, version, roots, List.of(), Map.of());
    }

    private String refusal(Evaluation e) {
        if (mode == Mode.REQUIRED) return "isolation required but unavailable: " + e.reason();
        // auto refuses a self-check that ran and failed, or a jail that was on and cannot start now
        return e.checks().isEmpty()
                ? "skill isolation was on and cannot start a jail now: " + e.reason()
                : "skill isolation is installed but failed its self-check: " + e.reason();
    }

    private void logState(Evaluation e) {
        switch (e.state()) {
            case READY -> log.info("Skill isolation ON ({}): hidden {}, exposed read-only {}",
                    e.version(), e.roots(), e.expose());
            case INACTIVE -> log.warn("Skill isolation INACTIVE, skills run unjailed until bubblewrap "
                    + "can start a jail: {}", e.reason());
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
            List<String> argv = bwrapArgs(bwrap.toString(), roots, resolver(RESOLV_CONF), probeDir,
                    List.of(ro), expose, tmp, command);

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
    // otherwise. It is made in skills/ itself, beside generated/ and _envs/, so no scan of
    // either sees it.
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
     *   <li>the host's /dev (serial devices keep working; permissions still apply), its own
     *       /dev/mqueue, a private /dev/shm, a /proc for the new PID namespace;</li>
     *   <li>an empty /run, and the resolver's directory put back into it, read-only;</li>
     *   <li>the run's own /tmp;</li>
     *   <li>a tmpfs over every hidden root;</li>
     *   <li>then, on top of those, the working directory read-write, and the read-only binds;</li>
     *   <li>then each hidden root's tmpfs, and /run's, made read-only -- after the binds, so the
     *       working directory's own mount stays writable;</li>
     *   <li>{@code --} and the command, which is never parsed as a bubblewrap option.</li>
     * </ol>
     * Nothing from the environment appears here, and no credential: those reach the skill on
     * its stdin.
     *
     * @param resolver what {@link #resolver} found, or null
     */
    static List<String> bwrapArgs(String bwrap, List<Path> hiddenRoots, Path resolver, Path workDir,
                                  List<Path> readOnly, List<Bind> expose, Path tmp,
                                  List<String> command) {
        var a = new ArrayList<String>();
        a.add(bwrap);
        a.addAll(List.of("--unshare-all", "--share-net", "--die-with-parent", "--new-session"));
        a.addAll(List.of("--ro-bind", "/", "/"));
        a.addAll(List.of("--dev-bind", "/dev", "/dev"));
        a.addAll(List.of("--mqueue", "/dev/mqueue"));
        a.addAll(List.of("--tmpfs", "/dev/shm"));
        a.addAll(List.of("--proc", "/proc"));
        a.addAll(List.of("--tmpfs", RUN.toString()));
        if (resolver != null) a.addAll(List.of("--ro-bind-try", resolver.toString(), resolver.toString()));
        a.addAll(List.of("--bind", tmp.toString(), "/tmp"));
        for (Path root : hiddenRoots) a.addAll(List.of("--tmpfs", root.toString()));
        a.addAll(List.of("--bind", workDir.toString(), workDir.toString()));
        for (Path p : readOnly) a.addAll(List.of("--ro-bind", p.toString(), p.toString()));
        // -try: an entry the owner listed that is absent right now is skipped, not fatal. A
        // missing file must not take the jail down with it.
        for (Bind b : expose) a.addAll(List.of("--ro-bind-try", b.source().toString(), b.dest().toString()));
        for (Path root : hiddenRoots) a.addAll(List.of("--remount-ro", root.toString()));
        a.addAll(List.of("--remount-ro", RUN.toString()));
        a.addAll(List.of("--chdir", workDir.toString()));
        a.add("--");
        a.addAll(command);
        return a;
    }

    /**
     * Hidden whole, the resolver aside. It holds the host daemons' sockets -- docker's, rootful
     * podman's and containerd's, the system bus, journald's -- and each user's runtime directory,
     * with the service user's systemd user manager and session bus in it, either of which starts
     * a process as the service user outside any jail when asked.
     */
    static final Path RUN = Path.of("/run");

    /**
     * What the self-check proves gone from /run: docker's socket (deploy.sh puts the service user
     * in the docker group when docker runs the containers), the system bus, and the users'
     * runtime directories. A host without one passes that item trivially.
     */
    static final List<Path> RUN_PROBES = List.of(Path.of("/run/docker.sock"),
            Path.of("/run/dbus/system_bus_socket"), Path.of("/run/user"));

    static final Path RESOLV_CONF = Path.of("/etc/resolv.conf");

    /**
     * What the jail's empty /run gets back, so names still resolve: the directory
     * {@code resolvConf} really lives in when that is under /run -- systemd-resolved's,
     * NetworkManager's, resolvconf's. The directory rather than the file, because the file is
     * usually replaced, not rewritten, and a bind of it would keep the old one; resolved keeps
     * its socket there too. The file alone when it lies directly in /run, which must not come back whole. Null
     * when it is not under /run: then /etc has it, and /etc is visible.
     */
    static Path resolver(Path resolvConf) {
        Path conf = real(resolvConf);
        Path dir = conf.getParent();
        if (dir == null || !dir.startsWith(RUN)) return null;
        return dir.equals(RUN) ? conf : dir;
    }

    /**
     * journald's persistent store. The unit sends the service's console output to the journal,
     * and deploy.sh puts the service user in systemd-journal, which reads all of it: a group the
     * jail cannot take away, so the store is hidden as the service's own state is.
     */
    static final Path JOURNAL = Path.of("/var/log/journal");

    /** Both of journald's stores; the volatile one goes with /run. */
    static final List<Path> JOURNALS = List.of(JOURNAL, RUN.resolve("log/journal"));

    /** What is directly in {@code dir}: nothing when it is absent or cannot be listed. */
    private static List<Path> entries(Path dir) {
        try (var s = Files.list(dir)) {
            return s.toList();
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }

    static List<Path> hiddenRoots(Layout l) {
        return hiddenRoots(l.cwd(), l.dataDir(), l.skillsRoot(), l.envsRoot(), l.logDir(), l.backups(),
                l.repo(), JOURNAL);
    }

    /**
     * The outermost directories to lay a tmpfs over: each candidate and, when it exists, its
     * real path -- a data directory symlinked elsewhere is hidden at both ends. Candidates that
     * do not exist hide nothing and are left out (bubblewrap cannot create a mount point on the
     * read-only host); so are nulls. Refuses a root that is '/' or holds a system directory,
     * because hiding it would blank the interpreter or the jail's own /proc, /dev or /tmp.
     *
     * @throws IllegalStateException when a root would hide the system
     */
    static List<Path> hiddenRoots(Path... candidates) {
        var all = new LinkedHashSet<Path>();
        for (Path c : candidates) {
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
     * that would reach what the jail hides: an entry equal to, inside, or containing a
     * {@link Layout#guarded} path is dropped -- by its written path and by its real path -- as
     * is anything under the host's /proc, and /tmp or the JVM's temp directory as a whole, which
     * would lay the JVM's temp files over the run's own /tmp. Entries absent today are kept and
     * bound with {@code -try}.
     */
    static List<Bind> filterExpose(List<String> entries, Layout layout) {
        var guarded = new ArrayList<>(layout.guarded());
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
     * The checks a skill's binds pass before anything is started, jailed or in a container, and
     * the read-only binds they leave: a skill directory's authored files, then each of
     * {@code readOnly} that exists (a missing one is logged and left out). Throws, with the
     * reason, at the first of: a bind that is a link, or is reached through a link below
     * skills/; a work directory whose authored files are not regular files; a hard link out of
     * any bind; a bind that is, or holds, a guarded path.
     *
     * @param workDir  the one directory the process may write
     * @param readOnly host paths the process may read, each at its own path; may be null
     */
    static List<Path> checkBinds(Path workDir, List<Path> readOnly, Layout l) throws IOException {
        Path dir = abs(workDir);
        // Before anything is created in it: requirements.txt made through a linked directory
        // would land wherever the link points.
        refuseLink(dir, l.skillsRoot());
        List<Path> ro = new ArrayList<>(l.generated().equals(dir.getParent()) ? authoredFiles(dir) : List.of());
        String linked = linkedOutside(dir);
        if (linked != null) throw new IOException(linked);
        for (Path p : readOnly == null ? List.<Path>of() : readOnly) {
            if (p == null) continue;
            Path n = abs(p);
            refuseLink(n, l.skillsRoot());
            if (!Files.exists(n)) {
                log.warn("Not binding {}: it does not exist", n);
                continue;
            }
            // A read-only bind shows a hard link's target as well as the writable one does:
            // a handed upload or an environment file linked to the database.
            linked = linkedOutside(n);
            if (linked != null) throw new IOException(linked);
            ro.add(n);
        }
        String leak = reachesProtected(dir, ro, l);
        if (leak != null) throw new IOException(leak);
        return ro;
    }

    /**
     * A bind source that is a link, or that is reached through one below {@code skills}, is
     * refused: a bind mount -- bubblewrap's, or a container's -- takes whatever a link leads to,
     * while {@link #linkedOutside} walks nothing through one and the protected-path test would
     * see only where it leads. A link there was planted -- the service makes none -- by a skill
     * that ran unjailed, before the jail existed, and can lead to the database, another skill, or
     * into backups/ or logs/, whose insides that test lets through because a skill's own
     * directory is inside generated/ in just the same way.
     * <p>
     * A link above skills/, or in a parent of a bind elsewhere, is followed: that is the owner's
     * layout -- the data directory on another disk, say -- which no skill can write, and every
     * check here works on where it leads. So does the mount: what is checked is what is bound.
     */
    static void refuseLink(Path p, Path skills) throws IOException {
        if (Files.isSymbolicLink(p)) throw new IOException(p + " is a link: tampered skill");
        if (p.startsWith(skills) && !real(p).equals(real(skills).resolve(skills.relativize(p)))) {
            throw new IOException(p + " is reached through a link below " + skills + ": tampered skill");
        }
    }

    /**
     * The authored files of a skill directory, all three, as regular files, for a read-only bind
     * each. A missing requirements.txt is created empty: a skill that declares nothing must still
     * find it read-only, or it could write one for the JVM to install from. Anything that is not
     * a regular file -- a link planted before the jail existed -- refuses the launch.
     */
    static List<Path> authoredFiles(Path workDir) throws IOException {
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
     * A regular file under {@code root} -- a bind's source, a directory or a single file -- that
     * also has a name outside it. A hard link made while nothing was jailed -- to the database,
     * say -- would keep that file readable, and current, through the bind. Nothing inside a jail
     * can make one: a link cannot cross the jail's mounts. Links whose every name is under root
     * are the skill's own business. Returns why, or null -- always null where the file system
     * keeps no link count the JVM can read (Windows, where a container run still checks its
     * binds): every file there has one name ({@link #nlink}), so there is nothing to find, and
     * nothing is walked.
     * <p>
     * It changes nothing, and reads each name's attributes without following a link. A second
     * run of the same skill, writing the tree while this walks, can still swap a directory for
     * a link between the walk reading it and listing it: the walk then lists, and reads the
     * attributes of, what lies where the link leads. So no name the walk finds goes into the
     * answer, which reaches the model; the log has it, for the owner. That second run is jailed,
     * so it cannot make a hard link out, and its own launch's walk found none to move out of
     * this one's way.
     * <p>
     * A name gone since its directory was listed -- another run of the same skill removing its
     * {@code _runner_} script -- is skipped; root itself must exist. A directory the service user
     * cannot list or enter as it is -- a skill's {@code os.makedirs('cache', mode=0o300)} -- is
     * the answer, since what it holds cannot be checked. It is not opened to find out: that
     * would be a chmod by its path, which follows a link.
     */
    static String linkedOutside(Path root) throws IOException {
        if (!root.getFileSystem().supportedFileAttributeViews().contains("unix")) return null;
        var namesInside = new HashMap<Object, Integer>();
        var links = new HashMap<Object, Integer>();
        var named = new HashMap<Object, Path>();
        var pending = new ArrayDeque<Path>(List.of(root));
        while (!pending.isEmpty()) {
            Path p = pending.pop();
            try {
                var a = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (a.isDirectory()) {
                    pending.addAll(listed(p));
                } else if (a.isRegularFile()) {
                    int names = nlink(p);
                    if (names > 1) {
                        Object key = a.fileKey() != null ? a.fileKey() : p;
                        namesInside.merge(key, 1, Integer::sum);
                        links.put(key, names);
                        named.putIfAbsent(key, p);
                    }
                }
            } catch (NoSuchFileException gone) {
                if (p.equals(root)) throw gone;
            } catch (AccessDeniedException denied) {
                log.warn("Refusing a bind of {}: {} cannot be listed or entered", root, p);
                return (p.equals(root) ? root.toString() : "a directory under " + root)
                        + " cannot be listed or entered, so what it holds cannot be checked";
            }
        }
        for (var e : links.entrySet()) {
            if (e.getValue() > namesInside.get(e.getKey())) {
                Path file = named.get(e.getKey());
                // A bind of the file itself: outside it is any other name at all.
                if (file.equals(root)) {
                    return root + " is a hard link (another name outside its bound path): tampered skill";
                }
                log.warn("Refusing a bind of {}: {} has another name outside it", root, file);
                return "a file under " + root + " has another name outside it (a hard link): tampered skill";
            }
        }
        return null;
    }

    /** The entries of a directory; denied when it cannot be listed or entered as it is. */
    private static List<Path> listed(Path dir) throws IOException {
        if (!Files.isReadable(dir) || !Files.isExecutable(dir)) {
            // Gone since it was listed is not unreadable: skipped, as any other name gone is.
            if (Files.notExists(dir, LinkOption.NOFOLLOW_LINKS)) throw new NoSuchFileException(dir.toString());
            throw new AccessDeniedException(dir.toString());
        }
        try (var entries = Files.list(dir)) {
            return entries.toList();
        }
    }

    /**
     * A bind that is, or contains, a {@link Layout#guarded} path would put it back on top of the
     * tmpfs over it -- or, in a container, simply show it. Checked by written and by real path.
     * Returns why, or null.
     */
    private static String reachesProtected(Path workDir, List<Path> readOnly, Layout l) {
        List<Path> guarded = l.guarded();
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

    /**
     * What the owner needs to decide on the jail and to verify it, for the ops health check:
     * what it is doing and why, what it hides, which bubblewrap is here, what the self-check
     * saw, how many launches went which way, and the host settings that can stop bubblewrap
     * starting a jail. Names and settings only, never the content of a file.
     */
    public Map<String, Object> health() {
        return health(Path.of("/"), home());
    }

    /** {@link #health()}, reading the host's settings under {@code hostRoot} and HOME at {@code home}. */
    Map<String, Object> health(Path hostRoot, Path home) {
        Status s = status();
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
        try {
            roots = hiddenRoots(layout).stream().map(Path::toString).toList();
        } catch (RuntimeException ex) {
            m.put("hiddenRootsError", ex.getMessage());
        }
        m.put("hiddenRoots", roots);
        // The owner's expose entries that survived the filter, as bound by the last self-check
        // that ran to the end (READY, or REFUSING on a failed item); empty while none has.
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
        runs.put("counts", "launches of any Python the service starts through the jail: skill runs, their "
                + "syntax and load checks, venv, pip and version probes");
        runs.put("since", iso(startedAt));
        runs.put("isolated", s.isolatedRuns());
        runs.put("direct", s.directRuns());
        runs.put("refused", s.refusedRuns());
        long ready = readySince;
        runs.put("readySince", ready == 0 ? null : iso(ready));
        m.put("runs", runs);

        // What can stop bubblewrap starting a jail here: on Ubuntu 24.04, AppArmor.
        var host = new LinkedHashMap<String, Object>();
        host.put("sysctls", sysctls);
        host.put("bwrapProfilePresent", profile);
        m.put("host", host);

        m.put("homeDotDirs", homeDotDirs(home));
        m.put("installHint", installHint(s, present, sysctls, profile));
        return m;
    }

    /**
     * One line for /status, in the web chat and in Telegram. Any user can ask for it, so it gives
     * the state and not the reason, which can name the service's paths: the owner reads that in
     * /api/ops/health.
     */
    public String statusLine() {
        Status s = status();
        return switch (s.state()) {
            case READY -> "Skill isolation: ON (" + (s.version() == null ? "bubblewrap" : s.version()) + ")";
            case INACTIVE -> "Skill isolation: INACTIVE, skills run unjailed — why: /api/ops/health";
            case REFUSING -> "Skill isolation: REFUSING every skill launch — why: /api/ops/health";
            case OFF -> "Skill isolation: OFF (ownclaw.sandbox.isolation=off)";
        };
    }

    /**
     * The one next step for this host, or "none". The user-namespace settings come before the
     * install command because on a host that blocks them installing bubblewrap is not enough,
     * and saying only "install it" would send the owner round the loop once for nothing.
     */
    private String installHint(Status s, boolean present, Map<String, String> sysctls, boolean profile) {
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
        // Then a restart: it ends whatever an earlier unjailed run left running outside any jail.
        String install = present ? null : "sudo apt install bubblewrap, then sudo systemctl restart ownclaw";
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

    private static String usernsBlocked(Map<String, String> sysctls, boolean profile) {
        if ("0".equals(sysctls.get("kernel.unprivileged_userns_clone"))
                || "0".equals(sysctls.get("user.max_user_namespaces"))) {
            return "unprivileged user namespaces are switched off on this host, and bubblewrap needs "
                    + "them. Turning them on is a host-wide change: the owner's decision; nothing is "
                    + "written automatically.";
        }
        if ("1".equals(sysctls.get("kernel.apparmor_restrict_unprivileged_userns")) && !profile) {
            return "kernel.apparmor_restrict_unprivileged_userns=1 and there is no /" + BWRAP_PROFILE
                    + ", so AppArmor stops bubblewrap creating user namespaces. Allowing it takes an "
                    + "AppArmor profile for bwrap or a sysctl change, and either weakens a host-wide "
                    + "restriction: the owner's decision; nothing is written automatically.";
        }
        return null;
    }

    /** The user-namespace sysctls under {@code root}/proc/sys: the value, "absent" or "unreadable". */
    static Map<String, String> sysctls(Path root) {
        var out = new LinkedHashMap<String, String>();
        for (String key : USERNS_SYSCTLS) {
            Path f = root.resolve("proc/sys/" + key.replace('.', '/'));
            String v;
            try {
                v = Files.readString(f, StandardCharsets.UTF_8).strip();
            } catch (NoSuchFileException e) {
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
     * service sets itself: the skill's environment (PATH or PYTHONPATH) and the encoding.
     * Applied to whatever is started -- bubblewrap passes its own environment to the skill, so
     * this is the skill's, and it is bubblewrap's, which runs outside the jail. A skill's
     * credentials are never here: they come on the skill's stdin (DynamicSkill.CREDENTIALS).
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

    /** How deep {@link #deleteTree} goes below its root; each level holds an open directory. */
    static final int DELETE_DEPTH = 1000;

    /**
     * Remove a directory tree a jailed run wrote, as far as it can be: the one recursive delete
     * for such trees. Returns whether {@code root} is gone. The root is opened by its path, once.
     * Everything below it is read, opened and removed by name, relative to its parent's open
     * directory, and never through a link: a link is unlinked, not entered, and every open below
     * the root refuses one. Each directory is made u+rwx before it is listed, since the run may
     * have left it unwritable: the root by its path, the rest through their parent without
     * following a link, which the JDK does by opening them. So one below the root that the
     * service user cannot read -- a run's {@code os.makedirs('cache', mode=0o300)} -- stays
     * closed and is left, as is anything more than {@link #DELETE_DEPTH} directories down. What
     * is left is logged.
     * <p>
     * So a process that writes the tree while this runs cannot turn the delete on anything
     * outside it. That holds by construction -- every open below the root is O_NOFOLLOW,
     * relative to its parent's descriptor -- and is not unit-tested: no test can time a swap
     * between the type being read and the open. Such a writer can still get in the delete's
     * way. Whatever it swaps in for a directory after its type was read is opened read-only (and
     * made u+rwx) before it is left -- a link fails to open -- and a FIFO put at a directory's
     * name blocks the delete until something opens it for writing, which may be never. The
     * callers delete a tree once nothing should be writing it: a run's /tmp once its jail has
     * ended ({@link Launch#close}); the self-check's own directories, which only its fixed probe
     * writes; a probe's or syntax check's scratch directory, written by system Python alone; a
     * container build's context, which only the JVM writes; a skill's directory and environments
     * once no run of it is in flight ({@code SkillManager.deleteSkill}); and environments whose
     * skill is gone (the prune). Two cases can still be writing: a load check or pip run of a
     * skill of the same name being created or updated at that moment, and a run whose caller
     * looked the skill up before it was unregistered and starts after the last check that none
     * was in flight. Either can leave part of the tree behind, or block the delete.
     * <p>
     * Where the file system offers no {@link SecureDirectoryStream} -- Windows, where there is
     * no jail either -- each entry is removed by its path, as a root of its own.
     */
    public static boolean deleteTree(Path root) {
        try {
            if (Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                openForOwner(Files.getFileAttributeView(root, PosixFileAttributeView.class));
                try (var dir = Files.newDirectoryStream(root)) {
                    if (dir instanceof SecureDirectoryStream<Path> secure) {
                        empty(secure, 1);
                    } else {
                        for (Path entry : dir) deleteTree(entry);
                    }
                }
            }
            Files.deleteIfExists(root);
        } catch (IOException | RuntimeException e) {
            // best effort: what is left is logged below
        }
        boolean gone = !Files.exists(root, LinkOption.NOFOLLOW_LINKS);
        if (!gone) log.warn("Could not fully remove {}", root);
        return gone;
    }

    /** Remove what is in the open directory {@code dir}, {@code depth} levels below the root. */
    private static void empty(SecureDirectoryStream<Path> dir, int depth) {
        for (Path entry : dir) {
            Path name = entry.getFileName();
            try {
                var view = dir.getFileAttributeView(name, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
                if (!view.readAttributes().isDirectory()) {
                    dir.deleteFile(name);
                    continue;
                }
                if (depth >= DELETE_DEPTH) {
                    log.warn("Not removing {}: more than {} directories down", entry, DELETE_DEPTH);
                    continue;
                }
                openForOwner(view);
                SecureDirectoryStream<Path> sub;
                try {
                    sub = dir.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS);
                } catch (IOException e) {
                    log.warn("Not removing {}: it cannot be opened (unreadable, or no longer the "
                            + "directory it was) ({})", entry, e.toString());
                    continue;
                }
                try (sub) {
                    empty(sub, depth + 1);
                }
                dir.deleteDirectory(name);
            } catch (IOException | RuntimeException e) {
                // gone meanwhile, or not empty: deleteTree reports what is left
            }
        }
    }

    // u+rwx, best effort: a directory still closed is found out by the open that follows. Null
    // where the file system has no POSIX permissions.
    private static void openForOwner(PosixFileAttributeView dir) {
        if (dir == null) return;
        try {
            var perms = new HashSet<>(dir.readAttributes().permissions());
            perms.addAll(List.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE));
            dir.setPermissions(perms);
        } catch (IOException | RuntimeException e) {
            // not the service user's, or gone
        }
    }
}
