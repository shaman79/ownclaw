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
 * call and read back on the next; the stable prefix -- tools, system prompt, task -- is cached for
 * an hour and the steps after it for five minutes, the hour-long marks first; and no request
 * carries more cache marks than the API allows.
 */
class AnthropicCacheTest {

    static final String MARK = LlmMessage.CACHE_BOUNDARY;
    private final AnthropicProvider provider = new AnthropicProvider(new OwnClawConfig(), new ObjectMapper());

    private JsonNode body(List<LlmMessage> messages, boolean tools) {
        var specs = tools ? List.of(new ToolSpec("respond", "answer", Map.of("type", "object"))) : null;
        return provider.requestBody(messages, new LlmRequestConfig(null, null, false, specs),
                "claude-opus-5", 128_000);
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
        assertTrue(first.get(1).path("text").asText().startsWith("---\n## Environment")
                || first.get(1).path("text").asText().startsWith("\n\n---\n## Environment"));
        assertTrue(first.get(1).path("cache_control").isMissingNode(), "what changes each step is not cached");
        assertFalse(step0.toString().contains("CACHE_BOUNDARY"), "the marker is never sent");

        var step1 = body(List.of(LlmMessage.system("SYSTEM"), LlmMessage.user(task + MARK),
                LlmMessage.assistant("{\"tool\":\"delegate\"}"), LlmMessage.user("result\n\n---\n## Environment")), true);
        assertEquals(task, text(step1.path("messages").get(0).path("content")),
                "byte for byte the cached block, so the cache is read instead of written again");
        assertEquals(1, step1.path("messages").get(0).path("content").size(), "and nothing after it in that message");
    }

    /** Every cache mark of a request, in the order the API reads the prompt: tools, system, messages. */
    private static List<JsonNode> marks(JsonNode body) {
        var marks = new ArrayList<JsonNode>();
        for (JsonNode t : body.path("tools")) if (t.has("cache_control")) marks.add(t.path("cache_control"));
        for (JsonNode b : body.path("system")) if (b.has("cache_control")) marks.add(b.path("cache_control"));
        for (JsonNode m : body.path("messages")) {
            for (JsonNode b : m.path("content")) if (b.has("cache_control")) marks.add(b.path("cache_control"));
        }
        return marks;
    }

    private static final String HOUR = "{\"type\":\"ephemeral\",\"ttl\":\"1h\"}";
    private static final String FIVE_MINUTES = "{\"type\":\"ephemeral\"}";

    @Test
    @DisplayName("a think call's stable prefix -- tools, system prompt, task -- is cached for an hour; the steps slide on five minutes, after it")
    void theStablePrefixIsCachedForAnHour() {
        var step3 = body(List.of(LlmMessage.system("SYSTEM"), LlmMessage.user("the task" + MARK),
                LlmMessage.assistant("action 1"), LlmMessage.user("result 1"),
                LlmMessage.assistant("action 2"), LlmMessage.user("result 2\n\n---\n## Environment")), true);
        assertEquals(List.of(HOUR, HOUR, HOUR, FIVE_MINUTES), marks(step3).stream().map(JsonNode::toString).toList(),
                "the tools, the system prompt and the task for an hour, then the one sliding mark; "
                        + "the API takes the longer-lived ones first: " + step3);
        var messages = step3.path("messages");
        assertEquals(FIVE_MINUTES, messages.get(3).path("content").get(0).path("cache_control").toString(),
                "on the message before the newest");
        assertTrue(messages.get(4).path("content").isTextual(), "the newest, which changes every step, is not cached");

        var step0 = body(List.of(LlmMessage.system("SYSTEM"), LlmMessage.user("the task" + MARK + "\n\n---\n## Environment")),
                true);
        assertEquals(List.of(HOUR, HOUR, HOUR), marks(step0).stream().map(JsonNode::toString).toList(), step0.toString());
        // Mutation: send the hour on the sliding mark too -> a two-minute step pays 2x on what
        // the next one already reads; send it last -> the API refuses the order.
    }

