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
 * Skills sent deferred and offered by TOOLS messages, as each provider renders them. Every skill's
 * description used to be sent on every step: 114,293 characters of a scheduled run's every
 * request, and most of a 42,000-token first prompt for the local model.
 */
class DeferredToolsTest {

    static final Map<String, Object> SCHEMA = Map.of("type", "object");
    static final List<ToolSpec> TOOLS = List.of(
            new ToolSpec("respond", "answer", SCHEMA),
            new ToolSpec("find_tools", "search the skills", SCHEMA),
            new ToolSpec("smtp_send_email", "send an email", SCHEMA),
            new ToolSpec("openwrt_run", "run on a router", SCHEMA, true),
            new ToolSpec("web_fetch", "fetch a page", SCHEMA, true));

    static List<LlmMessage> conversation() {
        return List.of(LlmMessage.system("SYSTEM"),
                LlmMessage.user("## Task\ncheck the routers" + LlmMessage.CACHE_BOUNDARY + "step block"),
                LlmMessage.toolsAdded(List.of("openwrt_run")),
                LlmMessage.assistant("{\"tool\":\"find_tools\"}"),
                LlmMessage.user("2 skills match"),
                // web_fetch again, openwrt_run a second time, a tool offered from the start, one deleted since
                LlmMessage.toolsAdded(List.of("web_fetch", "openwrt_run", "smtp_send_email", "gone_skill")),
                LlmMessage.assistant("{\"tool\":\"web_fetch\"}"),
                LlmMessage.user("the page"));
    }

    @Test
    @DisplayName("a provider without deferred loading offers the tools not deferred, then each deferred one from the message that offers it")
    void offered() {
        assertEquals(List.of("respond", "find_tools", "smtp_send_email", "openwrt_run", "web_fetch"),
                ToolSpec.offered(TOOLS, conversation()).stream().map(ToolSpec::name).toList());
        assertEquals(List.of("respond", "find_tools", "smtp_send_email"),
                ToolSpec.offered(TOOLS, List.of(LlmMessage.user("hi"))).stream().map(ToolSpec::name).toList(),
                "a deferred tool nothing offered is not offered");
        assertEquals(List.of(), LlmMessage.user("openwrt_run").addedTools(), "only a TOOLS message offers tools");
    }

    @Test
    @DisplayName("Anthropic: deferred tools carry defer_loading and no cache mark; each TOOLS message is a tool addition of deferred tools, each once")
    void anthropic() {
        var provider = new AnthropicProvider(new OwnClawConfig(), new ObjectMapper());
        JsonNode body = provider.requestBody(conversation(), new LlmRequestConfig(null, null, false, TOOLS),
                "claude-opus-5", 128_000);

        JsonNode tools = body.path("tools");
        assertEquals(5, tools.size(), "every tool is sent: the API expands a reference from the array");
        assertTrue(tools.get(3).path("defer_loading").asBoolean() && tools.get(4).path("defer_loading").asBoolean());
        assertTrue(tools.get(2).path("defer_loading").isMissingNode());
        assertTrue(tools.get(2).has("cache_control"), "the cache mark is on the last tool not deferred");
        for (int i = 0; i < tools.size(); i++) {
            if (i != 2) assertFalse(tools.get(i).has("cache_control"), tools.get(i).toString());
        }

        var additions = new ArrayList<List<String>>();
        var roles = new ArrayList<String>();
        for (JsonNode m : body.path("messages")) {
            roles.add(m.path("role").asText());
            if (!"system".equals(m.path("role").asText())) continue;
            var names = new ArrayList<String>();
            for (JsonNode block : m.path("content")) {
                assertEquals("tool_addition", block.path("type").asText());
                assertEquals("tool_reference", block.path("tool").path("type").asText());
                names.add(block.path("tool").path("name").asText());
            }
            additions.add(names);
        }
        assertEquals(List.of(List.of("openwrt_run"), List.of("web_fetch")), additions,
                "a tool offered once, one offered from the start and one that is no tool of the request are no additions");
        assertEquals(List.of("user", "system", "assistant", "user", "system", "assistant", "user"), roles,
                "each addition right after a user turn, before the next assistant turn");
        assertEquals("SYSTEM", body.path("system").get(0).path("text").asText(), "the TOOLS messages are not the system prompt");

        assertEquals(5, marked(body), "the sliding mark on the message before the newest user turn");

        // A step that found tools: its addition comes after the newest user turn.
        var found = new ArrayList<>(conversation());
        found.add(LlmMessage.toolsAdded(List.of("openwrt_run_to_file")));
        var more = new ArrayList<>(TOOLS);
        more.add(new ToolSpec("openwrt_run_to_file", "run on a router, to a file", SCHEMA, true));
        assertEquals(5, marked(provider.requestBody(found, new LlmRequestConfig(null, null, false, more),
                "claude-opus-5", 128_000)), "not on the newest user turn, which changes on every step");
    }

