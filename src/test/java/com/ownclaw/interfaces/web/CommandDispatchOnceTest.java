package com.ownclaw.interfaces.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownclaw.agent.AgentResult;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.conversation.ConversationService;
import com.ownclaw.conversation.MigratedDatabase;
import com.ownclaw.core.TaskQueue;
import com.ownclaw.interfaces.CommandHandler;
import com.ownclaw.skillrunner.SkillInteractionHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A slash command typed in the web chat runs once.
 * <p>
 * The chat first asks the shared CommandHandler whether the text is a command at all, so that an
 * answer to a waiting skill which happens to start with "/" is not refused as an unknown command.
 * Asking runs the command. handleCommand then called the handler again for the text to show, so
 * every shared command ran twice: /bg queued its task twice, /new created two chats, and /files rm
 * deleted the file and then reported that there was no such file.
 * <p>
 * Driven through handleTextMessage, with the real CommandHandler and a real ConversationService on
 * the migrated schema. Only the task queue is replaced, by one that counts and runs nothing.
 */
class CommandDispatchOnceTest {

    private static final String USER = "u1";

    /** The shared handler, recording every time the chat asks it. */
    static final class CountingCommandHandler extends CommandHandler {
        final List<String> asked = new ArrayList<>();

        CountingCommandHandler(ConversationService conversations, TaskQueue queue) {
            super(null, conversations, null, null, null, null, queue, null, null, null, null, null, null);
        }

        @Override
        public Optional<String> handle(String userId, String message) {
            asked.add(message);
            return super.handle(userId, message);
        }
    }

    /** Records what /bg submits. The future never completes, so nothing is delivered. */
    static final class CountingQueue extends TaskQueue {
        final List<String> submitted = new ArrayList<>();

        CountingQueue() {
            super(null, null, null, new OwnClawConfig(), null);
        }

        @Override
        public CompletableFuture<AgentResult> submit(String userId, String message, int priority) {
            submitted.add(message);
            return new CompletableFuture<>();
        }
    }

    private ConversationService conversations;
    private CountingQueue queue;
    private CountingCommandHandler commands;
    private ChatWebSocketHandler chat;
    /** Every frame the chat pushed to the browser, as JSON text. */
    private final List<String> sent = new ArrayList<>();
    private WebSocketSession socket;

    private void start(Path tmp) throws Exception {
        conversations = new ConversationService(MigratedDatabase.at(tmp.resolve("t.db")), null);
        queue = new CountingQueue();
        commands = new CountingCommandHandler(conversations, queue);
        chat = new ChatWebSocketHandler(queue, null, conversations, null, commands, null, null,
                new SkillInteractionHandler(null), null, null, new ObjectMapper());
        Map<String, Object> attributes = new HashMap<>(Map.of("userId", USER));
        socket = (WebSocketSession) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{WebSocketSession.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getAttributes" -> attributes;
                    case "getId" -> "ws1";
                    case "isOpen" -> true;
                    case "sendMessage" -> {
                        sent.add(String.valueOf(((TextMessage) args[0]).getPayload()));
                        yield null;
                    }
                    default -> null;
                });
    }

    /** What the browser sends for a line typed into the chat box. */
    private void type(String text) {
        chat.handleTextMessage(socket, new TextMessage("{\"message\":\"" + text + "\",\"attachmentIds\":[]}"));
    }

    @Test
    @DisplayName("/bg asks the command handler once and queues its task once")
    void backgroundTaskIsQueuedOnce(@TempDir Path tmp) throws Exception {
        start(tmp);

        type("/bg check the weather");

        assertEquals(List.of("check the weather"), queue.submitted);
        assertEquals(List.of("/bg check the weather"), commands.asked);
        // The reply shown is the one the single run returned.
        assertTrue(sent.stream().anyMatch(s -> s.contains("\"system\"") && s.contains("Running in the background")),
                String.valueOf(sent));
    }

    @Test
    @DisplayName("/new creates one chat, not two")
    void newCreatesOneChat(@TempDir Path tmp) throws Exception {
        start(tmp);

        type("/new Taxes");

        long created = conversations.listSessions(USER, false).stream()
                .filter(s -> "Taxes".equals(s.get("title"))).count();
        assertEquals(1, created);
        assertEquals(1, commands.asked.size(), String.valueOf(commands.asked));
    }

    @Test
    @DisplayName("/status and an unknown command are each asked once, and still answered")
    void statusAndUnknownAreAskedOnce(@TempDir Path tmp) throws Exception {
        start(tmp);

        type("/nosuchcommand");
        assertEquals(1, commands.asked.size(), String.valueOf(commands.asked));
        assertTrue(sent.stream().anyMatch(s -> s.contains("Unknown command: /nosuchcommand")), String.valueOf(sent));

        // The web chat adds its own line to the shared status text.
        commands.asked.clear();
        type("/status");
        assertEquals(1, commands.asked.size(), String.valueOf(commands.asked));
        assertTrue(sent.stream().anyMatch(s -> s.contains("Connected sockets: ")), String.valueOf(sent));
    }
}
