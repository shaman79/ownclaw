package com.ownclaw.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ownclaw.agent.AgentContext;
import com.ownclaw.agent.LiveCall;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.llm.LlmProgress.Part;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.ownclaw.llm.OllamaStreamingTest.CHAT;
import static com.ownclaw.llm.OllamaStreamingTest.JSON;
import static com.ownclaw.llm.OllamaStreamingTest.SHOW;
import static com.ownclaw.llm.OllamaStreamingTest.last;
import static com.ownclaw.llm.OllamaStreamingTest.line;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The local provider with a model whose template writes tool calls as text ({@link TextToolCalls}):
 * no tools field goes out, the tools are in the system message as the template puts them there, the
 * calls are read out of the streamed text, and every piece of a call reaches the progress hook while
 * it is written -- the loop guard's too.
 */
class OllamaTextToolCallsTest {

    /** /api/show for a model whose template is the Qwen3.6 one, as far as tools go. */
    static String show() throws Exception {
        ObjectNode s = JSON.createObjectNode().put("template",
                "{%- for message in messages %}{{ message.content }}{%- endfor %}\n"
                        + TextToolCallsTest.resource("qwen36-template-tools.jinja"));
        s.putArray("capabilities").add("completion").add("tools").add("thinking");
        s.putObject("model_info").put("general.architecture", "qwen35moe").put("qwen35moe.context_length", 262_144);
        return s.toString();
    }

    static FakeHttp ollama(String stream) throws Exception {
        return new FakeHttp().json(SHOW, 200, show()).on(CHAT, 200, "application/x-ndjson", stream);
    }

    /** The reply's text streamed {@code size} characters a line. */
    static String lines(String text, int size) {
        var sb = new StringBuilder();
        for (int i = 0; i < text.length(); i += size) sb.append(line(text.substring(i, Math.min(text.length(), i + size)), null));
        return sb.toString();
    }

    static final ToolSpec SHELL = TextToolCallsTest.SHELL;
    static final ToolSpec DONE = TextToolCallsTest.DONE;
    static final List<LlmMessage> ASK = List.of(LlmMessage.system("You are the executor.\n"), LlmMessage.user("Begin."));

    static LlmRequestConfig withTools(List<ToolSpec> tools) {
        return new LlmRequestConfig(null, null, false).withTools(tools);
    }

    @Test
    @DisplayName("no tools field: the tools open the system message as the template writes them, and the call comes back read")
    void theRequestAndTheCall() throws Exception {
        String reply = "Checking the uptime.\n\n" + TextToolCallsTest.call("shell_exec", "command", "uptime\nw", "timeout", "30");
        var http = ollama(lines(reply, 7) + last("stop", 900, 60));
        var provider = OllamaStreamingTest.provider(OllamaStreamingTest.config(), http);

        LlmResponse r = provider.chat(ASK, withTools(List.of(SHELL, DONE)));

        JsonNode body = JSON.readTree(http.to(CHAT).get(0).body());
        assertFalse(body.has("tools"), "a tools field would make Ollama hold the call back");
        JsonNode messages = body.path("messages");
        assertEquals(2, messages.size());
        assertEquals("system", messages.get(0).path("role").asText());
        assertEquals(TextToolCalls.system(List.of(SHELL, DONE), "You are the executor.\n"),
                messages.get(0).path("content").asText());
        assertTrue(messages.get(0).path("content").asText().endsWith("</IMPORTANT>\n\nYou are the executor."));
        assertEquals("user", messages.get(1).path("role").asText());
        assertEquals(262_144, body.path("options").path("num_ctx").asInt(), "the same context settings as every call");

        assertEquals("Checking the uptime.", r.content());
        assertEquals(1, r.toolCalls().size());
        assertEquals("shell_exec", r.toolCalls().get(0).name());
        assertEquals(Map.of("command", "uptime\nw", "timeout", 30), r.toolCalls().get(0).arguments(),
                "typed by the schema, then parsed as a native call's arguments are");
        assertNull(r.invalidToolCall());
        assertEquals(1, http.to(SHOW).size(), "the template was read with the context window, once");
    }

