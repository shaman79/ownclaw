package com.ownclaw.interfaces.web;

import com.ownclaw.conversation.FileStorageService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * REST controller for file upload/delete.
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
     * With {@code pasted=true}: the page says these are images the owner pasted or dropped on the
     * chat box -- a screenshot, a copied picture, a photo copied in a file manager -- and each that
     * decodes as an image is shown to the cloud model ({@link FileStorageService#store(String, String,
     * String, java.io.InputStream, boolean)}). Every other file, and one sent so that is no image,
     * stays private. The page sends without it every file that is no image, and every file picked
     * with 📎, the private way to give one.
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
                // As it is kept: a file sent to be shown under the page's name, never its own.
                result.put("original_name", FileStorageService.nameKept(name, type, pastedImages));
                result.put("size_bytes", file.getSize());
                result.put("content_type", type);
                if (pastedImages) seen(result, stored);
                results.add(result);
            } catch (Exception e) {
                log.error("File upload failed: {}", e.getMessage(), e);
                return ResponseEntity.internalServerError().body(Map.of(
                        "error", "Upload failed: " + e.getMessage()));
            }
        }
        return ResponseEntity.ok(Map.of("files", results));
    }

    /** Who sees a file, as the page's chip says it: shown as a picture and at what size, readable, or private and why. */
    private static void seen(Map<String, Object> result, FileStorageService.Stored stored) {
        result.put("shown_to_cloud", stored.shownToCloud());
        result.put("cloud_visible", stored.cloudVisible());
        if (stored.copy() != null) {
            result.put("cloud_width", stored.copy().width());
            result.put("cloud_height", stored.copy().height());
            result.put("image_width", stored.copy().originalWidth());
            result.put("image_height", stored.copy().originalHeight());
            // What its file carries beside the picture, which the cloud model is given too.
            if (stored.copy().metadata() != null) result.put("metadata", stored.copy().metadata());
        } else if (!stored.shownToCloud() && !stored.cloudVisible()) {
            result.put("private_because", stored.privateBecause());
        }
    }

    /**
     * The owner's choice for a file of his, before he sends it, whether the cloud model may see it.
     * POST /api/files/{fileId}/cloud {"visible": true|false}: an image shown as a picture, any other
     * file readable (FileStorageService#setCloud); not visible, private. Answered as an upload is:
     * who sees it now. 404 for a file that is not his, 409 once it went with a message.
     */
    @PostMapping("/{fileId}/cloud")
    public ResponseEntity<?> cloud(HttpServletRequest request, @PathVariable String fileId,
                                   @RequestBody Map<String, Object> body) {
        String userId = (String) request.getAttribute("userId");
        if (!(body.get("visible") instanceof Boolean visible)) {
            return ResponseEntity.badRequest().body(Map.of("error", "visible must be true or false"));
        }
        try {
            var stored = fileStorage.setCloud(userId, fileId, visible);
            if (stored == null) return ResponseEntity.notFound().build();
            var result = new java.util.LinkedHashMap<String, Object>();
            result.put("id", fileId);
            seen(result, stored);
            return ResponseEntity.ok(result);
        } catch (FileStorageService.AlreadySent sent) {
            return ResponseEntity.status(409).body(Map.of("error", "Not changed: " + sent.getMessage()));
        } catch (java.io.IOException e) {
            log.error("Could not change who sees file {}: {}", fileId, e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", "Could not change it: " + e.getMessage()));
        }
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
