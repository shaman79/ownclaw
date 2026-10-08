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
}
