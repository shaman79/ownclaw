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
                cancellation, null, new SkillInteractionHandler(), null);
        commands.handle("u2", "/cancel");
        assertEquals("you sent /cancel", cancellation.why("u2", "t1", running));

        commands.handle("u5", "/cancel", "chat-A");
        assertEquals("you sent /cancel", cancellation.why("u5", "t1", "chat-A", running), "typed in a chat: that chat's");
        assertNull(cancellation.why("u5", "t2", "chat-B", running), "the other chats' work goes on");
        commands.handle("u5", "/cancel all", "chat-A");
        assertEquals("you sent /cancel all", cancellation.why("u5", "t2", "chat-B", running), "/cancel all: every chat's");

        var ops = new OpsController(null, null, null, null, cancellation, null, null, null, null);
        ops.cancel("u3", "abcd1234");
        assertEquals("a stop request from the ops API", cancellation.why("u3", "abcd1234", System.currentTimeMillis()));
        assertNull(cancellation.why("u3", "other000", running), "one task named, one task stopped");
        ops.cancel("u4", null);
        assertEquals("a stop request from the ops API", cancellation.why("u4", "t1", running));
    }

    @Test
    @DisplayName("the Stop of a page that names its chat stops that chat's work alone")
    void aPagesStopIsItsChats(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) throws Exception {
        var jdbc = com.ownclaw.conversation.MigratedDatabase.at(tmp.resolve("t.db"));
        var conversations = new com.ownclaw.conversation.ConversationService(jdbc);
        String a = conversations.createSession("u1", "A");
        String b = conversations.createSession("u1", "B");
        var cancellation = new TaskCancellationService();
        long running = System.currentTimeMillis() - 1;
        var chat = new ChatWebSocketHandler(null, null, conversations, null, null, null, null,
                new SkillInteractionHandler(), cancellation, null, new ObjectMapper());
        chat.handleTextMessage(socket("u1"), new TextMessage("{\"type\":\"cancel\",\"sessionId\":\"" + a + "\"}"));
        assertEquals("you pressed Stop", cancellation.why("u1", "t1", a, running));
        assertNull(cancellation.why("u1", "t2", b, running), "chat B's task goes on");

        chat.handleTextMessage(socket("u2"), new TextMessage("{\"type\":\"cancel\",\"sessionId\":\"" + b + "\"}"));
        assertNull(cancellation.why("u1", "t2", b, running), "a chat of another user's is none of his to stop");
        assertNull(cancellation.why("u2", "t3", "anything", running),
                "nor does it stop everything of the user who named it: it names no chat of his, as a deleted one");
    }
}
