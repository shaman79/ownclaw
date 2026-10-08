package com.ownclaw.agent;

import com.ownclaw.llm.LlmMessage;
import com.ownclaw.llm.LlmProgress.Part;
import com.ownclaw.llm.LlmProvider;
import com.ownclaw.llm.LlmRequestConfig;
import com.ownclaw.llm.LlmResponse;
import com.ownclaw.llm.RepeatedOutput;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A model call of a task as it streams in ({@link LiveCall}): the live state the owner is shown,
 * what the ops API reads of it, and the guard that ends a reply that has become a verbatim loop.
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
        // ...and the guard counts this reply alone: four attempts that wrote the same text are not
        // four copies of it.
        for (int attempt = 0; attempt < 4; attempt++) {
            call.calling(() -> { });
            assertDoesNotThrow(() -> stream(call, Part.REASONING, distinct("same", 60), 41));
        }
        // Mutation: keep the text across requests -> the fourth attempt is ended as a loop.
    }

    @Test
    @DisplayName("a reasoning that has become a loop is ended, saying what was seen, soon after it can be seen")
    void aReasoningLoopIsEnded() {
        var ctx = task(6);
        LiveCall call = ctx.call(OLLAMA, true, ctx.atStep("delegation turn 1"));
        String preamble = distinct("thought", 30);
        String reply = preamble + LOOP.repeat(60);
        int fed = 0;
        RepeatedOutput looped = null;
        try {
            for (int i = 0; i < reply.length(); i += 23) {
                String piece = reply.substring(i, Math.min(reply.length(), i + 23));
                fed += piece.length();
                call.received(Part.REASONING, piece);
            }
        } catch (RepeatedOutput e) {
            looped = e;
        }
        assertNotNull(looped, "a loop of " + LOOP.length() + " characters, 60 times, went on");
        assertTrue(fed <= preamble.length() + LiveCall.LOOP_REPEATS * LOOP.length() + LiveCall.LOOP_CHECK_EVERY + 23,
                "ended within a check of being seen: " + fed + " characters fed");
        assertTrue(looped.seen().matches("the local model repeated the same 300 characters 4 times, after 0s and "
                + "[0-9,]+ characters of reasoning"), looped.seen());
        assertTrue(looped.seen().contains(String.format(java.util.Locale.ROOT, "%,d characters", fed)),
                "the count is the text so far: " + looped.seen() + " / " + fed);
        assertEquals("ollama", looped.getProvider());
        // Mutation: LOOP_REPEATS 400 -> the loop is never ended.
    }

    @Test
    @DisplayName("an answer that has become a loop is ended as one too, apart from the reasoning")
    void anAnswerLoopIsEnded() {
        var ctx = task(4);
        LiveCall call = ctx.call(provider("anthropic", "claude-opus-5"), false, ctx.atStep(null));
        stream(call, Part.REASONING, distinct("plan", 20), 50);
        var looped = assertThrows(RepeatedOutput.class,
                () -> stream(call, Part.ANSWER, "| eth0 | up | 192.0.2.1 | 1500 | ok |\n".repeat(400), 17));
        assertTrue(looped.seen().startsWith("the cloud model repeated the same 300 characters 4 times, after 0s and "),
                looped.seen());
        assertTrue(looped.seen().endsWith("characters of its answer"), looped.seen());
        assertEquals("anthropic", looped.getProvider());
    }

    @Test
    @DisplayName("a long reply that quotes a config file whose blocks repeat once or twice goes on, however it streams; a fourth copy is a loop")
    void aRepeatedBlockIsNoLoop() {
        // A stanza of some 500 characters, the same three times: written once and repeated twice,
        // as a router's config can hold the same block for three networks. Around it, some 30,000
        // characters of lines no stretch of which repeats. The last copy ends where the text is
        // checked, so the check reads the end of the block -- in the text three times -- as the
        // last characters it looks for.
        String block = "config interface 'guest'\n\toption proto 'static'\n\toption ipaddr '192.0.2.1'\n"
                + "\toption netmask '255.255.255.0'\n\toption type 'bridge'\n\toption ifname 'eth0.3'\n"
                + "\toption mtu '1500'\n\toption ip6assign '60'\n\toption delegate '0'\n\n"
                + "config dhcp 'guest'\n\toption interface 'guest'\n\toption start '100'\n\toption limit '150'\n"
                + "\toption leasetime '12h'\n\toption dhcpv4 'server'\n\tlist dhcp_option '6,192.0.2.53'\n\n"
                + "config zone\n\toption name 'guest'\n\tlist network 'guest'\n\toption input 'REJECT'\n"
                + "\toption output 'ACCEPT'\n\toption forward 'REJECT'\n\n";
        assertTrue(block.length() > LiveCall.LOOP_WINDOW, "the last characters lie wholly inside a copy: " + block.length());
        String thrice = endingAtACheck(distinct("line", 20) + block + distinct("route", 30) + block
                + distinct("rule", 25), block) + distinct("zone", 400);
        String fourTimes = endingAtACheck(distinct("line", 20) + block + distinct("route", 30) + block
                + distinct("rule", 25) + block + distinct("host", 12), block) + distinct("zone", 400);
        // Pieces that divide 2,000, so the checks fall where the copies end; and some that do not.
        for (int size : new int[] {1, 8, 40, 400, 2_000, 7, 61, 997}) {
            for (Part part : new Part[] {Part.ANSWER, Part.REASONING}) {
                var ctx = task(1);
                LiveCall call = ctx.call(OLLAMA, true, ctx.atStep(null));
                assertDoesNotThrow(() -> stream(call, part, thrice, size),
                        "a block three times is no loop: in pieces of " + size);
            }
        }
        for (int size : new int[] {1, 8, 40, 400, 2_000}) {
            var ctx = task(1);
            LiveCall call = ctx.call(OLLAMA, true, ctx.atStep(null));
            assertThrows(RepeatedOutput.class, () -> stream(call, Part.ANSWER, fourTimes, size),
                    "the same block a fourth time is: in pieces of " + size);
        }
        // Mutation: LOOP_REPEATS 3 -> the check at the end of the third copy ends the reply.
    }

    /**
     * {@code text}, padded with characters that repeat nothing so that, with {@code last} after
     * it, it ends where the text is checked for a loop: a multiple of {@link LiveCall#LOOP_CHECK_EVERY}.
     */
    static String endingAtACheck(String text, String last) {
        int pad = LiveCall.LOOP_CHECK_EVERY - (text.length() + last.length()) % LiveCall.LOOP_CHECK_EVERY;
        var unique = new StringBuilder();
        for (int i = 0; unique.length() < pad; i++) unique.append('#').append(i).append(' ');
        return text + unique.substring(0, pad) + last;
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
        assertEquals(LiveCall.OPS_TAIL - "Kolibri-2291".length() + "«vault:WIFI_PSK»".length(), tail.length());
        assertTrue(tail.endsWith("The wifi password is «vault:WIFI_PSK», so I can log in.\n"), tail);
        assertEquals(answer.substring(answer.length() - LiveCall.OPS_TAIL), ops.get("answerTail"));
        assertEquals("step 3", ops.get("purpose"));
        assertEquals("writing the answer", ops.get("phase"));
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
}
