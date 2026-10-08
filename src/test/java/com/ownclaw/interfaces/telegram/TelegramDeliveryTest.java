package com.ownclaw.interfaces.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownclaw.agent.AgentResult;
import com.ownclaw.agent.AgentTrajectory;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.conversation.ConversationService;
import com.ownclaw.conversation.MigratedDatabase;
import com.ownclaw.core.TaskQueue;
import com.ownclaw.core.TaskQueue.Fate;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What reaches the owner's Telegram chat, through the real bot and a recorded Bot API: every part
 * of a long answer, in Telegram's HTML or, where Telegram refuses that or it cannot be made, as
 * written, however often Telegram says to slow down, sent from the bot's own thread; what could
 * not be sent, said; results from the first moment after a restart, or after a first message;
 * and not the steps of a running task.
 */
class TelegramDeliveryTest {

    static final long ME = 4242L;
    static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Answers every message with the given result, unless the fate the test sets hands it to a
     * running task, which answers it with its own; runs nothing.
     */
    static final class Answering extends TaskQueue {
        final AgentResult answer;
        final List<com.ownclaw.agent.TaskChat.Channel> channels = new java.util.concurrent.CopyOnWriteArrayList<>();
        final List<String> texts = new java.util.concurrent.CopyOnWriteArrayList<>();
        final List<Boolean> queued = new java.util.concurrent.CopyOnWriteArrayList<>();
        final List<com.ownclaw.conversation.ChatOptions> options = new java.util.concurrent.CopyOnWriteArrayList<>();
        volatile Fate fate = Fate.STARTED;

        Answering(AgentResult answer) {
            super(null, null, null, new OwnClawConfig(), null);
            this.answer = answer;
        }

        @Override
        public Fate send(com.ownclaw.core.UserMessage message, boolean queue) {
            channels.add(message.channel());
            texts.add(message.text());
            queued.add(queue);
            options.add(message.options());
            if (fate != Fate.STEERED) message.answer().accept(answer);
            return fate;
        }
    }

    final FakeTelegram telegram = new FakeTelegram();
    final ChatStatusEmitter emitter = new ChatStatusEmitter();
    Answering queue;
    JdbcTemplate jdbc;
    String owner;
    TelegramBotService bot;

    private void start(Path tmp, String answer) throws Exception {
        start(tmp, AgentResult.completed(answer, new AgentTrajectory(), 1));
    }

    private void start(Path tmp, AgentResult answer) throws Exception {
        start(tmp, answer, ConversationService::new);
    }

