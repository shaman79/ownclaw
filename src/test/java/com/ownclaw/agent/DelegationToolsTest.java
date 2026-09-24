package com.ownclaw.agent;

import com.ownclaw.agent.DelegationBehaviourTest.FakeTool;
import com.ownclaw.agent.DelegationBehaviourTest.Scripted;
import com.ownclaw.agent.DelegationBehaviourTest.Usage;
import com.ownclaw.agent.tools.Tool;
import com.ownclaw.agent.tools.ToolRegistry;
import com.ownclaw.agent.tools.ToolResult;
import com.ownclaw.observability.ChatStatusEmitter;
import com.ownclaw.llm.LlmMessage;
import com.ownclaw.llm.LlmProvider;
import com.ownclaw.llm.LlmRequestConfig;
import com.ownclaw.llm.LlmResponse;
import com.ownclaw.llm.ToolSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.ownclaw.agent.DelegationBehaviourTest.done;
import static com.ownclaw.agent.DelegationBehaviourTest.task;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A delegation is given the tools the cloud named, not the whole registry: every definition
 * sent takes context the local model needs for the work, and with all 26 it ran out.
 */
class DelegationToolsTest {

    /** A local model that takes tools as structure, and remembers which it was offered. */
    static final class Native implements LlmProvider {
        final Scripted script;
        final List<List<String>> offered = new ArrayList<>();
        Native(String... r) { script = new Scripted(r); }
        public boolean supportsTools() { return true; }
        public boolean isAvailable() { return true; }
        public String name() { return "native"; }
        public LlmResponse chat(List<LlmMessage> m, LlmRequestConfig c) {
            offered.add(c.tools() == null ? null : c.tools().stream().map(ToolSpec::name).toList());
            return script.chat(m, c);
        }
    }

    private static LocalExecutor executor(LlmProvider llm, Usage usage, Tool... tools) {
        return new LocalExecutor(new LlmRouter(llm, null, null, null),
                new ToolRegistry(List.of(tools)), new ChatStatusEmitter(), usage);
    }

    private static FakeTool tool(String name) {
        return new FakeTool(name, false, List.of(), p -> ToolResult.success("{\"ok\":true}"));
    }

    private static final FakeTool FETCH = tool("daily_menu_fetcher");
    private static final FakeTool SMTP = tool("smtp_send_email");
    private static final FakeTool SKILL_CREATE = tool("skill_create");
    private static final FakeTool NEWS = tool("news_digest");

    private static DelegationPlan plan(List<String> tools, List<DelegationPlan.Step> steps) {
        return new DelegationPlan("get the lunch menus and send them", steps, List.of(), 4, tools);
    }

    @Test
    @DisplayName("only the named tools are offered, plus done")
    void onlyTheNamedTools() {
        var llm = new Native(done("sent"));
        executor(llm, new Usage(), FETCH, SMTP, NEWS, SKILL_CREATE)
                .execute(plan(List.of("daily_menu_fetcher", "smtp_send_email"), List.of()), task());
        assertEquals(List.of("done", "daily_menu_fetcher", "smtp_send_email"), llm.offered.get(0));
    }

    @Test
    @DisplayName("naming none offers every tool but skill_create, as before")
    void noneNamedOffersAll() {
        var llm = new Native(done("sent"));
        executor(llm, new Usage(), FETCH, SMTP, NEWS, SKILL_CREATE).execute(plan(List.of(), List.of()), task());
        assertEquals(List.of("done", "daily_menu_fetcher", "news_digest", "smtp_send_email"), llm.offered.get(0));
    }

    @Test
    @DisplayName("a tool a plan step names is offered even when the list left it out")
    void stepToolsAreOffered() {
        var llm = new Native(done("sent"));
        var step = new DelegationPlan.Step("send it", "smtp_send_email", Map.of());
        executor(llm, new Usage(), FETCH, SMTP, NEWS)
                .execute(plan(List.of("daily_menu_fetcher"), List.of(step)), task());
        assertEquals(List.of("done", "daily_menu_fetcher", "smtp_send_email"), llm.offered.get(0));
    }

    @Test
    @DisplayName("skill_create stays out even when named, and the cloud is told about bad names")
    void unknownNamesAreReported() {
        var llm = new Native(done("sent"));
        var outcome = executor(llm, new Usage(), FETCH, SMTP, SKILL_CREATE)
                .execute(plan(List.of("smtp_send_email", "skill_create", "emial_sender"), List.of()), task());
        assertEquals(List.of("done", "smtp_send_email"), llm.offered.get(0));
        assertTrue(outcome.text().contains("no tool is named skill_create, emial_sender"), outcome.text());
    }

    @Test
    @DisplayName("a list of names none of which exist offers everything rather than nothing, and says so")
    void onlyWrongNamesOffersAll() {
        var llm = new Native(done("sent"));
        var outcome = executor(llm, new Usage(), FETCH, SMTP)
                .execute(plan(List.of("emial_sender"), List.of()), task());
        assertEquals(List.of("done", "daily_menu_fetcher", "smtp_send_email"), llm.offered.get(0));
        assertTrue(outcome.text().contains("so it was given every tool"), outcome.text());
    }