    @Test
    @DisplayName("every piece of the call reaches the hook as it streams: its name, then its text")
    void theCallStreams() throws Exception {
        String reply = "Writing it.\n" + TextToolCallsTest.call("shell_exec", "command", "cat <<'EOF' > /tmp/x\n"
                + "line one\nline two\nEOF");
        var http = ollama(lines(reply, 5) + last("stop", 900, 60));
        var heard = new ArrayList<String>();
        var lines = new int[1];
        LlmProgress hook = new LlmProgress() {
            public void onProgress() { lines[0]++; }
            public void received(Part part, String text) { heard.add(part + ":" + text); }
        };

        OllamaStreamingTest.provider(OllamaStreamingTest.config(), http).chat(ASK, withTools(List.of(SHELL))
                .withProgress(hook));

        long arguments = heard.stream().filter(h -> h.startsWith("ARGUMENTS:")).count();
        assertTrue(arguments > 10, "the call told piece by piece as it streamed, not whole at the end: " + arguments);
        assertTrue(heard.contains("CALL:shell_exec"), heard.toString());
        assertTrue(heard.indexOf("CALL:shell_exec") < heard.indexOf(heard.stream()
                .filter(h -> h.startsWith("ARGUMENTS:")).findFirst().orElseThrow()), "named before its text");
        String told = String.join("", heard.stream().filter(h -> !h.startsWith("CALL:"))
                .map(h -> h.substring(h.indexOf(':') + 1)).toList());
        assertEquals(reply, told, "every character told once, in order");
        assertEquals((reply.length() + 4) / 5 + 1, lines[0], "every line heard, the last one too");
    }

    @Test
    @DisplayName("a call whose argument has become a loop is ended by the loop guard while it is written")
    void aLoopingArgumentIsEnded() throws Exception {
        var looping = new StringBuilder("#!/bin/sh\n");
        for (int i = 0; i < 400; i++) {
            looping.append("echo 'checking the configuration of the second router again, because the first answer "
                    + "did not show the route to 192.0.2.254 and I need it before I decide which command to run next'\n");
        }
        String reply = TextToolCallsTest.call("shell_exec", "command", looping.toString());
        var http = ollama(lines(reply, 40) + last("stop", 900, 60));
        var task = new AgentContext("u1", "t1", "check the routers");
        LiveCall live = task.call(OllamaStreamingTest.provider(OllamaStreamingTest.config(), http), true, "delegation turn 1");

        var e = assertThrows(RepeatedOutput.class, () -> OllamaStreamingTest.provider(OllamaStreamingTest.config(), http)
                .chat(ASK, withTools(List.of(SHELL)).withProgress(live)));
        assertTrue(e.getMessage().contains("repeated the same 300 characters"), e.getMessage());
        assertTrue(e.getMessage().contains("in a tool call it went on repeating"), e.getMessage());
        int seen = (int) live.shown().data().get("answerChars");
        assertTrue(seen < reply.length() / 2, "ended while it went on, not at its end: " + seen + " of " + reply.length());
        assertTrue(seen >= 3 * 2_000, "not before the checks in a row had found it going on: " + seen);
        assertEquals(List.of("shell_exec"), live.shown().data().get("toolCalls"), "the owner saw which call it was");
        assertEquals(http.opened.get(), http.closed.get(), "the stream was closed behind it");
        live.close();
    }

