package com.ownclaw.agent;

import com.ownclaw.conversation.FileStorageService.PastedImage;
import com.ownclaw.llm.LlmMessage;
import com.ownclaw.privacy.Redactor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Images and the room a request has: an image counts towards the chat message it was pasted with as
 * the model reads it, and a message folded to a line drops its image with it; past what the API
 * takes in one request -- images, or bytes of them -- the chat's oldest are shown by their lines
 * alone, never this message's; and the paths that see no image say so.
 */
class ImageFitTest {

    static PastedImage image(String id, int width, int height, long bytes) {
        return new PastedImage(id, "image/png", "image/png", width, height, bytes, width, height);
    }

    /** A chat of {@code n} short messages, an image pasted with every message in {@code withImages}. */
    static AgentContext chat(int n, List<Integer> withImages) {
        var ctx = new AgentContext("u1", "t1", "and now?");
        var messages = new ArrayList<String>();
        var images = new java.util.HashMap<Integer, List<PastedImage>>();
        for (int i = 0; i < n; i++) {
            messages.add((i % 2 == 0 ? "USER: question " : "ASSISTANT: answer ") + i + "\n");
            if (withImages.contains(i)) images.put(i, List.of(image("f" + i, 2576, 1449, 1_000_000)));
        }
        ctx.setConversationSummary(AgentContext.CONVERSATION_HEADER + String.join("", messages).strip());
        ctx.setConversation(messages, images);
        return ctx;
    }

    @Test
    @DisplayName("folding a message to a line frees its image's size too, drops the image, and the line says one was pasted")
    void aLineDropsItsImage() {
        var ctx = chat(6, List.of(0));
        assertEquals(1, ctx.conversationImagesShown(0).size());
        // A request that fits once the oldest message and its image -- 9,568 characters as the model
        // reads it -- are folded, and not before.
        long imageChars = (long) Math.ceil(4_784 * ContextFit.CHARS_PER_TOKEN);
        var plan = ContextFit.plan(List.of(), Map.of(), ctx.conversation(), Map.of(), 40_000 + imageChars, null, 0,
                t -> Redactor.cuttable(t, Map.of()), i -> i == 0 ? imageChars : 0L, i -> i == 0 ? 1 : 0);
        assertTrue(plan.chat().get(0).line(), "the oldest folded to a line");
        assertTrue(plan.chat().get(0).shown().contains("characters and an image pasted with it, folded to a line; "
                + "read_result with message=1 reads its text whole]"), plan.chat().get(0).shown());
        assertTrue(plan.labels().getFirst().contains("and 1 image"), plan.labels().toString());

        // Through the context: once folded to a line, the message shows no image.
        ctx.requestSize(40_000 + imageChars, "anthropic/claude-opus-5-5", true);
        assertFalse(ctx.fit(null).isEmpty());
        assertTrue(ctx.conversationImagesShown(0).isEmpty(), "the line shows no image");

        // A request that carried no image -- the local model's -- frees nothing of one by a line.
        var local = chat(6, List.of(0));
        local.requestSize(40_000 + imageChars, "ollama/local-model", false);
        assertTrue(local.fit(null).isEmpty(), "nothing a line could free: " + local.conversationShownMessages());
        // Mutation: leave the image's size out -> the fold frees too little and folds more messages.
    }

    @Test
    @DisplayName("past the API's image count, the chat's oldest images are their lines alone -- only as many as the limit forces, never this message's, and so they stay")
    void theOldestImagesFoldFirst() {
        // Images with messages 0, 2, 4 and 6, and one with this message; three a request at most.
        var ctx = chat(8, List.of(0, 2, 4, 6));
        ctx.addPastedImage(image("now", 640, 480, 10_000), List.of("pasted image"));
        // Message 6's request held four, one past three: it folded one. This one holds five: two.
        assertEquals(1, ctx.fitImages(3, Long.MAX_VALUE), "one more than the last turn's request folded");
        assertEquals(2, ctx.conversationImagesFolded());
        assertTrue(ctx.conversationImagesShown(0).isEmpty());
        assertTrue(ctx.conversationImagesShown(2).isEmpty());
        assertEquals(1, ctx.conversationImagesShown(4).size(), "no more than the limit forces");
        assertEquals(1, ctx.conversationImagesShown(6).size(), "the newest of the chat kept");
        assertEquals(1, ctx.pasted().size(), "this message's own is never folded");
        assertEquals(0, ctx.fitImages(600, Long.MAX_VALUE), "a larger limit later brings none back: the same bytes");
        assertTrue(ctx.conversationImagesShown(0).isEmpty());

        // By bytes: each chat image 1,333,336 bytes as sent; room for this message's and one more.
        var bytes = chat(8, List.of(0, 2, 4, 6));
        bytes.addPastedImage(image("now", 640, 480, 10_000), List.of("pasted image"));
        bytes.fitImages(600, LlmMessage.Image.base64Bytes(10_000) + LlmMessage.Image.base64Bytes(1_000_000));
        assertEquals(1, bytes.conversationImagesShown(6).size(), "the newest kept");
        assertTrue(bytes.conversationImagesShown(4).isEmpty());

        // The reviewers' numbers: 600 in the chat and one now on a 600 a request, one folds -- not 301;
        // 71 of 1.33 MB as sent and one now past 96 MB, one folds -- not 37.
        var count = turnWithImages(1200, "now", 10_000);
        assertEquals(1, count.fitImages(600, LlmMessage.IMAGE_BYTES_PER_REQUEST));
        assertEquals(1, count.conversationImagesFolded());
        var large = turnWithImages(142, "now", 1_000_000);
        assertEquals(1, large.fitImages(600, LlmMessage.IMAGE_BYTES_PER_REQUEST));
        assertEquals(1, large.conversationImagesFolded());
        // Mutation: fold to half the limit at once -> 301 and 37; fold the newest first -> the image the
        // owner is talking about now goes.
    }

