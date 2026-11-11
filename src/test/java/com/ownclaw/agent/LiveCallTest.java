package com.ownclaw.agent;

import com.ownclaw.llm.LlmMessage;
import com.ownclaw.llm.LlmProgress.Part;
import com.ownclaw.llm.LlmProvider;
import com.ownclaw.llm.LlmRequestConfig;
import com.ownclaw.llm.LlmResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A model call of a task as it streams in ({@link LiveCall}): the live state the owner is shown,
 * what the ops API reads of it -- and that nothing a reply holds, however long, ends it.
 */
class LiveCallTest {

    /** A provider by name only: the live state names it; nothing here calls it. */
    static LlmProvider provider(String name, String model) {
        return new LlmProvider() {
            public LlmResponse chat(List<LlmMessage> m, LlmRequestConfig c) { throw new AssertionError("not called"); }
            public boolean isAvailable() { return true; }
            public String name() { return name; }
            public String model() { return model; }
        };
    }

    static final LlmProvider OLLAMA = provider("ollama", "qwen3.6:35b-a3b");

    static AgentContext task(int step) {
        var ctx = new AgentContext("u1", "t1", "check the routers");
        ctx.setStep(step);
        return ctx;
    }

    /** {@code text} streamed in pieces of {@code size} characters, as one part each. */
    static void stream(LiveCall call, Part part, String text, int size) {
        for (int i = 0; i < text.length(); i += size) {
            call.received(part, text.substring(i, Math.min(text.length(), i + size)));
        }
    }

    /** Text in which no 300-character stretch appears twice: numbered lines, each unlike the last. */
    static String distinct(String what, int lines) {
        var sb = new StringBuilder();
        for (int i = 1; i <= lines; i++) {
            sb.append(what).append(' ').append(i).append(": the value read is ").append(i * 7919 % 10007)
                    .append(", and the next one follows.\n");
        }
        return sb.toString();
    }

    /** A paragraph a model in a loop writes again and again, 4 of them in a row and more. */
    static final String LOOP = "Wait, I need to check the configuration again. The interface eth0 has the "
            + "address 192.0.2.1 and the gateway is 192.0.2.254, so the route should be fine. But the "
            + "user asked about the second router, so let me look at its configuration once more before "
            + "I decide which command to run next. Actually, let me reconsider the whole thing.\n";

    @Test
    @DisplayName("the live state: which model and what for, the phase, the counts, the tool calls and the latest complete line, whole")
    void theLiveState() {
        var ctx = task(6);
        LiveCall call = ctx.call(OLLAMA, true, ctx.atStep("delegation turn 1"));
        assertSame(call, ctx.liveCall(), "the task's call under way");

        var before = call.shown();
        assertEquals("🏠 Local model · step 6, delegation turn 1 · loading the model and reading the prompt · 0s",
                before.text(), "nothing has arrived yet");
        assertEquals(false, before.data().get("arrived"));
        assertEquals("local", before.data().get("model"));
        assertEquals("ollama", before.data().get("provider"));
        assertEquals("qwen3.6:35b-a3b", before.data().get("modelName"));

        call.received(Part.REASONING, "The router is at 192.0.2.1.\nI should ch");
        assertEquals("reasoning", call.shown().data().get("phase"));
        assertEquals("The router is at 192.0.2.1.", call.shown().data().get("line"), "the line a break has ended");
        call.received(Part.REASONING, "eck its uptime first, then its routes, then the logs of the last hour.\n\n");
        call.received(Part.REASONING, "Then I a");
        assertEquals("I should check its uptime first, then its routes, then the logs of the last hour.",
                call.shown().data().get("line"), "whole, and not the line still being written");
        call.received(Part.ANSWER, "Checking.");
        assertEquals("writing the answer", call.shown().data().get("phase"));
        call.received(Part.CALL, "shell_exec");
        call.received(Part.ARGUMENTS, "{\"command\":\"uptime\"}");

        var during = call.shown();
        assertEquals("calling shell_exec", during.data().get("phase"));
        assertEquals(true, during.data().get("arrived"));
        assertEquals(List.of("shell_exec"), during.data().get("toolCalls"));
        assertEquals(119, during.data().get("reasoningChars"));
        assertEquals(29, during.data().get("answerChars"), "the call's arguments are its answer too");
        assertEquals("🏠 Local model · step 6, delegation turn 1 · calling shell_exec · 0s · 119 characters of "
                + "reasoning, 29 of answer so far · “I should check its uptime first, then its routes, then the "
                + "logs of the last hour.”", during.text());
        assertFalse(during.ended());

        call.close();
        assertNull(ctx.liveCall(), "no call is under way once it has ended");
        var after = call.shown();
        assertTrue(after.ended());
        assertEquals("🏠 Local model · step 6, delegation turn 1 · ended after 0s · 119 characters of reasoning, "
                + "29 of answer", after.text(), "how long it ran and what it wrote, no line");
        assertFalse(after.data().containsKey("line"));
    }

