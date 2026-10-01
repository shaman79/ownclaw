package com.ownclaw.interfaces.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.config.SetupWizardService;
import com.ownclaw.conversation.ConversationService;
import com.ownclaw.conversation.MigratedDatabase;
import com.ownclaw.core.TaskCancellationService;
import com.ownclaw.interfaces.CommandHandler;
import com.ownclaw.observability.ChatStatusEmitter;
import com.ownclaw.skillrunner.SkillInteractionHandler;
import com.ownclaw.users.AuthService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * /cancel typed in the web chat while the setup wizard waits for an answer ends the wizard, as
 * Stop does and as Telegram's /cancel did: the wizard says so, and the next message is a message --
 * it used to be taken as the wizard's answer, at the cloud step as the cloud API key.
 */
class CancelEndsTheWizardTest {

    /** Asks for the cloud API key, and records any answer it is given. */
    static final class Wizard extends SetupWizardService {
        final List<String> answers = new CopyOnWriteArrayList<>();

        Wizard() {
            super(null, new OwnClawConfig(), null, null, null, null);
        }

        @Override public boolean isSetupNeeded() { return true; }

        @Override public WizardResponse processStep(int step, String userInput) {
            if (step == 0) return new WizardResponse("Step 2/5 — paste your cloud API key", false);
            answers.add(userInput);
            return new WizardResponse("API key saved", true);
        }
    }

    private final Wizard wizard = new Wizard();
    private final SkillInteractionHandler interactions = new SkillInteractionHandler();
    private final ChatDeliveryTest.Queue queue = new ChatDeliveryTest.Queue();
    private final List<String> sent = new CopyOnWriteArrayList<>();
    private ChatWebSocketHandler chat;
    private WebSocketSession socket;

    @AfterEach
    void stop() {
        interactions.cancelPending("owner");   // a wizard still waiting, when the test failed
        if (chat != null) chat.shutdownWizardExecutor();
    }

    private void connect(Path tmp) throws Exception {
        var conversations = new ConversationService(MigratedDatabase.at(tmp.resolve("t.db")));
        var auth = new AuthService(null, null, new OwnClawConfig()) {
            @Override public Optional<String> validateToken(String token) { return Optional.of(token); }
            @Override public boolean isOwner(String userId) { return "owner".equals(userId); }
        };
        var cancellation = new TaskCancellationService();
        var commands = new CommandHandler(null, conversations, null, null, null, null, queue, null, null, null,
                null, cancellation, null, interactions);
        chat = new ChatWebSocketHandler(queue, null, conversations, new ChatStatusEmitter(), commands, wizard, auth,
                interactions, cancellation, null, new ObjectMapper());
        Map<String, Object> attributes = new HashMap<>();
        socket = (WebSocketSession) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{WebSocketSession.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getAttributes" -> attributes;
                    case "getUri" -> URI.create("ws://localhost/ws/chat?token=owner");
                    case "isOpen" -> true;
                    case "sendMessage" -> {
                        sent.add(String.valueOf(((TextMessage) args[0]).getPayload()));
                        yield null;
                    }
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
        chat.afterConnectionEstablished(socket);
    }

    private void type(String text) throws Exception {
        chat.handleMessage(socket, new TextMessage(new ObjectMapper().writeValueAsString(Map.of("message", text))));
    }

    /** Wait up to five seconds for {@code condition}. */
    private static boolean within(BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 100 && !condition.getAsBoolean(); i++) Thread.sleep(50);
        return condition.getAsBoolean();
    }

    @Test
    @DisplayName("a typed /cancel ends the wizard's question, the wizard says so, and the next message is no API key")
    void cancelEndsTheWizard(@TempDir Path tmp) throws Exception {
        connect(tmp);
        assertTrue(within(() -> interactions.hasPending("owner")), "the wizard asks: " + sent);

        type("/cancel");

        assertTrue(within(() -> sent.stream().anyMatch(s -> s.contains("Setup wizard was cancelled"))),
                "the wizard says it ended: " + sent);
        assertFalse(interactions.hasPending("owner"));

        type("what is the weather tomorrow?");
        assertEquals(List.of("what is the weather tomorrow?"), queue.messages, "a message, run as one");
        assertEquals(List.of(), wizard.answers, "and not the wizard's answer");
        // Mutations: /cancel without cancelPending -> the wizard takes the question as the API
        // key; the wait cancelled as a CancellationException -> the wizard ends without a word.
    }
}
