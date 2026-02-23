package com.ownclaw.core;

import com.ownclaw.config.OwnClawConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Circuit breaker for Ollama and cloud LLM connections.
 * States: CLOSED (normal) → OPEN (blocking) → HALF_OPEN (testing).
 */
@Component
public class CircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreaker.class);

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private static final int FAILURE_THRESHOLD = 3;
    private static final long COOLDOWN_MS = 120_000; // 2 minutes

    private volatile State state = State.CLOSED;
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private volatile long lastFailureTime = 0;
    private final String name;

    /** For Spring bean — creates a default instance. Use {@link #named(String)} for specific breakers. */
    public CircuitBreaker() {
        this.name = "default";
    }

    private CircuitBreaker(String name) {
        this.name = name;
    }

    /** Create a named circuit breaker instance (not a Spring bean — use programmatically). */
    public static CircuitBreaker named(String name) {
        return new CircuitBreaker(name);
    }

    /**
     * Check if the circuit allows a request through.
     *
     * @return true if request is allowed
     */
    public boolean allowRequest() {
        return switch (state) {
            case CLOSED -> true;
            case OPEN -> {
                if (System.currentTimeMillis() - lastFailureTime > COOLDOWN_MS) {
                    state = State.HALF_OPEN;
                    log.info("Circuit breaker [{}]: OPEN -> HALF_OPEN (trying one request)", name);
                    yield true;
                }
                yield false;
            }
            case HALF_OPEN -> true; // allow the test request
        };
    }

    /** Record a successful call — resets the breaker. */
    public void recordSuccess() {
        if (state != State.CLOSED) {
            log.info("Circuit breaker [{}]: {} -> CLOSED", name, state);
        }
        state = State.CLOSED;
        failureCount.set(0);
    }

    /** Record a failed call — may trip the breaker. */
    public void recordFailure() {
        lastFailureTime = System.currentTimeMillis();
        if (state == State.HALF_OPEN) {
            state = State.OPEN;
            log.warn("Circuit breaker [{}]: HALF_OPEN -> OPEN (test request failed)", name);
            return;
        }
        int count = failureCount.incrementAndGet();
        if (count >= FAILURE_THRESHOLD) {
            state = State.OPEN;
            log.warn("Circuit breaker [{}]: CLOSED -> OPEN ({} failures)", name, count);
        }
    }

    public State getState() { return state; }
    public String getName() { return name; }
}
