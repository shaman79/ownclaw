package com.ownclaw.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownclaw.agent.tools.Tool;
import com.ownclaw.agent.tools.ToolExecutionContext;
import com.ownclaw.agent.tools.ToolParam;
import com.ownclaw.agent.tools.ToolRegistry;
import com.ownclaw.agent.tools.ToolResult;
import com.ownclaw.llm.LlmMessage;
import com.ownclaw.llm.LlmProvider;
import com.ownclaw.llm.LlmRequestConfig;
import com.ownclaw.llm.LlmResponse;
import com.ownclaw.observability.ChatStatusEmitter;
import com.ownclaw.privacy.Label;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The delegation loop, driven for real: the actual {@link LocalExecutor}, a scripted local model
 * and fake tools that record what they were called with.
 * <p>
 * These replace source-text checks of the same guards. A reviewer showed twice that a text scan
 * can be satisfied by code that no longer does the thing — so these assert what reaches the
 * tool, what the model was shown, and what the cloud gets back.
 */
class DelegationBehaviourTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** A local model that answers from a script and remembers everything it was sent. */
    static final class Scripted implements LlmProvider {
        final Deque<String> replies = new ArrayDeque<>();
        final List<List<LlmMessage>> calls = new ArrayList<>();
        Scripted(String... r) { replies.addAll(List.of(r)); }
        public LlmResponse chat(List<LlmMessage> m, LlmRequestConfig c) {
            calls.add(List.copyOf(m));
            return new LlmResponse(replies.isEmpty() ? done("finished") : replies.poll(), 1, 1);
        }
        public boolean isAvailable() { return true; }
        public String name() { return "scripted"; }
        /** Everything the model was shown, across every call. */
        String allSeen() {
            var sb = new StringBuilder();
            for (var call : calls) for (var m : call) sb.append(m.content()).append('\n');
            return sb.toString();
        }
    }

    /** A tool that records its calls and answers from a function. */
    static final class FakeTool implements Tool {
        final String name; final boolean sideEffects; final List<String> creds;
        final Function<Map<String, Object>, ToolResult> body;
        final List<Map<String, Object>> calls = new ArrayList<>();
        FakeTool(String name, boolean sideEffects, List<String> creds,
                 Function<Map<String, Object>, ToolResult> body) {
            this.name = name; this.sideEffects = sideEffects; this.creds = creds; this.body = body;
        }
        public String name() { return name; }
        public String description() { return name; }
        public Map<String, ToolParam> inputSchema() { return Map.of(); }
        public boolean hasSideEffects() { return sideEffects; }
        public List<String> requiredCredentials() { return creds; }
        public ToolResult execute(Map<String, Object> p, ToolExecutionContext c) {
            calls.add(new LinkedHashMap<>(p));
            return body.apply(p);
        }
    }

    /** What each failed step recorded for the repair loop. */
    static final class Usage extends SkillCuratorService {
        final List<Map<String, Object>> failedArgs = new ArrayList<>();
        Usage() { super(null, null, null, null); }
        @Override
        public void recordUsage(String tool, String user, String task, boolean ok, long ms,
                                Map<String, Object> params, String error, Label label) {
            if (!ok) failedArgs.add(params == null ? null : new LinkedHashMap<>(params));
        }
    }

    static String call(String tool, Map<String, Object> params) {
        try {
            return JSON.writeValueAsString(Map.of("tool", tool, "params", params));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    static String done(String summary) {
        try {
            return JSON.writeValueAsString(Map.of("done", true, "summary", summary));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    static LocalExecutor executor(Scripted llm, Usage usage, Tool... tools) {
        return new LocalExecutor(new LlmRouter(llm, null, null, null),
                new ToolRegistry(List.of(tools)), new ChatStatusEmitter(), usage);
    }

    static DelegationPlan plan(String goal) {
        return new DelegationPlan(goal, List.of(), List.of(), 6);
    }

    static AgentContext task() {
        var ctx = new AgentContext("u1", "t1", "the morning menu");
        ctx.setUnattended(true);
        return ctx;
    }

    static final String MENU = "{\"ok\":true,\"body_text\":\"Polévka: česneková. Hlavní: guláš.\"}";
    static final String TRACEBACK = "Traceback: AttributeError: 'NoneType' object has no attribute 'text'";

    @Test
    @DisplayName("a second delegation's {{1}} is its own first step, not the first delegation's")
    void everyDelegationCountsFromOne() {
        // Round 5's blocking finding, reproduced as it happened: the first delegation's fetch
        // failed, the second's succeeded, and the second forwarded {{1}}. With task-wide numbering
        // that was the first delegation's traceback, and it became the morning email.
        int[] attempts = {0};
        var fetch = new FakeTool("daily_menu_fetcher", false, List.of(),
                p -> ++attempts[0] == 1 ? ToolResult.failure(TRACEBACK) : ToolResult.success(MENU));
        var smtp = new FakeTool("smtp_send_email", true, List.of(), p -> ToolResult.success("Sent"));
        var ctx = task();

        var first = new Scripted(call("daily_menu_fetcher", Map.of()), done("fetch failed"));
        executor(first, new Usage(), fetch, smtp).execute(plan("fetch today's menu"), ctx);

        var second = new Scripted(call("daily_menu_fetcher", Map.of()),
                call("smtp_send_email", Map.of("to", "petr@example.com", "body", "{{1.body_text}}")),
                done("menu emailed"));
        executor(second, new Usage(), fetch, smtp).execute(plan("fetch the menu and email it"), ctx);

        assertEquals(1, smtp.calls.size());
        assertEquals("Polévka: česneková. Hlavní: guláš.", smtp.calls.get(0).get("body"),
                "the owner gets the menu, not the first delegation's traceback");
        assertEquals(3, ctx.artifacts().size(), "every result is still recorded on the task");
    }

    @Test
    @DisplayName("a failed result is refused as an argument — its output is an error message")
    void aFailedResultIsNeverForwarded() {
        var fetch = new FakeTool("daily_menu_fetcher", false, List.of(), p -> ToolResult.failure(TRACEBACK));
        var smtp = new FakeTool("smtp_send_email", true, List.of(), p -> ToolResult.success("Sent"));
        var llm = new Scripted(call("daily_menu_fetcher", Map.of()),
                call("smtp_send_email", Map.of("to", "petr@example.com", "body", "{{1}}")),
                done("gave up"));

        executor(llm, new Usage(), fetch, smtp).execute(plan("email the menu"), task());

        assertTrue(smtp.calls.isEmpty(), "a traceback never goes out as the email");
        assertTrue(llm.allSeen().contains("FAILED"), "and the model is told why");
    }

    @Test
    @DisplayName("a change made in an earlier delegation is refused — and its output is not shown")
    void sideEffectsAreNotRepeatedAcrossDelegations() {
        var smtp = new FakeTool("smtp_send_email", true, List.of(),
                p -> ToolResult.success("Sent, message id SECRET-MID-771"));
        var args = Map.<String, Object>of("to", "petr@example.com", "body", "Dnešní menu");
        var ctx = task();

        executor(new Scripted(call("smtp_send_email", args), done("sent")), new Usage(), smtp)
                .execute(plan("email the menu"), ctx);
        var second = new Scripted(call("smtp_send_email", args), done("sent"));
        executor(second, new Usage(), smtp).execute(plan("email the menu"), ctx);

        assertEquals(1, smtp.calls.size(), "the owner gets one email");
        assertFalse(second.allSeen().contains("SECRET-MID-771"),
                "showing an earlier delegation's output is how a private result reached the local "
                        + "model and then, paraphrased, the cloud");
        assertTrue(second.allSeen().contains("already succeeded"));
    }

    @Test
    @DisplayName("after a private step a public tool's result stays PUBLIC; the model's summary is withheld")
    void privateMarkingFollowsWhatTheModelWrites() {
        var imap = new FakeTool("imap_fetch", false, List.of("IMAP_PASS"),
                p -> ToolResult.success("{\"body_text\":\"guest wifi password Kolibri-2291\"}"));
        var page = new FakeTool("web_fetch", false, List.of(),
                p -> ToolResult.success("Restaurant U Fleků — today's menu page"));
        var llm = new Scripted(call("imap_fetch", Map.of()),
                call("web_fetch", Map.of("url", "https://ufleku.cz/menu")),
                done("The mail says the wifi password is Kolibri-2291; the menu page is fetched."));

        var ctx = task();
        var outcome = executor(llm, new Usage(), imap, page).execute(plan("check mail and the menu"), ctx);

        assertEquals(Label.PRIVATE, ctx.artifacts().get(0).label());
        assertEquals(Label.PUBLIC, ctx.artifacts().get(1).label(),
                "marking it private made the cloud's own later fetch of the same page trip the "
                        + "canary, and a run whose email had gone reported 'did not finish'");
        assertTrue(outcome.text().contains("Restaurant U Fleků"), "the public result reaches the cloud");
        assertFalse(outcome.text().contains("Kolibri-2291"),
                "the model's own words, written after reading the mail, do not");
    }

    @Test
    @DisplayName("arguments typed after reading private content are withheld from the cloud and the repair log")
    void argumentsWrittenAfterPrivateAreWithheld() {
        var imap = new FakeTool("imap_fetch", false, List.of("IMAP_PASS"),
                p -> ToolResult.success("{\"body_text\":\"card PIN 4711\"}"));
        var search = new FakeTool("web_search", false, List.of(), p -> ToolResult.failure("rate limited"));
        var usage = new Usage();
        var llm = new Scripted(call("imap_fetch", Map.of()),
                call("web_search", Map.of("q", "reset card PIN 4711")), done("done"));

        var outcome = executor(llm, usage, imap, search).execute(plan("sort the mail"), task());

        assertFalse(outcome.text().contains("4711"), outcome.text());
        assertEquals(1, usage.failedArgs.size());
        assertNull(usage.failedArgs.get(0),
                "a PUBLIC usage row goes into the cloud's repair prompt; what the model typed after "
                        + "reading the mail must not be in it");
    }

    @Test
    @DisplayName("before anything private, a failed step's arguments are kept as repair evidence")
    void argumentsBeforeAnythingPrivateAreKept() {
        var search = new FakeTool("web_search", false, List.of(), p -> ToolResult.failure("rate limited"));
        var usage = new Usage();
        var llm = new Scripted(call("web_search", Map.of("q", "prague weather")), done("done"));

        var outcome = executor(llm, usage, search).execute(plan("weather"), task());

        assertTrue(outcome.text().contains("prague weather"), outcome.text());
        assertEquals(Map.of("q", "prague weather"), usage.failedArgs.get(0));
    }

    @Test
    @DisplayName("a goal that names an earlier result has the reference removed, and the cloud is told")
    void aGoalCannotReachEarlierResults() {
        var llm = new Scripted(done("nothing to do"));
        var outcome = executor(llm, new Usage()).execute(plan("Email {{3.body_text}} to Petr"), task());

        assertFalse(llm.allSeen().contains("{{3"),
                "the local model would read {{3}} as its own third step");
        assertTrue(outcome.text().startsWith("NOTE:"), outcome.text());
    }

    @Test
    @DisplayName("every result arrives with its handle, short ones included")
    void theModelIsToldEachHandle() {
        var ping = new FakeTool("ping", false, List.of(), p -> ToolResult.success("pong"));
        var llm = new Scripted(call("ping", Map.of()), call("ping", Map.of("n", 2)), done("ok"));

        executor(llm, new Usage(), ping).execute(plan("ping twice"), task());

        String seen = llm.allSeen();
        assertTrue(seen.contains("Tool result {{1}} [ping]"), "a short result used to arrive unnamed");
        assertTrue(seen.contains("Tool result {{2}} [ping]"));
    }
}
