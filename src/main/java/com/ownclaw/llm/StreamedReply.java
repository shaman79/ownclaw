package com.ownclaw.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One streamed reply being put together, event by event: its text, and its tool calls.
 * <p>
 * All three providers build their replies here, so a tool call is assembled -- and its arguments
 * parsed -- by one rule whichever provider streamed it. Arguments arrive as fragments of JSON
 * text (Anthropic, OpenAI) or as one object (Ollama, which is handed in as its JSON text) and are
 * parsed strictly once the call is complete: no lenient reading, no trailing text. Anthropic does
 * not check a streamed tool input itself, so a malformed one reaches this parser as it was
 * written.
 */
final class StreamedReply {

    private final String provider;
    private final ObjectMapper mapper;
    private final ObjectReader arguments;
    private final StringBuilder text = new StringBuilder();
    private final Map<Integer, Call> open = new LinkedHashMap<>();
    private final List<ToolCall> calls = new ArrayList<>();
    /** Why the first call whose arguments did not parse was dropped, or null. */
    private String malformed;

    private static final class Call {
        String id;
        String name;
        final StringBuilder arguments = new StringBuilder();
    }

    StreamedReply(String provider, ObjectMapper mapper) {
        this.provider = provider;
        this.mapper = mapper;
        this.arguments = mapper.readerFor(new TypeReference<Map<String, Object>>() {})
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    /** One event or line of the stream, as JSON. */
    JsonNode parse(String event) {
        try {
            return mapper.readTree(event);
        } catch (JsonProcessingException e) {
            throw new LlmException(provider,
                    "the reply stream carried an event that is not JSON (" + e.getOriginalMessage() + ")", 0, e);
        }
    }

    void text(String fragment) {
        if (fragment != null) text.append(fragment);
    }

    /**
     * A piece of the tool call at {@code index}: its id and name where they arrive, and the next
     * fragment of its arguments.
     */
    void call(int index, String id, String name, String argumentsFragment) {
        Call c = open.computeIfAbsent(index, i -> new Call());
        if (id != null && !id.isEmpty()) c.id = id;
        if (name != null && !name.isEmpty()) c.name = name;
        if (argumentsFragment != null) c.arguments.append(argumentsFragment);
    }

    /** The call at {@code index} is complete, so its arguments are parsed. No call there: nothing. */
    void close(int index) {
        Call c = open.remove(index);
        if (c == null) return;
        String json = c.arguments.toString();
        String problem;
        try {
            Map<String, Object> args = json.isBlank() ? Map.of() : arguments.readValue(json);
            if (args != null) {
                calls.add(new ToolCall(c.id, c.name, args));
                return;
            }
            problem = "null";
        } catch (JsonProcessingException e) {
            problem = e.getOriginalMessage();
        }
        if (malformed == null) {
            malformed = "the model's arguments for tool '" + c.name + "' are not a JSON object ("
                    + problem + ")";
        }
    }

    /**
     * A fallback model took over mid-reply. The calls so far were the declined model's and are
     * not part of the reply; its text is, because the fallback model continues from it.
     */
    void discardCalls() {
        open.clear();
        calls.clear();
        malformed = null;
    }

    /**
     * The reply. A complete reply with a tool call whose arguments did not parse is not
     * returned: running the call without them, or dropping it and taking the text for the
     * answer, would both be the wrong thing. An incomplete one is returned as it is, for
     * {@link LlmResponse#requireComplete} to reject with the reason that matters.
     */
    LlmResponse response(int promptTokens, int completionTokens, int cacheWriteTokens,
                         int cacheReadTokens, String stopReason, String stopDetail, String model,
                         Integer maxOutputTokens, Integer contextWindow) {
        for (Integer index : List.copyOf(open.keySet())) close(index);
        LlmResponse response = new LlmResponse(text.toString(), promptTokens, completionTokens,
                cacheWriteTokens, cacheReadTokens, stopReason, List.copyOf(calls), stopDetail, model,
                maxOutputTokens, contextWindow);
        if (malformed != null && response.complete()) {
            throw new LlmException(provider, malformed, 0, null);
        }
        return response;
    }
}
