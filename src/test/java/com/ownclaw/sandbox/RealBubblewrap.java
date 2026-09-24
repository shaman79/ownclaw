package com.ownclaw.sandbox;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * For tests outside this package that need the real bubblewrap: skips them, rather than failing,
 * where it is not installed or cannot create namespaces (CI, a host with user namespaces
 * blocked), and where there is no python3 to jail.
 */
public final class RealBubblewrap {

    public static final Path PATH = Path.of("/usr/bin/bwrap");

    private RealBubblewrap() {}

    public static void assumeWorks() {
        assumeTrue(Files.isExecutable(PATH), "bubblewrap is not installed");
        assumeTrue(exitsZero(PATH.toString(), "--unshare-all", "--share-net", "--die-with-parent",
                "--ro-bind", "/", "/", "--dev-bind", "/dev", "/dev", "--proc", "/proc", "--", "/bin/true"),
                "bubblewrap cannot create namespaces on this host");
        assumeTrue(exitsZero("python3", "--version"), "python3 is not available");
    }

    /** A jail in {@code required} mode over {@code layout}, which must come up READY. */
    public static SkillJail requiredJail(SkillJail.Layout layout) {
        var jail = new SkillJail("required", PATH.toString(), layout, List.of(), System::currentTimeMillis);
        var status = jail.status();
        if (status.state() != SkillJail.State.READY) {
            throw new AssertionError("the jail did not come up READY: " + status.reason());
        }
        return jail;
    }

    private static boolean exitsZero(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            p.getOutputStream().close();
            p.getInputStream().readAllBytes();
            return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
