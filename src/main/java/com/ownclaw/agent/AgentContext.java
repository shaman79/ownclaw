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

    private volatile boolean cancelled;
    private String conversationSummary;
    private String userPreferences;

    /** Deterministic capability hint from CapabilityResolver (null if no gap detected). */
    private CapabilityResolver.CapabilityHint capabilityHint;

    /** Credential keys available in the vault for this user (loaded once at task start). */
    private List<String> credentialKeys = List.of();

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

    public boolean isCancelled() { return cancelled; }
    public void cancel() { this.cancelled = true; }

    public String conversationSummary() { return conversationSummary; }
    public void setConversationSummary(String summary) { this.conversationSummary = summary; }

    public String userPreferences() { return userPreferences; }
    public void setUserPreferences(String prefs) { this.userPreferences = prefs; }

    public CapabilityResolver.CapabilityHint capabilityHint() { return capabilityHint; }
    public void setCapabilityHint(CapabilityResolver.CapabilityHint hint) { this.capabilityHint = hint; }

    // ── Credential keys ──

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