    @Test
    @DisplayName("a cloud call waits for its first words; a call before the loop's first step is just a model call")
    void aCloudCall() {
        var ctx = task(0);
        LiveCall call = ctx.call(provider("anthropic", "claude-opus-5"), false, ctx.atStep(null));
        assertEquals("☁️ Cloud model · a model call · waiting for its first words · 0s", call.shown().text());
        call.received(Part.ANSWER, "The router answers.");
        assertEquals("☁️ Cloud model · a model call · writing the answer · 0s · 19 characters so far",
                call.shown().text());
        assertEquals("step 3, delegation turn 2", task(3).atStep("delegation turn 2"));
        assertEquals("step 3", task(3).atStep(null));
    }

    @Test
    @DisplayName("what a call is doing, said for a long one: reading its prompt, or writing and how much")
    void whatACallIsDoing() {
        var ctx = task(1);
        LiveCall call = ctx.call(OLLAMA, true, ctx.atStep(null));
        assertEquals("0s, loading the model and reading the prompt, nothing written yet", call.doing());
        call.received(Part.REASONING, "Checking the router.\n");
        assertEquals("0s, reasoning, 21 characters so far", call.doing());
    }

    @Test
    @DisplayName("a request sent again is answered by a reply of its own: the state starts again with it")
    void aNewAttemptStartsAgain() {
        var ctx = task(2);
        LiveCall call = ctx.call(OLLAMA, true, ctx.atStep(null));
        call.calling(() -> { });
        stream(call, Part.REASONING, distinct("first", 40), 37);
        call.received(Part.CALL, "shell_exec");
        call.calling(null);

        call.calling(() -> { });   // the request sent again, after an overload
        var again = call.shown().data();
        assertEquals(false, again.get("arrived"));
        assertEquals(0, again.get("reasoningChars"));
        assertEquals(List.of(), again.get("toolCalls"));
        assertFalse(again.containsKey("line"));
        // Mutation: keep the text across requests -> the reasoning is counted twice.
    }

    @Test
    @DisplayName("a reasoning is never ended for what it holds: a script drafted with a grid of zeros, a paragraph thought 60 times")
    void aReasoningIsNeverEnded() {
        var ctx = task(6);
        LiveCall call = ctx.call(OLLAMA, true, ctx.atStep("delegation turn 1"));
        // The review's probe: the model drafts the script in its reasoning before it calls, and a
        // 30-row grid of zeros was enough to end it.
        var draft = new StringBuilder("Let me draft the script before I call shell_exec:\n```python\nGRID = [\n");
        for (int i = 0; i < 2_000; i++) draft.append("    [0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0],\n");
        String reasoning = draft + "]\n```\n" + LOOP.repeat(60);
        assertDoesNotThrow(() -> stream(call, Part.REASONING, reasoning, 23));
        assertEquals(reasoning.length(), call.shown().data().get("reasoningChars"));
        assertTrue(call.shown().text().contains(String.format(java.util.Locale.ROOT, "%,d characters so far",
                reasoning.length())), "the owner sees it grow: " + call.shown().text());
        // Mutation: end a reasoning whose last 300 characters appear 4 times -> the grid ends it.
    }

    @Test
    @DisplayName("an answer is output: however long and however much it repeats, it runs whole, and its size is shown")
    void anAnswerIsNeverEnded() {
        var ctx = task(4);
        LiveCall call = ctx.call(provider("anthropic", "claude-opus-5"), false, ctx.atStep(null));
        stream(call, Part.REASONING, distinct("plan", 20), 50);
        // A table of identical rows -- and the same paragraph a thousand times over, which a
        // reasoning would be ended for: written out, it is the reply's to give.
        String rows = "| eth0 | up | 192.0.2.1 | 1500 | ok |\n".repeat(400);
        assertDoesNotThrow(() -> stream(call, Part.ANSWER, rows + LOOP.repeat(1_000), 17));
        assertEquals(rows.length() + LOOP.length() * 1_000, call.shown().data().get("answerChars"));
        assertTrue(call.shown().text().contains(String.format(java.util.Locale.ROOT, "%,d of answer so far",
                rows.length() + LOOP.length() * 1_000)), "the owner sees it grow: " + call.shown().text());
        // Mutation: check the answer as the reasoning is -> the table is ended at its first check.
    }

