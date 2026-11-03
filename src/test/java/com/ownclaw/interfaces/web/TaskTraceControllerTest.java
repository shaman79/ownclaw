package com.ownclaw.interfaces.web;

import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.conversation.FileStorageService;
import com.ownclaw.observability.EventLogService;
import com.ownclaw.observability.TaskTraceService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The endpoint's three answers, and the names of files its 200 carries. Called directly: there is
 * no web test harness on the classpath.
 */
class TaskTraceControllerTest {

    /** An attachment's row, as registration writes it: the file by its id. */
    private static Map<String, Object> attachment(long id, String fileId) {
        return Map.of("id", id, "timestamp", "2026-09-24 05:00:00", "event_type", "attachment",
                "summary", "attachment PRIVATE", "details", "{\"artifact\":\"{{" + id + "}}\","
                        + "\"tool\":\"attachment\",\"fileId\":\"" + fileId + "\",\"label\":\"PRIVATE\",\"chars\":0}");
    }

    private static final TaskTraceController CONTROLLER = new TaskTraceController(new TaskTraceService(
            new EventLogService(null) {
                @Override
                public List<Map<String, Object>> taskEvents(String userId, String taskId) {
                    if (!"u1".equals(userId)) return List.of();
                    return switch (taskId) {
                        case "abcd1234" -> List.of(Map.of("id", 1L, "timestamp", "2026-09-24 05:00:00",
                                "event_type", "step", "summary", "s",
                                "details", "{\"step\":1,\"tool\":\"x\",\"success\":true}"));
                        case "f11e5000" -> List.of(attachment(1, "f1"), attachment(2, "f2"), attachment(3, "f3"));
                        default -> List.of();
                    };
                }
            }),
            new FileStorageService(null, new OwnClawConfig()) {
                @Override
                public Map<String, Object> getFileInfo(String fileId) {
                    return switch (fileId) {
                        case "f1" -> Map.of("id", "f1", "user_id", "u1", "original_name", "statement 2045-7781.pdf");
                        case "f2" -> Map.of("id", "f2", "user_id", "u2", "original_name", "not yours.pdf");
                        default -> null;                                       // deleted since
                    };
                }
            });

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

    @Test
    @DisplayName("your page names your file, looked up by its id; a file since deleted, or not yours, is not named")
    void yourFileIsNamed() {
        var r = CONTROLLER.trace("u1", "f11e5000");
        assertEquals(200, r.getStatusCode().value());
        @SuppressWarnings("unchecked")
        var files = (List<Map<String, Object>>) ((Map<?, ?>) r.getBody()).get("artifacts");
        assertEquals(List.of("f1", "f2", "f3"), files.stream().map(a -> a.get("fileId")).toList());
        assertEquals("statement 2045-7781.pdf", files.get(0).get("name"));
        assertNull(files.get(1).get("name"), "another account's file");
        assertNull(files.get(2).get("name"), "a file since deleted");
        // Mutations: no lookup -> "your file" and no name; no owner check -> another account's
        // file named on this page.
    }
}
