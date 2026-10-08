package com.ownclaw.interfaces.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.conversation.ChatOptions;
import com.ownclaw.conversation.ConversationService;
import com.ownclaw.conversation.MigratedDatabase;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A chat's messages come with what it has chosen next to the message box and the defaults it
 * follows where it chose nothing, which the page shows on opening it -- as JSON, as the page reads
 * them -- and only to its user.
 */
class ChatOptionsApiTest {

    /** A request as JwtAuthFilter leaves it: signed in as this user. */
    private static HttpServletRequest as(String userId) {
        return (HttpServletRequest) Proxy.newProxyInstance(ChatOptionsApiTest.class.getClassLoader(),
                new Class<?>[]{HttpServletRequest.class},
                (p, m, args) -> "getAttribute".equals(m.getName()) && "userId".equals(args[0]) ? userId : null);
    }

    @Test
    @DisplayName("the messages of a chat come with its choice and the defaults; another user's chat with no choice of it")
    void theMessagesComeWithTheChatsChoice(@TempDir Path tmp) throws Exception {
        var conversations = new ConversationService(MigratedDatabase.at(tmp.resolve("t.db")));
        var config = new OwnClawConfig();
        config.getMentor().setPreferCost(false);
        config.getMentor().setThinkingEffort("medium");
        var controller = new ChatHistoryController(conversations, config);
        String chat = conversations.createSession("u1", "Network");
        var json = new ObjectMapper();

        String fresh = json.writeValueAsString(controller.getMessages(as("u1"), chat, null, 50).getBody());
        Map<?, ?> body = json.readValue(fresh, Map.class);
        assertEquals(Map.of("costMode", "fast", "effort", "medium"), body.get("defaults"), fresh);
        Map<?, ?> none = (Map<?, ?>) body.get("options");
        assertTrue(none.get("costMode") == null && none.get("effort") == null, "a new chat chose nothing: " + fresh);

        conversations.setChatOptions("u1", chat, new ChatOptions("free", "low"));
        body = json.readValue(json.writeValueAsString(controller.getMessages(as("u1"), chat, null, 50).getBody()),
                Map.class);
        assertEquals(Map.of("costMode", "free", "effort", "low"), body.get("options"));

        body = json.readValue(json.writeValueAsString(controller.getMessages(as("u2"), chat, null, 50).getBody()),
                Map.class);
        Map<?, ?> theirs = (Map<?, ?>) body.get("options");
        assertNull(theirs.get("costMode"), "his chat's choice is not shown to another user: " + body);
        assertNull(theirs.get("effort"));
    }
}
