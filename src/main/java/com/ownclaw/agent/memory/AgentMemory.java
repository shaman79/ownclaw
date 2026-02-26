package com.ownclaw.agent.memory;

import java.util.List;

/**
 * Abstraction over the agent's persistent memory.
 * Memory allows the agent to learn from past executions and improve over time.
 *
 * Three tiers:
 *   1. Working memory — the current trajectory (handled by AgentTrajectory, not here)
 *   2. Episodic memory — past task summaries, searchable by similarity
 *   3. Semantic memory — distilled facts and preferences
 */
public interface AgentMemory {

    /**
     * Store an episodic memory — a summary of a completed task execution.
     *
     * @param userId   the user who executed the task
     * @param taskId   unique task identifier
     * @param summary  a concise summary of what was attempted and what happened
     * @param outcome  whether the task succeeded
     * @param tags     searchable tags derived from the task
     */
    void storeEpisode(String userId, String taskId, String summary, boolean outcome, List<String> tags);

    /**
     * Recall episodic memories relevant to a query.
     * Returns the most relevant past experiences.
     *
     * @param userId   the user to search memories for
     * @param query    natural language query or task description
     * @param maxResults maximum number of memories to return
     * @return list of relevant memory entries, most relevant first
     */
    List<MemoryEntry> recallEpisodes(String userId, String query, int maxResults);

    /**
     * Store a semantic fact — a distilled piece of knowledge.
     *
     * @param userId the user this fact belongs to
     * @param key    a unique key for this fact (for upsert behavior)
     * @param fact   the fact content
     */
    void storeFact(String userId, String key, String fact);

    /**
     * Retrieve all semantic facts for a user.
     */
    List<MemoryEntry> getFacts(String userId);

    /**
     * A single memory entry.
     */
    record MemoryEntry(
            String id,
            String content,
            boolean outcome,
            List<String> tags,
            long timestamp
    ) {}
}
