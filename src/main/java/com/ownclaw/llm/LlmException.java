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
}
