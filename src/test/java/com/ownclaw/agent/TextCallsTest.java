package com.ownclaw.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A tool call written as text is read one way, whoever reads it: the cloud's step and the local
 * model's turn in a delegation.
 */
class TextCallsTest {

    /** One call, in the shapes models write it. */
    static final List<String> ONE_CALL = List.of(
            "{\"tool\": \"web_fetch\", \"params\": {\"url\": \"https://example.org/a\"}}",
            "{\"name\": \"web_fetch\", \"arguments\": {\"url\": \"https://example.org/a\"}}",
            "<tool_call>\n{\"name\": \"web_fetch\", \"arguments\": {\"url\": \"https://example.org/a\"}}\n</tool_call>",
            "```json\n{\"tool\": \"web_fetch\", \"parameters\": {\"url\": \"https://example.org/a\"}}\n```",
            "{\"tool\": \"web_fetch\", // fetch it first\n \"params\": {\"url\": \"https://example.org/a\"}}",
            "{\"action\": \"web_fetch\", \"params\": {\"url\": \"https://example.org/a\", \"note\": \"line one\nline two\"}}",
            "I will fetch it now: {'tool': 'web_fetch', params: {'url': 'https://example.org/a',},}");

    @Test
    @DisplayName("the cloud's step and a delegation read every shape of a call alike, arguments included")
    void bothReadersReadACallAlike() {
        var engine = new ThinkingEngine(null, null, null);
        for (String text : ONE_CALL) {
            AgentAction cloud = engine.parseAction(text);
            List<LocalExecutor.ExecutorAction> local = LocalExecutor.parseExecutorActions(text);
            assertEquals(1, local.size(), text);
            assertEquals("web_fetch", cloud.tool(), text);
            assertEquals(cloud.tool(), local.get(0).tool(), text);
            assertEquals(cloud.params(), local.get(0).params(), text);
            assertEquals("https://example.org/a", local.get(0).params().get("url"), text);
        }
    }

    @Test
    @DisplayName("a comment is read where JSON has whitespace, never inside a string")
    void commentsAreNotCutOutOfStrings() {
        // Text stripped of whatever looked like a comment before parsing: a glob's "/*" and
        // another's "*/" took the command between them, and a single-quoted URL lost its rest.
        String command = "ls /var/log/*.log && rm /tmp/cache/*/old.tmp";
        var call = TextCalls.firstObject("{\"tool\": \"shell_exec\", /* the cleanup */ \"params\": "
                + "{\"command\": \"" + command + "\"}}");
        assertEquals(Map.of("command", command), TextCalls.params(call));
        var quoted = TextCalls.firstObject("{'tool': 'web_fetch', 'params': {'url': 'https://example.org/a'}}");
        assertEquals(Map.of("url", "https://example.org/a"), TextCalls.params(quoted));
    }

    @Test
    @DisplayName("every object of the text is read, in order; a nested one is part of its own")
    void everyObjectIsRead() {
        String two = "{\"tool\": \"a\", \"params\": {\"x\": {\"tool\": \"nested\"}}}\n{\"tool\": \"b\"}";
        assertEquals(List.of("a", "b"), TextCalls.objects(two).stream().map(TextCalls::tool).toList());
        assertEquals(List.of(), TextCalls.objects("no call here, only {CURRENT_YEAR}"));
        assertNull(TextCalls.tool(Map.of("tool", Map.of("name", "x"))), "an object is not a name");
    }
}
