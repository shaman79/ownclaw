package com.ownclaw.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownclaw.config.OwnClawConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The providers that are sent no image: the local model, which cannot see one, and OpenAI. The
 * engine writes them no marked message; one sent there all the same goes with no marker -- none of
 * its control characters -- and in each image's place a line that says why it is not shown, never
 * the bytes, whatever the message carries.
 */
class ImagesNotSentTest {

    static final ObjectMapper JSON = new ObjectMapper();

    /** A marked message holding an image the gateway would have attached bytes to. */
    static LlmMessage withImage() {
        byte[] bytes = "PIXELS".getBytes(StandardCharsets.UTF_8);
        return LlmMessage.marked("## Prior Context\n### The conversation so far\nUSER: a\n" + LlmMessage.HISTORY_END
                        + "\n## Task\n" + LlmMessage.image(0) + "what is this?" + LlmMessage.CACHE_BOUNDARY + "\n---\nstep",
                List.of(new LlmMessage.Image("f1", 640, 480, bytes.length,
                        new LlmMessage.Sent("image/png", bytes, "0123456789abcdef"))));
    }

    @Test
    @DisplayName("the local model: no marker, no image bytes, a line saying it cannot see images where the image was")
    void ollama() throws Exception {
        var http = OllamaStreamingTest.ollama(OllamaStreamingTest.line("ok", null) + OllamaStreamingTest.last("stop", 20, 1));
        OllamaStreamingTest.provider(OllamaStreamingTest.config(), http)
                .chat(List.of(LlmMessage.system("S"), withImage()), new LlmRequestConfig(null, null, false));
        String body = http.to(OllamaStreamingTest.CHAT).get(0).body();
        JsonNode sent = JSON.readTree(body);
        String content = sent.path("messages").get(1).path("content").asText();
        assertEquals("## Prior Context\n### The conversation so far\nUSER: a\n\n## Task\n\n[An image was pasted here. It "
                + "is not shown to you: the local model cannot see images.]\nwhat is this?\n---\nstep", content);
        assertFalse(body.contains("\\u0001") || body.contains("\\u001f"), body);
        assertFalse(body.contains(java.util.Base64.getEncoder().encodeToString("PIXELS".getBytes(StandardCharsets.UTF_8))),
                "no bytes of it");
        assertFalse(sent.path("messages").get(1).has("images"), "Ollama's own image field is never set");
        // Mutation: send the content as it came -> its markers reach the local model.
    }

    @Test
    @DisplayName("OpenAI: the same, saying it is sent no images")
    void openAi() {
        var body = new OpenAiProvider(new OwnClawConfig(), JSON).requestBody(List.of(LlmMessage.system("S"), withImage()),
                new LlmRequestConfig(null, null, false), "gpt-5");
        String content = body.path("messages").get(1).path("content").asText();
        assertTrue(content.contains("[An image was pasted here. It is not shown to you: images are not sent to this model.]"),
                content);
        assertFalse(body.toString().contains("\\u0001"), body.toString());
        assertFalse(body.toString().contains("image_url"));
    }

    @Test
    @DisplayName("as the model reads it and as a page shows it: no marker; the debug view says which image stood where")
    void readAndShown() {
        LlmMessage m = withImage();
        assertFalse(LlmMessage.asRead(m.content()).contains("\u0001"));
        assertFalse(LlmMessage.asRead(m.content()).contains("\u001f"));
        String shown = LlmMessage.asShown(m);
        assertTrue(shown.contains("[image 0: the pasted image f1, 640x480 px, 6 bytes, sent as image/png]"), shown);
        assertFalse(shown.contains("\u0001"), shown);
    }
}
