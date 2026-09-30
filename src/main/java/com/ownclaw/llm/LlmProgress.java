package com.ownclaw.llm;

/**
 * Told about every event of a streamed reply as it arrives, keep-alive pings included.
 * <p>
 * Replies are streamed, so a call that runs for many minutes still shows, event by event, that
 * it is alive -- and this is where a caller sees that. It is also how a caller ends an in-flight
 * call: whatever the hook throws reaches the caller unchanged, never wrapped in an
 * {@link LlmException}, and the provider closes the stream on its way out. The hook runs on the
 * calling thread between two reads of the stream, so it has to be quick.
 */
@FunctionalInterface
public interface LlmProgress {

    /** The hook of a call that asked for none. */
    LlmProgress NONE = () -> { };

    void onProgress();
}
