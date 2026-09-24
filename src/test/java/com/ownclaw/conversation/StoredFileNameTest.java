package com.ownclaw.conversation;

import com.ownclaw.config.OwnClawConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** A colon in a file name would split a container's "-v host:container:ro" into too many parts. */
class StoredFileNameTest {

    @Test
    @DisplayName("no colon on disk; the name the owner gave is kept")
    void noColonOnDisk(@TempDir Path tmp) throws Exception {
        var jdbc = MigratedDatabase.at(tmp.resolve("t.db"));
        var config = new OwnClawConfig();
        config.getDatabase().setPath(tmp.resolve("t.db").toString());
        Files.createDirectories(tmp.resolve("uploads"));
        var files = new FileStorageService(jdbc, config);
        String id = files.store("u1", "report 10:30.pdf", "application/pdf",
                new ByteArrayInputStream("%PDF".getBytes(StandardCharsets.UTF_8)));
        assertFalse(files.getFilePath(id).getFileName().toString().contains(":"));
        assertTrue(Files.exists(files.getFilePath(id)));
        assertEquals("report 10:30.pdf", files.getFileInfo(id).get("original_name"));
    }
}
