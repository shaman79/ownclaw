package com.ownclaw.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The task page's data: built from a task's event rows, scoped to the user who asks.
 */
class TaskTraceServiceTest {

    private static final String AT = "2026-09-24 05:00:00";   // one second for every row: order is by id
    private final List<Map<String, Object>> rows = new ArrayList<>();

    private void row(String type, String details) { row(type, details, "s"); }

    private void row(String type, String details, String summary) {
        var r = new LinkedHashMap<String, Object>();
        r.put("id", (long) rows.size() + 1);
        r.put("timestamp", AT);
        r.put("event_type", type);
        r.put("severity", "info");
        r.put("summary", summary);
        r.put("details", details);
        rows.add(r);
    }

    private static String egress(String decision, int prompt, int completion, double cost, String refusal) {
        return "{\"decision\":\"" + decision + "\",\"purpose\":\"think\",\"provider\":\"anthropic\","
                + "\"model\":\"claude-opus-5\",\"bytesOut\":1200,\"toolCount\":1,\"promptTokens\":" + prompt
                + ",\"completionTokens\":" + completion + ",\"cacheWriteTokens\":0,\"cacheReadTokens\":0,"
                + "\"costUsd\":" + cost + ",\"scrubs\":0"
                + (refusal == null ? "" : ",\"refusal\":\"" + refusal + "\"")
                + ",\"parts\":[{\"i\":0,\"kind\":\"user\",\"chars\":500,\"sha256_16\":\"deadbeefdeadbeef\"},"
                + "{\"i\":1,\"kind\":\"tool:web_fetch\",\"chars\":300,\"sha256_16\":\"cafebabecafebabe\"},"
                + "{\"i\":2,\"kind\":\"schema:web_fetch\",\"chars\":100,\"sha256_16\":\"0123456789abcdef\"}]}";
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> t, String key) {
        return (List<Map<String, Object>>) t.get(key);
    }

    @Test
    @DisplayName("steps take the cloud calls before them; the tier, tokens and cost follow")
    void stepsCallsTierAndCost() {
        row("egress", egress("SENT", 1000, 50, 0.01, null));
        row("step", "{\"step\":1,\"tool\":\"web_fetch\",\"success\":true,\"durationMs\":10,"
                + "\"localTokens\":0,\"cloudTokens\":1050,\"artifact\":\"{{1}}\",\"label\":\"PUBLIC\",\"chars\":5310}");
        row("egress", egress("SENT", 2000, 100, 0.02, null));
        row("step", "{\"step\":2,\"tool\":\"delegate\",\"success\":true,\"durationMs\":288,"
                + "\"localTokens\":34848,\"cloudTokens\":3150,\"artifacts\":[{\"n\":2,\"tool\":\"daily_menu_fetcher\","
                + "\"label\":\"PUBLIC\",\"chars\":1866,\"why\":[],\"indexed\":true}]}");
        row("step", "{\"step\":3,\"tool\":\"delegate\",\"success\":true,\"durationMs\":90,"
                + "\"localTokens\":40000,\"cloudTokens\":3150}");
        row("egress", egress("SENT", 3000, 10, 0.03, null));
        row("task_completed", "{\"cloudTokens\":6160,\"localTokens\":40000,\"steps\":3,\"durationMs\":999,"
                + "\"reason\":\"COMPLETED\"}", "Fetch the menus");

        var t = TaskTraceService.build(rows);
        var steps = list(t, "steps");
        assertEquals("cloud", steps.get(0).get("tier"));
        assertEquals(1, steps.get(0).get("cloudCalls"));
        assertEquals(1050L, steps.get(0).get("cloudTokens"));
        assertEquals(0.01, (double) steps.get(0).get("costUsd"), 1e-9);
        assertEquals("local", steps.get(1).get("tier"), "a delegation ran on the local model");
        assertEquals("cloud", steps.get(1).get("decidedBy"), "and the cloud chose to delegate");
        assertEquals(34848L, steps.get(1).get("localTokens"));
        assertEquals(5152L, steps.get(2).get("localTokens"), "the difference, not the running total");
        assertEquals(0, steps.get(2).get("cloudCalls"));

        assertFalse(t.containsKey("answer"), "no invented 'final answer'");
        var totals = (Map<String, Object>) t.get("totals");
        assertEquals(0.06, (double) totals.get("costUsd"), 1e-9);
        assertEquals(Map.of("SENT", 3), totals.get("decisions"));
        assertEquals("Fetch the menus", t.get("request"));
        assertEquals("COMPLETED", ((Map<String, Object>) t.get("outcome")).get("reason"));

        var call = list(t, "calls").get(0);
        assertEquals(1, call.get("step"));
        assertEquals(Map.of("count", 1, "chars", 500L), call.get("messages"));
        assertEquals(Map.of("count", 1, "chars", 400L), call.get("tools"));
    }

