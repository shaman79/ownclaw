package com.ownclaw.agent.tools;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * One lock for each skill, by its name, shared by every instance of it: a run holds it to read,
 * and what changes or removes the skill -- skill_create, a delete, a retirement -- holds it to
 * write, so it waits for the runs under way and no run starts while it works. Tasks of different
 * chats run side by side: two creating the same skill interleaved each other's files, and one
 * deleted a skill another was running.
 */
public final class SkillLocks {

    private static final ConcurrentHashMap<String, ReentrantReadWriteLock> LOCKS = new ConcurrentHashMap<>();

    private SkillLocks() {}

    /** The lock of the skill named {@code name}: fair, so a change waiting is not starved by runs. */
    public static ReentrantReadWriteLock of(String name) {
        return LOCKS.computeIfAbsent(name, n -> new ReentrantReadWriteLock(true));
    }
}
