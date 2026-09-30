package com.ownclaw.conversation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A private answer is stored beside its safe text, and read back by the owner's chat alone.
 * What builds a prompt -- a chat task's context -- reads the safe text, on the real schema.
 */
class ConversationPrivateContentTest {

    static final String NOTE = "[Private answer: sent to you only, never to the cloud model.]";
    static final String SECRET = "Closing balance 48,213.07 CZK; rent 12,500.00 CZK on the 1st.";

    @Test
    @DisplayName("the chat reload shows the private answer; a task's context shows the note")
    void onlyTheReloadReadsThePrivateText(@TempDir Path tmp) throws Exception {
        var jdbc = MigratedDatabase.at(tmp.resolve("t.db"));
        var conversations = new ConversationService(jdbc);
        String session = conversations.createSession("u1", "Statements");

        conversations.saveMessage("u1", session, "assistant", NOTE, List.of(), "a1b2c3d4", SECRET);
        conversations.saveMessage("u1", session, "assistant", "An ordinary answer.", List.of(), "b2c3d4e5");
        String asked = conversations.saveMessage("u1", session, "user", "and the rent?");

        var context = conversations.contextOf("u1", asked).stream()
                .map(m -> String.valueOf(m.get("content"))).toList();
        assertTrue(context.contains(NOTE), context.toString());
        assertTrue(context.stream().noneMatch(c -> c.contains("48,213.07")),
                "a task's context goes into every prompt of that task: " + context);

        var reload = conversations.getSessionMessages("u1", session).stream()
                .map(m -> String.valueOf(m.get("content"))).toList();
        assertTrue(reload.contains(SECRET), "the owner's chat shows what it showed live: " + reload);
        assertFalse(reload.contains(NOTE));
        assertTrue(reload.contains("An ordinary answer."), "a row with no private text shows its content");
    }
}
