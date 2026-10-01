package com.ownclaw.agent;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Tool calls a model wrote as text: the text protocol, and a tools-capable model that answers in
 * text anyway. One reading for both readers of such text -- the cloud's step
 * ({@link ThinkingEngine#parseAction}) and the local model's turn in a delegation
 * ({@code LocalExecutor}). There were two, and the delegation's read a call's tool from "name"
 * but not its arguments from the "arguments" beside it, so a call the cloud's reader ran with
 * its arguments ran there with none.
 */
final class TextCalls {

    private TextCalls() {}

    /**
     * Lenient: the JSON quirks models write -- {@code \'} and other escapes, unquoted and
     * single-quoted field names, trailing commas, a raw control character (a line break) inside
     * a string, and comments. A comment is read by the parser, where JSON allows whitespace and
     * nowhere else: text stripped of everything that looked like one before parsing lost the rest
     * of a single-quoted URL from its double slash on, and the stretch of a shell command between
     * one glob's slash-asterisk and another's asterisk-slash.
     */
    static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER)
            .enable(JsonReadFeature.ALLOW_UNQUOTED_FIELD_NAMES)
            .enable(JsonReadFeature.ALLOW_SINGLE_QUOTES)
            .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
            .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
            .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
            .build();

    /**
     * Every JSON object at the top level of the text, in the order written, with whatever text
     * surrounds them -- prose, a code fence, a {@code <tool_call>} tag -- left out. Each '{' is
     * tried as the start of one, and Jackson's streaming parser reads exactly one value from it,
     * so a nested brace, an escaped character or a brace inside a string is read as JSON reads
     * it, and "{CURRENT_YEAR}" in prose is not an object. Reading on after the first object is
     * what lets a turn that wrote two calls be read as two.
     */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> objects(String text) {
        var found = new ArrayList<Map<String, Object>>();
        if (text == null || text.isBlank()) return found;
        char[] json = text.toCharArray();
        int from = 0;
        while (from < json.length) {
            int start = indexOf(json, '{', from);
            if (start < 0) break;
            from = start + 1;
            try (var parser = MAPPER.getFactory().createParser(json, start, json.length - start)) {
                if (MAPPER.readValue(parser, Object.class) instanceof Map<?, ?> object) {
                    found.add((Map<String, Object>) object);
                    // Past the object, so the objects nested inside it are not read again.
                    from = start + (int) parser.currentLocation().getCharOffset();
                }
            } catch (Exception notAnObjectHere) {
                // Not an object that starts here; the next '{' may begin one.
            }
        }
        return found;
    }

    /** The first JSON object in the text ({@link #objects}), or null when there is none. */
    static Map<String, Object> firstObject(String text) {
        List<Map<String, Object>> all = objects(text);
        return all.isEmpty() ? null : all.get(0);
    }

    private static int indexOf(char[] text, char c, int from) {
        for (int i = from; i < text.length; i++) if (text[i] == c) return i;
        return -1;
    }

    /**
     * The tool a call names: the first of "tool", "action", "name", "function" and "command"
     * that holds a name, or null. A nested object or a list there is not a name.
     */
    static String tool(Map<String, Object> call) {
        for (String key : List.of("tool", "action", "name", "function", "command")) {
            Object value = call.get(key);
            if (value != null && !(value instanceof Map) && !(value instanceof List)
                    && !String.valueOf(value).isBlank()) {
                return String.valueOf(value);
            }
        }
        return null;
    }

    /** A call's arguments: the object under "params", "parameters" or "arguments"; else none. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> params(Map<String, Object> call) {
        for (String key : List.of("params", "parameters", "arguments")) {
            if (call.get(key) instanceof Map<?, ?> args) return (Map<String, Object>) args;
        }
        return Map.of();
    }
}
