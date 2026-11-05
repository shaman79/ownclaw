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
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.stream.Stream;

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
 * journald's store ({@link #HOST_STORES}; systemd-journal), which holds the service's own console
 * output, and another over all of {@code /run} ({@link #RUN}; docker's socket), with only the
 * resolver's directory put back ({@link #resolver}). Under /run too are the system bus and each user's
 * runtime directory, where the service user's systemd user manager and session bus would start
 * a process outside the jail when asked. The crash dumps are hidden as well
 * ({@link #HOST_STORES}): one of a process of the service user's is readable by it, and holds
 * that process's memory. A skill's /tmp is its account's, shared by that account's skills and
 * kept between their runs and across restarts, and the same directory is its /var/tmp.
 * A fresh PID namespace removes
 * {@code /proc/<jvm>/environ} and {@code /proc/<jvm>/root}. The network is deliberately shared:
 * the property that matters -- a skill cannot read the database and post it anywhere -- comes
 * from hiding the database, and skills that talk to the LAN, mail servers or Ollama keep
 * working.
 * <p>
 * Accepted as consequences of that design:
 * <ul>
 *   <li>{@code --share-net} shares loopback and the abstract Unix-socket namespace, so
 *       everything on the host listening on either is reachable: CUPS where it listens there,
 *       the service's own HTTP port (a skill can call its API as any client on the LAN can),
 *       and, on a dev host running as the owner, the owner's X11 display -- whose
 *       ~/.Xauthority a skill there can read -- and a browser's debug port;</li>
 *   <li>the kernel session keyring is the JVM's: bubblewrap has no option for a new one, so a
 *       skill sees the keys in it and can add its own, which outlive its run;</li>
 *   <li>a fresh PID namespace, kept because it hides {@code /proc/<jvm>/environ}: a skill sees
 *       only its own processes, so ps, pgrep and psutil list the jail's few, with no error;</li>
 *   <li>/dev is the host's but for a private /dev/shm and its own /dev/mqueue, and all of /run
 *       but the resolver is gone: {@code /dev/log}, which on a systemd host links into /run, no
 *       longer reaches the system log (logger exits 0 while nothing is logged, and Python's
 *       SysLogHandler prints a logging-error traceback to stderr for each record); nothing reaches
 *       the mDNS daemon (a {@code .local} name it would answer does not resolve), and there is
 *       no {@code /run/lock} or utmp;</li>
 *   <li>/dev/pts is the host's too: a skill can open a terminal its user owns -- none in
 *       production, where the service user has no terminal, but on a dev host running as the
 *       owner, the owner's own. {@code --new-session} keeps it from typing into one
 *       (TIOCSTI);</li>
 *   <li>the user namespace maps only the service user's own uid and gid, so its supplementary
 *       groups show as 65534 (nogroup) inside: a skill that looks at its group list concludes
 *       it lacks a group, while access is still decided by the real groups;</li>
 *   <li>a skill's /tmp, and /var/tmp with it, is one directory for each account
 *       ({@link #sharedTmp}): what a skill leaves there -- a report another skill of the account
 *       reads next, a downloaded browser, a backup -- is there for the account's next run, as it
 *       was in the service's own /tmp, and still there after OwnClaw restarts, which that was
 *       not; what no run of the account has used for a week is removed ({@link #TMP_UNUSED}).
 *       Another account's skills cannot see it. The checks and the venv and pip commands get a fresh one
 *       for each run instead. A process a run left in the background is gone with its jail, so
 *       nothing writes there between runs;</li>
 *   <li>journald's stores are hidden, so journalctl exits 0 with "-- No entries --";</li>
 *   <li>with /run gone, so are the system bus and systemd's own socket: systemctl, timedatectl,
 *       hostnamectl, loginctl, busctl, resolvectl, nmcli and the docker CLI fail;</li>
 *   <li>a bubblewrap that cannot mount the jail's own /dev/mqueue cannot start a jail, so under
 *       auto skills run unjailed until it can -- as long as the jail has not been ready in this
 *       process (see the modes below).</li>
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
 *       every launch, as a self-check that runs but fails always does. Once a launch has run
 *       unjailed, the jail stays off until the next start ({@link #RESTART_TO_JAIL}). A
 *       self-check that cannot run at all while bubblewrap is there -- no disk space for it, a
 *       timeout -- refuses every launch and looks again each minute: a jailed skill can fill a
 *       disk, and that must not make the next start unjailed.</li>
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
            // A tmpfs leaves the directory it covers in place -- the host's stores, and a backups/
            // or repo/ linked elsewhere, are roots of their own: what was in them, as the host has
            // it now, is what must be gone.
            for (Path store : HOST_STORES) out.addAll(entries(store));
            out.addAll(entries(backups()));
            out.addAll(entries(repo()));
            return out;
        }

        /**
         * What neither the owner's expose list nor a caller's bind may contain, by written and
         * by real path: the service's state, the host's stores ({@link #HOST_STORES}), and what
         * the self-check proves gone from /run.
         */
        List<Path> guarded() {
            var paths = new ArrayList<>(List.of(dataDir(), uploads(), database, dotEnv(), skillsRoot(),
                    generated, envsRoot(), backups(), repo()));
            if (logDir != null) paths.add(logDir);
            paths.addAll(HOST_STORES);
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
     * left of its jail and removes the run's private /tmp, when it had one of its own.
     *
     * @param marker what only this run's bubblewrap carries in its argv, for {@link #jailEnded}:
     *               the path of its own /tmp, or a random id when its /tmp is the account's
     * @param tmpDir the run's own /tmp, removed on close; null when there is none to remove
     * @param ended  what to do once the jail has ended, for a run whose /tmp is its account's:
     *               count the run out; null for none. Not done while a process of the jail is
     *               still running -- it can still be using the account's /tmp
     */
    public record Launch(List<String> argv, boolean jailed, String refusal, String marker, Path tmpDir,
                         Runnable ended)
            implements AutoCloseable {

        static Launch direct(List<String> command) {
            return new Launch(List.copyOf(command), false, null, null, null, null);
        }

        static Launch refused(String reason) {
            return new Launch(List.of(), false, reason, null, null, null);
        }

        @Override
        public void close() {
            if (marker == null) return;
            if (jailEnded(marker)) {
                if (tmpDir != null) deleteTree(tmpDir);
                if (ended != null) ended.run();
            } else if (tmpDir != null) {
                log.warn("Not removing {}: a process of its jail is still running", tmpDir);
            } else {
                log.warn("A process of jail {} is still running", marker);
            }
        }
    }

    /**
     * Whether every process of the jail whose argv carries {@code runTmp} -- its own /tmp's path,
     * or the id its launch was given ({@link Launch#marker}) -- is gone, killing what is left
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

    /**
     * @param bwrapFailed bubblewrap ran to its end and said, in its own words, that it cannot
     *                    start a jail here -- the only failure to complete that auto may take for
     *                    "not yet" and run unjailed. One that could not run at all (no space for
     *                    its directory, a timeout, an interrupt) says nothing about the host: a
     *                    full disk is something a jailed skill can bring about, and it must not
     *                    buy the next start an unjailed run.
     */
    private record SelfCheck(boolean completed, Map<String, Boolean> checks, String reason, boolean bwrapFailed) {
        SelfCheck(boolean completed, Map<String, Boolean> checks, String reason) {
            this(completed, checks, reason, false);
        }
    }

    private final Mode mode;
    private final String configError;
    private final Path bwrap;
    private final Layout layout;
    private final List<String> exposeEntries;
    private final Path browsers;
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
                layoutOf(config), config.getSandbox().getExpose(), System::currentTimeMillis,
                browsersOf(System.getProperty("user.home")));
    }

    /**
     * Where Playwright keeps the browsers it installs: {@code ~/.cache/ms-playwright}; null when
     * HOME is unknown. In production HOME is the service directory, which the jail hides, and the
     * lunch-menu skills drive a browser installed there; {@link #browserBuilds} exposes it.
     */
    static Path browsersOf(String home) {
        if (home == null || home.isBlank()) return null;
        return Path.of(home).resolve(".cache/ms-playwright").toAbsolutePath().normalize();
    }

    /**
     * @param isolation auto, required or off; anything else refuses every launch, because a
     *                  mistyped "required" must not quietly become "auto"
     * @param bwrap     absolute path of the bubblewrap binary; never looked up on PATH
     */
    public SkillJail(String isolation, String bwrap, Layout layout, List<String> expose,
                     LongSupplier clock) {
        this(isolation, bwrap, layout, expose, clock, null);
    }

    /**
     * @param browsers Playwright's browser directory, whose builds are exposed read-only without
     *                 the owner listing them ({@link #browserBuilds}); null for none
     */
    SkillJail(String isolation, String bwrap, Layout layout, List<String> expose,
              LongSupplier clock, Path browsers) {
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
        this.browsers = browsers;
        this.clock = clock;
        this.startedAt = clock.getAsLong();
    }

    /**
     * Evaluate once at startup, so the log says what the jail will do before the first skill;
     * and keep the accounts' /tmp directories an earlier process left ({@link #keepSharedTmp}).
     */
    @PostConstruct
    public void init() {
        keepSharedTmp();
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
     * Decide how to start {@code command} for a skill, with a fresh /tmp of its own: for the
     * checks of a skill and the venv and pip commands, whose build trees are nobody's to keep.
     *
     * @param command  the interpreter and its arguments, exactly as they would run unjailed
     * @param workDir  the only host directory the process may write (besides its private /tmp)
     * @param readOnly host paths the process may read, each at its own path
     */
    public Launch prepare(List<String> command, Path workDir, List<Path> readOnly) {
        return prepare(command, workDir, readOnly, null);
    }

    /**
     * Decide how to start {@code command} for a skill: a run of one, whose /tmp is its account's
     * ({@link #sharedTmp}) when {@code tmpOwner} names the account, and a fresh one of its own
     * when it is null.
     *
     * @param command  the interpreter and its arguments, exactly as they would run unjailed
     * @param workDir  the only host directory the process may write (besides its /tmp)
     * @param readOnly host paths the process may read, each at its own path
     * @param tmpOwner the account whose /tmp the run gets; null for a fresh one
     */
    public Launch prepare(List<String> command, Path workDir, List<Path> readOnly, String tmpOwner) {
        Evaluation e;
        // Read and counted under the evaluation's lock: an evaluation on another thread must not
        // turn the jail on between this launch being found unjailed and its being counted.
        synchronized (this) {
            e = current();
            if (e.state() == State.OFF || e.state() == State.INACTIVE) directRuns.incrementAndGet();
        }
        switch (e.state()) {
            case OFF, INACTIVE -> {
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
            if (tmpOwner != null) {
                // The account's /tmp is every run's of its skills at once, so its path tells no
                // run's processes apart: each launch carries an id of its own instead.
                String marker = "ownclaw-run-" + UUID.randomUUID();
                // Counted in until the run's jail has ended, so the sweep leaves the account alone.
                Path accountTmp = sharedTmp(tmpOwner, true);
                var countedOut = new java.util.concurrent.atomic.AtomicBoolean();
                Runnable ended = () -> {
                    if (countedOut.compareAndSet(false, true)) tmpRunEnded(tmpOwner);
                };
                List<String> argv;
                try {
                    argv = bwrapArgs(bwrap.toString(), roots, resolver(RESOLV_CONF), dir, ro,
                            e.expose(), accountTmp, marker, command);
                } catch (RuntimeException notStarted) {
                    ended.run();
                    throw notStarted;
                }
                isolatedRuns.incrementAndGet();
                return new Launch(argv, true, null, marker, null, ended);
            }
            tmp = Files.createTempDirectory("ownclaw-run-");
            List<String> argv = bwrapArgs(bwrap.toString(), roots, resolver(RESOLV_CONF), dir, ro,
                    e.expose(), tmp, command);
            isolatedRuns.incrementAndGet();
            return new Launch(argv, true, null, tmp.toString(), tmp, null);
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
     * Where the accounts' /tmp directories are: {@code skills/_tmp}, beside generated/ and _envs/,
     * inside a root the jail always hides -- not the JVM's temp directory, which is hidden only
     * when it is /tmp itself, and would otherwise show every account's /tmp to every skill.
     */
    Path sharedTmpRoot() {
        return layout.skillsRoot().resolve("_tmp");
    }

    /**
     * The directory of the accounts' /tmp directories, one for each account, named by
     * {@link #tmpName}: kept from one start to the next.
     */
    Path accountsTmpRoot() {
        return sharedTmpRoot().resolve("accounts");
    }

    private final Object sharedTmpLock = new Object();

    /** Each account's runs whose jail has not ended, by its directory's name ({@link #tmpName}). */
    private final Map<String, Integer> tmpRuns = new HashMap<>();

    /** How many runs have bound each account's /tmp in this process, by its directory's name. */
    private final Map<String, Long> tmpBinds = new HashMap<>();

    /** The accounts whose /tmp this process has walked for hard links ({@link #sharedTmp}). */
    private final Set<String> tmpWalked = new HashSet<>();

    /**
     * An account's /tmp: one directory its skills' runs share, as they shared the service's own
     * /tmp before there was a jail. A skill that writes a report there for another to read next,
     * keeps a downloaded browser or a backup there, finds it on the account's next run -- and
     * after OwnClaw restarts too: it is kept from one start to the next, and what no run of the
     * account has used for {@link #TMP_UNUSED} is removed ({@link #sweepUnusedTmp}). Until
     * 2026-11-05 it was new in each process, gone at every restart as the service's /tmp was:
     * on 2026-11-04 four restarts for deploys emptied an invoice search's working files four
     * times, and the owner's chat downloaded 17,004 emails again three times, for 50, 50 and 82
     * minutes. Other accounts' skills do not see it: each run sees its own account's at /tmp,
     * and the jail hides the directory they are all in, which only the service user can open.
     * <p>
     * Set back to the service user alone before each run binds it: a run can chmod its own /tmp.
     * The first time this process binds it, it is walked for a file with a name outside it
     * ({@link #linkedOutside}), as a skill's own directory is at every launch ({@link #checkBinds}):
     * an earlier process may have run skills unjailed, and an unjailed skill can put a hard link
     * to the database into it. Nothing inside a jail can make one, and nothing runs unjailed in a
     * process where the jail binds this directory: under auto the jail turns on only in a process
     * where no launch has run unjailed ({@link #RESTART_TO_JAIL}), and from then on none does;
     * required never runs one; off binds none. So one walk a process is enough ({@link #checkKept}).
     */
    Path sharedTmp(String owner) throws IOException {
        return sharedTmp(owner, false);
    }

    /** {@link #sharedTmp(String)}; counting a run in when {@code run}: see {@link #tmpRunEnded}. */
    private Path sharedTmp(String owner, boolean run) throws IOException {
        String name = tmpName(owner);
        synchronized (sharedTmpLock) {
            for (Path d : List.of(sharedTmpRoot(), accountsTmpRoot())) {
                if (!Files.exists(d, LinkOption.NOFOLLOW_LINKS)) Files.createDirectories(d);
                if (!Files.isDirectory(d, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("the accounts' /tmp root is not a directory of its own: " + d);
                }
                ownerOnly(d);
            }
            Path dir = accountsTmpRoot().resolve(name);
            boolean inFlight = tmpRuns.getOrDefault(name, 0) > 0;
            if (!inFlight && Files.exists(dir, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
                // A link or a file at its name: only something unjailed can have put it there.
                log.warn("The account's /tmp {} is not a directory: it is set aside", dir);
                setAside(dir);
            }
            if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(dir, ownerOnlyAttribute());
            if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("the account's /tmp is not a directory of its own");
            }
            ownerOnly(dir);
            if (!tmpWalked.contains(name)) {
                // The first bind of this process counts its run in after the walk: none is in flight.
                if (inFlight) throw new IOException("the account's /tmp cannot be checked while a run uses it");
                checkKept(dir);
                tmpWalked.add(name);
            }
            if (run) {
                tmpRuns.merge(name, 1, Integer::sum);
                tmpBinds.merge(name, 1L, Long::sum);
            }
            return dir;
        }
    }

    /** A run of the account whose /tmp it bound has ended, its jail with it: counted out. */
    private void tmpRunEnded(String owner) {
        synchronized (sharedTmpLock) {
            tmpRuns.computeIfPresent(tmpName(owner), (k, n) -> n > 1 ? n - 1 : null);
        }
    }

    /**
     * Move {@code dir} out of use, beside skills/_tmp under a name of its own, and remove it in the
     * background ({@link #deleteUnwrittenTree}): only for a directory no jail binds, and no run of
     * the account is using.
     */
    private void setAside(Path dir) throws IOException {
        Path aside = sharedTmpRoot().getParent().resolve("_tmp-old-" + UUID.randomUUID());
        openToMove(dir);
        Files.move(dir, aside, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        Thread.ofVirtual().name("ownclaw-old-tmp").start(() -> deleteUnwrittenTree(aside));
    }

    /**
     * A directory made rwx for the service user, by its path, so it can be moved to another
     * directory: Linux needs write permission on a directory itself to move it, for its "..",
     * and a run can leave one read-only (an archive unpacked, a copytree of a read-only source)
     * or closed. Only for a name nothing can be writing -- no run of its account in flight, or no
     * jail ever binds it -- and checked not to be a link; a file or a link is left as it is.
     */
    private static void openToMove(Path p) throws IOException {
        if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) ownerOnly(p);
    }

    /**
     * What this process does with an account's /tmp kept from an earlier start, before any run
     * binds it, and with none in flight, so nothing writes it ({@link #uncheckable}): in one walk,
     * every entry at its top that cannot be checked whole -- a directory a run closed, a path
     * longer than the system takes -- and every entry holding a file with a name the walk did not
     * find in it -- a hard link out, or into an entry that cannot be checked -- is set aside, and
     * the rest is kept. What is set aside no jail binds, so a link out it held reaches nothing.
     * Only a /tmp that cannot be listed at all is set aside whole. Once refused, a path too long
     * refused every launch of the account at every start; and set aside one at a time, with a walk
     * after each, thousands of closed directories held every account's launches for minutes.
     */
    private void checkKept(Path dir) throws IOException {
        Unchecked u = uncheckable(dir);
        if (u == null) return;
        if (u.whole()) {
            log.warn("The account's /tmp {} was kept from an earlier start, and {}: it is set aside "
                    + "and removed, and the account's skills start with an empty /tmp", dir, u.why());
            setAside(dir);
            Files.createDirectory(dir, ownerOnlyAttribute());
            return;
        }
        log.warn("{} {} of the account's /tmp {} kept from an earlier start {}: set aside and removed",
                u.entries().size(), u.entries().size() == 1 ? "entry" : "entries", dir, u.why());
        for (Path entry : u.entries()) {
            try {
                setAside(entry);
            } catch (IOException | RuntimeException e) {
                // Not kept unchecked: a launch that cannot put it out of use is refused.
                throw new IOException("an entry of the account's /tmp could not be set aside");
            }
        }
    }

    /**
     * What cannot be kept of an account's /tmp, or null when all of it can.
     *
     * @param whole   all of it: it cannot be listed
     * @param entries the entries at its top to set aside, otherwise
     * @param why     for the log, never for a model: it can name what a skill wrote
     */
    record Unchecked(boolean whole, List<Path> entries, String why) {}

    /**
     * Walk an account's /tmp as {@link #linkedOutside} walks a bind, file by file and following no
     * link, once: an entry at its top holding a name that cannot be read, listed or entered, and
     * one holding a file with more names than the walk found, are what cannot be kept.
     */
    static Unchecked uncheckable(Path root) throws IOException {
        if (!root.getFileSystem().supportedFileAttributeViews().contains("unix")) return null;
        var namesInside = new HashMap<Object, Integer>();
        var links = new HashMap<Object, Integer>();
        var tops = new HashMap<Object, java.util.Set<Path>>();
        var bad = new LinkedHashSet<Path>();
        boolean linkOut = false;
        record At(Path path, Path top) {}
        var pending = new ArrayDeque<At>(List.of(new At(root, null)));
        while (!pending.isEmpty()) {
            At at = pending.pop();
            if (at.top() != null && bad.contains(at.top())) continue;
            try {
                var a = Files.readAttributes(at.path(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (a.isDirectory()) {
                    for (Path child : listed(at.path())) pending.push(new At(child, at.top() == null ? child : at.top()));
                } else if (a.isRegularFile()) {
                    int names = nlink(at.path());
                    if (names > 1) {
                        Object key = a.fileKey() != null ? a.fileKey() : at.path();
                        namesInside.merge(key, 1, Integer::sum);
                        links.put(key, names);
                        tops.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(at.top() == null ? at.path() : at.top());
                    }
                }
            } catch (NoSuchFileException gone) {
                if (at.top() == null) throw gone;
            } catch (IOException e) {
                if (at.top() == null) return new Unchecked(true, List.of(), "it cannot be listed or entered");
                bad.add(at.top());
            }
        }
        for (var e : links.entrySet()) {
            if (e.getValue() > namesInside.get(e.getKey())) {
                bad.addAll(tops.get(e.getKey()));
                linkOut = true;
            }
        }
        if (bad.isEmpty()) return null;
        return new Unchecked(false, List.copyOf(bad), linkOut
                ? "cannot be checked whole, or hold a file with a name outside them (a hard link)"
                : "cannot be checked whole");
    }

    /** rwx for the service user alone, for a directory made new; none where there are no POSIX modes. */
    private static java.nio.file.attribute.FileAttribute<?>[] ownerOnlyAttribute() {
        if (!java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            return new java.nio.file.attribute.FileAttribute<?>[0];
        }
        return new java.nio.file.attribute.FileAttribute<?>[]{
                java.nio.file.attribute.PosixFilePermissions.asFileAttribute(OWNER_ONLY)};
    }

    private static final Set<PosixFilePermission> OWNER_ONLY = Set.of(PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);

    /**
     * Set a directory the service made back to rwx for the service user alone, by its path. Only
     * for one just checked to be a directory and no link, whose name nothing jailed can change:
     * the accounts' /tmp root, hidden, and an account's /tmp, which a run sees as its own /tmp, a
     * mount point it cannot rename or replace.
     */
    private static void ownerOnly(Path dir) throws IOException {
        if (!dir.getFileSystem().supportedFileAttributeViews().contains("posix")) return;
        Files.setPosixFilePermissions(dir, OWNER_ONLY);
    }

    /** The account's directory name: its id when that is a plain name, otherwise a hash of it. */
    static String tmpName(String owner) {
        if (owner.matches("[A-Za-z0-9_-]{1,64}")) return owner;
        try {
            byte[] h = java.security.MessageDigest.getInstance("SHA-256").digest(owner.getBytes(StandardCharsets.UTF_8));
            return "u-" + java.util.HexFormat.of().formatHex(h, 0, 16);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every Java platform", e);
        }
    }

    /**
     * At the start: keep the accounts' /tmp directories ({@link #accountsTmpRoot}), and put the
     * rest of the root out of use. An OwnClaw before 2026-11-05 made the accounts' directories in
     * a directory of each process, {@code run-*}: the newest one's are taken over, so the update
     * to this one keeps them; what is left goes. Each is walked for hard links before this
     * process binds it ({@link #sharedTmp}). What is set aside is removed in the background by
     * {@link #deleteUnwrittenTree}, which never follows a link and opens again a directory a run
     * closed (mode 000): a tree of millions of files must not hold up the start (and fail the
     * deploy's health check). Only a tree more than {@link #DELETE_DEPTH} directories deep is
     * left, hidden under skills/ and never bound; the log says what.
     */
    void keepSharedTmp() {
        Path skills;
        synchronized (sharedTmpLock) {
            tmpWalked.clear();
            Path root = sharedTmpRoot();
            skills = root.getParent();
            // Followed if it is a link: a skills/ elsewhere is the owner's layout (see refuseLink).
            if (skills == null || !Files.isDirectory(skills)) return;
            // Nothing here is bound yet: no run writes any of it, so a directory may be opened
            // by its path to move it (openToMove), and each name is moved on its own, so that one
            // that cannot be does not leave the rest where it is.
            try {
                if (Files.exists(root, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                    moveAside(skills, root);   // a link or a file where the root goes: never followed
                } else if (Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                    ownerOnly(root);
                    Path accounts = accountsTmpRoot();
                    if (Files.exists(accounts, LinkOption.NOFOLLOW_LINKS)
                            && !Files.isDirectory(accounts, LinkOption.NOFOLLOW_LINKS)) {
                        moveAside(skills, accounts);
                    }
                    if (Files.isDirectory(accounts, LinkOption.NOFOLLOW_LINKS)) {
                        ownerOnly(accounts);
                        for (Path account : listed(accounts)) {
                            // A link or a file at an account's name: only something unjailed puts one there.
                            if (!Files.isDirectory(account, LinkOption.NOFOLLOW_LINKS)) tryAside(skills, account);
                        }
                    }
                    List<Path> epochs = new ArrayList<>();
                    for (Path p : listed(root)) {
                        if (p.getFileName().toString().startsWith("run-") && Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
                            epochs.add(p);
                        }
                    }
                    epochs.sort(java.util.Comparator.comparing((Path p) -> {
                        try {
                            return Files.getLastModifiedTime(p, LinkOption.NOFOLLOW_LINKS);
                        } catch (IOException e) {
                            return java.nio.file.attribute.FileTime.fromMillis(0);
                        }
                    }).reversed());
                    for (Path epoch : epochs) {
                        try {
                            ownerOnly(epoch);
                            for (Path account : listed(epoch)) {
                                Path kept = accounts.resolve(account.getFileName().toString());
                                if (!Files.isDirectory(account, LinkOption.NOFOLLOW_LINKS)
                                        || Files.exists(kept, LinkOption.NOFOLLOW_LINKS)) continue;
                                try {
                                    if (!Files.exists(accounts, LinkOption.NOFOLLOW_LINKS)) {
                                        Files.createDirectory(accounts, ownerOnlyAttribute());
                                    }
                                    openToMove(account);
                                    Files.move(account, kept, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                                } catch (IOException | RuntimeException e) {
                                    log.warn("Could not keep the account's /tmp {}: {}", account, e.toString());
                                }
                            }
                        } catch (IOException | RuntimeException e) {
                            log.warn("Could not look into an older OwnClaw's /tmp {}: {}", epoch, e.toString());
                        }
                    }
                    for (Path p : listed(root)) {
                        if (!p.equals(accounts)) tryAside(skills, p);
                    }
                }
            } catch (IOException | RuntimeException e) {
                log.warn("Could not keep the accounts' /tmp under {} as it was: {}", root, e.toString());
            }
        }
        List<Path> old;
        try (var entries = Files.newDirectoryStream(skills, "_tmp-old-*")) {
            old = new ArrayList<>();
            entries.forEach(old::add);
        } catch (IOException | RuntimeException e) {
            log.warn("Could not list {} for old /tmp directories: {}", skills, e.toString());
            return;
        }
        synchronized (sharedTmpLock) {
            trash = null;
            trash();   // while there is room for it, made before anything is taken out
        }
        Thread.ofVirtual().name("ownclaw-old-tmp").start(() -> {
            for (Path p : old) deleteUnwrittenTree(p);
            // A disk a skill filled is made room on at the start, as a restart once emptied it.
            makeRoom();
        });
    }

    /** {@code p} renamed to skills/_tmp-old-*, at once, out of use; removed later. */
    private static void moveAside(Path skills, Path p) throws IOException {
        openToMove(p);
        Files.move(p, skills.resolve("_tmp-old-" + UUID.randomUUID()), java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    /** {@link #moveAside}, saying so in the log when it cannot. */
    private static void tryAside(Path skills, Path p) {
        try {
            moveAside(skills, p);
        } catch (IOException | RuntimeException e) {
            log.warn("Could not put {} out of use: {}", p, e.toString());
        }
    }

    /**
     * How long an entry of an account's /tmp -- a file or a directory at its top -- stays when no
     * run of the account uses it: about a week, as the owner chose on 2026-11-05. Nothing says
     * when a skill is done with its working files, and an entry in use is never removed however
     * old it is: the week counts from its last use, read or write, not from when it was made.
     */
    static final java.time.Duration TMP_UNUSED = java.time.Duration.ofDays(7);

    /** Whether a sweep is under way: a sweep of a large tree can outlast the hour. */
    private final java.util.concurrent.atomic.AtomicBoolean sweeping = new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * Every hour, from ten minutes after the start: remove what the accounts have not used, and
     * make room when the disk is nearly full ({@link #makeRoom}). On a thread of its own: Spring's
     * one scheduler thread also polls the owner's scheduled tasks, and walking a large tree must
     * not hold them up.
     */
    @org.springframework.scheduling.annotation.Scheduled(initialDelay = 600_000L, fixedDelay = 3_600_000L)
    public void sweepUnusedTmp() {
        if (!sweeping.compareAndSet(false, true)) return;
        Thread.ofVirtual().name("ownclaw-tmp-sweep").start(() -> {
            try {
                sweepUnusedTmp(System.currentTimeMillis());
                makeRoom();
            } catch (RuntimeException e) {
                log.warn("The sweep of the accounts' /tmp failed: {}", e.toString());
            } finally {
                sweeping.set(false);
            }
        });
    }

    /**
     * Remove every entry at the top of an account's /tmp that no run has used for
     * {@link #TMP_UNUSED} ({@link #lastUse}), a directory with all it holds. An account with a run
     * in flight is left alone, and so is one a run bound while the sweep was looking: the run may
     * be using what looked unused. What goes is moved aside at once, under the lock every launch
     * takes, and then removed. Returns how many entries were removed.
     *
     * @param now the time, in milliseconds since the epoch
     */
    int sweepUnusedTmp(long now) {
        Path accounts = accountsTmpRoot();
        if (!Files.isDirectory(accounts, LinkOption.NOFOLLOW_LINKS)) return 0;
        long cutoff = now - TMP_UNUSED.toMillis();
        List<Path> dirs;
        try {
            dirs = listed(accounts).stream().filter(p -> Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)).toList();
        } catch (IOException e) {
            log.warn("Could not list the accounts' /tmp directories in {}: {}", accounts, e.toString());
            return 0;
        }
        int removed = 0;
        for (Path dir : dirs) {
            String name = dir.getFileName().toString();
            long binds;
            List<Path> entries;
            synchronized (sharedTmpLock) {
                if (tmpRuns.getOrDefault(name, 0) > 0) continue;
                binds = tmpBinds.getOrDefault(name, 0L);
                try {
                    ownerOnly(dir);   // a run can close its own /tmp
                    entries = listed(dir);
                } catch (IOException e) {
                    log.warn("Could not look into the account's /tmp {}: {}", dir, e.toString());
                    continue;
                }
            }
            var unused = new ArrayList<Path>();
            for (Path entry : entries) {
                Long last = lastUse(entry);
                if (last != null && last < cutoff) unused.add(entry);
            }
            if (unused.isEmpty()) continue;
            Taken taken;
            synchronized (sharedTmpLock) {
                if (tmpRuns.getOrDefault(name, 0) > 0 || tmpBinds.getOrDefault(name, 0L) != binds) continue;
                taken = takeOut(unused);
            }
            // Removed outside the lock, which every launch takes: what was moved aside no run sees.
            if (taken.aside() != null) emptyTrash();
            int moved = taken.count();
            if (moved > 0) {
                log.info("Removed {} {} of the account's /tmp {} that no run had used for {} days", moved,
                        moved == 1 ? "entry" : "entries", dir, TMP_UNUSED.toDays());
            }
            removed += moved;
        }
        return removed;
    }

    /**
     * Where what is taken out of the accounts' /tmp is moved before it is removed: made at the
     * start, while the disk has room for it, so that taking something out on a full disk is a
     * rename, never a directory to make, and its removal happens after the lock every launch
     * takes is let go. Null when it cannot be made: then what is taken out is removed where it is.
     */
    private Path trash;

    /** The trash, made now if it is not there; null when it cannot be. Under {@link #sharedTmpLock}. */
    private Path trash() {
        if (trash != null && Files.isDirectory(trash, LinkOption.NOFOLLOW_LINKS)) return trash;
        try {
            trash = Files.createDirectory(sharedTmpRoot().getParent().resolve("_tmp-old-trash-" + UUID.randomUUID()),
                    ownerOnlyAttribute());
        } catch (IOException | RuntimeException e) {
            trash = null;
        }
        return trash;
    }

    /** Remove what is in the trash: outside {@link #sharedTmpLock}; no jail binds any of it. */
    private void emptyTrash() {
        Path t;
        synchronized (sharedTmpLock) {
            t = trash;
        }
        if (t == null) return;
        try {
            for (Path p : listed(t)) deleteUnwrittenTree(p);
        } catch (IOException | RuntimeException e) {
            log.warn("Could not empty {}: {}", t, e.toString());
        }
    }

    /**
     * Take these entries out of an account's /tmp and remove them: under {@link #sharedTmpLock},
     * with no run of the account in flight, so nothing writes them. Each is moved aside first, at
     * once, and removed after; where that cannot be done -- on a full disk the directory to move
     * them into cannot be made -- it is removed where it is. One that cannot be taken out is said
     * in the log, and the rest still go. Returns how many went, and where they were moved, for the
     * caller to remove once it has let the lock go.
     */
    private Taken takeOut(List<Path> entries) {
        Path aside = trash();
        int out = 0;
        for (Path entry : entries) {
            try {
                if (aside != null) {
                    openToMove(entry);
                    Files.move(entry, aside.resolve(UUID.randomUUID().toString()),
                            java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                    out++;
                } else if (deleteUnwrittenTree(entry)) {
                    out++;
                }
            } catch (IOException | RuntimeException e) {
                log.warn("Could not take {} out of the account's /tmp: {}", entry, e.toString());
            }
        }
        return new Taken(out, aside);
    }

    /** What {@link #takeOut} took out: how many, and the trash they were moved into, if any. */
    private record Taken(int count, Path aside) {}

    /**
     * Free space below which the disk the accounts' /tmp is on counts as full: 1 GiB. Below it,
     * the largest of their entries go first, until there is that much again ({@link #makeRoom}):
     * a jailed skill that fills the disk -- which a restart used to clear -- must not leave every
     * next launch refused, the jail's self-check unable to run. Only then: what fills a disk is
     * often something else -- backups, logs, images -- and a reserve that grew with the disk (5% of
     * it) emptied every account's /tmp, hourly, on a disk merely getting full.
     */
    static final long FULL = 1L << 30;

    /** The free and the total space of the disk the accounts' /tmp is on; a test's own. */
    volatile java.util.function.Function<Path, long[]> diskSpace = p -> {
        try {
            var store = Files.getFileStore(p);
            return new long[] {store.getUsableSpace(), store.getTotalSpace()};
        } catch (IOException e) {
            return null;
        }
    };

    /**
     * When the accounts' /tmp's disk has less than {@link #FULL} free: remove their entries, the
     * largest first -- a run that filled the disk left the largest -- until it has that much again,
     * or none is left. One that cannot be measured counts as the largest. An account with a run in
     * flight is left alone: its run may be what is writing, and it is removed once the run has
     * ended. Each is moved to the trash under the lock and removed after it, and the disk measured
     * again. Returns how many went.
     */
    int makeRoom() {
        Path accounts = accountsTmpRoot();
        if (!Files.isDirectory(accounts, LinkOption.NOFOLLOW_LINKS)) return 0;
        long[] space = diskSpace.apply(accounts);
        if (space == null || space[0] >= FULL) return 0;
        record Entry(String account, Path path, long size) {}
        var all = new ArrayList<Entry>();
        try {
            for (Path dir : listed(accounts)) {
                if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) continue;
                List<Path> entries;
                synchronized (sharedTmpLock) {
                    ownerOnly(dir);
                    entries = listed(dir);
                }
                for (Path entry : entries) {
                    Long size = sizeOf(entry);
                    all.add(new Entry(dir.getFileName().toString(), entry, size == null ? Long.MAX_VALUE : size));
                }
            }
        } catch (IOException | RuntimeException e) {
            log.warn("Could not look into the accounts' /tmp to make room on the disk: {}", e.toString());
        }
        all.sort(java.util.Comparator.comparingLong(Entry::size).reversed());
        int removed = 0;
        for (Entry e : all) {
            if (space == null || space[0] >= FULL) break;
            Taken taken;
            synchronized (sharedTmpLock) {
                if (tmpRuns.getOrDefault(e.account(), 0) > 0) continue;
                taken = takeOut(List.of(e.path()));
            }
            if (taken.aside() != null) emptyTrash();
            removed += taken.count();
            space = diskSpace.apply(accounts);
        }
        if (removed > 0) {
            log.warn("The disk of the accounts' /tmp had less than {} MiB free: removed {} of their entries, the "
                    + "largest first", FULL >> 20, removed);
        }
        return removed;
    }

    /** The bytes the regular files under {@code entry} take, following no link; null when part of it cannot be read. */
    static Long sizeOf(Path entry) {
        long total = 0;
        var pending = new ArrayDeque<Path>(List.of(entry));
        while (!pending.isEmpty()) {
            Path p = pending.pop();
            try {
                var a = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (a.isDirectory()) pending.addAll(listed(p));
                else if (a.isRegularFile()) total += a.size();
            } catch (NoSuchFileException gone) {
                // gone since it was listed
            } catch (IOException e) {
                return null;
            }
        }
        return total;
    }

    /**
     * When an entry of an account's /tmp was last used: the newest time that a file in it was
     * read, written or changed -- its access, modification or status-change time -- or a
     * directory in it written or changed. A directory's access time does not count: listing it,
     * which this does, sets it. The status-change time counts because nothing can set it back: a
     * file unpacked with its archive's dates is as new as its unpacking. So whatever sets it sets
     * the last use: a chown that changes nothing does, which is why the deploy's chown of skills/
     * leaves skills/_tmp out (deploy.sh, chown_skills_but_tmp). On a file system mounted noatime, a
     * file that is only read counts as used when it was last written. Null when part of it cannot
     * be listed: then it is kept, and the next start's first walk sets it aside ({@link #checkKept}).
     */
    static Long lastUse(Path entry) {
        boolean unix = entry.getFileSystem().supportedFileAttributeViews().contains("unix");
        long newest = Long.MIN_VALUE;
        var pending = new ArrayDeque<Path>(List.of(entry));
        while (!pending.isEmpty()) {
            Path p = pending.pop();
            try {
                var a = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                long t = a.lastModifiedTime().toMillis();
                if (!a.isDirectory()) t = Math.max(t, a.lastAccessTime().toMillis());
                if (unix) {
                    t = Math.max(t, ((java.nio.file.attribute.FileTime)
                            Files.getAttribute(p, "unix:ctime", LinkOption.NOFOLLOW_LINKS)).toMillis());
                }
                newest = Math.max(newest, t);
                if (a.isDirectory()) pending.addAll(listed(p));
            } catch (NoSuchFileException gone) {
                // Gone since its directory was listed: what is left still says when.
            } catch (IOException e) {
                return null;
            }
        }
        return newest == Long.MIN_VALUE ? null : newest;
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
        List<Bind> expose = exposed();
        SelfCheck check = selfCheck(roots, expose);
        if (!check.completed()) {
            if (check.bwrapFailed()) return notStarting(check.reason(), sig, now, version, roots);
            // bubblewrap is there and could not be checked: refused, and looked at again in a
            // minute -- never run unjailed on that.
            return new Evaluation(State.REFUSING, check.reason(), sig, now, version, roots, List.of(), Map.of());
        }
        if (check.checks().containsValue(false)) {
            // bubblewrap ran and an item failed. Every item is required, the functional ones (a
            // writable working directory, a readable /etc, the shared network) as much as the
            // hiding ones: that is not "not installed yet" but a jail that does not work as it
            // must, so even auto refuses rather than run skills in it or around it.
            return new Evaluation(State.REFUSING, check.reason(), sig, now, version, roots, expose,
                    check.checks());
        }
        if (mode == Mode.AUTO && readySince == 0 && directRuns.get() > 0) {
            // It works now, but skills have run unjailed in this process, and what they left
            // running -- a daemon, a process in another task's run -- would go on outside the jail,
            // beside it: able to put a hard link to the database where a jailed run reads. The
            // restart ends all of it (systemd stops the service's whole process group).
            return new Evaluation(State.INACTIVE, RESTART_TO_JAIL, sig, now, version, roots, expose,
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
    /** Why auto stays INACTIVE once bubblewrap works, until the restart. */
    static final String RESTART_TO_JAIL = "bubblewrap works now, but this process started without it and has run "
            + "skills unjailed; restart OwnClaw to turn the jail on (sudo systemctl restart ownclaw)";

    private Evaluation notStarting(String reason, Signature sig, long now, String version,
                                   List<Path> roots) {
        State s = mode == Mode.AUTO && readySince == 0 ? State.INACTIVE : State.REFUSING;
        return new Evaluation(s, reason, sig, now, version, roots, List.of(), Map.of());
    }

    private String refusal(Evaluation e) {
        if (mode == Mode.REQUIRED) return "isolation required but unavailable: " + e.reason();
        // auto refuses a self-check that ran and failed, a jail that was on and cannot start now,
        // and a self-check that could not run while bubblewrap is there
        if (!e.checks().isEmpty()) return "skill isolation is installed but failed its self-check: " + e.reason();
        return readySince != 0
                ? "skill isolation was on and cannot start a jail now: " + e.reason()
                : "skill isolation cannot be checked now, so no skill runs until it can: " + e.reason();
    }

    private void logState(Evaluation e) {
        switch (e.state()) {
            case READY -> {
                log.info("Skill isolation ON ({}): hidden {}, exposed read-only {}",
                        e.version(), e.roots(), e.expose());
                long few = fewNamespaces(sysctls(hostRoot));
                if (few > 0) {
                    log.warn("This host allows {} user namespaces per Linux user (user.max_user_namespaces), "
                            + "and each isolated run holds one while it runs: a run beyond them fails to start "
                            + "(bwrap: Creating new namespace failed ... ENOSPC). The owner can raise it: "
                            + "sudo sysctl -w user.max_user_namespaces=<n>, kept in /etc/sysctl.d", few);
                }
            }
            case INACTIVE -> {
                if (RESTART_TO_JAIL.equals(e.reason())) {
                    log.warn("Skill isolation INACTIVE until OwnClaw restarts: {}", e.reason());
                } else {
                    log.warn("Skill isolation INACTIVE, skills run unjailed until bubblewrap "
                            + "can start a jail: {}", e.reason());
                }
            }
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
                // Its own words are not enough to call the host unable: the jail's own arguments can
                // fail it too. Only when a bare jail -- namespaces and the mounts every jail needs,
                // nothing of ours -- fails as well is it the host.
                // No free user namespace says nothing about the host either: runs already going
                // hold them all -- and a jailed skill can make sure they do, to have the next
                // check call the host unable and the runs after it go unjailed. Unless the limit
                // is 0: then none is ever free, and that is the host.
                boolean shortage = namespacesUsedUp(p.exitValue(), stderr)
                        && !"0".equals(sysctls(hostRoot).get("user.max_user_namespaces"));
                boolean ownFailure = p.exitValue() != 0 && stderr.startsWith("bwrap:") && !shortage;
                return new SelfCheck(false, Map.of(), "bubblewrap could not start a jail (exit "
                        + p.exitValue() + "): " + (stderr.isEmpty() ? "no output" : stderr),
                        ownFailure && bareJailFails());
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
            // Without the probe's random names, so a recheck that fails the same way says the same.
            String why = String.valueOf(e.getMessage());
            if (probeDir != null) why = why.replace(probeDir.toString(), "<probe directory>");
            if (work != null) why = why.replace(work.toString(), "<check directory>");
            why = why.replaceAll("(\\.jail-probe-|ownclaw-jail-probe-|ownclaw-jail-check-)\\d+", "$1*");
            return new SelfCheck(false, Map.of(), "bubblewrap self-check could not run: "
                    + e.getClass().getSimpleName() + ": " + why);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new SelfCheck(false, Map.of(), "bubblewrap self-check was interrupted");
        } finally {
            if (probeDir != null) deleteTree(probeDir);
            if (work != null) deleteTree(work);
        }
    }

    /**
     * Whether bubblewrap, asked for a jail with nothing of the service's in it -- new namespaces,
     * the host read-only, /dev, the jail's own message queues and /proc -- ran to its end and
     * failed: then the host cannot jail. When it starts one, a self-check that bubblewrap failed
     * failed on the jail's own arguments; when it cannot be run or does not finish, nothing is
     * known. Neither is "cannot jail here", and neither may run a skill unjailed.
     */
    private boolean bareJailFails() {
        List<String> bare = List.of(bwrap.toString(), "--unshare-all", "--share-net", "--die-with-parent",
                "--ro-bind", "/", "/", "--dev-bind", "/dev", "/dev", "--mqueue", "/dev/mqueue",
                "--proc", "/proc", "--", "/bin/sh", "-c", "true");
        try {
            Process p = new ProcessBuilder(bare)
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            p.getOutputStream().close();
            if (!p.waitFor(SELF_CHECK_SECONDS, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() != 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
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
     *   <li>the host's /dev (serial devices keep working; permissions still apply);</li>
     *   <li>the run's own of each path in {@link #REPLACED}: /dev/mqueue, a private /dev/shm, a
     *       /proc for the new PID namespace, an empty /run, and its /tmp, which is its /var/tmp
     *       too;</li>
     *   <li>the resolver's directory put back into /run, read-only;</li>
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
        return bwrapArgs(bwrap, hiddenRoots, resolver, workDir, readOnly, expose, tmp, null, command);
    }

    /**
     * {@link #bwrapArgs(String, List, Path, Path, List, List, Path, List)} for a run whose /tmp is
     * shared: {@code marker}, an id of this launch alone, is set in the jail's environment as
     * {@code OWNCLAW_JAIL_RUN}, which puts it in bubblewrap's argv for {@link #jailEnded}.
     */
    static List<String> bwrapArgs(String bwrap, List<Path> hiddenRoots, Path resolver, Path workDir,
                                  List<Path> readOnly, List<Bind> expose, Path tmp, String marker,
                                  List<String> command) {
        var a = new ArrayList<String>();
        a.add(bwrap);
        a.addAll(List.of("--unshare-all", "--share-net", "--die-with-parent", "--new-session"));
        if (marker != null) a.addAll(List.of("--setenv", "OWNCLAW_JAIL_RUN", marker));
        a.addAll(List.of("--ro-bind", "/", "/"));
        a.addAll(List.of("--dev-bind", "/dev", "/dev"));
        for (Replaced r : REPLACED) {
            a.add(r.option());
            if (r.option().equals("--bind")) a.add(tmp.toString());
            a.add(r.at().toString());
        }
        if (resolver != null) a.addAll(List.of("--ro-bind-try", resolver.toString(), resolver.toString()));
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

    /** A host path a run has its own of, and the bubblewrap option that mounts the run's own there. */
    record Replaced(String option, Path at) {}

    /**
     * Every host path a run has its own of, in the order bwrapArgs mounts them: POSIX message
     * queues (over the host's, which the /dev bind brings in), /dev/shm, a /proc for its PID
     * namespace, an empty {@link #RUN}, and /tmp -- for {@code --bind}, the run's own directory --
     * which is /var/tmp as well: production gives the service a private, writable /var/tmp
     * (PrivateTmp=true), and a skill may write there as it may write /tmp. Whatever the host
     * keeps under one of them, the resolver's directory put back into /run aside, a run cannot
     * see.
     */
    static final List<Replaced> REPLACED = List.of(
            new Replaced("--mqueue", Path.of("/dev/mqueue")),
            new Replaced("--tmpfs", Path.of("/dev/shm")),
            new Replaced("--proc", Path.of("/proc")),
            new Replaced("--tmpfs", RUN),
            new Replaced("--bind", Path.of("/tmp")),
            new Replaced("--bind", Path.of("/var/tmp")));

    /**
     * What the self-check proves gone from /run: docker's socket (deploy.sh puts the service user
     * in the docker group when docker runs the containers), the system bus, the users' runtime
     * directories, and journald's volatile store (see {@link #HOST_STORES}). A host without one
     * passes that item trivially.
     */
    static final List<Path> RUN_PROBES = List.of(Path.of("/run/docker.sock"),
            Path.of("/run/dbus/system_bus_socket"), Path.of("/run/user"), Path.of("/run/log/journal"));

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
     * The host's stores a skill must not read, each a hidden root where present, as the
     * service's own state is; what is in them is what the self-check proves gone, and none may
     * be exposed or bound back.
     * <ul>
     *   <li>journald's persistent store. The unit sends the service's console output to the
     *       journal, and deploy.sh puts the service user in systemd-journal, which reads all of
     *       it: a group the jail cannot take away. Its volatile store goes with /run
     *       ({@link #RUN_PROBES});</li>
     *   <li>where crash reports and core dumps land: apport's reports, apport's core files and
     *       systemd-coredump's. One of a crashed process of the service user's is readable by
     *       that user, and holds the process's memory.</li>
     * </ul>
     */
    static final List<Path> HOST_STORES = List.of(Path.of("/var/log/journal"), Path.of("/var/crash"),
            Path.of("/var/lib/apport/coredump"), Path.of("/var/lib/systemd/coredump"));

    /** What is directly in {@code dir}: nothing when it is absent or cannot be listed. */
    private static List<Path> entries(Path dir) {
        try (var s = Files.list(dir)) {
            return s.toList();
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }

    static List<Path> hiddenRoots(Layout l) {
        return hiddenRoots(Stream.concat(Stream.of(l.cwd(), l.dataDir(), l.skillsRoot(), l.envsRoot(), l.logDir(),
                l.backups(), l.repo()), HOST_STORES.stream()).toArray(Path[]::new));
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
     * What a jailed run may read besides its own: the owner's list and the browser builds
     * ({@link #browserBuilds}), each kept out of what the jail hides ({@link #filterExpose}) and
     * walked for a file with a name outside it ({@link #linkedOutside}) -- a hard link made while
     * nothing was jailed, to the database say, would show it through the bind as it is now. One
     * that has one, or cannot be walked, is not exposed, and the log says why. Walked as the jail
     * is evaluated: in production the directories exposed are read-only to the service, so
     * nothing can add a link afterwards, and nothing jailed can make one at all.
     */
    private List<Bind> exposed() {
        var entries = new ArrayList<String>(exposeEntries);
        for (Path build : browserBuilds(browsers)) {
            if (!entries.contains(build.toString())) entries.add(build.toString());
        }
        var out = new ArrayList<Bind>();
        for (Bind b : filterExpose(entries, layout)) {
            String why;
            try {
                // Absent is fine -- bound with -try, so skipped -- but one that cannot be reached
                // (under ProtectHome's /home or /root, say) would make bubblewrap itself fail
                // to start every jail, and that must not read as "no jail can start here".
                Files.readAttributes(b.source(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                why = linkedOutside(b.source());
            } catch (NoSuchFileException absent) {
                why = null;
            } catch (IOException | RuntimeException e) {
                why = "it cannot be reached or walked: " + e;
            }
            if (why != null) {
                log.warn("Not exposing {} to skills: {}", b.dest(), why);
                continue;
            }
            out.add(b);
        }
        return List.copyOf(out);
    }

    /**
     * The browser builds in Playwright's directory, each its own entry: every directory in it but
     * the dotted ones. Not the directory whole: its {@code .links} names the environments of the
     * skills that installed the browsers -- other skills' names -- and a browser is launched from
     * its build directory alone. None when the directory is absent, a link, or not given.
     */
    static List<Path> browserBuilds(Path dir) {
        if (dir == null || !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) return List.of();
        try (var entries = Files.list(dir)) {
            return entries.filter(p -> !p.getFileName().toString().startsWith("."))
                    .filter(p -> Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS))
                    .sorted().toList();
        } catch (IOException | RuntimeException e) {
            log.warn("Could not list {}: {}", dir, e.toString());
            return List.of();
        }
    }

    /**
     * The owner's read-only exceptions (for example a HOME dotfile a skill needs), minus any
     * that would reach what the jail hides: an entry equal to, inside, or containing a
     * {@link Layout#guarded} path is dropped -- by its written path and by its real path -- as
     * is anything under the host's /proc, and any path in {@link #REPLACED} or the JVM's temp
     * directory as a whole (or anything holding one), which would lay the host's over the run's
     * own. An entry inside one of them -- one socket under /run -- is the owner's to choose.
     * Entries absent today are kept and bound with {@code -try}.
     */
    static List<Bind> filterExpose(List<String> entries, Layout layout) {
        var guarded = new ArrayList<>(layout.guarded());
        guarded.add(Path.of("/proc"));
        Path jvmTmp = abs(Path.of(System.getProperty("java.io.tmpdir")));
        var tmps = new ArrayList<Path>(List.of(jvmTmp, real(jvmTmp)));
        for (Replaced replaced : REPLACED) tmps.add(replaced.at());

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
     * binds): the JVM cannot read a link count there ({@link #nlink}), so nothing is found, and
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

    // ────────────────────────────── no free namespace ──────────────────────────────

    /**
     * Whether bubblewrap ended only because it found no room for the jail's namespaces: its
     * one line when the clone that makes them fails, before anything of the command exists.
     * The host's user.max_user_namespaces counts per Linux user, and every jailed run holds one
     * while it runs.
     */
    static boolean namespacesUsedUp(int exitCode, String stderr) {
        if (exitCode == 0 || stderr == null) return false;
        String line = stderr.strip();
        return line.indexOf('\n') < 0
                && line.startsWith("bwrap: Creating new namespace failed")
                && (line.contains("ENOSPC") || line.contains("No space left on device"));
    }

    /** Where the host's /proc/sys is read from for the jail's own decisions: "/" but in a test. */
    Path hostRoot = Path.of("/");

    /**
     * Fewer than this many user namespaces per Linux user is worth a word at start: the jail
     * runs that many skills at once at most, and the task queue runs several side by side.
     */
    static final int FEW_NAMESPACES = 16;

    /** The host's user.max_user_namespaces when it is a number below {@link #FEW_NAMESPACES}, else -1. */
    static long fewNamespaces(Map<String, String> sysctls) {
        try {
            long n = Long.parseLong(sysctls.getOrDefault("user.max_user_namespaces", ""));
            return n > 0 && n < FEW_NAMESPACES ? n : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
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
        if (RESTART_TO_JAIL.equals(s.reason())) return "sudo systemctl restart ownclaw: bubblewrap works, and "
                + "the jail turns on at the next start";
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
                // Not Files.readString: a /proc/sys file says it is empty, and that read takes the
                // first byte and then asks from offset 1, where the kernel has nothing more --
                // 87249 read as "8", and a production host's limit read as "3" (November 2026).
                // A buffered read has the whole value at offset 0.
                List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
                v = lines.isEmpty() ? "" : lines.get(0).strip();
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
            "LANG", "LC_ALL", "LC_CTYPE",
            "TZ",
            "TMPDIR", "TMP", "TEMP",
            "SSL_CERT_FILE", "SSL_CERT_DIR", "REQUESTS_CA_BUNDLE", "CURL_CA_BUNDLE",
            "SYSTEMROOT", "WINDIR", "COMSPEC", "PATHEXT",       // Windows needs these to run at all
            "TEMPDIR", "USERPROFILE");

    /**
     * UTF-8 for Python's stdin, stdout and stderr and for the files it opens, whatever the
     * platform's encoding: skills handle Czech text, and mojibake has been a recurring source of
     * bad output. Set for every launch, so a skill's run, its load check and pip alike.
     */
    static final Map<String, String> ENCODING = Map.of("PYTHONIOENCODING", "utf-8", "PYTHONUTF8", "1");

    /**
     * Replaces the inherited environment with the allow-listed subset, the {@link #ENCODING},
     * and the variables the caller sets: a skill's environment (PATH or PYTHONPATH). Applied to
     * whatever is started -- bubblewrap passes its own environment to the skill, so this is the
     * skill's, and it is bubblewrap's, which runs outside the jail. A skill's credentials are
     * never here: they come on the skill's stdin (DynamicSkill.CREDENTIALS).
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
        env.putAll(ENCODING);
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
        return deleteTree(root, false);
    }

    /**
     * {@link #deleteTree(Path)} for a tree nothing can be writing -- what of the accounts' /tmp
     * was set aside, which no jail binds, or an entry of one whose account has no run in flight --
     * that also removes a directory a run closed (mode 000 or 0300): one that cannot be opened is
     * checked, through its parent, to be a directory, made u+rwx by its path -- which follows a
     * link, so only where no writer can swap one in -- and opened again through its parent, never
     * following a link. A disk a skill filled must be freed ({@link #makeRoom}), closed or not.
     */
    static boolean deleteUnwrittenTree(Path root) {
        return deleteTree(root, true);
    }

    private static boolean deleteTree(Path root, boolean reopenClosed) {
        try {
            if (Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                openForOwner(Files.getFileAttributeView(root, PosixFileAttributeView.class));
                if (reopenClosed && !Files.isReadable(root)) Files.setPosixFilePermissions(root, OWNER_ONLY);
                try (var dir = Files.newDirectoryStream(root)) {
                    if (dir instanceof SecureDirectoryStream<Path> secure) {
                        empty(secure, 1, reopenClosed);
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
    private static void empty(SecureDirectoryStream<Path> dir, int depth, boolean reopenClosed) {
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
                    sub = reopenClosed ? reopened(dir, name, entry) : null;
                    if (sub == null) {
                        log.warn("Not removing {}: it cannot be opened (unreadable, or no longer the "
                                + "directory it was) ({})", entry, e.toString());
                        continue;
                    }
                }
                try (SecureDirectoryStream<Path> opened = sub) {
                    empty(opened, depth + 1, reopenClosed);
                }
                dir.deleteDirectory(name);
            } catch (IOException | RuntimeException e) {
                // gone meanwhile, or not empty: deleteTree reports what is left
            }
        }
    }

    /**
     * A closed directory of a tree nothing writes, opened again: checked through its parent to be
     * a directory, made u+rwx by its path, and opened through its parent without following a
     * link; null when it still cannot be.
     */
    private static SecureDirectoryStream<Path> reopened(SecureDirectoryStream<Path> parent, Path name, Path path) {
        try {
            var a = parent.getFileAttributeView(name, java.nio.file.attribute.BasicFileAttributeView.class,
                    LinkOption.NOFOLLOW_LINKS).readAttributes();
            if (!a.isDirectory()) return null;
            Files.setPosixFilePermissions(path, OWNER_ONLY);
            return parent.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException | RuntimeException e) {
            return null;
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
