package com.ownclaw.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What each provider tells a call's hook its reply carried, event by event, as it streams in
 * ({@link LlmProgress#received}): what the owner is shown of a call while it runs, and what the
 * loop guard reads. Each through the real provider over a scripted stream.
 */
class ReplyPartsTest {

    /** A hook that writes down every part it is told of, as "PART:text". */
    static final class Heard implements LlmProgress {
        final List<String> parts = new ArrayList<>();

        @Override public void onProgress() { }

        @Override public void received(Part part, String text) { parts.add(part + ":" + text); }
    }

    @Test
    @DisplayName("Ollama: the thinking of each line is reasoning, its content the answer, and a tool call its name and arguments")
    void ollama() {
        var stream = OllamaStreamingTest.line("", "Two and ") + OllamaStreamingTest.line("", "two.\n")
                + OllamaStreamingTest.line("The answer", null)
                + OllamaStreamingTest.toolLine("shell_exec", Map.of("command", "uptime"))
                + OllamaStreamingTest.last("stop", 26, 12);
        var heard = new Heard();

        OllamaStreamingTest.provider(OllamaStreamingTest.config(), OllamaStreamingTest.ollama(stream))
                .chat(OllamaStreamingTest.ASK, new LlmRequestConfig(null, null, false).withProgress(heard));

        assertEquals(List.of("REASONING:Two and ", "REASONING:two.\n", "ANSWER:The answer", "CALL:shell_exec",
                "ARGUMENTS:{\"command\":\"uptime\"}"), heard.parts, "an empty field is no part");
        // Mutation: report the content as reasoning -> the answer is never seen as one.
    }

    @Test
    @DisplayName("Anthropic: thinking deltas are reasoning, text deltas the answer, a tool_use block its name, input_json deltas its arguments")
    void anthropic() {
        String stream = AnthropicStreamingTest.start("claude-opus-5", 284, 0, 0) + AnthropicStreamingTest.ping()
                + AnthropicStreamingTest.blockStart(0, AnthropicStreamingTest.node("thinking").put("thinking", ""))
                + AnthropicStreamingTest.delta(0, AnthropicStreamingTest.node("thinking_delta").put("thinking", "Let me "))
                + AnthropicStreamingTest.delta(0, AnthropicStreamingTest.node("thinking_delta").put("thinking", "see."))
                + AnthropicStreamingTest.delta(0, AnthropicStreamingTest.node("signature_delta").put("signature", "Eq"))
                + AnthropicStreamingTest.blockStop(0)
                + AnthropicStreamingTest.text(1, "The router ", "answers.")
                + AnthropicStreamingTest.tool(2, "tu_1", "shell_exec", "{\"comm", "and\":\"uptime\"}")
                + AnthropicStreamingTest.end("tool_use", null, 42);
        var heard = new Heard();

        AnthropicStreamingTest.provider(AnthropicStreamingTest.api(stream)).chat(AnthropicStreamingTest.ASK,
                new LlmRequestConfig(null, null, false, AnthropicStreamingTest.TOOLS).withProgress(heard));

        assertEquals(List.of("REASONING:Let me ", "REASONING:see.", "ANSWER:The router ", "ANSWER:answers.",
                "CALL:shell_exec", "ARGUMENTS:{\"comm", "ARGUMENTS:and\":\"uptime\"}"), heard.parts,
                "pings, signatures and empty blocks are no part");
    }

    @Test
    @DisplayName("OpenAI: content is the answer, a tool call's first fragment its name, every fragment its arguments")
    void openAi() {
        String stream = OpenAiStreamingTest.content("Hello")
                + OpenAiStreamingTest.toolFragment(0, "call_1", "shell_exec", "{\"command\"")
                + OpenAiStreamingTest.toolFragment(0, null, null, ":\"uptime\"}")
                + OpenAiStreamingTest.finish("tool_calls") + OpenAiStreamingTest.usage(100, 20, 0)
                + OpenAiStreamingTest.DONE;
        var heard = new Heard();

        OpenAiStreamingTest.provider(OpenAiStreamingTest.api(stream)).chat(OpenAiStreamingTest.ASK,
                new LlmRequestConfig(null, null, false).withProgress(heard));

        assertEquals(List.of("ANSWER:Hello", "CALL:shell_exec", "ARGUMENTS:{\"command\"", "ARGUMENTS::\"uptime\"}"),
                heard.parts);
    }

    @Test
    @DisplayName("what the hook throws on a part ends the call unchanged, and the stream is closed")
    void whatTheHookThrowsEndsTheCall() {
        var stream = OllamaStreamingTest.line("", "Two and ") + OllamaStreamingTest.line("", "two.")
                + OllamaStreamingTest.last("stop", 26, 12);
        var http = OllamaStreamingTest.ollama(stream);
        var looped = new RepeatedOutput("ollama", "the local model repeated the same 300 characters 4 times");
        LlmProgress hook = new LlmProgress() {
            @Override public void onProgress() { }
            @Override public void received(Part part, String text) { throw looped; }
        };

        var thrown = assertThrows(RepeatedOutput.class, () -> OllamaStreamingTest.provider(OllamaStreamingTest.config(), http)
                .chat(OllamaStreamingTest.ASK, new LlmRequestConfig(null, null, false).withProgress(hook)));

        assertSame(looped, thrown, "not wrapped, not replaced");
        assertEquals("[ollama] the local model repeated the same 300 characters 4 times", thrown.getMessage());
        assertEquals("the local model repeated the same 300 characters 4 times", thrown.seen());
        assertFalse(thrown.isRetryable() || thrown.unreachable() || thrown.connectionFailed(),
                "a loop is no failure of the provider: nothing retries it, and the task does not move off it");
        assertEquals(http.opened.get(), http.closed.get(), "the stream is closed behind it");
    }
}
