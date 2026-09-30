package com.ownclaw.interfaces.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownclaw.agent.AgentResult;
import com.ownclaw.agent.AgentTrajectory;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.conversation.ConversationService;
import com.ownclaw.conversation.MigratedDatabase;
import com.ownclaw.core.TaskQueue;
import com.ownclaw.interfaces.CommandHandler;
import com.ownclaw.observability.ChatStatusEmitter;
import com.ownclaw.observability.ChatStatusEmitter.StatusMessage;
import com.ownclaw.skillrunner.SkillInteractionHandler;
import com.ownclaw.users.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What reaches the owner's Telegram chat, through the real bot and a recorded Bot API: every part
 * of a long answer, however often Telegram says to slow down; results from the first moment
 * after a restart; and not the steps of a running task.
 */
class TelegramDeliveryTest {

    static final long ME = 4242L;
    static final ObjectMapper JSON = new ObjectMapper();

    /** Answers every message with the given text; runs nothing. */
    static final class Answering extends TaskQueue {
        final String answer;

        Answering(String answer) {
            super(null, null, null, new OwnClawConfig(), null);
            this.answer = answer;
        }

        @Override
        public CompletableFuture<AgentResult> submit(String userId, String message, int priority,
                                                     String currentMessageId, List<String> attachmentIds) {
            return CompletableFuture.completedFuture(AgentResult.completed(answer, new AgentTrajectory(), 1));
        }
    }

    final FakeTelegram telegram = new FakeTelegram();
    final ChatStatusEmitter emitter = new ChatStatusEmitter();
    JdbcTemplate jdbc;
    String owner;
    TelegramBotService bot;

    private void start(Path tmp, String answer) throws Exception {
        jdbc = MigratedDatabase.at(tmp.resolve("t.db"));
        owner = new UserRepository(jdbc).createUser("petr", ME);
        var config = new OwnClawConfig();
        config.getTelegram().setBotToken("123:test");
        bot = new TelegramBotService(config, new Answering(answer), new UserRepository(jdbc), emitter, JSON,
                new ConversationService(jdbc, null), new SkillInteractionHandler(null), null,
                new CommandHandler(null, null, null, null, null, null, null, null, null, null, null, null, null),
                null, null, jdbc, telegram.client);
    }

    @AfterEach
    void stop() {
        if (bot != null) bot.stop();
    }

    private void receive(String text) throws Exception {
        var update = JSON.createObjectNode();
        var message = update.putObject("message");
        message.put("message_id", 1);
        message.putObject("chat").put("id", ME);
        message.putObject("from").put("id", ME).put("first_name", "Petr");
        message.put("text", text);
        var handle = TelegramBotService.class.getDeclaredMethod("handleUpdate", JsonNode.class);
        handle.setAccessible(true);
        handle.invoke(bot, update);
    }

    /** The texts of every sendMessage call, in order. */
    private List<String> sentTexts() throws Exception {
        var texts = new java.util.ArrayList<String>();
        for (String b : telegram.bodies("sendMessage")) texts.add(JSON.readTree(b).path("text").asText());
        return texts;
    }

    @Test
    @DisplayName("a long answer arrives whole, in parts, though Telegram asks to slow down in the middle")
    void everyPartArrives(@TempDir Path tmp) throws Exception {
        String line = "line of the answer " + "x".repeat(80) + "\n";
        String answer = line.repeat(120);                       // about 12,000 characters
        start(tmp, answer);
        // The first part is taken; the second is refused twice for going too fast.
        telegram.answer("sendMessage", FakeTelegram.Answer.ok("true"),
                FakeTelegram.Answer.tooMany(1), FakeTelegram.Answer.tooMany(0));

        long started = System.nanoTime();
        receive("what is in the log?");
        long waitedMs = (System.nanoTime() - started) / 1_000_000;

        var bodies = telegram.bodies("sendMessage");
        assertEquals(bodies.get(1), bodies.get(2), "the refused part is sent again, unchanged");
        assertEquals(bodies.get(1), bodies.get(3));
        var texts = new java.util.ArrayList<>(sentTexts());
        texts.subList(1, 3).clear();                              // the two calls refused with 429
        assertTrue(texts.size() >= 3, "in parts: " + texts.size());
        assertEquals(answer, String.join("", texts), "every part, in order, nothing lost or trimmed");
        assertTrue(waitedMs >= 1000, "it waited as long as Telegram said: " + waitedMs + " ms");
    }

