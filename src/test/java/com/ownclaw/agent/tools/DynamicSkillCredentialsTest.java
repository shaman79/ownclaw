package com.ownclaw.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.sandbox.JailTestKit;
import com.ownclaw.sandbox.ProcessSandbox;
import com.ownclaw.sandbox.SandboxManager;
import com.ownclaw.sandbox.SandboxResult;
import com.ownclaw.skills.PythonEnvironmentService;
import com.ownclaw.users.CredentialVault;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A skill's credentials go to its runner on stdin, with its input, and from there into the
 * runner's own environment: never into the environment of the process that starts it, never
 * into the params the skill is given, and never into what the caller keeps of the call.
 */
class DynamicSkillCredentialsTest {

    @TempDir Path tmp;

    private final ObjectMapper mapper = new ObjectMapper();

    /** A vault holding these, whoever asks. */
    private static CredentialVault vault(Map<String, String> values) {
        return new CredentialVault(null) {
            @Override
            public Map<String, String> getCredentials(String userId, List<String> keys) {
                return values;
            }
        };
    }

    /** A sandbox that keeps each launch's stdin and environment, and runs nothing. */
    private static final class Recording implements SandboxManager {
        final List<String> stdin = new ArrayList<>();
        final List<Map<String, String>> env = new ArrayList<>();

        @Override
        public SandboxResult execute(String python, Path script, Path dir, String in, Map<String, String> vars,
                                     int timeout, ProgressCallback cb, List<Path> ro) {
            stdin.add(in);
            env.add(Map.copyOf(vars));
            return new SandboxResult(0, "{\"success\": true, \"output\": \"sent\"}", "", 1, false);
        }

        @Override
        public Optional<String> unavailable() {
            return Optional.empty();
        }
    }

    private DynamicSkill skill(SandboxManager sandbox, List<String> credentials, CredentialVault vault,
                               String code) throws Exception {
        Path skillDir = Files.createDirectories(tmp.resolve("skills/generated/mailer"));
        Files.writeString(skillDir.resolve("skill.py"), code);
        var env = new PythonEnvironmentService(new OwnClawConfig(), JailTestKit.off()) {
            @Override
            public PythonResolution resolveExecution(Path dir, String name) {
                return new PythonResolution("python3", Map.of(), null);
            }
        };
        return new DynamicSkill("mailer", "sends mail", Map.of(), skillDir, false, false, 30, sandbox, env,
                credentials, vault, List.of(), null, null, null);
    }

    private static ToolExecutionContext context() {
        return new ToolExecutionContext("u1", "t1", null, () -> false);
    }

    @Test
    @DisplayName("the credentials go in with the input under one key, never in the environment the run is started with; the caller's params stay as they were")
    void onStdinNotInTheEnvironment() throws Exception {
        var sandbox = new Recording();
        var skill = skill(sandbox, List.of("SMTP_PASS", "LD_PRELOAD"),
                vault(Map.of("SMTP_PASS", "cred-canary-51", "LD_PRELOAD", "/x.so")), "def run(params):\n    return {}\n");
        Map<String, Object> params = new HashMap<>(Map.of("to", "owner@example.org"));
        Map<String, Object> before = Map.copyOf(params);

        var result = skill.execute(params, context());

        assertTrue(result.success(), result.output());
        assertEquals(before, params, "the caller's params -- what a task records of the call -- were changed");
        JsonNode in = mapper.readTree(sandbox.stdin.get(0));
        assertEquals(Map.of("SMTP_PASS", "cred-canary-51", "LD_PRELOAD", "/x.so"),
                mapper.convertValue(in.get(DynamicSkill.CREDENTIALS), Map.class));
        assertEquals("owner@example.org", in.get("to").asText());
        assertEquals(Set.of("PYTHONIOENCODING", "PYTHONUTF8"), sandbox.env.get(0).keySet(),
                "the run was started with more than the service's own variables");
    }

    @Test
    @DisplayName("a parameter named like the credentials' key never reaches the runner's environment")
    void aCallerCannotSetTheKey() throws Exception {
        var sandbox = new Recording();
        var skill = skill(sandbox, List.of(), null, "def run(params):\n    return {}\n");

        skill.execute(Map.of(DynamicSkill.CREDENTIALS, Map.of("PYTHONWARNINGS", "error")), context());

        assertEquals(Map.of(), mapper.convertValue(mapper.readTree(sandbox.stdin.get(0)).get(DynamicSkill.CREDENTIALS),
                Map.class));
    }

    @Test
    @DisplayName("run for real: the skill finds its credential in its environment, not in its params, and an error that prints the params carries none")
    void theRunnerTakesThemOutOfTheParams() throws Exception {
        assumeTrue(Files.isExecutable(Path.of("/usr/bin/python3")), "python3 is not available");
        var sandbox = new ProcessSandbox(JailTestKit.off());
        var creds = vault(Map.of("SMTP_PASS", "cred-canary-77"));

        var ok = skill(sandbox, List.of("SMTP_PASS"), creds, """
                import os
                def run(params):
                    return {'output': os.environ.get('SMTP_PASS', 'absent') + ' ' + repr(sorted(params))}
                """).execute(Map.of("to", "x"), context());
        assertTrue(ok.success(), ok.output());
        assertEquals("cred-canary-77 ['to']", ok.output());

        var failed = skill(sandbox, List.of("SMTP_PASS"), creds, """
                def run(params):
                    raise RuntimeError('given ' + repr(params))
                """).execute(Map.of("to", "x"), context());
        assertFalse(failed.success(), failed.output());
        assertTrue(failed.output().contains("given {'to': 'x'}"), failed.output());
        assertFalse(failed.output().contains("cred-canary-77") || failed.output().contains(DynamicSkill.CREDENTIALS),
                failed.output());
    }
}
