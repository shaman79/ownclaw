package com.ownclaw.agent;

import com.ownclaw.agent.tools.ToolRegistry;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.conversation.MigratedDatabase;
import com.ownclaw.llm.EgressRefused;
import com.ownclaw.llm.LlmException;
import com.ownclaw.llm.LlmMessage;
import com.ownclaw.llm.LlmProvider;
import com.ownclaw.llm.LlmRequestConfig;
import com.ownclaw.llm.LlmResponse;
import com.ownclaw.llm.OutputTruncated;
import com.ownclaw.llm.ProviderRefused;
import com.ownclaw.llm.ToolCall;
import com.ownclaw.llm.Replies;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A step whose reply cannot be run is called what it was.
 * <p>
 * Production, 29 September: two think calls came back with no text, no tool call and zero output
 * tokens. The empty reply was parsed, the parser invented "Empty response from reasoning engine.",
 * and the loop told a model holding a tools array "PARSE ERROR. Your output: ... Required format:
 * {tool, params}" -- a format it had not broken -- then replayed the invented sentence to it as its
 * own turn. A failed call was answered the same way, with "I encountered an error while reasoning
 * about this task." put in the model's mouth. And a refused or cut-off reply was asked again three
 * times, as if the model had fumbled it, before the owner was told "3 consecutive reasoning
 * failures".
 */
class EmptyReplyTest {

    /** A provider that answers each call from a script -- a reply, or an exception to throw. */
    static final class Script implements LlmProvider {
        final Deque<Object> replies = new ArrayDeque<>();
        final List<List<LlmMessage>> requests = new ArrayList<>();
        final boolean tools;
        Script(boolean tools, Object... replies) {
            this.tools = tools;
            this.replies.addAll(List.of(replies));
        }
        public LlmResponse chat(List<LlmMessage> m, LlmRequestConfig c) {
            requests.add(List.copyOf(m));
            Object next = replies.size() > 1 ? replies.poll() : replies.peek();
            if (next instanceof RuntimeException e) throw e;
            return (LlmResponse) next;
        }
        public boolean isAvailable() { return true; }
        public boolean supportsTools() { return tools; }
        public String name() { return "anthropic"; }
        public String model() { return "claude-opus-5"; }
    }

    static LlmResponse empty() {
        return Replies.of("", 300, 0, 0, 0, "end_turn");
    }

    static LlmResponse respond(String message) {
        return Replies.of("", 300, 20, 0, 0, "tool_use",
                List.of(new ToolCall("c1", AgentAction.RESPOND, Map.of("message", message))));
    }

    private static ThinkResult think(Script provider) {
        return think(new AgentContext("u1", "t1", "What is the capital of France?"), provider);
    }

    private static ThinkResult think(AgentContext ctx, Script provider) {
        return new ThinkingEngine(new ToolRegistry(List.of()), new OwnClawConfig(), null)
                .decideNextActionFull(ctx, provider);
    }

    // ── what the engine makes of each reply ──

    @Test
    @DisplayName("an empty reply is an empty reply, with its stop reason -- on both protocols")
    void anEmptyReplyIsNotAParseError() {
        for (boolean tools : new boolean[] {true, false}) {
            AgentAction a = think(new Script(tools, empty())).action();

            assertEquals(ThinkingEngine.THINKING, a.tool(), "nothing is run for it");
            assertTrue(a.reasoning().startsWith("Your previous reply was empty (stop_reason: end_turn)"),
                    a.reasoning());
            assertFalse(a.reasoning().contains("PARSE ERROR") || a.reasoning().contains("format"),
                    "nothing came back, so there is no format it broke: " + a.reasoning());
            assertEquals(Map.of(), a.params(), "and no sentence invented to stand for the reply");
        }
    }

    @Test
    @DisplayName("a failed call is a failed call, and no answer is invented for the model")
    void aFailedCallIsSaidToBeOne() {
        var ctx = new AgentContext("u1", "t1", "What is the capital of France?");
        var failed = new LlmException("anthropic", "HTTP 500: internal error", 500, null);
        AgentAction a = think(ctx, new Script(true, failed)).action();

        assertEquals(ThinkingEngine.THINKING, a.tool());
        assertTrue(a.reasoning().contains("the call to the model failed ([anthropic] HTTP 500: internal error)"),
                a.reasoning());
        assertEquals(Map.of(), a.params());
        assertFalse(a.reasoning().contains("I encountered an error"), a.reasoning());
    }

