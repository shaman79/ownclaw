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
    private final int partIndex;
    private final int offset;

    /** Refused for want of a context. */
    public EgressRefused(String provider) {
        super(provider, "cloud call refused: no egress context — every cloud call must be made "
                + "on behalf of a task (or explicitly as none); nothing was sent");
        this.handle = 0;
        this.partIndex = -1;
        this.offset = -1;
    }

    /** Refused on a canary hit. */
    public EgressRefused(String provider, int handle, String tool, int partIndex, String partKind,
                         int offset) {
        super(provider, String.format(
                "prompt part %d (%s) contains PRIVATE artifact $%d (%s) at offset %,d; nothing was sent",
                partIndex, partKind, handle, tool, offset));
        this.handle = handle;
        this.partIndex = partIndex;
        this.offset = offset;
    }

    /** The artifact whose bytes were found, or 0 when refused for want of a context. */
    public int handle() { return handle; }
    public int partIndex() { return partIndex; }
    public int offset() { return offset; }
}
