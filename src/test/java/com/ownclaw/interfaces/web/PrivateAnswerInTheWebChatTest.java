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
 * The owner's own screens show a private answer -- this web chat, live, when the task ends and
 * when a background result arrives, and (his decision) Telegram. Everything that stores or
 * forwards it -- the saved row's content, history, search -- gets the note that it exists.
 * <p>
 * Driven through a connected socket: afterConnectionEstablished subscribes the chat to status
 * messages, and handleTextMessage submits the message, with a real ConversationService and
 * status emitter on the migrated schema. Only the task queue, the token check and the first-run
 * wizard are replaced.
 */
class PrivateAnswerInTheWebChatTest {

    static final String USER = "u1";
    static final String NOTE = "[Private answer: sent to you only, never to the cloud model.]";
    static final String SECRET = "Closing balance 48,213.07 CZK";
    static final String OWNER = "**Private:**\n\n" + SECRET;

    static AgentResult privateAnswer() {
        return AgentResult.completed(NOTE, new AgentTrajectory(), 1).withOwnerText(OWNER).withTaskId("a1b2c3d4");
    }

    /** Answers every message with a finished private answer; runs nothing. */
    static final class Answering extends TaskQueue {
        Answering() {
            super(null, null, null, new OwnClawConfig(), null);
        }

        @Override
        public CompletableFuture<AgentResult> submit(String userId, String message, int priority,
                                                     String currentMessageId, List<String> attachmentIds) {
            return CompletableFuture.completedFuture(privateAnswer());
        }
    }

    private JdbcTemplate jdbc;
    private ConversationService conversations;
    private ChatStatusEmitter emitter;
    private ChatWebSocketHandler chat;
    private WebSocketSession socket;
    /** Every frame the chat pushed to the browser. */
    private final List<JsonNode> sent = new ArrayList<>();

    private void connect(Path tmp) throws Exception {
        jdbc = MigratedDatabase.at(tmp.resolve("t.db"));
        conversations = new ConversationService(jdbc, null);
        emitter = new ChatStatusEmitter();
        var auth = new AuthService(null, null, new OwnClawConfig()) {
            @Override
            public Optional<String> validateToken(String token) {
                return "t".equals(token) ? Optional.of(USER) : Optional.empty();
            }
        };
        var wizard = new SetupWizardService(null, new OwnClawConfig(), null, null, null) {
            @Override
            public boolean isSetupNeeded() { return false; }
        };
        var mapper = new ObjectMapper();
        chat = new ChatWebSocketHandler(new Answering(), null, conversations, emitter, null, wizard, auth,
                new SkillInteractionHandler(null), null, null, mapper);
        Map<String, Object> attributes = new HashMap<>();
        socket = (WebSocketSession) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{WebSocketSession.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getAttributes" -> attributes;
                    case "getUri" -> URI.create("ws://localhost/ws/chat?token=t");
                    case "getId" -> "ws1";
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

    /** The content of every frame of this type the browser was sent. */
    private List<String> frames(String type) {
        return sent.stream().filter(f -> type.equals(f.path("type").asText()))
                .map(f -> f.path("content").asText()).toList();
    }

    @Test
    @DisplayName("a task's private answer is shown in the chat, and saved as the note beside it")
    void theChatShowsThePrivateAnswer(@TempDir Path tmp) throws Exception {
        connect(tmp);

        chat.handleTextMessage(socket, new TextMessage("{\"message\":\"summarise this statement\",\"attachmentIds\":[]}"));

        assertEquals(List.of(OWNER), frames("response"), "the owner reads the answer, not the note");
        var row = jdbc.queryForMap(
                "SELECT content, private_content FROM conversations WHERE role = 'assistant'");
        assertEquals(NOTE, row.get("content"), "every later prompt reads this column");
        assertEquals(OWNER, row.get("private_content"));
    }

    @Test
    @DisplayName("a background private answer is shown in the chat; the message's text stays the note")
    void aBackgroundPrivateAnswerIsShownInTheChat(@TempDir Path tmp) throws Exception {
        connect(tmp);
        var telegram = new ArrayList<String>();
        emitter.subscribe(USER, "telegram", m -> telegram.add(
                com.ownclaw.interfaces.telegram.TelegramBotService.telegramText(m)));

        new ResultDelivery(conversations, emitter).deliver(USER, "Background task", privateAnswer());

        List<String> shown = frames("result");
        assertEquals(1, shown.size(), String.valueOf(sent));
        assertTrue(shown.get(0).contains(SECRET), "the web chat shows the answer: " + shown);
        assertTrue(telegram.stream().anyMatch(t -> t.contains(SECRET)),
                "Telegram gets the answer too -- the owner's decision: " + telegram);
    }
}
