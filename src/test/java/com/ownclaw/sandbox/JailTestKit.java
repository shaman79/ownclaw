package com.ownclaw.sandbox;

import com.ownclaw.config.OwnClawConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What the jail tests share: a mirror of the production directory on a temp dir, stand-ins for
 * /usr/bin/bwrap, and a reader for the argv the jail builds. Public for {@link #off} and
 * {@link #visibleDir(String)}.
 */
public final class JailTestKit {

    private JailTestKit() {}

    /**
     * A jail that is switched off, for tests anywhere that build a sandbox by hand. Its layout is
     * the default configuration's, as the service's own would be.
     */
    public static SkillJail off() {
        return new SkillJail("off", null, SkillJail.layoutOf(new OwnClawConfig()), List.of(),
                System::currentTimeMillis);
    }

    /**
     * A new directory the jail shows read-only, for a test that must see what the jail hides:
     * under the build directory of the project the tests run from; the caller deletes it.
     */
    public static Path visibleDir(String prefix) throws IOException {
        return visibleDir(Path.of("build", "jail-it"), prefix);
    }

    /**
     * A new directory under {@code parent}, which must be under none of the paths a run has its
     * own of ({@link SkillJail#REPLACED}), by its written path or its real one: what is kept there
     * is out of a run's sight whether or not the tmpfs over the hidden roots did its job, and a
     * test that it is hidden would pass whatever the jail did. Nor under the JVM's temp
     * directory, where each run's /tmp is made -- /tmp itself unless java.io.tmpdir says
     * otherwise.
     *
     * @throws AssertionError when it is, before anything is made there
     */
    static Path visibleDir(Path parent, String prefix) throws IOException {
        Path dir = parent.toAbsolutePath().normalize();
        refuseReplacedOrTemp(dir);
        // Its real path is its nearest existing ancestor's, followed by what is still to be made:
        // through a link, that is where it would be made.
        Path existing = dir;
        while (!Files.exists(existing)) existing = existing.getParent();
        refuseReplacedOrTemp(existing.toRealPath().resolve(existing.relativize(dir)));
        Files.createDirectories(dir);
        return Files.createTempDirectory(dir, prefix);
    }

    private static void refuseReplacedOrTemp(Path dir) {
        for (SkillJail.Replaced r : SkillJail.REPLACED) {
            refuseUnder(dir, r.at(), "which the jail replaces with the run's own");
        }
        refuseUnder(dir, Path.of(System.getProperty("java.io.tmpdir")),
                "the JVM's temp directory, where each run's /tmp is made");
    }

    private static void refuseUnder(Path dir, Path refused, String what) {
        var forms = new ArrayList<>(List.of(refused.toAbsolutePath().normalize()));
        try {
            forms.add(refused.toRealPath());
        } catch (IOException absent) {
            // nothing is there to be under
        }
        for (Path t : forms) {
            if (dir.startsWith(t)) {
                throw new AssertionError(dir + " is under " + t + ", " + what + ": a test there cannot see "
                        + "what the jail hides. Run the tests from a checkout outside "
                        + SkillJail.REPLACED.stream().map(r -> r.at().toString()).toList()
                        + " and the JVM's temp directory.");
            }
        }
    }

    /** A small copy of /opt/ownclaw: the secrets are canaries a test can look for. */
    record Mirror(Path root, Path db, Path env, Path uploads, Path handed, Path otherUpload,
                  Path generated, Path skillDir, Path otherSkill, Path logs) {

        SkillJail.Layout layout() {
            return new SkillJail.Layout(root, db, generated, logs);
        }

        static Mirror create(Path base) throws IOException {
            Path root = Files.createDirectories(base.resolve("opt-ownclaw"));
            Path env = Files.writeString(root.resolve(".env"), "OWNCLAW_OPS_TOKEN=canary-env-secret\n");
            Path data = Files.createDirectories(root.resolve("data"));
            Path db = Files.writeString(data.resolve("ownclaw.db"), "canary-db-secret");
            Path uploads = Files.createDirectories(data.resolve("uploads"));
            Path handed = Files.writeString(uploads.resolve("handed.txt"), "handed-content");
            Path otherUpload = Files.writeString(uploads.resolve("other.txt"), "canary-other-upload");
            Path generated = Files.createDirectories(root.resolve("skills/generated"));
            Files.createDirectories(root.resolve("skills/_envs"));
            Path skillDir = Files.createDirectories(generated.resolve("probe_skill"));
            Files.writeString(skillDir.resolve("SKILL.yaml"), "name: probe_skill\n");
            Files.writeString(skillDir.resolve("skill.py"), "def run(params):\n    return {}\n");
            Path otherSkill = Files.createDirectories(generated.resolve("other_skill"));
            Files.writeString(otherSkill.resolve("skill.py"), "# canary-other-skill\n");
            Path logs = Files.createDirectories(root.resolve("logs"));
            Files.writeString(logs.resolve("ownclaw.log"), "canary-log\n");
            return new Mirror(root, db, env, uploads, handed, otherUpload, generated, skillDir,
                    otherSkill, logs);
        }
    }

    // Reads just enough of a bubblewrap argv to know the working directory and the run's /tmp,
    // then leaves "$@" as the command after `--`.
    private static final String PARSE = """
            wd=.; tmp=
            while [ $# -gt 0 ]; do
              case "$1" in
                --) shift; break;;
                --chdir) wd="$2";;
                --bind) if [ "$3" = /tmp ]; then tmp="$2"; fi;;
              esac
              shift
            done
            cd "$wd" || exit 1
            """;

    // What a jail that holds answers the self-check's probe with, from its working directory.
    private static final String PROBE_ANSWER =
            "if [ \"$1\" = /bin/sh ] && [ \"$4\" = " + SkillJail.PROBE_NAME + " ]; then\n"
            + "  echo ok > rw.txt\n"
            + "  echo ok > \"$tmp/ownclaw-jail-probe.txt\"\n"
            + "  echo 'pidns=pid:[1]'\n"
            + "  echo \"netns=$(readlink /proc/self/ns/net)\"\n"
            + "  echo etc=readable\n"
            + "  echo probe=done\n"
            + "  exit 0\n"
            + "fi\n";

    /**
     * A bubblewrap that works, as far as the self-check can tell: it answers the probe the way
     * a real jail would and runs everything else (unjailed -- it is a stand-in for the wiring,
     * not for isolation). While {@code failFlag} exists it fails like a host whose user
     * namespaces are blocked. Every call's argv and environment are left in {@code record}.
     */
    static Path switchable(Path script, Path failFlag, Path record) throws IOException {
        return write(script, "#!/bin/sh\n"
                + "if [ \"$1\" = --version ]; then echo 'bubblewrap 0.0-test'; exit 0; fi\n"
                + "if [ -e '" + failFlag + "' ]; then\n"
                + "  echo 'bwrap: setting up uid map: Permission denied' >&2; exit 1\n"
                + "fi\n"
                + "printf '%s\\n' \"$@\" > '" + record + "/argv'\n"
                // What the JVM handed over, before the shell adds PWD and the like.
                + "tr '\\0' '\\n' < /proc/$$/environ > '" + record + "/env'\n"
                + PARSE
                + PROBE_ANSWER
                + "exec \"$@\"\n");
    }

    /**
     * A bubblewrap that works as far as the self-check can tell and keeps every call it gets
     * ({@code call.N/argv} under {@code record}), not only the last. What it runs is marked with
     * OWNCLAW_TEST_VIA_BWRAP=1, so a command can tell it was started through the jail and not
     * beside it.
     */
    static Path recordingEach(Path script, Path record) throws IOException {
        return write(script, "#!/bin/sh\n"
                + "if [ \"$1\" = --version ]; then echo 'bubblewrap 0.0-test'; exit 0; fi\n"
                + "d='" + record + "'/call.$(( $(ls '" + record + "' | wc -l) ))\n"
                + "mkdir \"$d\"\n"
                + "printf '%s\\n' \"$@\" > \"$d/argv\"\n"
                + PARSE
                + PROBE_ANSWER
                + "export OWNCLAW_TEST_VIA_BWRAP=1\n"
                + "exec \"$@\"\n");
    }

    /** The calls {@link #recordingEach} kept, in order, without the self-check's probe. */
    static List<List<String>> calls(Path record) throws IOException {
        var out = new ArrayList<List<String>>();
        for (Path d : numbered(record, "call.")) {
            List<String> argv = Files.readAllLines(d.resolve("argv"));
            // The probe's script spans lines, so the argv file does too: find it by its name.
            List<String> cmd = command(argv);
            if (cmd.get(0).equals("/bin/sh") && cmd.contains(SkillJail.PROBE_NAME)) continue;
            out.add(argv);
        }
        return out;
    }

    /**
     * A stand-in for python. Every start leaves its argv, the environment it was handed and
     * whether its input ended within 5 s ({@code launch.N/argv}, {@code launch.N/env},
     * {@code launch.N/stdin} under {@code launches}); {@code -m venv DIR}
     * makes DIR/bin/python a copy of itself, and anything else -- pip included -- succeeds
     * doing nothing. While {@code venvFails} exists, venv fails as it does on a host without
     * ensurepip, so provisioning falls back to a --target install.
     */
    static Path fakePython(Path script, Path launches, Path venvFails) throws IOException {
        return write(script, "#!/bin/sh\n"
                + "d='" + launches + "'/launch.$(( $(ls '" + launches + "' | wc -l) ))\n"
                + "mkdir \"$d\"\n"
                // What the JVM handed over, before the shell adds anything of its own.
                + "tr '\\0' '\\n' < /proc/$$/environ > \"$d/env\"\n"
                + "printf '%s\\n' \"$@\" > \"$d/argv\"\n"
                + "if timeout 5 cat > /dev/null; then echo ended; else echo open; fi > \"$d/stdin\"\n"
                + "if [ \"$1\" = -m ] && [ \"$2\" = venv ]; then\n"
                + "  if [ -e '" + venvFails + "' ]; then echo 'Error: ensurepip is not available'; exit 1; fi\n"
                + "  mkdir -p \"$3/bin\" && cp \"$0\" \"$3/bin/python\"\n"
                + "fi\n"
                + "exit 0\n");
    }

    /** One start of {@link #fakePython}; {@code inputEnded}: its stdin ended within 5 s. */
    record Launched(List<String> argv, Map<String, String> env, boolean inputEnded) {
        boolean pipInstall() {
            return argv.size() > 2 && argv.get(0).equals("-m") && argv.get(1).equals("pip")
                    && argv.get(2).equals("install");
        }
    }

    /** Every start of {@link #fakePython}, in order. */
    static List<Launched> launched(Path launches) throws IOException {
        var out = new ArrayList<Launched>();
        for (Path d : numbered(launches, "launch.")) {
            var env = new java.util.LinkedHashMap<String, String>();
            for (String line : Files.readAllLines(d.resolve("env"))) {
                int eq = line.indexOf('=');
                if (eq > 0) env.put(line.substring(0, eq), line.substring(eq + 1));
            }
            out.add(new Launched(Files.readAllLines(d.resolve("argv")), env,
                    Files.readString(d.resolve("stdin")).strip().equals("ended")));
        }
        return out;
    }

    private static List<Path> numbered(Path dir, String prefix) throws IOException {
        try (var s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().startsWith(prefix))
                    .sorted(java.util.Comparator.comparingInt(
                            p -> Integer.parseInt(p.getFileName().toString().substring(prefix.length()))))
                    .toList();
        }
    }

    /** A bubblewrap that runs whatever it is given with no isolation at all. */
    static Path passThrough(Path script, Path record) throws IOException {
        return write(script, "#!/bin/sh\n"
                + "printf '%s\\n' \"$@\" > '" + record + "/argv'\n"
                + "if [ \"$1\" = --version ]; then echo 'bubblewrap 0.0-test'; exit 0; fi\n"
                + PARSE
                + "exec \"$@\"\n");
    }

    private static Path write(Path script, String body) throws IOException {
        Files.createDirectories(script.getParent());
        Files.writeString(script, body, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        return script;
    }

    /** One bubblewrap option and its operands, in argv order. */
    record Op(int index, String flag, List<String> args) {}

    private static final Map<String, Integer> ARITY = Map.ofEntries(
            Map.entry("--unshare-all", 0), Map.entry("--share-net", 0),
            Map.entry("--die-with-parent", 0), Map.entry("--new-session", 0),
            Map.entry("--ro-bind", 2), Map.entry("--ro-bind-try", 2), Map.entry("--bind", 2),
            Map.entry("--dev-bind", 2), Map.entry("--mqueue", 1), Map.entry("--tmpfs", 1), Map.entry("--proc", 1),
            Map.entry("--remount-ro", 1), Map.entry("--chdir", 1), Map.entry("--setenv", 2));

    /** The options before `--`; an unknown option fails, so a new one gets a test first. */
    static List<Op> ops(List<String> argv) {
        var out = new ArrayList<Op>();
        int i = 1;
        while (i < argv.size() && !argv.get(i).equals("--")) {
            String flag = argv.get(i);
            Integer n = ARITY.get(flag);
            if (n == null) throw new AssertionError("unexpected bubblewrap option " + flag + " in " + argv);
            out.add(new Op(i, flag, argv.subList(i + 1, i + 1 + n)));
            i += 1 + n;
        }
        return out;
    }

    /** The command after `--`. */
    static List<String> command(List<String> argv) {
        return argv.subList(argv.indexOf("--") + 1, argv.size());
    }
}
