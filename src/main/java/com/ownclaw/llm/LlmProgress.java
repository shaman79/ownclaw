package com.ownclaw.llm;

/**
 * Told about every event of a streamed reply as it arrives, keep-alive pings included -- and,
 * while the request is under way, how to end it.
 * <p>
 * Replies are streamed, so a call that runs for many minutes still shows, event by event, that
 * it is alive -- and this is where a caller sees that. It is also how a caller ends an in-flight
 * call: whatever the hook throws reaches the caller unchanged, never wrapped in an
 * {@link LlmException}, and the provider closes the stream on its way out. The hook runs on the
 * calling thread between two reads of the stream, so it has to be quick.
 * <p>
 * A call can be silent for a long time before its first event -- Ollama sends nothing until it
 * has loaded the model and read the whole prompt, which can take most of an hour -- so no event
 * comes on which the hook could throw. For that, the provider hands the hook the call's cancel
 * ({@link #calling}). A call ended with it gets nothing more from the server, and its provider
 * then asks the hook once more ({@link #onProgress}), so that there too what the hook throws is
 * what the caller gets.
 */
@FunctionalInterface
public interface LlmProgress {

    /** The hook of a call that asked for none. */
    LlmProgress NONE = () -> { };

    void onProgress();

    /**
     * The request has been sent and {@code cancel} ends it, whether or not anything has arrived;
     * {@code null} once the call has returned. Called by the provider on the calling thread.
     */
    default void calling(Runnable cancel) { }
}
