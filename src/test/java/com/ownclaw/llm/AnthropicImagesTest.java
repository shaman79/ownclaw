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

    @Test
    @DisplayName("a turn that added more blocks than the API looks back over: on its first step the free fourth mark goes where the last turn's chat ended")
    void theLookbackMark() {
        // The last turn: "hello", "Hi." -- its chat mark on "Hi.". This turn adds the message the last
        // one asked, with eighteen images, and its answer: twenty blocks past that mark.
        var images = new ArrayList<LlmMessage.Image>();
        for (int i = 0; i < 18; i++) images.add(sent("f" + i, "PIXELS-" + i));
        var chat = List.of("USER: hello\n", "ASSISTANT: Hi.\n", "USER: what are these?\n", "ASSISTANT: Screens.\n");
        String content = chatThenTask(chat, List.of(0, 0, 18, 0), "thanks", 0, 18) + MARK + "\n\n---\n## Environment";
        var first = body(List.of(LlmMessage.system("SYSTEM"), LlmMessage.marked(content, images)));
        JsonNode blocks = first.path("messages").get(0).path("content");
        assertTrue(blocks.get(1).has("cache_control"), "where the last turn's chat ended: " + blocks.get(1));
        assertEquals("ASSISTANT: Hi.\n", blocks.get(1).path("text").asText());
        assertEquals(4, marks(first).size(), "system, the last turn's end, the chat's end, the task");
        // On a later step the sliding mark has the fourth: no extra one, and still at most four.
        var later = body(List.of(LlmMessage.system("SYSTEM"), LlmMessage.marked(content.substring(0, content.lastIndexOf(MARK) + MARK.length()), images),
                LlmMessage.assistant("action 1"), LlmMessage.user("result 1\n\n---\n## Environment")));
        assertTrue(later.path("messages").get(0).path("content").get(1).path("cache_control").isMissingNode());
        assertEquals(4, marks(later).size(), marks(later).toString());
        // A turn that added few blocks needs none: the chat's own mark finds the last turn's.
        var few = body(List.of(LlmMessage.system("SYSTEM"), LlmMessage.marked(chatThenTask(chat, List.of(0, 0, 1, 0), "thanks", 0, 1)
                + MARK, List.of(sent("f0", "PIXELS")))));
        assertEquals(3, marks(few).size(), marks(few).toString());
        // Mutation: never set it -> 18 images in one message write the whole chat to the cache again next turn.
    }

    @Test
    @DisplayName("a marked message the filter took the boundary from goes as text: each image's place says it was not sent, no marker")
    void noBoundaryStillSaysSo() {
        String content = "## Task\n" + LlmMessage.image(0) + "what is this?";
        var body = body(List.of(LlmMessage.system("SYSTEM"), LlmMessage.marked(content, List.of(sent("f1", "PIXELS")))));
        String text = body.path("messages").get(0).path("content").asText();
        assertTrue(text.contains(AnthropicProvider.IMAGE_NOT_SENT), text);
        assertFalse(text.contains("\u0001") || text.contains("\u001f"), text);
        assertFalse(body.toString().contains("\"image\""), "no image block in a message of plain text");
        // Mutation: send the plain text without the line -> the task says an image is shown that is not.
    }

    @Test
    @DisplayName("the request leaves as UTF-8 written straight from the tree -- Czech text and an image the same bytes the tree says")
    void theBodyIsWrittenAsBytes() throws Exception {
        var http = AnthropicStreamingTest.api(AnthropicStreamingTest.start("claude-opus-5", 10, 0, 0)
                + AnthropicStreamingTest.text(0, "ok") + AnthropicStreamingTest.end("end_turn", null, 1));
        var provider = AnthropicStreamingTest.provider(http);
        String content = "## Task\n" + LlmMessage.image(0) + "co je na obrázku? Příliš žluťoučký kůň" + MARK + "step";
        var messages = List.of(LlmMessage.system("SYSTEM"), LlmMessage.marked(content, List.of(sent("f1", "PIXELS"))));
        provider.chat(messages, new LlmRequestConfig(null, null, false));
        String sent = http.to(AnthropicStreamingTest.MESSAGES).get(0).body();
        JsonNode tree = new ObjectMapper().readTree(sent);
        assertEquals(provider.requestBody(messages, new LlmRequestConfig(null, null, false), "claude-opus-5", 128_000),
                tree, "the tree, whole");
        assertTrue(sent.contains("Příliš žluťoučký kůň"), "UTF-8, not escaped and not mangled");
        assertEquals(1_000_000, provider.contextWindow(null), "the window, now that the Models API was asked");
    }

    @Test
    @DisplayName("a request at the image budget is written once, as bytes: the heap it takes is about twice its size -- through a String it took eleven times")
    void theBodyIsWrittenOnce() throws Exception {
        // Ten images of 9.6 MB as base64 -- the 96 MB one request may carry -- and a line of Czech,
        // which made a String of the body UTF-16: two bytes a character.
        var images = new ArrayList<LlmMessage.Image>();
        var content = new StringBuilder("## Task\n");
        for (int k = 0; k < 10; k++) {
            byte[] data = new byte[7_200_000];
            java.util.Arrays.fill(data, (byte) k);
            images.add(new LlmMessage.Image("f" + k, 2576, 1932, data.length,
                    new LlmMessage.Sent("image/jpeg", data, CloudGateway.sha256(data).substring(0, 16))));
            content.append(LlmMessage.image(k));
        }
        content.append("co je na obrázcích? Příliš žluťoučký kůň").append(MARK).append("step");
        var body = (com.fasterxml.jackson.databind.node.ObjectNode) body(List.of(LlmMessage.system("SYSTEM"),
                LlmMessage.marked(content.toString(), images)));
        byte[][] out = new byte[1][];
        long peak = heapPeak(() -> out[0] = provider.utf8(body));
        long size = out[0].length;
        assertTrue(size > 96_000_000L, "the budget's worth: " + size);
        assertTrue(new String(out[0], StandardCharsets.UTF_8).contains("Příliš žluťoučký kůň"));
        assertTrue(peak < 3 * size, (peak >> 20) + " MB of heap to write " + (size >> 20) + " MB");
        // Measured: 187 MB to write 91 MB. Mutation: a String first, then its bytes -> 1,017 MB.
    }

    /** The most of the heap {@code run} takes above what was in use when it started, in bytes. */
    static long heapPeak(Runnable run) throws InterruptedException {
        var memory = java.lang.management.ManagementFactory.getMemoryMXBean();
        System.gc();
        long base = memory.getHeapMemoryUsage().getUsed();
        var peak = new java.util.concurrent.atomic.AtomicLong(base);
        var sampler = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                peak.accumulateAndGet(memory.getHeapMemoryUsage().getUsed(), Math::max);
                try {
                    Thread.sleep(2);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
        sampler.setDaemon(true);
        sampler.start();
        try {
            run.run();
        } finally {
            sampler.interrupt();
            sampler.join();
        }
        return peak.get() - base;
    }

    @Test
    @DisplayName("the window is asked of the Models API before a request is shaped, when this process does not know it yet")
    void theWindowBeforeTheFirstRequest() {
        var http = new FakeHttp().json(AnthropicStreamingTest.MODELS, 200, AnthropicStreamingTest.LIMITS);
        var provider = AnthropicStreamingTest.provider(http);
        assertNull(provider.knownContextWindow(), "not known before it is asked");
        assertEquals(1_000_000, provider.contextWindow(LlmProgress.NONE));
        assertEquals(600, LlmMessage.imagesPerRequest(provider.contextWindow(LlmProgress.NONE)), "a 1M window: 600 images");
        assertEquals(1, http.to(AnthropicStreamingTest.MODELS).size(), "asked once, then known");
        var noKey = new AnthropicProvider(new OwnClawConfig(), new ObjectMapper());
        assertNull(noKey.contextWindow(LlmProgress.NONE), "with no key nothing is asked");
    }
}
