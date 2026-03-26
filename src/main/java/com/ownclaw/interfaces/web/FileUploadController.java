package com.ownclaw.interfaces.web;

import com.ownclaw.conversation.FileStorageService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * REST controller for file upload/download/list/delete.
 * All endpoints require JWT authentication (enforced by JwtAuthFilter).
 */
@RestController
@RequestMapping("/api/files")
public class FileUploadController {

    private static final Logger log = LoggerFactory.getLogger(FileUploadController.class);

    /** Maximum file size: 50 MB */
    private static final long MAX_FILE_SIZE = 50 * 1024 * 1024;

    private final FileStorageService fileStorage;

    public FileUploadController(FileStorageService fileStorage) {
        this.fileStorage = fileStorage;
    }

    /**
     * Upload one or more files.
     * POST /api/files/upload (multipart/form-data, field name "files")
     *
     * @return list of uploaded file metadata (id, original_name, size_bytes, content_type)
     */
    @PostMapping("/upload")
    public ResponseEntity<?> upload(HttpServletRequest request,
                                    @RequestParam("files") List<MultipartFile> files) {
        String userId = (String) request.getAttribute("userId");

        var results = new java.util.ArrayList<Map<String, Object>>();
        for (MultipartFile file : files) {
            if (file.isEmpty()) continue;
            if (file.getSize() > MAX_FILE_SIZE) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "File too large: " + file.getOriginalFilename()
                                + " (" + file.getSize() + " bytes, max " + MAX_FILE_SIZE + ")"));
            }
            try {
                String id = fileStorage.store(userId,
                        file.getOriginalFilename() != null ? file.getOriginalFilename() : "upload",
                        file.getContentType() != null ? file.getContentType() : "application/octet-stream",
                        file.getInputStream());
                results.add(Map.of(
                        "id", id,
                        "original_name", file.getOriginalFilename() != null ? file.getOriginalFilename() : "upload",
                        "size_bytes", file.getSize(),
                        "content_type", file.getContentType() != null ? file.getContentType() : "application/octet-stream"
                ));
            } catch (Exception e) {
                log.error("File upload failed: {}", e.getMessage(), e);
                return ResponseEntity.internalServerError().body(Map.of(
                        "error", "Upload failed: " + e.getMessage()));
            }
        }
        return ResponseEntity.ok(Map.of("files", results));
    }

    /**
     * Download a file by ID.
     * GET /api/files/{fileId}
     */
    @GetMapping("/{fileId}")
    public ResponseEntity<Resource> download(HttpServletRequest request,
                                             @PathVariable String fileId) {
        String userId = (String) request.getAttribute("userId");
        Map<String, Object> info = fileStorage.getFileInfo(fileId);

        if (info == null || !userId.equals(info.get("user_id"))) {
            return ResponseEntity.notFound().build();
        }

        Path path = fileStorage.getFilePath(fileId);
        if (path == null || !path.toFile().exists()) {
            return ResponseEntity.notFound().build();
        }

        String contentType = (String) info.get("content_type");
        String originalName = (String) info.get("original_name");

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + originalName.replace("\"", "_") + "\"")
                .body(new FileSystemResource(path));
    }

    /**
     * List all files for the current user.
     * GET /api/files
     */
    @GetMapping
    public ResponseEntity<?> listFiles(HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        return ResponseEntity.ok(Map.of("files", fileStorage.listUserFiles(userId)));
    }

    /**
     * Delete a file by ID.
     * DELETE /api/files/{fileId}
     */
    @DeleteMapping("/{fileId}")
    public ResponseEntity<?> deleteFile(HttpServletRequest request,
                                        @PathVariable String fileId) {
        String userId = (String) request.getAttribute("userId");
        boolean deleted = fileStorage.delete(userId, fileId);
        if (!deleted) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("deleted", true));
    }
}
