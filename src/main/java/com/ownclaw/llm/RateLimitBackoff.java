package com.ownclaw.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Automatic exponential back-off for the provider failures worth another try
 * ({@link LlmException#isRetryable()}): a rate limit (HTTP 429), an overload (529, or an
 * overloaded_error in the middle of a stream) and a server error (5xx).
 *
 * <p>This is NOT a fixed rate limiter — it only activates reactively when a call fails that
 * way. The back-off schedule scales exponentially with jitter to avoid thundering-herd problems.
 *
 * <p>Schedule: 30s → 60s → 120s → 240s, each ±20% (4 retries, 7.5 min in all before jitter).
 * Most Anthropic/OpenAI rate limits are per-minute, so a 30s initial wait is usually enough to
 * clear the window.
 *
 * <p>A wait is part of the call. Its cancel is handed to the call's progress hook
 * ({@link LlmProgress#calling}), as a request's is, so Stop and the stall watchdog end a call
 * that is waiting to try again at once, as they end one that is waiting for its reply.
 */
public final class RateLimitBackoff {

    private static final Logger log = LoggerFactory.getLogger(RateLimitBackoff.class);

    /** Maximum number of retries before giving up. */
    private static final int MAX_RETRIES = 4;

    /** Initial wait time after the first failure (30 seconds). */
    private static final long INITIAL_WAIT_MS = 30_000;

    /** Multiplier applied after each retry (doubles each time). */
    private static final double BACKOFF_MULTIPLIER = 2.0;

    /** Random jitter range (±20%) to spread out concurrent retries. */
    private static final double JITTER_FACTOR = 0.2;

    private RateLimitBackoff() { /* utility class */ }

    /**
     * Execute a supplier, trying again after an exponentially increasing wait when it fails in a
     * way worth another try ({@link LlmException#isRetryable()}). Any other exception propagates
     * at once.
     *
     * @param call         the LLM call to execute
     * @param providerName human-readable provider name for log messages
     * @param progress     the call's progress hook: each wait hands it the wait's cancel, and a
     *                     wait ended with it ends the call -- with what the hook throws when it
     *                     is asked once more, as for a request ended that way, or else with the
     *                     failure the wait was going to retry
     * @param <T>          the return type (typically {@link LlmResponse})
     * @return the result of the successful call
     * @throws LlmException if retries are exhausted, a wait was ended, or a failure is not worth
     *                      another try
     */
    public static <T> T execute(Supplier<T> call, String providerName, LlmProgress progress) {
        int attempt = 0;
        long waitMs = INITIAL_WAIT_MS;

        while (true) {
            try {
                return call.get();
            } catch (LlmException e) {
                if (!e.isRetryable()) {
                    throw e;   // a malformed request or bad key fails identically on every try
                }

                attempt++;
                if (attempt > MAX_RETRIES) {
                    log.error("{} on {}: {} retries exhausted — giving up", kind(e), providerName, MAX_RETRIES);
                    throw e;
                }

                // Add ±20% jitter to avoid thundering herd
                long jitter = (long) (waitMs * JITTER_FACTOR * (Math.random() * 2 - 1));
                long actualWait = waitMs + jitter;

                log.warn("{} on {} (attempt {}/{}). Backing off {}s before retry...",
                        kind(e), providerName, attempt, MAX_RETRIES, actualWait / 1000);

                if (waitEnded(actualWait, progress, providerName)) {
                    log.info("{}: the wait before retry {} was ended, and the call with it", providerName, attempt);
                    progress.onProgress();
                    throw e;
                }

                waitMs = (long) (waitMs * BACKOFF_MULTIPLIER);
            }
        }
    }

    private static String kind(LlmException e) {
        return e.isRateLimit() ? "Rate limit" : e.isOverloaded() ? "Provider overloaded"
                : "Provider error " + e.getHttpStatus();
    }

    /**
     * Wait {@code ms}, holding out the wait's cancel to the hook meanwhile.
     *
     * @return whether the cancel ended the wait
     */
    private static boolean waitEnded(long ms, LlmProgress progress, String providerName) {
        var cancelled = new CountDownLatch(1);
        progress.calling(cancelled::countDown);
        try {
            return cancelled.await(ms, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new LlmException(providerName, "the wait before trying again was interrupted", 0, ie);
        } finally {
            progress.calling(null);
        }
    }
}
