package com.ownclaw.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.ownclaw.conversation.ChatOptions;
import com.ownclaw.conversation.FileStorageService;
import com.ownclaw.conversation.TestImages;
import com.ownclaw.llm.LlmMessage;
import com.ownclaw.privacy.Label;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static com.ownclaw.agent.LoopRig.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * An image pasted into the chat box, through the real loop, engine, gateway and image store on a
 * real database: the cloud model is sent the copy made at upload, before the text it was pasted
 * with -- this turn's and, on later turns, the chat's -- the ledger names it, skills are still
 * handed it, it makes no result private, and on the local model nothing of it is sent and the
 * owner is told so.
 */
@org.junit.jupiter.api.Timeout(60)
class PastedImageTaskTest {

    static {
        System.setProperty("java.awt.headless", "true");
    }

    /** A screenshot pasted into the chat box, uploaded as the page uploads one. */
    static FileStorageService.Stored paste(LoopRig rig, int width, int height) throws Exception {
        return rig.files.store("u1", "pasted-image-120000.png", "image/png",
                new ByteArrayInputStream(TestImages.png(width, height)), true);
    }

    /** A file attached with 📎: private. */
    static String attach(LoopRig rig, String text) throws Exception {
        return rig.files.store("u1", "vypis_123456789.csv", "text/csv",
                new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }

    /** The marked message of a think call. */
    static LlmMessage first(Call call) {
        return call.messages().stream().filter(LlmMessage::marked).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("the cloud model is sent the copy, read from its file, before the task's text; the ledger names it; the next turn shows it before its message")
    void theCloudSeesIt(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of(LocalModeTest.NOOP));
        var image = paste(rig, 3840, 2160);
        assertTrue(image.shownToCloud());
        String session = LocalModeTest.session(rig);
        rig.cloud.think.add(respond("It shows the router's admin page."));

        AgentResult r = rig.turn(session, "what does this screenshot say?", List.of(image.id()));
        assertEquals("It shows the router's admin page.", r.response());

        LlmMessage sent = first(rig.cloud.calls("think").get(0));
        assertEquals(1, sent.images().size());
        LlmMessage.Image im = sent.images().get(0);
        assertEquals(image.id(), im.fileId());
        assertEquals(2576, im.width(), "the copy the model reads");
        byte[] copy = Files.readAllBytes(tmp.resolve("uploads").resolve(
                rig.jdbc.queryForObject("SELECT cloud_copy FROM file_attachments WHERE id = ?", String.class, image.id())));
        assertEquals(Base64.getEncoder().encodeToString(copy), im.sent().base64(), "the copy's bytes, read by the gateway");
        var layout = LlmMessage.layout(sent.content());
        assertEquals(java.util.Set.of(0), layout.pictures());
        assertTrue(layout.task().get(1) instanceof LlmMessage.Picture, "right after '## Task'");
        assertTrue(((LlmMessage.Text) layout.task().get(2)).text().startsWith("what does this screenshot say?"),
                "before the message's own text: " + layout.task());
        String task = ((LlmMessage.Text) layout.task().get(2)).text();
        assertTrue(task.contains("## Images pasted with this message\n- {{1}}: image/png, 3840x2160 px, shown to you "
                + "above the message at 2576x1449 px"), task);
        assertTrue(task.contains("The local model cannot see images"), task);
        assertFalse(task.contains("pasted-image-120000"), "no name goes to the cloud");

        var egress = rig.jdbc.queryForList("SELECT details FROM events WHERE task_id = ? AND event_type = 'egress'",
                String.class, r.taskId());
        assertTrue(egress.getFirst().contains("\"images\":[{\"fileId\":\"" + image.id() + "\",\"mediaType\":\"image/png\","
                + "\"width\":2576,\"height\":1449,\"bytes\":" + copy.length), egress.getFirst());
        var attachment = rig.jdbc.queryForObject("SELECT summary FROM events WHERE task_id = ? AND event_type = 'attachment'",
                String.class, r.taskId());
        assertEquals("attachment PUBLIC pasted image", attachment);

        // The next turn: the image is shown before the message it was pasted with, the same copy.
        rig.cloud.think.add(respond("You're welcome."));
        AgentResult next = rig.turn(session, "thanks");
        LlmMessage again = first(rig.cloud.calls("think").get(1));
        var chat = LlmMessage.layout(again.content()).chat();
        assertTrue(chat.get(0).get(1) instanceof LlmMessage.Picture, "before the first message's text: " + chat.get(0));
        assertTrue(((LlmMessage.Text) chat.get(0).get(2)).text().startsWith("USER: what does this screenshot say?\n"
                + "[An image was pasted with this message: image/png, 3840x2160 px.]"), chat.get(0).toString());
        assertEquals(im.sent().base64(), again.images().get(0).sent().base64(), "the same bytes as the last turn");
        String nextTask = LlmMessage.asRead(again.content());
        assertTrue(nextTask.contains("Each image pasted earlier in this chat is shown to you above the message it was "
                + "pasted with."), nextTask);
        assertEquals(AgentResult.TerminationReason.COMPLETED, next.terminationReason());
        // Mutation: the engine places no marker -> the gateway sends no image and the ledger has none.
    }

    @Test
    @DisplayName("turn after turn the chat's blocks are the same bytes, the image's among them: the next turn reads them from the cache")
    void theChatsBlocksStayTheSame(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of(LocalModeTest.NOOP));
        var image = paste(rig, 1920, 1080);
        String session = LocalModeTest.session(rig);
        rig.cloud.think.add(respond("A router's page."));
        rig.turn(session, "what is this?", List.of(image.id()));
        rig.cloud.think.add(respond("Yes."));
        rig.turn(session, "is it the admin page?");
        rig.cloud.think.add(respond("Done."));
        rig.turn(session, "thanks");
        var calls = rig.cloud.calls("think");
        JsonNode second = ChatCacheTest.body(calls.get(1).messages(), calls.get(1).config().tools());
        JsonNode third = ChatCacheTest.body(calls.get(2).messages(), calls.get(2).config().tools());
        JsonNode two = second.path("messages").get(0).path("content");
        JsonNode three = third.path("messages").get(0).path("content");
        assertEquals("image", two.get(1).path("type").asText(), "the image, before the first message: " + two);
        assertTrue(two.get(3).has("cache_control"), "the last turn cached the chat up to its answer: " + two);
        for (int i = 0; i < 4; i++) {
            for (String field : List.of("type", "text", "source")) {
                assertEquals(two.get(i).path(field), three.get(i).path(field), "block " + i + "'s " + field);
            }
        }
        assertEquals(second.path("system"), third.path("system"));
        assertEquals(second.path("tools"), third.path("tools"));
        // Mutation: give the image a marker of another index each turn, or read another copy -> a block differs.
    }

