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
        second.put("body", "{{1.body_text}}");
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
                c("smtp_send_email", Map.of("to", "petr@example.com", "body", "{{1.body_text}}"))),
                task(), UNCOUNTED, lane);

        assertTrue(outcome.ok(), outcome.text());
        assertEquals("Polévka: česneková. Hlavní: guláš.", smtp.calls.get(0).get("body"),
                "the field is filled in by code, as in a call the local model writes");
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
                c("smtp_send_email", Map.of("to", "petr@example.com", "body", "{{1}}"))), task(), UNCOUNTED, lane);

        assertFalse(outcome.ok());
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
                c("smtp_send_email", Map.of("to", "petr@example.com", "body_text", "{{1.body_text}}"))),
                task(), UNCOUNTED);

        assertFalse(outcome.ok());
        assertTrue(smtp.calls.isEmpty());
        assertTrue(outcome.text().contains("call 2 (smtp_send_email) was not run. Not run: the value of 'body_text'"),
                outcome.text());
    }

    @Test
    @DisplayName("with a goal, the local model goes on from the calls' results as its own {{1}} on")
    void theModelGoesOnFromTheCalls() {
        var fetch = new FakeTool("daily_menu_fetcher", false, List.of(), p -> ToolResult.success(MENU));
        var smtp = new FakeTool("smtp_send_email", true, List.of(), p -> ToolResult.success("Sent"));
        var llm = new Scripted(call("smtp_send_email", Map.of("to", "petr@example.com", "body", "{{1.body_text}}")),
                done("sent the menu"));
        var lane = new Lane();

        var outcome = executor(llm, new Usage(), fetch, smtp).execute(
                calls("If the menu has a soup, email the menu to Petr.", c("daily_menu_fetcher", Map.of())),
                task(), UNCOUNTED, lane);

        assertTrue(outcome.ok(), outcome.text());
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

    @Test
    @DisplayName("the owner's chat shows each call as a call of the machine's, not a turn of the model")
    void theChatSaysCall() {
        var digest = new FakeTool("daily_news_digest", false, List.of(), p -> ToolResult.success(MENU));
        var ctx = task();
        executor(new Scripted(), new Usage(), digest).execute(calls("", c("daily_news_digest", Map.of())), ctx, UNCOUNTED);
        // The header a call's row carries: "call", not "turn".
        var header = new TaskChat.Header(TaskChat.Actor.LOCAL, "call", 1, "daily_news_digest", null, 0, 0);
        assertTrue(header.line().startsWith("🏠 Call 1 · daily_news_digest"), header.line());
        assertEquals(1, header.data().get("call"));
    }
}
