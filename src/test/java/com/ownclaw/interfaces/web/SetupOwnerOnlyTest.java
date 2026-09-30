package com.ownclaw.interfaces.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.config.SetupWizardService;
import com.ownclaw.conversation.ConversationService;
import com.ownclaw.conversation.MigratedDatabase;
import com.ownclaw.core.TaskQueue;
import com.ownclaw.interfaces.CommandHandler;
import com.ownclaw.observability.ChatStatusEmitter;
import com.ownclaw.skillrunner.SkillInteractionHandler;
import com.ownclaw.users.AuthService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Setup replaces the cloud API keys, the local model's address and the bot token, which only the
 * owner may change: the first-run wizard starts for the owner alone, and /setup from anyone else
 * is refused. Driven through connected sockets; the wizard records whether it was started.
 */
class SetupOwnerOnlyTest {

    /** Records every step it is asked for; the first one ends the wizard. */
    static final class Wizard extends SetupWizardService {
        final List<Integer> steps = new CopyOnWriteArrayList<>();

        Wizard() {
            super(null, new OwnClawConfig(), null, null, null);
        }

        @Override public boolean isSetupNeeded() { return true; }

        @Override public WizardResponse processStep(int step, String userInput) {
            steps.add(step);
            return new WizardResponse("setup step " + step, true);
        }
    }

    private final Wizard wizard = new Wizard();
    private ChatWebSocketHandler chat;
    private final List<String> sent = new ArrayList<>();

    private void start(Path tmp) throws Exception {
        var conversations = new ConversationService(MigratedDatabase.at(tmp.resolve("t.db")), null);
        var auth = new AuthService(null, null, new OwnClawConfig()) {
            @Override public Optional<String> validateToken(String token) { return Optional.of(token); }
            @Override public boolean isOwner(String userId) { return "owner".equals(userId); }
        };
        chat = new ChatWebSocketHandler(new TaskQueue(null, null, null, new OwnClawConfig(), null), null,
                conversations, new ChatStatusEmitter(),
                new CommandHandler(null, null, null, null, null, null, null, null, null, null, null, null, null),
                wizard, auth, new SkillInteractionHandler(null), null, null, new ObjectMapper());
    }

    /** A socket for this user, connected. */
    private WebSocketSession connect(String userId) throws Exception {
        Map<String, Object> attributes = new HashMap<>();
        var socket = (WebSocketSession) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{WebSocketSession.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getAttributes" -> attributes;
                    case "getUri" -> URI.create("ws://localhost/ws/chat?token=" + userId);
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
        return socket;
    }

    @Test
    @DisplayName("the first-run wizard starts for the owner, and anyone else is only welcomed")
    void firstRunIsTheOwners(@TempDir Path tmp) throws Exception {
        start(tmp);

        connect("guest");
        chat.shutdownWizardExecutor();   // waits for anything it started
        assertEquals(List.of(), wizard.steps, "a guest never sees the wizard");
        assertTrue(sent.stream().anyMatch(s -> s.contains("Connected to OwnClaw")), String.valueOf(sent));

        start(tmp);
        connect("owner");
        chat.shutdownWizardExecutor();
        assertEquals(List.of(0), wizard.steps, "the owner is taken through it");
    }

    @Test
    @DisplayName("/setup from anyone but the owner is refused, and the wizard does not start")
    void setupIsTheOwners(@TempDir Path tmp) throws Exception {
        start(tmp);
        var guest = connect("guest");
        sent.clear();

        chat.handleTextMessage(guest, new TextMessage("{\"message\":\"/setup\"}"));
        chat.shutdownWizardExecutor();

        assertEquals(List.of(), wizard.steps);
        assertTrue(sent.stream().anyMatch(s -> s.contains("Only the owner can run setup.")), String.valueOf(sent));
    }
}