    /** The message the sliding five-minute mark is on. */
    static int marked(JsonNode body) {
        JsonNode messages = body.path("messages");
        int marked = -1;
        for (int i = 1; i < messages.size(); i++) {
            JsonNode content = messages.get(i).path("content");
            if (content.isArray() && content.get(0).has("cache_control")) marked = i;
        }
        return marked;
    }

    @Test
    @DisplayName("Anthropic: the tool-changes beta is asked for only when a tool is deferred")
    void theBeta() {
        var http = AnthropicStreamingTest.api(AnthropicStreamingTest.start("claude-opus-5", 10, 0, 0)
                + AnthropicStreamingTest.text(0, "ok") + AnthropicStreamingTest.end("end_turn", null, 1));
        AnthropicStreamingTest.provider(http).chat(conversation(), new LlmRequestConfig(null, null, false, TOOLS));
        assertTrue(http.to(AnthropicStreamingTest.MESSAGES).get(0).header("anthropic-beta")
                .contains(AnthropicProvider.TOOL_CHANGES_BETA));

        var plain = AnthropicStreamingTest.api(AnthropicStreamingTest.start("claude-opus-5", 10, 0, 0)
                + AnthropicStreamingTest.text(0, "ok") + AnthropicStreamingTest.end("end_turn", null, 1));
        AnthropicStreamingTest.provider(plain).chat(AnthropicStreamingTest.ASK,
                new LlmRequestConfig(null, null, false, AnthropicStreamingTest.TOOLS));
        assertFalse(plain.to(AnthropicStreamingTest.MESSAGES).get(0).header("anthropic-beta")
                .contains(AnthropicProvider.TOOL_CHANGES_BETA));
    }

    @Test
    @DisplayName("a model that takes no tool changes is sent the offered tools, none deferred, and no tool addition")
    void aModelWithoutToolChanges() {
        for (String model : List.of("claude-opus-5", "claude-opus-4-8", "claude-opus-5-5", "claude-sonnet-5-5",
                "claude-fable-5-1", "claude-mythos-5-1")) {
            assertTrue(AnthropicProvider.supportsToolChanges(model), model);
        }
        for (String model : List.of("claude-sonnet-5", "claude-opus-4-7", "claude-fable-5", "claude-haiku-4-5",
                "claude-3-5-sonnet-20241022")) {
            assertFalse(AnthropicProvider.supportsToolChanges(model), model);
        }
        var provider = new AnthropicProvider(new OwnClawConfig(), new ObjectMapper());
        JsonNode body = provider.requestBody(conversation(), new LlmRequestConfig(null, null, false, TOOLS),
                "claude-sonnet-5", 64_000);
        var names = new ArrayList<String>();
        body.path("tools").forEach(t -> {
            names.add(t.path("name").asText());
            assertFalse(t.has("defer_loading"), t.toString());
        });
        assertEquals(List.of("respond", "find_tools", "smtp_send_email", "openwrt_run", "web_fetch"), names);
        body.path("messages").forEach(m -> assertNotEquals("system", m.path("role").asText()));
    }

    @Test
    @DisplayName("OpenAI and Ollama: the offered tools in the array, and no TOOLS message sent")
    void theOthers() throws Exception {
        var openai = new OpenAiProvider(new OwnClawConfig(), new ObjectMapper());
        JsonNode body = openai.requestBody(conversation(), new LlmRequestConfig(null, null, false, TOOLS), "gpt-5");
        var names = new ArrayList<String>();
        body.path("tools").forEach(t -> names.add(t.path("function").path("name").asText()));
        assertEquals(List.of("respond", "find_tools", "smtp_send_email", "openwrt_run", "web_fetch"), names);
        assertEquals(6, body.path("messages").size(), "the two TOOLS messages are not sent");
        JsonNode unoffered = openai.requestBody(List.of(LlmMessage.system("S"), LlmMessage.user("hi")),
                new LlmRequestConfig(null, null, false, TOOLS), "gpt-5");
        assertEquals(3, unoffered.path("tools").size(), "a deferred tool nothing offered is not sent to it");

        var http = OllamaStreamingTest.ollama(OllamaStreamingTest.last("stop", 10, 1));
        OllamaStreamingTest.provider(OllamaStreamingTest.config(), http)
                .chat(conversation(), new LlmRequestConfig(null, null, false, TOOLS));
        JsonNode sent = new ObjectMapper().readTree(http.to(OllamaStreamingTest.CHAT).get(0).body());
        names.clear();
        sent.path("tools").forEach(t -> names.add(t.path("function").path("name").asText()));
        assertEquals(List.of("respond", "find_tools", "smtp_send_email", "openwrt_run", "web_fetch"), names);
        sent.path("messages").forEach(m -> assertNotEquals("tools", m.path("role").asText()));
        assertEquals(6, sent.path("messages").size());
    }
}
