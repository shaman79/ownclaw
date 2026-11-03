package com.ownclaw.agent.tools;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BooleanSupplier;

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

    /** How often a task waiting for a skill's lock is said to wait still ({@link #take}). */
    static final long WAITING_EVERY_MS = 30_000;

    /**
     * Take {@code lock} for a task. While another task holds it -- runs the skill, or changes it
     * -- the task is said to wait still, at once and then every {@link #WAITING_EVERY_MS}
     * ({@code waiting}): what the stall watchdog counts as progress, as the wait for the local
     * model is. A Stop of the task ends the wait.
     *
     * @return whether it was taken: false when the task was stopped, or interrupted, first
     */
    public static boolean take(Lock lock, BooleanSupplier stopped, Runnable waiting) {
        long next = 0;
        try {
            while (!lock.tryLock(1, TimeUnit.SECONDS)) {
                if (stopped.getAsBoolean()) return false;
                long now = System.currentTimeMillis();
                if (now >= next) {
                    waiting.run();
                    next = now + WAITING_EVERY_MS;
                }
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
