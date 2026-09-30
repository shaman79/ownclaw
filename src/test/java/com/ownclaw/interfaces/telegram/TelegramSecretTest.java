package com.ownclaw.interfaces.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.conversation.ConversationService;
import com.ownclaw.conversation.MigratedDatabase;
import com.ownclaw.interfaces.CommandHandler;
import com.ownclaw.observability.ChatStatusEmitter;
import com.ownclaw.skillrunner.SkillInteractionHandler;
import com.ownclaw.users.CredentialVault;
import com.ownclaw.users.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A secret sent to the bot as a command is stored and then removed from the Telegram chat; a
 * mistyped one goes nowhere; a slash text that answers a waiting question is that answer.
 */
class TelegramSecretTest {

    static final String SECRET = "hunter2-Xq9";
    static final long ME = 4242L;

    @TempDir Path dir;
    JdbcTemplate jdbc;
    TelegramBotService bot;
    final List<String> stored = new ArrayList<>();
    final FakeTelegram telegram = new FakeTelegram();

    @BeforeEach
    void setUp() throws Exception {
        jdbc = MigratedDatabase.at(dir.resolve("t.db"));
        new UserRepository(jdbc).createUser("petr", ME);
        bot = botWith(new SkillInteractionHandler());
    }

    /** The bot with the real command handler, a vault that records, and this interaction handler. */
    private TelegramBotService botWith(SkillInteractionHandler interactions) {
        CredentialVault vault = new CredentialVault(jdbc) {
            @Override public void storeCredential(String userId, String key, String value) {
                stored.add(key + "=" + value);
            }
        };
        CommandHandler commands = new CommandHandler(null, null, null, null, vault, null, null,
                null, null, null, null, null, null);
        OwnClawConfig config = new OwnClawConfig();
        config.getTelegram().setBotToken("123:test");
        ConversationService conv = new ConversationService(jdbc);
        // No task queue: a message handed to the agent fails the test at taskQueue.submit.
        return new TelegramBotService(config, null, new UserRepository(jdbc), new ChatStatusEmitter(),
                new ObjectMapper(), conv, interactions, null, commands, null, null, jdbc, telegram.client);
    }

    private void receive(long messageId, String text) throws Exception {
        var update = new ObjectMapper().createObjectNode();
        var message = update.putObject("message");
        message.put("message_id", messageId);
        message.putObject("chat").put("id", ME);
        message.putObject("from").put("id", ME).put("first_name", "Petr");
        message.put("text", text);
        var handle = TelegramBotService.class.getDeclaredMethod("handleUpdate", JsonNode.class);
        handle.setAccessible(true);
        try {
            handle.invoke(bot, update);
        } catch (InvocationTargetException e) {
            fail("the message was handed to the agent: " + e.getCause());
        }
        FakeTelegram.drain(bot);
    }

    @Test
    @DisplayName("/cred set is stored, and the message holding the value is deleted from the chat")
    void credSetIsDeleted() throws Exception {
        receive(77, "/cred set OPENWRT_PASS " + SECRET);
        assertEquals(List.of("OPENWRT_PASS=" + SECRET), stored);
        var deleted = new ObjectMapper().readTree(telegram.bodies("deleteMessage").getFirst());
        assertEquals(ME, deleted.path("chat_id").asLong());
        assertEquals(77, deleted.path("message_id").asLong());
        for (String c : telegram.calls) assertFalse(c.startsWith("sendMessage") && c.contains(SECRET), c);
        assertFalse(telegram.bodies("sendMessage").stream().anyMatch(b -> b.contains("delete it yourself")),
                "deleted, so nothing to warn about");
    }

    @Test
    @DisplayName("a /cred set the grammar cannot read stores nothing, and the message holding the value is deleted all the same")
    void aMalformedCredSetIsDeleted() throws Exception {
        receive(81, "/cred set OPENWRT_PASS=" + SECRET);
        assertTrue(stored.isEmpty(), "stored: " + stored);
        assertEquals(81, new ObjectMapper().readTree(telegram.bodies("deleteMessage").getFirst()).path("message_id").asLong(),
                "calls: " + telegram.calls);
        assertTrue(telegram.bodies("sendMessage").stream().anyMatch(b -> b.contains("Usage: /cred set")), "calls: " + telegram.calls);
        assertTrue(telegram.bodies("sendMessage").stream().noneMatch(b -> b.contains(SECRET)));
    }

    @Test
    @DisplayName("when Telegram refuses the delete, the owner is told to delete it himself")
    void refusedDeleteIsSaid() throws Exception {
        telegram.answer("deleteMessage", FakeTelegram.Answer.refused(400));
        receive(78, "/cred set OPENWRT_PASS " + SECRET);
        assertTrue(telegram.bodies("sendMessage").stream().anyMatch(b -> b.contains("delete it yourself")),
                "calls: " + telegram.calls);
    }

    @Test
    @DisplayName("a command that is not one is answered, not handed to the agent, kept or titled")
    void mistypedCommandGoesNowhere() throws Exception {
        receive(79, "/creds set OPENWRT_PASS " + SECRET);
        assertTrue(stored.isEmpty());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM conversations", Integer.class), "nothing kept");
        for (Map<String, Object> row : jdbc.queryForList("SELECT title, preview FROM chat_sessions")) {
            assertFalse(String.valueOf(row).contains(SECRET), "titled: " + row);
        }
        assertTrue(telegram.bodies("sendMessage").stream().anyMatch(b -> b.contains(CommandHandler.UNKNOWN_COMMAND)),
                "calls: " + telegram.calls);
        assertTrue(telegram.bodies("sendMessage").stream().noneMatch(b -> b.contains(SECRET)));
    }

    @Test
    @DisplayName("a slash text that answers a waiting question is handed to it, not refused as a command")
    void anAnswerThatStartsWithASlash() throws Exception {
        List<String> answers = new ArrayList<>();
        bot = botWith(new SkillInteractionHandler() {
            @Override public boolean hasPending(String userId) { return true; }
            @Override public boolean provideInput(String userId, String taskId, String input) {
                answers.add(input);
                return true;
            }
        });

        receive(80, "/home/me/My Photos");

        assertEquals(List.of("/home/me/My Photos"), answers);
        assertTrue(telegram.bodies("sendMessage").stream().noneMatch(b -> b.contains(CommandHandler.UNKNOWN_COMMAND)),
                "calls: " + telegram.calls);
    }
}
