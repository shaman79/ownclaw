package com.ownclaw.agent;

import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.conversation.ConversationCompressor;
import com.ownclaw.conversation.ConversationService;
import com.ownclaw.conversation.FileStorageService;
import com.ownclaw.conversation.MigratedDatabase;
import com.ownclaw.llm.LlmMessage;
import com.ownclaw.llm.LlmProvider;
import com.ownclaw.llm.LlmRequestConfig;
import com.ownclaw.llm.LlmResponse;
import com.ownclaw.observability.ChatStatusEmitter;
import com.ownclaw.observability.EventLogService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** A file is read only by the local model, so without it a task holding one stops before the cloud. */
class StopWithoutLocalModelTest {

    private static AgentContext fileTask() {
        var ctx = new AgentContext("u1", "t1", "summarise this statement");
        ctx.addFile("f1", "", List.of("uploaded file", "application/pdf, 84211 bytes, not text or too large"));
        return ctx;
    }

    @Test
    @DisplayName("a file with the local model down ends the task and says why")
    void aFileWithTheLocalModelDownStops() {
        AgentResult r = AgentLoop.stopWithoutLocalModel(fileTask(), () -> false);
        assertNotNull(r);
        assertEquals(AgentResult.TerminationReason.ERROR, r.terminationReason());
        assertEquals(AgentLoop.LOCAL_DOWN_FOR_FILES, r.response());
    }

    @Test
    @DisplayName("a file with the local model up goes on")
    void aFileWithTheLocalModelUpGoesOn() {
        assertNull(AgentLoop.stopWithoutLocalModel(fileTask(), () -> true));
    }

    @Test
    @DisplayName("a task with no file goes on, and the local model is not even asked")
    void noFileNoProbe() {
        int[] probes = {0};
        assertNull(AgentLoop.stopWithoutLocalModel(new AgentContext("u1", "t1", "hello"),
                () -> { probes[0]++; return false; }));
        assertEquals(0, probes[0]);
    }

    /** A local model that is configured but not answering, and fails the test if it is asked. */
    static final class Down implements LlmProvider {
        public LlmResponse chat(List<LlmMessage> m, LlmRequestConfig c) {
            throw new AssertionError("a local model that is down was asked to answer");
        }
        public boolean isAvailable() { return false; }
        public String name() { return "down"; }
    }

    @Test
    @DisplayName("executeFull stops a task holding a file when the local model is down or missing")
    void executeFullStopsBeforeTheCloud(@TempDir Path tmp) throws Exception {
        // The real entry point, so what is pinned is the probe executeFull actually passes, not
        // only the helper: a probe that answers "up" whatever the model says, or that takes a
        // missing local model for a working one, would send a task the cloud cannot help with
        // into the loop. Nothing after the stop is wired here -- no thinking engine, no memory,
        // no vault -- so a task that goes on fails this test before it could reach any cloud.
        var jdbc = MigratedDatabase.at(tmp.resolve("t.db"));
        var config = new OwnClawConfig();
        config.getDatabase().setPath(tmp.resolve("t.db").toString());
        Files.createDirectories(tmp.resolve("uploads"));
        var files = new FileStorageService(jdbc, config);
        var conversations = new ConversationService(jdbc, new ConversationCompressor(jdbc, null, null));
        String pdf = files.store("u1", "statement.pdf", "application/pdf",
                new ByteArrayInputStream("%PDF-1.7 binary".getBytes(StandardCharsets.UTF_8)));

        for (LlmProvider local : new LlmProvider[]{new Down(), null}) {
            var loop = new AgentLoop(null, null, null, new ChatStatusEmitter(), config,
                    new LlmRouter(local, null, config, null), null, null, null, null, null, null,
                    conversations, null, null, null, new EventLogService(jdbc), null, null, files);
            String which = local == null ? "no local model" : "a local model that is down";
            AgentResult r = assertDoesNotThrow(
                    () -> loop.executeFull("u1", "summarise this statement", false, null, List.of(pdf)),
                    which + ": the task went on past the stop");
            assertEquals(AgentResult.TerminationReason.ERROR, r.terminationReason(), which);
            assertEquals(AgentLoop.LOCAL_DOWN_FOR_FILES, r.response(), which);
            assertNotNull(r.taskId(), which + ": stamped, so the chat can link to what happened");
        }
    }
}