    @Test
    @DisplayName("a reply that quotes a config whose block repeats goes on, however often it repeats and however it streams")
    void aRepeatedBlockIsNoLoop() {
        String block = "config interface 'guest'\n\toption proto 'static'\n\toption ipaddr '192.0.2.1'\n"
                + "\toption netmask '255.255.255.0'\n\toption type 'bridge'\n\toption ifname 'eth0.3'\n"
                + "\toption mtu '1500'\n\toption ip6assign '60'\n\toption delegate '0'\n\n";
        String config = distinct("line", 20) + block.repeat(12) + distinct("zone", 40);
        for (int size : new int[] {1, 8, 40, 400, 2_000, 7, 61, 997}) {
            for (Part part : new Part[] {Part.ANSWER, Part.REASONING, Part.ARGUMENTS}) {
                var ctx = task(1);
                LiveCall call = ctx.call(OLLAMA, true, ctx.atStep(null));
                assertDoesNotThrow(() -> stream(call, part, config, size), part + " in pieces of " + size);
            }
        }
    }

    @Test
    @DisplayName("a long reply that never repeats goes on to its end: nothing else bounds it")
    void nothingElseBoundsAReply() {
        var ctx = task(1);
        LiveCall call = ctx.call(OLLAMA, true, ctx.atStep(null));
        String reasoning = distinct("step", 4_000);    // some 270,000 characters
        long t0 = System.currentTimeMillis();
        assertDoesNotThrow(() -> stream(call, Part.REASONING, reasoning, 40));
        assertEquals(reasoning.length(), call.shown().data().get("reasoningChars"));
        assertTrue(System.currentTimeMillis() - t0 < 10_000, "the checks stay quick on a long reply");
    }

    @Test
    @DisplayName("the ops API reads the end of each text, and a vault value is scrubbed from what is shown")
    void whatOpsReadsAndScrubbing() {
        var ctx = task(3);
        ctx.setSecretValues(Map.of("WIFI_PSK", "Kolibri-2291"));
        LiveCall call = ctx.call(OLLAMA, true, ctx.atStep(null));
        String reasoning = distinct("thought", 100);
        stream(call, Part.REASONING, reasoning, 100);
        call.received(Part.REASONING, "The wifi password is Kolibri-2291, so I can log in.\n");
        String answer = distinct("row", 60);
        stream(call, Part.ANSWER, answer, 100);

        assertEquals("The wifi password is «vault:WIFI_PSK», so I can log in.", call.shown().data().get("line"));
        assertFalse(call.shown().text().contains("Kolibri-2291"));
        var ops = call.forOps();
        String tail = (String) ops.get("reasoningTail");
        assertEquals(LiveCall.OPS_TAIL, tail.length(), "scrubbed, then cut");
        assertTrue(tail.endsWith("The wifi password is «vault:WIFI_PSK», so I can log in.\n"), tail);
        assertEquals(answer.substring(answer.length() - LiveCall.OPS_TAIL), ops.get("answerTail"));
        assertEquals("step 3", ops.get("purpose"));
        assertEquals("writing the answer", ops.get("phase"));

        // A value the cut goes through: cut first, its end was shown.
        LiveCall straddled = ctx.call(OLLAMA, true, ctx.atStep(null));
        straddled.received(Part.REASONING, "Kolibri-2291" + distinct("pad", 200).substring(0, LiveCall.OPS_TAIL - 7) + "\n");
        assertFalse(((String) straddled.forOps().get("reasoningTail")).contains("2291"),
                "no part of the value at the start of the tail");

        // A value still being written: the line it is on is not shown until it ends.
        LiveCall writing = ctx.call(OLLAMA, true, ctx.atStep(null));
        writing.received(Part.REASONING, "Logging in.\nThe password is Kolib");
        assertEquals("Logging in.\n", writing.forOps().get("reasoningTail"));
        writing.received(Part.REASONING, "ri-2291, done.\n");
        assertEquals("Logging in.\nThe password is «vault:WIFI_PSK», done.\n", writing.forOps().get("reasoningTail"));
    }

    @Test
    @DisplayName("the watch is told as the call begins, and its end is run once, when the call ends")
    void theWatch() {
        var ctx = task(1);
        var started = new ArrayList<LiveCall>();
        var ended = new AtomicInteger();
        ctx.setCallWatch(c -> {
            started.add(c);
            return ended::incrementAndGet;
        });
        LiveCall call = ctx.call(OLLAMA, true, ctx.atStep(null));
        assertEquals(List.of(call), started);
        assertEquals(0, ended.get());
        call.close();
        call.close();
        assertEquals(1, ended.get());
    }

