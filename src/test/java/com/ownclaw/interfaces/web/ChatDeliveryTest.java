package com.ownclaw.interfaces.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownclaw.agent.AgentResult;
import com.ownclaw.agent.AgentTrajectory;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.config.SetupWizardService;
import com.ownclaw.conversation.ConversationService;
import com.ownclaw.conversation.MigratedDatabase;
import com.ownclaw.core.ResultDelivery;
import com.ownclaw.core.TaskQueue;
import com.ownclaw.interfaces.CommandHandler;
import com.ownclaw.observability.ChatStatusEmitter;
import com.ownclaw.skillrunner.SkillInteractionHandler;
import com.ownclaw.users.AuthService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What the web chat receives and keeps, through a connected socket with the real command handler,
 * result delivery and conversation store on the migrated schema. Only the task queue is replaced,
 * by one whose futures the test completes.
 */
class ChatDeliveryTest {

    static final String USER = "u1";

    /** Hands out futures the test completes; runs nothing. */
    static final class Queue extends TaskQueue {
        final List<String> messages = new ArrayList<>();
        final List<CompletableFuture<AgentResult>> futures = new ArrayList<>();

        Queue() {
            super(null, null, null, new OwnClawConfig(), null);
        }

        @Override
        public CompletableFuture<AgentResult> submit(String userId, String message, int priority,
                                                     String currentMessageId, List<String> attachmentIds) {
            messages.add(message);
            var future = new CompletableFuture<AgentResult>();
            futures.add(future);
            return future;
        }

        @Override
        public CompletableFuture<AgentResult> submit(String userId, String message, int priority) {
            return submit(userId, message, priority, null, List.of());
        }
    }

    private final ObjectMapper mapper = new ObjectMapper();
    private JdbcTemplate jdbc;
    private ConversationService conversations;
    private final Queue queue = new Queue();
    private ChatWebSocketHandler chat;
    private WebSocketSession socket;
    private final List<JsonNode> sent = new ArrayList<>();

    private void connect(Path tmp) throws Exception {
        jdbc = MigratedDatabase.at(tmp.resolve("t.db"));
        conversations = new ConversationService(jdbc, null);
        var emitter = new ChatStatusEmitter();
        var auth = new AuthService(null, null, new OwnClawConfig()) {
            @Override public Optional<String> validateToken(String token) { return Optional.of(USER); }
        };
        var wizard = new SetupWizardService(null, new OwnClawConfig(), null, null, null) {
            @Override public boolean isSetupNeeded() { return false; }
        };
        var commands = new CommandHandler(null, conversations, null, null, null, null, queue, null, null, null,
                null, null, new ResultDelivery(conversations, emitter));
        chat = new ChatWebSocketHandler(queue, null, conversations, emitter, commands, wizard, auth,
                new SkillInteractionHandler(null), null, null, mapper);
        Map<String, Object> attributes = new HashMap<>();
        socket = (WebSocketSession) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{WebSocketSession.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getAttributes" -> attributes;
                    case "getUri" -> URI.create("ws://localhost/ws/chat?token=t");
                    case "isOpen" -> true;
                    case "sendMessage" -> {
                        sent.add(mapper.readTree(String.valueOf(((TextMessage) args[0]).getPayload())));
                        yield null;
                    }
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
        chat.afterConnectionEstablished(socket);
    }

    private void type(String text) throws Exception {
        chat.handleMessage(socket, new TextMessage(mapper.writeValueAsString(Map.of("message", text))));
    }

    private List<JsonNode> frames(String type) {
        return sent.stream().filter(f -> type.equals(f.path("type").asText())).toList();
    }

    @Test
    @DisplayName("a message longer than the socket's 8,192-character buffer arrives whole, in fragments")
    void aLongMessageArrivesInFragments(@TempDir Path tmp) throws Exception {
        connect(tmp);
        assertTrue(chat.supportsPartialMessages(), "Tomcat closes the socket (1009) on a longer one otherwise");
        String text = "Please read this log:\n" + "2026-09-30 08:00:00 INFO something happened\n".repeat(600);
        String json = mapper.writeValueAsString(Map.of("message", text));
        assertTrue(json.length() > 8192 * 3);

        int a = json.length() / 3, b = 2 * json.length() / 3;
        chat.handleMessage(socket, new TextMessage(json.substring(0, a), false));
        chat.handleMessage(socket, new TextMessage(json.substring(a, b), false));
        assertEquals(List.of(), queue.messages, "nothing is taken until the last fragment");
        chat.handleMessage(socket, new TextMessage(json.substring(b), true));

        assertEquals(List.of(text), queue.messages, "one message, whole");
        assertEquals(List.of(text), jdbc.queryForList(
                "SELECT content FROM conversations WHERE role = 'user'", String.class));

        type("and a short one");
        assertEquals(List.of(text, "and a short one"), queue.messages, "the next message starts afresh");
    }

    @Test
    @DisplayName("a task that breaks is answered in its chat, and the answer is kept for the reload")
    void aBrokenTaskIsAnsweredAndKept(@TempDir Path tmp) throws Exception {
        connect(tmp);
        type("check the router");
        String asked = conversations.getCurrentSession(USER);

        queue.futures.getFirst().completeExceptionally(new IllegalStateException("database is locked"));

        var reply = frames("response").getLast();
        assertTrue(reply.path("content").asText().startsWith("Something went wrong: "), reply.toString());
        assertEquals(asked, reply.path("sessionId").asText(), "sent with its chat");
        assertEquals(List.of(reply.path("content").asText()), jdbc.queryForList(
                "SELECT content FROM conversations WHERE role = 'assistant' AND session_id = ?", String.class, asked),
                "kept, so a reload still shows why there is no answer");
    }

    @Test
    @DisplayName("/bg delivers into the chat it was typed in, even when another chat is open by then")
    void backgroundResultGoesToItsChat(@TempDir Path tmp) throws Exception {
        connect(tmp);
        String typedIn = conversations.getCurrentSession(USER);
        type("/bg check the weather");
        String openNow = conversations.createSession(USER, "Something else");

        queue.futures.getFirst().complete(AgentResult.completed("Sunny, 21 degrees.", new AgentTrajectory(), 1));

        assertEquals(List.of(typedIn), jdbc.queryForList(
                "SELECT session_id FROM conversations WHERE content LIKE '%Sunny, 21 degrees.%'", String.class));
        var result = frames("result").getLast();
        assertEquals(typedIn, result.path("sessionId").asText(),
                "the page appends it only when that chat is on screen, and marks it otherwise");
        assertTrue(result.path("content").asText().contains("Background task: check the weather"), result.toString());
        assertEquals(openNow, conversations.getCurrentSession(USER), "the open chat is left alone");
    }
}
