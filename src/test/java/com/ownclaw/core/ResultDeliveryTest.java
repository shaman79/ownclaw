package com.ownclaw.core;

import com.ownclaw.agent.AgentAction;
import com.ownclaw.agent.AgentObservation;
import com.ownclaw.agent.AgentResult;
import com.ownclaw.agent.AgentTrajectory;
import com.ownclaw.conversation.ConversationService;
import com.ownclaw.conversation.MigratedDatabase;
import com.ownclaw.observability.ChatStatusEmitter;
import com.ownclaw.observability.ChatStatusEmitter.StatusMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The one line on a delivered result about what stayed on this machine. Worded as what was
 * checked, computed from the run, and silent where it cannot see.
 */
class ResultDeliveryTest {

    private static AgentResult runWith(List<Map<String, Object>> artifacts) {
        var t = new AgentTrajectory();
        var structured = Map.<String, Object>of("delegatedTools", List.of("x"), "artifacts", artifacts);
        t.record(new AgentAction(AgentAction.DELEGATE, Map.of("goal", "g"), ""),
                AgentObservation.success(AgentAction.DELEGATE, "done", structured, 10));
        return AgentResult.completed("ok", t, 10);
    }

    @Test
    @DisplayName("counts the results and names only the withheld ones")
    void countsAndNames() {
        String line = ResultDelivery.withheldLine(runWith(List.of(
                Map.of("n", 1, "tool", "daily_news_digest", "label", "PUBLIC", "chars", 3000),
                Map.of("n", 2, "tool", "smtp_send_email", "label", "PRIVATE", "chars", 180),
                Map.of("n", 3, "tool", "smtp_send_email", "label", "PRIVATE", "chars", 180))));

        // TWO results were withheld, from ONE tool. The first version printed the size of the
        // name set as the count, so calling one skill twice read as "1 withheld" while two
        // results were being held back -- the line exists to tell the owner exactly that number.
        assertTrue(line.contains("3 results, 2 withheld from the cloud (smtp_send_email)"), line);
    }

    @Test
    @DisplayName("an all-public run says none were withheld")
    void allPublic() {
        String line = ResultDelivery.withheldLine(runWith(List.of(
                Map.of("n", 1, "tool", "daily_news_digest", "label", "PUBLIC", "chars", 3000))));
        assertTrue(line.contains("1 result, 0 withheld from the cloud"), line);
        assertFalse(line.contains("("), "nothing to name");
    }

    @Test
    @DisplayName("a private answer is saved and sent beside the safe text, never as it")
    void privateAnswerIsSavedAndEmittedSafely(@TempDir Path tmp) throws Exception {
        var jdbc = MigratedDatabase.at(tmp.resolve("t.db"));
        var conversations = new ConversationService(jdbc);
        String session = conversations.createSession("u1", "Where it was asked");
        var emitter = new ChatStatusEmitter();
        var messages = new ArrayList<StatusMessage>();
        var telegram = new ArrayList<String>();
        emitter.subscribe("u1", "web", messages::add);
        // What Telegram's subscriber sends, through the real function it uses.
        emitter.subscribe("u1", "telegram", m -> telegram.add(
                com.ownclaw.interfaces.telegram.TelegramBotService.telegramText(m)));
        String secret = "Closing balance 48,213.07 CZK";
        var answer = AgentResult.completed("[Private answer: kept on this machine.]", new AgentTrajectory(), 1)
                .withOwnerText("**Private:**\n\n" + secret);

        // With a task id and without one: the two are emitted by different calls.
        var delivery = new ResultDelivery(conversations, emitter);
        delivery.deliver("u1", () -> session, "Background task", answer.withTaskId("a1b2c3d4"));
        delivery.deliver("u1", () -> session, "Background task", answer);

        var rows = jdbc.queryForList("SELECT content, private_content FROM conversations");
        assertEquals(2, rows.size());
        for (var row : rows) {
            assertFalse(String.valueOf(row.get("content")).contains(secret), "content feeds later prompts");
            assertTrue(String.valueOf(row.get("private_content")).contains(secret), "the owner's reload shows it");
            assertTrue(String.valueOf(row.get("private_content")).startsWith("**Background task**"),
                    "with the header that says what it answers");
        }

        assertEquals(2, messages.size());
        for (StatusMessage m : messages) {
            assertEquals(StatusMessage.Type.RESULT, m.type());
            assertFalse(m.text().contains(secret), m.text());
            assertTrue(String.valueOf(m.data().get("ownerText")).contains(secret), "for the owner's screens");
        }
        assertEquals(2, telegram.size());
        assertTrue(telegram.stream().allMatch(t -> t.contains(secret)),
                "Telegram gets the owner's answer -- his decision: " + telegram);
    }