    @Test
    @DisplayName("turn after turn past the limit, the fold moves on by what each turn's images force and no more -- never back, never this message's")
    void theFoldMovesOnlyAsTheLimitForces() {
        for (int t = 0; t < 60; t++) {
            // 100 images in the chat when this starts, one more each turn, and one with each message.
            var ctx = turnWithImages(200 + 2 * t, "now-" + t, 700_000);
            assertEquals(1, ctx.fitImages(100, Long.MAX_VALUE), "turn " + t + ": its one image folds the oldest one");
            int folded = 0;
            for (int m = 0; m < 200 + 2 * t; m += 2) if (ctx.conversationImagesShown(m).isEmpty()) folded++;
            assertEquals(t + 1, folded, "turn " + t + ": the oldest " + (t + 1) + ", and no more");
            assertEquals(t + 1, ctx.conversationImagesFolded());
            assertEquals(1, ctx.pasted().size());
        }
        // Mutation: fold in batches -> turns that fold none, and turns that fold half the chat.
    }

    /** A turn of a chat of {@code messages} messages, every user message with an image, and one pasted now. */
    static AgentContext turnWithImages(int messages, String pastedNow) {
        return turnWithImages(messages, pastedNow, 700_000);
    }

    /** {@link #turnWithImages(int, String)}, each image's copy {@code bytes} long. */
    static AgentContext turnWithImages(int messages, String pastedNow, long bytes) {
        var ctx = new AgentContext("u1", "t" + messages, "and this one?");
        var texts = new ArrayList<String>();
        var images = new java.util.HashMap<Integer, List<PastedImage>>();
        int k = 0;
        for (int i = 0; i < messages; i++) {
            boolean user = i % 2 == 0;
            texts.add((user ? "USER: screenshot " : "ASSISTANT: seen ") + i + "\n");
            if (user) images.put(i, List.of(image("img-" + k++, 1456, 816, bytes)));
        }
        ctx.setConversationSummary(AgentContext.CONVERSATION_HEADER + String.join("", texts).strip());
        ctx.setConversation(texts, images);
        ctx.addPastedImage(image(pastedNow, 1456, 816, bytes), List.of("pasted image"));
        return ctx;
    }

    @Test
    @DisplayName("the API's count: 100 on a 200k window or one not known yet, 600 on a larger one")
    void theCountIsTheApis() {
        assertEquals(100, LlmMessage.imagesPerRequest(null));
        assertEquals(100, LlmMessage.imagesPerRequest(200_000));
        assertEquals(600, LlmMessage.imagesPerRequest(1_000_000));
        assertEquals(96_000_000L, LlmMessage.IMAGE_BYTES_PER_REQUEST);
    }

    @Test
    @DisplayName("the engine's request says how many of the chat's images it shows, and which are lines alone")
    void theTaskSaysWhichAreShown() {
        var ctx = chat(8, List.of(0, 2, 4, 6));
        ctx.fitImages(2, Long.MAX_VALUE);
        var task = ChatCacheTest.engine().anthropicTask(ctx);
        assertEquals(2, task.images().size(), "two placed");
        assertTrue(task.text().contains("2 of the 4 images pasted earlier in this chat are shown to you, each above the "
                + "message it was pasted with; the oldest 2 are not"), task.text());
        var local = ThinkingEngine.imagesSection(ctx, ThinkingEngine.Vision.LOCAL, 0);
        assertTrue(local.contains("You cannot see images: none pasted earlier in this chat is shown to you."), local);
    }

    @Test
    @DisplayName("the fact capture's local model is told the owner pasted an image it is not shown")
    void theFactCaptureIsTold() {
        var turn = new FactCapture.Turn("u1", "s1", "a1b2c3d4", List.of("what does this say?"), "It says 192.0.2.1.",
                true, TaskChat.Channel.WEB, 1);
        String prompt = FactCapture.prompt(turn, List.of(), Map.of()).get(1).content();
        assertTrue(prompt.contains("What the owner wrote:\nwhat does this say?\n[He pasted an image with it, which you "
                + "are not shown: the local model cannot see images.]"), prompt);
        var none = new FactCapture.Turn("u1", "s1", "a1b2c3d4", List.of("hi"), "Hello.", true, TaskChat.Channel.WEB);
        assertFalse(FactCapture.prompt(none, List.of(), Map.of()).get(1).content().contains("pasted"));
    }

    @Test
    @DisplayName("a delegation's local model is told the pasted image is handed to its tools, and that it cannot see it")
    void theDelegationIsTold() {
        var ctx = new AgentContext("u1", "t1", "read the screenshot");
        ctx.addPastedImage(image("f1", 640, 480, 10_000), List.of("pasted image"));
        var llm = new DelegationBehaviourTest.Scripted(DelegationBehaviourTest.done("read"));
        DelegationBehaviourTest.executor(llm, new DelegationBehaviourTest.Usage())
                .execute(DelegationBehaviourTest.plan("read the text in the screenshot"), ctx, DelegationBehaviourTest.UNCOUNTED);
        assertTrue(llm.allSeen().contains("- The image the user pasted is given to every tool you call (as _attached_files);\n"
                + "  you cannot see images yourself: only a tool reads it."), llm.allSeen());
        for (var call : llm.calls) for (var m : call) assertTrue(m.images().isEmpty(), "no image reaches it");
    }
}
