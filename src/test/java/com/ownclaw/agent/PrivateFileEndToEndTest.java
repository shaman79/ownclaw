package com.ownclaw.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownclaw.agent.DelegationBehaviourTest.Scripted;
import com.ownclaw.agent.DelegationBehaviourTest.Usage;
import com.ownclaw.agent.tools.Tool;
import com.ownclaw.agent.tools.ToolExecutionContext;
import com.ownclaw.agent.tools.ToolParam;
import com.ownclaw.agent.tools.ToolResult;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.conversation.ConversationService;
import com.ownclaw.conversation.FileStorageService;
import com.ownclaw.conversation.MigratedDatabase;
import com.ownclaw.core.TaskQueue;
import com.ownclaw.observability.EventLogService;
import com.ownclaw.observability.OpsService;
import com.ownclaw.users.AuthService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.ownclaw.agent.DelegationBehaviourTest.call;
import static com.ownclaw.agent.DelegationBehaviourTest.done;
import static com.ownclaw.agent.DelegationBehaviourTest.plan;
import static com.ownclaw.agent.DelegationBehaviourTest.windowOf;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A statement sent in chat, from upload to the next turn, through the real pieces: the file on
 * disk and in the database, registration, the local model's delegation, the answer, the saved
 * row, the next turn's history, the episode memory keeps, and what the ops API answers about it
 * -- read by sessions whose model runs in the cloud.
 * <p>
 * One property over every string that goes to the cloud, rather than one check per guard: a
 * guard that each parse of the same data enforces separately leaks through the others' gaps,
 * and only the whole path shows it.
 */
class PrivateFileEndToEndTest {

    static final String NAME = "vypis_123456789.csv";
    static final String STATEMENT = "date,amount,counterparty\n" + DelegationBehaviourTest.statement(3_000)
            + "\n2026-09-30,closing,balance 48213.07 KV-7f3a9c21\n";
    /**
     * The local model's answer, quoting the statement's last line: so it stays private. One that
     * quotes none of the file goes to the cloud (DelegationBehaviourTest).
     */
    static final String SUMMARY = "Your closing balance was 48,213.07 CZK; the statement ends with "
            + "2026-09-30,closing,balance 48213.07 KV-7f3a9c21 -- card spending ran through the whole month.";

    /** A skill that reads the files it is handed, as a generated one does. */
    static final class ReadsFiles implements Tool {
        final FileStorageService files;
        ReadsFiles(FileStorageService files) { this.files = files; }
        public String name() { return "read_statement"; }
        public String description() { return "reads the attached statement"; }
        public Map<String, ToolParam> inputSchema() { return Map.of(); }
        public boolean hasSideEffects() { return false; }
        public List<String> requiredCredentials() { return List.of(); }
        public ToolResult execute(Map<String, Object> p, ToolExecutionContext c) {
            return ToolResult.success(c.attachmentIds().stream().map(files::readAsText)
                    .collect(Collectors.joining("\n")));
        }
    }

