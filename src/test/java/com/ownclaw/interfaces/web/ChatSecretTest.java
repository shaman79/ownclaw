package com.ownclaw.interfaces.web;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownclaw.conversation.ConversationService;
import com.ownclaw.conversation.MigratedDatabase;
import com.ownclaw.interfaces.CommandHandler;
import com.ownclaw.skillrunner.SkillInteractionHandler;
import com.ownclaw.users.CredentialVault;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** A secret typed into the web chat as a command is stored, and is nowhere else: not on screen, not in history, not in the log. */
class ChatSecretTest {

    static final String SECRET = "hunter2-Xq9";

    @TempDir Path dir;
    JdbcTemplate jdbc;
    final List<String> stored = new ArrayList<>();
    final List<String> sent = new ArrayList<>();
    ChatWebSocketHandler ws;
    WebSocketSession session;
    final ListAppender<ILoggingEvent> logged = new ListAppender<>();
    final ch.qos.logback.classic.Logger wsLog =
            (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ChatWebSocketHandler.class);
    Level before;

    @BeforeEach
    void setUp() throws Exception {
        jdbc = MigratedDatabase.at(dir.resolve("t.db"));
        ws = chatWith(new SkillInteractionHandler());
        Map<String, Object> attrs = new HashMap<>(Map.of("userId", "owner"));
        session = (WebSocketSession) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{WebSocketSession.class}, (p, m, args) -> switch (m.getName()) {
                    case "getAttributes" -> attrs;
                    case "isOpen" -> true;
                    case "sendMessage" -> { sent.add(((TextMessage) args[0]).getPayload()); yield null; }
                    case "hashCode" -> System.identityHashCode(p);
                    case "equals" -> p == args[0];
                    default -> null;
                });
        logged.start();
        wsLog.addAppender(logged);
        before = wsLog.getLevel();
        wsLog.setLevel(Level.DEBUG);   // production runs INFO; DEBUG is where the text used to go
    }

    /** The web chat with the real command handler, a vault that records, and this interaction handler. */
    private ChatWebSocketHandler chatWith(SkillInteractionHandler interactions) {
        CredentialVault vault = new CredentialVault(jdbc) {
            @Override public void storeCredential(String userId, String key, String value) {
                stored.add(key + "=" + value);
            }
        };
        CommandHandler commands = new CommandHandler(null, null, null, null, vault, null, null,
                null, null, null, null, null, null);
        return new ChatWebSocketHandler(null, null, new ConversationService(jdbc), null, commands, null,
                null, interactions, null, null, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        wsLog.detachAppender(logged);
        wsLog.setLevel(before);
    }

    private void type(String text) throws Exception {
        ws.handleMessage(session, new TextMessage(new ObjectMapper().writeValueAsString(Map.of("message", text))));
    }

    private void assertNowhere(String secret) {
        for (String s : sent) assertFalse(s.contains(secret), "sent to the page: " + s);
        for (ILoggingEvent e : logged.list) assertFalse(e.getFormattedMessage().contains(secret), "logged: " + e.getFormattedMessage());
        for (Map<String, Object> row : jdbc.queryForList("SELECT content FROM conversations")) {
            assertFalse(String.valueOf(row.get("content")).contains(secret), "kept in history: " + row);
        }
        for (Map<String, Object> row : jdbc.queryForList("SELECT title, preview FROM chat_sessions")) {
            assertFalse(String.valueOf(row).contains(secret), "kept as a chat title: " + row);
        }
    }

    @Test
    @DisplayName("/cred set: the vault gets the value, the page gets the command with the value masked")
    void credSetIsStoredAndMasked() throws Exception {
        type("/cred set OPENWRT_PASS " + SECRET);
        assertEquals(List.of("OPENWRT_PASS=" + SECRET), stored);
        assertTrue(sent.getFirst().contains("\"type\":\"user\"") && sent.getFirst().contains("/cred set OPENWRT_PASS •"),
                "the bubble the page draws: " + sent);
        assertNowhere(SECRET);
        assertTrue(logged.list.stream().anyMatch(e -> e.getFormattedMessage().contains(" chars")),
                "the debug line says how long the message was, and nothing of it");
    }

    @Test
    @DisplayName("any spacing or letter case that stores the value also masks it")
    void everyStoredFormIsMasked() throws Exception {
        for (String form : List.of("/CRED SET k1 %s", "/cred set k2 %s", "/cred\tset k3 %s",
                "/Cred  set  k4  %s  ", "/cred set k5\n%s")) {
            type(form.formatted(SECRET));
        }
        assertEquals(5, stored.size(), "stored: " + stored);
        assertNowhere(SECRET);
    }

    @Test
    @DisplayName("the page draws a slash text only from the server's echo, never as typed")
    void thePageDrawsNoSlashTextItself() throws Exception {
        String page;
        try (var in = ChatSecretTest.class.getResourceAsStream("/static/index.html")) {
            assertNotNull(in, "static/index.html is not on the classpath");
            page = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        // No JS engine in the suite, so the one line of send() that draws the user's bubble.
        assertTrue(page.contains("if (text.charAt(0) !== '/') addMsg('user', display);"),
                "send() must leave a slash text to the echo, which masks what CommandHandler stores");
        assertFalse(page.contains("\n        addMsg('user', display);"), "send() draws every text as typed");
    }

    @Test
    @DisplayName("a mistyped command is not repeated back, kept or sent on: the reply does not quote it")
    void unknownCommandsAreNotRepeated() throws Exception {
        type("/creds set OPENWRT_PASS " + SECRET);
        type("/users add bob " + SECRET);
        assertTrue(stored.isEmpty());
        assertNowhere(SECRET);
        assertTrue(sent.stream().anyMatch(s -> s.contains("\"type\":\"user\"") && s.contains("/creds …")),
                "the page's bubble names the command it did not know: " + sent);
        assertTrue(sent.stream().anyMatch(s -> s.contains(CommandHandler.UNKNOWN_COMMAND)), String.valueOf(sent));
    }

    @Test
    @DisplayName("a mistyped command spaced with no-break spaces is not repeated either")
    void unknownCommandsWithOtherSpacesAreNotRepeated() throws Exception {
        type("/creds\u00a0set\u00a0OPENWRT_PASS\u00a0" + SECRET);
        type("/creds\u2003set OPENWRT_PASS " + SECRET);
        assertTrue(stored.isEmpty());
        assertNowhere(SECRET);
        assertEquals(2, sent.stream().filter(s -> s.contains("\"type\":\"user\"") && s.contains("/creds \u2026")).count(),
                "the bubble is the first word, however the words are spaced: " + sent);
    }

    @Test
    @DisplayName("a slash text that answers a waiting question is shown as typed and handed to the question")
    void anAnswerThatStartsWithASlash() throws Exception {
        List<String> answers = new ArrayList<>();
        ws = chatWith(new SkillInteractionHandler() {
            @Override public boolean hasPending(String userId) { return true; }
            @Override public boolean provideInput(String userId, String taskId, String input) {
                answers.add(input);
                return true;
            }
        });

        type("/home/me/My Photos");

        assertEquals(List.of("/home/me/My Photos"), answers);
        assertTrue(sent.stream().anyMatch(s -> s.contains("\"type\":\"user\"") && s.contains("/home/me/My Photos")),
                "the bubble is the answer as typed: " + sent);
        assertTrue(sent.stream().noneMatch(s -> s.contains(CommandHandler.UNKNOWN_COMMAND)), String.valueOf(sent));
    }
}
