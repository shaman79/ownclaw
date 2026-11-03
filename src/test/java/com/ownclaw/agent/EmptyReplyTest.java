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
            assertTrue(a.reasoning().startsWith("Your previous reply was empty (stop reason: end_turn)"),
                    a.reasoning());
            assertFalse(a.reasoning().contains("PARSE ERROR") || a.reasoning().contains("format"),
                    "nothing came back, so there is no format it broke: " + a.reasoning());
            assertEquals(Map.of(), a.params(), "and no sentence invented to stand for the reply");
        }
    }

    @Test
    @DisplayName("a delegation's local model is told of an empty reply in the same words as the cloud model")
    void bothLoopsSayItAlike() {
        String told = think(new Script(true, empty())).action().reasoning();
        String sentence = told.substring(0, told.indexOf("nothing was run.") + "nothing was run.".length());

        var ping = new DelegationBehaviourTest.FakeTool("ping", false, List.of(), p -> com.ownclaw.agent.tools.ToolResult.success("pong"));
        var local = new DelegationBehaviourTest.NativeTurns(empty(),
                DelegationBehaviourTest.turn(new ToolCall("a", "done", Map.of("summary", "nothing to do"))));
        DelegationBehaviourTest.executor(local, new DelegationBehaviourTest.Usage(), ping)
                .execute(DelegationBehaviourTest.plan("ping"), DelegationBehaviourTest.task(), DelegationBehaviourTest.UNCOUNTED);

        assertTrue(local.toldAfter(0).startsWith(sentence), sentence + " / " + local.toldAfter(0));
    }

    @Test
    @DisplayName("a failed call is a failed call, and no answer is invented for the model")
    void aFailedCallIsSaidToBeOne() {
        var ctx = new AgentContext("u1", "t1", "What is the capital of France?");
        var failed = new LlmException("anthropic", "the reply stream ended before message_stop", 0, null);
        AgentAction a = think(ctx, new Script(true, failed)).action();

        assertEquals(ThinkingEngine.THINKING, a.tool());
        assertTrue(a.reasoning().contains("the call to the model failed ([anthropic] the reply stream ended "
                + "before message_stop)"), a.reasoning());
        assertEquals(Map.of(), a.params());
        assertFalse(a.reasoning().contains("I encountered an error"), a.reasoning());
    }

    @Test
    @DisplayName("a refusal, a reply cut off at a limit, a privacy refusal and a provider that cannot be reached are not asked again")
    void notToAskAgain() {
        var refusal = new ProviderRefused("anthropic", Replies.of("", 300, 0, 0, 0, "refusal"));
        var cutOff = new OutputTruncated("anthropic", OutputTruncated.Limit.MAX_OUTPUT, 128_000,
                Replies.of("{\"tool\": \"respond\", \"params\": {\"message\": \"Par", 300, 128_000, 0, 0, "max_tokens"));
        var tooLong = new OutputTruncated("anthropic", OutputTruncated.Limit.CONTEXT_WINDOW, 1_000_000, null);
        var privacy = new EgressRefused("anthropic");
        var badKey = new LlmException("anthropic", "HTTP 401: invalid x-api-key", 401, null);
        // The loop decides what happens next: the local model takes the task over, or it ends.
        var noInternet = new LlmException("anthropic", "Connection failed: api.anthropic.com", 0,
                new java.net.UnknownHostException("api.anthropic.com"));
        var outage = new LlmException("anthropic", "HTTP 529: overloaded", 529, null);   // the backoff gave up
        for (RuntimeException e : List.of(refusal, cutOff, tooLong, privacy, badKey, noInternet, outage)) {
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
                config).run(ctx);
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
        assertTrue(r.response().startsWith("**Stopped:** The model produced nothing that could be run or "
                + "delivered 3 times in a row.\n\n"), r.response());
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
                .contains("Your previous reply was empty (stop reason: end_turn)"));
    }

    @Test
    @DisplayName("empty replies scattered through a long task that keeps working do not end it, however many")
    void scatteredOnesDoNotEndIt(@TempDir Path tmp) throws Exception {
        var registry = new ToolRegistry(List.of(AssistantPartsTest.tool("fetch_page", List.of(),
                p -> "the page says the service is up")));
        var script = new ArrayList<Object>();
        for (int i = 0; i < 6; i++) {
            script.add(empty());
            // A page each time: the same call made again and again is a loop the critic ends.
            script.add(call("fetch_page", Map.of("page", i)));
        }
        script.add(respond("It is up."));
        var cloud = new Script(true, script.toArray());
        var ctx = new AgentContext("u1", "t-six", "Is the service up?");

        AgentResult r = run(MigratedDatabase.at(tmp.resolve("t.db")), cloud, ctx, registry, new OwnClawConfig());

        assertEquals(AgentResult.TerminationReason.COMPLETED, r.terminationReason(), r.response());
        assertEquals("It is up.", r.response());
        var told = ctx.trajectory().turns().stream()
                .filter(t -> ThinkingEngine.THINKING.equals(t.action().tool()))
                .map(t -> t.observation().output()).toList();
        assertEquals(6, told.size(), "every one on the record");
        assertTrue(told.stream().noneMatch(t -> t.contains("WARNING")),
                "never two in a row, so never a warning: " + told);
        // Mutation: count them across the task again -> the fifth ends it, FAILURE_LIMIT.
    }

    @Test
    @DisplayName("an answer that is not delivered ran nothing: three such steps in a row end the task, an empty reply among them or not")
    void anUndeliveredAnswerRanNothing(@TempDir Path tmp) throws Exception {
        // {{7}} names no result, so the answer is refused and the model asked again. Counted as
        // anything but a step that ran nothing, a model that kept answering it never ended.
        var scripts = List.of(new Object[] {respond("{{7}}")},
                new Object[] {empty(), empty(), respond("{{7}}"), respond("Paris.")});
        for (int i = 0; i < scripts.size(); i++) {
            var cloud = new Script(true, scripts.get(i));
            var ctx = new AgentContext("u1", "t-undelivered", "What is the capital of France?");

            AgentResult r = run(MigratedDatabase.at(tmp.resolve(i + ".db")), cloud, ctx);

            assertEquals(AgentResult.TerminationReason.FAILURE_LIMIT, r.terminationReason(), r.response());
            assertEquals(3, cloud.requests.size());
            assertTrue(r.response().startsWith("**Stopped:** The model produced nothing that could be run or "
                    + "delivered 3 times in a row."), r.response());
            assertTrue(ctx.trajectory().turns().get(1).observation().output()
                    .contains("WARNING: one more step like this"), "warned before the step that stops it");
        }
    }

    @Test
    @DisplayName("answers that are not delivered, between steps that ran, do not end the task")
    void undeliveredAnswersBetweenStepsThatRan(@TempDir Path tmp) throws Exception {
        var registry = new ToolRegistry(List.of(AssistantPartsTest.tool("fetch_page", List.of(),
                p -> "the page says the service is up")));
        var script = new ArrayList<Object>();
        for (int i = 0; i < 5; i++) {
            script.add(respond("{{" + (i + 9) + "}}"));
            script.add(respond("{{" + (i + 9) + "}}"));
            script.add(call("fetch_page", Map.of("page", i)));
        }
        script.add(respond("It is up."));
        var cloud = new Script(true, script.toArray());
        var ctx = new AgentContext("u1", "t-between", "Is the service up?");

        AgentResult r = run(MigratedDatabase.at(tmp.resolve("t.db")), cloud, ctx, registry, new OwnClawConfig());

        assertEquals(AgentResult.TerminationReason.COMPLETED, r.terminationReason(), r.response());
        assertEquals("It is up.", r.response());
        assertEquals(16, cloud.requests.size());
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

    @Test
    @DisplayName("a tool call that cannot be run is shown to the model as it wrote it, counted, and asked again -- not an ending")
    void aCallThatCannotBeRunIsAskedAgain(@TempDir Path tmp) throws Exception {
        // Anthropic does not check a tool input it streams: the arguments can end half-written.
        String arguments = "{\"to\": \"owner@example.org\", \"body\": \"The capital is";
        var malformed = new LlmResponse("Sending the answer.", List.of(),
                "the model's arguments for tool 'send_email' are not a JSON object (Unexpected end-of-input):\n"
                        + arguments,
                "tool_use", null, "claude-opus-5", 128_000, 1_000_000,
                List.of(new LlmResponse.Usage("claude-opus-5", 300, 40, 0, 0)));
        var cloud = new Script(true, malformed, respond("Paris."));
        var ctx = new AgentContext("u1", "t-malformed", "What is the capital of France?");

        AgentResult r = run(MigratedDatabase.at(tmp.resolve("t.db")), cloud, ctx);

        assertEquals(AgentResult.TerminationReason.COMPLETED, r.terminationReason(), r.response());
        assertEquals("Paris.", r.response());
        assertEquals(ThinkingEngine.THINKING, ctx.trajectory().turns().get(0).action().tool(), "nothing was run");
        var second = cloud.requests.get(1);
        String told = second.get(second.size() - 1).content();
        assertTrue(told.contains("Your previous reply held a tool call that cannot be run, so nothing was run."), told);
        // Through the gateway's filter, as every part is: the address as its placeholder, which is
        // what a cloud model shown only placeholders writes to begin with.
        assertTrue(told.contains(arguments.replace("owner@example.org", "<email_1>"))
                        && told.contains("Sending the answer."),
                "the model is shown what it wrote, the call as it wrote it included: " + told);
        assertFalse(told.contains("never came"), "the reply came: " + told);
        assertEquals(340 + 320, ctx.cloudTokens(), "the reply that could not be run was billed, and is counted");
    }

    /** A call that never reached the model: what a network that cannot be reached throws. */
    static LlmException unreachable() {
        return new LlmException("anthropic",
                "Connection failed asking the Models API about 'claude-opus-5': timeout", 0, null);
    }

    static final String UNREACHABLE = "[anthropic] Connection failed asking the Models API about 'claude-opus-5': timeout";

    @Test
    @DisplayName("calls that failed stop the task as calls that failed, saying how -- not as a model that produced nothing")
    void failedCallsAreNamedAsSuch(@TempDir Path tmp) throws Exception {
        var cloud = new Script(true, unreachable());
        var ctx = new AgentContext("u1", "t-unreachable", "What is the capital of France?");

        AgentResult r = run(MigratedDatabase.at(tmp.resolve("t.db")), cloud, ctx);

        assertEquals(AgentResult.TerminationReason.FAILURE_LIMIT, r.terminationReason(), r.response());
        assertTrue(r.response().startsWith("**Stopped:** The call to the model failed 3 times in a row (the last: "
                + UNREACHABLE + ").\n\n"), r.response());
        // Mutation: the stop names steps that ran nothing whatever they were -> "The model
        // produced nothing that could be run", of a model that was never reached.
    }

    @Test
    @DisplayName("a run of empty replies and failed calls says how many were which")
    void aMixedRunSaysWhichWasWhich(@TempDir Path tmp) throws Exception {
        var cloud = new Script(true, empty(), unreachable(), empty());
        var ctx = new AgentContext("u1", "t-mixed", "What is the capital of France?");

        AgentResult r = run(MigratedDatabase.at(tmp.resolve("t.db")), cloud, ctx);

        assertEquals(AgentResult.TerminationReason.FAILURE_LIMIT, r.terminationReason(), r.response());
        assertTrue(r.response().startsWith("**Stopped:** 3 steps in a row ran nothing: the model's reply could not "
                + "be run or delivered 2 times, and the call to it failed 1 time (the last: " + UNREACHABLE
                + ").\n\n"), r.response());
    }

    @Test
    @DisplayName("a request the provider refuses as it stands -- a key it does not take -- is not sent again: with no local model, the task ends saying so")
    void aRefusedRequestIsNotSentAgain(@TempDir Path tmp) throws Exception {
        String refused = "HTTP 401: {\"type\":\"error\",\"error\":{\"type\":\"authentication_error\","
                + "\"message\":\"invalid x-api-key\"}}";
        var cloud = new Script(true, new LlmException("anthropic", refused, 401, null));
        var ctx = new AgentContext("u1", "t-401", "What is the capital of France?");

        AgentResult r = run(MigratedDatabase.at(tmp.resolve("t.db")), cloud, ctx);

        assertEquals(AgentResult.TerminationReason.ERROR, r.terminationReason(), r.response());
        assertEquals(1, cloud.requests.size(), "sent again, the same request is refused again");
        assertTrue(r.response().startsWith("**Stopped:** the cloud model (anthropic) could not be used -- it "
                + "rejected the API key (HTTP 401) -- and the local model is not available to go on with the "
                + "task.\n\n"), r.response());
        // Mutation: ask again after any failed call -> three requests, and an ending that blames
        // the model.
    }

    @Test
    @DisplayName("a provider's refusal that quotes a private result is named in the ending, never quoted: later prompts read it")
    void aRefusalQuotingAPrivateResultIsNotQuoted(@TempDir Path tmp) throws Exception {
        var registry = new ToolRegistry(List.of(AssistantPartsTest.tool("router_audit",
                List.of("ROUTER_PASS"), p -> AssistantPartsTest.REPORT)));
        String quoted = AssistantPartsTest.REPORT.substring(AssistantPartsTest.REPORT.indexOf("wireless.default_radio0"));
        var cloud = new Script(true, call("router_audit", Map.of()),
                new LlmException("anthropic", "HTTP 400: the request held " + quoted, 400, null));
        var ctx = new AgentContext("u1", "t-quote", "Audit the routers.");
        ctx.setPersonalSources(List.of("ROUTER_"));   // the audit stands for a private result

        AgentResult r = run(MigratedDatabase.at(tmp.resolve("t.db")), cloud, ctx, registry, new OwnClawConfig());

        assertEquals(AgentResult.TerminationReason.ERROR, r.terminationReason(), r.response());
        assertTrue(r.response().startsWith("**Stopped:** the call to the model failed (its message quotes a private "
                + "result; it is in the log).\n\n"), r.response());
        String said = com.ownclaw.privacy.PrivateIndex.normalise(r.response());
        String report = com.ownclaw.privacy.PrivateIndex.normalise(quoted);
        for (int i = 0; i + com.ownclaw.privacy.PrivateIndex.WINDOW <= report.length(); i++) {
            assertFalse(said.contains(report.substring(i, i + com.ownclaw.privacy.PrivateIndex.WINDOW)),
                    "a window of the private result is in what later prompts read: " + r.response());
        }
        // Mutation: end with the provider's message as it is -> the router's key in the chat.
    }
}
