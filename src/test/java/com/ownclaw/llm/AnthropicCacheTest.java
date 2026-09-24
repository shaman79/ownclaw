package com.ownclaw.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownclaw.config.OwnClawConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What the Anthropic request looks like to the prompt cache: the task is cached on a task's first
 * call and read back on the next, and no request carries more cache marks than the API allows.
 */
class AnthropicCacheTest {

    static final String MARK = AnthropicProvider.CACHE_BOUNDARY;
    private final AnthropicProvider provider = new AnthropicProvider(new OwnClawConfig(), new ObjectMapper());

    private JsonNode body(List<LlmMessage> messages, boolean tools) {
        var specs = tools ? List.of(new ToolSpec("respond", "answer", Map.of("type", "object"))) : null;
        return provider.requestBody(messages, new LlmRequestConfig(null, null, null, false, null, specs),
                "claude-opus-5", 1024);
    }

    private static String text(JsonNode content) {
        return content.isArray() ? content.get(0).path("text").asText() : content.asText();
    }

    @Test
    @DisplayName("step 0: the task is its own cached block; step 1 sends the same bytes as the whole first message")
    void theTaskIsCachedAndReadBack() {
        String task = "## Prior Context\n" + "a long chat ".repeat(500) + "\n\n## Task\nsummarise it";
        var step0 = body(List.of(LlmMessage.system("SYSTEM"),
                LlmMessage.user(task + MARK + "---\n## Environment\n- DateTime: now")), true);
        JsonNode first = step0.path("messages").get(0).path("content");
        assertTrue(first.isArray(), "two blocks: " + first);
        assertEquals(task, first.get(0).path("text").asText());
        assertEquals("ephemeral", first.get(0).path("cache_control").path("type").asText());
        assertTrue(first.get(1).path("text").asText().startsWith("---\n## Environment"));
        assertTrue(first.get(1).path("cache_control").isMissingNode(), "what changes each step is not cached");
        assertFalse(step0.toString().contains("CACHE_BOUNDARY"), "the marker is never sent");

        var step1 = body(List.of(LlmMessage.system("SYSTEM"), LlmMessage.user(task),
                LlmMessage.assistant("{\"tool\":\"delegate\"}"), LlmMessage.user("result\n\n---\n## Environment")), true);
        assertEquals(task, text(step1.path("messages").get(0).path("content")),
                "byte for byte the cached block, so the cache is read instead of written again");
    }

    @Test
    @DisplayName("a message that is already two blocks is left alone by the sliding breakpoints")
    void splitMessagesKeepTheirText() {
        // Not an order the engine produces today; the guard is what keeps it from wiping the task.
        var b = body(List.of(LlmMessage.user("task" + MARK + "dyn"), LlmMessage.assistant("action")), true);
        JsonNode first = b.path("messages").get(0).path("content");
        assertEquals("task", first.get(0).path("text").asText(), String.valueOf(first));
        assertEquals("dyn", first.get(1).path("text").asText());
    }

    @Test
    @DisplayName("never more than four cache marks, whatever the length of the task so far")
    void atMostFourMarks() {
        for (boolean split : List.of(true, false)) {
            for (int turns = 0; turns < 8; turns++) {
                var messages = new ArrayList<LlmMessage>();
                messages.add(LlmMessage.system("SYSTEM" + MARK + "dynamic"));
                messages.add(LlmMessage.user(split && turns == 0 ? "task" + MARK + "dyn" : "task"));
                for (int i = 0; i < turns; i++) {
                    messages.add(LlmMessage.assistant("action " + i));
                    messages.add(LlmMessage.user("observation " + i));
                }
                String json = body(messages, true).toString();
                int marks = json.split("cache_control", -1).length - 1;
                assertTrue(marks <= 4, marks + " marks with " + turns + " turns, split=" + split);
            }
        }
    }
}
