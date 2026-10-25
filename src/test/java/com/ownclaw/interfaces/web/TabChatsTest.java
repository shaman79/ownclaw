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
import com.ownclaw.core.TaskCancellationService;
import com.ownclaw.interfaces.CommandHandler;
import com.ownclaw.observability.ChatStatusEmitter;
import com.ownclaw.observability.ChatStatusEmitter.StatusMessage;
import com.ownclaw.skillrunner.SkillInteractionHandler;
import com.ownclaw.users.AuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Two browser tabs of one account, each in its own chat: what is typed in a tab is filed in the
 * chat it shows, its Stop stops that chat, a switch in it moves it alone, and what it is told --
 * session info, pongs, statuses -- names the chat it is about.
 */
class TabChatsTest {

    static final String USER = "u1";

    private final ObjectMapper mapper = new ObjectMapper();
    private final ChatStatusEmitter emitter = new ChatStatusEmitter();
    private final TaskCancellationService cancellation = new TaskCancellationService();
    private final ChatDeliveryTest.Queue queue = new ChatDeliveryTest.Queue();
    @TempDir Path tmp;
    private JdbcTemplate jdbc;
    private ConversationService conversations;
    private ChatWebSocketHandler chat;
    /** Two chats of the user's, each open in a tab. */
    private String x, y;

    /** A browser tab: its socket, and the frames sent to it. */
    final class Tab {
        final List<JsonNode> frames = new CopyOnWriteArrayList<>();
        final WebSocketSession socket;

        Tab(String address) throws Exception {
            Map<String, Object> attributes = new HashMap<>();
            socket = (WebSocketSession) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{WebSocketSession.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "getAttributes" -> attributes;
                        case "getUri" -> URI.create(address);
                        case "isOpen" -> true;
                        case "sendMessage" -> {
                            frames.add(mapper.readTree(String.valueOf(((TextMessage) args[0]).getPayload())));
                            yield null;
                        }
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> null;
                    });
            chat.afterConnectionEstablished(socket);
        }

        /** A frame of the page's, with the chat it shows when it names one. */
        void send(String type, String message, String sessionId) throws Exception {
            var json = new LinkedHashMap<String, Object>();
            json.put("type", type);
            json.put("message", message);
            if (sessionId != null) json.put("sessionId", sessionId);
            chat.handleMessage(socket, new TextMessage(mapper.writeValueAsString(json)));
        }

        JsonNode last(String type) {
            return frames.stream().filter(f -> type.equals(f.path("type").asText()))
                    .reduce((a, b) -> b).orElseThrow(() -> new AssertionError("no " + type + " in " + frames));
        }

        String shows() {
            return last("session_info").path("activeSessionId").asText();
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        jdbc = MigratedDatabase.at(tmp.resolve("t.db"));
        conversations = new ConversationService(jdbc);
        var auth = new AuthService(null, null, new OwnClawConfig()) {
            @Override public Optional<String> validateToken(String token) { return Optional.of(USER); }
            @Override public boolean isOwner(String userId) { return true; }
        };
        var wizard = new SetupWizardService(null, new OwnClawConfig(), null, null, null, null) {
            @Override public boolean isSetupNeeded() { return false; }
        };
        var interactions = new SkillInteractionHandler();
        var commands = new CommandHandler(null, conversations, null, null, null, null, queue, null, null, null,
                null, cancellation, new ResultDelivery(conversations, emitter), interactions, null);
        chat = new ChatWebSocketHandler(queue, null, conversations, emitter, commands, wizard, auth,
                interactions, cancellation, null, mapper);
        x = conversations.createSession(USER, "Router");
        y = conversations.createSession(USER, "Lunch");
    }

    private Tab tab(String chatId) throws Exception {
        return new Tab("ws://localhost/ws/chat?token=t" + (chatId == null ? "" : "&chat=" + chatId));
    }

    /** The chat's user rows, in order. */
    private List<String> asked(String session) {
        return jdbc.queryForList("SELECT content FROM conversations WHERE session_id = ? AND role = 'user' ORDER BY rowid",
                String.class, session);
    }

