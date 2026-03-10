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
     * Get a single task with its recent execution runs.
     * GET /api/scheduled-tasks/{taskId}
     */
    @GetMapping("/{taskId}")
    public ResponseEntity<?> getTask(HttpServletRequest request,
                                     @PathVariable long taskId) {
        String userId = (String) request.getAttribute("userId");
        var task = scheduledTaskService.getTask(userId, taskId);
        if (task.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        List<Map<String, Object>> runs = scheduledTaskService.getTaskRuns(userId, taskId, 20);
        return ResponseEntity.ok(Map.of("task", task.get(), "runs", runs));
    }

    /**
     * Get paginated execution history for all tasks.
     * GET /api/scheduled-tasks/runs?limit=50&offset=0
     */
    @GetMapping("/runs")
    public ResponseEntity<?> getRunHistory(HttpServletRequest request,
                                           @RequestParam(defaultValue = "50") int limit,
                                           @RequestParam(defaultValue = "0") int offset) {
        String userId = (String) request.getAttribute("userId");
        List<Map<String, Object>> runs = scheduledTaskService.getRunHistory(userId,
                Math.min(limit, 200), Math.max(offset, 0));
        Map<String, Object> stats = scheduledTaskService.getTaskStats(userId);
        return ResponseEntity.ok(Map.of("runs", runs, "stats", stats));
    }
}
