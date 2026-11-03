package com.ownclaw.llm;

/**
 * Thrown when an LLM provider call fails.
 */
public class LlmException extends RuntimeException {

    private final String provider;
    private final int httpStatus;
    private final LlmResponse reply;

    public LlmException(String provider, String message) {
        this(provider, message, 0, null);
    }

    public LlmException(String provider, String message, int httpStatus, Throwable cause) {
        this(provider, message, httpStatus, cause, null);
    }

    /** A failure that came with a reply: the provider answered, and its answer is no answer. */
    protected LlmException(String provider, String message, int httpStatus, Throwable cause,
                           LlmResponse reply) {
        super("[" + provider + "] " + message, cause);
        this.provider = provider;
        this.httpStatus = httpStatus;
        this.reply = reply;
    }

    public String getProvider() { return provider; }
    public int getHttpStatus() { return httpStatus; }

    /**
     * The reply the provider sent, when the call failed because that reply is no answer --
     * refused ({@link ProviderRefused}), cut off ({@link OutputTruncated}), or holding a tool call
     * that cannot be run ({@link MalformedToolCall}) -- for its token counts: every one of them
     * was billed. Null when there was no reply. Its content is not an answer.
     */
    public LlmResponse reply() { return reply; }

    public boolean isRateLimit() { return httpStatus == 429; }

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

    /**
     * No reply because the connection failed: no internet, a name that does not resolve, a host
     * that does not answer, a stream that broke off. Not a reply that came and could not be read,
     * though Jackson's exceptions are IOExceptions too.
     */
    public boolean connectionFailed() {
        for (Throwable t = getCause(); t != null; t = t.getCause()) {
            if (t instanceof com.fasterxml.jackson.core.JsonProcessingException) return false;
            if (t instanceof java.io.IOException) return true;
        }
        return false;
    }

    /**
     * No connection could be opened at all: nothing listens there, the name does not resolve, or
     * there is no route to it. Unlike a stream that broke off, asking again at once ends the same.
     */
    public boolean cannotConnect() {
        for (Throwable t = getCause(); t != null; t = t.getCause()) {
            if (t instanceof java.net.ConnectException || t instanceof java.net.UnknownHostException
                    || t instanceof java.net.NoRouteToHostException) return true;
        }
        return false;
    }

    /**
     * The provider cannot be reached, or will not serve this account at all: the connection
     * failed, the key is rejected (401, 403), the account is not billed (402, or Anthropic's 400
     * "Your credit balance is too low"), or the provider kept failing ({@link #isRetryable}) -- which,
     * from a cloud provider, reaches a caller only once {@link RateLimitBackoff} has given up.
     * Not an answer to this request: a task can go on on another model. A refusal, a request too
     * long, a request the provider read and rejected is none of these.
     */
    public boolean unreachable() {
        return unreachableBecause() != null;
    }

    /** Why the provider is {@link #unreachable}, in words, or null when it is not. */
    public String unreachableBecause() {
        if (connectionFailed()) return "no connection to it";
        if (httpStatus == 401) return "it rejected the API key (HTTP 401)";
        if (httpStatus == 403) return "it refused this account (HTTP 403)";
        if (httpStatus == 402 || httpStatus == 400 && getMessage().contains("Your credit balance is too low")) {
            return "the account has no credit left";
        }
        if (isRetryable()) return "it kept failing (HTTP " + httpStatus + ") however long it was given";
        return null;
    }
}
