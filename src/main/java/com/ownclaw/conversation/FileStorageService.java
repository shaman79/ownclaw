package com.ownclaw.conversation;

import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.sandbox.SkillJail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Manages file uploads: stores on disk under {@code data/uploads/},
 * tracks metadata in the {@code file_attachments} table,
 * and links files to conversation messages via {@code message_attachments}.
 */
@Service
public class FileStorageService {

    private static final Logger log = LoggerFactory.getLogger(FileStorageService.class);

    private final JdbcTemplate jdbc;
    private final Path uploadsDir;

    public FileStorageService(JdbcTemplate jdbc, OwnClawConfig config) {
        this.jdbc = jdbc;
        this.uploadsDir = uploadsDirFor(config);
    }

    /**
     * Where uploads live: next to the database, in {@code data/uploads/}. One formula, so the
     * directory files are stored in and the one the skill jail hides are the same directory.
     */
    public static Path uploadsDirFor(OwnClawConfig config) {
        return uploadsDirFor(Path.of(config.getDatabase().getPath()));
    }

    /** {@link #uploadsDirFor(OwnClawConfig)} from the database path itself. */
    public static Path uploadsDirFor(Path databasePath) {
        return databasePath.getParent().resolve("uploads");
    }

    @PostConstruct
    void ensureDirectory() throws IOException {
        Files.createDirectories(uploadsDir);
        log.info("File uploads directory: {}", uploadsDir.toAbsolutePath());
    }

    /**
     * Store an uploaded file on disk and record metadata in the DB.
     *
     * @return the file attachment ID
     */
    public String store(String userId, String originalName, String contentType, InputStream data) throws IOException {
        String id = UUID.randomUUID().toString();

        // Sanitize the original name: keep only the last segment, strip path separators
        String safeName = Path.of(originalName).getFileName().toString();
        if (safeName.isBlank()) safeName = "upload";

        // Generate stored name to avoid collisions: id_originalname
        // No ':' on disk: a container gets the file as "-v host:container:ro", and a colon in the
        // name splits that into too many fields. The name the owner gave is kept in the row.
        String storedName = id + "_" + safeName.replace(':', '_');

        Path dest = uploadsDir.resolve(storedName);
        long bytes = Files.copy(data, dest, StandardCopyOption.REPLACE_EXISTING);

        jdbc.update("""
            INSERT INTO file_attachments (id, user_id, original_name, stored_name, content_type, size_bytes)
            VALUES (?, ?, ?, ?, ?, ?)
            """, id, userId, safeName, storedName, contentType, bytes);

        // Not the name: a statement's file name can carry its account number, and the log is
        // read back through the ops API.
        log.info("Stored file: id={}, user={}, type={}, bytes={}", id, userId, contentType, bytes);
        return id;
    }