    @Test
    @DisplayName("what is typed in a tab is filed in the chat it shows, and its task is that chat's; the chat last written in is the account's open one")
    void eachTabFilesInItsChat() throws Exception {
        Tab a = tab(x), b = tab(y);
        assertEquals(x, a.shows(), "a reconnecting tab is told about the chat its address names");
        assertEquals(y, b.shows());

        a.send("message", "is the router up?", x);
        b.send("message", "where shall we eat?", y);
        assertEquals(List.of("is the router up?"), asked(x));
        assertEquals(List.of("where shall we eat?"), asked(y));
        assertEquals(x, queue.sent.get(0).sessionId(), "the task is tab A's chat's");
        assertEquals(y, queue.sent.get(1).sessionId());
        assertEquals(x, a.last("fate").path("sessionId").asText());
        assertEquals(y, conversations.getCurrentSession(USER), "Telegram goes on in the chat last written in");

        a.send("message", "and the second one?", null);
        assertEquals(List.of("is the router up?", "and the second one?"), asked(x),
                "a frame naming no chat is in the chat its tab named last");

        String theirs = conversations.createSession("u2", "Theirs");
        a.send("message", "one more", theirs);
        assertEquals(List.of(), asked(theirs), "another account's chat names nothing here");
        assertEquals(3, asked(x).size(), "it stays in the tab's own chat");
    }

    @Test
    @DisplayName("a switch or a new chat in one tab moves that tab alone; a chat that is not the user's is no switch")
    void aSwitchMovesItsTabAlone() throws Exception {
        Tab a = tab(x), b = tab(y);
        String z = conversations.createSession(USER, "Holidays");

        a.send("switch_session", z, x);
        assertEquals(z, a.shows());
        b.send("message", "still lunch", null);
        assertEquals(List.of("still lunch"), asked(y), "the other tab stays in its chat");
        a.send("message", "where to?", null);
        assertEquals(List.of("where to?"), asked(z), "the switched tab is in the chat it opened");

        int infos = a.frames.size();
        String theirs = conversations.createSession("u2", "Theirs");
        a.send("switch_session", theirs, z);
        assertEquals(z, a.shows(), "no switch to another account's chat");
        assertNotEquals(theirs, conversations.getCurrentSession(USER));
        assertEquals(infos, a.frames.size(), "nothing is said of it");

        a.send("new_session", "Plans", z);
        String created = a.shows();
        assertNotEquals(z, created);
        a.send("message", "first plan", null);
        assertEquals(List.of("first plan"), asked(created));
        b.send("message", "and dessert", null);
        assertEquals(List.of("still lunch", "and dessert"), asked(y));
    }

    @Test
    @DisplayName("/new and /switch typed in a tab move that tab; /history does not")
    void sessionCommandsMoveTheirTab() throws Exception {
        Tab a = tab(x), b = tab(y);
        a.send("message", "/history", x);
        assertEquals(x, a.shows(), "a list of chats opens none");

        a.send("message", "/new Plans", x);
        String created = a.shows();
        assertNotEquals(x, created);
        a.send("message", "first plan", null);
        assertEquals(List.of("first plan"), asked(created));

        b.send("message", "/switch 99", y);
        assertEquals(y, b.shows(), "no such chat: the tab stays");
        b.send("message", "still lunch", null);
        assertEquals(List.of("still lunch"), asked(y));
    }

    @Test
    @DisplayName("a tab's session info and pong say whether a task runs in its chat, and in which chats one does")
    void whatRunsIsTheTabsChats() throws Exception {
        queue.busyChats = Set.of(x);
        var call = new java.util.concurrent.atomic.AtomicReference<StatusMessage>(
                new StatusMessage(StatusMessage.Type.LIVE, "🏠 Local model · reasoning", Map.of(), "t1"));
        emitter.running(USER, "t1", x, call::get);
        Tab a = tab(x), b = tab(y);

        assertTrue(a.last("session_info").path("taskRunning").asBoolean(false));
        assertEquals("🏠 Local model · reasoning", a.last("session_info").path("doing").path("content").asText());
        assertFalse(b.last("session_info").path("taskRunning").asBoolean(true), "chat B runs nothing");
        assertTrue(b.last("session_info").path("doing").isMissingNode(), "chat A's task is not what B is doing");
        assertEquals(List.of(x), mapper.convertValue(b.last("session_info").path("workingChats"), List.class),
                "chat A is marked in B's list");

        // A chat whose task waits, not started: nothing is doing anything in it yet -- not chat A's task.
        String z = conversations.createSession(USER, "Holidays");
        queue.busyChats = Set.of(x, z);
        Tab c = tab(z);
        assertTrue(c.last("session_info").path("taskRunning").asBoolean(false));
        assertTrue(c.last("session_info").path("doing").isMissingNode(), c.last("session_info").toString());
        queue.busyChats = Set.of(x);

        a.send("ping", "", x);
        b.send("ping", "", y);
        assertTrue(a.last("pong").path("taskRunning").asBoolean(false));
        assertFalse(b.last("pong").path("taskRunning").asBoolean(true));
        assertEquals(List.of(x), mapper.convertValue(b.last("pong").path("workingChats"), List.class));
        b.send("ping", "", null);
        assertFalse(b.last("pong").path("taskRunning").asBoolean(true), "a ping naming no chat is its tab's");
    }

