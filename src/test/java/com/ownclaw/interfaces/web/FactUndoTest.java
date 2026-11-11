package com.ownclaw.interfaces.web;

import com.ownclaw.agent.memory.CapturedFacts;
import com.ownclaw.agent.memory.SqliteAgentMemory;
import com.ownclaw.conversation.ConversationService;
import com.ownclaw.conversation.MigratedDatabase;
import com.ownclaw.interfaces.CommandHandler;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Undo and the Keep of a fact the local model found in a chat turn, everywhere the owner can
 * press them: the web chat's buttons (their endpoints and the page's code), and /forget and /keep
 * in either chat.
 */
class FactUndoTest {

    @TempDir
    Path tmp;
    JdbcTemplate jdbc;
    SqliteAgentMemory memory;
    CapturedFacts captured;

    static String page;

    static final CapturedFacts.Row ROW = c -> "2026-11-11 12:00:00";

    @BeforeAll
    static void read() throws Exception {
        try (var in = FactUndoTest.class.getResourceAsStream("/static/index.html")) {
            assertNotNull(in, "static/index.html is not on the classpath");
            page = new String(in.readAllBytes(), StandardCharsets.UTF_8).replaceAll("\\s+", " ");
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        jdbc = MigratedDatabase.at(tmp.resolve("t.db"));
        memory = new SqliteAgentMemory(jdbc);
        captured = new CapturedFacts(jdbc, memory, new ConversationService(jdbc));
    }

    /** A request as JwtAuthFilter leaves it: signed in as this user, or not at all. */
    private static HttpServletRequest as(String userId) {
        return (HttpServletRequest) Proxy.newProxyInstance(FactUndoTest.class.getClassLoader(),
                new Class<?>[]{HttpServletRequest.class},
                (p, m, args) -> "getAttribute".equals(m.getName()) && "userId".equals(args[0]) ? userId : null);
    }

    private String kept(String key, String fact) {
        return captured.write("u1", "s1", null, CapturedFacts.Source.OWNER, key, fact, false, ROW).capture().ref();
    }

    private String offered(String key, String fact) {
        return captured.write("u1", "s1", null, CapturedFacts.Source.ANSWER, key, fact, true, ROW).capture().ref();
    }

    @Test
    @DisplayName("the web chat's Undo and Keep act on the caller's own fact, once; what fails is said, never an error page")
    void theEndpoints() {
        var controller = new FactController(captured);
        String ref = kept("nas", "The NAS is at 192.0.2.20.");
        assertEquals(401, controller.undo(as(null), ref).getStatusCode().value());
        assertEquals(Map.of("done", false, "text", "There is no such remembered fact."),
                controller.undo(as("u2"), ref).getBody(), "another account's");
        assertEquals(Map.of("done", true, "text", "Forgotten: The NAS is at 192.0.2.20."), controller.undo(as("u1"), ref).getBody());
        assertEquals(false, ((Map<?, ?>) controller.undo(as("u1"), ref).getBody()).get("done"));

        String offer = offered("supervisor", "The Supervisor answers at http://supervisor/core/api.");
        assertEquals(401, controller.keep(as(null), offer).getStatusCode().value());
        assertEquals(Map.of("done", true, "text", "Kept: The Supervisor answers at http://supervisor/core/api."),
                controller.keep(as("u1"), offer).getBody());

        jdbc.execute("DROP TABLE fact_captures");
        var failed = controller.undo(as("u1"), ref);
        assertEquals(200, failed.getStatusCode().value());
        assertEquals(Map.of("done", false, "text", "It could not be undone just now. Try again."), failed.getBody());
    }

    @Test
    @DisplayName("/forget and /keep take a fact back or keep it in either chat, as Telegram links them; without a ref they say how")
    void theCommands() {
        var commands = new CommandHandler(null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null);
        commands.setCapturedFacts(captured);
        String ref = kept("printer", "The printer is in the study.");
        assertEquals("Forgotten: The printer is in the study.", commands.handle("u1", "/forget_" + ref, null).orElseThrow());
        String again = kept("printer", "The printer is in the hall.");
        assertEquals("Forgotten: The printer is in the hall.",
                commands.handle("u1", "/forget " + again + "@OwnClawBot", null).orElseThrow());
        String offer = offered("supervisor", "The Supervisor answers at http://supervisor/core/api.");
        assertEquals("Kept: The Supervisor answers at http://supervisor/core/api.\n\nUndo: /forget_" + offer,
                commands.handle("u1", "/keep_" + offer, null).orElseThrow());
        assertTrue(commands.handle("u1", "/forget", null).orElseThrow().startsWith("Usage: `/forget <ref>`"));
        assertTrue(commands.handle("u1", "/forget the printer", null).orElseThrow().startsWith("Usage"));
        String help = commands.handle("u1", "/help", null).orElseThrow();
        assertTrue(help.contains("`/forget <ref>`") && help.contains("`/keep <ref>`"), help);
        assertTrue(CommandHandler.privateOnly("/forget_" + ref));
        assertTrue(CommandHandler.privateOnly(" /keep " + offer));
        assertFalse(CommandHandler.privateOnly("/forgetful"));
        assertFalse(CommandHandler.privateOnly("forget it"));
    }

    @Test
    @DisplayName("the page draws Undo and Keep under a fact found in a turn, by its ref; such a row is no sign of work")
    void thePage() {
        String handler = page.substring(page.indexOf("} else if (type === 'progress' && data.progress "
                + "&& (data.progress.kind === 'remembered' || data.progress.kind === 'offered')) {"));
        handler = handler.substring(0, handler.indexOf("} else if (type === 'progress') {"));
        assertFalse(handler.contains("markWorking") || handler.contains("resetThinkingSafetyTimer"), handler);
        assertTrue(handler.contains("if (currentView === 'chat' && data.sessionId === displayedSessionId) {"
                + " addProgress(content, data.progress, null, data.timestamp); } else if (data.sessionId) {"
                + " markUnread(data.sessionId); }"), "drawn, or its chat marked unread -- never dropped: " + handler);
        assertTrue(page.contains("if ((header.kind === 'remembered' || header.kind === 'offered') && header.ref) "
                + "addFactButtons(main, header);"));
        String buttons = page.substring(page.indexOf("function addFactButtons(main, header) {"));
        buttons = buttons.substring(0, buttons.indexOf("main.appendChild(group); }"));
        assertTrue(buttons.contains("node('div', 'msg-undo')"), "not msg-choices, which the next message retires: " + buttons);
        assertTrue(buttons.contains("fetch('/api/facts/captures/' + encodeURIComponent(header.ref) + '/' + action, "
                + "{ method: 'POST', headers: { 'Authorization': 'Bearer ' + authToken } })"), buttons);
        assertTrue(buttons.contains("act(b, 'undo',") && buttons.contains("act(keep, 'keep',"), buttons);
        assertTrue(buttons.contains("if (header.kind === 'offered' && !header.kept && !header.undone) {"),
                "a fact kept or taken back shows so after a reload: " + buttons);
        assertTrue(buttons.contains("said.textContent = res.text;"), "what was done is said, as text: " + buttons);
    }
}
