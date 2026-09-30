package com.ownclaw.llm;

/**
 * A cloud call the gateway would not send.
 * <p>
 * Either the call carried no {@link EgressContext} — unclassified means denied — or its body
 * contained a run of a PRIVATE artifact's bytes that nothing had allowed. Deterministic: the
 * same prompt refuses again, so a caller must not retry it as a transient failure.
 */
public final class EgressRefused extends LlmException {

    private final int handle;
    private final String tool;
    private final int partIndex;
    private final String partKind;
    private final int offset;

    /** Refused for want of a context. */
    public EgressRefused(String provider) {
        super(provider, "cloud call refused: no egress context — every cloud call must be made "
                + "on behalf of a task (or explicitly as none); nothing was sent");
        this.handle = 0;
        this.tool = null;
        this.partIndex = -1;
        this.partKind = null;
        this.offset = -1;
    }

    /**
     * Refused on a canary hit, or on a vault value that survived scrubbing.
     *
     * @param tool the tool that produced the result found, "vault:KEY" for a vault value, or
     *             null when the context does not say
     */
    public EgressRefused(String provider, int handle, String tool, int partIndex, String partKind,
                         int offset) {
        super(provider, String.format(
                "prompt part %d (%s) contains PRIVATE artifact {{%d}}%s at offset %,d; nothing was sent",
                partIndex, partKind, handle, tool == null ? "" : " (" + tool + ")", offset));
        this.handle = handle;
        this.tool = tool;
        this.partIndex = partIndex;
        this.partKind = partKind;
        this.offset = offset;
    }

    /** The artifact whose bytes were found, or 0 when none was: no context, or a vault value. */
    public int handle() { return handle; }
    /** The tool that produced it, "vault:KEY" for a vault value, or null. */
    public String tool() { return tool; }
    public int partIndex() { return partIndex; }
    /** The role of the part it was found in -- system, user, ... -- or null. */
    public String partKind() { return partKind; }
    public int offset() { return offset; }
}
