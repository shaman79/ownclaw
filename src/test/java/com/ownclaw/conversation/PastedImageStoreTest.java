package com.ownclaw.conversation;

import com.ownclaw.config.OwnClawConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * An upload is shown to the cloud model only when the page says it is image data it named itself
 * AND its bytes decode as an image -- a claim alone makes nothing an image -- and the gateway's
 * lookup gives the copy recorded then, of this user's file, and nothing else.
 */
class PastedImageStoreTest {

    static {
        System.setProperty("java.awt.headless", "true");
    }

    record Store(JdbcTemplate jdbc, FileStorageService files, Path uploads) {
        static Store in(Path tmp) throws Exception {
            var jdbc = MigratedDatabase.at(tmp.resolve("t.db"));
            var config = new OwnClawConfig();
            config.getDatabase().setPath(tmp.resolve("t.db").toString());
            Path uploads = Files.createDirectories(tmp.resolve("uploads"));
            return new Store(jdbc, new FileStorageService(jdbc, config), uploads);
        }

        FileStorageService.Stored put(String user, String name, String type, byte[] bytes, boolean pasted) throws Exception {
            return files.store(user, name, type, new ByteArrayInputStream(bytes), pasted);
        }

        Map<String, Object> row(String id) {
            return jdbc.queryForMap("SELECT * FROM file_attachments WHERE id = ?", id);
        }
    }

    static byte[] png(int width, int height) throws Exception {
        return TestImages.png(width, height);
    }

    @Test
    @DisplayName("a pasted image is marked shown with its copy recorded; the same bytes uploaded without the claim stay private")
    void onlyTheClaimAndAnImage(@TempDir Path tmp) throws Exception {
        var s = Store.in(tmp);
        byte[] bytes = png(1280, 720);
        var pasted = s.put("u1", "pasted-image-142233.png", "image/png", bytes, true);
        assertTrue(pasted.shownToCloud());
        var row = s.row(pasted.id());
        assertEquals(1, ((Number) row.get("shown_to_cloud")).intValue());
        assertNull(row.get("cloud_copy"), "within the tier the stored file is the copy");
        assertEquals("image/png", row.get("cloud_media_type"));
        assertEquals(1280, ((Number) row.get("cloud_width")).intValue());
        assertEquals(720, ((Number) row.get("cloud_height")).intValue());
        assertEquals((long) bytes.length, ((Number) row.get("cloud_bytes")).longValue());
        assertEquals(CloudImageCopy.sha256(bytes), row.get("cloud_sha256"));
        assertNotNull(FileStorageService.pastedImage(row));

        var attached = s.put("u1", "photo.png", "image/png", bytes, false);
        assertFalse(attached.shownToCloud());
        assertEquals(0, ((Number) s.row(attached.id()).get("shown_to_cloud")).intValue(), "attached with 📎: private");
        assertNull(FileStorageService.pastedImage(s.row(attached.id())));
        assertNull(s.files().copyOf("u1", attached.id()).copy());
        assertTrue(s.files().copyOf("u1", attached.id()).withheld().contains("private"));
        // Mutation: mark on the claim alone, or on an image alone -> one of the two is shown.
    }

    @Test
    @DisplayName("a text file claimed as a pasted image stays private, and the page is told why")
    void aClaimedTextFileStaysPrivate(@TempDir Path tmp) throws Exception {
        var s = Store.in(tmp);
        var stored = s.put("u1", "image.png", "image/png",
                "date,amount,counterparty\n2026-09-01,-1200,rent\n".getBytes(StandardCharsets.UTF_8), true);
        assertFalse(stored.shownToCloud());
        assertNotNull(stored.privateBecause());
        assertEquals(0, ((Number) s.row(stored.id()).get("shown_to_cloud")).intValue());
        assertNull(s.row(stored.id()).get("cloud_sha256"));
        assertNull(s.files().copyOf("u1", stored.id()).copy());
        // Mutation: trust the type the browser sent -> its text goes to the cloud as an "image".
    }

    @Test
    @DisplayName("the gateway's lookup: the copy of this user's pasted image, the bytes recorded at upload -- no other user's, nothing changed since")
    void theLookupIsTiedToTheFile(@TempDir Path tmp) throws Exception {
        var s = Store.in(tmp);
        var big = s.put("u1", "pasted-image-090000.png", "image/png", png(3840, 2160), true);
        var row = s.row(big.id());
        String sidecar = (String) row.get("cloud_copy");
        assertNotNull(sidecar, "scaled: a copy beside the upload");
        assertTrue(Files.isRegularFile(s.uploads().resolve(sidecar)));
        var found = s.files().copyOf("u1", big.id());
        assertNotNull(found.copy(), found.withheld());
        assertEquals(2576, found.copy().width());
        assertEquals(CloudImageCopy.sha256(found.copy().bytes()), found.copy().sha256());
        assertArrayEquals(Files.readAllBytes(s.uploads().resolve(sidecar)), found.copy().bytes());
        assertTrue(Files.size(s.files().getFilePath(big.id())) > 0, "the pasted file itself is untouched, for skills");
        assertNotEquals(CloudImageCopy.sha256(Files.readAllBytes(s.files().getFilePath(big.id()))), found.copy().sha256());

        assertNull(s.files().copyOf("u2", big.id()).copy(), "another user's file is never his");
        assertTrue(s.files().copyOf("u2", big.id()).withheld().contains("not this user's"));
        assertNull(s.files().copyOf("u1", "no-such-id").copy());

        // Changed on disk since -- by a skill that ran unjailed, say: not sent.
        Files.write(s.uploads().resolve(sidecar), png(100, 100));
        var changed = s.files().copyOf("u1", big.id());
        assertNull(changed.copy());
        assertTrue(changed.withheld().contains("not the bytes recorded"), changed.withheld());
        // A link where the copy was is not followed.
        Files.delete(s.uploads().resolve(sidecar));
        Files.createSymbolicLink(s.uploads().resolve(sidecar), s.uploads().resolve(sidecar + ".elsewhere"));
        assertNull(s.files().copyOf("u1", big.id()).copy());
        // Mutation: drop the hash check -> the changed bytes go; drop the user check -> u2's goes.
    }

    @Test
    @DisplayName("deleting the file deletes its copy too")
    void deleteTakesTheCopy(@TempDir Path tmp) throws Exception {
        var s = Store.in(tmp);
        var big = s.put("u1", "pasted-image-090001.png", "image/png", png(3000, 3000), true);
        Path copy = s.uploads().resolve((String) s.row(big.id()).get("cloud_copy"));
        assertTrue(Files.exists(copy));
        assertTrue(s.files().delete("u1", big.id()));
        assertFalse(Files.exists(copy), "no copy outlives its file");
        assertNull(s.files().copyOf("u1", big.id()).copy());
        // Mutation: leave the sidecar -> it stays in the uploads directory for good.
    }
}