    @Test
    @DisplayName("a result is saved into the chat the caller names, not the open one, and says which")
    void savedIntoTheNamedChat(@TempDir Path tmp) throws Exception {
        var jdbc = MigratedDatabase.at(tmp.resolve("t.db"));
        var conversations = new ConversationService(jdbc);
        String asked = conversations.createSession("u1", "Where /bg was typed");
        String open = conversations.createSession("u1", "Opened since");   // the active chat now
        var emitter = new ChatStatusEmitter();
        var messages = new ArrayList<StatusMessage>();
        emitter.subscribe("u1", "web", messages::add);

        new ResultDelivery(conversations, emitter).deliver("u1", () -> asked, "Background task",
                AgentResult.completed("the weather is fine", new AgentTrajectory(), 1));

        assertEquals(List.of(asked), jdbc.queryForList(
                "SELECT session_id FROM conversations WHERE content LIKE '%the weather is fine%'", String.class));
        assertEquals(open, conversations.getCurrentSession("u1"), "delivering does not switch chats");
        assertEquals(asked, messages.get(0).data().get("sessionId"), "the page is told which chat it is for");
    }

    /** The command a task's ending offers for a skill that waits for a grant (AgentLoop#grantsAsked). */
    static final String GRANT = "/grant mailer SMTP_PASS";

    @Test
    @DisplayName("a task's result goes with what it offers to click -- saved with it, sent with it, read back with its chat -- as an attended answer does; text of a caller's own offers nothing")
    void aResultsChoicesAreSavedAndSent(@TempDir Path tmp) throws Exception {
        var jdbc = MigratedDatabase.at(tmp.resolve("t.db"));
        var conversations = new ConversationService(jdbc);
        String session = conversations.createSession("u1", "Where /bg was typed");
        var emitter = new ChatStatusEmitter();
        var messages = new ArrayList<StatusMessage>();
        emitter.subscribe("u1", "web", messages::add);
        var delivery = new ResultDelivery(conversations, emitter);

        // As /bg delivers: the result itself. A question's answer first, then the command code wrote.
        delivery.deliver("u1", () -> session, "Background task: mail me the report",
                AgentResult.needsInput("Send it now or later?", new AgentTrajectory(), 1)
                        .withChoices(List.of("Later", GRANT)).withTaskId("a1b2c3d4"));
        // As self-repair tells the owner: text of its own.
        delivery.deliver("u1", () -> session, "Self-repair of mailer", "It was stopped.", "b2c3d4e5", null);

        assertEquals("{\"taskId\":\"a1b2c3d4\",\"choices\":[\"Later\",\"" + GRANT + "\"]}", jdbc.queryForObject(
                "SELECT metadata FROM conversations WHERE content LIKE '%Send it now or later?%'", String.class),
                "kept in the row's metadata, as an attended answer's are");
        var rows = conversations.getSessionMessages("u1", session, null, null).messages();
        assertEquals("[\"Later\",\"" + GRANT + "\"]", String.valueOf(rows.get(0).get("choices")),
                "the chat's history gives them back, for the page to draw after a reload");
        assertEquals(List.of("Later", GRANT), ResultDelivery.choicesOf(messages.get(0)), "sent with it: " + messages.get(0).data());
        assertEquals(List.of("Later", GRANT), messages.get(0).data().get(ResultDelivery.CHOICES));

        assertNull(rows.get(1).get("choices"), "a caller's own text offers nothing: " + rows.get(1));
        assertFalse(messages.get(1).data().containsKey(ResultDelivery.CHOICES), messages.get(1).data().toString());
        assertEquals(List.of(), ResultDelivery.choicesOf(messages.get(1)));
        // Mutations: saveResult keeps no choices -> the metadata and the history have none; the
        // result's data without them -> the event offers none; the AgentResult overload handing on
        // none -> neither.
    }

