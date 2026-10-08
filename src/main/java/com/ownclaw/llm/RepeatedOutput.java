package com.ownclaw.llm;

/**
 * A reply ended part-way because it had become a verbatim loop: the model was writing the same
 * text over and over, and would have gone on until its context window was full -- hours, for the
 * local model, which is sent no output limit. Thrown by a task's progress hook while the reply
 * streams in ({@code com.ownclaw.agent.LiveCall}), so it reaches the caller as what the hook
 * threw, the stream closed behind it. Its message says what was seen, in figures only -- how much
 * text repeated how many times, after how long and how much of it -- and nothing of the text
 * itself, so it may be shown and logged whatever the model had read. There is no reply: the call
 * was ended before it had one.
 */
public final class RepeatedOutput extends LlmException {

    private final String seen;

    /**
     * @param seen what was seen, as the message says it: "the local model repeated the same 300
     *             characters 4 times, after 23m 5s and 41,200 characters of reasoning"
     */
    public RepeatedOutput(String provider, String seen) {
        super(provider, seen, 0, null, null);
        this.seen = seen;
    }

    /** What was seen, without the provider's name the message opens with. */
    public String seen() { return seen; }
}
