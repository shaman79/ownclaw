package com.ownclaw.llm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownclaw.llm.LlmProgress.Part;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tool calls the local model writes as text ({@link TextToolCalls}): the system message that
 * describes the tools, byte for byte as the model's own template writes it; the calls read back
 * out of a reply; and the reply's text told to the progress hook as what it is while it streams.
 */
class TextToolCallsTest {

    static final ObjectMapper JSON = new ObjectMapper();

    static String resource(String name) throws IOException {
        try (InputStream in = TextToolCallsTest.class.getResourceAsStream(name)) {
            assertNotNull(in, name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @SuppressWarnings("unchecked")
    static List<ToolSpec> toolsOf(List<Map<String, Object>> tools) {
        var specs = new ArrayList<ToolSpec>();
        for (var tool : tools) {
            var fn = (Map<String, Object>) tool.get("function");
            specs.add(new ToolSpec((String) fn.get("name"), (String) fn.get("description"),
                    (Map<String, Object>) fn.get("parameters")));
        }
        return specs;
    }

    static ToolSpec tool(String name, Map<String, String> types) {
        var properties = new LinkedHashMap<String, Object>();
        types.forEach((p, t) -> properties.put(p, t == null ? Map.of("description", p) : Map.of("type", t)));
        var schema = new LinkedHashMap<String, Object>();
        schema.put("type", "object");
        schema.put("properties", properties);
        return new ToolSpec(name, name + " does things", schema);
    }

    static final ToolSpec SHELL = tool("shell_exec", Map.of("command", "string", "timeout", "integer"));
    static final ToolSpec HA = tool("ha_api", new LinkedHashMap<>(Map.of(
            "mode", "string", "body", "object", "ws_commands", "array", "ratio", "number", "verbose", "boolean")));
    static final ToolSpec DONE = tool("done", Map.of("summary", "string"));
    static final List<ToolSpec> OFFERED = List.of(SHELL, HA, DONE);

    // ── The prompt ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the system message is the one the model's template writes with the tools sent natively, byte for byte")
    void theSystemMessageIsTheTemplates() throws Exception {
        // Rendered from the GGUF's own Jinja template with jinja2 and HF's tojson: the system
        // block of the prompt the template writes when the tools are sent natively. The same
        // script checked that this block, sent as the system message with no tools, renders the
        // whole prompt identically.
        Map<String, Object> fixture = JSON.readValue(resource("text-tool-calls-qwen36.json"),
                new TypeReference<>() {});
        @SuppressWarnings("unchecked")
        List<ToolSpec> tools = toolsOf((List<Map<String, Object>>) fixture.get("tools"));
        @SuppressWarnings("unchecked")
        var expected = (Map<String, String>) fixture.get("expected");
        String system = (String) fixture.get("system");

        assertEquals(expected.get("with_system"), TextToolCalls.system(tools, system));
        assertEquals(expected.get("no_system"), TextToolCalls.system(tools, null));
        assertEquals(expected.get("blank_system"), TextToolCalls.system(tools, "   \n "));
        assertEquals(expected.get("no_system"), expected.get("blank_system"), "a blank prompt adds nothing");
    }

    @Test
    @DisplayName("only a template that writes exactly these instructions is offered its tools as text")
    void theTemplateIsRecognised() throws Exception {
        String tools = resource("qwen36-template-tools.jinja");
        assertTrue(TextToolCalls.writtenBy(tools), "the Qwen3.6 template's own tools section");
        assertTrue(TextToolCalls.writtenBy("{%- set x = 1 %}\n" + tools + "\n{%- endfor %}"));

        assertFalse(TextToolCalls.writtenBy(null));
        assertFalse(TextToolCalls.writtenBy(""));
        assertFalse(TextToolCalls.writtenBy("{{ .Messages }}"), "a Go template: tools go natively");
        assertTrue(TextToolCalls.writtenBy(tools.replace("\n    ", "\n\t")), "indentation the tags strip anyway");
        assertFalse(TextToolCalls.writtenBy(tools.replace("ONLY reply", "only reply")),
                "instructions worded otherwise are another template, whose prompt this class does not write");
        assertFalse(TextToolCalls.writtenBy(tools.replace("tool | tojson", "tool | tojson(indent=2)")),
                "tools written another way");
        assertFalse(TextToolCalls.writtenBy(tools.replace("{{- tool | tojson }}", "{{ tool | tojson }}")),
                "whitespace kept that the native prompt would not have");
        assertFalse(TextToolCalls.writtenBy(tools.replace("{{- '<|im_start|>system\\n' + content + '<|im_end|>\\n' }}",
                        "{{- '<|im_start|>system\\nYou are helpful. ' + content + '<|im_end|>\\n' }}")),
                "a system message written with no tools otherwise than this class sends it");
        // Qwen3's Hermes-style template: calls as JSON in <tool_call>, a format of its own.
        assertFalse(TextToolCalls.writtenBy("<tools>{{ tool | tojson }}</tools> <tool_call>\\n{\"name\": "
                + "<function-name>, \"arguments\": <args-json-object>}\\n</tool_call>"));
    }

    @Test
    @DisplayName("tojson writes what Python's json.dumps(ensure_ascii=False) writes")
    void pythonJson() {
        var map = new LinkedHashMap<String, Object>();
        map.put("s", "a\"b\\c\nd\te\u0001f\u007f é ✓ <&>'");
        map.put("n", List.of(1, 2L, new BigInteger("123456789012345678901234567890"), 0.5, 1e-05, 1e16, 12345678.0,
                -0.0, 100.0, 1.5e300, 0.0001, Double.NaN));
        map.put("b", List.of(true, false));
        map.put("e", Map.of());
        map.put("l", List.of());
        var withNull = new LinkedHashMap<String, Object>();
        withNull.put("x", null);
        map.put("z", withNull);
        assertEquals("{\"s\": \"a\\\"b\\\\c\\nd\\te\\u0001f\u007f é ✓ <&>'\", \"n\": [1, 2, "
                        + "123456789012345678901234567890, 0.5, 1e-05, 1e+16, 12345678.0, -0.0, 100.0, 1.5e+300, "
                        + "0.0001, NaN], \"b\": [true, false], \"e\": {}, \"l\": [], \"z\": {\"x\": null}}",
                TextToolCalls.toJson(map));
        assertEquals("1e-07", TextToolCalls.pythonFloat(1e-7));
        assertEquals("1234567890123456.0", TextToolCalls.pythonFloat(1234567890123456.0));
        assertEquals("1.2345678901234568e+16", TextToolCalls.pythonFloat(12345678901234567.0));
        assertEquals("-3.25", TextToolCalls.pythonFloat(-3.25));
    }

    // ── The reply ───────────────────────────────────────────────────────────────────────────

    static String call(String name, String... parameters) {
        var sb = new StringBuilder("<tool_call>\n<function=").append(name).append(">\n");
        for (int i = 0; i < parameters.length; i += 2) {
            sb.append("<parameter=").append(parameters[i]).append(">\n").append(parameters[i + 1])
                    .append("\n</parameter>\n");
        }
        return sb.append("</function>\n</tool_call>").toString();
    }

    @Test
    @DisplayName("a call: its name, its values whole over many lines, typed by the schema, and the text before it")
    void aCall() {
        String script = "#!/bin/sh\nset -e\n\necho \"one\"\necho 'two'   \n";
        String reply = "I will check the uptime first.\n\n" + call("shell_exec", "command", script, "timeout", " 45 ");
        var read = TextToolCalls.parse(reply, OFFERED);
        assertNull(read.malformed());
        assertEquals("I will check the uptime first.", read.text());
        assertEquals(1, read.calls().size());
        var c = read.calls().get(0);
        assertEquals("shell_exec", c.name());
        assertEquals(script, c.arguments().get("command"), "every line and space of it, the trailing line break too");
        assertEquals(BigInteger.valueOf(45), c.arguments().get("timeout"));
    }

    @Test
    @DisplayName("JSON values read leniently -- raw line breaks in strings, single quotes -- and kept as text when they do not read")
    void typedValues() {
        String body = "{\"template\": \"{% for s in states %}\n{{ s.entity_id }}\n{% endfor %}\"}";
        String reply = call("ha_api", "mode", "rest", "body", body,
                "ws_commands", "[{'type': 'get_config'}, {\"type\": \"trace/list\",}]",
                "ratio", "0.25", "verbose", "TRUE");
        var args = TextToolCalls.parse(reply, OFFERED).calls().get(0).arguments();
        assertEquals("rest", args.get("mode"));
        assertEquals(Map.of("template", "{% for s in states %}\n{{ s.entity_id }}\n{% endfor %}"), args.get("body"),
                "the template's line breaks are the template's, read as JSON reads an escaped one");
        assertEquals(List.of(Map.of("type", "get_config"), Map.of("type", "trace/list")), args.get("ws_commands"));
        assertEquals(0.25, args.get("ratio"));
        assertEquals(Boolean.TRUE, args.get("verbose"));

        var kept = TextToolCalls.parse(call("ha_api", "body", "{not json at all", "ratio", "a lot",
                "verbose", "maybe", "ws_commands", "\"a string\""), OFFERED).calls().get(0).arguments();
        assertEquals("{not json at all", kept.get("body"), "the tool, not this reader, says what it makes of it");
        assertEquals("a lot", kept.get("ratio"));
        assertEquals("maybe", kept.get("verbose"));
        assertEquals("\"a string\"", kept.get("ws_commands"), "an array's value that reads as a string is kept as written");

        String regex = "[{\"name\": \"plot\", \"text_regex\": \"\\bplot\\d+\"}]";
        var unchanged = TextToolCalls.parse(call("ha_api", "ws_commands", regex), OFFERED).calls().get(0).arguments();
        assertEquals(regex, unchanged.get("ws_commands"),
                "a backslash JSON does not know is not dropped: the text stays as written, \\d and all");

        var nulls = TextToolCalls.parse(call("ha_api", "body", "null", "mode", "null"), OFFERED).calls().get(0).arguments();
        assertTrue(nulls.containsKey("body"));
        assertNull(nulls.get("body"), "null is no object");
        assertEquals("null", nulls.get("mode"), "but text is what it says");
    }

    @Test
    @DisplayName("a value may hold the format's own tags: only a </parameter> the structure continues after ends it")
    void tagsInsideValues() {
        String tricky = "write <parameter=x>this</parameter> into the file\n</parameter> in the middle of a line, "
                + "and a <tool_call> too";
        String reply = call("shell_exec", "command", tricky, "timeout", "5");
        var read = TextToolCalls.parse(reply, OFFERED);
        assertNull(read.malformed());
        assertEquals(tricky, read.calls().get(0).arguments().get("command"));
        assertEquals(BigInteger.valueOf(5), read.calls().get(0).arguments().get("timeout"));
    }

    @Test
    @DisplayName("two calls, text between them, an unknown tool and a parameter no schema declares")
    void twoCalls() {
        String reply = "First this.\n" + call("shell_exec", "command", "uptime") + "\nthen\n"
                + call("no_such_tool", "x", "{\"a\": 1}", "extra", "7");
        var read = TextToolCalls.parse(reply, OFFERED);
        assertNull(read.malformed());
        assertEquals(2, read.calls().size());
        assertEquals("shell_exec", read.calls().get(0).name());
        assertEquals("no_such_tool", read.calls().get(1).name(), "the caller refuses a tool it does not have");
        assertEquals(Map.of("x", "{\"a\": 1}", "extra", "7"), read.calls().get(1).arguments(),
                "no schema to type them by: the text as written");
        assertEquals("First this.\n\nthen", read.text());
    }

    @Test
    @DisplayName("a reply with no call is returned exactly as written, and a <tool_call> in prose is prose")
    void noCall() {
        String answer = "  The router answers.\n\nUse a <tool_call> tag to call one.  \n";
        var read = TextToolCalls.parse(answer, OFFERED);
        assertEquals(answer, read.text());
        assertTrue(read.calls().isEmpty());
        assertNull(read.malformed());
        assertEquals("", TextToolCalls.parse("", OFFERED).text());
        assertEquals("", TextToolCalls.parse(null, OFFERED).text());
    }

    @Test
    @DisplayName("a call cut off inside a value, a name or right after it opened cannot be read, and says so")
    void cutOff() {
        String cut = "Running it.\n<tool_call>\n<function=shell_exec>\n<parameter=command>\necho one\necho tw";
        var read = TextToolCalls.parse(cut, OFFERED);
        assertTrue(read.calls().isEmpty());
        assertNotNull(read.malformed());
        assertTrue(read.malformed().contains("the value of 'command' has no end"), read.malformed());
        assertTrue(read.malformed().endsWith("echo tw"), "with the call as the model wrote it");

        assertTrue(TextToolCalls.parse("<tool_call>\n<function=shell_ex", OFFERED).malformed()
                .contains("inside its function's name"));
        assertTrue(TextToolCalls.parse("x\n<tool_call>\n", OFFERED).malformed().contains("where its function should begin"));
        assertTrue(TextToolCalls.parse("<tool_call>\n<function=shell_exec\n<parameter=command>\nls\n</parameter>\n"
                + "</function>\n</tool_call>", OFFERED).malformed().contains("not closed"), "a name that lost its >");
        assertTrue(TextToolCalls.parse("<tool_call>\n<function=shell_exec>\nls -l\n</function>\n</tool_call>", OFFERED)
                .malformed().contains("text where a parameter"));
    }

    @Test
    @DisplayName("a reply that ends after a whole parameter, before the function's end, is not run; one closed by </tool_call> is")
    void endsAfterAWholeParameter() {
        var read = TextToolCalls.parse("<tool_call>\n<function=shell_exec>\n<parameter=command>\nrm -rf /tmp/x\n</parameter>\n",
                OFFERED);
        assertTrue(read.calls().isEmpty(), "more parameters may have been coming: a dry_run, say");
        assertTrue(read.malformed().contains("ends before its function's end"), read.malformed());
        var noFunctionEnd = TextToolCalls.parse(
                "<tool_call>\n<function=shell_exec>\n<parameter=command>\nuptime\n</parameter>\n</tool_call>\nafter", OFFERED);
        assertNull(noFunctionEnd.malformed());
        assertEquals(Map.of("command", "uptime"), noFunctionEnd.calls().get(0).arguments());
        assertEquals("after", noFunctionEnd.text());
        var whole = TextToolCalls.parse(TextToolCallsTest.call("shell_exec", "timeout", "4.0"), OFFERED);
        assertEquals(BigInteger.valueOf(4), whole.calls().get(0).arguments().get("timeout"), "a whole number with a point");
    }

    @Test
    @DisplayName("a call written as a JSON object, as older Qwens write theirs, reads too")
    void jsonCall() {
        var read = TextToolCalls.parse("<tool_call>\n{\"name\": \"shell_exec\", \"arguments\": {\"command\": \"ls\n-l\"}}\n"
                + "</tool_call>", OFFERED);
        assertNull(read.malformed());
        assertEquals(Map.of("command", "ls\n-l"), read.calls().get(0).arguments());
        var asString = TextToolCalls.parse("<tool_call>{\"name\": \"done\", \"arguments\": \"{\\\"summary\\\": \\\"ok\\\"}\"}"
                + "</tool_call>", OFFERED);
        assertEquals(Map.of("summary", "ok"), asString.calls().get(0).arguments());
        assertNotNull(TextToolCalls.parse("<tool_call>{\"arguments\": {}}</tool_call>", OFFERED).malformed());
    }

    // ── The reply as it streams ─────────────────────────────────────────────────────────────

    record Heard(Part part, String text) {}

    /** What the hook is told of {@code text} streamed in pieces of {@code size}, adjacent pieces of a part joined. */
    static List<Heard> heard(String text, int size) {
        var heard = new ArrayList<Heard>();
        LlmProgress hook = new LlmProgress() {
            public void onProgress() { }
            public void received(Part part, String piece) {
                if (!heard.isEmpty() && heard.get(heard.size() - 1).part() == part && part != Part.CALL) {
                    var last = heard.remove(heard.size() - 1);
                    heard.add(new Heard(part, last.text() + piece));
                } else {
                    heard.add(new Heard(part, piece));
                }
            }
        };
        var stream = new TextToolCalls.Stream();
        for (int i = 0; i < text.length(); i += size) stream.content(text.substring(i, Math.min(text.length(), i + size)), hook);
        stream.end(hook);
        return heard;
    }

    @Test
    @DisplayName("the answer is told as the answer, then the call's name, then every piece of the call as it is written")
    void streamed() {
        String c1 = call("shell_exec", "command", "uptime");
        String c2 = call("done", "summary", "It is up.");
        String reply = "Checking <it>.\n\n" + c1 + "\n" + c2;
        for (int size : new int[]{1, 2, 3, 5, 7, 64, 10_000}) {
            var heard = heard(reply, size);
            assertEquals(List.of(new Heard(Part.ANSWER, "Checking <it>.\n\n"), new Heard(Part.CALL, "shell_exec"),
                    new Heard(Part.ARGUMENTS, c1), new Heard(Part.ANSWER, "\n"), new Heard(Part.CALL, "done"),
                    new Heard(Part.ARGUMENTS, c2)), heard, "in pieces of " + size);
        }
    }

    @Test
    @DisplayName("a <tool_call> in prose is told as the answer; a JSON call is named when its name is written")
    void streamedProseAndJson() {
        for (int size : new int[]{1, 4, 100}) {
            assertEquals(List.of(new Heard(Part.ANSWER, "Use <tool_call> tags.")), heard("Use <tool_call> tags.", size));
            String json = "<tool_call>\n{\"name\": \"shell_exec\", \"arguments\": {\"command\": \"ls\"}}\n</tool_call>";
            assertEquals(List.of(new Heard(Part.CALL, "shell_exec"), new Heard(Part.ARGUMENTS, json)), heard(json, size));
        }
        // A call cut off before its name ended is told all the same, unnamed.
        assertEquals(List.of(new Heard(Part.ARGUMENTS, "<tool_call>\n<function=shell")), heard("<tool_call>\n<function=shell", 3));
        // Text that only began like a tag is the answer once the reply ends.
        assertEquals(List.of(new Heard(Part.ANSWER, "a <tool_c")), heard("a <tool_c", 2));
    }

    @Test
    @DisplayName("a call whose name never ends is told after a few hundred characters, not held for good")
    void aNameThatNeverEnds() {
        String garbage = "<tool_call>\n<function=" + "x".repeat(1_000);
        var hook = new ArrayList<Heard>();
        var stream = new TextToolCalls.Stream();
        LlmProgress listener = new LlmProgress() {
            public void onProgress() { }
            public void received(Part part, String piece) { hook.add(new Heard(part, piece)); }
        };
        for (int i = 0; i < garbage.length(); i += 10) {
            stream.content(garbage.substring(i, Math.min(garbage.length(), i + 10)), listener);
        }
        int told = hook.stream().mapToInt(h -> h.text().length()).sum();
        assertTrue(told >= garbage.length() - TextToolCalls.Stream.HELD_AT_MOST, "told so far: " + told);
        assertTrue(hook.stream().allMatch(h -> h.part() == Part.ARGUMENTS), hook.toString());
    }
}
