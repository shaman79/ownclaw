package com.ownclaw.conversation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** A chat's title and preview are stored whole; the sidebar lays them out. */
class SessionTitleTest {

    @Test
    @DisplayName("the title is the first line with text, whole -- a dot in an address does not end it")
    void titles() {
        String line = "Check every router at 192.0.2.1 and 192.0.2.2 and tell me which guest network is not isolated";
        assertEquals(line, ConversationService.generateTitle("\n  " + line + "\nand then email me"));
        assertEquals("New Chat", ConversationService.generateTitle("  \n "));
    }

    @Test
    @DisplayName("the preview is the first user message, whole, and the title is set from it")
    void preview(@TempDir Path tmp) throws Exception {
        var jdbc = MigratedDatabase.at(tmp.resolve("t.db"));
        var chats = new ConversationService(jdbc);
        String session = chats.createSession("u1", "New Chat");
        String first = "Audit the network. " + "Every router, every access point, every guest network. ".repeat(20);
        chats.saveMessage("u1", session, "assistant", "Hello.");
        chats.saveMessage("u1", session, "user", first);
        chats.saveMessage("u1", session, "user", "a later message");
        chats.autoTitleIfNeeded("u1", session, first);
        assertEquals(first, jdbc.queryForObject("SELECT preview FROM chat_sessions WHERE id = ?", String.class, session));
        assertEquals(first.strip(), jdbc.queryForObject("SELECT title FROM chat_sessions WHERE id = ?", String.class, session));
    }
}
