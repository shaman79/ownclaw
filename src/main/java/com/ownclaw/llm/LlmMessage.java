package com.ownclaw.llm;

/**
 * A single message in an LLM conversation.
 *
 * @param authored written by this application's code, holding nothing a user, a tool or a model
 *                 supplied: the think call's system prompt. The cloud gateway removes the vault's
 *                 values from it and nothing else (CloudGateway). The identifiers the privacy
 *                 filter learns are the user's, and a word of the code's own text that was once
 *                 learned as one -- "unavailable", taken for an SSID from a sensor's state -- was
 *                 rewritten in every request from then on, the system prompt too, so every
 *                 cached prompt had to be written again.
 */
public record LlmMessage(Role role, String content, boolean authored) {

    /** A message of data, filtered whole on its way to a cloud model. */
    public LlmMessage(Role role, String content) {
        this(role, content, false);
    }

    /**
     * Where a prompt's stable prefix ends: in the first message of a think call, after the task,
     * on every step. Anthropic's provider splits there and caches everything before it -- the
     * tools, the system prompt and the task -- for an hour, so the task's later steps read it from
     * the cache while it is unchanged. The marker itself is never sent.
     */
    public static final String CACHE_BOUNDARY = "\n<!-- CACHE_BOUNDARY -->\n";

    /**
     * Between two messages of the chat a task comes from, in the first message of a think call
     * for Anthropic, and only there: the provider sends each message of the chat as a block of its
     * own. A block that is the same bytes on the chat's next turn is where the next turn's request
     * finds what this one cached; the chat as one block never is -- it is longer by a turn each
     * time, so every turn wrote the whole chat to the cache again. A line of its own, so that no
     * value the privacy filter reads up to the end of a line runs on into it; control characters,
     * so that no text a user or a tool wrote is taken for one ({@link #unmarked}) and no name the
     * filter learns can match one (it learns none holding a control character). Never sent.
     */
    public static final String HISTORY_BLOCK = "\n\u0001\u001e\n";

    /**
     * Where the chat ends and the task begins, in the same message, a line of its own like
     * {@link #HISTORY_BLOCK}: the provider caches the chat for an hour up to here, so the chat's
     * next turn reads it back and pays only for what was said since. Never sent.
     */
    public static final String HISTORY_END = "\n\u0001\u001d\n";

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

    /** A system prompt that is the code's own text: see {@link #authored}. */
    public static LlmMessage authoredSystem(String content) {
        return new LlmMessage(Role.SYSTEM, content, true);
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

    /**
     * {@code text} that can hold no history marker: the control character every one of them holds
     * is taken out wherever it stands. What a user, a tool or a page wrote goes between the markers
     * only through here.
     */
    public static String unmarked(String text) {
        return text == null || text.indexOf('\u0001') < 0 ? text : text.replace("\u0001", "");
    }

    /** {@code content} as a model reads it once the provider has split it: no marker in it. */
    public static String asRead(String content) {
        if (content == null) return null;
        return content.replace(CACHE_BOUNDARY, "").replace(HISTORY_BLOCK, "").replace(HISTORY_END, "");
    }
}
