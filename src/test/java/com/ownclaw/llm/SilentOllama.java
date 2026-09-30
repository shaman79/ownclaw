package com.ownclaw.llm;

import java.util.concurrent.TimeUnit;

/**
 * For tests outside this package: the real Ollama provider, over a server that lists its model
 * and describes it, and sends nothing to {@code /api/chat} until the call is cancelled -- a
 * model still loading. Nothing touches the network.
 */
public final class SilentOllama {

    private final FakeHttp http = new FakeHttp()
            .json("/api/tags", 200, "{\"models\":[{\"name\":\"" + OllamaStreamingTest.MODEL + "\"}]}")
            .json(OllamaStreamingTest.SHOW, 200, OllamaStreamingTest.show("qwen35moe", 262_144))
            .silent(OllamaStreamingTest.CHAT);
    private final LlmProvider provider = OllamaStreamingTest.provider(OllamaStreamingTest.config(), http);

    public LlmProvider provider() {
        return provider;
    }

    /** Wait until a chat request is under way -- sent, and answered with nothing. */
    public void awaitCall() throws InterruptedException {
        if (!http.silenced.await(10, TimeUnit.SECONDS)) throw new AssertionError("no chat request was made");
    }
}
