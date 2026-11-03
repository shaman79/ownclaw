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

    /**
     * TOOLS: from here on the model is offered these tools too -- deferred ones
     * ({@link ToolSpec#deferred}), named one per line. Anthropic's provider sends it as a
     * mid-conversation tool addition, which leaves the cached prefix as it was; the others add the
     * tools to the array they send ({@link ToolSpec#offered}) and send no message.
     */
    public enum Role {
        SYSTEM, USER, ASSISTANT, TOOLS;

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

    public static LlmMessage toolsAdded(java.util.List<String> names) {
        return new LlmMessage(Role.TOOLS, String.join("\n", names));
    }

    /** The tools a {@link Role#TOOLS} message offers; none for any other. */
    public java.util.List<String> addedTools() {
        if (role != Role.TOOLS || content == null || content.isBlank()) return java.util.List.of();
        return java.util.List.of(content.split("\n"));
    }
}
