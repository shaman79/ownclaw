package com.ownclaw.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** A stop says who asked for it, so the task's ending can. */
class TaskCancellationServiceTest {

    @Test
    @DisplayName("a stop for one task carries its why; the first request's why is kept")
    void oneTask() {
        var s = new TaskCancellationService();
        assertNull(s.why("u1", "t1", 0));
        s.request("u1", "t1", "a stop request from the ops API");
        s.request("u1", "t1", "something later");
        assertEquals("a stop request from the ops API", s.why("u1", "t1", 0));
        assertTrue(s.isCancelled("u1", "t1", 0));
        assertNull(s.why("u1", "t2", 0), "another task is not stopped");
        s.clear("u1", "t1");
        assertNull(s.why("u1", "t1", 0), "a new task starts clear");
    }

    @Test
    @DisplayName("Stop covers the tasks already running, with its why, and not the ones started after it")
    void everything() throws Exception {
        var s = new TaskCancellationService();
        long before = System.currentTimeMillis() - 1;
        s.requestAll("u1", "you pressed Stop");
        assertEquals("you pressed Stop", s.why("u1", "t1", before));
        assertNull(s.why("u1", "t1", System.currentTimeMillis() + 1_000), "a task started later runs");
        assertNull(s.why("u2", "t1", before), "another user's tasks run");
        assertEquals("you pressed Stop", s.why("u1", null, before), "a task queued before it, not yet started");
        s.request("u1", "t1", "a stop request from the ops API");
        assertEquals("a stop request from the ops API", s.why("u1", "t1", before), "the task's own request is the more specific");
    }
}
