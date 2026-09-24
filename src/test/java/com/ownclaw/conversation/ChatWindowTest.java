package com.ownclaw.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.llm.LlmMessage;
import com.ownclaw.llm.LlmRequestConfig;
import com.ownclaw.llm.LlmResponse;
import com.ownclaw.llm.OllamaProvider;
import com.ownclaw.llm.OllamaSemaphore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The recent-chat window is bounded by size as well as count; what falls out is summarised locally. */
class ChatWindowTest {

    @Test
    @DisplayName("the window: the newest messages up to 24,000 characters, never fewer than two, never more than ten")
    void keptNewest() {
        assertEquals(4, ConversationCompressor.keptNewest(Collections.nCopies(8, 6_000)));
        assertEquals(2, ConversationCompressor.keptNewest(List.of(30_000, 100, 100)), "the exchange being continued");
        assertEquals(10, ConversationCompressor.keptNewest(Collections.nCopies(15, 100)));
        assertEquals(0, ConversationCompressor.keptNewest(List.of()));
    }

    @Test
    @DisplayName("long messages outside the window are summarised by the local model and marked, not deleted")
    void longMessagesAreFoldedIntoTheSummary(@TempDir Path tmp) throws Exception {
        var jdbc = MigratedDatabase.at(tmp.resolve("t.db"));
        var sent = new ArrayList<String>();
        var local = new OllamaProvider(new OwnClawConfig(), new ObjectMapper(), null) {
            @Override public LlmResponse chat(List<LlmMessage> messages, LlmRequestConfig cfg) {
                sent.add(messages.get(1).content());
                return new LlmResponse("The owner and the assistant discussed three long reports in detail.", 10, 10);
            }
        };
        var compressor = new ConversationCompressor(jdbc, local, new OllamaSemaphore());
        var conversations = new ConversationService(jdbc, null);
        String session = conversations.createSession("u1", "Reports");
        for (int i = 0; i < 5; i++) {
            jdbc.update("INSERT INTO conversations (id, user_id, session_id, role, content) VALUES (?, 'u1', ?, ?, ?)",
                    "m" + i, session, i % 2 == 0 ? "user" : "assistant", "REPORT" + i + " " + "y".repeat(10_000));
        }

        compressor.compressIfNeeded("u1", session);

        var compressed = jdbc.queryForList("SELECT id FROM conversations WHERE compressed = 1 ORDER BY id", String.class);
        assertEquals(List.of("m0", "m1", "m2"), compressed, "the two newest stay; three are folded away");
        assertEquals(1, sent.size());
        assertTrue(sent.get(0).contains("REPORT0") && !sent.get(0).contains("REPORT4"));
        assertNotNull(compressor.getSessionSummary("u1", session));
        assertEquals(5, jdbc.queryForObject("SELECT COUNT(*) FROM conversations", Integer.class), "marked, not deleted");
    }
}
