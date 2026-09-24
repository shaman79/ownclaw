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

    /** What each failed step recorded for the repair loop, and under which label. */
    static final class Usage extends SkillCuratorService {
        final List<Map<String, Object>> failedArgs = new ArrayList<>();
        final List<Label> failedLabels = new ArrayList<>();
        Usage() { super(null, null, null, null); }
        @Override
        public void recordUsage(String tool, String user, String task, boolean ok, long ms,
                                Map<String, Object> params, String error, Label label) {
            if (ok) return;
            failedArgs.add(params == null ? null : new LinkedHashMap<>(params));
            failedLabels.add(label);
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
    @DisplayName("after private data, a delegation shows the cloud nothing more — without indexing it")
    void afterPrivateDataNothingMoreIsShown() {
        // The owner's choice after round 6. The local model has read the mail; a public page it
        // fetches afterwards is withheld from the cloud like any private result. But it is not
        // put into the canary's index: that is what made the cloud's own later fetch of the same
        // public page trip the canary and a run whose email had gone report "did not finish".
        String pageText = "Restaurant U Fleků — today's menu page, soup of the day and the goulash";
        var imap = new FakeTool("imap_fetch", false, List.of("IMAP_PASS"),
                p -> ToolResult.success("{\"body_text\":\"guest wifi password Kolibri-2291\"}"));
        var page = new FakeTool("web_fetch", false, List.of(), p -> ToolResult.success(pageText));
        var llm = new Scripted(call("imap_fetch", Map.of()),
                call("web_fetch", Map.of("url", "https://ufleku.cz/menu")),
                done("The mail says the wifi password is Kolibri-2291; the menu page is fetched."));

        var ctx = task();
        var outcome = executor(llm, new Usage(), imap, page).execute(plan("check mail and the menu"), ctx);

        Artifact fetched = ctx.artifacts().get(1);
        assertEquals(Label.PRIVATE, fetched.label());
        assertTrue(fetched.why().contains("after private data in this delegation"), fetched.why().toString());
        assertNull(ctx.privateIndex().firstHitIn(pageText),
                "not indexed, so the cloud's own fetch of the same page is not refused");
        assertFalse(outcome.text().contains("Restaurant U Fleků"), "withheld from the cloud");
        assertFalse(outcome.text().contains("Kolibri-2291"), "and so is the model's summary");
    }

    @Test
    @DisplayName("a public tool that echoes what the model typed after reading private data leaks nothing")
    void anEchoingToolCannotLaunderPrivateText() {
        // Round 6's leak: the model copies a PIN from the mail into a public tool, the tool echoes
        // its input, and a value that short is invisible to the canary's 32-character windows.
        var imap = new FakeTool("imap_fetch", false, List.of("IMAP_PASS"),
                p -> ToolResult.success("{\"body_text\":\"your card PIN is 4711, alarm code 5180\"}"));
        var echo = new FakeTool("web_search", false, List.of(),
                p -> ToolResult.success("No results for: " + p.get("q")));
        var llm = new Scripted(call("imap_fetch", Map.of()),
                call("web_search", Map.of("q", "reset card PIN 4711")), done("done"));

        var outcome = executor(llm, new Usage(), imap, echo).execute(plan("sort the mail"), task());

        assertFalse(outcome.text().contains("4711"), outcome.text());
    }

    @Test
    @DisplayName("a failed step after private data is kept out of the repair prompt")
    void failuresAfterPrivateAreKeptFromTheRepairPrompt() {
        var imap = new FakeTool("imap_fetch", false, List.of("IMAP_PASS"),
                p -> ToolResult.success("{\"body_text\":\"card PIN 4711\"}"));
        var search = new FakeTool("web_search", false, List.of(),
                p -> ToolResult.failure("rate limited for query " + p.get("q")));
        var usage = new Usage();
        var llm = new Scripted(call("imap_fetch", Map.of()),
                call("web_search", Map.of("q", "reset card PIN 4711")), done("done"));

        var outcome = executor(llm, usage, imap, search).execute(plan("sort the mail"), task());

        assertFalse(outcome.text().contains("4711"), outcome.text());
        assertEquals(List.of(Label.PRIVATE), usage.failedLabels,
                "the repair prompt reads only PUBLIC rows, and this row's arguments and error both "
                        + "carry what the model read");
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
    @DisplayName("pulling in a private result makes the step private, on the delegation path too")
    void aReferenceToPrivateMakesItPrivate() {
        var imap = new FakeTool("imap_fetch", false, List.of("IMAP_PASS"),
                p -> ToolResult.success("{\"body_text\":\"the mail\"}"));
        var archive = new FakeTool("archive_text", false, List.of(), p -> ToolResult.success("archived"));
        var ctx = task();
        // A fresh delegation, so taint is not what decides: the reference is.
        executor(new Scripted(call("imap_fetch", Map.of()), done("ok")), new Usage(), imap)
                .execute(plan("fetch"), ctx);
        var llm = new Scripted(call("imap_fetch", Map.of()),
                call("archive_text", Map.of("text", "{{1.body_text}}")), done("ok"));
        executor(llm, new Usage(), imap, archive).execute(plan("archive the mail"), ctx);

        assertEquals(Label.PRIVATE, ctx.artifacts().get(2).label());
        assertTrue(ctx.artifacts().get(2).why().contains("references {{2}}"),
                "named by its task handle: " + ctx.artifacts().get(2).why());
    }

    @Test
    @DisplayName("a send that reported ok:false is retried by the next delegation, not refused")
    void anOkFalseSendIsRetried() {
        // Round 6's blocking finding: the production smtp skill reports SMTP errors as success
        // with "ok": false inside, and the never-twice guard read that as a send that happened.
        int[] n = {0};
        var smtp = new FakeTool("smtp_send_email", true, List.of("SMTP_PASS"),
                p -> ToolResult.success(++n[0] == 1
                        ? "{\"ok\": false, \"error\": \"SMTP connection error: timed out\"}"
                        : "{\"ok\": true}"));
        var args = Map.<String, Object>of("to", "petr@example.com", "body", "Dnešní menu");
        var ctx = task();
        executor(new Scripted(call("smtp_send_email", args), done("sent")), new Usage(), smtp)
                .execute(plan("email the menu"), ctx);
        executor(new Scripted(call("smtp_send_email", args), done("sent")), new Usage(), smtp)
                .execute(plan("email the menu"), ctx);

        assertEquals(2, smtp.calls.size(), "the retry goes out, as it does on main");
    }

    @Test
    @DisplayName("the omission marker speaks the grammar the resolver reads")
    void theOmissionMarkerIsAReference() {
        // Round 6's other blocking finding: the marker still said "pass it on with $1", which is
        // no longer a reference, so a model that followed it emailed the owner the text "$1".
        String menu = "{\"ok\":true,\"body_text\":\"" + "Polévka dne. ".repeat(200) + "\"}";
        var fetch = new FakeTool("daily_menu_fetcher", false, List.of(), p -> ToolResult.success(menu));
        var smtp = new FakeTool("smtp_send_email", true, List.of(), p -> ToolResult.success("Sent"));
        var llm = new Scripted(call("daily_menu_fetcher", Map.of()),
                call("smtp_send_email", Map.of("to", "petr@example.com", "body", "{{1.body_text}}")),
                done("sent"));

        executor(llm, new Usage(), fetch, smtp).execute(plan("email the menu"), task());

        assertTrue(llm.allSeen().contains("pass it on with {{1}}⟧"), "the marker names {{1}}");
        assertFalse(llm.allSeen().contains("pass it on with $"), "and never the old $ form");
        assertTrue(String.valueOf(smtp.calls.get(0).get("body")).startsWith("Polévka dne."));
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
