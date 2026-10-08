package com.ownclaw.observability;

import com.ownclaw.observability.ChatStatusEmitter.StatusMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The mark on the statuses of unattended work: set on every status of a task while it runs in the
 * background, beside the data the status carries, and gone with the work however it ends.
 */
class ChatStatusEmitterTest {

    private final ChatStatusEmitter emitter = new ChatStatusEmitter();
    private final List<StatusMessage> seen = new CopyOnWriteArrayList<>();

    @BeforeEach
    void listen() {
        emitter.subscribe("u1", "test", seen::add);
    }

    @Test
    @DisplayName("a status of a task running in the background is marked, its own data kept; another task's is not touched")
    void backgroundStatusesAreMarked() {
        Map<String, Object> tokens = Map.of("cloudTokens", 1_200);
        String answer = emitter.inBackground("bg1", () -> {
            emitter.emitForTask("u1", "bg1", StatusMessage.Type.STEP, "Step 1 · anthropic", tokens);
            emitter.emitForTask("u1", "bg1", StatusMessage.Type.STEP, "Running ping...");
            emitter.emitForTask("u1", "chat1", StatusMessage.Type.STEP, "Step 3 · anthropic", tokens);
            return "done";
        });

        assertEquals("done", answer, "the work's own result");
        assertEquals(Map.of("cloudTokens", 1_200, ChatStatusEmitter.BACKGROUND, true), seen.get(0).data());
        assertEquals("bg1", seen.get(0).taskId());
        assertEquals(Map.of(ChatStatusEmitter.BACKGROUND, true), seen.get(1).data(), "a status without data too");
        assertSame(tokens, seen.get(2).data(), "an attended task's status, running beside it, is sent as it was");
        assertEquals(Map.of("cloudTokens", 1_200), tokens, "the caller's map is not written into");
        // Mutation: mark every status while any background work runs -> the chat's step is marked.
    }

    @Test
    @DisplayName("the mark ends with the work, even when the work throws")
    void theMarkEndsWithTheWork() {
        emitter.inBackground("bg1", () -> "done");
        var thrown = assertThrows(IllegalStateException.class, () -> emitter.inBackground("bg2", () -> {
            throw new IllegalStateException("database is locked");
        }));
        assertEquals("database is locked", thrown.getMessage(), "what the work threw reaches its caller");

        emitter.emitForTask("u1", "bg1", StatusMessage.Type.WARNING, "late");
        emitter.emitForTask("u1", "bg2", StatusMessage.Type.WARNING, "late");
        assertNull(seen.get(0).data(), "after the work: " + seen.get(0));
        assertNull(seen.get(1).data(), "after the work that threw: " + seen.get(1));
        // Mutation: remove the id after the work, not in a finally -> bg2 stays marked for good.
    }

    @Test
    @DisplayName("what a running task is doing: its model call under way, else its last step or progress; not another's, not unattended work, not after it ended")
    void whatARunningTaskIsDoing() {
        var call = new java.util.concurrent.atomic.AtomicReference<StatusMessage>();
        emitter.running("u1", "t1", call::get);
        assertNull(emitter.doing("u1"), "nothing emitted yet, no call under way");

        emitter.emitForTask("u1", "t1", StatusMessage.Type.STEP, "Step 2 · anthropic");
        emitter.emitForTask("u1", "t1", StatusMessage.Type.PROGRESS, "Running ping (40s)");
        emitter.emitForTask("u1", "t1", StatusMessage.Type.WARNING, "Daily budget at 80%");
        emitter.emitForTask("u1", "t1", StatusMessage.Type.PROGRESS_MESSAGE, "☁️ Step 2 · ping");
        assertEquals("Running ping (40s)", emitter.doing("u1").text(), "the last step or progress status");
        assertNull(emitter.doing("u2"), "another account's task is not his");

        var live = new StatusMessage(StatusMessage.Type.LIVE, "☁️ Cloud model · step 3 · reasoning · 4s", null, "t1");
        call.set(live);
        assertSame(live, emitter.doing("u1"), "a call under way is what it is doing");

        emitter.running("u1", "bg1", () -> live);
        emitter.ended("t1");
        emitter.inBackground("bg1", () -> {
            assertNull(emitter.doing("u1"), "unattended work is nobody's working state");
            return null;
        });
        emitter.ended("bg1");
        emitter.emitForTask("u1", "t1", StatusMessage.Type.STEP, "late");
        assertNull(emitter.doing("u1"), "nothing is kept of a task that has ended");
        // Mutation: keep every task's status -> "late" is what an ended task is doing.
    }
}
