package com.ownclaw.llm;

/**
 * Shared utilities for cleaning and normalizing LLM output.
 */
public final class LlmOutputUtils {

    private LlmOutputUtils() { }

    /**
     * Strip markdown code fences (```json ... ```) that LLMs sometimes wrap around JSON.
     * Safe to call on any string — returns it unchanged if no fences are present.
     */
    public static String stripCodeFences(String text) {
        if (text == null) return null;
        text = text.strip();
        if (text.startsWith("```")) {
            text = text.replaceAll("^```\\w*\\n?", "").replaceAll("\\n?```$", "");
        }
        return text.strip();
    }
}
