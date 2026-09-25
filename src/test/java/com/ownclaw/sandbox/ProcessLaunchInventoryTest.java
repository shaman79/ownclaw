package com.ownclaw.sandbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every process OwnClaw starts, found in the source.
 * <p>
 * A Python started beside the jail is a skill process with the service's whole view of the
 * disk and, unless someone remembered the allow-list, its whole environment. The jail only
 * holds if every launch goes through it, and nothing at run time can tell a launch that skipped
 * it. So this reads src/main/java: a process may be started only in the files below, each
 * only in its own way, and a new launch anywhere fails the build until it is looked at here.
 */
class ProcessLaunchInventoryTest {

    private static final Path MAIN = Path.of("src/main/java");

    // Also spelled out in full: this code base names classes by their package inline often enough.
    private static final Pattern NEW_PROCESS_BUILDER =
            Pattern.compile("new\\s+(?:java\\s*\\.\\s*lang\\s*\\.\\s*)?ProcessBuilder\\s*\\(");

    /** Other ways to start a process, or to change what a ProcessBuilder starts. None allowed. */
    private static final Pattern OTHER_LAUNCH = Pattern.compile(
            "\\.\\s*exec\\s*\\(|ProcessBuilder\\s*\\.\\s*startPipeline|\\.\\s*command\\s*\\("
            + "|ProcessBuilder\\s*::\\s*new");

    // What each file may pass to `new ProcessBuilder(...)`, whitespace collapsed.
    //  - ProcessSandbox, PythonEnvironmentService: every Python a skill or pip runs -- only the argv
    //    SkillJail.prepare returned, which is the command itself only when the jail is inactive.
    //  - SkillJail: bubblewrap itself -- its version, and the self-check probe built by bwrapArgs.
    //  - SetupWizardService: `<python> --version`, to find an interpreter; it runs nothing else.
    //  - ContainerSandbox: the container runtime (docker/podman); what it runs is in the container.
    private static final Map<String, List<String>> ALLOWED = Map.of(
            "com/ownclaw/sandbox/ProcessSandbox.java", List.of("launch.argv()"),
            "com/ownclaw/skills/PythonEnvironmentService.java", List.of("launch.argv()"),
            "com/ownclaw/sandbox/SkillJail.java", List.of("bwrap.toString(), \"--version\"", "argv"),
            "com/ownclaw/config/SetupWizardService.java", List.of("cmd, \"--version\"", "pythonCmd, \"--version\""),
            "com/ownclaw/sandbox/ContainerSandbox.java", List.of("*"));

    private static Map<String, List<String>> launches() throws IOException {
        assertTrue(Files.isDirectory(MAIN), "run from the project directory: " + MAIN.toAbsolutePath());
        var out = new LinkedHashMap<String, List<String>>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                String src = Files.readString(f);
                String rel = MAIN.relativize(f).toString().replace('\\', '/');
                Matcher other = OTHER_LAUNCH.matcher(src);
                if (other.find()) {
                    fail(rel + " starts or re-targets a process with '" + other.group()
                            + "'; launch through `new ProcessBuilder(launch.argv())` after SkillJail.prepare");
                }
                var args = new ArrayList<String>();
                Matcher m = NEW_PROCESS_BUILDER.matcher(src);
                while (m.find()) args.add(arguments(src, m.end()));
                if (!args.isEmpty()) out.put(rel, args);
            }
        }
        return out;
    }

    /** The text between the parenthesis at {@code from} and its match, whitespace collapsed. */
    private static String arguments(String src, int from) {
        int depth = 1;
        boolean inString = false;
        int i = from;
        for (; i < src.length() && depth > 0; i++) {
            char c = src.charAt(i);
            if (inString) {
                if (c == '\\') i++;
                else if (c == '"') inString = false;
            } else if (c == '"') {
                inString = true;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            }
        }
        return src.substring(from, i - 1).replaceAll("\\s+", " ").trim();
    }

    @Test
    @DisplayName("processes are started only where the inventory allows, each only in its allowed way")
    void everyLaunchIsAccountedFor() throws IOException {
        var found = launches();
        for (var e : found.entrySet()) {
            List<String> allowed = ALLOWED.get(e.getKey());
            assertNotNull(allowed, e.getKey() + " starts a process: " + e.getValue()
                    + ". A skill's Python goes through SandboxManager; anything else is added to this"
                    + " inventory only after checking it cannot run skill code beside the jail.");
            if (allowed.contains("*")) continue;
            for (String args : e.getValue()) {
                assertTrue(allowed.contains(args), e.getKey() + " starts `new ProcessBuilder(" + args
                        + ")`; allowed there: " + allowed);
            }
        }
        // The guard is only as good as its reading of the source: it must see the launches it guards.
        assertEquals(List.of("launch.argv()"), found.get("com/ownclaw/sandbox/ProcessSandbox.java"));
        assertTrue(found.containsKey("com/ownclaw/skills/PythonEnvironmentService.java"));
    }

    @Test
    @DisplayName("where a skill's Python starts, its argv came from SkillJail.prepare and its environment is the allow-list")
    void pythonLaunchesGoThroughTheJail() throws IOException {
        for (String file : List.of("com/ownclaw/sandbox/ProcessSandbox.java",
                "com/ownclaw/skills/PythonEnvironmentService.java")) {
            String src = Files.readString(MAIN.resolve(file));
            long builders = NEW_PROCESS_BUILDER.matcher(src).results().count();
            long prepared = Pattern.compile("jail\\s*\\.\\s*prepare\\s*\\(").matcher(src).results().count();
            long scrubbed = Pattern.compile("SkillJail\\s*\\.\\s*applyEnvironment\\s*\\(\\s*pb\\s*,").matcher(src)
                    .results().count();
            assertTrue(prepared > 0, file + " starts processes without asking the jail");
            assertTrue(scrubbed >= builders, file + ": " + builders + " launches, but the allow-list is applied "
                    + scrubbed + " times");
        }
        String jail = Files.readString(MAIN.resolve("com/ownclaw/sandbox/SkillJail.java"));
        assertTrue(Pattern.compile("List<String>\\s+argv\\s*=\\s*bwrapArgs\\s*\\(").matcher(jail).find(),
                "SkillJail starts `argv` only as bwrapArgs built it");
    }
}
