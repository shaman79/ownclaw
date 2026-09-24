package com.ownclaw.llm;

/**
 * A single message in an LLM conversation.
 */
public record LlmMessage(Role role, String content) {

    /**
     * Where a prompt's cached prefix ends: in the system prompt, and in the first message of a
     * task's first call. Anthropic's provider splits there and marks the part before it for the
     * prompt cache; the marker itself is never sent.
     */
    public static final String CACHE_BOUNDARY = "\n<!-- CACHE_BOUNDARY -->\n";

    public enum Role {
        SYSTEM, USER, ASSISTANT;

        public String apiValue() {
            return name().toLowerCase();
        }
    }

    public static LlmMessage system(String content) {
        return new LlmMessage(Role.SYSTEM, content);
    }

    public static LlmMessage user(String content) {
        return new LlmMessage(Role.USER, content);
    }

    public static LlmMessage assistant(String content) {
        return new LlmMessage(Role.ASSISTANT, content);
    }
}
