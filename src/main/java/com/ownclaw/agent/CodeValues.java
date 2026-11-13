package com.ownclaw.agent;

import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The user's values put back into a skill's code as Python reads it -- by Python's own tokenizer,
 * in the Python the skill is checked and run on ({@link SkillManager#restoreValues}), with the
 * values on its standard input and never on a command line (the script: restore_values.py beside
 * this class). The model writes a placeholder where it means a value, mostly inside a string: put
 * back there as text, a value holding the literal's own quote ended the string early, and the rest
 * of it was code -- a WiFi name a neighbour chose, {@code a'+__import__("os").getcwd()+'b}, ran in the
 * skill. Now each placeholder inside a literal becomes the value escaped for that literal's kind,
 * one in a comment cannot end the comment, and one anywhere else is refused: put back there, any
 * value would be code. So is one in a comment on the first two lines: there Python reads an
 * encoding declaration, and "coding:unicode_escape" put back in one made the runner decode the
 * restore's own escapes into quotes and line breaks, and the rest of the value ran.
 * <p>
 * And whatever it put back, the script checks as the skill's runner reads it -- the file's bytes,
 * decoded as they declare: the same encoding declared as the code as written, and the very program
 * it means, every placeholder in a string replaced by its value and nothing else different. One
 * check of the result, not of each way there: a value that turns the code into other code by a
 * way no rule above foresaw is refused all the same.
 * <p>
 * Only the code: the rest of a reply -- its requirements -- is put back as text, and only a value
 * that is one plain token there ({@code AgentLoop}).
 */
final class CodeValues {

    private CodeValues() {}

    /** A placeholder's shape: {@code <kind_n>}. */
    static final Pattern PLACEHOLDER = Pattern.compile("<[a-z]+_\\d+>");

    /** The script that puts them back, run as {@code python restore_values.py}. */
    static final String SCRIPT = load();

    private static String load() {
        try (InputStream in = CodeValues.class.getResourceAsStream("restore_values.py")) {
            if (in == null) throw new IllegalStateException("restore_values.py is missing from the classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Its input as JSON, every character outside ASCII escaped -- a value's lone surrogate too, which
     * UTF-8 cannot carry and Python's JSON reads back as it was.
     */
    private static final ObjectMapper ASCII = JsonMapper.builder().enable(JsonWriteFeature.ESCAPE_NON_ASCII).build();

    static String input(String code, Map<String, String> values) {
        var in = new LinkedHashMap<String, Object>();
        in.put("code", code);
        in.put("values", values);
        try {
            return ASCII.writeValueAsString(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * What putting the values back gave.
     *
     * @param code    the code with the values put back; null when refused, or when it could not run
     * @param refused why it was refused, as the script says it -- "outside" (code, an f-string's field
     *                or format), "header" (a comment on the first two lines, where an encoding is
     *                declared), "fstring" (an f-string, on a Python older than 3.12), "changed" (the
     *                code put back does not read as the program written), "bytes" (a value a bytes
     *                literal cannot hold), "read" (code the tokenizer could not read) -- or null
     * @param line    the line of the code it was refused at, 0 when no line is known
     * @param failed  why it could not run at all -- the check's own failure, nothing about the code --
     *                or null
     */
    record Restored(String code, String refused, int line, String failed) {
        static Restored unchanged(String code) {
            return new Restored(code, null, 0, null);
        }

        static Restored couldNotRun(String why) {
            return new Restored(null, null, 0, why);
        }

        /** Read from the script's output. */
        static Restored of(String stdout) {
            try {
                JsonNode out = ASCII.readTree(stdout.strip());
                if (out.hasNonNull("code")) return unchanged(out.get("code").asText());
                if (out.hasNonNull("refused")) {
                    return new Restored(null, out.get("refused").asText(), out.path("line").asInt(0), null);
                }
            } catch (IOException e) {
                return couldNotRun("its output could not be read");
            }
            return couldNotRun("its output held no code");
        }

        /** Why it was refused, as the owner reads it: the line, never the value or the code there. */
        String why() {
            String at = line > 0 ? " at line " + line : "";
            return switch (refused) {
                case "header" -> "names a value of yours in a comment on its first two lines" + at + ", where Python "
                        + "reads an encoding declaration";
                case "fstring" -> "names a value of yours in an f-string" + at + ", which the Python skills run on "
                        + "reads as one piece: put back there, it could not be told from the f-string's fields";
                case "changed" -> "would not read, with your values put back, as the program the model wrote -- "
                        + "Python, reading the file as the skill's runner does, takes it for other code";
                case "bytes" -> "names a value of yours that a bytes literal cannot hold" + at;
                case "read" -> "could not be read by Python's tokenizer" + at;
                default -> "names a value of yours" + at + " outside any string or comment, where it would be "
                        + "code, not text";
            };
        }
    }

    /** The values of the placeholders {@code code} names, by placeholder: those {@code restore} knows. */
    static Map<String, String> valuesIn(String code, java.util.function.UnaryOperator<String> restore) {
        var values = new LinkedHashMap<String, String>();
        var m = PLACEHOLDER.matcher(code);
        while (m.find()) {
            String placeholder = m.group();
            if (values.containsKey(placeholder)) continue;
            String value = restore.apply(placeholder);
            if (value != null && !value.equals(placeholder)) values.put(placeholder, value);
        }
        return values;
    }
}