    @Test
    @DisplayName("statuses and debug frames of a running task name its chat; a frame that names its own chat keeps it")
    void statusesNameTheirChat() throws Exception {
        Tab a = tab(x);
        emitter.running(USER, "t1", x, () -> null);
        emitter.emitForTask(USER, "t1", StatusMessage.Type.STEP, "Running ping");
        assertEquals(x, a.last("status").path("sessionId").asText(), a.last("status").toString());
        assertEquals("t1", a.last("status").path("taskId").asText());

        emitter.emitForTask(USER, "t1", StatusMessage.Type.DEBUG, "### Step 1 — Thinking");
        assertEquals(x, a.last("debug").path("sessionId").asText(), a.last("debug").toString());
        assertEquals("t1", a.last("debug").path("taskId").asText());

        emitter.emitForTask(USER, "t1", StatusMessage.Type.PROGRESS_MESSAGE, "Checked the router",
                Map.of(ChatStatusEmitter.SESSION, y));
        assertEquals(y, a.last("progress").path("sessionId").asText(), "the chat it was saved in");

        emitter.emitForTask(USER, "t2", StatusMessage.Type.STEP, "a task of no chat");
        assertTrue(a.last("status").path("sessionId").isMissingNode(), a.last("status").toString());
        emitter.ended("t1");
        emitter.emitForTask(USER, "t1", StatusMessage.Type.COMPLETED, "after it ended");
        assertTrue(a.last("status").path("sessionId").isMissingNode());
    }

    @Test
    @DisplayName("Stop in a tab stops its chat's work, not the other tab's")
    void stopIsTheTabsChat() throws Exception {
        Tab a = tab(x), b = tab(y);
        long before = System.currentTimeMillis() - 1;
        a.send("cancel", "", x);
        assertNotNull(cancellation.why(USER, "t1", x, before), "chat A's task stops");
        assertNull(cancellation.why(USER, "t2", y, before), "chat B's goes on");

        Thread.sleep(2);
        long later = System.currentTimeMillis() - 1;
        b.send("cancel", "", null);
        assertNotNull(cancellation.why(USER, "t3", y, later), "a Stop naming no chat is its tab's");
        assertNull(cancellation.why(USER, "t4", x, later));
    }

    @Test
    @DisplayName("/bg typed in a tab is answered in that tab's chat, not in the account's open one")
    void backgroundWorkAnswersInItsTab() throws Exception {
        Tab a = tab(x);
        tab(y).send("message", "where shall we eat?", y);   // the account's open chat: Y
        a.send("message", "/bg check the router", x);
        assertEquals("check the router", queue.messages.getLast());
        queue.futures.getLast().complete(AgentResult.completed("the router is up", new AgentTrajectory(), 1));
        assertTrue(jdbc.queryForList("SELECT content FROM conversations WHERE session_id = ?", String.class, x)
                .stream().anyMatch(c -> c.contains("the router is up")), "answered where it was asked");
        assertTrue(jdbc.queryForList("SELECT content FROM conversations WHERE session_id = ?", String.class, y)
                .stream().noneMatch(c -> c.contains("the router is up")));
    }

    @Test
    @DisplayName("a tab whose address names a deleted chat, or none, is told about the account's open chat")
    void aStaleAddressFallsBack() throws Exception {
        assertEquals(y, tab("nosuchchat").shows());
        assertEquals(y, tab(null).shows());
        Tab a = tab(x);
        conversations.deleteSession(USER, x);
        a.send("message", "hello?", null);
        assertEquals(List.of("hello?"), asked(conversations.getCurrentSession(USER)),
                "a deleted chat files nothing: the open one does");
    }
}
