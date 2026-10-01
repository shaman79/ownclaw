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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

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
    private final ChatStatusEmitter emitter = new ChatStatusEmitter();
    private final List<JsonNode> sent = new CopyOnWriteArrayList<>();
    /** The conversation store the chat is given; a test may hand it one that fails. */
    private Function<JdbcTemplate, ConversationService> store = db -> new ConversationService(db);
    /** Frames the socket refuses, as Tomcat's does: with an IllegalStateException. */
    private Predicate<JsonNode> refused = frame -> false;
    /** Called with each frame as the socket is sending it, before it is taken. */
    private Consumer<JsonNode> whileSending = frame -> {};
    /** Set while the socket is sending a frame: a second send then is refused, as Tomcat refuses it. */
    private final AtomicBoolean writing = new AtomicBoolean();

    private void connect(Path tmp) throws Exception {
        jdbc = MigratedDatabase.at(tmp.resolve("t.db"));
        conversations = store.apply(jdbc);
        var auth = new AuthService(null, null, new OwnClawConfig()) {
            @Override public Optional<String> validateToken(String token) { return Optional.of(USER); }
        };
        var wizard = new SetupWizardService(null, new OwnClawConfig(), null, null, null, null) {
            @Override public boolean isSetupNeeded() { return false; }
        };
        var interactions = new SkillInteractionHandler();
        var commands = new CommandHandler(null, conversations, null, null, null, null, queue, null, null, null,
                null, null, new ResultDelivery(conversations, emitter), interactions);
        chat = new ChatWebSocketHandler(queue, null, conversations, emitter, commands, wizard, auth,
                interactions, null, null, mapper);
        Map<String, Object> attributes = new HashMap<>();
        socket = (WebSocketSession) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{WebSocketSession.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getAttributes" -> attributes;
                    case "getUri" -> URI.create("ws://localhost/ws/chat?token=t");
                    case "isOpen" -> true;
                    case "sendMessage" -> {
                        JsonNode frame = mapper.readTree(String.valueOf(((TextMessage) args[0]).getPayload()));
                        if (!writing.compareAndSet(false, true)) {
                            throw new IllegalStateException("The remote endpoint was in state "
                                    + "[TEXT_PARTIAL_WRITING] which is an invalid state for called method");
                        }
                        try {
                            if (refused.test(frame)) {
                                throw new IllegalStateException("The remote endpoint was in state "
                                        + "[TEXT_FULL_WRITING] which is an invalid state for called method");
                            }
                            whileSending.accept(frame);
                            sent.add(frame);
                        } finally {
                            writing.set(false);
                        }
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

    @Test
    @DisplayName("an answer the page cannot be sent is kept once, and nothing says the task failed")
    void anUnsentAnswerIsKeptOnce(@TempDir Path tmp) throws Exception {
        connect(tmp);
        type("check the router");
        String asked = conversations.getCurrentSession(USER);
        refused = frame -> "response".equals(frame.path("type").asText());
        int refreshed = frames("session_updated").size();

        queue.futures.getFirst().complete(AgentResult.completed("the real answer", new AgentTrajectory(), 1));

        assertEquals(List.of("user: check the router", "assistant: the real answer"), jdbc.queryForList(
                "SELECT role || ': ' || content FROM conversations WHERE session_id = ? ORDER BY rowid",
                String.class, asked), "the answer, once, and no failure beside it");
        assertTrue(frames("response").isEmpty(), "the page was not sent one: " + sent);
        assertEquals(refreshed + 1, frames("session_updated").size(), "the sends after it still go out");
        assertEquals(asked, frames("session_updated").getLast().path("content").asText());
    }

    @Test
    @DisplayName("an answer for a chat the owner has left goes with its chat; the list's refresh after it names the chat open now, which the page follows")
    void theRefreshNamesTheOpenChat(@TempDir Path tmp) throws Exception {
        connect(tmp);
        type("check the router");
        String asked = conversations.getCurrentSession(USER);
        chat.handleMessage(socket, new TextMessage("{\"type\":\"new_session\",\"message\":\"\"}"));
        String opened = conversations.getCurrentSession(USER);
        assertNotEquals(asked, opened);

        queue.futures.getFirst().complete(AgentResult.completed("the router is up", new AgentTrajectory(), 1));

        assertEquals(asked, frames("response").getLast().path("sessionId").asText(), "the answer names its chat");
        assertEquals(opened, frames("session_updated").getLast().path("content").asText(),
                "named, the answer's chat took the page there, while what was typed next went to the open one");
        assertEquals(opened, conversations.getCurrentSession(USER), "the open chat is left alone");
    }

    @Test
    @DisplayName("a typed 'ping' is a message like any other; only the page's heartbeat frame is answered with a pong")
    void aTypedPingIsAMessage(@TempDir Path tmp) throws Exception {
        connect(tmp);

        type("Ping");

        assertEquals(List.of("Ping"), queue.messages, "run");
        assertEquals(List.of("Ping"), jdbc.queryForList(
                "SELECT content FROM conversations WHERE role = 'user'", String.class), "and saved");
        assertTrue(frames("pong").isEmpty(), String.valueOf(sent));

        chat.handleMessage(socket, new TextMessage("{\"type\":\"ping\"}"));
        assertEquals(1, frames("pong").size(), String.valueOf(sent));
        assertEquals(List.of("Ping"), queue.messages, "the heartbeat runs nothing");
    }

    @Test
    @DisplayName("an answer that cannot be saved is still sent, and nothing says the task failed")
    void anUnsavedAnswerIsStillSent(@TempDir Path tmp) throws Exception {
        store = db -> new ConversationService(db) {
            @Override
            public String saveMessage(String userId, String sessionId, String role, String content,
                                      List<String> attachmentIds, String taskId, String privateContent) {
                if (role.equals("assistant")) throw new IllegalStateException("database is locked");
                return super.saveMessage(userId, sessionId, role, content, attachmentIds, taskId, privateContent);
            }
        };
        connect(tmp);
        type("check the router");

        queue.futures.getFirst().complete(AgentResult.completed("the real answer", new AgentTrajectory(), 1));

        assertEquals(List.of("the real answer"), frames("response").stream().map(f -> f.path("content").asText()).toList());
    }

    @Test
    @DisplayName("sends to one socket take turns: a pong waits for a status being written, and both arrive")
    void sendsTakeTurns(@TempDir Path tmp) throws Exception {
        connect(tmp);
        var statusInside = new CountDownLatch(1);
        var secondSend = new CountDownLatch(1);
        whileSending = frame -> {
            if (!"status".equals(frame.path("type").asText())) {
                secondSend.countDown();
                return;
            }
            statusInside.countDown();
            try {
                // Long enough for the pong to be sent in the middle, if nothing makes it wait.
                secondSend.await(500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        var task = new Thread(() -> emitter.emit(USER,
                new ChatStatusEmitter.StatusMessage(ChatStatusEmitter.StatusMessage.Type.STEP, "Think", null, "abcd1234")));
        task.start();
        assertTrue(statusInside.await(5, TimeUnit.SECONDS));

        chat.handleMessage(socket, new TextMessage("{\"type\":\"ping\"}"));
        task.join();

        assertEquals(1, frames("status").size(), String.valueOf(sent));
        assertEquals(1, frames("pong").size(), "the pong was refused for coming while the status was written: " + sent);
    }

    @Test
    @DisplayName("a command's reply is shown, not kept: it is no message of the chat's later prompts")
    void commandRepliesAreNotKept(@TempDir Path tmp) throws Exception {
        connect(tmp);
        for (int i = 0; i < 3; i++) conversations.createSession(USER, "Chat " + i);

        type("/history");

        assertTrue(frames("system").stream().anyMatch(f -> f.path("content").asText().contains("Chat 2")),
                String.valueOf(sent));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM conversations", Integer.class),
                "neither the command nor its reply is saved");
    }
}
