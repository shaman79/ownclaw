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

    /**
     * Maximum upload: 100 MB, the request body Cloudflare accepts in front of production -- a
     * larger upload never reaches this server at all. The container refuses a larger upload
     * before this controller runs, under spring.servlet.multipart in application.yaml, which
     * must equal it (MultipartLimitTest).
     */
    static final long MAX_FILE_SIZE = 100L * 1024 * 1024;

    private final FileStorageService fileStorage;

    public FileUploadController(FileStorageService fileStorage) {
        this.fileStorage = fileStorage;
    }

    /**
     * Upload one or more files.
     * POST /api/files/upload (multipart/form-data, field name "files")
     * <p>
     * With {@code pasted=true}: the page says these are images on the clipboard that it named
     * itself -- a screenshot pasted into the chat box -- and each that decodes as an image is shown
     * to the cloud model ({@link FileStorageService#store(String, String, String, java.io.InputStream,
     * boolean)}). Every other file, and one sent so that is no image, stays private. The page sends a
     * file copied in a file manager and pasted without it: that is a file, and private.
     *
     * @return list of uploaded file metadata (id, original_name, size_bytes, content_type) -- and,
     *         for a pasted one, whether it is shown to the cloud model: the copy's pixel size, or why
     *         it stays private
     */
    @PostMapping("/upload")
    public ResponseEntity<?> upload(HttpServletRequest request,
                                    @RequestParam("files") List<MultipartFile> files,
                                    @RequestParam(value = "pasted", required = false) String pasted) {
        String userId = (String) request.getAttribute("userId");
        boolean pastedImages = "true".equals(pasted);
        // One deadline for the checks of every image of this upload, its wait for its turn included:
        // the request is answered by then, whatever is queued ahead of it.
        long checkedBy = com.ownclaw.conversation.CloudImageCopy.deadline();

        var results = new java.util.ArrayList<Map<String, Object>>();
        for (MultipartFile file : files) {
            if (file.isEmpty()) continue;
            if (file.getSize() > MAX_FILE_SIZE) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "File too large: " + file.getOriginalFilename()
                                + " (" + file.getSize() + " bytes, max " + MAX_FILE_SIZE + ")"));
            }
            try {
                String name = file.getOriginalFilename() != null ? file.getOriginalFilename() : "upload";
                String type = file.getContentType() != null ? file.getContentType() : "application/octet-stream";
                var stored = fileStorage.store(userId, name, type, file.getInputStream(), pastedImages, checkedBy);
                var result = new java.util.LinkedHashMap<String, Object>();
                result.put("id", stored.id());
                result.put("original_name", name);
                result.put("size_bytes", file.getSize());
                result.put("content_type", type);
                if (pastedImages) {
                    result.put("shown_to_cloud", stored.shownToCloud());
                    if (stored.copy() != null) {
                        result.put("cloud_width", stored.copy().width());
                        result.put("cloud_height", stored.copy().height());
                        result.put("image_width", stored.copy().originalWidth());
                        result.put("image_height", stored.copy().originalHeight());
                        // What its file carries beside the picture: skills are handed that file, so what
                        // they read from it is private, as for any file.
                        result.put("results_private", stored.copy().metadata() != null);
                        if (stored.copy().metadata() != null) result.put("metadata", stored.copy().metadata());
                    } else {
                        result.put("private_because", stored.privateBecause());
                    }
                }
                results.add(result);
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
