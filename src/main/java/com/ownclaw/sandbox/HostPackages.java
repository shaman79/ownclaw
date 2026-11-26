package com.ownclaw.sandbox;

import com.ownclaw.config.OwnClawConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The system packages a skill declares ({@code system_packages} in its SKILL.yaml), given to its
 * runs on the host without root: what the host lacks of them, and what that needs, fetched from
 * the host's own package archives with {@code apt-get download} and unpacked with
 * {@code dpkg-deb -x} into a directory of the service's ({@code <data>/system-packages}), which
 * a run reads, read-only, with its programs on PATH, its libraries on LD_LIBRARY_PATH and the
 * data paths a program is known to need -- tesseract's TESSDATA_PREFIX.
 * <p>
 * Until 2026-11-26 such a skill ran in docker or podman when one was there, and otherwise ran
 * without its packages: production has neither, so an OCR skill found no tesseract and a cloud
 * repair of it found none either. The owner: "if they need it, then they should be able to
 * install it". Nothing is installed on the host, and nothing runs as root: no package's own
 * scripts run, no service starts, no file is setuid -- the bits are taken off as the files are
 * unpacked, and a jailed run has no_new_privs besides. What a package holds comes from the
 * archives apt trusts, checked against their signed indexes as apt checks them; the service keeps
 * package lists of its own, made fresh once a day, so a host whose own lists are old -- the
 * updater runs apt-get update only when it installs -- still gets what its archives hold now.
 * <p>
 * Each set of missing packages is unpacked once a week, into a directory named by the set and the
 * week ({@link #key}) -- so its libraries get the archives' updates -- and reused by every run that
 * needs it; one not used for {@link #UNUSED} is removed. Only packages the host has not installed
 * are fetched, never an upgrade of one it has, so a library on LD_LIBRARY_PATH is never one the
 * host's own programs -- bubblewrap among them -- would otherwise load from the host; and each
 * declared name must be a package of exactly that name, never a pattern apt reads into it. A host
 * without apt -- a developer's Mac, a container -- runs the skill as before, and the run's result
 * says why its packages are not there ({@link Provided#failure}).
 * <p>
 * apt and dpkg run in the jail ({@link SkillJail#prepare}), as pip does: the host read-only, the
 * one directory a step writes writable, its lists read-only where it only reads them, and none of
 * the service's state; and in the jail's allow-listed environment, with nothing of the service's.
 */
@Component
public class HostPackages {

    private static final Logger log = LoggerFactory.getLogger(HostPackages.class);

    /** A Debian package name, as policy allows one: lowercase letters, digits, "+", "-", ".". No architecture: the host's. */
    static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9+.-]+");

    /**
     * A line of apt-get's simulated install: "Inst libtesseract5 (5.3.0-2 ...)" installs a package
     * the host has not; "Inst libfoo [1.0-1] (1.0-2 ...)" upgrades one it has, which is never
     * fetched -- the host's own stays the one that is used, and its updates are the host's.
     */
    private static final Pattern INST = Pattern.compile("(?m)^Inst (\\S+)( \\[[^\\]\\n]*\\])?");

    /** How old the service's own package lists may be before they are made again. */
    static final Duration LISTS_FRESH = Duration.ofHours(24);

    /** How long a set unpacked and not used since is kept: a set is fetched anew each week ({@link #key}). */
    static final Duration UNUSED = Duration.ofDays(8);

    /** How long one step -- the lists, the downloads, one unpacking -- may take: a stalled mirror, not a bound on anything kept. */
    static final Duration STEP = Duration.ofMinutes(20);

    /** What the tools are found as: never looked up on the service's own PATH. */
    static final String APT_GET = "/usr/bin/apt-get", DPKG_QUERY = "/usr/bin/dpkg-query", DPKG_DEB = "/usr/bin/dpkg-deb";

    /** A command's end: its exit code and everything it wrote, its errors too. */
    record Ran(int exit, String output) {}

    /** How a command is run: here, a process in the jail; in a test, what the test says. */
    interface Commands {
        /**
         * {@code argv}, in {@code dir}, the one directory it may write, reading {@code readOnly}
         * besides the read-only host.
         */
        Ran run(List<String> argv, Path dir, List<Path> readOnly, Map<String, String> env, BooleanSupplier stopped)
                throws IOException, InterruptedException;

        /** Whether {@code program} is there to run. */
        default boolean has(String program) {
            return Files.isExecutable(Path.of(program));
        }
    }

    /**
     * What a run of a skill that declares packages is given: the directory of the packages the
     * host lacks, to read -- null when it lacks none, or they could not be had -- the variables
     * that find them, and why they could not be had, or null.
     */
    public record Provided(Path root, Map<String, String> env, String failure) {

        static final Provided NONE = new Provided(null, Map.of(), null);

        static Provided failed(String why) {
            return new Provided(null, Map.of(), why);
        }

        /** What the run may read besides its own, read-only: the packages' directory, if any. */
        public List<Path> binds() {
            return root == null ? List.of() : List.of(root);
        }

        /**
         * {@code envVars}, a run's own variables, given these: the packages' programs after
         * whatever PATH it has -- the skill's environment first, then the host's, so nothing the
         * host has is shadowed -- their libraries on LD_LIBRARY_PATH, and their data paths where
         * the run sets none of its own.
         */
        public void applyTo(Map<String, String> envVars) {
            for (var e : env.entrySet()) {
                String now = envVars.get(e.getKey());
                if (e.getKey().equals("PATH")) {
                    String base = now != null ? now : System.getenv("PATH");
                    envVars.put("PATH", base == null || base.isBlank() ? e.getValue() : base + ":" + e.getValue());
                } else if (e.getKey().equals("LD_LIBRARY_PATH")) {
                    envVars.put("LD_LIBRARY_PATH", now == null || now.isBlank() ? e.getValue() : now + ":" + e.getValue());
                } else {
                    envVars.putIfAbsent(e.getKey(), e.getValue());
                }
            }
        }
    }

    private final Path store;
    private final Commands commands;
    /** Where the host keeps tesseract's data: its languages join a set's ({@link #withHostLanguages}). */
    private final Path hostTesseract;
    /** One unpacking at a time. */
    private final ReentrantLock lock = new ReentrantLock();
    /** A run's look at a set (read) against the sweep's removal of one (write): never a set removed between the two. */
    private final java.util.concurrent.locks.ReentrantReadWriteLock using = new java.util.concurrent.locks.ReentrantReadWriteLock();
    private final SecureRandom random = new SecureRandom();

    @Autowired
    public HostPackages(OwnClawConfig config, SkillJail jail) {
        this(Path.of(config.getDatabase().getPath()).toAbsolutePath().normalize().getParent().resolve("system-packages"),
                (argv, dir, readOnly, env, stopped) -> exec(jail, argv, dir, readOnly, env, stopped));
    }

    HostPackages(Path store, Commands commands) {
        this(store, commands, Path.of("/usr/share/tesseract-ocr"));
    }

    HostPackages(Path store, Commands commands, Path hostTesseract) {
        this.store = store;
        this.commands = commands;
        this.hostTesseract = hostTesseract;
    }

    /** A step ended because the task was stopped: no interrupt of the thread, which runs on. */
    static final class Stopped extends IOException {
        Stopped() {
            super("the task was stopped");
        }
    }

    /** Where the sets are unpacked. */
    Path store() {
        return store;
    }

    /**
     * What a run needs of {@code packages}, a skill's declared list: unpacked first when this is
     * the first run that needs this set -- {@code progress} is told so, as it can take a minute.
     * Never throws: a set that cannot be had is said in {@link Provided#failure}, and the skill
     * runs without it, as it did before.
     */
    public Provided provide(List<String> packages, Consumer<String> progress, BooleanSupplier stopped) {
        if (packages == null || packages.isEmpty()) return Provided.NONE;
        var wanted = new TreeSet<String>();
        for (String p : packages) {
            String name = p == null ? "" : p.strip();
            if (name.isEmpty()) continue;
            if (!NAME.matcher(name).matches()) return Provided.failed("\"" + name + "\" is not a package name");
            wanted.add(name);
        }
        if (wanted.isEmpty()) return Provided.NONE;
        if (!commands.has(APT_GET) || !commands.has(DPKG_QUERY) || !commands.has(DPKG_DEB)) {
            return Provided.failed("this host has no apt to fetch " + String.join(", ", wanted) + " with");
        }
        try {
            List<String> missing = missing(wanted, stopped);
            if (missing.isEmpty()) return Provided.NONE;
            Path set = store.resolve(key(missing));
            if (!inUse(set)) {
                while (!lock.tryLock(200, TimeUnit.MILLISECONDS)) {
                    if (stopped != null && stopped.getAsBoolean()) throw new Stopped();
                }
                try {
                    if (!inUse(set)) {
                        if (progress != null) {
                            progress.accept("Installing system packages " + String.join(", ", missing)
                                    + " for this skill (once a week, without root)");
                        }
                        try {
                            unpack(missing, set, stopped);
                        } catch (Stopped stop) {
                            throw stop;
                        } catch (IOException fetched) {
                            // Last week's set, while it is kept: the archives out of reach on a Monday
                            // do not take the skill's packages away.
                            Path last = store.resolve(key(missing, java.time.LocalDate.now(java.time.ZoneOffset.UTC).minusWeeks(1)));
                            if (!inUse(last)) throw fetched;
                            log.warn("System packages {} could not be fetched again, last week's are used: {}", missing,
                                    fetched.getMessage());
                            set = last;
                        }
                    }
                } finally {
                    lock.unlock();
                }
            }
            Path root = set.resolve("root");
            return new Provided(root, env(root), null);
        } catch (Stopped stop) {
            return Provided.failed("stopped while fetching " + String.join(", ", wanted));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Provided.failed("interrupted while fetching " + String.join(", ", wanted));
        } catch (IOException | RuntimeException e) {
            log.warn("System packages {} could not be had: {}", wanted, e.getMessage());
            return Provided.failed(String.valueOf(e.getMessage()));
        }
    }

    /**
     * Whether {@code set} is there to read, and marked used now -- under the read lock, which the
     * sweep's removal waits for, so a set it finds unused is not one a run is just taking.
     */
    private boolean inUse(Path set) throws IOException {
        using.readLock().lock();
        try {
            if (!ready(set)) return false;
            Files.setLastModifiedTime(set.resolve("ready"), FileTime.from(Instant.now()));
            return true;
        } finally {
            using.readLock().unlock();
        }
    }

    /** Those of {@code wanted} the host has not installed, in order. */
    List<String> missing(Set<String> wanted, BooleanSupplier stopped) throws IOException, InterruptedException {
        var argv = new ArrayList<>(List.of(DPKG_QUERY, "-W", "-f=${Package} ${Status}\\n"));
        argv.addAll(wanted);
        // Exit 1 when one of them is unknown to dpkg: what it printed is still each it knows. In an
        // empty directory of its own: it writes nothing, and the jail checks what it may write.
        Path query = Files.createDirectories(store.resolve("query"));
        Ran ran = commands.run(argv, query, List.of(), plainEnv(null), stopped);
        var installed = new LinkedHashSet<String>();
        for (String line : ran.output().split("\\R")) {
            String[] f = line.strip().split("\\s+", 2);
            // "install ok installed", "hold ok installed": the second and third words say it is there.
            if (f.length == 2 && f[1].matches("\\S+ ok installed")) installed.add(f[0]);
        }
        var out = new ArrayList<String>();
        for (String w : wanted) if (!installed.contains(w)) out.add(w);
        return out;
    }

    /**
     * A set's directory name: the first 16 hex digits of the sha-256 of its names, sorted, one a
     * line, and of the week it is fetched in -- so a set is fetched again every week, with the
     * archives' updates (a library that reads images is one a security update is for), and the
     * last week's is swept once it has gone unused.
     */
    static String key(List<String> missing) {
        return key(missing, java.time.LocalDate.now(java.time.ZoneOffset.UTC));
    }

    static String key(List<String> missing, java.time.LocalDate day) {
        String week = day.get(java.time.temporal.IsoFields.WEEK_BASED_YEAR) + "-W"
                + day.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR);
        try {
            byte[] h = MessageDigest.getInstance("SHA-256")
                    .digest((String.join("\n", new TreeSet<>(missing)) + "\n" + week).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h).substring(0, 16);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean ready(Path set) {
        return Files.isRegularFile(set.resolve("ready"), LinkOption.NOFOLLOW_LINKS)
                && Files.isDirectory(set.resolve("root"), LinkOption.NOFOLLOW_LINKS);
    }

    /**
     * {@code missing} and what it needs that the host has not, unpacked into {@code set}: the
     * service's lists made fresh if they are old, apt asked what installing them would install
     * -- against the host's own dpkg status, recommends left out -- each of those downloaded and
     * unpacked into a directory of its own first, which takes the set's name only once all of it
     * is there. Sets unused for {@link #UNUSED}, and what an unpacking that never ended left, are
     * removed on the way.
     */
    private void unpack(List<String> missing, Path set, BooleanSupplier stopped) throws IOException, InterruptedException {
        Files.createDirectories(store);
        sweep(set);
        Path apt = store.resolve("apt");
        lists(apt, stopped);
        Path work = store.resolve(set.getFileName() + ".partial-" + Long.toHexString(random.nextLong()));
        Path dl = work.resolve("dl"), root = work.resolve("root"), tmp = work.resolve("tmp");
        Files.createDirectories(dl);
        Files.createDirectories(root);
        Files.createDirectories(tmp);
        try {
            var sim = new ArrayList<>(aptGet(apt));
            sim.addAll(List.of("-s", "--no-install-recommends", "install"));
            sim.addAll(missing);
            Ran simulated = commands.run(sim, work, List.of(apt), plainEnv(tmp), stopped);
            if (simulated.exit() != 0) throw new IOException("apt cannot install " + String.join(", ", missing) + ": " + tail(simulated.output()));
            var names = new ArrayList<String>();
            Matcher m = INST.matcher(simulated.output());
            while (m.find()) {
                if (m.group(2) != null) continue;   // an upgrade of the host's own
                if (!NAME.matcher(m.group(1)).matches()) throw new IOException("apt named \"" + m.group(1) + "\", which is no package name");
                names.add(m.group(1));
            }
            // Each declared name has to be a package of its own: where none is, apt reads a name
            // as a pattern -- "texlive-lang-.+" is 68 packages, a gigabyte -- or takes what provides it.
            for (String w : missing) {
                if (!names.contains(w)) {
                    throw new IOException("apt has no package named exactly \"" + w + "\" to install"
                            + (names.isEmpty() ? "" : " (it would install " + String.join(", ", names) + ")"));
                }
            }
            var download = new ArrayList<>(aptGet(apt));
            download.add("download");
            download.addAll(names);
            // Into the directory it runs in, the one it may write: apt-get download writes nowhere else.
            Ran fetched = commands.run(download, dl, List.of(apt), plainEnv(null), stopped);
            if (fetched.exit() != 0) throw new IOException("downloading " + String.join(", ", names) + " failed: " + tail(fetched.output()));
            List<Path> debs;
            try (Stream<Path> files = Files.list(dl)) {
                debs = files.filter(p -> p.getFileName().toString().endsWith(".deb")).sorted().toList();
            }
            if (debs.size() != names.size()) {
                throw new IOException("apt downloaded " + debs.size() + " of the " + names.size() + " packages " + String.join(", ", names));
            }
            for (Path deb : debs) {
                Ran x = commands.run(List.of(DPKG_DEB, "-x", deb.toString(), root.toString()), work, List.of(), plainEnv(tmp), stopped);
                if (x.exit() != 0) throw new IOException("unpacking " + deb.getFileName() + " failed: " + tail(x.output()));
            }
            noSetIds(root);
            withHostLanguages(root);
            Files.writeString(work.resolve("packages.txt"), String.join("\n", names) + "\n");
            delete(dl);
            delete(tmp);
            Files.writeString(work.resolve("ready"), Instant.now() + "\n");
            if (Files.exists(set, LinkOption.NOFOLLOW_LINKS)) delete(set);   // one an unpacking left half-made
            Files.move(work, set, StandardCopyOption.ATOMIC_MOVE);
            log.info("System packages {} unpacked for skills, with {}: {}", missing, names, set);
        } finally {
            if (Files.exists(work, LinkOption.NOFOLLOW_LINKS)) delete(work);
        }
    }

    /**
     * The service's own package lists, made again when they are older than {@link #LISTS_FRESH}
     * or there are none. When they cannot be made -- the archives out of reach -- old ones are
     * used, and none at all is an error.
     */
    private void lists(Path apt, BooleanSupplier stopped) throws IOException, InterruptedException {
        Path lists = apt.resolve("lists"), stamp = apt.resolve("updated");
        Files.createDirectories(lists.resolve("partial"));
        Files.createDirectories(apt.resolve("cache").resolve("archives").resolve("partial"));
        boolean fresh = Files.isRegularFile(stamp) && Files.getLastModifiedTime(stamp).toInstant()
                .isAfter(Instant.now().minus(LISTS_FRESH));
        if (fresh) return;
        var update = new ArrayList<>(aptGet(apt));
        update.add("update");
        Path tmp = Files.createDirectories(apt.resolve("tmp"));
        Ran ran = commands.run(update, apt, List.of(), plainEnv(tmp), stopped);
        boolean some;
        try (Stream<Path> l = Files.list(lists)) {
            some = l.anyMatch(p -> p.getFileName().toString().contains("Packages"));
        }
        if (ran.exit() == 0 && some) {
            Files.writeString(stamp, Instant.now() + "\n");
        } else if (some) {
            log.warn("The package lists could not be made fresh, so the old ones are used: {}", tail(ran.output()));
        } else {
            throw new IOException("the package lists could not be fetched: " + tail(ran.output()));
        }
    }

    /**
     * apt-get, reading and writing the service's own lists, never the host's -- no lock of the
     * host's is taken -- and keeping no cache of them: a step that only reads the lists cannot
     * write one.
     */
    private static List<String> aptGet(Path apt) {
        return List.of(APT_GET, "-q",
                "-o", "Dir::State::Lists=" + apt.resolve("lists"),
                "-o", "Dir::Cache=" + apt.resolve("cache"),
                "-o", "Dir::Cache::pkgcache=",
                "-o", "Dir::Cache::srcpkgcache=",
                "-o", "Debug::NoLocking=1",
                "-o", "APT::Get::AllowUnauthenticated=false",
                "-o", "Acquire::AllowInsecureRepositories=false",
                "-o", "Acquire::AllowDowngradeToInsecureRepositories=false");
    }

    /** The environment apt and dpkg run in: the system's programs, the C locale, nothing of the service's. */
    private static Map<String, String> plainEnv(Path tmp) {
        var env = new LinkedHashMap<String, String>();
        env.put("PATH", "/usr/sbin:/usr/bin:/sbin:/bin");
        env.put("LANG", "C");
        env.put("LC_ALL", "C");
        env.put("DEBIAN_FRONTEND", "noninteractive");
        if (tmp != null) env.put("TMPDIR", tmp.toString());
        return env;
    }

    /**
     * The host's tesseract languages, linked into the set's tessdata where it lacks them: the set's
     * TESSDATA_PREFIX is where every tesseract the run starts looks -- the host's own too, when the
     * host has the program and only a language was missing -- and pointing it at "ces" alone hid
     * the host's "eng" and "osd". Links to the host's files, which the jail shows read-only.
     */
    void withHostLanguages(Path root) throws IOException {
        Path mine = root.resolve("usr/share/tesseract-ocr");
        if (!Files.isDirectory(mine, LinkOption.NOFOLLOW_LINKS) || !Files.isDirectory(hostTesseract)) return;
        var hostFiles = new LinkedHashMap<String, Path>();
        try (Stream<Path> versions = Files.list(hostTesseract)) {
            for (Path v : versions.sorted(Comparator.reverseOrder()).toList()) {
                Path data = v.resolve("tessdata");
                if (!Files.isDirectory(data)) continue;
                try (Stream<Path> files = Files.list(data)) {
                    for (Path f : files.sorted().toList()) {
                        if (f.getFileName().toString().endsWith(".traineddata")) hostFiles.putIfAbsent(f.getFileName().toString(), f);
                    }
                }
            }
        }
        try (Stream<Path> versions = Files.list(mine)) {
            for (Path v : versions.toList()) {
                Path data = v.resolve("tessdata");
                if (!Files.isDirectory(data, LinkOption.NOFOLLOW_LINKS)) continue;
                for (var e : hostFiles.entrySet()) {
                    Path link = data.resolve(e.getKey());
                    if (!Files.exists(link, LinkOption.NOFOLLOW_LINKS)) Files.createSymbolicLink(link, e.getValue());
                }
            }
        }
    }

    /** Every setuid and setgid bit in {@code root} taken off: a program here never runs as anyone but the skill. */
    static void noSetIds(Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (attrs.isRegularFile()) {
                    int mode = (Integer) Files.getAttribute(file, "unix:mode", LinkOption.NOFOLLOW_LINKS);
                    if ((mode & 06000) != 0) Files.setAttribute(file, "unix:mode", mode & ~06000 & 07777, LinkOption.NOFOLLOW_LINKS);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                int mode = (Integer) Files.getAttribute(dir, "unix:mode", LinkOption.NOFOLLOW_LINKS);
                if ((mode & 06000) != 0) Files.setAttribute(dir, "unix:mode", mode & ~06000 & 07777, LinkOption.NOFOLLOW_LINKS);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * The variables that find what {@code root} holds: its program directories for PATH, its
     * library directories -- the architecture's first -- for LD_LIBRARY_PATH, and tesseract's
     * data, whose path is built into the program, for TESSDATA_PREFIX. Only directories that are
     * there.
     */
    static Map<String, String> env(Path root) throws IOException {
        var env = new LinkedHashMap<String, String>();
        var bins = new ArrayList<String>();
        for (String d : List.of("usr/local/bin", "usr/bin", "bin", "usr/sbin", "sbin")) {
            if (Files.isDirectory(root.resolve(d), LinkOption.NOFOLLOW_LINKS)) bins.add(root.resolve(d).toString());
        }
        if (!bins.isEmpty()) env.put("PATH", String.join(":", bins));
        var libs = new ArrayList<String>();
        for (String d : List.of("usr/lib", "lib")) {
            Path base = root.resolve(d);
            if (!Files.isDirectory(base, LinkOption.NOFOLLOW_LINKS)) continue;
            try (Stream<Path> arch = Files.list(base)) {
                arch.filter(p -> p.getFileName().toString().matches("[a-z0-9_]+-linux-gnu[a-z0-9_]*"))
                        .filter(p -> Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS))
                        .sorted().forEach(p -> libs.add(p.toString()));
            }
        }
        for (String d : List.of("usr/lib", "lib")) {
            if (Files.isDirectory(root.resolve(d), LinkOption.NOFOLLOW_LINKS)) libs.add(root.resolve(d).toString());
        }
        if (!libs.isEmpty()) env.put("LD_LIBRARY_PATH", String.join(":", libs));
        Path tess = root.resolve("usr/share/tesseract-ocr");
        if (Files.isDirectory(tess, LinkOption.NOFOLLOW_LINKS)) {
            try (Stream<Path> versions = Files.list(tess)) {
                versions.map(v -> v.resolve("tessdata"))
                        .filter(t -> Files.isDirectory(t, LinkOption.NOFOLLOW_LINKS))
                        .max(Comparator.comparing(Path::toString))
                        .ifPresent(t -> env.put("TESSDATA_PREFIX", t.toString()));
            }
            if (!env.containsKey("TESSDATA_PREFIX") && Files.isDirectory(tess.resolve("tessdata"), LinkOption.NOFOLLOW_LINKS)) {
                env.put("TESSDATA_PREFIX", tess.resolve("tessdata").toString());
            }
        }
        return env;
    }

    /**
     * Sets not used for {@link #UNUSED} -- their ready file untouched since -- and what an
     * unpacking that never ended left an hour ago or more, removed; never {@code keep}, and never
     * the lists.
     */
    private void sweep(Path keep) {
        using.writeLock().lock();
        try {
            sweepLocked(keep);
        } finally {
            using.writeLock().unlock();
        }
    }

    private void sweepLocked(Path keep) {
        Instant unused = Instant.now().minus(UNUSED), stale = Instant.now().minus(Duration.ofHours(1));
        try (Stream<Path> sets = Files.list(store)) {
            for (Path p : sets.toList()) {
                String n = p.getFileName().toString();
                if (p.equals(keep) || n.equals("apt") || n.equals("query") || !Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) continue;
                try {
                    if (n.contains(".partial-")) {
                        if (Files.getLastModifiedTime(p, LinkOption.NOFOLLOW_LINKS).toInstant().isBefore(stale)) delete(p);
                    } else if (n.matches("[0-9a-f]{16}")) {
                        Path ready = p.resolve("ready");
                        Instant used = Files.isRegularFile(ready, LinkOption.NOFOLLOW_LINKS)
                                ? Files.getLastModifiedTime(ready).toInstant()
                                : Files.getLastModifiedTime(p, LinkOption.NOFOLLOW_LINKS).toInstant();
                        if (used.isBefore(unused)) {
                            delete(p);
                            log.info("System packages unused since {} removed: {}", used, p);
                        }
                    }
                } catch (IOException e) {
                    log.warn("Could not sweep {}: {}", p, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("Could not list {}: {}", store, e.getMessage());
        }
    }

    /** {@code p} and everything under it, links removed as links, never followed. */
    static void delete(Path p) throws IOException {
        if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(p, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                // A directory unpacked without its owner's write bit would refuse what is in it.
                int mode = (Integer) Files.getAttribute(dir, "unix:mode", LinkOption.NOFOLLOW_LINKS);
                if ((mode & 0700) != 0700) Files.setAttribute(dir, "unix:mode", mode | 0700, LinkOption.NOFOLLOW_LINKS);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException {
                if (e != null) throw e;
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** The last lines of a command's output, for a reason: what it said last is why it ended. */
    static String tail(String output) {
        String[] lines = output == null ? new String[0] : output.strip().split("\\R");
        return String.join(" / ", java.util.Arrays.asList(lines).subList(Math.max(0, lines.length - 3), lines.length));
    }

    /**
     * A command run as a process in the jail ({@link SkillJail#prepare}): {@code dir} its working
     * directory and the one it may write, {@code readOnly} what it may read besides the host, the
     * jail's allow-listed environment and {@code env}; its output read whole, beside the wait. It
     * is ended when it takes longer than {@link #STEP} or the task stops. A launch the jail
     * refuses is an error, never a run without it.
     */
    static Ran exec(SkillJail jail, List<String> argv, Path dir, List<Path> readOnly, Map<String, String> env,
                    BooleanSupplier stopped) throws IOException, InterruptedException {
        try (SkillJail.Launch launch = jail.prepare(argv, dir, readOnly)) {
            if (launch.refusal() != null) throw new IOException(launch.refusal());
            ProcessBuilder pb = new ProcessBuilder(launch.argv());
            pb.directory(dir.toFile()).redirectErrorStream(true);
            SkillJail.applyEnvironment(pb, env);
            Process p = pb.start();
            p.getOutputStream().close();
            var out = new java.io.ByteArrayOutputStream();
            Thread reader = Thread.ofVirtual().start(() -> {
                try (InputStream in = p.getInputStream()) {
                    in.transferTo(out);
                } catch (IOException ignored) {
                    // what was read before is kept
                }
            });
            long until = System.nanoTime() + STEP.toNanos();
            try {
                while (!p.waitFor(200, TimeUnit.MILLISECONDS)) {
                    if (stopped != null && stopped.getAsBoolean()) throw new Stopped();
                    if (System.nanoTime() > until) {
                        throw new IOException(argv.getFirst() + " took longer than " + STEP.toMinutes() + " minutes");
                    }
                }
            } finally {
                if (p.isAlive()) {
                    p.descendants().forEach(ProcessHandle::destroyForcibly);
                    p.destroyForcibly();
                    p.waitFor(5, TimeUnit.SECONDS);
                }
            }
            reader.join(java.time.Duration.ofSeconds(5));
            return new Ran(p.exitValue(), out.toString(StandardCharsets.UTF_8));
        }
    }
}
