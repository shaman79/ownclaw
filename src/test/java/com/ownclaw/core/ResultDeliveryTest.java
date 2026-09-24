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
        var conversations = new ConversationService(jdbc, null);
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
        delivery.deliver("u1", "Background task", answer.withTaskId("a1b2c3d4"));
        delivery.deliver("u1", "Background task", answer);

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
    @DisplayName("a run with nothing recorded says nothing at all")
    void silentWhereItCannotSee() {
        assertEquals("", ResultDelivery.withheldLine(AgentResult.completed("ok", new AgentTrajectory(), 1)));
        assertEquals("", ResultDelivery.withheldLine(null));
    }
}
