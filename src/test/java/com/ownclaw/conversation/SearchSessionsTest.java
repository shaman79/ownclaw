package com.ownclaw.conversation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/** The sidebar search, on the real schema: every chat that matches, once, with its best snippet. */
class SearchSessionsTest {

    @Test
    @DisplayName("a chat with many matches does not hide the others: every matching chat, once")
    void everyMatchingChatOnce(@TempDir Path tmp) throws Exception {
        var conversations = new ConversationService(MigratedDatabase.at(tmp.resolve("t.db")), null);
        String busy = conversations.createSession("u1", "Busy");
        String quiet = conversations.createSession("u1", "Quiet");
        String elsewhere = conversations.createSession("u2", "Someone else's");
        // Forty short, dense matches: each ranks above the one long message in the quiet chat,
        // so a cut on matching MESSAGES keeps only the busy chat.
        for (int i = 0; i < 40; i++) {
            conversations.saveMessage("u1", busy, "user", "router router " + i);
        }
        conversations.saveMessage("u1", busy, "assistant", "router router router firmware");
        conversations.saveMessage("u1", quiet, "user", "the router came up once, in a long message about "
                + "many other things that are not routers at all, " + "filler ".repeat(40));
        conversations.saveMessage("u2", elsewhere, "user", "router router router");

        var results = conversations.searchMessages("u1", "router");

        assertEquals(List.of(busy, quiet), results.stream().map(r -> r.get("session_id")).toList(),
                "each chat once, best match first, and no one else's");
        assertTrue(String.valueOf(results.get(0).get("snippet")).contains("firmware"),
                "the snippet is from the chat's best-matching message: " + results.get(0));
    }

    @Test
    @DisplayName("a snippet is 64 tokens, the most SQLite gives")
    void snippetOf64Tokens(@TempDir Path tmp) throws Exception {
        var conversations = new ConversationService(MigratedDatabase.at(tmp.resolve("t.db")), null);
        String chat = conversations.createSession("u1", "Words");
        String words = IntStream.range(1, 100).mapToObj(i -> "w" + i).collect(Collectors.joining(" "));
        conversations.saveMessage("u1", chat, "user", "needle " + words);

        String snippet = String.valueOf(conversations.searchMessages("u1", "needle").get(0).get("snippet"));

        assertTrue(snippet.contains(" w63..."), "64 tokens: needle and w1..w63: " + snippet);
        assertFalse(snippet.contains("w64"), snippet);
    }
}
