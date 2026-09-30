package com.ownclaw.llm;

import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.privacy.PrivateIndex;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * No caller receives a refused or cut-off reply as if it were an answer.
 * <p>
 * One check, {@link LlmResponse#requireComplete}. The cloud gateway applies it after the call's
 * ledger row is written, so the row keeps the tokens and why the reply ended even when the
 * caller is then told the reply is no answer; the local provider applies it before it returns
 * (see OllamaStreamingTest).
 */
class ReplyCompletenessTest {

    /** A cloud provider that answers every call with one fixed reply, or fails. */
    static final class Fixed implements LlmProvider {
        final LlmResponse reply;
        final RuntimeException failure;
        Fixed(LlmResponse reply, RuntimeException failure) { this.reply = reply; this.failure = failure; }
        public LlmResponse chat(List<LlmMessage> m, LlmRequestConfig c) {
            if (failure != null) throw failure;
            return reply;
        }
        public boolean isAvailable() { return true; }
        public String name() { return "anthropic"; }
        public String model() { return "claude-opus-5"; }
    }

    static LlmResponse reply(String stopReason, String detail, String model) {
        return new LlmResponse("", List.of(), null, stopReason, detail, model, 128_000, 1_000_000,
                List.of(new LlmResponse.Usage(model, 284, 12, 1830, 29072)));
    }

    /** A reply that stopped for {@code stopReason} under these limits, with nothing billed. */
    static LlmResponse stopped(String stopReason, Integer maxOutput, Integer window) {
        return new LlmResponse("", List.of(), null, stopReason, null, "m", maxOutput, window, List.of());
    }

    /** One call through the real gateway; the rows it wrote, and what the caller got. */
    static List<EgressLedger.Row> send(LlmProvider provider, List<Object> outcome) {
        var rows = new ArrayList<EgressLedger.Row>();
        var config = new OwnClawConfig();
        config.getMentor().setProvider("anthropic");
        var gateway = new CloudGateway(provider, provider, config, rows::add, null);
        var cfg = LlmRequestConfig.DEFAULT.withEgress(
                new EgressContext("u1", "t1", "think", new PrivateIndex(), Map.of(), (h, w) -> false));
        try {
            outcome.add(gateway.chat(List.of(LlmMessage.system("S"), LlmMessage.user("hello")), cfg));
        } catch (RuntimeException e) {
            outcome.add(e);
        }
        return rows;
    }

    @Test
    @DisplayName("a refusal: the row keeps its tokens and its reason, then the caller gets ProviderRefused")
    void refusalIsRecordedThenRefused() {
        var outcome = new ArrayList<Object>();
        var rows = send(new Fixed(reply("refusal", "cyber", "claude-opus-5"), null), outcome);

        assertEquals(1, rows.size());
        var row = rows.get(0);
        assertEquals(EgressLedger.Decision.SENT, row.decision(), "it was sent and answered");
        assertEquals("refusal (cyber)", row.stopReason());
        assertEquals(284, row.promptTokens());
        assertEquals(29072, row.cacheReadTokens());
        assertTrue(row.costUsd() > 0, "a refused reply can still be billed");
        var refused = assertInstanceOf(ProviderRefused.class, outcome.get(0));
        assertEquals("cyber", refused.category());
        assertEquals("anthropic", refused.getProvider());
        assertEquals(12, refused.reply().completionTokens(), "the caller can still account for what it cost");
    }

    @Test
    @DisplayName("a reply cut at the model's maximum: recorded, then OutputTruncated with the limit's size")
    void truncationIsRecordedThenRefused() {
        var outcome = new ArrayList<Object>();
        var rows = send(new Fixed(reply("max_tokens", null, "claude-opus-5"), null), outcome);

        assertEquals("max_tokens", rows.get(0).stopReason());
        var cut = assertInstanceOf(OutputTruncated.class, outcome.get(0));
        assertEquals(OutputTruncated.Limit.MAX_OUTPUT, cut.limit());
        assertEquals(128_000, cut.tokens());
        assertEquals(29072, cut.reply().cacheReadTokens(), "the cut-off reply's counters travel with it");
    }

    @Test
    @DisplayName("a whole reply goes through untouched, recorded under the model that wrote it and priced at its rates")
    void completeReplyAndTheServedModel() {
        var outcome = new ArrayList<Object>();
        var served = reply("end_turn", null, "claude-sonnet-5");
        var rows = send(new Fixed(served, null), outcome);

        assertSame(served, outcome.get(0));
        assertEquals("end_turn", rows.get(0).stopReason());
        assertEquals("claude-sonnet-5", rows.get(0).model(), "not the configured claude-opus-5");
        assertEquals(ModelPricing.costUsd("claude-sonnet-5", served), rows.get(0).costUsd(), 1e-12);
    }

    @Test
    @DisplayName("a failed call has no reply, so its row has no stop reason")
    void failedCallHasNoStopReason() {
        var outcome = new ArrayList<Object>();
        var rows = send(new Fixed(null, new LlmException("anthropic", "HTTP 400: bad", 400, null)), outcome);
        assertEquals(EgressLedger.Decision.ERROR, rows.get(0).decision());
        assertNull(rows.get(0).stopReason());
        assertEquals("claude-opus-5", rows.get(0).model());
    }

    @Test
    @DisplayName("what each stop reason means, in one place")
    void theRule() {
        var plain = Replies.of("ok", 1, 1, 0, 0, "end_turn");
        assertSame(plain, plain.requireComplete("x"));
        var noReason = Replies.of("ok", 1, 1);
        assertSame(noReason, noReason.requireComplete("x"), "no stop reason is no limit");
        var toolUse = Replies.of("", 1, 1, 0, 0, "tool_use");
        assertSame(toolUse, toolUse.requireComplete("x"));

        var window = stopped("length", null, 262_144);
        assertEquals(OutputTruncated.Limit.CONTEXT_WINDOW,
                assertThrows(OutputTruncated.class, () -> window.requireComplete("ollama")).limit(),
                "no output limit was sent and the window is known, so a length stop is the window");
        var output = stopped("length", 4096, 262_144);
        assertEquals(OutputTruncated.Limit.MAX_OUTPUT,
                assertThrows(OutputTruncated.class, () -> output.requireComplete("x")).limit());
        var neither = stopped("length", null, null);
        assertEquals(OutputTruncated.Limit.MAX_OUTPUT,
                assertThrows(OutputTruncated.class, () -> neither.requireComplete("openai")).limit(),
                "OpenAI states neither limit, and its length is taken to be the model's maximum output");
        assertThrows(ProviderRefused.class,
                () -> Replies.of("", 1, 0, 0, 0, "content_filter").requireComplete("openai"));
        assertThrows(ProviderRefused.class,
                () -> Replies.of("", 1, 0, 0, 0, "refusal").requireComplete("anthropic"));

        var malformed = new LlmResponse("", List.of(), "the model's arguments for tool 'x' are not a JSON object",
                "tool_use", null, "m", 128_000, 1_000_000, List.of());
        assertEquals("[anthropic] the model's arguments for tool 'x' are not a JSON object",
                assertThrows(MalformedToolCall.class, () -> malformed.requireComplete("anthropic")).getMessage());
        var cutMidCall = new LlmResponse("", List.of(), "the model's arguments for tool 'x' are not a JSON object",
                "max_tokens", null, "m", 128_000, 1_000_000, List.of());
        assertThrows(OutputTruncated.class, () -> cutMidCall.requireComplete("anthropic"),
                "a call cut off at the limit is the limit, not bad JSON");
    }

    @Test
    @DisplayName("a tool call whose arguments do not parse: the row keeps every billed token, then MalformedToolCall carries them")
    void aMalformedToolCallIsRecordedThenRefused() {
        String stream = AnthropicStreamingTest.start("claude-opus-5", 50_000, 0, 200_000)
                + AnthropicStreamingTest.tool(0, "toolu_1", "shell_exec", "{\"command\": \"say \"hi\"\"}")
                + AnthropicStreamingTest.end("tool_use", null, 3_000);
        var outcome = new ArrayList<Object>();
        var rows = send(AnthropicStreamingTest.provider(AnthropicStreamingTest.api(stream)), outcome);

        var row = rows.get(0);
        assertEquals(EgressLedger.Decision.SENT, row.decision());
        assertEquals(50_000, row.promptTokens());
        assertEquals(200_000, row.cacheReadTokens());
        assertEquals(3_000, row.completionTokens());
        assertEquals("tool_use", row.stopReason());
        assertTrue(row.costUsd() > 0);
        var e = assertInstanceOf(MalformedToolCall.class, outcome.get(0));
        assertTrue(e.getMessage().contains("'shell_exec'"), e.getMessage());
        assertEquals(3_000, e.reply().completionTokens(), "the caller can still account for what it cost");
        assertFalse(e.reply().hasToolCalls());
    }

    @Test
    @DisplayName("a turn sticky routing sends to the fallback model is recorded under that model and priced at its rates")
    void aStickyTurnIsPricedAtTheModelThatServedIt() {
        var http = new FakeHttp()
                .json("/v1/models/claude-fable-5-1", 200,
                        "{\"id\":\"claude-fable-5-1\",\"max_input_tokens\":1000000,\"max_tokens\":128000}")
                .on("/v1/messages", 200, "text/event-stream",
                        AnthropicStreamingTest.start("claude-opus-4-8", 1_000_000, 0, 0)
                                + AnthropicStreamingTest.text(0, "answer")
                                + AnthropicStreamingTest.end("end_turn", null, 100_000));
        var outcome = new ArrayList<Object>();
        var row = send(AnthropicStreamingTest.provider(http, "claude-fable-5-1"), outcome).get(0);

        assertEquals("claude-opus-4-8", row.model(), "not the requested claude-fable-5-1");
        assertEquals(5.00 + 2.50, row.costUsd(), 1e-9,
                "a million input tokens at $5 and 100,000 output at $25 -- Opus 4.8's rates, not Fable 5.1's");
        assertEquals("answer", ((LlmResponse) outcome.get(0)).content());
    }
}
