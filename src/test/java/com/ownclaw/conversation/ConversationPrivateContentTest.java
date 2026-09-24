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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A private answer is stored beside its safe text, and read back by the owner's chat alone.
 * Everything that builds a prompt -- the recent messages, the compressor's summary -- reads
 * the safe text, on the real schema.
 */
class ConversationPrivateContentTest {

    static final String NOTE = "[Private answer: sent to you only, never to the cloud model.]";
    static final String SECRET = "Closing balance 48,213.07 CZK; rent 12,500.00 CZK on the 1st.";

    @Test
    @DisplayName("the chat reload shows the private answer; the prompt's history shows the note")
    void onlyTheReloadReadsThePrivateText(@TempDir Path tmp) throws Exception {
        var jdbc = MigratedDatabase.at(tmp.resolve("t.db"));
        var conversations = new ConversationService(jdbc, new ConversationCompressor(jdbc, null, null));
        String session = conversations.createSession("u1", "Statements");

        conversations.saveMessage("u1", session, "assistant", NOTE, List.of(), "a1b2c3d4", SECRET);
        conversations.saveMessage("u1", session, "assistant", "An ordinary answer.", List.of(), "b2c3d4e5");

        var recent = conversations.getRecentMessages("u1", session, 10).stream()
                .map(m -> String.valueOf(m.get("content"))).toList();
        assertTrue(recent.contains(NOTE), recent.toString());
        assertTrue(recent.stream().noneMatch(c -> c.contains("48,213.07")),
                "the recent messages go into every later prompt: " + recent);

        var reload = conversations.getSessionMessages("u1", session).stream()
                .map(m -> String.valueOf(m.get("content"))).toList();
        assertTrue(reload.contains(SECRET), "the owner's chat shows what it showed live: " + reload);
        assertFalse(reload.contains(NOTE));
        assertTrue(reload.contains("An ordinary answer."), "a row with no private text shows its content");
    }

    @Test
    @DisplayName("the compressor summarises the note, never the private answer")
    void theCompressorIsNeverSentThePrivateText(@TempDir Path tmp) throws Exception {
        var jdbc = MigratedDatabase.at(tmp.resolve("t.db"));
        var sent = new CopyOnWriteArrayList<String>();
        var local = new OllamaProvider(new OwnClawConfig(), new ObjectMapper(), null) {
            @Override
            public LlmResponse chat(List<LlmMessage> messages, LlmRequestConfig config) {
                messages.forEach(m -> sent.add(m.content()));
                return new LlmResponse("The owner asked about a statement and was answered privately.", 1, 1);
            }
        };
        var compressor = new ConversationCompressor(jdbc, local, new OllamaSemaphore());
        // Saved with no compressor of its own, so the only compression is the one run below and
        // the test does not race the background thread.
        var conversations = new ConversationService(jdbc, null);
        String session = conversations.createSession("u1", "Statements");

        conversations.saveMessage("u1", session, "user", "summarise this statement");
        conversations.saveMessage("u1", session, "assistant", NOTE, List.of(), "a1b2c3d4", SECRET);
        // An hour older than the rest: rows saved in one second tie on timestamp, and the
        // compressor takes the oldest.
        jdbc.update("UPDATE conversations SET timestamp = datetime('now', '-1 hour')");
        for (int i = 0; i < 16; i++) {
            conversations.saveMessage("u1", session, i % 2 == 0 ? "user" : "assistant", "later message " + i);
        }
        compressor.compressIfNeeded("u1", session);

        assertFalse(sent.isEmpty(), "past the threshold, so the compressor ran");
        String all = String.join("\n", sent);
        assertTrue(all.contains(NOTE), "the oldest rows are the ones summarised: " + all);
        assertFalse(all.contains("48,213.07"), "the summary feeds every later prompt: " + all);
    }
}
