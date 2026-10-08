package com.ownclaw.agent;

import com.ownclaw.observability.ChatStatusEmitter;
import com.ownclaw.observability.ChatStatusEmitter.StatusMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.ownclaw.agent.LoopRig.*;
import static com.ownclaw.agent.ProgressMessagesTest.PING;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Every status an unattended task -- a scheduled run, /bg -- emits is its own and marked as
 * background work, so a page shows none of it as the working state of the chat it has open; an
 * attended task's are not marked.
 */
class UnattendedStatusTest {

    @Test
    @DisplayName("every status of an unattended task -- its steps, the detail of each, its ending -- carries its id and the mark; an attended task's none")
    void unattendedStatusesAreMarked(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of(PING));
        var seen = rig.statuses();
        rig.cloud.think.add(call("ping", Map.of()));
        rig.cloud.think.add(respond("It answers."));

        AgentResult r = rig.loop.executeFull("u1", "ping the router", true);

        assertEquals("It answers.", r.response());
        for (StatusMessage s : seen) {
            assertEquals(r.taskId(), s.taskId(), "its own: " + s);
            assertEquals(true, s.data() == null ? null : s.data().get(ChatStatusEmitter.BACKGROUND), "marked: " + s);
        }
        var categories = seen.stream().map(s -> s.data().get("category")).filter(Objects::nonNull).toList();
        assertTrue(categories.containsAll(List.of("think", "act", "observe")),
                "the detail of each step, which went out without its task: " + categories);
        assertTrue(seen.stream().anyMatch(s -> s.type() == StatusMessage.Type.COMPLETED), "its ending: " + seen);

        seen.clear();
        rig.cloud.think.add(call("ping", Map.of()));
        rig.cloud.think.add(respond("It answers."));
        rig.turn(rig.chat.createSession("u1", "Router"), "ping the router");
        assertFalse(seen.isEmpty());
        assertTrue(seen.stream().noneMatch(s -> s.data() != null && s.data().containsKey(ChatStatusEmitter.BACKGROUND)),
                "an attended task's statuses are not marked: " + seen);
        // Mutation: emit the think, act and observe detail without the task's id again -> unmarked;
        // mark only from run() -> the ending, emitted after it, is unmarked.
    }

    @Test
    @DisplayName("an unattended task that throws takes its mark with it: a later status under its id is not marked")
    void theMarkGoesWhenTheTaskThrows(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of(PING));
        var seen = rig.statuses();
        // Nothing scripted: the model call throws an AssertionError, which no catch of an Exception stops.
        assertThrows(AssertionError.class, () -> rig.loop.executeFull("u1", "ping the router", true));

        StatusMessage first = seen.getFirst();
        assertEquals(true, first.data().get(ChatStatusEmitter.BACKGROUND), "marked while it ran: " + first);
        rig.emitter.emitForTask("u1", first.taskId(), StatusMessage.Type.WARNING, "Long-running task cancelled.");
        assertNull(seen.getLast().data(), "after it threw: " + seen.getLast());
    }
}
