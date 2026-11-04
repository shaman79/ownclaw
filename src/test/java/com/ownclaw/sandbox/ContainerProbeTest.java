package com.ownclaw.sandbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The container runtime is asked about at every start; asking must never hold the start up. On
 * 2026-11-04 podman's version check took 20-30 s to fail under NoNewPrivileges -- the probe's
 * timeout never fired, since it read the output to its end before waiting -- and the deploy's
 * health check gave up on a build that was fine.
 */
class ContainerProbeTest {

    @TempDir Path tmp;

    private Path runtime(String body) throws Exception {
        Path p = Files.writeString(tmp.resolve("fake-runtime"), "#!/bin/sh\n" + body + "\n");
        Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rwx------"));
        return p;
    }

    @Test
    @DisplayName("a runtime command that hangs is killed at the timeout, not waited for")
    void aHangingCommandIsCutAtTheTimeout() throws Exception {
        Path rt = runtime("echo starting; exec sleep 30");
        long start = System.nanoTime();
        var p = ContainerSandbox.probe(List.of(rt.toString(), "version"), 2);
        long ms = (System.nanoTime() - start) / 1_000_000;
        assertNull(p.exit(), "a hanging command counted as ended");
        assertTrue(ms < 6000, "the probe waited " + ms + " ms");
    }

    @Test
    @DisplayName("what a hanging command started is killed with it")
    void aHangingCommandsChildrenAreKilledToo() throws Exception {
        Path pidFile = tmp.resolve("child.pid");
        Path rt = runtime("sleep 30 & echo $! > '" + pidFile + "'; wait");
        var p = ContainerSandbox.probe(List.of(rt.toString(), "version"), 2);
        assertNull(p.exit());
        long pid = Long.parseLong(Files.readString(pidFile).strip());
        var child = ProcessHandle.of(pid);
        long deadline = System.nanoTime() + 3_000_000_000L;
        while (child.map(ProcessHandle::isAlive).orElse(false) && System.nanoTime() < deadline) Thread.sleep(50);
        assertFalse(child.map(ProcessHandle::isAlive).orElse(false), "the child of a timed-out probe is still running");
    }

    @Test
    @DisplayName("a command that ends while something it started holds its output open still answers at once")
    void aLingeringChildDoesNotHoldTheAnswer() throws Exception {
        Path rt = runtime("echo ok; (sleep 30 &) ; exit 0");
        long start = System.nanoTime();
        var p = ContainerSandbox.probe(List.of(rt.toString(), "version"), 5);
        long ms = (System.nanoTime() - start) / 1_000_000;
        assertEquals(0, p.exit());
        assertTrue(ms < 6000, "the probe waited " + ms + " ms for the child");
    }

    @Test
    @DisplayName("a runtime that is not there answers as not there, at once")
    void anAbsentRuntime() {
        var p = ContainerSandbox.probe(List.of(tmp.resolve("no-such-runtime").toString(), "version"), 5);
        assertNotNull(p.exit());
        assertNotEquals(0, p.exit());
    }

    @Test
    @DisplayName("no_new_privs is read from /proc/self/status")
    void noNewPrivilegesIsRead() {
        assertTrue(ContainerSandbox.noNewPrivileges("Name:\tjava\nNoNewPrivs:\t1\nSeccomp:\t0\n"));
        assertFalse(ContainerSandbox.noNewPrivileges("Name:\tjava\nNoNewPrivs:\t0\n"));
        assertFalse(ContainerSandbox.noNewPrivileges(""));
    }
}
