package com.ownclaw.llm;

import java.util.concurrent.TimeUnit;

/**
 * For tests outside this package: the real Ollama provider, over a server that lists its model
 * and describes it, and sends nothing to {@code /api/chat} until the call is cancelled -- a
 * model still loading -- or, made with a silence and answers, until that silence is over, and
 * then answers each chat request in turn, as Ollama does once it has loaded the model and read
 * the prompt. Nothing touches the network.
 */
public final class SilentOllama {

    private final FakeHttp http = new FakeHttp()
            .json("/api/tags", 200, "{\"models\":[{\"name\":\"" + OllamaStreamingTest.MODEL + "\"}]}")
            .json(OllamaStreamingTest.SHOW, 200, OllamaStreamingTest.show("qwen35moe", 262_144));
    private final LlmProvider provider = OllamaStreamingTest.provider(OllamaStreamingTest.config(), http);

    /** Silent until the call is cancelled. */
    public SilentOllama() {
        http.silent(OllamaStreamingTest.CHAT);
    }

    /**
     * Silent for {@code silentMs} on every chat request, then answering with the next of
     * {@code answers}; the last one repeats.
     */
    public SilentOllama(long silentMs, String... answers) {
        http.slow(OllamaStreamingTest.CHAT, silentMs);
        for (String a : answers) http.on(OllamaStreamingTest.CHAT, 200, "application/x-ndjson", a);
    }

    /** A whole reply whose text is {@code content}, streamed as Ollama streams one. */
    public static String says(String content) {
        return OllamaStreamingTest.line(content, null) + OllamaStreamingTest.last("stop", 30, 8);
    }

    public LlmProvider provider() {
        return provider;
    }

    /** How many chat requests were made. */
    public int chats() {
        return http.to(OllamaStreamingTest.CHAT).size();
    }

    /** Wait until a chat request is under way -- sent, and answered with nothing. */
    public void awaitCall() throws InterruptedException {
        if (!http.silenced.await(10, TimeUnit.SECONDS)) throw new AssertionError("no chat request was made");
    }
}