    @Test
    @DisplayName("data that repeats in a call -- a grid of zeros, a table's identical rows, a rule of dashes -- is no loop")
    void repeatedDataIsNoLoop() throws Exception {
        var script = new StringBuilder("import numpy as np\n\n# The board, empty.\nGRID = [\n");
        for (int i = 0; i < 40; i++) script.append("    [0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0],\n");
        script.append("]\n\n# ").append("-".repeat(76)).append("\nROWS = '''\n");
        for (int i = 0; i < 18; i++) script.append("2026-11-11,router-1,eth0,up,1500,0,0,0,0,0,0,0,0,0,0,ok,ok,ok,ok\n");
        script.append("'''\n");
        for (int i = 0; i < 10; i++) script.append("# ").append("=".repeat(66)).append('\n');
        script.append("\ndef main():\n    print(len(GRID), len(ROWS.splitlines()))\n\nif __name__ == '__main__':\n    main()\n");
        String reply = "Writing the script.\n" + TextToolCallsTest.call("shell_exec", "command", script.toString(),
                "timeout", "30");
        var http = ollama(lines(reply, 37) + last("stop", 900, 60));
        var task = new AgentContext("u1", "t1", "write the script");
        LiveCall live = task.call(OllamaStreamingTest.provider(OllamaStreamingTest.config(), http), true, "delegation turn 2");

        LlmResponse r = assertDoesNotThrow(() -> OllamaStreamingTest.provider(OllamaStreamingTest.config(), http)
                .chat(ASK, withTools(List.of(SHELL)).withProgress(live)));
        assertEquals(script.toString(), r.toolCalls().get(0).arguments().get("command"), "the whole script, run as written");
        live.close();

        // The same text as an answer is ended: there the rule is the first check that finds it.
        var answer = new AgentContext("u1", "t2", "say it");
        LiveCall prose = answer.call(OllamaStreamingTest.provider(OllamaStreamingTest.config(), http), true, "step 1");
        assertThrows(RepeatedOutput.class, () -> {
            for (int i = 0; i < script.length(); i += 37) {
                prose.received(Part.ANSWER, script.substring(i, Math.min(script.length(), i + 37)));
            }
        });
        prose.close();
    }

    /** A dashboard card of some 430 characters, the same each time but for its entity line. */
    static String card(int i) {
        return "  - type: custom:apexcharts-card\n    header:\n      show: true\n      title: Spot price and charging\n"
                + "    graph_span: 48h\n    span:\n      start: day\n    now:\n      show: true\n      label: Now\n"
                + "    series:\n      - entity: sensor.current_spot_electricity_price_" + i + "\n        type: column\n"
                + "        name: Price\n        color: '#4caf50'\n        data_generator: |\n          return entity.attributes.prices;\n"
                + "        show:\n          in_header: false\n";
    }

    @Test
    @DisplayName("a dashboard card written again and again, only its entity changing, is ended within a few checks")
    void aVaryingLoopIsEnded() throws Exception {
        var looping = new StringBuilder("views:\n- title: Car\n  cards:\n");
        for (int i = 0; looping.length() < 200_000; i++) looping.append(card(i));
        String reply = TextToolCallsTest.call("ha_api", "mode", "ws", "ws_commands", looping.toString());
        var http = ollama(lines(reply, 40) + last("stop", 900, 60));
        var task = new AgentContext("u1", "t1", "fix the chart");
        LiveCall live = task.call(OllamaStreamingTest.provider(OllamaStreamingTest.config(), http), true, "delegation turn 7");

        var e = assertThrows(RepeatedOutput.class, () -> OllamaStreamingTest.provider(OllamaStreamingTest.config(), http)
                .chat(ASK, withTools(List.of(TextToolCallsTest.HA)).withProgress(live)));
        int seen = (int) live.shown().data().get("answerChars");
        assertTrue(seen <= 24_000, "ended within the checks in a row of the loop beginning: " + seen + " -- " + e.getMessage());
        live.close();
    }

