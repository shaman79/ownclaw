package com.ownclaw.agent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Carries all contextual information for a single agent execution.
 * This is the unified state object threaded through the agent loop.
 */
public class AgentContext {

    private final String userId;
    private final String taskId;
    private final String originalMessage;
    private final AgentTrajectory trajectory;
    private final Map<String, Object> metadata;
    private final long startTimeMs;
    /** Timestamp of the last forward progress (step completion, LLM response, etc.). */
    private volatile long lastProgressMs;

    /**
     * True when nobody is waiting for this task — the scheduler submitted it, or the user
     * explicitly sent it to the background. It is a fact about ORIGIN, not a guess: the
     * scheduler genuinely has no one watching. What it buys is permission to spend local
     * inference time, which is free but slow, on work that would otherwise be truncated.
     */
    private boolean unattended;

    private volatile boolean cancelled;
    /** Authoritative external cancellation source (the Stop button). See {@link #isCancelled()}. */
    private volatile java.util.function.BooleanSupplier externalCancel;
    private String conversationSummary;
    private String userPreferences;

    /** Deterministic capability hint from CapabilityResolver (null if no gap detected). */
    private CapabilityResolver.CapabilityHint capabilityHint;

    /** Credential keys available in the vault for this user (loaded once at task start). */
    private List<String> credentialKeys = List.of();

    /** Null until a step restricts the set; see {@link #offeredTools()}. */
    private volatile java.util.Set<String> offeredTools;

    // Per-task token usage counters
    private int localTokens;
    private int cloudTokens;

    /** File attachment IDs associated with the current user message. */
    private List<String> attachmentIds = List.of();

    public AgentContext(String userId, String taskId, String originalMessage) {
        this.userId = userId;
        this.taskId = taskId;
        this.originalMessage = originalMessage;
        this.trajectory = new AgentTrajectory();
        this.metadata = new HashMap<>();
        this.startTimeMs = System.currentTimeMillis();
        this.lastProgressMs = this.startTimeMs;
        this.cancelled = false;
    }

    public String userId() { return userId; }
    public String taskId() { return taskId; }
    public String originalMessage() { return originalMessage; }
    public AgentTrajectory trajectory() { return trajectory; }
    public Map<String, Object> metadata() { return metadata; }
    public long startTimeMs() { return startTimeMs; }

    public long elapsedMs() {
        return System.currentTimeMillis() - startTimeMs;
    }

    /** Mark forward progress (resets stall timer). */
    public void markProgress() { this.lastProgressMs = System.currentTimeMillis(); }

    /** Milliseconds since the last forward progress. */
    public long msSinceLastProgress() { return System.currentTimeMillis() - lastProgressMs; }

    /**
     * Whether this task should stop, consulting both the local flag and the external source
     * the Stop button writes to.
     * <p>
     * This used to read the local flag only, and nothing ever called {@link #cancel()} — zero
     * callers repo-wide — so it was permanently false for the lifetime of every task. The main
     * loop polls {@code cancellationService} itself at the top of each step, so Stop appeared to
     * work; what was dead was everything <em>inside</em> a step. The per-step check in
     * {@code LocalExecutor} and the {@code context::isCancelled} supplier handed to every tool
     * could never fire, so a running tool or an in-flight local call — 60 to 133 seconds on this
     * deployment, and up to ten of them in a delegated plan — carried on to completion after the
     * user pressed Stop.
     * <p>
     * Consulting the external source here revives all of those checks at once, rather than
     * relying on each caller to remember to poll two places.
     */
    public boolean isUnattended() { return unattended; }
    public void setUnattended(boolean unattended) { this.unattended = unattended; }

    public boolean isCancelled() {
        if (cancelled) return true;
        java.util.function.BooleanSupplier ext = externalCancel;
        return ext != null && ext.getAsBoolean();
    }

    public void cancel() { this.cancelled = true; }

    /**
     * Attach the authoritative cancellation source for this task, normally
     * {@code () -> cancellationService.isCancelled(userId)}. Set once at task start.
     */
    public void setExternalCancel(java.util.function.BooleanSupplier supplier) { this.externalCancel = supplier; }

    public String conversationSummary() { return conversationSummary; }
    public void setConversationSummary(String summary) { this.conversationSummary = summary; }

    public String userPreferences() { return userPreferences; }
    public void setUserPreferences(String prefs) { this.userPreferences = prefs; }

    public CapabilityResolver.CapabilityHint capabilityHint() { return capabilityHint; }
    public void setCapabilityHint(CapabilityResolver.CapabilityHint hint) { this.capabilityHint = hint; }

    // ── Credential keys ──

    /**
     * The tool names offered to the model on the current step, or null for "no restriction".
     * <p>
     * Set by {@link ThinkingEngine} each step and read by {@link AgentLoop} before a registry
     * tool runs. It exists because withholding a tool from the provider's tools array is not
     * enforcement: the text protocol is still parsed, {@code tryParseAction} accepts any name,
     * and the loop resolves it straight off the full registry. This is the one place the
     * restriction becomes structural rather than advisory.
     */
    public java.util.Set<String> offeredTools() { return offeredTools; }
    public void setOfferedTools(java.util.Set<String> names) { this.offeredTools = names; }

    public List<String> credentialKeys() { return credentialKeys; }
    public void setCredentialKeys(List<String> keys) { this.credentialKeys = keys != null ? keys : List.of(); }

    // ── Token tracking ──

    public void addLocalTokens(int tokens) { this.localTokens += tokens; }
    public void addCloudTokens(int tokens) { this.cloudTokens += tokens; }
    public int localTokens() { return localTokens; }
    public int cloudTokens() { return cloudTokens; }
    public int totalTokens() { return localTokens + cloudTokens; }

    // ── File attachments ──

    public List<String> attachmentIds() { return attachmentIds; }
    public void setAttachmentIds(List<String> ids) { this.attachmentIds = ids != null ? ids : List.of(); }
}
