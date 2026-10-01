package com.ownclaw.interfaces;

import com.ownclaw.conversation.ConversationService;
import com.ownclaw.conversation.MigratedDatabase;
import com.ownclaw.observability.EventLogService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What the owner can list from a chat -- on Telegram the only place he can look: every chat
 * session, and every event and error, a page at a time or all at once.
 */
class ListingCommandsTest {

    private EventLogService events;
    private ConversationService conversations;
    private CommandHandler commands;

    private void start(Path tmp) throws Exception {
        var jdbc = MigratedDatabase.at(tmp.resolve("t.db"));
        events = new EventLogService(jdbc);
        conversations = new ConversationService(jdbc);
        commands = new CommandHandler(null, conversations, events, null, null, null, null, null, null, null,
                null, null, null, null);
    }

    private String run(String command) {
        return commands.handle("u1", command).orElseThrow();
    }

    @Test
    @DisplayName("/history lists every chat, not the first fifteen, and /switch reaches each of them")
    void everySession(@TempDir Path tmp) throws Exception {
        start(tmp);
        for (int i = 1; i <= 20; i++) conversations.createSession("u1", "Chat " + i);

        String history = run("/history");

        for (int i = 1; i <= 20; i++) assertTrue(history.contains("Chat " + i + " —"), "Chat " + i + "\n" + history);
        assertTrue(history.contains("**20.**"));
        assertTrue(run("/switch 20").contains("Switched"));
    }

    @Test
    @DisplayName("/log shows ten, newest first, says how to go further, and every event is reachable")
    void everyEventReachable(@TempDir Path tmp) throws Exception {
        start(tmp);
        for (int i = 1; i <= 25; i++) events.info("u1", null, "test.event", "event number " + i + ".");
        events.error("u1", null, "test.error", "the one error");

        String first = run("/log");
        assertTrue(first.startsWith("Events 1–10, newest first:"), first);
        assertTrue(first.contains("the one error") && first.contains("event number 17.")
                && !first.contains("event number 16."), first);
        assertTrue(first.endsWith("Older: `/log 10 page 2`"), first);

        String third = run("/log 10 page 3");
        assertTrue(third.contains("Events 21–26") && third.contains("event number 1.")
                && !third.contains("Older:"), third);

        String all = run("/log all");
        for (int i = 1; i <= 25; i++) assertTrue(all.contains("event number " + i + "."), "event " + i);

        assertTrue(run("/log 5 page 2").contains("Events 6–10"));
        assertTrue(run("/log page 9").startsWith("No events on page 9"));
        assertTrue(run("/log errors").contains("the one error") && !run("/log errors").contains("event number"));
        for (String bad : new String[]{"/log 0", "/log foo", "/log all page 2", "/log 99999999999999999999",
                "/log 9223372036854775807 page 3"}) {
            assertTrue(run(bad).startsWith("Usage: `/log"), bad + " -> " + run(bad));
        }
        assertTrue(commands.handle("u1", "/logout").isEmpty(), "another word is not /log");
    }

    @Test
    @DisplayName("/log errors pages like /log; a page that ends the list offers no older one; page 0 is no page")
    void errorsArePagedToo(@TempDir Path tmp) throws Exception {
        start(tmp);
        for (int i = 1; i <= 12; i++) events.error("u1", null, "test.error", String.format("error e%02d.", i));
        for (int i = 1; i <= 8; i++) events.info("u1", null, "test.event", "an event.");

        String second = run("/log errors 10 page 2");
        assertTrue(second.startsWith("Errors 11–12, newest first:"), second);
        assertTrue(second.contains("error e02.") && second.contains("error e01.") && !second.contains("error e03."), second);
        assertFalse(second.contains("Older:"), second);

        String exact = run("/log 10 page 2");                   // twenty events: this page ends them
        assertTrue(exact.startsWith("Events 11–20"), exact);
        assertFalse(exact.contains("Older:"), "no page after the last: " + exact);
        assertTrue(run("/log 10").endsWith("Older: `/log 10 page 2`"));

        assertTrue(run("/log page 0").startsWith("Usage: `/log"), run("/log page 0"));
        assertTrue(run("/log errors 5 page 0").startsWith("Usage: `/log"));
    }
}