    @Test
    @DisplayName("a step chosen with no cloud call but more local tokens was the local model's")
    void localFallback() {
        row("step", "{\"step\":1,\"tool\":\"web_fetch\",\"success\":true,\"localTokens\":500,\"cloudTokens\":0}");
        var s = list(TaskTraceService.build(rows), "steps").get(0);
        assertEquals("local", s.get("decidedBy"));
        assertEquals("local", s.get("tier"));
    }

    @Test
    @DisplayName("a task from before request recording: tier from the tokens, cost unknown")
    void preLedgerTask() {
        row("step", "{\"step\":1,\"tool\":\"web_fetch\",\"success\":true,\"localTokens\":0,\"cloudTokens\":9000}");
        var s = list(TaskTraceService.build(rows), "steps").get(0);
        assertEquals("cloud", s.get("tier"));
        assertNull(s.get("costUsd"), "unknown is not zero");
        assertNull(s.get("cloudCalls"), "no requests were recorded then: unknown, not 0");
        assertNull(s.get("reportedFailure"), "not recorded then");
        assertEquals(9000L, s.get("cloudTokens"));
    }

    @Test
    @DisplayName("the check line counts only later requests, and only for what it could look for")
    void canaryLines() {
        row("egress", egress("SENT", 1, 1, 0, null));            // before the file: not counted
        row("attachment", "{\"artifact\":\"{{1}}\",\"tool\":\"attachment:statement.csv\",\"label\":\"PRIVATE\","
                + "\"chars\":5000,\"indexed\":true,\"why\":[\"attachment\"]}");
        row("attachment", "{\"artifact\":\"{{2}}\",\"tool\":\"attachment:scan.pdf\",\"label\":\"PRIVATE\","
                + "\"chars\":0,\"indexed\":true,\"why\":[\"attachment\"]}");
        row("attachment", "{\"artifact\":\"{{3}}\",\"tool\":\"attachment:x.txt\",\"label\":\"PRIVATE\","
                + "\"chars\":900,\"indexed\":false,\"why\":[\"after private data in this delegation\"]}");
        for (int i = 0; i < 3; i++) row("egress", egress("SENT", 1, 1, 0, null));
        row("egress", egress("REFUSED", 0, 0, 0, "vault:SMTP_PASS survived scrubbing"));
        var t = TaskTraceService.build(rows);
        var arts = list(t, "artifacts");
        assertEquals(Map.of("checkedCalls", 3, "hits", 0, "leaked", 0, "unchecked", 0), arts.get(0).get("canary"),
                "a vault refusal happens before the canary runs, so it checked nothing");
        assertNull(arts.get(1).get("canary"), "an empty text cannot be looked for");
        assertEquals(3, arts.get(1).get("requestsAfter"), "a refused request never left");
        assertNull(arts.get(2).get("canary"), "an unindexed result was never looked for");

        row("egress", egress("REFUSED", 0, 0, 0, "{{1}} in part 4 (user) at 10"));
        assertEquals(Map.of("checkedCalls", 4, "hits", 1, "leaked", 0, "unchecked", 0),
                list(TaskTraceService.build(rows), "artifacts").get(0).get("canary"));

        // OBSERVE: it went out anyway. And a request whose record names ANOTHER private result
        // was not checked for this one -- the canary stops at its first hit -- so if it went
        // out, it went out unchecked; if it was stopped, it did not matter.
        row("egress", egress("OBSERVED_LEAK", 5, 5, 0.01, "{{1}} in part 4 (user) at 10"));
        row("egress", egress("OBSERVED_LEAK", 5, 5, 0.01, "{{9}} in part 2 (user) at 3"));
        row("egress", egress("REFUSED", 0, 0, 0, "{{9}} in part 2 (user) at 3"));
        row("egress", egress("ERROR", 0, 0, 0, "{{1}} in part 4 (user) at 10 (call then failed: IOException)"));
        assertEquals(Map.of("checkedCalls", 6, "hits", 3, "leaked", 2, "unchecked", 1),
                list(TaskTraceService.build(rows), "artifacts").get(0).get("canary"));
    }

    @Test
    @DisplayName("a skill that said ok:false is a failed step, whatever the loop counted")
    void reportedFailureIsAFailure() {
        row("step", "{\"step\":1,\"tool\":\"smtp_send_email\",\"success\":true,\"reportedFailure\":true,"
                + "\"reason\":\"{\\\"ok\\\": false}\",\"localTokens\":0,\"cloudTokens\":0}");
        var s = list(TaskTraceService.build(rows), "steps").get(0);
        assertEquals(false, s.get("ok"));
        assertEquals(true, s.get("reportedFailure"));
    }

