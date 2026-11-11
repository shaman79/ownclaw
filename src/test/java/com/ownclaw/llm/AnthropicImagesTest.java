package com.ownclaw.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownclaw.config.OwnClawConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A pasted image in Anthropic's request: an image block right before the text of the message it was
 * pasted with -- in an earlier chat message and in the task -- among that message's blocks, so the
 * cache marks are where they were and no more than four; the same bytes on the chat's next turn;
 * and no block for an image the gateway attached no bytes to.
 */
class AnthropicImagesTest {

    static final String MARK = LlmMessage.CACHE_BOUNDARY;
    private final AnthropicProvider provider = new AnthropicProvider(new OwnClawConfig(), new ObjectMapper());

    private JsonNode body(List<LlmMessage> messages) {
        return provider.requestBody(messages, new LlmRequestConfig(null, null, false,
                List.of(new ToolSpec("respond", "answer", Map.of("type", "object")))), "claude-opus-5-5", 128_000);
    }

    /** An image as the gateway hands it over: bytes it read. */
    static LlmMessage.Image sent(String fileId, String bytes) {
        byte[] data = bytes.getBytes(StandardCharsets.UTF_8);
        return new LlmMessage.Image(fileId, 640, 480, data.length,
                new LlmMessage.Sent("image/png", data, CloudGateway.sha256(data).substring(0, 16)));
    }

    /** A chat of these messages, each with the images before it, then the task with its own. */
    static String chatThenTask(List<String> chat, List<Integer> imagesBefore, String task, int taskImages, int first) {
        var sb = new StringBuilder("## Prior Context\n### The conversation so far\n");
        int k = 0;
        for (int i = 0; i < chat.size(); i++) {
            if (i > 0) sb.append(LlmMessage.HISTORY_BLOCK);
            for (int n = 0; n < imagesBefore.get(i); n++) sb.append(LlmMessage.image(k++));
            sb.append(chat.get(i));
        }
        sb.append(LlmMessage.HISTORY_END).append("\n## Task\n");
        for (int n = 0; n < taskImages; n++) sb.append(LlmMessage.image(first + n));
        return sb.append(task).toString();
    }

    private static List<JsonNode> marks(JsonNode body) {
        var marks = new ArrayList<JsonNode>();
        for (JsonNode t : body.path("tools")) if (t.has("cache_control")) marks.add(t.path("cache_control"));
        for (JsonNode b : body.path("system")) if (b.has("cache_control")) marks.add(b.path("cache_control"));
        for (JsonNode m : body.path("messages")) {
            for (JsonNode b : m.path("content")) if (b.has("cache_control")) marks.add(b.path("cache_control"));
        }
        return marks;
    }

    /** The first message's blocks, each as its type and its text or its image's data. */
    private static List<String> blocks(JsonNode body) {
        var out = new ArrayList<String>();
        for (JsonNode b : body.path("messages").get(0).path("content")) {
            out.add("image".equals(b.path("type").asText())
                    ? "IMAGE " + b.path("source").path("media_type").asText() + " "
                            + new String(java.util.Base64.getDecoder().decode(b.path("source").path("data").asText()),
                                    StandardCharsets.UTF_8)
                    : b.path("text").asText());
        }
        return out;
    }

