package com.ownclaw.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A call that sends nothing -- Ollama loading the model and reading the prompt, a cloud call
 * before its first event, the Models API lookup before that, a call waiting to try again after
 * an overload -- has no event on which its progress hook could throw. Each provider hands the
 * hook the cancel of whatever the call is waiting on; a call ended with it ends with what the
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

    /**
     * A task's hook as AgentContext makes one: it holds the cancel of what the call is waiting
     * on, a Stop runs that cancel (as AgentLoop#interruptStopped does), and once stopped it
     * throws when it is asked -- unless it is one that does not stop.
     */
    static final class TaskHook implements LlmProgress {
        private final boolean throwsStop;
        private volatile Runnable inFlight;
        private volatile boolean stopped;

        TaskHook(boolean throwsStop) {
            this.throwsStop = throwsStop;
        }

        @Override
        public void onProgress() {
            if (stopped && throwsStop) throw new Stopped();
        }

        @Override
        public void calling(Runnable cancel) {
            inFlight = cancel;
            if (cancel != null && stopped) cancel.run();
        }

        /** The owner presses Stop {@code ms} from now. */
        void stopIn(long ms) {
            Thread.ofVirtual().start(() -> {
                try {
                    Thread.sleep(ms);
                } catch (InterruptedException e) {
                    return;
                }
                stopped = true;
                Runnable cancel = inFlight;
                if (cancel != null) cancel.run();
            });
        }
    }

    /** @param calls how many requests the call makes before the one that is silent, plus that one */
    private static void endsWithTheHooksStop(int calls, Function<LlmRequestConfig, LlmResponse> call) {
        var hook = new StopsTheCall();
        long t0 = System.currentTimeMillis();
        assertThrows(Stopped.class, () -> call.apply(LlmRequestConfig.DEFAULT.withProgress(hook)),
                "the call ends with what the hook throws, not a connection failure");
        assertTrue(System.currentTimeMillis() - t0 < 5_000, "ended when cancelled, not at a timeout");
        var expected = new ArrayList<String>();
        for (int i = 0; i < calls; i++) expected.addAll(List.of("under way", "returned"));
        assertEquals(expected, hook.handed,
                "the hook holds the cancel while each request runs, and is told when it has returned");
    }

    @Test
    @DisplayName("Anthropic: a request with no event yet is ended by the cancel its hook holds")
    void anthropic() {
        var http = new FakeHttp().json(AnthropicStreamingTest.MODELS, 200, AnthropicStreamingTest.LIMITS)
                .silent(AnthropicStreamingTest.MESSAGES);
        // The Models API lookup, then the request.
        endsWithTheHooksStop(2, c -> AnthropicStreamingTest.provider(http).chat(AnthropicStreamingTest.ASK, c));
    }

    @Test
    @DisplayName("Anthropic: a Models API lookup with no answer yet is ended by the cancel its hook holds")
    void anthropicModelsLookup() {
        var http = new FakeHttp().silent(AnthropicStreamingTest.MODELS);
        endsWithTheHooksStop(1, c -> AnthropicStreamingTest.provider(http).chat(AnthropicStreamingTest.ASK, c));
        assertTrue(http.to(AnthropicStreamingTest.MESSAGES).isEmpty(), "and nothing was sent after it");
        // Mutation: make the lookup with the client directly -> no cancel is handed over, and the
        // silent server fails the test after 30 s.
    }

    @Test
    @DisplayName("OpenAI: a request with no event yet is ended by the cancel its hook holds")
    void openAi() {
        var http = new FakeHttp().silent(OpenAiStreamingTest.COMPLETIONS);
        endsWithTheHooksStop(1, c -> OpenAiStreamingTest.provider(http).chat(AnthropicStreamingTest.ASK, c));
    }

    @Test
    @DisplayName("Ollama: a model still loading, which sends nothing, is ended by the cancel its hook holds")
    void ollama() {
        var http = new FakeHttp().json(OllamaStreamingTest.SHOW, 200, OllamaStreamingTest.show("qwen35moe", 262_144))
                .silent(OllamaStreamingTest.CHAT);
        endsWithTheHooksStop(1, c -> OllamaStreamingTest.provider(OllamaStreamingTest.config(), http)
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

    // ── the wait before trying again ──

    static final String OVERLOADED = "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}";

    /**
     * The provider fails in a way worth retrying and waits -- at least 24 s -- before it tries
     * again; the owner presses Stop a second in. The call ends then, with the stop.
     */
    private static void aWaitEndsWithTheHooksStop(FakeHttp http, String path,
                                                  Function<LlmRequestConfig, LlmResponse> call) {
        var hook = new TaskHook(true);
        long t0 = System.currentTimeMillis();
        hook.stopIn(1_000);
        assertTimeoutPreemptively(Duration.ofSeconds(20),
                () -> assertThrows(Stopped.class, () -> call.apply(LlmRequestConfig.DEFAULT.withProgress(hook))),
                "a Stop during the wait was heard only when the wait was over");
        assertTrue(System.currentTimeMillis() - t0 < 5_000, "ended at the Stop, not at the end of the wait");
        assertEquals(1, http.to(path).size(), "and nothing was sent again");
    }

    @Test
    @DisplayName("Anthropic: a call waiting to try again after an overload is ended by the cancel its hook holds")
    void anthropicWaitToTryAgain() {
        var http = new FakeHttp().json(AnthropicStreamingTest.MODELS, 200, AnthropicStreamingTest.LIMITS)
                .json(AnthropicStreamingTest.MESSAGES, 529, OVERLOADED);
        aWaitEndsWithTheHooksStop(http, AnthropicStreamingTest.MESSAGES,
                c -> AnthropicStreamingTest.provider(http).chat(AnthropicStreamingTest.ASK, c));
        // Mutation: a wait that hands its hook no cancel -> the Stop is heard at the next attempt,
        // 24 s or more later.
    }

    @Test
    @DisplayName("OpenAI: a call waiting to try again after a rate limit is ended by the cancel its hook holds")
    void openAiWaitToTryAgain() {
        var http = new FakeHttp().json(OpenAiStreamingTest.COMPLETIONS, 429,
                "{\"error\":{\"type\":\"requests\",\"code\":\"rate_limit_exceeded\",\"message\":\"slow down\"}}");
        aWaitEndsWithTheHooksStop(http, OpenAiStreamingTest.COMPLETIONS,
                c -> OpenAiStreamingTest.provider(http).chat(AnthropicStreamingTest.ASK, c));
        // Mutation: the OpenAI provider hands the back-off no hook -> as above.
    }

    @Test
    @DisplayName("a wait ended by a hook that does not stop ends the call with the failure it was waiting to retry")
    void aWaitEndedWithNoStop() {
        var http = new FakeHttp().json(AnthropicStreamingTest.MODELS, 200, AnthropicStreamingTest.LIMITS)
                .json(AnthropicStreamingTest.MESSAGES, 529, OVERLOADED);
        var hook = new TaskHook(false);
        hook.stopIn(500);
        var e = assertTimeoutPreemptively(Duration.ofSeconds(20),
                () -> assertThrows(LlmException.class, () -> AnthropicStreamingTest.provider(http)
                        .chat(AnthropicStreamingTest.ASK, LlmRequestConfig.DEFAULT.withProgress(hook))),
                "the wait ran to its end");
        assertTrue(e.isOverloaded(), e.getMessage());
        assertEquals(1, http.to(AnthropicStreamingTest.MESSAGES).size(), "no retry after the wait was ended");
    }
}