    /**
     * Get metadata for a file by ID. Returns null if not found.
     */
    public Map<String, Object> getFileInfo(String fileId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM file_attachments WHERE id = ?", fileId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /**
     * Get the disk path for a stored file, or null when there is none -- when the row does not
     * name a file directly inside the uploads directory, or what is at that name is not a
     * regular file.
     * <p>
     * A link at the name -- left by a skill that ran unjailed, say -- is not followed: the path
     * is read into a model's context and served for download, and would hand over whatever the
     * link leads to. Nor is a file with a second name, a hard link: {@link #store} writes each
     * upload new, so another name was made later, and to .env or the database it is that file.
     * The count is read where the file system keeps one the JVM can read ({@link SkillJail#nlink}).
     * {@link #delete} takes the name as it is, so it still removes a link there.
     */
    public Path getFilePath(String fileId) {
        List<String> names = jdbc.queryForList(
                "SELECT stored_name FROM file_attachments WHERE id = ?", String.class, fileId);
        if (names.isEmpty()) return null;
        Path path = storedPath(names.getFirst());
        if (path == null || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return null;
        try {
            return SkillJail.nlink(path) == 1 ? path : null;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * The path of a stored name, or null unless it is a plain name that lands directly in the
     * uploads directory.
     * <p>
     * {@link #store} only ever writes such names, but the row is read back from the database and
     * trusted: a stored_name of {@code ../ownclaw.db} or {@code ../../.env}, put there by an edit
     * of the database, would hand that file to a skill as its attachment -- read-only inside the
     * jail, but read -- or serve it from the download endpoint. So the name is checked here, on
     * the way out, where every caller gets it.
     */
    private Path storedPath(String storedName) {
        // '/' everywhere, and the platform's own separator ('\\' on Windows; on Linux a
        // backslash is an ordinary character a browser can send in a file name).
        if (storedName == null || storedName.isEmpty() || storedName.equals(".") || storedName.equals("..")
                || storedName.indexOf('/') >= 0 || storedName.indexOf(java.io.File.separatorChar) >= 0) {
            log.warn("Refusing a stored file name that is not a plain name inside the uploads directory");
            return null;
        }
        Path dir = uploadsDir.toAbsolutePath().normalize();
        Path path;
        try {
            path = dir.resolve(storedName).normalize();
        } catch (java.nio.file.InvalidPathException e) {
            log.warn("Refusing a stored file name the file system cannot represent");
            return null;
        }
        if (!dir.equals(path.getParent())) {
            log.warn("Refusing a stored file name that resolves outside the uploads directory");
            return null;
        }
        return path;
    }

    /**
     * Get the disk path from stored_name directly (for container mounting).
     */
    public Path getUploadsDir() {
        return uploadsDir;
    }

    /**
     * List all files uploaded by a user.
     */
    public List<Map<String, Object>> listUserFiles(String userId) {
        return jdbc.queryForList("""
            SELECT id, original_name, content_type, size_bytes, uploaded_at
            FROM file_attachments WHERE user_id = ? ORDER BY uploaded_at DESC
            """, userId);
    }

    /**
     * Get full info for all files attached to a message.
     */
    public List<Map<String, Object>> getMessageAttachmentDetails(String messageId) {
        return jdbc.queryForList("""
            SELECT f.id, f.original_name, f.content_type, f.size_bytes, f.uploaded_at
            FROM file_attachments f
            JOIN message_attachments ma ON ma.file_id = f.id
            WHERE ma.message_id = ?
            """, messageId);
    }

    /**
     * Delete a file (both disk and DB). Only the owning user may delete.
     */
    public boolean delete(String userId, String fileId) {
        Map<String, Object> info = getFileInfo(fileId);
        if (info == null || !userId.equals(info.get("user_id"))) return false;

        // Remove from disk -- only a name that is really inside uploads/, or a tampered row
        // would delete whatever it points at.
        Path path = storedPath((String) info.get("stored_name"));
        if (path != null) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException e) {
                log.warn("Failed to delete file from disk: {}", path, e);
            }
        }

        // Remove DB records
        jdbc.update("DELETE FROM message_attachments WHERE file_id = ?", fileId);
        jdbc.update("DELETE FROM file_attachments WHERE id = ? AND user_id = ?", fileId, userId);
        log.info("Deleted file: id={}, user={}", fileId, userId);
        return true;
    }

    /**
     * Check if a file is a text-based type that can be included inline in LLM context.
     */
    public boolean isTextContent(String contentType) {
        if (contentType == null) return false;
        return contentType.startsWith("text/")
                || contentType.equals("application/json")
                || contentType.equals("application/xml")
                || contentType.equals("application/javascript")
                || contentType.equals("application/x-yaml")
                || contentType.equals("application/yaml")
                || contentType.contains("+xml")
                || contentType.contains("+json");
    }

    /**
     * Read a file's content as text, whole. Returns null if there is no such file or it cannot
     * be read as UTF-8 text.
     * <p>
     * A file over 100 KB used to come back as null, so its text was never indexed for the privacy
     * canary and nothing of it could be recognised on its way out. The upload limit is the only
     * bound on its size.
     */
    public String readAsText(String fileId) {
        Path path = getFilePath(fileId);
        if (path == null || !Files.exists(path)) return null;
        try {
            return Files.readString(path);
        } catch (IOException e) {
            // The id, not the path: the stored name carries the name the owner gave the file.
            log.warn("Could not read file {} as text ({})", fileId, e.getClass().getSimpleName());
            return null;
        }
    }
}
