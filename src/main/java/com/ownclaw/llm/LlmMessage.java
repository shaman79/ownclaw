package com.ownclaw.llm;

/**
 * A single message in an LLM conversation.
 */
public record LlmMessage(Role role, String content) {

    /**
     * Where a prompt's stable prefix ends: in the first message of a think call, after the task,
     * on every step. Anthropic's provider splits there and caches everything before it -- the
     * tools, the system prompt and the task -- for an hour, so the task's later steps read it from
     * the cache while it is unchanged. The marker itself is never sent.
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
