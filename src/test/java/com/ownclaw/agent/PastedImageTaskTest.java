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
    @DisplayName("the task page names the image that went to the cloud, its copy and hash, and the pasted image among the task's files")
    @SuppressWarnings("unchecked")
    void theTaskPageShowsIt(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of(LocalModeTest.NOOP));
        var image = paste(rig, 3840, 2160);
        rig.cloud.think.add(respond("A router."));
        AgentResult r = rig.turn(LocalModeTest.session(rig), "what is it?", List.of(image.id()));
        var page = (Map<String, Object>) new com.ownclaw.interfaces.web.TaskTraceController(
                new com.ownclaw.observability.TaskTraceService(rig.events), rig.files).trace("u1", r.taskId()).getBody();
        var calls = (List<Map<String, Object>>) page.get("calls");
        var sent = (List<Map<String, Object>>) calls.getFirst().get("images");
        assertEquals(1, sent.size(), String.valueOf(calls));
        assertEquals("pasted-image-120000.png", sent.getFirst().get("name"), "named for the owner, looked up by its id");
        assertEquals("image/png", sent.getFirst().get("mediaType"));
        assertEquals(2576, sent.getFirst().get("width"));
        assertEquals(16, String.valueOf(sent.getFirst().get("sha256_16")).length());
        assertEquals(1, ((Map<String, Object>) page.get("totals")).get("images"));
        var artifact = ((List<Map<String, Object>>) page.get("artifacts")).getFirst();
        assertEquals("pasted image", artifact.get("kind"));
        assertEquals("PUBLIC", artifact.get("label"));
        assertEquals("image/png", artifact.get("contentType"));
        assertEquals(3840, artifact.get("imageWidth"));
        assertEquals(2576, artifact.get("width"));
        // The rows themselves hold the file's id, never its name: the ops API reads them.
        var rows = rig.jdbc.queryForList("SELECT details FROM events WHERE task_id = ?", String.class, r.taskId());
        assertTrue(rows.stream().noneMatch(d -> d.contains("pasted-image-120000")), String.valueOf(rows));
        // Mutation: leave images out of the trace's calls -> the page cannot say an image went.
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
        String sidecar = rig.jdbc.queryForObject("SELECT cloud_copy FROM file_attachments WHERE id = ?", String.class, image.id());
        assertEquals(Base64.getEncoder().encodeToString(Files.readAllBytes(tmp.resolve("uploads").resolve(sidecar))),
                content.get(at).path("source").path("data").asText(), "its copy, made at upload -- never the pasted bytes");
        assertTrue(content.get(at - 1).path("text").asText().endsWith("## Task\n"), content.get(at - 1).toString());
        assertTrue(content.get(at + 1).path("text").asText().startsWith("read it"), content.get(at + 1).toString());
        assertFalse(body.toString().contains("\\u0001"));
    }

    /** {@code n} different small screenshots, pasted. */
    static List<String> pasteMany(LoopRig rig, int n) throws Exception {
        var ids = new java.util.ArrayList<String>();
        for (int i = 0; i < n; i++) {
            var stored = rig.files.store("u1", "pasted-image-" + i + ".png", "image/png",
                    new ByteArrayInputStream(TestImages.png(40 + i, 30 + i % 7)), true);
            assertTrue(stored.shownToCloud(), stored.privateBecause());
            ids.add(stored.id());
        }
        return ids;
    }

    @Test
    @DisplayName("the task page counts each image once, however many requests carried it, and says with how many it went")
    @SuppressWarnings("unchecked")
    void eachImageOnce(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of(LocalModeTest.NOOP));
        rig.cloud.think.add(call("noop", Map.of()));
        rig.cloud.think.add(call("noop", Map.of()));
        rig.cloud.think.add(respond("A router."));
        AgentResult r = rig.turn(LocalModeTest.session(rig), "what is this?", pasteMany(rig, 1));
        var page = (Map<String, Object>) new com.ownclaw.interfaces.web.TaskTraceController(
                new com.ownclaw.observability.TaskTraceService(rig.events), rig.files).trace("u1", r.taskId()).getBody();
        var totals = (Map<String, Object>) page.get("totals");
        assertEquals(1, totals.get("images"), "one image you pasted");
        assertEquals(3, totals.get("imageRequests"), "went with three requests");
        assertEquals(0, totals.get("imagesFailed"));
        var artifact = ((List<Map<String, Object>>) page.get("artifacts")).getFirst();
        assertEquals(3, artifact.get("shownWith"));
        // Mutation: count an image per request again -> "3 images you pasted".
    }

    @Test
    @DisplayName("a request the provider billed and then failed -- a stream cut after message_start -- was read, its image with it: the page and the ending count it as shown, and say it failed")
    @SuppressWarnings("unchecked")
    void readThenFailedIsShown(@TempDir Path tmp) throws Exception {
        for (boolean billed : List.of(true, false)) {
            var rig = new LoopRig(tmp.resolve("b" + billed), List.of(LocalModeTest.NOOP));
            for (int i = 0; i < 8; i++) {
                rig.cloud.think.add(c -> {
                    // message_start had come: the provider read the whole request, the image with it.
                    if (billed) c.progress().billed(new com.ownclaw.llm.LlmResponse.Usage("claude-opus-5", 6_200, 0, 0, 0));
                    throw new com.ownclaw.llm.LlmException("anthropic", "Connection failed: timeout", 0,
                            new java.net.SocketTimeoutException("timeout"));
                });
            }
            AgentResult r = rig.turn(LocalModeTest.session(rig), "what does this screenshot say?", pasteMany(rig, 1));
            var page = (Map<String, Object>) new com.ownclaw.interfaces.web.TaskTraceController(
                    new com.ownclaw.observability.TaskTraceService(rig.events), rig.files).trace("u1", r.taskId()).getBody();
            var totals = (Map<String, Object>) page.get("totals");
            int calls = rig.cloud.calls("think").size();
            assertTrue(calls > 0, "the requests were made");
            var artifact = ((List<Map<String, Object>>) page.get("artifacts")).getFirst();
            if (billed) {
                assertEquals(1, totals.get("images"), "read by the model: " + totals);
                assertEquals(calls, totals.get("imageRequests"));
                assertEquals(calls, totals.get("imageRequestsFailed"));
                assertEquals(0, totals.get("imagesFailed"), "not 'may not have reached'");
                assertEquals(calls, artifact.get("shownWith"));
                assertEquals(calls, artifact.get("shownFailed"));
                assertTrue(r.response().contains("shown to the cloud model with " + calls + (calls == 1 ? " request" : " requests")
                        + " (" + calls + " failed after the model had read " + (calls == 1 ? "it)" : "them)")), r.response());
            } else {
                assertEquals(0, totals.get("images"), "nothing billed: it may not have reached the provider");
                assertEquals(1, totals.get("imagesFailed"));
                assertEquals(0, artifact.get("shownWith"));
                assertTrue(r.response().contains("not shown to a model in this task"), r.response());
            }
        }
        // Mutation: count only SENT rows again -> the page says "not shown" beside "failed after the
        // provider had begun to answer".
    }

    @Test
    @DisplayName("a message pasted with more images than one request takes is refused before any call, saying how to send them")
    void tooManyImagesForOneRequest(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of(LocalModeTest.NOOP));
        rig.cloud.think.add(respond("never asked"));
        AgentResult r = rig.turn(LocalModeTest.session(rig), "all of these", pasteMany(rig, 101));
        assertTrue(rig.cloud.calls.isEmpty(), "nothing went: " + rig.cloud.calls.size());
        assertEquals(AgentResult.TerminationReason.ERROR, r.terminationReason());
        assertTrue(r.response().contains("this message carries 101 pasted images"), r.response());
        assertTrue(r.response().contains("at most 100 images"), r.response());
        assertTrue(r.response().contains("Paste them over several messages"), r.response());
        // On Free nothing is sent, so nothing is refused.
        var local = LocalModeTest.localModel();
        var free = new LoopRig(tmp.resolve("free"), List.of(LocalModeTest.NOOP), 600, local);
        local.think.add(respond("done locally"));
        assertEquals("done locally", free.turn(LocalModeTest.session(free), "all of these", new ChatOptions("free", null),
                pasteMany(free, 101)).response());
        // Mutation: drop the check -> the request holds 101 images and the API refuses it.
    }

    @Test
    @DisplayName("a Stop during the window's lookup, before the first request, ends the task at once: the lookup is made in the loop, where a Stop reaches it")
    void stopDuringTheWindowsLookup(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of(LocalModeTest.NOOP));
        // A Models API that takes six seconds, hearing a Stop as a request in flight does.
        rig.cloud.window = progress -> {
            long end = System.currentTimeMillis() + 6_000;
            while (System.currentTimeMillis() < end) {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
                progress.onProgress();
            }
            return 1_000_000;
        };
        rig.cloud.think.add(respond("seen"));
        String session = LocalModeTest.session(rig);
        var ids = pasteMany(rig, 1);
        long t0 = System.currentTimeMillis();
        var done = new java.util.concurrent.CompletableFuture<AgentResult>();
        new Thread(() -> {
            try {
                done.complete(rig.turn(session, "what is on it?", ids));
            } catch (Throwable e) {
                done.completeExceptionally(e);
            }
        }).start();
        while (rig.cloud.lookups.isEmpty() && System.currentTimeMillis() - t0 < 10_000) Thread.sleep(10);
        Thread.sleep(500);
        rig.cancellation.requestAll("u1", "the owner pressed Stop");
        long stopAt = System.currentTimeMillis();
        AgentResult r = done.get(30, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(AgentResult.TerminationReason.CANCELLED, r.terminationReason());
        assertTrue(System.currentTimeMillis() - stopAt < 2_000, "ended at the Stop, not when the lookup did: "
                + (System.currentTimeMillis() - stopAt) + " ms");
        assertTrue(rig.cloud.calls("think").isEmpty(), "no request was made");
        // Mutation: look the window up before the loop again -> the Stop waits out the six seconds.
    }

    @Test
    @DisplayName("a window lookup that failed is made once a task, not on every step: each waited out its timeout")
    void aFailedLookupOnceATask(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of(LocalModeTest.NOOP));
        rig.cloud.window = progress -> null;
        rig.cloud.think.add(call("noop", Map.of()));
        rig.cloud.think.add(call("noop", Map.of()));
        rig.cloud.think.add(respond("seen"));
        rig.turn(LocalModeTest.session(rig), "what is on it?", pasteMany(rig, 1));
        assertEquals(3, rig.cloud.calls("think").size());
        assertEquals(1, rig.cloud.lookups.size(), "asked once in three steps: " + rig.cloud.lookups);
        // Mutation: ask the provider on every step -> four lookups, each a timeout on a network that is down.
    }

    @Test
    @DisplayName("a message pasted with eighteen images: the next turn still finds the last turn's cached chat, through the free fourth mark")
    void eighteenImagesKeepTheCache(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of(LocalModeTest.NOOP));
        String session = LocalModeTest.session(rig);
        rig.cloud.think.add(respond("Hi."));
        rig.turn(session, "hello");
        rig.cloud.think.add(respond("Screens."));
        rig.turn(session, "what are these?", pasteMany(rig, 18));
        rig.cloud.think.add(respond("Bye."));
        rig.turn(session, "thanks");
        var calls = rig.cloud.calls("think");
        JsonNode two = ChatCacheTest.body(calls.get(1).messages(), calls.get(1).config().tools());
        JsonNode three = ChatCacheTest.body(calls.get(2).messages(), calls.get(2).config().tools());
        int was = firstMark(two), is = firstMark(three);
        assertTrue(is >= 0 && is - was <= 19, "turn 3's first mark is " + (is - was) + " blocks past turn 2's");
        for (int i = 0; i <= was; i++) {
            assertEquals(two.path("messages").get(0).path("content").get(i).path("text"),
                    three.path("messages").get(0).path("content").get(i).path("text"), "the cached prefix, block " + i);
        }
        // Mutation: no fourth mark -> the mark 21 blocks on cannot reach back, and the chat is written again.
    }

    static int firstMark(JsonNode body) {
        JsonNode content = body.path("messages").get(0).path("content");
        for (int i = 0; i < content.size(); i++) if (content.get(i).has("cache_control")) return i;
        return -1;
    }

    @Test
    @DisplayName("a photo whose file carries EXIF with a place and an address: shown to the cloud, and what skills read from it private")
    void metadataMakesResultsPrivate(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of(LocalModeTest.NOOP));
        byte[] jpeg = TestImages.withSegment(TestImages.encoded(TestImages.screenshot(800, 600), "jpeg"), 0xE1,
                TestImages.exif(1, "GPS 49.19N 16.61E owner@example.com"));
        var photo = rig.files.store("u1", "image.jpg", "image/jpeg", new ByteArrayInputStream(jpeg), true);
        assertTrue(photo.shownToCloud());
        var ctx = new AgentContext("u1", "c3d4e5f6", "what does it show?");
        AgentLoop.registerAttachments(ctx, List.of(photo.id()), rig.files, rig.events);
        assertEquals(1, ctx.pasted().size(), "its picture is shown");
        assertEquals(1, ctx.files().size(), "and it counts among the files that make results private");
        assertTrue(ctx.privateFiles().isEmpty(), "it is no private file: the cloud sees the picture");
        var decision = ctx.decide(List.of(), List.of(), false, "{\"GPS\": \"49.19N 16.61E\", \"owner\": \"owner@example.com\"}");
        assertEquals(Label.PRIVATE, decision.label(), "a skill's report of its EXIF stays on this machine");
        String task = ChatCacheTest.engine().anthropicTask(ctx).text();
        assertTrue(task.contains("The file of {{1}} carries EXIF data beside the picture"), task);
        assertTrue(task.contains("every result of this task is private"), task);
        assertEquals("", ThinkingEngine.filesSection(ctx), "not listed as a private file: it is shown");

        // And through the loop: the picture goes, the address does not.
        rig.cloud.think.add(respond("A router's page."));
        rig.turn(LocalModeTest.session(rig), "what does it show?", List.of(photo.id()));
        LlmMessage sent = first(rig.cloud.calls("think").get(0));
        assertNotNull(sent.images().getFirst().sent(), "the picture is sent");
        String bytes = new String(Base64.getDecoder().decode(sent.images().getFirst().sent().base64()), StandardCharsets.ISO_8859_1);
        assertFalse(bytes.contains("owner@example.com"), "its copy holds the picture alone");
        // Mutation: count only private files in files() -> the EXIF a skill reads goes to the cloud as public.
    }

    @Test
    @DisplayName("a cloud model that is sent no images -- OpenAI -- is told so, and so is the owner, once")
    void openAiIsSentNone(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of(LocalModeTest.NOOP));
        rig.cloud.name = "openai";
        rig.config.getMentor().setProvider("openai");
        var image = paste(rig, 640, 480);
        rig.cloud.think.add(call("noop", Map.of()));
        rig.cloud.think.add(respond("I cannot see it."));
        rig.turn(LocalModeTest.session(rig), "what is this?", List.of(image.id()));
        for (Call call : rig.cloud.calls("think")) {
            for (LlmMessage m : call.messages()) {
                assertTrue(m.images().isEmpty(), "no image goes to OpenAI");
                assertFalse(m.content().contains("\u0001"));
            }
        }
        assertTrue(LocalModeTest.all(rig.cloud.calls("think").getFirst()).contains("Images are not sent to this model"));
        List<String> notes = LocalModeTest.rows(rig).stream().filter(n -> n.startsWith("🖼️")).toList();
        assertEquals(1, notes.size(), String.valueOf(LocalModeTest.rows(rig)));
        assertTrue(notes.getFirst().contains("this chat's cloud model is one OwnClaw sends no images"), notes.getFirst());
        assertFalse(notes.getFirst().contains("openai"), "the provider is the owner's setting: " + notes.getFirst());
        // Mutation: tell only the local model's steps -> the owner trusts that OpenAI saw his screenshot.
    }

    @Test
    @DisplayName("the ending names the pasted image as one, and whether a request showed it: on Free, none did")
    void theEndingSaysItsFate(@TempDir Path tmp) throws Exception {
        var local = LocalModeTest.localModel();
        var rig = new LoopRig(tmp, List.of(LocalModeTest.NOOP), 600, local);
        var image = paste(rig, 1280, 720);
        local.think.add(c -> {
            throw new com.ownclaw.llm.LlmException("ollama", "Connection failed: refused", 0, new java.net.ConnectException("refused"));
        });
        AgentResult r = rig.turn(LocalModeTest.session(rig), "what does this say?", new ChatOptions("free", null),
                List.of(image.id()));
        assertTrue(r.response().contains("result 1 (the image you pasted, 1280x720 px): not shown to a model in this task."),
                r.response());
        assertFalse(r.response().contains("(attachment): 0 chars"), r.response());
        // Mutation: list it as any result -> "result 1 (attachment): 0 chars, public".
    }
}
