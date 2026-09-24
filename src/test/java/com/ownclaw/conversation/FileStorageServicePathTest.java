package com.ownclaw.conversation;

import com.ownclaw.config.OwnClawConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A file's path is built from the stored_name in its row, and the row is trusted on the way out:
 * by a skill handed the file, by the download endpoint, by delete. A row edited to say
 * {@code ../ownclaw.db} must give no path at all, rather than the database.
 */
class FileStorageServicePathTest {

    @TempDir Path tmp;

    private JdbcTemplate jdbc;
    private FileStorageService files;
    private Path db;
    private Path uploads;
    private Path env;

    @BeforeEach
    void setUp() throws Exception {
        Path data = Files.createDirectories(tmp.resolve("data"));
        db = data.resolve("ownclaw.db");
        jdbc = MigratedDatabase.at(db);
        env = Files.writeString(tmp.resolve(".env"), "OWNCLAW_OPS_TOKEN=canary-env-secret\n");
        var config = new OwnClawConfig();
        config.getDatabase().setPath(db.toString());
        uploads = Files.createDirectories(FileStorageService.uploadsDirFor(config));
        files = new FileStorageService(jdbc, config);
    }

    /** A row as a database edit would leave it: any stored_name at all. */
    private String row(String storedName) {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO file_attachments (id, user_id, original_name, stored_name, content_type, size_bytes)"
                + " VALUES (?, 'u1', 'statement.pdf', ?, 'text/plain', 1)", id, storedName);
        return id;
    }

    @ParameterizedTest
    @ValueSource(strings = {"../ownclaw.db", "../../.env", "a/b", "..", ".", "", "../uploads/x",
            "./x", "/etc/passwd"})
    @DisplayName("a stored_name that is not a plain name inside uploads gives no path")
    void tamperedNamesGiveNoPath(String storedName) {
        String id = row(storedName);
        assertNull(files.getFilePath(id), "a path for stored_name '" + storedName + "'");
        assertNull(files.readAsText(id));
    }

    @Test
    @DisplayName("the database's and .env's own absolute paths give no path either")
    void absolutePathsGiveNoPath() {
        assertNull(files.getFilePath(row(db.toAbsolutePath().toString())));
        assertNull(files.getFilePath(row(env.toAbsolutePath().toString())));
        assertNull(files.readAsText(row(env.toAbsolutePath().toString())));
    }

    @Test
    @DisplayName("a stored file resolves directly inside uploads, to the file store() wrote")
    void aStoredFileResolvesInsideUploads() throws Exception {
        String id = files.store("u1", "statement.txt", "text/plain",
                new ByteArrayInputStream("the statement".getBytes(StandardCharsets.UTF_8)));
        Path path = files.getFilePath(id);
        assertNotNull(path);
        assertEquals(uploads.toAbsolutePath().normalize(), path.getParent());
        assertEquals("the statement", files.readAsText(id));
    }

    @Test
    @DisplayName("on Linux a backslash is part of a name, not a separator: such an upload still resolves")
    void aBackslashIsJustACharacterOnLinux() throws Exception {
        assumeTrue(File.separatorChar == '/', "only on a platform where '\\' is not a separator");
        String id = files.store("u1", "C:\\Users\\me\\statement.txt", "text/plain",
                new ByteArrayInputStream("the statement".getBytes(StandardCharsets.UTF_8)));
        assertNotNull(files.getFilePath(id));
        assertEquals("the statement", files.readAsText(id));
    }

    @Test
    @DisplayName("deleting a tampered row removes the row, never the file it points at")
    void deleteNeverFollowsATamperedName() throws Exception {
        String id = row("../ownclaw.db");
        assertTrue(files.delete("u1", id));
        assertTrue(Files.exists(db), "the database was deleted through a tampered row");
        assertNull(files.getFileInfo(id), "the row itself is gone, read back from the same database");

        String envRow = row("../../.env");
        assertTrue(files.delete("u1", envRow));
        assertEquals("OWNCLAW_OPS_TOKEN=canary-env-secret\n", Files.readString(env));
    }
}