    @Test
    @DisplayName("only a think call that went out chose a step; code-writing and failed calls did not")
    void whoChose() {
        row("egress", egress("SENT", 1, 1, 0.01, null).replace("\"think\"", "\"codegen\""));
        row("step", "{\"step\":1,\"tool\":\"skill_create\",\"success\":true,\"localTokens\":0,\"cloudTokens\":2}");
        row("egress", egress("ERROR", 0, 0, 0, "HttpTimeoutException"));
        row("step", "{\"step\":2,\"tool\":\"respond\",\"success\":false,\"localTokens\":0,\"cloudTokens\":2}");
        row("egress", egress("SENT", 3, 3, 0.02, null));
        row("step", "{\"step\":3,\"tool\":\"delegate\",\"success\":false,\"localTokens\":0,\"cloudTokens\":8}");
        var steps = list(TaskTraceService.build(rows), "steps");
        assertNull(steps.get(0).get("decidedBy"), "chosen by a rule, not a model; the call wrote the code");
        assertEquals(1, steps.get(0).get("cloudCalls"));
        assertNull(steps.get(1).get("decidedBy"), "the only call failed: nothing chose it");
        assertEquals(true, steps.get(1).get("costIsFloor"));
        assertEquals("cloud", steps.get(2).get("decidedBy"));
        assertNull(steps.get(2).get("tier"), "a delegation that never reached the local model ran nowhere");

        var error = list(TaskTraceService.build(rows), "calls").get(1);
        assertNull(error.get("promptTokens"), "a failed call's tokens are not recorded, not 0");
        assertNull(error.get("costUsd"));
    }

    @Test
    @DisplayName("a task from after recording began that made no cloud request says so, not 'not recorded'")
    void recordedWithoutCalls() {
        row("step", "{\"step\":1,\"tool\":\"delegate\",\"success\":true,\"reportedFailure\":false,"
                + "\"localTokens\":900,\"cloudTokens\":0}");
        var t = TaskTraceService.build(rows);
        assertEquals(true, t.get("recorded"));
        assertEquals(0, list(t, "steps").get(0).get("cloudCalls"));

        rows.clear();
        row("step", "{\"step\":1,\"tool\":\"web_fetch\",\"success\":true,\"localTokens\":0,\"cloudTokens\":9}");
        assertEquals(false, TaskTraceService.build(rows).get("recorded"), "a row from before");
    }

    @Test
    @DisplayName("no invented answer or running state; a finished task has its outcome")
    void outcomeOnly() {
        row("egress", egress("SENT", 1, 1, 0, null));
        var t = TaskTraceService.build(rows);
        assertNull(t.get("outcome"));
        assertFalse(t.containsKey("inProgress"));
    }

    @Test
    @DisplayName("an unreadable row is counted, not thrown")
    void unparsedRows() {
        row("egress", "not json");
        row("step", "{broken");
        var t = assertDoesNotThrow(() -> TaskTraceService.build(rows));
        assertEquals(Map.of("unparsed", 1), ((Map<?, ?>) t.get("totals")).get("decisions"));
    }

    @Test
    @DisplayName("what reaches the browser: no hashes, no per-part list, no user id, no provider")
    void nothingExtraReachesTheBrowser() throws Exception {
        row("egress", egress("SENT", 1, 1, 0, null));
        row("step", "{\"step\":1,\"tool\":\"x\",\"success\":true,\"localTokens\":0,\"cloudTokens\":2,"
                + "\"artifact\":\"{{1}}\",\"label\":\"PRIVATE\",\"chars\":50,\"sha256_16\":\"feedfacefeedface\"}");
        String json = new ObjectMapper().writeValueAsString(TaskTraceService.build(rows));
        for (String banned : List.of("sha256", "deadbeef", "feedface", "\"parts\"", "user_id", "anthropic")) {
            assertFalse(json.contains(banned), banned + " in " + json);
        }
    }

    // ── scoped to the user, against a real database ──

    private static EventLogService events(Path tmp) {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:" + tmp.resolve("t.db")));
        jdbc.execute("""
            CREATE TABLE events (
                id INTEGER PRIMARY KEY AUTOINCREMENT, timestamp TEXT DEFAULT (datetime('now')),
                user_id TEXT NOT NULL, task_id TEXT, event_type TEXT NOT NULL, severity TEXT NOT NULL,
                summary TEXT NOT NULL, details TEXT, tokens_used INTEGER DEFAULT 0)""");
        return new EventLogService(jdbc);
    }

    @Test
    @DisplayName("a task id is only eight hex characters: the user id is what keeps accounts apart")
    void scopedToTheUser(@TempDir Path tmp) {
        var log = events(tmp);
        log.log("alice", "abcd1234", "step", "info", "s",
                "{\"step\":1,\"tool\":\"alice_tool\",\"success\":true,\"localTokens\":0,\"cloudTokens\":0}", 0);
        log.log("bob", "abcd1234", "step", "info", "s",
                "{\"step\":1,\"tool\":\"bob_tool\",\"success\":true,\"localTokens\":0,\"cloudTokens\":0}", 0);
        var service = new TaskTraceService(log);

        var alice = service.trace("alice", "abcd1234").orElseThrow();
        assertEquals(List.of("alice_tool"), list(alice, "steps").stream().map(s -> s.get("tool")).toList());
        assertEquals("abcd1234", alice.get("taskId"));
        assertEquals(List.of("bob_tool"),
                list(service.trace("bob", "abcd1234").orElseThrow(), "steps").stream().map(s -> s.get("tool")).toList());
        assertTrue(service.trace("carol", "abcd1234").isEmpty(), "someone else's task is no task");
    }
}
