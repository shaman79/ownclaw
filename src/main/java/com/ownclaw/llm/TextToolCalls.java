package com.ownclaw.llm;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * <p>
 * The format has no way to quote its own tags: text the model copies into a value that holds them
 * -- an email, a web page -- cannot always be told from the call around it. A value ends only where
 * the format's structure follows its {@code </parameter>}, a call that names a parameter twice is
 * not run, and neither is a reply that leaves a closing tag outside its calls -- what a value split
 * by such text leaves behind -- but text built to look exactly like a whole call is read as one.
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
     * <p>
     * Not measured, because no schema this application sends has them: an enum, a list of types,
     * items of more than one key, $defs and anyOf -- Ollama keeps an items value as Go's "any", which
     * writes its keys sorted, so a multi-key items may come out in another order here than there.
     * Rendered otherwise, the model reads its tools all the same; only the prompt's bytes differ
     * from the native ones from that tool on.
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
     * The model's own JSON, read as models write it: a line break or a tab left raw inside a string,
     * a comma before a closing bracket. Such a value read strictly failed the call it was in: a Home
     * Assistant template sent as a request body on 2026-11-10, its line breaks raw. Each of these
     * reads as the value the model meant, every character kept. Nothing that drops one is forgiven:
     * a backslash before a character JSON does not escape -- read leniently, the {@code \d} of a
     * regular expression would become {@code d} -- nor single quotes, which bring {@code \'} with them
     * and read the {@code 'it'\''s'} of a shell command as {@code 'it'''s'}. Such a value stays the
     * text the model wrote, and the tool says what it makes of it.
     */
    private static final ObjectMapper LENIENT = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
            .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
            .build();

    /**
     * One JSON value, and nothing after it: {@code {"state": "on"}} written twice is two values, and
     * read as the first, the second would be dropped without a word.
     */
    private static final ObjectReader ONE_VALUE = LENIENT.readerFor(Object.class)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /**
     * The longest text read as a number. A model writes a number in a few characters; one written
     * longer, or one beyond what a double holds ({@link #number}), stays the text it is -- read,
     * "1e10000000" was ten million digits to write out, nineteen seconds of work and an error message
     * as long, for a value no tool takes.
     */
    static final int NUMBER_AT_MOST = 64;

    /**
     * The calls in a reply, read the way they are written: each {@code <tool_call>} where the model
     * writes its calls ({@link #opens}), its function, and its parameters, each value ending only
     * where the format's structure -- the next parameter, the function's end, the call's end --
     * follows its {@code </parameter>}. A parameter's value is its text, but for one line break at
     * each end, which the format adds; typed by the tool's schema where it declares a type other than
     * string: an integer, a number or a boolean as the value it reads as, an object as a JSON object
     * and an array as a JSON array (read leniently, {@link #LENIENT}), each kept as text when it does
     * not read as one.
     * <p>
     * A reply with no call is returned exactly as written. One with calls returns the text outside
     * them, trimmed. A call that cannot be read is not among the calls; the reply names it instead,
     * and {@link LlmResponse#requireComplete} refuses the reply for it, as it does a native call
     * whose arguments do not parse: one cut off before its value, its function's name or its
     * function ends; one with text where a parameter should be; one that names a parameter twice;
     * and every call of a reply that leaves, after a call, a value's end with the format's structure
     * after it ({@link #leftOver}) -- what a value holding the format's own tags leaves behind when it
     * splits the call it was in.
     *
     * @param tools the tools of the request, offered or deferred, whose schemas type the values
     */
    static Parsed parse(String content, List<ToolSpec> tools) {
        if (content == null) return new Parsed("", List.of(), null);
        var schemas = new LinkedHashMap<String, ToolSpec>();
        if (tools != null) for (ToolSpec t : tools) schemas.putIfAbsent(t.name(), t);
        var calls = new ArrayList<Call>();
        var outside = new StringBuilder();
        String malformed = null;
        boolean afterACall = false;
        int pos = 0;
        while (pos < content.length()) {
            int open = callStart(content, pos);
            String between = content.substring(pos, open < 0 ? content.length() : open);
            outside.append(between);
            if (afterACall && malformed == null && leftOver(between)) {
                malformed = "the model's tool call cannot be read (the text after a call holds </parameter> and the "
                        + "format's structure after it, which a value holding the format's own tags leaves behind):\n"
                        + between;
            }
            if (open < 0) break;
            Read read = readCall(content, open, schemas);
            if (read.call() != null) calls.add(read.call());
            else if (malformed == null) {
                malformed = "the model's tool call cannot be read (" + read.problem() + "):\n"
                        + content.substring(open, read.end());
            }
            afterACall = true;
            pos = read.end();
        }
        if (calls.isEmpty() && malformed == null) return new Parsed(content, List.of(), null);
        return new Parsed(outside.toString().strip(), List.copyOf(calls), malformed);
    }

    /** One call read: the call, or why it could not be; and where the text after it begins. */
    private record Read(Call call, String problem, int end) {}

    /**
     * Whether text after a call holds what a value split by the format's own tags leaves behind: the
     * outer value's own {@code </parameter>}, with its function's end, its call's end or another
     * parameter after it, past whitespace. A split always leaves that much, and nothing a reply says
     * about the format does: a {@code </tool_call>} quoted or written twice, a {@code </parameter>}
     * named in a sentence.
     */
    static boolean leftOver(String text) {
        for (int at = text.indexOf(PARAMETER_END); at >= 0; at = text.indexOf(PARAMETER_END, at + 1)) {
            int next = skipSpace(text, at + PARAMETER_END.length());
            if (text.startsWith(FUNCTION_END, next) || text.startsWith(CLOSE, next) || text.startsWith(PARAMETER, next)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A function's or a parameter's name as the format writes it: one word, no space and no tag in
     * it. A name that runs into a line break or the next tag lost its {@code >}, and the call is not
     * read as the one the model meant.
     */
    private static final Pattern NAME = Pattern.compile("[^\\s<>/]+");

    /** Where the next call begins at or after {@code from} ({@link #opens}), or -1. */
    static int callStart(String content, int from) {
        for (int at = content.indexOf(OPEN, from); at >= 0; at = content.indexOf(OPEN, at + 1)) {
            if (Boolean.TRUE.equals(opens(content, at, true))) return at;
        }
        return -1;
    }

    /**
     * Whether the {@code <tool_call>} at {@code at} opens a call. It does when a function follows it,
     * past any whitespace, and either the tag stands where the model writes its calls -- at the start
     * of a line, or with a line break after it -- or the format's structure goes on after the
     * function's name: a parameter, the function's end or the call's end, as in a call written
     * compactly after text on the same line ({@code Sure.<tool_call><function=x>\n<parameter=...}).
     * At the start of a line, a tag with nothing but whitespace after it to the reply's end is a
     * call cut off as it began. Anywhere else the tag is text that mentions it -- "the tag
     * `<tool_call> <function=x>` is how" -- and is read as the text it is.
     *
     * @param ended whether the text is the whole reply
     * @return null while the text so far cannot tell: it has not ended, and what follows the tag is
     *         whitespace, or the start of what would decide
     */
    static Boolean opens(CharSequence text, int at, boolean ended) {
        boolean lineStart = atLineStart(text, at);
        boolean whereCallsAre = lineStart;
        int p = at + OPEN.length();
        while (p < text.length() && Character.isWhitespace(text.charAt(p))) {
            if (text.charAt(p) == '\n') whereCallsAre = true;
            p++;
        }
        if (p >= text.length()) return ended ? lineStart : null;
        if (!startsWith(text, p, FUNCTION)) return !ended && beginsTag(text, p, FUNCTION) ? null : false;
        if (whereCallsAre) return true;
        // Mid-line: a call only if the structure goes on after the function's name.
        int nameEnd = -1;
        for (int i = p + FUNCTION.length(); i < text.length(); i++) {
            if (text.charAt(i) == '>') {
                nameEnd = i;
                break;
            }
        }
        if (nameEnd < 0) return ended ? false : null;
        if (!NAME.matcher(text.subSequence(p + FUNCTION.length(), nameEnd).toString().strip()).matches()) return false;
        int q = nameEnd + 1;
        while (q < text.length() && Character.isWhitespace(text.charAt(q))) q++;
        if (q >= text.length()) return ended ? false : null;
        if (startsWith(text, q, PARAMETER) || startsWith(text, q, FUNCTION_END) || startsWith(text, q, CLOSE)) return true;
        boolean undecided = beginsTag(text, q, PARAMETER) || beginsTag(text, q, FUNCTION_END) || beginsTag(text, q, CLOSE);
        return !ended && undecided ? null : false;
    }

    /** Whether the text from {@code at} to its end is the beginning of {@code tag}, not all of it. */
    private static boolean beginsTag(CharSequence text, int at, String tag) {
        int left = text.length() - at;
        return left > 0 && left < tag.length() && tag.startsWith(text.subSequence(at, text.length()).toString());
    }

    /** Whether nothing but spaces and tabs stands between {@code at} and the line's start. */
    private static boolean atLineStart(CharSequence text, int at) {
        for (int i = at - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if (c == '\n') return true;
            if (c != ' ' && c != '\t') return false;
        }
        return true;
    }

    private static boolean startsWith(CharSequence text, int at, String prefix) {
        if (at < 0 || at + prefix.length() > text.length()) return false;
        for (int i = 0; i < prefix.length(); i++) if (text.charAt(at + i) != prefix.charAt(i)) return false;
        return true;
    }

    private static Read readCall(String content, int open, Map<String, ToolSpec> schemas) {
        int p = skipSpace(content, open + OPEN.length());
        if (p >= content.length()) return new Read(null, "it ends where its function should begin", content.length());

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
            // A second value for one parameter is the model's mistake or text copied into a value
            // that held the format's tags, a parameter of its own among them; neither is run.
            if (arguments.containsKey(parameter)) {
                return new Read(null, "it names the parameter '" + parameter + "' twice",
                        endOfCall(content, valueEnd));
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
                    BigDecimal d = number(v);
                    if (d == null) return raw;
                    try {
                        return d.longValueExact();   // "4.0" and "1e3" too: whole numbers written so
                    } catch (ArithmeticException notAWholeLong) {
                        return raw;
                    }
                }
                case "number" -> {
                    BigDecimal d = number(v);
                    if (d == null) return raw;
                    try {
                        return d.longValueExact();
                    } catch (ArithmeticException notAWholeLong) {
                        return d.doubleValue();
                    }
                }
                case "boolean" -> {
                    if (v.equalsIgnoreCase("true")) return Boolean.TRUE;
                    if (v.equalsIgnoreCase("false")) return Boolean.FALSE;
                    return raw;
                }
                case "object", "array" -> {
                    Object read = ONE_VALUE.readValue(v);
                    boolean fits = "object".equals(type) ? read instanceof Map<?, ?> : read instanceof List<?>;
                    return fits ? read : raw;
                }
                default -> {
                    return raw;
                }
            }
        } catch (Exception doesNotRead) {
            return raw;
        }
    }

    /**
     * A value's text as a number a tool can be handed, or null: at most {@link #NUMBER_AT_MOST}
     * characters, and one a double holds as the number it is -- not infinite, as 2e308 would be,
     * and not zero when it is not, as 1e-324 would be; such a value stays the text it is. How large
     * it is, is known from the digits and the exponent as written, before anything is worked out
     * from them.
     */
    static BigDecimal number(String v) {
        if (v.isEmpty() || v.length() > NUMBER_AT_MOST) return null;
        BigDecimal d;
        try {
            d = new BigDecimal(v);
        } catch (NumberFormatException notANumber) {
            return null;
        }
        if (d.signum() == 0) return BigDecimal.ZERO;
        long digitsBeforeThePoint = (long) d.precision() - d.scale();   // 0 or less below 1
        if (digitsBeforeThePoint > 309 || digitsBeforeThePoint < -323) return null;
        double held = d.doubleValue();
        return Double.isFinite(held) && held != 0 ? d : null;
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
     * the next one says whether it is, and never more than {@link #HELD_AT_MOST} characters of it.
     * Only what the hook is told depends on this; the reply is read whole, by {@link #parse}, once it
     * has ended.
     */
    static final class Stream {

        private enum State { TEXT, OPENED, CALL }

        /**
         * How much of a call's beginning is held back while it is not yet clear whether it is one,
         * or while its function's name is written: a name is a few dozen characters, and past this
         * the text is told as a call's, named or not, so neither the owner nor the loop guard is left
         * without it -- a tag followed by a hundred thousand line breaks was.
         */
        static final int HELD_AT_MOST = 256;

        /**
         * The names the hook may be told: the request's tools'. A call to anything else is shown as
         * a call to "a tool" -- its name is the model's text, which the owner is shown unscrubbed.
         */
        private final java.util.Set<String> tools;
        private final StringBuilder all = new StringBuilder();
        /** How much of {@link #all} the hook has been told. */
        private int told;
        private State state = State.TEXT;

        /** @param tools the names of the request's tools */
        Stream(java.util.Set<String> tools) {
            this.tools = tools == null ? java.util.Set.of() : java.util.Set.copyOf(tools);
        }

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
                    }
                    case OPENED -> {
                        boolean holding = !ended && all.length() - told < HELD_AT_MOST;
                        Boolean call = opens(all, told, ended);
                        if (call == null) {
                            if (holding) return;
                            call = true;   // held long enough: told as the call it may be
                        }
                        if (!call) {
                            tell(progress, LlmProgress.Part.ANSWER, told + OPEN.length());
                            state = State.TEXT;
                            continue;
                        }
                        int p = skipSpace(all, told + OPEN.length());
                        if (startsWith(all, p, FUNCTION)) {
                            int end = all.indexOf(">", p + FUNCTION.length());
                            if (end < 0 && holding) return;
                            String name = end < 0 ? "" : all.substring(p + FUNCTION.length(), end).strip();
                            if (tools.contains(name)) progress.received(LlmProgress.Part.CALL, name);
                        }
                        state = State.CALL;
                    }
                    case CALL -> {
                        int close = callEnd(told);
                        tell(progress, LlmProgress.Part.ARGUMENTS, close >= 0 ? close + CLOSE.length()
                                : ended ? all.length() : all.length() - heldBack(CLOSE));
                        if (close < 0) return;
                        state = State.TEXT;
                    }
                }
            }
        }

        /**
         * Where the call under way ends: the first {@code </tool_call>} at or after {@code from} that
         * the function's end or a parameter's end comes before, past whitespace -- or -1. One inside a
         * value -- {@code echo '</tool_call>' >> log.txt} -- is the value's: ended there, the rest of
         * the call was told as the answer, which the loop guard judges as an answer, and a grid of
         * zeros after it was ended as a loop.
         */
        private int callEnd(int from) {
            for (int at = all.indexOf(CLOSE, from); at >= 0; at = all.indexOf(CLOSE, at + 1)) {
                int before = at;
                while (before > 0 && Character.isWhitespace(all.charAt(before - 1))) before--;
                if (endsWith(all, before, FUNCTION_END) || endsWith(all, before, PARAMETER_END)) return at;
            }
            return -1;
        }

        private static boolean endsWith(CharSequence text, int end, String suffix) {
            return startsWith(text, end - suffix.length(), suffix);
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
    }
}
