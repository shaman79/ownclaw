package com.ownclaw.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/**
 * Automatic exponential back-off for LLM rate-limit (HTTP 429) errors.
 *
 * <p>This is NOT a fixed rate limiter — it only activates reactively when a
 * provider actually returns a 429. The back-off schedule scales exponentially
 * with jitter to avoid thundering-herd problems.
 *
 * <p>Schedule: 30s → 60s → 120s → 240s (4 retries, ~7.5 min total worst case).
 * Most Anthropic/OpenAI rate limits are per-minute, so a 30s initial wait is
 * usually enough to clear the window.
 */
public final class RateLimitBackoff {

    private static final Logger log = LoggerFactory.getLogger(RateLimitBackoff.class);

    /** Maximum number of retries before giving up. */
    private static final int MAX_RETRIES = 4;

    /** Initial wait time after the first 429 (30 seconds). */
    private static final long INITIAL_WAIT_MS = 30_000;

    /** Multiplier applied after each retry (doubles each time). */
    private static final double BACKOFF_MULTIPLIER = 2.0;

    /** Random jitter range (±20%) to spread out concurrent retries. */
    private static final double JITTER_FACTOR = 0.2;

    private RateLimitBackoff() { /* utility class */ }

    /**
     * Execute a supplier with automatic retry on rate-limit errors.
     *
     * <p>If the supplier throws an {@link LlmException} with
     * {@link LlmException#isRateLimit()} == true, the call is retried after
     * an exponentially increasing delay. Non-rate-limit exceptions propagate
     * immediately.
     *
     * @param call         the LLM call to execute
     * @param providerName human-readable provider name for log messages
     * @param <T>          the return type (typically {@link LlmResponse})
     * @return the result of the successful call
     * @throws LlmException if retries are exhausted or a non-rate-limit error occurs
     */
    public static <T> T execute(Supplier<T> call, String providerName) {
        int attempt = 0;
        long waitMs = INITIAL_WAIT_MS;

        while (true) {
            try {
                return call.get();
            } catch (LlmException e) {
                if (!e.isRateLimit()) {
                    throw e;   // non-429 errors propagate immediately
                }

                attempt++;
                if (attempt > MAX_RETRIES) {
                    log.error("Rate limit: {} retries exhausted for {} — giving up", MAX_RETRIES, providerName);
                    throw e;
                }

                // Add ±20% jitter to avoid thundering herd
                long jitter = (long) (waitMs * JITTER_FACTOR * (Math.random() * 2 - 1));
                long actualWait = waitMs + jitter;

                log.warn("Rate limit hit on {} (attempt {}/{}). Backing off {}s before retry...",
                        providerName, attempt, MAX_RETRIES, actualWait / 1000);

                try {
                    Thread.sleep(actualWait);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new LlmException(providerName,
                            "Rate-limit back-off interrupted after " + attempt + " retries", 429, ie);
                }

                waitMs = (long) (waitMs * BACKOFF_MULTIPLIER);
            }
        }
    }
}
