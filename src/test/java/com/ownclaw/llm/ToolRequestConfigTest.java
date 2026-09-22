package com.ownclaw.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The request-side contract for native tools.
 * <p>
 * Two of these guard decisions that are invisible at runtime: getting them wrong does not throw,
 * it quietly produces the old text protocol wearing the new one's clothes, and the only symptom
 * is that nothing ever improves.
 */
class ToolRequestConfigTest {

    private static final List<ToolSpec> SOME_TOOLS =
            List.of(new ToolSpec("shell_exec", "run a command", Map.of("type", "object")));

    @Test
    @DisplayName("a config without tools is unchanged, so no existing call site behaves differently")
    void defaultsAreInert() {
        var c = new LlmRequestConfig(null, null, 8192, true, null);
        assertNull(c.tools());
        assertFalse(c.hasTools());
        assertTrue(c.jsonMode(), "the text protocol still asks for JSON");
    }

    @Test
    @DisplayName("withTools preserves every other setting")
    void withToolsPreservesTheRest() {
        var base = new LlmRequestConfig("m", 0.3, 4096, false, 600);
        var withTools = base.withTools(SOME_TOOLS);
        assertEquals("m", withTools.model());
        assertEquals(0.3, withTools.temperature());
        assertEquals(4096, withTools.maxTokens());
        assertEquals(600, withTools.readTimeoutSec());
        assertTrue(withTools.hasTools());
        assertFalse(base.hasTools(), "the original must not be mutated");
    }

    @Test
    @DisplayName("an empty tool list counts as no tools")
    void emptyIsNotTools() {
        assertFalse(new LlmRequestConfig(null, null, null, false, null)
                .withTools(List.of()).hasTools(),
                "sending an empty tools array would turn on the native path with nothing to call");
    }

    @Test
    @DisplayName("a response without tool calls reports none rather than null")
    void responseDefaultsToNoCalls() {
        var r = new LlmResponse("hello", 10, 5, 0, 0, "end_turn");
        assertNotNull(r.toolCalls(), "callers iterate this without a null check");
        assertFalse(r.hasToolCalls());
    }

    @Test
    @DisplayName("a response carrying tool calls reports them")
    void responseWithCalls() {
        var r = new LlmResponse("", 10, 5, 0, 0, "tool_use",
                List.of(new ToolCall("id_1", "shell_exec", Map.of("command", "hostname"))));
        assertTrue(r.hasToolCalls());
        assertEquals("shell_exec", r.toolCalls().get(0).name());
        assertEquals("hostname", r.toolCalls().get(0).arguments().get("command"));
    }

    @Test
    @DisplayName("providers declare tool support rather than it being assumed")
    void capabilityIsExplicit() {
        // The default is the safe answer: a provider not taught the protocol keeps the text path
        // instead of silently sending a field the API ignores.
        LlmProvider untaught = new LlmProvider() {
            public LlmResponse chat(List<LlmMessage> m, LlmRequestConfig c) { return null; }
            public boolean isAvailable() { return true; }
            public String name() { return "untaught"; }
            public String model() { return "x"; }
        };
        assertFalse(untaught.supportsTools());
    }
}