    @Test
    @DisplayName("a </tool_call> inside a value leaves the rest of the call judged as a call: a grid of zeros after it runs whole")
    void aCloseInsideAValueKeepsTheCallACall() throws Exception {
        var script = new StringBuilder("echo '</tool_call>' >> log.txt\nGRID = [\n");
        for (int i = 0; i < 40; i++) script.append("    [0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0],\n");
        script.append("]\nprint(len(GRID))\n");
        String reply = TextToolCallsTest.call("shell_exec", "command", script.toString());
        var http = ollama(lines(reply, 37) + last("stop", 900, 60));
        var task = new AgentContext("u1", "t1", "write it");
        LiveCall live = task.call(OllamaStreamingTest.provider(OllamaStreamingTest.config(), http), true, "delegation turn 3");
        LlmResponse r = assertDoesNotThrow(() -> OllamaStreamingTest.provider(OllamaStreamingTest.config(), http)
                .chat(ASK, withTools(List.of(SHELL)).withProgress(live)));
        assertEquals(script.toString(), r.toolCalls().get(0).arguments().get("command"));
        live.close();
    }

    @Test
    @DisplayName("a call to a tool deferred and not yet offered has its values typed by that tool's schema all the same")
    void deferredToolsAreTyped() throws Exception {
        String reply = TextToolCallsTest.call("ha_api", "ratio", "2", "verbose", "true", "body", "{\"a\": [1, 2]}");
        var http = ollama(lines(reply, 9) + last("stop", 900, 60));
        LlmResponse r = OllamaStreamingTest.provider(OllamaStreamingTest.config(), http)
                .chat(ASK, withTools(List.of(SHELL, TextToolCallsTest.HA.asDeferred())));
        assertEquals(Map.of("ratio", 2, "verbose", true, "body", Map.of("a", List.of(1, 2))), r.toolCalls().get(0).arguments());
        String system = JSON.readTree(http.to(CHAT).get(0).body()).path("messages").get(0).path("content").asText();
        assertEquals(TextToolCalls.system(List.of(SHELL), "You are the executor.\n"), system,
                "the system message offers what is offered, the deferred tool not among it");
    }

    @Test
    @DisplayName("a call the reply ends inside is refused as unreadable; cut off by the window it is the window")
    void cutOffCalls() throws Exception {
        String cut = "Running it.\n<tool_call>\n<function=shell_exec>\n<parameter=command>\necho on";
        var stopped = ollama(lines(cut, 9) + last("stop", 900, 60));
        var e = assertThrows(MalformedToolCall.class, () -> OllamaStreamingTest.provider(OllamaStreamingTest.config(), stopped)
                .chat(ASK, withTools(List.of(SHELL))));
        assertTrue(e.getMessage().contains("has no end"), e.getMessage());
        assertNotNull(e.reply(), "its billed tokens are still counted");
        assertEquals(60, e.reply().completionTokens());

        var full = ollama(lines(cut, 9) + last("length", 200_000, 62_144));
        var t = assertThrows(OutputTruncated.class, () -> OllamaStreamingTest.provider(OllamaStreamingTest.config(), full)
                .chat(ASK, withTools(List.of(SHELL))));
        assertEquals(OutputTruncated.Limit.CONTEXT_WINDOW, t.limit());
    }

    @Test
    @DisplayName("a conversation with no system prompt gets a system message of the tools alone; a TOOLS message's tools join it")
    void noSystemPromptAndAddedTools() throws Exception {
        var http = ollama(lines("Done.", 3) + last("stop", 900, 2));
        var deferred = TextToolCallsTest.tool("router_status", Map.of("host", "string")).asDeferred();
        var conversation = List.of(LlmMessage.user("Begin."), LlmMessage.toolsAdded(List.of("router_status")),
                LlmMessage.user("Go on."));

        LlmResponse r = OllamaStreamingTest.provider(OllamaStreamingTest.config(), http)
                .chat(conversation, withTools(List.of(SHELL, deferred)));

        JsonNode messages = JSON.readTree(http.to(CHAT).get(0).body()).path("messages");
        assertEquals(List.of("system", "user", "user"), List.of(messages.get(0).path("role").asText(),
                messages.get(1).path("role").asText(), messages.get(2).path("role").asText()));
        String expected = TextToolCalls.system(List.of(SHELL, new ToolSpec(deferred.name(), deferred.description(),
                deferred.inputSchema())), null);
        assertEquals(expected, messages.get(0).path("content").asText());
        assertTrue(expected.contains("\"name\": \"router_status\""), "the added tool is offered");
        assertEquals("Done.", r.content(), "a reply with no call is the answer, as written");
    }