    private void start(Path tmp, AgentResult answer,
                       java.util.function.Function<JdbcTemplate, ConversationService> store) throws Exception {
        jdbc = MigratedDatabase.at(tmp.resolve("t.db"));
        owner = new UserRepository(jdbc).createUser("petr", ME);
        var config = new OwnClawConfig();
        config.getTelegram().setBotToken("123:test");
        queue = new Answering(answer);
        bot = new TelegramBotService(config, queue, new UserRepository(jdbc), emitter, JSON,
                store.apply(jdbc), new SkillInteractionHandler(), null,
                new CommandHandler(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null),
                null, jdbc, telegram.client);
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
    @DisplayName("an answer is saved as every chat turn's is: the safe text, the owner's private text beside it, the task id")
    void theAnswerIsSavedAsEveryTurnsIs(@TempDir Path tmp) throws Exception {
        start(tmp, AgentResult.completed("[Private answer]", new AgentTrajectory(), 1)
                .withOwnerText("Closing balance 48,213.07 CZK").withTaskId("a1b2c3d4"));

        receive("what is my balance?");
        FakeTelegram.drain(bot);

        var row = jdbc.queryForMap(
                "SELECT content, private_content, metadata FROM conversations WHERE role = 'assistant'");
        assertEquals("[Private answer]", row.get("content"), "what later prompts read");
        assertEquals("Closing balance 48,213.07 CZK", row.get("private_content"), "what the web chat shows");
        assertEquals("{\"taskId\":\"a1b2c3d4\"}", row.get("metadata"), "what links it to what the task did");
    }

    @Test
    @DisplayName("a message from Telegram runs on what its chat has chosen next to the web chat's message box, else the defaults")
    void itRunsOnItsChatsChoice(@TempDir Path tmp) throws Exception {
        start(tmp, "The router is up.");
        var conversations = new ConversationService(jdbc);
        receive("check the router");
        FakeTelegram.drain(bot);
        assertEquals(com.ownclaw.conversation.ChatOptions.NONE, queue.options.getLast(), "a chat that chose nothing");

        var chosen = new com.ownclaw.conversation.ChatOptions("free", "low");
        conversations.setChatOptions(owner, conversations.getCurrentSession(owner), chosen);
        receive("and the printer");
        FakeTelegram.drain(bot);
        assertEquals(chosen, queue.options.getLast());
        assertEquals(chosen, conversations.chatOptions(owner, conversations.getCurrentSession(owner)),
                "and leaves it as it was");
        // Mutation: send it with NONE -> a chat set to Free runs Telegram's messages on the cloud.
    }

    @Test
    @DisplayName("a message while a task runs goes to it, and Telegram says so in a line; /queue queues, and says so")
    void steerAndQueueSayWhatBecameOfIt(@TempDir Path tmp) throws Exception {
        start(tmp, "The router is up.");
        queue.fate = Fate.STEERED;
        receive("use the backup link");
        FakeTelegram.drain(bot);
        assertEquals(List.of(Fate.STEERED.line()), sentTexts(), "one line, and no answer: the running task answers");
        assertEquals(List.of(false), queue.queued);

        queue.fate = Fate.QUEUED;
        receive("/queue check the printer");
        FakeTelegram.drain(bot);
        assertEquals(List.of("use the backup link", "check the printer"), queue.texts, "the message, without the command");
        assertEquals(List.of(false, true), queue.queued);
        assertEquals(List.of(Fate.STEERED.line(), "The router is up.", Fate.QUEUED.line()), sentTexts(),
                "its answer, and the line saying it was queued");
        assertEquals(List.of("use the backup link", "check the printer"), jdbc.queryForList(
                "SELECT content FROM conversations WHERE role = 'user' ORDER BY rowid", String.class));

        receive("/queue");
        FakeTelegram.drain(bot);
        assertEquals(2, queue.texts.size(), "nothing to run");
        assertTrue(sentTexts().getLast().startsWith("Usage: <code>/queue &lt;message&gt;</code>"), sentTexts().getLast());
    }

    @Test
    @DisplayName("an answer that cannot be saved is still sent, as the web chat's is")
    void anUnsavedAnswerIsStillSent(@TempDir Path tmp) throws Exception {
        start(tmp, AgentResult.completed("The router is up.", new AgentTrajectory(), 1),
                jdbc -> new ConversationService(jdbc) {
                    @Override public String saveAnswer(String userId, String sessionId, AgentResult result) {
                        throw new IllegalStateException("database is locked");
                    }
                });

        receive("is the router up?");
        FakeTelegram.drain(bot);

        assertTrue(sentTexts().contains("The router is up."), "saving failed, and the answer went all the same");
    }

    @Test
    @DisplayName("a long answer arrives whole, in parts, though Telegram asks to slow down in the middle")
    void everyPartArrives(@TempDir Path tmp) throws Exception {
        String line = "line of the answer " + "x".repeat(80) + "\n";
        String answer = line.repeat(120);                       // about 12,000 characters
        start(tmp, answer);
        // The first part is taken; the second is refused for going too fast, with two seconds to wait.
        telegram.answer("sendMessage", FakeTelegram.Answer.ok("true"), FakeTelegram.Answer.tooMany(2));

        long started = System.nanoTime();
        receive("what is in the log?");
        FakeTelegram.drain(bot);
        long waitedMs = (System.nanoTime() - started) / 1_000_000;

        var bodies = telegram.bodies("sendMessage");
        assertEquals(bodies.get(1), bodies.get(2), "the refused part is sent again, unchanged");
        var texts = new java.util.ArrayList<>(sentTexts());
        texts.remove(1);                                          // the call refused with 429
        assertTrue(texts.size() >= 3, "in parts: " + texts.size());
        assertEquals(answer, String.join("", texts), "every part, in order, nothing lost or trimmed");
        assertTrue(waitedMs >= 2000, "it waited as long as Telegram said: " + waitedMs + " ms");
        assertEquals(List.of("telegram-outbox"), telegram.threadsOf("sendMessage").stream().distinct().toList(),
                "the waiting is the bot's own: the thread that handed it the answer went on at once");
    }

    @Test
    @DisplayName("an answer goes in Telegram's HTML: what the model marked up renders, and no underscore or asterisk of an identifier or a sum is taken for a marker")
    void sentInTelegramsHtml(@TempDir Path tmp) throws Exception {
        String answer = "## Report\nDone: result 1 (**smtp_send_email**) mailed `report_2026_09.csv`; "
                + "your network is Home_Net_5G, and 2*3*4 = 24 < 25.";
        start(tmp, answer);

        receive("send the report");
        FakeTelegram.drain(bot);

        assertEquals(List.of("<b>Report</b>\nDone: result 1 (<b>smtp_send_email</b>) mailed "
                + "<code>report_2026_09.csv</code>; your network is Home_Net_5G, and 2*3*4 = 24 &lt; 25."), sentTexts());
        for (String body : telegram.bodies("sendMessage")) {
            assertEquals("HTML", JSON.readTree(body).path("parse_mode").asText(), body);
        }
        // Mutation: parse_mode Markdown back -> smtpsendemail, HomeNet5G, 234 on the owner's phone;
        // no parse_mode -> he reads ## and ** and backticks.
    }

    @Test
    @DisplayName("a text the renderer fails on still arrives, every part of it, as it was written")
    void whatCannotBeRenderedGoesAsWritten(@TempDir Path tmp) throws Exception {
        // A link whose address has two thousand bracketed parts: the renderer's pattern goes a
        // call deeper for each, more than the smallest stack a thread may have can hold.
        String answer = "**Route**\n[the map](https://example.org/m" + "()".repeat(2000) + ")\n"
                + ("line of the answer " + "x".repeat(80) + "\n").repeat(30);
        var parts = TelegramBotService.telegramParts(answer, TelegramBotService.TELEGRAM_MAX_CHARS);
        assertEquals(2, parts.size());
        start(tmp, "The router is up.");
        // The way to Telegram, taken once on the outbox's thread: nothing on it is loaded for the
        // first time on the small stack.
        receive("is the router up?");
        FakeTelegram.drain(bot);

        var deliver = TelegramBotService.class.getDeclaredMethod("deliver", long.class, String.class);
        deliver.setAccessible(true);
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread small = new Thread(null, () -> {
            try {
                deliver.invoke(bot, ME, answer);
            } catch (Throwable e) {
                failure.set(e);
            }
        }, "small-stack", 1);                  // as small as the JVM allows
        small.start();
        small.join();

        assertNull(failure.get(), "nothing escaped the delivery");
        var sent = new java.util.ArrayList<JsonNode>();
        for (String b : telegram.bodies("sendMessage")) sent.add(JSON.readTree(b));
        assertEquals("The router is up.", sent.removeFirst().path("text").asText());
        assertEquals(parts, sent.stream().map(s -> s.path("text").asText()).toList(), "every part, as written");
        assertEquals(List.of(false, false), sent.stream().map(s -> s.has("parse_mode")).toList(), "as plain text");
        // Mutation: render with no guard -> StackOverflowError on the outbox's thread: no part,
        // no notice, no line in the log.
    }

    @Test
    @DisplayName("a part Telegram refuses as HTML is sent again as it was written, as plain text; the next part is HTML again")
    void refusedHtmlGoesAsWritten(@TempDir Path tmp) throws Exception {
        String answer = ("**line** of the answer " + "x".repeat(80) + "\n").repeat(60);   // two parts
        start(tmp, answer);
        var parts = TelegramBotService.telegramParts(answer, TelegramBotService.TELEGRAM_MAX_CHARS);
        assertEquals(2, parts.size());
        telegram.answer("sendMessage", FakeTelegram.Answer.refused(400));

        receive("what is in the log?");
        FakeTelegram.drain(bot);

        var sent = new java.util.ArrayList<JsonNode>();
        for (String b : telegram.bodies("sendMessage")) sent.add(JSON.readTree(b));
        assertEquals(3, sent.size(), "the refused part once more, and nothing else");
        var html = TelegramHtml.render(parts);
        assertEquals(List.of("HTML", "", "HTML"), sent.stream().map(s -> s.path("parse_mode").asText()).toList());
        assertEquals(List.of(html.get(0), parts.get(0), html.get(1)), sent.stream().map(s -> s.path("text").asText()).toList(),
                "the refused part as written -- the Markdown, not the HTML Telegram could not read");
        assertTrue(parts.get(0).startsWith("**line**") && html.get(0).startsWith("<b>line</b>"));
        // Mutation: resend the HTML -> refused again; no resend -> the owner is told it was not taken.
    }

    @Test
    @DisplayName("a code block longer than a message is code in every part it is cut into, and the text after it is read again")
    void aLongCodeBlock(@TempDir Path tmp) throws Exception {
        String code = "print('**not bold** <tag>')  # " + "y".repeat(60) + "\n";
        String answer = "Here is the **script**:\n```python\n" + code.repeat(100) + "```\nRun it **daily**.";
        start(tmp, answer);

        receive("write the script");
        FakeTelegram.drain(bot);

        List<String> texts = sentTexts();
        assertTrue(texts.size() >= 3, "in parts: " + texts.size());
        assertTrue(texts.getFirst().startsWith("Here is the <b>script</b>:\n<pre>print('**not bold** &lt;tag&gt;')"),
                texts.getFirst());
        assertTrue(texts.getLast().endsWith("</pre>\nRun it <b>daily</b>."), texts.getLast());
        for (int i = 0; i < texts.size(); i++) {
            String t = texts.get(i);
            assertEquals(1, t.split("<pre>", -1).length - 1, "one block opened in part " + i);
            assertEquals(1, t.split("</pre>", -1).length - 1, "and closed in it");
            if (i > 0) assertTrue(t.startsWith("<pre>print('**not bold**"), "code from its first character: " + t);
            if (i > 0 && i < texts.size() - 1) assertTrue(t.endsWith("</pre>"), "code to its last: " + t);
        }
        // Every line of the script is there, as written.
        String shown = texts.stream().map(TelegramHtmlTest::textOf).collect(java.util.stream.Collectors.joining());
        assertEquals(("Here is the script:" + code.repeat(100) + "Run it daily.").replace("\n", ""), shown.replace("\n", ""));
    }

    @Test
    @DisplayName("a part as long as a message may be goes whole, as HTML, though its HTML is longer: Telegram counts the text the HTML holds")
    void theLimitCountsTheText(@TempDir Path tmp) throws Exception {
        String line = "**a** <b> & `c`\n";                                // 16 characters, 40 as HTML
        String answer = line.repeat(TelegramBotService.TELEGRAM_MAX_CHARS / line.length());
        assertEquals(TelegramBotService.TELEGRAM_MAX_CHARS, answer.length(), "one part");
        start(tmp, answer);

        receive("show me");
        FakeTelegram.drain(bot);

        var bodies = telegram.bodies("sendMessage");
        assertEquals(1, bodies.size(), "one message: not cut, not split again, not sent as plain text");
        JsonNode sent = JSON.readTree(bodies.getFirst());
        assertEquals("HTML", sent.path("parse_mode").asText());
        String html = sent.path("text").asText();
        assertEquals("<b>a</b> &lt;b&gt; &amp; <code>c</code>\n".repeat(256), html);
        assertEquals(10_240, html.length(), "longer than a message may be");
        assertEquals("a <b> & c\n".repeat(256), TelegramHtmlTest.textOf(html), "the 2,560 characters Telegram counts");
    }

    @Test
    @DisplayName("a status is sent from the bot's own thread, so a wait for Telegram never holds up the task")
    void theTaskNeverWaits(@TempDir Path tmp) throws Exception {
        start(tmp, "unused");
        jdbc.update("INSERT INTO system_settings (key, value) VALUES (?, ?)", "telegram.chat." + owner, String.valueOf(ME));
        telegram.answer("getMe", FakeTelegram.Answer.ok("{\"username\":\"testbot\"}"));
        telegram.otherwise.put("getUpdates", FakeTelegram.Answer.refused(502));
        bot.start();
        telegram.answer("sendMessage", FakeTelegram.Answer.tooMany(1));

        emitter.emit(owner, new StatusMessage(StatusMessage.Type.WARNING, "a tool failed", null, "abcd1234"));
        FakeTelegram.drain(bot);

        assertEquals(List.of("\u26a0\ufe0f a tool failed", "\u26a0\ufe0f a tool failed"), sentTexts());
        assertEquals(List.of("telegram-outbox", "telegram-outbox"), telegram.threadsOf("sendMessage"),
                "not the task's thread, which emitted it");
    }

    @Test
    @DisplayName("a failure on Telegram's side is tried again, and the part arrives")
    void aFailureIsTriedAgain(@TempDir Path tmp) throws Exception {
        start(tmp, "the answer");
        telegram.answer("sendMessage", FakeTelegram.Answer.refused(502));

        receive("what is in the log?");
        FakeTelegram.drain(bot);

        assertEquals(List.of("the answer", "the answer"), sentTexts(), "refused once, then taken");
    }

    @Test
    @DisplayName("a part Telegram will not take ends the answer there, and the owner is told which")
    void whatIsNotTakenIsSaid(@TempDir Path tmp) throws Exception {
        String line = "line of the answer " + "x".repeat(80) + "\n";
        String answer = line.repeat(120);                       // three parts
        start(tmp, answer);
        var parts = TelegramBotService.telegramParts(answer, TelegramBotService.TELEGRAM_MAX_CHARS);
        assertEquals(3, parts.size());
        telegram.answer("sendMessage", FakeTelegram.Answer.ok("true"), FakeTelegram.Answer.refused(403));

        receive("what is in the log?");
        FakeTelegram.drain(bot);

        assertEquals(List.of(parts.get(0), parts.get(1), TelegramBotService.notTaken(2, 3, 403)), sentTexts(),
                "the third part does not follow as if nothing were missing");
        assertTrue(TelegramBotService.notTaken(2, 3, 403).contains("part 2 of 3"));
        assertTrue(TelegramBotService.notTaken(1, 1, 0).contains("a message for you (it could not be reached)"));
    }

    @Test
    @DisplayName("a failure that goes on is tried three times, and then said")
    void aLastingFailureIsSaid(@TempDir Path tmp) throws Exception {
        start(tmp, "the answer");
        telegram.answer("sendMessage", FakeTelegram.Answer.refused(502), FakeTelegram.Answer.refused(502),
                FakeTelegram.Answer.refused(502));

        receive("what is in the log?");
        FakeTelegram.drain(bot);

        assertEquals(List.of("the answer", "the answer", "the answer", TelegramBotService.notTaken(1, 1, 502)),
                sentTexts());
    }

    @Test
    @DisplayName("a chat first heard from after start-up gets results from then on")
    void aNewChatGetsResults(@TempDir Path tmp) throws Exception {
        start(tmp, "hello to you");
        telegram.answer("getMe", FakeTelegram.Answer.ok("{\"username\":\"testbot\"}"));
        telegram.otherwise.put("getUpdates", FakeTelegram.Answer.refused(502));
        bot.start();                                            // no chat remembered yet

        receive("hello");
        emitter.emit(owner, new StatusMessage(StatusMessage.Type.RESULT, "**Scheduled task: digest**\n\nall quiet",
                java.util.Map.of("sessionId", "s1"), "abcd1234"));
        FakeTelegram.drain(bot);

        assertEquals(List.of("hello to you", "<b>Scheduled task: digest</b>\n\nall quiet"), sentTexts());
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
        FakeTelegram.drain(bot);

        assertEquals(List.of("<b>Scheduled task: digest</b>\n\nall quiet"), sentTexts());
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
        FakeTelegram.drain(bot);

        assertEquals(List.of(), sentTexts());
    }

    @Test
    @DisplayName("the steps, progress, live state and debug output of a running task are not sent; its warnings and failures are")
    void notTheHeartbeat(@TempDir Path tmp) throws Exception {
        start(tmp, "unused");
        jdbc.update("INSERT INTO system_settings (key, value) VALUES (?, ?)", "telegram.chat." + owner, String.valueOf(ME));
        telegram.answer("getMe", FakeTelegram.Answer.ok("{\"username\":\"testbot\"}"));
        telegram.otherwise.put("getUpdates", FakeTelegram.Answer.refused(502));
        bot.start();

        emitter.emit(owner, new StatusMessage(StatusMessage.Type.QUEUED, "Task queued (position 2)", null));
        emitter.emit(owner, new StatusMessage(StatusMessage.Type.STEP, "Think", null, "abcd1234"));
        emitter.emit(owner, new StatusMessage(StatusMessage.Type.PROGRESS, "writing code… 20s", null, "abcd1234"));
        emitter.emitForTask(owner, "abcd1234", StatusMessage.Type.LIVE,
                "🏠 Local model · step 6, delegation turn 1 · reasoning · 23m 5s · 41,200 characters so far",
                Map.of("live", Map.of("phase", "reasoning")));
        emitter.emit(owner, new StatusMessage(StatusMessage.Type.DEBUG, "PROMPT: the whole of it", null));
        emitter.emit(owner, new StatusMessage(StatusMessage.Type.COMPLETED, "Done in 3 steps", null, "abcd1234"));
        emitter.emit(owner, new StatusMessage(StatusMessage.Type.WARNING, "No progress for 600s", null, "abcd1234"));
        emitter.emit(owner, new StatusMessage(StatusMessage.Type.FAILED, "STALLED", null, "abcd1234"));
        FakeTelegram.drain(bot);

        assertEquals(List.of("\u26a0\ufe0f No progress for 600s", "\u274c STALLED"), sentTexts());
    }

    @Test
    @DisplayName("a task asked from Telegram sends its progress there, a private summary in full; another task's progress is not sent")
    void progressOfATelegramTask(@TempDir Path tmp) throws Exception {
        start(tmp, "It is up.");
        telegram.answer("getMe", FakeTelegram.Answer.ok("{\"username\":\"testbot\"}"));
        telegram.otherwise.put("getUpdates", FakeTelegram.Answer.refused(502));
        bot.start();
        receive("is the router up?");
        assertEquals(List.of(com.ownclaw.agent.TaskChat.Channel.TELEGRAM), queue.channels,
                "the task knows it came from Telegram");
        FakeTelegram.drain(bot);
        int before = sentTexts().size();

        emitter.emit(owner, new StatusMessage(StatusMessage.Type.PROGRESS_MESSAGE,
                "☁️ Step 1 · ping · 2.0s · $0.01\n\nPinging the router.",
                java.util.Map.of("sessionId", "s1", "telegram", true), "abcd1234"));
        emitter.emit(owner, new StatusMessage(StatusMessage.Type.PROGRESS_MESSAGE,
                "☁️ Step 1 · ping · 1.0s · $0.01\n\nAsked from the web chat.",
                java.util.Map.of("sessionId", "s2"), "bcde2345"));
        emitter.emit(owner, new StatusMessage(StatusMessage.Type.PROGRESS_MESSAGE,
                "🏠 Result 1 · bank_fetch · 2.1s · $0.01\n\nA private summary, shown only to you.",
                java.util.Map.of("sessionId", "s1", "telegram", true, "ownerText",
                        "🏠 Result 1 · bank_fetch · 2.1s · $0.01\n\nBalance 48,213.07 CZK"), "abcd1234"));
        emitter.emit(owner, new StatusMessage(StatusMessage.Type.PROGRESS_MESSAGE,
                "🏠 Turn 1 · shell_exec · 9.0s · $0.01",
                java.util.Map.of("sessionId", "s1", "telegram", true, "ownerText",
                        "🏠 Turn 1 · shell_exec · 9.0s · $0.01\n\nČtu tabulku tras.\n\n```sh\nip route show\n```"),
                "abcd1234"));
        FakeTelegram.drain(bot);

        // The actor is its emoji in plain text, as the page draws it as a chip.
        assertEquals(List.of("☁️ Step 1 · ping · 2.0s · $0.01\n\nPinging the router.",
                        "🏠 Result 1 · bank_fetch · 2.1s · $0.01\n\nBalance 48,213.07 CZK",
                        "🏠 Turn 1 · shell_exec · 9.0s · $0.01\n\nČtu tabulku tras.\n\n<pre>ip route show</pre>"),
                sentTexts().subList(before, sentTexts().size()));
        for (String b : telegram.bodies("sendMessage").subList(before, sentTexts().size())) {
            var body = JSON.readTree(b);
            assertEquals("HTML", body.path("parse_mode").asText(), "rendered like an answer");
        }
    }
}