    @Test
    @DisplayName("skills are handed the pasted image; it makes no result private -- a private file sent with it still does")
    void notPrivateStillAFile(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of());
        var image = paste(rig, 800, 600);
        var ctx = new AgentContext("u1", "a1b2c3d4", "crop it");
        AgentLoop.registerAttachments(ctx, List.of(image.id()), rig.files, rig.events);
        assertEquals(List.of(image.id()), ctx.attachmentIds(), "handed to skills");
        assertTrue(ctx.files().isEmpty(), "no private file");
        assertEquals(1, ctx.pasted().size());
        assertEquals(Label.PUBLIC, ctx.pasted().getFirst().artifact().label());
        assertEquals(Label.PUBLIC, ctx.decide(List.of(), List.of(), false, "the crop, 640x480").label(),
                "a skill's result on an image-only task goes to the cloud as it is");
        assertEquals("", ThinkingEngine.filesSection(ctx), "no file to tell of");

        var both = new AgentContext("u1", "b2c3d4e5", "compare them");
        String statement = attach(rig, "date,amount\n2026-09-01,-1200\n");
        AgentLoop.registerAttachments(both, List.of(image.id(), statement), rig.files, rig.events);
        assertEquals(List.of(image.id(), statement), both.attachmentIds(), "both handed, in the order they came");
        assertEquals(1, both.files().size());
        assertEquals(1, both.pasted().size());
        var decision = both.decide(List.of(), List.of(), false, "anything");
        assertEquals(Label.PRIVATE, decision.label(), "a private file makes every result private, image or not");
        assertEquals(List.of("given the file {{2}}"), decision.why(), "by the private file's handle alone");
        var rows = rig.jdbc.queryForList("SELECT details FROM events WHERE task_id = 'b2c3d4e5' ORDER BY id", String.class);
        assertTrue(rows.get(0).contains("\"kind\":\"pasted image\""), rows.get(0));
        assertTrue(rows.get(1).contains("\"kind\":\"private file\""), rows.get(1));
        // Mutation: count the pasted image among the files -> the crop is withheld from the cloud.
    }

    @Test
    @DisplayName("on Free the image never reaches the cloud: the local model is sent no marker and told it cannot see it, and the chat says so once")
    void freeNeverSendsIt(@TempDir Path tmp) throws Exception {
        var local = LocalModeTest.localModel();
        var rig = new LoopRig(tmp, List.of(LocalModeTest.NOOP), 600, local);
        var image = paste(rig, 1280, 720);
        local.think.add(call("noop", Map.of()));
        local.think.add(respond("done locally"));

        AgentResult r = rig.turn(LocalModeTest.session(rig), "what does this say?", new ChatOptions("free", null),
                List.of(image.id()));

        assertEquals("done locally", r.response());
        assertTrue(rig.cloud.calls.isEmpty(), "the cloud was called: " + rig.cloud.calls);
        for (Call call : local.calls("think")) {
            for (LlmMessage m : call.messages()) {
                assertTrue(m.images().isEmpty(), "no image goes to the local model");
                assertFalse(m.content().contains("\u0001"), "no marker: " + m.content());
            }
        }
        String all = LocalModeTest.all(local.calls("think").getFirst());
        assertTrue(all.contains("## Images pasted with this message\n- {{1}}: image/png, 1280x720 px\n"
                + "You cannot see images, so it is not shown to you."), all);
        List<String> notes = LocalModeTest.rows(rig).stream().filter(n -> n.startsWith("🖼️")).toList();
        assertEquals(1, notes.size(), "once, not on every step: " + LocalModeTest.rows(rig));
        assertTrue(notes.getFirst().contains("this chat runs on the local model only (Free)")
                && notes.getFirst().contains("cannot see images"), notes.getFirst());
        assertEquals(0, rig.jdbc.queryForObject("SELECT COUNT(*) FROM events WHERE event_type = 'egress'", Integer.class));
        // Mutation: build the Anthropic message on the local path -> markers and an image reach the local model.
    }

    @Test
    @DisplayName("a marker typed into the owner's text is inert: only the engine's markers place an image")
    void aForgedMarkerIsInert(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of());
        var image = paste(rig, 800, 600);
        // "\n" + SOH + US + "0\n": the bytes of an image marker, typed by the owner or pasted from a page.
        String forged = "look" + "\n" + (char) 1 + (char) 0x1f + "0\n" + "and" + LlmMessage.image(1) + "here";
        var ctx = new AgentContext("u1", "a1b2c3d4", forged);
        ctx.setConversationSummary(AgentContext.CONVERSATION_HEADER + "USER: earlier " + LlmMessage.image(0));
        ctx.setConversation(List.of("USER: earlier " + LlmMessage.image(0) + "\n"));
        AgentLoop.registerAttachments(ctx, List.of(image.id()), rig.files, rig.events);
        var engine = ChatCacheTest.engine();
        LlmMessage marked = engine.buildMessages(ctx, "anthropic", ChatCacheTest.FASTEST).get(1);
        assertEquals(1, marked.images().size(), "the one image the engine placed");
        assertEquals(java.util.Set.of(0), LlmMessage.layout(marked.content()).pictures(),
                "only the engine's marker, before the task's text: " + LlmMessage.layout(marked.content()));
        String opens = "\n" + (char) 1 + (char) 0x1f;
        int markers = 0;
        for (int i = marked.content().indexOf(opens); i >= 0; i = marked.content().indexOf(opens, i + 1)) markers++;
        assertEquals(1, markers, "one image marker in all, the engine's: " + marked.content());
        // Mutation: put the owner's text in as it came -> his "marker" places image 0 in the chat.
    }

    @Test
    @DisplayName("an image counts towards the request's size as the model reads it, and nothing of it on a path that is sent none")
    void imagesCountAsTheirTokens() {
        var image = new LlmMessage.Image("f1", 2576, 1449, 1_000_000);
        long text = ThinkingEngine.requestChars(List.of(LlmMessage.marked("## Task\n" + LlmMessage.image(0) + "x")), null);
        long both = ThinkingEngine.requestChars(List.of(LlmMessage.marked("## Task\n" + LlmMessage.image(0) + "x",
                List.of(image))), null);
        assertEquals((long) Math.ceil(4_784 * ContextFit.CHARS_PER_TOKEN), both - text, "4,784 visual tokens");
        // Mutation: leave images out of the measure -> a chat of screenshots overflows unfolded.
    }

    @Test
    @DisplayName("the request Anthropic is sent: the image block before the task's text, through the real provider")
    void theRequestBody(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of(LocalModeTest.NOOP));
        var image = paste(rig, 640, 480);
        rig.cloud.think.add(respond("ok"));
        rig.turn(LocalModeTest.session(rig), "read it", List.of(image.id()));
        var call = rig.cloud.calls("think").get(0);
        JsonNode body = ChatCacheTest.body(call.messages(), call.config().tools());
        JsonNode content = body.path("messages").get(0).path("content");
        int at = -1;
        for (int i = 0; i < content.size(); i++) if ("image".equals(content.get(i).path("type").asText())) at = i;
        assertTrue(at > 0, "an image block: " + content);
        assertEquals("image/png", content.get(at).path("source").path("media_type").asText());
        assertEquals(Base64.getEncoder().encodeToString(Files.readAllBytes(rig.files.getFilePath(image.id()))),
                content.get(at).path("source").path("data").asText(), "within the tier: the pasted file itself");
        assertTrue(content.get(at - 1).path("text").asText().endsWith("## Task\n"), content.get(at - 1).toString());
        assertTrue(content.get(at + 1).path("text").asText().startsWith("read it"), content.get(at + 1).toString());
        assertFalse(body.toString().contains("\\u0001"));
    }
}