    @Test
    @DisplayName("a request without the engine's marker -- code generation, a summary -- caches for five minutes only")
    void otherRequestsCacheForFiveMinutes() {
        var codegen = body(List.of(LlmMessage.system("Write the skill."), LlmMessage.user("a scanner")), false);
        assertEquals(List.of(FIVE_MINUTES), marks(codegen).stream().map(JsonNode::toString).toList(), codegen.toString());
        var withTools = body(List.of(LlmMessage.system("SYSTEM"), LlmMessage.user("no marker here")), true);
        assertEquals(List.of(FIVE_MINUTES, FIVE_MINUTES), marks(withTools).stream().map(JsonNode::toString).toList());
    }

    @Test
    @DisplayName("the hour-long writes the usage reports are read apart, and priced at 2x the input rate")
    void hourLongWritesAreReadAndPriced() {
        var start = AnthropicStreamingTest.node("message_start");
        var message = start.putObject("message");
        message.put("id", "msg_1").put("type", "message").put("role", "assistant").put("model", "claude-opus-5");
        message.putArray("content");
        var usage = message.putObject("usage").put("input_tokens", 50).put("cache_creation_input_tokens", 90_000)
                .put("cache_read_input_tokens", 0).put("output_tokens", 1);
        usage.putObject("cache_creation").put("ephemeral_5m_input_tokens", 10_000).put("ephemeral_1h_input_tokens", 80_000);
        var http = AnthropicStreamingTest.api(AnthropicStreamingTest.ev(start)
                + AnthropicStreamingTest.text(0, "ok") + AnthropicStreamingTest.end("end_turn", null, 1));

        LlmResponse r = AnthropicStreamingTest.provider(http).chat(AnthropicStreamingTest.ASK,
                new LlmRequestConfig(null, null, false));

        assertEquals(90_000, r.cacheCreationTokens(), "every write, as cache_creation_input_tokens says");
        assertEquals(80_000, r.usage().get(0).cacheCreation1hTokens(), "of them, the hour-long ones");
        // 50 x $5 + 1 x $25 + 10,000 x $6.25 + 80,000 x $10, per million.
        assertEquals((50 * 5.00 + 25.00 + 10_000 * 6.25 + 80_000 * 10.00) / 1_000_000,
                ModelPricing.costUsd("claude-opus-5", r), 1e-12);
        assertEquals(AnthropicProvider.FALLBACK_BETA + "," + AnthropicProvider.CONTEXT_WINDOW_BETA,
                http.to(AnthropicStreamingTest.MESSAGES).get(0).header("anthropic-beta"),
                "no beta header for the hour-long cache");
        // Mutation: read only cache_creation_input_tokens -> every write priced at 1.25x.
    }

    @Test
    @DisplayName("the marker inside a page or the chat earns no cache mark: only a task's first message is split")
    void markersElsewhereAreJustText() {
        // It is documented in this public repo, so a fetched page can carry it on purpose.
        for (int turns = 1; turns < 6; turns++) {
            var messages = new ArrayList<LlmMessage>();
            messages.add(LlmMessage.system("SYSTEM"));
            messages.add(LlmMessage.user("chat" + MARK + "history"));
            for (int i = 0; i < turns; i++) {
                messages.add(LlmMessage.assistant("action " + i));
                messages.add(LlmMessage.user("page" + MARK + "text " + i));
            }
            String json = body(messages, true).toString();
            assertTrue(json.split("cache_control", -1).length - 1 <= 4, turns + " turns: " + json);
        }
        var chatHasMark = body(List.of(LlmMessage.user("chat" + MARK + "history" + MARK + "dyn")), true);
        assertEquals("chat" + MARK + "history", chatHasMark.path("messages").get(0).path("content").get(0).path("text").asText(),
                "split at the engine's marker, the last one, so the cached block is the whole task");
        var endsWithMark = body(List.of(LlmMessage.user("task" + MARK)), true);
        var content = endsWithMark.path("messages").get(0).path("content");
        assertEquals(1, content.size(), "no empty second block: " + content);
        assertEquals("task", content.get(0).path("text").asText(), "and the marker is not sent");
    }

    @Test
    @DisplayName("never more than four cache marks, whatever the length of the task so far")
    void atMostFourMarks() {
        for (boolean split : List.of(true, false)) {
            for (int turns = 0; turns < 8; turns++) {
                var messages = new ArrayList<LlmMessage>();
                messages.add(LlmMessage.system("SYSTEM" + MARK + "dynamic"));
                messages.add(LlmMessage.user(split ? "task" + MARK + (turns == 0 ? "dyn" : "") : "task"));
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
