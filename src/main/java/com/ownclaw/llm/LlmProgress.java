package com.ownclaw.llm;

/**
 * Told about every event of a streamed reply as it arrives, keep-alive pings included -- and,
 * while the call is under way, how to end it, and what an attempt that ended without a reply
 * had been billed for.
 * <p>
 * Replies are streamed, so a call that runs for many minutes still shows, event by event, that
 * it is alive -- and this is where a caller sees that, and what each event carried
 * ({@link #received}). It is also how a caller ends an in-flight
 * call: whatever the hook throws reaches the caller unchanged, never wrapped in an
 * {@link LlmException}, and the provider closes the stream on its way out. The hook runs on the
 * calling thread between two reads of the stream, so it has to be quick.
 * <p>
 * A call can go a long time with no event on which the hook could throw: Ollama sends nothing
 * until it has loaded the model and read the whole prompt, which can take most of an hour; the
 * Anthropic provider asks the Models API for the model's limits before its first request; and a
 * call that failed in a way worth retrying waits, for minutes, before it tries again
 * ({@link RateLimitBackoff}). For each of these the hook is handed the cancel ({@link #calling}).
 * A call ended with it gets nothing more, and the provider then asks the hook once more
 * ({@link #onProgress}), so that there too what the hook throws is what the caller gets.
 */
@FunctionalInterface
public interface LlmProgress {

    /** The hook of a call that asked for none. */
    LlmProgress NONE = () -> { };

    void onProgress();

    /** Which part of a reply an event carried text of ({@link #received}). */
    enum Part {
        /** The model's reasoning before it answers: Ollama's thinking, Anthropic's thinking blocks. */
        REASONING,
        /** The answer's text. */
        ANSWER,
        /** A tool call that begins: the text is its name. */
        CALL,
        /** The arguments of the tool call under way, whole or a fragment of them. */
        ARGUMENTS
    }

    /**
     * What an event of the reply carried, part by part, as it arrives: called by the provider on
     * the calling thread after {@link #onProgress} for the same event, once for each part the
     * event carried text of -- a ping or a closing event carries none, and is not reported here.
     * It runs between two reads of the stream, so it has to be quick too, and what it throws
     * leaves the provider unchanged, closing the stream, as what {@link #onProgress} throws does.
     */
    default void received(Part part, String text) { }

    /**
     * The call is under way -- a request has been sent, or the call is waiting to send one again
     * -- and {@code cancel} ends it, whether or not anything has arrived; {@code null} once that
     * request has returned or that wait is over. Called by the provider on the calling thread.
     */
    default void calling(Runnable cancel) { }

    /**
     * What an attempt that ended without a reply had been billed for when it ended: the counts
     * its stream had reported by then. A reply stopped part-way, or cut by a timeout, a dropped
     * connection or an error event, was billed all the same, and no reply carries its counts.
     * They are what the stream said, and Anthropic reports a reply's output only when it ends, so
     * the output of a reply stopped part-way is mostly not in them. Reported once for each such
     * attempt, on the calling thread, before the call goes on to its next attempt or ends; what it
     * throws takes the place of whatever was ending the call. Only the cloud providers report it,
     * whose calls are billed: a local model's counts come on its last line, which completes the
     * reply.
     */
    default void billed(LlmResponse.Usage usage) { }
}
