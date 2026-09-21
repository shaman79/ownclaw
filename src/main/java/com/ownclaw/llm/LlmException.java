package com.ownclaw.llm;

/**
 * Thrown when an LLM provider call fails.
 */
public class LlmException extends RuntimeException {

    private final String provider;
    private final int httpStatus;

    public LlmException(String provider, String message) {
        this(provider, message, 0, null);
    }

    public LlmException(String provider, String message, int httpStatus, Throwable cause) {
        super("[" + provider + "] " + message, cause);
        this.provider = provider;
        this.httpStatus = httpStatus;
    }

    public String getProvider() { return provider; }
    public int getHttpStatus() { return httpStatus; }

    public boolean isRateLimit() { return httpStatus == 429; }
    public boolean isAuthError() { return httpStatus == 401 || httpStatus == 403; }

    /**
     * Anthropic's "overloaded" response. It is capacity pressure, not a fault in the request.
     * <p>
     * It is a distinct status from 429 and was therefore not treated as a rate limit, so it
     * skipped the backoff entirely and surfaced as a reasoning failure within seconds. Three of
     * those in a row abort the task — so a few moments of provider load could end a scheduled
     * run that would have succeeded on a retry.
     */
    public boolean isOverloaded() { return httpStatus == 529; }

    /**
     * Whether trying again unchanged could plausibly succeed.
     * <p>
     * 429 and 529 are explicit "come back shortly". A 5xx is the provider failing rather than
     * the request being wrong, and is worth one or two attempts. Everything else — a malformed
     * request, a bad key — will fail identically however many times it is sent, and retrying
     * only delays an error the caller needs to see.
     */
    public boolean isRetryable() {
        return isRateLimit() || isOverloaded() || (httpStatus >= 500 && httpStatus < 600);
    }
}
