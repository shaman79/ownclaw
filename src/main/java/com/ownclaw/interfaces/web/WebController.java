package com.ownclaw.interfaces.web;

import com.ownclaw.core.TaskQueue;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the WebUI static page and provides basic REST endpoints.
 */
@RestController
public class WebController {

    private final TaskQueue taskQueue;

    public WebController(TaskQueue taskQueue) {
        this.taskQueue = taskQueue;
    }

    @GetMapping(value = "/", produces = MediaType.TEXT_HTML_VALUE)
    public Resource index() {
        return new ClassPathResource("static/index.html");
    }

    @GetMapping("/api/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("ok");
    }

    /**
     * Returns whether the agent is currently processing a task.
     * Used by the deploy script to avoid restarting during active work.
     * Unauthenticated (same as /api/health) — only exposed on localhost.
     */
    @GetMapping("/api/health/busy")
    public ResponseEntity<String> busy() {
        return ResponseEntity.ok(taskQueue.isBusy() ? "busy" : "idle");
    }
}