    @Test
    @DisplayName("every choice a result offers is saved and sent whole: no cap on how many there are, or on how long one is")
    void everyChoiceWhole(@TempDir Path tmp) throws Exception {
        var jdbc = MigratedDatabase.at(tmp.resolve("t.db"));
        var conversations = new ConversationService(jdbc);
        String session = conversations.createSession("u1", "Where /bg was typed");
        var emitter = new ChatStatusEmitter();
        var messages = new ArrayList<StatusMessage>();
        emitter.subscribe("u1", "web", messages::add);
        var offered = new ArrayList<String>();
        for (int i = 0; i < 60; i++) offered.add("/grant skill_" + i + " KEY_" + i);
        offered.add("/grant mailer " + "SMTP_PASS_".repeat(800) + "END");

        new ResultDelivery(conversations, emitter).deliver("u1", () -> session, "Background task",
                AgentResult.completed("Sixty-one skills wait.", new AgentTrajectory(), 1).withChoices(offered));

        assertEquals(offered, ResultDelivery.choicesOf(messages.get(0)));
        var saved = (com.fasterxml.jackson.databind.JsonNode) conversations.getSessionMessages("u1", session, null, null)
                .messages().get(0).get("choices");
        assertEquals(offered.size(), saved.size());
        assertEquals(offered.getLast(), saved.get(offered.size() - 1).asText());
    }

    @Test
    @DisplayName("only a delivered result is read for choices: a status of any other kind that carries the key offers nothing")
    void onlyAResultOffers() {
        var data = Map.<String, Object>of(ResultDelivery.CHOICES, List.of(GRANT));
        for (var type : StatusMessage.Type.values()) {
            assertEquals(type == StatusMessage.Type.RESULT ? List.of(GRANT) : List.of(),
                    ResultDelivery.choicesOf(new StatusMessage(type, "text", data, "a1b2c3d4")), type.name());
        }
        assertEquals(List.of(), ResultDelivery.choicesOf(new StatusMessage(StatusMessage.Type.RESULT, "text", null)));
        assertEquals(List.of(), ResultDelivery.choicesOf(new StatusMessage(StatusMessage.Type.RESULT, "text",
                Map.of(ResultDelivery.CHOICES, GRANT))), "one string is no list of choices");
        assertEquals(List.of(), ResultDelivery.choicesOf(null));
        // Mutation: choicesOf without its test of the type -> a warning, a question's status or a
        // progress message carrying the key offers it, and Telegram makes it a key.
    }

    /** What /bg's delivery saves for {@code result}: its content row. */
    private static String deliveredAsBg(Path tmp, AgentResult result) throws Exception {
        var jdbc = MigratedDatabase.at(tmp.resolve("t.db"));
        var conversations = new ConversationService(jdbc);
        String session = conversations.createSession("u1", "Where /bg was typed");
        new ResultDelivery(conversations, new ChatStatusEmitter())
                .deliver("u1", () -> session, "Background task: digest", result);
        return jdbc.queryForObject("SELECT content FROM conversations", String.class);
    }

    /** A run whose delegation reported one private result; a public direct call reports none. */
    private static AgentTrajectory mixedRun() {
        return runWith(List.of(Map.of("n", 2, "tool", "imap_fetch", "label", "PRIVATE", "chars", 61))).trajectory();
    }

    @Test
    @DisplayName("a run that did not finish is delivered as its ending, which lists every result: no second count beneath it")
    void anEndingIsNotCountedTwice(@TempDir Path tmp) throws Exception {
        String ending = "**Stopped:** no progress for 10m 0s.\n\n**What it produced:**\n"
                + "- result 1 (web_fetch): 54 chars, public — in full below.\n"
                + "- result 2 (imap_fetch): 61 chars, private (personal source) — shown to you only.";
        String saved = deliveredAsBg(tmp, AgentResult.stalled(ending, mixedRun(), 30));
        assertTrue(saved.endsWith(ending), "a line counting \"1 result\" under a list of two: " + saved);
    }

    @Test
    @DisplayName("a finished answer, which lists no results, still says what stayed on this machine")
    void anAnswerIsCounted(@TempDir Path tmp) throws Exception {
        String saved = deliveredAsBg(tmp, AgentResult.completed("Your digest: sunny.", mixedRun(), 30));
        assertTrue(saved.endsWith("Your digest: sunny.\n\n_1 result, 1 withheld from the cloud (imap_fetch)_"), saved);
    }

    @Test
    @DisplayName("the header of a run that did not finish says so; why is the ending's first line, said once")
    void theHeaderDoesNotRepeatWhy(@TempDir Path tmp) throws Exception {
        String saved = deliveredAsBg(tmp, AgentResult.stalled("**Stopped:** no progress for 10m 0s.",
                new AgentTrajectory(), 30));
        assertEquals("**Background task: digest — did not finish**\n\n**Stopped:** no progress for 10m 0s.",
                saved);
    }

    @Test
    @DisplayName("a run with nothing recorded says nothing at all")
    void silentWhereItCannotSee() {
        assertEquals("", ResultDelivery.withheldLine(AgentResult.completed("ok", new AgentTrajectory(), 1)));
        assertEquals("", ResultDelivery.withheldLine(null));
    }
}