    @Test
    @DisplayName("a refusal, a reply cut off at a limit and a privacy refusal are not asked again")
    void notToAskAgain() {
        var refusal = new ProviderRefused("anthropic", Replies.of("", 300, 0, 0, 0, "refusal"));
        var cutOff = new OutputTruncated("anthropic", OutputTruncated.Limit.MAX_OUTPUT, 128_000,
                Replies.of("{\"tool\": \"respond\", \"params\": {\"message\": \"Par", 300, 128_000, 0, 0, "max_tokens"));
        var tooLong = new OutputTruncated("anthropic", OutputTruncated.Limit.CONTEXT_WINDOW, 1_000_000, null);
        var privacy = new EgressRefused("anthropic");
        for (RuntimeException e : List.of(refusal, cutOff, tooLong, privacy)) {
            var thrown = assertThrows(RuntimeException.class, () -> think(new Script(true, e)),
                    e.getClass().getSimpleName() + " became a step to ask again");
            assertSame(e, thrown, "passed to the loop unchanged, so it can say which it was");
        }
    }

    @Test
    @DisplayName("a reply that is not an action is quoted whole, and the format restated")
    void notAnActionIsQuotedWhole() {
        String prose = "The capital of France is Paris. ".repeat(700);   // ~22 KB
        AgentAction a = think(new Script(false, Replies.of(prose, 300, 5_000, 0, 0, "end_turn"))).action();

        assertEquals(ThinkingEngine.THINKING, a.tool());
        assertEquals(prose, a.params().get("message"), "what the model wrote, whole");
        assertTrue(a.reasoning().contains(prose), "quoted to it whole, not its first 500 characters");
        assertTrue(a.reasoning().contains("Reply with one JSON object"), a.reasoning());
    }

    @Test
    @DisplayName("JSON that names no tool is quoted, not replaced by a sentence the model never wrote")
    void jsonWithoutAToolIsQuoted() {
        String json = "{\"thought\": \"compare the two\", \"plan\": [1, 2]}";
        AgentAction a = think(new Script(false, Replies.of(json, 300, 30, 0, 0, "end_turn"))).action();

        assertEquals(ThinkingEngine.THINKING, a.tool());
        assertTrue(a.reasoning().contains("names no tool") && a.reasoning().contains(json), a.reasoning());
        assertFalse(a.reasoning().contains("I had trouble deciding"), a.reasoning());
    }

    // ── what the loop does with them ──

    /** The real loop, engine and gateway, with the cloud behind the gateway answering from a script. */
    private static AgentResult run(JdbcTemplate jdbc, Script cloud, AgentContext ctx) {
        return run(jdbc, cloud, ctx, new ToolRegistry(List.of()), new OwnClawConfig());
    }

    private static AgentResult run(JdbcTemplate jdbc, Script cloud, AgentContext ctx,
                                   ToolRegistry registry, OwnClawConfig config) {
        config.getMentor().setProvider("anthropic");
        return AssistantPartsTest.loop(jdbc, registry, AssistantPartsTest.gateway(cloud, new ArrayList<>()),
                config).executeWithContext(ctx);
    }

    static LlmResponse call(String tool, Map<String, Object> args) {
        return Replies.of("", 300, 20, 0, 0, "tool_use", List.of(new ToolCall("c-" + tool, tool, args)));
    }

    @Test
    @DisplayName("three empty replies stop the task, each recorded as the step it was, the last included")
    void threeEmptyRepliesStopTheTask(@TempDir Path tmp) throws Exception {
        var jdbc = MigratedDatabase.at(tmp.resolve("t.db"));
        var cloud = new Script(true, empty());
        var ctx = new AgentContext("u1", "t-empty", "What is the capital of France?");

        AgentResult r = run(jdbc, cloud, ctx);

        assertEquals(AgentResult.TerminationReason.FAILURE_LIMIT, r.terminationReason(), r.response());
        assertEquals("The model produced nothing that could be run 3 times in a row.", r.response());
        assertEquals(3, cloud.requests.size());

        var turns = ctx.trajectory().turns();
        assertEquals(3, turns.size(), "the step that ended the task is recorded too");
        assertTrue(turns.stream().allMatch(t -> ThinkingEngine.THINKING.equals(t.action().tool())
                && ThinkingEngine.THINKING.equals(t.observation().tool())));
        assertFalse(turns.get(0).observation().output().contains("WARNING"));
        assertTrue(turns.get(1).observation().output().contains("WARNING: one more step like this"),
                "warned before the step that stops it: " + turns.get(1).observation().output());
        assertFalse(turns.get(2).observation().output().contains("WARNING"),
                "the last one stopped the task; a warning about the next would not be true");

        List<String> rows = jdbc.queryForList("SELECT summary FROM events WHERE task_id = ? "
                + "AND event_type = 'step' ORDER BY id", String.class, "t-empty");
        assertEquals(List.of("_thinking FAILED (0ms)", "_thinking FAILED (0ms)", "_thinking FAILED (0ms)"),
                rows, "a step whose reply could not be used is not a respond that failed");

        // What the model was told reached it, in its place, with no turn invented for it.
        var second = cloud.requests.get(1);
        assertTrue(second.stream().noneMatch(m -> m.role() == LlmMessage.Role.ASSISTANT),
                "there is no reply to replay: " + second);
        assertTrue(second.get(second.size() - 1).content()
                .contains("Your previous reply was empty (stop_reason: end_turn)"));
    }

