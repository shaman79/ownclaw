package com.ownclaw.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Serializes access to the shared Ollama instance.
 * Only one inference request may run at a time (GPU bottleneck).
 * Skill execution (ProcessBuilder) does NOT go through this semaphore.
 */
@Component
public class OllamaSemaphore {

    private static final Logger log = LoggerFactory.getLogger(OllamaSemaphore.class);
    private static final long ACQUIRE_TIMEOUT_SECONDS = 300; // 5 min max wait

    private final Semaphore semaphore = new Semaphore(1, true); // fair ordering

    /**
     * Acquire exclusive Ollama access. Blocks until available or timeout.
     *
     * @throws LlmException if timeout is exceeded
     */
    public void acquire() {
        try {
            if (!semaphore.tryAcquire(ACQUIRE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new LlmException("ollama", "Semaphore timeout — Ollama busy for "
                        + ACQUIRE_TIMEOUT_SECONDS + "s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("ollama", "Interrupted waiting for Ollama", 0, e);
        }
    }

    /**
     * Release Ollama access. Must be called in a finally block.
     */
    public void release() {
        semaphore.release();
    }

    /**
     * Number of threads currently waiting for Ollama.
     */
    public int getQueueLength() {
        return semaphore.getQueueLength();
    }
}
