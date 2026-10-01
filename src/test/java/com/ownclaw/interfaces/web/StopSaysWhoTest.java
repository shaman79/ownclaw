package com.ownclaw.interfaces.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownclaw.core.TaskCancellationService;
import com.ownclaw.interfaces.CommandHandler;
import com.ownclaw.skillrunner.SkillInteractionHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Each way of stopping a task says who stopped it, and the task's ending says it to the owner:
 * the Stop button, /cancel, and the ops API all used to end on the same "Task was cancelled.".
 */
class StopSaysWhoTest {

    private static WebSocketSession socket(String userId) {
        Map<String, Object> attributes = new HashMap<>(Map.of("userId", userId));
        return (WebSocketSession) Proxy.newProxyInstance(StopSaysWhoTest.class.getClassLoader(),
                new Class<?>[]{WebSocketSession.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getAttributes" -> attributes;
                    case "getId" -> "ws-" + userId;
                    case "isOpen" -> true;
                    default -> null;
                });
    }

    @Test
    @DisplayName("the Stop button, /cancel and the ops API each give their own why")
    void eachStopSaysWho() {
        var cancellation = new TaskCancellationService();
        long running = System.currentTimeMillis() - 1;

        var chat = new ChatWebSocketHandler(null, null, null, null, null, null, null,
                new SkillInteractionHandler(), cancellation, null, new ObjectMapper());
        chat.handleTextMessage(socket("u1"), new TextMessage("{\"type\":\"cancel\"}"));
        assertEquals("you pressed Stop", cancellation.why("u1", "t1", running));

        var commands = new CommandHandler(null, null, null, null, null, null, null, null, null, null, null,
                cancellation, null, new SkillInteractionHandler());
        commands.handle("u2", "/cancel");
        assertEquals("you sent /cancel", cancellation.why("u2", "t1", running));

        var ops = new OpsController(null, null, null, null, cancellation, null, null, null, null);
        ops.cancel("u3", "abcd1234");
        assertEquals("a stop request from the ops API", cancellation.why("u3", "abcd1234", System.currentTimeMillis()));
        assertNull(cancellation.why("u3", "other000", running), "one task named, one task stopped");
        ops.cancel("u4", null);
        assertEquals("a stop request from the ops API", cancellation.why("u4", "t1", running));
    }
}
