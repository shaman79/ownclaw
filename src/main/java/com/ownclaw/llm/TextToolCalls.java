package com.ownclaw.llm;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tool calls the local model writes as text, so that they are read as they are written.
 * <p>
 * Ollama holds a tool call back until the model has finished writing it. Offered tools through the
 * request's {@code tools} field, a reply streams its words one by one and then nothing at all while
 * the model writes a call, which arrives whole with the last line: measured on the production host
 * on 2026-11-11, 13.6 seconds of nothing for a call of about 280 tokens. While nothing arrives the
 * owner is shown no progress, the loop guard ({@code LiveCall}) has nothing to read, and only the
 * hour-long read timeout ends a call that has become a loop -- or that retypes a whole dashboard
 * configuration into one argument, as one did on 2026-11-10, until that hour was up. Sent no
 * {@code tools} field, Ollama passes the same call through as the text the model writes, line by
 * line like any other (measured the same day: 112 lines for a short call).
 * <p>
 * So for a model whose chat template writes calls in the format below -- Qwen3.5 and Qwen3.6, the
 * model this deployment runs -- the tools go into the system message exactly as that template
 * would put them there ({@link #system}), no {@code tools} field is sent, and the calls are read
 * back out of the reply here ({@link #parse}). The model reads the same prompt it would read with
 * the tools sent natively, and every piece of a call reaches the progress hook as it is written
 * ({@link Stream}).
 * <pre>
 * &lt;tool_call&gt;
 * &lt;function=NAME&gt;
 * &lt;parameter=PARAMETER&gt;
 * value, over as many lines as it takes
 * &lt;/parameter&gt;
 * &lt;/function&gt;
 * &lt;/tool_call&gt;
 * </pre>
 */
final class TextToolCalls {

    private TextToolCalls() {}

    /** What the template writes before the first tool. */
    static final String HEADER = "# Tools\n\nYou have access to the following functions:\n\n<tools>";

    /** What it writes after the last. */
    static final String FOOTER = "\n</tools>";

    /** The instructions it writes after the tools, word for word. */
    static final String INSTRUCTIONS = "\n\nIf you choose to call a function ONLY reply in the following format "
            + "with NO suffix:\n\n<tool_call>\n<function=example_function_name>\n<parameter=example_parameter_1>\n"
            + "value_1\n</parameter>\n<parameter=example_parameter_2>\nThis is the value for the second "
            + "parameter\nthat can span\nmultiple lines\n</parameter>\n</function>\n</tool_call>\n\n<IMPORTANT>\n"
            + "Reminder:\n- Function calls MUST follow the specified format: an inner <function=...></function> "
            + "block must be nested within <tool_call></tool_call> XML tags\n- Required parameters MUST be "
            + "specified\n- You may provide optional reasoning for your function call in natural language "
            + "BEFORE the function call, but NOT after\n- If there is no function call available, answer the "
            + "question like normal with your current knowledge and do not tell the user about function calls\n"
            + "</IMPORTANT>";

    static final String OPEN = "<tool_call>";
    static final String CLOSE = "</tool_call>";
    static final String FUNCTION = "<function=";
    static final String FUNCTION_END = "</function>";
    static final String PARAMETER = "<parameter=";
    static final String PARAMETER_END = "</parameter>";

    /**
     * The part of the template that writes the system message, with the tools and without them,
     * as its source reads -- each line without the whitespace before and after it, which the
     * {@code -} opening every tag removes anyway, and each line break inside a string as the two
     * characters {@code \n}. The tools are written by the first branch; the second writes the
     * system message this class sends instead, with no tools, as it is sent. A template holding
     * exactly this writes the same prompt either way ({@link #system}).
     */
    private static final String SYSTEM_BRANCHES = String.join("\n",
            "{%- if tools and tools is iterable and tools is not mapping %}",
            "{{- '<|im_start|>system\\n' }}",
            "{{- \"" + source(HEADER) + "\" }}",
            "{%- for tool in tools %}",
            "{{- \"\\n\" }}",
            "{{- tool | tojson }}",
            "{%- endfor %}",
            "{{- \"" + source(FOOTER) + "\" }}",
            "{{- '" + source(INSTRUCTIONS) + "' }}",
            "{%- if messages[0].role == 'system' %}",
            "{%- set content = render_content(messages[0].content, false, true)|trim %}",
            "{%- if content %}",
            "{{- '\\n\\n' + content }}",
            "{%- endif %}",
            "{%- endif %}",
            "{{- '<|im_end|>\\n' }}",
            "{%- else %}",
            "{%- if messages[0].role == 'system' %}",
            "{%- set content = render_content(messages[0].content, false, true)|trim %}",
            "{{- '<|im_start|>system\\n' + content + '<|im_end|>\\n' }}",
            "{%- endif %}",
            "{%- endif %}");

    /**
     * Whether a chat template (Ollama's {@code /api/show}) writes the system message exactly as
     * {@link #SYSTEM_BRANCHES} does: the tools described in exactly the words this class writes, the
     * calls asked for in the format it reads. Anything else -- another model, or a later version of
     * this one that words a line differently -- is offered its tools natively, as before: a prompt
     * this class wrote for a template it does not match would not be the one the model expects.
     */
    static boolean writtenBy(String template) {
        if (template == null || template.isEmpty()) return false;
        return template.lines().map(String::strip).collect(java.util.stream.Collectors.joining("\n"))
                .contains(SYSTEM_BRANCHES);
    }

    /** A text as a Jinja string literal holds it: each line break written as backslash and n. */
    private static String source(String text) {
        return text.replace("\n", "\\n");
    }

    // ── The prompt ──────────────────────────────────────────────────────────────────────────

    /**
     * The system message that describes the tools as the template does when they are sent natively:
     * the tools, the instructions, then the system prompt, trimmed, after a blank line -- or nothing
     * after the instructions when there is no system prompt. The template trims the system message
     * it is sent, and this one starts with "#" and ends with the trimmed prompt, so trimming it
     * again changes nothing.
     *
     * @param tools  the tools offered, in their order ({@link ToolSpec#offered})
     * @param system the system prompt, or null when the conversation has none
     */
    static String system(List<ToolSpec> tools, String system) {
        var sb = new StringBuilder(HEADER);
        for (ToolSpec tool : tools) sb.append('\n').append(toJson(asTemplateSeesIt(tool)));
        sb.append(FOOTER).append(INSTRUCTIONS);
        String content = system == null ? "" : system.strip();
        if (!content.isEmpty()) sb.append("\n\n").append(content);
        return sb.toString();
    }

    /**
     * A tool as the template is handed it when it is sent natively, which is not the JSON the
     * request carries: Ollama reads that into its own structure and hands the template what the
     * structure holds, in its order. Measured against Ollama 0.40.15 on 2026-11-11 by its prompt
     * cache -- the same conversation sent with the tools natively, then with this text instead,
     * read back from the cache to the last token, the two prompts identical:
     * {@code {"type": "function", "function": {"name", "description", "parameters": {"type",
     * "required", "properties"}}}} -- the description always, an empty one too; required only when
     * there is one; every property, in the order given, with its type, items, description and enum
     * when it has them, a property without a type written without one; an empty set of properties
     * as {}. Anything else a schema holds -- additionalProperties, a minimum, a default, properties
     * nested in a property -- is not among them, and the model is not shown it either way.
     */
    static Map<String, Object> asTemplateSeesIt(ToolSpec tool) {
        Map<String, Object> schema = tool.inputSchema() == null ? Map.of() : tool.inputSchema();
        var parameters = new LinkedHashMap<String, Object>();
        parameters.put("type", schema.get("type") instanceof String type ? type : "object");
        if (schema.get("required") instanceof Collection<?> required && !required.isEmpty()) {
            parameters.put("required", required);
        }
        var properties = new LinkedHashMap<String, Object>();
        if (schema.get("properties") instanceof Map<?, ?> given) {
            for (var e : given.entrySet()) {
                var property = new LinkedHashMap<String, Object>();
                if (e.getValue() instanceof Map<?, ?> p) {
                    Object type = p.get("type");
                    // One type is written as itself, a union as the list it is.
                    if (type instanceof Collection<?> types && types.size() == 1) type = types.iterator().next();
                    if (type != null) property.put("type", type);
                    if (p.get("items") != null) property.put("items", p.get("items"));
                    if (p.get("description") instanceof String d && !d.isEmpty()) property.put("description", d);
                    if (p.get("enum") instanceof Collection<?> values && !values.isEmpty()) property.put("enum", values);
                }
                properties.put(String.valueOf(e.getKey()), property);
            }
        }
        parameters.put("properties", properties);
        var function = new LinkedHashMap<String, Object>();
        function.put("name", tool.name());
        function.put("description", tool.description() == null ? "" : tool.description());
        function.put("parameters", parameters);
        var out = new LinkedHashMap<String, Object>();
        out.put("type", "function");
        out.put("function", function);
        return out;
    }

    /**
     * A value as the template's {@code tojson} writes it, which is how Python's
     * {@code json.dumps(value, ensure_ascii=False)} does: ", " between items, ": " after a key,
     * every character as it is but the quote, the backslash and the control characters, and a
     * number as Python prints it.
     */
    static String toJson(Object value) {
        var sb = new StringBuilder();
        writeJson(sb, value);
        return sb.toString();
    }

    private static void writeJson(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String s) {
            writeString(sb, s);
        } else if (value instanceof Boolean b) {
            sb.append(b ? "true" : "false");
        } else if (value instanceof Integer || value instanceof Long || value instanceof Short
                || value instanceof Byte || value instanceof BigInteger) {
            sb.append(value);
        } else if (value instanceof Number n) {
            sb.append(pythonFloat(n.doubleValue()));
        } else if (value instanceof Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (var e : map.entrySet()) {
                if (!first) sb.append(", ");
                first = false;
                writeString(sb, String.valueOf(e.getKey()));
                sb.append(": ");
                writeJson(sb, e.getValue());
            }
            sb.append('}');
        } else if (value instanceof Collection<?> list) {
            sb.append('[');
            boolean first = true;
            for (Object item : list) {
                if (!first) sb.append(", ");
                first = false;
                writeJson(sb, item);
            }
            sb.append(']');
        } else if (value instanceof Object[] array) {
            writeJson(sb, List.of(array));
        } else {
            // Not a plain value -- a Jackson node, say: as the plain value it stands for.
            writeJson(sb, LENIENT.convertValue(value, Object.class));
        }
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    /** A float as Python's repr writes it: "0.5", "1.0", "1e-05", "1e+16". */
    static String pythonFloat(double d) {
        if (Double.isNaN(d)) return "NaN";
        if (Double.isInfinite(d)) return d > 0 ? "Infinity" : "-Infinity";
        if (d == 0) return (1 / d < 0) ? "-0.0" : "0.0";
        // Java writes the shortest digits that read back as the same double, as Python does; only
        // where the point goes and how an exponent is written differ.
        BigDecimal exact = new BigDecimal(Double.toString(Math.abs(d))).stripTrailingZeros();
        String digits = exact.unscaledValue().toString();
        int point = digits.length() - exact.scale();   // digits before the decimal point
        String sign = d < 0 ? "-" : "";
        if (point < -3 || point > 16) {
            String mantissa = digits.length() == 1 ? digits : digits.charAt(0) + "." + digits.substring(1);
            int exponent = point - 1;
            return sign + mantissa + "e" + (exponent < 0 ? "-" : "+")
                    + String.format(Locale.ROOT, "%02d", Math.abs(exponent));
        }
        if (point <= 0) return sign + "0." + "0".repeat(-point) + digits;
        if (point >= digits.length()) return sign + digits + "0".repeat(point - digits.length()) + ".0";
        return sign + digits.substring(0, point) + "." + digits.substring(point);
    }

    // ── The reply ───────────────────────────────────────────────────────────────────────────

    /** A call read out of a reply: the tool's name and its arguments, typed by its schema. */
    record Call(String name, Map<String, Object> arguments) {}

    /**
     * A reply read: the text the model wrote outside its calls, the calls, and why the first call
     * that could not be read was not -- with the call as the model wrote it -- or null.
     */
    record Parsed(String text, List<Call> calls, String malformed) {}

    /**
     * The model's own JSON, read as models write it: a line break or a tab left raw inside a
     * string, single quotes, a comma before a closing bracket. Such a value read strictly failed the
     * call it was in: a Home Assistant template sent as a request body on 2026-11-10, its line breaks
     * raw. Each of these reads as the value the model meant. A backslash before a character JSON
     * does not escape is not forgiven: read leniently, the {@code \d} of a regular expression would
     * become {@code d}, a value changed without a word; read strictly, the value stays the text the
     * model wrote, and the tool says what it makes of it.
     */
    private static final ObjectMapper LENIENT = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
            .enable(JsonReadFeature.ALLOW_SINGLE_QUOTES)
            .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
            .build();

    /**
     * The calls in a reply, read the way they are written: each {@code <tool_call>} that opens a
     * function (or, as an older Qwen writes it, a JSON object with its name and arguments), its
     * parameters each delimited by the format's own structure -- a {@code </parameter>} counts as
     * a value's end only where the next parameter, the function's end or the call's end follows it,
     * so a value may hold that text itself. A parameter's value is its text, but for one line break
     * at each end, which the format adds; typed by the tool's schema where it declares a type other
     * than string: an integer, a number or a boolean as the value it reads as, an object or an array
     * as the JSON it holds (read leniently), each kept as text when it does not read as one.
     * <p>
     * A reply with no call is returned exactly as written. One with calls returns the text outside
     * them, trimmed. A call that cannot be read -- cut off before its value or its function's name
     * ends, text where a parameter should be -- is not among the calls; the reply names it instead,
     * and {@link LlmResponse#requireComplete} refuses the reply for it, as it does a native call
     * whose arguments do not parse.
     *
     * @param offered the tools the model was offered, whose schemas type the values
     */
    static Parsed parse(String content, List<ToolSpec> offered) {
        if (content == null) return new Parsed("", List.of(), null);
        var schemas = new LinkedHashMap<String, ToolSpec>();
        if (offered != null) for (ToolSpec t : offered) schemas.putIfAbsent(t.name(), t);
        var calls = new ArrayList<Call>();
        var outside = new StringBuilder();
        String malformed = null;
        int pos = 0;
        while (pos <= content.length()) {
            int open = callStart(content, pos);
            if (open < 0) {
                outside.append(content, pos, content.length());
                break;
            }
            outside.append(content, pos, open);
            Read read = readCall(content, open, schemas);
            if (read.call() != null) calls.add(read.call());
            else if (malformed == null) {
                malformed = "the model's tool call cannot be read (" + read.problem() + "):\n"
                        + content.substring(open, read.end());
            }
            pos = read.end();
            if (pos >= content.length()) break;
        }
        if (calls.isEmpty() && malformed == null) return new Parsed(content, List.of(), null);
        return new Parsed(outside.toString().strip(), List.copyOf(calls), malformed);
    }

    /** One call read: the call, or why it could not be; and where the text after it begins. */
    private record Read(Call call, String problem, int end) {}

    /**
     * A function's or a parameter's name as the format writes it: one word, no space and no tag in
     * it. A name that runs into a line break or the next tag lost its {@code >}, and the call is not
     * read as the one the model meant.
     */
    private static final Pattern NAME = Pattern.compile("[^\\s<>/]+");

    /**
     * Where the next call begins at or after {@code from}: a {@code <tool_call>} followed -- past any
     * whitespace -- by a function, a JSON object, or the end of the reply (a call cut off as it
     * began). One followed by anything else is text, as the model may write the tag in prose.
     */
    static int callStart(String content, int from) {
        for (int at = content.indexOf(OPEN, from); at >= 0; at = content.indexOf(OPEN, at + 1)) {
            int p = skipSpace(content, at + OPEN.length());
            if (p >= content.length() || content.startsWith(FUNCTION, p) || content.charAt(p) == '{') return at;
        }
        return -1;
    }

    private static Read readCall(String content, int open, Map<String, ToolSpec> schemas) {
        int p = skipSpace(content, open + OPEN.length());
        if (p >= content.length()) return new Read(null, "it ends where its function should begin", content.length());
        if (content.charAt(p) == '{') return readJsonCall(content, p, schemas);

        int nameEnd = content.indexOf('>', p + FUNCTION.length());
        if (nameEnd < 0) return new Read(null, "it ends inside its function's name", content.length());
        String name = content.substring(p + FUNCTION.length(), nameEnd).strip();
        if (!NAME.matcher(name).matches()) {
            return new Read(null, name.isEmpty() ? "its function has no name" : "its function's name is not closed",
                    endOfCall(content, nameEnd));
        }
        ToolSpec tool = schemas.get(name);
        var arguments = new LinkedHashMap<String, Object>();
        p = nameEnd + 1;
        while (true) {
            p = skipSpace(content, p);
            if (p >= content.length()) {
                // The reply ended after a whole parameter, before the function's end: whether more
                // were to follow cannot be told, and a call that changes something is not run on
                // what the model may not have finished.
                return new Read(null, "it ends before its function's end", content.length());
            }
            if (content.startsWith(FUNCTION_END, p)) {
                int after = skipSpace(content, p + FUNCTION_END.length());
                int end = content.startsWith(CLOSE, after) ? after + CLOSE.length() : p + FUNCTION_END.length();
                return new Read(new Call(name, arguments), null, end);
            }
            if (content.startsWith(CLOSE, p)) {
                return new Read(new Call(name, arguments), null, p + CLOSE.length());
            }
            if (!content.startsWith(PARAMETER, p)) {
                return new Read(null, "text where a parameter or the function's end should be",
                        endOfCall(content, p));
            }
            int parameterEnd = content.indexOf('>', p + PARAMETER.length());
            if (parameterEnd < 0) return new Read(null, "it ends inside a parameter's name", content.length());
            String parameter = content.substring(p + PARAMETER.length(), parameterEnd).strip();
            if (!NAME.matcher(parameter).matches()) {
                return new Read(null, "a parameter's name is missing or not closed", endOfCall(content, parameterEnd));
            }
            int valueEnd = valueEnd(content, parameterEnd + 1);
            if (valueEnd < 0) {
                return new Read(null, "the value of '" + parameter + "' has no end", content.length());
            }
            String raw = content.substring(parameterEnd + 1, valueEnd);
            if (raw.startsWith("\n")) raw = raw.substring(1);
            if (raw.endsWith("\n")) raw = raw.substring(0, raw.length() - 1);
            arguments.put(parameter, typed(raw, declaredType(tool, parameter)));
            p = valueEnd + PARAMETER_END.length();
        }
    }

    /**
     * Where the value starting at {@code from} ends: the first {@code </parameter>} that the next
     * parameter, the function's end, the call's end or the end of the reply follows, past any
     * whitespace; -1 when there is none.
     */
    static int valueEnd(String content, int from) {
        for (int at = content.indexOf(PARAMETER_END, from); at >= 0; at = content.indexOf(PARAMETER_END, at + 1)) {
            int next = skipSpace(content, at + PARAMETER_END.length());
            if (next >= content.length() || content.startsWith(PARAMETER, next)
                    || content.startsWith(FUNCTION_END, next) || content.startsWith(CLOSE, next)) {
                return at;
            }
        }
        return -1;
    }

    /** Past the {@code </tool_call>} that ends the call this position is in, or the reply's end. */
    private static int endOfCall(String content, int from) {
        int close = content.indexOf(CLOSE, from);
        return close < 0 ? content.length() : close + CLOSE.length();
    }

    /**
     * A call written as a JSON object -- {@code {"name": ..., "arguments": {...}}} -- as Qwen2.5 and
     * Qwen3 write theirs: read leniently, its arguments an object or a string holding one.
     */
    @SuppressWarnings("unchecked")
    private static Read readJsonCall(String content, int start, Map<String, ToolSpec> schemas) {
        Object value;
        int after;
        try (var parser = LENIENT.getFactory().createParser(content.substring(start))) {
            value = LENIENT.readValue(parser, Object.class);
            after = start + (int) parser.currentLocation().getCharOffset();
        } catch (Exception e) {
            return new Read(null, "its JSON does not read as an object", endOfCall(content, start));
        }
        int end = skipSpace(content, after);
        end = content.startsWith(CLOSE, end) ? end + CLOSE.length() : after;
        if (!(value instanceof Map<?, ?> object) || !(object.get("name") instanceof String name) || name.isBlank()) {
            return new Read(null, "its JSON names no function", end);
        }
        Object args = object.containsKey("arguments") ? object.get("arguments") : object.get("parameters");
        if (args instanceof String text) {
            try {
                args = text.isBlank() ? Map.of() : LENIENT.readValue(text, Object.class);
            } catch (Exception e) {
                return new Read(null, "the arguments of '" + name + "' are not a JSON object", end);
            }
        }
        if (args == null) args = Map.of();
        if (!(args instanceof Map<?, ?> map)) {
            return new Read(null, "the arguments of '" + name + "' are not a JSON object", end);
        }
        var arguments = new LinkedHashMap<String, Object>();
        map.forEach((k, v) -> arguments.put(String.valueOf(k), v));
        return new Read(new Call(name.strip(), arguments), null, end);
    }

    /** The JSON Schema type a tool declares for a parameter, or null when it declares none. */
    static String declaredType(ToolSpec tool, String parameter) {
        if (tool == null || tool.inputSchema() == null) return null;
        if (!(tool.inputSchema().get("properties") instanceof Map<?, ?> properties)) return null;
        if (!(properties.get(parameter) instanceof Map<?, ?> property)) return null;
        Object type = property.get("type");
        if (type instanceof String s) return s;
        if (type instanceof Collection<?> types) {
            // A union: text if it allows text, else its first type that is not null.
            if (types.contains("string")) return "string";
            for (Object t : types) if (t instanceof String s && !"null".equals(s)) return s;
        }
        return null;
    }

    /**
     * A value's text as the type its schema declares, or as the text when it does not read as one:
     * the tool, not this reader, is the one to say what it makes of a value it cannot use.
     */
    static Object typed(String raw, String type) {
        if (type == null || "string".equals(type)) return raw;
        String v = raw.strip();
        if ("null".equals(v)) return null;
        try {
            switch (type) {
                case "integer" -> {
                    // "4.0" too: a whole number written with a point.
                    return new BigDecimal(v).toBigIntegerExact();
                }
                case "number" -> {
                    BigDecimal d = new BigDecimal(v);
                    try {
                        return d.toBigIntegerExact();
                    } catch (ArithmeticException notWhole) {
                        return d.doubleValue();
                    }
                }
                case "boolean" -> {
                    if (v.equalsIgnoreCase("true")) return Boolean.TRUE;
                    if (v.equalsIgnoreCase("false")) return Boolean.FALSE;
                    return raw;
                }
                case "object", "array" -> {
                    Object read = LENIENT.readValue(v, Object.class);
                    return read instanceof Map<?, ?> || read instanceof List<?> ? read : raw;
                }
                default -> {
                    return raw;
                }
            }
        } catch (Exception doesNotRead) {
            return raw;
        }
    }

    private static int skipSpace(CharSequence text, int from) {
        int p = from;
        while (p < text.length() && Character.isWhitespace(text.charAt(p))) p++;
        return p;
    }

    // ── The reply as it streams ─────────────────────────────────────────────────────────────

    /**
     * The reply's text as it streams in, told to the progress hook as what it is: the answer until
     * a call begins, then the call -- its name ({@link LlmProgress.Part#CALL}) once that is written,
     * and every piece of it as {@link LlmProgress.Part#ARGUMENTS}, so the owner sees the call being
     * written and the loop guard reads it. A piece that may be the start of a tag is held back until
     * the next one says whether it is. Only what the hook is told depends on this; the reply is read
     * whole, by {@link #parse}, once it has ended.
     */
    static final class Stream {

        private enum State { TEXT, OPENED, CALL }

        /**
         * How much of a call's beginning is held back while its function's name is written: a name
         * is a few dozen characters, and past this the call is told as it is, named or not.
         */
        static final int HELD_AT_MOST = 256;

        /** How far into a call written as JSON its name is looked for. */
        static final int NAME_WITHIN = 4_096;

        private static final Pattern JSON_NAME = Pattern.compile("\"name\"\\s*:\\s*\"([^\"\\\\]*)\"");

        private final StringBuilder all = new StringBuilder();
        /** How much of {@link #all} the hook has been told. */
        private int told;
        private State state = State.TEXT;
        /** Where the call under way begins: its {@code <tool_call>}. */
        private int callFrom;
        /** Whether the hook has been told the name of the call under way. */
        private boolean named;

        /** The next piece of the reply's text. */
        void content(String piece, LlmProgress progress) {
            all.append(piece);
            advance(progress, false);
        }

        /** The reply has ended: whatever was held back is told. */
        void end(LlmProgress progress) {
            advance(progress, true);
        }

        private void advance(LlmProgress progress, boolean ended) {
            while (told < all.length()) {
                switch (state) {
                    case TEXT -> {
                        int open = all.indexOf(OPEN, told);
                        if (open < 0) {
                            tell(progress, LlmProgress.Part.ANSWER, ended ? all.length() : all.length() - heldBack(OPEN));
                            return;
                        }
                        tell(progress, LlmProgress.Part.ANSWER, open);
                        state = State.OPENED;
                        callFrom = open;
                        named = false;
                    }
                    case OPENED -> {
                        // A call when what follows the tag, past any whitespace, is a function, a JSON
                        // object or the reply's end -- as parse reads it -- and text otherwise.
                        int p = skipSpace(all, told + OPEN.length());
                        String rest = all.substring(Math.min(p, all.length()), Math.min(all.length(), p + FUNCTION.length()));
                        boolean call;
                        if (p >= all.length()) {
                            if (!ended) return;
                            call = true;
                        } else if (all.charAt(p) == '{') {
                            Matcher m = JSON_NAME.matcher(all);
                            m.region(p, all.length());
                            boolean found = m.find();
                            if (!found && !ended && all.length() - told < HELD_AT_MOST) return;
                            if (found) {
                                progress.received(LlmProgress.Part.CALL, m.group(1));
                                named = true;
                            }
                            call = true;
                        } else if (startsWith(all, p, FUNCTION)) {
                            int end = all.indexOf(">", p + FUNCTION.length());
                            if (end < 0 && !ended && all.length() - told < HELD_AT_MOST) return;
                            if (end >= 0) {
                                String name = all.substring(p + FUNCTION.length(), end).strip();
                                if (!name.isEmpty()) {
                                    progress.received(LlmProgress.Part.CALL, name);
                                    named = true;
                                }
                            }
                            call = true;
                        } else if (FUNCTION.startsWith(rest) && !ended) {
                            return;   // "<func": the next piece says whether it is one
                        } else {
                            call = false;
                        }
                        if (call) {
                            state = State.CALL;
                        } else {
                            tell(progress, LlmProgress.Part.ANSWER, told + OPEN.length());
                            state = State.TEXT;
                        }
                    }
                    case CALL -> {
                        int close = all.indexOf(CLOSE, told);
                        int upTo = close >= 0 ? close + CLOSE.length()
                                : ended ? all.length() : all.length() - heldBack(CLOSE);
                        if (!named && upTo - callFrom <= NAME_WITHIN) {
                            Matcher m = JSON_NAME.matcher(all);
                            m.region(callFrom, Math.max(callFrom, upTo));
                            if (m.find()) {
                                progress.received(LlmProgress.Part.CALL, m.group(1));
                                named = true;
                            }
                        }
                        tell(progress, LlmProgress.Part.ARGUMENTS, upTo);
                        if (close < 0) return;
                        state = State.TEXT;
                    }
                }
            }
        }

        /** Tell the hook the text from where it was last told up to {@code upTo}, if there is any. */
        private void tell(LlmProgress progress, LlmProgress.Part part, int upTo) {
            if (upTo <= told) return;
            String piece = all.substring(told, upTo);
            told = upTo;
            progress.received(part, piece);
        }

        /**
         * How many characters at the end of the text may be the start of {@code tag}: held back
         * until the next piece says whether they are.
         */
        private int heldBack(String tag) {
            int max = Math.min(tag.length() - 1, all.length() - told);
            for (int n = max; n > 0; n--) {
                if (startsWith(tag, 0, all.substring(all.length() - n))) return n;
            }
            return 0;
        }

        private static boolean startsWith(CharSequence text, int at, String prefix) {
            if (at < 0 || at + prefix.length() > text.length()) return false;
            for (int i = 0; i < prefix.length(); i++) if (text.charAt(at + i) != prefix.charAt(i)) return false;
            return true;
        }
    }
}
