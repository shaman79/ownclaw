package com.ownclaw.agent.memory;

import java.util.List;

/**
 * Abstraction over the agent's persistent memory.
 * Memory allows the agent to learn from past executions and improve over time.
 * <p>
 * A method whose read or write fails throws: its caller says so. It never answers as if the
 * store were empty, or as if a write had been made.
 *
 * Three tiers:
 *   1. Working memory — the current trajectory (handled by AgentTrajectory, not here)
 *   2. Episodic memory — past task summaries, searchable by similarity
 *   3. Semantic memory — distilled facts and preferences
 */
public interface AgentMemory {

    /**
     * Store an episodic memory — the record of a finished task execution.
     *
     * @param userId   the user who executed the task
     * @param taskId   unique task identifier
     * @param summary  what was asked and what the task answered, whole
     * @param outcome  whether the task succeeded
     * @param tags     searchable tags derived from the task
     */
    void storeEpisode(String userId, String taskId, String summary, boolean outcome, List<String> tags);

    /**
     * Every episode of this user that has a word of the query, whole: the most words in common
     * first, and the newest first among equals. Asked for by the agent (memory_manage
     * action=recall), never put into a prompt unasked.
     *
     * @param userId the user to search memories for
     * @param query  words to look for
     */
    Recall recallEpisodes(String userId, String query);

    /**
     * What a recall looked for, and what it found.
     *
     * @param words    the words of the query that were looked for, lowercased, in order
     * @param skipped  the words that were not: ones nearly every task has -- "the", "and", a
     *                 single letter -- which would find every episode; so the caller can say so
     * @param episodes every episode with at least one of {@code words}, as above; none when
     *                 there is no word to look for
     */
    record Recall(List<String> words, List<String> skipped, List<MemoryEntry> episodes) {}

    /**
     * Store a semantic fact — a distilled piece of knowledge.
     *
     * @param userId the user this fact belongs to
     * @param key    a unique key for this fact (for upsert behavior)
     * @param fact   the fact content
     */
    void storeFact(String userId, String key, String fact);

    /**
     * Delete a semantic fact by key.
     *
     * @param userId the user this fact belongs to
     * @param key    the fact key to delete
     * @return true if a fact was deleted, false if not found
     */
    boolean deleteFact(String userId, String key);

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