    @Test
    @DisplayName("five in a task stop it, though never three in a row -- with a warning before the fifth")
    void fiveInATaskStopIt(@TempDir Path tmp) throws Exception {
        var registry = new ToolRegistry(List.of(AssistantPartsTest.tool("fetch_page", List.of(),
                p -> "the page says the service is up")));
        var cloud = new Script(true, empty(), call("fetch_page", Map.of()), empty(), call("fetch_page", Map.of()),
                empty(), call("fetch_page", Map.of()), empty(), call("fetch_page", Map.of()), empty());
        var ctx = new AgentContext("u1", "t-five", "Is the service up?");

        AgentResult r = run(MigratedDatabase.at(tmp.resolve("t.db")), cloud, ctx, registry, new OwnClawConfig());

        assertEquals(AgentResult.TerminationReason.FAILURE_LIMIT, r.terminationReason(), r.response());
        assertEquals("The model produced nothing that could be run 5 times in this task.", r.response());
        var told = ctx.trajectory().turns().stream()
                .filter(t -> ThinkingEngine.THINKING.equals(t.action().tool()))
                .map(t -> t.observation().output()).toList();
        assertEquals(5, told.size(), "every one on the record, the one that ended the task too");
        for (int i = 0; i < 5; i++) {
            assertEquals(i == 3, told.get(i).contains("WARNING: one more step like this"),
                    "warned before the fifth, and only then: " + i + " " + told.get(i));
        }
    }

    @Test
    @DisplayName("the last step allowed, producing nothing to run, ends the task as out of steps, saying so")
    void theLastStepProducingNothing(@TempDir Path tmp) throws Exception {
        var config = new OwnClawConfig();
        config.getTasks().setMaxPlanSteps(2);
        var cloud = new Script(true, empty());
        var ctx = new AgentContext("u1", "t-last", "What is the capital of France?");

        AgentResult r = run(MigratedDatabase.at(tmp.resolve("t.db")), cloud, ctx, new ToolRegistry(List.of()), config);

        assertEquals(AgentResult.TerminationReason.MAX_STEPS, r.terminationReason(), r.response());
        assertEquals("The task used all 2 steps it may take; the last produced nothing that could be run.",
                r.response(), "not an offer to continue a task whose last step did nothing");
        assertFalse(ctx.trajectory().turns().get(1).observation().output().contains("WARNING"),
                "the task stopped; a warning about the next step would not be true");
    }

    @Test
    @DisplayName("an answer between two empty replies ends the run of them, even one that is not delivered")
    void anAnswerEndsTheRun(@TempDir Path tmp) throws Exception {
        // {{7}} names no result, so the answer is refused and the model asked again -- but it was
        // an answer, not a step that produced nothing.
        var cloud = new Script(true, empty(), empty(), respond("{{7}}"), empty(), respond("Paris."));
        var ctx = new AgentContext("u1", "t-between", "What is the capital of France?");

        AgentResult r = run(MigratedDatabase.at(tmp.resolve("t.db")), cloud, ctx);

        assertEquals(AgentResult.TerminationReason.COMPLETED, r.terminationReason(), r.response());
        assertEquals("Paris.", r.response());
        assertEquals(5, cloud.requests.size());
    }

    @Test
    @DisplayName("after an empty reply the model is asked again, and its answer ends the task")
    void thenTheAnswer(@TempDir Path tmp) throws Exception {
        var cloud = new Script(true, empty(), respond("Paris."));
        var ctx = new AgentContext("u1", "t-then", "What is the capital of France?");

        AgentResult r = run(MigratedDatabase.at(tmp.resolve("t.db")), cloud, ctx);

        assertEquals(AgentResult.TerminationReason.COMPLETED, r.terminationReason(), r.response());
        assertEquals("Paris.", r.response());
        assertEquals(2, cloud.requests.size());
    }
}
