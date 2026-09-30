package com.ownclaw.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A call that sends nothing -- Ollama loading the model and reading the prompt, a cloud call
 * before its first event -- has no event on which its progress hook could throw. Each provider
 * hands the hook the call's cancel while the call runs; a call ended with it ends with what the
 * hook throws, as a stop heard on an event does.
 */
class SilentCallTest {

    /** What a stopped task's hook throws. */
    static final class Stopped extends RuntimeException {}

    /** A hook that ends the call a moment after it is under way, and then says it was stopped. */
    static class StopsTheCall implements LlmProgress {
        final List<String> handed = new CopyOnWriteArrayList<>();
        volatile boolean stopped;

        @Override
        public void onProgress() {
            if (stopped) throw new Stopped();
        }

        @Override
        public void calling(Runnable cancel) {
            handed.add(cancel == null ? "returned" : "under way");
            if (cancel == null) return;
            Thread.ofVirtual().start(() -> {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    return;
                }
                stopped = true;
                cancel.run();
            });
        }
    }

    private static void endsWithTheHooksStop(Function<LlmRequestConfig, LlmResponse> call) {
        var hook = new StopsTheCall();
        long t0 = System.currentTimeMillis();
        assertThrows(Stopped.class, () -> call.apply(LlmRequestConfig.DEFAULT.withProgress(hook)),
                "the call ends with what the hook throws, not a connection failure");
        assertTrue(System.currentTimeMillis() - t0 < 5_000, "ended when cancelled, not at a timeout");
        assertEquals(List.of("under way", "returned"), hook.handed,
                "the hook holds the cancel while the call runs, and is told when it has returned");
    }

    @Test
    @DisplayName("Anthropic: a request with no event yet is ended by the cancel its hook holds")
    void anthropic() {
        var http = new FakeHttp().json(AnthropicStreamingTest.MODELS, 200, AnthropicStreamingTest.LIMITS)
                .silent(AnthropicStreamingTest.MESSAGES);
        endsWithTheHooksStop(c -> AnthropicStreamingTest.provider(http).chat(AnthropicStreamingTest.ASK, c));
    }

    @Test
    @DisplayName("OpenAI: a request with no event yet is ended by the cancel its hook holds")
    void openAi() {
        var http = new FakeHttp().silent(OpenAiStreamingTest.COMPLETIONS);
        endsWithTheHooksStop(c -> OpenAiStreamingTest.provider(http).chat(AnthropicStreamingTest.ASK, c));
    }

    @Test
    @DisplayName("Ollama: a model still loading, which sends nothing, is ended by the cancel its hook holds")
    void ollama() {
        var http = new FakeHttp().json(OllamaStreamingTest.SHOW, 200, OllamaStreamingTest.show("qwen35moe", 262_144))
                .silent(OllamaStreamingTest.CHAT);
        endsWithTheHooksStop(c -> OllamaStreamingTest.provider(OllamaStreamingTest.config(), http)
                .chat(AnthropicStreamingTest.ASK, c));
    }

    @Test
    @DisplayName("a call cancelled by a hook that does not stop is a connection failure, as before")
    void aCancelWithNoStopIsAFailure() {
        var http = new FakeHttp().silent(OpenAiStreamingTest.COMPLETIONS);
        var hook = new StopsTheCall() {
            @Override public void onProgress() { }
        };
        var e = assertThrows(LlmException.class,
                () -> OpenAiStreamingTest.provider(http).chat(AnthropicStreamingTest.ASK, LlmRequestConfig.DEFAULT.withProgress(hook)));
        assertTrue(e.getMessage().startsWith("[openai] Connection failed"), e.getMessage());
    }
}
