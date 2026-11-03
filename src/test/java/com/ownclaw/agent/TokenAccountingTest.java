package com.ownclaw.agent;

import com.ownclaw.llm.LlmResponse;
import com.ownclaw.llm.Replies;
import com.ownclaw.llm.ToolCall;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What a model call made for a task is charged for, through the real loop: every token it was
 * billed, the prompt cache included, priced attempt by attempt at the rates of the model that ran
 * each -- the think, code, analysis and delegation calls alike, by one path (AgentLoop.account).
 * <p>
 * Anthropic reports {@code input_tokens} as only the tokens that were neither read from nor
 * written to the prompt cache; reads and writes are separate and additional. Every figure in this
 * system was derived from prompt + completion alone, so with caching working as designed — which
 * is the entire point of keeping the system prompt static — the live counter, the token_usage
 * table and every budget ceiling were reading a small fraction of the real usage. And a reply can
 * be billed for more than one attempt: a model that declines part-way hands the reply to a
 * fallback model, and a refusal can be retried on the model it names.
 */
class TokenAccountingTest {

    /** A think reply that calls {@code noop}, billed for these attempts, written by {@code model}. */
    private static LoopRig.Reply noop(String model, LlmResponse.Usage... attempts) {
        return c -> new LlmResponse("", List.of(new ToolCall("c1", "noop", Map.of())), null, "tool_use",
                null, model, null, null, List.of(attempts));
    }

    /** The answer, billed for nothing, so the step under test is the only row. */
    private static final LoopRig.Reply DONE = c -> Replies.of("", 0, 0, 0, 0, "tool_use",
            List.of(new ToolCall("c2", AgentAction.RESPOND, Map.of("message", "done"))));

    /** The task's usage row after one step replied as {@code step}. */
    private static Map<String, Object> usage(Path tmp, LoopRig.Reply step) throws Exception {
        var rig = new LoopRig(tmp, List.of(TaskEndToEndTest.NOOP));
        rig.cloud.think.add(step);
        rig.cloud.think.add(DONE);
        rig.turn(TaskEndToEndTest.session(rig), "check the network");
        return rig.jdbc.queryForMap("SELECT tokens_used, cost_usd FROM token_usage WHERE user_id = 'u1'");
    }

    private static LlmResponse.Usage attempt(String model, int prompt, int completion, int cacheWrite, int cacheRead) {
        return new LlmResponse.Usage(model, prompt, completion, cacheWrite, cacheRead);
    }

    @Test
    @DisplayName("a cached step counts the cache, not just the uncached remainder")
    void cachedStepCountsCache(@TempDir Path tmp) throws Exception {
        // The shape of a real production step: a small uncached delta over a large cached prefix.
        var row = usage(tmp, noop("claude-opus-5", attempt("claude-opus-5", 400, 250, 0, 11_800)));
        assertEquals(12_450, row.get("tokens_used"),
                "what is actually billed — dropping the cache read understates this by ~19x");
        assertEquals((400 * 5.0 + 250 * 25.0 + 11_800 * 0.5) / 1_000_000,
                ((Number) row.get("cost_usd")).doubleValue(), 1e-12, "a cache read at a tenth of input");
    }

    @Test
    @DisplayName("a cache write is billed too")
    void cacheWriteCounts(@TempDir Path tmp) throws Exception {
        var row = usage(tmp, noop("claude-opus-5", attempt("claude-opus-5", 500, 200, 10_000, 0)));
        assertEquals(10_700, row.get("tokens_used"));
    }

    @Test
    @DisplayName("with no caching the billed tokens are the reported ones, so nothing changes for Ollama or OpenAI")
    void uncachedIsUnchanged(@TempDir Path tmp) throws Exception {
        var row = usage(tmp, noop("claude-opus-5", attempt("claude-opus-5", 3_000, 500, 0, 0)));
        assertEquals(3_500, row.get("tokens_used"));
    }

    @Test
    @DisplayName("a step billed for two attempts is priced attempt by attempt, not as the model asked for or the one that answered")
    void everyAttemptAtItsOwnRates(@TempDir Path tmp) throws Exception {
        // Asked of claude-opus-5 (the provider's model); declined part-way, finished by
        // claude-sonnet-5, which the reply names. A million input tokens each.
        var row = usage(tmp, noop("claude-sonnet-5",
                attempt("claude-opus-5", 1_000_000, 0, 0, 0), attempt("claude-sonnet-5", 1_000_000, 0, 0, 0)));
        assertEquals(2_000_000, row.get("tokens_used"));
        assertEquals(5.0 + 2.0, ((Number) row.get("cost_usd")).doubleValue(), 1e-9,
                "$5 for the attempt on the model asked, $2 for the one that finished: not $10, not $4");
    }

    @Test
    @DisplayName("an attempt that names no model is priced as the model the reply names, not the one asked")
    void anUnnamedAttemptIsTheServedModels(@TempDir Path tmp) throws Exception {
        var row = usage(tmp, noop("claude-sonnet-5", attempt(null, 1_000_000, 0, 0, 0)));
        assertEquals(2.0, ((Number) row.get("cost_usd")).doubleValue(), 1e-9,
                "claude-sonnet-5's $2, not the $5 of claude-opus-5, the provider's model");
    }

