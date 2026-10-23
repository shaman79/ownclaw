package com.ownclaw.sandbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownclaw.agent.tools.DynamicSkill;
import com.ownclaw.agent.tools.ToolExecutionContext;
import com.ownclaw.agent.tools.ToolResult;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.skills.PythonEnvironmentService;
import com.ownclaw.users.CredentialVault;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * How a skill is given its credentials: on the runner's stdin, which puts them into its own
 * environment before skill.py loads. Never on a command line -- {@code podman run -e
 * NAME=VALUE} put every value in the host's process list, readable by every account on the
 * machine -- and never in the environment of a process the service starts around the skill.
 */
class CredentialHandoverTest {

    @TempDir Path tmp;

    /** A value of more than one line, with blanks and a tab in it: an SSH private key's shape. */
    static final String SECRET = "Zq7-first-line\nZq7 second line with spaces\n\tZq7-third";

    /** What the skill reports: whether it, and a program it starts, see the value, and where. */
    static final String SKILL = String.join("\n",
            "import os, subprocess",
            "def run(params):",
            "    child = subprocess.run(['sh', '-c', 'printf %s \"$SSH_PRIVATE_KEY\"'],",
            "                          capture_output=True, text=True).stdout",
            "    started = ''",
            "    if os.path.exists('/proc/self/environ'):",
            "        started = open('/proc/self/environ', 'rb').read().decode('utf-8', 'replace')",
            "    return {'output': ' '.join([",
            "        'env=' + str(os.environ.get('SSH_PRIVATE_KEY') == " + pyString(SECRET) + "),",
            "        'child=' + str(child == " + pyString(SECRET) + "),",
            "        'param=' + str('_credentials' in params),",
            "        'exec_env=' + str('Zq7' in started)])}",
            "");

    static String pyString(String s) {
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\t", "\\t") + "'";
    }

    static final CredentialVault VAULT = new CredentialVault(null) {
        @Override public Map<String, String> getCredentials(String userId, List<String> keys) {
            return Map.of("SSH_PRIVATE_KEY", SECRET);
        }
    };

    private Path skillDir() throws IOException {
        Path dir = Files.createDirectories(tmp.resolve("skills/ssh_uptime"));
        Files.writeString(dir.resolve("skill.py"), SKILL);
        return dir;
    }

    private static ToolResult run(DynamicSkill skill) {
        return skill.execute(Map.of(), new ToolExecutionContext("u1", "t1", null, () -> false, null, List.of()));
    }

    /**
     * A runtime standing in for docker: it writes down its command line and its environment, and
     * runs the runner it was told to with python3 on the host, the skill directory it was told to
     * mount as its working directory. Every image exists.
     */
    private Path runtime(Path record) throws IOException {
        Path script = tmp.resolve("bin/docker");
        Files.createDirectories(script.getParent());
        Files.writeString(script, "#!/bin/sh\n"
                + "case \"$1\" in\n"
                + "  image) exit 0 ;;\n"
                + "  run)\n"
                + "    printf '%s\\n' \"$@\" > '" + record.resolve("argv") + "'\n"
                + "    env > '" + record.resolve("env") + "'\n"
                + "    for a; do case \"$a\" in *:/skill) dir=${a%:/skill} ;; esac; last=$a; done\n"
                + "    cd \"$dir\" && exec python3 \"$dir/${last#/skill/}\" ;;\n"
                + "  *) exit 0 ;;\n"
                + "esac\n");
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
        return script;
    }

    @Test
    @DisplayName("a container skill reads its credential from its environment; the runtime's command line and environment never hold it")
    void containerRun() throws Exception {
        Path record = Files.createDirectories(tmp.resolve("record"));
        var container = ContainerSandbox.withRuntimes(new OwnClawConfig(), runtime(record).toString(), null);
        var skill = new DynamicSkill("ssh_uptime", "runs uptime over ssh", Map.of(), skillDir(), true, false, 30,
                null, new PythonEnvironmentService(new OwnClawConfig()), List.of("SSH_PRIVATE_KEY"), VAULT,
                List.of("openssh-client"), null, container, null);

        ToolResult result = run(skill);

        assertTrue(result.success(), result.output());
        assertEquals("env=True child=True param=False exec_env=False", result.output().strip());
        String argv = Files.readString(record.resolve("argv"));
        assertTrue(argv.contains("run"), "the runtime was started to run: " + argv);
        for (String line : SECRET.split("\n")) {
            assertFalse(argv.contains(line.strip()), "on the runtime's command line: " + argv);
            assertFalse(Files.readString(record.resolve("env")).contains(line.strip()),
                    "in the runtime's environment");
        }
        assertFalse(argv.contains("SSH_PRIVATE_KEY"), "not even the name: " + argv);
    }

    @Test
    @DisplayName("a host skill reads its credential from its environment, not from the one it was started with")
    void hostRun() throws Exception {
        var env = new PythonEnvironmentService(new OwnClawConfig());
        var skill = new DynamicSkill("ssh_uptime", "runs uptime over ssh", Map.of(), skillDir(), true, false, 30,
                new ProcessSandbox(env, new ObjectMapper()), env, List.of("SSH_PRIVATE_KEY"), VAULT,
                List.of(), null, null, null);

        ToolResult result = run(skill);

        assertTrue(result.success(), result.output());
        assertEquals("env=True child=True param=False exec_env=False", result.output().strip());
    }

    @Test
    @DisplayName("a parameter named like the credentials from the caller never reaches the runner's environment")
    void aCallerCannotSetTheEnvironment() throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("skills/echo_env"));
        Files.writeString(dir.resolve("skill.py"), String.join("\n",
                "import os",
                "def run(params):",
                "    return {'output': str(os.environ.get('LD_PRELOAD')) + ' ' + str('_credentials' in params)}",
                ""));
        var env = new PythonEnvironmentService(new OwnClawConfig());
        var skill = new DynamicSkill("echo_env", "echoes", Map.of(), dir, false, false, 30,
                new ProcessSandbox(env, new ObjectMapper()), env, List.of(), null, List.of(), null, null, null);

        ToolResult result = skill.execute(Map.of("_credentials", Map.of("LD_PRELOAD", "/tmp/evil.so")),
                new ToolExecutionContext("u1", "t1", null, () -> false, null, List.of()));

        assertTrue(result.success(), result.output());
        assertEquals("None False", result.output().strip());
    }
}
