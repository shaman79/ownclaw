package com.ownclaw.agent.tools;

import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.sandbox.ContainerSandbox;
import com.ownclaw.sandbox.SandboxManager;
import com.ownclaw.sandbox.SandboxResult;
import com.ownclaw.skills.PythonEnvironmentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What a skill's run is turned into: everything the process wrote reaches the result, and
 * nothing of it is cut. The run itself is a stand-in runtime answering with what a real one
 * returned -- the thing under test is how {@link DynamicSkill} reads it.
 */
class DynamicSkillOutputTest {

    @TempDir Path tmp;

    /** A container runtime that answers each run with the next of the given results. */
    static final class Runs extends ContainerSandbox {
        final Deque<SandboxResult> results;
        Runs(SandboxResult... r) {
            super(new OwnClawConfig());
            results = new ArrayDeque<>(List.of(r));
        }
        @Override public boolean isAvailable() { return true; }
        @Override public String ensureImage(List<String> packages, String pip, Path dir, String image,
                                            SandboxManager.ProgressCallback cb) { return "test-image"; }
        @Override public SandboxResult execute(String image, String python, Path script, Path dir,
                                               String stdinJson, Map<String, String> env, int timeout,
                                               SandboxManager.ProgressCallback cb,
                                               Map<String, String> extraVolumes) {
            return results.poll();
        }
    }

    private ToolResult run(PythonEnvironmentService env, SandboxResult... results) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("skills/net_scan"));
        Files.writeString(dir.resolve("skill.py"), "def run(params):\n    return {}\n");
        var skill = new DynamicSkill("net_scan", "scans the network", Map.of(), dir, false, false, 30,
                null, env, List.of(), null, List.of("nmap"), null, new Runs(results), null);
        return skill.execute(Map.of(), new ToolExecutionContext("u1", "t1", null, () -> false,
                null, List.of()));
    }

    private ToolResult run(SandboxResult... results) throws Exception {
        return run(new PythonEnvironmentService(new OwnClawConfig()), results);
    }

    private static SandboxResult exited(int code, String stdout, String stderr) {
        return new SandboxResult(code, stdout, stderr, 5, false);
    }

    @Test
    @DisplayName("stderr is kept when the skill fails, not only when it succeeds")
    void stderrOnFailure() throws Exception {
        var r = run(exited(0, "{\"success\": false, \"output\": \"could not reach the router\"}",
                "WARNING urllib3: Retrying (Retry(total=2)) after connection broken\n"));
        assertFalse(r.success());
        assertTrue(r.output().startsWith("could not reach the router"), r.output());
        assertTrue(r.output().contains("[stderr: WARNING urllib3: Retrying (Retry(total=2))"),
                "a failure is exactly when its warnings are the evidence: " + r.output());
    }

    @Test
    @DisplayName("stdout printed before the result line is kept with the result")
    void earlierStdoutIsKept() throws Exception {
        String nmap = "Starting Nmap 7.94\nNmap scan report for 192.0.2.10\n"
                + "Host is up (0.0021s latency).";
        var r = run(exited(0, nmap + "\n{\"success\": true, \"output\": \"1 host up\"}\n", ""));
        assertTrue(r.success(), r.output());
        assertTrue(r.output().startsWith("1 host up"), r.output());
        assertTrue(r.output().contains("[skill stdout: " + nmap + "]"),
                "what a subprocess wrote to stdout is the skill's output too: " + r.output());
    }

    @Test
    @DisplayName("a stalled run reports what it wrote before it went quiet")
    void aStallKeepsItsPartialOutput() throws Exception {
        var r = run(new SandboxResult(-1, "scanning 192.0.2.0/28\n",
                "Traceback: waiting on socket.recv\n", 30_000, true));
        assertFalse(r.success());
        assertTrue(r.output().startsWith("Skill 'net_scan' stalled (no output for 30s)."), r.output());
        assertTrue(r.output().contains("[stdout: scanning 192.0.2.0/28]"), r.output());
        assertTrue(r.output().contains("[stderr: Traceback: waiting on socket.recv]"), r.output());
    }

    @Test
    @DisplayName("a skill's data dict is part of its output")
    void dataIsFoldedIntoTheOutput() throws Exception {
        var r = run(exited(0, "{\"success\": true, \"output\": \"3 hosts up\", "
                + "\"data\": {\"hosts\": [\"192.0.2.1\", \"192.0.2.7\", \"192.0.2.9\"]}}", ""));
        assertTrue(r.success());
        assertEquals("3 hosts up\n[data: {\"hosts\":[\"192.0.2.1\",\"192.0.2.7\",\"192.0.2.9\"]}]",
                r.output(), "kept on the side, no record and no prompt ever showed it");
    }

    @Test
    @DisplayName("output that is not JSON is shown whole")
    void nonJsonOutputIsWhole() throws Exception {
        String noise = "Segmentation fault in libpcap while opening eth0\n".repeat(40) + "THE LAST LINE";
        var r = run(exited(0, noise, ""));
        assertFalse(r.success());
        assertTrue(r.output().contains(noise.strip()), "500 characters of it used to be all: " + r.output());
    }

    @Test
    @DisplayName("a crash keeps its stdout beside its stderr")
    void aCrashKeepsStdout() throws Exception {
        var r = run(exited(139, "partial table of 12 hosts", "Fatal Python error: Segmentation fault"));
        assertFalse(r.success());
        assertEquals("Fatal Python error: Segmentation fault\n[stdout: partial table of 12 hosts]", r.output());
    }

    @Test
    @DisplayName("after a self-heal, the retry's own failure is what is reported")
    void theRetrysFailureIsReported() throws Exception {
        var installs = new PythonEnvironmentService(new OwnClawConfig()) {
            @Override public boolean installPackages(Path skillDir, String skillName, List<String> packages) {
                return true;
            }
        };
        var r = run(installs,
                exited(1, "", "ModuleNotFoundError: No module named 'scapy'"),
                exited(1, "", "PermissionError: raw sockets need root"));
        assertFalse(r.success());
        assertTrue(r.output().startsWith("PermissionError: raw sockets need root"),
                "the package is installed by then; reporting the missing module again hides the "
                        + "error that is left: " + r.output());
    }
}