    @Test
    @DisplayName("no string bound for the cloud carries the statement, the answer or the file's name")
    void nothingFromTheFileReachesTheCloud(@TempDir Path tmp) throws Exception {
        var jdbc = MigratedDatabase.at(tmp.resolve("t.db"));
        var config = new OwnClawConfig();
        config.getDatabase().setPath(tmp.resolve("t.db").toString());
        Files.createDirectories(tmp.resolve("uploads"));
        var files = new FileStorageService(jdbc, config);
        var conversations = new ConversationService(jdbc);
        String session = conversations.createSession("u1", "Statements");

        // The upload and the message it came with, as the chat saves them.
        String fileId = files.store("u1", NAME, "text/csv",
                new ByteArrayInputStream(STATEMENT.getBytes(StandardCharsets.UTF_8)));
        String row = conversations.saveMessage("u1", session, "user", "summarise this statement",
                List.of(fileId));

        var ctx = new AgentContext("u1", "a1b2c3d4", "summarise this statement");
        AgentLoop.loadConversationContext(ctx, "u1", row, conversations, files, id -> null);
        AgentLoop.registerAttachments(ctx, List.of(fileId), files, new EventLogService(jdbc));

        // What the cloud is told of the file, and what the delegation it asks for reports back.
        String filesSection = ThinkingEngine.filesSection(ctx);
        var llm = new Scripted(call("read_statement", Map.of()), done(SUMMARY));
        var outcome = DelegationBehaviourTest.executor(llm, new Usage(), new ReadsFiles(files))
                .execute(plan("summarise the attached statement"), ctx, DelegationBehaviourTest.UNCOUNTED);
        assertTrue(llm.allSeen().contains("KV-7f3a9c21"), "the local model read the file");

        // The cloud answers with the handle it was given, and the answer is made on this machine.
        assertEquals("local_answer", ctx.artifacts().get(2).tool());
        var answer = AgentLoop.answerFor("{{3}}", ctx);
        assertNull(answer.refusal());
        var result = AgentResult.completed(answer.response(), ctx.trajectory(), 1)
                .withOwnerText(answer.ownerText()).withTaskId(ctx.taskId());
        assertTrue(result.ownerText().contains(SUMMARY), "the owner gets the answer: " + result.ownerText());

        // Saved as the chat saves it; the reload shows the owner the answer.
        conversations.saveMessage("u1", session, "assistant", result.response(), List.of(),
                result.taskId(), result.ownerText());
        assertTrue(conversations.getSessionMessages("u1", session).stream()
                .anyMatch(m -> String.valueOf(m.get("content")).contains(SUMMARY)));

        // The next turn, and the episode memory recalls into later prompts.
        String nextRow = conversations.saveMessage("u1", session, "user", "and last month's?");
        var next = new AgentContext("u1", "b2c3d4e5", "and last month's?");
        AgentLoop.loadConversationContext(next, "u1", nextRow, conversations, files, id -> null);
        String episode = AgentLoop.episodeSummary(ctx.originalMessage(), result);

        jdbc.update("INSERT INTO users (id, display_name) VALUES ('u1', 'someone')");
        var ops = new OpsService(config, jdbc, null, null, new TaskQueue(null, null, null, config, null),
                new AuthService(jdbc, null, config), null, new ObjectMapper(), null);
        var page = new OpsService.Page(0, 500);

        var cloudBound = new LinkedHashMap<String, String>();
        cloudBound.put("files section", filesSection);
        cloudBound.put("delegation report", outcome.text());
        cloudBound.put("response", result.response());
        cloudBound.put("next turn's history", next.conversationSummary());
        cloudBound.put("episode", episode);
        cloudBound.put("ops forensics", String.valueOf(ops.forensics("u1", page)));
        cloudBound.put("ops tasks", String.valueOf(ops.tasks(page)));
        cloudBound.put("ops task", String.valueOf(ops.task(ctx.taskId())));
        cloudBound.put("ops query of events", String.valueOf(ops.query("SELECT * FROM events", page)));
        for (var e : cloudBound.entrySet()) {
            String text = e.getValue();
            assertNotNull(text, e.getKey());
            assertNull(windowOf(STATEMENT, text), e.getKey() + " carries the statement: " + text);
            assertNull(windowOf(SUMMARY, text), e.getKey() + " carries the answer: " + text);
            assertFalse(text.contains("vypis") || text.contains("123456789"),
                    e.getKey() + " names the file: " + text);
            assertNull(ctx.egress("test").index().firstLeakIn(text, (h, w) -> false), e.getKey() + " trips the canary: " + text);
        }
        assertTrue(next.conversationSummary().contains(AgentLoop.PRIVATE_NOTE),
                "the next turn is told an answer was given privately");
        assertTrue(cloudBound.get("ops task").contains(fileId), "ops sees the task's file, by its id");
        // Mutation: the name back in the attachment's events row -> forensics, tasks, the task
        // and a query of events all name the file.
    }

    @Test
    @DisplayName("an answer that quotes nothing of the file reaches the owner though the cloud, which read it, does not give it")
    void aReleasedAnswerReachesTheOwner(@TempDir Path tmp) throws Exception {
        var jdbc = MigratedDatabase.at(tmp.resolve("t.db"));
        var config = new OwnClawConfig();
        config.getDatabase().setPath(tmp.resolve("t.db").toString());
        Files.createDirectories(tmp.resolve("uploads"));
        var files = new FileStorageService(jdbc, config);
        String fileId = files.store("u1", NAME, "text/csv",
                new ByteArrayInputStream(STATEMENT.getBytes(StandardCharsets.UTF_8)));
        var ctx = new AgentContext("u1", "a1b2c3d4", "summarise this statement");
        AgentLoop.registerAttachments(ctx, List.of(fileId), files, new EventLogService(jdbc));
        String plain = "Your closing balance was 48,213.07 CZK, and card spending ran through the whole month.";

        var outcome = DelegationBehaviourTest.executor(new Scripted(call("read_statement", Map.of()), done(plain)),
                new Usage(), new ReadsFiles(files))
                .execute(plan("summarise the attached statement"), ctx, DelegationBehaviourTest.UNCOUNTED);
        assertTrue(outcome.text().startsWith(plain), "it quotes nothing of the file, so the cloud reads it: "
                + outcome.text());

        // The cloud answers in words of its own.
        var answer = AgentLoop.answerFor("Done -- the summary is above.", ctx);
        assertNull(answer.refusal());
        assertEquals("Done -- the summary is above.\n\n" + AgentLoop.LOCAL_HEADER + plain, answer.response(),
                "the owner is given the local model's answer, and the history holds what the cloud read");
        assertNull(answer.ownerText(), "nothing private: the owner reads the response");
        // Mutation: keep only an answer that quotes the file -> the owner reads "Done -- the summary is above."
    }
}
