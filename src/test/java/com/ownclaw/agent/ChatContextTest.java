package com.ownclaw.agent;

import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.conversation.ConversationService;
import com.ownclaw.conversation.FileStorageService;
import com.ownclaw.conversation.MigratedDatabase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The chat a task came from goes into it whole -- every message, each in full -- and into
 * nothing else: every scheduled run used to carry the open chat to the cloud on every call.
 */
class ChatContextTest {

    private record Db(JdbcTemplate jdbc, ConversationService conversations, FileStorageService files) {}

    private static Db db(Path tmp) throws Exception {
        var jdbc = MigratedDatabase.at(tmp.resolve("t.db"));
        var config = new OwnClawConfig();
        config.getDatabase().setPath(tmp.resolve("t.db").toString());
        Files.createDirectories(tmp.resolve("uploads"));
        return new Db(jdbc, new ConversationService(jdbc), new FileStorageService(jdbc, config));
    }

    private static String contextOf(Db db, String currentMessageId) {
        var ctx = new AgentContext("u1", "t1", "the task's own text");
        AgentLoop.loadConversationContext(ctx, "u1", currentMessageId, db.conversations(), db.files(), id -> null);
        return ctx.conversationSummary();
    }

    @Test
    @DisplayName("every message of the chat, each whole and oldest first -- a long chat and summarised rows too")
    void theWholeChat(@TempDir Path tmp) throws Exception {
        var db = db(tmp);
        String session = db.conversations().createSession("u1", "Long answers");
        for (int i = 0; i < 30; i++) {
            db.conversations().saveMessage("u1", session, i % 2 == 0 ? "user" : "assistant",
                    "MSG" + i + " " + "x".repeat(6_000) + " END" + i);
        }
        // What the old summariser left behind: marked, never changed.
        db.jdbc().update("UPDATE conversations SET compressed = 1 WHERE content LIKE 'MSG1 %' OR content LIKE 'MSG2 %'");
        db.jdbc().update("UPDATE conversations SET timestamp = datetime('now', '-1 hour') WHERE content LIKE 'MSG0 %'");
        String current = db.conversations().saveMessage("u1", session, "user", "CURRENT and next?");

        String shown = contextOf(db, current);
        for (int i = 0; i < 30; i++) {
            assertTrue(shown.contains("MSG" + i + " " + "x".repeat(6_000) + " END" + i), "MSG" + i + " whole");
        }
        assertTrue(shown.indexOf("MSG0 ") < shown.indexOf("MSG1 ") && shown.indexOf("MSG28 ") < shown.indexOf("MSG29 "),
                "oldest first");
        assertFalse(shown.contains("CURRENT"), "the task's own message is its text, not its history");
        // Mutation: bring back a count or a size window -> the oldest messages are missing.
    }

    @Test
    @DisplayName("the chat is the one the task's message was saved in, not the one open now")
    void theTasksOwnChat(@TempDir Path tmp) throws Exception {
        var db = db(tmp);
        String network = db.conversations().createSession("u1", "Network");
        db.conversations().saveMessage("u1", network, "assistant", "NETWORK-CHAT answer");
        String asked = db.conversations().saveMessage("u1", network, "user", "what is the status?");
        // The owner opens another chat while the task waits in the queue.
        String other = db.conversations().createSession("u1", "Lunch");
        db.conversations().saveMessage("u1", other, "assistant", "LUNCH-CHAT answer");

        String shown = contextOf(db, asked);
        assertTrue(shown.contains("NETWORK-CHAT"), shown);
        assertFalse(shown.contains("LUNCH-CHAT"), "the chat open now is not the task's: " + shown);
        // Mutation: read getCurrentSession instead -> the lunch chat.
    }

    @Test
    @DisplayName("a question typed after the task's own is left out; an answer that came while it waited is read")
    void onlyWhatWasSaidBeforeIt(@TempDir Path tmp) throws Exception {
        var db = db(tmp);
        String session = db.conversations().createSession("u1", "Network");
        db.conversations().saveMessage("u1", session, "user", "EARLIER question");
        db.conversations().saveMessage("u1", session, "user", "QUEUED question, still running");
        String current = db.conversations().saveMessage("u1", session, "user", "CURRENT question");
        db.conversations().saveMessage("u1", session, "user", "LATER question, typed while CURRENT waited");
        db.conversations().saveMessage("u1", session, "assistant", "QUEUED answer", List.of(), "a1b2c3d4");

        String shown = contextOf(db, current);
        assertEquals("### The conversation so far\nUSER: EARLIER question\nUSER: QUEUED question, still running"
                + "\nASSISTANT: QUEUED answer", shown);
        // Mutations: read every row of the session -> the later question is presented as asked
        // before this one; read only the rows before this one -> the answer the owner's follow-up
        // builds on is missing.
    }

