package com.ownclaw.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownclaw.agent.Artifact;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.privacy.Label;
import com.ownclaw.sandbox.ContainerSandbox;
import com.ownclaw.sandbox.ProcessSandbox;
import com.ownclaw.sandbox.SandboxManager;
import com.ownclaw.sandbox.SandboxResult;
import com.ownclaw.skills.PythonEnvironmentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
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

    /** Whether the task would take this output for a success: the one reading every consumer uses. */
    private static boolean succeeded(ToolResult r) {
        return new Artifact(1, "net_scan", Map.of(), Map.of(), r.output(), r.success(), Label.PUBLIC,
                List.of()).succeeded();
    }

    @Test
    @DisplayName("every key the skill returned is part of its output, not only \"output\"")
    void everyReturnedKeyIsKept() throws Exception {
        String line = "{\"success\": true, \"output\": \"Sent\", \"message_id\": \"<m1@example.org>\", "
                + "\"data\": [\"192.0.2.1\", \"192.0.2.7\"]}";
        var r = run(exited(0, line + "\n", ""));
        assertTrue(r.success());
        assertEquals(line, r.output(), "the message id and a data list were dropped");
    }

    @Test
    @DisplayName("a key that holds nothing leaves a text output as it is")
    void anEmptyKeyLeavesTheTextAlone() throws Exception {
        var r = run(exited(0, "{\"success\": true, \"output\": \"3 hosts up\", \"data\": {}}", ""));
        assertEquals("3 hosts up", r.output());
    }

    @Test
    @DisplayName("a data dict beside an empty output is the output, and a success")
    void dataBesideAnEmptyOutputIsASuccess() throws Exception {
        String line = "{\"success\": true, \"output\": \"\", \"data\": {\"hosts\": [\"192.0.2.1\"]}}";
        var r = run(exited(0, line, ""));
        assertTrue(r.success(), r.output());
        assertEquals(line, r.output());
    }

    @Test
    @DisplayName("a dict returned without output is the output as printed, its data in it once")
    void aDictWithoutOutputIsKeptAsPrinted() throws Exception {
        String line = "{\"ok\": true, \"data\": {\"hosts\": [\"192.0.2.1\"]}, \"success\": true}";
        var r = run(exited(0, line, ""));
        assertEquals(line, r.output());
    }

    @Test
    @DisplayName("an output that is a dict stays JSON, so its ok:false still reads as a failure")
    void aDictOutputStaysJson() throws Exception {
        var r = run(exited(0, "{\"success\": true, \"output\": {\"ok\": false, \"error\": \"SMTP 550\"}}", ""));
        assertEquals("{\"ok\":false,\"error\":\"SMTP 550\"}", r.output(), "it became {ok=false, error=SMTP 550}");
        assertFalse(succeeded(r));
    }

    @Test
    @DisplayName("an empty output with stdout printed before it is that stdout, and a success")
    void stdoutBesideAnEmptyOutputIsTheOutput() throws Exception {
        var r = run(exited(0, "3 hosts up\n{\"success\": true, \"output\": \"\"}", ""));
        assertTrue(r.success(), r.output());
        assertEquals("\n[skill stdout: 3 hosts up]", r.output());
    }

    @Test
    @DisplayName("stdout printed before a JSON result goes inside it: ok:false still reads as a failure")
    void earlierStdoutDoesNotHideAnOkFalse() throws Exception {
        var r = run(exited(0, "PING 192.0.2.25 56(84) bytes of data.\n"
                + "{\"success\": true, \"output\": \"{\\\"ok\\\": false, \\\"error\\\": \\\"host down\\\"}\"}", ""));
        assertEquals("{\"ok\": false, \"error\": \"host down\", \"skill stdout\": "
                + "\"PING 192.0.2.25 56(84) bytes of data.\"}", r.output());
        assertFalse(succeeded(r), "text after the object hid its ok:false");
    }

    @Test
    @DisplayName("stderr goes inside a JSON result too, never over a key the skill returned")
    void stderrDoesNotHideAnOkFalse() throws Exception {
        var r = run(exited(0, "{\"ok\": false, \"error\": \"SMTP 550\", \"stderr\": \"its own\", "
                + "\"success\": true}", "DeprecationWarning: ssl.wrap_socket() is deprecated\n"));
        assertEquals("{\"ok\": false, \"error\": \"SMTP 550\", \"stderr\": \"its own\", \"success\": true, "
                + "\"_stderr\": \"DeprecationWarning: ssl.wrap_socket() is deprecated\"}", r.output());
        assertFalse(succeeded(r), "a warning on stderr used to turn a failed send into a sent one");
    }

    @Test
    @DisplayName("the result is the last line: a JSON line before it is the process's own stdout")
    void theLastLineIsTheResult() throws Exception {
        var r = run(exited(0, "{\"type\": \"progress\", \"message\": \"Scanning 1/3\"}\n"
                + "{\"output\": \"curl says hi\"}\n{\"success\": true, \"output\": \"3 hosts up\"}", ""));
        assertTrue(r.success(), "the first line was taken for the result: " + r.output());
        assertEquals("3 hosts up\n[skill stdout: {\"type\": \"progress\", \"message\": \"Scanning 1/3\"}\n"
                + "{\"output\": \"curl says hi\"}]", r.output());
    }

    @Test
    @DisplayName("a stall is not self-healed, whatever its stderr names")
    void aStallIsNotSelfHealed() throws Exception {
        var installs = new ArrayList<List<String>>();
        var env = new PythonEnvironmentService(new OwnClawConfig()) {
            @Override public boolean installPackages(Path skillDir, String skillName, List<String> packages) {
                installs.add(packages);
                return true;
            }
        };
        var r = run(env, new SandboxResult(-1, "", "ModuleNotFoundError: No module named 'scapy'\n",
                30_000, true));
        assertTrue(installs.isEmpty(), "the stall is what ended it, not the module");
        assertTrue(r.output().startsWith("Skill 'net_scan' stalled (no output for 30s)."), r.output());
    }

    // ── the real harness, in a real python3 process ──

    /** {@code code} as skill.py, run through the runner harness by the process sandbox. */
    private ToolResult runPython(String code) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("skills/smtp_send_email"));
        Files.writeString(dir.resolve("skill.py"), code);
        var cfg = new OwnClawConfig();
        cfg.getSandbox().setPythonPath("python3");
        var env = new PythonEnvironmentService(cfg);
        var skill = new DynamicSkill("smtp_send_email", "sends mail", Map.of(), dir, false, true, 30,
                new ProcessSandbox(env, new ObjectMapper()), env, List.of(), null, List.of(), null,
                null, null);
        // No progress callback, as on a self-heal retry: nobody intercepts a progress report.
        return skill.execute(Map.of(), new ToolExecutionContext("u1", "t1", null, () -> false,
                null, List.of()));
    }

    @Test
    @DisplayName("a dict the skill returned after print()ing keeps its ok:false")
    void anEnvelopeSurvivesThePrints() throws Exception {
        var r = runPython("def run(params):\n    print('connecting to 192.0.2.25')\n"
                + "    return {'ok': False, 'error': 'SMTP 550 mailbox unavailable'}\n");
        assertTrue(r.output().contains("SMTP 550 mailbox unavailable"), r.output());
        assertTrue(r.output().contains("connecting to 192.0.2.25"), r.output());
        assertFalse(succeeded(r), "the print()s became the output and the failed send a success: "
                + r.output());
    }

    @Test
    @DisplayName("a progress report nobody intercepted does not become the result")
    void aProgressReportIsNotTheResult() throws Exception {
        var r = runPython("def run(params):\n    report_progress('Scanning 1/3', 33)\n"
                + "    return {'output': '3 hosts up'}\n");
        assertTrue(r.success(), r.output());
        assertTrue(r.output().startsWith("3 hosts up"), r.output());
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
