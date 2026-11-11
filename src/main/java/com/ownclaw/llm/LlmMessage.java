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
 * @param marked   a think call's first message as the engine wrote it for Anthropic: every marker
 *                 in it ({@link #MARKERS}) is the engine's, and the engine took the markers'
 *                 control character out of every text it put there ({@link #unmarked}). Only in such
 *                 a message are the markers acted on -- by the provider, which splits the message
 *                 at them into the blocks it caches, and by the gateway, whose filter ends a
 *                 private key with no END line at the next one. Anywhere else marker-like bytes are
 *                 text a user, a tool or a page wrote, and acting on them would let that text decide
 *                 where a key's removal ends.
 */
public record LlmMessage(Role role, String content, boolean authored, boolean marked) {

    /** A message of data, filtered whole on its way to a cloud model. */
    public LlmMessage(Role role, String content) {
        this(role, content, false, false);
    }

    /** A message of data, or the code's own text ({@link #authored}). */
    public LlmMessage(Role role, String content, boolean authored) {
        this(role, content, authored, false);
    }

    /**
     * Where a prompt's stable prefix ends: in the first message of a think call, after the task,
     * on every step. Anthropic's provider splits there and caches everything before it -- the
     * tools, the system prompt and the task -- for an hour, so the task's later steps read it from
     * the cache while it is unchanged. A line of its own and control characters, like the chat's
     * markers, so no text in the message can be taken for it: a private key's header in the task
     * ends there, and the step's context after it survives. Never sent.
     */
    public static final String CACHE_BOUNDARY = "\n\u0001\u001c\n";

    /**
     * Between two messages of the chat a task comes from, in the first message of a think call
     * for Anthropic, and only there: the provider sends each message of the chat as a block of its
     * own. A block that is the same bytes on the chat's next turn is where the next turn's request
     * finds what this one cached; the chat as one block never is -- it is longer by a turn each
     * time, so every turn wrote the whole chat to the cache again. A line of its own, so that no
     * value the privacy filter reads up to the end of a line runs on into it; control characters,
     * which no text the engine puts in the message keeps ({@link #unmarked}). Never sent.
     */
    public static final String HISTORY_BLOCK = "\n\u0001\u001e\n";

    /**
     * Where the chat ends and the task begins, in the same message, a line of its own like
     * {@link #HISTORY_BLOCK}: the provider caches the chat for an hour up to here, so the chat's
     * next turn reads it back and pays only for what was said since. Never sent.
     */
    public static final String HISTORY_END = "\n\u0001\u001d\n";

    /** Every marker a {@link #marked} message can hold. */
    public static final java.util.List<String> MARKERS = java.util.List.of(HISTORY_BLOCK, HISTORY_END, CACHE_BOUNDARY);

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
        return new LlmMessage(Role.SYSTEM, content, true, false);
    }

    public static LlmMessage user(String content) {
        return new LlmMessage(Role.USER, content);
    }

    /** A think call's first message as the engine wrote it, its markers its own: see {@link #marked}. */
    public static LlmMessage marked(String content) {
        return new LlmMessage(Role.USER, content, false, true);
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
     * {@code text} that can hold no marker: the control character every one of them holds is taken
     * out wherever it stands. What a user, a tool or a page wrote goes into a {@link #marked}
     * message only through here.
     */
    public static String unmarked(String text) {
        return text == null || text.indexOf('\u0001') < 0 ? text : text.replace("\u0001", "");
    }

    /** {@code content} as a model reads it once the provider has split it: no marker in it. */
    public static String asRead(String content) {
        if (content == null) return null;
        String out = content;
        for (String marker : MARKERS) out = out.replace(marker, "");
        return out;
    }
}