    @Test
    @DisplayName("a command's reply is not the conversation: /files names files, and none of it goes to the cloud")
    void commandRepliesStayOut(@TempDir Path tmp) throws Exception {
        var db = db(tmp);
        String session = db.conversations().createSession("u1", "Statements");
        db.conversations().saveMessage("u1", session, "user", "summarise my statement");
        db.conversations().saveMessage("u1", session, "assistant", AgentLoop.PRIVATE_NOTE);
        // What the chat kept of a command before replies stopped being saved, and what the setup
        // wizard still keeps.
        db.conversations().saveMessage("u1", session, "system",
                "### Uploaded files\n- `f1` vypis_123456789.pdf (15 B, 2026-09-30)");
        String asked = db.conversations().saveMessage("u1", session, "user", "and last month's?");

        String shown = contextOf(db, asked);
        assertFalse(shown.contains("vypis") || shown.contains("123456789") || shown.contains("SYSTEM"),
                "a file's name went into a cloud prompt: " + shown);
        assertTrue(shown.contains("USER: summarise my statement\nASSISTANT: " + AgentLoop.PRIVATE_NOTE), shown);
        // Mutation: read role != 'status' again -> the /files listing, file name and all.
    }

    @Test
    @DisplayName("a chat task sees its chat; an unattended one, and one no message came with, do not")
    void onlyChatTasksSeeTheChat(@TempDir Path tmp) throws Exception {
        var db = db(tmp);
        String session = db.conversations().createSession("u1", "Backups");
        db.conversations().saveMessage("u1", session, "user", "The rclone unit is CHAT-ONLY-DETAIL on the NAS");
        db.conversations().saveMessage("u1", session, "assistant", "Noted.");
        String asked = db.conversations().saveMessage("u1", session, "user", "and the second VM?");

        assertTrue(contextOf(db, asked).contains("CHAT-ONLY-DETAIL"));

        var scheduled = new AgentContext("u1", "t2", "Fetch the daily news digest and email it");
        scheduled.setUnattended(true);
        AgentLoop.loadConversationContext(scheduled, "u1", asked, db.conversations(), db.files(), id -> null);
        assertNull(scheduled.conversationSummary(), "a scheduled task is its own instruction");

        assertNull(contextOf(db, null), "a run with no message row came from no chat");
    }

    @Test
    @DisplayName("under each answer with a task, the record the caller gives for it; none where it gives none")
    void recordsGoUnderTheirAnswers(@TempDir Path tmp) throws Exception {
        var db = db(tmp);
        String session = db.conversations().createSession("u1", "Network");
        db.conversations().saveMessage("u1", session, "user", "audit the routers");
        db.conversations().saveMessage("u1", session, "assistant", "ANSWER-ONE", List.of(), "a1b2c3d4");
        db.conversations().saveMessage("u1", session, "assistant", "ANSWER-TWO", List.of(), "b2c3d4e5");
        String asked = db.conversations().saveMessage("u1", session, "user", "what happened?");

        var ctx = new AgentContext("u1", "t9", "what happened?");
        AgentLoop.loadConversationContext(ctx, "u1", asked, db.conversations(), db.files(),
                id -> id.equals("a1b2c3d4") ? "[RECORD OF a1b2c3d4]" : null);
        String shown = ctx.conversationSummary();
        assertTrue(shown.contains("ASSISTANT: ANSWER-ONE\n[RECORD OF a1b2c3d4]\nASSISTANT: ANSWER-TWO"), shown);
    }

    @Test
    @DisplayName("a later turn is told a file was attached and an answer was private, never what either held")
    void laterTurnNamesNoFile(@TempDir Path tmp) throws Exception {
        var db = db(tmp);
        String session = db.conversations().createSession("u1", "Statements");
        String pdf = db.files().store("u1", "vypis_123456789.pdf", "application/pdf",
                new ByteArrayInputStream("%PDF-1.7 binary".getBytes(StandardCharsets.UTF_8)));
        db.conversations().saveMessage("u1", session, "user", "summarise this statement", List.of(pdf));
        db.conversations().saveMessage("u1", session, "assistant", AgentLoop.PRIVATE_NOTE, List.of(),
                "a1b2c3d4", AgentLoop.PRIVATE_HEADER + "Closing balance SECRET-48213 CZK.");
        db.conversations().saveMessage("u1", session, "assistant", "Done.");
        String asked = db.conversations().saveMessage("u1", session, "user", "and last month's?");

        String summary = contextOf(db, asked);
        assertNotNull(summary);
        assertTrue(summary.contains("[A file was attached here (application/pdf, 15 bytes)"), summary);
        assertFalse(summary.contains("vypis") || summary.contains("123456789"),
                "a statement's file name carries its account number, and this goes to the cloud: "
                        + summary);
        assertTrue(summary.contains("ASSISTANT: " + AgentLoop.PRIVATE_NOTE), summary);
        assertFalse(summary.contains("SECRET"), "the private answer went into the next prompt: " + summary);
        assertTrue(summary.contains("ASSISTANT: Done."), "a row with no private text is read as it was");
    }

    @Test
    @DisplayName("a handle in an earlier message is read in words: in this task it would be one of its own results")
    void noHandleResolvesHere(@TempDir Path tmp) throws Exception {
        var db = db(tmp);
        String session = db.conversations().createSession("u1", "Mail");
        db.conversations().saveMessage("u1", session, "user", "send {{1}} to the team");
        db.conversations().saveMessage("u1", session, "assistant", "Sent {{2.body}} as asked.");
        String asked = db.conversations().saveMessage("u1", session, "user", "and to Jana?");

        String shown = contextOf(db, asked);
        assertTrue(shown.contains("USER: send result 1 to the team"), shown);
        assertTrue(shown.contains("ASSISTANT: Sent result 2.body as asked."), shown);
        assertFalse(ArtifactRef.TOKEN.matcher(shown).find(), "a handle this task would resolve: " + shown);
    }
}
