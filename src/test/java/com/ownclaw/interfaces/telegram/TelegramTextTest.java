package com.ownclaw.interfaces.telegram;

import com.ownclaw.observability.ChatStatusEmitter.StatusMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
}