    @Test
    @DisplayName("the library analysis is counted and priced by the same path, a reply that is no answer too")
    void theAnalysisIsCounted(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of());
        rig.cloud.think.add(c -> Replies.of("", 0, 0, 0, 0, "tool_use",
                List.of(new ToolCall("c1", AgentAction.SKILL_MANAGE, Map.of("action", "analyze")))));
        rig.cloud.think.add(c -> new LlmResponse("{\"summary\": \"lean\"}", List.of(), null, "end_turn", null,
                "claude-sonnet-5", null, null, List.of(attempt("claude-sonnet-5", 1_000_000, 0, 0, 0))));
        rig.cloud.think.add(c -> Replies.of("", 0, 0, 0, 0, "tool_use",
                List.of(new ToolCall("c2", AgentAction.SKILL_MANAGE, Map.of("action", "analyze")))));
        rig.cloud.think.add(c -> new LlmResponse("", List.of(), null, "refusal", "cyber", "claude-opus-5",
                null, null, List.of(attempt("claude-opus-5", 1_000_000, 0, 0, 0))));
        rig.cloud.think.add(DONE);
        rig.turn(TaskEndToEndTest.session(rig), "tidy up my skills");

        var row = rig.jdbc.queryForMap("SELECT tokens_used, cost_usd FROM token_usage WHERE user_id = 'u1'");
        assertEquals(2_000_000, row.get("tokens_used"), "both analyses were billed, the refused one too");
        assertEquals(2.0 + 5.0, ((Number) row.get("cost_usd")).doubleValue(), 1e-9);
    }

    @Test
    @DisplayName("a delegation's local calls are counted by the same path: every token billed, a reply cut off too")
    void theDelegationIsCounted(@TempDir Path tmp) throws Exception {
        // Ollama reports no prompt cache today; a local reply that did is counted as the cloud's
        // are, its reads included, because it is counted by the same account.
        var local = new com.ownclaw.llm.LlmProvider() {
            int calls;
            public LlmResponse chat(List<com.ownclaw.llm.LlmMessage> m, com.ownclaw.llm.LlmRequestConfig c) {
                if (calls++ == 0) {
                    return Replies.of("{\"tool\": \"noop\", \"params\": {}}", 100, 10, 0, 1_000, "stop");
                }
                throw new com.ownclaw.llm.OutputTruncated("ollama", com.ownclaw.llm.OutputTruncated.Limit.CONTEXT_WINDOW,
                        262_144, Replies.of("", 200, 20, 0, 2_000, "length"));
            }
            public boolean isAvailable() { return true; }
            public String name() { return "ollama"; }
        };
        var rig = new LoopRig(tmp, List.of(TaskEndToEndTest.NOOP), 600, local);
        rig.cloud.think.add(LoopRig.call(AgentAction.DELEGATE, Map.of("goal", "check the network")));
        rig.cloud.think.add(DONE);
        var r = rig.turn(TaskEndToEndTest.session(rig), "check the network");

        var completed = new com.fasterxml.jackson.databind.ObjectMapper().readTree(rig.jdbc.queryForObject(
                "SELECT details FROM events WHERE event_type = 'task_completed' AND task_id = ?", String.class, r.taskId()));
        assertEquals(100 + 10 + 1_000 + 200 + 20 + 2_000, completed.get("localTokens").asInt(),
                "both local replies, the one cut off at the window too, with their cache reads");
        assertEquals(1_100, completed.get("cloudTokens").asInt(), "the delegate step's, and nothing local in it");
    }

    @Test
    @DisplayName("the page is told the cloud's tokens kind by kind and what they cost, live; their sum stays for whatever reads it")
    void theCloudKindByKind(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of(TaskEndToEndTest.NOOP));
        rig.cloud.think.add(noop("claude-opus-5", attempt("claude-opus-5", 400, 250, 1_000, 11_800)));
        rig.cloud.think.add(noop("claude-opus-5", attempt("claude-opus-5", 300, 50, 200, 12_800)));
        rig.cloud.think.add(DONE);
        var statuses = rig.statuses();
        rig.turn(TaskEndToEndTest.session(rig), "check the network");

        double cost = ((Number) rig.jdbc.queryForObject("SELECT SUM(cost_usd) FROM token_usage WHERE user_id = 'u1'",
                Double.class)).doubleValue();
        var done = statuses.stream().filter(s -> s.type() == com.ownclaw.observability.ChatStatusEmitter.StatusMessage.Type.COMPLETED)
                .findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        var cloud = (Map<String, Object>) done.data().get("cloud");
        assertEquals(700, cloud.get("promptTokens"), "new input");
        assertEquals(300, cloud.get("completionTokens"));
        assertEquals(24_600, cloud.get("cacheReadTokens"));
        assertEquals(1_200, cloud.get("cacheWriteTokens"));
        assertEquals(cost, (double) cloud.get("costUsd"), 1e-12, "what the budget was charged");
        assertEquals(700 + 300 + 24_600 + 1_200, done.data().get("cloudTokens"), "their sum, for Telegram");
        assertTrue(done.text().endsWith(String.format(java.util.Locale.ROOT, " · $%.2f cloud", cost)),
                "the last line of the trace says what the cloud cost: " + done.text());
        // The first step's own observe frame: its running totals, kind by kind.
        var observed = statuses.stream().filter(s -> s.data() != null && "observe".equals(s.data().get("category")))
                .findFirst().orElseThrow();
        assertEquals(Map.of("promptTokens", 400, "completionTokens", 250, "cacheReadTokens", 11_800,
                "cacheWriteTokens", 1_000), withoutCost(observed.data().get("cloud")));
        // Mutation: count a cache write as a read -> 24,600 and 1,200 come out swapped.
    }

    /** A status's `cloud`, without its cost. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> withoutCost(Object cloud) {
        var copy = new java.util.HashMap<>((Map<String, Object>) cloud);
        copy.remove("costUsd");
        return copy;
    }
}
