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
import com.ownclaw.llm.ToolCall;
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
        /** The file ids each call was handed, as a skill receives them. */
        final List<List<String>> handed = new ArrayList<>();
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
            handed.add(c == null ? List.of() : c.attachmentIds());
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

    static LocalExecutor executor(LlmProvider llm, Usage usage, Tool... tools) {
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
        assertNull(usage.failedArgs.get(0),
                "and the arguments are not stored at all -- the label is the only other defence");
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
    @DisplayName("an ok:false send is a FAILED step: the model is told so and the delegation fails")
    void anOkFalseSendFailsTheDelegation() {
        // Round 7: the ledger said FAILED but the verdict said ok, so the fallback that hands the
        // job on never opened -- the morning email depended on the cloud happening to notice.
        var smtp = new FakeTool("smtp_send_email", true, List.of("SMTP_PASS"),
                p -> ToolResult.success("{\"ok\": false, \"error\": \"SMTP connection error\"}"));
        var usage = new Usage();
        var llm = new Scripted(call("smtp_send_email", Map.of("to", "petr@example.com", "body", "x")),
                done("sent"));

        var outcome = executor(llm, usage, smtp).execute(plan("email it"), task());

        assertFalse(outcome.ok(), "a failed delegation is what opens the fallback");
        assertTrue(llm.allSeen().contains("[smtp_send_email] FAILED"), "the model is told the truth");
        assertEquals(1, usage.failedLabels.size(), "and the repair log records a failure");
    }

    @Test
    @DisplayName("a change is attempted once per delegation, even when it failed")
    void aFailedChangeIsNotRetriedInTheSameDelegation() {
        // An SMTP timeout can arrive after the server has accepted the message. Retrying a
        // "failed" send in a loop delivered a copy each time; main allowed one per delegation.
        var smtp = new FakeTool("smtp_send_email", true, List.of("SMTP_PASS"),
                p -> ToolResult.success("{\"ok\": false, \"error\": \"timed out\"}"));
        var args = Map.<String, Object>of("to", "petr@example.com", "body", "menu");
        var llm = new Scripted(call("smtp_send_email", args), call("smtp_send_email", args),
                call("smtp_send_email", args), done("gave up"));

        executor(llm, new Usage(), smtp).execute(plan("email it"), task());

        assertEquals(1, smtp.calls.size());
        assertTrue(llm.allSeen().contains("attempted once per delegation"));
    }

    @Test
    @DisplayName("what the local tier read in one delegation hides what the next one reads back")
    void privateDataCarriesAcrossDelegations() {
        // Round 7's probe: delegation A read the mail and wrote the PIN into a file; delegation B
        // read the file, which was PUBLIC, and its summary gave the PIN to the cloud.
        var imap = new FakeTool("imap_fetch", false, List.of("IMAP_PASS"),
                p -> ToolResult.success("{\"body_text\":\"card PIN 4711\"}"));
        var write = new FakeTool("write_file", true, List.of(), p -> ToolResult.success("written"));
        var read = new FakeTool("read_file", false, List.of(), p -> ToolResult.success("card PIN 4711"));
        var ctx = task();
        executor(new Scripted(call("imap_fetch", Map.of()),
                        call("write_file", Map.of("path", "/tmp/n", "content", "card PIN 4711")), done("ok")),
                new Usage(), imap, write).execute(plan("note the PIN"), ctx);

        var outcome = executor(new Scripted(call("read_file", Map.of("path", "/tmp/n")),
                done("The note says card PIN 4711")), new Usage(), read).execute(plan("read the note"), ctx);

        assertEquals(Label.PRIVATE, ctx.artifacts().get(2).label());
        assertFalse(outcome.text().contains("4711"), outcome.text());
    }

    @Test
    @DisplayName("a result derived from a hidden one stays unindexed, and its descriptor names no fields")
    void hiddenStaysHiddenOneHopOn() {
        String page = "{\"title\":\"Restaurant U Fleků — today's menu, soup and goulash\"}";
        var imap = new FakeTool("imap_fetch", false, List.of("IMAP_PASS"),
                p -> ToolResult.success("{\"body_text\":\"lunch at U Fleků?\"}"));
        var fetch = new FakeTool("web_fetch", false, List.of(), p -> ToolResult.success(page));
        var ctx = task();
        executor(new Scripted(call("imap_fetch", Map.of()), call("web_fetch", Map.of("url", "u")),
                done("ok")), new Usage(), imap, fetch).execute(plan("mail then menu"), ctx);
        // The cloud forwards the hidden page into a public tool of its own.
        var fetched = ctx.artifacts().get(1);
        var d = ctx.decide(List.of(), List.of(fetched), false);

        assertFalse(fetched.indexed());
        assertFalse(d.indexed(), "one hop on it is still the same public page");
        assertNull(ctx.privateIndex().firstHitIn(page));
        assertFalse(fetched.describe().contains("title"),
                "a key name can be what the model typed: " + fetched.describe());
    }

    @Test
    @DisplayName("a refused reference is answered with what the local model can reference")
    void theLocalModelIsToldWhatExists() {
        var ping = new FakeTool("ping", false, List.of(), p -> ToolResult.success("{\"reply\":\"pong\"}"));
        var smtp = new FakeTool("smtp_send_email", true, List.of(), p -> ToolResult.success("Sent"));
        var llm = new Scripted(call("ping", Map.of()),
                call("smtp_send_email", Map.of("body", "{{5.reply}}")), done("ok"));

        executor(llm, new Usage(), ping, smtp).execute(plan("ping and send"), task());

        assertTrue(llm.allSeen().contains("Results you can reference: {{1}} = ping (ok; fields: reply)"),
                llm.allSeen());
        assertTrue(smtp.calls.isEmpty());
    }

    @Test
    @DisplayName("a large result is shown whole, and forwarded exactly by its reference")
    void aLargeResultIsShownWholeAndForwarded() {
        // It used to be shown as 400 characters, a marker and 150 more, so the model forwarded
        // what it had never read -- and one that copied what it was shown sent half a digest.
        String text = "Polévka dne. ".repeat(200);
        String menu = "{\"ok\":true,\"body_text\":\"" + text + "\"}";
        var fetch = new FakeTool("daily_menu_fetcher", false, List.of(), p -> ToolResult.success(menu));
        var smtp = new FakeTool("smtp_send_email", true, List.of(), p -> ToolResult.success("Sent"));
        var llm = new Scripted(call("daily_menu_fetcher", Map.of()),
                call("smtp_send_email", Map.of("to", "petr@example.com", "body", "{{1.body_text}}")),
                done("sent"));

        executor(llm, new Usage(), fetch, smtp).execute(plan("email the menu"), task());

        assertTrue(llm.allSeen().contains("Tool result {{1}} [daily_menu_fetcher] SUCCESS:\n" + menu),
                "the model reads the whole of what it is forwarding");
        assertEquals(text, smtp.calls.get(0).get("body"));
    }

    /** A local model that takes tools natively and answers each turn with the reply given. */
    static final class Replies implements LlmProvider {
        final Deque<LlmResponse> replies = new ArrayDeque<>();
        final List<List<LlmMessage>> calls = new ArrayList<>();
        Replies(LlmResponse... r) { replies.addAll(List.of(r)); }
        public boolean supportsTools() { return true; }
        public boolean isAvailable() { return true; }
        public String name() { return "replies"; }
        public LlmResponse chat(List<LlmMessage> m, LlmRequestConfig c) {
            calls.add(List.copyOf(m));
            return replies.isEmpty() ? turn(new ToolCall("end", "done", Map.of("summary", "finished")))
                    : replies.poll();
        }
        /** What the model was told after its turn {@code n} (0-based). */
        String toldAfter(int n) {
            var m = calls.get(n + 1);
            return m.get(m.size() - 1).content();
        }
    }

    /** One turn of the model: these tool calls, in this order. */
    static LlmResponse turn(ToolCall... calls) {
        return new LlmResponse("", 1, 1, 0, 0, "stop", List.of(calls));
    }

    @Test
    @DisplayName("every tool call of a turn runs, in its order, through the same guards")
    void everyCallOfATurnRuns() {
        // Only the first call of a turn used to run. The others were neither run nor mentioned,
        // and the transcript showed only the first, so the model never learned.
        var fetch = new FakeTool("daily_menu_fetcher", false, List.of(), p -> ToolResult.success(MENU));
        var smtp = new FakeTool("smtp_send_email", true, List.of(), p -> ToolResult.success("Sent"));
        var send = Map.<String, Object>of("to", "petr@example.com", "body", "{{1.body_text}}");
        var llm = new Replies(
                turn(new ToolCall("a", "daily_menu_fetcher", Map.of()),
                        new ToolCall("b", "smtp_send_email", send),
                        new ToolCall("c", "smtp_send_email", send)),
                turn(new ToolCall("d", "done", Map.of("summary", "menu emailed"))));

        var outcome = executor(llm, new Usage(), fetch, smtp).execute(plan("email the menu"), task());

        assertEquals(1, fetch.calls.size());
        assertEquals(1, smtp.calls.size(), "the same send twice in one turn is still twice");
        assertEquals("Polévka: česneková. Hlavní: guláš.", smtp.calls.get(0).get("body"),
                "a call can use a result from earlier in the same turn");
        String told = llm.toldAfter(0);
        assertTrue(told.contains("[call 1 of 3: daily_menu_fetcher] Tool result {{1}}"), told);
        assertTrue(told.contains("[call 2 of 3: smtp_send_email] Tool result {{2}}"), told);
        assertTrue(told.contains("[call 3 of 3: smtp_send_email] Not run: you already made exactly"), told);
        String replayed = llm.calls.get(1).get(llm.calls.get(1).size() - 2).content();
        assertEquals(3, replayed.lines().count(), "the model's turn is replayed with all its calls: " + replayed);
        assertTrue(outcome.ok(), outcome.text());
    }

    @Test
    @DisplayName("done beside other calls waits for their results: the model finishes having seen them")
    void doneBesideOtherCallsIsNotTaken() {
        var fetch = new FakeTool("daily_menu_fetcher", false, List.of(), p -> ToolResult.success(MENU));
        var llm = new Replies(
                turn(new ToolCall("a", "daily_menu_fetcher", Map.of()),
                        new ToolCall("b", "done", Map.of("summary", "fetched, probably"))),
                turn(new ToolCall("c", "done", Map.of("summary", "Fetched: soup and goulash."))));

        var outcome = executor(llm, new Usage(), fetch).execute(plan("fetch the menu"), task());

        assertEquals(1, fetch.calls.size(), "the call beside it ran");
        assertTrue(llm.toldAfter(0).contains("[call 2 of 2: done] Not taken"), llm.toldAfter(0));
        assertTrue(outcome.text().startsWith("Fetched: soup and goulash."),
                "the summary written after the result, not before it: " + outcome.text());
    }

    @Test
    @DisplayName("done first in a turn waits too: the calls after it run")
    void doneFirstInATurnIsNotTaken() {
        var fetch = new FakeTool("daily_menu_fetcher", false, List.of(), p -> ToolResult.success(MENU));
        var smtp = new FakeTool("smtp_send_email", true, List.of(), p -> ToolResult.success("Sent"));
        var llm = new Replies(
                turn(new ToolCall("a", "daily_menu_fetcher", Map.of())),
                turn(new ToolCall("b", "done", Map.of("summary", "menu emailed")),
                        new ToolCall("c", "smtp_send_email",
                                Map.of("to", "petr@example.com", "body", "{{1.body_text}}"))),
                turn(new ToolCall("d", "done", Map.of("summary", "Menu emailed to Petr."))));

        var outcome = executor(llm, new Usage(), fetch, smtp).execute(plan("email the menu"), task());

        assertEquals(1, smtp.calls.size(), "taking the done would have dropped the send unrun and unsaid");
        assertTrue(llm.toldAfter(1).contains("[call 1 of 2: done] Not taken"), llm.toldAfter(1));
        assertTrue(outcome.text().startsWith("Menu emailed to Petr."), outcome.text());
    }

    @Test
    @DisplayName("Stop pressed during a call of a turn: the calls after it do not run")
    void stopMidTurnRunsNothingMore() {
        var ctx = task();
        var scan = new FakeTool("net_scan", false, List.of(), p -> {
            ctx.cancel();                      // the owner presses Stop while the scan runs
            return ToolResult.success("3 hosts up");
        });
        var smtp = new FakeTool("smtp_send_email", true, List.of(), p -> ToolResult.success("Sent"));
        var llm = new Replies(turn(new ToolCall("a", "net_scan", Map.of()),
                new ToolCall("b", "smtp_send_email", Map.of("to", "petr@example.com", "body", "{{1}}"))));

        var outcome = executor(llm, new Usage(), scan, smtp).execute(plan("scan and email"), ctx);

        assertEquals(1, scan.calls.size());
        assertTrue(smtp.calls.isEmpty(), "the send ran after Stop: " + outcome.text());
        assertTrue(outcome.text().startsWith("Delegation incomplete: Task cancelled during delegation."),
                outcome.text());
        assertTrue(outcome.text().contains("{{1}} [net_scan] OK: 3 hosts up"), "what ran is kept");
    }

    @Test
    @DisplayName("a finished call is progress: the calls of a turn are not one long silence")
    void aFinishedCallIsProgress() {
        var ctx = task();
        long[] quietAtSecond = {-1};
        var first = new FakeTool("scan_a", false, List.of(), p -> {
            try { Thread.sleep(300); } catch (InterruptedException e) { throw new IllegalStateException(e); }
            return ToolResult.success("subnet A: 3 hosts up");
        });
        var second = new FakeTool("scan_b", false, List.of(), p -> {
            quietAtSecond[0] = ctx.msSinceLastProgress();
            return ToolResult.success("subnet B: 2 hosts up");
        });
        var llm = new Replies(turn(new ToolCall("a", "scan_a", Map.of()), new ToolCall("b", "scan_b", Map.of())));

        executor(llm, new Usage(), first, second).execute(plan("scan both subnets"), ctx);

        assertTrue(quietAtSecond[0] >= 0 && quietAtSecond[0] < 150, "the watchdog counted the first "
                + "call's 300 ms as silence: " + quietAtSecond[0] + " ms");
    }

    @Test
    @DisplayName("a skill's progress report reaches the owner and keeps the task alive")
    void aSkillsProgressIsShownAndCounted() {
        var ctx = task();
        var emitter = new ChatStatusEmitter();
        var lines = new ArrayList<String>();
        emitter.subscribe("u1", "test", m -> lines.add(m.text()));
        long[] quiet = {-1, -1};
        var scan = new Tool() {
            public String name() { return "net_scan"; }
            public String description() { return "scans"; }
            public Map<String, ToolParam> inputSchema() { return Map.of(); }
            public ToolResult execute(Map<String, Object> p, ToolExecutionContext c) {
                try { Thread.sleep(30); } catch (InterruptedException e) { throw new IllegalStateException(e); }
                quiet[0] = ctx.msSinceLastProgress();
                c.progressCallback().onProgress("Scanning 1/3", 33);
                quiet[1] = ctx.msSinceLastProgress();
                return ToolResult.success("3 hosts up");
            }
        };
        var llm = new Scripted(call("net_scan", Map.of()), done("scanned"));

        new LocalExecutor(new LlmRouter(llm, null, null, null), new ToolRegistry(List.of(scan)), emitter,
                new Usage()).execute(plan("scan"), ctx);

        assertTrue(lines.contains("Delegate: net_scan: Scanning 1/3 (33%)"), lines.toString());
        assertTrue(quiet[0] >= 30 && quiet[1] < quiet[0], quiet[0] + " then " + quiet[1] + " ms");
    }

    @Test
    @DisplayName("a result typed out again is refused; text the model composes itself goes out")
    void retypedIsRefusedComposedIsSent() {
        // A 600-character rule refused both: the local tier could not write an email at all.
        String digest = DelegationSafetyTest.digest();
        String composed = DelegationSafetyTest.composed(3_000);
        var news = new FakeTool("daily_news_digest", false, List.of(), p -> ToolResult.success(digest));
        var smtp = new FakeTool("smtp_send_email", true, List.of(), p -> ToolResult.success("Sent"));
        var llm = new Scripted(call("daily_news_digest", Map.of()),
                call("smtp_send_email", Map.of("to", "petr@example.com", "body", digest.substring(0, 900))),
                call("smtp_send_email", Map.of("to", "petr@example.com", "body", composed)),
                done("sent a note"));

        executor(llm, new Usage(), news, smtp).execute(plan("email a note about the news"), task());

        assertEquals(1, smtp.calls.size());
        assertEquals(composed, smtp.calls.get(0).get("body"));
        assertTrue(llm.allSeen().contains("repeats part of an earlier result"), llm.allSeen());
    }

    @Test
    @DisplayName("every turn carries the whole conversation: nothing older is dropped")
    void theWholeConversationIsSent() {
        var ping = new FakeTool("ping", false, List.of(), p -> ToolResult.success("pong " + p.get("n")));
        var script = new ArrayList<String>();
        for (int i = 1; i <= 7; i++) script.add(call("ping", Map.of("n", i)));
        script.add(done("pinged seven times"));
        var llm = new Scripted(script.toArray(String[]::new));

        executor(llm, new Usage(), ping).execute(
                new DelegationPlan("ping seven times", List.of(), List.of(), 10), task());

        List<LlmMessage> last = llm.calls.get(llm.calls.size() - 1);
        assertEquals(2 + 2 * 7, last.size(), "system, opening, and every call with its result");
        assertTrue(last.get(3).content().contains("pong 1"), "the first result, at the last turn");
    }

    @Test
    @DisplayName("a delegation that does not finish still hands every result on whole")
    void partialResultsAreWhole() {
        String big = DelegationSafetyTest.digest().repeat(15) + "END-OF-RESULT";
        var news = new FakeTool("daily_news_digest", false, List.of(), p -> ToolResult.success(big));
        var llm = new Scripted(call("daily_news_digest", Map.of()));

        var outcome = executor(llm, new Usage(), news).execute(
                new DelegationPlan("the digest", List.of(), List.of(), 1), task());

        assertFalse(outcome.ok());
        assertTrue(outcome.text().startsWith("Delegation incomplete: Delegation reached max steps (1)"));
        assertTrue(outcome.text().contains(big), "2,000 characters of it used to be all the cloud got");
    }

    @Test
    @DisplayName("an empty reply is said to be empty, and the work goes on")
    void anEmptyReplyIsSaidPlainly() {
        // A thinking model can spend its turn reasoning and stop with nothing written.
        var ping = new FakeTool("ping", false, List.of(), p -> ToolResult.success("pong"));
        var llm = new Replies(new LlmResponse("", 40, 5_947, 0, 0, "stop", List.of()),
                turn(new ToolCall("a", "ping", Map.of())),
                turn(new ToolCall("b", "done", Map.of("summary", "pinged"))));

        var outcome = executor(llm, new Usage(), ping).execute(plan("ping"), task());

        assertEquals(1, ping.calls.size(), "the delegation did not end on it");
        assertTrue(outcome.ok(), outcome.text());
        var second = llm.calls.get(1);
        assertEquals("", second.get(second.size() - 2).content(),
                "its turn is in the transcript as the empty turn it was, not an invented sentence");
        assertEquals("Your previous reply was empty (stop reason: stop): no text and no tool call, "
                + "so nothing was run. Continue from where the task stands.", llm.toldAfter(0));
    }

    @Test
    @DisplayName("every event of a local reply is progress, and Stop ends the call at the next one")
    void theProgressHookKeepsTheTaskAliveAndStops() throws Exception {
        var ctx = task();
        long[] quiet = new long[2];
        boolean[] carriedOn = {false};
        LlmProvider llm = new LlmProvider() {
            int n;
            public LlmResponse chat(List<LlmMessage> m, LlmRequestConfig c) {
                if (n++ == 0) {
                    try { Thread.sleep(30); } catch (InterruptedException e) { throw new IllegalStateException(e); }
                    quiet[0] = ctx.msSinceLastProgress();
                    c.progress().onProgress();          // an event of the stream
                    quiet[1] = ctx.msSinceLastProgress();
                    return new LlmResponse(call("ping", Map.of()), 1, 1);
                }
                ctx.cancel();                           // Stop, while the model is writing
                c.progress().onProgress();
                carriedOn[0] = true;
                return new LlmResponse(done("pinged"), 1, 1);
            }
            public boolean isAvailable() { return true; }
            public String name() { return "streaming"; }
        };
        var ping = new FakeTool("ping", false, List.of(), p -> ToolResult.success("pong"));

        var outcome = executor(llm, new Usage(), ping).execute(plan("ping"), ctx);

        assertTrue(quiet[0] >= 30 && quiet[1] < quiet[0],
                "a long generation is not a stall: " + quiet[0] + " then " + quiet[1] + " ms");
        assertFalse(carriedOn[0], "the call ended at the event after Stop");
        assertTrue(outcome.text().startsWith("Delegation incomplete: Task cancelled during delegation."),
                outcome.text());
        assertEquals(1, ping.calls.size(), "and what ran before it is kept");
    }

    @Test
    @DisplayName("the owner's status line names the whole goal")
    void theStatusLineIsWhole() {
        var emitter = new ChatStatusEmitter();
        var lines = new ArrayList<String>();
        emitter.subscribe("u1", "test", m -> lines.add(m.text()));
        String goal = "Fetch today's lunch menus from the three restaurants on Vinohradská, "
                + "compare their soups, and email Petr the cheapest vegetarian main course. ".repeat(3);
        var llm = new Scripted(done("nothing to do"));

        new LocalExecutor(new LlmRouter(llm, null, null, null), new ToolRegistry(List.of()), emitter,
                new Usage()).execute(plan(goal), task());

        assertTrue(lines.contains("Delegating to local LLM: " + goal), lines.toString());
    }

    // ── a task holding the user's file ──

    static final List<String> PDF = List.of("uploaded file",
            "application/pdf, 84211 bytes, no text read (not text, over 100 KB, or not UTF-8)");

    /** A statement-shaped text: every line differs, so a window of it names one place in it. */
    static String statement(int length) {
        var sb = new StringBuilder();
        for (int i = 1; sb.length() < length; i++) {
            sb.append("2026-09-").append(String.format("%02d", i % 28 + 1)).append(" card ")
              .append(1000 + i * 37).append('.').append(String.format("%02d", i % 100))
              .append(" CZK ref ").append(70_000 + i * 13).append('\n');
        }
        return sb.substring(0, length);
    }

    static final String STATEMENT = statement(3_000);
    static final String SUMMARY = "Closing balance 48,213.07 CZK on 30 September; the largest "
            + "debit was the 12,500.00 CZK rent on the 1st.";

    /** A 12-character run of {@code secret} that appears in {@code text}, or null. */
    static String windowOf(String secret, String text) {
        for (int i = 0; i + 12 <= secret.length(); i++) {
            if (text.contains(secret.substring(i, i + 12))) return secret.substring(i, i + 12);
        }
        return null;
    }

    @Test
    @DisplayName("on a file task the local model's answer is kept as a private handle, not dropped")
    void aFileTaskAnswersThroughAHandle() {
        // Attended chat with a PDF: what the skill reads is withheld from the cloud, so the only
        // answer there can be is the one the local model writes. It used to be withheld and lost.
        var ctx = new AgentContext("u1", "t1", "summarise this statement");
        ctx.addFile("f1", "", PDF);
        var read = new FakeTool("read_statement", false, List.of(), p -> ToolResult.success(STATEMENT));
        var llm = new Scripted(call("read_statement", Map.of()), done(SUMMARY));

        var outcome = executor(llm, new Usage(), read).execute(plan("summarise the file"), ctx);

        assertEquals(List.of(List.of("f1")), read.handed, "the skill is handed the file");
        assertTrue(llm.allSeen().contains(STATEMENT),
                "the local model answers from the whole of it, not a 400-character excerpt");
        Artifact fromFile = ctx.artifacts().get(1);
        assertEquals("{{2}}", fromFile.handle());
        assertEquals(Label.PRIVATE, fromFile.label());
        assertFalse(fromFile.indexed());
        Artifact answer = ctx.artifacts().get(2);
        assertEquals("local_answer", answer.tool());
        assertEquals(Label.PRIVATE, answer.label());
        assertTrue(answer.indexed());
        assertNotNull(ctx.privateIndex().firstHitIn(SUMMARY),
                "the canary looks for the answer in every later request of the task");
        assertTrue(answer.output().startsWith(SUMMARY), answer.output());
        assertTrue(outcome.produced().contains(answer), "the step and the task page show it");
        assertTrue(outcome.text().contains("{{3}}"), "the cloud is told the handle: " + outcome.text());
        assertNull(windowOf(STATEMENT, outcome.text()), outcome.text());
        assertNull(windowOf(SUMMARY, outcome.text()), outcome.text());
    }

    /** {@code length} characters with {@code marker} at {@code at}, and nothing else to find. */
    static String withMarkerAt(int length, int at, String marker) {
        String filler = "x".repeat(length);
        return filler.substring(0, at) + marker + filler.substring(at + marker.length());
    }

    @Test
    @DisplayName("every result is shown to the local model whole, on a file task and off it")
    void resultsAreShownWhole() {
        String first = withMarkerAt(10_000, 9_000, "FIRST-MARKER-7731");
        String second = withMarkerAt(10_000, 9_000, "SECOND-MARKER-4410");
        var read = new FakeTool("read_statement", false, List.of(),
                p -> ToolResult.success(Integer.valueOf(1).equals(p.get("page")) ? first : second));
        var ctx = new AgentContext("u1", "t1", "summarise this statement");
        ctx.addFile("f1", "", PDF);
        var llm = new Scripted(call("read_statement", Map.of("page", 1)),
                call("read_statement", Map.of("page", 2)), done(SUMMARY));

        executor(llm, new Usage(), read).execute(plan("summarise the file"), ctx);

        // A file task's reader once had 16,000 characters for the whole delegation, and the
        // answer then covered the first pages only.
        assertTrue(llm.allSeen().contains(first), "the first page, whole");
        assertTrue(llm.allSeen().contains(second), "and the second: no allowance runs out");
        Artifact answer = ctx.artifacts().get(3);
        assertEquals("local_answer", answer.tool());
        assertEquals(SUMMARY, answer.output(), "the model's answer, and nothing cut to note in it");

        // The same text, from a credentialed tool in a task with no file: shown whole too, and
        // the summary withheld as before, with nothing kept.
        var imap = new FakeTool("imap_fetch", false, List.of("IMAP_PASS"), p -> ToolResult.success(first));
        var plain = new AgentContext("u1", "t2", "what came in the mail?");
        var llm2 = new Scripted(call("imap_fetch", Map.of()), done(SUMMARY));

        var outcome = executor(llm2, new Usage(), imap).execute(plan("read the mail"), plain);

        assertTrue(llm2.allSeen().contains(first), "whole, where it was 400 characters and 150 more");
        assertTrue(plain.artifacts().stream().noneMatch(a -> "local_answer".equals(a.tool())));
        assertTrue(outcome.text().contains("(local summary withheld — this delegation touched {{1}})"),
                outcome.text());
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