    @Test
    @DisplayName("each image is a block right before its message's text -- an earlier chat message's and the task's -- and the marks are where they were")
    void imagesBeforeTheirText() {
        var chat = List.of("USER: what is wrong here?\n[An image was pasted with this message: image/png, 640x480 px.]\n",
                "ASSISTANT: The cable is unplugged.\n", "USER: and now?\n");
        String content = chatThenTask(chat, List.of(1, 0, 0), "this one?", 1, 1) + MARK + "\n\n---\n## Environment";
        var withImages = body(List.of(LlmMessage.system("SYSTEM"),
                LlmMessage.marked(content, List.of(sent("f-old", "OLD-PIXELS"), sent("f-new", "NEW-PIXELS"))),
                LlmMessage.assistant("action 1"), LlmMessage.user("result 1\n\n---\n## Environment")));
        List<String> b = blocks(withImages);
        assertEquals(List.of("## Prior Context\n### The conversation so far\n", "IMAGE image/png OLD-PIXELS",
                chat.get(0), chat.get(1), chat.get(2), "\n## Task\n", "IMAGE image/png NEW-PIXELS", "this one?",
                "\n\n---\n## Environment"), b, "each image before the text it was pasted with");
        JsonNode content0 = withImages.path("messages").get(0).path("content");
        assertTrue(content0.get(4).has("cache_control"), "the chat's mark on its last block, as before");
        assertTrue(content0.get(7).has("cache_control"), "the task's mark on its last block");
        assertTrue(content0.get(1).path("cache_control").isMissingNode(), "no mark of an image's own");
        assertTrue(content0.get(6).path("cache_control").isMissingNode());
        assertEquals(4, marks(withImages).size(), "system, chat, task, sliding: " + marks(withImages));

        // The same request with no image has its marks in the same places.
        var without = body(List.of(LlmMessage.system("SYSTEM"),
                LlmMessage.marked(chatThenTask(chat, List.of(0, 0, 0), "this one?", 0, 0) + MARK + "\n\n---\n## Environment"),
                LlmMessage.assistant("action 1"), LlmMessage.user("result 1\n\n---\n## Environment")));
        assertEquals(marks(without).toString(), marks(withImages).toString());
        assertFalse(withImages.toString().contains("\\u0001"), "no marker is sent: " + withImages);
        assertEquals("base64", content0.get(1).path("source").path("type").asText());
        assertFalse(content0.get(1).path("source").path("data").asText().contains("\n"), "base64 with no line breaks");
        // Mutation: put the image after the text, or a cache mark on it -> the order or the count differs.
    }

    @Test
    @DisplayName("the chat's next turn sends the same blocks for the same chat, images included, a turn apart")
    void theNextTurnIsTheSameBytes() {
        var chat = new ArrayList<>(List.of("USER: look\n[An image was pasted with this message: image/png, 640x480 px.]\n",
                "ASSISTANT: A router.\n"));
        var turn1 = body(List.of(LlmMessage.system("SYSTEM"), LlmMessage.marked(
                chatThenTask(chat, List.of(1, 0), "and this?", 1, 1) + MARK + "\n\n---\n## Environment",
                List.of(sent("f1", "FIRST"), sent("f2", "SECOND")))));
        chat.add("USER: and this?\n[An image was pasted with this message: image/png, 640x480 px.]\n");
        chat.add("ASSISTANT: A switch.\n");
        var turn2 = body(List.of(LlmMessage.system("SYSTEM"), LlmMessage.marked(
                chatThenTask(chat, List.of(1, 0, 1, 0), "thanks", 0, 2) + MARK + "\n\n---\n## Environment",
                List.of(sent("f1", "FIRST"), sent("f2", "SECOND")))));
        JsonNode one = turn1.path("messages").get(0).path("content");
        JsonNode two = turn2.path("messages").get(0).path("content");
        for (int i = 0; i < 4; i++) {
            assertEquals(one.get(i).path("type"), two.get(i).path("type"));
            assertEquals(one.get(i).path("text"), two.get(i).path("text"), "block " + i);
            assertEquals(one.get(i).path("source"), two.get(i).path("source"), "block " + i + ": the image, the same bytes");
        }
        assertEquals("IMAGE image/png SECOND", blocks(turn2).get(4), "the last turn's own image, before its message now");
        // Mutation: number the images per request differently, or send them elsewhere -> a block moves.
    }

    @Test
    @DisplayName("an image the gateway attached no bytes to is no image block: a line says it was not sent, the text stays")
    void noBytesNoBlock() {
        String content = chatThenTask(List.of("USER: look\n"), List.of(1), "go", 0, 1) + MARK;
        var body = body(List.of(LlmMessage.marked(content, List.of(new LlmMessage.Image("f1", 640, 480, 10)))));
        assertEquals(List.of("## Prior Context\n### The conversation so far\n", AnthropicProvider.IMAGE_NOT_SENT,
                "USER: look\n", "\n## Task\ngo"), blocks(body));
        assertFalse(body.toString().contains("\"image\""), body.toString());
        // And a message that is not the engine's marked one never places one, whatever it carries.
        var unmarked = body(List.of(new LlmMessage(LlmMessage.Role.USER, content, false, false,
                List.of(sent("f1", "PIXELS")))));
        assertFalse(unmarked.toString().contains("\"image\""), unmarked.toString());
        // Mutation: render images whose bytes are null, or on any message -> a block of nothing, or a forged one.
    }
}
