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

    /** The replies stream in, one to each request in turn, the last to every request after it. */
    public static ScriptedAnthropic replying(String... streams) {
        var http = new FakeHttp();
        for (String stream : streams) http.on(AnthropicStreamingTest.MESSAGES, 200, "text/event-stream", stream);
        return new ScriptedAnthropic(http);
    }

    /** A reply that calls {@code tool} with {@code arguments}, a JSON object, and nothing beside it. */
    public static String calling(String tool, String arguments) {
        return AnthropicStreamingTest.start("claude-opus-5", 1_000, 0, 0)
                + AnthropicStreamingTest.tool(0, "toolu_" + tool, tool, arguments)
                + AnthropicStreamingTest.end("tool_use", null, 50);
    }

    /** A reply that says {@code words}, then calls {@code tool} with {@code arguments}, a JSON object. */
    public static String calling(String words, String tool, String arguments) {
        return AnthropicStreamingTest.start("claude-opus-5", 1_000, 0, 0)
                + AnthropicStreamingTest.text(0, words)
                + AnthropicStreamingTest.tool(1, "toolu_" + tool, tool, arguments)
                + AnthropicStreamingTest.end("tool_use", null, 50);
    }

    /** A reply the provider declines before anything is written, as {@code category}, naming no model to retry on. */
    public static String declining(String category) {
        return AnthropicStreamingTest.start("claude-opus-5", 1_000, 0, 0)
                + AnthropicStreamingTest.end("refusal", category, 0);
    }

    public LlmProvider provider() {
        return provider;
    }

    /** The anthropic-beta header of each request sent to /v1/messages, oldest first. */
    public java.util.List<String> betas() {
        return http.to(AnthropicStreamingTest.MESSAGES).stream().map(s -> s.header("anthropic-beta")).toList();
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
