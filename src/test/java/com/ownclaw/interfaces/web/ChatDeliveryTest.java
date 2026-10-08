package com.ownclaw.interfaces.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownclaw.agent.AgentResult;
import com.ownclaw.agent.AgentTrajectory;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.config.SetupWizardService;
import com.ownclaw.conversation.ChatOptions;
import com.ownclaw.conversation.ConversationService;
import com.ownclaw.conversation.MigratedDatabase;
import com.ownclaw.core.ResultDelivery;
import com.ownclaw.core.TaskQueue;
import com.ownclaw.core.UserMessage;
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

    /**
     * Hands out futures the test completes, each delivering to its message's answer; runs nothing.
     * A chat message's fate is the one the test sets.
     */
    static final class Queue extends TaskQueue {
        final List<String> messages = new ArrayList<>();
        final List<com.ownclaw.agent.TaskChat.Channel> channels = new ArrayList<>();
        final List<UserMessage> sent = new ArrayList<>();
        /** Whether each chat message was sent to be queued. */
        final List<Boolean> queued = new ArrayList<>();
        final List<CompletableFuture<AgentResult>> futures = new ArrayList<>();
        volatile Fate fate = Fate.STARTED;

        Queue() {
            super(null, null, null, new OwnClawConfig(), null);
        }

        @Override
        public Fate send(UserMessage message, boolean queue) {
            messages.add(message.text());
            channels.add(message.channel());
            sent.add(message);
            queued.add(queue);
            var future = new CompletableFuture<AgentResult>();
            future.thenAccept(message.answer());
            futures.add(future);
            return fate;
        }

        @Override
        public CompletableFuture<AgentResult> submit(String userId, String message, int priority) {
            messages.add(message);
            channels.add(null);
            var future = new CompletableFuture<AgentResult>();
            futures.add(future);
            return future;
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
    /** Frames whose send throws an Error, which no catch of an Exception stops. */
    private Predicate<JsonNode> crashes = frame -> false;
    /** The task queue the chat is given: the one above, unless a test hands it the real one. */
    private Function<JdbcTemplate, TaskQueue> tasks = db -> queue;
    /** Called with each frame as the socket is sending it, before it is taken. */
    private Consumer<JsonNode> whileSending = frame -> {};
    /** Set while the socket is sending a frame: a second send then is refused, as Tomcat refuses it. */
    private final AtomicBoolean writing = new AtomicBoolean();
    /** Whether the chat's account is the owner's. */
    private boolean owner = true;

    private void connect(Path tmp) throws Exception {
        jdbc = MigratedDatabase.at(tmp.resolve("t.db"));
        conversations = store.apply(jdbc);
        TaskQueue taskQueue = tasks.apply(jdbc);
        var auth = new AuthService(null, null, new OwnClawConfig()) {
            @Override public Optional<String> validateToken(String token) { return Optional.of(USER); }
            @Override public boolean isOwner(String userId) { return owner; }
        };
        var wizard = new SetupWizardService(null, new OwnClawConfig(), null, null, null, null) {
            @Override public boolean isSetupNeeded() { return false; }
        };
        var interactions = new SkillInteractionHandler();
        var commands = new CommandHandler(null, conversations, null, null, null, null, taskQueue, null, null, null,
                null, null, new ResultDelivery(conversations, emitter), interactions, null);
        chat = new ChatWebSocketHandler(taskQueue, null, conversations, emitter, commands, wizard, auth,
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
                            if (crashes.test(frame)) throw new StackOverflowError();
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

    /** The chat's rows, as "role: content", in order. */
    private List<String> rows(String session) {
        return jdbc.queryForList("SELECT role || ': ' || content FROM conversations WHERE session_id = ? ORDER BY rowid",
                String.class, session);
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
        // The real queue: what breaks in a task comes back as its ERROR result, never as a
        // future completed exceptionally.
        var loop = new com.ownclaw.agent.AgentLoop(null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null) {
            @Override
            public AgentResult executeFull(String userId, String message, boolean unattended,
                                           String currentMessageId, List<String> attachmentIds,
                                           com.ownclaw.agent.TaskChat.Channel channel,
                                           com.ownclaw.core.Inbox inbox,
                                           com.ownclaw.conversation.ChatOptions chosen) {
                throw new IllegalStateException("database is locked");
            }
        };
        var real = new ArrayList<TaskQueue>();
        tasks = db -> {
            var q = new TaskQueue(loop, new com.ownclaw.observability.EventLogService(db), emitter,
                    new OwnClawConfig(), new com.ownclaw.core.TaskCancellationService());
            real.add(q);
            return q;
        };
        connect(tmp);
        real.getFirst().start();
        try {
            type("check the router");
            String asked = conversations.getCurrentSession(USER);
            for (int i = 0; i < 100 && frames("response").isEmpty(); i++) Thread.sleep(50);

            var reply = frames("response").getLast();
            assertEquals("Internal error: database is locked", reply.path("content").asText(), reply.toString());
            assertEquals(asked, reply.path("sessionId").asText(), "sent with its chat");
            assertEquals(List.of("user: check the router", "assistant: Internal error: database is locked"),
                    rows(asked), "kept, once, so a reload still shows why there is no answer");
        } finally {
            real.getFirst().stop();
        }
    }

    @Test
    @DisplayName("an answer whose sending breaks with an Error is kept once, and nothing says the task failed")
    void anErrorWhileSendingAddsNoFailure(@TempDir Path tmp) throws Exception {
        connect(tmp);
        type("check the router");
        String asked = conversations.getCurrentSession(USER);
        crashes = frame -> "response".equals(frame.path("type").asText());

        queue.futures.getFirst().complete(AgentResult.completed("the real answer", new AgentTrajectory(), 1));

        assertEquals(List.of("user: check the router", "assistant: the real answer"), rows(asked),
                "the task did not fail: its answer is saved, and nothing beside it says it did");
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
    @DisplayName("a web chat task's progress messages reach the page as messages of their chat: the owner's text, when only he may read it")
    void progressReachesThePage(@TempDir Path tmp) throws Exception {
        connect(tmp);
        type("check the router");
        assertEquals(List.of(com.ownclaw.agent.TaskChat.Channel.WEB), queue.channels, "the task knows it came from here");

        var header = Map.<String, Object>of("actor", "cloud", "step", 1, "tool", "ping", "elapsedMs", 2000, "costUsd", 0.01);
        emitter.emitForTask(USER, "abcd1234", ChatStatusEmitter.StatusMessage.Type.PROGRESS_MESSAGE,
                "☁️ Step 1 · ping · 2.0s · $0.01\n\nPinging it.", Map.of("sessionId", "s1", "progress", header));
        emitter.emitForTask(USER, "abcd1234", ChatStatusEmitter.StatusMessage.Type.PROGRESS_MESSAGE,
                "🏠 Result 1 · bank_fetch · 2.1s · $0.01\n\nA private summary, shown only to you.",
                Map.of("sessionId", "s1", "ownerText", "🏠 Result 1 · bank_fetch · 2.1s · $0.01\n\nBalance 48,213.07 CZK"));

        var progress = frames("progress");
        assertEquals(List.of("☁️ Step 1 · ping · 2.0s · $0.01\n\nPinging it.", "🏠 Result 1 · bank_fetch · 2.1s · $0.01\n\nBalance 48,213.07 CZK"),
                progress.stream().map(f -> f.path("content").asText()).toList());
        for (var f : progress) {
            assertEquals("s1", f.path("sessionId").asText());
            assertEquals("abcd1234", f.path("taskId").asText());
        }
        assertEquals("cloud", progress.get(0).path("progress").path("actor").asText(), "the header, for the page to draw");
        assertEquals(1, progress.get(0).path("progress").path("step").asInt());
        assertTrue(progress.get(1).path("progress").isMissingNode(), "none when the message carries none");
        assertTrue(frames("status").stream().noneMatch(f -> f.path("content").asText().contains("Step 1 · ping")),
                "not an entry of the activity strip");

        emitter.emitForTask(USER, "abcd1234", ChatStatusEmitter.StatusMessage.Type.PROGRESS_MESSAGE,
                "☁️ Step 2 · your message · 3.0s · $0.01\n\nRead your message: part of the task from this step on.",
                Map.of("sessionId", "s1", "progress", header, "read", List.of("row7")));
        assertEquals("row7", frames("progress").getLast().path("read").path(0).asText(),
                "the rows the task read, for the page to mark under their bubbles");
    }

    @Test
    @DisplayName("a status of unattended work reaches the page with its mark; a page is told of attended work alone as going on")
    void unattendedWorkReachesThePageMarked(@TempDir Path tmp) throws Exception {
        var real = new ArrayList<TaskQueue>();
        tasks = db -> {
            var q = new TaskQueue(null, new com.ownclaw.observability.EventLogService(db), emitter,
                    new OwnClawConfig(), new com.ownclaw.core.TaskCancellationService());
            q.submit(USER, "morning digest", TaskQueue.BACKGROUND_PRIORITY);   // not started: it waits
            real.add(q);
            return q;
        };
        connect(tmp);
        assertFalse(frames("session_info").getLast().path("taskRunning").asBoolean(true),
                "a scheduled run waiting starts no spinner in the chat this page opens");

        real.getFirst().submit(USER, "check the router", 1);
        chat.handleMessage(socket, new TextMessage(mapper.writeValueAsString(
                Map.of("type", "switch_session", "message", conversations.getCurrentSession(USER)))));
        assertTrue(frames("session_info").getLast().path("taskRunning").asBoolean(false),
                "a task asked for, waiting behind it, does");

        emitter.inBackground("bg123456", () -> {
            emitter.emitForTask(USER, "bg123456", ChatStatusEmitter.StatusMessage.Type.STEP, "Step 1 · anthropic",
                    Map.of("cloudTokens", 1_200));
            return null;
        });
        var status = frames("status").getLast();
        assertEquals("bg123456", status.path("taskId").asText(), status.toString());
        assertTrue(status.path("data").path(ChatStatusEmitter.BACKGROUND).asBoolean(false),
                "the mark the page shows it apart by: " + status);
        assertEquals(1_200, status.path("data").path("cloudTokens").asInt(), "beside the data it carries");
        // Mutation: count unattended work as running again -> taskRunning is true at connect.
    }

    @Test
    @DisplayName("on connect and on opening a chat, the page is told what the running task is doing now: its model call under way, else its last step")
    void sessionInfoSaysWhatTheTaskIsDoing(@TempDir Path tmp) throws Exception {
        tasks = db -> {
            var q = new TaskQueue(null, new com.ownclaw.observability.EventLogService(db), emitter,
                    new OwnClawConfig(), new com.ownclaw.core.TaskCancellationService());
            q.submit(USER, "audit the routers", 1);   // attended, not started: the page's working state
            return q;
        };
        var call = new java.util.concurrent.atomic.AtomicReference<ChatStatusEmitter.StatusMessage>();
        var line = Map.<String, Object>of("live", Map.of("summary",
                "🏠 Local model · step 6, delegation turn 1 · reasoning · 23m 5s · 41,200 characters so far",
                "line", "Let me check the second router.", "ended", false));
        call.set(new ChatStatusEmitter.StatusMessage(ChatStatusEmitter.StatusMessage.Type.LIVE,
                "🏠 Local model · step 6, delegation turn 1 · reasoning · 23m 5s · 41,200 characters so far "
                        + "· “Let me check the second router.”", line, "t1"));
        emitter.running(USER, "t1", call::get);
        connect(tmp);

        var doing = frames("session_info").getLast().path("doing");
        assertEquals("status", doing.path("type").asText(), doing.toString());
        assertEquals("live", doing.path("status").asText());
        assertEquals("t1", doing.path("taskId").asText());
        assertEquals(call.get().text(), doing.path("content").asText(), "what the next live frame would say");
        assertEquals("Let me check the second router.", doing.path("data").path("live").path("line").asText());

        // A live frame reaches the page as a status of its own, written as it was.
        emitter.emitForTask(USER, "t1", ChatStatusEmitter.StatusMessage.Type.LIVE, call.get().text(), line);
        var frame = frames("status").getLast();
        assertEquals("live", frame.path("status").asText(), frame.toString());
        assertEquals(call.get().text(), frame.path("content").asText(), "it opens with the model's own chip");

        // Between calls: the step it emitted last.
        emitter.emitForTask(USER, "t1", ChatStatusEmitter.StatusMessage.Type.STEP, "Running openwrt_run...");
        call.set(null);
        String open = conversations.getCurrentSession(USER);
        chat.handleMessage(socket, new TextMessage(mapper.writeValueAsString(
                Map.of("type", "switch_session", "message", open))));
        doing = frames("session_info").getLast().path("doing");
        assertEquals("step", doing.path("status").asText(), doing.toString());
        assertEquals("→ Running openwrt_run...", doing.path("content").asText());

        // Ended: nothing more is said of it.
        emitter.ended("t1");
        chat.handleMessage(socket, new TextMessage(mapper.writeValueAsString(
                Map.of("type", "switch_session", "message", open))));
        assertTrue(frames("session_info").getLast().path("doing").isMissingNode(),
                frames("session_info").getLast().toString());
        // Mutation: leave doing out of session_info -> a reload shows a bare spinner.
    }

    /** What the page sends for a message: the text, the name it gave the bubble, and queue when asked. */
    private void sendFromPage(String text, String clientId, boolean queued) throws Exception {
        var json = new java.util.LinkedHashMap<String, Object>(Map.of("message", text, "clientId", clientId));
        if (queued) json.put("queue", true);
        chat.handleMessage(socket, new TextMessage(mapper.writeValueAsString(json)));
    }

    @Test
    @DisplayName("what became of a message is sent back under the page's name for its bubble, with its row; nothing is run twice")
    void theFateComesBackToItsBubble(@TempDir Path tmp) throws Exception {
        connect(tmp);
        queue.fate = TaskQueue.Fate.STEERED;
        sendFromPage("use the backup link", "m7", false);

        var fate = frames("fate").getLast();
        assertEquals("steered", fate.path("fate").asText());
        assertEquals(TaskQueue.Fate.STEERED.line(), fate.path("content").asText(), "the line the page shows");
        assertEquals("m7", fate.path("clientId").asText());
        assertEquals(jdbc.queryForObject("SELECT session_id FROM conversations WHERE role = 'user'", String.class),
                fate.path("sessionId").asText(), "the chat where Send reached the task: where the page offers it again");
        assertFalse(fate.path("queue").asBoolean(true), "sent with Send");
        String row = jdbc.queryForObject("SELECT id FROM conversations WHERE role = 'user'", String.class);
        assertEquals(row, fate.path("messageId").asText(), "its row, which the read progress row will name");
        assertEquals(List.of(false), queue.queued, "sent to the running task, not queued");
        assertEquals(row, queue.sent.getFirst().messageId(), "saved first, then sent");

        queue.fate = TaskQueue.Fate.STARTED;
        sendFromPage("hello", "m8", false);
        assertTrue(frames("fate").getLast().path("content").isMissingNode(), "nothing to say when it just starts");
    }

    @Test
    @DisplayName("Queue asks for a task of its own; so does a typed /queue, drawn by the server's echo under the page's name")
    void queueAsksForATaskOfItsOwn(@TempDir Path tmp) throws Exception {
        connect(tmp);
        queue.fate = TaskQueue.Fate.QUEUED;
        sendFromPage("check the printer", "m1", true);
        sendFromPage("/queue check the scanner", "m2", false);

        assertEquals(List.of("check the printer", "check the scanner"), queue.messages);
        assertEquals(List.of(true, true), queue.queued);
        var echo = frames("user").getLast();
        assertEquals("/queue check the scanner", echo.path("content").asText(), "the page draws no slash text itself");
        assertEquals("m2", echo.path("clientId").asText());
        assertEquals("m2", frames("fate").getLast().path("clientId").asText());
        assertTrue(frames("fate").getLast().path("queue").asBoolean(), "queued as asked: it says nothing of the running task");
        assertEquals(List.of("check the printer", "check the scanner"), jdbc.queryForList(
                "SELECT content FROM conversations WHERE role = 'user' ORDER BY rowid", String.class),
                "the message is saved, without the command");

        sendFromPage("/queue", "m3", false);
        sendFromPage("/queue /cred set SMTP_PASS hunter2", "m4", false);
        assertEquals(2, queue.messages.size(), "neither is a message to run: " + queue.messages);
        assertTrue(frames("system").stream().anyMatch(f -> f.path("content").asText().startsWith("Usage: `/queue <message>`")));
        assertTrue(sent.stream().noneMatch(f -> f.toString().contains("hunter2")), "a command where the message goes is not repeated");
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
    @DisplayName("what a message is sent with is its task's and, from then on, its chat's -- over what the chat chose before; the fate says it")
    void theOptionsAMessageIsSentWith(@TempDir Path tmp) throws Exception {
        connect(tmp);
        String session = conversations.getCurrentSession(USER);
        conversations.setChatOptions(USER, session, new ChatOptions("free", "low"));

        chat.handleMessage(socket, new TextMessage(mapper.writeValueAsString(
                Map.of("message", "check the routers", "clientId", "m1", "costMode", "fast"))));

        var fast = new ChatOptions("fast", null);
        assertEquals(fast, queue.sent.getLast().options(), "the message's choice, the default effort with it");
        assertEquals(fast, conversations.chatOptions(USER, session), "kept for the chat's next messages");
        var fate = frames("fate").getLast();
        assertEquals("fast", fate.path("options").path("costMode").asText());
        assertTrue(fate.path("options").path("effort").isNull(), fate.toString());

        type("and the printer");
        assertEquals(ChatOptions.NONE, queue.sent.getLast().options(), "sent with nothing chosen: the defaults");
        assertEquals(ChatOptions.NONE, conversations.chatOptions(USER, session));

        chat.handleMessage(socket, new TextMessage(mapper.writeValueAsString(
                Map.of("message", "/queue then the switch", "clientId", "m3", "effort", "medium"))));
        assertEquals(new ChatOptions(null, "medium"), queue.sent.getLast().options(), "a queued message too");

        chat.handleMessage(socket, new TextMessage(mapper.writeValueAsString(
                Map.of("message", "/history", "clientId", "m4", "costMode", "free"))));
        assertEquals(new ChatOptions(null, "medium"), conversations.chatOptions(USER, session),
                "a command runs no task: the chat's choice stays");
        // Mutation: build the message without what it was sent with -> its task runs on the defaults.
    }

    @Test
    @DisplayName("another account's chat runs on the owner's defaults: what its message is sent with is neither its task's nor kept")
    void anotherAccountsChoiceIsNotTaken(@TempDir Path tmp) throws Exception {
        owner = false;
        connect(tmp);
        String session = conversations.getCurrentSession(USER);

        chat.handleMessage(socket, new TextMessage(mapper.writeValueAsString(
                Map.of("message", "check the routers", "clientId", "m1", "costMode", "fast", "effort", "high"))));

        assertEquals(ChatOptions.NONE, queue.sent.getLast().options(), "the settings page and /local are the owner's");
        assertEquals(ChatOptions.NONE, conversations.chatOptions(USER, session), "nor kept for its Telegram messages");
    }

    @Test
    @DisplayName("a frame showing a kept row says when the row was saved, as the chat's history reads it; one not kept says nothing")
    void framesSayWhenTheirRowWasSaved(@TempDir Path tmp) throws Exception {
        connect(tmp);
        MigratedDatabase.eachRowAtItsOwnMoment(jdbc);
        String asked = conversations.getCurrentSession(USER);
        sendFromPage("check the router", "m1", false);
        queue.futures.getFirst().complete(AgentResult.completed("the router is up", new AgentTrajectory(), 1));
        queue.fate = TaskQueue.Fate.QUEUED;
        sendFromPage("/queue and the printer", "m2", false);
        type("/bg check the weather");
        queue.futures.getLast().complete(AgentResult.completed("Sunny, 21 degrees.", new AgentTrajectory(), 1));

        var saved = new HashMap<String, Object>();
        for (var row : conversations.getSessionMessages(USER, asked, null, null).messages()) {
            saved.put(String.valueOf(row.get("content")), row.get("timestamp"));
        }
        assertEquals(4, saved.size(), String.valueOf(saved));
        assertEquals(4, new java.util.HashSet<>(saved.values()).size(), "each at its own moment: " + saved);
        var fates = frames("fate");
        assertEquals(saved.get("check the router"), fates.get(0).path("timestamp").asText(), "the message's: " + fates);
        assertEquals(saved.get("and the printer"), fates.get(1).path("timestamp").asText(), "a queued one's: " + fates);
        assertEquals(saved.get("the router is up"), frames("response").getLast().path("timestamp").asText(), "the answer's");
        String result = frames("result").getLast().path("content").asText();
        assertEquals(saved.get(result), frames("result").getLast().path("timestamp").asText(), "a background result's");
        assertTrue(String.valueOf(saved.get(result)).matches("\\d{4}-\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d"),
                "as SQLite keeps it, UTC with no zone, which the page reads as UTC: " + saved.get(result));

        emitter.emitForTask(USER, "abcd1234", ChatStatusEmitter.StatusMessage.Type.PROGRESS_MESSAGE,
                "☁️ Step 1 · ping · 2.0s · $0.01", Map.of("sessionId", asked, "timestamp", "2026-10-08 06:59:08"));
        assertEquals("2026-10-08 06:59:08", frames("progress").getLast().path("timestamp").asText(), "a progress row's");

        assertFalse(frames("system").isEmpty(), "the /bg reply");
        assertFalse(frames("user").isEmpty(), "the /queue echo, which its fate stamps");
        for (var f : sent) {
            if (List.of("system", "user").contains(f.path("type").asText())) {
                assertTrue(f.path("timestamp").isMissingNode(), "not kept: the page's own clock says when: " + f);
            }
        }
        // Mutations: send the answer without the time its row was saved -> the page shows its own
        // clock live and the saved time after a reload; leave it out of the result -> the same.
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
