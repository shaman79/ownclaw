package com.ownclaw.llm;

import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.privacy.PrivateIndex;
import com.ownclaw.privacy.Redactor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The second privacy review of the echo: what the gateway takes for one, and what it does not. */
class EchoProbeReviewTest {

    static final String SPEC = "Write it for the network ssid=\"ExampleHome5G\".";

    static OwnClawConfig config() {
        var c = new OwnClawConfig();
        c.getMentor().setProvider("anthropic");
        c.getPrivacy().setCanary(CloudGateway.Mode.ENFORCE);
        return c;
    }

    /** Answers each call from a script: a reply, or an exception. */
    static final class Script implements LlmProvider {
        final List<List<LlmMessage>> calls = new ArrayList<>();
        final java.util.Deque<Object> next = new java.util.ArrayDeque<>();
        public LlmResponse chat(List<LlmMessage> m, LlmRequestConfig c) {
            calls.add(m);
            Object o = next.poll();
            if (o instanceof RuntimeException e) throw e;
            if (o instanceof LlmResponse r) return r;
            return Replies.of((String) o, 100, 50, 0, 0, "end_turn");
        }
        public boolean isAvailable() { return true; }
        public String name() { return "anthropic"; }
        public String model() { return "claude-opus-5"; }
    }

    static LlmRequestConfig asWritten(EgressContext e) {
        return new LlmRequestConfig(null, null, false).withEgress(e).repliesAsWritten();
    }

    static EgressContext ctx(PrivateIndex index) {
        return new EgressContext("u1", "t1", "codegen", index, Map.of(), null, null);
    }

    List<LlmMessage> withTurn(String turn) {
        return List.of(LlmMessage.system("Write code."), LlmMessage.user(SPEC), LlmMessage.assistant(turn),
                LlmMessage.user("Continue it."));
    }

    @Test
    @DisplayName("a reply that never arrived (provider threw after streaming) or a request the canary refused hands nothing out")
    void nothingHandedOutWithoutAReply() {
        var p = new Script();
        var gw = new CloudGateway(p, p, config(), r -> { }, null, new Redactor(null));
        var e = ctx(new PrivateIndex());
        var cfg = asWritten(e);
        p.next.add(new LlmException("anthropic", "stream broke", 0, null));
        assertThrows(LlmException.class, () -> gw.chat(List.of(LlmMessage.system("Write code."), LlmMessage.user(SPEC)), cfg));
        // The caller replays what it thinks the model wrote, values in it: filtered, not an echo.
        p.next.add("done");
        gw.chat(withTurn("NET = 'ExampleHome5G'"), cfg);
        assertEquals("NET = '<ssid_1>'", p.calls.getLast().get(2).content());
    }

    @Test
    @DisplayName("a refused reply (stop_reason refusal) is handed out: it is the provider's text, an echo after")
    void aRefusedReplyIsTheProvidersText() {
        var p = new Script();
        var gw = new CloudGateway(p, p, config(), r -> { }, null, new Redactor(null));
        var cfg = asWritten(ctx(new PrivateIndex()));
        p.next.add(Replies.of("NET = 'OpenWrt'", 100, 50, 0, 0, "refusal"));
        assertThrows(RuntimeException.class, () -> gw.chat(List.of(LlmMessage.system("Write code."), LlmMessage.user(SPEC)), cfg));
        p.next.add("done");
        gw.chat(withTurn("NET = 'OpenWrt'"), cfg);
        assertEquals("NET = 'OpenWrt'", p.calls.getLast().get(2).content());
    }

    @Test
    @DisplayName("an echo plus anything, or a restored echo, is no echo; a normal request under a fresh context never echoes")
    void onlyTheExactText() {
        var p = new Script();
        var r = new Redactor(null);
        var gw = new CloudGateway(p, p, config(), x -> { }, null, r);
        var cfg = asWritten(ctx(new PrivateIndex()));
        p.next.add("NET = '<ssid_1>'\n");
        String handed = gw.chat(List.of(LlmMessage.system("Write code."), LlmMessage.user(SPEC)), cfg).content();
        assertEquals("NET = '<ssid_1>'\n", handed);
        p.next.add("x");
        gw.chat(withTurn(gw.restored(handed, cfg)), cfg);
        assertEquals("NET = '<ssid_1>'\n", p.calls.getLast().get(2).content(), "the restored text is filtered again");
        p.next.add("x");
        gw.chat(withTurn(handed + " ExampleHome5G"), cfg);
        assertTrue(p.calls.getLast().get(2).content().endsWith("<ssid_1>"), p.calls.getLast().get(2).content());
        // A think call, its own context: the same text is filtered as on main.
        var think = new LlmRequestConfig(null, null, false).withEgress(new EgressContext("u1", "t1", "think",
                new PrivateIndex(), Map.of(), null, null));
        p.next.add("x");
        gw.chat(withTurn("NET = 'ExampleHome5G'"), think);
        assertEquals("NET = '<ssid_1>'", p.calls.getLast().get(2).content());
        // The canonical constructor takes no caller's memory.
        var e = ctx(new PrivateIndex());
        var copy = new EgressContext("u1", "t1", "codegen", new PrivateIndex(), Map.of(), null, null, e.handedOut());
        assertNotSame(e.handedOut(), copy.handedOut());
    }
}