    // ── a tool call's text is output: nothing it holds ends it ─────────────────────────────

    /** A dashboard card of some 430 characters, the same each time but for its entity line. */
    static String card(int i) {
        return "  - type: custom:apexcharts-card\n    header:\n      show: true\n      title: Spot price and charging\n"
                + "    graph_span: 48h\n    span:\n      start: day\n    now:\n      show: true\n      label: Now\n"
                + "    series:\n      - entity: sensor.current_spot_electricity_price_" + i + "\n        type: column\n"
                + "        name: Price\n        color: '#4caf50'\n        data_generator: |\n          return entity.attributes.prices;\n"
                + "        show:\n          in_header: false\n";
    }

    /** {@code head} and then rounds of {@code round}, to {@code length} characters. */
    static String rounds(String head, java.util.function.IntFunction<String> round, int length) {
        var sb = new StringBuilder(head);
        for (int i = 0; sb.length() < length; i++) sb.append(round.apply(i));
        return sb.toString();
    }

    /** Lines no 300-character stretch of which repeats, numbered from {@code from}. */
    static String unique(int from, int lines) {
        var sb = new StringBuilder();
        for (int i = from; i < from + lines; i++) {
            sb.append("# line ").append(i * 7919 % 10007).append(" of the rest, ").append(i).append('\n');
        }
        return sb.toString();
    }

    /** {@code text} as a call's arguments, 40 characters a piece: how much of it was kept. */
    static int asACall(String text) {
        var ctx = task(3);
        LiveCall call = ctx.call(OLLAMA, true, ctx.atStep("delegation turn 2"));
        call.received(Part.CALL, "ha_api");
        for (int i = 0; i < text.length(); i += 40) {
            call.received(Part.ARGUMENTS, text.substring(i, Math.min(text.length(), i + 40)));
        }
        return (int) call.shown().data().get("answerChars");
    }

    static final String HEAD = "<tool_call>\n<function=ha_api>\n<parameter=ws_commands>\n";

    @Test
    @DisplayName("a call's data runs whole however long it repeats: grids, identical rows, cards, records -- no length ends it")
    void aCallsDataRunsWholeAtAnyLength() {
        var grid = new StringBuilder(HEAD).append("GRID = [\n");
        for (int i = 0; i < 2_000; i++) grid.append("    [0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0],\n");
        { String t = grid + "]\n" + unique(0, 100); assertEquals(t.length(), asACall(t), "a 2,000-row grid of zeros, some 140,000 characters"); }
        String row = "2026-11-11,router-1,eth0,up,1500,0,0,0,0,0,0,0,0,0,0,ok,ok,ok,ok\n";
        { String t = HEAD + row.repeat(5_000); assertEquals(t.length(), asACall(t), "5,000 identical rows, some 330,000 characters"); }
        { String t = rounds(HEAD, LiveCallTest::card, 300_000); assertEquals(t.length(), asACall(t), "700 dashboard cards that differ in one line"); }
        String body = "status: ok, checked by the nightly job, nothing to report; ".repeat(7).substring(0, 380);
        var records = new StringBuilder(HEAD);
        for (int i = 0; records.length() < 200_000; i++) {
            records.append(String.format(java.util.Locale.ROOT, "id %05d: ", i)).append(body).append('\n');
        }
        { String t = records.toString(); assertEquals(t.length(), asACall(t), "200,000 characters of records alike but for their ids"); }
        // The same paragraph again and again is a loop to the eye -- and output all the same: the
        // owner sees it grow and stops it, or the model's own limits end it.
        { String t = rounds(HEAD, i -> LOOP, 300_000); assertEquals(t.length(), asACall(t), "300,000 characters of one paragraph"); }
        // Mutation: bring back LOOP_CALL_CHECKS 10 -> the records are ended near 18,000 characters.
    }

    @Test
    @DisplayName("a million characters of reasoning stream in moments and are all counted")
    void aMillionCharactersStream() {
        var text = new StringBuilder();
        for (int i = 0; text.length() < 1_000_000; i++) text.append(" ".repeat(290 + i % 7)).append(i).append('\n');
        var ctx = task(1);
        LiveCall call = ctx.call(OLLAMA, true, ctx.atStep(null));
        long t0 = System.nanoTime();
        assertDoesNotThrow(() -> stream(call, Part.REASONING, text.toString(), 40));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertEquals(text.length(), call.shown().data().get("reasoningChars"));
        assertTrue(ms < 30_000, "streamed in " + ms + " ms");
    }
}
