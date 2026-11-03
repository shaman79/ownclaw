package com.ownclaw.interfaces.telegram;

import com.ownclaw.observability.ChatStatusEmitter.StatusMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** What Telegram is sent: the owner decided a private answer goes there in full. */
class TelegramTextTest {

    @Test
    @DisplayName("a result with the owner's text sends that text; anything else is sent as formatted")
    void ownersTextForResults() {
        var privateResult = new StatusMessage(StatusMessage.Type.RESULT, "[Private answer: kept here.]",
                Map.of("ownerText", "Closing balance 48,213.07 CZK"), "abcd1234");
        assertEquals("Closing balance 48,213.07 CZK", TelegramBotService.telegramText(privateResult));

        var plainResult = new StatusMessage(StatusMessage.Type.RESULT, "The menu is ready.", null, "abcd1234");
        assertEquals("The menu is ready.", TelegramBotService.telegramText(plainResult));

        var step = new StatusMessage(StatusMessage.Type.STEP, "fetching",
                Map.of("ownerText", "never for a step"), "abcd1234");
        assertEquals("→ fetching", TelegramBotService.telegramText(step));
    }

    @Test
    @DisplayName("results go only to the private chat of a Telegram id linked to this user now")
    void onlyTheOwnersOwnChat() {
        java.util.function.LongFunction<java.util.Optional<String>> linked =
                id -> id == 4242L ? java.util.Optional.of("owner") : java.util.Optional.empty();
        assertTrue(TelegramBotService.isOwnersChat("owner", 4242L, linked));
        assertFalse(TelegramBotService.isOwnersChat("owner", -100123L, linked), "a group");
        assertFalse(TelegramBotService.isOwnersChat("someone", 4242L, linked), "linked to another user");
        assertFalse(TelegramBotService.isOwnersChat("owner", 4242L, id -> java.util.Optional.empty()), "unlinked");
        assertFalse(TelegramBotService.isOwnersChat("owner", null, linked));
    }

    @Test
    @DisplayName("a long answer goes in parts Telegram accepts, split after a line break, nothing lost")
    void longAnswersAreSplit() {
        String line = "  indented " + "x".repeat(88) + "\n";
        String text = line.repeat(100);                       // 10,000 characters
        var parts = TelegramBotService.telegramParts(text, 4096);
        assertTrue(parts.size() >= 3);
        for (String part : parts) {
            assertTrue(part.length() <= 4096, "part of " + part.length());
            assertTrue(part.endsWith("\n"), "cut after a line break");
        }
        // Exactly the text: the indentation and the line break at each cut are kept.
        assertEquals(text, String.join("", parts));
        assertEquals(List.of("short"), TelegramBotService.telegramParts("short", 4096));
    }

    @Test
    @DisplayName("a cut with no line break near it never splits a character in two")
    void neverHalfACharacter() {
        String emoji = "\uD83D\uDE00";                          // one character, two UTF-16 units
        for (String text : List.of("a" + emoji.repeat(10), emoji.repeat(10), "ab" + emoji.repeat(10))) {
            var parts = TelegramBotService.telegramParts(text, 5);
            assertEquals(text, String.join("", parts));
            for (String part : parts) {
                assertTrue(part.length() <= 5, part);
                assertFalse(Character.isHighSurrogate(part.charAt(part.length() - 1)), "half an emoji ends " + part);
                assertFalse(Character.isLowSurrogate(part.charAt(0)), "half an emoji starts " + part);
            }
        }
    }

    @Test
    @DisplayName("Telegram is sent what is meant for the owner, not a task's steps and heartbeats")
    void onlyWhatIsMeantForTheOwner() {
        for (var type : StatusMessage.Type.values()) {
            boolean sent = TelegramBotService.forTelegram(new StatusMessage(type, "x", null, "abcd1234"));
            boolean meant = switch (type) {
                case RESULT, NEED_INPUT, WARNING, FAILED -> true;
                default -> false;
            };
            assertEquals(meant, sent, type.name());
        }
    }
}
