package com.ownclaw.interfaces.web;

import com.ownclaw.core.ScheduledTaskService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST controller for scheduled task execution history.
 * Provides endpoints for the pinned "Scheduled Tasks" view in the chat sidebar.
 */
@RestController
@RequestMapping("/api/scheduled-tasks")
public class ScheduledTaskController {

    private final ScheduledTaskService scheduledTaskService;

    public ScheduledTaskController(ScheduledTaskService scheduledTaskService) {
        this.scheduledTaskService = scheduledTaskService;
    }

    /**
     * List all scheduled tasks for the current user.
     * GET /api/scheduled-tasks
     */
    @GetMapping
    public ResponseEntity<?> listTasks(HttpServletRequest request,
                                       @RequestParam(required = false) String status) {
        String userId = (String) request.getAttribute("userId");
        List<Map<String, Object>> tasks = scheduledTaskService.listTasks(userId, status);
        return ResponseEntity.ok(Map.of("tasks", tasks));
    }

    /**
     * Get a single task with its execution runs, newest first: every run, or a page of them.
     * GET /api/scheduled-tasks/{taskId}?limit=20&offset=0 -- without a limit, every run from the
     * offset on. It used to return the 20 newest and no way to reach the rest.
     */
    @GetMapping("/{taskId}")
    public ResponseEntity<?> getTask(HttpServletRequest request,
                                     @PathVariable long taskId,
                                     @RequestParam(required = false) Long limit,
                                     @RequestParam(defaultValue = "0") long offset) {
        String userId = (String) request.getAttribute("userId");
        var task = scheduledTaskService.getTask(userId, taskId);
        if (task.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        List<Map<String, Object>> runs = scheduledTaskService.getTaskRuns(userId, taskId,
                limit == null ? -1 : limit, Math.max(offset, 0));
        return ResponseEntity.ok(Map.of("task", task.get(), "runs", runs));
    }

    /**
     * Get execution history for all tasks, newest first: every run, or a page of them.
     * GET /api/scheduled-tasks/runs?limit=50&offset=0 -- without a limit, every run from the
     * offset on. A limit is not lowered: asked for 1,000, it used to answer 200.
     */
    @GetMapping("/runs")
    public ResponseEntity<?> getRunHistory(HttpServletRequest request,
                                           @RequestParam(required = false) Long limit,
                                           @RequestParam(defaultValue = "0") long offset) {
        String userId = (String) request.getAttribute("userId");
        List<Map<String, Object>> runs = scheduledTaskService.getRunHistory(userId,
                limit == null ? -1 : limit, Math.max(offset, 0));
        Map<String, Object> stats = scheduledTaskService.getTaskStats(userId);
        return ResponseEntity.ok(Map.of("runs", runs, "stats", stats));
    }

    /**
     * Update a scheduled task.
     * PUT /api/scheduled-tasks/{taskId}
     * Body: {"description": "...", "cronExpression": "..."}
     */
    @PutMapping("/{taskId}")
    public ResponseEntity<?> updateTask(HttpServletRequest request,
                                        @PathVariable long taskId,
                                        @RequestBody Map<String, String> body) {
        String userId = (String) request.getAttribute("userId");
        try {
            boolean ok = scheduledTaskService.updateTask(userId, taskId,
                    body.get("description"), body.get("cronExpression"));
            if (!ok) return ResponseEntity.notFound().build();
            var task = scheduledTaskService.getTask(userId, taskId);
            return ResponseEntity.ok(Map.of("updated", true, "task", task.orElse(Map.of())));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Delete a scheduled task permanently.
     * DELETE /api/scheduled-tasks/{taskId}
     */
    @DeleteMapping("/{taskId}")
    public ResponseEntity<?> deleteTask(HttpServletRequest request,
                                        @PathVariable long taskId) {
        String userId = (String) request.getAttribute("userId");
        boolean ok = scheduledTaskService.deleteTask(userId, taskId);
        if (!ok) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(Map.of("deleted", true, "taskId", taskId));
    }

    /**
     * Pause a scheduled task.
     * PUT /api/scheduled-tasks/{taskId}/pause
     */
    @PutMapping("/{taskId}/pause")
    public ResponseEntity<?> pauseTask(HttpServletRequest request,
                                       @PathVariable long taskId) {
        String userId = (String) request.getAttribute("userId");
        boolean ok = scheduledTaskService.pause(userId, taskId);
        if (!ok) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(Map.of("paused", true, "taskId", taskId));
    }

    /**
     * Resume a paused task.
     * PUT /api/scheduled-tasks/{taskId}/resume
     */
    @PutMapping("/{taskId}/resume")
    public ResponseEntity<?> resumeTask(HttpServletRequest request,
                                        @PathVariable long taskId) {
        String userId = (String) request.getAttribute("userId");
        boolean ok = scheduledTaskService.resume(userId, taskId);
        if (!ok) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(Map.of("resumed", true, "taskId", taskId));
    }

    /**
     * Cancel a scheduled task (soft delete — keeps history).
     * PUT /api/scheduled-tasks/{taskId}/cancel
     */
    @PutMapping("/{taskId}/cancel")
    public ResponseEntity<?> cancelTask(HttpServletRequest request,
                                        @PathVariable long taskId) {
        String userId = (String) request.getAttribute("userId");
        boolean ok = scheduledTaskService.cancel(userId, taskId);
        if (!ok) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(Map.of("cancelled", true, "taskId", taskId));
    }
}
