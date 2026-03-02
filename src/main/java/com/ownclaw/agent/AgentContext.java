package com.ownclaw.agent;

import java.util.HashMap;
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

    private volatile boolean cancelled;
    private String conversationSummary;
    private String userPreferences;

    // Per-task token usage counters
    private int localTokens;
    private int cloudTokens;

    public AgentContext(String userId, String taskId, String originalMessage) {
        this.userId = userId;
        this.taskId = taskId;
        this.originalMessage = originalMessage;
        this.trajectory = new AgentTrajectory();
        this.metadata = new HashMap<>();
        this.startTimeMs = System.currentTimeMillis();
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

    public boolean isCancelled() { return cancelled; }
    public void cancel() { this.cancelled = true; }

    public String conversationSummary() { return conversationSummary; }
    public void setConversationSummary(String summary) { this.conversationSummary = summary; }

    public String userPreferences() { return userPreferences; }
    public void setUserPreferences(String prefs) { this.userPreferences = prefs; }

    // ── Token tracking ──

    public void addLocalTokens(int tokens) { this.localTokens += tokens; }
    public void addCloudTokens(int tokens) { this.cloudTokens += tokens; }
    public int localTokens() { return localTokens; }
    public int cloudTokens() { return cloudTokens; }
    public int totalTokens() { return localTokens + cloudTokens; }
}
