package com.ownclaw.interfaces.web;

import com.ownclaw.conversation.ConversationService;
import com.ownclaw.conversation.MigratedDatabase;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A chat is read a page at a time, newest first, as the page draws it: every row of the chat is
 * in exactly one page, in the order the whole chat is read in, on the real schema.
 */
class ChatHistoryPagingTest {

    private JdbcTemplate jdbc;
    private ConversationService conversations;
    private ChatHistoryController controller;

    private void start(Path tmp) throws Exception {
        jdbc = MigratedDatabase.at(tmp.resolve("t.db"));
        conversations = new ConversationService(jdbc);
        controller = new ChatHistoryController(conversations);
    }

    /** A request as JwtAuthFilter leaves it: signed in as this user. */
    private static HttpServletRequest as(String userId) {
        return (HttpServletRequest) Proxy.newProxyInstance(ChatHistoryPagingTest.class.getClassLoader(),
                new Class<?>[]{HttpServletRequest.class},
                (p, m, args) -> "getAttribute".equals(m.getName()) && "userId".equals(args[0]) ? userId : null);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> get(String userId, String chat, String before, Integer limit) {
        var response = controller.getMessages(as(userId), chat, before, limit);
        assertEquals(200, response.getStatusCode().value());
        return (Map<String, Object>) response.getBody();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> ids(Map<String, Object> page) {
        return ((List<Map<String, Object>>) page.get("messages")).stream().map(m -> m.get("id")).toList();
    }

    /** Every row, read as the page reads them: the newest page, then the one before its oldest row, and on. */
    private List<Object> paged(String userId, String chat, int limit) {
        var rows = new ArrayList<Object>();
        var page = get(userId, chat, null, limit);
        while (true) {
            var ids = ids(page);
            assertTrue(ids.size() <= limit, "a page of " + limit + ": " + ids);
            assertTrue(Collections.disjoint(rows, ids), "a row in two pages: " + ids + " after " + rows);
            rows.addAll(0, ids);
            if (!(Boolean) page.get("hasMore")) return rows;
            assertEquals(limit, ids.size(), "only the last page is short");
            page = get(userId, chat, (String) ids.getFirst(), limit);
        }
    }

    @Test
    @DisplayName("the pages cover every row once, in the chat's order, whatever their size -- across rows of one second")
    void pagesCoverEveryRowOnce(@TempDir Path tmp) throws Exception {
        start(tmp);
        String chat = conversations.createSession("u1", "Router");
        var saved = new ArrayList<String>();
        for (int i = 0; i < 9; i++) {
            saved.add(conversations.saveMessage("u1", chat, "user", "question " + i));
            conversations.saveProgress("u1", chat, "Step " + i, "a1b2c3d4", null, Map.of("actor", "cloud"));
            saved.add(jdbc.queryForObject(
                    "SELECT id FROM conversations WHERE role = 'progress' ORDER BY rowid DESC LIMIT 1", String.class));
            saved.add(conversations.saveMessage("u1", chat, "assistant", "answer " + i, List.of(), "a1b2c3d4"));
            if (i % 3 == 0) saved.add(conversations.saveMessage("u1", chat, "system", "command reply " + i));
        }
        conversations.saveMessage("u1", chat, "status", "Working...");
        // Four rows to a second, so page boundaries fall inside a second; the last row saved under
        // a clock set back, so its second is the first.
        for (int i = 0; i < saved.size(); i++) {
            jdbc.update("UPDATE conversations SET timestamp = ? WHERE id = ?",
                    "2026-10-08 12:00:%02d".formatted(i == saved.size() - 1 ? 0 : i / 4), saved.get(i));
        }
        var expected = new ArrayList<Object>(saved);
        expected.add(4, expected.removeLast());

        var whole = get("u1", chat, null, null);
        assertEquals(expected, ids(whole), "the whole chat, by second and then in the order saved, without status rows");
        assertEquals(false, whole.get("hasMore"));
        for (int limit : new int[] {1, 2, 3, 4, 5, 7, saved.size() - 1, saved.size(), saved.size() + 1, 50}) {
            assertEquals(expected, paged("u1", chat, limit), "pages of " + limit);
        }
        // Mutation: compare the timestamp alone at the boundary -> the rest of a second the page
        // ends in is skipped; order by timestamp alone -> rows of one second come in any order.
    }

    @Test
    @DisplayName("a page says whether rows before it remain: not when the chat holds exactly a page")
    void hasMoreIsExact(@TempDir Path tmp) throws Exception {
        start(tmp);
        String chat = conversations.createSession("u1", "Mail");
        for (int i = 0; i < 4; i++) conversations.saveMessage("u1", chat, "user", "message " + i);

        assertEquals(false, get("u1", chat, null, 4).get("hasMore"));
        assertEquals(true, get("u1", chat, null, 3).get("hasMore"));
        assertEquals(4, ids(get("u1", chat, null, 4)).size());
    }

    @Test
    @DisplayName("an empty chat is one empty page")
    void emptyChat(@TempDir Path tmp) throws Exception {
        start(tmp);
        String chat = conversations.createSession("u1", "New Chat");

        var page = get("u1", chat, null, 50);
        assertEquals(List.of(), page.get("messages"));
        assertEquals(false, page.get("hasMore"));
        assertEquals(chat, page.get("sessionId"));
    }

    @Test
    @DisplayName("another user's chat reads as empty, whole or a page at a time, and his row is no cursor in one's own")
    void anotherUsersChatIsRefused(@TempDir Path tmp) throws Exception {
        start(tmp);
        // Saved after the second user's row, so as a cursor each would have it in the page.
        String mine = conversations.createSession("u2", "Mine");
        conversations.saveMessage("u2", mine, "user", "hello");
        String theirs = conversations.createSession("u1", "Bank");
        conversations.saveMessage("u1", theirs, "user", "my statement");
        String theirRow = conversations.saveMessage("u1", theirs, "assistant", "48,213.07", List.of(), "a1b2c3d4");
        String otherChat = conversations.createSession("u2", "Other");
        String otherRow = conversations.saveMessage("u2", otherChat, "user", "elsewhere");

        assertEquals(List.of(), ids(get("u2", theirs, null, null)));
        assertEquals(List.of(), ids(get("u2", theirs, null, 50)));
        assertEquals(List.of(), ids(get("u2", theirs, theirRow, 50)));
        assertEquals(false, get("u2", theirs, theirRow, 50).get("hasMore"));
        assertEquals(List.of(), ids(get("u2", mine, theirRow, 50)), "a row of another user's chat");
        assertEquals(List.of(), ids(get("u2", mine, otherRow, 50)), "a row of another chat");
        assertEquals(1, ids(get("u2", mine, null, 50)).size(), "his own chat reads as before");
        // Mutation: drop the user from the query -> the second user reads the first one's balance.
    }

    @Test
    @DisplayName("a page of no rows is refused, not answered with an empty page that says more remain")
    void aPageHoldsARow(@TempDir Path tmp) throws Exception {
        start(tmp);
        String chat = conversations.createSession("u1", "Router");
        conversations.saveMessage("u1", chat, "user", "hello");

        assertEquals(400, controller.getMessages(as("u1"), chat, null, 0).getStatusCode().value());
        assertEquals(400, controller.getMessages(as("u1"), chat, null, -1).getStatusCode().value());
    }
}
