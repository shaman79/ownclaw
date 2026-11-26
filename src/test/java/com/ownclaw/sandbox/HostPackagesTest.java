package com.ownclaw.sandbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A skill's declared system packages, given to its runs on the host without root: what the host
 * lacks fetched with apt into the service's own directory, once a set, unpacked, setuid bits off,
 * and found by PATH, LD_LIBRARY_PATH and tesseract's TESSDATA_PREFIX -- with apt played here by a
 * fake that answers as apt does. (A real fetch needs the archives: see the commit's check.)
 */
class HostPackagesTest {

    /** apt and dpkg, as far as HostPackages asks them: what each call was, and what it did. */
    static class FakeApt implements HostPackages.Commands {
        final List<List<String>> calls = new CopyOnWriteArrayList<>();
        final List<Path> dirs = new CopyOnWriteArrayList<>();
        final List<List<Path>> reads = new CopyOnWriteArrayList<>();
        Set<String> installed = Set.of("libc6", "zlib1g");
        Set<String> held = Set.of();
        /** What the simulated install prints; null: what apt would for the names asked, tesseract's libraries before them. */
        String inst;
        int simulateExit = 0, downloadExit = 0, updateExit = 0;
        boolean has = true;
        CountDownLatch holdDownload;

        @Override
        public boolean has(String program) {
            return has;
        }

        @Override
        public HostPackages.Ran run(List<String> argv, Path dir, List<Path> readOnly, Map<String, String> env,
                                    BooleanSupplier stopped) throws IOException, InterruptedException {
            calls.add(List.copyOf(argv));
            dirs.add(dir);
            reads.add(List.copyOf(readOnly));
            assertEquals("/usr/sbin:/usr/bin:/sbin:/bin", env.get("PATH"), "nothing of the service's PATH");
            String tool = argv.getFirst();
            if (tool.equals(HostPackages.DPKG_QUERY)) {
                var out = new StringBuilder();
                for (String a : argv.subList(3, argv.size())) {
                    if (installed.contains(a)) out.append(a).append(" install ok installed\n");
                    if (held.contains(a)) out.append(a).append(" hold ok installed\n");
                }
                return new HostPackages.Ran(out.length() == argv.size() - 3 ? 0 : 1, out.toString());
            }
            if (tool.equals(HostPackages.DPKG_DEB)) {
                Path deb = Path.of(argv.get(2)), root = Path.of(argv.get(3));
                String pkg = deb.getFileName().toString().replaceAll("_.*$", "");
                switch (pkg) {
                    case "tesseract-ocr" -> {
                        Path bin = Files.createDirectories(root.resolve("usr/bin")).resolve("tesseract");
                        Files.writeString(bin, "#!/bin/sh\n");
                        Files.setAttribute(bin, "unix:mode", 04755);
                    }
                    case "libtesseract5", "libleptonica6" ->
                            Files.writeString(Files.createDirectories(root.resolve("usr/lib/x86_64-linux-gnu"))
                                    .resolve(pkg + ".so"), "elf");
                    case "tesseract-ocr-eng", "tesseract-ocr-ces", "tesseract-ocr-osd" ->
                            Files.writeString(Files.createDirectories(root.resolve("usr/share/tesseract-ocr/5/tessdata"))
                                    .resolve(pkg.substring("tesseract-ocr-".length()) + ".traineddata"), "data");
                    default -> { }
                }
                return new HostPackages.Ran(0, "");
            }
            assertEquals(HostPackages.APT_GET, tool);
            assertTrue(argv.contains("Debug::NoLocking=1"), "no lock of the host's");
            assertTrue(argv.stream().anyMatch(a -> a.startsWith("Dir::State::Lists=")), "its own lists");
            String verb = argv.contains("update") ? "update" : argv.contains("download") ? "download" : "install";
            switch (verb) {
                case "update" -> {
                    String lists = argv.stream().filter(a -> a.startsWith("Dir::State::Lists=")).findFirst().orElseThrow()
                            .substring("Dir::State::Lists=".length());
                    if (updateExit == 0) Files.writeString(Path.of(lists).resolve("deb.debian.org_dists_bookworm_main_binary-amd64_Packages"), "x");
                    return new HostPackages.Ran(updateExit, updateExit == 0 ? "Reading package lists..." : "E: Could not resolve 'deb.debian.org'");
                }
                case "install" -> {
                    assertTrue(argv.contains("-s"), "only simulated");
                    assertTrue(argv.contains("--no-install-recommends"));
                    String out = inst;
                    if (out == null) {
                        var sb = new StringBuilder();
                        var asked = argv.subList(argv.indexOf("install") + 1, argv.size());
                        if (asked.stream().anyMatch(a -> a.startsWith("tesseract"))) {
                            sb.append("Inst libleptonica6 (1.82.0-3 Debian:12 [amd64])\nInst libtesseract5 (5.3.0-2 Debian:12 [amd64])\n");
                        }
                        for (String a : asked) sb.append("Inst ").append(a).append(" (1.0-1 Debian:12 [amd64])\n");
                        out = sb.toString();
                    }
                    return new HostPackages.Ran(simulateExit, simulateExit == 0 ? out : "E: Unable to locate package tesseract-ocr-xyz");
                }
                default -> {
                    if (holdDownload != null) holdDownload.await();
                    if (downloadExit != 0) return new HostPackages.Ran(downloadExit, "E: Failed to fetch http://deb.debian.org/x 404 Not Found");
                    int at = argv.indexOf("download");
                    for (String name : argv.subList(at + 1, argv.size())) {
                        Files.writeString(dir.resolve(name + "_1.0_amd64.deb"), "deb");
                    }
                    return new HostPackages.Ran(0, "Fetched 10 MB");
                }
            }
        }

        long count(String verb) {
            return calls.stream().filter(c -> c.contains(verb)).count();
        }
    }

    private static final List<String> TESSERACT = List.of("tesseract-ocr", "tesseract-ocr-eng");

    /** Where nothing is: a host with no tesseract data and no libraries of its own. */
    static final Path NOWHERE = Path.of("/nonexistent/ownclaw-test-host");

    /** HostPackages over a fake apt, on a host that has none of what the fake unpacks. */
    static HostPackages fake(Path store, FakeApt apt) {
        return new HostPackages(store, apt, NOWHERE).withHostLibraries(NOWHERE);
    }

    @Test
    @DisplayName("what the host lacks is fetched once, unpacked without setuid bits, and found by PATH, LD_LIBRARY_PATH and TESSDATA_PREFIX")
    void fetchedOnceAndFound(@TempDir Path tmp) throws Exception {
        var apt = new FakeApt();
        var packages = fake(tmp.resolve("data/system-packages"), apt);
        var said = new ArrayList<String>();

        var got = packages.provide(TESSERACT, said::add, () -> false);

        assertNull(got.failure(), got.failure());
        Path root = got.root();
        assertNotNull(root);
        assertTrue(root.startsWith(tmp.resolve("data/system-packages")));
        assertEquals(List.of(root), got.binds(), "the run reads it, read-only");
        assertEquals(root.resolve("usr/bin").toString(), got.env().get("PATH"));
        assertEquals(root.resolve("usr/lib/x86_64-linux-gnu") + ":" + root.resolve("usr/lib"), got.env().get("LD_LIBRARY_PATH"));
        assertEquals(root.resolve("usr/share/tesseract-ocr/5/tessdata").toString(), got.env().get("TESSDATA_PREFIX"));
        int mode = (Integer) Files.getAttribute(root.resolve("usr/bin/tesseract"), "unix:mode");
        assertEquals(0755, mode & 07777, "the setuid bit is off");
        assertEquals(1, said.size(), "the task is told, once: " + said);
        assertTrue(said.getFirst().contains("tesseract-ocr, tesseract-ocr-eng"), said.getFirst());
        assertEquals("libleptonica6\nlibtesseract5\ntesseract-ocr\ntesseract-ocr-eng\n",
                Files.readString(root.getParent().resolve("packages.txt")), "what apt said installing them needs");
        try (var left = Files.list(tmp.resolve("data/system-packages"))) {
            assertEquals(Set.of("apt", "query", root.getParent().getFileName().toString()),
                    Set.copyOf(left.map(p -> p.getFileName().toString()).toList()), "nothing half-made is left");
        }
        assertFalse(Files.exists(root.getParent().resolve("dl")), "the downloads are not kept");
        // Each step writes one directory: the downloads into their own, the lists only read there.
        int download = indexOf(apt, "download");
        assertTrue(apt.dirs.get(download).getFileName().toString().equals("dl"), String.valueOf(apt.dirs.get(download)));
        assertTrue(apt.reads.get(download).getFirst().endsWith("apt"), String.valueOf(apt.reads.get(download)));

        int calls = apt.calls.size();
        var again = packages.provide(List.of("tesseract-ocr-eng", "tesseract-ocr"), said::add, () -> false);
        assertEquals(root, again.root(), "the same set, in any order, is the same directory");
        assertEquals(calls + 1, apt.calls.size(), "only dpkg is asked what the host has: nothing is fetched again");
        assertEquals(1, said.size(), "and the task is not told again");
        // Mutation: drop noSetIds -> the setuid bit stays; key the set by its order -> fetched twice.
    }

    @Test
    @DisplayName("a host that has every package gets nothing to read; one without apt, or a name that is none, runs as before and says why")
    void nothingToDoOrNothingToDoItWith(@TempDir Path tmp) {
        var apt = new FakeApt();
        apt.installed = Set.of("tesseract-ocr", "tesseract-ocr-eng");
        var packages = fake(tmp.resolve("sp"), apt);
        var got = packages.provide(TESSERACT, line -> fail("nothing to install: " + line), () -> false);
        assertNull(got.root());
        assertNull(got.failure());
        assertEquals(List.of(), got.binds());
        assertEquals(1, apt.calls.size(), "dpkg asked, nothing fetched");

        var none = new FakeApt();
        none.has = false;
        var without = fake(tmp.resolve("sp2"), none).provide(TESSERACT, null, () -> false);
        assertTrue(without.failure().contains("no apt"), without.failure());
        assertTrue(none.calls.isEmpty());

        for (String bad : List.of("-o", "--allow-unauthenticated", "Tesseract", "a", "tess ocr", "x;rm", "../etc")) {
            var odd = new FakeApt();
            var refused = fake(tmp.resolve("sp3"), odd).provide(List.of(bad), null, () -> false);
            assertNotNull(refused.failure(), bad);
            assertTrue(odd.calls.isEmpty(), bad + ": apt is never handed it");
        }
        // Mutation: let a name begin with "-" -> apt-get is handed an option.
    }

    @Test
    @DisplayName("what apt cannot do is said, and nothing half-made is kept: a package it does not know, a download that fails, a name it makes up")
    void failuresAreSaid(@TempDir Path tmp) throws Exception {
        var unknown = new FakeApt();
        unknown.simulateExit = 100;
        var a = fake(tmp.resolve("a"), unknown).provide(TESSERACT, null, () -> false);
        assertTrue(a.failure().contains("Unable to locate package"), a.failure());
        assertNull(a.root());

        var offline = new FakeApt();
        offline.downloadExit = 100;
        var b = fake(tmp.resolve("b"), offline).provide(TESSERACT, null, () -> false);
        assertTrue(b.failure().contains("404 Not Found"), b.failure());
        try (var left = Files.list(tmp.resolve("b"))) {
            var names = Set.copyOf(left.map(p -> p.getFileName().toString()).toList());
            assertTrue(names.contains("apt") && names.contains("query"), String.valueOf(names));
            assertEquals(3, names.size(), "no set, nor its partial -- only that it failed: " + names);
            assertTrue(names.stream().anyMatch(n -> n.endsWith(".failed")), String.valueOf(names));
        }
        // Made again after the hour, not taken for done.
        try (var left = Files.list(tmp.resolve("b"))) {
            for (Path f : left.filter(x -> x.getFileName().toString().endsWith(".failed")).toList()) {
                Files.setLastModifiedTime(f, FileTime.from(Instant.now().minus(Duration.ofHours(2))));
            }
        }
        offline.downloadExit = 0;
        assertNull(fake(tmp.resolve("b"), offline).provide(TESSERACT, null, () -> false).failure());

        var odd = new FakeApt();
        odd.inst = "Inst --force-yes (1.0)\n";
        var c = fake(tmp.resolve("c"), odd).provide(TESSERACT, null, () -> false);
        assertTrue(c.failure().contains("no package name"), c.failure());
        assertEquals(0, odd.count("download"), "never handed to apt-get download");

        var noLists = new FakeApt();
        noLists.updateExit = 100;
        var d = fake(tmp.resolve("d"), noLists).provide(TESSERACT, null, () -> false);
        assertTrue(d.failure().contains("package lists could not be fetched"), d.failure());
    }

    @Test
    @DisplayName("the lists are made fresh once a day; old ones are used when the archives are out of reach")
    void listsOncePerDay(@TempDir Path tmp) throws Exception {
        var apt = new FakeApt();
        var packages = fake(tmp.resolve("sp"), apt);
        assertNull(packages.provide(TESSERACT, null, () -> false).failure());
        assertNull(packages.provide(List.of("poppler-utils"), null, () -> false).failure());
        assertEquals(1, apt.count("update"), "made once for both sets");

        Path stamp = tmp.resolve("sp/apt/updated");
        Files.setLastModifiedTime(stamp, FileTime.from(Instant.now().minus(Duration.ofHours(25))));
        apt.updateExit = 100;
        assertNull(packages.provide(List.of("sshpass"), null, () -> false).failure(), "the old lists do");
        assertEquals(2, apt.count("update"), "tried again after a day");
    }

    @Test
    @DisplayName("a set not used for eight days is removed, and what an unpacking left; a set in use and the lists stay")
    void unusedSetsAreSwept(@TempDir Path tmp) throws Exception {
        var apt = new FakeApt();
        var packages = fake(tmp.resolve("sp"), apt);
        Path used = packages.provide(TESSERACT, null, () -> false).root().getParent();
        Path old = Files.createDirectories(tmp.resolve("sp/0123456789abcdef/root"));
        Files.writeString(old.getParent().resolve("ready"), "x");
        Files.setLastModifiedTime(old.getParent().resolve("ready"), FileTime.from(Instant.now().minus(Duration.ofDays(9))));
        Path partial = Files.createDirectories(tmp.resolve("sp/fedcba9876543210.partial-1a2b"));
        Files.setLastModifiedTime(partial, FileTime.from(Instant.now().minus(Duration.ofHours(2))));
        Path fresh = Files.createDirectories(tmp.resolve("sp/fedcba9876543210.partial-3c4d"));
        Path keep = Files.createDirectories(tmp.resolve("sp/notes"));

        assertNull(packages.provide(List.of("poppler-utils"), null, () -> false).failure());   // a new set sweeps

        assertFalse(Files.exists(old.getParent()), "unused for nine days: last week's");
        assertFalse(Files.exists(partial), "an unpacking that ended two hours ago");
        assertTrue(Files.exists(fresh), "one that may still be under way");
        assertTrue(Files.exists(used), "used today");
        assertTrue(Files.exists(tmp.resolve("sp/apt")), "the lists");
        assertTrue(Files.exists(keep), "nothing it did not make");
    }

    @Test
    @DisplayName("two runs that need the same set at once unpack it once")
    void onceForTwoAtOnce(@TempDir Path tmp) throws Exception {
        var apt = new FakeApt();
        apt.holdDownload = new CountDownLatch(1);
        var packages = fake(tmp.resolve("sp"), apt);
        var got = new HostPackages.Provided[2];
        Thread first = Thread.ofVirtual().start(() -> got[0] = packages.provide(TESSERACT, null, () -> false));
        Thread second = Thread.ofVirtual().start(() -> got[1] = packages.provide(TESSERACT, null, () -> false));
        long until = System.currentTimeMillis() + 10_000;
        while (apt.count("download") < 1 && System.currentTimeMillis() < until) Thread.sleep(10);
        Thread.sleep(200);
        apt.holdDownload.countDown();
        first.join(10_000);
        second.join(10_000);
        assertEquals(1, apt.count("download"));
        assertEquals(got[0].root(), got[1].root());
        assertNull(got[1].failure());
    }

    @Test
    @DisplayName("a run's PATH keeps the skill's and the host's programs first; its own data path is not overridden")
    void theRunsEnvironment(@TempDir Path tmp) {
        var provided = new HostPackages.Provided(tmp, Map.of("PATH", "/sp/usr/bin", "LD_LIBRARY_PATH", "/sp/usr/lib",
                "TESSDATA_PREFIX", "/sp/tessdata"), null);
        var env = new HashMap<String, String>(Map.of("PATH", "/envs/x/bin:/usr/bin:/bin", "TESSDATA_PREFIX", "/mine"));
        provided.applyTo(env);
        assertEquals("/envs/x/bin:/usr/bin:/bin:/sp/usr/bin", env.get("PATH"));
        assertEquals("/sp/usr/lib", env.get("LD_LIBRARY_PATH"));
        assertEquals("/mine", env.get("TESSDATA_PREFIX"));
        var bare = new HashMap<String, String>();
        provided.applyTo(bare);
        assertTrue(bare.get("PATH").endsWith(":/sp/usr/bin") || bare.get("PATH").equals("/sp/usr/bin"), bare.get("PATH"));
        // Mutation: put the packages first -> a package's python3 would come before the skill's own.
    }

    @Test
    @DisplayName("an upgrade of a package the host has is never fetched: its own library stays the one loaded, with its updates")
    void upgradesAreNotFetched(@TempDir Path tmp) {
        var apt = new FakeApt();
        apt.inst = "Inst libtiff6 [4.5.0-6] (4.5.0-6+deb12u1 Debian-Security:12 [amd64])\n"
                + "Inst tesseract-ocr (5.3.0-2 Debian:12 [amd64])\nInst tesseract-ocr-eng (1:4.1.0-2 Debian:12 [all])\n";
        assertNull(fake(tmp.resolve("sp"), apt).provide(TESSERACT, null, () -> false).failure());
        var download = apt.calls.get(indexOf(apt, "download"));
        assertFalse(download.contains("libtiff6"), String.valueOf(download));
        assertTrue(download.contains("tesseract-ocr"));
        // Mutation: take every Inst line -> libtiff6 is fetched and loaded before the host's.
    }

    @Test
    @DisplayName("a declared name apt reads as a pattern, or one another package provides, is refused: only exactly that package")
    void onlyExactNames(@TempDir Path tmp) {
        var apt = new FakeApt();
        apt.inst = "Inst texlive-lang-czechslovak (2022.20230122-3 Debian:12 [all])\nInst texlive-lang-german (2022 Debian:12 [all])\n";
        var got = fake(tmp.resolve("sp"), apt).provide(List.of("texlive-lang-.+"), null, () -> false);
        assertNotNull(got.failure());
        assertTrue(got.failure().contains("no package named exactly \"texlive-lang-.+\""), got.failure());
        assertEquals(0, apt.count("download"));
    }

    @Test
    @DisplayName("a package the host holds counts as installed")
    void heldIsInstalled(@TempDir Path tmp) {
        var apt = new FakeApt();
        apt.held = Set.of("tesseract-ocr", "tesseract-ocr-eng");
        var got = fake(tmp.resolve("sp"), apt).provide(TESSERACT, null, () -> false);
        assertNull(got.failure(), got.failure());
        assertNull(got.root());
    }

    @Test
    @DisplayName("a host that has tesseract and lacks a language: the set's tessdata holds the host's languages too")
    void theHostsLanguagesStay(@TempDir Path tmp) throws Exception {
        Path host = tmp.resolve("host/tesseract-ocr");
        Files.createDirectories(host.resolve("5/tessdata"));
        Files.writeString(host.resolve("5/tessdata/eng.traineddata"), "host eng");
        Files.writeString(host.resolve("5/tessdata/osd.traineddata"), "host osd");
        Files.createDirectories(host.resolve("5/tessdata/configs"));
        Files.writeString(host.resolve("5/tessdata/configs/tsv"), "tessedit_create_tsv 1");
        var apt = new FakeApt();
        apt.installed = Set.of("tesseract-ocr", "tesseract-ocr-eng");
        var got = new HostPackages(tmp.resolve("sp"), apt, host).withHostLibraries(NOWHERE)
                .provide(List.of("tesseract-ocr", "tesseract-ocr-ces", "tesseract-ocr-eng"), null, () -> false);
        assertNull(got.failure(), got.failure());
        Path data = Path.of(got.env().get("TESSDATA_PREFIX"));
        try (var files = Files.list(data)) {
            assertEquals(Set.of("ces.traineddata", "eng.traineddata", "osd.traineddata", "configs"),
                    Set.copyOf(files.map(f -> f.getFileName().toString()).toList()));
        }
        assertTrue(Files.isSymbolicLink(data.resolve("eng.traineddata")));
        assertEquals("host eng", Files.readString(data.resolve("eng.traineddata")));
        assertFalse(Files.isSymbolicLink(data.resolve("ces.traineddata")), "the set's own is its own");
        // Mutation: drop withHostLanguages -> TESSDATA_PREFIX holds ces alone, and -l ces+eng fails.
    }

    @Test
    @DisplayName("a run stopped while another unpacks ends at once, its thread not interrupted")
    void aStopWhileWaiting(@TempDir Path tmp) throws Exception {
        var apt = new FakeApt();
        apt.holdDownload = new CountDownLatch(1);
        var packages = fake(tmp.resolve("sp"), apt);
        Thread first = Thread.ofVirtual().start(() -> packages.provide(TESSERACT, null, () -> false));
        long until = System.currentTimeMillis() + 10_000;
        while (apt.count("download") < 1 && System.currentTimeMillis() < until) Thread.sleep(10);
        var result = new HostPackages.Provided[1];
        var interrupted = new boolean[1];
        Thread second = Thread.ofVirtual().start(() -> {
            result[0] = packages.provide(List.of("poppler-utils"), null, () -> true);
            interrupted[0] = Thread.currentThread().isInterrupted();
        });
        second.join(5_000);
        assertFalse(second.isAlive(), "it waits for the lock no longer than the stop");
        assertTrue(result[0].failure().startsWith("stopped"), result[0].failure());
        assertFalse(interrupted[0], "a stop is no interrupt: the run after it is not killed for one");
        apt.holdDownload.countDown();
        first.join(10_000);
    }

    @Test
    @DisplayName("a set is fetched again each week; when the archives are out of reach, last week's is used while it is kept")
    void weekly(@TempDir Path tmp) throws Exception {
        var monday = java.time.LocalDate.of(2026, 11, 23);
        assertEquals(HostPackages.key(TESSERACT, monday), HostPackages.key(TESSERACT, monday.plusDays(6)));
        assertNotEquals(HostPackages.key(TESSERACT, monday), HostPackages.key(TESSERACT, monday.plusDays(7)));
        assertEquals(HostPackages.key(TESSERACT, monday), HostPackages.key(List.of("tesseract-ocr-eng", "tesseract-ocr"), monday));

        var apt = new FakeApt();
        var today = java.time.LocalDate.now(java.time.ZoneOffset.UTC);
        Path last = tmp.resolve("sp").resolve(HostPackages.key(TESSERACT, today.minusWeeks(1)));
        Files.createDirectories(last.resolve("root/usr/bin"));
        Files.writeString(last.resolve("ready"), "x");
        Files.writeString(last.resolve("packages.txt"), "tesseract-ocr\n");
        Files.writeString(last.resolve("wanted.txt"), "tesseract-ocr\ntesseract-ocr-eng\n");
        apt.downloadExit = 100;
        var got = fake(tmp.resolve("sp"), apt).provide(TESSERACT, null, () -> false);
        assertNull(got.failure(), got.failure());
        assertEquals(last.resolve("root"), got.root());
    }

    @Test
    @DisplayName("a library the host has at the same path is taken out of the set: bubblewrap and every program load the host's")
    void theHostsLibrariesWin(@TempDir Path tmp) throws Exception {
        Path host = tmp.resolve("host");
        Files.createDirectories(host.resolve("usr/lib/x86_64-linux-gnu"));
        Files.writeString(host.resolve("usr/lib/x86_64-linux-gnu/libleptonica6.so"), "the host's");
        var got = new HostPackages(tmp.resolve("sp"), new FakeApt(), NOWHERE).withHostLibraries(host)
                .provide(TESSERACT, null, () -> false);
        assertNull(got.failure(), got.failure());
        Path libs = got.root().resolve("usr/lib/x86_64-linux-gnu");
        assertFalse(Files.exists(libs.resolve("libleptonica6.so")), "the host's own is the one loaded");
        assertTrue(Files.exists(libs.resolve("libtesseract5.so")), "one the host has not stays");
    }

    @Test
    @DisplayName("a set that would replace a package of the host's is refused")
    void noReplacement(@TempDir Path tmp) {
        var apt = new FakeApt();
        apt.inst = "Remv chrony [4.3-2]\nInst ntpsec (1.2.2 Debian:12 [amd64])\n";
        var got = fake(tmp.resolve("sp"), apt).provide(List.of("ntpsec"), null, () -> false);
        assertTrue(got.failure().contains("would replace chrony"), got.failure());
        assertEquals(0, apt.count("download"));
    }

    @Test
    @DisplayName("a fetch that failed is not tried again within the hour, and the newest earlier set of the same packages is used meanwhile")
    void aFailureBacksOff(@TempDir Path tmp) throws Exception {
        var apt = new FakeApt();
        apt.downloadExit = 100;
        var packages = fake(tmp.resolve("sp"), apt);
        assertNotNull(packages.provide(TESSERACT, null, () -> false).failure());
        assertEquals(1, apt.count("download"));
        assertNotNull(packages.provide(TESSERACT, null, () -> false).failure());
        assertEquals(1, apt.count("download"), "not again within the hour");

        // Two weeks ago's set, still kept: used while this week's cannot be had.
        var older = new FakeApt();
        var store = tmp.resolve("sp2");
        var today = java.time.LocalDate.now(java.time.ZoneOffset.UTC);
        Path old = store.resolve(HostPackages.key(TESSERACT, today.minusWeeks(2)));
        Files.createDirectories(old.resolve("root/usr/bin"));
        Files.writeString(old.resolve("ready"), "x");
        Files.writeString(old.resolve("packages.txt"), "tesseract-ocr\n");
        Files.writeString(old.resolve("wanted.txt"), "tesseract-ocr\ntesseract-ocr-eng\n");
        older.downloadExit = 100;
        var got = fake(store, older).provide(TESSERACT, null, () -> false);
        assertNull(got.failure(), got.failure());
        assertEquals(old.resolve("root"), got.root());
    }

    private static int indexOf(FakeApt apt, String verb) {
        for (int i = 0; i < apt.calls.size(); i++) if (apt.calls.get(i).contains(verb)) return i;
        throw new AssertionError(verb + " was never run");
    }
}
