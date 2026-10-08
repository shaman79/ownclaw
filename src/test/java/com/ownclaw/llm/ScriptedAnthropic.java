package com.ownclaw.llm;

/**
 * For tests outside this package: the real Anthropic provider, over a server that describes
 * claude-opus-5 and answers every request to /v1/messages the one way it was told to. Nothing
 * touches the network.
 */
public final class ScriptedAnthropic {

    private final FakeHttp http;
    private final LlmProvider provider;

    private ScriptedAnthropic(FakeHttp http) {
        this.http = http.json(AnthropicStreamingTest.MODELS, 200, AnthropicStreamingTest.LIMITS);
        this.provider = AnthropicStreamingTest.provider(this.http);
    }

    /** Anthropic is overloaded: every request is answered with HTTP 529. */
    public static ScriptedAnthropic overloaded() {
        return new ScriptedAnthropic(new FakeHttp()
                .json(AnthropicStreamingTest.MESSAGES, 529, SilentCallTest.OVERLOADED));
    }

    /**
     * The reply streams in: message_start saying {@code input} tokens of the prompt were read,
     * and {@code cacheRead} more from the cache; a ping; a text block of {@code pieces}; and the
     * end, {@code output} tokens written.
     */
    public static ScriptedAnthropic answering(int input, int cacheRead, int output, String... pieces) {
        String stream = AnthropicStreamingTest.start("claude-opus-5", input, 0, cacheRead)
                + AnthropicStreamingTest.ping() + AnthropicStreamingTest.text(0, pieces)
                + AnthropicStreamingTest.end("end_turn", null, output);
        return new ScriptedAnthropic(new FakeHttp()
                .on(AnthropicStreamingTest.MESSAGES, 200, "text/event-stream", stream));
    }

    public LlmProvider provider() {
        return provider;
    }

    /** How many requests were sent to /v1/messages. */
    public int requests() {
        return http.to(AnthropicStreamingTest.MESSAGES).size();
    }

    /** The bodies of the requests sent to /v1/messages, oldest first. */
    public java.util.List<String> bodies() {
        return http.to(AnthropicStreamingTest.MESSAGES).stream().map(FakeHttp.Sent::body).toList();
    }
}
