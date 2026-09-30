package com.ownclaw.interfaces.web;

import com.ownclaw.agent.AgentLoop;
import com.ownclaw.agent.AgentResult;
import com.ownclaw.agent.AgentTrajectory;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.conversation.MigratedDatabase;
import com.ownclaw.observability.ChatStatusEmitter;
import com.ownclaw.observability.DebugSessionService;
import com.ownclaw.users.AuthService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** The owner's stored debug traces: kept for a day however many there are, listed whole. */
class DebugControllerTest {

    static final long T0 = Instant.parse("2026-09-30T12:00:00Z").toEpochMilli();

    /** A day, written out: the test pins the duration itself, not whatever a constant says. */
    static final long DAY = 24L * 60 * 60 * 1000;

    @Test
    @DisplayName("a trace is kept for a day, not until fifty newer ones push it out, and listed whole")
    void tracesAreKeptByAgeAndListedWhole(@TempDir Path tmp) throws Exception {
        var jdbc = MigratedDatabase.at(tmp.resolve("t.db"));
        jdbc.update("INSERT INTO users (id, display_name, password_hash) VALUES ('owner', 'owner', 'x')");
        var calls = new AtomicInteger();
        var loop = new AgentLoop(null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null) {
            @Override
            public AgentResult executeFull(String userId, String message) {
                return AgentResult.completed("done", new AgentTrajectory(), 1)
                        .withTaskId(String.format("%08x", calls.incrementAndGet()));
            }
        };
        var clock = new AtomicLong(T0);
        var debug = new DebugController(loop, null, null, new DebugSessionService(), new ChatStatusEmitter(),
                jdbc, new AuthService(jdbc, null, new OwnClawConfig()), clock::get);

        String longMessage = "m".repeat(300) + " END";
        var ids = new ArrayList<String>();
        for (int i = 0; i < 51; i++) {
            @SuppressWarnings("unchecked")
            var trace = (Map<String, Object>) debug.submitPrompt(
                    Map.of("message", i == 0 ? longMessage : "prompt " + i), "owner").getBody();
            ids.add((String) trace.get("taskId"));
        }
        assertEquals("00000001", ids.getFirst(), "the task's own id");
        assertEquals(200, debug.getOutput(ids.getFirst(), "owner").getStatusCode().value(),
                "fifty newer traces do not push the first one out");
        @SuppressWarnings("unchecked")
        var listed = (List<Map<String, Object>>) debug.listTraces("owner").getBody();
        assertEquals(ids, listed.stream().map(t -> t.get("taskId")).toList(), "every trace, oldest first");
        assertEquals(longMessage, listed.getFirst().get("message"), "whole");

        clock.set(T0 + DAY);
        assertEquals(200, debug.getOutput(ids.getFirst(), "owner").getStatusCode().value(),
                "a day after it was stored: kept");
        clock.set(T0 + DAY + 1);
        assertEquals(404, debug.getOutput(ids.getFirst(), "owner").getStatusCode().value(), "past a day: dropped");
        assertEquals(List.of(), debug.listTraces("owner").getBody());
    }
}
