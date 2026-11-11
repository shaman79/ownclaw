package com.ownclaw.interfaces.telegram;

import com.ownclaw.observability.ChatStatusEmitter.StatusMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** What Telegram is sent for a fact the local model found in a turn from there. */
class TelegramUndoLineTest {

    @Test
    @DisplayName("the fact, with the command that takes it back -- a link to tap in Telegram")
    void theUndoLine() {
        var kept = new StatusMessage(StatusMessage.Type.PROGRESS_MESSAGE, "🏠 Remembered",
                Map.of("ownerText", "🏠 Remembered\n\nThe NAS is at 192.0.2.20.", "undo", "/forget_k3j9x2m1qa", "telegram", true),
                null);
        assertTrue(TelegramBotService.forTelegram(kept));
        assertEquals("🏠 Remembered\n\nThe NAS is at 192.0.2.20.\n\nUndo: /forget_k3j9x2m1qa", TelegramBotService.statusText(kept));
        var offer = new StatusMessage(StatusMessage.Type.PROGRESS_MESSAGE, "🏠 Found in the answer: keep it?",
                Map.of("ownerText", "🏠 Found in the answer: keep it?\n\nThe Supervisor answers at http://supervisor/core/api.",
                        "keep", "/keep_k3j9x2m1qa", "telegram", true), null);
        assertEquals("🏠 Found in the answer: keep it?\n\nThe Supervisor answers at http://supervisor/core/api."
                + "\n\nKeep: /keep_k3j9x2m1qa", TelegramBotService.statusText(offer));
        assertTrue(TelegramBotService.PRIVATE_ONLY.contains("private chat"), "a group is told where they work");
        var step = new StatusMessage(StatusMessage.Type.PROGRESS_MESSAGE, "☁️ Step 1 · ping",
                Map.of("telegram", true), null);
        assertEquals("☁️ Step 1 · ping", TelegramBotService.statusText(step), "no undo line where there is nothing to undo");
    }
}
