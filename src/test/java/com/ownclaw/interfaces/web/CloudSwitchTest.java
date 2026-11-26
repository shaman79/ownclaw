package com.ownclaw.interfaces.web;

import com.ownclaw.agent.AgentContext;
import com.ownclaw.agent.AgentLoopAccess;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.conversation.FileStorageService;
import com.ownclaw.conversation.MigratedDatabase;
import com.ownclaw.conversation.TestImages;
import com.ownclaw.observability.EventLogService;
import com.ownclaw.privacy.Label;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The owner's choice, file by file before he sends it, whether the cloud model may see it (his
 * decision of 2026-11-26): POST /api/files/{id}/cloud. An image is then shown as a picture; any
 * other file is no private file, its text given to the cloud model and what skills read from it
 * not private; switched back, private. His own files only, and only before they went with a message.
 */
class CloudSwitchTest {

    static {
        System.setProperty("java.awt.headless", "true");
    }

    private static HttpServletRequest as(String userId) {
        return (HttpServletRequest) Proxy.newProxyInstance(CloudSwitchTest.class.getClassLoader(),
                new Class<?>[]{HttpServletRequest.class},
                (p, m, args) -> "getAttribute".equals(m.getName()) && "userId".equals(args[0]) ? userId : null);
    }

    record Rig(JdbcTemplate jdbc, FileStorageService files, FileUploadController controller, Path uploads) {
        static Rig in(Path tmp) throws Exception {
            var jdbc = MigratedDatabase.at(tmp.resolve("t.db"));
            var config = new OwnClawConfig();
            config.getDatabase().setPath(tmp.resolve("t.db").toString());
            Path uploads = Files.createDirectories(tmp.resolve("uploads"));
            var files = new FileStorageService(jdbc, config);
            return new Rig(jdbc, files, new FileUploadController(files), uploads);
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> cloud(String user, String id, Object visible) {
            var r = controller.cloud(as(user), id, visible == null ? Map.of() : Map.of("visible", visible));
            return r.getBody() instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of("status", r.getStatusCode().value());
        }

        int status(String user, String id, Object visible) {
            return controller.cloud(as(user), id, visible == null ? Map.of() : Map.of("visible", visible)).getStatusCode().value();
        }
    }

    @Test
    @DisplayName("an image attached with 📎 is shown as a picture when he lets the cloud see it, and private again when he does not")
    void anImageBothWays(@TempDir Path tmp) throws Exception {
        var rig = Rig.in(tmp);
        String id = rig.files.store("u1", "photo.png", "image/png", new java.io.ByteArrayInputStream(TestImages.png(640, 480)));
        assertNull(FileStorageService.pastedImage(rig.files.getFileInfo(id)), "attached: private");

        var shown = rig.cloud("u1", id, true);
        assertEquals(true, shown.get("shown_to_cloud"), String.valueOf(shown));
        assertEquals(640, shown.get("cloud_width"));
        var image = FileStorageService.pastedImage(rig.files.getFileInfo(id));
        assertNotNull(image, "shown as a pasted one is");
        String copy = String.valueOf(rig.files.getFileInfo(id).get("cloud_copy"));
        assertTrue(Files.exists(rig.uploads.resolve(copy)));

        var kept = rig.cloud("u1", id, false);
        assertEquals(false, kept.get("shown_to_cloud"));
        assertEquals("you chose to keep it private", kept.get("private_because"));
        assertNull(FileStorageService.pastedImage(rig.files.getFileInfo(id)));
        assertFalse(Files.exists(rig.uploads.resolve(copy)), "its copy is gone with it");
        // Mutation: leave shown_to_cloud set when made private -> the picture is still sent.
    }

    @Test
    @DisplayName("any other file he lets the cloud see is no private file: registered PUBLIC, its text and name listed, its results not private")
    void aFileIsShared(@TempDir Path tmp) throws Exception {
        var rig = Rig.in(tmp);
        String id = rig.files.store("u1", "cenik.csv", "text/csv",
                new java.io.ByteArrayInputStream("Položka;Cena\nObědové menu;155 Kč\n".getBytes(StandardCharsets.UTF_8)));
        var said = rig.cloud("u1", id, true);
        assertEquals(true, said.get("cloud_visible"), String.valueOf(said));
        assertEquals(false, said.get("shown_to_cloud"));
        assertTrue(FileStorageService.cloudVisible(rig.files.getFileInfo(id)));

        var ctx = new AgentContext("u1", "t1", "Read the price list.");
        AgentLoopAccess.registerAttachments(ctx, List.of(id), rig.files, new EventLogService(rig.jdbc));
        assertEquals(1, ctx.sharedFiles().size());
        assertTrue(ctx.files().isEmpty(), "no file makes a result private");
        assertTrue(ctx.privateFiles().isEmpty());
        assertEquals(List.of(id), ctx.attachmentIds(), "every skill is handed it, as any file");
        var shared = ctx.sharedFiles().getFirst();
        assertEquals(Label.PUBLIC, shared.label());
        assertEquals(Label.PUBLIC, ctx.decide(List.of(), List.of(), false, "Obědové menu: 155 Kč").label(),
                "what a skill reads from it is not private");
        String section = AgentLoopAccess.filesSection(ctx);
        assertTrue(section.contains("## Files the user lets you read"), section);
        assertTrue(section.contains("named \"cenik.csv\""), section);
        assertTrue(section.contains("Obědové menu;155 Kč"), "its text, given whole: " + section);
        assertFalse(section.contains("## Files sent with this message\n"), "no private file listed: " + section);

        rig.cloud("u1", id, false);
        var again = new AgentContext("u1", "t2", "Read it.");
        AgentLoopAccess.registerAttachments(again, List.of(id), rig.files, new EventLogService(rig.jdbc));
        assertEquals(1, again.privateFiles().size(), "switched back: private");
        assertEquals(Label.PRIVATE, again.decide(List.of(), List.of(), false, "x").label());
        // Mutation: register a cloud-visible file as before -> every result of the task is private.
    }

    @Test
    @DisplayName("an image that does not decode is shared as a file; a file not his is no file; one that went with a message is fixed; a body without the choice is refused")
    void theEdges(@TempDir Path tmp) throws Exception {
        var rig = Rig.in(tmp);
        String heic = rig.files.store("u1", "photo.heic", "image/heic", new java.io.ByteArrayInputStream("not a png".getBytes()));
        var said = rig.cloud("u1", heic, true);
        assertEquals(false, said.get("shown_to_cloud"));
        assertEquals(true, said.get("cloud_visible"), "readable through skills, as any file");

        String mine = rig.files.store("u1", "a.pdf", "application/pdf", new java.io.ByteArrayInputStream("%PDF".getBytes()));
        assertEquals(404, rig.status("u2", mine, true), "not his");
        assertFalse(FileStorageService.cloudVisible(rig.files.getFileInfo(mine)));
        assertEquals(400, rig.status("u1", mine, null));
        assertEquals(400, rig.status("u1", mine, "yes"));

        rig.jdbc.update("INSERT INTO users (id, display_name) VALUES ('u1', 'Owner')");
        rig.jdbc.update("INSERT INTO conversations (id, user_id, session_id, role, content) VALUES ('m1', 'u1', 's1', 'user', 'x')");
        rig.jdbc.update("INSERT INTO message_attachments (message_id, file_id) VALUES ('m1', ?)", mine);
        assertEquals(409, rig.status("u1", mine, true), "it went with a message: fixed");
        assertFalse(FileStorageService.cloudVisible(rig.files.getFileInfo(mine)));
    }
}
