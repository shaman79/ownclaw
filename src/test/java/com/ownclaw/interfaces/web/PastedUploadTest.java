package com.ownclaw.interfaces.web;

import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.conversation.TestImages;
import com.ownclaw.conversation.FileStorageService;
import com.ownclaw.conversation.MigratedDatabase;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * POST /api/files/upload: with pasted=true the page claims clipboard image data it named itself,
 * and the answer says, file by file, whether the server shows it to the cloud model -- the copy's
 * size -- or keeps it private, and why. Without the claim nothing is said, and nothing is shown.
 */
class PastedUploadTest {

    static {
        System.setProperty("java.awt.headless", "true");
    }

    /** A part of a multipart upload, as the container hands it over. */
    record Part(String name, String type, byte[] bytes) implements MultipartFile {
        public String getName() { return "files"; }
        public String getOriginalFilename() { return name; }
        public String getContentType() { return type; }
        public boolean isEmpty() { return bytes.length == 0; }
        public long getSize() { return bytes.length; }
        public byte[] getBytes() { return bytes; }
        public InputStream getInputStream() { return new ByteArrayInputStream(bytes); }
        public void transferTo(File dest) { throw new UnsupportedOperationException(); }
    }

    private static HttpServletRequest as(String userId) {
        return (HttpServletRequest) Proxy.newProxyInstance(PastedUploadTest.class.getClassLoader(),
                new Class<?>[]{HttpServletRequest.class},
                (p, m, args) -> "getAttribute".equals(m.getName()) && "userId".equals(args[0]) ? userId : null);
    }

    @Test
    @DisplayName("the answer says which pasted file is shown and at what size, which stays private and why; a 📎 upload says neither")
    @SuppressWarnings("unchecked")
    void theAnswerSaysWhoSeesIt(@TempDir Path tmp) throws Exception {
        var jdbc = MigratedDatabase.at(tmp.resolve("t.db"));
        var config = new OwnClawConfig();
        config.getDatabase().setPath(tmp.resolve("t.db").toString());
        Files.createDirectories(tmp.resolve("uploads"));
        var files = new FileStorageService(jdbc, config);
        var controller = new FileUploadController(files);
        byte[] png = TestImages.png(3840, 2160);

        var pasted = controller.upload(as("u1"), List.of(new Part("pasted-image-101010.png", "image/png", png),
                new Part("pasted-image-101010-2.png", "image/png", "not an image".getBytes(StandardCharsets.UTF_8))), "true");
        var answer = (List<Map<String, Object>>) ((Map<String, Object>) pasted.getBody()).get("files");
        assertEquals(2, answer.size());
        assertEquals(true, answer.get(0).get("shown_to_cloud"));
        assertEquals(2576, answer.get(0).get("cloud_width"));
        assertEquals(1449, answer.get(0).get("cloud_height"));
        assertEquals(3840, answer.get(0).get("image_width"));
        assertEquals(false, answer.get(1).get("shown_to_cloud"));
        assertNotNull(answer.get(1).get("private_because"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM file_attachments WHERE shown_to_cloud = 1", Integer.class));

        var attached = controller.upload(as("u1"), List.of(new Part("screenshot.png", "image/png", png)), null);
        var plain = (List<Map<String, Object>>) ((Map<String, Object>) attached.getBody()).get("files");
        assertFalse(plain.get(0).containsKey("shown_to_cloud"), "a file attached is a file: nothing to say");
        assertEquals(0, jdbc.queryForObject("SELECT shown_to_cloud FROM file_attachments WHERE id = ?", Integer.class,
                plain.get(0).get("id")), "private");
        // Mutation: read the flag as set when absent -> the 📎 upload is shown.
    }
}
