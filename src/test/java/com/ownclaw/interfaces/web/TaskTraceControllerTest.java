package com.ownclaw.interfaces.web;

import com.ownclaw.observability.EventLogService;
import com.ownclaw.observability.TaskTraceService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The endpoint's three answers. Called directly: there is no web test harness on the classpath. */
class TaskTraceControllerTest {

    private static final TaskTraceController CONTROLLER = new TaskTraceController(new TaskTraceService(
            new EventLogService(null) {
                @Override
                public List<Map<String, Object>> taskEvents(String userId, String taskId) {
                    if (!"u1".equals(userId) || !"abcd1234".equals(taskId)) return List.of();
                    return List.of(Map.of("id", 1L, "timestamp", "2026-09-24 05:00:00",
                            "event_type", "step", "summary", "s",
                            "details", "{\"step\":1,\"tool\":\"x\",\"success\":true}"));
                }
            }));

    @Test
    @DisplayName("a malformed task id is a 400, before anything is read")
    void badIds() {
        for (String id : List.of("ABCDEFGH", "../x", "abc", "abcd12345", "abcd123g")) {
            assertEquals(400, CONTROLLER.trace("u1", id).getStatusCode().value(), id);
        }
    }

    @Test
    @DisplayName("a task that does not exist and a task that is not yours look the same: 404")
    void notFound() {
        assertEquals(404, CONTROLLER.trace("u1", "00000000").getStatusCode().value());
        assertEquals(404, CONTROLLER.trace("u2", "abcd1234").getStatusCode().value());
    }

    @Test
    @DisplayName("your own task: 200, with its id")
    void ownTask() {
        var r = CONTROLLER.trace("u1", "abcd1234");
        assertEquals(200, r.getStatusCode().value());
        assertEquals("abcd1234", ((Map<?, ?>) r.getBody()).get("taskId"));
    }
}