    @Test
    @DisplayName("tools go natively when the switch is off, or when the template writes calls some other way")
    void nativeOtherwise() throws Exception {
        var config = OllamaStreamingTest.config();
        config.getExecutor().setToolCallsAsText(false);
        var off = new FakeHttp().json(SHOW, 200, show()).on(CHAT, 200, "application/x-ndjson",
                OllamaStreamingTest.toolLine("shell_exec", Map.of("command", "uptime")) + last("stop", 30, 8));
        LlmResponse r = OllamaStreamingTest.provider(config, off).chat(ASK, withTools(List.of(SHELL)));
        JsonNode body = JSON.readTree(off.to(CHAT).get(0).body());
        assertEquals("shell_exec", body.path("tools").get(0).path("function").path("name").asText());
        assertEquals("You are the executor.\n", body.path("messages").get(0).path("content").asText(),
                "the system prompt as it was");
        assertEquals(Map.of("command", "uptime"), r.toolCalls().get(0).arguments());

        var other = OllamaStreamingTest.ollama(OllamaStreamingTest.toolLine("shell_exec", Map.of("command", "ls"))
                + last("stop", 30, 8));
        OllamaStreamingTest.provider(OllamaStreamingTest.config(), other).chat(ASK, withTools(List.of(SHELL)));
        assertTrue(JSON.readTree(other.to(CHAT).get(0).body()).has("tools"), "a Go template: native, as before");
    }

    @Test
    @DisplayName("a call with no tools offered is text like any other, and a reply's native calls are still read")
    void noToolsNoParsing() throws Exception {
        String reply = TextToolCallsTest.call("shell_exec", "command", "uptime");
        var http = ollama(lines(reply, 50) + last("stop", 30, 8));
        LlmResponse r = OllamaStreamingTest.provider(OllamaStreamingTest.config(), http)
                .chat(ASK, new LlmRequestConfig(null, null, false));
        assertEquals(reply, r.content(), "offered no tools, the model's text is its answer");
        assertFalse(r.hasToolCalls());
        JsonNode body = JSON.readTree(http.to(CHAT).get(0).body());
        assertEquals("You are the executor.\n", body.path("messages").get(0).path("content").asText());

        var both = ollama(lines("Text.", 5) + OllamaStreamingTest.toolLine("done", Map.of("summary", "ok"))
                + last("stop", 30, 8));
        LlmResponse r2 = OllamaStreamingTest.provider(OllamaStreamingTest.config(), both).chat(ASK, withTools(List.of(DONE)));
        assertEquals(List.of("done"), r2.toolCalls().stream().map(ToolCall::name).toList(),
                "should Ollama ever read a call itself, it is not lost");
    }

    @Test
    @DisplayName("a reply holding two calls gives both, in order")
    void twoCallsInOneReply() throws Exception {
        String reply = TextToolCallsTest.call("shell_exec", "command", "uptime") + "\n"
                + TextToolCallsTest.call("shell_exec", "command", "df -h");
        var http = ollama(lines(reply, 11) + last("stop", 30, 8));
        LlmResponse r = OllamaStreamingTest.provider(OllamaStreamingTest.config(), http).chat(ASK, withTools(List.of(SHELL)));
        var commands = new ArrayList<Object>();
        for (ToolCall c : r.toolCalls()) commands.add(c.arguments().get("command"));
        assertEquals(List.of("uptime", "df -h"), commands);
        assertEquals("", r.content());
        var args = new LinkedHashMap<>(r.toolCalls().get(0).arguments());
        assertEquals(1, args.size());
    }
}