    @Test
    @DisplayName("after a restart, results reach Telegram before the owner has written anything")
    void resultsAfterARestart(@TempDir Path tmp) throws Exception {
        start(tmp, "unused");
        jdbc.update("INSERT INTO system_settings (key, value) VALUES (?, ?)", "telegram.chat." + owner, String.valueOf(ME));
        telegram.answer("getMe", FakeTelegram.Answer.ok("{\"username\":\"testbot\"}"));
        telegram.otherwise.put("getUpdates", FakeTelegram.Answer.refused(502));   // the poller backs off

        bot.start();
        emitter.emit(owner, new StatusMessage(StatusMessage.Type.RESULT, "**Scheduled task: digest**\n\nall quiet",
                java.util.Map.of("sessionId", "s1"), "abcd1234"));

        assertEquals(List.of("**Scheduled task: digest**\n\nall quiet"), sentTexts());
        assertEquals(ME, JSON.readTree(telegram.bodies("sendMessage").getFirst()).path("chat_id").asLong());
    }

    @Test
    @DisplayName("a stopped bot sends nothing: its token may be the one just replaced")
    void aStoppedBotIsSilent(@TempDir Path tmp) throws Exception {
        start(tmp, "unused");
        jdbc.update("INSERT INTO system_settings (key, value) VALUES (?, ?)", "telegram.chat." + owner, String.valueOf(ME));
        telegram.answer("getMe", FakeTelegram.Answer.ok("{\"username\":\"testbot\"}"));
        telegram.otherwise.put("getUpdates", FakeTelegram.Answer.refused(502));
        bot.start();

        bot.stop();
        emitter.emit(owner, new StatusMessage(StatusMessage.Type.RESULT, "a result", null));

        assertEquals(List.of(), sentTexts());
    }

    @Test
    @DisplayName("the steps, progress and debug output of a running task are not sent; its warnings and failures are")
    void notTheHeartbeat(@TempDir Path tmp) throws Exception {
        start(tmp, "unused");
        jdbc.update("INSERT INTO system_settings (key, value) VALUES (?, ?)", "telegram.chat." + owner, String.valueOf(ME));
        telegram.answer("getMe", FakeTelegram.Answer.ok("{\"username\":\"testbot\"}"));
        telegram.otherwise.put("getUpdates", FakeTelegram.Answer.refused(502));
        bot.start();

        emitter.emit(owner, new StatusMessage(StatusMessage.Type.QUEUED, "Task queued (position 2)", null));
        emitter.emit(owner, new StatusMessage(StatusMessage.Type.STEP, "Think", null, "abcd1234"));
        emitter.emit(owner, new StatusMessage(StatusMessage.Type.PROGRESS, "writing code… 20s", null, "abcd1234"));
        emitter.emit(owner, new StatusMessage(StatusMessage.Type.DEBUG, "PROMPT: the whole of it", null));
        emitter.emit(owner, new StatusMessage(StatusMessage.Type.COMPLETED, "Done in 3 steps", null, "abcd1234"));
        emitter.emit(owner, new StatusMessage(StatusMessage.Type.WARNING, "No progress for 600s", null, "abcd1234"));
        emitter.emit(owner, new StatusMessage(StatusMessage.Type.FAILED, "STALLED", null, "abcd1234"));

        assertEquals(List.of("\u26a0\ufe0f No progress for 600s", "\u274c STALLED"), sentTexts());
    }
}
