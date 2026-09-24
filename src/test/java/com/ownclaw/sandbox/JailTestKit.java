package com.ownclaw.sandbox;

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
 * /usr/bin/bwrap, and a reader for the argv the jail builds.
 */
final class JailTestKit {

    private JailTestKit() {}

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
                + "if [ \"$1\" = /bin/sh ] && [ \"$4\" = " + SkillJail.PROBE_NAME + " ]; then\n"
                + "  echo ok > rw.txt\n"
                + "  echo ok > \"$tmp/ownclaw-jail-probe.txt\"\n"
                + "  echo 'pidns=pid:[1]'\n"
                + "  echo \"netns=$(readlink /proc/self/ns/net)\"\n"
                + "  echo etc=readable\n"
                + "  echo probe=done\n"
                + "  exit 0\n"
                + "fi\n"
                + "exec \"$@\"\n");
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
            Map.entry("--dev-bind", 2), Map.entry("--tmpfs", 1), Map.entry("--proc", 1),
            Map.entry("--remount-ro", 1), Map.entry("--chdir", 1));

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
