package com.ownclaw.interfaces.web;

import com.ownclaw.conversation.ConversationService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST controller for chat session management: create, list, switch, rename, delete, search.
 * All endpoints require JWT authentication (enforced by JwtAuthFilter).
 */
@RestController
@RequestMapping("/api/chats")
public class ChatHistoryController {

    private static final Logger log = LoggerFactory.getLogger(ChatHistoryController.class);

    private final ConversationService conversationService;

    public ChatHistoryController(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    /**
     * List all chat sessions for the current user.
     * GET /api/chats
     * Query param: archived=true to include archived sessions
     */
    @GetMapping
    public ResponseEntity<?> listSessions(HttpServletRequest request,
                                          @RequestParam(defaultValue = "false") boolean archived) {
        String userId = (String) request.getAttribute("userId");
        String activeSessionId = conversationService.getCurrentSession(userId);
        List<Map<String, Object>> sessions = conversationService.listSessions(userId, archived);
        return ResponseEntity.ok(Map.of(
                "sessions", sessions,
                "activeSessionId", activeSessionId
        ));
    }

    /**
     * Create a new chat session and switch to it.
     * POST /api/chats
     * Body: {"title": "optional title"}  (defaults to "New Chat")
     */
    @PostMapping
    public ResponseEntity<?> createSession(HttpServletRequest request,
                                           @RequestBody(required = false) Map<String, String> body) {
        String userId = (String) request.getAttribute("userId");
        String title = (body != null && body.containsKey("title")) ? body.get("title") : "New Chat";
        String sessionId = conversationService.createSession(userId, title);
        log.info("New chat session created: user={}, session={}, title={}", userId, sessionId, title);
        return ResponseEntity.ok(Map.of("sessionId", sessionId, "title", title));
    }

    /**
     * Switch to an existing chat session.
     * PUT /api/chats/{sessionId}/activate
     */
    @PutMapping("/{sessionId}/activate")
    public ResponseEntity<?> switchSession(HttpServletRequest request,
                                           @PathVariable String sessionId) {
        String userId = (String) request.getAttribute("userId");
        conversationService.setActiveSession(userId, sessionId);
        return ResponseEntity.ok(Map.of("sessionId", sessionId));
    }

    /**
     * Get messages for a specific session.
     * GET /api/chats/{sessionId}/messages
     */
    @GetMapping("/{sessionId}/messages")
    public ResponseEntity<?> getMessages(HttpServletRequest request,
                                         @PathVariable String sessionId) {
        String userId = (String) request.getAttribute("userId");
        List<Map<String, Object>> messages = conversationService.getSessionMessages(userId, sessionId);
        return ResponseEntity.ok(Map.of("messages", messages, "sessionId", sessionId));
    }

    /**
     * Rename a chat session.
     * PUT /api/chats/{sessionId}
     * Body: {"title": "new title"}
     */
    @PutMapping("/{sessionId}")
    public ResponseEntity<?> renameSession(HttpServletRequest request,
                                           @PathVariable String sessionId,
                                           @RequestBody Map<String, String> body) {
        String userId = (String) request.getAttribute("userId");
        String title = body.get("title");
        if (title == null || title.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Title is required"));
        }
        conversationService.renameSession(userId, sessionId, title.trim());
        return ResponseEntity.ok(Map.of("sessionId", sessionId, "title", title.trim()));
    }

    /**
     * Archive a chat session (soft delete).
     * PUT /api/chats/{sessionId}/archive
     */
    @PutMapping("/{sessionId}/archive")
    public ResponseEntity<?> archiveSession(HttpServletRequest request,
                                            @PathVariable String sessionId) {
        String userId = (String) request.getAttribute("userId");
        conversationService.archiveSession(userId, sessionId);
        return ResponseEntity.ok(Map.of("archived", true, "sessionId", sessionId));
    }

    /**
     * Permanently delete a chat session.
     * DELETE /api/chats/{sessionId}
     * Returns the updated session list and active session so the frontend can refresh in one call.
     */
    @DeleteMapping("/{sessionId}")
    public ResponseEntity<?> deleteSession(HttpServletRequest request,
                                           @PathVariable String sessionId) {
        String userId = (String) request.getAttribute("userId");
        conversationService.deleteSession(userId, sessionId);
        log.info("Chat session deleted: user={}, session={}", userId, sessionId);

        // Return updated state so frontend can refresh sidebar without a second call
        String activeSessionId = conversationService.getCurrentSession(userId);
        List<Map<String, Object>> sessions = conversationService.listSessions(userId, false);
        return ResponseEntity.ok(Map.of(
                "deleted", true,
                "sessionId", sessionId,
                "activeSessionId", activeSessionId,
                "sessions", sessions
        ));
    }

    /**
     * Search across all sessions.
     * GET /api/chats/search?q=search+term
     */
    @GetMapping("/search")
    public ResponseEntity<?> search(HttpServletRequest request,
                                    @RequestParam("q") String query) {
        String userId = (String) request.getAttribute("userId");
        if (query == null || query.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Search query is required"));
        }
        List<Map<String, Object>> results = conversationService.searchMessages(userId, query.trim());
        return ResponseEntity.ok(Map.of("results", results, "query", query.trim()));
    }
}
