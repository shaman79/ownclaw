package com.ownclaw.agent;

import com.ownclaw.observability.ChatStatusEmitter;
import com.ownclaw.observability.ChatStatusEmitter.StatusMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static com.ownclaw.agent.LoopRig.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tasks of different chats run side by side, and a page shows a status in the chat of its task
 * alone: every status a chat's task emits names that chat -- its last, emitted once the task has
 * ended, as well.
 */
class StatusesNameTheirChatTest {

    @Test
    @DisplayName("every status of a chat's task names its chat: its steps, its debug frames, and its ending")
    void everyStatusNamesItsChat(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of());
        String chat = rig.chat.createSession("u1", "Network");
        rig.debug.toggle("u1");
        rig.cloud.think.add(respond("the router is up"));
        var seen = rig.statuses();

        AgentResult r = rig.turn(chat, "is the router up?");

        assertEquals("the router is up", r.response());
        var ofTask = seen.stream().filter(s -> r.taskId().equals(s.taskId())).toList();
        assertTrue(ofTask.stream().anyMatch(s -> s.type() == StatusMessage.Type.DEBUG), "debug frames: " + ofTask);
        assertEquals(StatusMessage.Type.COMPLETED, ofTask.getLast().type(), ofTask.toString());
        for (StatusMessage s : ofTask) {
            assertEquals(chat, s.data() == null ? null : s.data().get(ChatStatusEmitter.SESSION),
                    s.type() + " names no chat: " + s.text());
        }
    }
}