    @Test
    @DisplayName("a tool the goal names is offered, whether or not the list has it")
    void goalNamedToolsAreOffered() {
        String goal = "Fetch the menus using daily_menu_fetcher, then email them via smtp_send_email.";
        var listed = new Native(done("sent"));
        executor(listed, new Usage(), FETCH, SMTP, NEWS)
                .execute(new DelegationPlan(goal, List.of(), List.of(), 4, List.of("daily_menu_fetcher")), task());
        assertEquals(List.of("done", "daily_menu_fetcher", "smtp_send_email"), listed.offered.get(0));

        var unlisted = new Native(done("sent"));
        executor(unlisted, new Usage(), FETCH, SMTP, NEWS)
                .execute(new DelegationPlan(goal, List.of(), List.of(), 4), task());
        assertEquals(List.of("done", "daily_menu_fetcher", "smtp_send_email"), unlisted.offered.get(0),
                "the cloud forgot the list: the goal still narrows it");
    }

    @Test
    @DisplayName("no special action declares an array without items: OpenAI rejects the whole request")
    @SuppressWarnings("unchecked")
    void everyArrayHasItems() {
        for (ToolSpec spec : SpecialActionSchemas.ALL) {
            var props = (Map<String, Map<String, Object>>) spec.inputSchema().get("properties");
            props.forEach((name, prop) -> assertFalse(
                    "array".equals(prop.get("type")) && !prop.containsKey("items"),
                    spec.name() + "." + name + " is an array with no items"));
        }
    }

    @Test
    @DisplayName("a tool the task's own message names is offered, whatever the cloud wrote")
    void taskMessageNamesCount() {
        var ctx = new AgentContext("u1", "t1",
                "Fetch daily news digest using daily_news_digest skill, then email it. Use smtp_send_email.");
        ctx.setUnattended(true);
        var llm = new Native(done("sent"));
        executor(llm, new Usage(), FETCH, SMTP, NEWS, tool("daily_news_digest"))
                .execute(new DelegationPlan("Get the digest and email it", List.of(), List.of(), 4,
                        List.of("daily_news_digest")), ctx);
        assertEquals(List.of("done", "daily_news_digest", "smtp_send_email"), llm.offered.get(0),
                "the cloud listed only the digest and paraphrased the goal");
    }

    @Test
    @DisplayName("in chat the message's names do not narrow: it names tools in passing")
    void chatMessagesDoNotNarrow() {
        var ctx = new AgentContext("u1", "t1", "Show me today's menus here. Do NOT use smtp_send_email.");
        var llm = new Native(done("shown"));
        executor(llm, new Usage(), FETCH, SMTP, NEWS)
                .execute(new DelegationPlan("Fetch today's lunch menus and return them", List.of(), List.of(), 4), ctx);
        assertEquals(List.of("done", "daily_menu_fetcher", "news_digest", "smtp_send_email"), llm.offered.get(0));
    }

    @Test
    @DisplayName("a name counts only as a whole word")
    void wholeWordsOnly() {
        assertTrue(LocalExecutor.namedIn("run web_fetch now", "web_fetch"));
        assertTrue(LocalExecutor.namedIn("(web_fetch)", "web_fetch"));
        assertFalse(LocalExecutor.namedIn("run web_fetcher now", "web_fetch"));
        assertFalse(LocalExecutor.namedIn("my_web_fetch", "web_fetch"));
    }

    @Test
    @DisplayName("on the text protocol the manifest lists only the named tools")
    void textManifestIsNarrowed() {
        var llm = new Scripted(done("sent"));
        executor(llm, new Usage(), FETCH, SMTP, NEWS).execute(plan(List.of("daily_menu_fetcher"), List.of()), task());
        String system = llm.calls.get(0).get(0).content();
        assertTrue(system.contains("daily_menu_fetcher"), system);
        assertFalse(system.contains("news_digest"), system);
        assertFalse(system.contains("smtp_send_email"), system);
    }

    @Test
    @DisplayName("the tools argument is read as a list, or as one string of names")
    void parsePlanReadsTools() {
        assertEquals(List.of("a", "b"),
                LocalExecutor.parsePlan(Map.of("goal", "g", "tools", List.of("a", " b "))).tools());
        assertEquals(List.of("a", "b"), LocalExecutor.parsePlan(Map.of("goal", "g", "tools", "a, b")).tools());
        assertEquals(List.of("a", "b"),
                LocalExecutor.parsePlan(Map.of("goal", "g", "tools", "[\"a\", \"b\"]")).tools(),
                "a JSON array sent as a string");
        assertEquals(List.of(), LocalExecutor.parsePlan(Map.of("goal", "g")).tools());
    }
}
