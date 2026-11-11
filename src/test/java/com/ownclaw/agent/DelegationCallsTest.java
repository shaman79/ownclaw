package com.ownclaw.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownclaw.agent.tools.ToolResult;
import com.ownclaw.llm.LlmMessage;
import com.ownclaw.llm.LlmProvider;
import com.ownclaw.llm.LlmRequestConfig;
import com.ownclaw.llm.LlmResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.ownclaw.agent.DelegationBehaviourTest.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Exact calls a delegation is handed ('calls'): code runs them in the cloud's order before the
 * local model is given anything, so nothing of them is retyped, and a delegation of calls alone
 * never waits for the local model.
 * <p>
 * Most delegation goals of 5 to 10 November 2026 were such a list written in prose ("Step 1: call
 * daily_news_digest. Step 2: send its body_text with smtp_send_email ..."), which the local model
 * then typed out call by call at a few tokens a second: the daily news run did 22 seconds of work
 * in four minutes or more, and two copied JSON arguments came back broken.
 */
class DelegationCallsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    static DelegationPlan calls(String goal, DelegationPlan.Call... calls) {
        return new DelegationPlan(goal, List.of(), List.of(), List.of(), Map.of(), List.of(calls));
    }

    static DelegationPlan.Call c(String tool, Map<String, Object> params) {
        return new DelegationPlan.Call(tool, params);
    }

    /** A lane that counts how often the local model was taken. */
    static final class Lane implements java.util.function.Function<java.util.function.Supplier<LocalExecutor.Outcome>,
            LocalExecutor.Outcome> {
        int taken;
        public LocalExecutor.Outcome apply(java.util.function.Supplier<LocalExecutor.Outcome> work) {
            taken++;
            return work.get();
        }
    }

    // ── parsing ──

    @Test
    @DisplayName("calls are read as a list of tool and params, in their order, or as that list written as JSON")
    void callsAreRead() throws Exception {
        var second = new LinkedHashMap<String, Object>();
        second.put("to", "petr@example.com");
        second.put("body", "{{call1.body_text}}");
        var list = List.of(Map.of("tool", "daily_news_digest"),
                Map.of("tool", "smtp_send_email", "params", second));
        var plan = LocalExecutor.parsePlan(Map.of("calls", list));
        assertEquals(List.of(c("daily_news_digest", Map.of()), c("smtp_send_email", second)), plan.calls());
        assertEquals("", plan.goal(), "no goal is needed with calls");
        assertEquals(plan.calls(), LocalExecutor.parsePlan(Map.of("calls", JSON.writeValueAsString(list))).calls(),
                "a JSON list sent as a string");
        assertEquals(List.of(c("ha_api", Map.of("mode", "ws"))), LocalExecutor.parsePlan(Map.of("calls",
                List.of(Map.of("tool", " ha_api ", "params", "{\"mode\": \"ws\"}")))).calls(),
                "params written as a JSON object string, and the name's blank edges");
        assertEquals(List.of(), LocalExecutor.parsePlan(Map.of("goal", "g")).calls());
    }

    @Test
    @DisplayName("a call in any other shape is refused, never guessed at")
    void badCallsAreRefused() {
        for (Object calls : List.of("not json", 7, Map.of("tool", "x"))) {
            var e = assertThrows(IllegalArgumentException.class,
                    () -> LocalExecutor.parsePlan(Map.of("calls", calls)), String.valueOf(calls));
            assertTrue(e.getMessage().contains("'calls' must be a list"), e.getMessage());
        }
        var noTool = assertThrows(IllegalArgumentException.class,
                () -> LocalExecutor.parsePlan(Map.of("calls", List.of(Map.of("params", Map.of())))));
        assertTrue(noTool.getMessage().contains("call 1 names no tool"), noTool.getMessage());
        var elsewhere = assertThrows(IllegalArgumentException.class, () -> LocalExecutor.parsePlan(Map.of("calls",
                List.of(Map.of("tool", "a"), Map.of("tool", "b", "arguments", Map.of("x", 1))))));
        assertTrue(elsewhere.getMessage().contains("call 2's arguments go under \"params\""), elsewhere.getMessage());
        var notObject = assertThrows(IllegalArgumentException.class, () -> LocalExecutor.parsePlan(Map.of("calls",
                List.of(Map.of("tool", "a", "params", List.of("x"))))));
        assertTrue(notObject.getMessage().contains("\"params\" must be an object"), notObject.getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> LocalExecutor.parsePlan(Map.of("calls", List.of("daily_news_digest"))), "a bare name");
    }

    @Test
    @DisplayName("delegate's schema offers calls as a list of tool and params, and goal is no longer required")
    @SuppressWarnings("unchecked")
    void theSchemaSaysSo() {
        var delegate = SpecialActionSchemas.ALL.stream().filter(s -> AgentAction.DELEGATE.equals(s.name()))
                .findFirst().orElseThrow();
        var properties = (Map<String, Map<String, Object>>) delegate.inputSchema().get("properties");
        assertEquals("array", properties.get("calls").get("type"));
        var items = (Map<String, Object>) properties.get("calls").get("items");
        assertEquals("object", items.get("type"));
        assertEquals(List.of("tool"), items.get("required"));
        Object required = delegate.inputSchema().get("required");
        assertTrue(required == null || !((List<String>) required).contains("goal"), String.valueOf(required));
        assertTrue(delegate.description().contains("put them in 'calls'"), delegate.description());
    }

    // ── running ──

    @Test
    @DisplayName("the news run: both calls run by code, the email gets the digest's field, the local model is never taken")
    void callsAloneNeedNoModel() {
        var digest = new FakeTool("daily_news_digest", false, List.of(), p -> ToolResult.success(MENU));
        var smtp = new FakeTool("smtp_send_email", true, List.of(), p -> ToolResult.success("Sent"));
        var llm = new Scripted();
        var lane = new Lane();

        var outcome = executor(llm, new Usage(), digest, smtp).execute(calls("",
                c("daily_news_digest", Map.of()),
                c("smtp_send_email", Map.of("to", "petr@example.com", "body", "{{call1.body_text}}"))),
                task(), UNCOUNTED, lane);

        assertTrue(outcome.ok(), outcome.text());
        assertTrue(outcome.byCode(), "ended by the calls");
        assertEquals("Polévka: česneková. Hlavní: guláš.", smtp.calls.get(0).get("body"),
                "the field of call 1's result, filled in by code");
        assertTrue(llm.calls.isEmpty(), "no model call");
        assertEquals(0, lane.taken, "and the local model was not taken: nothing here needs it");
        assertEquals(List.of("daily_news_digest", "smtp_send_email"), outcome.toolsRun());
        assertTrue(outcome.text().contains("Delegation completed for: the 2 calls you gave"), outcome.text());
        assertTrue(outcome.text().contains("Polévka"), "the results come back to the cloud: " + outcome.text());
    }

    @Test
    @DisplayName("the calls stop at the first that fails, and the cloud is told what did not run")
    void callsStopAtAFailure() {
        var fetch = new FakeTool("daily_menu_fetcher", false, List.of(), p -> ToolResult.failure(TRACEBACK));
        var smtp = new FakeTool("smtp_send_email", true, List.of(), p -> ToolResult.success("Sent"));
        var lane = new Lane();

        var outcome = executor(new Scripted(), new Usage(), fetch, smtp).execute(calls("Tell Petr what is for lunch.",
                c("daily_menu_fetcher", Map.of()),
                c("smtp_send_email", Map.of("to", "petr@example.com", "body", "{{call1}}"))), task(), UNCOUNTED, lane);

        assertFalse(outcome.ok());
        assertTrue(outcome.byCode(), "a stop of the calls is the cloud's to fix, not the local tier failing");
        assertTrue(smtp.calls.isEmpty(), "nothing after the failure runs");
        assertEquals(0, lane.taken, "and the local model is not given the goal");
        String text = outcome.text();
        assertTrue(text.contains("Delegation stopped at call 1 of 2"), text);
        assertTrue(text.contains("The call after it did not run."), text);
        assertTrue(text.contains("The local model was not given the goal."), text);
        assertTrue(text.contains(TRACEBACK), "the error, verbatim: " + text);
        assertFalse(text.contains("ALREADY DONE"), "nothing succeeded");
    }

    @Test
    @DisplayName("a refused call stops them too, and what already succeeded comes first")
    void aRefusedCallStopsThem() {
        var smtp = new FakeTool("smtp_send_email", true, List.of(), p -> ToolResult.success("Sent"));
        var fetch = new FakeTool("daily_menu_fetcher", false, List.of(), p -> ToolResult.success(MENU));

        var outcome = executor(new Scripted(), new Usage(), smtp, fetch).execute(calls("",
                c("smtp_send_email", Map.of("to", "petr@example.com", "body", "Menu follows")),
                c("menu_fetcher", Map.of()),
                c("daily_menu_fetcher", Map.of())), task(), UNCOUNTED);

        assertFalse(outcome.ok());
        assertTrue(fetch.calls.isEmpty());
        String text = outcome.text();
        assertTrue(text.startsWith("ALREADY DONE — these succeeded and must NOT be repeated: smtp_send_email"), text);
        assertTrue(text.contains("call 2 (menu_fetcher) was not run. Not run: there is no tool named 'menu_fetcher'. "
                + "Use the exact name, as find_tools lists it."), text);
        assertTrue(text.contains("The call after it did not run."), text);
    }

    @Test
    @DisplayName("a reference to a field the result does not have stops the calls at once, not after three model turns")
    void aMissingFieldStopsAtOnce() {
        // The 9 November lunch run: {{1}}.body_text of a menu that is plain text, refused three
        // times over 25 minutes of local turns before the cloud was told.
        var fetch = new FakeTool("daily_menu_fetcher", false, List.of(), p -> ToolResult.success("Polévka: česneková."));
        var smtp = new FakeTool("smtp_send_email", true, List.of(), p -> ToolResult.success("Sent"));

        var outcome = executor(new Scripted(), new Usage(), fetch, smtp).execute(calls("",
                c("daily_menu_fetcher", Map.of()),
                c("smtp_send_email", Map.of("to", "petr@example.com", "body_text", "{{call1.body_text}}"))),
                task(), UNCOUNTED);

        assertFalse(outcome.ok());
        assertTrue(smtp.calls.isEmpty());
        assertTrue(outcome.text().contains("call 2 (smtp_send_email) was not run. Not run: 'body_text' could not be "
                + "filled in. {{1.body_text}} names a field that result does not have."), outcome.text());
    }

    @Test
    @DisplayName("with a goal, the local model goes on from the calls' results as its own {{1}} on")
    void theModelGoesOnFromTheCalls() {
        var fetch = new FakeTool("daily_menu_fetcher", false, List.of(), p -> ToolResult.success(MENU));
        var smtp = new FakeTool("smtp_send_email", true, List.of(), p -> ToolResult.success("Sent"));
        // The local model's own numbering: the calls' results are its first.
        var llm = new Scripted(call("smtp_send_email", Map.of("to", "petr@example.com", "body", "{{1.body_text}}")),
                done("sent the menu"));
        var lane = new Lane();

        var outcome = executor(llm, new Usage(), fetch, smtp).execute(
                calls("If the menu has a soup, email the menu to Petr.", c("daily_menu_fetcher", Map.of())),
                task(), UNCOUNTED, lane);

        assertTrue(outcome.ok(), outcome.text());
        assertFalse(outcome.byCode(), "the local model had its part");
        assertEquals(1, lane.taken, "the local model is taken for the goal");
        assertEquals(1, fetch.calls.size(), "the call ran once, by code");
        assertEquals("Polévka: česneková. Hlavní: guláš.", smtp.calls.get(0).get("body"), "{{1}} is the call's result");
        List<LlmMessage> first = llm.calls.get(0);
        assertEquals(4, first.size(), "the system prompt, then the calls as the model's first turn and their results");
        assertTrue(first.get(0).content().contains("Already run for you"), first.get(0).content());
        assertEquals(LlmMessage.Role.ASSISTANT, first.get(2).role());
        assertTrue(first.get(2).content().contains("daily_menu_fetcher"), first.get(2).content());
        assertTrue(first.get(3).content().startsWith("Tool result {{1}} [daily_menu_fetcher] SUCCESS:\n" + MENU),
                first.get(3).content());
        assertEquals(List.of("daily_menu_fetcher", "smtp_send_email"), outcome.toolsRun());
    }

    @Test
    @DisplayName("a call the cloud wrote is not held to the rule against retyping: nobody here typed it")
    void aCallTheCloudWroteIsNoRetype() {
        String digestText = DelegationSafetyTest.digest();
        var digest = new FakeTool("daily_news_digest", false, List.of(), p -> ToolResult.success(digestText));
        var smtp = new FakeTool("smtp_send_email", true, List.of(), p -> ToolResult.success("Sent"));
        String half = digestText.substring(0, digestText.length() / 2);

        var outcome = executor(new Scripted(), new Usage(), digest, smtp).execute(calls("",
                c("daily_news_digest", Map.of()),
                c("smtp_send_email", Map.of("to", "petr@example.com", "body", half))), task(), UNCOUNTED);

        assertTrue(outcome.ok(), outcome.text());
        assertEquals(half, smtp.calls.get(0).get("body"));
    }

    @Test
    @DisplayName("a change already made in the task is not made again by a call")
    void aCallDoesNotRepeatAChange() {
        var smtp = new FakeTool("smtp_send_email", true, List.of(), p -> ToolResult.success("Sent"));
        var ctx = task();
        var args = Map.<String, Object>of("to", "petr@example.com", "body", "Dnešní menu");

        assertTrue(executor(new Scripted(), new Usage(), smtp)
                .execute(calls("", c("smtp_send_email", args)), ctx, UNCOUNTED).ok());
        var again = executor(new Scripted(), new Usage(), smtp)
                .execute(calls("", c("smtp_send_email", args)), ctx, UNCOUNTED);

        assertEquals(1, smtp.calls.size(), "one email");
        assertFalse(again.ok());
        assertTrue(again.text().contains("already succeeded earlier in this task"), again.text());
    }

    @Test
    @DisplayName("calls run while the local model is down; only a goal needs it, and then the calls' results still come back")
    void callsNeedNoLocalModel() {
        var down = new LlmProvider() {
            public LlmResponse chat(List<LlmMessage> m, LlmRequestConfig c) { throw new AssertionError("not called"); }
            public boolean isAvailable() { return false; }
            public String name() { return "down"; }
        };
        var fetch = new FakeTool("daily_menu_fetcher", false, List.of(), p -> ToolResult.success(MENU));

        assertTrue(executor(down, new Usage(), fetch)
                .execute(calls("", c("daily_menu_fetcher", Map.of())), task(), UNCOUNTED).ok());
        var withGoal = executor(down, new Usage(), fetch)
                .execute(calls("Summarise it.", c("daily_menu_fetcher", Map.of())), task(), UNCOUNTED);
        assertFalse(withGoal.ok());
        assertTrue(withGoal.text().contains("the local model is not available, so the goal was not worked on"),
                withGoal.text());
        assertTrue(withGoal.text().contains("Polévka"), "the call's result is not lost: " + withGoal.text());
    }

    // ── the numbering the cloud writes everywhere ──

    /** A task whose first result is the owner's mail, private and not indexed, as a mail reader's is. */
    static AgentContext withMail() {
        var ctx = task();
        ctx.addArtifact("imap_fetch", Map.of(), Map.of(),
                "{\"ok\":true,\"body_text\":\"Jana: the boiler is fixed.\"}", true,
                new Artifact.Decision(com.ownclaw.privacy.Label.PRIVATE, List.of("personal source"), false));
        return ctx;
    }

    @Test
    @DisplayName("{{N}} in a call is the task's result N, as everywhere the cloud writes one; {{callN}} is call N's")
    void theTasksNumbering() {
        var weather = new FakeTool("weather", false, List.of(), p -> ToolResult.success("{\"ok\":true,\"text\":\"Rain, 7 C\"}"));
        var smtp = new FakeTool("smtp_send_email", true, List.of(), p -> ToolResult.success("Sent"));

        var outcome = executor(new Scripted(), new Usage(), weather, smtp).execute(calls("",
                c("weather", Map.of()),
                c("smtp_send_email", Map.of("to", "petr@example.com",
                        "body", "{{1.body_text}}\n\nWeather: {{call1.text}}"))), withMail(), UNCOUNTED);

        assertTrue(outcome.ok(), outcome.text());
        assertEquals("Jana: the boiler is fixed.\n\nWeather: Rain, 7 C", smtp.calls.get(0).get("body"),
                "the task's mail, and the weather the first call returned -- not the weather twice");
        // Mutation: resolve calls against the delegation's results, as before -> "Rain, 7 C" is
        // emailed as the mail.
    }

    @Test
    @DisplayName("a reference that cannot be filled in stops the calls: nothing goes out as literal braces")
    void nothingGoesOutLiterally() {
        var smtp = new FakeTool("smtp_send_email", true, List.of(), p -> ToolResult.success("Sent"));

        var outcome = executor(new Scripted(), new Usage(), smtp).execute(calls("",
                c("smtp_send_email", Map.of("to", "petr@example.com", "body", "Here is what Jana wrote:\n\n{{4.body_text}}"))),
                withMail(), UNCOUNTED);

        assertTrue(smtp.calls.isEmpty(), "the literal {{4.body_text}} was emailed before");
        assertTrue(outcome.text().contains("{{4.body_text}} refers to a result the task does not have"), outcome.text());
        for (String malformed : List.of("Total: {{1.}}", "From {{1:body_text}}", "{{\uFF11}}")) {
            var again = executor(new Scripted(), new Usage(), smtp).execute(calls("",
                    c("smtp_send_email", Map.of("body", malformed))), withMail(), UNCOUNTED);
            assertFalse(again.ok(), malformed);
        }
        assertTrue(smtp.calls.isEmpty());
        var template = executor(new Scripted(), new Usage(), smtp).execute(calls("",
                c("smtp_send_email", Map.of("body", "{{ states('sensor.battery') }} and {{ name }}"))), withMail(), UNCOUNTED);
        assertTrue(template.ok(), "a template's own braces are text: " + template.text());
    }

    @Test
    @DisplayName("references are filled in at any depth of real JSON arguments, and a whole nested value too")
    void nestedReferencesAreFilledIn() {
        var level = new FakeTool("battery_level", false, List.of(), p -> ToolResult.success("{\"ok\":true,\"level\":41}"));
        var ha = new FakeTool("ha_api", true, List.of(), p -> ToolResult.success("{\"ok\":true}"));

        var outcome = executor(new Scripted(), new Usage(), level, ha).execute(calls("",
                c("battery_level", Map.of()),
                c("ha_api", Map.of("mode", "ws", "ws_commands", List.of(Map.of("type", "notify",
                        "message", "Battery at {{call1.level}}%", "level", "{{call1.level}}"))))), task(), UNCOUNTED);

        assertTrue(outcome.ok(), outcome.text());
        assertEquals(List.of(Map.of("type", "notify", "message", "Battery at 41%", "level", "41")),
                ha.calls.get(0).get("ws_commands"));
    }

    @Test
    @DisplayName("a call cannot name its own result or a later call's")
    void onlyAnEarlierCallCanBeNamed() {
        var ping = new FakeTool("ping", false, List.of(), p -> ToolResult.success("pong"));

        var outcome = executor(new Scripted(), new Usage(), ping).execute(calls("",
                c("ping", Map.of("host", "{{call1}}"))), task(), UNCOUNTED);

        assertFalse(outcome.ok());
        assertTrue(ping.calls.isEmpty());
        assertTrue(outcome.text().contains("'{{call1}}' names a call that has not run before it"), outcome.text());
    }

    @Test
    @DisplayName("a refused call tells the cloud the reason, never the fields of a private result")
    void privateFieldNamesStayOut() {
        // A contacts lookup keyed by people's names: its descriptor withholds them, and a refusal
        // listing every field of every result used to name them.
        var ctx = task();
        ctx.addArtifact("contacts_lookup", Map.of(), Map.of(),
                "{\"Jana Novakova\":\"+420 601 000 001\",\"Petr Svoboda\":\"+420 601 000 002\"}", true,
                new Artifact.Decision(com.ownclaw.privacy.Label.PRIVATE, List.of("personal source"), false));
        var smtp = new FakeTool("smtp_send_email", true, List.of(), p -> ToolResult.success("Sent"));

        var outcome = executor(new Scripted(), new Usage(), smtp).execute(calls("",
                c("smtp_send_email", Map.of("to", "petr@example.com", "body", "{{1.phone}}"))), ctx, UNCOUNTED);

        assertFalse(outcome.ok());
        assertTrue(outcome.text().contains("{{1.phone}} names a field that result does not have"), outcome.text());
        assertFalse(outcome.text().contains("Novakova"), outcome.text());
        assertFalse(outcome.text().contains("Svoboda"), outcome.text());
    }

    @Test
    @DisplayName("in the goal {{callN}} is the local model's own result N; a call's name is no text's")
    void callHandlesInTheGoal() {
        var plan = calls("Email {{call1.body_text}} to Petr, and mention {{call3}}.", c("daily_menu_fetcher", Map.of()));
        var given = LocalExecutor.given(plan, List.of());
        assertEquals("Email {{1.body_text}} to Petr, and mention (a result that does not exist).", given.plan().goal());
        assertEquals(1, given.missing());
        var e = assertThrows(IllegalArgumentException.class,
                () -> LocalExecutor.parsePlan(Map.of("goal", "g", "texts", Map.of("call1", "x"))));
        assertTrue(e.getMessage().contains("{{call1}} is how a call's result is named"), e.getMessage());
    }

    @Test
    @DisplayName("a Stop while the local model is awaited, after the calls ran, is a partial result with what ran")
    void aStopAfterTheCallsKeepsWhatRan() {
        var smtp = new FakeTool("smtp_send_email", true, List.of(), p -> ToolResult.success("Sent"));
        java.util.function.Function<java.util.function.Supplier<LocalExecutor.Outcome>, LocalExecutor.Outcome> stopped =
                work -> { throw new com.ownclaw.core.TaskCancellationService.TaskCancelledException("stopped"); };

        var outcome = executor(new Scripted(), new Usage(), smtp).execute(
                calls("Then tell Petr.", c("smtp_send_email", Map.of("to", "petr@example.com", "body", "Menu"))),
                task(), UNCOUNTED, stopped);

        assertFalse(outcome.ok());
        assertEquals(List.of("smtp_send_email"), outcome.toolsRun(), "the send is on record: " + outcome.text());
        assertTrue(outcome.text().contains("Task cancelled during delegation."), outcome.text());
        assertThrows(com.ownclaw.core.TaskCancellationService.TaskCancelledException.class,
                () -> executor(new Scripted(), new Usage(), smtp).execute(plan("tell Petr"), task(), UNCOUNTED, stopped),
                "with nothing run, the Stop goes on as before");
    }

    @Test
    @DisplayName("every spelling of a call's result is filled in or refused, never sent as braces")
    void callSpellings() {
        var level = new FakeTool("battery_level", false, List.of(), p -> ToolResult.success("{\"ok\":true,\"level\":41}"));
        var notify = new FakeTool("notify", true, List.of(), p -> ToolResult.success("ok"));
        for (String written : List.of("{{ call1 }}", "{{call1 }}", "{{Call1}}", "{{$call1.level}}", "{{ call1.level }}")) {
            var outcome = executor(new Scripted(), new Usage(), level, notify).execute(calls("",
                    c("battery_level", Map.of()), c("notify", Map.of("message", "Battery at " + written))), task(), UNCOUNTED);
            assertTrue(outcome.ok(), written + ": " + outcome.text());
            String sent = String.valueOf(notify.calls.get(notify.calls.size() - 1).get("message"));
            assertFalse(sent.contains("{{"), written + " went out as " + sent);
        }
        int before = notify.calls.size();
        for (var args : List.<Map<String, Object>>of(Map.of("message", "{{call1}}.level"),
                Map.of("data", Map.of("{{call1.level}}", "x")), Map.of("data", Map.of("{{1}}", "x")))) {
            // {{call1}}.level: the whole JSON result and a literal ".level" would go out.
            var outcome = executor(new Scripted(), new Usage(), level, notify).execute(calls("",
                    c("battery_level", Map.of()), c("notify", args)), task(), UNCOUNTED);
            assertFalse(outcome.ok(), args + " should be refused: " + outcome.text());
        }
        assertEquals(before, notify.calls.size(), "a field outside the braces, a reference in a key: refused");
        // A field's value, or a text result, followed by a file's extension is a name, not a field.
        var file = new FakeTool("find_invoice", false, List.of(),
                p -> ToolResult.success("{\"ok\":true,\"path\":\"/srv/docs/invoice_42\",\"host\":\"nas\"}"));
        var plainName = new FakeTool("latest_name", false, List.of(), p -> ToolResult.success("invoice_42"));
        var shell = new FakeTool("shell_exec", true, List.of(), p -> ToolResult.success("ok"));
        var copy = executor(new Scripted(), new Usage(), file, plainName, shell).execute(calls("",
                c("find_invoice", Map.of()), c("latest_name", Map.of()),
                c("shell_exec", Map.of("command", "cp {{call1.path}} {{call1.path}}.bak && ssh root@{{call1.host}}.lan ls "
                        + "/docs/{{call2}}.pdf"))), task(), UNCOUNTED);
        assertTrue(copy.ok(), copy.text());
        assertEquals("cp /srv/docs/invoice_42 /srv/docs/invoice_42.bak && ssh root@nas.lan ls /docs/invoice_42.pdf",
                shell.calls.get(0).get("command"));
    }

    @Test
    @DisplayName("a text cannot hold a call's result: it is put in exactly, nothing in it filled in")
    void aTextCannotHoldACallsResult() {
        var e = assertThrows(IllegalArgumentException.class, () -> LocalExecutor.parsePlan(Map.of(
                "calls", List.of(Map.of("tool", "daily_menu_fetcher")),
                "texts", Map.of("email", "Dobrý den,\n\ndnešní menu:\n{{call1.body_text}}\n\nPetr"))));
        assertTrue(e.getMessage().contains("texts.email holds a reference ({{call1.body_text}})"), e.getMessage());
        var field = assertThrows(IllegalArgumentException.class, () -> LocalExecutor.parsePlan(Map.of(
                "texts", Map.of("email", "Menu:\n{{1.body_text}}"))));
        assertTrue(field.getMessage().contains("texts.email holds a reference ({{1.body_text}})"), field.getMessage());
        assertEquals("Hi {{1}}, your code is {{2}}", LocalExecutor.parsePlan(Map.of(
                "texts", Map.of("whatsapp", "Hi {{1}}, your code is {{2}}"))).texts().get("whatsapp"),
                "a template's own placeholders, with no field, stay");
    }

    @Test
    @DisplayName("after calls and a goal the local model is shown them in its own numbering; a task result it was not given is a mark it cannot send")
    void theModelReadsTheCallsInItsOwnNumbering() {
        var weather = new FakeTool("weather", false, List.of(), p -> ToolResult.success("{\"ok\":true,\"text\":\"Rain, 7 C\"}"));
        var smtp = new FakeTool("smtp_send_email", true, List.of(), p -> ToolResult.success("Sent"));
        var ctx = task();
        ctx.addArtifact("note", Map.of(), Map.of(), "{\"ok\":true,\"text\":\"Pick up the kids at 15:30.\"}", true,
                new Artifact.Decision(com.ownclaw.privacy.Label.PUBLIC, List.of()));
        // The local model copies what it was shown for Petr's email, then sends the weather by
        // the cloud's {{call1}} spelling.
        var llm = new Scripted(
                call("smtp_send_email", Map.of("to", "jana@example.com", "body", "⟦task result 1.text⟧")),
                call("smtp_send_email", Map.of("to", "jana@example.com", "body", "{{call1.text}}")),
                done("sent"));

        var outcome = executor(llm, new Usage(), weather, smtp).execute(calls("Send Jana the weather too.",
                c("weather", Map.of()),
                c("smtp_send_email", Map.of("to", "petr@example.com", "body", "{{1.text}}"))), ctx, UNCOUNTED);

        assertTrue(outcome.ok(), outcome.text());
        assertEquals("Pick up the kids at 15:30.", smtp.calls.get(0).get("body"), "Petr got the task's note");
        assertEquals(2, smtp.calls.size(), "the copied mark was refused, not sent as words");
        assertEquals("Rain, 7 C", smtp.calls.get(1).get("body"), "{{call1}} is the model's own result 1");
        var first = llm.calls.get(0);
        assertTrue(first.get(2).content().contains("\"body\":\"⟦task result 1.text⟧\""), first.get(2).content());
        assertFalse(first.get(2).content().contains("{{1.text}}"), "the cloud's handle would be the model's own result 1");
        assertFalse(first.get(0).content().contains("Pick up the kids"),
                "a task result a call named is not handed over whole: it would not fold");
        assertTrue(llm.allSeen().contains("stands for a result of the task, and it has no reference here. You were not "
                + "given it"), llm.allSeen());
    }

    @Test
    @DisplayName("a template's expression that starts with a number is no reference: in a text and in a call's argument")
    void templatesWithNumbersStay() {
        String scaled = "state: \"{{ 3.6 * states('sensor.wind_speed')|float }}\"";
        String boost = "{{ 1.5 if is_state('input_boolean.boost', 'on') else 1.0 }}";
        assertEquals(scaled, LocalExecutor.parsePlan(Map.of("texts", Map.of("wind", scaled))).texts().get("wind"));
        assertEquals(boost, LocalExecutor.parsePlan(Map.of("texts", Map.of("boost", boost))).texts().get("boost"));
        var ha = new FakeTool("ha_config_file", true, List.of(), p -> ToolResult.success("{\"ok\":true}"));
        var outcome = executor(new Scripted(), new Usage(), ha).execute(calls("",
                c("ha_config_file", Map.of("content", scaled + "\n" + boost + "\nFirmware {{ 2.4 }} is required"))),
                task(), UNCOUNTED);
        assertTrue(outcome.ok(), outcome.text());
        assertEquals(scaled + "\n" + boost + "\nFirmware {{ 2.4 }} is required", ha.calls.get(0).get("content"));
        for (String broken : List.of("Total: {{1.}}", "From {{1:body_text}}")) {
            assertFalse(executor(new Scripted(), new Usage(), ha).execute(calls("",
                    c("ha_config_file", Map.of("content", broken))), withMail(), UNCOUNTED).ok(), broken);
        }
    }

    @Test
    @DisplayName("a field after the braces of a JSON result -- an object or a list -- is refused, with the right advice")
    void aFieldOutsideTheBracesOfJson() {
        var host = new FakeTool("find_host", false, List.of(), p -> ToolResult.success("{\"ok\":true,\"host\":\"nas\"}"));
        var list = new FakeTool("list_mail", false, List.of(), p -> ToolResult.success("[{\"body_text\":\"hi\"}]"));
        var shell = new FakeTool("shell_exec", true, List.of(), p -> ToolResult.success("ok"));
        var lan = executor(new Scripted(), new Usage(), host, shell).execute(calls("",
                c("find_host", Map.of()), c("shell_exec", Map.of("command", "ping {{call1}}.lan"))), task(), UNCOUNTED);
        assertTrue(lan.text().contains("Put the field you mean inside the braces"), lan.text());
        var ok = executor(new Scripted(), new Usage(), host, shell).execute(calls("",
                c("find_host", Map.of()), c("shell_exec", Map.of("command", "ping {{call1}}.host"))), task(), UNCOUNTED);
        assertTrue(ok.text().contains("Write {{1.host}} for its field."), ok.text());
        var arr = executor(new Scripted(), new Usage(), list, shell).execute(calls("",
                c("list_mail", Map.of()), c("shell_exec", Map.of("command", "echo {{call1}}.body_text"))), task(), UNCOUNTED);
        assertFalse(arr.ok(), arr.text());
        assertTrue(shell.calls.isEmpty());
    }

    @Test
    @DisplayName("a copy of the mark however written is refused; when the goal gives that result, the model is told to type it")
    void theMarkHoweverCopied() {
        var weather = new FakeTool("weather", false, List.of(), p -> ToolResult.success("{\"ok\":true,\"text\":\"Rain, 7 C\"}"));
        var smtp = new FakeTool("smtp_send_email", true, List.of(), p -> ToolResult.success("Sent"));
        for (String copy : List.of("[task result 1.text]", "task result 1.text", "Task Result 1",
                "{\"message\": \"\\u27E6task result 1.text\\u27E7\"}")) {
            var ctx = task();
            ctx.addArtifact("note", Map.of(), Map.of(), "{\"ok\":true,\"text\":\"Pick up the kids at 15:30.\"}", true,
                    new Artifact.Decision(com.ownclaw.privacy.Label.PUBLIC, List.of()));
            var llm = new Scripted(call("smtp_send_email", Map.of("to", "jana@example.com", "body", copy)), done("stopped"));
            executor(llm, new Usage(), weather, smtp).execute(calls("Tell Jana too.",
                    c("smtp_send_email", Map.of("to", "petr@example.com", "body", "{{1.text}}"))), ctx, UNCOUNTED);
            assertTrue(llm.allSeen().contains("stands for a result of the task"), copy + ": " + llm.allSeen());
        }
        assertTrue(smtp.calls.stream().noneMatch(c -> String.valueOf(c.get("to")).startsWith("jana")), "no copy was sent");
        var ctx = task();
        ctx.addArtifact("note", Map.of(), Map.of(), "{\"ok\":true,\"text\":\"Pick up the kids at 15:30.\"}", true,
                new Artifact.Decision(com.ownclaw.privacy.Label.PUBLIC, List.of()));
        var llm = new Scripted(call("smtp_send_email", Map.of("to", "jana@example.com", "body", "⟦task result 1.text⟧")),
                done("stopped"));
        executor(llm, new Usage(), weather, smtp).execute(calls("Send Jana the reminder {{1.text}} too.",
                c("smtp_send_email", Map.of("to", "petr@example.com", "body", "{{1.text}}"))), ctx, UNCOUNTED);
        assertTrue(llm.allSeen().contains("Result 1 is given above: type its text exactly as given."), llm.allSeen());
    }

    @Test
    @DisplayName("a field a result has is filled in whatever it is called; one it lacks is refused; a number expression is text")
    void fieldsAreFilledWhateverTheirName() {
        var headers = new FakeTool("read_headers", false, List.of(), p -> ToolResult.success(
                "{\"ok\":true,\"Content-Type\":\"text/plain\",\"2fa_code\":\"481516\",\"2026-11-11\":\"dentist 14:00\"}"));
        var notify = new FakeTool("notify", true, List.of(), p -> ToolResult.success("ok"));
        var filled = executor(new Scripted(), new Usage(), headers, notify).execute(calls("",
                c("read_headers", Map.of()),
                c("notify", Map.of("message", "{{call1.Content-Type}} {{call1.2fa_code}} {{call1.2026-11-11}}"))),
                task(), UNCOUNTED);
        assertTrue(filled.ok(), filled.text());
        assertEquals("text/plain 481516 dentist 14:00", notify.calls.get(0).get("message"));
        for (String missing : List.of("{{call1.messages.0.body}}", "{{call1.messages[0].body}}", "{{call1.body text}}",
                "Value: {{call1.body_text|upper}}")) {
            var refused = executor(new Scripted(), new Usage(), headers, notify).execute(calls("",
                    c("read_headers", Map.of()), c("notify", Map.of("message", missing))), task(), UNCOUNTED);
            assertFalse(refused.ok(), missing + " went out as " + notify.calls.get(notify.calls.size() - 1));
        }
        assertEquals(1, notify.calls.size());
        // A template written as the whole value is text, as inside a longer one.
        var ha = new FakeTool("ha_config_file", true, List.of(), p -> ToolResult.success("{\"ok\":true}"));
        for (String template : List.of("{{ 3.6 * states('sensor.wind_speed')|float }}", "{{ 2.4 }}")) {
            var outcome = executor(new Scripted(), new Usage(), ha).execute(calls("",
                    c("ha_config_file", Map.of("value_template", template))), withMail(), UNCOUNTED);
            assertTrue(outcome.ok(), template + ": " + outcome.text());
            assertEquals(template, ha.calls.get(ha.calls.size() - 1).get("value_template"));
        }
        assertFalse(ArtifactRef.numberExpression("{{1.2026-11-11}}"));
        assertFalse(ArtifactRef.numberExpression("{{1.2fa_code}}"));
        assertTrue(ArtifactRef.numberExpression("{{ 1.5 if x else 1.0 }}"));
        long start = System.nanoTime();
        assertFalse(ArtifactRef.numberExpression("{{1." + " ".repeat(40_000) + "*}}"));
        assertTrue(System.nanoTime() - start < 500_000_000L, "one look per character");
    }

    @Test
    @DisplayName("a number template is text on every path: the local model's calls, the cloud's own, and the calls it is shown")
    void numberTemplatesEverywhere() {
        String wind = "{{ 3.6 * states('sensor.wind_speed')|float }}";
        String gust = "{{ 3.6 * states('sensor.wind_gust')|float }}";
        var ctx = withMail();
        for (int i = 0; i < 3; i++) {
            ctx.addArtifact("fetch", Map.of(), Map.of(), "{\"ok\":true}", true,
                    new Artifact.Decision(com.ownclaw.privacy.Label.PUBLIC, List.of()));
        }
        // The shared resolver: the local model's calls and the cloud's own direct calls.
        var whole = References.resolveInText(Map.of("value_template", gust), ctx.artifacts());
        assertTrue(whole.ok(), whole.reason());
        assertEquals(gust, whole.params().get("value_template"));
        var inside = References.resolveInText(Map.of("content", "state: \"" + gust + "\""), ctx.artifacts());
        assertTrue(inside.ok(), inside.reason());
        // The calls the local model is shown keep the template as written, and its own copy runs.
        var ha = new FakeTool("ha_config_file", true, List.of(), p -> ToolResult.success("{\"ok\":true}"));
        var llm = new Scripted(call("ha_config_file", Map.of("value_template", gust)), done("added the gust sensor"));
        var outcome = executor(llm, new Usage(), ha).execute(calls("Add a gust sensor the same way.",
                c("ha_config_file", Map.of("value_template", wind))), ctx, UNCOUNTED);
        assertTrue(outcome.ok(), outcome.text());
        assertTrue(llm.calls.get(0).get(2).content().contains("3.6 * states('sensor.wind_speed')"),
                llm.calls.get(0).get(2).content());
        assertFalse(llm.calls.get(0).get(2).content().contains("⟦task result"), "no mark for a template");
        assertEquals(List.of(wind, gust), ha.calls.stream().map(c -> c.get("value_template")).toList());
    }

    static Artifact.Decision open() {
        return new Artifact.Decision(com.ownclaw.privacy.Label.PUBLIC, List.of());
    }

    @Test
    @DisplayName("a field named by digits that code filled is shown as the task's mark, never as the model's own result")
    void digitFieldsAreShownAsFilled() {
        // Round 7's probes: the weather is the local model's own result 1, and it has a field "5" too.
        var weather = new FakeTool("weather", false, List.of(),
                p -> ToolResult.success("{\"ok\":true,\"5\":\"rain\",\"text\":\"Rain, 7 C\"}"));
        var smtp = new FakeTool("smtp_send_email", true, List.of(), p -> ToolResult.success("Sent"));
        var ctx = task();
        ctx.addArtifact("energy", Map.of(), Map.of(), "{\"2025\":\"4,210 kWh\",\"2026\":\"3,980 kWh\",\"5\":\"five\"}",
                true, open());
        var llm = new Scripted(
                call("smtp_send_email", Map.of("to", "jana@example.com", "body", "This year: ⟦task result 1.2026⟧")),
                call("smtp_send_email", Map.of("to", "jana@example.com", "body", "This year: {{1.2026}}")),
                call("smtp_send_email", Map.of("to", "jana@example.com", "body", "{{1.5}}")),
                done("stopped"));
        executor(llm, new Usage(), weather, smtp).execute(calls("Send Jana the same figures.",
                c("weather", Map.of()),
                c("smtp_send_email", Map.of("to", "petr@example.com", "body", "This year: {{1.2026}}, five: {{1.5}}"))),
                ctx, UNCOUNTED);
        assertEquals("This year: 3,980 kWh, five: five", smtp.calls.get(0).get("body"), "Petr got the task's figures");
        String shown = llm.calls.get(0).get(2).content();
        assertTrue(shown.contains("This year: ⟦task result 1.2026⟧, five: ⟦task result 1.5⟧"), shown);
        assertFalse(shown.contains("{{1.2026}}") || shown.contains("{{1.5}}"), "its own {{1}} is the weather: " + shown);
        assertTrue(llm.allSeen().contains("stands for a result of the task"), "the copied mark is refused: " + llm.allSeen());
        // Its own {{1.2026}} names a field its result 1 lacks: refused, not sent as braces. Its own
        // {{1.5}} is the weather's field 5 -- what it writes in its own numbering is its own.
        assertTrue(llm.allSeen().contains("{{1.2026}} in it names a field that result does not have"), llm.allSeen());
        assertEquals(List.of("This year: 3,980 kWh, five: five", "rain"),
                smtp.calls.stream().map(c -> c.get("body")).toList());
        // Mutation: decide by the token's shape alone, as before -> {{1.2026}} is shown as written.
    }

    @Test
    @DisplayName("each call is shown as it was filled: against the results it saw when it ran, not the ones after it")
    void eachCallIsShownAsItRan() {
        var ha = new FakeTool("ha_config_file", true, List.of(),
                p -> ToolResult.success("{\"ok\":true,\"5\":\"later\"}"));
        var ctx = task();
        ctx.addArtifact("note", Map.of(), Map.of(), "{\"ok\":true}", true, open());
        // {{2.5}} names no result when the call runs (the task has one): a template's text, sent as
        // written. The call's own result is the task's 2 afterwards, and it has a field "5".
        var llm = new Scripted(done("checked"));
        var outcome = executor(llm, new Usage(), ha).execute(calls("Check it.",
                c("ha_config_file", Map.of("value_template", "{{2.5}}"))), ctx, UNCOUNTED);
        assertTrue(outcome.ok(), outcome.text());
        assertEquals("{{2.5}}", ha.calls.get(0).get("value_template"));
        String shown = llm.calls.get(0).get(2).content();
        assertTrue(shown.contains("\"value_template\":\"{{2.5}}\""), shown);
        assertFalse(shown.contains("⟦task result"), shown);
    }

    @Test
    @DisplayName("a number alone in braces naming a result there is, is a reference tried: refused on every path")
    void aBareNumberIsAReferenceTried() {
        var ctx = task();
        ctx.addArtifact("list_mail", Map.of(), Map.of(), "[{\"body_text\":\"hi\"},{\"body_text\":\"ho\"}]", true, open());
        ctx.addArtifact("fetch", Map.of(), Map.of(), "{\"ok\":true}", true, open());
        ctx.addArtifact("fetch", Map.of(), Map.of(), "Connection refused", false, open());
        var results = ctx.artifacts();
        // An index into a list, a field an object lacks, a field of a failed result.
        for (String bare : List.of("{{1.0}}", "{{1.1}}", "{{$1.0}}", "{{2.5}}", "{{3.0}}", "Index: {{1.0}}", "`{{3.0}}`")) {
            var mine = References.resolveInText(Map.of("message", bare), results);
            assertFalse(mine.ok(), bare + " went out as " + mine.params());
            var cloud = References.resolveExactly(Map.of("message", bare), results, Map.of());
            assertFalse(cloud.ok(), bare + " went out as " + cloud.params());
            var nestedInCloud = References.resolveExactly(Map.of("data", Map.of("message", bare)), results, Map.of());
            assertFalse(nestedInCloud.ok(), bare + " nested went out as " + nestedInCloud.params());
        }
        assertTrue(References.resolveInText(Map.of("message", "{{3.0}}"), results).reason().contains("FAILED"));
        assertFalse(References.resolveInText(Map.of("data", Map.of("message", "{{1.0}}")), results).ok(),
                "a whole nested value is refused, as {{1}} there is");
        assertFalse(References.resolveExactly(Map.of("data", Map.of("{{1.0}}", "x")), results, Map.of()).ok(),
                "a key written as one");
        var text = assertThrows(IllegalArgumentException.class,
                () -> LocalExecutor.parsePlan(Map.of("texts", Map.of("figure", "This year: {{1.0}}"))));
        assertTrue(text.getMessage().contains("holds a reference ({{1.0}})"), text.getMessage());

        // A template's text: a blank or an operator in the braces, a handle past the last result, or 0.
        for (String template : List.of("{{ 2.4 }}", "{{1.5*x}}", "{{ 3.6 * states('sensor.x')|float }}", "{{9.5}}",
                "{{0.5}}", "{{ 0.5 * states('sensor.x')|float }}", "Firmware {{ 1.0 }} or later")) {
            var mine = References.resolveInText(Map.of("message", template), results);
            assertTrue(mine.ok(), template + ": " + mine.reason());
            assertEquals(template, mine.params().get("message"));
            var nested = References.resolveInText(Map.of("data", Map.of("message", template)), results);
            assertTrue(nested.ok(), template + " nested: " + nested.reason());
            var cloud = References.resolveExactly(Map.of("data", List.of(template)), results, Map.of());
            assertTrue(cloud.ok(), template + ": " + cloud.reason());
            assertEquals(Map.of("data", List.of(template)), cloud.params());
            if (!template.equals("{{9.5}}")) {
                assertEquals(template, LocalExecutor.parsePlan(Map.of("texts", Map.of("t", template))).texts().get("t"));
            }
        }
        // A text has no results to ask, and goes in as written: a number alone in braces is taken
        // there as the reference it would be wherever a result has that handle.
        assertThrows(IllegalArgumentException.class,
                () -> LocalExecutor.parsePlan(Map.of("texts", Map.of("t", "{{9.5}}"))));
        assertEquals(2, ArtifactRef.bareNumber("{{2.0}}"));
        assertEquals(1, ArtifactRef.bareNumber("\"{{$1.25}}\""));
        for (String more : List.of("{{ 2.4 }}", "{{2.4 }}", "{{1.5*x}}", "{{2}}", "{{1234567890.5}}", "{{2.}}", "plain")) {
            assertEquals(0, ArtifactRef.bareNumber(more), more);
        }
        // Mutation: templateText true for every number expression -> {{1.0}} on the list goes out as braces.
    }

    @Test
    @DisplayName("prose that mentions a task result is not the mark: only a result the model was shown as one is refused")
    void proseIsNotTheMark() {
        var smtp = new FakeTool("smtp_send_email", true, List.of(), p -> ToolResult.success("Sent"));
        var weather = new FakeTool("weather", false, List.of(), p -> ToolResult.success("Rain"));
        var llm = new Scripted(call("smtp_send_email", Map.of("to", "petr@example.com",
                "body", "The scheduler's task result 3 days ago was fine.")), done("sent"));
        var outcome = executor(llm, new Usage(), smtp, weather).execute(calls("Mail Petr the scheduler's state.",
                c("weather", Map.of())), task(), UNCOUNTED);
        assertTrue(outcome.ok(), outcome.text());
        assertEquals(1, smtp.calls.size());
        var plain = new Scripted(call("smtp_send_email", Map.of("to", "petr@example.com",
                "body", "Task result 1 of the review is attached.")), done("sent"));
        assertTrue(executor(plain, new Usage(), smtp).execute(plan("mail Petr"), task(), UNCOUNTED).ok(),
                "no calls ran, so no mark was shown");
    }

    @Test
    @DisplayName("a delegation the calls ended is no failed delegation: the cloud keeps orchestrating")
    void theValveStaysShut() {
        var ctx = task();
        var stopped = new LinkedHashMap<String, Object>();
        stopped.put(AgentLoop.BY_CODE, true);
        ctx.trajectory().record(new AgentAction(AgentAction.DELEGATE, Map.of(), null),
                AgentObservation.failure(AgentAction.DELEGATE, "Delegation stopped at call 1 of 1", stopped, 5));
        assertFalse(ThinkingEngine.delegationFailed(ctx));
        ctx.trajectory().record(new AgentAction(AgentAction.DELEGATE, Map.of(), null),
                AgentObservation.failure(AgentAction.DELEGATE, "Delegation incomplete", Map.of(), 5));
        assertTrue(ThinkingEngine.delegationFailed(ctx), "the local model's failure still opens it");
    }
}
