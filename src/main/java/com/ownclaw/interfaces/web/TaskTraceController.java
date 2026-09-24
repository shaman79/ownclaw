package com.ownclaw.interfaces.web;

import com.ownclaw.observability.TaskTraceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * GET /api/tasks/{taskId} — what one of your tasks did: its steps, which model ran each, what it
 * cost, and what went to the cloud model.
 * <p>
 * Behind the JWT filter like every /api path, and scoped to the caller: the user id comes from
 * the authenticated request, never from a parameter, and a task that is not yours answers 404 —
 * the same as one that does not exist, so the endpoint cannot be used to probe for task ids.
 */
@RestController
@RequestMapping("/api/tasks")
public class TaskTraceController {

    private static final Pattern TASK_ID = Pattern.compile("[0-9a-f]{8}");

    private final TaskTraceService traces;

    public TaskTraceController(TaskTraceService traces) {
        this.traces = traces;
    }

    @GetMapping("/{taskId}")
    public ResponseEntity<?> trace(@RequestAttribute("userId") String userId,
                                   @PathVariable("taskId") String taskId) {
        if (taskId == null || !TASK_ID.matcher(taskId).matches()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Bad task id"));
        }
        return traces.trace(userId, taskId)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(404).body(Map.of("error", "No such task")));
    }
}
