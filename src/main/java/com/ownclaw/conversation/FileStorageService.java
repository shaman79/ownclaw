package com.ownclaw.conversation;

import com.ownclaw.config.OwnClawConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
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
        // Store uploads next to the database: ./data/uploads/
        this.uploadsDir = Path.of(config.getDatabase().getPath()).getParent().resolve("uploads");
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
        String storedName = id + "_" + safeName;

        Path dest = uploadsDir.resolve(storedName);
        long bytes = Files.copy(data, dest, StandardCopyOption.REPLACE_EXISTING);

        jdbc.update("""
            INSERT INTO file_attachments (id, user_id, original_name, stored_name, content_type, size_bytes)
            VALUES (?, ?, ?, ?, ?, ?)
            """, id, userId, safeName, storedName, contentType, bytes);

        log.info("Stored file: id={}, user={}, name={}, type={}, bytes={}", id, userId, safeName, contentType, bytes);
        return id;
    }

    /**
     * Link a file to a conversation message.
     */
    public void attachToMessage(String messageId, String fileId) {
        jdbc.update("INSERT OR IGNORE INTO message_attachments (message_id, file_id) VALUES (?, ?)",
                messageId, fileId);
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
     * Get the disk path for a stored file.
     */
    public Path getFilePath(String fileId) {
        List<String> names = jdbc.queryForList(
                "SELECT stored_name FROM file_attachments WHERE id = ?", String.class, fileId);
        if (names.isEmpty()) return null;
        return uploadsDir.resolve(names.getFirst());
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
     * List file attachment IDs for a specific conversation message.
     */
    public List<String> getMessageAttachments(String messageId) {
        return jdbc.queryForList(
                "SELECT file_id FROM message_attachments WHERE message_id = ?",
                String.class, messageId);
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

        // Remove from disk
        Path path = uploadsDir.resolve((String) info.get("stored_name"));
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Failed to delete file from disk: {}", path, e);
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
     * Read file content as text (for LLM context injection).
     * Returns null if file is too large (>100KB) or not readable.
     */
    public String readAsText(String fileId) {
        Path path = getFilePath(fileId);
        if (path == null || !Files.exists(path)) return null;
        try {
            long size = Files.size(path);
            if (size > 100 * 1024) return null; // skip files > 100KB
            return Files.readString(path);
        } catch (IOException e) {
            log.warn("Failed to read file as text: {}", path, e);
            return null;
        }
    }
}
